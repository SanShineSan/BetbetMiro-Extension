package com.reynime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.reynime.ReynimeUtils.cleanEscaped
import com.reynime.ReynimeUtils.decodeBase64Payloads
import com.reynime.ReynimeUtils.encode
import com.reynime.ReynimeUtils.extractEpisodeNumber
import com.reynime.ReynimeUtils.isDirectVideoUrl
import com.reynime.ReynimeUtils.isSubtitleUrl
import com.reynime.ReynimeUtils.normalizeUrl
import com.reynime.ReynimeUtils.parsePlaybackData
import com.reynime.ReynimeUtils.shouldSkipUrl
import org.jsoup.nodes.Document
import java.net.URI

object ReynimeExtractor {
    suspend fun loadLinks(
        data: String,
        mainUrl: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playback = parsePlaybackData(data, mainUrl)
        val pageUrl = playback.pageUrl
        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        collectBackendEpisodeUrls(playback, mainUrl, headers, pageUrl).forEach { raw ->
            addCandidate(raw, pageUrl, mainUrl, directLinks, embedLinks)
        }

        buildPlaybackCandidates(playback, mainUrl).forEach { candidate ->
            val response = runCatching {
                app.get(candidate, headers = apiHeaders(headers), referer = pageUrl, timeout = 14L)
            }.getOrNull() ?: return@forEach

            collectCandidatesFromDocument(response.document, candidate, mainUrl, directLinks, embedLinks)
            collectCandidatesFromText(response.text, candidate, mainUrl, directLinks, embedLinks, subtitleCallback)

            decodeBase64Payloads(response.text).forEach { decoded ->
                collectCandidatesFromText(decoded, candidate, mainUrl, directLinks, embedLinks, subtitleCallback)
            }

            runCatching {
                if (!getPacked(response.text).isNullOrEmpty()) getAndUnpack(response.text) else null
            }.getOrNull()?.let { unpacked ->
                collectCandidatesFromText(unpacked, candidate, mainUrl, directLinks, embedLinks, subtitleCallback)
            }
        }

        var found = false
        prioritizeEmbeds(embedLinks, mainUrl).take(18).forEach { embed ->
            if (runCatching { loadExtractor(embed, pageUrl, subtitleCallback, callback) }.getOrDefault(false)) {
                found = true
            } else {
                resolveNestedDirectLinks(embed, pageUrl, mainUrl, headers, subtitleCallback).forEach { nested ->
                    if (emitDirectLink(nested, embed, callback)) found = true
                }
            }
        }

        directLinks.forEach { link ->
            if (emitDirectLink(link, pageUrl, callback)) found = true
        }

        if (!found) {
            resolveDailymotionFallback(playback, mainUrl, headers, pageUrl).forEach { dmUrl ->
                if (runCatching { loadExtractor(dmUrl, pageUrl, subtitleCallback, callback) }.getOrDefault(false)) found = true
            }
        }

        return found
    }

    private fun apiHeaders(headers: Map<String, String>): Map<String, String> = headers + mapOf(
        "Accept" to "application/json,text/plain,text/html,*/*",
        "X-Requested-With" to "XMLHttpRequest"
    )

    private suspend fun collectBackendEpisodeUrls(
        playback: ReynimePlaybackData,
        mainUrl: String,
        headers: Map<String, String>,
        referer: String
    ): List<String> {
        val urls = linkedSetOf<String>()
        val idsToProbe = linkedSetOf<String>()

        suspend fun probe(url: String): List<ReynimeBackendEpisode> {
            val response = runCatching { app.get(url, headers = apiHeaders(headers), referer = referer, timeout = 12L) }.getOrNull() ?: return emptyList()
            return ReynimeParser.parseBackendEpisodeRecords(response.text)
        }

        playback.episodeId?.let { id ->
            probe("$mainUrl/backend/api/episodes.php?id=$id&_t=${System.currentTimeMillis()}").forEach { record ->
                record.urls.forEach(urls::add)
            }
        }

        if (!playback.seriesId.isNullOrBlank()) {
            val records = probe("$mainUrl/backend/api/episodes.php?series_id=${playback.seriesId}&limit=1000&_t=${System.currentTimeMillis()}")
            val selected = records.filter { record ->
                val recordEpisode = record.episodeNumber?.let { extractEpisodeNumber(it) } ?: extractEpisodeNumber(record.title)
                playback.episodeNumber.isNullOrBlank() || record.episodeNumber == playback.episodeNumber || recordEpisode?.toString() == playback.episodeNumber
            }
            selected.forEach { record ->
                record.urls.forEach(urls::add)
                record.id?.let(idsToProbe::add)
            }
        }

        playback.episodeId?.let { idsToProbe.remove(it) }
        idsToProbe.take(5).forEach { id ->
            probe("$mainUrl/backend/api/episodes.php?id=$id&_t=${System.currentTimeMillis()}").forEach { record ->
                record.urls.forEach(urls::add)
            }
        }

        return urls.map { it.cleanEscaped().trim() }.distinct()
    }

