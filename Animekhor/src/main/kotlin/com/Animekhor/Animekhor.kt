package com.Animekhor

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class Animekhor : MainAPI() {

    override var mainUrl = "https://animekhor.org"
    override var name = "Animekhor"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&type=&order=update" to "Recently Updated",
        "anime/?type=comic&order=update" to "Comic Recently Updated",
        "anime/?type=comic" to "Comic Series",
        "anime/?status=&type=ona&sub=&order=update" to "Donghua Recently Updated",
        "anime/?status=&type=ona" to "Donghua Series",
        "anime/?status=&sub=&order=latest" to "Latest Added",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?status=completed&order=update" to "Completed"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(
            "$mainUrl/${request.data}&page=$page"
        ).document

        val home = document.select("div.listupd > article")
            .mapNotNull { it.toSearchResult() }

        val hasNext = document.select("li.next a").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("div.bsx > a").attr("title")
        val href = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("div.bsx > a img")?.getsrcAttribute())

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..3) {
            val document = app.get("${mainUrl}/page/$i/?s=$query").document
            val results = document.select("div.listupd > article")
                .mapNotNull { it.toSearchResult() }

            if (results.isEmpty()) break

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().toString()
        val href = document.selectFirst(".eplister li > a")?.attr("href") ?: ""
        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().toString()

        val tvtag = if (type.contains("Movie")) TvType.Movie else TvType.TvSeries

        return if (tvtag == TvType.TvSeries) {

            if (href.isEmpty()) return newMovieLoadResponse(title, url, TvType.Movie, "")

            val doc = app.get(href).document
            val epPoster = doc.select("meta[property=og:image]").attr("content")

            val episodes = doc.select("div.episodelist > ul > li").map { info ->

                val href1 = info.select("a").attr("href")
                val epText = info.select("a span").text()
                val episode = epText.substringAfter("-", epText).substringBeforeLast("-", epText)

                newEpisode(href1) {
                    this.name = episode
                    this.posterUrl = epPoster
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }

        } else {

            val finalPoster = if (poster.isEmpty()) {
                document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
            } else poster

            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = finalPoster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        val servers = document.select(".mobius option, #mobius option, select option")

        servers.forEach { server ->

            val base64 = server.attr("value")
            if (base64.isBlank()) return@forEach

            try {
                val decoded = base64Decode(base64)
                val patterns = listOf(
                    Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                    Regex("""iframe.+?src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                    Regex("""file["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                    Regex("""data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                )

                var extractedUrl: String? = null
                for (regex in patterns) {
                    val match = regex.find(decoded)
                    if (match != null) {
                        extractedUrl = match.groupValues[1]
                        break
                    }
                }

                if (extractedUrl.isNullOrBlank()) return@forEach

                var fixedUrl = extractedUrl.trim()
                if (fixedUrl.startsWith("//")) fixedUrl = httpsify(fixedUrl)
                if (!fixedUrl.startsWith("http")) return@forEach

                Log.d("Animekhor", fixedUrl)

                loadExtractor(fixedUrl, referer = mainUrl, subtitleCallback = subtitleCallback, callback = callback)

            } catch (e: Exception) {
                Log.e("Animekhor", "Failed to decode server link: ${e.message}")
            }
        }

        return true
    }

    private fun Element.getsrcAttribute(): String {
        val src = this.attr("src")
        val dataSrc = this.attr("data-src")
        return src.takeIf { it.startsWith("http") } ?: dataSrc.takeIf { it.startsWith("http") } ?: ""
    }
}