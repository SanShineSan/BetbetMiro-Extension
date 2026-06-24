package com.sad25kag.moenime

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Moenime : MainAPI() {
    override var mainUrl = "https://moenime.com"
    override var name = "Moenime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/tag/ongoing/page/{page}/" to "Ongoing",
        "$mainUrl/tag/completed/page/{page}/" to "Completed",
        "$mainUrl/tag/movie/page/{page}/" to "Movie",
        "$mainUrl/daftar-anime/page/{page}/" to "Daftar Anime",
        "$mainUrl/page/{page}/" to "Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val document = app.get(url, headers = defaultHeaders, referer = mainUrl).document
        val home = document.select(contentCardSelector)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val fallback = if (home.isEmpty()) {
            document.select("main a[href], article a[href], .site-main a[href], .content a[href]")
                .mapNotNull { it.toSearchResultFromAnchor() }
                .distinctBy { it.url }
        } else {
            emptyList()
        }

        val items = if (home.isNotEmpty()) home else fallback
        val hasNext = document.select("a.next, .next a, .nav-previous a, .pagination a[href*='/page/${page + 1}/'], a[href*='paged=${page + 1}']").isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext || (page == 1 && items.isNotEmpty())
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResponse>()
        val encoded = query.urlEncoded()
        for (page in 1..3) {
            val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
            val document = app.get(url, headers = defaultHeaders, referer = mainUrl).document
            val pageResults = document.select(contentCardSelector)
                .mapNotNull { it.toSearchResult() }
                .ifEmpty {
                    document.select("main a[href], article a[href], .site-main a[href], .content a[href]")
                        .mapNotNull { it.toSearchResultFromAnchor() }
                }
                .filterNot { result -> results.any { it.url == result.url } }

            if (pageResults.isEmpty()) break
            results.addAll(pageResults)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders, referer = mainUrl).document
        val title = document.selectFirst("h1.entry-title, h1.post-title, article h1, h1")
            ?.textClean()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Judul Moenime tidak ditemukan")

        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")
            ?.absoluteUrl(url)
            ?.takeIf { it.isImageCandidate() }
            ?: findPoster(document.body(), url)

        val description = document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")
            ?.textClean()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".entry-content p, .post-content p, article p")
                ?.textClean()
                ?.takeIf { it.isNotBlank() }

        val tags = document.select("a[rel=tag], a[href*='/tag/'], a[href*='/genre/'], .genres a, .genre a")
            .map { it.textClean() }
            .filter { it.isNotBlank() && !it.equals("more", true) }
            .distinct()

        val recommendations = document.select(contentCardSelector)
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == url }
            .distinctBy { it.url }
            .take(12)

        val episodes = collectEpisodeLinks(document, url, poster)
        val inferredType = inferTvType(title, tags, url)

        return when {
            inferredType == TvType.AnimeMovie || episodes.size <= 1 -> {
                val playableUrl = episodes.firstOrNull()?.data ?: findFirstPlayableUrl(document, url) ?: url
                newMovieLoadResponse(title, url, if (inferredType == TvType.OVA) TvType.OVA else TvType.AnimeMovie, playableUrl) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }
            else -> {
                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val emitted = linkedSetOf<String>()
        val attemptedExtractors = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, label: String = name, referer: String = data) {
            val finalUrl = rawUrl
                ?.decodePlayerText()
                ?.absoluteUrl(referer)
                ?.takeIf { it.isPlayableCandidate() }
                ?: return

            val key = finalUrl.substringBefore("#")
            if (!emitted.add(key)) return

            when {
                finalUrl.contains(".m3u8", true) -> {
                    M3u8Helper.generateM3u8(label, finalUrl, referer).forEach(callback)
                }
                else -> {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = label,
                            url = finalUrl,
                            type = inferExtractorType(finalUrl)
                        ) {
                            this.referer = referer
                            this.quality = getQualityFromName(label)
                            this.headers = mapOf(
                                "Referer" to referer,
                                "Origin" to referer.originUrl(),
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                }
            }
        }

        if (data.isPlayableCandidate()) {
            emitDirect(data, "$name - Direct", data)
            if (emitted.isNotEmpty()) return true
        }

        val response = runCatching {
            app.get(data, headers = defaultHeaders, referer = mainUrl)
        }.getOrNull() ?: return false

        val document = response.document
        val html = response.text
        val candidates = linkedSetOf<String>()

        candidates.addAll(collectPlayerCandidates(document, data))
        candidates.addAll(collectPlayerCandidatesFromText(html, data))

        val decodedPayloads = candidates.flatMap { decodePayloadVariants(it) }
        decodedPayloads.forEach { payload ->
            candidates.addAll(collectPlayerCandidatesFromText(payload, data))
            candidates.addAll(collectPlayerCandidates(Jsoup.parse(payload, data), data))
        }

        for (candidate in candidates) {
            val finalUrl = candidate.decodePlayerText().absoluteUrl(data)
            if (finalUrl.isBlank() || !finalUrl.isAllowedCandidate()) continue

            if (finalUrl.isPlayableCandidate()) {
                emitDirect(finalUrl, finalUrl.hostName(), data)
            } else if (attemptedExtractors.add(finalUrl)) {
                runCatching {
                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                }.onSuccess { success ->
                    if (success) emitted.add(finalUrl)
                }
            }
        }

        return emitted.isNotEmpty() || attemptedExtractors.isNotEmpty()
    }

    private fun collectEpisodeLinks(document: Document, baseUrl: String, poster: String?): List<Episode> {
        val seen = linkedSetOf<String>()
        val scoped = document.select("article a[href], .entry-content a[href], .post-content a[href], .episode-list a[href], .episodes a[href], .eplister a[href], .episodelist a[href]")

        return scoped.mapNotNull { anchor ->
            val href = anchor.attr("href").absoluteUrl(baseUrl)
            val text = anchor.textClean()
            if (href.isBlank() || !seen.add(href)) return@mapNotNull null
            if (!href.isMoenimeContentUrl() && !text.isEpisodeText()) return@mapNotNull null
            if (!text.isEpisodeText() && !href.contains("episode", true) && !href.contains("batch", true)) return@mapNotNull null

            val epNumber = Regex("""(?i)(?:episode|eps|ep)\s*[-:.#]?\s*(\d{1,4})""")
                .find(text.ifBlank { href })
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            newEpisode(href) {
                this.name = text.ifBlank { "Episode ${epNumber ?: ""}".trim() }
                this.episode = epNumber
                this.posterUrl = poster
            }
        }.distinctBy { it.data }
    }

    private fun findFirstPlayableUrl(document: Document, baseUrl: String): String? {
        return document.select("article a[href], .entry-content a[href], .post-content a[href], iframe[src]")
            .firstNotNullOfOrNull { element ->
                val raw = element.attr("href").ifBlank { element.attr("src") }
                raw.absoluteUrl(baseUrl).takeIf { it.isAllowedCandidate() }
            }
    }

    private fun collectPlayerCandidates(document: Document, baseUrl: String): Set<String> {
        val candidates = linkedSetOf<String>()

        document.select("iframe[src], embed[src], video[src], source[src]").forEach { element ->
            element.attr("src").takeIf { it.isNotBlank() }?.let { candidates.add(it.absoluteUrl(baseUrl)) }
        }

        document.select("option[value], button[value], li[data-video], li[data-url], div[data-video], div[data-url], div[data-src], a[data-video], a[data-url], a[data-src], a[data-href]").forEach { element ->
            listOf("value", "data-video", "data-url", "data-src", "data-href", "data-embed", "data-link", "data-file", "data-iframe", "data-player").forEach { attr ->
                val value = element.attr(attr).trim()
                if (value.isNotBlank()) {
                    candidates.add(value)
                    decodePayloadVariants(value).forEach { payload ->
                        candidates.addAll(collectPlayerCandidatesFromText(payload, baseUrl))
                    }
                }
            }
        }

        document.select("article a[href], .entry-content a[href], .post-content a[href], .download a[href], .mirror a[href], .server a[href], .player a[href]").forEach { anchor ->
            val href = anchor.attr("href").absoluteUrl(baseUrl)
            val label = anchor.textClean()
            if (href.isAllowedCandidate() && (href.isPlayableCandidate() || label.isLikelyServerLabel() || !href.isMoenimeContentUrl())) {
                candidates.add(href)
            }
        }

        return candidates
    }

    private fun collectPlayerCandidatesFromText(text: String, baseUrl: String): Set<String> {
        val candidates = linkedSetOf<String>()
        val decoded = text.decodePlayerText()

        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(decoded)
            .forEach { candidates.add(it.groupValues[1].absoluteUrl(baseUrl)) }

        Regex("""(?:file|source|src|url|embed|player)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(decoded)
            .forEach { candidates.add(it.groupValues[1].absoluteUrl(baseUrl)) }

        Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
            .findAll(decoded)
            .map { it.value.trimEnd(')', ']', ',', ';') }
            .filter { it.isAllowedCandidate() }
            .forEach { candidates.add(it.absoluteUrl(baseUrl)) }

        Regex("""atob\(["']([^"']+)["']\)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { decodeBase64OrNull(it.groupValues[1]) }
            .forEach { payload ->
                candidates.addAll(collectPlayerCandidatesFromText(payload, baseUrl))
                candidates.addAll(collectPlayerCandidates(Jsoup.parse(payload, baseUrl), baseUrl))
            }

        return candidates
    }

    private fun decodePayloadVariants(value: String): List<String> {
        val decoded = linkedSetOf<String>()
        val unescaped = value.decodePlayerText()
        decoded.add(unescaped)
        decodeUrlOrNull(unescaped)?.let { decoded.add(it.decodePlayerText()) }
        decodeBase64OrNull(unescaped)?.let { decoded.add(it.decodePlayerText()) }
        decoded.toList().forEach { item ->
            decodeBase64OrNull(item.substringAfter("base64,", item))?.let { decoded.add(it.decodePlayerText()) }
        }
        return decoded.filter { it.contains("http", true) || it.contains("iframe", true) || it.contains("source", true) }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        return anchor.toSearchResultFromAnchor(this)
    }

    private fun Element.toSearchResultFromAnchor(scope: Element = this): SearchResponse? {
        val href = attr("href").absoluteUrl(mainUrl)
        if (!href.isMoenimeContentUrl()) return null
        val title = attr("title").textClean()
            .ifBlank { scope.selectFirst("h1, h2, h3, .entry-title, .post-title, .title, .tt")?.textClean().orEmpty() }
            .ifBlank { textClean() }
            .ifBlank { scope.selectFirst("img")?.attr("alt")?.textClean().orEmpty() }
            .ifBlank { return null }

        if (title.length < 3 || title.equals("more", true)) return null

        val poster = findPoster(scope, href)
        return newAnimeSearchResponse(title, href, inferTvType(title, emptyList(), href)) {
            this.posterUrl = poster
        }
    }

    private fun inferTvType(title: String, tags: List<String>, url: String): TvType {
        val haystack = (listOf(title, url) + tags).joinToString(" ")
        return when {
            haystack.contains("movie", true) || haystack.contains("the movie", true) -> TvType.AnimeMovie
            haystack.contains("ova", true) || haystack.contains("ona", true) || haystack.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun findPoster(element: Element, pageUrl: String): String? {
        val boxes = listOfNotNull(
            element,
            element.parent(),
            element.parent()?.parent(),
            element.parent()?.parent()?.parent()
        ).distinct()

        for (box in boxes) {
            extractImage(box, pageUrl)?.let { return it }
            box.select("img, source, div, span, a").forEach { child ->
                extractImage(child, pageUrl)?.let { return it }
            }
        }
        return null
    }

    private fun extractImage(element: Element, pageUrl: String): String? {
        listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-img", "src", "poster").forEach { attr ->
            val value = element.attr(attr).trim()
            if (value.isImageCandidate()) return value.absoluteUrl(pageUrl)
        }

        listOf("srcset", "data-srcset").forEach { attr ->
            val src = element.attr(attr)
                .split(",")
                .map { it.trim().substringBefore(" ").trim() }
                .firstOrNull { it.isImageCandidate() }
            if (!src.isNullOrBlank()) return src.absoluteUrl(pageUrl)
        }

        Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(element.attr("style"))
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.isImageCandidate() }
            ?.let { return it.absoluteUrl(pageUrl) }

        return null
    }

    private fun buildPagedUrl(rawUrl: String, page: Int): String {
        if (!rawUrl.contains("{page}")) return rawUrl
        return if (page <= 1) {
            rawUrl
                .replace("/page/{page}/", "/")
                .replace("/page/{page}", "/")
                .replace("paged={page}", "paged=1")
        } else {
            rawUrl.replace("{page}", page.toString())
        }
    }

    private fun String.isMoenimeContentUrl(): Boolean {
        val clean = substringBefore("#")
        if (!clean.startsWith(mainUrl, true)) return false
        if (clean == mainUrl || clean == "$mainUrl/") return false
        val excluded = listOf(
            "/tag/", "/category/", "/genre/", "/genres/", "/daftar-anime", "/jadwal", "/faq", "/pasang-iklan",
            "/wp-content/", "/wp-json/", "/feed/", "/comments/", "?s=", "/page/"
        )
        return excluded.none { clean.contains(it, true) }
    }

    private fun String.isAllowedCandidate(): Boolean {
        if (isBlank()) return false
        val clean = trim().decodePlayerText()
        if (clean.startsWith("#") || clean.startsWith("javascript:", true) || clean.startsWith("mailto:", true)) return false
        if (clean.isImageCandidate()) return false
        val blockedHosts = listOf(
            "saweria.co", "facebook.com", "7mter.vip", "bogil.pro", "shortbioo.com", "doubleclick.net",
            "googlesyndication.com", "google-analytics.com", "google.com/recaptcha", "twitter.com", "x.com"
        )
        return blockedHosts.none { clean.contains(it, true) }
    }

    private fun String.isPlayableCandidate(): Boolean {
        val clean = substringBefore("?").substringBefore("#")
        return clean.contains(".m3u8", true) ||
            clean.contains(".mp4", true) ||
            clean.contains(".mkv", true) ||
            clean.contains(".mpd", true)
    }

    private fun String.isImageCandidate(): Boolean {
        if (isBlank() || startsWith("data:", true)) return false
        if (contains("blank", true) || contains("placeholder", true) || contains("spacer", true)) return false
        return contains(".jpg", true) || contains(".jpeg", true) || contains(".png", true) || contains(".webp", true) || contains("/wp-content/uploads/", true)
    }

    private fun String.isEpisodeText(): Boolean {
        return contains("episode", true) || contains("eps", true) || contains(" ep ", true) || contains("batch", true) || contains("ova", true) || contains("movie", true)
    }

    private fun String.isLikelyServerLabel(): Boolean {
        return contains("server", true) || contains("mirror", true) || contains("stream", true) || contains("download", true) ||
            contains("watch", true) || contains("play", true) || contains("720", true) || contains("1080", true) || contains("480", true)
    }

    private fun inferExtractorType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun String.absoluteUrl(baseUrl: String): String {
        val value = trim().decodePlayerText()
        if (value.isBlank()) return ""
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> runCatching { URI(baseUrl).resolve(value).toString() }.getOrElse {
                val base = baseUrl.substringBeforeLast("/", mainUrl)
                "$base/$value"
            }
        }
    }

    private fun String.originUrl(): String {
        return runCatching {
            val uri = URI(this)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(mainUrl)
    }

    private fun String.hostName(): String {
        return runCatching {
            URI(this).host.substringBeforeLast(".").substringAfterLast(".").ifBlank { name }
        }.getOrDefault(name)
    }

    private fun String.textClean(): String {
        return Parser.unescapeEntities(this, false)
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun Element.textClean(): String = text().textClean()

    private fun String.decodePlayerText(): String {
        return Parser.unescapeEntities(this, false)
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .trim()
    }

    private fun decodeUrlOrNull(value: String): String? {
        return runCatching { URLDecoder.decode(value, "UTF-8") }
            .getOrNull()
            ?.takeIf { it != value && it.isNotBlank() }
    }

    private fun decodeBase64OrNull(value: String): String? {
        val clean = value.trim()
            .substringAfter("base64,", value.trim())
            .replace("-", "+")
            .replace("_", "/")
            .replace(Regex("""\s+"""), "")
        if (clean.length < 12 || !clean.matches(Regex("""[A-Za-z0-9+/=]+"""))) return null
        return runCatching { base64Decode(clean) }
            .getOrNull()
            ?.takeIf { it.contains("http", true) || it.contains("iframe", true) || it.contains("source", true) }
    }

    private fun String.urlEncoded(): String = runCatching {
        URLEncoder.encode(this, "UTF-8")
    }.getOrDefault(this)

    private val contentCardSelector = listOf(
        "article",
        ".post",
        ".hentry",
        ".items .item",
        ".listupd article",
        ".bs",
        ".animepost",
        ".series",
        ".film-list .item"
    ).joinToString(", ")

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )
}
