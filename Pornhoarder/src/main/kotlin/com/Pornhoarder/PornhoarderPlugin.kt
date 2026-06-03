package com.PornhoarderPlugin

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.FormBody

class PornhoarderPlugin : MainAPI() {
    override var mainUrl              = "https://pornhoarder.io"
    override var name                 = "Pornhoarder"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    private val ajaxUrl = "$mainUrl/ajax_search.php"

    override val mainPage = mainPageOf(
            "Latest" to "Latest Videos",
            "Popular" to "Popular Videos",
            "/trending-videos/" to "Trending Videos",
            "/random-videos/" to "Random Videos"
        )

    private fun getRequestBody (query: String, isLatest : Boolean, page:Int) : FormBody
    {
        return FormBody.Builder()
            .addEncoded("search", query)
            .addEncoded("sort", if (isLatest) {"0"} else {"2"})
            .addEncoded("date", "0")
            .addEncoded("servers[]", "40")
            .addEncoded("servers[]", "45")
            .addEncoded("servers[]", "12")
            .addEncoded("servers[]", "29")
            .addEncoded("servers[]", "25")
            .addEncoded("servers[]", "41")
            .addEncoded("servers[]", "46")
            .addEncoded("servers[]", "17")
            .addEncoded("servers[]", "44")
            .addEncoded("servers[]", "42")
            .addEncoded("servers[]", "43")
            .addEncoded("author", "0")
            .addEncoded("page", page.toString())
            .build()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if(request.data == "Latest" || request.data == "Popular")
        {
            val body = getRequestBody("",request.data == "Latest",page)
            val document = app.post(ajaxUrl, requestBody = body).document
            val responseList  = document.select(".video article").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true),hasNext = true)

        }
        else
        {
            val document = app.get("$mainUrl${request.data}?page=$page").document
            val responseList  = document.select(".video article").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true),hasNext = true)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select(".video-content h1").text()
            .replace("| PornHoarder.tv", "")
            .replace("| PornHoarder.io", "")
            .trim()
            .takeIf { it.isNotBlank() } ?: return null
        val href = absoluteUrl(this.select(".video-link").attr("href")) ?: return null
        val posterUrl = absoluteUrl(this.selectFirst(".video-image.primary.b-lazy")?.attr("data-src"))
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..10) {
            val requestBody = getRequestBody(query,true,i)
            val document = app.post(ajaxUrl, requestBody = requestBody).document
            //val document = app.get("${mainUrl}/page/$i/?s=$query").document

            val results = document.select(".video article").mapNotNull { it.toSearchResult() }

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break
        }

        return searchResponse

    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString().replace("| PornHoarder.tv","")
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
    

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private fun absoluteUrl(url: String?, base: String = mainUrl): String? {
        val cleaned = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (cleaned.startsWith("javascript:", true) ||
            cleaned.startsWith("about:", true) ||
            cleaned.startsWith("data:", true) ||
            cleaned.startsWith("blob:", true) ||
            cleaned.startsWith("#")
        ) return null

        return when {
            cleaned.startsWith("http://") || cleaned.startsWith("https://") -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "${base.substringBefore("://")}://${base.substringAfter("://").substringBefore("/")}$cleaned"
            cleaned.startsWith("?") -> "${base.substringBefore("?")}$cleaned"
            else -> "${base.substringBeforeLast("/", base).trimEnd('/')}/$cleaned"
        }
    }

    private fun String.isPlayableCandidate(): Boolean {
        val lower = lowercase()
        return startsWith("http") && !listOf(
            "javascript:", "about:", "data:", "blob:", "googlesyndication", "doubleclick",
            "popads", "exoclick", "juicyads", "adsterra", "analytics", "vast", "preroll",
            "commentsmodule", "tagbom", "megawebify", "videosprofitnetwork", "magsrv", "vstserv"
        ).any { lower.contains(it) }
    }

    private fun String.isPornhoarderPlayer(): Boolean {
        val lower = lowercase()
        return lower.contains("pornhoarder.net/player_t.php") || lower.contains("pornhoarder.net/player.php")
    }

    private suspend fun tryLoadExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var emitted = false
        return try {
            val loaded = loadExtractor(url, referer, subtitleCallback) { link ->
                emitted = true
                callback(link)
            }
            loaded || emitted
        } catch (_: Throwable) {
            false
        }
    }

    private fun collectPlayerCandidates(html: String, pageUrl: String): List<String> {
        val results = mutableListOf<String>()
        val document = org.jsoup.Jsoup.parse(html, pageUrl)

        document.select(".video-player iframe[src], iframe[src], source[src], video[src]").forEach { element ->
            absoluteUrl(element.attr("src"), pageUrl)?.let(results::add)
        }

        document.select("script[type=application/ld+json]").forEach { script ->
            Regex("""\"embedUrl\"\s*:\s*\"([^\"]+)\"""").findAll(script.data())
                .map { it.groupValues[1].replace("\\/", "/") }
                .mapNotNull { absoluteUrl(it, pageUrl) }
                .forEach(results::add)
        }

        Regex("""['\"](https?://[^'\"\s<>]+(?:player(?:_t)?\.php\?video=|/e/|/embed/|mp4|m3u8)[^'\"\s<>]*)['\"]""")
            .findAll(html)
            .take(16)
            .map { it.groupValues[1] }
            .mapNotNull { absoluteUrl(it, pageUrl) }
            .forEach(results::add)

        return results.distinct().filter { it.isPlayableCandidate() }
    }

    private fun pornhoarderOrigin(url: String): String {
        val cleaned = url.substringBefore("?").trimEnd('/')
        val scheme = cleaned.substringBefore("://", "https")
        val host = cleaned.substringAfter("://", cleaned).substringBefore("/")
        return "$scheme://$host"
    }

    private suspend fun fetchPornhoarderPlayerHtml(playerUrl: String, referer: String): String? {
        val origin = pornhoarderOrigin(playerUrl)
        val headers = mapOf(
            "Origin" to origin,
            "Referer" to playerUrl,
            "Content-Type" to "application/x-www-form-urlencoded"
        )

        val postHtml = runCatching {
            app.post(
                playerUrl,
                referer = playerUrl,
                requestBody = FormBody.Builder().addEncoded("play", "").build(),
                headers = headers
            ).text
        }.getOrNull()

        if (!postHtml.isNullOrBlank() && collectPlayerCandidates(postHtml, playerUrl).isNotEmpty()) {
            return postHtml
        }

        return runCatching {
            app.get(playerUrl, referer = referer).text
        }.getOrNull()
    }

    private suspend fun resolvePlayerCandidate(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!playerUrl.isPornhoarderPlayer()) {
            return tryLoadExtractor(playerUrl, referer, subtitleCallback, callback)
        }

        val playerDoc = fetchPornhoarderPlayerHtml(playerUrl, referer) ?: return false

        val nested = collectPlayerCandidates(playerDoc, playerUrl)
            .filterNot { it.isPornhoarderPlayer() }
            .sortedWith(compareByDescending<String> {
                val lower = it.lowercase()
                lower.contains("playmogo.com") || lower.contains("dirtyvideo.fun") || lower.contains("voe.sx")
            })

        for (nestedUrl in nested.take(8)) {
            if (tryLoadExtractor(nestedUrl, playerUrl, subtitleCallback, callback)) return true
        }
        return false
    }

    private fun extractVideoId(url: String): String? {
        return url.substringBefore("?").trimEnd('/').substringAfterLast('/').takeIf { it.length > 16 }
    }

    private suspend fun collectAlternateServerCandidates(pageHtml: String, pageUrl: String): List<String> {
        val document = org.jsoup.Jsoup.parse(pageHtml, pageUrl)
        val alternates = document.select(".server-list a[href], a[href*='/watch/']")
            .mapNotNull { absoluteUrl(it.attr("href"), pageUrl) }
            .filter { it.contains("/watch/") && it != pageUrl }
            .distinct()
            .take(4)

        val candidates = mutableListOf<String>()
        for (alternate in alternates) {
            extractVideoId(alternate)?.let { id ->
                candidates.add("https://pornhoarder.net/player_t.php?video=$id")
            }
            val html = runCatching { app.get(alternate, referer = pageUrl).text }.getOrNull() ?: continue
            candidates.addAll(collectPlayerCandidates(html, alternate))
        }
        return candidates.distinct()
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val pageHtml = runCatching {
            app.get(data, referer = "$mainUrl/").text
        }.getOrNull() ?: return false

        val candidates = collectPlayerCandidates(pageHtml, data).toMutableList()

        extractVideoId(data)?.let { videoId ->
            candidates.add("https://pornhoarder.net/player_t.php?video=$videoId")
        }

        candidates.addAll(collectAlternateServerCandidates(pageHtml, data))

        for (candidate in candidates.distinct().filter { it.isPlayableCandidate() }.take(12)) {
            if (resolvePlayerCandidate(candidate, data, subtitleCallback, callback)) {
                return true
            }
        }

        return false
    }
}