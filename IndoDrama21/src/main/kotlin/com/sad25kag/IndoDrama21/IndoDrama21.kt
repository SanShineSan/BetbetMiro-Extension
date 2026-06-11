package com.sad25kag.IndoDrama21

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
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
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

@CloudstreamPlugin
class IndoDrama21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(IndoDrama21())
    }
}

class IndoDrama21 : MainAPI() {
    override var mainUrl = "https://indodrama21.online"
    override var name = "IndoDrama21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "movie" to "Movie",
        "tv" to "TV Series",
        "drama-korea" to "Drama Korea",
        "drama-china" to "Drama China",
        "film-action-terbaru" to "Action",
        "drama" to "Drama",
        "comedy" to "Comedy",
        "romance" to "Romance",
        "anime" to "Anime",
        "country/indonesia" to "Indonesia",
        "country/korea" to "Korea",
        "country/china" to "China",
        "country/japan" to "Japan"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private val mediaHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            buildPageUrl(request.data, page),
            headers = headers,
            referer = mainUrl,
            timeout = 25L
        ).document

        return newHomePageResponse(
            request.name,
            parseCards(document),
            hasNext = hasNextPage(document, page)
        )
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val clean = path.trim().trim('/')
        return when {
            clean.startsWith("http", true) -> appendPage(clean, page)
            clean.isBlank() && page <= 1 -> mainUrl
            clean.isBlank() -> "$mainUrl/page/$page/"
            clean.startsWith("?") && page <= 1 -> "$mainUrl/$clean"
            clean.startsWith("?") -> "$mainUrl/page/$page/$clean"
            page <= 1 -> "$mainUrl/$clean/"
            else -> "$mainUrl/$clean/page/$page/"
        }
    }

    private fun appendPage(url: String, page: Int): String {
        if (page <= 1) return url
        return when {
            url.contains("/page/", true) -> url
            url.contains("?") -> "$url&page=$page"
            else -> "${url.trimEnd('/')}/page/$page/"
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a.next, a[rel=next], .page-numbers.next, " +
                ".pagination a:contains(Next), .nav-links a:contains(Next), " +
                "a[href*='/page/${page + 1}/'], a[href*='page=${page + 1}']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article.item, article.item-infinite, div#gmr-main-load article, " +
                "article:has(h2.entry-title a):has(img), .gmr-box-content:has(h2.entry-title a):has(img)"
        ).forEach { element ->
            element.toSearchResult()?.let { item -> results[item.url] = item }
        }

        if (results.isEmpty()) {
            document.select("h2.entry-title a[href], .entry-title a[href], article a[title*='Permalink'][href], a[href]:has(img)")
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
            selectFirst("h2.entry-title a[href], .entry-title a[href], a[title*='Permalink'][href], a[href]:has(img), a[href]")
                ?: return null
        }

        val href = normalizeUrl(anchor.attr("abs:href").ifBlank { anchor.attr("href") }, mainUrl)
        if (href.isBlank() || isBlockedUrl(href) || !href.contains("indodrama21.online")) return null

        val image = selectFirst("div.content-thumbnail img, img[data-src], img[data-lazy-src], img[src]")
            ?: anchor.selectFirst("img")
        val rawTitle = listOf(
            selectFirst("h2.entry-title a[href]")?.text(),
            selectFirst(".entry-title a[href]")?.text(),
            anchor.text(),
            anchor.attr("title").removePrefix("Permalink ke:").removePrefix("Permalink to:"),
            image?.attr("alt"),
            href.substringAfterLast('/').replace('-', ' ')
        ).firstOrNull { !it.isNullOrBlank() && !isBadTitle(it) }

        val title = rawTitle?.cleanTitle()?.takeIf { it.length > 1 && !isBadTitle(it) } ?: return null
        val poster = fixUrlNull(image?.getImageAttr())?.takeIf { !isBadImage(it) }
        val type = guessType(href, text(), title)

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.trim('/').lowercase() }
            .getOrDefault(url.substringAfter(mainUrl).trim('/').lowercase())
        if (path.isBlank()) return true

        val blockedExact = setOf(
            "privacy", "dmca", "contact", "sitemap", "pasang-iklan", "author", "wp-admin", "wp-json", "feed"
        )
        val blockedPrefixes = listOf(
            "wp-content/", "wp-includes/", "page/", "search", "tag/", "author/", "director/", "cast/"
        )

        return path in blockedExact || blockedPrefixes.any { path.startsWith(it) }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val attempts = listOf(
            if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded",
            if (page <= 1) "$mainUrl/?s=$encoded&search=advanced" else "$mainUrl/page/$page/?s=$encoded&search=advanced",
            if (page <= 1) "$mainUrl/search/$encoded/" else "$mainUrl/search/$encoded/page/$page/"
        )

        for (url in attempts) {
            val document = runCatching {
                app.get(url, headers = headers, referer = mainUrl, timeout = 25L).document
            }.getOrNull() ?: continue
            val results = parseCards(document).distinctBy { it.url }
            if (results.isNotEmpty()) return newSearchResponseList(results, hasNextPage(document, page))
        }

        return newSearchResponseList(emptyList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = normalizeUrl(url, mainUrl)
        val document = app.get(pageUrl, headers = headers, referer = mainUrl, timeout = 25L).document

        val title = listOf(
            document.selectFirst("h1.entry-title, h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            pageUrl.substringAfterLast('/').replace('-', ' ')
        ).firstOrNull { !it.isNullOrBlank() && !isBadTitle(it) }
            ?.cleanTitle()
            ?: name

        val poster = getPoster(document)
        val text = document.text()
        val infoText = document.selectFirst("div.content-moviedata, div.gmr-movie-data, article")?.text().orEmpty()
        val plot = document.selectFirst("div.entry-content, div.description, .sinopsis, .summary, article p")
            ?.text()
            ?.trim()
            ?.takeIf { it.length > 40 }
        val year = extractYear(title) ?: extractYear(infoText) ?: extractYear(text)
        val duration = parseDuration(infoText.ifBlank { text })
        val rating = parseRating(infoText) ?: parseRating(text)
        val tags = parseTags(document)
        val actors = parseActors(document)
        val trailer = document.selectFirst("a.gmr-trailer-popup[href], iframe[src*='youtube'], a[href*='youtube'], a[href*='youtu.be']")
            ?.let { it.attr("href").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }
        val recommendations = document.select("div.gmr-grid article, .gmr-related-title + .row article, article.item:has(h2.entry-title a)")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != pageUrl }
            .distinctBy { it.url }
            .take(12)

        val episodes = parseEpisodeLinks(document, pageUrl, poster)
        val hasPlayableOnPage = hasPlayerEvidence(document)
        val isSeries = pageUrl.contains("/tv/", true) || infoText.contains("Jumlah Episode", true)

        return if (!isSeries) {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val finalEpisodes = if (episodes.isNotEmpty()) {
                episodes
            } else if (hasPlayableOnPage) {
                listOf(newEpisode(pageUrl) {
                    name = title
                    episode = 1
                    posterUrl = poster
                })
            } else {
                emptyList()
            }

            newTvSeriesLoadResponse(title, pageUrl, TvType.AsianDrama, finalEpisodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun hasPlayerEvidence(document: Document): Boolean {
        return document.selectFirst("iframe[src*='watch.asiastream.cc'], iframe[src], ul.muvipro-player-tabs li a[href], .gmr-embed-responsive iframe") != null
    }

    private fun parseEpisodeLinks(document: Document, currentUrl: String, poster: String?): List<Episode> {
        val links = linkedMapOf<String, Episode>()
        document.select(
            "div.gmr-listseries a[href], .gmr-listseries a[href], .episodes a[href], " +
                ".eplister a[href], .les-content a[href], a[href*='/eps/'], a[href*='episode']"
        ).forEachIndexed { index, element ->
            val href = normalizeUrl(element.attr("abs:href").ifBlank { element.attr("href") }, currentUrl)
            if (href.isBlank() || isBlockedUrl(href)) return@forEachIndexed
            val label = element.text().trim().ifBlank { "Episode ${index + 1}" }
            if (label.contains("trailer", true) || href.contains("youtube", true)) return@forEachIndexed
            val epNumber = extractEpisodeNumber(label, href) ?: index + 1
            links[href] = newEpisode(href) {
                name = label.cleanTitle()
                episode = epNumber
                posterUrl = poster
            }
        }
        return links.values.sortedBy { it.episode ?: 0 }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = normalizeUrl(data, mainUrl)
        val emitted = intArrayOf(0)
        val countedCallback: (ExtractorLink) -> Unit = {
            emitted[0]++
            callback(it)
        }
        val directLinks = linkedSetOf<String>()
        val directReferers = linkedMapOf<String, String>()
        val embedLinks = linkedSetOf<String>()
        val inspectedPages = linkedSetOf<String>()

        suspend fun inspectPage(url: String, referer: String) {
            val fixedUrl = normalizeUrl(url, referer)
            if (fixedUrl.isBlank() || !inspectedPages.add(fixedUrl) || shouldSkipUrl(fixedUrl)) return
            val response = runCatching {
                app.get(
                    fixedUrl,
                    headers = headers + mapOf("Origin" to getBaseUrl(referer)),
                    referer = referer,
                    timeout = 25L
                )
            }.getOrNull() ?: return

            val document = response.document
            val html = response.text.cleanEscaped()
            val directBefore = directLinks.toSet()
            collectPlayerTabs(document, fixedUrl).forEach { inspectPage(it, fixedUrl) }
            collectCandidatesFromDocument(document, fixedUrl, directLinks, embedLinks)
            extractPlayableUrls(html).forEach { addCandidate(it, fixedUrl, directLinks, embedLinks) }
            extractAsiastreamMaster(html, fixedUrl)?.let { directLinks.add(it) }

            val unpacked = runCatching {
                if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
            }.getOrNull()
            if (!unpacked.isNullOrBlank()) {
                extractPlayableUrls(unpacked.cleanEscaped()).forEach { addCandidate(it, fixedUrl, directLinks, embedLinks) }
                extractAsiastreamMaster(unpacked.cleanEscaped(), fixedUrl)?.let { directLinks.add(it) }
            }

            val decoded = runCatching { URLDecoder.decode(html, "UTF-8") }.getOrDefault(html)
            if (decoded != html) {
                extractPlayableUrls(decoded.cleanEscaped()).forEach { addCandidate(it, fixedUrl, directLinks, embedLinks) }
                extractAsiastreamMaster(decoded.cleanEscaped(), fixedUrl)?.let { directLinks.add(it) }
            }

            directLinks
                .filterNot { it in directBefore }
                .forEach { directReferers[it] = fixedUrl }
        }

        inspectPage(pageUrl, mainUrl)

        for (embed in prioritizeEmbeds(embedLinks).take(16)) {
            if (isAsiastreamWatch(embed)) {
                inspectPage(embed, pageUrl)
                continue
            }
            runCatching {
                loadExtractor(embed, pageUrl, subtitleCallback, countedCallback)
            }
            if (emitted[0] > 0) return true
            inspectPage(embed, pageUrl)
            if (emitted[0] > 0) return true
        }

        for (link in directLinks.distinct().sortedWith(compareBy<String> { if (isHlsLike(it)) 0 else 1 }.thenBy { hostPriority(it) })) {
            emitDirectLink(link, directReferers[link] ?: pageUrl, countedCallback)
            if (emitted[0] > 0) return true
        }

        return emitted[0] > 0
    }

    private fun collectPlayerTabs(document: Document, pageUrl: String): List<String> {
        return document.select("ul.muvipro-player-tabs li a[href], .muvipro-player-tabs a[href], a[href*='?player=']")
            .mapNotNull { element ->
                val href = normalizeUrl(element.attr("abs:href").ifBlank { element.attr("href") }, pageUrl)
                href.takeIf { it.isNotBlank() && !isBlockedUrl(it) }
            }
            .distinct()
    }

    private fun collectCandidatesFromDocument(
        document: Document,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        document.select(
            "iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], object[data], " +
                "source[src], video[src], video source[src], meta[property=og:video], meta[property=og:video:url], " +
                "a[href], [data-src], [data-file], [data-video], [data-url], [data-embed], [data-iframe]"
        ).forEach { element ->
            val raw = element.attr("content")
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            val label = element.text().lowercase()
            if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript", true)) return@forEach
            if (label.contains("trailer") || raw.contains("youtube.com", true) || raw.contains("youtu.be", true)) return@forEach

            val tag = element.tagName().lowercase()
            if (isLikelyPlayable(raw) || isLikelyDownloadText(label) || tag in setOf("iframe", "embed", "object", "source", "video", "meta")) {
                addCandidate(raw, baseUrl, directLinks, embedLinks)
            }
        }
    }

    private fun extractAsiastreamMaster(text: String, baseUrl: String): String? {
        val clean = text.cleanEscaped()
        val sniff = Regex(
            """sniff\(\s*[\"']([^\"']+)[\"']\s*,\s*[\"']([^\"']+)[\"']\s*,\s*[\"']([^\"']+)[\"']\s*,.*?,\s*(\d+)\s*,""",
            RegexOption.IGNORE_CASE
        ).find(clean) ?: return null
        val uid = sniff.groupValues.getOrNull(2).orEmpty()
        val md5 = sniff.groupValues.getOrNull(3).orEmpty()
        val cache = sniff.groupValues.getOrNull(4).orEmpty().ifBlank { "1" }
        if (uid.isBlank() || md5.isBlank()) return null
        return "${getBaseUrl(baseUrl)}/m3u8/$uid/$md5/master.txt?s=1&cache=$cache"
    }

    private fun addCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (raw.isBlank()) return
        val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl).trim()
        if (fixed.isBlank() || isAdUrl(fixed) || shouldSkipUrl(fixed)) return

        when {
            isHlsLike(fixed) || fixed.contains(".mp4", true) || fixed.contains(".webm", true) -> directLinks.add(fixed)
            isAsiastreamWatch(fixed) -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && isKnownHost(fixed) -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && (fixed.contains("embed", true) || fixed.contains("player", true) || fixed.contains("stream", true)) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(link: String, referer: String, callback: (ExtractorLink) -> Unit) {
        if (isAdUrl(link) || shouldSkipUrl(link)) return
        val fixedReferer = referer.ifBlank { mainUrl }
        val linkHeaders = mediaHeaders + mapOf(
            "Referer" to fixedReferer,
            "Origin" to getBaseUrl(fixedReferer)
        )

        if (isHlsLike(link)) {
            generateM3u8(
                source = name,
                streamUrl = link,
                referer = fixedReferer,
                headers = linkHeaders
            ).forEach(callback)
            return
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = fixedReferer
                this.quality = getQualityFromName(link).takeIf { it != Qualities.Unknown.value } ?: qualityFromUrl(link)
                this.headers = linkHeaders
            }
        )
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^\"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|master\.txt|/m3u8/|/stream/)[^\"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """//[^\"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|master\.txt|/m3u8/|/stream/)[^\"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped()}" }
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^\"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|master\.txt|watch\.asiastream|drive|gdrive|filemoon|streamwish|dood|streamtape|vidhide|voe|mixdrop|mp4upload)[^\"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .map { it.cleanEscaped() }
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoSource|videoUrl|video_url|embedUrl|embed_url|contentUrl|stream|streamUrl|stream_url|download_url)\s*[:=]\s*[\"']([^\"']+)[\"']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .filter { isLikelyPlayable(it) || isKnownHost(it) }
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^\"'\\\s<>]+?(?:watch\.asiastream|drive|gdrive|dood|streamtape|filemoon|vidhide|vidguard|mp4upload|okru|odnoklassniki|sendvid|streamwish|wishfast|voe|filelions|lulustream|mixdrop|krakenfiles|acefile|mega)[^\"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun prioritizeEmbeds(links: Collection<String>): List<String> {
        return links
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .distinct()
            .sortedWith(compareBy<String> { hostPriority(it) }.thenBy { it.length })
    }

    private fun hostPriority(url: String): Int {
        val value = url.lowercase()
        return when {
            value.contains("watch.asiastream.cc") -> 0
            value.contains("drive.google") || value.contains("gdrive") -> 1
            value.contains("filemoon") -> 2
            value.contains("streamwish") || value.contains("wishfast") -> 3
            value.contains("dood") -> 4
            value.contains("streamtape") -> 5
            value.contains("vidhide") || value.contains("vidguard") -> 6
            value.contains("voe") -> 7
            value.contains("mixdrop") -> 8
            value.contains("mp4upload") -> 9
            value.contains("ok.ru") || value.contains("odnoklassniki") -> 10
            value.contains("embed") -> 20
            value.contains("player") -> 21
            value.contains("stream") -> 22
            else -> 50
        }
    }

    private fun isKnownHost(url: String): Boolean {
        val value = url.lowercase()
        return listOf(
            "watch.asiastream.cc",
            "drive.google", "googleusercontent", "gdrive",
            "dood", "streamtape", "filemoon", "vidhide", "vidguard", "mp4upload",
            "ok.ru", "odnoklassniki", "sendvid", "streamwish", "wishfast", "voe",
            "filelions", "lulustream", "mixdrop", "krakenfiles", "acefile", "mega.nz"
        ).any { value.contains(it) }
    }

    private fun isLikelyPlayable(url: String): Boolean {
        return isHlsLike(url) || url.contains(".mp4", true) || url.contains(".webm", true) || isKnownHost(url)
    }

    private fun isAsiastreamWatch(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("watch.asiastream.cc/watch") || value.contains("watch.asiastream.cc/embed")
    }

    private fun isHlsLike(url: String): Boolean {
        val value = url.lowercase()
        return value.contains(".m3u8") ||
            value.contains("/m3u8/") ||
            value.contains("master.txt") ||
            (value.contains("watch.asiastream.cc/stream/") && !value.contains(".jpg"))
    }

    private fun isLikelyDownloadText(text: String): Boolean {
        return text.contains("download") || text.contains("stream") || text.contains("nonton") ||
            text.contains("server") || text.contains("play") || text.contains("premium") ||
            text.contains("360p") || text.contains("480p") || text.contains("720p") || text.contains("1080p")
    }

    private fun shouldSkipUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() ||
            value.contains("facebook.com") || value.contains("twitter.com") || value.contains("telegram") ||
            value.contains("whatsapp") || value.contains("mailto:") || value.contains("youtube.com") ||
            value.contains("youtu.be") || value.contains("trailer") || value.contains("/ads/") ||
            value.contains("doubleclick") || value.contains("googlesyndication") || value.contains("googletagmanager") ||
            value.contains("recaptcha") || value.contains("cloudflareinsights") || value.contains("histats") ||
            value.contains("dtscout") || value.contains("dtscdn") || value.contains("safebrowsing")
    }

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped().trim()
        return when {
            clean.isBlank() -> ""
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "${getBaseUrl(baseUrl)}$clean"
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }

    private fun resolveRefererFor(url: String, fallback: String): String {
        return when {
            url.contains("watch.asiastream.cc", true) -> {
                val watchId = Regex("""watch\.asiastream\.cc/watch\?v=([^&\"'<>\s]+)""", RegexOption.IGNORE_CASE)
                    .find(fallback)
                    ?.groupValues
                    ?.getOrNull(1)
                if (!watchId.isNullOrBlank()) "https://watch.asiastream.cc/watch?v=$watchId" else fallback
            }
            else -> fallback
        }
    }

    private fun getPoster(document: Document): String? {
        return fixUrlNull(
            document.selectFirst(
                "div.gmr-movie-data figure img, figure.pull-left img, article img.wp-post-image, " +
                    "div.content-thumbnail img, img[data-src], img[data-lazy-src], img[src]"
            )?.getImageAttr()
        )?.takeIf { !isBadImage(it) }
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? {
            if (value.isNullOrBlank()) return null
            return value.split(',')
                .map { it.trim().substringBefore(' ') }
                .lastOrNull { it.isNotBlank() && !isBadImage(it) }
        }

        val raw = fromSrcSet(attr("data-srcset"))
            ?: fromSrcSet(attr("data-lazy-srcset"))
            ?: fromSrcSet(attr("srcset"))
            ?: attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }

        return raw?.trim()?.takeIf { !isBadImage(it) }
    }

    private fun isBadImage(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() || value.startsWith("data:image") || value.contains("blank") ||
            value.contains("placeholder") || value.contains("default") || value.contains("no-image") ||
            value.contains("noimage") || value.contains("loader") || value.contains("loading") ||
            value.contains("spacer") || value.contains("logo") || value.contains("favicon") ||
            value.contains("facebook.com/tr") || value.endsWith(".svg")
    }

    private fun parseTags(document: Document): List<String> {
        return document.select("div.gmr-moviedata a[rel='category tag'], div.gmr-moviedata a[href*='/film-'], div.gmr-moviedata a[href*='/country/'], .gmr-movie-on a[rel='category tag']")
            .map { it.text().trim().cleanTitle() }
            .filter { it.isNotBlank() && !isBadTitle(it) }
            .distinct()
            .take(12)
    }

    private fun parseActors(document: Document): List<Actor> {
        return document.select("div.gmr-moviedata a[href*='/cast/'], a[href*='/actor/'], a[href*='/stars/']")
            .map { it.text().trim().cleanTitle() }
            .filter { it.isNotBlank() && !isBadTitle(it) }
            .distinct()
            .take(20)
            .map { Actor(it) }
    }

    private fun guessType(url: String, text: String, title: String): TvType {
        val value = "$url $text $title"
        return when {
            url.contains("/tv/", true) -> TvType.AsianDrama
            value.contains("jumlah episode", true) -> TvType.AsianDrama
            value.contains("series", true) || value.contains("season", true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun firstNumber(text: String?): Int? {
        return Regex("""\b(19|20)\d{2}\b""").find(text.orEmpty())?.value?.toIntOrNull()
    }

    private fun extractYear(text: String?): Int? = firstNumber(text)

    private fun parseDuration(text: String?): Int? {
        return Regex("""(\d+)\s*(?:min|menit)""", RegexOption.IGNORE_CASE)
            .find(text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseRating(text: String): String? {
        return Regex("""(?:rating\s*:\s*)?([0-9](?:\.[0-9])?|10(?:\.0)?)\s*(?:dari\s*10|/10)?""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull()
    }

    private fun extractEpisodeNumber(text: String, href: String): Int? {
        return Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find("$text $href")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            url.contains("240", true) -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun isAdUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("vast") || value.contains("preroll") || value.contains("doubleclick") ||
            value.contains("googlesyndication") || value.contains("popads") || value.contains("adskeeper") ||
            value.contains("adsterra") || value.contains("/ads/") || value.contains("banner") ||
            value.contains("tracking") || value.contains("analytics") || value.contains("dtscout") ||
            value.contains("histats")
    }

    private fun String.cleanEscaped(): String {
        return replace("&amp;", "&")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")
            .replace("&#038;", "&")
            .replace("&#8211;", "-")
            .replace("&#8217;", "'")
            .replace("&#8220;", "\"")
            .replace("&#8221;", "\"")
    }

    private fun String.cleanTitle(): String {
        return cleanEscaped()
            .replace(Regex("""(?i)^indodrama21\s+"""), "")
            .replace(Regex("""(?i)\s+(lk21|layarkaca21|rebahin|bioskopkeren|sub indo).*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|')
    }

    private fun isBadTitle(title: String): Boolean {
        val value = title.trim().lowercase()
        return value.isBlank() ||
            value in setOf("home", "movie", "tv series", "genre", "country", "nonton", "tonton", "download", "play", "premium") ||
            value.contains("pasang iklan") ||
            value.contains("privacy") ||
            value.contains("dmca")
    }
}
