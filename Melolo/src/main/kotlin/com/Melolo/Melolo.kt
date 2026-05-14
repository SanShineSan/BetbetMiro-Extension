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

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    private val aid = "645713"
    private val catalogBase = "https://melolo-api-azure.vercel.app/api/melolo"
    private val browserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

    // =========================
    // LOAD DETAIL
    // =========================

    override suspend fun load(url: String): LoadResponse {
        val bookId = url.substringAfterLast("/").substringBefore("?").trim()
        val detail = fetchDetail(bookId)

        val episodes = detail.video_list
            .filter { it.disable_play != true }
            .mapNotNull { ep ->
                val vid = ep.vid ?: return@mapNotNull null
                val epIndex = ep.vid_index ?: return@mapNotNull null

                newEpisode(
                    EpisodeData(
                        bookId = bookId,
                        seriesId = detail.series_id_str ?: bookId,
                        vid = vid,
                        episode = epIndex,
                        videoPlatform = 2
                    ).toJson()
                ) {
                    this.name = "Episode $epIndex"
                    this.posterUrl = ep.cover
                    this.episode = epIndex
                }
            }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

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

    // =========================
    // LOAD LINKS
    // =========================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val ep = tryParseJson<EpisodeData>(data) ?: return false

        val body = """
            {
              "video_id": "${ep.vid}",
              "biz_param": {
                "video_id_type": 0,
                "device_level": 1,
                "video_platform": ${ep.videoPlatform}
              },
              "NovelCommonParam": {
                "app_language": "id",
                "sys_language": "id",
                "user_language": "id",
                "region": "ID",
                "time_zone": "Asia/Jakarta"
              }
            }
        """.trimIndent()

        val responseText = app.post(
            url = "$mainUrl/novel/player/video_model/v1/?aid=$aid",
            requestBody = body.toRequestBody("application/json".toMediaType()),
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json, text/plain, */*",
                "X-Xs-From-Web" to "true",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/",
                "User-Agent" to browserUA
            )
        ).text

        val resp = tryParseJson<PlayerVideoModelResponse>(responseText)

        val links = listOfNotNull(
            resp?.data?.main_url,
            resp?.data?.backup_url,
            resp?.data?.video_info?.main_url,
            resp?.data?.video_info?.backup_url,
            resp?.data?.video_model?.video_list?.values?.firstOrNull()?.main_url
        ).distinct()

        links.forEach { videoUrl ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "Melolo",
                    url = videoUrl,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf(
                        "User-Agent" to browserUA,
                        "Referer" to "$mainUrl/"
                    )
                )
            )
        }

        return links.isNotEmpty()
    }

    // =========================
    // MAIN PAGE & SEARCH
    // =========================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isSearchCategory = request.data.startsWith("q:", true)
        val books = if (isSearchCategory) {
            val query = request.data.removePrefix("q:")
            val url = "$catalogBase/search?query=${URLEncoder.encode(query, "UTF-8")}&limit=20&offset=${(page - 1) * 20}"
            tryParseJson<CatalogSearchResponse>(app.get(url).text)?.data?.search_data?.flatMap { it.books }.orEmpty()
        } else {
            if (request.data == "trending") fetchTrending() else fetchLatest()
        }

        val items = books.mapNotNull { b ->
            newTvSeriesSearchResponse(
                b.book_name ?: return@mapNotNull null,
                "$mainUrl/series/${b.book_id ?: return@mapNotNull null}",
                TvType.TvSeries
            ) { this.posterUrl = b.thumb_url }
        }
        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchSearch(query, 20, 0).mapNotNull { b ->
            newTvSeriesSearchResponse(
                b.book_name ?: return@mapNotNull null,
                "$mainUrl/series/${b.book_id ?: return@mapNotNull null}",
                TvType.TvSeries
            ) { this.posterUrl = b.thumb_url }
        }
    }

    // =========================
    // API FETCHERS
    // =========================

    private suspend fun fetchLatest(): List<CatalogBook> =
        tryParseJson<CatalogLatestResponse>(app.get("$catalogBase/latest").text)?.books.orEmpty().filter { it.language.equals("id", true) }

    private suspend fun fetchTrending(): List<CatalogBook> =
        tryParseJson<CatalogTrendingResponse>(app.get("$catalogBase/trending").text)?.books.orEmpty().filter { it.language.equals("id", true) }

    private suspend fun fetchSearch(query: String, limit: Int, offset: Int): List<CatalogBook> {
        val url = "$catalogBase/search?query=${URLEncoder.encode(query, "UTF-8")}&limit=$limit&offset=$offset"
        return tryParseJson<CatalogSearchResponse>(app.get(url).text)?.data?.search_data?.flatMap { it.books }.orEmpty().filter { it.language.equals("id", true) }
    }

    private suspend fun fetchDetail(bookId: String): CatalogVideoData =
        tryParseJson<CatalogDetailResponse>(app.get("$catalogBase/detail/$bookId").text)?.data?.video_data ?: throw ErrorLoadingException("Detail Empty")

    // =========================
    // DATA CLASSES
    // =========================

    data class PlayerVideoModelResponse(@JsonProperty("data") val data: PlayerVideoModelData? = null)
    data class PlayerVideoModelData(
        @JsonProperty("main_url") val main_url: String? = null,
        @JsonProperty("backup_url") val backup_url: String? = null,
        @JsonProperty("video_info") val video_info: PlayerVideoInfo? = null,
        @JsonProperty("video_model") val video_model: PlayerVideoModel? = null
    )
    data class PlayerVideoInfo(@JsonProperty("main_url") val main_url: String? = null, @JsonProperty("backup_url") val backup_url: String? = null)
    data class PlayerVideoModel(@JsonProperty("video_list") val video_list: Map<String, PlayerVideoInfo>? = null)
    data class CatalogLatestResponse(@JsonProperty("books") val books: List<CatalogBook> = emptyList())
    data class CatalogTrendingResponse(@JsonProperty("books") val books: List<CatalogBook> = emptyList())
    data class CatalogSearchResponse(@JsonProperty("data") val data: CatalogSearchData? = null)
    data class CatalogSearchData(@JsonProperty("has_more") val has_more: Boolean? = null, @JsonProperty("search_data") val search_data: List<CatalogSearchBlock> = emptyList())
    data class CatalogSearchBlock(@JsonProperty("books") val books: List<CatalogBook> = emptyList())
    data class CatalogBook(@JsonProperty("book_id") val book_id: String? = null, @JsonProperty("book_name") val book_name: String? = null, @JsonProperty("thumb_url") val thumb_url: String? = null, @JsonProperty("language") val language: String? = null)
    data class CatalogDetailResponse(@JsonProperty("data") val data: CatalogDetailData? = null)
    data class CatalogDetailData(@JsonProperty("video_data") val video_data: CatalogVideoData? = null)
    data class CatalogVideoData(
        @JsonProperty("series_id_str") val series_id_str: String? = null,
        @JsonProperty("series_title") val series_title: String? = null,
        @JsonProperty("series_intro") val series_intro: String? = null,
        @JsonProperty("series_cover") val series_cover: String? = null,
        @JsonProperty("video_list") val video_list: List<CatalogEpisode> = emptyList(),
        @JsonProperty("video_platform") val video_platform: Int? = null
    )
    data class CatalogEpisode(
        @JsonProperty("vid") val vid: String? = null,
        @JsonProperty("vid_index") val vid_index: Int? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("disable_play") val disable_play: Boolean? = null
    )
    data class EpisodeData(
        @JsonProperty("bookId") val bookId: String,
        @JsonProperty("seriesId") val seriesId: String,
        @JsonProperty("vid") val vid: String,
        @JsonProperty("episode") val episode: Int,
        @JsonProperty("videoPlatform") val videoPlatform: Int
    )
}
