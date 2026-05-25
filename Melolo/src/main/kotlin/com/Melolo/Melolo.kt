package com.Melolo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import kotlin.math.abs

class Melolo : MainAPI() {
    override var mainUrl = "https://api31-normal-mya.tmtreader.com"
    override var name = "Melolo"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    private val aid = "645713"
    private val catalogBase = "https://melolo-api-azure.vercel.app/api/melolo"
    private val splayBase = "https://api.splay.id"

    override val mainPage = mainPageOf(
        "latest" to "Terbaru",
        "trending" to "Trending",
        "q:ceo" to "CEO",
        "q:romansa" to "Romansa",
        "q:sistem" to "Sistem",
        "q:keluarga" to "Keluarga",
        "q:mafia" to "Mafia",
        "q:aksi" to "Aksi",
        "q:balas dendam" to "Balas Dendam",
        "q:pernikahan" to "Pernikahan",
        "q:drama periode" to "Drama Periode",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isSearchCategory = request.data.startsWith("q:", true)
        if (page > 1 && !isSearchCategory) return newHomePageResponse(HomePageList(request.name, emptyList()), false)

        val (books, hasNext) = if (isSearchCategory) {
            fetchSearchPage(
                request.data.removePrefix("q:").trim(),
                limit = 20,
                offset = (page.coerceAtLeast(1) - 1) * 20
            )
        } else {
            (if (request.data == "trending") fetchTrending() else fetchLatest()) to false
        }

        val items = books.mapNotNull { b ->
            newTvSeriesSearchResponse(
                b.book_name ?: return@mapNotNull null,
                "$mainUrl/series/${b.book_id ?: return@mapNotNull null}",
                TvType.TvSeries
            ) {
                this.posterUrl = b.thumb_url
            }
        }

        return newHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchSearch(query, 20, 0).mapNotNull { b ->
            newTvSeriesSearchResponse(
                b.book_name ?: return@mapNotNull null,
                "$mainUrl/series/${b.book_id ?: return@mapNotNull null}",
                TvType.TvSeries
            ) {
                this.posterUrl = b.thumb_url
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = url.substringAfterLast("/").substringBefore("?").trim()
        val detail = fetchDetail(bookId)
        val seriesTitle = detail.series_title ?: "Melolo"
        val episodes = detail.video_list
            .filter { it.disable_play != true }
            .mapNotNull { ep ->
                newEpisode(
                    EpisodeData(
                        bookId = bookId,
                        seriesId = detail.series_id_str ?: bookId,
                        vid = ep.vid ?: return@mapNotNull null,
                        episode = ep.vid_index ?: return@mapNotNull null,
                        videoPlatform = detail.video_platform ?: 3,
                        seriesTitle = seriesTitle
                    ).toJson()
                ) {
                    this.name = "Episode ${ep.vid_index}"
                    this.posterUrl = ep.cover
                    this.episode = ep.vid_index
                }
            }.sortedBy { it.episode ?: Int.MAX_VALUE }

        return newTvSeriesLoadResponse(
            seriesTitle,
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

        if (emitFromCatalogProxy(ep, callback)) return true
        if (emitFromOfficialPlayerApi(ep, callback)) return true
        if (emitFromSplay(ep, subtitleCallback, callback)) return true

        logError("Melolo", "All playback strategies failed for book=${ep.bookId}, vid=${ep.vid}, episode=${ep.episode}")
        return false
    }

    private suspend fun emitFromCatalogProxy(
        ep: EpisodeData,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val proxyUrl = "$catalogBase/video/${ep.vid}"
            val proxyText = executeWithRetry {
                rateLimitDelay(moduleName = "Melolo")
                app.get(proxyUrl, timeout = 30L).text
            }
            logDebug("Melolo", "Proxy video[${ep.vid}] response: ${proxyText.take(300)}")

            val proxyResp = tryParseJson<PlayerVideoModelResponse>(proxyText)
            val proxyUrls = collectPlayerUrls(proxyResp)
            if (proxyUrls.isEmpty()) return false

            proxyUrls.forEachIndexed { index, videoUrl ->
                emitVideoUrl(
                    url = videoUrl,
                    label = if (index == 0) "Proxy" else "Proxy Backup",
                    referer = "$mainUrl/",
                    callback = callback
                )
            }
            true
        } catch (e: Exception) {
            logError("Melolo", "Proxy video fetch failed for vid=${ep.vid}", e)
            false
        }
    }

    private suspend fun emitFromOfficialPlayerApi(
        ep: EpisodeData,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val body = """
            {
              "series_id": "${ep.seriesId}",
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
                "ui_language": "id",
                "language": "id",
                "region": "ID",
                "current_region": "ID",
                "app_region": "ID",
                "sys_region": "ID",
                "carrier_region": "ID",
                "carrier_region_v2": "ID",
                "fake_priority_region": "ID",
                "time_zone": "Asia/Jakarta",
                "mcc_mnc": "51011"
              }
            }
        """.trimIndent()

        val responseText = try {
            executeWithRetry {
                rateLimitDelay(moduleName = "Melolo")
                app.post(
                    "$mainUrl/novel/player/video_model/v1/?aid=$aid",
                    requestBody = body.toRequestBody("application/json".toMediaType()),
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "X-Xs-From-Web" to "false",
                        "User-Agent" to "okhttp/4.9.3",
                        "Referer" to "$mainUrl/"
                    )
                ).text
            }
        } catch (e: Exception) {
            logError("Melolo", "Direct API request failed for vid=${ep.vid}", e)
            return false
        }

        logDebug("Melolo", "Direct API[${ep.vid}] response: ${responseText.take(500)}")

        val resp = tryParseJson<PlayerVideoModelResponse>(responseText)
        val videoUrls = collectPlayerUrls(resp)

        if (videoUrls.isEmpty()) {
            logError("Melolo", "No direct API video URLs returned for vid=${ep.vid}. Response: ${responseText.take(500)}")
            return false
        }

        videoUrls.forEachIndexed { index, videoUrl ->
            emitVideoUrl(
                url = videoUrl,
                label = if (index == 0) "Official" else "Official Backup",
                referer = "$mainUrl/",
                callback = callback
            )
        }
        return true
    }

    private suspend fun emitFromSplay(
        ep: EpisodeData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dramas = findSplayDramas(ep)
        if (dramas.isEmpty()) {
            logError("Melolo", "SPlay did not find matching drama for title=${ep.seriesTitle}, book=${ep.bookId}")
            return false
        }

        for (drama in dramas) {
            val dramaId = drama.id.asString()
            if (dramaId.isBlank()) continue
            val detail = fetchSplayDetail(dramaId)
            val detailEpisodes = detail?.data?.episodes.orEmpty()
            val episodePool = if (detailEpisodes.isNotEmpty()) {
                detailEpisodes
            } else {
                fetchSplayEpisodes(dramaId)
            }

            val target = chooseSplayEpisode(ep, episodePool) ?: continue
            val emitted = emitSplayEpisode(target, subtitleCallback, callback)
            if (emitted) {
                logDebug("Melolo", "SPlay emitted episode=${ep.episode} from drama=$dramaId (${drama.title})")
                return true
            }
        }

        return false
    }

    private suspend fun findSplayDramas(ep: EpisodeData): List<SplayDrama> {
        val candidates = linkedMapOf<String, SplayDrama>()

        suspend fun addFromUrl(url: String) {
            val text = try {
                executeWithRetry(maxRetries = 2) {
                    rateLimitDelay(moduleName = "Melolo")
                    app.get(
                        url,
                        headers = mapOf("Accept" to "application/json"),
                        timeout = 25L
                    ).text
                }
            } catch (_: Exception) {
                return
            }

            val resp = tryParseJson<SplayListResponse>(text) ?: return
            resp.data.forEach { drama ->
                val id = drama.id.asString()
                if (id.isNotBlank()) candidates[id] = drama
            }
        }

        val encodedBook = URLEncoder.encode(ep.bookId, "UTF-8")
        val title = ep.seriesTitle.orEmpty().trim()
        val encodedTitle = URLEncoder.encode(title, "UTF-8")

        addFromUrl("$splayBase/api/dramas?provider=melolo&language=id&external_id=$encodedBook&per_page=10")
        if (title.isNotBlank()) {
            addFromUrl("$splayBase/api/search?q=$encodedTitle&provider=melolo&language=id&per_page=10")
            addFromUrl("$splayBase/api/search?q=$encodedTitle&provider=melolo&per_page=10")
        }
        addFromUrl("$splayBase/api/search?q=$encodedBook&provider=melolo&per_page=10")

        return candidates.values
            .filter { it.provider_slug.equals("melolo", true) || it.provider_name.equals("Melolo", true) }
            .sortedWith(
                compareByDescending<SplayDrama> { it.external_id == ep.bookId }
                    .thenByDescending { normalized(it.title) == normalized(title) }
                    .thenBy { titleDistance(normalized(it.title), normalized(title)) }
            )
    }

    private suspend fun fetchSplayDetail(id: String): SplayDetailResponse? {
        val url = "$splayBase/api/dramas/$id?expires_in=14400"
        return try {
            val text = executeWithRetry(maxRetries = 2) {
                rateLimitDelay(moduleName = "Melolo")
                app.get(
                    url,
                    headers = mapOf("Accept" to "application/json"),
                    timeout = 25L
                ).text
            }
            tryParseJson<SplayDetailResponse>(text)
        } catch (e: Exception) {
            logError("Melolo", "SPlay detail failed for id=$id", e)
            null
        }
    }

    private suspend fun fetchSplayEpisodes(id: String): List<SplayEpisode> {
        val all = mutableListOf<SplayEpisode>()
        var page = 1
        while (page <= 5) {
            val url = "$splayBase/api/dramas/$id/episodes?page=$page&per_page=100&status=published&expires_in=14400"
            val resp = try {
                val text = executeWithRetry(maxRetries = 2) {
                    rateLimitDelay(moduleName = "Melolo")
                    app.get(
                        url,
                        headers = mapOf("Accept" to "application/json"),
                        timeout = 25L
                    ).text
                }
                tryParseJson<SplayEpisodeListResponse>(text)
            } catch (_: Exception) {
                null
            } ?: break

            all.addAll(resp.data)
            val meta = resp.meta
            if (meta?.total_pages == null || page >= meta.total_pages) break
            page++
        }
        return all
    }

    private fun chooseSplayEpisode(ep: EpisodeData, episodes: List<SplayEpisode>): SplayEpisode? {
        return episodes.firstOrNull { it.external_id == ep.vid }
            ?: episodes.firstOrNull { it.episode_index.asIntOrNull() == ep.episode }
            ?: episodes.minByOrNull { abs((it.episode_index.asIntOrNull() ?: Int.MAX_VALUE) - ep.episode) }
    }

    private suspend fun emitSplayEpisode(
        episode: SplayEpisode,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = 0

        episode.subtitles.orEmpty().forEach { subtitle ->
            val url = subtitle.url ?: return@forEach
            subtitleCallback(
                newSubtitleFile(
                    subtitle.lang?.ifBlank { "Subtitle" } ?: "Subtitle",
                    url
                )
            )
        }

        val directUrls = linkedMapOf<String, String>()
        episode.qualities.orEmpty().forEach { (quality, url) ->
            if (!url.isNullOrBlank()) directUrls[quality] = url
        }
        episode.video_url?.takeIf { it.isNotBlank() }?.let { directUrls.putIfAbsent("Auto", it) }

        directUrls.forEach { (qualityLabel, url) ->
            emitVideoUrl(
                url = url,
                label = "SPlay $qualityLabel",
                referer = splayBase,
                callback = callback
            )
            emitted++
        }

        return emitted > 0
    }

    private fun collectPlayerUrls(resp: PlayerVideoModelResponse?): List<String> {
        return listOfNotNull(
            resp?.data?.main_url,
            resp?.data?.backup_url,
            resp?.data?.play_url,
            resp?.data?.video_url
        ).flatMap { it.split("|", ",") }
            .map { it.trim() }
            .filter { it.startsWith("http", true) }
            .distinct()
    }

    private suspend fun emitVideoUrl(
        url: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = url.trim()
        if (!fixed.startsWith("http", true)) return

        callback(
            newExtractorLink(
                source = name,
                name = "Melolo $label",
                url = fixed,
                type = if (fixed.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.quality = getQualityFromName(label).takeIf { it != Qualities.Unknown.value }
                    ?: getQualityFromName(fixed).takeIf { it != Qualities.Unknown.value }
                    ?: Qualities.Unknown.value
                this.referer = referer
                this.headers = mapOf(
                    "User-Agent" to "okhttp/4.9.3",
                    "Accept" to "*/*"
                )
            }
        )
    }

    private suspend fun fetchLatest(): List<CatalogBook> = try {
        val res = executeWithRetry { app.get("$catalogBase/latest", timeout = 30L) }
        tryParseJson<CatalogLatestResponse>(res.text)?.books.orEmpty()
            .filter { it.language.equals("id", true) }
    } catch (e: Exception) {
        logError("Melolo", "fetchLatest failed", e)
        emptyList()
    }

    private suspend fun fetchTrending(): List<CatalogBook> = try {
        val res = executeWithRetry { app.get("$catalogBase/trending", timeout = 30L) }
        tryParseJson<CatalogTrendingResponse>(res.text)?.books.orEmpty()
            .filter { it.language.equals("id", true) }
    } catch (e: Exception) {
        logError("Melolo", "fetchTrending failed", e)
        emptyList()
    }

    private suspend fun fetchSearch(query: String, limit: Int, offset: Int): List<CatalogBook> = try {
        val url = "$catalogBase/search?query=${URLEncoder.encode(query, "UTF-8")}&limit=$limit&offset=$offset"
        val res = executeWithRetry { app.get(url, timeout = 30L) }
        tryParseJson<CatalogSearchResponse>(res.text)
            ?.data?.search_data?.flatMap { it.books }.orEmpty()
            .filter { it.language.equals("id", true) }
    } catch (e: Exception) {
        logError("Melolo", "fetchSearch failed for query=$query", e)
        emptyList()
    }

    private suspend fun fetchSearchPage(
        query: String, limit: Int, offset: Int
    ): Pair<List<CatalogBook>, Boolean> = try {
        val url = "$catalogBase/search?query=${URLEncoder.encode(query, "UTF-8")}&limit=$limit&offset=$offset"
        val res = executeWithRetry { app.get(url, timeout = 30L) }
        val resp = tryParseJson<CatalogSearchResponse>(res.text)
        val books = resp?.data?.search_data?.flatMap { it.books }.orEmpty()
            .filter { it.language.equals("id", true) }
        books to (resp?.data?.has_more == true)
    } catch (e: Exception) {
        logError("Melolo", "fetchSearchPage failed for query=$query", e)
        emptyList<CatalogBook>() to false
    }

    private suspend fun fetchDetail(bookId: String): CatalogVideoData {
        val res = executeWithRetry { app.get("$catalogBase/detail/$bookId", timeout = 30L) }
        return tryParseJson<CatalogDetailResponse>(res.text)?.data?.video_data
            ?: throw ErrorLoadingException("Detail tidak ditemukan untuk bookId=$bookId")
    }

    private fun normalized(value: String?): String {
        return value.orEmpty()
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun titleDistance(left: String, right: String): Int {
        if (left.isBlank() || right.isBlank()) return Int.MAX_VALUE
        if (left == right) return 0
        if (left.contains(right) || right.contains(left)) return 1
        return abs(left.length - right.length) + 5
    }

    private fun Any?.asString(): String {
        return when (this) {
            null -> ""
            is String -> this
            is Number -> this.toLong().toString()
            else -> this.toString()
        }
    }

    private fun Any?.asIntOrNull(): Int? {
        return when (this) {
            null -> null
            is Int -> this
            is Number -> this.toInt()
            is String -> this.toIntOrNull()
            else -> this.toString().toIntOrNull()
        }
    }

    // ---- Catalog proxy data classes ----

    data class CatalogLatestResponse(
        @JsonProperty("books") val books: List<CatalogBook> = emptyList()
    )

    data class CatalogTrendingResponse(
        @JsonProperty("books") val books: List<CatalogBook> = emptyList()
    )

    data class CatalogSearchResponse(
        @JsonProperty("data") val data: CatalogSearchData? = null
    )

    data class CatalogSearchData(
        @JsonProperty("has_more") val has_more: Boolean? = null,
        @JsonProperty("search_data") val search_data: List<CatalogSearchBlock> = emptyList()
    )

    data class CatalogSearchBlock(
        @JsonProperty("books") val books: List<CatalogBook> = emptyList()
    )

    data class CatalogBook(
        @JsonProperty("book_id") val book_id: String? = null,
        @JsonProperty("book_name") val book_name: String? = null,
        @JsonProperty("thumb_url") val thumb_url: String? = null,
        @JsonProperty("language") val language: String? = null
    )

    data class CatalogDetailResponse(
        @JsonProperty("data") val data: CatalogDetailData? = null
    )

    data class CatalogDetailData(
        @JsonProperty("video_data") val video_data: CatalogVideoData? = null
    )

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

    data class PlayerVideoModelResponse(
        @JsonProperty("data") val data: PlayerVideoModelData? = null
    )

    data class PlayerVideoModelData(
        @JsonProperty("main_url") val main_url: String? = null,
        @JsonProperty("backup_url") val backup_url: String? = null,
        @JsonProperty("play_url") val play_url: String? = null,
        @JsonProperty("video_url") val video_url: String? = null
    )

    data class EpisodeData(
        @JsonProperty("bookId") val bookId: String = "",
        @JsonProperty("seriesId") val seriesId: String = "",
        @JsonProperty("vid") val vid: String = "",
        @JsonProperty("episode") val episode: Int = 0,
        @JsonProperty("videoPlatform") val videoPlatform: Int = 3,
        @JsonProperty("seriesTitle") val seriesTitle: String? = null
    )

    // ---- SPlay fallback data classes ----

    data class SplayListResponse(
        @JsonProperty("data") val data: List<SplayDrama> = emptyList(),
        @JsonProperty("meta") val meta: SplayMeta? = null
    )

    data class SplayDetailResponse(
        @JsonProperty("data") val data: SplayDetailData? = null
    )

    data class SplayDetailData(
        @JsonProperty("drama") val drama: SplayDrama? = null,
        @JsonProperty("tags") val tags: List<SplayTag> = emptyList(),
        @JsonProperty("episodes") val episodes: List<SplayEpisode> = emptyList()
    )

    data class SplayEpisodeListResponse(
        @JsonProperty("data") val data: List<SplayEpisode> = emptyList(),
        @JsonProperty("meta") val meta: SplayMeta? = null
    )

    data class SplayMeta(
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("per_page") val per_page: Int? = null,
        @JsonProperty("total") val total: Int? = null,
        @JsonProperty("total_pages") val total_pages: Int? = null
    )

    data class SplayDrama(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("external_id") val external_id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cover_url") val cover_url: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("chapter_count") val chapter_count: Int? = null,
        @JsonProperty("provider_slug") val provider_slug: String? = null,
        @JsonProperty("provider_name") val provider_name: String? = null,
        @JsonProperty("language") val language: String? = null
    )

    data class SplayTag(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("en_name") val en_name: String? = null
    )

    data class SplayEpisode(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("drama_id") val drama_id: Any? = null,
        @JsonProperty("external_id") val external_id: String? = null,
        @JsonProperty("episode_index") val episode_index: Any? = null,
        @JsonProperty("episode_name") val episode_name: String? = null,
        @JsonProperty("video_url") val video_url: String? = null,
        @JsonProperty("subtitle_url") val subtitle_url: String? = null,
        @JsonProperty("subtitles") val subtitles: List<SplaySubtitle>? = null,
        @JsonProperty("qualities") val qualities: Map<String, String?>? = null,
        @JsonProperty("status") val status: String? = null
    )

    data class SplaySubtitle(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("url") val url: String? = null
    )
}
