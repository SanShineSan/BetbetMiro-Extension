package com.gomunime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class Gomunime : MainAPI() {

    override var mainUrl = "https://gomunime.top"
    override var name = "Gomunime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update&page=" to "Terbaru",
        "anime/?status=ongoing&type=&order=update&page=" to "Ongoing",
        "anime/?status=completed&type=&order=update&page=" to "Completed",
        "anime/?status=&type=&order=popular&page=" to "Popular",
        "genres/action/page/" to "Action",
        "genres/fantasy/page/" to "Fantasy",
        "genres/isekai/page/" to "Isekai"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = when {
            request.data.contains("genres/") -> {
                "$mainUrl/${request.data}$page/"
            }
            else -> {
                "$mainUrl/${request.data}$page"
            }
        }

        val document = app.get(url).document

        val home = document.select(
            """
            div.listupd article,
            div.listupd div.bs,
            article.bs,
            .bsx,
            .listupd .bs
            """.trimIndent()
        ).mapNotNull {
            it.toSearchResult()
        }

        // FIX PARAMETER: Menggunakan format standar agar anti-crash di Cloudstream Prerelease
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val title = selectFirst(
            """
            div.tt,
            div.tt h2,
            h2,
            .entry-title
            """.trimIndent()
        )?.text()?.trim() ?: return null

        val href = fixUrl(
            selectFirst("a")?.attr("href") ?: return null
        )

        val poster = fixUrlNull(
            selectFirst("img")?.let { img ->
                when {
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("data-lazy-src") -> img.attr("data-lazy-src")
                    img.hasAttr("src") -> img.attr("src")
                    else -> null
                }
            }
        )

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val document = app.get(
            "$mainUrl/?s=$query"
        ).document

        return document.select(
            """
            div.listupd article,
            div.listupd div.bs,
            article.bs,
            .bsx,
            .listupd .bs
            """.trimIndent()
        ).mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document

        val title = document.selectFirst(
            """
            h1.entry-title,
            .entry-title,
            h1
            """.trimIndent()
        )?.text()
            ?.replace("Subtitle Indonesia", "")
            ?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst(
                """
                div.thumb img,
                .thumb img,
                .poster img
                """.trimIndent()
            )?.let { img ->
                when {
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("data-lazy-src") -> img.attr("data-lazy-src")
                    img.hasAttr("src") -> img.attr("src")
                    else -> null
                }
            }
        )

        val type = getType(
            document.selectFirst(".spe")?.text()
        )

        val episodes = document.getEpisodes(url)

        return if (type == TvType.AnimeMovie) {

            newMovieLoadResponse(
                title,
                url,
                TvType.AnimeMovie,
                url
            ) {
                this.posterUrl = poster
            }

        } else {

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.Anime,
                episodes
            ) {
                this.posterUrl = poster

                this.showStatus = getStatus(
                    document.selectFirst(".spe")?.text()
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCaster: Boolean, // FIX SAKTI: Diubah dari isCasting ke isCaster agar lolos kompilasi!
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return loadGomunimeLinks(
            data,
            subtitleCallback,
            callback
        )
    }

    private fun Document.getEpisodes(
        url: String
    ): List<Episode> {

        val slug = runCatching {
            URI(url).path
                .trim('/')
                .substringAfter("anime/")
        }.getOrNull() ?: return emptyList()

        return select(
            """
            a[href*="$slug-episode-"],
            div.eplister ul li a,
            ul.episodios li a
            """.trimIndent()
        ).mapNotNull { a ->

            val href = fixUrlNull(
                a.attr("href")
            ) ?: return@mapNotNull null

            val epNum = Regex(
                """episode-(\d+)""",
                RegexOption.IGNORE_CASE
            ).find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            newEpisode(href) {
                this.episode = epNum
                this.name = "Episode ${epNum ?: "?"}"
            }
        }.distinctBy {
            it.url
        }.sortedBy {
            it.episode
        }
    }

    private fun getType(text: String?): TvType {
        val value = text ?: return TvType.Anime

        return when {
            value.contains("movie", true) -> TvType.AnimeMovie
            value.contains("ova", true) -> TvType.OVA
            value.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getStatus(text: String?): ShowStatus {
        val value = text ?: return ShowStatus.Completed

        return when {
            value.contains("ongoing", true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private fun fixUrlNull(url: String?): String? {
        return url?.let { fixUrl(it) }
    }
}