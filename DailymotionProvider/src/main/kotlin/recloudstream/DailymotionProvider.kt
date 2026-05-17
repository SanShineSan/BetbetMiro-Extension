package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor

class DailymotionProvider : MainAPI() {

    override var mainUrl = "https://www.dailymotion.com"
    override var name = "Dailymotion 📺"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie)

    // Menyusun menu beranda berdasarkan channel/kategori terpopuler di Dailymotion
    override val mainPage = mainPageOf(
        "sort=trending" to "Trending Hari Ini",
        "channel=shortfilms&sort=recent" to "Film Pendek & Sinema",
        "channel=news&sort=recent" to "Berita & Politik",
        "channel=sport&sort=recent" to "Olahraga",
        "channel=music&sort=recent" to "Musik & Hiburan",
        "channel=videogames&sort=recent" to "Gaming & Live"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "https://api.dailymotion.com/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page&${request.data}"
        val response = app.get(url).text
        val json = tryParseJson<VideoSearchResponse>(response)
        
        // Memetakan video yang valid saja ke beranda
        val home = json?.list?.mapNotNull { it.toSearchResponse(this) } ?: emptyList()
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://api.dailymotion.com/videos?fields=id,title,thumbnail_360_url&limit=20&search=${encodeUri(query)}"
        val response = app.get(url).text
        val json = tryParseJson<VideoSearchResponse>(response)
        
        return json?.list?.mapNotNull { it.toSearchResponse(this) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        // Ekstraksi ID video secara aman dari URL untuk ditembak ke API Detail
        val videoId = Regex("dailymotion.com/video/([a-zA-Z0-9]+)").find(url)?.groups?.get(1)?.value 
            ?: url.substringAfter("/video/").substringBefore("?")
            
        val response = app.get("https://api.dailymotion.com/video/$videoId?fields=id,title,description,thumbnail_720_url,duration").text
        val videoDetail = tryParseJson<VideoDetailResponse>(response) ?: return null
        return videoDetail.toLoadResponse(this)
    }

    private fun VideoItem.toSearchResponse(provider: DailymotionProvider): SearchResponse? {
        val id = this.id ?: return null
        return provider.newMovieSearchResponse(
            this.title ?: "Dailymotion Video",
            "https://www.dailymotion.com/video/$id",
            TvType.Movie
        ) {
            this.posterUrl = thumbnail360Url
        }
    }

    private fun VideoDetailResponse.toLoadResponse(provider: DailymotionProvider): LoadResponse {
        val watchUrl = "https://www.dailymotion.com/video/${this.id}"
        return provider.newMovieLoadResponse(
            this.title ?: "Dailymotion Video",
            watchUrl,
            TvType.Movie,
            watchUrl // Mengirimkan url nonton penuh agar diekstrak oleh Dailymotion Extractor core
        ) {
            this.plot = description
            this.posterUrl = thumbnail720Url
            this.duration = (this.duration ?: 0) / 60
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Memanggil extractor internal/bawaan Cloudstream untuk mengurai m3u8 dari Dailymotion
        loadExtractor(data, subtitleCallback, callback)
        return true
    }

    // Model data yang aman dari warning target anotasi masa depan
    data class VideoSearchResponse(
        @param:JsonProperty("list") val list: List<VideoItem>? = emptyList()
    )

    data class VideoItem(
        @param:JsonProperty("id") val id: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("thumbnail_360_url") val thumbnail360Url: String? = null
    )

    data class VideoDetailResponse(
        @param:JsonProperty("id") val id: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("description") val description: String? = null,
        @param:JsonProperty("thumbnail_720_url") val thumbnail720Url: String? = null,
        @param:JsonProperty("duration") val duration: Int? = null
    )
}
