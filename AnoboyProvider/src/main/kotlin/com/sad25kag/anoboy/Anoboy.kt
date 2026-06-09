package com.sad25kag.anoboy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

class Anoboy : MainAPI() {
    override var mainUrl = "https://anoboy.be"
    override var name = "AnoBoy"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "anime/?type=movie" to "Movie",
        "az-list/" to "Anime List",
        "genres/action/" to "Action",
        "genres/adventure/" to "Adventure",
        "genres/comedy/" to "Comedy",
        "genres/drama/" to "Drama",
        "genres/ecchi/" to "Ecchi",
        "genres/fantasy/" to "Fantasy",
        "genres/harem/" to "Harem",
        "genres/historical/" to "Historical",
        "genres/horror/" to "Horror",
        "genres/isekai/" to "Isekai",
        "genres/martial-arts/" to "Martial Arts",
        "genres/mecha/" to "Mecha",
        "genres/music/" to "Music",
        "genres/mystery/" to "Mystery",
        "genres/psychological/" to "Psychological",
        "genres/romance/" to "Romance",
        "genres/school/" to "School",
        "genres/sci-fi/" to "Sci-Fi",
        "genres/seinen/" to "Seinen",
        "genres/shoujo/" to "Shoujo",
        "genres/shounen/" to "Shounen",
        "genres/slice-of-life/" to "Slice of Life"
    )

    private data class CardData(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType,
        val episode: Int?
    )

    private fun buildPageUrl(data: String, page: Int): String {
        val raw = data.trim()
        if (raw.isBlank()) return if (page <= 1) mainUrl else "$mainUrl/page/$page/"

        val firstPage = normalizeAnoboyUrl(raw)
        if (page <= 1) return firstPage

        val base = firstPage.substringBefore("?").trimEnd('/')
        val query = firstPage.substringAfter("?", "")
        return if (query.isBlank() || query == firstPage) {
            "$base/page/$page/"
        } else {
            "$base/page/$page/?$query"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = buildPageUrl(request.data, page)
        val document = app.get(pageUrl, headers = defaultHeaders()).document

        val items = collectCards(document)
            .distinctBy { it.url }
            .map { it.toSearchResponse() }

        val hasNext = document.selectFirst(
            ".wp-pagenavi a.nextpostslink, a.next, a[rel=next], a[href*='/page/${page + 1}/']"
        ) != null

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

        suspend fun parseSearchPage(url: String): List<CardData> {
            val document = runCatching {
                app.get(url, headers = defaultHeaders()).document
            }.getOrNull() ?: return emptyList()

            return collectCards(document)
                .filter { card ->
                    queryWords.isEmpty() || queryWords.all { word -> card.title.lowercase().contains(word) }
                }
                .distinctBy { it.url }
        }

        val searchResults = parseSearchPage("$mainUrl/?s=$encodedQuery")
        if (searchResults.isNotEmpty()) return searchResults.map { it.toSearchResponse() }

        val slug = query
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        if (slug.isNotBlank()) {
            val directAnime = runCatching {
                val directUrl = "$mainUrl/anime/$slug/"
                val document = app.get(directUrl, headers = defaultHeaders()).document
                val title = document.selectFirst("h1.entry-title, h1, h2.entry-title, .pagetitle h1")
                    ?.text()
                    ?.trim()
                    .orEmpty()
                if (title.isNotBlank()) {
                    CardData(
                        title = cleanTitle(title),
                        url = directUrl,
                        poster = document.selectFirst(".sisi.entry-content img, .deskripsi img, div.bigcontent img, article img")
                            ?.imageAttr()
                            ?.let { fixUrlNull(it) },
                        type = TvType.Anime,
                        episode = null
                    )
                } else {
                    null
                }
            }.getOrNull()

            if (directAnime != null) return listOf(directAnime.toSearchResponse())
        }

        return parseSearchPage("$mainUrl/az-list/").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeAnoboyUrl(url)
        val document = app.get(fixedUrl, headers = defaultHeaders()).document

        val rawPageTitle = document.selectFirst("h1.entry-title, h1, h2.entry-title, .pagetitle h1")
            ?.text()
            ?.trim()
            .orEmpty()
            .ifBlank {
                document.title()
                    .substringBefore("–")
                    .substringBefore("- anoBoy")
                    .substringBefore("AnoBoy")
                    .trim()
            }

        val pageTitle = cleanTitle(rawPageTitle).ifBlank {
            throw ErrorLoadingException("Judul tidak ditemukan")
        }

        val poster = document.selectFirst(
            ".sisi.entry-content img, .deskripsi img, div.column-three-fourth > img, " +
                "div.column-content > img, div.bigcontent img, div.entry-content img, " +
                ".thumb img, .poster img, .info-content img, article img"
        )?.imageAttr()?.let { fixUrlNull(it) }

        val description = document.selectFirst(".contentdeks")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.select("div.entry-content p, .sisi.entry-content p")
                .joinToString("\n") { it.text() }
                .trim()

        val episodes = parseEpisodeList(document, fixedUrl)

        val tags = extractDetailTags(document)

        val recommendations = collectRecommendations(document)
            .distinctBy { it.url }
            .map { it.toSearchResponse() }

        val type = detectType(fixedUrl, rawPageTitle, pageTitle)
        val moviePlaybackData = when {
            type == TvType.AnimeMovie -> episodes.firstOrNull()?.data ?: findMoviePlaybackData(document, fixedUrl)
            episodes.isEmpty() -> findMoviePlaybackData(document, fixedUrl)
            else -> null
        }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(pageTitle, fixedUrl, type) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newMovieLoadResponse(pageTitle, fixedUrl, type, encodeEpisodeData(fixedUrl, moviePlaybackData ?: fixedUrl)) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (embeddedReferer, requestData) = decodeEpisodeData(data)
        val rootReferer = embeddedReferer ?: mainUrl
        val emittedKeys = linkedSetOf<String>()
        val discovered = linkedSetOf<String>()
        val candidateReferers = mutableMapOf<String, String>()
        val queued = ArrayDeque<Pair<String, String>>()
        val crawled = linkedSetOf<String>()
        var emitted = false

        fun callbackOnce(link: ExtractorLink) {
            val key = "${link.source.lowercase()}|${canonicalLink(link.url)}"
            if (emittedKeys.add(key)) {
                emitted = true
                callback(link)
            }
        }

        fun rememberCandidate(raw: String?, baseUrl: String, referer: String = baseUrl) {
            val url = resolvePlayerUrl(raw, baseUrl) ?: return
            if (isBadUrl(url)) return
            if (discovered.add(url)) {
                candidateReferers[url] = referer.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                    ?: rootReferer
                if (shouldCrawlPlayerPage(url)) queued.add(url to (candidateReferers[url] ?: rootReferer))
            }
        }

        suspend fun crawlDocument(pageUrl: String, pageReferer: String) {
            val page = runCatching {
                app.get(
                    pageUrl,
                    referer = pageReferer,
                    headers = defaultHeaders(pageReferer),
                    timeout = 20L
                ).document
            }.getOrNull() ?: return

            collectPlayerCandidates(page).forEach { rememberCandidate(it, pageUrl, pageUrl) }
        }

        if (requestData.startsWith("multi::")) {
            requestData.removePrefix("multi::")
                .split("||")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { rememberCandidate(it, rootReferer, rootReferer) }
        } else {
            crawlDocument(requestData, rootReferer)
            rememberCandidate(requestData, rootReferer, rootReferer)
        }

        var safety = 0
        while (queued.isNotEmpty() && safety++ < 120) {
            val (nextUrl, nextReferer) = queued.removeFirst()
            val crawlKey = canonicalLink(nextUrl)
            if (!crawled.add(crawlKey)) continue
            crawlDocument(nextUrl, nextReferer)
        }

        suspend fun processResolvedCandidate(url: String) {
            val candidateReferer = candidateReferers[url] ?: rootReferer
            when {
                isDirectMedia(url) -> emitDirect(url, candidateReferer, ::callbackOnce)
                url.contains("blogger.com/video.g", ignoreCase = true) ||
                    url.contains("blogger.googleusercontent.com", ignoreCase = true) -> {
                    emitBloggerVideo(url, candidateReferer, ::callbackOnce)
                }
                isPlayerCandidate(url) -> {
                    try {
                        loadExtractor(url, candidateReferer, subtitleCallback, ::callbackOnce)
                    } catch (_: Exception) {
                    }

                    if (!emitted && shouldCrawlPlayerPage(url)) {
                        crawlDocument(url, candidateReferer)
                    }
                }
            }
        }

        discovered.toList().forEach { processResolvedCandidate(it) }
        if (!emitted) {
            discovered.toList().forEach { processResolvedCandidate(it) }
        }

        return emitted
    }

    private fun collectPlayerCandidates(document: Document): List<String> {
        val candidates = linkedSetOf<String>()

        document.select(
            "#pembed iframe[src], .player-embed iframe[src], .video-content iframe[src], " +
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], iframe[data-lazy-src], " +
                "video[src], video[data-src], source[src], embed[src], object[data]"
        ).forEach { element ->
            candidates.add(element.iframeAttr().orEmpty())
            candidates.add(element.attr("src"))
            candidates.add(element.attr("data-src"))
            candidates.add(element.attr("data-litespeed-src"))
            candidates.add(element.attr("data-lazy-src"))
            candidates.add(element.attr("data"))
        }

        document.select(
            ".mobius select.mirror option[value], select.mirror option[value], " +
                "#selectServer option[value], select.server option[value], select[id*=server] option[value], " +
                "select[class*=server] option[value], select[id*=mirror] option[value], select[class*=mirror] option[value]"
        ).forEach { option ->
            val value = option.attr("value").trim()
            if (value.isBlank()) return@forEach

            addServerValueCandidates(value, candidates)
            decodeServerValue(value)?.let { decoded -> addServerValueCandidates(decoded, candidates) }

            if (isLikelyServerValue(value) && !isEmbeddedPlayerPayload(value)) {
                candidates.add(value)
            }
        }

        document.select(
            "#fplay a#allmiror[data-video], #fplay a[data-video], #fplay [data-video], " +
                "a#allmiror[data-video], a[data-video], [data-video], [data-src], [data-url], " +
                "[data-iframe], [data-embed], [data-player], [data-file]"
        ).forEach { element ->
            candidates.add(element.attr("data-video"))
            candidates.add(element.attr("data-src"))
            candidates.add(element.attr("data-url"))
            candidates.add(element.attr("data-iframe"))
            candidates.add(element.attr("data-embed"))
            candidates.add(element.attr("data-player"))
            candidates.add(element.attr("data-file"))
            candidates.add(element.attr("href"))
        }

        document.select(
            "a[href*='gofile.io'], a[href*='mp4upload.com'], a[href*='mir.cr'], " +
                "a[href*='ranoz.gg'], a[href*='playerwish.com'], a[href*='blogger.com/video.g'], " +
                "a[href*='filedon'], a[href*='yourupload.com'], a[href*='/uploads/'], " +
                "a[href*='.mp4'], a[href*='.m3u8']"
        ).forEach { element ->
            candidates.add(element.attr("href"))
        }

        val html = document.html()
        addServerValueCandidates(html, candidates)

        Regex("""https?:\\?/\\?/[^"'<>\s]+""")
            .findAll(html)
            .forEach { match -> candidates.add(match.value) }

        Regex("""(?i)(?:src|file|url)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(html)
            .forEach { match -> candidates.add(match.groupValues[1]) }

        Regex("""(?i)/uploads/(?:adsbatch|acbatch|yupbatch|stream/embed\.php)[^"'<>\s]+""")
            .findAll(html)
            .forEach { match -> candidates.add(match.value) }

        Regex("""(?i)https?:\\?/\\?/[^"'<>\s]+""")
            .findAll(html)
            .filter { match ->
                val lower = match.value.lowercase()
                lower.contains("blogger.com/video.g") ||
                    lower.contains("yourupload.com") ||
                    lower.contains("filedon") ||
                    lower.contains("/uploads/")
            }
            .forEach { match -> candidates.add(match.value) }

        return candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun addServerValueCandidates(raw: String?, candidates: MutableSet<String>) {
        expandServerPayload(raw).forEach { payload ->
            val parsed = Jsoup.parseBodyFragment(payload)
            parsed.select(
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], iframe[data-lazy-src], " +
                    "video[src], video[data-src], source[src], embed[src], object[data], a[href]"
            ).forEach { embedded ->
                candidates.add(embedded.iframeAttr().orEmpty())
                candidates.add(embedded.attr("src"))
                candidates.add(embedded.attr("data-src"))
                candidates.add(embedded.attr("data-litespeed-src"))
                candidates.add(embedded.attr("data-lazy-src"))
                candidates.add(embedded.attr("href"))
                candidates.add(embedded.attr("data"))
            }

            Regex(
                """(?i)<iframe[^>]+(?:src|data-src|data-litespeed-src|data-lazy-src)\s*=\s*["']([^"']+)["']"""
            ).findAll(payload).forEach { match ->
                candidates.add(match.groupValues[1])
            }

            Regex(
                """(?i)(?:src|file|url|data-video|data-src|data-url|data-iframe|data-embed|data-player|data-file)\s*[:=]\s*["']([^"']+)["']"""
            ).findAll(payload).forEach { match ->
                candidates.add(match.groupValues[1])
            }

            Regex("""https?:\\?/\\?/[^"'<>\s&]+""")
                .findAll(payload)
                .forEach { match -> candidates.add(match.value) }

            Regex("""(?<!:)//[^"'<>\s&]+""")
                .findAll(payload)
                .forEach { match -> candidates.add(match.value) }

            Regex("""(?i)/uploads/(?:adsbatch|acbatch|yupbatch|stream/embed\.php)[^"'<>\s&]+""")
                .findAll(payload)
                .forEach { match -> candidates.add(match.value) }
        }
    }

    private fun expandServerPayload(raw: String?): List<String> {
        val value = raw.orEmpty().trim()
        if (value.isBlank()) return emptyList()

        val decoded = decodePlayerPayload(value)
        val urlDecoded = runCatching { java.net.URLDecoder.decode(decoded, "UTF-8") }.getOrNull()

        return listOfNotNull(value, decoded, urlDecoded)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun decodeServerValue(value: String): String? {
        val decoded = runCatching { base64Decode(value) }.getOrNull()
            ?: runCatching { String(android.util.Base64.decode(value, android.util.Base64.DEFAULT)) }.getOrNull()
            ?: return null

        return decodePlayerPayload(decoded)
    }

    private fun isEmbeddedPlayerPayload(value: String): Boolean {
        val lower = decodePlayerPayload(value).lowercase()
        return lower.contains("<iframe") ||
            lower.contains("&lt;iframe") ||
            lower.contains("src=") ||
            lower.contains("data-src") ||
            lower.contains("data-video") ||
            lower.contains("data-iframe")
    }

    private fun decodePlayerPayload(input: String): String {
        var output = input
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }

        output = Parser.unescapeEntities(output, false)
        return output
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#038;", "&")
    }

    private fun extractDetailTags(document: Document): List<String> {
        val tags = linkedSetOf<String>()

        fun addTag(text: String?) {
            val clean = text.orEmpty().trim()
            if (clean.isBlank()) return
            if (clean.length >= 40) return
            if (isNavigationTitle(clean)) return
            val lower = clean.lowercase()
            if (lower.endsWith(" all") || lower == "all") return
            tags.add(clean)
        }

        document.select(
            ".info-content a[href*='/genres/'], .genre-info a[href*='/genres/'], " +
                "div.bigcontent a[href*='/genres/'], article a[href*='/genres/'], " +
                "div.unduhan table tr:has(th:matchesOwn((?i)Genre)) td a, " +
                "tr:has(th:matchesOwn((?i)Genre)) td a"
        ).forEach { addTag(it.text()) }

        if (tags.isEmpty()) {
            document.select("a[href*='/genres/']")
                .filterNot { element ->
                    element.parents().any { parent ->
                        val cls = parent.className().lowercase()
                        cls.contains("sidebar") || cls.contains("side_home") ||
                            cls.contains("filter") || cls.contains("advanced") ||
                            cls.contains("bixbox") && parent.text().contains("Genre All", true)
                    }
                }
                .take(12)
                .forEach { addTag(it.text()) }
        }

        return tags.toList()
    }

    private fun collectCards(document: Document): List<CardData> {
        val selectors = listOf(
            "article.bs",
            "div.bs",
            "div.listupd article",
            "div.listupd div.bs",
            "a[href]:has(div.amv)",
            "a[href]:has(div#amv)",
            "a[href*='/anime/']",
            "a[href*='episode-']",
            "a[href*='subtitle-indonesia']",
            ".venz ul li",
            ".latest a[href]",
            ".listupd a[href]",
            ".topten .serieslist li",
            ".az-list a[href]",
            ".result li"
        ).joinToString(", ")

        return document.select(selectors)
            .mapNotNull { it.toCardData() }
            .filterNot { isNavigationTitle(it.title) }
            .distinctBy { it.url }
    }

    private fun collectRecommendations(document: Document): List<CardData> {
        return document.select(
            "a[href]:has(div.amv), a[href]:has(div#amv), a[href*='/anime/'], " +
                "div.listupd article.bs, article.bs, div.bs, .topten .serieslist li"
        ).mapNotNull { it.toCardData() }
            .filterNot { isNavigationTitle(it.title) }
    }

    private fun Element.toCardData(): CardData? {
        val href = when {
            tagName().equals("a", true) -> attr("href")
            else -> selectFirst("a[href]")?.attr("href").orEmpty()
        }.trim()

        val fixedHref = normalizeAnoboyUrl(href)
        if (!isContentUrl(fixedHref)) return null

        val rawTitle = attr("title").trim().ifBlank {
            selectFirst("h3.ibox1, h3.ibox, h2, h3, .tt, .title, .judul, .entry-title")?.text()?.trim().orEmpty()
        }.ifBlank {
            selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }.ifBlank {
            text().trim()
        }

        val title = cleanTitle(rawTitle)

        if (title.length < 2 || isNavigationTitle(title)) return null

        val episode = parseEpisodeNumber(rawTitle) ?: parseEpisodeNumber(title) ?: parseEpisodeNumber(fixedHref)
        val type = detectType(fixedHref, rawTitle, title)

        val poster = selectFirst("img")?.imageAttr()?.let { fixUrlNull(it) }

        return CardData(
            title = title,
            url = fixedHref,
            poster = poster,
            type = type,
            episode = episode
        )
    }

    private fun CardData.toSearchResponse(): SearchResponse {
        return newAnimeSearchResponse(title, url, type) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private fun parseEpisodeList(document: Document, referer: String): List<Episode> {
        val anchors = document.select(
            "div.singlelink ul.lcp_catlist li a, div.eplister ul li a, " +
                "div.bixbox.bxcl ul li a, .episodelist ul li a, .episode-list a[href], " +
                "ul li a[href*='episode'], a[href*='episode-'], a[href*='subtitle-indonesia']"
        ).filter {
            val href = normalizeAnoboyUrl(it.attr("href"))
            isEpisodeLikeUrl(href, it.text()) && !href.equals(referer, true)
        }

        return anchors
            .mapNotNull { anchor ->
                val href = normalizeAnoboyUrl(anchor.attr("href"))
                val rawTitle = anchor.text().trim().ifBlank {
                    href.trimEnd('/').substringAfterLast('/').replace("-", " ")
                }
                val title = cleanTitle(rawTitle)
                val episode = parseEpisodeNumber(rawTitle) ?: parseEpisodeNumber(title) ?: parseEpisodeNumber(href)
                if (episode == null && title.length < 2) return@mapNotNull null

                newEpisode(href) {
                    name = title.ifBlank { "Episode ${episode ?: 1}" }
                    this.episode = episode
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun findMoviePlaybackData(document: Document, referer: String): String? {
        val candidates = linkedSetOf<String>()

        fun addCandidate(raw: String?) {
            if (!raw.isNullOrBlank()) candidates.add(raw)
        }

        document.select("a[href]").forEach { anchor ->
            addCandidate(anchor.attr("href"))
            addCandidate(anchor.attr("abs:href"))
        }

        val html = document.html()

        Regex(
            """href\s*=\s*["']([^"']*(?:episode|subtitle-indonesia)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { match ->
            addCandidate(match.groupValues.getOrNull(1))
        }

        Regex(
            """https?://[^"'<>\s]+(?:episode|subtitle-indonesia)[^"'<>\s]*""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { match ->
            addCandidate(match.value)
        }

        return candidates.asSequence()
            .map { cleanCandidate(it).substringBefore("#") }
            .map { normalizeAnoboyUrl(it) }
            .filter { it.isNotBlank() && !it.equals(referer, true) }
            .filter { isEpisodeLikeUrl(it) }
            .distinct()
            .firstOrNull()
    }

    private suspend fun emitBloggerVideo(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videos = extractBloggerDirectVideos(url, referer)
        if (videos.isEmpty()) return false

        videos.forEach { videoUrl ->
            val directReferer = if (videoUrl.contains("googlevideo.com/", true)) {
                "https://youtube.googleapis.com/"
            } else {
                referer
            }
            callback(
                newExtractorLink(
                    source = "Blogger",
                    name = "Blogger",
                    url = videoUrl,
                    type = if (isM3u8Media(videoUrl)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = directReferer
                    this.quality = qualityFromBloggerUrl(videoUrl)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to directReferer,
                        "Accept" to "*/*"
                    )
                }
            )
        }

        return true
    }

    private suspend fun extractBloggerDirectVideos(url: String, referer: String): List<String> {
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url
        if (fixedUrl.contains("blogger.googleusercontent.com", true)) return listOf(fixedUrl)

        val token = Regex("""[?&]token=([^&#]+)""")
            .find(fixedUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val bloggerOrigin = "https://www.blogger.com"
        val page = runCatching {
            app.get(
                fixedUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
        }.getOrNull() ?: return emptyList()

        val html = page.text
        val cookies = page.cookies
        val fSid = Regex("""FdrFJe":"(-?\d+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return extractGoogleVideoUrls(html)
        val bl = Regex("""cfb2h":"([^"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return extractGoogleVideoUrls(html)
        val hl = Regex("""lang="([^"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.ifBlank { null }
            ?: "id"

        val rpcId = "WcwnYd"
        val reqId = (System.currentTimeMillis() % 90000L + 10000L).toString()
        val payload = """[[["$rpcId","[\"$token\",\"\",0]",null,"generic"]]]"""
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute" +
            "?rpcids=$rpcId&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=$hl&_reqid=$reqId&rt=c"

        val response = runCatching {
            app.post(
                apiUrl,
                data = mapOf("f.req" to payload),
                referer = fixedUrl,
                cookies = cookies,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to bloggerOrigin,
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "X-Same-Domain" to "1",
                    "Referer" to fixedUrl
                )
            ).text
        }.getOrNull() ?: return emptyList()

        return extractGoogleVideoUrls(response)
            .ifEmpty { extractGoogleVideoUrls(html) }
    }

    private fun extractGoogleVideoUrls(raw: String): List<String> {
        val decoded = decodeBloggerEscapes(raw)
        return Regex("""https://[^\s"'\\]+""")
            .findAll(decoded)
            .map { decodeBloggerEscapes(it.value) }
            .filter {
                it.contains("googlevideo.com/videoplayback", ignoreCase = true) ||
                    it.contains("blogger.googleusercontent.com", ignoreCase = true)
            }
            .distinct()
            .toList()
    }

    private fun decodeBloggerEscapes(input: String): String {
        var output = input
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }

        return output
            .replace("\\/", "/")
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\=", "=")
            .replace("\\&", "&")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
    }

    private fun qualityFromBloggerUrl(url: String): Int {
        val itag = Regex("""[?&]itag=(\d+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return when (itag) {
            37, 96, 137, 248, 299 -> Qualities.P1080.value
            22, 59, 136, 247, 298 -> Qualities.P720.value
            135 -> Qualities.P480.value
            18, 134, 244 -> Qualities.P360.value
            36 -> Qualities.P240.value
            17 -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun emitDirect(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isDirectMedia(link) || isBadUrl(link)) return false

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isM3u8Media(link)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(link).takeIf { it != Qualities.Unknown.value }
                    ?: when {
                        link.contains("1080", true) -> Qualities.P1080.value
                        link.contains("720", true) -> Qualities.P720.value
                        link.contains("480", true) -> Qualities.P480.value
                        link.contains("360", true) -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "*/*"
                )
            }
        )

        return true
    }

    private fun normalizeAnoboyUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return mainUrl
        val fixed = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$mainUrl$trimmed"
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            else -> "$mainUrl/${trimmed.trimStart('/')}"
        }

        return fixed
            .replace("https://www1.anoboy.boo", mainUrl, ignoreCase = true)
            .replace("http://www1.anoboy.boo", mainUrl, ignoreCase = true)
            .replace("https://anoboy.be", mainUrl, ignoreCase = true)
            .replace("http://anoboy.be", mainUrl, ignoreCase = true)
            .replace("https://www.anoboy.be", mainUrl, ignoreCase = true)
            .replace("http://www.anoboy.be", mainUrl, ignoreCase = true)
            .replace("https://www1.anoboy.be", mainUrl, ignoreCase = true)
            .replace("http://www1.anoboy.be", mainUrl, ignoreCase = true)
            .replace("https://anoboy.watch", mainUrl, ignoreCase = true)
            .replace("http://anoboy.watch", mainUrl, ignoreCase = true)
    }

    private fun resolvePlayerUrl(raw: String?, base: String): String? {
        val clean = cleanCandidate(raw)
        if (!isValidCandidate(clean)) return null

        return runCatching {
            when {
                clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("/") -> URI(base).resolve(clean).toString()
                else -> URI(base).resolve(clean).toString()
            }
        }.getOrNull()
    }

    private fun cleanCandidate(raw: String?): String {
        return raw.orEmpty()
            .trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#038;", "&")
            .replace(" ", "%20")
    }

    private fun isValidCandidate(clean: String): Boolean {
        return clean.isNotBlank() &&
            clean != "#" &&
            !clean.equals("none", true) &&
            !clean.equals("null", true) &&
            !clean.startsWith("javascript", true) &&
            !clean.startsWith("about:", true) &&
            !clean.startsWith("data:", true) &&
            !clean.startsWith("blob:", true) &&
            !clean.startsWith("mailto:", true)
    }

    private fun isContentUrl(url: String): Boolean {
        val lower = normalizeAnoboyUrl(url).lowercase()
        val path = runCatching { URI(lower).path.orEmpty() }.getOrDefault(lower)
        val cleanPath = path.trimEnd('/')
        val slug = cleanPath.substringAfterLast('/')

        if (!lower.startsWith(mainUrl.lowercase())) return false
        if (
            path.contains("/category/") ||
            path.contains("/genre/") ||
            path.contains("/genres/") ||
            path.contains("/tag/") ||
            path.contains("/season/") ||
            path.contains("/studio/") ||
            path.contains("/anime-list") ||
            path.contains("/donghua-list") ||
            path.contains("/az-list") ||
            path.contains("/page/") ||
            lower.contains("?order=") ||
            lower.contains("?status=") ||
            lower.contains("?type=")
        ) return false

        val isAnimeDetail = Regex("""^/anime/[a-z0-9][a-z0-9-]+/?$""").matches(path)
        return isAnimeDetail ||
            slug.contains("episode-") ||
            slug.contains("-subtitle-indonesia") ||
            Regex("/20\\d{2}/\\d{2}/[a-z0-9-]+/?$").containsMatchIn(path)
    }

    private fun isEpisodeLikeUrl(url: String, title: String? = null): Boolean {
        if (!isContentUrl(url)) return false
        val lower = normalizeAnoboyUrl(url).lowercase()
        val path = runCatching { URI(lower).path.orEmpty() }.getOrDefault(lower)
        val slug = path.trimEnd('/').substringAfterLast('/')
        val lowerTitle = title.orEmpty().lowercase()

        return slug.contains("episode-") ||
            slug.contains("-subtitle-indonesia") ||
            lowerTitle.contains("episode") ||
            Regex("""\beps?\s*\d+\b""").containsMatchIn(lowerTitle)
    }

    private fun shouldCrawlPlayerPage(url: String): Boolean {
        val lower = url.lowercase()
        if (isBadUrl(url)) return false
        if (isDirectMedia(url)) return false
        if (lower.contains("blogger.com/video.g") || lower.contains("blogger.googleusercontent.com")) return false

        return lower.contains("/uploads/adsbatch") ||
            lower.contains("/uploads/acbatch") ||
            lower.contains("/uploads/yupbatch") ||
            lower.contains("/uploads/stream/embed.php") ||
            lower.contains("filedon") ||
            lower.contains("yourupload.com/embed/") ||
            lower.contains("yourupload.com/watch/") ||
            (lower.startsWith(mainUrl.lowercase()) && (
                lower.contains("embed") ||
                    lower.contains("player") ||
                    lower.contains("stream") ||
                    lower.contains("video")
                ))
    }

    private fun isPlayerCandidate(url: String): Boolean {
        val lower = url.lowercase()

        if (isBadUrl(url)) return false
        if (isDirectMedia(url)) return true

        return lower.contains("blogger.com/video.g") ||
            lower.contains("playerwish.com") ||
            lower.contains("gofile.io") ||
            lower.contains("mp4upload.com") ||
            lower.contains("mir.cr") ||
            lower.contains("ranoz.gg") ||
            lower.contains("filedon") ||
            lower.contains("yourupload.com") ||
            lower.contains("/uploads/adsbatch") ||
            lower.contains("/uploads/acbatch") ||
            lower.contains("/uploads/yupbatch") ||
            lower.contains("/uploads/stream/embed.php")
    }

    private fun isBadUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") ||
            lower.contains("twitter.com") ||
            lower.contains("x.com/") ||
            lower.contains("telegram") ||
            lower.contains("whatsapp") ||
            lower.contains("/az-list") ||
            lower.contains("mailto:") ||
            lower.contains("/genres/") ||
            lower.contains("/genre/") ||
            lower.contains("/category/") ||
            lower.contains("/tag/") ||
            lower.contains("/season/") ||
            lower.contains("/studio/") ||
            lower.contains("/anime/?") ||
            lower.endsWith("/anime/") ||
            lower.contains("?order=") ||
            lower.contains("?status=") ||
            lower.contains("?type=") ||
            lower.contains("adsbygoogle") ||
            lower.contains("googlesyndication") ||
            lower.contains("doubleclick") ||
            lower.contains("analytics") ||
            lower.contains("histats") ||
            lower.contains("safebrowsing") ||
            lower.contains("beacons.gcp.gvt2.com") ||
            lower.contains("dns.google") ||
            lower.contains("cloudflareinsights") ||
            lower.contains("google-analytics") ||
            lower.contains("googletagmanager") ||
            lower.contains("wp-json") ||
            lower.contains("/wp-content/themes/") ||
            lower.endsWith(".css") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".svg")
    }

    private fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase()
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault(url.substringBefore("?").lowercase())

        return path.endsWith(".m3u8") ||
            path.endsWith(".mp4") ||
            path.endsWith(".webm") ||
            path.endsWith(".mkv") ||
            path.endsWith(".mov") ||
            path.endsWith(".ts") ||
            lower.contains("googlevideo.com/videoplayback") ||
            lower.contains("redirector.googlevideo.com/videoplayback") ||
            lower.contains("blogger.googleusercontent.com") ||
            (lower.contains("gofile.io") && lower.contains("/download/"))
    }

    private fun isM3u8Media(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault(url.substringBefore("?").lowercase())
        return path.endsWith(".m3u8") || url.lowercase().contains(".m3u8?")
    }

    private fun canonicalLink(link: String): String {
        return runCatching {
            val uri = URI(link)
            val host = uri.host.orEmpty().removePrefix("www.").lowercase()
            val path = uri.path.orEmpty().trimEnd('/').lowercase()
            "$host$path"
        }.getOrDefault(link.substringBefore("?").trimEnd('/').lowercase())
    }

    private fun detectType(url: String, vararg titleHints: String): TvType {
        val lowerUrl = url.lowercase()
        val lowerTitle = titleHints.joinToString(" ").lowercase()

        return when {
            lowerUrl.contains("/anime-movie/") ||
                Regex("""(?i)\bmovie\b""").containsMatchIn(lowerTitle) -> TvType.AnimeMovie

            Regex("""(?i)\b(?:ova|special)\b""").containsMatchIn(lowerTitle) -> TvType.OVA

            else -> TvType.Anime
        }
    }

    private fun isLikelyServerValue(value: String): Boolean {
        val clean = cleanCandidate(value)
        if (!isValidCandidate(clean)) return false
        if (isPlayerCandidate(clean) || isDirectMedia(clean)) return true

        val lower = clean.lowercase()
        if (isEmbeddedPlayerPayload(clean)) return true

        if (clean.startsWith("//") || clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
            return lower.contains("embed") ||
                lower.contains("player") ||
                lower.contains("stream") ||
                lower.contains("video")
        }

        if (clean.startsWith("/") && (
                lower.contains("embed") ||
                    lower.contains("player") ||
                    lower.contains("stream") ||
                    lower.contains("video")
                )
        ) {
            return true
        }

        if (!Regex("""^[A-Za-z0-9+/=]{24,}$""").matches(value)) return false

        val decoded = runCatching { base64Decode(value) }.getOrNull()
            ?: runCatching { String(android.util.Base64.decode(value, android.util.Base64.DEFAULT)) }.getOrNull()
            ?: return false

        return decoded.contains("iframe", true) ||
            decoded.contains("video", true) ||
            decoded.contains("source", true) ||
            decoded.contains("embed", true) ||
            decoded.contains("blogger.com/video.g", true) ||
            decoded.contains(".m3u8", true) ||
            decoded.contains(".mp4", true)
    }

    private fun parseEpisodeNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""(?i)\b(?:episode|eps|ep)\s*[-:]?\s*(\d+)\b""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?i)(?:episode|eps|ep)-(\d+)""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun cleanTitle(raw: String): String {
        var title = raw
            .replace(Regex("""(?i)\[(?:streaming|download)\]"""), "")
            .replace(Regex("""(?i)\b(?:streaming|download|subtitle indonesia|sub indo|nonton)\b"""), " ")
            .replace(Regex("""(?i)\s+episode\s*\d+\s*$"""), " ")

        repeat(3) {
            title = title
                .replace(Regex("""(?i)^\s*(?:completed|ongoing|upcoming|hiatus)\s+"""), " ")
                .replace(Regex("""(?i)^\s*(?:tv|ova|ona|movie|special|bd|live action)\s+"""), " ")
                .replace(Regex("""(?i)^\s*ep(?:isode)?\s*[-:]?\s*\d+\s+"""), " ")
                .replace(Regex("""(?i)^\s*sub\s+"""), " ")
        }

        title = title
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '|')

        return collapseDuplicatedTitle(title)
    }

    private fun collapseDuplicatedTitle(raw: String): String {
        val words = raw.split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (words.size < 4) return raw.trim()

        for (size in words.size / 2 downTo 2) {
            val first = words.take(size).joinToString(" ")
            val second = words.drop(size).take(size).joinToString(" ")

            if (first.equals(second, ignoreCase = true)) {
                val tail = words.drop(size * 2)
                return (listOf(first) + tail).joinToString(" ").trim()
            }
        }

        return raw.trim()
    }

    private fun isNavigationTitle(title: String): Boolean {
        val lower = title.lowercase().trim()
        return lower in setOf(
            "home",
            "az list",
            "anime list",
            "text mode",
            "switch mode",
            "genre all",
            "season all",
            "studio all",
            "status all",
            "type all",
            "order by all",
            "search",
            "download",
            "expand",
            "turn off light",
            "prev",
            "next",
            "all episodes"
        )
    }

    private fun encodeEpisodeData(referer: String?, payload: String): String {
        if (referer.isNullOrBlank()) return payload
        return "anoboyref::$referer:::$payload"
    }

    private fun decodeEpisodeData(data: String): Pair<String?, String> {
        val prefix = "anoboyref::"
        val trimmed = data.trim()
        val fixed = normalizeAnoboyUrl(trimmed)
        val legacyData = when {
            trimmed.startsWith(prefix) -> trimmed
            fixed.startsWith("$mainUrl/$prefix", ignoreCase = true) -> fixed.removePrefix("$mainUrl/")
            else -> null
        }

        if (legacyData == null) return null to fixed

        val parts = legacyData.removePrefix(prefix).split(":::", limit = 2)
        return if (parts.size == 2) {
            normalizeAnoboyUrl(parts[0]) to normalizeAnoboyUrl(parts[1])
        } else {
            null to fixed
        }
    }

    private fun defaultHeaders(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
    }

    private fun Element.imageAttr(): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        return image?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("abs:data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("abs:srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
            ?: image?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
    }

    private fun Element?.iframeAttr(): String? {
        return this?.attr("data-litespeed-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("src")?.takeIf { it.isNotBlank() }
    }
}
