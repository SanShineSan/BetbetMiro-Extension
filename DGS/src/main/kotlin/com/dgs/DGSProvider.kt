package com.dgs

import com.dgs.DGSUtils.pageUrl
import com.dgs.DGSUtils.searchUrl
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

class DGSProvider : MainAPI() {
    override var mainUrl = DGSSeeds.MAIN_URL
    override var name = "DGS"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(*DGSSeeds.mainPageRows())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(mainUrl, request.data, page)
        val document = app.get(url, headers = DGSUtils.headers, referer = mainUrl).document
        val items = DGSParser.parseListing(this, document)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        val document = app.get(searchUrl(mainUrl, query), headers = DGSUtils.headers, referer = mainUrl).document
        return DGSParser.parseListing(this, document)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = DGSUtils.headers, referer = mainUrl).document
        return DGSParser.parseLoadResponse(this, url, document)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return DGSExtractor.loadLinks(name, mainUrl, data, subtitleCallback, callback)
    }
}
