package com.Dramabox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

class Dramabox : MainAPI() {
    override var mainUrl = "https://www.dramabox.com/in"
    private val apiUrl = "https://db.hafizhibnusyam.my.id"
    override var name = "DramaBox👌"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/api/dramas/indo" to "Drama Dub Indo",
        "/api/dramas/trending" to "Trending",
        "/api/dramas/must-sees" to "Must Sees",
        "/api/dramas/hidden-gems" to "Hidden Gems",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = fetchDramaList(request.data, if (page < 1) 1 else page)
        val items = response?.data.orEmpty().mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(HomePageList(request.name, items, false), response?.meta?.pagination?.hasMore ?: items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val url = "$apiUrl/api/search?keyword=${URLEncoder.encode(keyword, "UTF-8")}&page=1&size=50"
        val body = executeWithRetry {
            rateLimitDelay(moduleName = "Dramabox")
            app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
        }

        val response = tryParseJson<DramaListResponse>(body)
        return response?.data.orEmpty().mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val dramaId = url.substringAfterLast("_").substringBefore("?").trim()
        // fetchDramaDetail now returns DramaItem? directly (handles both API response formats)
        val drama = fetchDramaDetail(dramaId) ?: throw ErrorLoadingException("Drama tidak ditemukan")
        val episodeCount = drama.episodeCount ?: inferEpisodeCount(dramaId)
        if (episodeCount <= 0) throw ErrorLoadingException("Episode tidak ditemukan")

        val episodes = (1..episodeCount).map { ep ->
            newEpisode(LoadData(bookId = dramaId, episodeNo = ep).toJson()) {
                this.name = "Episode $ep"
                this.episode = ep
                this.posterUrl = drama.coverImage
            }
        }

        return newTvSeriesLoadResponse(cleanTitle(drama.title ?: "DramaBox"), "$mainUrl/drama/_$dramaId", TvType.AsianDrama, episodes) {
            this.posterUrl = drama.coverImage
            this.plot = drama.introduction
            this.tags = drama.tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = parseJson<LoadData>(data)
        val dramaId = parsed.bookId ?: return false
        val episodeNo = parsed.episodeNo ?: return false

        val chapter = fetchChapterForEpisode(dramaId, episodeNo)
        val streams = chapter?.streamUrl.orEmpty()
            .filter { it.url?.isNotBlank() == true }
            .distinctBy { it.url }
            .sortedByDescending { it.quality ?: 0 }

        if (streams.isEmpty()) return false

        streams.forEach { s ->
            callback.invoke(
                newExtractorLink(
                    name,
                    "DramaBox ${s.quality?.let { "${it}p" } ?: "Auto"}",
                    s.url!!,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = s.quality ?: Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                }
            )
        }
        return true
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("\\((Sulih Suara|Dub Indo|Indonesian Sub|Sub Indo)\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    private fun DramaItem.toSearchResult(): SearchResponse? {
        val dramaId = id?.trim() ?: return null
        return newTvSeriesSearchResponse(cleanTitle(title ?: ""), "$mainUrl/drama/_$dramaId", TvType.AsianDrama) {
            this.posterUrl = coverImage
        }
    }

    private suspend fun fetchDramaList(path: String, page: Int): DramaListResponse? {
        return try {
            val url = "${if (path.startsWith("http")) path else "$apiUrl$path"}${if (path.contains("?")) "&" else "?"}page=$page"
            val body = executeWithRetry {
                rateLimitDelay(moduleName = "Dramabox")
                app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
            }
            tryParseJson<DramaListResponse>(body)
        } catch (e: Exception) {
            // FIX #5: Log errors instead of silently swallowing them
            logError("Dramabox", "fetchDramaList failed for path=$path page=$page", e)
            null
        }
    }

    // Returns DramaItem? directly instead of DramaDetailResponse?.
    // FIX: API sometimes returns {"data": {...}} and sometimes just {...} without wrapper.
    // Previously the code only tried the wrapped format — if the API returned the object
    // directly, data would be null and "Drama tidak ditemukan" was always thrown.
    // Now we try both formats: wrapped first, then unwrapped as fallback.
    private suspend fun fetchDramaDetail(dramaId: String): DramaItem? {
        return try {
            val url = "$apiUrl/api/dramas/$dramaId"
            val body = executeWithRetry {
                rateLimitDelay(moduleName = "Dramabox")
                app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
            }
            // Try wrapped format {"data": {...}} first
            tryParseJson<DramaDetailResponse>(body)?.data
                // Fall back to direct object format {...}
                ?: tryParseJson<DramaItem>(body)?.takeIf { it.id != null }
        } catch (e: Exception) {
            logError("Dramabox", "fetchDramaDetail failed for id=$dramaId", e)
            null
        }
    }

    // FIX #1: Changed app.post() to app.get().
    // The endpoint uses query string parameters (?book_id=...&episode=...),
    // which is a GET-style API. Using POST with no body was returning
    // 405 Method Not Allowed / empty response, causing all video loading to fail.
    private suspend fun fetchChapterForEpisode(dramaId: String, episodeNo: Int): ChapterContent? {
        return try {
            val url = "$apiUrl/api/chapters/video?book_id=$dramaId&episode=$episodeNo"
            val body = executeWithRetry {
                rateLimitDelay(moduleName = "Dramabox")
                app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
            }
            val res = tryParseJson<ChapterResponse>(body) ?: return null
            (res.data.orEmpty() + res.extras.orEmpty()).firstOrNull { it.chapterIndex?.toIntOrNull() == episodeNo }
        } catch (e: Exception) {
            logError("Dramabox", "fetchChapterForEpisode failed id=$dramaId ep=$episodeNo", e)
            null
        }
    }

    // FIX #1 (same): inferEpisodeCount also used app.post() on the same endpoint.
    private suspend fun inferEpisodeCount(dramaId: String): Int {
        return try {
            val url = "$apiUrl/api/chapters/video?book_id=$dramaId&episode=1"
            val body = executeWithRetry {
                rateLimitDelay(moduleName = "Dramabox")
                app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
            }
            val res = tryParseJson<ChapterResponse>(body) ?: return 0
            (res.data.orEmpty() + res.extras.orEmpty()).mapNotNull { it.chapterIndex?.toIntOrNull() }.maxOrNull() ?: 0
        } catch (e: Exception) {
            logError("Dramabox", "inferEpisodeCount failed for id=$dramaId", e)
            0
        }
    }

    data class DramaListResponse(@JsonProperty("data") val data: List<DramaItem>? = null, @JsonProperty("meta") val meta: ResponseMeta? = null)
    data class DramaDetailResponse(@JsonProperty("data") val data: DramaItem? = null)
    data class DramaItem(@JsonProperty("id") val id: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("cover_image") val coverImage: String? = null, @JsonProperty("introduction") val introduction: String? = null, @JsonProperty("tags") val tags: List<String>? = null, @JsonProperty("episode_count") val episodeCount: Int? = null)
    data class ResponseMeta(@JsonProperty("pagination") val pagination: Pagination? = null)
    data class Pagination(@JsonProperty("has_more") val hasMore: Boolean? = null)
    data class ChapterResponse(@JsonProperty("data") val data: List<ChapterContent>? = null, @JsonProperty("extras") val extras: List<ChapterContent>? = null)
    data class ChapterContent(@JsonProperty("chapter_index") val chapterIndex: String? = null, @JsonProperty("stream_url") val streamUrl: List<StreamItem>? = null)
    data class StreamItem(@JsonProperty("quality") val quality: Int? = null, @JsonProperty("url") val url: String? = null)
    data class LoadData(@JsonProperty("bookId") val bookId: String? = null, @JsonProperty("episodeNo") val episodeNo: Int? = null)
}