    private fun buildPlaybackCandidates(playback: ReynimePlaybackData, mainUrl: String): List<String> {
        val candidates = linkedSetOf<String>()
        playback.pageUrl.takeIf { it.startsWith("http", true) }?.let(candidates::add)

        playback.episodeId?.let { id ->
            candidates.add("$mainUrl/backend/api/episodes.php?pid=$id&_t=${System.currentTimeMillis()}")
            candidates.add("$mainUrl/backend/api/episodes.php?id=$id&_t=${System.currentTimeMillis()}")
            candidates.add("$mainUrl/backend/api/episode.php?pid=$id&_t=${System.currentTimeMillis()}")
            candidates.add("$mainUrl/backend/api/player.php?pid=$id&_t=${System.currentTimeMillis()}")
            candidates.add("$mainUrl/backend/api/watch.php?pid=$id&_t=${System.currentTimeMillis()}")
            candidates.add("$mainUrl/api/episodes/$id")
            candidates.add("$mainUrl/api/watch/$id")
            candidates.add("$mainUrl/watch/$id")
        }

        if (!playback.seriesId.isNullOrBlank()) {
            candidates.add("$mainUrl/backend/api/episodes.php?series_id=${playback.seriesId}&limit=1000&_t=${System.currentTimeMillis()}")
            candidates.add("$mainUrl/series/${playback.seriesId}")
            if (!playback.seedSlug.isNullOrBlank()) candidates.add("$mainUrl/series/${playback.seriesId}/${playback.seedSlug}")
            if (!playback.episodeNumber.isNullOrBlank()) {
                candidates.add("$mainUrl/watch/${playback.seriesId}/${playback.episodeNumber}")
                candidates.add("$mainUrl/watch/${playback.seriesId}?episode=${playback.episodeNumber}")
                candidates.add("$mainUrl/watch?series=${playback.seriesId}&episode=${playback.episodeNumber}")
                candidates.add("$mainUrl/backend/api/episodes.php?series_id=${playback.seriesId}&episode=${playback.episodeNumber}&_t=${System.currentTimeMillis()}")
                candidates.add("$mainUrl/backend/api/player.php?series_id=${playback.seriesId}&episode=${playback.episodeNumber}&_t=${System.currentTimeMillis()}")
                candidates.add("$mainUrl/api/watch?series=${playback.seriesId}&episode=${playback.episodeNumber}")
                candidates.add("$mainUrl/api/stream?series=${playback.seriesId}&episode=${playback.episodeNumber}")
                candidates.add("$mainUrl/api/video?series=${playback.seriesId}&episode=${playback.episodeNumber}")
            }
        }

        return candidates.filter { it.startsWith("http", true) }.distinct()
    }

