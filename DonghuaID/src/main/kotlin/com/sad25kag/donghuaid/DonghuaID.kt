package com.sad25kag.donghuaid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
            val data = episodes.firstOrNull()?.data ?: url
            newMovieLoadResponse(title, url, type, data) {
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
        val response = app.get(data, headers = siteHeaders, referer = "$mainUrl/")
        val document = response.document
        val emitted = linkedSetOf<String>()

        fun countedCallback(link: ExtractorLink) {
            if (emitted.add(link.url.substringBefore("#"))) {
                callback.invoke(link)
            }
        }

        fun emitDirect(rawUrl: String, label: String?, referer: String): Boolean {
            val finalUrl = rawUrl.normalizePlayerUrl(referer)?.takeIf { it.isDirectMediaLike() } ?: return false
            if (!emitted.add(finalUrl.substringBefore("#"))) return true
            val linkName = listOfNotNull(name, label?.cleanText()?.takeIf { it.isNotBlank() }).joinToString(" - ")
            callback.invoke(
                newExtractorLink(linkName, linkName, finalUrl, inferType(finalUrl)) {
                    this.referer = referer
                    this.quality = getQualityFromName(label ?: finalUrl)
                    this.headers = siteHeaders + mapOf(
                        "Referer" to referer,
                        "Origin" to originOf(referer),
                        "Range" to "bytes=0-",
                    )
                }
            )
            return true
        }

        val candidates = collectSourcePlayerCandidates(document, data)
        for (candidate in candidates) {
            val playerUrl = candidate.url.normalizePlayerUrl(candidate.referer) ?: continue
            val before = emitted.size
            if (playerUrl.isDirectMediaLike()) {
                emitDirect(playerUrl, candidate.label, candidate.referer)
            } else {
                runCatching { loadExtractor(playerUrl, candidate.referer, subtitleCallback) { link -> countedCallback(link) } }
            }
            if (emitted.size > before) continue
        }

        return emitted.isNotEmpty()
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

    private fun parseEpisodes(document: Document, pageUrl: String): List<Episode> {
        val scopedAnchors = document.select(
            ".episodelist li a[href], .eplister li a[href], .bixbox.bxcl li a[href], .episodelist a[href*='episode']"
        )
        val anchors = if (scopedAnchors.isNotEmpty()) scopedAnchors else {
            val currentSlug = pageUrl.slugSeriesKey()
            document.select("article.post a[href*='episode'], .postbody a[href*='episode'], .entry-content a[href*='episode']")
                .filter { anchor -> anchor.attr("href").absoluteUrl(pageUrl)?.slugSeriesKey() == currentSlug }
        }

        return anchors.mapNotNull { anchor ->
            val href = anchor.attr("href").absoluteUrl(pageUrl) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl, true) || !href.contains("episode", true)) return@mapNotNull null
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

    private data class PlayerCandidate(
        val url: String,
        val label: String? = null,
        val referer: String,
    )

    private fun collectSourcePlayerCandidates(document: Document, referer: String): LinkedHashSet<PlayerCandidate> {
        val candidates = linkedSetOf<PlayerCandidate>()

        fun addPlayer(rawUrl: String?, label: String? = null, candidateReferer: String = referer) {
            val fixedUrl = rawUrl?.normalizePlayerUrl(candidateReferer) ?: return
            if (!fixedUrl.isUsablePlayerUrl() && !fixedUrl.isDirectMediaLike()) return
            candidates.add(PlayerCandidate(fixedUrl, label?.cleanText()?.takeIf { it.isNotBlank() }, candidateReferer))
        }

        // Source structure: server list/player option is the primary source of truth.
        document.select(".mobius option[value], .mirror option[value], select option[value]").forEach { option ->
            val value = option.attr("value").trim()
            if (value.isBlank()) return@forEach
            val label = option.text().cleanText().takeIf { it.isNotBlank() && !it.equals("Select Video Server", true) }
            decodeServerOption(value, referer).forEach { playerUrl -> addPlayer(playerUrl, label, referer) }
        }

        // Fallback only when source exposes iframe/player directly on the same watch page.
        if (candidates.isEmpty()) {
            document.select("#embed_holder, #pembed, .player-embed, .mobius, .mirror, .server, .servers, .player, body")
                .select("iframe[src], embed[src], video[src], source[src], meta[itemprop=embedUrl][content]")
                .forEach { node ->
                    val src = node.attr("src").ifBlank { node.attr("content") }
                    addPlayer(src, node.attr("title").ifBlank { hostLabel(src) }, referer)
                }
        }

        return candidates
    }

    private fun decodeServerOption(value: String, referer: String): List<String> {
        val results = linkedSetOf<String>()
        val rawVariants = linkedSetOf<String>()
        val clean = value.cleanOptionValue()
        rawVariants.add(clean)
        clean.percentDecodePreservePlus().takeIf { it != clean }?.let { rawVariants.add(it.cleanOptionValue()) }

        rawVariants.toList().forEach { variant ->
            decodeBase64(variant)?.let { rawVariants.add(it.cleanDecodedHtml()) }
            decodeBase64UrlSafe(variant)?.let { rawVariants.add(it.cleanDecodedHtml()) }
        }

        rawVariants.forEach { decoded ->
            decoded.normalizePlayerUrl(referer)?.let { fixed ->
                if (fixed.isUsablePlayerUrl() || fixed.isDirectMediaLike()) results.add(fixed)
            }

            val parsed = Jsoup.parse(decoded)
            parsed.select("iframe[src], embed[src], video[src], source[src], meta[itemprop=embedUrl][content]").forEach { node ->
                val src = node.attr("src").ifBlank { node.attr("content") }
                src.normalizePlayerUrl(referer)?.let { fixed ->
                    if (fixed.isUsablePlayerUrl() || fixed.isDirectMediaLike()) results.add(fixed)
                }
            }
        }
        return results.toList()
    }

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

    private fun String.decodeEmbedText(): String {
        var value = cleanOptionValue()
        repeat(2) {
            value = value.percentDecodePreservePlus()
        }
        return value.trim()
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

    private fun String.slugSeriesKey(): String {
        val slug = substringBefore("?").trimEnd('/').substringAfterLast('/')
        return slug
            .replace(Regex("""(?i)-episode-?\d+.*$"""), "")
            .replace(Regex("""(?i)-eps?-?\d+.*$"""), "")
            .trim('-')
    }

    private fun String.isDirectMediaLike(): Boolean {
        val value = lowercase(Locale.ROOT).substringBefore("#")
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".m4s") || value.contains(".webm") || value.contains(".mkv") || value.contains("videoplayback") || value.contains("/stream/")
    }

    private fun String.isUsablePlayerUrl(): Boolean {
        val value = decodeEmbedText().lowercase(Locale.ROOT).substringBefore("#")
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false
        if (isDirectMediaLike()) return true
        if (value.contains("google-analytics") || value.contains("googletagmanager") || value.contains("doubleclick")) return false
        val path = value.substringBefore("?")
        val assetExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".css", ".js", ".woff", ".woff2", ".ttf", ".ico")
        return assetExtensions.none { path.endsWith(it) }
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

    private fun inferType(url: String): ExtractorLinkType = when {
        url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun hostLabel(url: String): String? = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()
}
