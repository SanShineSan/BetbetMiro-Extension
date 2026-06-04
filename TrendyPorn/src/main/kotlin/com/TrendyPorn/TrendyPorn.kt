package com.megix

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TrendyPorn : MainAPI() {
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private val videoHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
        "Referer" to "https://www.trendyporn.com/"
    )

    override var mainUrl              = "https://www.trendyporn.com"
    override var name                 = "TrendyPorn"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/most-recent/" to "Most Recent",
        "$mainUrl/most-viewed/day/" to "Most Viewed(Day)",
        "$mainUrl/tag/onlyfans/" to "OnlyFans",
        "$mainUrl/most-viewed/week/" to "Most Viewed(Week)",
        "$mainUrl/most-viewed/month/" to "Most Viewed(Month)",
        "$mainUrl/most-viewed/" to "Most Viewed(All Time)",
        "$mainUrl/random/" to "Random",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + "page" + page + ".html").document
        val home = document.select("#wrapper > div.container > div:nth-child(4) > div div.well-sm").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("a").attr("title")
        val href = this.select("a").attr("href")
        val posterUrl = this.select("img").attr("data-original")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.get("${mainUrl}/search/${query}/page${page}.html").document
        val results = document.select("#wrapper > div.container > div:nth-child(4) > div div.well-sm").mapNotNull { it.toSearchResult() }
        val hasNext = if(results.isEmpty()) false else true
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.select("meta[property=og:title]").attr("content")
        val posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content")) ?:""

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val response = app.get(data, referer = "$mainUrl/", headers = browserHeaders)
        val document = response.document
        val html = response.text
        val emitted = linkedSetOf<String>()

        suspend fun emitVideo(rawUrl: String?, label: String? = null) {
            val link = rawUrl
                ?.replace("\\/", "/")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return

            val fixedLink = fixUrl(link)
            if (!fixedLink.startsWith("http", true)) return
            if (!fixedLink.contains(".mp4", true) && !fixedLink.contains(".webm", true)) return
            if (fixedLink.contains("images.trendyporn.com", true) || fixedLink.contains("/thumbs/", true)) return
            if (!emitted.add(fixedLink)) return

            val quality = label?.let { getQualityFromName(it) } ?: Qualities.Unknown.value
            val displayName = label?.takeIf { it.isNotBlank() }?.let { "$name $it" } ?: name

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = displayName,
                    url = fixedLink,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = videoHeaders
                    this.quality = quality
                }
            )
        }

        document.select("video source[src], video[src], source[src]").forEach { element ->
            val label = element.attr("label").ifBlank { element.attr("res") }.ifBlank { element.attr("type") }
            emitVideo(element.attr("src"), label)
        }

        Regex("""(?i)(https?:\/\/[^\s"'<>]+?\.(?:mp4|webm)(?:\?[^\s"'<>]*)?)""").findAll(html).forEach { match ->
            emitVideo(match.groupValues[1])
        }

        document.select("track[kind=subtitles][src], track[kind=captions][src]").forEach { track ->
            val subtitleUrl = fixUrlNull(track.attr("src")) ?: return@forEach
            if (subtitleUrl.endsWith(".vtt", true) || subtitleUrl.endsWith(".srt", true)) {
                subtitleCallback.invoke(SubtitleFile(track.attr("srclang").ifBlank { "Unknown" }, subtitleUrl))
            }
        }

        return emitted.isNotEmpty()
    }
}
