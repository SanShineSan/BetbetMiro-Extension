package com.betbet.yunshanid

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class YunshanIDProvider : MainAPI() {
    override var mainUrl = DomainManager.getMainDomain()
    override var name = "YunshanID"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/donghuas/page/" to "Latest Donghua",
        "$mainUrl/donghua-tamat/page/" to "Completed Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page/").document
        val home = document.select("div.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".tt")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "YunshanID"
        val poster = document.selectFirst(".thumb img")?.attr("src")
        val description = document.selectFirst(".entry-content p")?.text()?.trim()
        
        val episodes = document.select("div.eplister li").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            val name = it.selectFirst(".epl-num")?.text() ?: "Episode"
            Episode(href, name)
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.episodes = episodes
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        document.select("select.mirror option").forEach { 
            val rawLink = it.attr("value")
            if (rawLink.isNotEmpty()) {
                val decoded = if (rawLink.startsWith("ey")) {
                    try {
                        String(Base64.decode(rawLink, Base64.DEFAULT))
                    } catch (e: Exception) {
                        rawLink
                    }
                } else {
                    rawLink
                }
                
                if (decoded.startsWith("http")) {
                    loadExtractor(decoded, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
