package com.sad25kag.animeisme

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeIsMe : MainAPI() {
    override var mainUrl = "https://animeisme.net"
    override var name = "AnimeIsMe"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private val bloggerHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://www.blogger.com",
        "Referer" to "https://www.blogger.com/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Update Terbaru",
        "$mainUrl/anime/?status=&type=&sub=&order=" to "Daftar Anime",
        "$mainUrl/anime/?status=ongoing&type=&order=" to "Ongoing",
        "$mainUrl/anime/?status=completed&sub=" to "Completed",
        "$mainUrl/anime/?status=&type=movie&order=" to "Movie",
        "$mainUrl/genres/action/" to "Action",
        "$mainUrl/genres/adventure/" to "Adventure",
        "$mainUrl/genres/fantasy/" to "Fantasy",
        "$mainUrl/genres/shounen/" to "Shounen"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page), headers = siteHeaders).document
        val items = document.parseItems().distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = document.hasNextPage(page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val encoded = withContext(Dispatchers.IO) { URLEncoder.encode(cleanQuery, "UTF-8") }
        val results = linkedMapOf<String, SearchResponse>()
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/index.php?s=$encoded",
            "$mainUrl/anime/?s=$encoded"
        )

        for (url in urls) {
            val document = runCatching { app.get(url, headers = siteHeaders, referer = "$mainUrl/").document }.getOrNull() ?: continue
            val parsed = document.parseItems().filter { it.matchesQuery(cleanQuery) }
            for (item in parsed) results[item.url] = item
            if (results.isNotEmpty()) break
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, headers = siteHeaders, referer = "$mainUrl/").document
        val title = document.bestTitle()?.cleanTitle()?.ifBlank { null } ?: fixedUrl.slugTitle()
        val poster = document.bestPoster()
        val plot = document.bestPlot()
        val tags = document.bestTags()
        val year = document.text().parseYear()
        val recommendations = document.parseItems().filter { it.url != fixedUrl }.distinctBy { it.url }.take(24)

        val episodes = document.parseEpisodes(fixedUrl, title, poster)

        val type = when {
            title.contains("Movie", true) || fixedUrl.contains("movie", true) -> TvType.AnimeMovie
            title.contains("OVA", true) || title.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(title, fixedUrl, type) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.takeIf { it.startsWith("http", true) } ?: return false
        val queue = ArrayDeque<ServerCandidate>()
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var hasLinks = false

        suspend fun enqueueFromText(text: String?, referer: String) {
            text?.extractCandidatesFromText(referer)?.forEach { queue.add(it) }
        }

        suspend fun enqueueFromUrl(url: String, referer: String) {
            val response = runCatching { app.get(url, headers = playerHeaders(url), referer = referer) }.getOrNull() ?: return
            response.document.extractCandidates(url).forEach { queue.add(it) }
            enqueueFromText(response.text, url)
        }

        suspend fun emitDirect(url: String, label: String?, referer: String, extraHeaders: Map<String, String> = emptyMap()) {
            val fixed = normalizeMediaUrl(url) ?: return
            val key = fixed.normalizedMediaKey()
            if (!emitted.add(key)) return
            val qualityLabel = label?.cleanLabel().orEmpty().ifBlank { fixed.qualityLabelFromUrl() }
            val quality = qualityLabel.takeIf { it.isNotBlank() }?.let { getQualityFromName(it) }
                ?: fixed.parseQuality()
                ?: fixed.parseGoogleVideoQuality()
                ?: Qualities.Unknown.value
            val headers = when {
                extraHeaders.isNotEmpty() -> extraHeaders
                fixed.contains("googlevideo.com/videoplayback", true) -> googleVideoHeaders
                else -> siteHeaders + mapOf("Range" to "bytes=0-")
            }
            val finalReferer = headers["Referer"] ?: referer

            if (fixed.contains(".m3u8", true)) {
                val links = M3u8Helper.generateM3u8(name, fixed, finalReferer, headers = headers)
                for (link in links) {
                    callback(link)
                    hasLinks = true
                }
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = listOf(name, qualityLabel).filter { it.isNotBlank() }.joinToString(" "),
                        url = fixed,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = finalReferer
                        this.quality = quality
                        this.headers = headers
                    }
                )
                hasLinks = true
            }
        }

        suspend fun resolveBlogger(url: String, label: String?, referer: String): Boolean {
            val links = resolveBloggerVideo(url, referer)
            links.forEach { link ->
                emitDirect(link, label ?: "Blogger", "https://youtube.googleapis.com/", googleVideoHeaders)
            }
            return links.isNotEmpty()
        }

        suspend fun emitExtractor(url: String, label: String?, referer: String) {
            val fixed = normalizeMediaUrl(url) ?: return
            if (fixed.isDirectMedia()) {
                emitDirect(fixed, label, referer)
                return
            }
            if (fixed.isBloggerVideoUrl()) {
                if (resolveBlogger(fixed, label, referer)) hasLinks = true
                return
            }
            val key = fixed.normalizedMediaKey()
            if (!emitted.add(key)) return
            loadExtractor(fixed, referer, subtitleCallback) { link ->
                callback(link)
                hasLinks = true
            }
        }

        if (pageUrl.isNavigationUrl()) return false

        enqueueFromUrl(pageUrl, "$mainUrl/")
        var guard = 0
        while (queue.isNotEmpty() && guard < 120) {
            guard++
            val candidate = queue.removeFirst()
            val fixed = normalizeMediaUrl(candidate.url) ?: continue
            if (!visited.add(fixed.normalizedMediaKey())) continue
            val referer = candidate.referer ?: pageUrl

            when {
                fixed.isDirectMedia() -> emitDirect(fixed, candidate.label, referer)
                fixed.isBloggerVideoUrl() -> if (resolveBlogger(fixed, candidate.label, referer)) hasLinks = true
                !fixed.startsWith(mainUrl, true) -> {
                    emitExtractor(fixed, candidate.label, referer)
                    if (fixed.shouldInlineResolve()) enqueueFromUrl(fixed, referer)
                }
                fixed.isInternalPlayerUrl() -> enqueueFromUrl(fixed, referer)
            }
        }

        return hasLinks
    }

    private val googleVideoHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://youtube.googleapis.com/",
        "Origin" to "https://www.blogger.com",
        "Range" to "bytes=0-"
    )

    private suspend fun resolveBloggerVideo(url: String, referer: String): List<String> {
        val token = url.extractQueryValue("token") ?: return emptyList()
        val page = runCatching {
            app.get(url, headers = bloggerHeaders, referer = referer)
        }.getOrNull() ?: return emptyList()

        val html = page.text
        val fSid = html.extractJsonValue("FdrFJe") ?: return emptyList()
        val bl = html.extractJsonValue("cfb2h") ?: return emptyList()
        val reqId = ((System.currentTimeMillis() % 90000L) + 10000L).toString()
        val endpoint = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute" +
            "?rpcids=WcwnYd&source-path=%2Fvideo.g" +
            "&f.sid=${fSid.urlEncode()}" +
            "&bl=${bl.urlEncode()}" +
            "&hl=id&_reqid=$reqId&rt=c"
        val request = "[[[\"WcwnYd\",\"[\\\"${token.escapeJson()}\\\",null,0]\",null,\"generic\"]]]"
        val response = runCatching {
            app.post(
                endpoint,
                data = mapOf("f.req" to request),
                headers = bloggerHeaders + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "X-Same-Domain" to "1"
                ),
                referer = "https://www.blogger.com/"
            )
        }.getOrNull() ?: return emptyList()

        return response.text.extractGoogleVideoLinks()
    }

    private fun buildPageUrl(data: String, page: Int): String {
        if (page <= 1) return data
        val sep = if (data.contains("?")) "&" else "?"
        return "${data.trimEnd('/')}$sep${"page=$page"}"
    }

    private fun Document.parseItems(): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val cardSelector = ".listupd article.bs, article.bs, .bs, .bsx, .swiper-slide, .serieslist li, .anime-list .bs, .postbody article"
        for (card in select(cardSelector)) {
            val response = card.toSearchResponse() ?: continue
            results[response.url] = response
        }
        return results.values.toList()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val card = bestCard()
        val anchor = when {
            tagName().equals("a", true) && hasAttr("href") -> this
            else -> card.selectFirst("a.tip[href], a[href*='/anime/'][href], a[href*='episode'][href], a[href]")
        } ?: return null

        val href = anchor.attr("href").toAbsoluteUrl() ?: return null
        if (!href.isContentItemUrl()) return null

        val title = listOf(
            card.selectFirst(".tt h2, .tt, h1, h2, h3, h4, h5, h6, .title, .judul, .anime-title")?.text(),
            anchor.attr("title"),
            card.selectFirst("img[title]")?.attr("title"),
            card.selectFirst("img[alt]")?.attr("alt"),
            anchor.text()
        ).firstCleanTitle() ?: return null
        if (title.isBlockedTitle()) return null

        val poster = card.bestImage()?.toAbsoluteUrl()
        val episode = card.text().parseEpisodeNumber() ?: href.parseEpisodeNumber()
        val tvType = when {
            title.contains("Movie", true) || href.contains("movie", true) -> TvType.AnimeMovie
            title.contains("OVA", true) || title.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, tvType) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private fun Element.bestCard(): Element {
        if (selectFirst("img") != null && selectFirst("a[href]") != null) return this
        return parents().firstOrNull { parent ->
            parent.selectFirst("img") != null && parent.selectFirst("a[href]") != null && parent.text().length in 2..1000
        } ?: this
    }

    private fun Document.parseEpisodes(pageUrl: String, title: String, poster: String?): List<Episode> {
        val episodeSelectors = listOf(
            ".episodelist a[href]",
            ".episode-list a[href]",
            ".eplister a[href]",
            ".all-episode a[href]",
            ".bixbox .episodelist a[href]"
        )
        val episodes = episodeSelectors
            .flatMap { selector -> select(selector) }
            .mapNotNull { it.toEpisodeOrNull() }
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.episode ?: Int.MAX_VALUE }, { it.name ?: "" }))

        if (episodes.isNotEmpty()) return episodes

        val hasInlinePlayer = extractCandidates(pageUrl).isNotEmpty()
        if (!hasInlinePlayer && !pageUrl.isEpisodeContentUrl(title)) return emptyList()

        return listOf(newEpisode(pageUrl) {
            this.name = when {
                title.parseEpisodeName() != null -> title.parseEpisodeName()
                pageUrl.isEpisodeContentUrl(title) -> title
                else -> "Movie"
            }
            this.episode = title.parseEpisodeNumber() ?: pageUrl.parseEpisodeNumber()
            this.posterUrl = poster
        })
    }

    private fun Element.toEpisodeOrNull(): Episode? {
        val href = attr("href").toAbsoluteUrl() ?: return null
        val rawText = text().trim().ifBlank { attr("title") }.ifBlank { href.slugTitle() }
        if (!href.isEpisodeContentUrl(rawText)) return null
        val ep = rawText.parseEpisodeNumber() ?: href.parseEpisodeNumber()
        return newEpisode(href) {
            this.name = rawText.parseEpisodeName() ?: ep?.let { "Episode $it" } ?: rawText.cleanTitle()
            this.episode = ep
            this.posterUrl = bestCard().bestImage()?.toAbsoluteUrl()
        }
    }

    private fun Document.extractCandidates(referer: String): List<ServerCandidate> {
        val results = linkedMapOf<String, ServerCandidate>()
        fun add(raw: String?, label: String? = null) {
            val fixed = raw?.trim()?.trim('"', '\'', ' ', ',', ')', ']', '}')?.basicHtmlDecode()?.unescapeJs()?.toAbsoluteUrl() ?: return
            if (fixed.isBlank() || fixed == mainUrl || fixed == referer) return
            if (fixed.startsWith("javascript:", true) || fixed.startsWith("data:", true)) return
            if (!fixed.isPotentialPlayerUrl()) return
            results[fixed] = ServerCandidate(fixed, label, referer)
        }

        for (iframe in select("iframe[src], embed[src]")) add(iframe.attr("src"), iframe.attr("title").ifBlank { iframe.attr("name") })
        for (source in select("video[src], source[src]")) add(source.attr("src"), source.attr("label").ifBlank { source.attr("res") })
        for (element in select("[data-src], [data-url], [data-link], [data-iframe], [data-embed], [data-player], [data-video], [data-file]")) {
            val label = element.text().ifBlank { element.attr("title") }.ifBlank { element.attr("aria-label") }
            val attrs = listOf("data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file")
            for (attr in attrs) add(element.attr(attr), label)
        }
        for (option in select("option[value]")) {
            add(option.attr("value"), option.text())
            decodePossibleBase64(option.attr("value"))?.extractCandidatesFromText(referer)?.forEach { add(it.url, option.text()) }
        }
        html().extractCandidatesFromText(referer).forEach { add(it.url, it.label) }
        return results.values.toList()
    }

    private fun String.extractCandidatesFromText(referer: String): List<ServerCandidate> {
        val results = linkedMapOf<String, ServerCandidate>()
        fun add(raw: String?, label: String? = null) {
            val fixed = raw?.trim()?.trim('"', '\'', ' ', ',', ')', ']', '}')?.basicHtmlDecode()?.unescapeJs()?.toAbsoluteUrl() ?: return
            if (fixed.isBlank() || fixed == mainUrl || fixed == referer) return
            if (fixed.startsWith("javascript:", true) || fixed.startsWith("data:", true)) return
            if (!fixed.isPotentialPlayerUrl()) return
            results[fixed] = ServerCandidate(fixed, label, referer)
        }

        val variants = linkedSetOf(this, basicHtmlDecode(), unescapeJs(), basicHtmlDecode().unescapeJs())
        runCatching { getAndUnpack(this) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { unpacked ->
            variants.add(unpacked)
            variants.add(unpacked.basicHtmlDecode().unescapeJs())
        }

        for (source in variants) {
            val cleaned = source.basicHtmlDecode().unescapeJs()
            Regex("""(?:src|url|link|file|iframe|embed|player|video|contentUrl|embedUrl)\s*["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(cleaned).forEach { add(it.groupValues.getOrNull(1), it.value.qualityLabelFromUrl()) }
            Regex("""https?://[^"'<>&\s\\]+""", RegexOption.IGNORE_CASE)
                .findAll(cleaned).forEach { add(it.value, it.value.qualityLabelFromUrl()) }
            Regex("""(?:atob\(|base64_decode\(|["'])([A-Za-z0-9+/=_-]{32,})(?:["']|\))""")
                .findAll(cleaned).forEach { match ->
                    val decoded = decodePossibleBase64(match.groupValues[1]) ?: return@forEach
                    decoded.extractCandidatesFromText(referer).forEach { add(it.url, it.label) }
                }
        }
        return results.values.toList()
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        return selectFirst("a[rel=next], .pagination a[href*='page=${page + 1}'], a[href*='p=${page + 1}']") != null
    }


    private fun Document.bestTags(): List<String> {
        val selectors = listOf(
            ".single-info .infox .info-content .genxed a[href*='/genres/']",
            ".single-info .genxed a[href*='/genres/']",
            ".infox .info-content .genxed a[href*='/genres/']",
            ".info-content .genxed a[href*='/genres/']"
        )

        return selectors
            .asSequence()
            .map { selector -> select(selector) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length <= 40 && !it.isBlockedTagLabel() }
            .distinct()
            .take(20)
    }

    private fun Document.bestTitle(): String? {
        return selectFirst("h1, .title h1, .entry-title, .anime-title, .judul")?.text()?.trim()
            ?: selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content")?.trim()
            ?: selectFirst("title")?.text()?.substringBefore("|")?.substringBefore(" - ")?.trim()
    }

    private fun Document.bestPoster(): String? {
        return listOfNotNull(
            selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content"),
            selectFirst(".poster img, .cover img, .thumb img, .thumbnail img, article img, main img")?.bestImage()
        ).firstOrNull { it.isNotBlank() }?.toAbsoluteUrl()
    }

    private fun Document.bestPlot(): String? {
        return selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst(".sinopsis, .synopsis, .description, .desc, .entry-content p, article p")?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun Element.bestImage(): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        return image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: image?.attr("srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.takeIf { it.isNotBlank() }
    }

    private fun String.toAbsoluteUrl(): String? {
        val raw = trim().trim('"', '\'', ' ')
        if (raw.isBlank() || raw == "#") return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> mainUrl.trimEnd('/') + raw
            raw.startsWith("?") -> "$mainUrl/index.php$raw"
            raw.startsWith("index.php", true) -> mainUrl.trimEnd('/') + "/$raw"
            raw.startsWith("./") -> mainUrl.trimEnd('/') + "/" + raw.removePrefix("./")
            raw.contains(".") && !raw.contains(" ") -> "https://$raw"
            else -> null
        }
    }

    private fun normalizeMediaUrl(raw: String?): String? {
        return raw?.basicHtmlDecode()?.unescapeJs()?.cleanEscapedUrl()?.toAbsoluteUrl()
    }

    private fun String.isPotentialPlayerUrl(): Boolean {
        val lower = lowercase()
        return lower.isDirectMedia() ||
            lower.contains("/embed") || lower.contains("iframe") || lower.contains("player") ||
            lower.contains("stream") || lower.contains("vidhide") || lower.contains("filedon") ||
            lower.contains("mega") || lower.contains("dood") || lower.contains("filemoon") ||
            lower.contains("mp4upload") || lower.contains("voe") || lower.contains("mixdrop") ||
            lower.contains("streamtape") || lower.contains("pixeldrain") || lower.contains("gdrive") ||
            lower.contains("krakenfiles") || lower.contains("gofile") || lower.contains("acefile") || lower.contains("mediafire") ||
            lower.contains("blogger.com/video.g") || lower.contains("youtube.googleapis.com/embed") ||
            lower.contains("googlevideo.com/videoplayback") || lower.isInternalPlayerUrl()
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") ||
            lower.contains("googlevideo.com/videoplayback") || lower.contains("mime=video") || lower.contains("cloudflarestorage.com")
    }

    private fun String.isBloggerVideoUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("blogger.com/video.g") && lower.contains("token=")
    }

    private fun String.shouldInlineResolve(): Boolean {
        val lower = lowercase()
        return lower.contains("vidhide") || lower.contains("filedon") || lower.contains("dood") || lower.contains("filemoon") ||
            lower.contains("blogger.com/video.g") || lower.contains("youtube.googleapis.com/embed") ||
            lower.contains("stream") || lower.contains("player") || lower.isInternalPlayerUrl()
    }

    private fun String.normalizedMediaKey(): String {
        return substringBefore("&Expires=").substringBefore("?Expires=").substringBefore("&X-Amz-Signature=")
    }

    private fun String.normalizedPath(): String {
        return substringBefore("#")
            .substringAfter(mainUrl, "")
            .substringBefore("?")
            .trim('/')
            .lowercase()
    }

    private fun String.isNavigationUrl(): Boolean {
        if (!startsWith(mainUrl, true)) return false
        val path = normalizedPath()
        if (path.isBlank()) return true
        if (path in setOf(
                "anime", "ongoing", "completed", "movie", "schedule", "bookmark", "bookmarks",
                "login", "register", "discord", "privacy-policy", "dmca", "contact", "text-mode"
            )
        ) return true
        val firstSegment = path.substringBefore('/')
        return firstSegment in setOf("genres", "genre", "tag", "studio", "season", "cast", "director", "author")
    }

    private fun String.isContentItemUrl(): Boolean {
        if (!startsWith(mainUrl, true)) return false
        if (contains("login", true) || contains("register", true) || contains("discord", true)) return false
        if (isNavigationUrl()) return false
        return normalizedPath().length >= 3
    }

    private fun String.isEpisodeContentUrl(rawText: String? = null): Boolean {
        if (!isContentItemUrl()) return false
        val combined = "${normalizedPath()} ${rawText.orEmpty()}".lowercase()
        return combined.contains("episode") || Regex("""\beps?\s*[-:]?\s*\d{1,4}""").containsMatchIn(combined)
    }

    private fun String.isInternalPlayerUrl(): Boolean {
        if (!startsWith(mainUrl, true)) return false
        if (isNavigationUrl()) return false
        val lower = lowercase()
        return lower.contains("player") || lower.contains("embed") || lower.contains("iframe") ||
            lower.contains("stream") || lower.contains("ajax") || lower.contains("download") || lower.contains("wp-admin/admin-ajax.php")
    }

    private fun SearchResponse.matchesQuery(query: String): Boolean {
        val tokens = query.searchTokens()
        if (tokens.isEmpty()) return false
        val haystack = "${name.lowercase()} ${url.slugTitle().lowercase()}"
        return tokens.all { haystack.contains(it) }
    }

    private fun String.searchTokens(): List<String> {
        return lowercase()
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.length >= 2 }
    }

    private fun String.cleanTitle(): String {
        return htmlDecode()
            .replace(Regex("(?i)\\s*sub\\s*indo.*$"), "")
            .replace(Regex("(?i)\\s*nonton\\s*"), "")
            .replace(Regex("(?i)\\s*episode\\s*\\d+.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':')
    }

    private fun List<String?>.firstCleanTitle(): String? {
        for (item in this) {
            val clean = item?.cleanTitle()?.takeIf { it.length >= 2 } ?: continue
            return clean
        }
        return null
    }

    private fun String.isBlockedTagLabel(): Boolean {
        val normalized = lowercase().trim()
        return normalized in setOf(
            "genre", "genres", "all", "anime", "ongoing", "completed", "movie",
            "daftar anime", "update terbaru", "lihat semua"
        )
    }

    private fun String.isBlockedTitle(): Boolean {
        val normalized = lowercase().trim()
        return normalized in setOf(
            "home", "terbaru", "terpopuler", "jadwal rilis", "diskusi", "login", "register",
            "nonton", "previous", "next", "animeisme", "anime", "ongoing", "completed", "movie",
            "genre", "genres", "lihat semua", "text mode", "daftar anime", "update terbaru",
            "animeisme - anime subtitle indonesia", "animeisme anime subtitle indonesia"
        )
    }

    private fun String.slugTitle(): String = substringBefore("?").trimEnd('/').substringAfterLast('/').replace('-', ' ').cleanTitle().ifBlank { name }

    private fun String.parseYear(): Int? = Regex("""\b(?:19|20)\d{2}\b""").find(this)?.value?.toIntOrNull()

    private fun String.parseEpisodeNumber(): Int? {
        val match = Regex("""(?i)(?:episode|ep)\s*[-:]?\s*(\d{1,4})""").find(this)
            ?: Regex("""\bEp\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE).find(this)
            ?: Regex("""-(\d{1,4})(?:-|$)""").find(this)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.parseEpisodeName(): String? = parseEpisodeNumber()?.let { "Episode $it" }

    private fun String.htmlDecode(): String = Jsoup.parse(this).text()

    private fun String.basicHtmlDecode(): String {
        return replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#x22;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun String.unescapeJs(): String {
        var output = this
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }
        return output.replace("\\/", "/").replace("\\\"", "\"").replace("\\'", "'")
    }

    private fun decodePossibleBase64(value: String?): String? {
        val raw = value?.trim()?.trim('"', '\'', ' ') ?: return null
        if (raw.length < 16) return null
        return runCatching {
            val normalized = raw.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
        }.getOrNull()?.takeIf { it.contains("http", true) || it.contains("<iframe", true) }
    }

    private fun String.cleanEscapedUrl(): String {
        return replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\&", "&")
            .replace("\\=", "=")
            .replace("\\?", "?")
    }

    private fun String.extractJsonValue(key: String): String? {
        return Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]+)"""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.basicHtmlDecode()
            ?.unescapeJs()
    }

    private fun String.extractQueryValue(key: String): String? {
        return Regex("""[?&]${Regex.escape(key)}=([^&#]+)""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.extractGoogleVideoLinks(): List<String> {
        val links = linkedSetOf<String>()
        val cleaned = basicHtmlDecode().unescapeJs()
        Regex("""https?://[^"'<>\s]+googlevideo\.com/videoplayback[^"'<>\s]+""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .forEach { match ->
                match.value.cleanEscapedUrl().toAbsoluteUrl()?.takeIf { it.contains("googlevideo.com/videoplayback", true) }?.let { links.add(it) }
            }
        return links.toList()
    }

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")

    private fun String.parseQuality(): Int? = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String.parseGoogleVideoQuality(): Int? {
        val itag = extractQueryValue("itag")?.toIntOrNull() ?: return null
        return when (itag) {
            18 -> Qualities.P360.value
            22 -> Qualities.P720.value
            37 -> Qualities.P1080.value
            59, 78 -> Qualities.P480.value
            133, 160 -> Qualities.P144.value
            134 -> Qualities.P360.value
            135 -> Qualities.P480.value
            136 -> Qualities.P720.value
            137 -> Qualities.P1080.value
            else -> null
        }
    }

    private fun String.qualityLabelFromUrl(): String = parseQuality()?.let { "${it}p" }.orEmpty()
    private fun String.cleanLabel(): String = htmlDecode().replace(Regex("\\s+"), " ").trim()

    private fun playerHeaders(url: String): Map<String, String> {
        val lower = url.lowercase()
        return when {
            lower.contains("blogger.com") -> bloggerHeaders
            lower.contains("googlevideo.com/videoplayback") -> googleVideoHeaders
            lower.contains(mainUrl.removePrefix("https://")) -> siteHeaders + mapOf("Referer" to "$mainUrl/")
            else -> siteHeaders + mapOf("Referer" to "$mainUrl/")
        }
    }

    private data class ServerCandidate(
        val url: String,
        val label: String?,
        val referer: String?
    )
}
