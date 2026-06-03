package com.nomat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Nomat : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://nomat.asia"
    override var name = "Nomat"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "slug/film-terbaru" to "Film Terbaru",
        "slug/film-terfavorit" to "Film Terfavorit",
        "slug/film-box-office" to "Box Office",
        "slug/semua-film" to "Semua Film",

        "category/genre/action" to "Action",
        "category/genre/horror" to "Horror",
        "category/genre/sci-fi" to "Sci-Fi",
        "category/genre/romance" to "Romance",
        "category/genre/history" to "History",
        "category/genre/animasi" to "Animation",
        "category/genre/serial-tv" to "Serial",

        "category/country/indonesia" to "Indonesia",
        "category/country/japan" to "Japan",
        "category/country/korea" to "Korea",
        "category/country/china" to "China",
        "category/country/thailand" to "Thailand",
        "category/country/usa" to "USA",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val cleanPath = request.data.trim('/')
        val url = if (page <= 1) {
            "$mainUrl/$cleanPath/"
        } else {
            "$mainUrl/$cleanPath/page/$page/"
        }

        val document = app.get(url).document
        val home = document.select(".section .body a[href]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        if (selectFirst(".item") == null) return null

        val title = selectFirst(".title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = attr("abs:href").ifBlank { attr("href") }.takeIf { it.isNotBlank() } ?: return null
        val posterUrl = selectFirst(".poster")?.getBackgroundImage()
            ?: selectFirst("img")?.getImageAttr()
        val isTvSeries = href.contains("/series/") ||
            href.contains("/tv/") ||
            href.contains("/serial", true) ||
            selectFirst(".qual.eps") != null

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val document = app.get("$mainUrl/search/$encoded/").document

        return document.select(".section .body a[href]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(".video-title h1")?.text()
            ?: document.select("h1").firstOrNull { !it.text().contains("NOMAT:", true) }?.text()
            ?: document.selectFirst("title")?.text()?.substringBefore(" Subtitle Indonesia")?.substringAfter("Nonton ")
            ?: return null

        val poster = document.selectFirst(".video-poster")?.getBackgroundImage()
        val tags = document.select(".video-genre a").map { it.text().trim() }.filter { it.isNotBlank() }
        val actors = document.select(".video-actor a").map { it.text().trim() }.filter { it.isNotBlank() }
        val plot = document.selectFirst(".video-synopsis")?.text()
            ?.substringAfter("Sinopsis:", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val rating = document.selectFirst(".video-rating")?.text()
            ?.substringAfter("Rating:")
            ?.substringBefore("/")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "?" }
        val recommendations = document.select(".section .body a[href]")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        val trailerId = document.selectFirst("amp-youtube[data-videoid]")?.attr("data-videoid")
            ?.takeIf { it.isNotBlank() }

        val episodes = document.select(".video-episodes a[href]")
            .mapIndexedNotNull { index, element ->
                val episodeUrl = element.attr("abs:href").ifBlank { element.attr("href") }
                    .takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null

                val episodeName = element.text().trim().ifBlank { "Episode ${index + 1}" }
                val episodeNumber = Regex("""\d+""").find(episodeName)?.value?.toIntOrNull() ?: (index + 1)

                newEpisode(fixUrl(episodeUrl)) {
                    this.name = episodeName
                    this.episode = episodeNumber
                }
            }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addScore(rating)
                if (trailerId != null) addTrailer("https://www.youtube.com/watch?v=$trailerId")
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addScore(rating)
                if (trailerId != null) addTrailer("https://www.youtube.com/watch?v=$trailerId")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = 0

        val playerPages = runCatching {
            if (data.contains("nontonhemat.link")) {
                listOf(data)
            } else {
                val document = app.get(data, referer = mainUrl).document
                document.select("a[href*=\"nontonhemat.link\"]")
                    .mapNotNull { it.attr("abs:href").ifBlank { it.attr("href") }.takeIf { it.isNotBlank() } }
                    .distinct()
                    .take(2)
            }
        }.getOrElse {
            logError(it)
            emptyList()
        }

        for (playerPage in playerPages) {
            val playerDoc = try {
                app.get(playerPage, referer = data).document
            } catch (t: Throwable) {
                logError(t)
                continue
            }

            val playerLinks = playerDoc.select(".server-item[data-url]")
                .mapNotNull { it.attr("data-url").decodeBase64Url() }
                .filter(::isSupportedPlayer)
                .distinct()
                .take(6)

            for (link in playerLinks) {
                runCatching {
                    loadExtractor(link, playerPage, subtitleCallback) {
                        emitted++
                        callback(it)
                    }
                }.onFailure { logError(it) }

                if (emitted > 0) return true
            }
        }

        return emitted > 0
    }

    private fun isSupportedPlayer(url: String): Boolean {
        return listOf(
            "filelions.to",
            "filemoon.sx",
            "streamwish.to",
            "streamhide.to",
            "playhydrax.com",
            "vidhide",
        ).any { url.contains(it, true) }
    }

    private fun String.decodeBase64Url(): String? {
        return runCatching { base64Decode(this).trim() }
            .getOrNull()
            ?.takeIf { it.startsWith("http") }
    }

    private fun Element.getBackgroundImage(): String? {
        return Regex("""url\(['"]?([^'")]+)""")
            .find(attr("style"))
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }.takeIf { it.isNotBlank() }
    }
}
