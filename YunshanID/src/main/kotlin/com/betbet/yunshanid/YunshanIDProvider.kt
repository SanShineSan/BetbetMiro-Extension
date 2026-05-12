package com.betbet.yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
[span_0](start_span)import android.util.Base64 // Tambahkan ini untuk decode[span_0](end_span)

class YunshanIDProvider : MainAPI() {
    [span_1](start_span)override var mainUrl = DomainManager.getMainDomain() // Gunakan DomainManager[span_1](end_span)
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
        [span_2](start_span)"$mainUrl/donghua-tamat/page/" to "Completed Donghua" //[span_2](end_span)
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        [span_3](start_span)val document = app.get("${request.data}$page/").document //[span_3](end_span)
        [span_4](start_span)val home = document.select("div.bs").mapNotNull { it.toSearchResult() } //[span_4](end_span)
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        [span_5](start_span)val title = this.selectFirst(".tt")?.text()?.trim() ?: return null //[span_5](end_span)
        [span_6](start_span)val href = this.selectFirst("a")?.attr("href") ?: return null //[span_6](end_span)
        [span_7](start_span)val posterUrl = this.selectFirst("img")?.attr("src") //[span_7](end_span)
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        [span_8](start_span)val document = app.get("$mainUrl/?s=$query").document //[span_8](end_span)
        [span_9](start_span)return document.select("div.bs").mapNotNull { it.toSearchResult() } //[span_9](end_span)
    }

    override suspend fun load(url: String): LoadResponse {
        [span_10](start_span)val document = app.get(url).document //[span_10](end_span)
        [span_11](start_span)val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "YunshanID" //[span_11](end_span)
        [span_12](start_span)val poster = document.selectFirst(".thumb img")?.attr("src") //[span_12](end_span)
        [span_13](start_span)val description = document.selectFirst(".entry-content p")?.text()?.trim() //[span_13](end_span)
        
        [span_14](start_span)val episodes = document.select("div.eplister li").mapNotNull { //[span_14](end_span)
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            val name = it.selectFirst(".epl-num")?.text() ?: "Episode"
            Episode(href, name)
        [span_15](start_span)}.reversed() //[span_15](end_span)

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
        [span_16](start_span)val document = app.get(data).document //[span_16](end_span)
        
        [span_17](start_span)document.select("select.mirror option").forEach { //[span_17](end_span)
            val rawLink = it.attr("value")
            if (rawLink.isNotEmpty()) {
                val decoded = if (rawLink.startsWith("ey")) {
                    [span_18](start_span)// Perbaikan: Gunakan standard Android Base64[span_18](end_span)
                    String(Base64.decode(rawLink, Base64.DEFAULT)) 
                } else {
                    rawLink
                }
                // Perbaikan: Pastikan URL valid sebelum load extractor
                if (decoded.startsWith("http")) {
                    loadExtractor(decoded, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
