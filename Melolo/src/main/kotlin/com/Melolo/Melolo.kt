package com.Melolo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class Melolo : MainAPI() {
    override var mainUrl = "https://www.melolo.com"
    override var name = "Melolo😶"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    private val aid = "645713"
    private val catalogBase = "https://melolo-api-azure.vercel.app/api/melolo"
    private val browserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

    override suspend fun load(url: String): LoadResponse {
        val bookId = url.substringAfterLast("/").substringBefore("?").trim()
        val detail = fetchDetail(bookId)
        
        val episodes = detail.video_list.filter { it.disable_play != true }.mapNotNull { ep ->
            val epIndex = ep.vid_index ?: return@mapNotNull null
            val vid = ep.vid ?: return@mapNotNull null
            
            // Format newEpisode yang paling stabil untuk build
            newEpisode(EpisodeData(bookId, detail.series_id_str ?: bookId, vid, epIndex, 2).toJson()) {
                this.name = "Episode $epIndex"
                this.posterUrl = ep.cover
                this.episode = epIndex
            }
        }.sortedBy { it.episode ?: Int.MAX_VALUE }

        return newTvSeriesLoadResponse(
            detail.series_title ?: "Melolo",
            "$mainUrl/series/$bookId",
            TvType.TvSeries,
            episodes
        ) {
            this.plot = detail.series_intro
            this.posterUrl = detail.series_cover
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        val body = """{"video_id":"${ep.vid}","biz_param":{"video_id_type":0,"device_level":1,"video_platform":${ep.videoPlatform}},"NovelCommonParam":{"app_language":"id","sys_language":"id","user_language":"id","region":"ID","time_zone":"Asia/Jakarta"}}"""
        
        val response = app.post(
            "$mainUrl/novel/player/video_model/v1/?aid=$aid",
            requestBody = body.toRequestBody("application/json".toMediaType()),
            headers = mapOf(
                "Content-Type" to "application/json",
                "X-Xs-From-Web" to "true",
                "User-Agent" to browserUA,
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
        ).text

        val resp = tryParseJson<PlayerVideoModelResponse>(response)
        val links = listOfNotNull(
            resp?.data?.main_url, 
            resp?.data?.backup_url, 
            resp?.data?.video_info?.main_url,
            resp?.data?.video_info?.backup_url, 
            resp?.data?.video_model?.video_list?.values?.firstOrNull()?.main_url
        ).distinct()

        links.forEach { videoUrl ->
            // Menggunakan named arguments supaya tidak pening kepala masa build
            callback(
                newExtractorLink(
                    source = name,
                    name = "Melolo",
                    url = videoUrl,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf("User-Agent" to browserUA)
                )
            )
        }
        return links.isNotEmpty()
    }

    // --- FETCHERS ---
    private suspend fun fetchLatest(): List<CatalogBook> = 
        tryParseJson<CatalogLatestResponse>(app.get("$catalogBase/latest").text)?.books.orEmpty().filter { it.language.equals("id", true) }

    private suspend fun fetchTrending(): List<CatalogBook> = 
        tryParseJson<CatalogTrendingResponse>(app.get("$catalogBase/trending").text)?.books.orEmpty().filter { it.language.equals("id", true) }

    private suspend fun fetchSearch(q: String, l: Int, o: Int): List<CatalogBook> {
        val url = "$catalogBase/search?query=${URLEncoder.encode(q,"UTF-8")}&limit=$l&offset=$o"
        return tryParseJson<CatalogSearchResponse>(app.get(url).text)?.data?.search_data?.flatMap { it.books }.orEmpty().filter { it.language.equals("id", true) }
    }

    private suspend fun fetchDetail(id: String): CatalogVideoData = 
        tryParseJson<CatalogDetailResponse>(app.get("$catalogBase/detail/$id").text)?.data?.video_data ?: throw ErrorLoadingException("Detail Empty")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val books = if (request.data.startsWith("q:")) {
            fetchSearch(request.data.removePrefix("q:"), 20, (page - 1) * 20)
        } else { 
            if (request.data == "trending") fetchTrending() else fetchLatest() 
        }
        val items = books.mapNotNull { b -> 
            newTvSeriesSearchResponse(b.book_name ?: return@mapNotNull null, "$mainUrl/series/${b.book_id}", TvType.TvSeries) { this.posterUrl = b.thumb_url } 
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> = 
        fetchSearch(query, 20, 0).mapNotNull { b -> 
            newTvSeriesSearchResponse(b.book_name ?: return@mapNotNull null, "$mainUrl/series/${b.book_id}", TvType.TvSeries) { this.posterUrl = b.thumb_url } 
        }

    // --- DATA CLASSES ---
    data class PlayerVideoModelResponse(@JsonProperty("data") val data: PlayerVideoModelData?)
    data class PlayerVideoModelData(
        @JsonProperty("main_url") val main_url: String?, 
        @JsonProperty("backup_url") val backup_url: String?, 
        @JsonProperty("video_info") val video_info: PlayerVideoInfo?, 
        @JsonProperty("video_model") val video_model: PlayerVideoModel?
    )
    data class PlayerVideoInfo(@JsonProperty("main_url") val main_url: String?, @JsonProperty("backup_url") val backup_url: String?)
    data class PlayerVideoModel(@JsonProperty("video_list") val video_list: Map<String, PlayerVideoInfo>?)
    data class CatalogLatestResponse(@JsonProperty("books") val books: List<CatalogBook>)
    data class CatalogTrendingResponse(@JsonProperty("books") val books: List<CatalogBook>)
    data class CatalogSearchResponse(@JsonProperty("data") val data: CatalogSearchData?)
    data class CatalogSearchData(@JsonProperty("search_data") val search_data: List<CatalogSearchBlock>?, @JsonProperty("has_more") val has_more: Boolean?)
    data class CatalogSearchBlock(@JsonProperty("books") val books: List<CatalogBook>)
    data class CatalogBook(
        @JsonProperty("book_id") val book_id: String?, 
        @JsonProperty("book_name") val book_name: String?, 
        @JsonProperty("thumb_url") val thumb_url: String?, 
        @JsonProperty("language") val language: String?
    )
    data class CatalogDetailResponse(@JsonProperty("data") val data: CatalogDetailData?)
    data class CatalogDetailData(@JsonProperty("video_data") val video_data: CatalogVideoData?)
    data class CatalogVideoData(
        @JsonProperty("series_id_str") val series_id_str: String?, 
        @JsonProperty("series_title") val series_title: String?, 
        @JsonProperty("series_intro") val series_intro: String?, 
        @JsonProperty("series_cover") val series_cover: String?, 
        @JsonProperty("video_list") val video_list: List<CatalogEpisode>, 
        @JsonProperty("video_platform") val video_platform: Int?
    )
    data class CatalogEpisode(
        @JsonProperty("vid") val vid: String?, 
        @JsonProperty("vid_index") val vid_index: Int?, 
        @JsonProperty("cover") val cover: String?, 
        @JsonProperty("disable_play") val disable_play: Boolean?
    )
    data class EpisodeData(
        @JsonProperty("bookId") val bookId: String, 
        @JsonProperty("seriesId") val seriesId: String, 
        @JsonProperty("vid") val vid: String, 
        @JsonProperty("episode") val episode: Int, 
        @JsonProperty("videoPlatform") val videoPlatform: Int
    )
}
