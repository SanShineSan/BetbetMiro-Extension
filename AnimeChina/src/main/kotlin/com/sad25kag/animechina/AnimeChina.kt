package com.sad25kag.animechina

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class AnimeChina : MainAPI() {
    override var mainUrl = "https://animechina.my.id"
    override var name = "AnimeChina"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "" to "Latest Update",
        "ongoing/" to "Ongoing",
        "genres/adventure/" to "Adventure",
        "genres/fantasy/" to "Fantasy",
        "genres/action/" to "Action",
        "genres/action-fantasy/" to "Action Fantasy",
        "genres/cultivation/" to "Cultivation",
        "genres/comedy/" to "Comedy",
        "genres/demons/" to "Demons",
        "genres/drama/" to "Drama",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = browserHeaders).document
        val results = parseAnimeChinaCards(document).distinctBy { it.url.normalizedKey() }
        val hasNext = document.selectFirst("a.next[href], link[rel=next], .pagination a[href*='/page/${page + 1}/'], a[href*='/page/${page + 1}/']") != null
        return newHomePageResponse(request.name, results, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val routes = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/index-list/?s=$encoded",
        )
        return routes.flatMap { route ->
            runCatching {
                parseAnimeChinaCards(app.get(route, headers = browserHeaders).document)
            }.getOrDefault(emptyList())
        }.filter { it.name.contains(query, ignoreCase = true) || query.length < 3 }
            .distinctBy { it.url.normalizedKey() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders).document
        val title = cleanTitle(
            document.selectFirst("h1, h2[itemprop=name], .entry-title, .title")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ) ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl(url)
            ?: document.selectFirst(".thumb img, .poster img, .bigcover img, .mvic-desc img, article img")?.imageUrl(url)

        val plot = document.select(".entry-content p, .synopsis p, .desc p, .storyline p, article p")
            .map { it.text().cleanText() }
            .filter { paragraph -> paragraph.isNotBlank() && !paragraph.contains("Share on", true) && !paragraph.contains("Article Rating", true) }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.cleanText()

        val tags = document.select("a[href*='/genres/'], a[href*='/genre/'], a[rel=tag]")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() && !it.equals("All Genres", true) }
            .distinct()

        val episodes = parseEpisodeList(document, url).distinctBy { it.data.normalizedKey() }
        val recommendations = parseAnimeChinaCards(document)
            .filterNot { it.url.normalizedKey() == url.normalizedKey() }
            .take(16)

        return newTvSeriesLoadResponse(title, canonicalSeriesUrl(url), TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            this.showStatus = detectStatus(document)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data, headers = browserHeaders, referer = "$mainUrl/").document
        val emitted = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, label: String? = null, referer: String = data): Boolean {
            val videoUrl = rawUrl?.decodeEmbedText()?.toAbsoluteUrl(referer)?.takeIf { it.isDirectMediaLike() } ?: return false
            val key = videoUrl.substringBefore("#")
            if (!emitted.add(key)) return true
            val sourceName = listOfNotNull(name, label?.cleanText()?.takeIf { it.isNotBlank() }).joinToString(" - ")
            val headers = browserHeaders + mapOf(
                "Referer" to referer,
                "Origin" to originOf(referer),
            )
            if (videoUrl.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(sourceName, videoUrl, referer, headers = headers).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(sourceName, sourceName, videoUrl, ExtractorLinkType.VIDEO) {
                        this.referer = referer
                        this.quality = getQualityFromName(label ?: videoUrl)
                        this.headers = headers
                    },
                )
            }
            return true
        }

        val candidates = collectPlayerCandidates(document, data)
        for (candidate in candidates.take(40)) {
            val playerUrl = candidate.decodeEmbedText().toAbsoluteUrl(data) ?: continue
            if (emitDirect(playerUrl, hostLabel(playerUrl), data)) continue

            val before = emitted.size
            val countedCallback: (ExtractorLink) -> Unit = { link ->
                emitted.add(link.url.substringBefore("#"))
                callback.invoke(link)
            }
            runCatching { loadExtractor(playerUrl, data, subtitleCallback, countedCallback) }
            if (emitted.size > before) continue

            val playerText = runCatching {
                app.get(playerUrl, headers = browserHeaders + mapOf("Referer" to data), referer = data).text
            }.getOrNull() ?: continue
            val unpacked = runCatching { getAndUnpack(playerText) }.getOrNull().orEmpty()
            val nested = collectUrlsFromText(playerText + "\n" + unpacked, playerUrl)
            for (nestedUrl in nested.take(20)) {
                if (emitDirect(nestedUrl, hostLabel(playerUrl), playerUrl)) continue
                val fixedNested = nestedUrl.toAbsoluteUrl(playerUrl) ?: continue
                runCatching { loadExtractor(fixedNested, playerUrl, subtitleCallback, countedCallback) }
            }
        }
        return emitted.isNotEmpty()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim().trimStart('/')
        val base = if (cleanPath.startsWith("http", true)) cleanPath.trimEnd('/') else "$mainUrl/$cleanPath".trimEnd('/')
        if (page <= 1) return if (cleanPath.isBlank()) "$mainUrl/" else "$base/"
        return if (cleanPath.isBlank()) "$mainUrl/page/$page/" else "$base/page/$page/"
    }

    private fun parseAnimeChinaCards(document: Document): List<SearchResponse> {
        val anchors = document.select("a[href*='/watch/']")
        return anchors.mapNotNull { it.toAnimeChinaCard() }
            .filterNot { it.name.equals("Watch", true) || it.name.startsWith("Episode ", true) }
            .distinctBy { it.url.normalizedKey() }
    }

    private fun Element.toAnimeChinaCard(): SearchResponse? {
        val href = attr("href").toAbsoluteUrl() ?: return null
        if (!href.contains("/watch/", true) || !href.startsWith(mainUrl)) return null
        val rawTitle = attr("title").cleanText().takeIf { it.length > 2 }
            ?: selectFirst("img")?.attr("alt")?.cleanText()?.takeIf { it.length > 2 }
            ?: text().cleanText().takeIf { it.length > 2 }
            ?: return null
        val title = cleanCardTitle(rawTitle).takeIf { it.length > 2 } ?: return null
        val poster = selectFirst("img")?.imageUrl(href)
        val responseUrl = if (href.contains("episode=", ignoreCase = true)) href else canonicalSeriesUrl(href)
        return newMovieSearchResponse(title, responseUrl, TvType.Anime) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodeList(document: Document, baseUrl: String): List<Episode> {
        val anchors = document.select("div.gsd a[href*='episode='], .gsd a[href*='episode=']")
            .ifEmpty { document.select("a[href*='episode=']") }

        val episodes = anchors.mapNotNull { anchor ->
            val href = anchor.attr("href").toAbsoluteUrl(baseUrl) ?: return@mapNotNull null
            val episodeNumber = href.toEpisodeNumber() ?: anchor.text().toEpisodeNumber()
            newEpisode(href) {
                this.name = anchor.text().cleanText().takeIf { it.isNotBlank() } ?: episodeNumber?.let { "Episode $it" }
                this.episode = episodeNumber
                this.posterUrl = anchor.selectFirst("img")?.imageUrl(href)
            }
        }.distinctBy { it.data.normalizedKey() }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name ?: "" })

        if (episodes.isNotEmpty()) return episodes

        val currentEpisode = baseUrl.toEpisodeNumber() ?: return emptyList()
        return listOf(
            newEpisode(baseUrl) {
                this.name = "Episode $currentEpisode"
                this.episode = currentEpisode
            }
        )
    }

    private fun collectPlayerCandidates(document: Document, referer: String): LinkedHashSet<String> {
        val candidates = linkedSetOf<String>()
        document.select("iframe[src], embed[src], video[src], video source[src], source[src]").forEach { element ->
            element.attr("src").takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        }
        val dataKeys = listOf(
            "data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file", "data-stream", "value"
        )
        document.select("[data-src], [data-url], [data-link], [data-iframe], [data-embed], [data-player], [data-video], [data-file], [data-stream], option[value]")
            .forEach { element ->
                dataKeys.forEach { key ->
                    val value = element.attr(key).trim()
                    if (value.isNotBlank()) addCandidateValue(value, referer, candidates)
                }
            }
        collectUrlsFromText(document.html(), referer).forEach { candidates.add(it) }
        document.select("script").forEach { script ->
            val unpacked = runCatching { getAndUnpack(script.html()) }.getOrNull().orEmpty()
            collectUrlsFromText(script.html() + "\n" + unpacked, referer).forEach { candidates.add(it) }
        }
        return candidates
    }

    private fun addCandidateValue(value: String, referer: String, candidates: LinkedHashSet<String>) {
        candidates.add(value)
        value.decodeEmbedText().takeIf { it != value }?.let { candidates.add(it) }
        runCatching { base64Decode(value) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { decoded ->
            candidates.add(decoded)
            collectUrlsFromText(decoded, referer).forEach { candidates.add(it) }
            Jsoup.parse(decoded).select("iframe[src], video[src], source[src]").forEach { element ->
                element.attr("src").takeIf { it.isNotBlank() }?.let { candidates.add(it) }
            }
        }
    }

    private fun collectUrlsFromText(text: String, referer: String): List<String> {
        val normalized = text.decodeEmbedText()
        val urls = linkedSetOf<String>()
        Regex("""https?:\\?/\\?/[^'\"<>\\\s]+""", RegexOption.IGNORE_CASE).findAll(normalized).forEach { match ->
            urls.add(match.value.replace("\\/", "/"))
        }
        Regex("""(?:src|file|url|source|video|embed|iframe)\s*[:=]\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE).findAll(normalized).forEach { match ->
            match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
        }
        Regex("""['\"]([^'\"]+\.(?:m3u8|mp4|webm|mkv)(?:\?[^'\"]*)?)['\"]""", RegexOption.IGNORE_CASE).findAll(normalized).forEach { match ->
            match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
        }
        return urls.mapNotNull { it.toAbsoluteUrl(referer) }.filter { it.isPlayableCandidate() }.distinct()
    }

    private fun String.isPlayableCandidate(): Boolean {
        val value = decodeEmbedText().lowercase(Locale.ROOT)
        if (value.isDirectMediaLike()) return true
        if (!value.startsWith("http")) return false
        return listOf(
            "dailymotion", "filemoon", "streamtape", "streamsb", "sbembed", "vidhide", "vidguard",
            "voe", "mixdrop", "mp4upload", "dood", "sendvid", "ok.ru", "rumble", "youtube",
            "googlevideo", "blogger", "drive.google", "cdn", "player", "embed"
        ).any { value.contains(it) }
    }

    private fun String.isDirectMediaLike(): Boolean {
        val lower = lowercase(Locale.ROOT).substringBefore("#")
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") || lower.contains("videoplayback")
    }

    private fun cleanCardTitle(raw: String): String {
        return raw.cleanText()
            .replace(Regex("(?i)^HD\\s*"), "")
            .replace(Regex("(?i)^Episode\\s*\\d+\\s*"), "")
            .replace(Regex("(?i)^Eps?\\s*\\d+\\s*"), "")
            .replace(Regex("(?i)\\s*Subtitle\\s*Indonesia.*$"), "")
            .replace(Regex("(?i)\\s*Sub\\s*Indo.*$"), "")
            .trim()
    }

    private fun cleanTitle(raw: String?): String? {
        return raw?.cleanText()
            ?.replace(Regex("(?i)^Nonton\\s+Donghua\\s+"), "")
            ?.replace(Regex("(?i)\\s+Subtitle\\s+Indonesia.*$"), "")
            ?.replace(Regex("(?i)\\s+-\\s+AnimeChina.*$"), "")
            ?.replace(Regex("(?i)^HD\\s*"), "")
            ?.trim()
            ?.takeIf { it.length > 1 }
    }

    private fun canonicalSeriesUrl(url: String): String {
        return url.substringBefore("?").trimEnd('/') + "/"
    }

    private fun detectStatus(document: Document): ShowStatus? {
        val text = document.text().lowercase(Locale.ROOT)
        return when {
            "completed" in text || "complete" in text || "tamat" in text -> ShowStatus.Completed
            "ongoing" in text || "sedang tayang" in text -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun Element.imageUrl(referer: String = mainUrl): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        return listOf("data-src", "data-original", "data-lazy-src", "src")
            .firstNotNullOfOrNull { key -> image?.attr(key)?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("data:") } }
            ?.toAbsoluteUrl(referer)
    }

    private fun String.toAbsoluteUrl(base: String = mainUrl): String? {
        val value = trim().trim('\'', '"').replace("\\/", "/")
        if (value.isBlank() || value.startsWith("javascript:", true) || value == "#") return null
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return runCatching { URI(base).resolve(value).toString() }.getOrNull()
    }

    private fun String.decodeEmbedText(): String {
        var value = cleanText().replace("\\/", "/")
        repeat(2) {
            value = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }
        value = value.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        return value
    }

    private fun String.cleanText(): String {
        return replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.toEpisodeNumber(): Int? {
        return Regex("(?i)(?:episode|eps?|ep)[^0-9]{0,4}(\\d+)").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: substringAfter("episode=", "").substringBefore("&").toIntOrNull()
    }

    private fun String.normalizedKey(): String = substringBefore("#").trimEnd('/').lowercase(Locale.ROOT)

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun hostLabel(url: String): String? = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()
}
