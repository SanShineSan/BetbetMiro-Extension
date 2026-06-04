package com.sad25kag.dramaindo

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class DramaIndo : MainAPI() {
    override var mainUrl = "https://dramaindo.my"
    override var name = "DramaIndo"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Dramaindo",
        "$mainUrl/movie/page/%d/" to "Movie Korea",
        "$mainUrl/genre/action/page/%d/" to "Action",
        "$mainUrl/genre/comedy/page/%d/" to "Comedy",
        "$mainUrl/genre/crime/page/%d/" to "Crime",
        "$mainUrl/genre/drama/page/%d/" to "Drama",
        "$mainUrl/genre/family/page/%d/" to "Family",
        "$mainUrl/genre/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/genre/horror/page/%d/" to "Horror",
        "$mainUrl/genre/medical/page/%d/" to "Medical",
        "$mainUrl/genre/mystery/page/%d/" to "Mystery",
        "$mainUrl/genre/romance/page/%d/" to "Romance",
        "$mainUrl/genre/school/page/%d/" to "School",
        "$mainUrl/genre/thriller/page/%d/" to "Thriller",
        "$mainUrl/genre/variety-show/page/%d/" to "Variety Show"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data.replace("/page/%d/", "/").replace("%d", "")
        } else {
            request.data.format(page)
        }
        val document = app.get(url, headers = headers, referer = "$mainUrl/").document
        val results = document.toSearchResults()
        val hasNext = document.select("a[href*='/page/${page + 1}/'], a.next, .pagination a[href], .nav-links a[href]").isNotEmpty()
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/search/$encoded/"
        )
        val results = linkedMapOf<String, SearchResponse>()
        for (url in urls) {
            val text = runCatching { app.get(url, headers = headers, referer = "$mainUrl/").text }.getOrNull().orEmpty()
            if (text.isBlank()) continue
            Jsoup.parse(text, mainUrl).toSearchResults().forEach { results[it.url] = it }
            if (results.isNotEmpty()) break
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = url.absoluteUrl(mainUrl) ?: url
        val document = app.get(pageUrl, headers = headers, referer = "$mainUrl/").document

        if (pageUrl.isEpisodeUrl()) {
            return document.toEpisodeLoadResponse(pageUrl)
        }

        val title = document.selectFirst("h1.entry-title, h1, meta[property=og:title], title")
            ?.textOrContent()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst(".poster img, .thumb img, .image img, .movie-poster img, .gmr-movie-data img, meta[property=og:image], img.wp-post-image")
            ?.imageUrl(pageUrl)
        val plot = document.selectFirst(".entry-content p, .synopsis, .desc, .summary, meta[name=description], meta[property=og:description]")
            ?.textOrContent()
            ?.cleanText()
        val tags = document.select("a[href*='/genre/'], .genres a, .gmr-movie-data a[href*='/genre/'], .entry-categories a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val status = document.text().toShowStatus()
        val year = Regex("(?:19|20)\\d{2}").find(document.text())?.value?.toIntOrNull()

        val episodes = document.select("a[href*='/episode-'], a[href*='episode'], .eplister a[href], .episodelist a[href], .gmr-listseries a[href], .episode-list a[href]")
            .mapNotNull { it.toEpisode(pageUrl) }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })

        val recommendations = document.select("article a[href], .ml-item a[href], .item a[href], .gmr-box-content a[href], .related-post a[href]")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == pageUrl }
            .distinctBy { it.url }

        val type = if (title.contains("Movie", true) || pageUrl.contains("/movie", true)) TvType.Movie else TvType.AsianDrama
        return if (episodes.isEmpty() || type == TvType.Movie) {
            newMovieLoadResponse(title, pageUrl, type, pageUrl) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot?.let { this.plot = it }
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title, pageUrl, TvType.AsianDrama, episodes) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot?.let { this.plot = it }
                this.tags = tags
                this.year = year
                showStatus = status
                this.recommendations = recommendations
            }
        }
    }

    private fun Document.toEpisodeLoadResponse(url: String): LoadResponse {
        val title = selectFirst("h1.entry-title, h1, meta[property=og:title], title")
            ?.textOrContent()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').replace('-', ' ').cleanTitle()
        val poster = selectFirst("meta[property=og:image], video[poster], .poster img, .thumb img, img.wp-post-image")?.imageUrl(url)
        val plot = selectFirst("meta[name=description], meta[property=og:description], .entry-content p")?.textOrContent()?.cleanText()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot?.let { this.plot = it }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.absoluteUrl(mainUrl) ?: data
        val text = app.get(pageUrl, headers = headers, referer = "$mainUrl/").text
        val document = Jsoup.parse(text, pageUrl)
        val emitted = linkedSetOf<String>()
        var delivered = 0

        suspend fun emit(raw: String?, sourceName: String = name, refererUrl: String = pageUrl, depth: Int = 0) {
            val fixed = raw.decodeCandidate()?.absoluteUrl(refererUrl)?.cleanMediaUrl() ?: return
            if (fixed.isBlank() || !emitted.add(fixed)) return

            if (fixed.isDirectMedia()) {
                val type = if (fixed.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(
                    newExtractorLink(sourceName, sourceName, fixed, type) {
                        quality = fixed.qualityFromUrl()
                        referer = refererUrl
                        headers = mapOf("Referer" to refererUrl, "User-Agent" to USER_AGENT)
                    }
                )
                delivered++
                return
            }

            if (fixed.startsWith("http", true)) {
                if (loadExtractor(fixed, refererUrl, subtitleCallback, callback)) {
                    delivered++
                    return
                }

                if (depth < 1 && fixed.isInspectableHost()) {
                    val hostText = runCatching { app.get(fixed, headers = headers, referer = refererUrl).text }.getOrNull().orEmpty()
                    if (hostText.isNotBlank()) {
                        val unpacked = runCatching { getAndUnpack(hostText) }.getOrNull().orEmpty()
                        val hostDocument = Jsoup.parse(hostText, fixed)
                        hostDocument.select("iframe[src], embed[src], video[src], video source[src], source[src]").forEach { element ->
                            emit(element.attr("src"), fixed.hostLabel(), fixed, depth + 1)
                        }
                        MEDIA_URL_REGEX.findAll(hostText + "\n" + unpacked).forEach { match ->
                            emit(match.value, fixed.hostLabel(), fixed, depth + 1)
                        }
                    }
                }
            }
        }

        document.select("iframe[src], embed[src], video[src], video source[src], source[src]").forEach { element ->
            emit(element.attr("src"), element.attr("label").ifBlank { element.attr("type").ifBlank { name } })
        }

        document.select("[data-src], [data-url], [data-link], [data-href], [data-iframe], [data-embed], [data-player], [data-video], [data-file], [data-stream], option[value], a[href]").forEach { element ->
            listOf(
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-link"),
                element.attr("data-href"),
                element.attr("data-iframe"),
                element.attr("data-embed"),
                element.attr("data-player"),
                element.attr("data-video"),
                element.attr("data-file"),
                element.attr("data-stream"),
                element.attr("value"),
                element.attr("href")
            ).forEach { emit(it) }
        }

        Regex("""(?:atob|Base64\.decode)\(['\"]([^'\"]+)['\"]\)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { emit(runCatching { base64Decode(it.groupValues[1]) }.getOrNull()) }

        val unpacked = runCatching { getAndUnpack(text) }.getOrNull().orEmpty()
        MEDIA_URL_REGEX.findAll(text + "\n" + unpacked).forEach { match -> emit(match.value) }
        IFRAME_REGEX.findAll(text + "\n" + unpacked).forEach { match -> emit(match.groupValues[1]) }

        return delivered > 0
    }

    private fun Document.toSearchResults(): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        select(
            "article a[href], .ml-item a[href], .item a[href], .movie-item a[href], .gmr-item a[href], " +
                ".gmr-box-content a[href], .post a[href], .grid-item a[href], .latest a[href], .archive a[href]"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName().equals("a", true) && hasAttr("href")) this else selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").absoluteUrl(mainUrl) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (href == mainUrl || href.contains("/genre/", true) || href.contains("/page/", true) || href.contains("/tag/", true)) return null
        if (!href.matches(Regex("""https?://[^/]+/(?:20\d{2}/|[^?#]+/)"""))) return null

        val scope = when {
            hasClass("ml-item") || hasClass("item") || hasClass("post") || tagName().equals("article", true) -> this
            else -> anchor.parent() ?: anchor
        }
        val title = listOf(
            scope.selectFirst("h1, h2, h3, h4, .entry-title, .post-title, .mli-info h2, .mli-title, .title, .name")?.text(),
            anchor.attr("title"),
            scope.selectFirst("img[alt]")?.attr("alt"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?.takeIf { it.length > 2 }
            ?: return null

        val poster = scope.selectFirst("img")?.imageUrl(href)
        val isEpisode = href.isEpisodeUrl()
        return if (isEpisode || title.contains("Episode", true)) {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
        }
    }

    private fun Element.toEpisode(baseUrl: String): Episode? {
        val href = attr("href").absoluteUrl(baseUrl) ?: return null
        val text = text().trim()
        val epNum = Regex("(?i)(?:EP|Episode|Eps?\\.?|Ep\\.?)\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)episode[-_ ]?(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return newEpisode(href) {
            name = text.cleanTitle().ifBlank { "Episode ${epNum ?: ""}" }
            episode = epNum
            posterUrl = selectFirst("img")?.imageUrl(baseUrl)
        }
    }

    private fun Element.textOrContent(): String {
        return attr("content").ifBlank { text() }.trim()
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val raw = attr("content")
            .ifBlank { attr("src") }
            .ifBlank { attr("data-src") }
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("poster") }
        return raw.absoluteUrl(baseUrl)
    }

    private fun String?.absoluteUrl(baseUrl: String): String? {
        val raw = this?.trim()?.replace("&amp;", "&")?.replace("\\/", "/") ?: return null
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript", true) || raw.startsWith("mailto:", true)) return null
        return runCatching { URI(baseUrl).resolve(raw).toString() }.getOrNull()
    }

    private fun String?.decodeCandidate(): String? {
        val raw = this?.trim()?.trim(' ', '\'', '"') ?: return null
        if (raw.isBlank()) return null
        val clean = raw.replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&")
        if (clean.startsWith("http", true) || clean.startsWith("//") || clean.startsWith("/") || clean.startsWith("./")) return clean
        if (clean.length > 16 && clean.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) {
            runCatching { base64Decode(clean) }.getOrNull()?.let { decoded ->
                if (decoded.contains("http", true) || decoded.contains("iframe", true)) return decoded
            }
        }
        return clean
    }

    private fun String.cleanTitle(): String {
        return replace("Dramaindo", "", ignoreCase = true)
            .replace("Dramaindo.my", "", ignoreCase = true)
            .replace("Nonton Streaming Drama Korea Sub Indo", "", ignoreCase = true)
            .replace("Download", "", ignoreCase = true)
            .replace("Streaming", "", ignoreCase = true)
            .replace("Sub Indo", "", ignoreCase = true)
            .replace(Regex("(?i)\u22c6.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', '⋆')
    }

    private fun String.cleanText(): String {
        return Jsoup.parse(this).text().replace(Regex("\\s+"), " ").trim()
    }

    private fun String.cleanMediaUrl(): String {
        return replace("\\/", "/")
            .replace("&amp;", "&")
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .trim(' ', '\'', '"')
    }

    private fun String.isEpisodeUrl(): Boolean {
        return contains("episode", true) || matches(Regex("""https?://[^/]+/20\d{2}/[^?#]+/"""))
    }

    private fun String.toShowStatus(): ShowStatus? {
        val lower = lowercase()
        return when {
            lower.contains("completed") || lower.contains("tamat") || lower.contains("end") -> ShowStatus.Completed
            lower.contains("ongoing") || lower.contains("on-going") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase()
        return lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".webm") ||
            lower.contains(".mkv") || lower.contains("videoplayback")
    }

    private fun String.qualityFromUrl(): Int {
        val lower = lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.isInspectableHost(): Boolean {
        val lower = lowercase()
        return startsWith("http", true) && !lower.contains("dramaindo.my") && !isDirectMedia()
    }

    private fun String.hostLabel(): String {
        return runCatching { URI(this).host?.removePrefix("www.")?.substringBefore('.')?.replaceFirstChar { it.uppercase() } }
            .getOrNull()
            ?: name
    }

    companion object {
        private val MEDIA_URL_REGEX = Regex("""https?://[^'"<>()\\s]+?(?:\.mp4|\.m3u8|\.webm|\.mkv|videoplayback)[^'"<>()\\s]*""", RegexOption.IGNORE_CASE)
        private val IFRAME_REGEX = Regex("""<(?:iframe|embed)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    }
}
