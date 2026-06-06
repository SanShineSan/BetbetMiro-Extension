package com.sad25kag.yurinime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class YuriNime : MainAPI() {
    override var mainUrl = "https://yurinime.com"
    override var name = "YuriNime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "" to "Home",
        "/anime/?order=update&status=&type=" to "Rilisan Terbaru",
        "/anime/list-mode/" to "Hentai List",
        "/genres/uncensored/" to "Uncensored",
        "/genres/yuri/" to "Yuri",
        "/genres/romance/" to "Romance",
        "/genres/school/" to "School",
        "/genres/vanilla/" to "Vanilla",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page), headers = siteHeaders).document
        val cards = parseCards(document).distinctBy { normalizeKey(it.url) }
        val hasNext = document.selectFirst(
            "a.next[href], a[rel=next], .pagination a[href]:contains(Selanjutnya), " +
                ".pagination a[href]:contains(Next), .nav-links a[href]:contains(Next), .hpage a[href]"
        ) != null
        return newHomePageResponse(request.name, cards, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val routes = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/anime/?s=$encoded",
        )

        val pageResults = routes.flatMap { url ->
            runCatching {
                app.get(url, headers = siteHeaders + mapOf("Referer" to "$mainUrl/")).document.let(::parseCards)
            }.getOrDefault(emptyList())
        }

        val ajaxResults = runCatching {
            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf("action" to "ts_ac_do_search", "ts_ac_query" to query),
                headers = ajaxHeaders(mainUrl),
                referer = mainUrl,
            ).text.let(::parseAjaxSearch)
        }.getOrDefault(emptyList())

        return (ajaxResults + pageResults).distinctBy { normalizeKey(it.url) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = siteHeaders + mapOf("Referer" to "$mainUrl/")).document
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1[itemprop=name], .entry-title h1, .bigcontent h1, h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ) ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl(url)
            ?: document.selectFirst(".thumb img, .poster img, .bigcontent img, .entry-content img, article img")?.imageUrl(url)

        val plot = document.selectFirst(".entry-content p, .synopsis, .sinopsis, .desc, .description, meta[name=description]")?.let {
            if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
        }?.cleanText()

        val tags = document.select("a[href*='/genres/'], a[rel=tag]")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = parseEpisodes(document).distinctBy { normalizeKey(it.data) }
        val recommendations = parseCards(document)
            .filterNot { normalizeKey(it.url) == normalizeKey(url) }
            .take(18)

        return if (episodes.isNotEmpty()) {
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
        val seen = linkedSetOf<String>()
        val seenPages = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, label: String? = null, referer: String = data): Boolean {
            val videoUrl = rawUrl?.decodeUrlLike()?.toAbsoluteUrl(referer)?.takeIf { it.isDirectMedia() } ?: return false
            val sourceName = listOfNotNull(name, label?.cleanText()?.takeIf { it.isNotBlank() }).joinToString(" - ")
            val headers = siteHeaders + mapOf("Referer" to referer, "Origin" to originOf(referer))

            if (videoUrl.contains(".m3u8", true)) {
                var emitted = false
                M3u8Helper.generateM3u8(
                    sourceName,
                    videoUrl,
                    referer = referer,
                    headers = headers,
                ).forEach { link ->
                    if (seen.add(link.url.substringBefore("#"))) {
                        emitted = true
                        callback.invoke(link)
                    }
                }
                return emitted
            }

            if (!seen.add(videoUrl.substringBefore("#"))) return true
            callback.invoke(
                newExtractorLink(sourceName, sourceName, videoUrl, INFER_TYPE) {
                    this.referer = referer
                    this.quality = getQualityFromName(label ?: videoUrl)
                    this.headers = headers
                },
            )
            return true
        }

        suspend fun resolvePage(pageUrl: String, referer: String = data, depth: Int = 0) {
            if (depth > 4) return
            val fixedPage = pageUrl.toAbsoluteUrl(referer) ?: return
            if (!seenPages.add(fixedPage)) return

            if (fixedPage.isDirectMedia()) {
                emitDirect(fixedPage, hostLabel(fixedPage), referer)
                return
            }

            val countedCallback: (ExtractorLink) -> Unit = { link ->
                if (seen.add(link.url.substringBefore("#"))) callback.invoke(link)
            }

            runCatching { loadExtractor(fixedPage, referer, subtitleCallback, countedCallback) }

            val text = runCatching {
                app.get(fixedPage, headers = siteHeaders + mapOf("Referer" to referer), referer = referer).text
            }.getOrNull() ?: return
            val unpacked = runCatching { getAndUnpack(text) }.getOrNull().orEmpty()
            val document = Jsoup.parse(text, fixedPage)

            collectSubtitles(document, fixedPage, subtitleCallback)
            collectStaticPlayers(document, fixedPage).forEach { candidate ->
                if (!emitDirect(candidate, hostLabel(candidate), fixedPage)) resolvePage(candidate, fixedPage, depth + 1)
            }
            collectScriptPlayers(text + "\n" + unpacked, fixedPage).forEach { candidate ->
                if (!emitDirect(candidate, hostLabel(candidate), fixedPage)) resolvePage(candidate, fixedPage, depth + 1)
            }
        }

        val document = app.get(data, headers = siteHeaders + mapOf("Referer" to "$mainUrl/"), referer = mainUrl).document
        collectSubtitles(document, data, subtitleCallback)

        val candidates = linkedSetOf<String>()
        candidates.addAll(collectStaticPlayers(document, data))
        candidates.addAll(collectAjaxPlayers(document, data))
        candidates.addAll(collectScriptPlayers(document.html(), data))

        for (candidate in candidates.take(60)) {
            val fixed = candidate.decodeUrlLike().toAbsoluteUrl(data) ?: continue
            if (fixed.isDirectMedia()) {
                emitDirect(fixed, hostLabel(fixed), data)
                continue
            }
            if (!fixed.isSupportedPlayerUrl()) continue
            val before = seen.size
            val countedCallback: (ExtractorLink) -> Unit = { link ->
                if (seen.add(link.url.substringBefore("#"))) callback.invoke(link)
            }
            runCatching { loadExtractor(fixed, data, subtitleCallback, countedCallback) }
            if (seen.size == before) resolvePage(fixed, data, 1)
        }

        if (seen.isEmpty()) {
            runCatching { loadExtractor(data, mainUrl, subtitleCallback) { link ->
                if (seen.add(link.url.substringBefore("#"))) callback.invoke(link)
            } }
        }

        return seen.isNotEmpty()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val base = when {
            path.isBlank() -> mainUrl
            path.startsWith("http", true) -> path
            else -> mainUrl + path
        }.trimEnd('/')

        if (page <= 1) return when {
            base.contains("?") -> base
            else -> "$base/"
        }

        return if (base.contains("?")) {
            val cleanBase = base.substringBefore("?").trimEnd('/')
            val query = base.substringAfter("?")
            "$cleanBase/page/$page/?$query"
        } else {
            "$base/page/$page/"
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val roots = listOf(
            document.select(".listupd article, .listupd .bs, .listupd .bsx, .series-gen .bsx"),
            document.select(".postbody article, .postbody .bsx, main article, article"),
            document.select(".bixbox .bsx, .releases article, .serieslist li, .mrgn article"),
            document.select(".result, .search-page article, .animepost, .animposx, .bs"),
            document.select("a[href*='/anime/']"),
        )
        return roots.asSequence()
            .flatMap { it.asSequence() }
            .mapNotNull { it.toYuriNimeCard() }
            .distinctBy { normalizeKey(it.url) }
            .toList()
    }

    private fun Element.toYuriNimeCard(): SearchResponse? {
        val anchor = when {
            tagName().equals("a", true) -> this
            else -> selectFirst("a[href*='/anime/'], h2 a[href], h3 a[href], .tt a[href], .bsx a[href], .title a[href]")
        } ?: return null

        val href = anchor.attr("href").toAbsoluteUrl() ?: return null
        if (!href.contains("/anime/", true)) return null
        if (href.contains("/anime/list-mode", true)) return null

        val title = listOfNotNull(
            anchor.selectFirst("img[alt]")?.attr("alt"),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.attr("title"),
            anchor.selectFirst("h2, h3, .tt, .title, .series-title")?.text(),
            selectFirst("h2, h3, .tt, .title, .series-title")?.text(),
            anchor.ownText(),
            anchor.text(),
        ).mapNotNull { cleanTitle(it) }
            .firstOrNull { it.length > 2 }
            ?: return null

        val poster = selectFirst("img")?.imageUrl(href)
            ?: anchor.selectFirst("img")?.imageUrl(href)
            ?: styleImage(href)

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        val anchors = document.select(
            ".eplister li a[href], .episodelist li a[href], .episodelist a[href], .episode-list a[href], " +
                ".epslist a[href], .bixbox a[href*='episode'], .entry-content a[href*='episode'], " +
                "a[href*='/episode/'], a[href*='-episode-'], a[href*='episode-']"
        )

        return anchors.mapNotNull { anchor ->
            val href = anchor.attr("href").toAbsoluteUrl() ?: return@mapNotNull null
            if (!href.contains("episode", true) && !anchor.text().contains("episode", true)) return@mapNotNull null
            val rawTitle = anchor.selectFirst(".epl-title, .title, .ep-title, h3, span")?.text()
                ?: anchor.text()
            val title = cleanTitle(rawTitle) ?: return@mapNotNull null
            val episodeNumber = Regex("""(?i)(?:episode|eps?|ep)\s*\(?0*(\d+)\)?""")
                .find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(?i)(?:episode|ep)-?0*(\d+)""")
                    .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(href) {
                this.name = title
                this.episode = episodeNumber
                this.posterUrl = anchor.selectFirst("img")?.imageUrl(href)
            }
        }.distinctBy { normalizeKey(it.data) }
    }

    private suspend fun collectAjaxPlayers(document: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val options = document.select(
            "#playeroptionsul li, ul#playeroptionsul li, li.dooplay_player_option, .dooplay_player_option, " +
                ".player-option, .playeroptions li, [data-post][data-nume], [data-post][data-type], [data-id][data-nume]"
        )

        for (option in options) {
            val post = option.attr("data-post").ifBlank { option.attr("data-id") }
            val nume = option.attr("data-nume")
                .ifBlank { option.attr("data-number") }
                .ifBlank { option.attr("data-index") }
                .ifBlank { option.attr("data-server") }
                .ifBlank { "1" }
            val type = option.attr("data-type").ifBlank { "movie" }
            if (post.isBlank()) continue

            val actions = listOf("doo_player_ajax", "doo_ajax_player", "player_ajax", "load_player", "yurinime_player_ajax")
            for (action in actions) {
                val body = runCatching {
                    app.post(
                        ajaxUrl,
                        data = mapOf("action" to action, "post" to post, "nume" to nume, "type" to type),
                        headers = ajaxHeaders(pageUrl),
                        referer = pageUrl,
                    ).text
                }.getOrDefault("")
                if (body.isBlank()) continue
                links.addAll(collectScriptPlayers(body, pageUrl))
                links.addAll(collectStaticPlayers(Jsoup.parse(body, pageUrl), pageUrl))
                if (links.isNotEmpty()) break
            }
        }
        return links.toList()
    }

    private fun collectStaticPlayers(document: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()

        fun addCandidate(raw: String?) {
            val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return
            links.add(value)
            decodePossibleBase64(value)?.let { decoded ->
                links.add(decoded)
                links.addAll(extractUrlsFromDecodedMirror(decoded, pageUrl))
            }
        }

        document.select("iframe[src], embed[src], video[src], video source[src], source[src]").forEach { element ->
            addCandidate(element.attr("src"))
        }
        val dataAttrs = listOf(
            "data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file",
            "data-stream", "data-href", "value"
        )
        document.select("a[href], option[value], button, div, li, span").forEach { element ->
            addCandidate(element.attr("href"))
            dataAttrs.forEach { attr -> addCandidate(element.attr(attr)) }
        }
        return links.mapNotNull { it.toAbsoluteUrl(pageUrl) }
            .filter { it.isSupportedPlayerUrl() || it.isDirectMedia() }
            .distinct()
    }

    private fun collectScriptPlayers(raw: String, baseUrl: String): List<String> {
        val html = raw.decodeUrlLike()
        val links = linkedSetOf<String>()
        Jsoup.parse(html, baseUrl).select("iframe[src], embed[src], source[src], video[src], a[href]").forEach { element ->
            val value = element.attr("src").ifBlank { element.attr("href") }
            value.toAbsoluteUrl(baseUrl)?.let { links.add(it) }
        }
        val keyRegex = Regex("""(?i)(?:file|url|src|source|embed|embed_url|player|iframe|hls|playlist)\s*[:=]\s*['\"]([^'\"]+)['\"]""")
        keyRegex.findAll(html).forEach { match -> match.groupValues.getOrNull(1)?.toAbsoluteUrl(baseUrl)?.let { links.add(it) } }
        val quotedMediaRegex = Regex("""(?i)['\"]((?:https?:)?//[^'\"<>\s]+?(?:\.m3u8|\.mp4|googlevideo\.com/[^'\"<>\s]+|videoplayback[^'\"<>\s]*)(?:\?[^'\"<>\s]*)?)['\"]""")
        quotedMediaRegex.findAll(html).forEach { match -> match.groupValues.getOrNull(1)?.toAbsoluteUrl(baseUrl)?.let { links.add(it) } }
        val urlRegex = Regex("""(?i)https?:\\?/\\?/[^'\"<>\s]+""")
        urlRegex.findAll(html).forEach { match -> match.value.toAbsoluteUrl(baseUrl)?.let { links.add(it) } }
        val encodedHttpRegex = Regex("""https?%3A%2F%2F[^\s'\"<>]+""", RegexOption.IGNORE_CASE)
        encodedHttpRegex.findAll(html).forEach { match -> match.value.decodeUrlLike().toAbsoluteUrl(baseUrl)?.let { links.add(it) } }
        val b64Regex = Regex("""(?i)(?:atob|base64_decode)\(['\"]([A-Za-z0-9+/=_-]{16,})['\"]\)""")
        b64Regex.findAll(html).forEach { match ->
            decodeBase64(match.groupValues[1])?.let { decoded -> links.addAll(collectScriptPlayers(decoded, baseUrl)) }
        }
        return links.filter { it.isSupportedPlayerUrl() || it.isDirectMedia() }.distinct()
    }

    private fun parseAjaxSearch(raw: String): List<SearchResponse> {
        val normalized = raw.decodeUrlLike()
        val itemRegex = Regex(
            """(?s)\{[^{}]*"post_image"\s*:\s*"((?:\\.|[^"\\])*)"[^{}]*"post_title"\s*:\s*"((?:\\.|[^"\\])*)"[^{}]*"post_link"\s*:\s*"((?:\\.|[^"\\])*)"[^{}]*\}"""
        )
        return itemRegex.findAll(normalized).mapNotNull { match ->
            val poster = match.groupValues.getOrNull(1)?.unescapeJsonLike()?.toAbsoluteUrl(mainUrl)
            val title = cleanTitle(match.groupValues.getOrNull(2)?.unescapeJsonLike()) ?: return@mapNotNull null
            val href = match.groupValues.getOrNull(3)?.unescapeJsonLike()?.toAbsoluteUrl(mainUrl) ?: return@mapNotNull null
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        }.distinctBy { normalizeKey(it.url) }.toList()
    }

    private fun extractUrlsFromDecodedMirror(decoded: String, baseUrl: String): List<String> {
        val normalized = decoded.decodeUrlLike()
        val links = linkedSetOf<String>()
        Jsoup.parse(normalized, baseUrl).select("iframe[src], embed[src], source[src], video[src], a[href]").forEach { element ->
            val value = element.attr("src").ifBlank { element.attr("href") }
            value.toAbsoluteUrl(baseUrl)?.let { links.add(it) }
        }
        val srcRegex = Regex("""(?i)(?:src|href|file|url)\s*=\s*['"]([^'"]+)['"]""")
        srcRegex.findAll(normalized).forEach { match ->
            match.groupValues.getOrNull(1)?.toAbsoluteUrl(baseUrl)?.let { links.add(it) }
        }
        return links.filter { it.isSupportedPlayerUrl() || it.isDirectMedia() }.distinct()
    }

    private fun collectSubtitles(document: Document, pageUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[kind=subtitles], track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val subUrl = element.attr("src").ifBlank { element.attr("href") }.toAbsoluteUrl(pageUrl) ?: return@forEach
            val label = element.attr("label").ifBlank { element.text().ifBlank { "Subtitle" } }.cleanText()
            subtitleCallback.invoke(SubtitleFile(label, subUrl))
        }
    }

    private fun detectStatus(document: Document): ShowStatus? {
        val text = document.select(".info, .spe, .infodetail, .entry-content, .bigcontent").text().lowercase()
        return when {
            "ongoing" in text -> ShowStatus.Ongoing
            "completed" in text || "complete" in text || "batch" in text -> ShowStatus.Completed
            else -> null
        }
    }

    private fun ajaxHeaders(referer: String): Map<String, String> = siteHeaders + mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to referer,
        "Origin" to originOf(referer),
    )

    private fun cleanTitle(value: String?): String? {
        val text = value?.cleanText()?.takeIf { it.isNotBlank() } ?: return null
        return text
            .substringBefore(" - Yurinime", text)
            .substringBefore(" | Yurinime", text)
            .substringBefore(" – Yurinime", text)
            .replace(Regex("""(?i)\bIndo\s+Sub(?:title)?\b"""), " ")
            .replace(Regex("""(?i)\bSub\b"""), " ")
            .replace(Regex("""(?i)\bUncensored\b"""), " ")
            .replace(Regex("""(?i)\b(?:Completed|Ongoing|Upcoming|Hiatus)\b"""), " ")
            .replace(Regex("""(?i)\b(?:TV|OVA|ONA|Movie|Special|BD)\b"""), " ")
            .replace(Regex("""(?i)\b(?:Episode|Ep)\s*\(?\d+\)?"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '|')
            .takeIf { it.isNotBlank() }
    }

    private fun Element.imageUrl(base: String = mainUrl): String? = attr("data-src")
        .ifBlank { attr("data-lazy-src") }
        .ifBlank { attr("data-original") }
        .ifBlank { attr("src") }
        .toAbsoluteUrl(base)

    private fun Element.styleImage(base: String = mainUrl): String? = Regex("""url\(['\"]?([^)'"]+)""")
        .find(attr("style"))?.groupValues?.getOrNull(1)?.toAbsoluteUrl(base)

    private fun String.cleanText(): String = Jsoup.parse(this).text()
        .replace("\\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.decodeUrlLike(): String = trim()
        .replace("\\/", "/")
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#039;", "'")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003a", ":")
        .replace("%3A", ":", ignoreCase = true)
        .replace("%2F", "/", ignoreCase = true)
        .replace("%3F", "?", ignoreCase = true)
        .replace("%26", "&", ignoreCase = true)
        .replace("%3D", "=", ignoreCase = true)

    private fun String?.toAbsoluteUrl(base: String = mainUrl): String? {
        val raw = this?.trim()?.trim('"', '\'', ' ')?.decodeUrlLike()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> mainUrl.trimEnd('/') + raw
            raw.startsWith("#") || raw.startsWith("javascript:", true) || raw.equals("about:blank", true) -> null
            else -> runCatching { URI(base).resolve(raw).toString() }.getOrNull()
        }
    }

    private fun String.isDirectMedia(): Boolean {
        val value = lowercase().substringBefore("#")
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".webm") || value.contains(".mkv") || value.contains("googlevideo.com/videoplayback")
    }

    private fun String.isSupportedPlayerUrl(): Boolean {
        val value = lowercase()
        if (!value.startsWith("http")) return false
        if (isDirectMedia()) return true
        if (value.contains("yurinime.com/wp-content") && !value.contains("player")) return false
        if (value.contains("yurinime.com/") && !value.contains("player") && !value.contains("embed")) return false
        if (Regex("""\.(?:jpg|jpeg|png|webp|gif|svg|css|woff|woff2|ttf|ico)(?:\?|$)""").containsMatchIn(value)) return false
        return listOf(
            "streampoi", "playmogo", "filemoon", "streamtape", "streamruby", "dood", "d000d",
            "vidhide", "voe", "mixdrop", "mp4upload", "streamwish", "vidguard", "luluvdo",
            "sbembed", "savefiles", "pixeldrain", "gofile", "krakenfiles", "blogger", "blogspot",
            "googlevideo", "ok.ru", "vk.com", "sendvid", "uqload", "embed", "player", "hls", "m3u8",
            "abyssplayer", "abyss.to", "alqastream", "upn.bio", "resharer", "sssrr.org", "morphify"
        ).any { value.contains(it) }
    }

    private fun hostLabel(url: String): String = runCatching { URI(url).host ?: name }
        .getOrDefault(name)
        .removePrefix("www.")
        .substringBefore('.')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun String.unescapeJsonLike(): String = replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003a", ":")
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("\\n", " ")
        .replace("\\t", " ")
        .cleanText()
    private fun decodePossibleBase64(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.length >= 20 } ?: return null
        if (!Regex("""^[A-Za-z0-9+/=_-]+$""").matches(raw)) return null
        return decodeBase64(raw)?.decodeUrlLike()?.takeIf { decoded ->
            decoded.contains("<iframe", true) || decoded.contains("http", true) || decoded.contains("src=", true)
        }
    }

    private fun decodeBase64(value: String): String? = runCatching {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        base64Decode(padded)
    }.getOrNull()

    private fun normalizeKey(url: String): String = url.trim().trimEnd('/').lowercase()
}
