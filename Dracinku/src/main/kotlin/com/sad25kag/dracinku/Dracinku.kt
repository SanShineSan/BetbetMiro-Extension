package com.sad25kag.dracinku

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Dracinku : MainAPI() {
    override var mainUrl = "https://dracinku.com"
    override var name = "DraCinku"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "" to "Latest Movie",
        "category/drama-china" to "Drama China",
        "category/drama-korea" to "Drama Korea",
        "category/sub-indo" to "Sub Indo",
        "category/sub-english" to "Sub English",
        "category/dramabox" to "Dramabox",
        "category/dramawave" to "Dramawave",
        "category/goodshort" to "GoodShort",
        "category/netshort" to "Netshort",
        "category/flickreels" to "Flickreels",
        "category/flareflow" to "Flareflow",
        "category/shortmax" to "Shortmax",
        "category/melolo" to "Melolo",
        "category/freereel" to "Freereel",
        "category/moboreels" to "Moboreels",
        "category/reelshort" to "Reelshort",
        "category/vigloo" to "Vigloo",
        "category/reelbuzz" to "Reelbuzz",
        "category/iqiyi" to "IQIYI",
        "category/shot-short" to "Shot Short",
        "category/tv-show" to "TV Show",
        "category/idn" to "IDN",
        "category/stardust" to "Stardust"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildListingUrl(request.data, page.coerceAtLeast(1))
        val document = app.get(url, headers = headers, referer = mainUrl).document
        val items = parseCards(document).distinctBy { it.url }
        val hasNext = document.selectFirst(
            "a.next, a[rel=next], .pagination a:contains(${page + 1}), " +
                "a[href*='/page/${page + 1}/'], a[href*='paged=${page + 1}']"
        ) != null || items.size >= 10

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun buildListingUrl(path: String, page: Int): String {
        val clean = path.trim('/')
        return when {
            clean.isBlank() && page <= 1 -> mainUrl
            clean.isBlank() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$clean/"
            else -> "$mainUrl/$clean/page/$page/"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/search/$encoded/"
        )

        val exact = linkedMapOf<String, SearchResponse>()
        val fallback = linkedMapOf<String, SearchResponse>()
        val tokens = keyword.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }

        urls.forEach { url ->
            val document = runCatching { app.get(url, headers = headers, referer = mainUrl).document }.getOrNull()
                ?: return@forEach
            parseCards(document).forEach { item ->
                fallback[item.url] = item
                val lower = item.name.lowercase()
                if (lower.contains(keyword.lowercase()) || tokens.any { lower.contains(it) }) {
                    exact[item.url] = item
                }
            }
            if (exact.isNotEmpty()) return@forEach
        }

        return exact.values.ifEmpty { fallback.values.take(20) }.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers, referer = mainUrl).document
        val title = document.selectFirst("h1.entry-title, h1.post-title, h1, meta[property=og:title], meta[name=title]")
            ?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').replace('-', ' ').cleanTitle()

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image], meta[name=twitter:image], article img, .post img, img")
                ?.let { if (it.hasAttr("content")) it.attr("content") else it.imageAttr() }
        )

        val plot = document.selectFirst(
            "meta[property=og:description], meta[name=description], .entry-content p, .post-content p, article p, .description, .synopsis"
        )?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.cleanText()
            ?.takeIf { it.length > 20 }

        val tags = document.select("a[href*='/category/'], a[href*='/tag/'], .cat-links a, .tags-links a")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() && !it.equals("Watch", true) }
            .distinct()

        val recommendations = parseCards(document).filter { it.url != url }.distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.AsianDrama, url) {
            posterUrl = poster
            this.plot = plot
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
        val emitted = linkedSetOf<String>()
        var found = false

        suspend fun emitDirect(url: String, referer: String, label: String = name): Boolean {
            val clean = url.cleanEscaped().trimEnd(',', ';')
            val key = clean.substringBefore("?token=").substringBefore("&token=").substringBefore("#")
            if (!emitted.add(key)) return false

            return if (clean.contains(".m3u8", true)) {
                generateM3u8(label, clean, referer).forEach(callback)
                true
            } else {
                callback(
                    newExtractorLink(label, label, clean, ExtractorLinkType.VIDEO) {
                        this.referer = referer
                        this.quality = getQualityFromName(clean).takeIf { it != Qualities.Unknown.value }
                            ?: Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "*/*",
                            "Range" to "bytes=0-"
                        )
                    }
                )
                true
            }
        }

        suspend fun handleCandidate(raw: String?, base: String, depth: Int = 0) {
            val fixed = raw?.decodeCandidate()?.absoluteUrl(base) ?: return
            if (fixed.isNoiseUrl()) return
            val key = fixed.substringBefore("?")
            if (!emitted.add("candidate:$key:$depth")) return

            when {
                fixed.isDirectMedia() -> if (emitDirect(fixed, base)) found = true
                fixed.startsWith(mainUrl, true) && depth < 1 -> inspectPage(fixed, base, depth + 1, ::handleCandidate)
                fixed.startsWith("http", true) -> {
                    runCatching { if (loadExtractor(fixed, base, subtitleCallback, callback)) found = true }
                    if (!found && depth < 2) inspectPage(fixed, base, depth + 1, ::handleCandidate)
                }
            }
        }

        inspectPage(data, mainUrl, 0, ::handleCandidate)
        return found
    }

    private suspend fun inspectPage(
        url: String,
        referer: String,
        depth: Int,
        handleCandidate: suspend (String?, String, Int) -> Unit
    ) {
        val response = runCatching { app.get(url, headers = headers, referer = referer).text }.getOrNull() ?: return
        val blobs = mutableListOf(response, response.cleanEscaped())
        runCatching { getAndUnpack(response) }.getOrNull()?.let { blobs.add(it) }
        val document = runCatching { Jsoup.parse(response, url) }.getOrNull()

        document?.select(
            "iframe[src], embed[src], video[src], video source[src], source[src], " +
                "a[href*='embed'], a[href*='player'], a[href*='.mp4'], a[href*='.m3u8'], " +
                "a[href*='video'], [data-src], [data-url], [data-link], [data-href], " +
                "[data-iframe], [data-embed], [data-player], [data-video], [data-file], [data-stream]"
        )?.forEach { element ->
            listOf(
                "src", "href", "data-src", "data-url", "data-link", "data-href", "data-iframe",
                "data-embed", "data-player", "data-video", "data-file", "data-stream"
            ).map { element.attr(it) }
                .filter { it.isNotBlank() }
                .forEach { handleCandidate(it, url, depth) }
        }

        document?.select("option[value]")?.forEach { option ->
            option.attr("value").takeIf { it.isNotBlank() }?.let { handleCandidate(it, url, depth) }
            option.attr("data-url").takeIf { it.isNotBlank() }?.let { handleCandidate(it, url, depth) }
        }

        blobs.forEach { blob ->
            extractCandidatesFromText(blob).forEach { handleCandidate(it, url, depth) }
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val selectors = listOf(
            "article",
            ".post",
            ".type-post",
            ".latestpost",
            ".item",
            ".ml-item",
            ".movie",
            ".movies",
            ".card",
            ".grid-item",
            "a[href*='/20']"
        )

        selectors.forEach { selector ->
            document.select(selector).forEach { element ->
                element.toSearchResult()?.let { results[it.url] = it }
            }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = when {
            this.`is`("a[href]") -> this
            else -> selectFirst("h1 a[href], h2 a[href], h3 a[href], a[rel=bookmark], a[href*='/20']")
                ?: selectFirst("a[href]")
                ?: return null
        }

        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href").absoluteUrl(mainUrl).orEmpty() }
        if (!href.isDracinkuPostUrl()) return null

        val rawTitle = listOf(
            selectFirst("h1")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst(".post-title")?.text(),
            selectFirst("[class*=title]")?.text(),
            anchor.attr("title"),
            anchor.attr("aria-label"),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() && !it.equals("Watch", true) && !it.equals("Full HD", true) }
            ?.cleanTitle()
            ?.takeIf { it.length >= 2 }
            ?: return null

        val poster = fixUrlNull(selectFirst("img")?.imageAttr())

        return newMovieSearchResponse(rawTitle, href, TvType.AsianDrama) {
            posterUrl = poster
        }
    }

    private fun extractCandidatesFromText(text: String): List<String> {
        val clean = text.cleanEscaped()
        val results = linkedSetOf<String>()

        Regex("""https?://[^"'\s<>)\\\]}]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.cleanEscaped().trimEnd(',', ';', '.') }
            .filterNot { it.isNoiseUrl() }
            .forEach { results.add(it) }

        Regex("""(?:file|source|src|url|iframe|embed|player|video)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filter { it.isNotBlank() }
            .forEach { results.add(it) }

        Regex("""(?:atob|base64Decode)\(["']([^"']{16,})["']\)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1)?.decodeBase64OrNull() }
            .forEach { decoded ->
                results.add(decoded)
                Regex("""https?://[^"'\s<>)\\\]}]+""", RegexOption.IGNORE_CASE)
                    .findAll(decoded)
                    .map { it.value.cleanEscaped() }
                    .forEach { results.add(it) }
            }

        return results.toList()
    }

    private fun String.decodeCandidate(): String {
        val value = cleanEscaped().trim().trim('"', '\'')
        if (value.startsWith("http", true) || value.startsWith("//") || value.startsWith("/")) return value
        return value.decodeBase64OrNull()?.cleanEscaped()?.trim('"', '\'') ?: value
    }

    private fun String.decodeBase64OrNull(): String? {
        val normalized = trim().replace('-', '+').replace('_', '/')
        return runCatching { base64Decode(normalized) }.getOrNull()
    }

    private fun Element.imageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-original") -> attr("abs:data-original")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("src") -> attr("abs:src")
            else -> null
        }
    }

    private fun String.isDracinkuPostUrl(): Boolean {
        val fixed = cleanEscaped().trimEnd('/')
        return fixed.startsWith(mainUrl, true) && Regex("""/20\d{2}/\d{2}/\d{2}/[^/?#]+""").containsMatchIn(fixed)
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") ||
            lower.contains(".mkv") || lower.contains("videoplayback") || lower.contains("mime_type=video")
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("google-analytics") || lower.contains("googletagmanager") ||
            lower.contains("doubleclick") || lower.contains("facebook.com/tr") ||
            lower.contains("wp-json") || lower.contains("/wp-content/") && (
                lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".jpg") ||
                    lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") ||
                    lower.endsWith(".svg") || lower.endsWith(".woff") || lower.endsWith(".woff2")
                )
    }

    private fun String.absoluteUrl(baseUrl: String): String? {
        val clean = trim().cleanEscaped()
        if (clean.isBlank() || clean == "#" || clean.startsWith("javascript:", true) || clean.startsWith("data:", true)) return null
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull() ?: fixUrlNull(clean)
        }
    }

    private fun String.cleanEscaped(): String {
        return replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("\\u003f", "?")
            .replace("\\u003F", "?")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .trim()
    }

    private fun String.cleanText(): String {
        return cleanEscaped()
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return cleanText()
            .replace(Regex("""\s+[-|–]\s+DraCinku.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Full\s*HD.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\[[^\]]*Sub[^\]]*\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Nonton\s+""", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
