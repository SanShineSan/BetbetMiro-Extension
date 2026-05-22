package com.gerakin21

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.toRatingInt
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Gerakin21 : MainAPI() {
    override var mainUrl = "https://gerakin21.cloud"
    override var name = "Gerakin21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "trending" to "Trending",
        "movies" to "Movies",
        "tv-series" to "TV Series",

        "genre/action" to "Action",
        "genre/adventure" to "Adventure",
        "genre/animation" to "Animation",
        "genre/comedy" to "Comedy",
        "genre/crime" to "Crime",
        "genre/drama" to "Drama",
        "genre/family" to "Family",
        "genre/fantasy" to "Fantasy",
        "genre/history" to "History",
        "genre/horror" to "Horror",
        "genre/mystery" to "Mystery",
        "genre/romance" to "Romance",
        "genre/science-fiction" to "Sci-Fi",
        "genre/thriller" to "Thriller",
        "genre/war" to "War"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url).document

        val home = document.select(
            "article, " +
                ".item, " +
                ".ml-item, " +
                ".movie, " +
                ".film, " +
                ".post, " +
                ".grid-item, " +
                ".result-item, " +
                ".latest-post, " +
                ".content article, " +
                ".movie-list article, " +
                ".movies-list article, " +
                "div[class*=movie], " +
                "div[class*=film], " +
                "div[class*=item]"
        ).mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = hasNextPage(document, page)
        )
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val cleanPath = path.trim('/')

        return when {
            page <= 1 && cleanPath.isBlank() -> mainUrl
            page <= 1 -> "$mainUrl/$cleanPath/"
            cleanPath.isBlank() -> "$mainUrl/page/$page/"
            else -> "$mainUrl/$cleanPath/page/$page/"
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                "a[href][title], " +
                    "h2 a[href], " +
                    "h3 a[href], " +
                    ".title a[href], " +
                    ".entry-title a[href], " +
                    ".movie-title a[href], " +
                    ".film-title a[href], " +
                    ".poster a[href], " +
                    ".thumb a[href], " +
                    "a[href]"
            ) ?: return null
        }

        val href = fixUrl(anchor.attr("href"))
        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val title = listOf(
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".title")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst(".movie-title")?.text(),
            selectFirst(".film-title")?.text(),
            selectFirst(".jt")?.text(),
            anchor.attr("title"),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.text()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Home", true) &&
                !it.equals("Next", true) &&
                !it.equals("Previous", true) &&
                !it.equals("Movies", true) &&
                !it.equals("TV Series", true) &&
                !it.equals("Trending", true) &&
                !it.equals("Search", true)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val poster = selectFirst(
            "img[data-src], " +
                "img[data-lazy-src], " +
                "img[data-original], " +
                "img[srcset], " +
                "img[src]"
        )?.getImageAttr()
            ?.let { fixUrlNull(it) }

        val type = getTypeFromUrl(href, title, emptyList())

        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }

            else -> newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl, "").lowercase().trimEnd('/')

        if (path.isBlank()) return true

        val exactBlocked = setOf(
            "/trending",
            "/movies",
            "/tv-series",
            "/genre",
            "/country",
            "/year",
            "/tag",
            "/category",
            "/privacy",
            "/dmca",
            "/contact",
            "/terms",
            "/about"
        )

        if (exactBlocked.contains(path)) return true

        val prefixBlocked = listOf(
            "/page/",
            "/search",
            "/feed",
            "/wp-json",
            "/wp-content",
            "/wp-admin"
        )

        return prefixBlocked.any { path.startsWith(it) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$q").document

        return document.select(
            "article, " +
                ".item, " +
                ".ml-item, " +
                ".movie, " +
                ".film, " +
                ".post, " +
                ".grid-item, " +
                ".result-item, " +
                ".search-result, " +
                ".content article, " +
                "div[class*=movie], " +
                "div[class*=film], " +
                "div[class*=item]"
        ).mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(
            "h1.title, " +
                "h1[itemprop=name], " +
                "h1.entry-title, " +
                ".entry-title, " +
                ".s-title, " +
                ".movie-title, " +
                ".film-title, " +
                "meta[property=og:title]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.selectFirst(
            "meta[property=og:image], " +
                "meta[name=twitter:image], " +
                "div.poster img, " +
                ".poster img, " +
                ".s-cover img, " +
                ".thumb img, " +
                ".entry-content img, " +
                "img.wp-post-image"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.getImageAttr()
            }
        }?.let { fixUrlNull(it) }

        val description = document.selectFirst(
            "meta[property=og:description], " +
                "meta[name=description], " +
                "div.wp-content, " +
                ".entry-content, " +
                ".s-desc, " +
                ".sinopsis, " +
                ".description, " +
                ".summary, " +
                ".movie-desc"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.cleanPlot()

        val rating = document.selectFirst(
            "span.rating, " +
                ".rating, " +
                ".score, " +
                ".imdb, " +
                "[itemprop=ratingValue]"
        )?.text()
            ?.toRatingInt()

        val tags = document.select(
            "a[href*='/genre/'], " +
                "a[href*='/category/'], " +
                "a[href*='/tag/'], " +
                ".genres a, " +
                ".genre a, " +
                ".tags a, " +
                ".category a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select(
            ".related article, " +
                ".related-posts article, " +
                ".related a[href], " +
                ".post, " +
                ".item, " +
                ".movie, " +
                ".film"
        ).mapNotNull { element ->
            element.toSearchResult()
        }.filter { it.url != url }
            .distinctBy { it.url }

        val type = getTypeFromUrl(url, title, tags)

        return if (type == TvType.TvSeries) {
            val episodes = document.select(
                "ul.episodes li a[href], " +
                    "ul.episodes a[href], " +
                    "div.episode-list a[href], " +
                    ".episode-list a[href], " +
                    ".episodios li a[href], " +
                    ".eplister a[href], " +
                    ".episodelist a[href], " +
                    "a[href*='/episode/']"
            ).mapNotNull { episodeElement ->
                val epHref = fixUrlNull(episodeElement.attr("href")) ?: return@mapNotNull null
                val epTitle = listOf(
                    episodeElement.selectFirst(".title")?.text(),
                    episodeElement.selectFirst(".nama-episode")?.text(),
                    episodeElement.selectFirst(".epl-title")?.text(),
                    episodeElement.attr("title"),
                    episodeElement.text()
                ).firstOrNull { !it.isNullOrBlank() }
                    ?.cleanTitle()
                    ?: "Episode"

                Episode(
                    data = epHref,
                    name = epTitle
                )
            }.distinctBy { it.data }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                plot = description
                this.rating = rating
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                plot = description
                this.rating = rating
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val embeds = linkedSetOf<String>()

        document.select(
            "iframe[src], " +
                "iframe[data-src], " +
                "embed[src], " +
                "a[href*='embed'], " +
                "a[href*='player'], " +
                "a[href*='streamtape'], " +
                "a[href*='filemoon'], " +
                "a[href*='vidhide'], " +
                "a[href*='voe'], " +
                "a[href*='dood'], " +
                "a[href*='mixdrop'], " +
                "a[href*='streamwish'], " +
                "a[href*='wish'], " +
                "a[href*='filelions'], " +
                "a[href*='vidguard']"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isNotBlank()) {
                embeds.add(fixUrl(raw))
            }
        }

        embeds.forEach { embed ->
            loadExtractor(
                embed,
                data,
                subtitleCallback,
                callback
            )
        }

        return embeds.isNotEmpty()
    }

    private fun hasNextPage(
        document: Document,
        page: Int
    ): Boolean {
        return document.selectFirst(
            "a[rel=next], " +
                ".pagination a:contains(Next), " +
                ".pagination a:contains(Berikutnya), " +
                ".pagination a[href*='/page/${page + 1}'], " +
                "a[href*='/page/${page + 1}/'], " +
                "a[href*='paged=${page + 1}']"
        ) != null
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src").ifBlank { attr("data-src") }
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src").ifBlank { attr("data-lazy-src") }
            hasAttr("data-original") -> attr("abs:data-original").ifBlank { attr("data-original") }
            hasAttr("srcset") -> attr("abs:srcset").ifBlank { attr("srcset") }.substringBefore(" ")
            hasAttr("src") -> attr("abs:src").ifBlank { attr("src") }
            else -> null
        }
    }

    private fun getTypeFromUrl(
        url: String,
        title: String,
        tags: List<String>
    ): TvType {
        val check = (url + " " + title + " " + tags.joinToString(" ")).lowercase()

        return when {
            check.contains("tv-series") ||
                check.contains("series") ||
                check.contains("episode") ||
                check.contains("season") -> TvType.TvSeries

            else -> TvType.Movie
        }
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+-\s+Gerakin21.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+Gerakin21.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s+Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanPlot(): String? {
        return this
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }
}