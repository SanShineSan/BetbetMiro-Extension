package com.sad25kag.terbit21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import java.net.URLEncoder
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Terbit21Provider : MainAPI() {
    override var mainUrl = "https://162.244.95.227"
    override var name = "Terbit21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val sourceHeaders = mapOf(
        "User-Agent" to MOBILE_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "tv" to "TV Shows",
        "film-action-terbaru" to "Action",
        "adventure" to "Adventure",
        "comedy" to "Comedy",
        "crime" to "Crime",
        "drama" to "Drama",
        "dramaserial" to "Drama Serial",
        "fantasy" to "Fantasy",
        "film-horror-terbaru" to "Horror",
        "mystery" to "Mystery",
        "romance" to "Romance",
        "science-fiction" to "Science Fiction",
        "thriller" to "Thriller",
        "country/korea" to "Korea",
        "country/china" to "China",
        "country/usa" to "USA",
        "best-rating" to "Best Rating",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page), headers = sourceHeaders, timeout = 30L).document
        val home = document.parseCards()

        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = true,
                )
            ),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv",
        )

        return urls.flatMap { url ->
            runCatching {
                app.get(url, headers = sourceHeaders, timeout = 30L).document.parseCards()
            }.getOrDefault(emptyList())
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = sourceHeaders, timeout = 30L).document

        val title = document.selectFirst("h1.entry-title[itemprop=name], h1.entry-title, h1[itemprop=name], h1")
            ?.text()
            ?.cleanTitle()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.cleanTitle()
            ?: name

        val poster = document.selectFirst("figure.pull-left img, figure img[itemprop=image], img[itemprop=image], meta[property=og:image]")
            ?.let {
                if (it.tagName().equals("meta", true)) it.attr("content") else it.getImageAttr()
            }
            ?.fixUrlMaybe()
            ?.fixImageQuality()

        val description = document.selectFirst("div[itemprop=description] > p, .entry-content.entry-content-single > p, .entry-content > p")
            ?.text()
            ?.trim()

        val tags = document.parseMetaLinks("Genre")
        val actors = document.select("span[itemprop=actors] a, .gmr-moviedata:contains(Cast:) a, .gmr-moviedata:contains(Actor:) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = document.select("a[href*='/year/'], .gmr-moviedata:contains(Year:) a")
            .asSequence()
            .mapNotNull { YEAR_REGEX.find(it.text())?.value?.toIntOrNull() }
            .firstOrNull()
            ?: YEAR_REGEX.find(title)?.value?.toIntOrNull()

        val rating = document.selectFirst("div.gmr-meta-rating span[itemprop=ratingValue], .gmr-rating-item")
            ?.text()
            ?.trim()

        val duration = document.selectFirst("div.gmr-duration-item, span[property=duration], .gmr-moviedata:contains(Duration:)")
            ?.text()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()

        val trailer = document.selectFirst("a.gmr-trailer-popup[href*='youtube'], a[href*='youtube.com/watch'], iframe[src*='youtube.com/embed']")
            ?.let { el ->
                el.attr("href").ifBlank { el.attr("src") }
            }
            ?.takeIf { it.contains("youtube", ignoreCase = true) }

        val recommendations = document.select("article.item")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == url }
            .distinctBy { it.url }

        val episodes = document.parseEpisodes()
        val isSeries = url.contains("/tv/", true) || episodes.isNotEmpty()

        return if (isSeries && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addActors(actors)
                addScore(rating)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addActors(actors)
                addScore(rating)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data, headers = sourceHeaders, timeout = 30L).document
        val baseUrl = getBaseUrl(data)
        var delivered = false

        val iframeUrls = document.collectPlayerUrls(data)

        iframeUrls.forEach { playerUrl ->
            if (playerUrl.contains(SF21_HOST, ignoreCase = true)) {
                if (resolveSf21(playerUrl, data, callback)) delivered = true
            } else {
                val success = runCatching {
                    loadExtractor(playerUrl, data, subtitleCallback, callback)
                }.getOrDefault(false)
                if (success) delivered = true
            }
        }

        document.select("a[href*='safelink='], a[href*='/download/'], a[href*='/dl/'], a.button.button-shadow[href]")
            .mapNotNull { it.attr("href").fixUrlMaybe() }
            .filterNot { it.startsWith(baseUrl, ignoreCase = true) || it.contains("youtube", ignoreCase = true) }
            .distinct()
            .forEach { downloadUrl ->
                val success = runCatching {
                    loadExtractor(downloadUrl, data, subtitleCallback, callback)
                }.getOrDefault(false)
                if (success) delivered = true
            }

        return delivered
    }

    private suspend fun resolveSf21(
        playerUrl: String,
        sourceReferer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val resolver = WebViewResolver(
            SF21_M3U8_REGEX,
            userAgent = MOBILE_USER_AGENT,
            useOkhttp = true,
        )

        val captured = runCatching {
            app.get(
                playerUrl,
                headers = mapOf(
                    "User-Agent" to MOBILE_USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                    "Referer" to sourceReferer,
                ),
                interceptor = resolver,
                timeout = 45L,
            ).url
        }.getOrNull()

        val m3u8 = captured?.takeIf { it.contains(".m3u8", ignoreCase = true) } ?: return false

        val headers = mapOf(
            "Referer" to "$SF21_ORIGIN/",
            "Origin" to SF21_ORIGIN,
            "User-Agent" to MOBILE_USER_AGENT,
        )

        M3u8Helper.generateM3u8(
            name,
            m3u8,
            SF21_ORIGIN,
            headers = headers,
        ).forEach(callback)

        return true
    }

    private fun Document.parseCards(): List<SearchResponse> {
        return select("article.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleAnchor = selectFirst("h2.entry-title > a[href], h2 a[href], .entry-title a[href], a[itemprop=url][href]")
            ?: return null

        val title = titleAnchor.text().cleanTitle().ifBlank { titleAnchor.attr("title").cleanTitle() }
        if (title.isBlank()) return null

        val href = titleAnchor.attr("href").fixUrlMaybe() ?: return null
        if (!href.startsWith(mainUrl, ignoreCase = true)) return null
        if (href.contains("/tag/", true) || href.contains("/country/", true) || href.contains("/year/", true)) return null

        val poster = selectFirst("div.content-thumbnail img, img.wp-post-image, img[itemprop=image]")
            ?.getImageAttr()
            ?.fixUrlMaybe()
            ?.fixImageQuality()

        val rating = selectFirst("div.gmr-rating-item")?.ownText()?.trim()
            ?: selectFirst("div.gmr-rating-item")?.text()?.trim()

        val tvType = if (href.contains("/tv/", ignoreCase = true)) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.score = Score.from10(rating?.toDoubleOrNull())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.score = Score.from10(rating?.toDoubleOrNull())
            }
        }
    }

    private fun Document.parseEpisodes(): List<Episode> {
        val result = linkedMapOf<String, Episode>()

        select("div.gmr-listseries a.button.button-shadow[href], .gmr-listseries a[href], a[href*='/eps/'][href]")
            .forEachIndexed { index, element ->
                val href = element.attr("href").fixUrlMaybe() ?: return@forEachIndexed
                if (!href.startsWith(mainUrl, true) || !href.contains("/eps/", true)) return@forEachIndexed

                val rawName = element.text().ifBlank { element.attr("title") }.cleanTitle()
                if (rawName.isBlank()) return@forEachIndexed

                val episodeNumber = EPISODE_NUMBER_REGEX.find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: EPISODE_NUMBER_REGEX.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)

                val seasonNumber = SEASON_NUMBER_REGEX.find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: SEASON_NUMBER_REGEX.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

                result[href] = newEpisode(href) {
                    this.name = rawName
                    this.episode = episodeNumber
                    this.season = seasonNumber
                }
            }

        return result.values.toList()
    }

    private fun Document.collectPlayerUrls(referer: String): List<String> {
        val urls = linkedSetOf<String>()

        select("iframe[src], iframe[data-src], iframe[data-litespeed-src]").forEach { iframe ->
            iframe.getIframeAttr()?.fixUrlMaybe()?.let { urls.add(it) }
        }

        select("a[href*='vidplayer.live'], a[href*='embed'], a[href*='player']").forEach { link ->
            link.attr("href").fixUrlMaybe()?.let { urls.add(it) }
        }

        URL_REGEX.findAll(html())
            .map { it.value.cleanUrl() }
            .filter {
                it.contains(SF21_HOST, ignoreCase = true) ||
                    it.contains("embed", ignoreCase = true) ||
                    it.contains("player", ignoreCase = true)
            }
            .mapNotNull { it.fixUrlMaybe() }
            .forEach { urls.add(it) }

        return urls
            .filterNot { it.contains("youtube", ignoreCase = true) }
            .filterNot { it == referer || it == mainUrl || it == "$mainUrl/" }
            .toList()
    }

    private fun Document.parseMetaLinks(label: String): List<String> {
        return select("div.gmr-moviedata")
            .firstOrNull { it.selectFirst("strong")?.text()?.contains(label, ignoreCase = true) == true }
            ?.select("a")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()
    }

    private fun Element.getImageAttr(): String {
        return attr("abs:data-src").ifBlank { attr("abs:data-lazy-src") }
            .ifBlank { attr("abs:srcset").substringBefore(" ") }
            .ifBlank { attr("abs:src") }
            .ifBlank { attr("data-src") }
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("srcset").substringBefore(" ") }
            .ifBlank { attr("src") }
    }

    private fun Element.getIframeAttr(): String? {
        return attr("data-litespeed-src").ifBlank { attr("data-src") }
            .ifBlank { attr("src") }
            .takeIf { it.isNotBlank() }
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim('/')
        return when {
            cleanPath.isBlank() && page <= 1 -> "$mainUrl/"
            cleanPath.isBlank() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$cleanPath/"
            else -> "$mainUrl/$cleanPath/page/$page/"
        }
    }

    private fun String.fixUrlMaybe(): String? {
        val cleaned = cleanUrl()
        if (cleaned.isBlank() || cleaned.startsWith("javascript:", true) || cleaned.startsWith("#")) return null
        return when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("http", true) -> cleaned
            cleaned.startsWith("/") -> mainUrl + cleaned
            else -> "$mainUrl/$cleaned"
        }
    }

    private fun String.cleanUrl(): String {
        return trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .trim('"', '\'', ' ', '\n', '\r', '\t')
    }

    private fun String.cleanTitle(): String {
        return replace("Permalink to:", "", ignoreCase = true)
            .replace("Nonton Film", "", ignoreCase = true)
            .replace("Sub Indo Full Movie", "", ignoreCase = true)
            .replace("| Terbit21", "", ignoreCase = true)
            .replace("| LK21", "", ignoreCase = true)
            .trim()
    }

    private fun String.fixImageQuality(): String {
        return replace(Regex("-\\d+x\\d+(?=\\.[a-zA-Z]{3,4}(?:$|[?]))"), "")
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }

    companion object {
        private const val SF21_HOST = "sf21.vidplayer.live"
        private const val SF21_ORIGIN = "https://sf21.vidplayer.live"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

        private val SF21_M3U8_REGEX =
            Regex("""https://sf21\.vidplayer\.live/hls/[^'"<>\s]+\.m3u8[^'"<>\s]*""", RegexOption.IGNORE_CASE)

        private val URL_REGEX = Regex("""https?:\/\/[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
        private val YEAR_REGEX = Regex("""(?:19|20)\d{2}""")
        private val EPISODE_NUMBER_REGEX = Regex("""(?:Episode|Eps|Ep|E)[\s.-]*(\d+)""", RegexOption.IGNORE_CASE)
        private val SEASON_NUMBER_REGEX = Regex("""(?:Season|S)[\s.-]*(\d+)""", RegexOption.IGNORE_CASE)
    }
}
