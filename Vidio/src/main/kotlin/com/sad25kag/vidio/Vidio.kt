package com.sad25kag.vidio

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder

class Vidio : MainAPI() {
    override var mainUrl = "https://m.vidio.com"
    override var name = "Vidio"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val desktopUrl = "https://www.vidio.com"

    private val vidioHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "" to "Home",
        "categories/series" to "Series",
        "categories/movies" to "Movies",
        "categories/177-originals" to "Vidio Originals",
        "kids" to "Kids",
        "live" to "Live TV",
        "categories/sports" to "Sports"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildListingUrl(request.data, page)
        val document = getVidioDocument(url)
        val home = document.extractCards()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // Vidio exposes a Search entry in the web UI. The concrete search action can be rendered
        // dynamically, so resolve it from the live page first instead of hardcoding an unverified endpoint.
        val home = getVidioDocument(mainUrl)
        val searchUrl = home.selectFirst("form[action*=search], a[href*=search]")
            ?.let { form ->
                val action = form.attr("action").ifBlank { form.attr("href") }
                val inputName = form.selectFirst("input[name]")?.attr("name")?.takeIf { it.isNotBlank() }
                val parameter = inputName ?: "q"
                val separator = if (action.contains("?")) "&" else "?"
                fixVidioUrl(action) + separator + parameter + "=" + URLEncoder.encode(query, "UTF-8")
            }
            ?: return emptyList<SearchResponse>().toNewSearchResponseList()

        return getVidioDocument(searchUrl).extractCards().toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getVidioDocument(fixVidioUrl(url))
        val pageUrl = document.location().ifBlank { fixVidioUrl(url) }
        val title = document.titleFromPage()
        val poster = document.posterFromPage()
        val description = document.descriptionFromPage()
        val episodeLinks = document.extractEpisodeLinks(pageUrl, poster)

