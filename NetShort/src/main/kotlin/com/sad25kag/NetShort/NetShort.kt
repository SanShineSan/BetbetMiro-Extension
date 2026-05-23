package com.sad25kag.NetShort

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageRequest
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class NetShort : MainAPI() {
    override var mainUrl = "https://netshort.com"
    override var name = "NetShort"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val homeUrl = "$mainUrl/id"

    override val mainPage = mainPageOf(
        homeUrl to "Beranda",
        "$homeUrl/dramas/epic-dramas" to "Drama Epik",
        "$homeUrl/dramas/popular-series" to "Serial Populer",
        "$homeUrl/dramas/all-plots" to "Semua Alur",
        "$homeUrl/all-episodes" to "Semua Episode",
    )

    private fun String.cleanText(): String {
        return this
            .replace(Regex("\\s+"), " ")
            .replace("Tonton Sekarang", "", ignoreCase = true)
            .replace("Play Now", "", ignoreCase = true)
            .trim()
    }

    private fun absoluteUrl(url: String?): String {
        val clean = url?.trim().orEmpty()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        return when {
            clean.isBlank() -> ""
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("/") -> "$mainUrl$clean"
            else -> "$homeUrl/$clean"
        }
    }

    private fun safeDecode(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun Element.poster(): String? {
        val img = selectFirst("img") ?: return null

        return absoluteUrl(
            img.attr("data-src").ifBlank {
                img.attr("data-original").ifBlank {
                    img.attr("data-lazy-src").ifBlank {
                        img.attr("src")
                    }
                }
            }
        ).takeIf { it.isNotBlank() }
    }

    private fun Element.cardTitle(): String {
        return (
            selectFirst("img[alt]")?.attr("alt")
                ?: selectFirst("h1, h2, h3, .title, .name, [class*=title], [class*=name]")?.text()
                ?: selectFirst("a[href*='/episode/'], a[href*='/full-episodes/']")?.text()
                ?: text()
        ).orEmpty().cleanText()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(
            "a[href*='/id/episode/'], a[href*='/episode/'], a[href*='/id/full-episodes/'], a[href*='/full-episodes/']"
        ) ?: return null

        val href = absoluteUrl(anchor.attr("href"))
        if (!href.contains("/episode/") && !href.contains("/full-episodes/")) return null

        val title = cardTitle().ifBlank {
            anchor.text().cleanText()
        }

        if (title.isBlank()) return null

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster()
            posterHeaders = defaultHeaders
        }
    }

    private fun Document.parseCards(): List<SearchResponse> {
        val selectors = listOf(
            "article",
            "li",
            ".swiper-slide",
            ".drama-item",
            ".video-item",
            ".movie-item",
            ".recommend-item",
            ".recommend-list > div",
            "div:has(a[href*='/episode/'])",
            "div:has(a[href*='/full-episodes/'])"
        )

        return selectors
            .flatMap { selector -> select(selector) }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.parseCardsInside(): List<SearchResponse> {
        return select(
            "article, li, .swiper-slide, .drama-item, .video-item, .movie-item, .recommend-item, div:has(a[href*='/episode/']), div:has(a[href*='/full-episodes/'])"
        )
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Document.parseHomeRows(): List<HomePageList> {
        val rows = mutableListOf<HomePageList>()

        select("h1, h2, h3").forEach { heading ->
            val rowName = heading.text().cleanText()

            if (
                rowName.isBlank() ||
                rowName.equals("NetShort", true) ||
                rowName.equals("Tentang", true) ||
                rowName.equals("Hubungi Kami", true) ||
                rowName.equals("Komunitas", true) ||
                rowName.equals("Unduh Aplikasi", true) ||
                rowName.equals("Bahasa Indonesia", true)
            ) return@forEach

            val items = mutableListOf<SearchResponse>()

            var sibling = heading.nextElementSibling()
            var guard = 0

            while (sibling != null && guard < 10) {
                if (sibling.`is`("h1, h2, h3")) break
                items.addAll(sibling.parseCardsInside())
                sibling = sibling.nextElementSibling()
                guard++
            }

            if (items.isEmpty()) {
                heading.parent()?.let { items.addAll(it.parseCardsInside()) }
            }

            val cleanItems = items.distinctBy { it.url }.take(24)
            if (cleanItems.isNotEmpty()) {
                rows.add(HomePageList(rowName, cleanItems, isHorizontalImages = false))
            }
        }

        if (rows.isEmpty()) {
            val items = parseCards()
            if (items.isNotEmpty()) {
                rows.add(HomePageList("NetShort", items, isHorizontalImages = false))
            }
        }

        return rows.distinctBy { it.name }
    }

    override suspend fun getMainPage(
        page: Int,
        request: HomePageRequest
    ): HomePageResponse {
        val pageUrl = when {
            page <= 1 -> request.data
            request.data.contains("?") -> "${request.data}&page=$page"
            else -> "${request.data}?page=$page"
        }

        val document = app.get(
            pageUrl,
            headers = defaultHeaders
        ).document

        return if (request.data == homeUrl) {
            newHomePageResponse(document.parseHomeRows(), hasNext = false)
        } else {
            val items = document.parseCards()
            val hasNext = document.selectFirst(
                "a[href*='page=${page + 1}'], a[rel=next], a:contains(${page + 1})"
            ) != null

            newHomePageResponse(
                HomePageList(request.name, items, isHorizontalImages = false),
                hasNext = hasNext
            )
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val results = mutableListOf<SearchResponse>()

        val urls = listOf(
            "$homeUrl/search?keyword=$encoded",
            "$homeUrl/search?q=$encoded",
            "$homeUrl/all-episodes",
            "$homeUrl/dramas/popular-series",
            "$homeUrl/dramas/epic-dramas",
            "$homeUrl/dramas/all-plots",
        )

        urls.forEach { url ->
            runCatching {
                val document = app.get(url, headers = defaultHeaders).document
                results.addAll(
                    document.parseCards().filter {
                        it.name.contains(keyword, ignoreCase = true)
                    }
                )
            }
        }

        if (results.isNotEmpty()) {
            return results.distinctBy { it.url }
        }

        for (page in 1..4) {
            runCatching {
                val document = app.get(
                    "$homeUrl/all-episodes?page=$page",
                    headers = defaultHeaders
                ).document

                results.addAll(
                    document.parseCards().filter {
                        it.name.contains(keyword, ignoreCase = true)
                    }
                )
            }
        }

        return results.distinctBy { it.url }
    }

    private fun titleFromDocument(document: Document, url: String): String {
        val h1 = document.selectFirst("h1")?.text().orEmpty().cleanText()

        val withoutEpisode = h1
            .replace(Regex("(?i)\\s+episode\\s+\\d+.*$"), "")
            .cleanText()

        val h2 = document.selectFirst("h2")?.text().orEmpty().cleanText()

        return withoutEpisode
            .ifBlank { h2 }
            .ifBlank {
                url.substringAfterLast("/")
                    .substringBeforeLast("-")
                    .replace("-", " ")
                    .cleanText()
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            .ifBlank { "NetShort" }
    }

    private fun parseEpisodeNumber(text: String, fallback: Int? = null): Int? {
        return Regex("(?i)(?:episode|ep)?\\s*(\\d+)")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: fallback
    }

    private fun Document.parseEpisodes(currentUrl: String, seriesTitle: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val currentNumber = parseEpisodeNumber(
            selectFirst("h1")?.text().orEmpty(),
            currentUrl.substringAfterLast("-ep-", "").toIntOrNull() ?: 1
        )

        episodes.add(
            newEpisode(currentUrl) {
                name = "Episode ${currentNumber ?: 1}"
                episode = currentNumber ?: 1
            }
        )

        select("a[href*='/episode/'], a[href*='/full-episodes/']").forEachIndexed { index, anchor ->
            val href = absoluteUrl(anchor.attr("href"))
            if (href.isBlank()) return@forEachIndexed

            val text = anchor.text().cleanText()
            val number = parseEpisodeNumber(text, index + 1)
                ?: href.substringAfterLast("-ep-", "").toIntOrNull()

            episodes.add(
                newEpisode(href) {
                    name = if (number != null) "Episode $number" else seriesTitle
                    episode = number
                }
            )
        }

        return episodes
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.episode ?: Int.MAX_VALUE }
                    .thenBy { it.name }
            )
    }

    private fun Document.parsePoster(title: String): String? {
        val selectors = listOf(
            "meta[property=og:image]",
            "meta[name=twitter:image]"
        )

        selectors.forEach { selector ->
            val content = selectFirst(selector)?.attr("content")
            val url = absoluteUrl(content)
            if (url.isNotBlank()) return url
        }

        return selectFirst("img[alt*=$title], img")?.let {
            absoluteUrl(
                it.attr("data-src").ifBlank {
                    it.attr("data-original").ifBlank {
                        it.attr("data-lazy-src").ifBlank {
                            it.attr("src")
                        }
                    }
                }
            )
        }?.takeIf { it.isNotBlank() }
    }

    private fun Document.parsePlot(): String? {
        val selectors = listOf(
            ".desc",
            ".description",
            "[class*=desc]",
            "[class*=summary]",
            "meta[name=description]",
            "meta[property=og:description]"
        )

        selectors.forEach { selector ->
            val element = selectFirst(selector) ?: return@forEach
            val text = element.attr("content").ifBlank { element.text() }.cleanText()
            if (text.length > 30) return text
        }

        return select("p")
            .map { it.text().cleanText() }
            .firstOrNull { it.length > 60 }
    }

    private fun Document.parseTags(): List<String> {
        return select("a[href*='/tag/'], a[href*='/dramas/']")
            .map { it.text().cleanText() }
            .filter {
                it.isNotBlank() &&
                    !it.equals("Beranda", true) &&
                    !it.equals("Serial Drama", true) &&
                    !it.equals("Unduh", true) &&
                    !it.equals("Blog", true) &&
                    !it.equals("Semua Episode", true)
            }
            .distinct()
            .take(16)
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = absoluteUrl(url)
        val document = app.get(
            fixedUrl,
            headers = defaultHeaders
        ).document

        val title = titleFromDocument(document, fixedUrl)
        val episodes = document.parseEpisodes(fixedUrl, title)

        return newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, episodes) {
            posterUrl = document.parsePoster(title)
            posterHeaders = defaultHeaders
            plot = document.parsePlot()
            tags = document.parseTags()
            recommendations = document.parseCards()
                .filterNot { it.url == fixedUrl }
                .distinctBy { it.url }
                .take(18)
        }
    }

    private fun String.unescapeVideoText(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u003F", "?")
            .replace("\\u002D", "-")
            .replace("\\u005C", "\\")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\\"", "\"")
    }

    private fun normalizeMediaUrl(url: String, pageUrl: String): String {
        val clean = safeDecode(
            url.unescapeVideoText()
                .trim()
                .trim('"', '\'', '`', ',', ';', ')', ']', '}')
        )

        if (clean.isBlank()) return ""

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("/") -> "$mainUrl$clean"
            clean.contains(".m3u8", true) || clean.contains(".mp4", true) -> {
                runCatching {
                    val uri = URI(pageUrl)
                    "${uri.scheme}://${uri.host}${if (clean.startsWith("/")) "" else "/"}$clean"
                }.getOrDefault(clean)
            }
            else -> clean
        }
    }

    private fun extractMediaUrls(raw: String, pageUrl: String): List<String> {
        val text = raw.unescapeVideoText()

        val results = mutableListOf<String>()

        val directPatterns = listOf(
            Regex("""https?:\/\/[^"'\s\\<>]+?\.m3u8[^"'\s\\<>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?:\/\/[^"'\s\\<>]+?\.mp4[^"'\s\\<>]*""", RegexOption.IGNORE_CASE),
            Regex("""\/\/[^"'\s\\<>]+?\.m3u8[^"'\s\\<>]*""", RegexOption.IGNORE_CASE),
            Regex("""\/\/[^"'\s\\<>]+?\.mp4[^"'\s\\<>]*""", RegexOption.IGNORE_CASE)
        )

        directPatterns.forEach { regex ->
            regex.findAll(text).forEach { match ->
                results.add(match.value)
            }
        }

        val keyPatterns = listOf(
            "url",
            "src",
            "source",
            "video",
            "videoUrl",
            "video_url",
            "playUrl",
            "play_url",
            "hls",
            "hlsUrl",
            "hls_url",
            "m3u8",
            "mp4"
        )

        keyPatterns.forEach { key ->
            val regex = Regex(
                """"$key"\s*:\s*"([^"]+)"""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )

            regex.findAll(text).forEach { match ->
                val candidate = match.groupValues.getOrNull(1).orEmpty()
                if (
                    candidate.contains(".m3u8", true) ||
                    candidate.contains(".mp4", true)
                ) {
                    results.add(candidate)
                }
            }
        }

        val singleQuoteRegex = Regex(
            """(?:url|src|source|videoUrl|playUrl|hlsUrl|m3u8|mp4)\s*:\s*'([^']+)'""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        singleQuoteRegex.findAll(text).forEach { match ->
            val candidate = match.groupValues.getOrNull(1).orEmpty()
            if (
                candidate.contains(".m3u8", true) ||
                candidate.contains(".mp4", true)
            ) {
                results.add(candidate)
            }
        }

        return results
            .map { normalizeMediaUrl(it, pageUrl) }
            .filter {
                it.startsWith("http", true) &&
                    (
                        it.contains(".m3u8", true) ||
                            it.contains(".mp4", true)
                        ) &&
                    !it.contains(".jpg", true) &&
                    !it.contains(".jpeg", true) &&
                    !it.contains(".png", true) &&
                    !it.contains(".webp", true) &&
                    !it.contains(".gif", true)
            }
            .distinct()
    }

    private suspend fun collectScriptTexts(document: Document): List<String> {
        val scriptUrls = document.select("script[src]")
            .mapNotNull { script ->
                absoluteUrl(script.attr("src")).takeIf { it.isNotBlank() }
            }
            .filter {
                it.contains("/_nuxt/", true) ||
                    it.contains(".js", true)
            }
            .distinct()
            .take(12)

        return scriptUrls.mapNotNull { scriptUrl ->
            runCatching {
                app.get(
                    scriptUrl,
                    headers = defaultHeaders + mapOf("Referer" to homeUrl)
                ).text
            }.getOrNull()
        }
    }

    private fun Document.extractMetaVideoUrls(pageUrl: String): List<String> {
        val results = mutableListOf<String>()

        select(
            "meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player], video[src], source[src]"
        ).forEach { element ->
            val candidate = element.attr("content").ifBlank {
                element.attr("src")
            }

            if (candidate.isNotBlank()) {
                results.add(candidate)
            }
        }

        return results
            .map { normalizeMediaUrl(it, pageUrl) }
            .filter {
                it.startsWith("http", true) &&
                    (
                        it.contains(".m3u8", true) ||
                            it.contains(".mp4", true)
                        )
            }
            .distinct()
    }

    private suspend fun findPlayableLinks(pageUrl: String): List<String> {
        val response = app.get(
            pageUrl,
            headers = defaultHeaders + mapOf(
                "Referer" to homeUrl,
                "Origin" to mainUrl
            )
        )

        val document = response.document
        val links = mutableListOf<String>()

        links.addAll(document.extractMetaVideoUrls(pageUrl))
        links.addAll(extractMediaUrls(response.text, pageUrl))

        if (links.isEmpty()) {
            collectScriptTexts(document).forEach { scriptText ->
                links.addAll(extractMediaUrls(scriptText, pageUrl))
            }
        }

        return links.distinct()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = absoluteUrl(data)
        val links = findPlayableLinks(pageUrl)

        links.forEachIndexed { index, mediaUrl ->
            val isM3u8 = mediaUrl.contains(".m3u8", ignoreCase = true)

            callback.invoke(
                newExtractorLink(
                    name,
                    "$name ${index + 1}",
                    url = mediaUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = pageUrl
                    quality = if (isM3u8) Qualities.Unknown.value else Qualities.P720.value
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to pageUrl,
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                }
            )
        }

        return links.isNotEmpty()
    }

    private val defaultHeaders
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to homeUrl
        )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}