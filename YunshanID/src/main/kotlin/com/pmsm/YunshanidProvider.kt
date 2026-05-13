package com.Yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class YunshanidProvider : MainAPI() {
    override var mainUrl = "https://yunshanid.site"
    override var name = "Yunshanid"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse? {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document
        
        val home = document.select("article, .list-upd .bs").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article, .list-upd .bs").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2, .tt")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        val type = if (this.select(".type").text().contains("TV", true)) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".poster img, .thumb img")?.attr("src")
        val plot = document.selectFirst(".entry-content p, .synopsis p")?.text()
        
        val episodes = document.select(".list-episode li, .eplister li").mapNotNull {
            val epName = it.select(".ep-num, .epl-num").text() ?: "Episode"
            val epHref = it.select("a").attr("href") ?: return@mapNotNull null
            Episode(epHref, epName)
        }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = plot
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
        
        // Ekstraksi dari iframe/embed langsung
        document.select("iframe, source").forEach { element ->
            val src = element.attr("src").ifEmpty { element.attr("data-src") }
            if (src.isNotEmpty() && src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        // Ekstraksi dari opsi server (jika ada)
        document.select(".mirror-option option, .nav-tabs li a").forEach {
            val embedUrl = it.attr("value").ifEmpty { it.attr("data-embed") }
            if (embedUrl.isNotEmpty() && embedUrl.startsWith("http")) {
                loadExtractor(embedUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}