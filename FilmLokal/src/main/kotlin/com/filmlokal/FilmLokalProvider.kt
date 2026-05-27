package com.filmlokal

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.filmlokal.FilmLokalUtils.pageUrl
import com.filmlokal.FilmLokalUtils.searchUrl

class FilmLokalProvider : MainAPI() {
    override var mainUrl = FilmLokalSeeds.MAIN_URL
    override var name = "FilmLokal"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)

    override val mainPage = mainPageOf(*FilmLokalSeeds.mainPageRows())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = runCatching {
            val url = pageUrl(mainUrl, request.data, page)
            val document = app.get(url, headers = FilmLokalUtils.siteHeaders, referer = mainUrl).document
            FilmLokalParser.parseListing(this, document)
        }.getOrElse { emptyList() }
        return newHomePageResponse(listOf(HomePageList(request.name, results, isHorizontalImages = true)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return runCatching {
            val url = searchUrl(mainUrl, query)
            val document = app.get(url, headers = FilmLokalUtils.siteHeaders, referer = mainUrl).document
            FilmLokalParser.parseListing(this, document)
        }.getOrElse { emptyList() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return runCatching {
            val document = app.get(url, headers = FilmLokalUtils.siteHeaders, referer = mainUrl).document
            FilmLokalParser.parseLoadResponse(this, url, document)
        }.getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return FilmLokalExtractor.loadLinks(name, mainUrl, data, subtitleCallback, callback)
    }
}
