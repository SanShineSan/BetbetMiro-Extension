package com.sad25kag.adikfilm

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

class AdikFilm : MainAPI() {
    companion object {
        private const val DEFAULT_MAIN_URL = "http://178.128.107.129"
    }

    override var mainUrl = DEFAULT_MAIN_URL
    override var name = "AdikFilm"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie)

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Referer" to "$DEFAULT_MAIN_URL/"
    )

    override val mainPage = mainPageOf(
        "/genre/action/" to "Action",
        "/genre/adventure/" to "Adventure",
        "/genre/horror/" to "Horror",
        "/genre/comedy/" to "Comedy",
        "/genre/crime/" to "Crime",
        "/genre/drama/" to "Drama",
        "/genre/fantasy/" to "Fantasy",
        "/genre/mystery/" to "Mystery",
        "/genre/romance/" to "Romance",
        "/genre/science-fiction/" to "Science Fiction",
        "/genre/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = runCatching {
            app.get(buildPageUrl(request.data, page), headers = baseHeaders, referer = mainUrl).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val items = parseListing(document)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && hasNextPage(document, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/?s=$encoded&post_type%5B%5D=post&post_type%5B%5D=tv",
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded"
        )

        val results = linkedMapOf<String, SearchResponse>()
        for (url in searchUrls) {
            val document = runCatching {
                app.get(url, headers = baseHeaders, referer = mainUrl).document
            }.getOrNull() ?: continue
            for (item in parseListing(document)) {
                if (item.name.contains(keyword, ignoreCase = true) || keyword.length <= 3) {
                    results[contentKey(item.url)] = item
                }
            }
            if (results.isNotEmpty()) break
        }
        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = url.toAbsoluteUrl(mainUrl) ?: return null
        val response = runCatching {
            app.get(pageUrl, headers = baseHeaders, referer = mainUrl)
        }.getOrNull() ?: return null
        val document = response.document
        val pageText = cleanText(document.text())
        val rawTitle = document.selectFirst("h1.entry-title, h1, .entry-title, meta[property=og:title], title")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        val title = cleanTitle(rawTitle).ifBlank { titleFromUrl(pageUrl) }
        if (title.isBlank()) return null

        val poster = findPoster(document, pageUrl)
        val tags = parseDetailGenres(document)
        val actors = document
            .select("span[itemprop=actors] a, a[href*='/cast/'], a[href*='/actor/'], a[href*='/director/'], [itemprop=director] a")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..60 }
            .distinct()
            .take(24)
        val year = document.selectFirst("a[href*='/year/'], time[datetime]")?.text()?.firstYear()
            ?: title.firstYear()
            ?: pageText.firstYear()
        val rating = document.selectFirst("[itemprop=ratingValue], .gmr-rating-item, .rating, .score, .imdb, .vote")
            ?.text()
            ?.replace(',', '.')
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        val duration = Regex("""(?i)(\d{1,3})\s*(?:min|menit|m)\b""").find(pageText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val description = cleanDescription(
            document.selectFirst("meta[property=og:description], meta[name=description], div[itemprop=description] > p, .entry-content p, .post-content p, .description, .desc, .sinopsis, .storyline, [itemprop=description]")
                ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        )
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup, a[href*='youtube.com'], a[href*='youtu.be']")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            this.duration = duration ?: 0
            addActors(actors)
            rating?.let { this.score = Score.from10(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.toAbsoluteUrl(mainUrl) ?: data
        val response = runCatching {
            app.get(pageUrl, headers = baseHeaders, referer = mainUrl)
        }.getOrNull() ?: return false
        val document = response.document
        val visited = linkedSetOf<String>()
        var emitted = false

        collectSubtitles(document, pageUrl, subtitleCallback)

        val iframeUrls = linkedSetOf<String>()
        document.select(".gmr-embed-responsive iframe[src], .gmr-embed-responsive iframe[data-src], iframe[src], iframe[data-src]").forEach { element ->
            element.firstAttr("src", "data-src").toAbsoluteUrl(pageUrl)?.let { iframeUrls.add(it) }
        }
        document.select("video source[src], source[src]").forEach { element ->
            element.attr("src").toAbsoluteUrl(pageUrl)?.let { iframeUrls.add(it) }
        }
        collectCandidates(document.html(), pageUrl).forEach { iframeUrls.add(it) }

        for (iframeUrl in iframeUrls.filterNot { it.isNoiseUrl() }) {
            if (!visited.add(iframeUrl)) continue
            if (iframeUrl.isDirectVideoUrl()) {
                emitDirect(iframeUrl, pageUrl, callback)
                emitted = true
                continue
            }

            val beforeExtractor = emitted
            runCatching {
                loadExtractor(iframeUrl, pageUrl, subtitleCallback) { link ->
                    emitted = true
                    callback(link)
                }
            }
            if (emitted && !beforeExtractor) continue

            if (iframeUrl.isKnownInlinePlayer()) {
                if (resolveInlinePlayer(iframeUrl, pageUrl, visited, subtitleCallback, callback)) emitted = true
            }
        }

        return emitted
    }

    private suspend fun resolveInlinePlayer(
        playerUrl: String,
        referer: String,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(playerUrl, headers = baseHeaders + mapOf("Referer" to referer), referer = referer, timeout = 15000L)
        }.getOrNull() ?: return false
        val html = response.text
        val unpacked = runCatching { getAndUnpack(html) }.getOrNull().orEmpty()
        val bodies = listOf(html, unpacked).filter { it.isNotBlank() }
        var emitted = false

        for (body in bodies) {
            for (candidate in collectCandidates(body, playerUrl)) {
                if (!visited.add(candidate) || candidate.isNoiseUrl()) continue
                if (candidate.isSubtitleUrl()) {
                    subtitleCallback(newSubtitleFile("Indonesian", candidate))
                    continue
                }
                if (candidate.isDirectVideoUrl()) {
                    emitDirect(candidate, playerUrl, callback)
                    emitted = true
                    continue
                }
                runCatching {
                    loadExtractor(candidate, playerUrl, subtitleCallback) { link ->
                        emitted = true
                        callback(link)
                    }
                }
            }
        }

        return emitted
    }

    private suspend fun emitDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val type = if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(
            newExtractorLink(name, name, url, type) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        for (element in document.select(cardSelector)) {
            element.toSearchResult()?.let { results[contentKey(it.url)] = it }
        }
        if (results.size < 6) {
            for (anchor in document.select("article a[href], .post a[href], .item a[href], .movie a[href], .film a[href], .ml-item a[href], .result-item a[href], .entry-title a[href]")) {
                anchor.toSearchResult()?.let { results[contentKey(it.url)] = it }
            }
        }
        return results.values.take(80)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (`is`("a[href]")) {
            this
        } else {
            selectFirst("a[title^='Permalink to:'][href], h2.entry-title a[href], h3.entry-title a[href], .entry-title a[href], a[itemprop=url][href], a[rel=bookmark][href], a[href][title], a[href]")
        } ?: return null

        val href = anchor.attr("href").toAbsoluteUrl(mainUrl) ?: return null
        if (!isContentUrl(href)) return null

        val container = anchor.bestContainer()
        val image = container.selectFirst("img[itemprop=image], img[data-src], img[data-original], img[data-lazy-src], img[src]:not([src^='data:'])")
            ?: anchor.selectFirst("img[itemprop=image], img[data-src], img[src]:not([src^='data:'])")
        val title = listOf(
            anchor.attr("title").removePrefix("Permalink to:").trim(),
            anchor.attr("aria-label"),
            container.selectFirst("h2.entry-title a[href], h3.entry-title a[href], .entry-title a[href]")?.text(),
            container.selectFirst("h1, h2.entry-title, h2, h3, .entry-title, .title")?.text(),
            image?.attr("alt"),
            anchor.text(),
            titleFromUrl(href)
        ).firstOrNull { isUsefulTitle(it) }?.let { cleanTitle(it) } ?: return null

        val poster = image?.imageUrl(mainUrl) ?: container.styleImage(mainUrl)
        val text = cleanText(container.text())
        val year = title.firstYear() ?: text.firstYear()
        val score = container.selectFirst(".gmr-rating-item, .rating, .score, .imdb, .vote")?.text()?.replace(',', '.')
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            this.year = year
            score?.let { this.score = Score.from10(it) }
        }
    }

    private fun parseDetailGenres(document: Document): List<String> {
        return document.select(".gmr-moviedata")
            .firstOrNull { it.text().contains("Genre", ignoreCase = true) }
            ?.select("a[href*='/genre/']")
            ?.map { cleanText(it.text()).substringBefore("(").trim() }
            ?.filter { it.length in 2..40 && !it.equals("Trailer", true) && !it.equals("Tonton", true) }
            ?.distinct()
            ?.take(20)
            ?: emptyList()
    }

    private fun buildPageUrl(data: String, page: Int): String {
        val base = data.toAbsoluteUrl(mainUrl) ?: mainUrl
        if (page <= 1) return base
        return base.trimEnd('/') + "/page/$page/"
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.select("a.next, .next a, .pagination a, .nav-links a, .page-numbers a").any { element ->
            val text = element.text().trim()
            val href = element.attr("href")
            text.equals("Next", true) || text == "›" || text == "»" || Regex("""\b${page + 1}\b""").containsMatchIn(text) || href.contains("/page/${page + 1}/")
        }
    }

    private suspend fun collectSubtitles(document: Document, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href*='.srt'], a[href*='.vtt'], a[href*='.ass']").forEach { element ->
            val url = element.firstAttr("src", "href").toAbsoluteUrl(baseUrl)
            if (url != null && url.isSubtitleUrl()) {
                subtitleCallback(newSubtitleFile("Indonesian", url))
            }
        }
    }

    private fun findPoster(document: Document, baseUrl: String): String? {
        return document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.toAbsoluteUrl(baseUrl)
            ?: document.selectFirst("figure.pull-left img[data-src], figure.pull-left img[src]:not([src^='data:']), .poster img[data-src], .thumb img[data-src], .content-thumbnail img[data-src], img[itemprop=image][data-src], article img[data-src]")?.imageUrl(baseUrl)
            ?: document.selectFirst("figure.pull-left img, .poster img, .thumb img, .content-thumbnail img, img[itemprop=image], article img")?.imageUrl(baseUrl)
    }

    private fun Element.bestContainer(): Element {
        var current = this
        repeat(5) {
            val parent = current.parent() ?: return current
            val className = parent.className()
            val isCard = parent.`is`("article") || className.contains("item", true) || className.contains("gmr-box-content", true) || className.contains("content-thumbnail", true)
            val isArchiveOrSearchWrapper = className.contains("site-main", true) || className.contains("main", true) || className.contains("archive", true) || className.contains("search", true)
            if (isArchiveOrSearchWrapper && !isCard) return current
            if (isCard) {
                current = parent
                return current
            }
            val hasImage = parent.select("img").isNotEmpty()
            val hasOnlyOneLink = parent.select("a[href]").size <= 2
            if (hasImage && hasOnlyOneLink) {
                current = parent
            } else {
                return current
            }
        }
        return current
    }

    private fun Element.firstAttr(vararg names: String): String? {
        return names.asSequence().map { attr(it) }.firstOrNull { it.isNotBlank() }
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val candidates = listOf(
            attr("data-src"),
            attr("data-original"),
            attr("data-lazy-src"),
            attr("src").takeUnless { it.startsWith("data:") },
            attr("srcset").split(" ").firstOrNull()?.takeIf { it.isNotBlank() }
        )
        val raw = candidates.firstOrNull { !it.isNullOrBlank() } ?: return null
        return raw.toAbsoluteUrl(baseUrl)?.fixImageQuality()
    }

    private fun Element.styleImage(baseUrl: String): String? {
        return Regex("""url\((["']?)(.*?)\1\)""", RegexOption.IGNORE_CASE).find(attr("style"))
            ?.groupValues
            ?.getOrNull(2)
            ?.toAbsoluteUrl(baseUrl)
    }

    private fun String?.toAbsoluteUrl(baseUrl: String): String? {
        val raw = this?.trim()?.trim('"', '\'', ' ', '\n', '\r', '\t')?.cleanHtml()?.takeIf { it.isNotBlank() } ?: return null
        val decoded = decodeBase64(raw)?.takeIf { it.startsWith("http", true) || it.startsWith("//") || it.startsWith("/") }
        val candidate = decoded ?: raw

        val fixed = when {
            candidate.startsWith("//") -> "https:$candidate"
            candidate.startsWith("http://", true) || candidate.startsWith("https://", true) -> candidate
            candidate.startsWith("/") -> getBaseUrl(baseUrl).trimEnd('/') + candidate
            candidate.startsWith("?") -> baseUrl.substringBefore("?") + candidate
            else -> runCatching { URI(baseUrl).resolve(candidate).toString() }.getOrNull()
        } ?: return null
        return if (fixed.startsWith("http://178.128.107.129", true)) fixed else httpsify(fixed)
    }

    private fun getBaseUrl(url: String): String {
        return runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: mainUrl
    }

    private fun collectCandidates(source: String, baseUrl: String): List<String> {
        val out = linkedSetOf<String>()
        val bodies = mutableListOf(source.cleanHtml())
        decodeBase64(source)?.let { bodies.add(it.cleanHtml()) }
        Regex("""atob\(["']([^"']+)["']\)""", RegexOption.IGNORE_CASE).findAll(source).forEach { match ->
            decodeBase64(match.groupValues.getOrNull(1))?.let { bodies.add(it.cleanHtml()) }
        }

        for (body in bodies) {
            for (regex in candidatePatterns) {
                regex.findAll(body).forEach { match ->
                    match.groupValues.getOrNull(1)?.toAbsoluteUrl(baseUrl)?.let(out::add)
                }
            }
            runCatching { Jsoup.parse(body) }.getOrNull()?.select("iframe[src], iframe[data-src], source[src], video source[src], track[src], a[href]")?.forEach { element ->
                element.firstAttr("src", "data-src", "href").toAbsoluteUrl(baseUrl)?.let(out::add)
            }
        }

        return out.filterNot { it.isNoiseUrl() }
    }

    private fun decodeBase64(value: String?): String? {
        val clean = value?.trim()?.trim('"', '\'', ' ', '\n', '\r', '\t') ?: return null
        if (clean.length < 8 || clean.length % 4 == 1) return null
        return runCatching { String(Base64.getDecoder().decode(clean), Charsets.UTF_8) }.getOrNull()
    }

    private fun cleanTitle(value: String?): String {
        return cleanText(value)
            .replace(Regex("(?i)^nonton\\s+film\\s+"), "")
            .replace(Regex("(?i)^film\\s+"), "")
            .replace(Regex("(?i)^download\\s+streaming\\s+film\\s+"), "")
            .replace(Regex("(?i)^download\\s+nonton\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)^tonton\\s+"), "")
            .substringBefore(" - Adikfilm")
            .substringBefore(" - AdikFilm")
            .substringBefore(" - Adik Film")
            .substringBefore(" – AdikFilm")
            .trim()
    }

    private fun cleanDescription(value: String?): String? {
        return cleanText(value)
            .replace(Regex("""(?i)^sinopsis\s*:?\s*"""), "")
            .takeIf { it.length > 20 }
    }

    private fun cleanText(value: String?): String {
        return value.orEmpty().replace(Regex("\\s+"), " ").trim()
    }

    private fun String.cleanHtml(): String {
        return replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&#38;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("\u0026", "&")
            .replace("\u003d", "=")
            .replace("\u002F", "/")
            .replace("\u003A", ":")
    }

    private fun String.fixImageQuality(): String {
        return replace(Regex("-\\d+x\\d+(?=\\.(?:jpg|jpeg|png|webp))", RegexOption.IGNORE_CASE), "")
    }

    private fun String.firstYear(): Int? {
        return Regex("""\b(19|20)\d{2}\b""").find(this)?.value?.toIntOrNull()
    }

    private fun titleFromUrl(url: String): String {
        return url.trimEnd('/').substringAfterLast('/').replace('-', ' ').replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
        }
    }

    private fun isUsefulTitle(value: String?): Boolean {
        val text = cleanTitle(value)
        if (text.length < 2) return false
        if (text.startsWith("Genre:", true) || text.startsWith("Search Results For:", true)) return false
        val bad = listOf("tonton", "trailer", "download", "genre", "negara", "tahun", "beranda", "pasang iklan", "tweet", "sharer", "kumpulan film")
        return bad.none { text.equals(it, true) }
    }

    private fun isContentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (host != "178.128.107.129") return false
        val path = uri.path.orEmpty().trim('/').lowercase(Locale.ROOT)
        if (path.isBlank()) return false
        if (path.matches(Regex("""(?:page/\d+|genre/.+|country/.+|year/.+|category/.+|tag/.+|author/.+|search/.+|tv|quality/.+|network/.+|director/.+|cast/.+)"""))) return false
        if (path.contains("wp-content") || path.contains("wp-admin") || path.contains("pasang-iklan")) return false
        return !url.isNoiseUrl()
    }

    private fun contentKey(url: String): String {
        return url.substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)
    }

    private fun String.isDirectVideoUrl(): Boolean {
        return contains(Regex("""(?i)\.(m3u8|mp4|webm|mkv|mpd)(?:\?|$)"""))
    }

    private fun String.isSubtitleUrl(): Boolean {
        return contains(Regex("""(?i)\.(srt|vtt|ass)(?:\?|$)"""))
    }

    private fun String.isKnownInlinePlayer(): Boolean {
        val host = runCatching { URI(this).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return host.contains("morencius.com") || host.contains("minochinos.com")
    }

    private fun String.isNoiseUrl(): Boolean {
        val value = lowercase(Locale.ROOT)
        return value.contains("youtube.com") || value.contains("youtu.be") ||
            value.contains("facebook.com") || value.contains("twitter.com") ||
            value.contains("instagram.com") || value.contains("telegram") ||
            value.contains("api.whatsapp") || value.contains("t.me/share") ||
            value.contains("/wp-content/") || value.contains("/wp-json/") ||
            value.contains("/wp-admin/") ||
            value.endsWith(".jpg") || value.endsWith(".jpeg") ||
            value.endsWith(".png") || value.endsWith(".webp") || value.endsWith(".gif") ||
            value.startsWith("javascript:") || value.startsWith("mailto:") ||
            value.startsWith("#") || value.startsWith("data:") ||
            value.contains("pasang-iklan") || value.contains("slot") ||
            value.contains("campaign.") || value.contains("doubleclick.net") ||
            value.contains("histats.com") || value.contains("dtscout.com") ||
            value.contains("dtscdn.com") || value.contains("yandex.ru/watch")
    }

    private val cardSelector = listOf(
        "article.item-infinite",
        "article.item",
        "article",
        ".gmr-item-modulepost",
        ".gmr-box-content",
        ".content-thumbnail",
        ".ml-item",
        ".result-item",
        ".movie",
        ".film",
        ".post"
    ).joinToString(",")

    private val candidatePatterns = listOf(
        Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""(?:file|source|src|url)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4|webm|mpd)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE),
        Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|webm|mpd)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE),
        Regex("""["'](https?://[^"']+(?:morencius|minochinos|embed|player|stream)[^"']*)["']""", RegexOption.IGNORE_CASE)
    )
}
