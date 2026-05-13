package com.oppadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URI

class OppadramaProvider : MainAPI() {

    override var mainUrl = "http://45.11.57.199"
    override var name = "OppaDrama"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ---------------- MAIN PAGE ----------------
    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update" to "Latest Update",
        "series/?country[]=south-korea&type=Drama&order=update" to "Korean Drama",
        "series/?country[]=japan&type=Drama&order=update" to "Japanese Drama",
        "series/?country[]=china&type=Drama&order=update" to "Chinese Drama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val url = "$mainUrl/${request.data}&page=$page"
        val doc = app.get(url).document

        val items = doc.select("div.listupd article.bs, article, .bs").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ---------------- SEARCH ----------------
    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.get("$mainUrl/?s=$query").document

        return doc.select("div.listupd article.bs, article, .bs")
            .mapNotNull { it.toSearchResult() }
    }

    // ---------------- SEARCH PARSER ----------------
    private fun Element.toSearchResult(): SearchResponse? {

        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))

        val title = a.attr("title").ifBlank {
            this.selectFirst(".tt, h2, h3")?.text()
        } ?: return null

        val poster = this.selectFirst("img")?.attr("src")

        val isSeries = href.contains("/series") || href.contains("episode")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ---------------- LOAD ----------------
    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title = doc.selectFirst("h1, h1.entry-title")?.text().orEmpty()
        val poster = doc.selectFirst("img")?.attr("src")
        val plot = doc.select("div.entry-content p").text()

        val episodes = doc.select("div.eplister ul li a, .eplister a")
            .mapIndexedNotNull { index, a ->
                val link = fixUrl(a.attr("href"))
                newEpisode(link) {
                    this.name = "Episode ${index + 1}"
                    this.episode = index + 1
                }
            }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // ---------------- LOAD LINKS (FIX UTAMA) ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        // 1. iframe langsung (primary player)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // 2. embed container fallback
        doc.select("div.player-embed iframe, .metaframe, .responsive-embed iframe")
            .forEach {
                val src = it.attr("src")
                if (src.isNotBlank()) {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }

        // 3. mirror base64 fallback (kalau ada)
        doc.select("select.mirror option[value]").forEach { opt ->
            try {
                val decoded = base64Decode(opt.attr("value"))
                val iframe = Jsoup.parse(decoded).selectFirst("iframe")
                val src = iframe?.attr("src")

                if (!src.isNullOrBlank()) {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }

        // 4. download links fallback
        doc.select("div.dlbox a[href]").forEach {
            val url = it.attr("href")
            if (url.isNotBlank()) {
                loadExtractor(url, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // ---------------- HELPERS ----------------
    private fun String.toUriHost(): String {
        return try {
            val u = URI(this)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            mainUrl
        }
    }
}