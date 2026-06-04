package com.sad25kag.drakorasia

import android.util.Base64
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class DrakorAsia : MainAPI() {
    override var mainUrl = "https://www.drakorasia.eu.org"
    override var name = "DrakorAsia"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "" to "Episode Terbaru",
        "search/label/Action" to "Action",
        "search/label/Comedy" to "Comedy",
        "search/label/Drama" to "Drama",
        "search/label/Fantasy" to "Fantasy",
        "search/label/Historical" to "Historical",
        "search/label/Mystery" to "Mystery",
        "search/label/Romance" to "Romance",
        "search/label/Thriller" to "Thriller",
        "search/label/Family" to "Family",
        "search/label/Sci-Fi" to "Sci-Fi",
        "search/label/Supernatural" to "Supernatural",
        "search/label/War" to "War",
        "p/drama-list.html" to "Drama List"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest) = newHomePageResponse(
        request.name,
        parseCards(app.get(buildPageUrl(request.data, page), headers = headers, referer = mainUrl).document),
        hasNext = false
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val urls = listOf(
            "$mainUrl/search?q=$encoded",
            "$mainUrl/search/label/Series%20$encoded",
            "$mainUrl/p/drama-list.html"
        )

        val results = linkedMapOf<String, SearchResponse>()
        urls.forEach { url ->
            val document = runCatching {
                app.get(url, headers = headers, referer = mainUrl).document
            }.getOrNull() ?: return@forEach

            parseCards(document).forEach { response ->
                val haystack = response.name.lowercase()
                if (haystack.contains(keyword.lowercase()) || keyword.lowercase().split(Regex("\\s+")).any { it.length >= 3 && haystack.contains(it) }) {
                    results[response.url] = response
                }
            }
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = normalizeUrl(url)
        val isSeriesLabel = fixedUrl.contains("search?q=label%3ASeries", true) || fixedUrl.contains("/search/label/Series", true)
        val document = app.get(fixedUrl, headers = headers, referer = mainUrl).document

        return if (isSeriesLabel) {
            loadSeriesLabel(fixedUrl, document)
        } else {
            val seriesLink = document.select("a[href*='label%3ASeries'], a[href*='/search/label/Series']")
                .firstOrNull()
                ?.attr("abs:href")
                ?.takeIf { it.isNotBlank() }
            val title = cleanEpisodeTitle(readTitle(document, fixedUrl))
            val poster = readPoster(document)
            val plot = readPlot(document)
            val tags = readTags(document)

            val episodes = if (!seriesLink.isNullOrBlank()) {
                runCatching {
                    parseEpisodeCards(app.get(seriesLink, headers = headers, referer = fixedUrl).document, title)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            if (episodes.size > 1 && !title.contains("Episode", true)) {
                newTvSeriesLoadResponse(title, seriesLink ?: fixedUrl, TvType.AsianDrama, episodes) {
                    posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                }
            } else {
                newMovieLoadResponse(readTitle(document, fixedUrl), fixedUrl, TvType.AsianDrama, fixedUrl) {
                    posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                }
            }
        }
    }

    private suspend fun loadSeriesLabel(url: String, document: Document): LoadResponse {
        val title = decodeSeriesTitle(url).ifBlank {
            document.selectFirst("h1, h2, h3, title")?.text()?.cleanTitle().orEmpty()
        }.ifBlank { "DrakorAsia" }
        val episodes = parseEpisodeCards(document, title)
        val poster = episodes.firstOrNull()?.posterUrl
        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            posterUrl = poster
            tags = document.select("a[href*='/search/label/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && !it.equals("Series", true) }
                .distinct()
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select("article, .post, .blog-posts .hentry, .hentry, .item, .grid-post, .post-outer, a[href]")
            .forEach { element ->
                element.toSearchResult()?.let { results[it.url] = it }
            }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = when {
            tagName().equals("a", true) -> this
            else -> selectFirst("a[href*='.html'], a[href*='label%3ASeries'], a[href*='/search/label/Series'], a[href]")
        } ?: return null

        val href = normalizeUrl(anchor.attr("abs:href").ifBlank { anchor.attr("href") })
        if (!href.startsWith(mainUrl)) return null
        if (href == mainUrl || href.contains("/search/label/") && !href.contains("Series", true)) return null
        if (href.contains("/p/", true) && !href.contains("drama-list", true)) return null

        val title = listOf(
            selectFirst("h1, h2, h3, h4, .entry-title, .post-title, .title")?.text(),
            anchor.attr("title"),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?.takeIf { it.length >= 2 }
            ?: return null

        if (title.equals("Tonton", true) || title.equals("Home", true) || title.equals("Genre", true) || title.equals("Season", true)) return null

        val poster = fixUrlNull(selectFirst("img")?.imageAttr())
        val isMovie = href.contains("/movie/", true) || title.contains("Movie", true)

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
        }
    }

    private fun parseEpisodeCards(document: Document, seriesTitle: String): List<Episode> {
        val episodes = linkedMapOf<Int, Episode>()
        document.select("article, .hentry, .post-outer, .post, a[href*='.html']").forEachIndexed { index, element ->
            val anchor = when {
                element.tagName().equals("a", true) -> element
                else -> element.selectFirst("a[href*='.html']")
            } ?: return@forEachIndexed

            val href = normalizeUrl(anchor.attr("abs:href").ifBlank { anchor.attr("href") })
            if (!href.startsWith(mainUrl) || !href.contains(".html", true)) return@forEachIndexed

            val text = listOf(
                element.selectFirst("h1, h2, h3, h4, .entry-title, .post-title")?.text(),
                anchor.attr("title"),
                anchor.text()
            ).firstOrNull { !it.isNullOrBlank() }?.cleanTitle().orEmpty()
            if (!text.contains("Episode", true) && !href.contains("episode", true)) return@forEachIndexed

            val ep = extractEpisodeNumber(text, href) ?: (index + 1)
            episodes[ep] = newEpisode(href) {
                name = text.ifBlank { "$seriesTitle Episode $ep" }
                episode = ep
                posterUrl = fixUrlNull(element.selectFirst("img")?.imageAttr())
            }
        }
        return episodes.values.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = normalizeUrl(data)
        val emitted = linkedSetOf<String>()
        var loaded = false

        val document = app.get(pageUrl, headers = headers, referer = mainUrl).document
        val candidates = linkedSetOf<String>()
        collectCandidates(document.html(), pageUrl, candidates)
        document.select("iframe[src], embed[src], video[src], video source[src], a[href], option[value], [data-url], [data-src], [data-link], [data-href], [data-iframe], [data-embed], [data-player], [data-video], [data-file], [data-stream]").forEach { element ->
            listOf("src", "href", "value", "data-url", "data-src", "data-link", "data-href", "data-iframe", "data-embed", "data-player", "data-video", "data-file", "data-stream").forEach attrs@{ attr ->
                val raw = element.attr(attr).trim()
                if (raw.isBlank() || raw == "#" || raw.startsWith("javascript", true)) return@attrs
                val decoded = decodePayload(raw)
                collectCandidates(decoded, pageUrl, candidates)
                if (decoded.startsWith("http", true)) candidates.add(decoded)
                else normalizeUrl(decoded, pageUrl).takeIf { it.startsWith("http", true) }?.let(candidates::add)
            }
        }

        candidates.filter { it.isPlayableCandidate() }.forEach { candidate ->
            if (resolveCandidate(candidate, pageUrl, emitted, subtitleCallback, callback)) loaded = true
        }

        return loaded
    }

    private suspend fun resolveCandidate(
        candidate: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0
    ): Boolean {
        val url = candidate.replace("\\/", "/").trim().trim('"', '\'', ',', ';')
        if (!url.startsWith("http", true)) return false
        if (!emitted.add(url.substringBefore("#"))) return false

        return when {
            url.isDirectMedia() -> {
                emitDirect(url, referer, callback)
                true
            }
            url.contains("blogger.com/video.g", true) || url.contains("blogspot.com", true) || url.contains("googlevideo.com", true) -> {
                var found = false
                if (url.contains("googlevideo.com", true)) {
                    emitDirect(url, referer, callback)
                    found = true
                }
                if (depth < 2) {
                    val body = runCatching { app.get(url, headers = headers, referer = referer).text }.getOrNull()
                    body?.let {
                        val nested = linkedSetOf<String>()
                        collectCandidates(it, url, nested)
                        nested.filter { item -> item.isPlayableCandidate() }.forEach { item ->
                            if (resolveCandidate(item, url, emitted, subtitleCallback, callback, depth + 1)) found = true
                        }
                    }
                }
                if (!found) loadExtractor(url, referer, subtitleCallback, callback).also { found = true }
                found
            }
            else -> {
                loadExtractor(url, referer, subtitleCallback, callback)
                true
            }
        }
    }

    private suspend fun emitDirect(mediaUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val fixed = mediaUrl.replace("\\/", "/")
        val type = if (fixed.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val quality = getQualityFromName(fixed).let { if (it == Qualities.Unknown.value) inferQuality(fixed) else it }
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name ${if (quality == Qualities.Unknown.value) "Auto" else "${quality}p"}",
                url = fixed,
                type = type
            ) {
                this.quality = quality
                this.referer = referer
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to USER_AGENT
                )
            }
        )
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val base = when {
            path.isBlank() -> mainUrl
            path.startsWith("http", true) -> path
            else -> "$mainUrl/${path.trimStart('/')}"
        }
        if (page <= 1) return base
        return if (base.contains("/search/label/", true)) {
            "$base?max-results=20"
        } else {
            base
        }
    }

    private fun readTitle(document: Document, fallbackUrl: String): String {
        return document.selectFirst("h1.entry-title, h1.post-title, h1, meta[property=og:title], title")
            ?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackUrl.substringAfterLast('/').substringBefore('.').replace('-', ' ').cleanTitle()
    }

    private fun readPoster(document: Document): String? {
        return fixUrlNull(document.selectFirst("meta[property=og:image], meta[name=twitter:image], article img, .post img, img")
            ?.let { if (it.hasAttr("content")) it.attr("content") else it.imageAttr() })
    }

    private fun readPlot(document: Document): String? {
        return document.selectFirst("meta[property=og:description], meta[name=description], .post-body p, .entry-content p, article p")
            ?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.cleanTitle()
            ?.takeIf { it.length > 20 }
    }

    private fun readTags(document: Document): List<String> {
        return document.select("a[href*='/search/label/'], a[href*='label%3A']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Series", true) }
            .distinct()
    }

    private fun collectCandidates(text: String, referer: String, output: MutableSet<String>) {
        val normalized = decodePayload(text).replace("\\/", "/")
        Regex("""https?://[^\"'\s<>]+""")
            .findAll(normalized)
            .map { it.value.trim().trim('"', '\'', ',', ';', ')', ']', '}') }
            .forEach(output::add)

        Regex("""(?:src|href|file|url)\s*[:=]\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { normalizeUrl(it, referer) }
            .filter { it.startsWith("http", true) }
            .forEach(output::add)
    }

    private fun decodePayload(raw: String): String {
        val unescaped = raw.htmlUnescape().replace("\\/", "/")
        val urlDecoded = runCatching { URLDecoder.decode(unescaped, "UTF-8") }.getOrDefault(unescaped)
        val base64Candidate = urlDecoded.substringAfter("base64,", urlDecoded).trim().trim('"', '\'', ')', ';')
        if (base64Candidate.length >= 8 && base64Candidate.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) {
            listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP).forEach { flag ->
                val decoded = runCatching { String(Base64.decode(base64Candidate, flag), Charsets.UTF_8) }.getOrNull()
                if (!decoded.isNullOrBlank() && (decoded.contains("http", true) || decoded.contains("iframe", true))) return decoded
            }
        }
        return urlDecoded
    }

    private fun decodeSeriesTitle(url: String): String {
        val label = url.substringAfter("label%3ASeries+", "")
            .ifBlank { url.substringAfter("/search/label/Series%20", "") }
            .substringBefore('&')
            .substringBefore('?')
            .replace('+', ' ')
        return runCatching { URLDecoder.decode(label, "UTF-8") }.getOrDefault(label).cleanTitle()
    }

    private fun cleanEpisodeTitle(title: String): String {
        return title.replace(Regex("(?i)\\s+Episode\\s+\\d+.*$"), "")
            .replace(Regex("(?i)\\s+Sub\\s+Indo.*$"), "")
            .cleanTitle()
    }

    private fun extractEpisodeNumber(text: String, href: String): Int? {
        return Regex("(?i)episode[-\\s]*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)episode[-\\s]*(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)eps?\\.?[-\\s]*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun normalizeUrl(url: String, referer: String = mainUrl): String {
        val clean = url.trim().replace("&amp;", "&")
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("/") -> mainUrl.trimEnd('/') + clean
            clean.isBlank() -> clean
            else -> referer.substringBeforeLast('/').trimEnd('/') + "/" + clean
        }
    }

    private fun Element.imageAttr(): String {
        return attr("abs:src").ifBlank { attr("abs:data-src") }
            .ifBlank { attr("abs:data-original") }
            .ifBlank { attr("src") }
            .ifBlank { attr("data-src") }
            .ifBlank { attr("data-original") }
    }

    private fun String.isPlayableCandidate(): Boolean {
        val value = lowercase()
        return isDirectMedia() || listOf(
            "blogger.com/video.g", "googlevideo.com", "blogspot.com", "dailymotion", "ok.ru", "odnoklassniki",
            "streamtape", "filemoon", "dood", "vidhide", "vidguard", "voe.sx", "mixdrop", "mp4upload",
            "abyssplayer", "streamwish", "streamruby", "sendvid", "uqload", "short.ink", "desustream", "ondesu"
        ).any { value.contains(it) }
    }

    private fun String.isDirectMedia(): Boolean {
        val value = lowercase().substringBefore("#")
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".webm") || value.contains(".mkv") || value.contains("videoplayback")
    }

    private fun inferQuality(url: String): Int {
        return when {
            url.contains("2160", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.cleanTitle(): String {
        return htmlUnescape()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)\\s+[-–]\\s+drakorasia.*$"), "")
            .replace(Regex("(?i)^Nonton\\s+"), "")
            .trim()
    }

    private fun String.htmlUnescape(): String {
        return replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
    }
}
