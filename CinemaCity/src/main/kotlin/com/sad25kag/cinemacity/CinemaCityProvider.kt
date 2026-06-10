package com.sad25kag.cinemacity

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink

class CinemaCityProvider : MainAPI() {
    override var mainUrl = CinemaCityConstans.MAIN_URL
    override var name = CinemaCityConstans.NAME
    override var lang = CinemaCityConstans.LANGUAGE
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Documentary,
        TvType.Anime
    )

    override val mainPage = CinemaCitySeed.mainPage

    private val parser by lazy { CinemaCityParser(this) }
    private val extractor by lazy { CinemaCityExtractor(parser) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page > 1) "$base/page/$page/" else "$base/"
        val document = CinemaCityUtils.get(url).document
        val items = parser.parseCards(document)
        val hasNext = document.selectFirst(
            "a[href*='/page/${page + 1}/'], a.next, a[rel=next], .pnext, .next"
        ) != null

        return newHomePageResponse(listOf(HomePageList(request.name, items)), hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val results = search(query)
        return newSearchResponseList(results, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = CinemaCityUtils.encodeQuery(query)
        val searchUrls = listOf(
            "$mainUrl/?do=search&subaction=search&story=$encoded",
            "$mainUrl/index.php?do=search&subaction=search&story=$encoded"
        )

        searchUrls.forEach { url ->
            val results = parser.parseCards(CinemaCityUtils.get(url).document)
            if (results.isNotEmpty()) return results
        }

        val ajaxResults = runCatching {
            val document = CinemaCityUtils.post(
                "$mainUrl/engine/ajax/controller.php?mod=search",
                mapOf("query" to query, "skin" to "cinemacity")
            ).document
            parser.parseCards(document)
        }.getOrDefault(emptyList())

        return ajaxResults.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = CinemaCityUtils.get(url).document
        val title = parser.parseTitle(document) ?: return null
        val poster = parser.parsePoster(document)
        val description = parser.parseDescription(document)
        val tags = parser.parseTags(document)
        val score = parser.parseScore(document)
        val recommendations = parser.parseRecommendations(document)
        val episodes = parser.parseEpisodes(document, poster)
        val year = CinemaCityUtils.parseYear(title) ?: CinemaCityUtils.parseYear(document.text())
        val isTv = CinemaCityUtils.isTvSeries(url, document.text())
        val status = when {
            document.text().contains("Ongoing", true) -> ShowStatus.Ongoing
            document.text().contains("Completed", true) || document.text().contains("Ended", true) -> ShowStatus.Completed
            else -> null
        }

        return if (isTv) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                posterHeaders = mapOf("Referer" to "$mainUrl/")
                plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from(score, 10)
                this.recommendations = recommendations
                showStatus = status
            }
        } else {
            val movieData = episodes.firstOrNull()?.data ?: url
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                posterUrl = poster
                posterHeaders = mapOf("Referer" to "$mainUrl/")
                plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from(score, 10)
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
        return extractor.loadLinks(data, subtitleCallback, callback)
    }
}
