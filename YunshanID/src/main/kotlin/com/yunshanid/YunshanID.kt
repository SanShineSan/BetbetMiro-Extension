package com.yunshanid

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.*

class YunshanID : MainAPI() {
    override var mainUrl = "https://yunshanid.site"
    override var name = "Yunshan ID 🏔️"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    companion object {
        const val API_BASE = "https://yunshanid.site/api"
    }

    override val mainPage = mainPageOf(
        "latest" to "Rilisan Terbaru",
        "ongoing" to "Donghua Ongoing",
        "completed" to "Donghua Completed",
        "movie" to "Donghua Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("$API_BASE/donghuas").text
        val items = tryParseJson<List<YunshanItem>>(response) ?: emptyList()
        
        val filteredItems = when (request.data) {
            "ongoing" -> items.filter { it.status?.contains("On-Going", true) == true }
            "completed" -> items.filter { it.status?.contains("Completed", true) == true }
            "movie" -> items.filter { it.type?.contains("Movie", true) == true }
            else -> items
        }

        val homeResults = filteredItems.map { item ->
            newAnimeSearchResponse(item.title, "$mainUrl/donghua/${item.id}", TvType.Anime) {
                this.posterUrl = item.posterUrl ?: item.poster
            }
        }

        return newHomePageResponse(HomePageList(request.name, homeResults), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$API_BASE/donghuas").text
        val items = tryParseJson<List<YunshanItem>>(response) ?: emptyList()

        return items.filter { it.title.contains(query, true) }.map { item ->
            newAnimeSearchResponse(item.title, "$mainUrl/donghua/${item.id}", TvType.Anime) {
                this.posterUrl = item.posterUrl ?: item.poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/")
        
        val response = app.get("$API_BASE/donghuas").text
        val items = tryParseJson<List<YunshanItem>>(response) ?: emptyList()
        
        val item = items.find { it.id.toString() == id } 
            ?: throw ErrorLoadingException("Detail Donghua tidak ditemukan")

        val title = item.title
        val poster = item.posterUrl ?: item.poster
        val description = item.synopsis
        val tags = item.genres

        val tvType = if (item.type?.contains("Movie", true) == true) TvType.Movie else TvType.TvSeries

        if (tvType == TvType.Movie) {
            return newMovieLoadResponse(title, url, TvType.Movie, "$id-1") {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            val episodes = item.episodesMap?.sorted()?.map { epNum ->
                newEpisode("$id-$epNum") {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                }
            } ?: emptyList()

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("-")
        if (parts.size < 2) return false
        val animeId = parts[0]
        val epNum = parts[1]

        // 1. Ambil respon JSON dari API
        val watchResponse = app.get("$API_BASE/watch/$animeId/$epNum").text

        // 2. Tangkap nilai dari "video_url" menggunakan Regex presisi
        val urlRegex = """"video_url"\s*:\s*"([^"]+)"""".toRegex()
        val match = urlRegex.find(watchResponse)
        
        // Ambil link mentahnya (misal: //ok.ru/videoembed/...)
        val rawUrl = match?.groupValues?.get(1) ?: return false
        
        // 3. Bersihkan pelindung garis miring (\/) jika ada
        val cleanUrl = rawUrl.replace("\\/", "/")

        // 4. Tambahkan "https:" jika link hanya diawali "//"
        val finalUrl = if (cleanUrl.startsWith("//")) {
            "https:$cleanUrl"
        } else {
            cleanUrl
        }

        // 5. Lempar link matang ke mesin pemutar video (Extractor)
        loadExtractor(finalUrl, subtitleCallback, callback)

        return true
    }
}

data class YunshanItem(
    val id: Int,
    val title: String,
    val synopsis: String? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    val poster: String? = null,
    val status: String? = null,
    val type: String? = null,
    val genres: List<String>? = null,
    @JsonProperty("episodes_map") val episodesMap: List<Int>? = null
)