    private fun collectCandidatesFromDocument(
        document: Document,
        baseUrl: String,
        mainUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        document.select(
            "meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player], " +
                "iframe[src], iframe[data-src], video[src], video[poster], video source[src], source[src], embed[src], object[data], a[href], " +
                "[data-url], [data-src], [data-video], [data-file], [data-link], [data-embed], [data-iframe], [data-player]"
        ).forEach { element ->
            val raw = element.attr("content")
                .ifBlank { element.attr("data-player") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-link") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
            addCandidate(raw, baseUrl, mainUrl, directLinks, embedLinks)
        }
    }

    private suspend fun collectCandidatesFromText(
        text: String,
        baseUrl: String,
        mainUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val clean = text.cleanEscaped()
        extractSubtitles(clean, baseUrl, mainUrl, subtitleCallback)

        Regex("""https?:\\?/\\?/[^\"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.replace("\\/", "/") }
            .forEach { addCandidate(it, baseUrl, mainUrl, directLinks, embedLinks) }

        Regex("""//[^\"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { "https:${it.value}" }
            .forEach { addCandidate(it, baseUrl, mainUrl, directLinks, embedLinks) }

        Regex(
            """[\"'](?:file|url|src|source|video|link|embed|iframe|player|manifest|hls|mp4|m3u8)[\"']\s*:\s*[\"']([^\"']+)[\"']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            addCandidate(match.groupValues[1], baseUrl, mainUrl, directLinks, embedLinks)
        }

        ReynimeParser.parseJsonRoot(clean)?.let { root ->
            val objects = mutableListOf<org.json.JSONObject>()
            ReynimeParser.collectJsonObjects(root, objects)
            objects.forEach { obj ->
                ReynimeParser.run {
                    ReynimeParser.backendVideoKeys.mapNotNull { obj.stringValue(it) }
                        .forEach { addCandidate(it, baseUrl, mainUrl, directLinks, embedLinks) }
                    obj.stringValue("dailymotion", "dailymotion_id", "dailymotionId", "dm", "dm_id", "dmId")
                        ?.let { normalizeDailyMotion(it) }
                        ?.let(embedLinks::add)
                    obj.stringValue("rumble", "rumble_url", "rumbleUrl", "rumble_embed", "rumbleEmbed")
                        ?.let { addCandidate(it, baseUrl, mainUrl, directLinks, embedLinks) }
                }
            }
        }

        Regex("""(?:dailymotion(?:_id|Id)?|dm(?:_id|Id)?|video)\s*[\"':=]+\s*[\"']?(x[0-9a-zA-Z]+)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { embedLinks.add("https://www.dailymotion.com/embed/video/$it") }

        Regex("""(?:video=|/video/)(x[0-9a-zA-Z]+)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { embedLinks.add("https://www.dailymotion.com/embed/video/$it") }
    }

    private suspend fun extractSubtitles(
        text: String,
        baseUrl: String,
        mainUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        Regex("""https?:\\?/\\?/[^\"'\\\s<>]+\.(?:srt|vtt|ass)(?:\?[^\"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { normalizeUrl(it.value.replace("\\/", "/"), baseUrl, mainUrl) }
            .distinct()
            .forEach { url ->
                subtitleCallback.invoke(newSubtitleFile("Indonesian", url))
            }

        Regex("""[\"'](?:subtitle|sub|captions?|tracks?|file)[\"']\s*:\s*[\"']([^\"']+\.(?:srt|vtt|ass)(?:\?[^\"']*)?)[\"']""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { normalizeUrl(it.groupValues[1], baseUrl, mainUrl) }
            .distinct()
            .forEach { url ->
                subtitleCallback.invoke(newSubtitleFile("Indonesian", url))
            }
    }

    private fun addCandidate(
        raw: String?,
        baseUrl: String,
        mainUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val normalized = normalizeUrl(raw, baseUrl, mainUrl)
        if (normalized.isBlank() || shouldSkipUrl(normalized)) return
        val lower = normalized.lowercase()
        if (lower.contains("/series/") || lower.contains("/browse") || lower.contains("/login") || lower.contains("/register")) return
        if (lower.contains("/backend/api/episodes.php") || lower.contains("/api/episodes")) return
        if (normalized.isSubtitleUrl()) return

        when {
            normalized.isDirectVideoUrl() -> directLinks.add(normalized)
            isProbablyEmbed(normalized, mainUrl) -> embedLinks.add(normalized)
        }
    }

    private fun isProbablyEmbed(url: String, mainUrl: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        val mainHost = runCatching { URI(mainUrl).host.orEmpty() }.getOrDefault("")
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        if (host.isBlank()) return false
        if (host == mainHost && !lower.contains("/watch") && !lower.contains("/player") && !lower.contains("/embed")) return false
        return listOf(
            "dailymotion", "rumble", "filemoon", "streamwish", "wishfast", "dood", "streamtape", "vidhide",
            "vidguard", "voe", "mixdrop", "mp4upload", "ok.ru", "yourupload", "sendvid", "player", "embed"
        ).any { lower.contains(it) } || host != mainHost
    }

    private fun prioritizeEmbeds(links: Collection<String>, mainUrl: String): List<String> = links
        .filterNot { shouldSkipUrl(it) }
        .distinct()
        .sortedWith(compareBy<String> { hostPriority(it, mainUrl) }.thenBy { it.length })

    private fun hostPriority(url: String, mainUrl: String): Int {
        val value = url.lowercase()
        val mainHost = runCatching { URI(mainUrl).host.orEmpty() }.getOrDefault("")
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return when {
            host == mainHost -> 0
            value.contains("filemoon") -> 1
            value.contains("streamwish") || value.contains("wishfast") -> 2
            value.contains("dood") -> 3
            value.contains("streamtape") -> 4
            value.contains("vidhide") -> 5
            value.contains("vidguard") -> 6
            value.contains("voe") -> 7
            value.contains("mixdrop") -> 8
            value.contains("mp4upload") -> 9
            value.contains("ok.ru") -> 10
            value.contains("dailymotion") -> 11
            value.contains("rumble") -> 12
            else -> 50
        }
    }

    private suspend fun resolveNestedDirectLinks(
        embed: String,
        referer: String,
        mainUrl: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<String> {
        val direct = linkedSetOf<String>()
        val nested = linkedSetOf<String>()
        val response = runCatching { app.get(embed, headers = apiHeaders(headers), referer = referer, timeout = 12L) }.getOrNull() ?: return emptyList()
        collectCandidatesFromDocument(response.document, embed, mainUrl, direct, nested)
        collectCandidatesFromText(response.text, embed, mainUrl, direct, nested, subtitleCallback)
        decodeBase64Payloads(response.text).forEach { decoded ->
            collectCandidatesFromText(decoded, embed, mainUrl, direct, nested, subtitleCallback)
        }
        runCatching {
            if (!getPacked(response.text).isNullOrEmpty()) getAndUnpack(response.text) else null
        }.getOrNull()?.let { unpacked ->
            collectCandidatesFromText(unpacked, embed, mainUrl, direct, nested, subtitleCallback)
        }
        return direct.toList()
    }

    private suspend fun emitDirectLink(url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val clean = url.cleanEscaped().trim()
        if (clean.isBlank() || shouldSkipUrl(clean)) return false
        val quality = getQualityFromName(Regex("""(2160|1440|1080|720|480|360|240)p?""", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.getOrNull(1) ?: "")
            .takeIf { it > 0 } ?: Qualities.Unknown.value

        return when {
            clean.contains(".m3u8", true) || clean.contains("/hls/", true) -> {
                generateM3u8(
                    source = "Reynime",
                    streamUrl = clean,
                    referer = referer,
                    quality = quality,
                    headers = mapOf("Referer" to referer, "Origin" to originOf(referer))
                ).forEach(callback)
                true
            }
            clean.contains(Regex("""\.(?:mp4|mkv|webm)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)) -> {
                callback.invoke(
                    newExtractorLink(
                        source = "Reynime",
                        name = "Reynime ${if (quality > 0) "${quality}p" else "Direct"}",
                        url = clean,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.headers = mapOf("Referer" to referer, "Origin" to originOf(referer))
                    }
                )
                true
            }
            else -> false
        }
    }

    private fun originOf(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(url.substringBefore("/", url))
    }

    private fun normalizeDailyMotion(value: String): String? {
        val clean = value.cleanEscaped().trim()
        if (clean.isBlank()) return null
        Regex("""x[0-9a-zA-Z]+""").find(clean)?.groupValues?.getOrNull(0)?.let { id ->
            return "https://www.dailymotion.com/embed/video/$id"
        }
        return clean.takeIf { it.contains("dailymotion", true) }
    }

    private suspend fun resolveDailymotionFallback(
        playback: ReynimePlaybackData,
        mainUrl: String,
        headers: Map<String, String>,
        referer: String
    ): List<String> {
        val title = playback.title?.takeIf { it.isNotBlank() }
            ?: playback.seedSlug?.replace('-', ' ')
            ?: ReynimeSeeds.byId(playback.seriesId?.toIntOrNull())?.title
            ?: return emptyList()
        val episode = playback.episodeNumber?.toIntOrNull() ?: return emptyList()
        val queries = listOf(
            "$title Episode $episode Sub Indo",
            "$title Ep $episode Reynime"
        )

        val results = linkedSetOf<String>()
        queries.forEach { query ->
            val url = "https://www.dailymotion.com/search/${encode(query)}/videos"
            val raw = runCatching { app.get(url, headers = headers, referer = mainUrl, timeout = 10L).text }.getOrNull().orEmpty().cleanEscaped()
            Regex("""/(?:embed/)?video/(x[0-9a-zA-Z]+)""", RegexOption.IGNORE_CASE)
                .findAll(raw)
                .map { it.groupValues[1] }
                .distinct()
                .take(5)
                .forEach { id -> results.add("https://www.dailymotion.com/embed/video/$id") }
        }
        return results.toList()
    }
}
