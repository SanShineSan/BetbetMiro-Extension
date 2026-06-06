package com.sad25kag.drakorindo

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
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
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class DrakorIndoProvider : MainAPI() {
    override var mainUrl = "https://drakor-indo.one"
    override var name = "DrakorIndo"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    private val baseHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
    )

    override val mainPage = mainPageOf(
        "" to "Latest Update",
        "ongoing/" to "Ongoing",
        "genres/action/" to "Action",
        "genres/action-adventure/" to "Action & Adventure",
        "genres/adventure/" to "Adventure",
        "genres/animation/" to "Animation",
        "genres/comedy/" to "Comedy",
        "genres/drama/" to "Drama",
        "genres/mystery/" to "Mystery",
        "genres/romance/" to "Romance",
        "genres/thriller/" to "Thriller",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            buildPageUrl(request.data, page),
            headers = baseHeaders,
            referer = mainUrl,
        ).document

        val items = document.extractSearchResults()
            .distinctBy { it.url }
            .take(40)

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty() && document.hasNextPage(page),
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(clean, "UTF-8")
        val document = app.get(
            "$mainUrl/?s=$encoded",
            headers = baseHeaders,
            referer = mainUrl,
        ).document

        val words = clean.split(Regex("\\s+"))
            .filter { it.length >= 2 }

        return document.extractSearchResults()
            .filter { response ->
                response.name.contains(clean, ignoreCase = true) ||
                    words.any { word -> response.name.contains(word, ignoreCase = true) }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = fixUrl(url)
        val document = app.get(
            pageUrl,
            headers = baseHeaders,
            referer = mainUrl,
        ).document

        val rawTitle = document.selectFirst("h1, .entry-title, .post-title, .title, meta[property=og:title], meta[name=title]")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Judul DrakorIndo tidak ditemukan")

        val seriesTitle = rawTitle.toSeriesTitle()
        val poster = document.findPoster()
        val plot = document.findPlot()
        val tags = document.findTags()
        val year = document.findYear(rawTitle)
        val typeText = document.text()
        val isMovie = pageUrl.isMovieUrl() ||
            rawTitle.hasYearSuffix() ||
            (typeText.contains("Movie", ignoreCase = true) && !pageUrl.contains("episode=", true))

        val episodes = document.extractEpisodes(pageUrl, seriesTitle)
            .ifEmpty {
                if (!isMovie && pageUrl.contains("episode=", ignoreCase = true)) {
                    val epNo = extractEpisodeNumber(pageUrl, rawTitle) ?: 1
                    listOf(
                        newEpisode(pageUrl) {
                            name = "Episode $epNo"
                            episode = epNo
                        },
                    )
                } else {
                    emptyList()
                }
            }

        if (isMovie || episodes.isEmpty()) {
            return newMovieLoadResponse(seriesTitle.ifBlank { rawTitle }, pageUrl, if (isMovie) TvType.Movie else TvType.AsianDrama, pageUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }

        return newTvSeriesLoadResponse(seriesTitle.ifBlank { rawTitle }, pageUrl, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val response = app.get(
            pageUrl,
            headers = baseHeaders,
            referer = mainUrl,
        )
        val document = response.document
        val html = response.text
        val emitted = AtomicInteger(0)

        fun markEmit(link: ExtractorLink) {
            emitted.incrementAndGet()
            callback.invoke(link)
        }

        document.emitSubtitles(pageUrl, subtitleCallback)
        emitTextSubtitles(html, pageUrl, subtitleCallback)

        val candidates = linkedSetOf<String>()

        candidates += document.select("#cars option[value], select[name=cars] option[value]")
            .mapNotNull { it.attr("value").takeIf { value -> value.isNotBlank() } }
            .map { fixUrl(it, pageUrl) }

        candidates += document.select("iframe[src], video source[src], video[src], source[src]")
            .mapNotNull { element ->
                element.attr("abs:src")
                    .ifBlank { fixUrl(element.attr("src"), pageUrl) }
                    .takeIf { it.startsWith("http", true) }
            }

        document.select(
            "option[value], [data-video], [data-src], [data-url], [data-link], [data-embed], [data-file], [data-player], [data-href], a[href], button, div"
        ).forEach { element ->
            listOf("value", "data-video", "data-src", "data-url", "data-link", "data-embed", "data-file", "data-player", "data-href", "href").forEach attrs@{ attr ->
                val raw = element.attr(attr).trim()
                if (raw.isBlank() || raw == "#" || raw.equals("javascript:;", true)) return@attrs

                val fixed = fixUrl(raw, pageUrl)
                if (fixed.startsWith("http", true)) candidates += fixed

                val decoded = decodeServerPayload(raw)
                if (decoded.isNotBlank()) {
                    candidates += extractUrls(decoded, pageUrl)
                    if (decoded.startsWith("http", true)) candidates += fixUrl(decoded, pageUrl)
                }
            }
        }

        candidates += extractUrls(html, pageUrl)
        candidates += extractBase64Urls(html, pageUrl)

        candidates
            .map { it.trim().replace("\\/", "/") }
            .map { it.trim('"', '\'', ',', ';', ')', ']', '}') }
            .map { fixUrl(it, pageUrl) }
            .filter { it.startsWith("http", true) }
            .filterNot { it.contains(mainUrl, ignoreCase = true) && !it.isDirectMedia() }
            .filter { it.isPlayableCandidate() }
            .distinct()
            .forEach { candidate ->
                when {
                    candidate.isDirectMedia() -> emitDirect(candidate, pageUrl, ::markEmit)
                    candidate.isVidHideCandidate() -> {
                        val before = emitted.get()
                        resolveVidHide(candidate, pageUrl, subtitleCallback, ::markEmit)
                        if (emitted.get() == before) {
                            loadExtractor(candidate.normalizeVidHideUrl(), pageUrl, subtitleCallback, ::markEmit)
                        }
                    }
                    candidate.isCallistaniseCandidate() -> {
                        val before = emitted.get()
                        resolveVidHide(candidate, pageUrl, subtitleCallback, ::markEmit)
                        if (emitted.get() == before) {
                            loadExtractor(candidate, pageUrl, subtitleCallback, ::markEmit)
                        }
                    }
                    else -> loadExtractor(candidate, pageUrl, subtitleCallback, ::markEmit)
                }
            }

        return emitted.get() > 0
    }

    private suspend fun resolveVidHide(
        rawUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playerUrl = rawUrl.normalizeVidHideUrl()
        val emitted = AtomicInteger(0)
        fun markEmit(link: ExtractorLink) {
            emitted.incrementAndGet()
            callback.invoke(link)
        }

        val response = runCatching {
            app.get(
                playerUrl,
                headers = baseHeaders + mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to pageUrl,
                ),
                referer = pageUrl,
            )
        }.getOrNull() ?: return false

        val html = response.text
        val unpacked = unpackPackerScripts(html)
        val candidates = linkedSetOf<String>()

        candidates += extractUrls(html, playerUrl)
        unpacked.forEach { script -> candidates += extractUrls(script, playerUrl) }

        candidates
            .map { it.replace("\\/", "/").trim('"', '\'', ',', ';', ')', ']', '}') }
            .map { fixUrl(it, playerUrl) }
            .filter { it.isDirectMedia() }
            .distinct()
            .forEach { mediaUrl -> emitDirect(mediaUrl, playerUrl, ::markEmit) }

        if (emitted.get() == 0) {
            emitTextSubtitles(html, playerUrl, subtitleCallback)
            unpacked.forEach { emitTextSubtitles(it, playerUrl, subtitleCallback) }
        }

        return emitted.get() > 0
    }

    private fun Document.extractSearchResults(): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = linkedSetOf<String>()

        select(
            ".latest-update a[href], .latest-movies a[href], .movie-carousel a[href], .drama-carousel a[href], .anime-carousel a[href], article, .item, .items, .movie-item, .ml-item, .post, .post-item, .film-poster, .grid-item, .list-item, .card, .bs, .result-item, a[href]"
        ).forEach { element ->
            val response = element.toSearchResult() ?: return@forEach
            if (seen.add(response.url)) results.add(response)
        }

        return results
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = when {
            tagName().equals("a", true) -> this
            else -> selectFirst("a[href]")
        } ?: return null

        val href = fixUrl(linkEl.attr("abs:href").ifBlank { linkEl.attr("href") })
        if (!href.startsWith(mainUrl, true)) return null
        if (!href.isLikelyTitleUrl()) return null

        val rawTitle = listOf(
            selectFirst("h1, h2, h3, h4, h5, .tt, .title, .name, .entry-title, .post-title")?.text().orEmpty(),
            linkEl.attr("title"),
            selectFirst("img[alt]")?.attr("alt").orEmpty(),
            linkEl.text(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        val title = rawTitle.cleanTitle().takeIf { it.isNotBlank() } ?: return null
        if (title.length < 2 || title.isMenuNoise()) return null

        val poster = selectFirst("img[src], img[data-src], img[data-lazy-src]")
            ?.let { img -> img.attr("abs:data-src").ifBlank { img.attr("abs:data-lazy-src") }.ifBlank { img.attr("abs:src") } }
            ?.takeIf { it.isNotBlank() }

        val cardText = text()
        val isMovie = href.isMovieUrl() || cardText.contains("Movie", ignoreCase = true) || title.hasYearSuffix()

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
    }

    private fun Document.extractEpisodes(currentUrl: String, seriesTitle: String): List<com.lagradost.cloudstream3.Episode> {
        val episodes = mutableListOf<Triple<Int, String, String>>()
        val currentEpisode = extractEpisodeNumber(currentUrl, seriesTitle)

        select("a[href*='?episode='], a[href*='&episode=']").forEach { element ->
            val href = fixUrl(element.attr("abs:href").ifBlank { element.attr("href") }, currentUrl)
            if (!href.startsWith(mainUrl, true)) return@forEach
            if (!href.contains("episode", ignoreCase = true)) return@forEach
            if (!sameSeriesPath(currentUrl, href)) return@forEach

            val rawName = element.text().cleanTitle().ifBlank { element.attr("title").cleanTitle() }
            val episodeNumber = extractEpisodeNumber(href, rawName) ?: return@forEach
            val title = rawName.takeIf { it.isNotBlank() && !it.isMenuNoise() } ?: "Episode $episodeNumber"
            episodes += Triple(episodeNumber, href, title)
        }

        if (episodes.isEmpty() && currentEpisode != null) {
            episodes += Triple(currentEpisode, currentUrl, "Episode $currentEpisode")
        }

        return episodes
            .distinctBy { it.second }
            .sortedBy { it.first }
            .map { (episodeNumber, episodeUrl, episodeName) ->
                newEpisode(episodeUrl) {
                    name = episodeName
                    episode = episodeNumber
                }
            }
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val base = when {
            path.isBlank() -> mainUrl
            path.startsWith("http://", true) || path.startsWith("https://", true) -> path.trimEnd('/')
            else -> "$mainUrl/${path.trim('/')}"
        }

        if (page <= 1) return if (base == mainUrl) base else "$base/"
        return if (base.contains("?")) {
            val pathPart = base.substringBefore("?").trimEnd('/')
            val queryPart = base.substringAfter("?")
            "$pathPart/page/$page/?$queryPart"
        } else {
            "${base.trimEnd('/')}/page/$page/"
        }
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        return select("a.next, .pagination a, .nav-links a, a[href*='/page/${page + 1}/'], a:contains(Next), a:contains(Berikut)").isNotEmpty()
    }

    private fun Document.findPoster(): String? {
        return selectFirst("meta[property=og:image], meta[name=twitter:image], .info__poster img, .poster img, .thumb img, .post-thumbnail img, article img, img[src*='wp-content']")
            ?.let { element ->
                element.attr("content")
                    .ifBlank { element.attr("abs:data-src") }
                    .ifBlank { element.attr("abs:data-lazy-src") }
                    .ifBlank { element.attr("abs:src") }
            }
            ?.takeIf { it.isNotBlank() }
    }

    private fun Document.findPlot(): String? {
        return selectFirst(".the__content, .entry-content p, .post-content p, .content p, .desc p, .synopsis p, [itemprop=description], meta[property=og:description]")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.cleanSpaces()
            ?.takeIf { it.length >= 20 }
    }

    private fun Document.findTags(): List<String> {
        return select("a[href*='/genres/'], a[href*='/genre/'], .genre a, .genres a, .tagcloud a")
            .map { it.text().cleanSpaces() }
            .filter { it.isNotBlank() && !it.isMenuNoise() }
            .distinct()
    }

    private fun Document.findYear(title: String): Int? {
        return Regex("\\((\\d{4})\\)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: selectFirst(".year, a[href*='/release-year/'], a[href*='/years/']")?.text()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
    }

    private fun Document.emitSubtitles(pageUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        select("track[src], a[href$='.vtt'], a[href$='.srt'], a[href*='.vtt'], a[href*='.srt']").forEach { element ->
            val raw = element.attr("abs:src").ifBlank { element.attr("abs:href") }.ifBlank { element.attr("src") }.ifBlank { element.attr("href") }
            val url = fixUrl(raw, pageUrl)
            if (!url.startsWith("http", true)) return@forEach
            if (url.contains("op=get_slides", true)) return@forEach
            val label = element.attr("label").ifBlank { element.text() }.ifBlank { "Indonesian" }
            subtitleCallback.invoke(SubtitleFile(label, url))
        }
    }

    private fun emitTextSubtitles(html: String, pageUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        Regex("""https?://[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html.replace("\\/", "/"))
            .map { it.value.trim().trim('"', '\'', ',', ';', ')', ']') }
            .filterNot { it.contains("op=get_slides", true) }
            .distinct()
            .forEach { subtitleCallback.invoke(SubtitleFile("Subtitle", fixUrl(it, pageUrl))) }
    }

    private fun decodeServerPayload(raw: String): String {
        val decodedUrl = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val candidates = listOf(raw, decodedUrl, decodedUrl.substringAfter("base64,", decodedUrl))
        candidates.forEach { candidate ->
            val clean = candidate.trim().trim('"', '\'', '(', ')')
            if (clean.length < 8 || !clean.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) return@forEach
            listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP).forEach { flag ->
                val decoded = runCatching { String(Base64.decode(clean, flag), Charsets.UTF_8) }.getOrNull()
                if (!decoded.isNullOrBlank() && (decoded.contains("http", true) || decoded.contains("<iframe", true))) {
                    return decoded
                }
            }
        }
        return decodedUrl
    }

    private fun extractUrls(text: String, referer: String): Set<String> {
        val normalized = text.replace("\\/", "/")
        val urls = linkedSetOf<String>()

        Regex("""https?://[^"'\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map { it.value.trim().trim('"', '\'', ',', ';', ')', ']', '}') }
            .forEach { urls.add(it) }

        Regex("""(?:src|href|file|url|source|hls\d*)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { fixUrl(it, referer) }
            .filter { it.startsWith("http", true) }
            .forEach { urls.add(it) }

        Regex("""["'](/[^"']+?\.(?:m3u8|mp4)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { fixUrl(it, referer) }
            .forEach { urls.add(it) }

        return urls
    }

    private fun extractBase64Urls(text: String, referer: String): Set<String> {
        val urls = linkedSetOf<String>()
        Regex("""(?:atob|Base64\.decode|data|source|url)\s*\(?\s*[=:]?\s*["']([A-Za-z0-9+/=_-]{24,})["']""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { decodeServerPayload(it) }
            .flatMap { extractUrls(it, referer) }
            .forEach { urls.add(it) }
        return urls
    }

    private fun unpackPackerScripts(html: String): List<String> {
        return PACKER_REGEX.findAll(html)
            .mapNotNull { match ->
                val payload = match.groupValues.getOrNull(1)?.unescapeJsString().orEmpty()
                val radix = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                val count = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return@mapNotNull null
                val words = match.groupValues.getOrNull(4)?.split("|") ?: return@mapNotNull null
                unpackPacker(payload, radix, count, words)
            }
            .toList()
    }

    private fun unpackPacker(payload: String, radix: Int, count: Int, words: List<String>): String {
        var unpacked = payload
        for (index in count - 1 downTo 0) {
            val replacement = words.getOrNull(index).orEmpty()
            if (replacement.isBlank()) continue
            val encoded = index.toRadix(radix)
            unpacked = Regex("\\b${Regex.escape(encoded)}\\b").replace(unpacked, replacement)
        }
        return unpacked.replace("\\'", "'").replace("\\\"", "\"")
    }

    private fun emitDirect(mediaUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = mediaUrl.replace("\\/", "/")
        val type = if (fixedUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val quality = getQualityFromName(fixedUrl).let { quality ->
            if (quality == Qualities.Unknown.value) inferQuality(fixedUrl) else quality
        }
        val origin = getBaseUrl(referer)

        callback.invoke(
            newExtractorLink(name, "$name ${qualityLabel(quality)}", fixedUrl, type) {
                this.referer = referer
                this.quality = quality
                this.headers = baseHeaders + mapOf(
                    "Accept" to "*/*",
                    "Origin" to origin,
                    "Referer" to referer,
                )
            },
        )
    }

    private fun String.isPlayableCandidate(): Boolean {
        val host = runCatching { URI(this).host?.removePrefix("www.") }.getOrNull().orEmpty()
        return isDirectMedia() ||
            contains("/embed", true) ||
            contains("/player", true) ||
            host.contains("short.icu", true) ||
            host.contains("emturbovid", true) ||
            host.contains("dailymotion.com", true) ||
            host.contains("streamtape", true) ||
            host.contains("filemoon", true) ||
            host.contains("vidhide", true) ||
            host.contains("callistanise.com", true) ||
            host.contains("voe.sx", true) ||
            host.contains("uqload", true) ||
            host.contains("mixdrop", true) ||
            host.contains("mp4upload", true) ||
            host.contains("dood", true) ||
            host.contains("streamsb", true) ||
            host.contains("sbplay", true) ||
            host.contains("lulustream", true) ||
            host.contains("lulu.st", true) ||
            host.contains("abyss", true) ||
            host.contains("vidmoly", true) ||
            host.contains("gofile", true) ||
            host.contains("pixeldrain", true)
    }

    private fun String.isVidHideCandidate(): Boolean {
        val host = runCatching { URI(this).host?.removePrefix("www.") }.getOrNull().orEmpty()
        return host.contains("vidhide", true)
    }

    private fun String.isCallistaniseCandidate(): Boolean {
        val host = runCatching { URI(this).host?.removePrefix("www.") }.getOrNull().orEmpty()
        return host.equals("callistanise.com", true) && contains("/v/", true)
    }

    private fun String.normalizeVidHideUrl(): String {
        val clean = trim().replace("\\/", "/")
        val code = clean.substringAfterLast("/v/", "").substringBefore("?").substringBefore("#").trim('/')
        return if (code.isNotBlank() && isVidHideCandidate()) {
            "https://callistanise.com/v/$code"
        } else {
            clean
        }
    }

    private fun String.isDirectMedia(): Boolean {
        return contains(".m3u8", true) || contains(".mp4", true)
    }

    private fun String.isMovieUrl(): Boolean {
        val path = substringBefore("?").lowercase()
        return path.contains("/movie/") || hasYearSuffix()
    }

    private fun String.isLikelyTitleUrl(): Boolean {
        val clean = substringBefore("#").trimEnd('/')
        val path = runCatching { URI(clean).path.orEmpty() }.getOrDefault(clean).trim('/').lowercase()
        if (path.isBlank()) return false
        val blocked = listOf(
            "genres", "genre", "tag", "category", "all-genres", "index-list", "ongoing", "request", "privacy", "dmca", "contact", "page"
        )
        if (blocked.any { path == it || path.startsWith("$it/") }) return false
        if (contains("linktr.ee", true)) return false
        return true
    }

    private fun sameSeriesPath(left: String, right: String): Boolean {
        val leftPath = runCatching { URI(left).path.trim('/') }.getOrDefault(left.substringBefore("?").trim('/'))
        val rightPath = runCatching { URI(right).path.trim('/') }.getOrDefault(right.substringBefore("?").trim('/'))
        return leftPath.equals(rightPath, ignoreCase = true) || leftPath.isBlank() || rightPath.isBlank()
    }

    private fun fixUrl(url: String, referer: String = mainUrl): String {
        val clean = url.trim().replace("\\/", "/")
        return when {
            clean.isBlank() -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("/") -> getBaseUrl(referer).trimEnd('/') + clean
            else -> referer.substringBefore("?").substringBeforeLast("/").trimEnd('/') + "/" + clean
        }
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.authority}"
        }.getOrDefault(mainUrl)
    }

    private fun extractEpisodeNumber(href: String, text: String): Int? {
        return Regex("[?&]episode=(\\d+)", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("episode[-\\s=]*(\\d+)", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?:Eps?|Episode|EP)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun inferQuality(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityLabel(quality: Int): String {
        return if (quality == Qualities.Unknown.value) "Auto" else "${quality}p"
    }

    private fun String.toSeriesTitle(): String {
        return cleanTitle()
            .replace(Regex("(?i)^Episode\\s+\\d+\\s+"), "")
            .replace(Regex("(?i)\\s+Episode\\s+\\d+.*$"), "")
            .cleanSpaces()
    }

    private fun String.cleanTitle(): String {
        return cleanSpaces()
            .replace(Regex("(?i)^HD\\s+"), "")
            .replace(Regex("(?i)^Nonton\\s+"), "")
            .replace(Regex("(?i)\\s+Subtitle\\s+Indonesia.*$"), "")
            .replace(Regex("(?i)\\s+[-–|]\\s+DrakorIndo.*$"), "")
            .trim()
    }

    private fun String.cleanSpaces(): String = replace(Regex("\\s+"), " ").trim()

    private fun String.isMenuNoise(): Boolean {
        val value = cleanSpaces().lowercase()
        return value in setOf(
            "home", "all genres", "index list", "ongoing", "requestt!!!", "linktree", "search", "apply now", "select orderby", "orderby", "svr1", "svr2", "svr3"
        ) || value.startsWith("select orderby") || value.startsWith("order by")
    }

    private fun String.hasYearSuffix(): Boolean = Regex("\\(\\d{4}\\)").containsMatchIn(this)

    private fun String.unescapeJsString(): String {
        return replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
    }

    private fun Int.toRadix(radix: Int): String {
        if (this == 0) return "0"
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var value = this
        val builder = StringBuilder()
        while (value > 0) {
            builder.insert(0, chars[value % radix])
            value /= radix
        }
        return builder.toString()
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
        private val PACKER_REGEX = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{[\s\S]*?\}\('((?:\\'|[^'])*)',(\d+),(\d+),'((?:\\'|[^'])*)'\.split\('\|'\)\)\)"""
        )
    }
}
