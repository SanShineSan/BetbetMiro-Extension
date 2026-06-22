package com.sad25kag.bioskopkeren

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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class BioskopKeren : MainAPI() {
    override var mainUrl = "http://134.209.20.140"
    override var name = "BioskopKeren"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override var lang = "id"

    override val supportedTypes = setOf(TvType.Movie)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "best-rating/" to "Best Rating",
        "genre/action/" to "Action",
        "genre/adventure/" to "Adventure",
        "genre/animation/" to "Animation",
        "genre/comedy/" to "Comedy",
        "genre/crime/" to "Crime",
        "genre/documentary/" to "Documentary",
        "genre/drama/" to "Drama",
        "genre/family/" to "Family",
        "genre/fantasy/" to "Fantasy",
        "genre/history/" to "History",
        "genre/horror/" to "Horror",
        "genre/mystery/" to "Mystery",
        "genre/romance/" to "Romance",
        "genre/science-fiction/" to "Science Fiction",
        "genre/thriller/" to "Thriller",
        "genre/war/" to "War"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = buildPageUrl(request.data, page)
        val document = app.get(pageUrl, headers = siteHeaders, referer = "$mainUrl/").document
        val cards = parseCards(document).distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, cards, isHorizontalImages = false),
            hasNext = hasNextPage(document, page)
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded&post_type%5B%5D=post&post_type%5B%5D=tv",
            "$mainUrl/?s=$encoded"
        )

        val results = linkedMapOf<String, SearchResponse>()
        urls.forEach { url ->
            val document = runCatching {
                app.get(url, headers = siteHeaders, referer = "$mainUrl/").document
            }.getOrNull() ?: return@forEach

            parseCards(document).forEach { item ->
                if (
                    item.name.contains(cleanQuery, ignoreCase = true) ||
                    item.url.contains(cleanQuery.slugQuery(), ignoreCase = true)
                ) {
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
        val document = app.get(fixedUrl, headers = siteHeaders, referer = "$mainUrl/").document

        val title = document.selectFirst(
            "h1.entry-title, .gmr-movie-data h1.entry-title, meta[property=og:title], meta[name=title]"
        )?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() && !it.isUiText() }
            ?: fixedUrl.slugTitle()

        val poster = document.selectFirst(
            "meta[property=og:image], meta[name=twitter:image], " +
                ".gmr-movie-data img, .entry-content img, img.wp-post-image"
        )?.let { if (it.hasAttr("content")) it.attr("content") else it.getImageAttr() }
            ?.let { resolveUrl(it, fixedUrl) ?: fixUrlNull(it) }
            ?.takeIf { !isBadImage(it) }

        val plot = extractPlot(document)
        val tags = document.select(".content-moviedata a[href*='/genre/'], .gmr-moviedata a[href*='/genre/']")
            .map { it.text().cleanTitle() }
            .filter { it.isNotBlank() && !it.isUiText() }
            .distinct()
            .take(20)

        val year = document.selectFirst(".content-moviedata a[href*='/year/'], .gmr-moviedata a[href*='/year/']")
            ?.text()
            ?.toIntOrNull()
            ?: extractYear(title)
            ?: extractYear(document.text())

        val recommendations = parseCards(document)
            .filter { it.url != fixedUrl }
            .distinctBy { it.url }
            .take(24)

        return newMovieLoadResponse(
            title,
            fixedUrl,
            TvType.Movie,
            fixedUrl
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
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
        val watchUrl = normalizeProviderUrl(fixUrl(data))
        val document = app.get(
            watchUrl,
            headers = siteHeaders,
            referer = "$mainUrl/",
            timeout = 30L
        ).document

        val iframeUrls = linkedSetOf<String>()
        collectPlayerUrls(document, watchUrl).forEach { iframeUrls.add(it) }

        collectServerPageUrls(document, watchUrl).forEach { serverPage ->
            if (serverPage == watchUrl) return@forEach
            val serverDocument = runCatching {
                app.get(serverPage, headers = siteHeaders, referer = watchUrl, timeout = 30L).document
            }.getOrNull() ?: return@forEach

            collectPlayerUrls(serverDocument, serverPage).forEach { iframeUrls.add(it) }
        }

        var found = false
        iframeUrls
            .filterNot { isBadPlaybackUrl(it) }
            .forEach { iframeUrl ->
                found = runCatching {
                    loadExtractor(iframeUrl, watchUrl, subtitleCallback, callback)
                }.getOrDefault(false) || found
            }

        return found
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article.item-infinite, article.item, " +
                ".gmr-box-content:has(h2.entry-title a), .item:has(h2.entry-title a)"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }

        if (results.isEmpty()) {
            document.select("h2.entry-title a[href], a[rel=bookmark][href]:has(img)")
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
            selectFirst("h2.entry-title a[href], .entry-title a[href], a[rel=bookmark][href]:has(img)")
                ?: return null
        }

        val href = normalizeProviderUrl(resolveUrl(anchor.attr("href"), mainUrl) ?: return null)
        if (!isProviderUrl(href) || isBlockedUrl(href)) return null

        val title = listOf(
            selectFirst("h2.entry-title a")?.text(),
            selectFirst(".entry-title a")?.text(),
            anchor.text(),
            anchor.attr("title"),
            selectFirst("img[alt]")?.attr("alt")
        ).mapNotNull {
            it?.cleanTitle()?.takeIf { clean -> clean.isNotBlank() && !clean.isUiText() }
        }.firstOrNull() ?: return null

        val poster = extractPosterUrl(this, anchor)

        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            year = extractYear(title) ?: extractYear(text())
        }
    }

    private fun collectPlayerUrls(document: Document, pageUrl: String): List<String> {
        val results = linkedSetOf<String>()

        document.select(
            ".player-wrap iframe[src], .player-wrap iframe[data-src], " +
                ".gmr-pagi-player iframe[src], .gmr-pagi-player iframe[data-src], " +
                ".gmr-embed-responsive iframe[src], .gmr-embed-responsive iframe[data-src], " +
                "iframe[src], iframe[data-src], embed[src], source[src], video[src]"
        ).forEach { element ->
            listOf("src", "data-src", "href")
                .mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                .mapNotNull { resolveUrl(it, pageUrl) }
                .forEach { results.add(it) }
        }

        document.select(
            ".player-wrap option[value], .gmr-pagi-player option[value], " +
                ".server option[value], .mirror option[value], select option[value], " +
                "[data-iframe], [data-frame], [data-src], [data-url], [data-link]"
        ).forEach { element ->
            listOf("value", "data-iframe", "data-frame", "data-src", "data-url", "data-link")
                .mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                .flatMap { expandServerValue(it, pageUrl) }
                .forEach { results.add(it) }
        }

        return results.toList()
    }

    private fun collectServerPageUrls(document: Document, pageUrl: String): List<String> {
        return document.select(
            ".muvipro-player-tabs a[href], .gmr-player-tabs a[href], " +
                ".server a[href], .mirror a[href], .player-nav a[href]"
        ).mapNotNull { resolveUrl(it.attr("href"), pageUrl) }
            .map { normalizeProviderUrl(it) }
            .filter { isProviderUrl(it) && !isBlockedUrl(it) }
            .distinct()
    }

    private fun expandServerValue(value: String, pageUrl: String): List<String> {
        val results = linkedSetOf<String>()
        val clean = value.trim().decodeEscaped()
        if (clean.isBlank() || clean == "#") return emptyList()

        resolveUrl(clean, pageUrl)?.let { results.add(it) }

        val urlDecoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        if (urlDecoded != clean) {
            resolveUrl(urlDecoded, pageUrl)?.let { results.add(it) }
            extractIframeUrls(urlDecoded, pageUrl).forEach { results.add(it) }
        }

        if (clean.contains("<iframe", true) || clean.contains("<embed", true) || clean.contains("<source", true)) {
            extractIframeUrls(clean, pageUrl).forEach { results.add(it) }
        }

        decodeBase64(clean)?.let { decoded ->
            resolveUrl(decoded, pageUrl)?.let { results.add(it) }
            extractIframeUrls(decoded, pageUrl).forEach { results.add(it) }
        }

        return results.toList()
    }

    private fun extractIframeUrls(html: String, pageUrl: String): List<String> {
        val doc = Jsoup.parse(html.decodeEscaped(), pageUrl)
        return doc.select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href]")
            .mapNotNull { element ->
                listOf("src", "data-src", "href")
                    .mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                    .firstOrNull()
            }
            .mapNotNull { resolveUrl(it, pageUrl) }
            .filterNot { isBadPlaybackUrl(it) }
            .distinct()
    }

    private fun extractPlot(document: Document): String? {
        document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")
            ?.cleanPlot()
            ?.let { return it }

        val entry = document.selectFirst(".entry-content.entry-content-single, .entry-content")
            ?: return null

        val clone = entry.clone()
        clone.select("script, style, .content-moviedata, .gmr-moviedata, h1, h2, h3").remove()

        return clone.selectFirst("p")?.text()?.cleanPlot()
            ?: clone.text().cleanPlot()
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a[rel=next], .pagination a:contains(Next), .pagination a:contains(Berikutnya), " +
                ".page-numbers.next, .nav-links a[href*='/page/${page + 1}'], a[href*='/page/${page + 1}/']"
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
                    val root = "${uri.scheme ?: "http"}://${uri.host}"
                    normalizeProviderUrl("$root$clean")
                }
                else -> normalizeProviderUrl(URI(base).resolve(clean).toString())
            }
        }.getOrElse {
            runCatching { fixUrl(clean) }.getOrNull()
        }
    }

    private fun normalizeProviderUrl(url: String): String {
        return url.replace("https://134.209.20.140", mainUrl)
            .replace("http://www.134.209.20.140", mainUrl)
            .trim()
    }

    private fun isProviderUrl(url: String): Boolean {
        return runCatching {
            URI(url).host.orEmpty().lowercase().removePrefix("www.") == "134.209.20.140"
        }.getOrDefault(url.startsWith(mainUrl))
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().trim('/').lowercase() }
            .getOrDefault(url.substringAfter(mainUrl, "").trim('/').lowercase())

        if (path.isBlank()) return false

        val blockedPrefixes = listOf(
            "genre/", "country/", "quality/", "year/", "tag/", "director/", "cast/",
            "blog/", "page/", "search", "feed", "wp-json", "wp-content", "wp-admin"
        )

        val blockedExact = setOf(
            "genre", "country", "quality", "year", "tag", "director", "cast",
            "dmca", "pasang-iklan", "cara-download", "privacy", "contact", "terms", "about"
        )

        val lower = url.lowercase()
        return path in blockedExact ||
            blockedPrefixes.any { path.startsWith(it) } ||
            lower.endsWith(".jpg") ||
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
            lower.contains("dtscout") ||
            lower.contains("dtscdn") ||
            lower.contains("histats") ||
            lower.contains("adsbygoogle") ||
            lower.contains("googlesyndication") ||
            lower.contains("doubleclick") ||
            lower.contains("analytics") ||
            lower.contains("googletagmanager") ||
            lower.contains("cloudflareinsights") ||
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
            val image = box.selectFirst(
                "img[data-src], img[data-lazy-src], img[data-original], img[data-img], img[data-image], " +
                    "img[data-poster], img[src], source[data-srcset], source[srcset]"
            ) ?: return@forEach

            image.getImageAttr()
                ?.let { raw -> resolveUrl(raw, mainUrl) ?: fixUrlNull(raw) }
                ?.takeIf { !isBadImage(it) }
                ?.let { return it }
        }

        return null
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("data-src")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("data-original") -> attr("data-original")
            hasAttr("data-img") -> attr("data-img")
            hasAttr("data-image") -> attr("data-image")
            hasAttr("data-poster") -> attr("data-poster")
            hasAttr("poster") -> attr("poster")
            hasAttr("data-srcset") -> pickSrcSet(attr("data-srcset"))
            hasAttr("srcset") -> pickSrcSet(attr("srcset"))
            hasAttr("src") -> attr("src")
            else -> null
        }
    }

    private fun pickSrcSet(srcset: String): String? {
        return srcset.split(",")
            .map { it.trim().substringBefore(" ").trim() }
            .filter { it.isNotBlank() }
            .lastOrNull()
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

    private fun extractYear(text: String): Int? {
        return Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun decodeBase64(value: String): String? {
        val clean = value.trim()
        if (clean.length < 16 || !clean.matches(Regex("""^[A-Za-z0-9+/=_-]+$"""))) return null

        return runCatching {
            val normalized = clean.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun String.slugTitle(): String {
        return substringBefore("?")
            .trimEnd('/')
            .substringAfterLast("/")
            .replace("-", " ")
            .cleanTitle()
            .ifBlank { "BioskopKeren" }
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

        return if (cleaned.contains("%3A%2F%2F", true) || cleaned.contains("%3C", true)) {
            runCatching { URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
        } else {
            cleaned
        }
    }

    private fun String.cleanTitle(): String {
        return decodeEscaped()
            .replace(Regex("""(?i)^\s*permalink\s+ke\s*:\s*"""), "")
            .replace(Regex("""(?i)^\s*permalink\s+to\s*:\s*"""), "")
            .replace(Regex("""\s+-\s+BIOSKOPKEREN.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+BIOSKOPKEREN.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Nonton\s+Film\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Streaming\s+Online.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Download.*$""", RegexOption.IGNORE_CASE), "")
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
            "hd", "sd", "cam", "ts", "hdrip", "bluray", "web-dl", "semua tipe", "film"
        )
    }
}
