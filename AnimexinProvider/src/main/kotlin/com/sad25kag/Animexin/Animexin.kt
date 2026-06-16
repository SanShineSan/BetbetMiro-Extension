package com.sad25kag.Animexin

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder

class Animexin : MainAPI() {
    override var mainUrl              = "https://animexin.dev"
    override var name                 = "Animexin"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&order=update" to "Recently Updated",
        "anime/?status=ongoing&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&page=" to "Movies",
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
        val link = if (request.data.contains("genres")) {
            "$mainUrl/${request.data}page/$page"
        } else {
            "$mainUrl/${request.data}&page=$page"
        }

        val document = app.get(link).documentLarge
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = document.select("a.next, li.next a").isNotEmpty()
        )
    }


    private fun inferTvType(title: String, href: String, cardText: String = ""): TvType {
        val haystack = "$title $href $cardText".lowercase()
        return if (
            haystack.contains("/anime/?type=movie") ||
            haystack.contains(" movie") ||
            haystack.contains("movie ")
        ) {
            TvType.Movie
        } else {
            TvType.Anime
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val cardText = text()
        if (cardText.contains("Upcoming", true)) return null

        val anchor = selectFirst("div.bsx > a, a[href]") ?: return null
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
            path.startsWith("bookmark")
        ) return false

        return path.contains("-episode-", true) || !path.contains("/")
    }

    private fun sameContentUrl(first: String, second: String): Boolean {
        return first.substringBefore("?").trimEnd('/') == second.substringBefore("?").trimEnd('/')
    }

    private fun extractEpisodeList(document: org.jsoup.nodes.Document, poster: String): List<Episode> {
        val episodeRegex = Regex("(\\d+)")
        return document.select(
            "div.eplister > ul > li, .eplister li, .episodelist li, ul li"
        ).mapNotNull { info ->
            val anchor = info.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            if (!href.contains("-episode-", true)) return@mapNotNull null

            val posterEpisode = info.selectFirst("a img, img")?.attr("src").orEmpty()
            val epText = info.selectFirst("div.epl-num, .epl-num, a span")?.text()?.ifBlank { anchor.text() } ?: anchor.text()
            val epnum = episodeRegex.find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("episode-(\\d+)", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(href) {
                this.episode = epnum
                this.name = epnum?.let { "Episode $it" } ?: epText
                this.posterUrl = posterEpisode.ifBlank { poster }
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun extractMoviePlayData(document: org.jsoup.nodes.Document, detailUrl: String): String {
        val detailSlug = detailUrl.substringBefore("?").trimEnd('/').substringAfterLast('/').lowercase()
        val scopedAnchors = document.select(
            "div.eplister a[href], .eplister a[href], .episodelist a[href], .episodios a[href], .epcheck a[href], .bixbox.bxcl a[href], .bxcl a[href], article.post a[href], .postbody a[href]"
        )

        val candidates = scopedAnchors.mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl, true)) return@mapNotNull null
            if (sameContentUrl(href, detailUrl) || !isContentUrl(href)) return@mapNotNull null

            val text = listOf(anchor.text(), anchor.parent()?.text(), href)
                .joinToString(" ") { it.orEmpty() }
                .lowercase()
            val score = listOf(
                text.contains("episode movie"),
                text.contains("eps movie"),
                text.contains(" movie"),
                text.contains("subtitle"),
                text.contains("sub"),
                text.contains(detailSlug)
            ).count { it }

            if (score <= 0) return@mapNotNull null
            score to href
        }

        return candidates.sortedByDescending { it.first }
            .map { it.second }
            .distinct()
            .firstOrNull()
            ?: detailUrl
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
            "$mainUrl/page/$page/?s=$encodedQuery"
        ).distinct()

        searchUrls.forEach { url ->
            runCatching {
                app.get(url).documentLarge
                    .select("div.listupd > article")
                    .mapNotNull { it.toSearchResult() }
                    .filter { it.matchesQuery() }
                    .forEach { results.add(it) }
            }
        }

        if (results.isEmpty()) {
            val fallbackPages = if (page <= 1) 1..3 else page..page
            fallbackPages.forEach { fallbackPage ->
                runCatching {
                    app.get("$mainUrl/anime/?status=&order=latest&page=$fallbackPage").documentLarge
                        .select("div.listupd > article")
                        .mapNotNull { it.toSearchResult() }
                        .filter { it.matchesQuery() }
                        .forEach { results.add(it) }
                }
            }
        }

        return results.toList().toNewSearchResponseList()
    }

    @Suppress("SuspiciousIndentation")
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.select("div.thumb img").attr("src").ifEmpty {
            document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
        }
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().orEmpty()
        val tvtag = if (type.contains("Movie", true)) TvType.Movie else TvType.TvSeries

        return if (tvtag == TvType.TvSeries) {
            val episodes = extractEpisodeList(document, poster)

            if (episodes.isEmpty()) {
                throw ErrorLoadingException("No episodes found")
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val movieData = extractMoviePlayData(document, url)
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.takeIf { it.startsWith("http", true) } ?: fixUrl(data)
        val visitedPages = linkedSetOf<String>()
        val candidates = linkedSetOf<String>()
        val candidateReferers = linkedMapOf<String, String>()
        val ajaxRequests = linkedSetOf<String>()
        var found = false

        val safeCallback: (ExtractorLink) -> Unit = { link ->
            found = true
            callback(link)
        }

        fun originOf(url: String): String {
            return Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.getOrNull(1) ?: mainUrl
        }

        fun isDirectMedia(url: String): Boolean {
            val lower = url.lowercase().substringBefore("#")
            return lower.contains(".m3u8") ||
                lower.contains(".mp4") ||
                lower.contains(".webm") ||
                lower.contains(".mkv") ||
                lower.contains("videoplayback") ||
                lower.contains("r_file=chunklist.m3u8") ||
                lower.contains("r_type=video") ||
                lower.contains("r_type=application%2fvnd.apple.mpegurl") ||
                lower.contains("/hls-vod/") ||
                lower.contains("/manifest/video/") ||
                lower.contains("/cdn/manifest/")
        }

        fun shouldFollowInternal(url: String): Boolean {
            val lower = url.lowercase()
            if (!lower.startsWith(mainUrl.lowercase())) return false
            return lower.contains("player") ||
                lower.contains("embed") ||
                lower.contains("ajax") ||
                lower.contains("wp-admin") ||
                lower.contains("wp-content") ||
                lower.contains("stream") ||
                lower.contains("subtitle") ||
                lower.contains("-sub") ||
                lower.contains("episode") ||
                lower.contains("movie")
        }

        fun isNoiseUrl(url: String): Boolean {
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

        fun isExternalEmbedOrPlayer(url: String): Boolean {
            val lower = url.lowercase()
            if (!lower.startsWith("http") || lower.startsWith(mainUrl.lowercase()) || isNoiseUrl(lower)) return false
            return lower.contains("/embed") ||
                lower.contains("player") ||
                lower.contains("videoembed") ||
                lower.contains("/hls-vod/") ||
                lower.contains("playlist.m3u8") ||
                lower.contains("manifest") ||
                lower.contains("/video/")
        }

        fun isPlaybackCandidate(url: String, trusted: Boolean): Boolean {
            val lower = url.lowercase()
            if (!lower.startsWith("http") || isNoiseUrl(lower)) return false
            if (isDirectMedia(lower)) return true
            if (shouldFollowInternal(lower)) return true
            if (trusted && !lower.startsWith(mainUrl.lowercase())) return true
            return isExternalEmbedOrPlayer(lower)
        }

        fun shouldScanPage(url: String): Boolean {
            val lower = url.lowercase()
            if (isDirectMedia(lower) || isNoiseUrl(lower)) return false
            return shouldFollowInternal(lower) || isExternalEmbedOrPlayer(lower)
        }

        fun normalizeUrl(raw: String?, baseUrl: String = mainUrl): String? {
            var url = raw?.trim()
                ?.trim('"', '\'', '`')
                ?.replace("&amp;", "&")
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
                ?.replace("\\u002F", "/")
                ?.replace("%3A", ":")
                ?.replace("%2F", "/")
                ?.substringBefore("\\\"")
                ?.substringBefore("\"")
                ?.substringBefore("'")
                ?.takeIf { it.isNotBlank() }
                ?: return null

            if (url.startsWith("//")) url = "https:$url"
            if (url.startsWith("/")) url = originOf(baseUrl) + url
            if (!url.startsWith("http", true)) return null
            return url
        }

        fun rememberCandidate(url: String, referer: String, trusted: Boolean = false) {
            if (!isPlaybackCandidate(url, trusted)) return
            candidates.add(url)
            candidateReferers.putIfAbsent(url, referer)
        }

        fun addRumbleHlsFromEmbed(url: String) {
            val id = Regex("""rumble\.com/embed/v([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?: return
            val playlist = "https://rumble.com/hls-vod/$id/playlist.m3u8?u=0&b=0"
            rememberCandidate(playlist, "https://rumble.com/", true)
        }

        fun decodeCandidate(value: String): String? {
            val cleaned = value.trim().replace("\n", "").replace("\r", "")
            if (cleaned.length < 8 || !cleaned.matches(Regex("^[A-Za-z0-9+/=_%.-]+$"))) return null
            return runCatching { base64Decode(cleaned) }.getOrNull()
                ?.takeIf { decoded ->
                    decoded != value && (
                        decoded.contains("http", true) ||
                            decoded.contains("iframe", true) ||
                            decoded.contains("video", true) ||
                            decoded.contains("source", true)
                        )
                }
        }

        lateinit var addCandidate: (String?, String, Boolean) -> Unit

        fun addFromText(text: String?, referer: String, trusted: Boolean = false) {
            val body = text ?: return
            listOf(
                Regex("""https?://[^"'\\< >\n\r\t]+"""),
                Regex("""https?:\\/\\/[^"'\\< >\n\r\t]+"""),
                Regex("""(?<!:)//[A-Za-z0-9][^"'\\< >\n\r\t]+"""),
                Regex("""(?i)(?:src|data-src|data-video|data-url|data-file|data-link|data-embed|data-player|data-content|embed_url|file|url)["']?\s*[:=]\s*["']([^"']+)["']""")
            ).forEach { regex ->
                regex.findAll(body).forEach { match ->
                    val value = match.groupValues.getOrNull(1).takeIf { !it.isNullOrBlank() } ?: match.value
                    addCandidate(value, referer, trusted)
                }
            }
        }

        fun addAjaxRequest(post: String?, nume: String?, type: String?) {
            val cleanPost = post?.trim()?.takeIf { it.isNotBlank() } ?: return
            val cleanNume = nume?.trim()?.takeIf { it.isNotBlank() } ?: "1"
            val cleanType = type?.trim()?.takeIf { it.isNotBlank() }
            val types = if (cleanType != null) listOf(cleanType) else listOf("movie", "tv", "episode")
            types.forEach { ajaxRequests.add("$cleanPost|$cleanNume|$it") }
        }

        fun addFromDocument(doc: org.jsoup.nodes.Document, referer: String, trusted: Boolean = true) {
            doc.select("iframe[src], iframe[data-src], embed[src], video[src], source[src]").forEach { element ->
                addCandidate(element.attr("src"), referer, true)
                addCandidate(element.attr("data-src"), referer, true)
            }

            doc.select("a[href]").forEach { element ->
                addCandidate(element.attr("href"), referer, false)
            }

            doc.select("[data-src], [data-video], [data-url], [data-file], [data-link], [data-embed], [data-player], [data-content], [data-hash]").forEach { element ->
                addCandidate(element.attr("data-src"), referer, trusted)
                addCandidate(element.attr("data-video"), referer, trusted)
                addCandidate(element.attr("data-url"), referer, trusted)
                addCandidate(element.attr("data-file"), referer, trusted)
                addCandidate(element.attr("data-link"), referer, trusted)
                addCandidate(element.attr("data-embed"), referer, trusted)
                addCandidate(element.attr("data-player"), referer, trusted)
                addCandidate(element.attr("data-content"), referer, trusted)
                addCandidate(element.attr("data-hash"), referer, trusted)
            }

            doc.select(".mobius option[value], #mobius option[value], select.mirror option[value], select option[value], option[value]").forEach { option ->
                addCandidate(option.attr("value"), referer, true)
                addCandidate(option.attr("data-src"), referer, true)
                addCandidate(option.attr("data-url"), referer, true)
                addCandidate(option.attr("data-file"), referer, true)
                addAjaxRequest(
                    option.attr("data-post").ifBlank { option.attr("data-id") },
                    option.attr("data-nume").ifBlank { option.attr("data-server") }.ifBlank { option.attr("data-index") },
                    option.attr("data-type")
                )
            }

            doc.select("[data-post][data-nume], [data-id][data-nume], [data-post][data-server], [data-episode][data-server], .dooplay_player_option, .server_option, .mirror li, .mobius li").forEach { element ->
                val post = element.attr("data-post").ifBlank { element.attr("data-id") }.ifBlank { element.attr("data-episode") }
                val nume = element.attr("data-nume").ifBlank { element.attr("data-server") }.ifBlank { element.attr("data-index") }
                val type = element.attr("data-type")
                addAjaxRequest(post, nume, type)
            }
        }

        addCandidate = candidate@{ raw: String?, referer: String, trusted: Boolean ->
            val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return@candidate

            if (value.contains("<iframe", true) || value.contains("<video", true) || value.contains("<source", true)) {
                val parsed = Jsoup.parse(value)
                addFromDocument(parsed, referer, true)
                addFromText(value, referer, true)
            }

            decodeCandidate(value)?.let { decoded -> addCandidate(decoded, referer, true) }

            normalizeUrl(value, referer)?.let { url ->
                rememberCandidate(url, referer, trusted)
                addRumbleHlsFromEmbed(url)
            }
        }

        suspend fun scanPage(url: String, referer: String, trusted: Boolean = false) {
            val normalized = normalizeUrl(url, referer) ?: return
            if (isDirectMedia(normalized) || !visitedPages.add(normalized)) return

            val responseText = runCatching {
                app.get(
                    normalized,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Referer" to referer,
                        "Origin" to originOf(referer)
                    ),
                    referer = referer
                ).text
            }.getOrNull() ?: return

            val parsed = Jsoup.parse(responseText, normalized)
            addFromDocument(parsed, normalized, trusted)
            addFromText(responseText, normalized, false)
        }

        suspend fun scanAjaxPlayers(referer: String) {
            val actions = listOf("doo_player_ajax", "player_ajax", "ts_player_ajax")
            ajaxRequests.toList().take(36).forEach { raw ->
                val parts = raw.split("|")
                val post = parts.getOrNull(0).orEmpty()
                val nume = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "1"
                val type = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "movie"
                actions.forEach { action ->
                    val responseText = runCatching {
                        app.post(
                            "$mainUrl/wp-admin/admin-ajax.php",
                            data = mapOf(
                                "action" to action,
                                "post" to post,
                                "nume" to nume,
                                "type" to type
                            ),
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "application/json, text/javascript, */*; q=0.01",
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                "X-Requested-With" to "XMLHttpRequest",
                                "Origin" to mainUrl,
                                "Referer" to referer
                            ),
                            referer = referer
                        ).text
                    }.getOrNull().orEmpty()

                    if (responseText.isNotBlank()) {
                        addCandidate(responseText, referer, true)
                        addFromText(responseText, referer, true)
                        Jsoup.parse(responseText).let { addFromDocument(it, referer, true) }
                    }
                }
            }
        }

        scanPage(pageUrl, mainUrl, true)

        candidates.toList()
            .filter { shouldFollowInternal(it) }
            .filterNot { it.substringBefore("?").trimEnd('/') == pageUrl.substringBefore("?").trimEnd('/') }
            .take(10)
            .forEach { internalPlayer -> scanPage(internalPlayer, pageUrl, true) }

        scanAjaxPlayers(pageUrl)

        candidates.toList()
            .filter { shouldScanPage(it) }
            .filterNot { visitedPages.contains(it.substringBefore("?").trimEnd('/')) }
            .take(16)
            .forEach { player -> scanPage(player, candidateReferers[player] ?: pageUrl, true) }

        scanAjaxPlayers(pageUrl)

        candidates.toList()
            .filter { isDirectMedia(it) }
            .distinct()
            .forEach { link ->
                val referer = candidateReferers[link] ?: pageUrl
                val linkType = if (link.contains("m3u8", true) || link.contains("mpegurl", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(
                    newExtractorLink(name, name, link, linkType) {
                        this.referer = referer
                        this.quality = getQualityFromName(link)
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to referer,
                            "Origin" to originOf(referer),
                            "Range" to "bytes=0-"
                        )
                    }
                )
                found = true
            }

        candidates.toList()
            .filterNot { isDirectMedia(it) }
            .filter { isPlaybackCandidate(it, true) }
            .distinct()
            .forEach { link ->
                runCatching {
                    loadExtractor(
                        link,
                        referer = candidateReferers[link] ?: pageUrl,
                        subtitleCallback = subtitleCallback,
                        callback = safeCallback
                    )
                }
            }

        return found
    }

    private fun Element.getsrcAttribute(): String {
        val src = attr("src")
        val dataSrc = attr("data-src")
        return src.takeIf { it.startsWith("http") } ?: dataSrc.takeIf { it.startsWith("http") } ?: ""
    }
}
