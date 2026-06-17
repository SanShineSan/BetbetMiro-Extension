package com.sad25kag.donghuaid

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newEpisode
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

class DonghuaID : MainAPI() {
    override var mainUrl = "https://donghuaid.live"
    override var name = "DonghuaID"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Release",
        "$mainUrl/anime/?status=ongoing&type=&sub=&order=&page={page}" to "Ongoing",
        "$mainUrl/anime/?status=completed&type=&sub=&order=&page={page}" to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val document = app.get(url, headers = siteHeaders, referer = "$mainUrl/").document
        val results = parseDonghuaCards(document, includeSidebar = request.data == "$mainUrl/")
            .distinctBy { it.url.normalizedKey() }
        val hasNext = document.selectFirst(
            "a.next[href], a.next.page-numbers[href], link[rel=next], .hpage a[href*='page=${page + 1}'], a[href*='/page/${page + 1}/'], a[href*='page=${page + 1}']"
        ) != null
        return newHomePageResponse(request.name, results, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val routes = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/anime/?status=&type=&sub=&order=&keyword=$encoded",
            "$mainUrl/anime/?s=$encoded",
        )
        return routes.flatMap { route ->
            runCatching {
                parseDonghuaCards(app.get(route, headers = siteHeaders, referer = "$mainUrl/").document, includeSidebar = false)
            }.getOrDefault(emptyList())
        }
            .filter { result -> result.name.contains(query, true) || result.url.contains(query.slugHint(), true) }
            .distinctBy { it.url.normalizedKey() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = siteHeaders, referer = "$mainUrl/").document
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1[itemprop=name], .infox h1, .entry-title, h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ) ?: throw ErrorLoadingException("Judul DonghuaID tidak ditemukan")

        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")?.absoluteUrl(url)
            ?: document.selectFirst(".thumb img, .ime img, .bigcontent img, .poster img, img.wp-post-image")?.imageUrl(url)

        val plot = document.selectFirst(".entry-content, .synopsis, .desc, .mindesc, .bixbox.synp, article .content")
            ?.text()
            ?.cleanText()
            ?.takeIf { it.length > 20 }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.cleanText()

        val tags = document.select(".info-content a[href*='genre'], .spe a[href*='genre'], a[rel=tag]")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() && !it.equals("Genres", true) }
            .distinct()

        val infoText = document.select(".spe, .info-content, .infotable, .bigcontent, .tsinfo, .postbody").joinToString(" ") { it.text() }.cleanText()
        val year = Regex("""(?i)(?:Released|Rilis|Aired|Year)\s*:?\s*([12][0-9]{3})""").find(infoText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val status = detectStatus(infoText)
        val episodes = parseEpisodes(document, url).distinctBy { it.data.normalizedKey() }

        val type = when {
            episodes.size > 1 -> TvType.Anime
            infoText.contains("Movie", true) || tags.any { it.equals("Movie", true) } || url.contains("movie", true) -> TvType.AnimeMovie
            infoText.contains("OVA", true) || tags.any { it.equals("OVA", true) } -> TvType.OVA
            else -> TvType.Anime
        }

        val recommendations = parseDonghuaCards(document, includeSidebar = false)
            .filterNot { it.url.normalizedKey() == url.normalizedKey() }
            .take(16)

        return if (type == TvType.Anime && episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.showStatus = status
                this.recommendations = recommendations
            }
        } else {
            // Phisher-style: movie/single item also uses the first watch/episode link when available.
            val watchLink = episodes.firstOrNull()?.data ?: url
            newMovieLoadResponse(title, url, type, watchLink) {
                this.posterUrl = poster
                this.plot = plot
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
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val emitted = linkedSetOf<String>()

        fun countedCallback(link: ExtractorLink) {
            if (emitted.add(link.url.substringBefore("#"))) {
                callback.invoke(link)
            }
        }

        suspend fun runSourceFlow(watchUrl: String): Boolean {
            val document = app.get(watchUrl, headers = siteHeaders, referer = "$mainUrl/").document
            val serverOptions = document.select(".mobius option[value], .mirror option[value], .servers option[value], select option[value]")
                .filter { option -> option.attr("value").trim().isNotBlank() }

            for (server in serverOptions) {
                val value = server.attr("value").trim()
                val iframeUrls = decodeServerOption(value, watchUrl)
                for (iframeUrl in iframeUrls) {
                    val before = emitted.size
                    runCatching {
                        loadExtractor(iframeUrl, watchUrl, subtitleCallback) { link -> countedCallback(link) }
                    }
                    if (emitted.size > before) {
                        // Keep trying the remaining mirrors only when needed by app/user; one emitted link already proves this mirror works.
                        continue
                    }
                }
            }
            return emitted.isNotEmpty()
        }

        val inputDocument = app.get(data, headers = siteHeaders, referer = "$mainUrl/").document

        // If the test/app accidentally sends a detail URL into loadLinks, keep the source flow:
        // detail -> first episode/watch link -> watch page -> .mobius option -> decode -> iframe -> loadExtractor.
        if (data.isDetailUrl()) {
            val watchLink = firstEpisodeWatchLink(inputDocument, data) ?: return false
            return runSourceFlow(watchLink)
        }

        if (runSourceFlow(data)) return true

        // Final source-flow guard for unusual pages: only resolve to a watch link; do not scan/guess hosts from the detail page.
        val watchLink = firstEpisodeWatchLink(inputDocument, data)
        return if (!watchLink.isNullOrBlank() && watchLink.normalizedKey() != data.normalizedKey()) {
            runSourceFlow(watchLink)
        } else {
            false
        }
    }

    private fun decodeServerOption(value: String, referer: String): List<String> {
        val decodedValues = linkedSetOf<String>()
        val clean = value.cleanOptionValue()
        decodedValues.add(clean)
        clean.percentDecodePreservePlus().takeIf { it != clean }?.let { decodedValues.add(it.cleanOptionValue()) }

        decodedValues.toList().forEach { candidate ->
            decodeBase64(candidate)?.let { decodedValues.add(it.cleanDecodedHtml()) }
            decodeBase64UrlSafe(candidate)?.let { decodedValues.add(it.cleanDecodedHtml()) }
        }

        val iframeUrls = linkedSetOf<String>()
        decodedValues.forEach { decoded ->
            decoded.normalizePlayerUrl(referer)?.takeIf { it.isPlayerUrl() }?.let { iframeUrls.add(it) }
            val parsed = Jsoup.parse(decoded)
            parsed.select("iframe[src], embed[src], video[src], source[src], meta[itemprop=embedUrl][content]").forEach { node ->
                val src = node.attr("src").ifBlank { node.attr("content") }
                src.normalizePlayerUrl(referer)?.takeIf { it.isPlayerUrl() }?.let { iframeUrls.add(it) }
            }
        }
        return iframeUrls.toList()
    }

    private fun parseDonghuaCards(document: Document, includeSidebar: Boolean = false): List<SearchResponse> {
        val primarySelectors = listOf(
            ".listupd article.bs, .listupd article, .listupd .bs",
            ".result .bsx, .search-page article",
            ".items .item, .post-show li, .latest li",
        )
        val sidebarSelectors = listOf(
            ".serieslist.pop ul li, .ongoingseries ul li, .bixbox ul li",
        )

        val primary = primarySelectors.asSequence()
            .flatMap { selector -> document.select(selector).asSequence() }
            .mapNotNull { it.toDonghuaCard() }
            .distinctBy { it.url.normalizedKey() }
            .toList()

        if (primary.isNotEmpty() || !includeSidebar) return primary

        return sidebarSelectors.asSequence()
            .flatMap { selector -> document.select(selector).asSequence() }
            .mapNotNull { it.toDonghuaCard() }
            .distinctBy { it.url.normalizedKey() }
            .toList()
    }

    private fun Element.toDonghuaCard(): SearchResponse? {
        val anchor = selectFirst(
            ".bsx a[href], a.series[href], .tt a[href], h2 a[href], h3 a[href], h4 a[href], a[href*='/anime/'], a[href*='episode'], a[href]"
        ) ?: return null
        val href = anchor.attr("href").absoluteUrl(mainUrl) ?: return null
        if (!href.startsWith(mainUrl, true)) return null
        if (!href.isDonghuaContentUrl()) return null

        val rawTitle = anchor.attr("title").cleanText().takeIf { it.length > 2 }
            ?: selectFirst(".tt h2, .tt, .eggtitle, .epl-title, h2, h3, h4")?.text()?.cleanText()?.takeIf { it.length > 2 }
            ?: selectFirst("img")?.attr("title")?.cleanText()?.takeIf { it.length > 2 }
            ?: selectFirst("img")?.attr("alt")?.cleanText()?.takeIf { it.length > 2 }
            ?: anchor.text().cleanText().takeIf { it.length > 2 }
            ?: return null

        val title = cleanCardTitle(rawTitle).takeIf { it.length > 2 } ?: return null
        val poster = selectFirst("img")?.imageUrl(href) ?: anchor.selectFirst("img")?.imageUrl(href)
        val typeText = listOf(
            selectFirst(".typez")?.text(),
            selectFirst(".epx")?.text(),
            text(),
            href,
        ).joinToString(" ") { it.orEmpty() }
        val tvType = when {
            typeText.contains("Movie", true) -> TvType.AnimeMovie
            typeText.contains("OVA", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodes(document: Document, pageUrl: String): List<com.lagradost.cloudstream3.Episode> {
        val anchors = document.select(
            "div.eplister > ul > li a[href], .eplister a[href], .episodelist a[href], .bixbox.bxcl a[href], #episodes a[href], #episode a[href], .episodes a[href], .episode-list a[href], .epcheck a[href], .episodios a[href], .listing a[href]"
        )

        return anchors.mapNotNull { anchor ->
            val href = anchor.attr("href").absoluteUrl(pageUrl) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl, true)) return@mapNotNull null
            if (!href.isWatchUrlCandidate(pageUrl, true)) return@mapNotNull null
            val rawTitle = anchor.selectFirst(".epl-title, .playinfo h3, h3, .title")?.text()?.cleanText()
                ?: anchor.ownText().cleanText().takeIf { it.length > 2 }
                ?: anchor.text().cleanText().takeIf { it.length > 2 && !it.equals("Prev", true) && !it.equals("Next", true) }
                ?: href.substringAfter(mainUrl).trim('/').replace('-', ' ')
            val episodeNumber = anchor.selectFirst(".epl-num, .epx, .num")?.text()?.episodeNumber()
                ?: rawTitle.episodeNumber()
                ?: href.episodeNumber()
            newEpisode(href) {
                this.name = cleanTitle(rawTitle) ?: rawTitle
                this.episode = episodeNumber
                this.posterUrl = anchor.selectFirst("img")?.imageUrl(href)
            }
        }
            .distinctBy { it.data.normalizedKey() }
            .sortedByDescending { it.episode ?: -1 }
    }

    private fun firstEpisodeWatchLink(document: Document, pageUrl: String): String? {
        return parseEpisodes(document, pageUrl)
            .map { it.data }
            .firstOrNull { it.normalizedKey() != pageUrl.normalizedKey() }
            ?: document.selectFirst(".mobius option[value], .mirror option[value], select option[value]")?.let { pageUrl }
    }

    private fun buildPagedUrl(rawUrl: String, page: Int): String {
        if (rawUrl.contains("{page}")) {
            return if (page <= 1) {
                rawUrl
                    .replace("/page/{page}/", "/")
                    .replace("/page/{page}", "/")
                    .replace("page={page}", "page=1")
            } else {
                rawUrl.replace("{page}", page.toString())
            }
        }
        if (page <= 1) return rawUrl
        val clean = rawUrl.trimEnd('/')
        return when {
            clean.contains("page=") -> clean.replace(Regex("""page=\d+"""), "page=$page")
            clean.contains("?") -> "$clean&page=$page"
            else -> "$clean/page/$page/"
        }
    }

    private fun detectStatus(infoText: String): ShowStatus? {
        val value = infoText.lowercase(Locale.ROOT)
        return when {
            value.contains("completed") || value.contains("selesai") || value.contains("end") -> ShowStatus.Completed
            value.contains("ongoing") || value.contains("airing") || value.contains("tayang") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun Element.imageUrl(base: String = mainUrl): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        val raw = listOf("data-src", "data-lazy-src", "data-original", "data-image", "src", "poster", "srcset")
            .firstNotNullOfOrNull { attr ->
                image?.attr(attr)?.split(",")?.firstOrNull()?.substringBefore(" ")?.trim()?.takeIf { it.isImageCandidate() }
            }
        return raw?.absoluteUrl(base)
    }

    private fun cleanCardTitle(raw: String): String = raw.cleanText()
        .replace(Regex("""(?i)^\s*(?:ONA|TV|Movie|OVA|Special)\s+"""), "")
        .replace(Regex("""(?i)^\s*(?:Ongoing|Completed|Upcoming|Hiatus)\s+"""), "")
        .replace(Regex("""(?i)^\s*(?:Ep|Episode|Eps?)\s*\d+\s*"""), "")
        .replace(Regex("""(?i)\s+Episode\s+\d+.*$"""), "")
        .replace(Regex("""(?i)\s+Sub(?:title)?\s*(?:Indo|Indonesia)?.*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun cleanTitle(raw: String?): String? = raw?.cleanText()
        ?.replace(Regex("""(?i)^\s*Nonton\s+"""), "")
        ?.replace(Regex("""(?i)^\s*Download\s+"""), "")
        ?.replace(Regex("""(?i)\s+-\s+Donghuaid.*$"""), "")
        ?.replace(Regex("""(?i)\s+Subtitle\s+Indonesia.*$"""), "")
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()
        ?.takeIf { it.length > 1 }

    private fun String.cleanText(): String = Jsoup.parse(this).text()
        .replace("\u00a0", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.cleanOptionValue(): String = trim()
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun String.cleanDecodedHtml(): String = cleanOptionValue()
        .replace("\\&quot;", "\"")
        .replace("\\\\", "\\")

    private fun String.percentDecodePreservePlus(): String {
        val safe = replace("+", "%2B")
        return runCatching { URLDecoder.decode(safe, "UTF-8") }.getOrDefault(this)
    }

    private fun decodeBase64(value: String): String? {
        val clean = value.trim().replace("\n", "").replace("\r", "")
        if (clean.length < 12 || clean.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '+' && it != '/' && it != '=' }) return null
        val padded = clean.padEnd(clean.length + (4 - clean.length % 4) % 4, '=')
        return runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.contains("<iframe", true) || it.contains("http", true) }
    }

    private fun decodeBase64UrlSafe(value: String): String? {
        val clean = value.trim().replace("\n", "").replace("\r", "")
        if (clean.length < 12 || clean.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_' && it != '=' }) return null
        val padded = clean.padEnd(clean.length + (4 - clean.length % 4) % 4, '=')
        return runCatching { String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.contains("<iframe", true) || it.contains("http", true) }
    }

    private fun String.normalizePlayerUrl(baseUrl: String = mainUrl): String? {
        val value = cleanDecodedHtml().percentDecodePreservePlus().trim().trim('"', '\'')
        if (value.isBlank() || value.startsWith("javascript:", true) || value == "#" || value.startsWith("data:", true)) return null
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return runCatching { URI(baseUrl).resolve(value).toString() }.getOrNull()
    }

    private fun String.absoluteUrl(baseUrl: String = mainUrl): String? {
        val value = trim().trim('"', '\'').replace("\\/", "/")
        if (value.isBlank() || value.startsWith("javascript:", true) || value == "#" || value.startsWith("data:", true)) return null
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return runCatching { URI(baseUrl).resolve(value).toString() }.getOrNull()
    }

    private fun String.normalizedKey(): String = substringBefore("#").trimEnd('/').lowercase(Locale.ROOT)

    private fun String.episodeNumber(): Int? = Regex("""(?i)(?:episode|eps?|ep)\s*\.?\s*(\d{1,4})""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("""\b(\d{1,4})\b""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String.slugHint(): String = lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), "-").trim('-')

    private fun String.isDirectMediaLike(): Boolean {
        val value = lowercase(Locale.ROOT).substringBefore("#")
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".m4s") || value.contains(".webm") || value.contains(".mkv") || value.contains("videoplayback") || value.contains("/stream/")
    }

    private fun String.isPlayerUrl(): Boolean {
        val value = lowercase(Locale.ROOT).substringBefore("#")
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false
        if (isDirectMediaLike()) return true
        if (value.contains("google-analytics") || value.contains("googletagmanager") || value.contains("doubleclick")) return false
        val path = value.substringBefore("?")
        val assetExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".css", ".js", ".woff", ".woff2", ".ttf", ".ico")
        return assetExtensions.none { path.endsWith(it) }
    }

    private fun String.isDetailUrl(): Boolean {
        val value = substringBefore("#").substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)
        val root = mainUrl.trimEnd('/').lowercase(Locale.ROOT)
        if (!value.startsWith(root)) return false
        val path = value.removePrefix(root).trim('/')
        return path.startsWith("anime/")
    }

    private fun String.isWatchUrlCandidate(pageUrl: String, fromEpisodeContainer: Boolean): Boolean {
        val value = substringBefore("#").substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)
        val root = mainUrl.trimEnd('/').lowercase(Locale.ROOT)
        if (!value.startsWith(root)) return false
        val path = value.removePrefix(root).trim('/')
        if (path.isBlank()) return false
        if (path.startsWith("anime/") || path.startsWith("genre/") || path.startsWith("genres/") || path.startsWith("tag/") || path.startsWith("category/") || path.startsWith("wp-")) return false
        if (path.contains("/page/")) return false
        if (path.contains("episode") || path.contains("-eps-") || path.contains("-ep-")) return true
        // Movie pages on DonghuaID often use the same page as the watch page and appear inside eplister.
        return fromEpisodeContainer && !path.contains("/") && value != pageUrl.normalizedKey()
    }

    private fun String.isDonghuaContentUrl(): Boolean {
        val value = substringBefore("#").substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)
        val root = mainUrl.trimEnd('/').lowercase(Locale.ROOT)
        if (value == root || !value.startsWith(root)) return false
        val path = value.removePrefix(root).trim('/')
        if (path.isBlank()) return false
        if (path.startsWith("wp-") || path.startsWith("tag/") || path.startsWith("category/") || path.startsWith("genres/")) return false
        if (path.startsWith("anime/list-mode") || path.startsWith("anime/page") || path == "anime") return false
        if (path.contains("/page/")) return false
        return true
    }

    private fun String.isImageCandidate(): Boolean {
        if (isBlank() || startsWith("data:", true)) return false
        if (contains("blank", true) || contains("placeholder", true) || contains("spacer", true)) return false
        return contains(".jpg", true) || contains(".jpeg", true) || contains(".png", true) || contains(".webp", true) || contains("/wp-content/uploads/", true)
    }
}
