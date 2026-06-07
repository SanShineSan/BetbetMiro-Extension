package com.sad25kag.animesail

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AnimeSailProvider : MainAPI() {
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    private val turnstileInterceptor = TurnstileInterceptor()

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t.trim()) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            interceptor = turnstileInterceptor,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "User-Agent" to USER_AGENT
            ),
            referer = ref
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/rilisan-anime-terbaru/page/" to "Ongoing Anime",
        "$mainUrl/rilisan-donghua-terbaru/page/" to "Ongoing Donghua",
        "$mainUrl/movie-terbaru/page/" to "Movie Terbaru",
        "$mainUrl/daftar-anime/page/" to "Daftar Anime",

        "$mainUrl/genres/action/page/" to "Action",
        "$mainUrl/genres/adventure/page/" to "Adventure",
        "$mainUrl/genres/comedy/page/" to "Comedy",
        "$mainUrl/genres/drama/page/" to "Drama",
        "$mainUrl/genres/fantasy/page/" to "Fantasy",
        "$mainUrl/genres/romance/page/" to "Romance",
        "$mainUrl/genres/school/page/" to "School",
        "$mainUrl/genres/slice-of-life/page/" to "Slice of Life",
        "$mainUrl/genres/shounen/page/" to "Shounen",
        "$mainUrl/genres/seinen/page/" to "Seinen",
        "$mainUrl/genres/isekai/page/" to "Isekai",
        "$mainUrl/genres/supernatural/page/" to "Supernatural",
        "$mainUrl/genres/magic/page/" to "Magic",
        "$mainUrl/genres/mystery/page/" to "Mystery",
        "$mainUrl/genres/sci-fi/page/" to "Sci-Fi",
        "$mainUrl/genres/mecha/page/" to "Mecha",
        "$mainUrl/genres/sports/page/" to "Sports",
        "$mainUrl/genres/historical/page/" to "Historical",
        "$mainUrl/genres/harem/page/" to "Harem",
        "$mainUrl/genres/ecchi/page/" to "Ecchi",
        "$mainUrl/genres/horror/page/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, pageRequest: MainPageRequest): HomePageResponse {
        val document = request(pageRequest.data + page).document
        val home = document.select("div.listupd article, article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(
            pageRequest.name,
            home,
            hasNext = home.isNotEmpty()
        )
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> title.substringBefore("-episode")
                (title.contains("-movie")) -> title.substringBefore("-movie")
                else -> title
            }
            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val linkElement = selectFirst(
            ".tt > h2 > a, h2.entry-title > a, h2 > a, h3 > a, a[rel=bookmark], a[href]"
        ) ?: return null

        val rawHref = fixUrlNull(
            linkElement.attr("href").ifBlank {
                selectFirst("a[href]")?.attr("href")
            }
        ) ?: return null
        val href = getProperAnimeLink(rawHref)

        val rawTitle = listOfNotNull(
            selectFirst(".tt > h2")?.text(),
            selectFirst("h2.entry-title")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            linkElement.attr("title").takeIf { it.isNotBlank() },
            select("a[href]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && !it.equals("Next", true) && !it.equals("Previous", true) }
                .maxByOrNull { it.length }
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

        val title = rawTitle.replace(Regex("(?i)Episode\\s?\\d+"), "")
            .replace(Regex("(?i)Subtitle Indonesia"), "")
            .replace(Regex("(?i)Sub Indo"), "")
            .trim()
            .removeSuffix("-")
            .trim()

        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(
            selectFirst("div.limit img, img.wp-post-image, img.attachment-post-thumbnail, img")?.let { img ->
                img.attr("abs:data-src").ifBlank {
                    img.attr("abs:data-lazy-src").ifBlank {
                        img.attr("abs:src").ifBlank {
                            img.attr("data-src").ifBlank {
                                img.attr("data-lazy-src").ifBlank {
                                    img.attr("src")
                                }
                            }
                        }
                    }
                }
            }
        )

        val epNum = Regex("(?i)Episode\\s?(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val typeText = listOfNotNull(
            selectFirst(".tt > span")?.text(),
            selectFirst(".typez")?.text(),
            text().takeIf { it.contains("·") }
        ).joinToString(" ")
        val type = if (typeText.contains("Movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val document = request("$mainUrl/?s=$encoded").document

        return document.select("div.listupd article, article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.entry-title")?.text().orEmpty()
            .replace("Subtitle Indonesia", "").trim()
        val poster = fixUrlNull(document.selectFirst("div.entry-content > img, .entry-content img")?.attr("src"))
        val type = getType(document.select("tbody th:contains(Tipe)").next().text().lowercase())
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()

        val episodes = document.select("ul.daftar > li").mapNotNull {
            val link = fixUrlNull(it.selectFirst("a[href]")?.attr("href")) ?: return@mapNotNull null
            val name = it.selectFirst("a")?.text().orEmpty()
            val episode = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(link) {
                this.name = name.ifBlank { null }
                this.episode = episode
            }
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(document.select("tbody th:contains(Status)").next().text().trim())
            plot = document.selectFirst("div.entry-content > p")?.text()
            tags = document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }
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
        val document = request(data).document
        val playerPath = "$mainUrl/utils/player/"
        val visitedUrls = linkedSetOf<String>()
        var emitted = false

        document.select(".mobius > .mirror > option, .mobius option, select.mirror option").amap { element ->
            safeApiCall {
                val rawText = element.text().trim()
                val quality = getIndexQuality(rawText)
                val serverName = rawText.split(" ").firstOrNull()?.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                } ?: name

                val candidates = element.extractMirrorCandidates()
                candidates.forEach { candidate ->
                    if (resolveMirrorLink(
                            rawUrl = candidate,
                            referer = data,
                            playerPath = playerPath,
                            serverName = serverName,
                            quality = quality,
                            visitedUrls = visitedUrls,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    ) {
                        emitted = true
                    }
                }
            }
        }

        return emitted
    }

    private suspend fun resolveMirrorLink(
        rawUrl: String,
        referer: String,
        playerPath: String,
        serverName: String,
        quality: Int?,
        visitedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalized = normalizeMirrorUrl(rawUrl) ?: return false
        if (normalized.contains("statistic", true)) return false
        if (!visitedUrls.add(normalized)) return false

        return when {
            isDirectMediaUrl(normalized) -> {
                emitDirectMediaLink(normalized, serverName, quality, referer, callback)
                true
            }

            normalized.contains("${playerPath}popup", true) -> {
                val encodedUrl = normalized.substringAfter("url=", "").substringBefore("&")
                val realUrl = runCatching { URLDecoder.decode(encodedUrl, "UTF-8") }.getOrNull()
                if (realUrl.isNullOrBlank()) false else resolveMirrorLink(
                    rawUrl = realUrl,
                    referer = normalized,
                    playerPath = playerPath,
                    serverName = serverName,
                    quality = quality,
                    visitedUrls = visitedUrls,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            normalized.contains("aghanim.xyz/tools/redirect/", true) -> {
                val id = normalized.substringAfter("id=").substringBefore("&token")
                if (id.isBlank()) false else resolveMirrorLink(
                    rawUrl = "https://rasa-cintaku-semakin-berantai.xyz/v/$id",
                    referer = normalized,
                    playerPath = playerPath,
                    serverName = serverName,
                    quality = quality,
                    visitedUrls = visitedUrls,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            normalized.contains(playerPath, true) ||
                normalized.contains("player-kodir.aghanim.xyz", true) ||
                normalized.contains("uservideo.xyz", true) -> {
                val response = runCatching { request(normalized, ref = referer) }.getOrNull()
                val nestedLinks = linkedSetOf<String>()

                if (response != null) {
                    val text = response.text
                    val playerDoc = response.document
                    val packedHtml = text.substringAfter("= `", "").substringBefore("`;", "")
                    if (packedHtml.isNotBlank()) {
                        nestedLinks.addAll(
                            Jsoup.parse(packedHtml)
                                .select("source[src], video[src], iframe[src], a[href]")
                                .mapNotNull { it.attr("src").ifBlank { it.attr("href") }.trim().takeIf(String::isNotBlank) }
                        )
                    }

                    nestedLinks.addAll(
                        playerDoc.select("source[src], video[src], iframe[src], a[href], script[src]")
                            .mapNotNull { it.attr("src").ifBlank { it.attr("href") }.trim().takeIf(String::isNotBlank) }
                    )

                    nestedLinks.addAll(extractCandidatesFromText(text, normalized))
                }

                if (nestedLinks.isEmpty()) {
                    loadFixedExtractor(normalized, serverName, quality, referer, subtitleCallback, callback)
                    true
                } else {
                    var emitted = false
                    nestedLinks.forEach { nested ->
                        if (resolveMirrorLink(nested, normalized, playerPath, serverName, quality, visitedUrls, subtitleCallback, callback)) {
                            emitted = true
                        }
                    }
                    emitted
                }
            }

            else -> {
                loadFixedExtractor(normalized, serverName, quality, referer, subtitleCallback, callback)
                true
            }
        }
    }

    private fun Element.extractMirrorCandidates(): List<String> {
        val rawCandidates = listOf(
            attr("data-em"),
            attr("value"),
            attr("data-iframe"),
            attr("data-url"),
            attr("data-src")
        ).filter { it.isNotBlank() }

        val results = linkedSetOf<String>()
        rawCandidates.forEach { encoded ->
            results.addAll(decodeMirrorCandidates(encoded))
        }
        return results.toList()
    }

    private fun decodeMirrorCandidates(encodedData: String): List<String> {
        if (encodedData.isBlank()) return emptyList()
        val candidates = linkedSetOf<String>()
        val clean = encodedData.trim().replace("\\u0026", "&")

        fun addUrl(raw: String?) {
            normalizeMirrorUrl(raw)?.let { candidates.add(it) }
        }

        fun parseBlob(blob: String) {
            if (blob.isBlank()) return
            addUrl(blob)
            val doc = Jsoup.parse(blob)
            doc.select("iframe[src], source[src], video[src], a[href]").forEach { el ->
                addUrl(el.attr("src").ifBlank { el.attr("href") })
            }
            Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                .findAll(blob)
                .forEach { addUrl(it.value) }
        }

        parseBlob(clean)
        runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrNull()?.let(::parseBlob)
        runCatching { base64Decode(clean.replace("\\s".toRegex(), "")) }.getOrNull()?.let(::parseBlob)
        return candidates.toList()
    }

    private fun normalizeMirrorUrl(raw: String?): String? {
        return normalizeUrlFromBase(raw, mainUrl)
    }

    private fun normalizeUrlFromBase(raw: String?, baseUrl: String?): String? {
        val clean = raw?.trim()
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.removePrefix("'")
            ?.removeSuffix("'")
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.replace("\\u0026", "&")
            ?.trim()
            ?: return null

        if (clean.isBlank() || clean.startsWith("javascript:", true) || clean.startsWith("data:", true)) return null

        fun resolveWithBase(path: String): String? {
            if (baseUrl.isNullOrBlank()) return null
            return runCatching { URI(baseUrl).resolve(path).toString() }.getOrNull()
        }

        return when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> resolveWithBase(clean) ?: runCatching { fixUrl(clean) }.getOrNull()
            else -> resolveWithBase(clean)
        }
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        return Regex("""(?i)\.(m3u8|mp4)(?:$|[?#&])""").containsMatchIn(url)
    }

    private fun extractCandidatesFromText(text: String, baseUrl: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val out = linkedSetOf<String>()
        val patterns = listOf(
            Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source|video_url|play_url|hls)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']((?:/|//)[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        )

        patterns.forEach { rgx ->
            rgx.findAll(text).forEach { match ->
                val raw = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
                normalizeUrlFromBase(raw, baseUrl)?.let(out::add)
            }
        }
        return out
    }

    private suspend fun emitDirectMediaLink(
        mediaUrl: String,
        serverName: String,
        quality: Int?,
        refererHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val isMp4UploadDirect = mediaUrl.contains("mp4upload.com", ignoreCase = true)
        val directReferer = if (isMp4UploadDirect) "https://www.mp4upload.com/" else (refererHint ?: mainUrl)
        val directHeaders = if (isMp4UploadDirect) {
            mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to directReferer,
                "Origin" to "https://www.mp4upload.com"
            )
        } else {
            mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to directReferer
            )
        }

        callback.invoke(
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = mediaUrl,
                type = if (mediaUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                referer = directReferer
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = directHeaders
            }
        )
    }

    private suspend fun loadFixedExtractor(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = normalizeYourUploadUrl(url)

        if (tryLoadMp4UploadDirect(normalizedUrl, serverName, quality, callback)) return

        loadExtractor(normalizedUrl, referer, subtitleCallback) { link ->
            val finalName = if (serverName.equals(link.name, ignoreCase = true)) link.name else "$serverName - ${link.name}"
            callback.invoke(
                newExtractorLink(
                    source = link.name,
                    name = finalName,
                    url = link.url,
                    type = link.type
                ) {
                    this.referer = link.referer.takeIf { it.isNotBlank() } ?: referer ?: mainUrl
                    this.quality = if (link.type == ExtractorLinkType.M3U8) link.quality else quality ?: Qualities.Unknown.value
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }

    private fun normalizeYourUploadUrl(url: String): String {
        if (!url.contains("yourupload.com", true)) return url
        return if (url.contains("/watch/", true)) url.replace("/watch/", "/embed/", true) else url
    }

    private suspend fun tryLoadMp4UploadDirect(
        url: String,
        serverName: String,
        quality: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""mp4upload\.com/(?:embed-)?([A-Za-z0-9]+)(?:\.html)?""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return false

        val downloadUrl = "https://www.mp4upload.com/dl?op=download2&id=$id"
        val watchReferer = "https://www.mp4upload.com/"
        val redirect = runCatching {
            app.get(
                downloadUrl,
                referer = watchReferer,
                allowRedirects = false,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to "https://www.mp4upload.com"
                )
            )
        }.getOrNull() ?: return false

        val location = redirect.headers["Location"] ?: redirect.headers["location"]
        val finalUrl = when {
            location.isNullOrBlank() -> return false
            location.startsWith("http://", true) || location.startsWith("https://", true) -> location
            location.startsWith("//") -> "https:$location"
            location.startsWith("/") -> "https://www.mp4upload.com$location"
            else -> return false
        }

        callback.invoke(
            newExtractorLink(
                source = "Mp4Upload",
                name = serverName,
                url = finalUrl,
                type = INFER_TYPE
            ) {
                referer = watchReferer
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to "https://www.mp4upload.com"
                )
            }
        )
        return true
    }

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}

class TurnstileInterceptor(
    private val targetCookies: List<String> = listOf("cf_clearance", "_as_turnstile")
) : Interceptor {
    companion object {
        private const val POLL_INTERVAL_MS = 500L
        private const val MAX_ATTEMPTS = 30
        private const val PAGE_WAIT_SECONDS = 45L
    }

    private fun getCookieHeader(url: String, domainUrl: String): String {
        val manager = CookieManager.getInstance()
        return manager.getCookie(url) ?: manager.getCookie(domainUrl) ?: ""
    }

    private fun getCookieValue(url: String, domainUrl: String): String? {
        val raw = getCookieHeader(url, domainUrl)
        if (raw.isBlank()) return null
        return raw.split(";")
            .map { it.trim() }
            .firstNotNullOfOrNull { cookie ->
                targetCookies.firstOrNull { target -> cookie.startsWith("$target=") }
                    ?.let { cookie.substringAfter("=") }
                    ?.takeIf { it.isNotBlank() }
            }
    }

    private fun invalidateCookie(domainUrl: String) {
        CookieManager.getInstance().apply {
            targetCookies.forEach { cookie ->
                setCookie(domainUrl, "$cookie=; Max-Age=0")
            }
            flush()
        }
    }

    private fun hasChallenge(response: Response): Boolean {
        if (response.code == 403 || response.code == 429 || response.code == 503) return true

        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("text/html", ignoreCase = true)) return false

        val preview = runCatching { response.peekBody(128 * 1024).string() }.getOrDefault("")
        if (preview.isBlank()) return false

        val challengeHints = listOf(
            "cf-challenge",
            "cf-browser-verification",
            "cf_clearance",
            "challenge-platform",
            "Just a moment",
            "Attention Required",
            "turnstile",
            "/cdn-cgi/challenge-platform/"
        )
        return challengeHints.any { preview.contains(it, ignoreCase = true) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()

        if (getCookieValue(url, domainUrl) != null) {
            val response = chain.proceed(
                originalRequest.newBuilder()
                    .header("Cookie", getCookieHeader(url, domainUrl))
                    .build()
            )
            if (!hasChallenge(response)) return response
            response.close()
            invalidateCookie(domainUrl)
        }

        val context = AcraApplication.context
            ?: return chain.proceed(originalRequest)

        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var resolvedUserAgent = originalRequest.header("User-Agent") ?: ""
        val challengeLatch = CountDownLatch(1)

        handler.post {
            try {
                val wv = WebView(context).also { webView = it }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true
                    loadsImagesAutomatically = true
                    if (resolvedUserAgent.isNotBlank()) userAgentString = resolvedUserAgent
                    resolvedUserAgent = userAgentString
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        super.onPageFinished(view, finishedUrl)
                        cookieManager.flush()
                        if (getCookieValue(finishedUrl, domainUrl) != null) {
                            challengeLatch.countDown()
                        }
                    }
                }
                wv.loadUrl(url)
            } catch (e: Exception) {
                challengeLatch.countDown()
                e.printStackTrace()
            }
        }

        challengeLatch.await(PAGE_WAIT_SECONDS, TimeUnit.SECONDS)

        var attempts = 0
        while (attempts < MAX_ATTEMPTS && getCookieValue(url, domainUrl) == null) {
            Thread.sleep(POLL_INTERVAL_MS)
            cookieManager.flush()
            attempts++
        }

        handler.post {
            try {
                webView?.apply {
                    stopLoading()
                    clearCache(false)
                    destroy()
                }
                webView = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val finalCookies = getCookieHeader(url, domainUrl)
        val finalResponse = chain.proceed(
            originalRequest.newBuilder()
                .header("Cookie", finalCookies)
                .apply { if (resolvedUserAgent.isNotBlank()) header("User-Agent", resolvedUserAgent) }
                .build()
        )

        if (!hasChallenge(finalResponse)) return finalResponse

        return finalResponse
    }
}
