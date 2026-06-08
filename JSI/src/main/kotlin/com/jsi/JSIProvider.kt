package com.jsi

import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

class JSIProvider : MainAPI() {
    override var name = "JSI"
    override var mainUrl = "https://javsubindo.life"
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "category/jav-sub-indo/" to "Jav Sub Indo",
        "tag/4k/" to "4K",
        "tag/big-tits/" to "Big Tits",
        "tag/creampie/" to "Creampie",
        "tag/drama/" to "Drama",
        "tag/featured-actress/" to "Featured Actress",
        "tag/hi-def/" to "Hi-Def",
        "tag/milf/" to "MILF"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = headers, timeout = 30L).document
        val results = parseCards(document).distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, results, isHorizontalImages = true),
            hasNext = hasNextPage(document, page) || results.isNotEmpty()
        )
    }

    private fun buildPageUrl(data: String, page: Int): String {
        val clean = data.trim().trim('/')

        return when {
            clean.isBlank() && page <= 1 -> mainUrl
            clean.isBlank() -> "$mainUrl/page/$page/"

            clean.startsWith("http", ignoreCase = true) -> {
                if (page <= 1) clean else clean.trimEnd('/') + "/page/$page/"
            }

            page <= 1 -> "$mainUrl/$clean/"
            else -> "$mainUrl/$clean/page/$page/"
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select("article.loop-video.thumb-block, article.loop-video, article.thumb-block")
            .forEach { element ->
                element.toSearchResult()?.let { item -> results[item.url] = item }
            }

        if (results.isEmpty()) {
            document.select("article:has(a[href]):has(img), .post:has(a[href]):has(img), a[href]:has(img)")
                .forEach { element ->
                    element.toSearchResult()?.let { item -> results[item.url] = item }
                }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst("a[href][title], a[href]:has(img), h2 a[href], h3 a[href], .entry-header a[href], a[href]")
                ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl, ignoreCase = true)) return null
        if (isBlockedUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val title = listOf(
            anchor.attr("title"),
            selectFirst(".entry-header span")?.text(),
            selectFirst("h2, h3, .entry-title, .title")?.text(),
            image?.attr("alt"),
            anchor.text(),
            href.substringBeforeLast('/').substringAfterLast('/').replace("-", " ")
        ).firstOrNull { !it.isNullOrBlank() && !isBadTitle(it) }
            ?.cleanTitle()
            ?: return null

        val poster = image?.getImageUrl()
        val duration = selectFirst(".duration")?.text()?.cleanText()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = poster
            posterHeaders = mapOf("Referer" to mainUrl)
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            ".pagination a[href*='/page/${page + 1}/'], " +
                ".pagination a.inactive[href], a.next[href], a[rel=next], a[href*='paged=${page + 1}']"
        ) != null
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()

        if (path.isBlank()) return true

        val blocked = listOf(
            "category/",
            "tag/",
            "tags",
            "actor/",
            "actors",
            "wp-content",
            "wp-admin",
            "wp-json",
            "feed",
            "dmca",
            "privacy",
            "contact",
            "login",
            "register"
        )

        return blocked.any { path == it.trimEnd('/') || path.startsWith(it) }
    }

    private fun isBadTitle(title: String): Boolean {
        val clean = title.cleanText()

        return clean.isBlank() ||
            clean.equals("Home", true) ||
            clean.equals("Categories", true) ||
            clean.equals("Tags", true) ||
            clean.equals("Actors", true) ||
            clean.equals("Join Telegram", true) ||
            clean.equals("Jav Sub Indo", true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }

        val document = app.get(url, headers = headers, referer = mainUrl, timeout = 30L).document
        val results = parseCards(document).distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = hasNextPage(document, page) || results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers, referer = mainUrl, timeout = 30L).document

        val title = listOf(
            document.selectFirst("h1.entry-title")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[itemprop=name]")?.attr("content"),
            url.substringBeforeLast('/').substringAfterLast('/').replace("-", " ")
        ).firstOrNull { !it.isNullOrBlank() && !isBadTitle(it) }
            ?.cleanTitle()
            ?: name

        val poster = listOfNotNull(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst(".video-player meta[itemprop=thumbnailUrl]")?.attr("content"),
            document.selectFirst(".responsive-player")?.attr("style")?.extractCssBackgroundUrl(),
            document.selectFirst("article img, .post-thumbnail img, img")?.getImageUrl()
        ).firstOrNull { it.isNotBlank() }?.let { fixUrlNull(it) }

        val description = listOfNotNull(
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst(".video-description .desc p")?.text(),
            document.selectFirst(".entry-content p")?.text(),
            document.selectFirst(".video-player meta[itemprop=description]")?.attr("content")
        ).firstOrNull { it.isNotBlank() }?.cleanText()

        val tags = document.select(".tags-list a[href], a[href*='/tag/'], a[href*='/category/']")
            .map { it.text().cleanText() }
            .filter { it.length in 2..40 && !isBadTitle(it) }
            .distinct()
            .take(20)

        val actors = document.select("#video-actors a[href*='/actor/'], a[href*='/actor/']")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)

        val duration = document.selectFirst(".video-player meta[itemprop=duration]")?.attr("content")
            ?.durationToMinutes()
            ?: document.selectFirst("#video-date")?.text()?.substringAfter("Time:", "")?.durationTextToMinutes()

        val recommendations = parseCards(document)
            .filterNot { it.url == url }
            .distinctBy { it.url }
            .take(12)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            posterHeaders = mapOf("Referer" to mainUrl)
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            this.duration = duration ?: 0
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers, referer = mainUrl, timeout = 30L).document
        var emitted = false

        collectSubtitles(document, subtitleCallback)

        val pageHtml = document.html()
        val candidates = linkedSetOf<String>()

        document.select("iframe[src], iframe[data-src], video[src], source[src], a[href]")
            .forEach { element ->
                listOf("src", "data-src", "data-lazy-src", "data-url", "href")
                    .mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                    .mapNotNull { fixUrlNull(it) }
                    .forEach { candidates.add(it) }
            }

        extractRocketLazyScripts(document).forEach { script ->
            extractUrlsFromText(script).forEach { candidates.add(it) }
        }

        extractUrlsFromText(pageHtml).forEach { candidates.add(it) }

        suspend fun emitDirect(url: String, referer: String) {
            when {
                url.contains(".m3u8", ignoreCase = true) -> {
                    try {
                        generateM3u8(
                            source = name,
                            streamUrl = url,
                            referer = referer,
                            headers = headers
                        ).forEach { link ->
                            emitted = true
                            callback(link)
                        }
                    } catch (e: Throwable) {
                        Log.e(name, "M3U8 parse failed: ${e.message}")
                    }
                }

                url.contains(".mp4", ignoreCase = true) -> {
                    emitted = true
                    callback(
                        newExtractorLink(name, "$name MP4", url) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                }
            }
        }

        suspend fun emitExtractor(url: String, referer: String) {
            try {
                loadExtractor(url, referer, subtitleCallback) { link ->
                    emitted = true
                    callback(link)
                }
            } catch (e: Throwable) {
                Log.e(name, "Extractor failed for $url: ${e.message}")
            }
        }

        val playableCandidates = candidates
            .mapNotNull { it.normalizedCandidate() }
            .filter { it.isPlayableCandidate() }
            .distinct()

        for (candidate in playableCandidates) {
            if (candidate.isDirectMedia()) {
                emitDirect(candidate, data)
            } else {
                emitExtractor(candidate, data)
            }
        }

        if (!emitted) {
            for (candidate in playableCandidates.filterNot { it.isDirectMedia() }) {
                try {
                    val playerDocument = app.get(candidate, headers = headers, referer = data, timeout = 30L).document
                    collectSubtitles(playerDocument, subtitleCallback)

                    extractUrlsFromText(playerDocument.html())
                        .mapNotNull { it.normalizedCandidate() }
                        .filter { it.isPlayableCandidate() && it != candidate }
                        .distinct()
                        .forEach { nested ->
                            if (nested.isDirectMedia()) {
                                emitDirect(nested, candidate)
                            } else {
                                emitExtractor(nested, candidate)
                            }
                        }
                } catch (e: Throwable) {
                    Log.e(name, "Nested player scan failed for $candidate: ${e.message}")
                }
            }
        }

        if (!emitted) {
            Log.e(name, "Playback callback link > 0 not proven for $data")
        }

        return emitted
    }

    private fun collectSubtitles(document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[kind=subtitles], track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            val url = fixUrlNull(raw) ?: return@forEach
            val label = element.attr("label").ifBlank { element.text().ifBlank { "Subtitle" } }.cleanText()
            subtitleCallback(newSubtitleFile(label, url))
        }
    }

    private fun extractRocketLazyScripts(document: Document): List<String> {
        val scripts = mutableListOf<String>()

        document.select("script[data-rocketlazyloadscript]").forEach { script ->
            val value = script.attr("data-rocketlazyloadscript")
            if (value.contains("base64,", ignoreCase = true)) {
                val raw = value.substringAfter("base64,")
                decodeBase64(raw)?.let { scripts.add(it) }
            } else if (value.isNotBlank()) {
                scripts.add(value)
            }
        }

        document.select("script").forEach { script ->
            val text = script.data().ifBlank { script.html() }.ifBlank { script.text() }
            if (text.isNotBlank()) {
                scripts.add(text)

                Regex("""(?i)atob\(['"]([^'"]+)['"]\)""")
                    .findAll(text)
                    .mapNotNull { decodeBase64(it.groupValues[1]) }
                    .forEach { scripts.add(it) }
            }
        }

        return scripts
    }

    private fun extractUrlsFromText(value: String): List<String> {
        val normalized = value
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")

        val urls = linkedSetOf<String>()

        Regex("""(?i)https?://[^\s'"<>\\]+""")
            .findAll(normalized)
            .map { it.value.trimEnd(',', ';', ')', ']', '}') }
            .forEach { urls.add(it) }

        Regex("""(?i)//[^\s'"<>\\]+""")
            .findAll(normalized)
            .map { "https:${it.value}".trimEnd(',', ';', ')', ']', '}') }
            .forEach { urls.add(it) }

        Regex("""(?i)https?%3A%2F%2F[^\s'"<>\\]+""")
            .findAll(value)
            .mapNotNull { decodeUrl(it.value) }
            .forEach { urls.add(it) }

        return urls.distinct()
    }

    private fun decodeBase64(value: String): String? {
        return try {
            String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeUrl(value: String): String? {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Throwable) {
            null
        }
    }

    private fun String.normalizedCandidate(): String? {
        val decoded = decodeUrl(this) ?: this
        val clean = decoded
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .trim()
            .trim('"', '\'', '`', ',', ';', ')', ']', '}')

        if (clean.isBlank()) return null
        if (clean.startsWith("data:", ignoreCase = true)) return null
        if (clean.startsWith("blob:", ignoreCase = true)) return null
        if (clean.startsWith("javascript:", ignoreCase = true)) return null

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("/") -> mainUrl.trimEnd('/') + clean
            else -> null
        }
    }

    private fun String.isPlayableCandidate(): Boolean {
        val lower = lowercase()

        if (lower.contains("thumbnail.") || lower.contains("/image/") || lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".webp")) {
            return false
        }

        return lower.contains("streamvid.dev") ||
            lower.contains("vodstream.streamvid.dev") ||
            lower.contains("te.streamvid.dev") ||
            lower.contains(".m3u8") ||
            lower.contains(".mp4")
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4")
    }

    private fun Element.getImageUrl(): String? {
        return attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
            ?: attr("data-original").takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
            ?: attr("src").takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
    }

    private fun String.extractCssBackgroundUrl(): String? {
        return Regex("""url\(['"]?([^'")]+)['"]?\)""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun String.cleanTitle(): String {
        return cleanText()
            .replace(Regex("""\s+-\s+Javsubindo\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+JAVsubid\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun String.cleanText(): String {
        return replace('\u00a0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.durationTextToMinutes(): Int? {
        val match = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{1,2})""").find(this.cleanText())
            ?: return null

        val parts = match.groupValues
        val hours = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = parts.getOrNull(2)?.toIntOrNull() ?: 0

        return hours * 60 + minutes
    }

    private fun String.durationToMinutes(): Int? {
        // Retrotube evidence uses ISO-like values such as P0DT2H0M0S.
        val match = Regex("""P(?:(\d+)D)?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""", RegexOption.IGNORE_CASE)
            .find(this)
            ?: return durationTextToMinutes()

        val days = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
        val hours = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        val minutes = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0

        return days * 24 * 60 + hours * 60 + minutes
    }
}
