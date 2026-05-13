package com.Melolo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject
import java.net.URLEncoder

class Melolo : MainAPI() {

    override var mainUrl = "https://melolo-api-azure.vercel.app/api/melolo"
    override var name = "Melolo"
    override var lang = "id"

    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "latest" to "Terbaru",
        "trending" to "Trending"
    )

    // ---------------- MAIN PAGE ----------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val url = when (request.data) {
            "trending" -> "$mainUrl/trending"
            else -> "$mainUrl/latest"
        }

        val json = JSONObject(app.get(url).text)
        val books = json.optJSONArray("books") ?: return newHomePageResponse(request.name, emptyList())

        val items = (0 until books.length()).mapNotNull { i ->
            val b = books.getJSONObject(i)

            val id = b.optString("book_id")
            val title = b.optString("book_name")
            val poster = b.optString("thumb_url")

            if (id.isBlank()) return@mapNotNull null

            newTvSeriesSearchResponse(
                title,
                "$mainUrl/detail/$id",
                TvType.TvSeries
            ) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, items)
    }

    // ---------------- SEARCH ----------------
    override suspend fun search(query: String): List<SearchResponse> {

        val url = "$mainUrl/search?query=${URLEncoder.encode(query, "UTF-8")}&limit=20&offset=0"
        val json = JSONObject(app.get(url).text)

        val data = json.optJSONObject("data") ?: return emptyList()
        val blocks = data.optJSONArray("search_data") ?: return emptyList()

        val results = mutableListOf<SearchResponse>()

        for (i in 0 until blocks.length()) {
            val block = blocks.getJSONObject(i)
            val books = block.optJSONArray("books") ?: continue

            for (j in 0 until books.length()) {
                val b = books.getJSONObject(j)

                val id = b.optString("book_id")
                val title = b.optString("book_name")
                val poster = b.optString("thumb_url")

                if (id.isBlank()) continue

                results.add(
                    newTvSeriesSearchResponse(
                        title,
                        "$mainUrl/detail/$id",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        return results
    }

    // ---------------- LOAD DETAIL ----------------
    override suspend fun load(url: String): LoadResponse {

        val id = url.substringAfterLast("/")

        val json = JSONObject(app.get("$mainUrl/detail/$id").text)

        val data = json.optJSONObject("data")
            ?.optJSONObject("video_data")
            ?: throw ErrorLoadingException("No video data")

        val title = data.optString("series_title")
        val poster = data.optString("series_cover")
        val plot = data.optString("series_intro")

        val videoList = data.optJSONArray("video_list")
            ?: return throw ErrorLoadingException("No episodes")

        val episodes = (0 until videoList.length()).mapNotNull { i ->
            val ep = videoList.getJSONObject(i)

            val vid = ep.optString("vid")
            val index = ep.optInt("vid_index")

            if (vid.isBlank()) return@mapNotNull null

            newEpisode(vid) {
                this.name = "Episode $index"
                this.episode = index
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------- LOAD LINKS ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // data = vid (dari Episode)
        val vid = data

        // ⚠️ IMPORTANT:
        // API ini biasanya tidak langsung kasih mp4/m3u8
        // jadi kita coba resolve dari endpoint detail episode

        val json = JSONObject(
            app.get("$mainUrl/detail/$vid").text
        )

        val videoData = json.optJSONObject("data")
            ?.optJSONObject("video_data")

        val list = videoData?.optJSONArray("video_list")

        if (list != null) {
            for (i in 0 until list.length()) {
                val ep = list.getJSONObject(i)

                val playUrl = ep.optString("play_url")

                if (playUrl.isNotBlank()) {
                    callback(
                        newExtractorLink(
                            name,
                            "Melolo",
                            playUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = mainUrl
                        }
                    )
                }
            }
        }

        return true
    }
}