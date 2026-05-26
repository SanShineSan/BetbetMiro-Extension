package com.oploverz

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder


class OploverzProvider : MainAPI() {
    override var mainUrl = "https://anime.oploverz.ac"
    private val backAPI = "https://backapi.oploverz.ac"
    override var name = "Oploverz🧚"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("Serial TV", true) -> TvType.Anime
                t.contains("OVA", true) -> TvType.OVA
                t.contains("Movie", true) || t.contains("BD", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }
        
        var context: android.content.Context? = null

        fun getStatus(t: String?): ShowStatus {
            return when {
                t?.contains("Berlangsung", true) == true -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "episodes:latest" to "Rilis Terbaru",
        "series:sort=latest" to "Anime Terbaru",
        "series:sort=popular" to "Sedang Trending",
        "series:sort=score" to "Rating Tertinggi",
        "series:status=Berlangsung" to "Ongoing",
        "series:status=Selesai" to "Completed",
        "series:type=Serial TV" to "Serial TV",
        "series:type=Movie" to "Movie",
        "series:type=OVA" to "OVA",
        "series:type=Live Action" to "Live Action",
        "series:genre=Action" to "Action",
        "series:genre=Adult Cast" to "Adult Cast",
        "series:genre=Adventure" to "Adventure",
        "series:genre=Cars" to "Cars",
        "series:genre=Childcare" to "Childcare",
        "series:genre=Comedy" to "Comedy",
        "series:genre=Delinquents" to "Delinquents",
        "series:genre=Demons" to "Demons",
        "series:genre=Detective" to "Detective",
        "series:genre=Donghua" to "Donghua",
        "series:genre=Drama" to "Drama",
        "series:genre=Ecchi" to "Ecchi",
        "series:genre=Fantasy" to "Fantasy",
        "series:genre=Gag Humor" to "Gag Humor",
        "series:genre=Game" to "Game",
        "series:genre=Gore" to "Gore",
        "series:genre=Gourmet" to "Gourmet",
        "series:genre=Harem" to "Harem",
        "series:genre=Historical" to "Historical",
        "series:genre=Horror" to "Horror",
        "series:genre=Infinite" to "Infinite",
        "series:genre=Isekai" to "Isekai",
        "series:genre=Josei" to "Josei",
        "series:genre=KlockWorx" to "KlockWorx",
        "series:genre=Live Action" to "Live Action",
        "series:genre=Magic" to "Magic",
        "series:genre=Martial Arts" to "Martial Arts",
        "series:genre=Mecha" to "Mecha",
        "series:genre=Medical" to "Medical",
        "series:genre=Military" to "Military",
        "series:genre=Movic" to "Movic",
        "series:genre=Music" to "Music",
        "series:genre=Mystery" to "Mystery",
        "series:genre=Mythology" to "Mythology",
        "series:genre=Organized Crime" to "Organized Crime",
        "series:genre=Otaku Culture" to "Otaku Culture",
        "series:genre=Parody" to "Parody",
        "series:genre=Performing Arts" to "Performing Arts",
        "series:genre=Pets" to "Pets",
        "series:genre=Police" to "Police",
        "series:genre=Psychological" to "Psychological",
        "series:genre=Racing" to "Racing",
        "series:genre=Reincarnation" to "Reincarnation",
        "series:genre=Reverse Harem" to "Reverse Harem",
        "series:genre=Romance" to "Romance",
        "series:genre=Romantic Subtext" to "Romantic Subtext",
        "series:genre=Samurai" to "Samurai",
        "series:genre=School" to "School",
        "series:genre=Sci-Fi" to "Sci-Fi",
        "series:genre=Seinen" to "Seinen",
        "series:genre=Shoujo" to "Shoujo",
        "series:genre=Shounen" to "Shounen",
        "series:genre=Showbiz" to "Showbiz",
        "series:genre=Showgate" to "Showgate",
        "series:genre=Slice of Life" to "Slice of Life",
        "series:genre=Sotsu" to "Sotsu",
        "series:genre=Space" to "Space",
        "series:genre=Sports" to "Sports",
        "series:genre=Strategy Game" to "Strategy Game",
        "series:genre=Super Power" to "Super Power",
        "series:genre=Supernatural" to "Supernatural",
        "series:genre=Survival" to "Survival",
        "series:genre=Suspense" to "Suspense",
        "series:genre=Team Sports" to "Team Sports",
        "series:genre=Thriller" to "Thriller",
        "series:genre=Time Travel" to "Time Travel",
        "series:genre=Urban Fantasy" to "Urban Fantasy",
        "series:genre=Vampire" to "Vampire",
        "series:genre=Video Game" to "Video Game",
        "series:genre=Villainess" to "Villainess",
        "series:genre=Warner Bros" to "Warner Bros",
        "series:genre=Workplace" to "Workplace",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val currentPage = page.coerceAtLeast(1)
        val data = request.data

        val home = when {
            data.startsWith("episodes:") -> {
                val sort = data.substringAfter("episodes:").ifBlank { "latest" }
                app.get("$backAPI/api/episodes?page=$currentPage&pageSize=24&sort=${sort.urlEncode()}")
                    .parsedSafe<Anime>()
                    ?.data
                    ?.map { it.toSearchResult() }
                    .orEmpty()
            }

            data.startsWith("series:") -> {
                val query = data.substringAfter("series:").toApiQuery()
                val separator = if (query.isBlank()) "" else "&"
                app.get("$backAPI/api/series?page=$currentPage&pageSize=24$separator$query")
                    .parsedSafe<SearchAnime>()
                    ?.data
                    ?.map { it.toSearchResult() }
                    .orEmpty()
            }

            else -> {
                app.get("$backAPI/api/episodes?page=$currentPage&pageSize=24&sort=${data.urlEncode()}")
                    .parsedSafe<Anime>()
                    ?.data
                    ?.map { it.toSearchResult() }
                    .orEmpty()
            }
        }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    private fun String.toApiQuery(): String {
        if (isBlank()) return ""

        return split("&")
            .mapNotNull { item ->
                val key = item.substringBefore("=").trim()
                val value = item.substringAfter("=", "").trim()
                if (key.isBlank()) return@mapNotNull null
                if (value.isBlank()) key else "$key=${value.urlEncode()}"
            }
            .joinToString("&")
    }

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, "UTF-8")
            .replace("+", "%20")
    }

