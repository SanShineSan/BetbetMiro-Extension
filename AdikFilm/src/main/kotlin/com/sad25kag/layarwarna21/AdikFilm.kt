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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
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
    override val hasDownloadSupport = false
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
        val pageUrl = buildPageUrl(request.data, page)
        val document = runCatching {
            app.get(pageUrl, headers = baseHeaders, referer = mainUrl).document
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
            "$mainUrl/?s=$encoded"
        )

        val results = linkedMapOf<String, SearchResponse>()
        for (url in searchUrls) {
            val document = runCatching {
                app.get(url, headers = baseHeaders, referer = mainUrl).document
            }.getOrNull() ?: continue

            for (item in parseListing(document)) {
                if (keyword.length <= 3 || item.name.contains(keyword, ignoreCase = true)) {
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
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1[itemprop=name], h1, meta[property=og:title], title")
                ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        ).ifBlank { titleFromUrl(pageUrl) }

        if (title.isBlank()) return null

        val pageText = cleanText(document.text())
        val poster = findPoster(document, pageUrl)
        val description = cleanDescription(
            document.selectFirst("div[itemprop=description] > p, .entry-content-single > p, .entry-content > p, meta[property=og:description], meta[name=description]")
                ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        )
        val tags = document
            .select(".entry-content-single a[href*='/genre/'], .gmr-moviedata a[href*='/genre/'], a[rel~=category][href*='/genre/']")
            .map { cleanText(it.text()).substringBefore("(").trim() }
            .filter { it.length in 2..40 }
            .distinct()
            .take(20)
        val actors = document
            .select("a[href*='/cast/'], a[href*='/actor/'], a[href*='/director/'], span[itemprop=actors] a, [itemprop=director] a")
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

        val visited = linkedSetOf<String>()
        var emitted = false

        collectSubtitles(response.document, pageUrl, subtitleCallback)

        val playerUrls = collectPlayerUrls(response.document, response.text, pageUrl)
        for (playerUrl in playerUrls) {
            if (resolvePlayer(playerUrl, pageUrl, visited, subtitleCallback, callback)) {
                emitted = true
            }
        }

        return emitted
    }

    private suspend fun resolvePlayer(
        rawUrl: String,
        referer: String,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = rawUrl.toAbsoluteUrl(referer) ?: return false
        if (url.isNoiseUrl() || !visited.add(url)) return false

        if (url.isSubtitleUrl()) {
            subtitleCallback(newSubtitleFile("Indonesian", url))
            return false
        }

        if (url.isDirectVideoUrl()) {
            emitDirect(url, referer, callback)
            return true
        }

        var emitted = false
        runCatching {
            loadExtractor(url, referer, subtitleCallback) { link ->
                emitted = true
                callback(link)
            }
        }

        val playerResponse = runCatching {
            app.get(url, headers = baseHeaders + mapOf("Referer" to referer), referer = referer, timeout = 15000L)
        }.getOrNull() ?: return emitted

        val contentType = playerResponse.headers["Content-Type"].orEmpty().lowercase(Locale.ROOT)
        if (contentType.contains("mpegurl") || contentType.startsWith("video/")) {
            emitDirect(url, referer, callback)
            return true
        }

        collectSubtitles(playerResponse.document, url, subtitleCallback)

        for (candidate in collectUrlsFromHtml(playerResponse.text, url)) {
            val candidateUrl = candidate.toAbsoluteUrl(url) ?: continue
            if (candidateUrl.isDirectVideoUrl() && visited.add(candidateUrl)) {
                emitDirect(candidateUrl, url, callback)
                emitted = true
            } else if (!candidateUrl.isNoiseUrl() && visited.add(candidateUrl)) {
                runCatching {
                    loadExtractor(candidateUrl, url, subtitleCallback) { link ->
                        emitted = true
                        callback(link)
                    }
                }
            }
        }

        return emitted
    }

    private fun collectPlayerUrls(document: Document, html: String, baseUrl: String): List<String> {
        val out = linkedSetOf<String>()

        document.select(".gmr-embed-responsive iframe[src], .gmr-embed-responsive iframe[data-src], iframe[src], iframe[data-src]").forEach { element ->
            element.firstAttr("src", "data-src").toAbsoluteUrl(baseUrl)?.let(out::add)
        }

        document.select(".muvipro-player-tabs a[href], ul.nav-tabs a[href], a[href*='server'], a[href*='player']").forEach { element ->
            element.attr("href").toAbsoluteUrl(baseUrl)?.let(out::add)
        }

        document.select("video source[src], source[src], track[src], a[href*='.m3u8'], a[href*='.mp4'], a[href*='.webm']").forEach { element ->
            element.firstAttr("src", "href").toAbsoluteUrl(baseUrl)?.let(out::add)
        }

        document.select(".mobius option[value], .mirror option[value], select option[value], option[value]").forEach { option ->
            val raw = option.attr("value").trim()
            if (raw.startsWith("http", true) || raw.startsWith("//") || raw.startsWith("/")) {
                raw.toAbsoluteUrl(baseUrl)?.let(out::add)
            }

            val decoded = decodeBase64(raw)?.cleanHtml()
            if (!decoded.isNullOrBlank()) {
                collectUrlsFromHtml(decoded, baseUrl).forEach(out::add)
            }
        }

        collectUrlsFromHtml(html, baseUrl).forEach(out::add)

        return out.filterNot { it.isNoiseUrl() }
    }

    private fun collectUrlsFromHtml(source: String, baseUrl: String): List<String> {
        if (source.isBlank()) return emptyList()

        val out = linkedSetOf<String>()
        val bodies = mutableListOf(source.cleanHtml())
        unpackPackerScripts(source).forEach { bodies.add(it.cleanHtml()) }

        val urlPattern = Regex("""(?i)(https?:)?//[^\s"'<>]+(?:/embed/|/stream/|\.m3u8|\.mp4|\.webm)[^\s"'<>]*""")
        val quotedMediaPattern = Regex("""["']((?:https?:)?//[^"']+\.(?:m3u8|mp4|webm)(?:\?[^"']*)?|/[^"']+\.(?:m3u8|mp4|webm)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
        val iframePattern = Regex("""(?i)<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""")

        for (body in bodies) {
            iframePattern.findAll(body).forEach { match ->
                match.groupValues.getOrNull(1)?.toAbsoluteUrl(baseUrl)?.let(out::add)
            }
            quotedMediaPattern.findAll(body).forEach { match ->
                match.groupValues.getOrNull(1)?.toAbsoluteUrl(baseUrl)?.let(out::add)
            }
            urlPattern.findAll(body).forEach { match ->
                match.value.toAbsoluteUrl(baseUrl)?.let(out::add)
            }
        }

        return out.filterNot { it.isNoiseUrl() }
    }

    private fun unpackPackerScripts(source: String): List<String> {
        val out = mutableListOf<String>()
        val pattern = Regex(
            """eval\(function\(p,a,c,k,e,d\).*?return p\}\('((?:\\'|[^'])*)',(\d+),(\d+),'((?:\\'|[^'])*)'\.split\('\|'\)\)\)""",
            RegexOption.DOT_MATCHES_ALL
        )

        pattern.findAll(source).forEach { match ->
            val payload = unescapeJs(match.groupValues.getOrNull(1).orEmpty())
            val radix = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@forEach
            val count = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return@forEach
            val words = match.groupValues.getOrNull(4).orEmpty().split("|")

            var decoded = payload
            for (index in count - 1 downTo 0) {
                val word = words.getOrNull(index).orEmpty()
                if (word.isBlank()) continue
                val key = index.toBase(radix)
                decoded = Regex("""\b${Regex.escape(key)}\b""").replace(decoded, word)
            }
            out.add(decoded)
        }

        return out
    }

    private suspend fun emitDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val type = if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(
            newExtractorLink(name, name, url, type) {
                this.referer = referer
                this.quality = url.qualityFromUrl()
            }
        )
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        val selectors = listOf(
            "article.item-infinite",
            "article.item",
            "article.has-post-thumbnail",
            ".gmr-box-content.gmr-box-archive",
            ".gmr-item-modulepost",
            ".content-thumbnail"
        ).joinToString(",")

        for (element in document.select(selectors)) {
            element.toSearchResult()?.let { results[contentKey(it.url)] = it }
        }

        if (results.size < 6) {
            document.select("h2.entry-title a[href], h3.entry-title a[href], article a[rel=bookmark][href], article a[itemprop=url][href]").forEach { anchor ->
                anchor.toSearchResult()?.let { results[contentKey(it.url)] = it }
            }
        }

        return results.values.take(80)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (`is`("a[href]")) {
            this
        } else {
            selectFirst("h2.entry-title a[href], h3.entry-title a[href], .entry-title a[href], a[itemprop=url][href], a[rel=bookmark][href], a[href][title], a[href]")
        } ?: return null

        val href = anchor.attr("href").toAbsoluteUrl(mainUrl) ?: return null
        if (!isContentUrl(href)) return null

        val container = anchor.bestContainer()
        val image = container.selectFirst("img[itemprop=image], img[data-src], img[data-original], img[data-lazy-src], img[src]:not([src^='data:'])")
            ?: anchor.selectFirst("img[itemprop=image], img[data-src], img[src]:not([src^='data:'])")
        val title = listOf(
            container.selectFirst("h1, h2.entry-title, h2, h3, .entry-title, .title")?.text(),
            anchor.attr("title").removePrefix("Permalink to:").trim(),
            anchor.attr("aria-label"),
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

    private fun collectSubtitles(document: Document, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
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
        repeat(6) {
            val parent = current.parent() ?: return current
            val hasImage = parent.select("img").isNotEmpty()
            val hasTitle = parent.select("h1, h2, h3, .entry-title, .title").isNotEmpty()
            val isCard = parent.className().contains("gmr-box-content", true) || parent.className().contains("item", true)
            if (hasImage || hasTitle || isCard) {
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
            candidate.contains("://").not() && !candidate.contains(" ") -> runCatching { URI(baseUrl).resolve(candidate).toString() }.getOrNull()
            else -> null
        } ?: return null

        return fixed
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrNull() ?: mainUrl
    }

    private fun decodeBase64(value: String?): String? {
        val clean = value?.trim()?.trim('"', '\'', ' ', '\n', '\r', '\t') ?: return null
        if (clean.length < 8 || clean.length % 4 == 1 || clean.any { it.isWhitespace() }) return null
        return runCatching { String(Base64.getDecoder().decode(clean), Charsets.UTF_8) }.getOrNull()
    }

    private fun unescapeJs(value: String): String {
        return value
            .replace("\\\\", "\\")
            .replace("\\'", "'")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    private fun Int.toBase(radix: Int): String {
        if (this == 0) return "0"
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        var number = this
        val builder = StringBuilder()
        while (number > 0) {
            builder.append(chars[number % radix])
            number /= radix
        }
        return builder.reverse().toString()
    }

    private fun cleanTitle(value: String?): String {
        return cleanText(value)
            .replace(Regex("(?i)^nonton\\s+film\\s+"), "")
            .replace(Regex("(?i)^download\\s+streaming\\s+film\\s+"), "")
            .replace(Regex("(?i)^download\\s+nonton\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)^tonton\\s+"), "")
            .substringBefore(" - Adikfilm")
            .substringBefore(" - AdikFilm")
            .trim()
    }

    private fun cleanDescription(value: String?): String? {
        return cleanText(value)
            .substringBefore("Perlu diketahui")
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
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
    }

    private fun String.fixImageQuality(): String {
        return replace(Regex("-\\d+x\\d+(?=\\.(?:jpg|jpeg|png|webp))", RegexOption.IGNORE_CASE), "")
    }

    private fun String.firstYear(): Int? {
        return Regex("""\b(19|20)\d{2}\b""").find(this)?.value?.toIntOrNull()
    }

    private fun String.qualityFromUrl(): Int {
        return Regex("""(?i)(?:^|[^\d])(\d{3,4})p(?:[^\d]|$)""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun titleFromUrl(url: String): String {
        return url.trimEnd('/').substringAfterLast('/').replace('-', ' ').replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
        }
    }

    private fun isUsefulTitle(value: String?): Boolean {
        val text = cleanTitle(value)
        if (text.length < 2) return false
        val bad = listOf("tonton", "trailer", "download", "genre", "negara", "tahun", "beranda", "pasang iklan", "tweet", "sharer")
        return bad.none { text.equals(it, true) }
    }

    private fun isContentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (host != "178.128.107.129") return false

        val path = uri.path.orEmpty().trim('/').lowercase(Locale.ROOT)
        if (path.isBlank()) return false
        if (path.matches(Regex("""(?:page/\d+|genre/.+|country/.+|year/.+|category/.+|tag/.+|author/.+|search/.+|tv|quality/.+|network/.+|director/.+|cast/.+)"""))) return false
        if (path.contains("wp-content") || path.contains("wp-admin") || path.contains("wp-json") || path.contains("pasang-iklan")) return false

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
}
