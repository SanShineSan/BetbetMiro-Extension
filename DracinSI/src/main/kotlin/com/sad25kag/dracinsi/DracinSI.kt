package com.sad25kag.dracinsi

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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class DracinSI : MainAPI() {
    override var mainUrl = "https://dramacinasubindo.com"
    override var name = "DracinSI"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Episode Terbaru",
        "$mainUrl/trending" to "Trending",
        "$mainUrl/ongoing" to "Sedang Berjalan",
        "$mainUrl/completed" to "Tamat",
        "$mainUrl/coming-soon" to "Segera Hadir"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = headers, referer = "$mainUrl/").document
        val items = document.toSearchResults()
        val hasNext = document.select("a[href*='/page/${page + 1}'], .pagination a[href], a[rel=next]").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun buildPageUrl(data: String, page: Int): String {
        val clean = data.trimEnd('/')
        return when {
            page <= 1 -> clean.ifBlank { mainUrl }
            clean == mainUrl -> "$mainUrl/page/$page"
            else -> "$clean/page/$page"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = listOf(
            "$mainUrl/search?q=$encoded",
            "$mainUrl/ajax-search.php?q=$encoded"
        )

        val results = linkedMapOf<String, SearchResponse>()
        urls.forEach { url ->
            val text = runCatching { app.get(url, headers = headers, referer = "$mainUrl/").text }.getOrNull().orEmpty()
            if (text.isBlank()) return@forEach
            val document = Jsoup.parse(text, mainUrl)
            document.toSearchResults().forEach { results[it.url] = it }
            if (results.isEmpty()) {
                document.select("a[href*='/drama/'], a[href*='watch.php?id=']")
                    .mapNotNull { it.toSearchResult() }
                    .forEach { results[it.url] = it }
            }
        }

        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = url.absoluteUrl(mainUrl) ?: url
        val document = app.get(fixedUrl, headers = headers, referer = "$mainUrl/").document

        if (fixedUrl.contains("/episode-", true) || fixedUrl.contains("watch.php", true)) {
            return document.toEpisodeLoadResponse(fixedUrl)
        }

        val title = document.selectFirst("h1, meta[property=og:title], title")
            ?.textOrContent()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst(".col-md-3 img, meta[property=og:image], img.img-fluid, img.drama-thumb")
            ?.imageUrl(fixedUrl)
        val plot = document.selectFirst("#shortSynopsis, .synopsis-box, meta[name=description], meta[property=og:description]")
            ?.textOrContent()
            ?.cleanText()
        val tags = document.select(".badge.bg-info, a[href*='genre'], .genre a")
            .flatMap { it.text().replace("🎭", "").split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.contains("Views", true) }
            .distinct()
        val statusText = document.selectFirst(".badge.bg-primary, .breadcrumb-item a[href*='ongoing'], .breadcrumb-item a[href*='completed']")?.text()
        val episodes = document.select("a.episode-card[href]")
            .mapNotNull { it.toEpisode(fixedUrl) }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
        val recommendations = document.select(".card a[href*='/drama/'], a[href*='/drama/']:has(.card)")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        return newTvSeriesLoadResponse(title, fixedUrl, TvType.AsianDrama, episodes.ifEmpty { listOf(newEpisode(fixedUrl)) }) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot?.let { this.plot = it }
            this.tags = tags
            showStatus = statusText.toShowStatus()
            this.recommendations = recommendations
        }
    }

    private fun Document.toEpisodeLoadResponse(url: String): LoadResponse {
        val title = selectFirst(".video-title, h1, meta[property=og:title], title")
            ?.textOrContent()
            ?.replace(Regex("\\s+"), " ")
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').replace('-', ' ').cleanTitle()
        val poster = selectFirst("meta[property=og:image], video[poster], img")?.imageUrl(url)
        val plot = selectFirst("meta[name=description], meta[property=og:description]")?.textOrContent()?.cleanText()
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

        suspend fun emit(raw: String?, sourceName: String = name, refererUrl: String = pageUrl) {
            val fixed = raw?.absoluteUrl(refererUrl)?.cleanMediaUrl() ?: return
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
            } else if (fixed.startsWith("http", true)) {
                if (loadExtractor(fixed, refererUrl, subtitleCallback, callback)) delivered++
            }
        }

        document.select("video[src], video source[src], source[src], iframe[src], embed[src]").forEach { element ->
            emit(element.attr("src"), element.attr("label").ifBlank { element.attr("type").ifBlank { name } })
        }

        document.select("[data-src], [data-url], [data-video], [data-file], [data-stream], a[href]").forEach { element ->
            emit(element.attr("data-src"))
            emit(element.attr("data-url"))
            emit(element.attr("data-video"))
            emit(element.attr("data-file"))
            emit(element.attr("data-stream"))
            emit(element.attr("href"))
        }

        MEDIA_URL_REGEX.findAll(text).forEach { match -> emit(match.value) }
        return delivered > 0
    }

    private fun Document.toSearchResults(): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        select(
            "a[href*='/drama/']:has(.card), .card a[href*='/drama/'], " +
                "a[href*='watch.php?id=']:has(.card), a.episode-card[href*='/drama/']"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName().equals("a", true) && hasAttr("href")) this else selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").absoluteUrl(mainUrl) ?: return null
        if (!href.startsWith(mainUrl) || (!href.contains("/drama/", true) && !href.contains("watch.php?id=", true))) return null

        val scope = if (hasClass("card") || hasClass("episode-card")) this else anchor
        val title = listOf(
            scope.selectFirst(".card-title, .episode-info h6, h1, h2, h3, h6")?.text(),
            anchor.attr("title"),
            scope.selectFirst("img[alt]")?.attr("alt"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.replace(Regex("(?i)^NEW\\s+"), "")
            ?.cleanTitle()
            ?.takeIf { it.length > 2 }
            ?: return null

        val poster = scope.selectFirst("img")?.imageUrl(href)
        val isEpisode = href.contains("/episode-", true) || href.contains("watch.php?id=", true)
        return if (isEpisode) {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
        }
    }

    private fun Element.toEpisode(baseUrl: String): Episode? {
        val href = attr("href").absoluteUrl(baseUrl) ?: return null
        val text = text().trim()
        val epNum = Regex("(?i)(?:EP|Episode|Eps?\\.?)\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("/episode-(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return newEpisode(href) {
            name = selectFirst(".episode-info h6")?.text()?.cleanTitle() ?: text.cleanTitle().ifBlank { "Episode ${epNum ?: ""}" }
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
            .ifBlank { attr("poster") }
        return raw.absoluteUrl(baseUrl)
    }

    private fun String?.toShowStatus(): ShowStatus? {
        val value = this?.lowercase().orEmpty()
        return when {
            value.contains("tamat") || value.contains("completed") || value.contains("selesai") -> ShowStatus.Completed
            value.contains("berjalan") || value.contains("ongoing") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun String.cleanTitle(): String {
        return replace("Dracin Sub Indo", "", ignoreCase = true)
            .replace("Sub Indo", "", ignoreCase = true)
            .replace(Regex("(?i)\\s*-\\s*$name.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|')
    }

    private fun String.cleanText(): String {
        return replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }.orEmpty()
    }

    private fun String?.absoluteUrl(baseUrl: String): String? {
        val raw = this?.trim()?.replace("&amp;", "&") ?: return null
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript", true)) return null
        return runCatching { URI(baseUrl).resolve(raw).toString() }.getOrNull()
    }

    private fun String.cleanMediaUrl(): String {
        return replace("\\/", "/")
            .replace("&amp;", "&")
            .trim(' ', '\'', '"')
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase()
        return lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".webm") ||
            lower.contains(".mkv") || lower.contains("videoplayback")
    }

    private fun String.qualityFromUrl(): Int {
        val lower = lowercase()
        return when {
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    companion object {
        private val MEDIA_URL_REGEX = Regex("""https?://[^'"<>()\\s]+?(?:\.mp4|\.m3u8|\.webm|\.mkv|videoplayback)[^'"<>()\\s]*""", RegexOption.IGNORE_CASE)
    }
}