    private fun Data.toSearchResult(): AnimeSearchResponse {
        return newAnimeSearchResponse(
            series?.title ?: "",
            "$mainUrl/series/${series?.slug}",
            TvType.Anime
        ) {
            this.otherName = series?.japaneseTitle
            this.posterUrl = series?.poster
            this.score = Score.from10(series?.score)
            addSub((episodeNumber?.toIntOrNull() ?: series?.totalEpisodes))
        }
    }

    private fun Series.toSearchResult(): AnimeSearchResponse {
        return newAnimeSearchResponse(
            title ?: "",
            "$mainUrl/series/${slug}",
            TvType.Anime
        ) {
            this.otherName = japaneseTitle
            this.posterUrl = poster
            this.score = Score.from10(score)
            addSub(totalEpisodes)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.get("$backAPI/api/series?q=$query")
            .parsedSafe<SearchAnime>()?.data?.map {
                newAnimeSearchResponse(
                    it.title ?: "",
                    "$mainUrl/series/${it.slug}",
                    TvType.Anime
                ) {
                    this.otherName = it.japaneseTitle
                    this.posterUrl = it.poster
                    this.score = Score.from10(it.score)
                    addSub(it.totalEpisodes)
                }
            }
    }

    private fun Document.selectList(selector: String): String {
        return this.select("ul.grid.list-inside li:contains($selector:)").text().substringAfter(":")
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).body.string().let { Jsoup.parse(it) }

        val title = document.selectFirst("p.text-2xl.font-semibold")?.text() ?: ""
        val poster = document.selectFirst("img.h-full.w-full")
            ?.attr("src")
        val tags = document.selectList("Genre").split(",")
            .map { it.trim() }

        val year = document.selectList("Tanggal Rilis").let {
            Regex("\\d{4}").find(it)?.groupValues?.get(0)?.toIntOrNull()
        }
        val status = getStatus(document.selectList("Status").trim())
        val type = getType(document.selectList("Tipe"))
        val description = document.select("div.flex.w-full p").text().trim()

        val episodes =
            document.select("a.ring-offset-background.gap-2").mapIndexedNotNull { index, element ->
                val episode =
                    element.select("p:first-child").text().filter { it.isDigit() }.toIntOrNull()
                        ?: (index + 1)
                val link = fixUrl(element.attr("href"))
                newEpisode(url = link, initializer = { this.episode = episode }, fix = false)
            }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover ?: poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        doc.select("div.flex.flex-row.items-start").amap { selector ->
            val quality = getQuality(selector.select("div.w-20 > p").text().trim())

            selector.select("div.flex.flex-row.flex-wrap > a").amap { server ->
                loadFixedExtractor(server.attr("href"), quality, null, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type
                    ) {
                        this.referer = link.referer
                        this.quality = quality ?: Qualities.Unknown.value
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun getQuality(quality: String) : Int {
        return when {
            quality.equals("Mini", false) -> Qualities.P480.value
            quality.equals("HD", false) -> Qualities.P720.value
            quality.equals("FHD", false) -> Qualities.P1080.value
            else -> {
                getQualityFromName(quality)
            }
        }
    }

    data class Sources(
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("url") val url: ArrayList<String>? = arrayListOf(),
    )

    data class Anime(
        @JsonProperty("data") val data: ArrayList<Data>? = arrayListOf(),
    )

    data class SearchAnime(
        @JsonProperty("data") val data: ArrayList<Series>? = arrayListOf(),
    )

    data class Data(
        @JsonProperty("episodeNumber") val episodeNumber: String? = null,
        @JsonProperty("subbed") val subbed: String? = null,
        @JsonProperty("series") val series: Series? = null,
    )

    data class Series(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("seriesId") val seriesId: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("japaneseTitle") val japaneseTitle: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("score") val score: Int? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
    )

}
