package com.kraptor

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FreeUsePorn : MainAPI() {
    override var mainUrl = "https://www.freeuseporn.com"
    override var name = "FreeUsePorn"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "videos" to "Video Terbaru",

        "videos/general-freeuse" to "General Freeuse",
        "videos/free-service" to "Free Service",
        "videos/mind-control" to "Mind Control",
        "videos/forced" to "Forced",
        "videos/japanese" to "Japanese",
        "videos/time-stop" to "Time Stop",
        "videos/ignored-sex" to "Ignored Sex",
        "videos/glory-hole" to "Glory Hole",

        "videos/hypno" to "Hypno",
        "videos/public-freeuse" to "Public Freeuse",
        "videos/office-freeuse" to "Office Freeuse",
        "videos/maid" to "Maid",
        "videos/creampie" to "Creampie",
        "videos/cosplay" to "Cosplay",
        "videos/amateur" to "Amateur",
        "videos/compilation" to "Compilation"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            fixUrl(request.data)
        } else {
            "${fixUrl(request.data)}?page=$page"
        }

        val document = app.get(url).document

        val home = document.select(
            "div#videos-list a.group[href], " +
                "a.group[href], " +
                "div.related-video a[href]"
        ).mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(
                "a[rel=next], " +
                    "a:contains(Next), " +
                    "button:contains(Next), " +
                    ".pagination a[href*='page=${page + 1}']"
            ) != null || home.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/search/videos/$q?page=${page.coerceAtLeast(1)}"
        val document = app.get(searchUrl).document

        val results = document.select(
            "div#videos-list a.group[href], " +
                "a.group[href], " +
                "div.related-video a[href]"
        ).mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = document.selectFirst(
                "a[rel=next], " +
                    "a:contains(Next), " +
                    ".pagination a[href*='page=${page + 1}']"
            ) != null
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1).items
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(
            "h1, " +
                "meta[property=og:title]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "video[poster], " +
                    "img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    element.hasAttr("poster") -> element.attr("poster")
                    element.hasAttr("data-src") -> element.attr("data-src")
                    element.hasAttr("data-lazy-src") -> element.attr("data-lazy-src")
                    else -> element.attr("src")
                }
            }
        )

        val description = document.selectFirst(
            "meta[property=og:description], " +
                "div.prose, " +
                "div.description, " +
                "p"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.trim()
            ?.takeIf { it.isNotBlank() }

        val tags = document.select(
            "a[href*='/search/videos/'], " +
                "a[href*='/videos/'], " +
                ".tags a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select(
            "div.related-video, " +
                "div#videos-list a.group[href], " +
                "a.group[href]"
        ).mapNotNull { element ->
            when {
                element.`is`("a") -> element.toMainPageResult()
                else -> {
                    val anchor = element.selectFirst("a[href]") ?: return@mapNotNull null
                    val recTitle = element.selectFirst("span.text-sm.font-bold, h3, .font-bold")
                        ?.text()
                        ?.trim()
                        ?: anchor.attr("title").trim()

                    if (recTitle.isBlank()) return@mapNotNull null

                    val recHref = fixUrl(anchor.attr("href"))
                    val recPoster = fixUrlNull(element.selectFirst("img")?.getImageAttr())

                    newMovieSearchResponse(recTitle.cleanTitle(), recHref, TvType.NSFW) {
                        this.posterUrl = recPoster
                    }
                }
            }
        }.filter { it.url != url }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val videos = linkedSetOf<Pair<String, Int>>()

        document.select(
            "video source[src], " +
                "source[src], " +
                "video[src], " +
                "a[href$=.mp4], " +
                "a[href*=.mp4]"
        ).forEach { video ->
            val link = video.attr("src").ifBlank { video.attr("href") }.trim()
            if (link.isBlank()) return@forEach

            val quality = video.attr("res").toIntOrNull()
                ?: video.attr("label").replace(Regex("\\D"), "").toIntOrNull()
                ?: 0

            videos.add(fixUrl(link) to quality)
        }

        document.select(
            "meta[property=og:video], " +
                "meta[property=og:video:url], " +
                "meta[property=og:video:secure_url]"
        ).forEach { meta ->
            val link = meta.attr("content").trim()
            if (link.isNotBlank()) {
                videos.add(fixUrl(link) to 0)
            }
        }

        videos.forEach { (link, quality) ->
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = link,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                }
            )
        }

        return videos.isNotEmpty()
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val anchor = if (this.`is`("a")) this else selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null

        val title = listOf(
            selectFirst("h3")?.text()?.trim(),
            selectFirst("span.text-sm.font-bold")?.text()?.trim(),
            selectFirst(".font-bold")?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Watch", true) &&
                !it.equals("Play", true)
        }?.cleanTitle() ?: return null

        val posterUrl = fixUrlNull(selectFirst("img")?.getImageAttr())

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("data-src")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("srcset") -> attr("srcset").substringBefore(" ")
            hasAttr("data-original") -> attr("data-original")
            else -> attr("src")
        }
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+-\s+FreeUsePorn\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}