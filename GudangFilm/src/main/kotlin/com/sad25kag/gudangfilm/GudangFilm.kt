package com.sad25kag.gudangfilm

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addQuality
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class GudangFilm : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://malcontentgames.com"
    override var name = "GudangFilm🎉"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "movie/page/%d/" to "Movie",
        "serial-tv/page/%d/" to "Serial TV",
        "animasi/page/%d/" to "Animasi",
        "category/box-office/page/%d/" to "Box Office",
        "category/serial-tv/page/%d/" to "Serial TV Update",
        "category/animation/page/%d/" to "Animation Update",

        "action/page/%d/" to "Action",
        "adventure/page/%d/" to "Adventure",
        "animation/page/%d/" to "Animation",
        "comedy/page/%d/" to "Comedy",
        "crime/page/%d/" to "Crime",
        "documentary/page/%d/" to "Documentary",
        "drama/page/%d/" to "Drama",
        "family/page/%d/" to "Family",
        "fantasy/page/%d/" to "Fantasy",
        "history/page/%d/" to "History",
        "horror/page/%d/" to "Horror",
        "mystery/page/%d/" to "Mystery",
        "romance/page/%d/" to "Romance",
        "science-fiction/page/%d/" to "Science Fiction",
        "thriller/page/%d/" to "Thriller",
        "war/page/%d/" to "War",

        "country/indonesia/page/%d/" to "Indonesia",
        "country/korea/page/%d/" to "Korea",
        "country/japan/page/%d/" to "Japan",
        "country/china/page/%d/" to "China",
        "country/india/page/%d/" to "India",
        "country/thailand/page/%d/" to "Thailand",
        "country/philippines/page/%d/" to "Philippines",
        "country/usa/page/%d/" to "USA",
        "country/united-kingdom/page/%d/" to "United Kingdom",
        "country/canada/page/%d/" to "Canada",
        "country/australia/page/%d/" to "Australia",
        "country/hong-kong/page/%d/" to "Hong Kong",
        "country/ireland/page/%d/" to "Ireland",
        "country/new-zealand/page/%d/" to "New Zealand"
    )

    private val desktopHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/${request.data.format(page.coerceAtLeast(1))}"
        val document = app.get(url, headers = desktopHeaders).document

        val home = document.select(
            "article.item, " +
                "article, " +
                ".item, " +
                ".gmr-box-content, " +
                ".content-thumbnail"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(
                "a.next, " +
                    ".pagination a:contains(Next), " +
                    ".page-numbers:contains(»), " +
                    "a[href*='/page/${page + 1}/']"
            ) != null || home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2.entry-title > a[href], h3.entry-title > a[href], a[href]")
            ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (href.contains("/tag/", true) || href.contains("/country/", true)) return null

        val rawTitle = listOf(
            selectFirst("h2.entry-title > a")?.text()?.trim(),
            selectFirst("h3.entry-title > a")?.text()?.trim(),
            selectFirst(".entry-title a")?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Home", true) &&
                !it.equals("Next", true) &&
                !it.equals("Previous", true)
        } ?: return null

        val title = rawTitle.cleanTitle()
        if (title.length < 2) return null

        val ratingText = selectFirst("div.gmr-rating-item")?.ownText()?.trim()
        val posterUrl = fixUrlNull(selectFirst("a > img, img")?.getImageAttr())?.fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item > a")
            .text()
            .trim()
            .replace("-", "")

        val tvType = getTypeFromUrl(href)

        return if (tvType == TvType.TvSeries || tvType == TvType.Anime || quality.isEmpty()) {
            val episode = Regex("""Episode\s?(\d+)""", RegexOption.IGNORE_CASE)
                .find(title)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: select("div.gmr-numbeps > span").text().toIntOrNull()

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                addSub(episode)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encodedQuery = URLEncoder.encode(keyword, "UTF-8")
        val document = app.get(
            "$mainUrl?s=$encodedQuery&post_type[]=post&post_type[]=tv",
            timeout = 50L,
            headers = desktopHeaders
        ).document

        return document.select("article.item, article, .item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = desktopHeaders).document

        val title = document.selectFirst("h1.entry-title, h1")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.cleanTitle()
            ?.ifBlank { null }
            ?: url.substringAfterLast("/").replace("-", " ")

        val poster = fixUrlNull(
            document.selectFirst("figure.pull-left > img, .poster img, img.wp-post-image")
                ?.getImageAttr()
        )?.fixImageQuality()

        val tags = document.select(
            "strong:contains(Genre) ~ a, " +
                "a[href*='/genre/'], " +
                "a[href*='/category/']"
        ).eachText().distinct()

        val year = document.selectFirst("div.gmr-moviedata strong:contains(Year:) > a")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val tvType = getTypeFromUrl(url)
        val description = document.selectFirst("div[itemprop=description] > p, div[itemprop=description], .entry-content p")
            ?.text()
            ?.trim()

        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }

        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")
            ?.text()
            ?.trim()

        val actors = document.select("div.gmr-moviedata")
            .last()
            ?.select("span[itemprop=actors]")
            ?.map { it.select("a").text() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val duration = document.selectFirst("div.gmr-moviedata span[property=duration]")
            ?.text()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()

        val recommendations = document.select("article.item.col-md-20, article.item, .item")
            .mapNotNull { it.toRecommendResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        } else {
            val episodes = parseEpisodes(url, document)

            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        }
    }

    private suspend fun parseEpisodes(
        url: String,
        currentDocument: org.jsoup.nodes.Document
    ): List<Episode> {
        val seriesUrl = currentDocument.selectFirst("a.button.button-shadow.active")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?: url.substringBefore("/eps/")

        val seriesDoc = runCatching {
            app.get(seriesUrl, headers = desktopHeaders).document
        }.getOrDefault(currentDocument)

        val episodes = linkedMapOf<String, Episode>()
        var episodeCounter = 1

        seriesDoc.select(
            "div.gmr-listseries a.button.button-shadow[href], " +
                "a[href*='/eps/'], " +
                "a[href*='episode']"
        ).forEach { eps ->
            val href = fixUrlNull(eps.attr("href")) ?: return@forEach
            val name = eps.text().trim()

            if (name.contains("View All Episodes", ignoreCase = true)) return@forEach
            if (href == seriesUrl) return@forEach
            if (!name.contains("Eps", ignoreCase = true) && !href.contains("/eps/", true)) return@forEach

            val season = Regex("""S(\d+)\s*Eps""", RegexOption.IGNORE_CASE)
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 1

            val epNum = Regex("""Eps\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: episodeCounter++

            episodes[href] = newEpisode(href) {
                this.name = name.ifBlank { "Episode $epNum" }
                this.season = season
                this.episode = epNum
            }
        }

        return episodes.values
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 9999 })
            .ifEmpty {
                listOf(
                    newEpisode(url) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title > a, h3.entry-title > a, a[title]")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: return null

        val href = fixUrlNull(
            selectFirst("h2.entry-title > a, h3.entry-title > a, a[href]")?.attr("href")
        ) ?: return null

        val img = selectFirst("div.content-thumbnail img, img")
        val posterUrl = img?.getImageAttr()

        return newMovieSearchResponse(title.cleanTitle(), href, getTypeFromUrl(href)) {
            this.posterUrl = fixUrlNull(posterUrl)?.fixImageQuality()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val baseUrl = getBaseUrl(data)
        val document = app.get(data, headers = desktopHeaders).document
        val embedLinks = linkedSetOf<String>()
        var delivered = false

        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")

        if (id.isNullOrBlank()) {
            document.select("ul.muvipro-player-tabs li a[href]").forEach { ele ->
                val tabUrl = fixUrl(ele.attr("href"))

                val iframe = runCatching {
                    app.get(tabUrl, headers = desktopHeaders)
                        .document
                        .selectFirst("div.gmr-embed-responsive iframe, iframe")
                        ?.getIframeAttr()
                        ?.let { httpsify(it) }
                }.getOrNull()

                if (!iframe.isNullOrBlank()) embedLinks.add(iframe)
            }
        } else {
            document.select("div.tab-content-ajax[id]").forEach { ele ->
                val tabId = ele.attr("id").trim()
                if (tabId.isBlank()) return@forEach

                val iframe = runCatching {
                    app.post(
                        "$baseUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to tabId,
                            "post_id" to id
                        ),
                        headers = mapOf(
                            "Referer" to data,
                            "Origin" to baseUrl,
                            "User-Agent" to USER_AGENT,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).document
                        .selectFirst("iframe")
                        ?.getIframeAttr()
                        ?.let { httpsify(it) }
                }.getOrNull()

                if (!iframe.isNullOrBlank()) embedLinks.add(iframe)
            }
        }

        document.select(
            "iframe[src], " +
                "embed[src], " +
                "ul.gmr-download-list li a[href], " +
                "a[href*='/download/'], " +
                "a[href*='/dl/'], " +
                "a[href*='dood'], " +
                "a[href*='streamtape'], " +
                "a[href*='filemoon'], " +
                "a[href*='veev'], " +
                "a[href*='hglink'], " +
                "a[href*='ghbrisk'], " +
                "a[href*='ryderjet'], " +
                "a[href*='movearnpre'], " +
                "a[href*='minochinos'], " +
                "a[href*='mivalyo'], " +
                "a[href*='bingezove'], " +
                "a[href*='dintezuvio'], " +
                "a[href*='dingtezuni']"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isNotBlank()) {
                embedLinks.add(fixUrl(raw))
            }
        }

        embedLinks.distinct().forEach { embed ->
            val success = loadExtractor(embed, data, subtitleCallback, callback)
            if (success) delivered = true
        }

        return delivered
    }

    private fun getTypeFromUrl(url: String): TvType {
        return when {
            url.contains("/tv/", true) -> TvType.TvSeries
            url.contains("/serial-tv/", true) -> TvType.TvSeries
            url.contains("/animasi/", true) -> TvType.Anime
            url.contains("/anime/", true) -> TvType.Anime
            url.contains("/eps/", true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-original") -> attr("abs:data-original")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src")
            ?.takeIf { it.isNotBlank() }
            ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return replace(regex, "")
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""\s+"""), " ").trim()
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }
}