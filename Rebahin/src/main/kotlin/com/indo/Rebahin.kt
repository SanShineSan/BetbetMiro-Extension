package com.indo

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class Rebahin : MainAPI() {
    // 1. UPDATE DOMAIN KE YANG WORK (.biz)
    override var mainUrl = "https://rebahinxxi3.biz"
    override var name = "Rebahin"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf("__home__" to "Rebahin")

    private data class HomeRoute(val title: String, val path: String)

    // 2. SUNAT ROUTE UTAMA (Mencegah "Hadew" kena limit IP dari Cloudflare Rebahin)
    private val homeRoutes = listOf(
        HomeRoute("Featured", "/"),
        HomeRoute("Film Terbaru", "/movies/"),
        HomeRoute("Serial TV", "/tvshows/"),
        HomeRoute("Anime", "/anime/"),
        HomeRoute("Drama Korea", "/genre/drama-korea/")
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache"
    )

    private val mirrors = listOf(
        "https://rebahinxxi3.biz",
        "https://rebahinxxi3.com",
        "https://rebahinxxi3.ink",
        "https://rebahinxxi3.cyou",
        "https://rebahinxxi3.autos",
        "https://rebahinxxi3.rest",
        "https://windowsxpuser.com",
        "http://178.62.98.100"
    )

    private data class ParsedItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.trim()
        val isHome = data.isBlank() || data == "/" ||
            data.equals("home", true) || data.equals("featured", true) ||
            data.contains("__home__", true) || data.removeSuffix("/") == mainUrl.removeSuffix("/")

        if (isHome && page <= 1) {
            val rows = mutableListOf<HomePageList>()
            homeRoutes.forEach { route ->
                val items = runCatching {
                    parseSearchItems(fetchDocument(route.path))
                        .distinctBy { it.url }
                        .take(24)
                }.getOrDefault(emptyList())

                if (items.isNotEmpty()) rows.add(HomePageList(route.title, items))
            }

            if (rows.isNotEmpty()) return newHomePageResponse(rows, hasNext = false)

            val fallback = runCatching {
                parseSearchItems(fetchDocument("/"))
                    .distinctBy { it.url }
                    .take(40)
            }.getOrDefault(emptyList())

            return newHomePageResponse(
                listOf(HomePageList("Film Terbaru", fallback)),
                hasNext = fallback.isNotEmpty()
            )
        }

        val targetPath = if (isHome) "/" else data
        val doc = fetchDocument(pagedUrl(targetPath, page))
        val items = parseSearchItems(doc).distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name.ifBlank { "Rebahin" }, items)),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val htmlResults = runCatching {
            parseSearchItems(fetchDocument("/?s=$encoded"))
        }.getOrDefault(emptyList())

        if (htmlResults.isNotEmpty()) return htmlResults

        return runCatching {
            val apiUrl = "$mainUrl/wp-json/wp/v2/search?search=$encoded&per_page=20"
            val json = app.get(apiUrl, headers = headers).text
            Regex("""\{[^{}]*"title"\s*:\s*"([^"]+)"[^{}]*"url"\s*:\s*"([^"]+)"[^{}]*\}""")
                .findAll(json)
                .mapNotNull { match ->
                    val title = match.groupValues[1].cleanText()
                    val url = match.groupValues[2].cleanEscaped()
                    if (title.isBlank() || !isValidContentUrl(url)) return@mapNotNull null
                    newMovieSearchResponse(title, url, TvType.Movie)
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.removeSuffix("/play/").removeSuffix("/play") + "/"
        val doc = fetchDocument(cleanUrl)

        val title = cleanTitle(
            doc.selectFirst("h1, h1.entry-title, .sheader .data h1, .data h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: "Rebahin"
        )

        val poster = firstImage(doc)
        val plot = doc.selectFirst(".wp-content p, .entry-content p, .description, .storyline, .sinopsis, .content p")
            ?.text()
            ?.cleanText()
            .orEmpty()

        val tags = doc.select("a[href*=/category/], a[href*=/genre/], .sgeneros a, .genres a, .gmr-movie-genre a, a[rel=tag]")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() && it.length <= 40 }
            .distinct()

        val year = (doc.selectFirst("a[href*=/year/], .date, .release, .year")?.text()
            ?: Regex("""\b(19|20)\d{2}\b""").find(doc.text())?.value)
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }

        val isSeries = isSeriesDoc(doc, cleanUrl, tags)
        if (isSeries) {
            val episodes = parseEpisodes(doc).ifEmpty {
                listOf(
                    newEpisode(resolvePlayablePage(cleanUrl, doc)) {
                        this.name = title
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            return newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }

        return newMovieLoadResponse(title, cleanUrl, TvType.Movie, resolvePlayablePage(cleanUrl, doc)) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val safeCallback: (ExtractorLink) -> Unit = {
            found = true
            callback(it)
        }

        val pages = linkedSetOf<String>()
        pages.add(data)
        if (!data.trimEnd('/').endsWith("/play")) {
            pages.add(data.trimEnd('/') + "/play/")
        }

        for (page in pages) {
            val doc = runCatching { fetchDocument(page) }.getOrNull() ?: continue
            val referer = doc.location().ifBlank { page }
            val html = doc.html().cleanEscaped()

            requestTopXtabPlayers(doc, referer).forEach { raw ->
                handlePlayable(raw, referer, subtitleCallback, safeCallback)
            }

            extractPlayerOptions(doc).forEach { (post, nume, type) ->
                requestDooplayPlayer(post, nume, type, referer).forEach { raw ->
                    handlePlayable(raw, referer, subtitleCallback, safeCallback)
                }
            }

            extractCandidateLinks(doc, html, referer).forEach { raw ->
                handlePlayable(raw, referer, subtitleCallback, safeCallback)
            }
        }

        return found
    }

    // 3. SUNTIKAN HYBRID: MESIN PENGEKSTRAK PUNYA ORANG DIGABUNG KE SISTEM LU
    private suspend fun handlePlayable(
        raw: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = normalizeUrl(raw, referer)
            .replace("/embed-", "/")
            .trim()

        if (fixed.isBlank() || !fixed.startsWith("http", true) || isBadLink(fixed)) return

        // Ekstraksi Custom Player "Pelm" dari kodingan orang lain
        if (fixed.contains("pelm.re") || fixed.contains("pelm.my.id")) {
            runCatching {
                val pelmLink = fixed.replace("pelm.re", "pelm.my.id")
                val iframe = app.get(pelmLink).document.selectFirst("iframe")?.attr("src")
                
                if (iframe != null) {
                    val script = app.get(iframe, referer = pelmLink).document.select("script").html()
                    
                    // Ekstrak M3U8
                    Regex("[\"'](http.*m3u8.*?)[\"']").find(script)?.groupValues?.getOrNull(1)?.let { m3u8Link ->
                        M3u8Helper.generateM3u8(
                            name,
                            m3u8Link,
                            referer = "$mainUrl/",
                            headers = mapOf("Accept" to "*/*", "Origin" to mainUrl)
                        ).forEach(callback)
                    }

                    // Ekstrak Subtitle
                    val subData = Regex("\"?tracks\"?:\\s\\n?\\[(.*)],").find(script)?.groupValues?.getOrNull(1)
                        ?: Regex("\"?tracks\"?:\\s\\n?\\[\\s*(?s:(.+)],\\n\\s*\"sources)").find(script)?.groupValues?.getOrNull(1)
                        
                    tryParseJson<List<Map<String, String>>>("[$subData]")?.forEach { track ->
                        val file = track["file"]
                        val label = track["label"] ?: "Unknown"
                        if (file?.contains(".srt") == true) {
                            val lang = if (label.contains("indonesia", true) || label.contains("bahasa", true)) "Indonesian" else label
                            subtitleCallback.invoke(SubtitleFile(lang, file))
                        }
                    }
                }
            }
            return // Stop eksekusi agar tidak dobel dengan loadExtractor bawah
        }

        // Kalau bukan pelm, jalankan mesin fallback asli lu
        when {
            isDirectVideo(fixed) -> emitDirectLink(fixed, referer, callback)
            else -> runCatching { loadExtractor(fixed, referer, subtitleCallback, callback) }
        }
    }

    private suspend fun emitDirectLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = url,
                type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(url).takeIf { it != Qualities.Unknown.value }
                    ?: Qualities.Unknown.value
            }
        )
    }

    private suspend fun requestDooplayPlayer(
        post: String,
        nume: String,
        type: String,
        referer: String
    ): List<String> {
        if (post.isBlank() || nume.isBlank()) return emptyList()

        val ajaxUrls = listOf(
            "$mainUrl/wp-admin/admin-ajax.php",
            rootOf(referer) + "/wp-admin/admin-ajax.php"
        ).distinct()

        val results = linkedSetOf<String>()
        ajaxUrls.forEach { ajaxUrl ->
            runCatching {
                val response = app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type.ifBlank { "movie" }
                    ),
                    headers = headers,
                    referer = referer
                ).text.cleanEscaped()

                val json = runCatching { JSONObject(response) }.getOrNull()
                listOf(
                    json?.optString("embed_url").orEmpty(),
                    json?.optString("iframe").orEmpty(),
                    json?.optString("html").orEmpty(),
                    response
                ).flatMap { extractUrlsFromText(it) }.forEach { results.add(it) }
            }
        }
        return results.toList()
    }

    private suspend fun requestTopXtabPlayers(doc: Document, referer: String): List<String> {
        val html = doc.html().cleanEscaped()
        val endpoints = linkedSetOf<String>()

        Regex("""["']([^"']*ajax-top-xtab\.php[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .map { normalizeUrl(it, referer) }
            .filter { it.contains("ajax-top-xtab.php", true) }
            .forEach { endpoints.add(it) }

        val root = rootOf(referer)
        listOf(
            "$root/ajax-top-xtab.php",
            "$root/wp-content/themes/indoxxi/ajax-top-xtab.php",
            "$root/wp-content/themes/indoxxi21/ajax-top-xtab.php",
            "$root/wp-content/themes/rebahin/ajax-top-xtab.php",
            "$root/wp-content/themes/dooplay/ajax-top-xtab.php"
        ).forEach { endpoints.add(it) }

        val playerPairs = linkedSetOf<Pair<String, String>>()
        doc.select("[data-id], [data-post], [data-movie], [data-server], [data-nume], [data-iframe]").forEach { el ->
            val id = el.attr("data-id")
                .ifBlank { el.attr("data-post") }
                .ifBlank { el.attr("data-movie") }
                .ifBlank { el.attr("data-film") }
                .trim()
            val server = el.attr("data-server")
                .ifBlank { el.attr("data-nume") }
                .ifBlank { el.attr("data-xfield") }
                .trim()
            if (id.matches(Regex("\\d+"))) playerPairs.add(id to server.ifBlank { "1" })
        }

        Regex("""(?:post|movie|id|id_post|movie_id)\s*[:=]\s*["']?(\d{2,})["']?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .take(3)
            .forEach { match ->
                val id = match.groupValues[1]
                (1..6).forEach { playerPairs.add(id to it.toString()) }
            }

        val results = linkedSetOf<String>()
        endpoints.take(5).forEach { endpoint ->
            runCatching {
                val response = app.get(endpoint, headers = ajaxHeaders(), referer = referer).text.cleanEscaped()
                collectPlayableFromAjaxResponse(response).forEach { results.add(it) }
            }

            playerPairs.take(18).forEach { (id, server) ->
                val postBodies = listOf(
                    mapOf("id" to id, "server" to server),
                    mapOf("movie" to id, "server" to server),
                    mapOf("post" to id, "nume" to server, "type" to "movie")
                )

                postBodies.forEach { body ->
                    runCatching {
                        val response = app.post(
                            endpoint,
                            data = body,
                            headers = ajaxHeaders(),
                            referer = referer
                        ).text.cleanEscaped()
                        collectPlayableFromAjaxResponse(response).forEach { results.add(it) }
                    }
                }
            }
        }
        return results.toList()
    }

    private fun ajaxHeaders(): Map<String, String> {
        return headers + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
    }

    private fun collectPlayableFromAjaxResponse(response: String): List<String> {
        val results = linkedSetOf<String>()
        val clean = response.cleanEscaped()

        extractUrlsFromText(clean).forEach { results.add(it) }
        extractBase64Payloads(clean).forEach { payload ->
            results.add(payload)
            extractUrlsFromText(payload).forEach { results.add(it) }
        }

        Regex("""data-iframe=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.groupValues[1].cleanEscaped() }
            .forEach { raw ->
                decodeBase64(raw)?.let { decoded ->
                    results.add(decoded)
                    extractUrlsFromText(decoded).forEach { results.add(it) }
                } ?: results.add(raw)
            }

        return results.toList()
    }

    private fun decodeBase64(raw: String): String? {
        val fixed = raw.trim()
            .replace("-", "+")
            .replace("_", "/")
            .let { value ->
                val padding = (4 - value.length % 4) % 4
                value + "=".repeat(padding)
            }
        return runCatching { String(Base64.decode(fixed, Base64.DEFAULT)) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractPlayerOptions(doc: Document): List<Triple<String, String, String>> {
        val options = mutableListOf<Triple<String, String, String>>()
        doc.select("[data-post][data-nume], .dooplay_player_option, .player-option, li[id*=player-option]").forEach { el ->
            val post = el.attr("data-post").ifBlank { el.attr("data-id") }
            val nume = el.attr("data-nume").ifBlank { el.attr("data-server") }.ifBlank { el.attr("data-n") }
            val type = el.attr("data-type").ifBlank { el.attr("data-tipo") }
            if (post.isNotBlank() && nume.isNotBlank()) {
                options.add(Triple(post, nume, type.ifBlank { "movie" }))
            }
        }

        Regex("""data-post=["'](\d+)["'][^>]+data-nume=["'](\d+)["'][^>]+data-type=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(doc.html())
            .forEach { options.add(Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3])) }

        return options.distinct()
    }

    private fun extractCandidateLinks(doc: Document, html: String, referer: String): List<String> {
        val links = linkedSetOf<String>()

        doc.select(
            "iframe[src], embed[src], video[src], source[src], " +
                "a[href], [data-src], [data-url], [data-link], [data-href], [data-iframe], [data-frame], [data-video]"
        ).forEach { el ->
            listOf("src", "href", "data-src", "data-url", "data-link", "data-href", "data-iframe", "data-frame", "data-video")
                .map { el.attr(it) }
                .filter { it.isNotBlank() }
                .forEach { links.add(normalizeUrl(it, referer)) }
        }

        extractUrlsFromText(html).forEach { links.add(normalizeUrl(it, referer)) }
        extractBase64Payloads(html).forEach { payload ->
            extractUrlsFromText(payload).forEach { links.add(normalizeUrl(it, referer)) }
        }

        return links.filter { isValidCandidate(it) }.distinct()
    }

    private fun extractUrlsFromText(text: String): List<String> {
        val clean = text.cleanEscaped()
        val urls = linkedSetOf<String>()

        Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.trimEnd(',', ';', ')', ']') }
            .forEach { urls.add(it) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrNull() }
            .forEach { urls.add(it) }

        Regex("""(?:file|url|src|link|embed_url|iframe)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.groupValues[1] }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun extractBase64Payloads(text: String): List<String> {
        val results = mutableListOf<String>()
        val patterns = listOf(
            Regex("""(?:atob|Base64\.decode)\(["']([A-Za-z0-9+/=]{24,})["']\)""", RegexOption.IGNORE_CASE),
            Regex("""data-iframe=["']([A-Za-z0-9+/=]{24,})["']""", RegexOption.IGNORE_CASE),
            Regex("""data-src=["']([A-Za-z0-9+/=]{24,})["']""", RegexOption.IGNORE_CASE),
            Regex("""iframe\s*[:=]\s*["']([A-Za-z0-9+/=]{24,})["']""", RegexOption.IGNORE_CASE)
        )

        patterns.forEach { regex ->
            regex.findAll(text).forEach { match ->
                decodeBase64(match.groupValues[1])?.let { results.add(it.cleanEscaped()) }
            }
        }
        return results.distinct()
    }

    private fun parseSearchItems(doc: Document): List<SearchResponse> {
        val parsed = linkedMapOf<String, ParsedItem>()
        val selectors = listOf(
            "article",
            "div[id^=post-]",
            ".items article",
            ".result-item article",
            ".ml-item",
            ".movie",
            ".movies .movie",
            ".item",
            ".items .item",
            ".poster",
            ".post",
            ".post-item",
            ".box-item",
            ".halim-item",
            ".card",
            ".gmr-movie-item",
            ".gmr-item-modulepost",
            ".pelicula",
            ".module .content .items > *",
            ".movies-list > *",
            ".series-list > *",
            ".owl-item",
            ".slider .item"
        ).joinToString(",")

        doc.select(selectors).forEach { element ->
            parseItem(element)?.let { item -> parsed.putIfAbsent(item.url, item) }
        }

        doc.select("a[href]").forEach { anchor ->
            parseAnchorItem(anchor)?.let { item -> parsed.putIfAbsent(item.url, item) }
        }

        return parsed.values.map { item ->
            when (item.type) {
                TvType.TvSeries -> newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) {
                    this.posterUrl = item.poster
                }
                else -> newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                    this.posterUrl = item.poster
                }
            }
        }
    }

    private fun parseItem(element: Element): ParsedItem? {
        val anchor = element.select("h2 a[href], h3 a[href], h4 a[href], .title a[href], .data a[href], a[href]")
            .firstOrNull { isRealContentAnchor(it) } ?: return null
        return parseAnchorWithContainer(anchor, element)
    }

    private fun parseAnchorItem(anchor: Element): ParsedItem? {
        if (!isRealContentAnchor(anchor)) return null
        val container = listOfNotNull(
            anchor.closest("article"),
            anchor.closest(".item"),
            anchor.closest(".movie"),
            anchor.closest(".ml-item"),
            anchor.closest(".post"),
            anchor.closest(".post-item"),
            anchor.closest(".box-item"),
            anchor.closest(".card"),
            anchor.closest(".gmr-movie-item"),
            anchor.parent(),
            anchor
        ).first()
        return parseAnchorWithContainer(anchor, container)
    }

    private fun parseAnchorWithContainer(anchor: Element, element: Element): ParsedItem? {
        val url = normalizeUrl(anchor.attr("href"), mainUrl)
        if (!isValidContentUrl(url)) return null

        val titleAnchor = element.selectFirst("h1 a[href], h2 a[href], h3 a[href], h4 a[href], .title a[href], .data h2, .data h3, .tt, .entry-title")
        val rawTitle = listOf(
            titleAnchor?.attr("title").orEmpty(),
            titleAnchor?.text().orEmpty(),
            anchor.attr("title"),
            anchor.selectFirst("img[alt]")?.attr("alt").orEmpty(),
            element.selectFirst("img[alt]")?.attr("alt").orEmpty(),
            anchor.text(),
            element.text().lineSequence().firstOrNull().orEmpty()
        ).firstOrNull { value ->
            val clean = value.cleanText()
            clean.length > 2 &&
                !clean.equals("Tonton", true) &&
                !clean.equals("Tonton Film", true) &&
                !clean.equals("Trailer", true) &&
                !clean.equals("Lihat Semua", true)
        }.orEmpty()

        val title = cleanTitle(rawTitle)
        if (title.length < 2 || isBadTitle(title)) return null

        val poster = findImage(element) ?: findImage(anchor)
        val text = element.text()
        val type = if (text.contains("TV Show", true) ||
            text.contains("Serial TV", true) ||
            text.contains("Eps:", true) ||
            url.contains("/tv/", true) ||
            url.contains("/episode/", true)
        ) TvType.TvSeries else TvType.Movie

        return ParsedItem(title, url, poster, type)
    }

    private fun isRealContentAnchor(anchor: Element): Boolean {
        val href = normalizeUrl(anchor.attr("href"), mainUrl)
        val text = anchor.text().cleanText()
        return isValidContentUrl(href) &&
            !href.contains("youtube", true) &&
            !text.equals("Trailer", true) &&
            !text.equals("Home", true) &&
            !text.startsWith("Lihat Semua", true)
    }

    private fun findImage(element: Element): String? {
        return element.selectFirst("img[data-src], img[data-lazy-src], img[data-original], img[data-wpfc-original-src], img[src], source[srcset]")?.let { img ->
            listOf("data-src", "data-lazy-src", "data-original", "data-wpfc-original-src", "src", "srcset")
                .firstNotNullOfOrNull { attr ->
                    img.attr(attr)
                        .split(",")
                        .firstOrNull()
                        ?.substringBefore(" ")
                        ?.takeIf { it.isNotBlank() }
                }?.let { normalizeUrl(it, mainUrl) }
        }
    }

    private fun parseEpisodes(doc: Document): List<Episode> {
        return doc.select(
            "a[href*=/episode/], a[href*=/episodes/], .episodios a[href], .episodes a[href], " +
                ".se-c a[href], .se-a a[href], .eplister a[href], .episode-list a[href], " +
                ".wp-content a[href*='episode'], a[href*='/play/']"
        ).mapNotNull { a ->
            val href = normalizeUrl(a.attr("href"), mainUrl)
            val label = a.text().cleanText().ifBlank { a.attr("title").cleanText() }
            if (!isValidContentUrl(href) || label.contains("Trailer", true)) return@mapNotNull null

            val season = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(label)?.groupValues?.get(1)?.toIntOrNull()
            val episode = Regex("""(?:Episode|Eps?\.?|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\b(\d{1,4})\b""").find(label)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(href) {
                this.name = label.ifBlank { "Episode ${episode ?: ""}".trim() }
                this.season = season
                this.episode = episode
            }
        }.distinctBy { it.data }
    }

    private fun isSeriesDoc(doc: Document, url: String, tags: List<String>): Boolean {
        val text = (doc.selectFirst(".sheader, .extra, .meta, .entry-content")?.text() ?: doc.text()).take(2000)
        return tags.any { it.equals("Serial TV", true) || it.equals("TV Show", true) } ||
            text.contains("TV Show", true) ||
            text.contains("Serial TV", true) ||
            text.contains("Episode", true) ||
            url.contains("/tv/", true) ||
            url.contains("/episode/", true)
    }

    private fun resolvePlayablePage(url: String, doc: Document): String {
        if (doc.selectFirst("iframe[src], video[src], source[src], [data-post][data-nume], .player, .player-area, #player") != null) {
            return url
        }

        doc.select("a[href*='/play/'], a[href*='?player='], a[href*='watch']").firstOrNull { a ->
            !a.text().contains("Trailer", true) && !a.attr("href").contains("youtube", true)
        }?.attr("href")?.let { return normalizeUrl(it, url) }

        return if (url.trimEnd('/').endsWith("/play")) url else url.trimEnd('/') + "/play/"
    }

    private suspend fun fetchDocument(pathOrUrl: String): Document {
        val firstUrl = normalizeUrl(pathOrUrl, mainUrl)
        runCatching { return app.get(firstUrl, headers = headers).document }

        val path = pathOnly(firstUrl)
        mirrors.forEach { mirror ->
            val candidate = mirror.trimEnd('/') + path
            runCatching { return app.get(candidate, headers = headers).document }
        }

        return app.get(firstUrl, headers = headers).document
    }

    private fun pagedUrl(path: String, page: Int): String {
        val normalized = if (path.startsWith("http", true)) path else mainUrl.trimEnd('/') + "/" + path.trimStart('/')
        if (page <= 1) return normalized
        return normalized.trimEnd('/') + "/page/$page/"
    }

    private fun normalizeUrl(raw: String, base: String): String {
        var url = raw.cleanEscaped().trim()
        if (url.isBlank() || url.startsWith("javascript:", true) || url.startsWith("#")) return ""
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return rootOf(base) + url
        if (!url.startsWith("http", true)) return rootOf(base) + "/" + url.trimStart('/')
        return url
    }

    private fun rootOf(url: String): String {
        return Regex("""https?://[^/]+""", RegexOption.IGNORE_CASE).find(url)?.value ?: mainUrl
    }

    private fun pathOnly(url: String): String {
        val path = url.substringAfter(rootOf(url), "/")
        return "/" + path.trimStart('/')
    }

    private fun firstImage(doc: Document): String? {
        return listOf(
            "meta[property=og:image]" to "content",
            ".poster img" to "src",
            ".sheader .poster img" to "src",
            "img.wp-post-image" to "src",
            ".gmr-movie-thumb img" to "src",
            "img" to "src"
        ).firstNotNullOfOrNull { (selector, attr) ->
            doc.selectFirst(selector)?.attr(attr)?.takeIf { it.isNotBlank() }?.let { normalizeUrl(it, mainUrl) }
        }
    }

    private fun cleanTitle(title: String): String {
        return title.cleanText()
            .replace(Regex("""(?i)^nonton\s+"""), "")
            .replace(Regex("""(?i)\s+(subtitle\s+indonesia|sub\s*indo|streaming|download).*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|')
    }

    private fun String.cleanText(): String {
        return this.cleanEscaped()
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun isValidContentUrl(url: String): Boolean {
        if (!url.startsWith("http", true)) return false
        if (isBadLink(url)) return false
        val path = pathOnly(url).lowercase().trimEnd('/') + "/"
        if (path in setOf("/", "/movies/", "/series/", "/nonton-drama/", "/privacy/", "/dmca/", "/report/", "/pasang-iklan/")) return false
        return !listOf(
            "/category/", "/country/", "/year/", "/tag/", "/genre/", "/quality/",
            "/wp-content/", "/wp-json/", "/feed/", "/privacy", "/dmca", "/report", "/pasang-iklan"
        ).any { url.contains(it, true) }
    }

    private fun isValidCandidate(url: String): Boolean {
        if (url.isBlank() || isBadLink(url)) return false
        return url.startsWith("http", true) && !url.contains("/wp-content/uploads/", true)
    }

    private fun isBadTitle(title: String): Boolean {
        val clean = title.cleanText()
        return clean.length < 2 ||
            clean.equals("Tonton Film", true) ||
            clean.equals("Trailer", true) ||
            clean.equals("Lihat Semua", true) ||
            clean.equals("Home", true) ||
            clean.equals("HD", true) ||
            clean.equals("HDCAM", true) ||
            clean.equals("HDTS", true)
    }

    private fun isBadLink(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be") ||
            lower.contains("facebook.com") || lower.contains("twitter.com") ||
            lower.contains("instagram.com") || lower.contains("t.me/") ||
            lower.contains("whatsapp") || lower.contains("doubleclick") ||
            lower.contains("googlesyndication") || lower.contains("/ads") ||
            lower.contains("/cdn-cgi/") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") ||
            lower.endsWith(".css") || lower.endsWith(".js")
    }

    private fun isDirectVideo(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") ||
            lower.contains(".webm") || lower.contains("videoplayback")
    }
}