        if (pageUrl.contains("/premier/") || episodeLinks.size > 1) {
            val usableEpisodes = episodeLinks.ifEmpty {
                listOf(
                    newEpisode(pageUrl) {
                        this.name = "Play"
                        this.episode = null
                        this.posterUrl = poster
                    }
                )
            }

            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, usableEpisodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        if (pageUrl.contains("/sections/")) {
            val sectionItems = document.extractSectionItems(poster)
            if (sectionItems.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, sectionItems) {
                    this.posterUrl = poster
                    this.plot = description
                    }
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = fixVidioUrl(data)
        val document = getVidioDocument(pageUrl)
        val html = document.html().decodeVidioEscapes()

        document.extractSubtitleLinks(pageUrl).forEach { subtitleCallback(it) }

        val directLinks = html.extractDirectMediaLinks()
        directLinks.forEach { mediaUrl ->
            callback(
                newExtractorLink(
                    name,
                    "Vidio HLS",
                    url = mediaUrl,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = pageUrl
                    this.quality = mediaUrl.extractQuality()
                }
            )
        }

        // Some public embeds expose a regular iframe instead of an inline media URL.
        // This keeps the resolver generic without inventing Vidio-only fallback hosts.
        document.select("iframe[src]").mapNotNull { it.attr("src").takeIf(String::isNotBlank) }
            .map { fixVidioUrl(it) }
            .distinct()
            .forEach { iframe -> loadExtractor(iframe, pageUrl, subtitleCallback, callback) }

        return directLinks.isNotEmpty()
    }

    private fun buildListingUrl(path: String, page: Int): String {
        val base = if (path.isBlank()) mainUrl else "$mainUrl/${path.trimStart('/')}"
        return if (page <= 1) base else base + if (base.contains("?")) "&page=$page" else "?page=$page"
    }

    private suspend fun getVidioDocument(url: String): Document {
        val fixed = fixVidioUrl(url)
        val response = app.get(fixed, headers = vidioHeaders, referer = mainUrl)
        return response.document
    }

    private fun Document.extractCards(): List<SearchResponse> {
        val fromAnchors = select("a[href]").mapNotNull { it.toVidioSearchResult() }
        val fromJson = html().decodeVidioEscapes().extractContentUrls()
            .mapNotNull { contentUrl ->
                val title = contentUrl.titleFromUrl()
                if (title.isBlank()) return@mapNotNull null
                newMovieSearchResponse(title, contentUrl, contentUrl.tvTypeFromUrl())
            }

        return (fromAnchors + fromJson)
            .distinctBy { it.url.substringBefore('?') }
            .filter { it.name.isNotBlank() }
            .take(60)
    }

    private fun Element.toVidioSearchResult(): SearchResponse? {
        val href = attr("href").takeIf(String::isNotBlank)?.let { fixVidioUrl(it) } ?: return null
        if (!href.isVidioContentUrl()) return null

        val image = selectFirst("img")
        val title = attr("title")
            .ifBlank { attr("aria-label") }
            .ifBlank { image?.attr("alt").orEmpty() }
            .ifBlank { ownText() }
            .ifBlank { text() }
            .cleanTitle()

        if (title.isBlank() || title.isNoiseTitle()) return null

        val poster = image?.attr("src")
            ?.ifBlank { image.attr("data-src") }
            ?.ifBlank { image.attr("data-lazy-src") }
            ?.takeIf { it.isNotBlank() }
            ?.let { fixVidioUrl(it) }

        return newMovieSearchResponse(title, href, href.tvTypeFromUrl()) {
            this.posterUrl = poster
        }
    }

    private fun Document.extractEpisodeLinks(pageUrl: String, fallbackPoster: String?): List<Episode> {
        val anchorEpisodes = select("a[href*=/watch/]").mapNotNull { anchor ->
            val href = anchor.attr("href").takeIf(String::isNotBlank)?.let { fixVidioUrl(it) } ?: return@mapNotNull null
            val name = anchor.attr("title")
                .ifBlank { anchor.attr("aria-label") }
                .ifBlank { anchor.selectFirst("img[alt]")?.attr("alt").orEmpty() }
                .ifBlank { anchor.text() }
                .ifBlank { href.titleFromUrl() }
                .cleanTitle()

            newEpisode(href) {
                this.name = name.ifBlank { href.titleFromUrl() }
                this.episode = name.extractEpisodeNumber() ?: href.extractEpisodeNumber()
                this.posterUrl = anchor.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixVidioUrl(it) } ?: fallbackPoster
            }
        }

        val jsonEpisodes = html().decodeVidioEscapes().extractWatchUrls().map { href ->
            val name = href.titleFromUrl()
            newEpisode(href) {
                this.name = name.ifBlank { "Episode" }
                this.episode = name.extractEpisodeNumber() ?: href.extractEpisodeNumber()
                this.posterUrl = fallbackPoster
            }
        }

        val episodes = (anchorEpisodes + jsonEpisodes)
            .distinctBy { it.data.substringBefore('?') }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name.orEmpty() })

        val nonTrailer = episodes.filterNot { it.name.orEmpty().contains("trailer", ignoreCase = true) }
        return nonTrailer.ifEmpty { episodes.ifEmpty { pageUrl.takeIf { it.contains("/watch/") }?.let { listOf(newEpisode(it)) } ?: emptyList() } }
    }

    private fun Document.extractSectionItems(fallbackPoster: String?): List<Episode> {
        return select("a[href]").mapNotNull { anchor ->
            val href = anchor.attr("href").takeIf(String::isNotBlank)?.let { fixVidioUrl(it) } ?: return@mapNotNull null
            if (!href.isVidioContentUrl() || href.contains("/sections/")) return@mapNotNull null
            val name = anchor.attr("title")
                .ifBlank { anchor.attr("aria-label") }
                .ifBlank { anchor.selectFirst("img[alt]")?.attr("alt").orEmpty() }
                .ifBlank { anchor.text() }
                .ifBlank { href.titleFromUrl() }
                .cleanTitle()
            if (name.isBlank() || name.isNoiseTitle()) return@mapNotNull null
            newEpisode(href) {
                this.name = name
                this.episode = name.extractEpisodeNumber() ?: href.extractEpisodeNumber()
                this.posterUrl = anchor.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixVidioUrl(it) } ?: fallbackPoster
            }
        }.distinctBy { it.data.substringBefore('?') }.take(80)
    }

    private fun Document.extractSubtitleLinks(pageUrl: String): List<SubtitleFile> {
        return html().decodeVidioEscapes().extractSubtitleUrls().map { subtitleUrl ->
            SubtitleFile(
                lang = if (subtitleUrl.contains("en", true)) "English" else "Indonesia",
                url = subtitleUrl.fixProtocol(pageUrl)
            )
        }.distinctBy { it.url }
    }

    private fun Document.titleFromPage(): String {
        return selectFirst("h1")?.text()?.cleanTitle()
            ?: selectFirst("meta[property=og:title]")?.attr("content")?.cleanTitle()
            ?: title().substringBefore("|").substringBefore("-").cleanTitle().ifBlank { name }
    }

    private fun Document.posterFromPage(): String? {
        return selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { fixVidioUrl(it) }
            ?: selectFirst("img[alt]")?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixVidioUrl(it) }
    }

    private fun Document.descriptionFromPage(): String? {
        return selectFirst("meta[name=description]")?.attr("content")?.cleanDescription()
            ?: selectFirst("meta[property=og:description]")?.attr("content")?.cleanDescription()
            ?: select("p, div").map { it.text().cleanDescription() }
                .firstOrNull { it.length > 80 && !it.isNoiseDescription() }
    }

    private fun String.isVidioContentUrl(): Boolean {
        return contains("vidio.com/premier/") ||
            contains("vidio.com/watch/") ||
            contains("vidio.com/live/") ||
            contains("vidio.com/sections/")
    }

    private fun String.tvTypeFromUrl(): TvType {
        return when {
            contains("/premier/") || contains("/sections/") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun String.extractContentUrls(): List<String> {
        return Regex("""(?:https?:)?//(?:m\.|www\.)?vidio\.com/(?:premier|watch|live|sections)/[^\"'\\<>,\s]+|/(?:premier|watch|live|sections)/[^\"'\\<>,\s]+""")
            .findAll(this)
            .map { it.value.trimEnd('/', ',', '.', ')', ']') }
            .map { fixVidioUrl(it) }
            .filter { it.isVidioContentUrl() }
            .distinct()
            .toList()
    }

    private fun String.extractWatchUrls(): List<String> {
        return Regex("""(?:https?:)?//(?:m\.|www\.)?vidio\.com/watch/[^\"'\\<>,\s]+|/watch/[^\"'\\<>,\s]+""")
            .findAll(this)
            .map { fixVidioUrl(it.value.trimEnd('/', ',', '.', ')', ']')) }
            .distinct()
            .toList()
    }

    private fun String.extractDirectMediaLinks(): List<String> {
        return Regex("""https?://[^\"'\\<>,\s]+\.m3u8(?:\?[^\"'\\<>,\s]*)?""", RegexOption.IGNORE_CASE)
            .findAll(this)
            .map { Parser.unescapeEntities(it.value, true) }
            .map { it.replace("\\/", "/") }
            .map { it.trimEnd(',', '.', ')', ']') }
            .distinct()
            .toList()
    }

    private fun String.extractSubtitleUrls(): List<String> {
        return Regex("""https?://[^\"'\\<>,\s]+\.(?:vtt|srt)(?:\?[^\"'\\<>,\s]*)?""", RegexOption.IGNORE_CASE)
            .findAll(this)
            .map { Parser.unescapeEntities(it.value, true).replace("\\/", "/") }
            .distinct()
            .toList()
    }

    private fun String.decodeVidioEscapes(): String {
        return Parser.unescapeEntities(this, true)
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&quot;", "\"")
            .replace("&#x2F;", "/")
    }

    private fun String.fixProtocol(referer: String): String {
        return when {
            startsWith("//") -> "https:$this"
            startsWith("/") -> referer.substringBefore("/", missingDelimiterValue = mainUrl) + this
            else -> this
        }
    }

    private fun fixVidioUrl(url: String): String {
        val cleaned = url.trim().decodeVidioEscapes()
        return when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("http://") || cleaned.startsWith("https://") -> cleaned
            cleaned.startsWith("/") -> "$mainUrl$cleaned"
            else -> "$mainUrl/${cleaned.trimStart('/')}"
        }
    }

    private fun String.titleFromUrl(): String {
        return substringAfterLast('/')
            .substringAfter('-')
            .replace('-', ' ')
            .replace(Regex("\\?.*"), "")
            .cleanTitle()
    }

    private fun String.cleanTitle(): String {
        return Parser.unescapeEntities(this, true)
            .replace(Regex("\\s+"), " ")
            .replace("Check Now", "", ignoreCase = true)
            .replace("Watch Now", "", ignoreCase = true)
            .replace("Nonton Sekarang", "", ignoreCase = true)
            .replace("Putar", "", ignoreCase = true)
            .trim(' ', '-', '|', '•')
    }

    private fun String.cleanDescription(): String {
        return Parser.unescapeEntities(this, true)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.isNoiseTitle(): Boolean {
        val lower = lowercase()
        return lower in setOf("home", "search", "subscribe", "my list", "rate", "share", "show more", "see all", "check now", "watch now", "putar", "play", "trailer") ||
            lower.length < 2 || lower.startsWith("image:")
    }

    private fun String.isNoiseDescription(): Boolean {
        val lower = lowercase()
        return lower.contains("copyright") || lower.contains("subscriptions and purchases") || lower.contains("promo vouchers")
    }

    private fun String.extractEpisodeNumber(): Int? {
        return Regex("""(?:ep|episode|eps|e)\s*[-.]?\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
            .find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""/(?:watch/)?\d+-(?:ep|episode)-?(\d{1,4})""", RegexOption.IGNORE_CASE)
                .find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.extractQuality(): Int {
        return Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
            .find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
