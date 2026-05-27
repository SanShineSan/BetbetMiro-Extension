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
        val url = pageUrl(mainUrl, request.data, page)
        val document = app.get(url, headers = FilmLokalUtils.siteHeaders, referer = mainUrl).document
        val results = FilmLokalParser.parseListing(this, document)
        return newHomePageResponse(listOf(HomePageList(request.name, results, isHorizontalImages = true)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = searchUrl(mainUrl, query)
        val document = app.get(url, headers = FilmLokalUtils.siteHeaders, referer = mainUrl).document
        return FilmLokalParser.parseListing(this, document)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = FilmLokalUtils.siteHeaders, referer = mainUrl).document
        return FilmLokalParser.parseLoadResponse(this, url, document)
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
