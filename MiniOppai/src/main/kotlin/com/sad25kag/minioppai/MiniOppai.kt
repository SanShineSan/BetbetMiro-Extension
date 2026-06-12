package com.sad25kag.minioppai

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class MiniOppai : MainAPI() {
    override var mainUrl = "https://minioppai.org"
    override var name = "MiniOppai"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "" to "Rilisan Terbaru",
        "/populars/" to "Popular",
        "/schedule/" to "Schedule",
        "/uncensored/" to "Uncensored",
        "/tag/festa/" to "Festa",
        "/genres/big-oppai/" to "Big Oppai",
        "/genre/maid/" to "Maid",
        "/genres/vanilla/" to "Vanilla",
        "/genres/milf/" to "MILF",
        "/genres/ntr/" to "NTR",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page), headers = browserHeaders).document
        val results = when (request.data) {
            "/populars/" -> parseRankingCards(document).ifEmpty { parseMiniOppaiCards(document) }
            else -> parseMiniOppaiCards(document)
        }.distinctBy { normalizeKey(it.url) }

        val hasNext = document.selectFirst(
            "a.next[href], a[rel=next], .pagination a[href]:contains(Selanjutnya), " +
                ".nav-links a[href]:contains(Next), a[href*='/page/']:contains(Selanjutnya)"
        ) != null
        return newHomePageResponse(request.name, results, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val routes = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/anime-list/?s=$encoded",
        )

        return routes.flatMap { url ->
            runCatching {
                val document = app.get(url, headers = browserHeaders).document
                parseMiniOppaiCards(document) + parseRankingCards(document)
            }.getOrDefault(emptyList())
        }.distinctBy { normalizeKey(it.url) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders).document
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1[itemprop=name], h1, .entry-title, .infox h1, .title-section h1")?.text()
                ?: document.selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content")
                ?: document.title()
        ) ?: return null

        val poster = pickPoster(document)
        val plot = document.selectFirst(
            ".entry-content p, .synopsis, .sinopsis, .desc, .description, .infox .desc, .info-content p, meta[name=description], meta[property=og:description]"
        )?.let {
            if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
        }?.cleanText()

        val tags = document.select("a[href*='/genres/'], a[href*='/genre/'], a[rel=tag], .genres a, .genre a, .seriestugenre a")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = parseEpisodes(document).distinctBy { normalizeKey(it.data) }
        val recommendations = parseMiniOppaiCards(document)
            .filterNot { normalizeKey(it.url) == normalizeKey(url) }
            .take(16)

        return if (episodes.isNotEmpty() && !looksEpisodePage(url, title)) {
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                this.showStatus = detectStatus(document)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = plot
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
        val document = app.get(data, headers = requestHeaders(data), referer = mainUrl).document
        val emitted = linkedSetOf<String>()
        val visited = linkedSetOf<String>()

        fun mark(url: String): Boolean = emitted.add(url.substringBefore("#"))

        val countedCallback: (ExtractorLink) -> Unit = { link ->
            emitted.add(link.url.substringBefore("#"))
            callback.invoke(link)
        }

        suspend fun emitDirect(rawUrl: String?, label: String? = null, referer: String = data): Boolean {
            val videoUrl = rawUrl?.decodeUrlLike()?.toAbsoluteUrl(referer)?.takeIf { it.isDirectMedia() } ?: return false
            if (!mark(videoUrl)) return true
            val sourceName = listOfNotNull(name, label?.cleanText()?.takeIf { it.isNotBlank() }).joinToString(" - ")
            val headers = mediaHeaders(referer)
            if (videoUrl.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(
                    sourceName,
                    videoUrl,
                    referer = referer,
                    headers = headers,
                ).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(sourceName, sourceName, videoUrl, INFER_TYPE) {
                        this.referer = referer
                        this.quality = getQualityFromName(label ?: videoUrl)
                        this.headers = headers
                    },
                )
            }
            return true
        }

        val candidates = linkedSetOf<String>()
        candidates.addAll(collectStaticPlayers(document, data))
        candidates.addAll(collectAjaxPlayers(document, data))
        candidates.addAll(collectScriptPlayers(document.html(), data))
        candidates.addAll(collectJsonLdPlayers(document, data))

        for (candidate in candidates.take(60)) {
            val fixed = candidate.decodeUrlLike().toAbsoluteUrl(data) ?: continue
            if (!fixed.isPlayableCandidate()) continue
            if (!visited.add(fixed.substringBefore("#"))) continue

            if (fixed.isDirectMedia()) {
                emitDirect(fixed, hostLabel(fixed), data)
                continue
            }

            val before = emitted.size
            runCatching { loadExtractor(fixed, data, subtitleCallback, countedCallback) }
            if (emitted.size > before) continue

            val playerHtml = runCatching {
                app.get(fixed, headers = requestHeaders(data), referer = data).text
            }.getOrNull() ?: continue

            val unpacked = runCatching { getAndUnpack(playerHtml) }.getOrNull().orEmpty()
            val nested = linkedSetOf<String>()
            nested.addAll(collectScriptPlayers(playerHtml + "\n" + unpacked, fixed))
            nested.addAll(collectHtmlPlayers(playerHtml, fixed))

            for (nestedUrl in nested.take(24)) {
                val nestedFixed = nestedUrl.decodeUrlLike().toAbsoluteUrl(fixed) ?: continue
                if (!nestedFixed.isPlayableCandidate()) continue
                if (!visited.add(nestedFixed.substringBefore("#"))) continue
                if (emitDirect(nestedFixed, hostLabel(fixed), fixed)) continue
                val nestedBefore = emitted.size
                runCatching { loadExtractor(nestedFixed, fixed, subtitleCallback, countedCallback) }
                if (emitted.size > nestedBefore) continue
            }
        }

        return emitted.isNotEmpty()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val base = when {
            path.isBlank() -> mainUrl
            path.startsWith("http", true) -> path.trimEnd('/')
            else -> mainUrl + path
        }.trimEnd('/')
        return if (page <= 1) "$base/" else "$base/page/$page/"
    }

    private fun parseMiniOppaiCards(document: Document): List<SearchResponse> {
        val roots = listOf(
            document.select(".listupd article, .listupd .bs, .listupd .bsx, .listupd .utao, .listupd .excstf"),
            document.select(".postbody article, .postbody .bsx, .postbody .bs, main article"),
            document.select("article:has(a[href*='episode']), article:has(a[href*='/anime/'])"),
            document.select(".bixbox .bsx, .bixbox .bs, .serieslist li, .releases article, .items article, .items .item"),
            document.select(".mrgn article, .mrgn .bsx, .mrgn .bs, .result article, .result .bsx"),
        )
        return roots.asSequence()
            .flatMap { it.asSequence() }
            .mapNotNull { it.toMiniOppaiCard() }
            .filterNot { it.name.equals("Tonton", true) || it.name.equals("Lihat Semua", true) }
            .distinctBy { normalizeKey(it.url) }
            .toList()
    }

    private fun parseRankingCards(document: Document): List<SearchResponse> {
        return document.select(".popular, .ranking, .topten, .sidebar, aside, .wpop-series, .series-gen")
            .flatMap { section -> section.select("a[href*='/anime/'], a[href*='episode']") }
            .mapNotNull { anchor ->
                val href = anchor.attr("href").toAbsoluteUrl() ?: return@mapNotNull null
                val title = anchor.attr("title").cleanText().takeIf { it.length > 2 }
                    ?: anchor.selectFirst("h2, h3, h4, .tt, .title, .series-title")?.text()?.cleanText()?.takeIf { it.length > 2 }
                    ?: anchor.text().cleanText().takeIf { it.length > 2 }
                    ?: return@mapNotNull null
                newMovieSearchResponse(cleanTitle(title) ?: title, href, TvType.NSFW) {
                    this.posterUrl = anchor.selectFirst("img")?.imageUrl()
                    this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                }
            }
            .filterNot { it.name.equals("Tonton", true) || it.name.equals("Lihat Semua", true) }
            .distinctBy { normalizeKey(it.url) }
    }

    private fun Element.toMiniOppaiCard(): SearchResponse? {
        val anchor = selectFirst(
            "h2 a[href], h3 a[href], h4 a[href], .tt a[href], .title a[href], .bsx a[href], " +
                ".bs a[href], .series-title a[href], a[href*='episode'], a[href*='/anime/']"
        ) ?: selectFirst("a[href*='episode'], a[href*='/anime/']") ?: return null
        val href = anchor.attr("href").toAbsoluteUrl() ?: return null
        val title = anchor.attr("title").cleanText().takeIf { it.length > 2 }
            ?: anchor.selectFirst("h2, h3, h4, .tt, .title, .series-title")?.text()?.cleanText()?.takeIf { it.length > 2 }
            ?: selectFirst("h2, h3, h4, .tt, .title, .series-title")?.text()?.cleanText()?.takeIf { it.length > 2 }
            ?: anchor.text().cleanText().takeIf { it.length > 2 }
            ?: return null
        val poster = selectFirst("img")?.imageUrl()
            ?: anchor.selectFirst("img")?.imageUrl()
            ?: selectFirst("noscript")?.html()?.let { Jsoup.parse(it).selectFirst("img")?.imageUrl() }
            ?: styleImage()
        return newMovieSearchResponse(cleanTitle(title) ?: title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        val anchors = document.select(
            ".eplister li a[href], .eplister a[href], .episodelist li a[href], .episodelist a[href], " +
                ".episode-list li a[href], .episode-list a[href], .epslist li a[href], .epslist a[href], " +
                ".bixbox a[href*='episode'], .entry-content a[href*='episode'], .epcheck li a[href], " +
                ".episodelist article a[href], .episodes li a[href], .episodes a[href]"
        )
        return anchors.mapNotNull { anchor ->
            val href = anchor.attr("href").toAbsoluteUrl() ?: return@mapNotNull null
            if (!href.contains("episode", true)) return@mapNotNull null
            val rawTitle = anchor.selectFirst(".epl-title, .title, .ep-title, h2, h3, h4")?.text()
                ?: anchor.attr("title")
                ?: anchor.text()
                ?: return@mapNotNull null
            val title = rawTitle.cleanText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val episodeNumber = Regex("""(?i)(?:episode|eps?|ep)\s*(\d+)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""-(\d+)/?$""").find(href.trimEnd('/'))?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = cleanTitle(title) ?: title
                this.episode = episodeNumber
                this.posterUrl = anchor.selectFirst("img")?.imageUrl()
            }
        }.distinctBy { normalizeKey(it.data) }
    }

    private suspend fun collectAjaxPlayers(document: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val options = document.select(
            "#playeroptionsul li, ul#playeroptionsul li, li.dooplay_player_option, .dooplay_player_option, " +
                "[data-post][data-nume], [data-post][data-type], [data-id][data-nume], [data-player], [data-embed], [data-video]"
        )
        val nonce = document.html().firstMatch(
            """(?i)(?:nonce|security|ajax_nonce|doo_nonce)['\"\s:=]+([a-zA-Z0-9_-]{6,})"""
        )

        for (option in options) {
            val post = option.attr("data-post").ifBlank { option.attr("data-id") }
            val nume = option.attr("data-nume").ifBlank {
                option.attr("data-index").ifBlank { option.attr("data-server").ifBlank { option.attr("data-player").ifBlank { "1" } } }
            }
            val typeFromSource = option.attr("data-type")
            val typeCandidates = linkedSetOf<String>()
            if (typeFromSource.isNotBlank()) typeCandidates.add(typeFromSource)
            if (looksEpisodePage(pageUrl, pageUrl)) {
                typeCandidates.add("tv")
                typeCandidates.add("movie")
                typeCandidates.add("episode")
            } else {
                typeCandidates.add("movie")
                typeCandidates.add("tv")
            }
            option.attributes().forEach { attr ->
                if (attr.key.startsWith("data-", true)) attr.value.toAbsoluteUrl(pageUrl)?.let { links.add(it) }
            }
            if (post.isBlank()) continue

            val actions = listOf("doo_player_ajax", "doo_ajax_player", "player_ajax", "minioppai_player_ajax", "player_ajax_func")
            for (action in actions) {
                for (type in typeCandidates) {
                    val payload = mutableMapOf("action" to action, "post" to post, "nume" to nume, "type" to type)
                    if (!nonce.isNullOrBlank()) {
                        payload["nonce"] = nonce
                        payload["security"] = nonce
                    }
                    val body = runCatching {
                        app.post(
                            ajaxUrl,
                            data = payload,
                            headers = ajaxHeaders(pageUrl),
                            referer = pageUrl,
                        ).text
                    }.getOrDefault("")
                    links.addAll(collectScriptPlayers(body, pageUrl))
                    links.addAll(collectHtmlPlayers(body, pageUrl))
                }
            }
        }
        return links.toList()
    }

    private fun collectStaticPlayers(document: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select("iframe[src], embed[src], video[src], video source[src], source[src]").forEach { element ->
            links.add(element.attr("src"))
        }
        val dataAttrs = listOf(
            "data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file",
            "data-stream", "data-href", "data-lazy", "data-original", "data-content"
        )
        document.select("a[href], option[value], button, div, li, span").forEach { element ->
            val href = element.attr("href").ifBlank { element.attr("value") }
            if (href.isNotBlank()) links.add(href)
            dataAttrs.forEach { attr ->
                element.attr(attr).takeIf { it.isNotBlank() }?.let { links.add(it) }
            }
        }
        return links.mapNotNull { it.toAbsoluteUrl(pageUrl) }.filter { it.isPlayableCandidate() || it.isDirectMedia() }.distinct()
    }

    private fun collectHtmlPlayers(raw: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val html = raw.decodeUrlLike()
        val parsed = Jsoup.parse(html)
        parsed.select("iframe[src], embed[src], source[src], video[src], a[href]").forEach { element ->
            val value = element.attr("src").ifBlank { element.attr("href") }
            value.toAbsoluteUrl(baseUrl)?.let { links.add(it) }
        }
        parsed.select("[data-src], [data-url], [data-link], [data-iframe], [data-embed], [data-player], [data-video], [data-file]").forEach { element ->
            listOf("data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file").forEach { attr ->
                element.attr(attr).takeIf { it.isNotBlank() }?.toAbsoluteUrl(baseUrl)?.let { links.add(it) }
            }
        }
        return links.filter { it.isPlayableCandidate() || it.isDirectMedia() }.distinct()
    }

    private fun collectScriptPlayers(raw: String, baseUrl: String): List<String> {
        val html = raw.decodeUrlLike()
        val links = linkedSetOf<String>()
        links.addAll(collectHtmlPlayers(html, baseUrl))
        val keyRegex = Regex("""(?i)(?:file|url|src|source|embed|embed_url|player|iframe|link)\s*[:=]\s*['\"]([^'\"]+)['\"]""")
        keyRegex.findAll(html).forEach { match -> match.groupValues.getOrNull(1)?.toAbsoluteUrl(baseUrl)?.let { links.add(it) } }
        val urlRegex = Regex("""(?i)https?:\\?/\\?/[^'\"<>\s]+""")
        urlRegex.findAll(html).forEach { match -> match.value.toAbsoluteUrl(baseUrl)?.let { links.add(it) } }
        val b64Regex = Regex("""(?i)(?:atob|base64_decode)\(['\"]([A-Za-z0-9+/=_-]{16,})['\"]\)""")
        b64Regex.findAll(html).forEach { match ->
            decodeBase64(match.groupValues[1])?.let { decoded -> links.addAll(collectScriptPlayers(decoded, baseUrl)) }
        }
        return links.filter { it.isPlayableCandidate() || it.isDirectMedia() }.distinct()
    }

    private fun collectJsonLdPlayers(document: Document, pageUrl: String): List<String> {
        return document.select("script[type='application/ld+json'], script[type='application/json']")
            .flatMap { collectScriptPlayers(it.html(), pageUrl) }
            .distinct()
    }

    private fun pickPoster(document: Document): String? {
        val candidates = linkedSetOf<String>()
        listOf(
            "meta[property=og:image]", "meta[property=og:image:secure_url]", "meta[name=twitter:image]", "meta[name=thumbnail]"
        ).forEach { selector ->
            document.selectFirst(selector)?.attr("content")?.toAbsoluteUrl()?.let { candidates.add(it) }
        }
        document.selectFirst("link[rel=image_src]")?.attr("href")?.toAbsoluteUrl()?.let { candidates.add(it) }
        document.select(
            ".thumb img, .bigcontent img, .animefull img, .postbody img, article img, figure img, " +
                ".entry-content img, .wp-post-image, .attachment-post-thumbnail, .ts-post-image, .poster img, .cover img"
        ).forEach { img -> img.imageUrl()?.let { candidates.add(it) } }
        document.select("noscript").forEach { ns ->
            Jsoup.parse(ns.html()).select("img").forEach { img -> img.imageUrl()?.let { candidates.add(it) } }
        }
        document.select("[style*=url]").forEach { it.styleImage()?.let { image -> candidates.add(image) } }
        return candidates.firstOrNull { !it.isBadImage() }
    }

    private fun detectStatus(document: Document): ShowStatus? {
        val text = document.select(".info, .spe, .infodetail, .entry-content, .infox, .seriestucontent").text().lowercase()
        return when {
            "ongoing" in text -> ShowStatus.Ongoing
            "completed" in text || "complete" in text || "tamat" in text -> ShowStatus.Completed
            else -> null
        }
    }

    private fun looksEpisodePage(url: String, title: String): Boolean =
        url.contains("episode", true) || Regex("""(?i)episode\s*\d+""").containsMatchIn(title)

    private fun requestHeaders(referer: String): Map<String, String> = browserHeaders + mapOf(
        "Referer" to referer,
        "Origin" to mainUrl,
    )

    private fun mediaHeaders(referer: String): Map<String, String> = browserHeaders + mapOf(
        "Accept" to "*/*",
        "Referer" to referer,
        "Origin" to originOf(referer),
    )

    private fun ajaxHeaders(referer: String): Map<String, String> = browserHeaders + mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to referer,
        "Origin" to mainUrl,
    )

    private fun cleanTitle(value: String?): String? {
        val text = value?.cleanText()?.takeIf { it.isNotBlank() } ?: return null
        return text
            .substringBefore(" - MiniOppai", text)
            .substringBefore(" | MiniOppai", text)
            .substringBefore(" – MiniOppai", text)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun Element.imageUrl(): String? {
        val raw = attr("data-src").ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("data-lazy") }
            .ifBlank { attr("srcset").firstSrcSet() }
            .ifBlank { attr("data-srcset").firstSrcSet() }
            .ifBlank { attr("src") }
        return raw.toAbsoluteUrl()?.takeIf { !it.isBadImage() }
    }

    private fun Element.styleImage(): String? = Regex("""url\(['\"]?([^)'\"]+)""")
        .find(attr("style"))?.groupValues?.getOrNull(1)?.toAbsoluteUrl()?.takeIf { !it.isBadImage() }

    private fun String.firstSrcSet(): String = split(',')
        .map { it.trim().substringBefore(' ').trim() }
        .lastOrNull { it.isNotBlank() }
        .orEmpty()

    private fun String.cleanText(): String = Jsoup.parse(this).text()
        .replace("\\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.decodeUrlLike(): String = trim()
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#039;", "'")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("%3A", ":", ignoreCase = true)
        .replace("%2F", "/", ignoreCase = true)
        .replace("%3F", "?", ignoreCase = true)
        .replace("%26", "&", ignoreCase = true)
        .replace("%3D", "=", ignoreCase = true)
        .replace("%23", "#", ignoreCase = true)

    private fun String?.toAbsoluteUrl(base: String = mainUrl): String? {
        val raw = this?.trim()?.trim('"', '\'', ' ')?.decodeUrlLike()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> mainUrl.trimEnd('/') + raw
            raw.startsWith("#") || raw.startsWith("javascript:", true) || raw.startsWith("mailto:", true) -> null
            else -> runCatching { URI(base).resolve(raw).toString() }.getOrNull()
        }
    }

    private fun String.isDirectMedia(): Boolean {
        val value = lowercase().substringBefore("#")
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".webm") || value.contains(".mkv") || value.contains("googlevideo.com/videoplayback")
    }

    private fun String.isPlayableCandidate(): Boolean {
        val value = lowercase().decodeUrlLike()
        if (!value.startsWith("http")) return false
        if (isDirectMedia()) return true
        if (value.isBadAsset()) return false
        if (value.contains("minioppai.org/wp-content") && !value.contains("player") && !value.contains("embed")) return false
        if (isBlockedUtilityHost()) return false
        if (isExternalHost()) return true
        return listOf("embed", "player", "iframe", "stream", "video", "ajax", "source").any { value.contains(it) }
    }

    private fun String.isExternalHost(): Boolean {
        val host = hostOrNull()?.removePrefix("www.") ?: return false
        val mainHost = mainUrl.hostOrNull()?.removePrefix("www.") ?: return false
        return host != mainHost && !host.endsWith(".$mainHost")
    }

    private fun String.isBlockedUtilityHost(): Boolean {
        val host = hostOrNull()?.lowercase()?.removePrefix("www.") ?: return false
        return listOf(
            "facebook.com", "x.com", "twitter.com", "instagram.com", "t.me", "telegram.me", "disqus.com",
            "google.com", "googleapis.com", "gstatic.com", "gravatar.com", "doubleclick.net", "googletagmanager.com"
        ).any { host == it || host.endsWith(".$it") }
    }

    private fun String.isBadAsset(): Boolean {
        val value = lowercase().substringBefore("?").substringBefore("#")
        return Regex("""\.(?:jpg|jpeg|png|webp|gif|svg|css|woff|woff2|ttf|ico|js)(?:$|[?#])""").containsMatchIn(value)
    }

    private fun String.isBadImage(): Boolean {
        val value = lowercase()
        return value.isBlank() || value.contains("placeholder") || value.contains("no-image") || value.contains("default") ||
            value.contains("favicon") || value.contains("logo") || value.endsWith(".svg") || value.endsWith("/blank.gif")
    }

    private fun String.hostOrNull(): String? = runCatching { URI(this).host }.getOrNull()

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun hostLabel(url: String): String = runCatching { URI(url).host ?: name }
        .getOrDefault(name)
        .removePrefix("www.")
        .substringBefore('.')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun String.firstMatch(pattern: String): String? = Regex(pattern).find(this)?.groupValues?.getOrNull(1)

    private fun decodeBase64(value: String): String? = runCatching {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        base64Decode(padded)
    }.getOrNull()

    private fun normalizeKey(url: String): String = url.trim().trimEnd('/').lowercase()
}
