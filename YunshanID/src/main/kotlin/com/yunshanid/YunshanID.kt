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
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie) // FIX: Menggunakan TvType.Movie resmi

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
                
                // FIX: Menggunakan sorted & lastOrNull agar 100% lolos compile versi Kotlin manapun
                val maxEp = item.episodesMap?.sorted()?.lastOrNull()
                if (maxEp != null) {
                    this.latestEpisode = maxEp
                }
            }
        }

        // FIX: Menggunakan deklarasi parameter eksplisit (list = ...) agar sesuai signature CloudStream
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = homeResults,
                isHorizontalImages = false
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$API_BASE/donghuas").text
        val items = tryParseJson<List<YunshanItem>>(response) ?: emptyList()

        return items.filter { it.title.contains(query, true) }.map { item ->
            newAnimeSearchResponse(item.title, "$mainUrl/donghua/${item.id}", TvType.Anime) {
                this.posterUrl = item.posterUrl ?: item.poster
                
                val maxEp = item.episodesMap?.sorted()?.lastOrNull()
                if (maxEp != null) {
                    this.latestEpisode = maxEp
                }
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
            // FIX: Menggunakan TvType.Movie resmi bawaan core library
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

        val watchResponse = app.get("$API_BASE/watch/$animeId/$epNum").text
        val cleanResponse = watchResponse.replace("\\/", "/")

        val linkRegex = """https?://[^\s"']+""".toRegex()
        val foundLinks = linkRegex.findAll(cleanResponse).map { it.value }.toList()

        var linkFound = false
        for (link in foundLinks) {
            val cleanLink = link.trim().removeSuffix("\\").removeSuffix("\"").removeSuffix("'")
            if (cleanLink.contains("ok.ru") || cleanLink.contains("odnoklassniki") || cleanLink.contains("video")) {
                loadExtractor(cleanLink, subtitleCallback, callback)
                linkFound = true
            }
        }

        if (!linkFound && foundLinks.isNotEmpty()) {
            val primaryLink = foundLinks.first().trim().removeSuffix("\\").removeSuffix("\"").removeSuffix("'")
            if (primaryLink.startsWith("http")) {
                loadExtractor(primaryLink, subtitleCallback, callback)
                linkFound = true
            }
        }

        return linkFound
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