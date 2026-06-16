package com.sad25kag.Animexin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Animexin : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "Animexin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&order=update" to "Recently Updated",
        "anime/?status=ongoing&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie" to "Movies",
        "genres/action/" to "Action",
        "genres/adventure/" to "Adventure",
        "genres/demon/" to "Demon",
        "genres/fantasy/" to "Fantasy",
        "genres/historical/" to "Historical",
        "genres/martial-arts/" to "Martial Arts",
        "genres/romance/" to "Romance",
        "genres/supernatural/" to "Supernatural"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val link = buildPagedUrl(request.data, page)
        val document = app.get(link, headers = siteHeaders, referer = "$mainUrl/").documentLarge
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = document.select("a.next[href], li.next a[href], link[rel=next]").isNotEmpty()
        )
    }

    private fun buildPagedUrl(data: String, page: Int): String {
        val clean = data.trimStart('/')
        return if (clean.startsWith("genres/")) {
            "$mainUrl/${clean.trimEnd('/')}/page/$page/"
        } else {
            val base = "$mainUrl/$clean"
            val separator = if (base.contains("?")) "&" else "?"
            "$base${separator}page=$page"
        }
    }

    private fun inferTvType(title: String, href: String, cardText: String = ""): TvType {
        val haystack = "$title $href $cardText".lowercase()
        return if (haystack.contains("movie")) TvType.Movie else TvType.Anime
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val cardText = text()
        if (cardText.contains("Upcoming", true)) return null

        val anchor = selectFirst("div.bsx > a[href], a[href]") ?: return null
        val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (title.isBlank() || !isContentUrl(href)) return null

        val posterUrl = fixUrlNull(selectFirst("div.bsx > a img, img")?.getsrcAttribute().orEmpty())

        return newAnimeSearchResponse(title, href, inferTvType(title, href, cardText)) {
            this.posterUrl = posterUrl
        }
    }

    private fun isContentUrl(url: String): Boolean {
        val clean = url.substringBefore("?").trimEnd('/')
        if (!clean.startsWith(mainUrl)) return false
        val path = clean.removePrefix(mainUrl).trim('/')
        if (path.isBlank()) return false
        if (
            path.startsWith("genres/") ||
            path.startsWith("anime/") ||
            path.startsWith("tag/") ||
            path.startsWith("page/") ||
            path.startsWith("az-list") ||
            path.startsWith("release-date") ||
            path.startsWith("bookmark") ||
            path.startsWith("author/") ||
            path.startsWith("wp-")
        ) return false

        return !path.contains("/")
    }

    private fun sameContentUrl(first: String, second: String): Boolean {
        return first.substringBefore("?").trimEnd('/') == second.substringBefore("?").trimEnd('/')
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = linkedSetOf<SearchResponse>()
        val queryTokens = query.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 }

        fun SearchResponse.matchesQuery(): Boolean {
            if (queryTokens.isEmpty()) return name.contains(query, true)
            return queryTokens.all { token -> name.contains(token, true) }
        }

        val searchUrls = listOf(
            "$mainUrl/?s=$encodedQuery",
            "$mainUrl/page/$page/?s=$encodedQuery",
            "$mainUrl/anime/?s=$encodedQuery&page=$page"
        ).distinct()

        searchUrls.forEach { url ->
            runCatching {
                app.get(url, headers = siteHeaders, referer = "$mainUrl/").documentLarge
                    .select("div.listupd > article")
                    .mapNotNull { it.toSearchResult() }
                    .filter { it.matchesQuery() }
                    .forEach { results.add(it) }
            }
        }

        return results.toList().toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = siteHeaders, referer = "$mainUrl/").documentLarge
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.select("div.thumb img").attr("src").ifEmpty {
            document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
        }
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val infoText = document.selectFirst(".spe")?.text().orEmpty()
        val episodes = extractEpisodes(document, poster)
        val isMovie = infoText.contains("Movie", true)

        return if (!isMovie && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val movieData = extractWatchPage(document, url) ?: episodes.firstOrNull()?.data ?: url
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    private fun extractEpisodes(document: Document, poster: String): List<Episode> {
        val episodeRegex = Regex("(\\d+)")
        return document.select(
            "div.eplister > ul > li, .eplister li, .episodelist li"
        ).mapNotNull { info ->
            val anchor = info.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl, true) || !href.contains("episode", true) || !isContentUrl(href)) return@mapNotNull null

            val posterEpisode = info.selectFirst("a img, img")?.attr("src").orEmpty()
            val epText = info.selectFirst("div.epl-num, .epl-num, a span")?.text()?.ifBlank { anchor.text() } ?: anchor.text()
            val epnum = episodeRegex.find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("episode-?(\\d+)", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(href) {
                this.episode = epnum
                this.name = epnum?.let { "Episode $it" } ?: epText.ifBlank { "Episode" }
                this.posterUrl = posterEpisode.ifBlank { poster }
            }
        }
            .distinctBy { it.data.substringBefore("?").trimEnd('/') }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun extractWatchPage(document: Document, detailUrl: String): String? {
        val scopedAnchors = document.select(
            "div.eplister > ul > li a[href], .eplister li a[href], .episodelist li a[href], .bixbox.bxcl li a[href]"
        )
        val fallbackAnchors = if (scopedAnchors.isNotEmpty()) scopedAnchors else document.select(
            "article.post a[href], .postbody a[href], .entry-content a[href]"
        )

        return fallbackAnchors.asSequence()
            .mapNotNull { anchor -> fixUrlNull(anchor.attr("href")) }
            .filter { href -> href.startsWith(mainUrl, true) }
            .filter { href -> isContentUrl(href) && !sameContentUrl(href, detailUrl) }
            .distinct()
            .firstOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.takeIf { it.startsWith("http", true) } ?: fixUrl(data)
        val response = app.get(pageUrl, headers = siteHeaders, referer = "$mainUrl/")
        val document = response.document
        val candidates = linkedMapOf<String, String>()
        val directLinks = linkedMapOf<String, String>()
        val emitted = linkedSetOf<String>()
        var found = false

        val safeCallback: (ExtractorLink) -> Unit = { link ->
            if (emitted.add(link.url.substringBefore("#"))) {
                found = true
                callback(link)
            }
        }

        fun addCandidate(raw: String?, label: String?, baseUrl: String = pageUrl) {
            val normalized = normalizeUrl(raw, baseUrl) ?: return
            if (isNoiseUrl(normalized)) return
            if (isDirectMedia(normalized)) {
                directLinks.putIfAbsent(normalized, label.orEmpty())
            } else {
                candidates.putIfAbsent(normalized, label.orEmpty())
                addRumbleHls(normalized)?.let { hls -> directLinks.putIfAbsent(hls, label.orEmpty().ifBlank { "Rumble" }) }
            }
        }

        fun collectFromHtml(html: String, label: String?, baseUrl: String) {
            if (html.isBlank()) return
            val parsed = Jsoup.parse(html, baseUrl)
            parsed.select("iframe[src], iframe[data-src], embed[src], video[src], source[src]").forEach { element ->
                addCandidate(element.attr("src"), label, baseUrl)
                addCandidate(element.attr("data-src"), label, baseUrl)
            }
            collectUrlsFromText(html).forEach { addCandidate(it, label, baseUrl) }
        }

        document.select("iframe[src], iframe[data-src], embed[src], video[src], source[src]").forEach { element ->
            addCandidate(element.attr("src"), "Direct iframe", pageUrl)
            addCandidate(element.attr("data-src"), "Direct iframe", pageUrl)
        }

        document.select(".mobius option[value], #mobius option[value], select.mirror option[value], .mirror option[value]").forEach { option ->
            val label = option.text().cleanText().takeIf { it.isNotBlank() && !it.contains("Select", true) }
            val value = option.attr("value").trim()
            if (value.isBlank()) return@forEach

            decodeBase64Html(value)?.let { decoded -> collectFromHtml(decoded, label, pageUrl) }
            addCandidate(value, label, pageUrl)
        }

        collectAjaxPlayers(document, pageUrl).forEach { ajaxHtml -> collectFromHtml(ajaxHtml, "AJAX", pageUrl) }

        directLinks.forEach { (link, label) ->
            emitDirect(link, label, pageUrl, safeCallback)
        }

        candidates.forEach { (link, label) ->
            runCatching {
                loadExtractor(
                    link,
                    referer = pageUrl,
                    subtitleCallback = subtitleCallback,
                    callback = safeCallback
                )
            }
            if (!found && shouldScanExternalPlayer(link)) {
                runCatching {
                    app.get(
                        link,
                        headers = siteHeaders + mapOf(
                            "Referer" to pageUrl,
                            "Origin" to originOf(pageUrl)
                        ),
                        referer = pageUrl
                    ).text
                }.getOrNull()?.let { playerHtml ->
                    val nestedDirect = linkedMapOf<String, String>()
                    val nestedCandidates = linkedMapOf<String, String>()
                    collectUrlsFromText(playerHtml).forEach { raw ->
                        val url = normalizeUrl(raw, link)
                        if (url != null && !isNoiseUrl(url)) {
                            if (isDirectMedia(url)) nestedDirect.putIfAbsent(url, label) else nestedCandidates.putIfAbsent(url, label)
                        }
                    }
                    Jsoup.parse(playerHtml, link).select("iframe[src], iframe[data-src], embed[src], video[src], source[src]").forEach { element ->
                        listOf(element.attr("src"), element.attr("data-src")).forEach { raw ->
                            val url = normalizeUrl(raw, link)
                            if (url != null && !isNoiseUrl(url)) {
                                if (isDirectMedia(url)) nestedDirect.putIfAbsent(url, label) else nestedCandidates.putIfAbsent(url, label)
                            }
                        }
                    }
                    nestedDirect.forEach { (nested, nestedLabel) -> emitDirect(nested, nestedLabel, link, safeCallback) }
                    nestedCandidates.forEach { (nested, _) ->
                        runCatching {
                            loadExtractor(
                                nested,
                                referer = link,
                                subtitleCallback = subtitleCallback,
                                callback = safeCallback
                            )
                        }
                    }
                }
            }
        }

        return found
    }

    private suspend fun collectAjaxPlayers(document: Document, referer: String): List<String> {
        val requests = linkedSetOf<String>()
        document.select("[data-post][data-nume], [data-id][data-nume], [data-post][data-server], [data-episode][data-server]").forEach { element ->
            val post = element.attr("data-post").ifBlank { element.attr("data-id") }.ifBlank { element.attr("data-episode") }
            val nume = element.attr("data-nume").ifBlank { element.attr("data-server") }.ifBlank { "1" }
            val type = element.attr("data-type").ifBlank { "tv" }
            if (post.isNotBlank()) requests.add("$post|$nume|$type")
        }

        val actions = listOf("doo_player_ajax", "player_ajax", "ts_player_ajax")
        return requests.take(12).flatMap { request ->
            val parts = request.split("|")
            val post = parts.getOrNull(0).orEmpty()
            val nume = parts.getOrNull(1).orEmpty().ifBlank { "1" }
            val type = parts.getOrNull(2).orEmpty().ifBlank { "tv" }
            actions.mapNotNull { action ->
                runCatching {
                    app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to action,
                            "post" to post,
                            "nume" to nume,
                            "type" to type
                        ),
                        headers = siteHeaders + mapOf(
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to mainUrl,
                            "Referer" to referer
                        ),
                        referer = referer
                    ).text
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }
    }

    private fun emitDirect(
        link: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val linkType = if (link.contains("m3u8", true) || link.contains("mpegurl", true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }
        callback(
            newExtractorLink(name, listOf(name, label).filter { it.isNotBlank() }.joinToString(" - "), link, linkType) {
                this.referer = referer
                this.quality = getQualityFromName(label.ifBlank { link })
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to originOf(referer),
                    "Range" to "bytes=0-"
                )
            }
        )
    }

    private fun decodeBase64Html(value: String): String? {
        val clean = value.trim().replace("\n", "").replace("\r", "")
        if (clean.length < 12 || !clean.matches(Regex("^[A-Za-z0-9+/=]+$"))) return null
        return runCatching { base64Decode(clean) }
            .getOrNull()
            ?.takeIf { decoded -> decoded.contains("iframe", true) || decoded.contains("http", true) || decoded.contains("video", true) }
    }

    private fun collectUrlsFromText(text: String): List<String> {
        val decoded = decodeText(text)
        val urls = linkedSetOf<String>()
        Regex("""https?:\\?/\\?/[^'\"<>()\\\s]+""", RegexOption.IGNORE_CASE)
            .findAll(decoded)
            .map { it.value.replace("\\/", "/") }
            .forEach { urls.add(it) }
        Regex("""https?://[^'"<>()\s]+""", RegexOption.IGNORE_CASE)
            .findAll(decoded)
            .map { it.value.trimEnd(',', ';', ')') }
            .forEach { urls.add(it) }
        Regex("""(?<!:)//[A-Za-z0-9][^'"<>()\s]+""", RegexOption.IGNORE_CASE)
            .findAll(decoded)
            .map { it.value.trimEnd(',', ';', ')') }
            .forEach { urls.add(it) }
        return urls.toList()
    }

    private fun normalizeUrl(raw: String?, baseUrl: String = mainUrl): String? {
        var value = raw?.trim()
            ?.trim('"', '\'', '`')
            ?.replace("&amp;", "&")
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?.replace("\\u002F", "/")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        value = decodeText(value)
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        if (value.startsWith("/")) return originOf(baseUrl) + value
        if (!value.contains("/")) return null
        return runCatching { URI(baseUrl).resolve(value).toString() }.getOrNull()?.takeIf { it.startsWith("http", true) }
    }

    private fun decodeText(raw: String): String {
        var value = raw
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\\/", "/")
        repeat(2) {
            value = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }
        return value.trim()
    }

    private fun addRumbleHls(url: String): String? {
        val id = Regex("""rumble\.com/embed/v([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        return "https://rumble.com/hls-vod/$id/playlist.m3u8?u=0&b=0"
    }

    private fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase().substringBefore("#")
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains(".mkv") ||
            lower.contains("videoplayback") ||
            lower.contains("r_file=chunklist.m3u8") ||
            lower.contains("r_type=application%2fvnd.apple.mpegurl") ||
            lower.contains("/hls-vod/") ||
            lower.contains("/manifest/video/") ||
            lower.contains("/cdn/manifest/")
    }

    private fun shouldScanExternalPlayer(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http") || lower.startsWith(mainUrl.lowercase()) || isNoiseUrl(lower) || isDirectMedia(lower)) return false
        return lower.contains("/embed") ||
            lower.contains("player") ||
            lower.contains("videoembed") ||
            lower.contains("rumble.com") ||
            lower.contains("odysee.com") ||
            lower.contains("d.tube")
    }

    private fun isNoiseUrl(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            "googletagmanager.com",
            "google-analytics.com",
            "doubleclick.net",
            "googlesyndication.com",
            "googleapis.com",
            "gstatic.com",
            "2mdn.net",
            "histats.com",
            "dtscout.com",
            "dtscdn.com",
            "dtssrv.com",
            "mrktmtrcs.net",
            "yandex.ru",
            "chatbro.com",
            "cloudflare-ech.com",
            "gravatar.com",
            "clientservices.googleapis.com",
            "safebrowsing.googleapis.com",
            "update.googleapis.com"
        ).any { lower.contains(it) }
    }

    private fun originOf(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(mainUrl)
    }

    private fun String.cleanText(): String = Jsoup.parse(this).text()
        .replace("\u00a0", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun Element.getsrcAttribute(): String {
        val src = attr("src")
        val dataSrc = attr("data-src")
        return src.takeIf { it.startsWith("http") } ?: dataSrc.takeIf { it.startsWith("http") } ?: ""
    }
}
