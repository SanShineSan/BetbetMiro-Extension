package com.sad25kag.kiosfilm21

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class KiosFilm21 : MainAPI() {
    override var mainUrl = "https://kiosfilm21.asia"
    override var name = "KiosFilm21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val providerHosts = setOf(
        "kiosfilm21.asia",
        "www.kiosfilm21.asia"
    )

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "genre/action/" to "Action",
        "genre/drama/" to "Drama",
        "genre/comedy/" to "Comedy",
        "genre/crime/" to "Crime",
        "genre/science-fiction/" to "Science Fiction",
        "genre/fantasy/" to "Fantasy",
        "genre/romance/" to "Romance"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            buildPageUrl(request.data, page),
            headers = siteHeaders,
            referer = "$mainUrl/"
        ).document

        val items = parseCards(document).distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNextPage(document, page)
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?s=$encoded&post_type%5B%5D=post&post_type%5B%5D=tv"
        )

        val results = linkedMapOf<String, SearchResponse>()
        urls.forEach { url ->
            val document = runCatching {
                app.get(url, headers = siteHeaders, referer = "$mainUrl/").document
            }.getOrNull() ?: return@forEach

            parseCards(document).forEach { item ->
                if (item.name.contains(cleanQuery, ignoreCase = true) || item.url.contains(cleanQuery.slugQuery(), ignoreCase = true)) {
                    results[item.url] = item
                }
            }

            if (results.isNotEmpty()) return@forEach
        }

        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = normalizeProviderUrl(fixUrl(url))
        val document = app.get(
            fixedUrl,
            headers = siteHeaders,
            referer = "$mainUrl/"
        ).document

        val title = document.selectFirst("h1.entry-title, h1[itemprop=name], h1[itemprop=headline], h1, meta[property=og:title], meta[name=title]")
            ?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() && !it.isUiText() }
            ?: fixedUrl.slugTitle()

        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image], .gmr-movie-data img, .content-thumbnail img, img.wp-post-image, img")
            ?.let { if (it.hasAttr("content")) it.attr("content") else it.getImageAttr() }
            ?.let { fixUrlNull(it) }
            ?.takeIf { !isBadImage(it) }

        val description = extractDescription(document)

        val tags = document.select(
            ".content-moviedata a[href*='/genre/'], " +
                ".content-moviedata a[href*='/country/'], " +
                ".content-moviedata a[href*='/quality/'], " +
                ".gmr-movie-on a[href*='/genre/'], " +
                ".gmr-movie-on a[href*='/country/'], " +
                "a[href*='/tag/']"
        ).map { it.text().trim().cleanTitle() }
            .filter { it.isNotBlank() && !it.isUiText() }
            .distinct()
            .take(20)

        val year = extractYear(title) ?: document.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull() ?: extractYear(document.text())

        val recommendations = parseCards(document)
            .filter { it.url != fixedUrl }
            .distinctBy { it.url }
            .take(24)

        val episodes = parseEpisodes(document, fixedUrl, poster)
        val isSeries = fixedUrl.contains("/tv/", true) || episodes.size > 1

        return if (isSeries) {
            newTvSeriesLoadResponse(
                title,
                fixedUrl,
                TvType.TvSeries,
                episodes.ifEmpty {
                    listOf(
                        newEpisode(fixedUrl) {
                            name = "Episode 1"
                            episode = 1
                            posterUrl = poster
                        }
                    )
                }
            ) {
                posterUrl = poster
                plot = description
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(
                title,
                fixedUrl,
                TvType.Movie,
                fixedUrl
            ) {
                posterUrl = poster
                plot = description
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedData = normalizeProviderUrl(fixUrl(data))
        val document = app.get(
            fixedData,
            headers = siteHeaders,
            referer = "$mainUrl/",
            timeout = 30L
        ).document

        val discovered = linkedSetOf<String>()

        document.select(
            ".player-wrap iframe[src], " +
                ".gmr-pagi-player iframe[src], " +
                ".gmr-embed-responsive iframe[src], " +
                "iframe[src], iframe[data-src], embed[src], source[src], " +
                "a[href*='abyssplayer'], a[href*='embed'], a[href*='player'], a[href*='stream']"
        ).forEach { element ->
            listOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("href")
            ).mapNotNull { resolveUrl(it, fixedData) }
                .filterNot { isBadPlaybackUrl(it) }
                .forEach { discovered.add(it) }
        }

        extractMediaUrls(document.html(), fixedData).forEach { discovered.add(it) }

        var found = false
        discovered.forEach { link ->
            found = resolvePlayerLink(link, fixedData, subtitleCallback, callback) || found
        }

        return found
    }

    private suspend fun resolvePlayerLink(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = resolveUrl(link, referer) ?: return false
        if (isBadPlaybackUrl(fixed)) return false

        if (tryEmitDirect(fixed, referer, callback)) return true

        if (runCatching { loadExtractor(fixed, referer, subtitleCallback, callback) }.getOrDefault(false)) {
            return true
        }

        return crawlPlayerPage(fixed, referer, subtitleCallback, callback)
    }

    private suspend fun crawlPlayerPage(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(link, headers = siteHeaders, referer = referer, timeout = 25L)
        }.getOrNull() ?: return false

        val html = response.text.decodeEscaped()
        val doc = Jsoup.parse(html, link)
        val candidates = linkedSetOf<String>()

        extractMediaUrls(html, link).forEach { candidates.add(it) }

        Regex("""(?:const|let|var)\s+datas\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { runCatching { String(android.util.Base64.decode(it.groupValues[1], android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() }
            .forEach { decoded ->
                extractMediaUrls(decoded, link).forEach { candidates.add(it) }
            }

        doc.select("iframe[src], iframe[data-src], embed[src], source[src], a[href*='embed'], a[href*='player'], a[href*='stream']")
            .mapNotNull { element ->
                element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("href") }
            }
            .mapNotNull { resolveUrl(it, link) }
            .filterNot { isBadPlaybackUrl(it) }
            .forEach { candidates.add(it) }

        var found = false
        candidates.filter { it != link }.forEach { next ->
            found = tryEmitDirect(next, link, callback) ||
                runCatching { loadExtractor(next, link, subtitleCallback, callback) }.getOrDefault(false) ||
                found
        }

        return found
    }

    private suspend fun tryEmitDirect(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = resolveUrl(url, referer) ?: return false
        if (isBadPlaybackUrl(fixed)) return false

        return when {
            fixed.contains(".m3u8", true) -> {
                generateM3u8(
                    source = name,
                    streamUrl = fixed,
                    referer = referer,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer
                    )
                ).forEach(callback)
                true
            }

            fixed.contains(".mp4", true) || fixed.contains(".webm", true) || fixed.contains(".mkv", true) -> {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixed,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = getQualityFromName(fixed).takeIf { it != Qualities.Unknown.value } ?: Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to referer,
                            "Range" to "bytes=0-"
                        )
                    }
                )
                true
            }

            else -> false
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article.item-infinite, " +
                "article.item, " +
                ".gmr-module-posts article, " +
                ".site-main article.item, " +
                ".gmr-box-content:has(h2.entry-title a), " +
                ".item:has(a[rel=bookmark])"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }

        if (results.isEmpty()) {
            document.select("h2.entry-title a[href], a[rel=bookmark][href], a[href]:has(img)")
                .forEach { element ->
                    element.toSearchResult()?.let { results[it.url] = it }
                }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst("h2.entry-title a[href], .entry-title a[href], a[rel=bookmark][href], a[href]:has(img)")
                ?: return null
        }

        val href = normalizeProviderUrl(resolveUrl(anchor.attr("href"), mainUrl) ?: return null)
        if (!isProviderUrl(href) || isBlockedUrl(href) || !looksLikeContentUrl(href)) return null

        val title = listOf(
            selectFirst("h2.entry-title a")?.text(),
            selectFirst(".entry-title a")?.text(),
            anchor.text(),
            anchor.attr("title"),
            selectFirst("img[alt]")?.attr("alt")
        ).mapNotNull {
            it?.cleanTitle()?.takeIf { clean -> clean.isNotBlank() && !clean.isUiText() }
        }.firstOrNull() ?: return null

        if (title.length < 2) return null

        val poster = extractPosterUrl(this, anchor)
        val type = if (href.contains("/tv/", true) || text().contains("TV Show", true)) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        }
    }

    private fun parseEpisodes(document: Document, fallbackUrl: String, poster: String?): List<com.lagradost.cloudstream3.Episode> {
        val episodes = linkedMapOf<String, com.lagradost.cloudstream3.Episode>()

        document.select(
            ".episodelist a[href], .episode-list a[href], .episodes a[href], .eplister a[href], " +
                "a[href*='/episode/'], a[href*='episode-'], a[href*='eps-']"
        ).forEachIndexed { index, element ->
            val href = normalizeProviderUrl(resolveUrl(element.attr("href"), fallbackUrl) ?: return@forEachIndexed)
            if (!isProviderUrl(href) || isBlockedUrl(href)) return@forEachIndexed

            val rawTitle = listOf(
                element.selectFirst(".title")?.text(),
                element.attr("title"),
                element.text()
            ).firstOrNull { !it.isNullOrBlank() && !it.isUiText() }?.cleanTitle()
                ?: "Episode ${index + 1}"

            val number = extractEpisodeNumber(rawTitle, href) ?: index + 1
            episodes[href] = newEpisode(href) {
                name = rawTitle.ifBlank { "Episode $number" }
                episode = number
                posterUrl = poster
            }
        }

        return episodes.values.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun extractDescription(document: Document): String? {
        document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")
            ?.cleanPlot()
            ?.let { return it }

        val entry = document.selectFirst(".entry-content.entry-content-single, .entry-content")
            ?: return null

        entry.select("script, style, .content-moviedata, .gmr-moviedata, h1, h2, h3").remove()

        return entry.selectFirst("p")?.text()?.cleanPlot()
            ?: entry.text().cleanPlot()
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a[rel=next], .pagination a:contains(Next), .pagination a:contains(Berikutnya), " +
                ".page-numbers.next, .nav-links a[href*='/page/${page + 1}'], " +
                "a[href*='/page/${page + 1}/'], a[href*='paged=${page + 1}']"
        ) != null
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim('/')
        return when {
            cleanPath.isBlank() && page <= 1 -> mainUrl
            cleanPath.isBlank() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$cleanPath/"
            else -> "$mainUrl/$cleanPath/page/$page/"
        }
    }

    private fun extractMediaUrls(text: String, base: String): List<String> {
        val cleaned = text.decodeEscaped()
        val results = linkedSetOf<String>()

        Regex("""https?://[^"'<>\s\\]+?\.(?:m3u8|mp4|webm|mkv)(?:\?[^"'<>\s\\]*)?""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .map { it.value }
            .forEach { results.add(it) }

        Regex("""//[^"'<>\s\\]+?\.(?:m3u8|mp4|webm|mkv)(?:\?[^"'<>\s\\]*)?""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .map { "https:${it.value}" }
            .forEach { results.add(it) }

        Regex("""/(?:stream|hls|video)/[^"'<>\s\\]+?\.(?:m3u8|mp4|webm|mkv)(?:\?[^"'<>\s\\]*)?""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .mapNotNull { resolveUrl(it.value, base) }
            .forEach { results.add(it) }

        Regex("""(?:file|src|source|url|video|videoUrl|streamUrl|link)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .mapNotNull { resolveUrl(it.groupValues[1], base) }
            .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains(".webm", true) || it.contains(".mkv", true) }
            .forEach { results.add(it) }

        return results.filterNot { isBadPlaybackUrl(it) }
    }

    private fun resolveUrl(raw: String?, base: String): String? {
        val clean = raw
            ?.trim()
            ?.decodeEscaped()
            ?.takeIf { it.isNotBlank() && it != "#" && !it.equals("none", true) && !it.equals("null", true) }
            ?: return null

        if (clean.startsWith("javascript", true) || clean.startsWith("mailto:", true) || clean.startsWith("tel:", true)) {
            return null
        }

        return runCatching {
            when {
                clean.startsWith("http://", true) || clean.startsWith("https://", true) -> normalizeProviderUrl(clean)
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("/") -> {
                    val uri = URI(base)
                    val root = "${uri.scheme ?: "https"}://${uri.host}"
                    normalizeProviderUrl("$root$clean")
                }
                else -> normalizeProviderUrl(URI(base).resolve(clean).toString())
            }
        }.getOrElse {
            runCatching { fixUrl(clean) }.getOrNull()
        }
    }

    private fun normalizeProviderUrl(url: String): String {
        return url.replace("http://kiosfilm21.asia", "https://kiosfilm21.asia")
            .replace("http://www.kiosfilm21.asia", "https://kiosfilm21.asia")
            .replace("https://www.kiosfilm21.asia", "https://kiosfilm21.asia")
    }

    private fun extractPosterUrl(element: Element, anchor: Element): String? {
        val candidates = listOfNotNull(
            element,
            anchor,
            element.parent(),
            element.parent()?.parent(),
            anchor.parent(),
            anchor.parent()?.parent()
        ).distinct()

        candidates.forEach { box ->
            box.selectFirst("img[src], img[data-src], img[data-lazy-src], img[data-original], source[srcset], source[data-srcset]")
                ?.getImageAttr()
                ?.let { fixUrlNull(it) }
                ?.takeIf { !isBadImage(it) }
                ?.let { return it }
        }

        return null
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src").ifBlank { attr("data-src") }
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src").ifBlank { attr("data-lazy-src") }
            hasAttr("data-original") -> attr("abs:data-original").ifBlank { attr("data-original") }
            hasAttr("data-img") -> attr("abs:data-img").ifBlank { attr("data-img") }
            hasAttr("data-image") -> attr("abs:data-image").ifBlank { attr("data-image") }
            hasAttr("data-poster") -> attr("abs:data-poster").ifBlank { attr("data-poster") }
            hasAttr("poster") -> attr("abs:poster").ifBlank { attr("poster") }
            hasAttr("data-srcset") -> attr("abs:data-srcset").ifBlank { attr("data-srcset") }.split(",").lastOrNull()?.substringBefore(" ")?.trim()
            hasAttr("srcset") -> attr("abs:srcset").ifBlank { attr("srcset") }.split(",").lastOrNull()?.substringBefore(" ")?.trim()
            hasAttr("src") -> attr("abs:src").ifBlank { attr("src") }
            else -> null
        }
    }

    private fun isProviderUrl(url: String): Boolean {
        return runCatching {
            val host = URI(url).host.orEmpty().lowercase().removePrefix("www.")
            providerHosts.any { host == it.removePrefix("www.") }
        }.getOrDefault(url.startsWith(mainUrl))
    }

    private fun looksLikeContentUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().trim('/').lowercase() }.getOrDefault("")
        if (path.isBlank()) return false
        if (isBlockedUrl(url)) return false
        if (path.startsWith("wp-")) return false
        if (Regex("""^[a-z0-9][a-z0-9-]+-\d{4}$""").matches(path)) return true
        if (path.startsWith("tv/")) return true
        return path.count { it == '/' } == 0 && extractYear(path) != null
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().trim('/').lowercase() }
            .getOrDefault(url.substringAfter(mainUrl, "").trim('/').lowercase())

        if (path.isBlank()) return true

        val exactBlocked = setOf(
            "genre",
            "country",
            "quality",
            "year",
            "tag",
            "director",
            "cast",
            "blog",
            "dmca",
            "pasang-iklan",
            "cara-download",
            "privacy",
            "contact",
            "terms",
            "about",
            "login",
            "register"
        )

        if (path in exactBlocked) return true

        val prefixBlocked = listOf(
            "genre/",
            "country/",
            "quality/",
            "year/",
            "tag/",
            "director/",
            "cast/",
            "blog/",
            "page/",
            "search",
            "feed",
            "wp-json",
            "wp-content",
            "wp-admin"
        )

        if (prefixBlocked.any { path.startsWith(it) }) return true

        val lower = url.lowercase()
        return lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".css") ||
            lower.endsWith(".js")
    }

    private fun isBadPlaybackUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") ||
            lower.contains("twitter.com") ||
            lower.contains("x.com/") ||
            lower.contains("telegram") ||
            lower.contains("whatsapp") ||
            lower.contains("mailto:") ||
            lower.contains("youtube.com/watch") ||
            lower.contains("shopee.") ||
            lower.contains("mrktmtrcs") ||
            lower.contains("sssrr.org") ||
            lower.contains("dtscout") ||
            lower.contains("dtscdn") ||
            lower.contains("histats") ||
            lower.contains("morphify.net") ||
            lower.contains("adsbygoogle") ||
            lower.contains("googlesyndication") ||
            lower.contains("doubleclick") ||
            lower.contains("analytics") ||
            lower.contains("googletagmanager") ||
            lower.contains("google-analytics") ||
            lower.contains("cloudflareinsights") ||
            lower.contains("wp-json") ||
            lower.contains("/wp-content/themes/") ||
            lower.contains("/wp-content/plugins/") ||
            lower.endsWith(".css") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".svg") ||
            lower.endsWith(".ico") ||
            lower.endsWith(".woff") ||
            lower.endsWith(".woff2") ||
            lower.endsWith(".ttf")
    }

    private fun isBadImage(url: String): Boolean {
        val lower = url.lowercase()
        return lower.isBlank() ||
            lower.startsWith("data:") ||
            lower.contains("logo") ||
            lower.contains("icon") ||
            lower.contains("avatar") ||
            lower.contains("favicon") ||
            lower.contains("placeholder") ||
            lower.contains("no-image") ||
            lower.endsWith(".svg")
    }

    private fun extractEpisodeNumber(text: String, href: String): Int? {
        return Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?:episode|eps?|ep)[-/\s]?(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun extractYear(text: String): Int? {
        return Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun String.slugTitle(): String {
        return substringBefore("?")
            .trimEnd('/')
            .substringAfterLast("/")
            .replace("-", " ")
            .cleanTitle()
            .ifBlank { "KiosFilm21" }
    }

    private fun String.slugQuery(): String {
        return lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
    }

    private fun String.decodeEscaped(): String {
        val cleaned = replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("&#8217;", "’")
            .replace("&#8211;", "–")
            .replace("&#8212;", "—")

        return if (cleaned.contains("%3A%2F%2F", true)) {
            runCatching { URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
        } else {
            cleaned
        }
    }

    private fun String.cleanTitle(): String {
        return decodeEscaped()
            .replace(Regex("""(?i)^\s*permalink\s+ke\s*:\s*"""), "")
            .replace(Regex("""(?i)^\s*permalink\s+to\s*:\s*"""), "")
            .replace(Regex("""\s+-\s+KiosFilm21.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+KiosFilm21.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Nonton\s+Film\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s+Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Full\s+Movie.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+…$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanPlot(): String? {
        return decodeEscaped()
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() && it.length > 20 }
    }

    private fun String.isUiText(): Boolean {
        val lower = trim().lowercase()
        if (lower.isBlank()) return true
        if (lower.length <= 1) return true
        if (lower.matches(Regex("""^\d+$"""))) return true

        return lower in setOf(
            "home", "next", "previous", "prev", "movies", "movie", "tv series", "series",
            "trending", "search", "genre", "country", "year", "tag", "category", "quality",
            "watch", "watch movie", "watch now", "tonton", "download", "trailer", "play", "login",
            "register", "read more", "more", "lihat semua", "nonton", "nonton movie", "nonton film",
            "hd", "sd", "cam", "ts", "hdrip", "bluray", "web-dl"
        )
    }
}
