package com.sad25kag.duniafilm21

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

object DuniaFilm21Extractor {
    private val candidatePatterns = listOf(
        Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""(?:data-src|data-embed|data-video|data-url|data-link|data-file)=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""(?:file|src|source|video|videoUrl|video_url|hls\d*|url|embed|embed_url|embed_frame_url)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""["']((?:https?:)?//[^"']+(?:\.m3u8|\.mp4|\.webm|\.mpd|\.txt)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE),
        Regex("""["']((?:/[^"']*)/(?:embed|player|stream|get|watch|video|dl)[^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""["'](https?://[^"']*(?:minochinos|pixibay|abyssplayer|dood|streamtape|filemoon|vidhide|vidguard|voe|mixdrop|streamwish|wishfast|mp4upload|uqload|krakenfiles|streamlare|filelions|gdrive|drive\.google)[^"']*)["']""", RegexOption.IGNORE_CASE)
    )

    suspend fun load(pageUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var found = false

        fun emit(link: ExtractorLink) {
            val key = link.url.substringBefore("?token=").substringBefore("&token=")
            if (emitted.add(key)) {
                found = true
                callback(link)
            }
        }

        suspend fun resolve(raw: String?, referer: String = pageUrl, depth: Int = 0) {
            val url = raw.absUrlDf21(referer) ?: return
            if (url.isNoiseUrlDf21()) return
            if (!visited.add(url)) return

            when {
                url.isSubtitleUrlDf21() -> {
                    subtitleCallback(newSubtitleFile("Indonesian", url))
                    return
                }
                url.isVideoUrlDf21() -> {
                    emitDirect(url, referer, ::emit)
                    return
                }
                url.contains("minochinos.com/embed/", ignoreCase = true) -> {
                    extractMinochinos(url, ::emit)
                    return
                }
            }

            runCatching {
                loadExtractor(url, referer, subtitleCallback) { link -> emit(link) }
            }

            if (depth >= 3 || isKnownExternal(url)) return

            val response = runCatching {
                app.get(
                    url = url,
                    headers = DuniaFilm21Network.baseHeaders + mapOf("Referer" to referer),
                    referer = referer,
                    timeout = 15000L
                )
            }.getOrNull() ?: return

            val contentType = response.headers["Content-Type"].orEmpty().lowercase()
            val contentLength = response.headers["Content-Length"]?.toLongOrNull()
            if (contentType.startsWith("video/") || contentType.contains("mpegurl") || contentType.contains("dash") || contentType.contains("octet-stream") || (contentLength != null && contentLength > 5_000_000L)) return

            val body = runCatching { response.text.cleanDf21() }.getOrNull() ?: return
            for (candidate in collectCandidates(body, url)) {
                runCatching { resolve(candidate, url, depth + 1) }
            }
        }

        val html = DuniaFilm21Network.getText(pageUrl, DuniaFilm21Provider.DEFAULT_MAIN_URL).cleanDf21()
        val document = DuniaFilm21Parser.parseDocumentFromHtml(html, pageUrl)

        document.select(".gmr-embed-responsive iframe[src], iframe[src], .muvipro-player-tabs a[href], .gmr-download-wrap a[href], a[href*='minochinos.com'], video source[src], source[src], track[src], a[href*='.srt'], a[href*='.vtt'], a[href*='.mp4'], a[href*='.m3u8'], a[href*='.webm']").forEach { element ->
            val candidate = listOf("src", "href", "data-src", "data-embed", "data-video", "data-url", "data-link", "data-file")
                .map { element.attr(it) }
                .firstOrNull { it.isNotBlank() }
            if (candidate != null) runCatching { resolve(candidate, pageUrl, 0) }
        }

        for (candidate in collectCandidates(html, pageUrl)) {
            runCatching { resolve(candidate, pageUrl, 0) }
        }

        return found
    }

    private suspend fun extractMinochinos(iframeUrl: String, emit: (ExtractorLink) -> Unit) {
        val iframeHtml = app.get(
            url = iframeUrl,
            headers = DuniaFilm21Network.baseHeaders + mapOf("Referer" to DuniaFilm21Provider.DEFAULT_MAIN_URL),
            referer = DuniaFilm21Provider.DEFAULT_MAIN_URL
        ).text.cleanDf21()

        val unpacked = runCatching { getAndUnpack(iframeHtml) }.getOrNull().orEmpty().ifBlank { iframeHtml }
        val fileCode = Regex("""file_code=([^&"']+)""", RegexOption.IGNORE_CASE).find(unpacked)?.groupValues?.getOrNull(1)
            ?: iframeUrl.substringAfterLast("/").substringBefore("?")
        val hash = Regex("""hash=([^&"']+)""", RegexOption.IGNORE_CASE).find(unpacked)?.groupValues?.getOrNull(1)

        if (!hash.isNullOrBlank() && fileCode.isNotBlank()) {
            val viewUrl = "https://minochinos.com/dl?op=view&file_code=$fileCode&hash=$hash&embed=1&referer=207.180.246.102&adb=0&hls4=1"
            runCatching { app.get(viewUrl, headers = mapOf("Referer" to iframeUrl), referer = iframeUrl).text }
        }

        val hlsCandidates = linkedSetOf<String>()
        Regex("""["']hls\d*["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(unpacked)
            .mapNotNull { it.groupValues.getOrNull(1)?.absUrlDf21(iframeUrl) }
            .forEach(hlsCandidates::add)

        Regex("""file\s*:\s*links\.(hls\d*)""", RegexOption.IGNORE_CASE)
            .findAll(unpacked)
            .map { it.groupValues.getOrNull(1).orEmpty() }
            .forEach { key ->
                Regex("""["']$key["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(unpacked)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.absUrlDf21(iframeUrl)
                    ?.let(hlsCandidates::add)
            }

        collectCandidates(unpacked, iframeUrl)
            .filter { it.isVideoUrlDf21() }
            .forEach(hlsCandidates::add)

        hlsCandidates
            .filterNot { it.isNoiseUrlDf21() }
            .sortedWith(compareByDescending<String> { it.contains("/stream/", true) }.thenBy { it.length })
            .distinct()
            .forEach { videoUrl -> emitDirect(videoUrl, iframeUrl, emit, "Minochinos") }
    }

    fun collectCandidates(html: String, baseUrl: String): Set<String> {
        val clean = html.cleanDf21()
        val out = linkedSetOf<String>()

        fun collectFrom(text: String, currentBase: String = baseUrl) {
            val normalized = text.cleanDf21()
            candidatePatterns.forEach { pattern ->
                pattern.findAll(normalized).forEach { match ->
                    match.groupValues.getOrNull(1)?.absUrlDf21(currentBase)?.let(out::add)
                }
            }
        }

        collectFrom(clean)

        Regex("""atob\(["']([^"']+)["']\)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { decodeBase64Df21(it.groupValues[1]) }
            .forEach { decoded -> collectFrom(decoded, baseUrl) }

        Regex("""Base64\.decode\(["']([^"']+)["']\)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { decodeBase64Df21(it.groupValues[1]) }
            .forEach { decoded -> collectFrom(decoded, baseUrl) }

        if (clean.contains("eval(function(p,a,c,k,e,d)", ignoreCase = true)) {
            runCatching { getAndUnpack(clean) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != clean }
                ?.let { unpacked -> collectFrom(unpacked, baseUrl) }
        }

        return out.filterNot { it.isNoiseUrlDf21() }.toCollection(linkedSetOf())
    }

    private suspend fun emitDirect(url: String, referer: String, emit: (ExtractorLink) -> Unit, sourceName: String = "DuniaFilm21") {
        val type = if (url.contains(".m3u8", true) || url.contains(".txt", true)) ExtractorLinkType.M3U8 else INFER_TYPE
        emit(
            newExtractorLink("DuniaFilm21", sourceName, url, type) {
                this.referer = referer
                this.quality = url.qualityDf21()
                this.headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
            }
        )
    }

    private fun isKnownExternal(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            "dood", "streamtape", "filemoon", "vidhide", "vidguard", "voe", "mixdrop", "streamwish", "wishfast", "mp4upload", "uqload", "krakenfiles", "streamlare", "filelions", "drive.google", "gdrive", "ok.ru", "streamsb", "sbembed", "upstream", "vidoza", "fembed", "feurl", "gofile", "pixeldrain"
        ).any { lower.contains(it) }
    }
}
