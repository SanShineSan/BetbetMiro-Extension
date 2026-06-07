package com.sad25kag.mynimeku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MynimekuProvider : MainAPI() {
    override var mainUrl = "https://www.mynimeku.com"
    override var name = "Mynimeku"
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
        "latest-series/" to "Series Update",
        "full-list/mix/s%3Aon-going~t%3ABD%2CLA%2CMOVIE%2CMUSIC%2CONA%2COVA%2CSPECIAL%2CTV/" to "On-Going",
        "full-list/mix/s%3Acompleted~t%3ABD%2CLA%2CMOVIE%2CMUSIC%2CONA%2COVA%2CSPECIAL%2CTV/" to "Completed",
        "full-list/mix/t%3ATV/" to "TV",
        "full-list/mix/t%3AMOVIE/" to "Movie",
        "full-list/mix/t%3AONA/" to "ONA",
        "full-list/mix/t%3AOVA/" to "OVA",
        "full-list/mix/t%3ASPECIAL/" to "Special",
        "genre/action/" to "Action",
        "genre/adventure/" to "Adventure",
        "genre/comedy/" to "Comedy",
        "genre/drama/" to "Drama",
        "genre/fantasy/" to "Fantasy",
        "genre/romance/" to "Romance",
        "genre/school/" to "School",
        "genre/sci-fi/" to "Sci-Fi",
        "genre/shounen/" to "Shounen",
        "genre/slice-of-life/" to "Slice of Life",
        "genre/supernatural/" to "Supernatural"
    )

    private data class CardData(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType,
        val episode: Int?
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = buildPageUrl(request.data, page)
        val document = app.get(pageUrl, headers = defaultHeaders()).document

        val items = collectCards(document)
            .distinctBy { it.url }
            .map { it.toSearchResponse() }

        if (items.isEmpty()) throw ErrorLoadingException("Mynimeku category cards kosong")

        val hasNext = document.selectFirst(
            "a.next, a[rel=next], .pagination a[href*='/page/${page + 1}/'], " +
                "nav a[href*='/page/${page + 1}/'], a[href*='/page/${page + 1}/']"
        ) != null

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

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

        val normalSearch = parseSearchPage("$mainUrl/?s=$encodedQuery")
        if (normalSearch.isNotEmpty()) return normalSearch.map { it.toSearchResponse() }

        val slug = query
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        val directHits = listOf(
            "$mainUrl/anime/$slug/",
            "$mainUrl/series/$slug/"
        ).mapNotNull { directUrl ->
            runCatching {
                val document = app.get(directUrl, headers = defaultHeaders()).document
                val title = document.selectFirst("h1, h1.entry-title, .entry-title, .post-title")
                    ?.text()
                    ?.trim()
                    .orEmpty()
                if (title.isBlank()) null else CardData(
                    title = cleanTitle(title),
                    url = directUrl,
                    poster = document.selectFirst("article img, .poster img, .thumb img, .entry-content img, img[alt]")
                        ?.imageAttr()
                        ?.let { normalizeUrlOrNull(it, directUrl) },
                    type = detectType(directUrl, title),
                    episode = null
                )
            }.getOrNull()
        }

        return directHits.distinctBy { it.url }.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeUrl(url)
        val document = app.get(fixedUrl, headers = defaultHeaders()).document

        val rawTitle = document.selectFirst("h1, h1.entry-title, .entry-title, .post-title, .series-title")
            ?.text()
            ?.trim()
            .orEmpty()
            .ifBlank {
                document.title()
                    .substringBefore(" - MyNimeku")
                    .substringBefore(" – MyNimeku")
                    .substringBefore(" | MyNimeku")
                    .trim()
            }

        val title = cleanTitle(rawTitle).ifBlank {
            throw ErrorLoadingException("Judul Mynimeku tidak ditemukan")
        }

        val poster = document.selectFirst(
            "article img[alt*='${title.take(24)}'], .poster img, .thumb img, .series-poster img, " +
                ".entry-content img, img[alt='$title'], img[alt]"
        )?.imageAttr()?.let { normalizeUrlOrNull(it, fixedUrl) }

        val description = parseDescription(document)
        val tags = parseTags(document)
        val year = parseYear(document)
        val status = parseStatus(document)
        val type = detectType(fixedUrl, rawTitle, document.text())
        val episodes = parseEpisodeList(document, fixedUrl)
        val recommendations = collectRecommendations(document)
            .distinctBy { it.url }
            .map { it.toSearchResponse() }

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.year = year
                showStatus = status
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newMovieLoadResponse(title, fixedUrl, TvType.AnimeMovie, fixedUrl) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.year = year
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
        val requestData = normalizeUrl(data)
        val emittedKeys = linkedSetOf<String>()
        var emitted = false

        fun callbackOnce(link: ExtractorLink) {
            val key = "${link.source.lowercase()}|${canonicalLink(link.url)}"
            if (emittedKeys.add(key)) {
                emitted = true
                callback(link)
            }
        }

        suspend fun processCandidate(
            raw: String?,
            baseUrl: String = requestData,
            referer: String = requestData,
            qualityHint: String = raw.orEmpty()
        ) {
            val url = resolveUrl(raw, baseUrl) ?: return
            if (!isPlayerCandidate(url)) return

            when {
                isMyPlayerku(url) -> resolveMyPlayerku(url, referer, subtitleCallback, ::callbackOnce)
                isGoogleDriveMedia(url) -> emitGoogleDrive(url, qualityHint, ::callbackOnce)
                isDirectMedia(url) -> emitDirect(url, referer, ::callbackOnce)
                else -> runCatching {
                    loadExtractor(url, referer, subtitleCallback, ::callbackOnce)
                }
            }
        }

        val page = runCatching {
            app.get(
                requestData,
                referer = mainUrl,
                headers = defaultHeaders(mainUrl),
                timeout = 20L
            ).document
        }.getOrNull()

        if (page != null) {
            collectPlayerCandidates(page).forEach { processCandidate(it, requestData, requestData) }
        }

        processCandidate(requestData, mainUrl, mainUrl)
        return emitted
    }

    private suspend fun resolveMyPlayerku(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(
                playerUrl,
                referer = mainUrl,
                headers = defaultHeaders(mainUrl),
                timeout = 20L
            )
        }.getOrNull() ?: return false

        val html = response.text
        val playerTitle = Regex("""(?is)<title>(.*?)</title>""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { Jsoup.parse(it).text().trim() }
            ?.takeIf { it.isNotBlank() }
            ?: playerUrl

        var emitted = false
        collectMediaCandidatesFromText(html).forEach { candidate ->
            val resolved = resolveUrl(candidate, playerUrl) ?: return@forEach
            when {
                isGoogleDriveMedia(resolved) -> {
                    emitGoogleDrive(resolved, playerTitle, callback)
                    emitted = true
                }
                isDirectMedia(resolved) -> {
                    emitDirect(resolved, playerUrl, callback)
                    emitted = true
                }
                isPlayerCandidate(resolved) && !canonicalLink(resolved).equals(canonicalLink(playerUrl), true) -> {
                    runCatching {
                        loadExtractor(resolved, referer, subtitleCallback, callback)
                        emitted = true
                    }
                }
            }
        }

        return emitted
    }

    private fun collectMediaCandidatesFromText(raw: String): List<String> {
        val candidates = linkedSetOf<String>()
        val decodedBlocks = mutableListOf(raw, decodeJsEscapes(raw))
        unpackPackerPayloads(raw).forEach { decodedBlocks.add(it) }

        decodedBlocks.forEach { block ->
            Regex("""https?:\\?/\\?/[^"'<>\s]+""")
                .findAll(block)
                .forEach { candidates.add(it.value) }

            Regex("""(?i)["'](?:file|url|src|source)["']\s*:\s*["']([^"']+)["']""")
                .findAll(block)
                .forEach { candidates.add(it.groupValues[1]) }

            Regex("""(?i)(?:file|url|src|source)\s*[:=]\s*["']([^"']+)["']""")
                .findAll(block)
                .forEach { candidates.add(it.groupValues[1]) }

            Regex("""(?i)<(?:iframe|source|video|embed)[^>]+(?:src|data-src)=['"]([^'"]+)['"]""")
                .findAll(block)
                .forEach { candidates.add(it.groupValues[1]) }
        }

        return candidates
            .map { cleanCandidate(it) }
            .filter { it.isNotBlank() && !isBadUrl(it) }
            .distinct()
    }

    private fun unpackPackerPayloads(raw: String): List<String> {
        val regex = Regex(
            """eval\(function\(p,a,c,k,e,d\).*?\}\('((?:\\'|[^'])*)',(\d+),(\d+),'([^']*)'\.split\('\|'\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        return regex.findAll(raw).mapNotNull { match ->
            val payload = decodeJsEscapes(match.groupValues[1])
            val radix = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val count = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val words = match.groupValues[4].split("|")
            var output = payload

            for (index in count - 1 downTo 0) {
                val replacement = words.getOrNull(index).orEmpty()
                if (replacement.isBlank()) continue
                val encoded = intToRadix(index, radix)
                output = Regex("""\b${Regex.escape(encoded)}\b""").replace(output, replacement)
            }

            decodeJsEscapes(output)
        }.toList()
    }

    private fun intToRadix(value: Int, radix: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (value == 0) return "0"
        var number = value
        val output = StringBuilder()
        val safeRadix = radix.coerceIn(2, chars.length)

        while (number > 0) {
            output.insert(0, chars[number % safeRadix])
            number /= safeRadix
        }

        return output.toString()
    }

    private fun decodeJsEscapes(input: String): String {
        var output = input
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }

        return output
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
    }

    private fun isMyPlayerku(url: String): Boolean {
        return runCatching { URI(url).host.orEmpty().equals("players.myplayerku.my.id", true) }
            .getOrDefault(false)
    }

    private fun isGoogleDriveMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("www.googleapis.com/drive/v2/files/") &&
            lower.contains("alt=media")
    }

    private suspend fun emitGoogleDrive(
        link: String,
        qualityHint: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isGoogleDriveMedia(link)) return false

        callback(
            newExtractorLink(
                source = "MyPlayerku Google Drive",
                name = "MyPlayerku Google Drive",
                url = link,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = ""
                this.quality = getQualityFromName(qualityHint).takeIf { it != Qualities.Unknown.value }
                    ?: when {
                        qualityHint.contains("1080", true) -> Qualities.P1080.value
                        qualityHint.contains("720", true) -> Qualities.P720.value
                        qualityHint.contains("480", true) -> Qualities.P480.value
                        qualityHint.contains("360", true) -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*"
                )
            }
        )

        return true
    }

    private fun buildPageUrl(data: String, page: Int): String {
        val firstPage = normalizeUrl(data.trim())
        if (page <= 1) return firstPage
        val base = firstPage.substringBefore("?").trimEnd('/')
        val query = firstPage.substringAfter("?", "")
        return if (query.isBlank() || query == firstPage) {
            "$base/page/$page/"
        } else {
            "$base/page/$page/?$query"
        }
    }

    private fun collectCards(document: Document): List<CardData> {
        val selectors = listOf(
            "article",
            "div[class*=anime]",
            "div[class*=series]",
            "div[class*=item]",
            "div[class*=card]",
            "div[class*=post]",
            "a[href*='/anime/']",
            "a[href*='/series/']",
            "a[href*='/episode/']"
        ).joinToString(", ")

        return document.select(selectors)
            .mapNotNull { it.toCardData() }
            .filterNot { isNavigationTitle(it.title) }
            .distinctBy { it.url }
    }

    private fun collectRecommendations(document: Document): List<CardData> {
        return document.select(
            "article a[href*='/anime/'], article a[href*='/series/'], " +
                "a[href*='/anime/'], a[href*='/series/']"
        ).mapNotNull { it.toCardData() }
            .filterNot { isNavigationTitle(it.title) }
    }

    private fun Element.toCardData(): CardData? {
        val href = when {
            tagName().equals("a", true) -> attr("abs:href").ifBlank { attr("href") }
            else -> selectFirst("a[href*='/anime/'], a[href*='/series/'], a[href*='/episode/']")
                ?.let { it.attr("abs:href").ifBlank { it.attr("href") } }
                .orEmpty()
        }.trim()

        val fixedHref = normalizeUrl(href)
        if (!isContentUrl(fixedHref)) return null

        val rawTitle = attr("title").trim().ifBlank {
            selectFirst("h1, h2, h3, h4, .title, .judul, .entry-title, .post-title, .series-title")
                ?.text()
                ?.trim()
                .orEmpty()
        }.ifBlank {
            selectFirst("a[href*='/anime/'], a[href*='/series/'], a[href*='/episode/']")
                ?.text()
                ?.trim()
                .orEmpty()
        }.ifBlank {
            selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }.ifBlank {
            text().trim()
        }

        val title = cleanTitle(rawTitle)
        if (title.length < 2 || isNavigationTitle(title)) return null

        val episode = parseEpisodeNumber(rawTitle) ?: parseEpisodeNumber(title) ?: parseEpisodeNumber(fixedHref)
        val type = detectType(fixedHref, rawTitle, title)
        val poster = selectFirst("img")?.imageAttr()?.let { normalizeUrlOrNull(it, fixedHref) }

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
            "#episode-list a[href*='/episode/'], .episode-list a[href*='/episode/'], " +
                ".daftar-episode a[href*='/episode/'], .eplister a[href*='/episode/'], " +
                "a[href*='/episode/']"
        ).filter {
            val href = normalizeUrl(it.attr("abs:href").ifBlank { it.attr("href") })
            isContentUrl(href) && !href.equals(referer, true)
        }

        return anchors.mapNotNull { anchor ->
            val href = normalizeUrl(anchor.attr("abs:href").ifBlank { anchor.attr("href") })
            val rawTitle = anchor.text().trim().ifBlank {
                href.trimEnd('/').substringAfterLast('/').replace("-", " ")
            }
            val title = cleanEpisodeTitle(rawTitle)
            val episode = parseEpisodeNumber(rawTitle) ?: parseEpisodeNumber(href)

            if (episode == null && title.length < 2) return@mapNotNull null

            newEpisode(href) {
                name = title.ifBlank { "Episode ${episode ?: 1}" }
                this.episode = episode
            }
        }.distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })
    }

    private fun parseDescription(document: Document): String? {
        val direct = document.selectFirst(".sinopsis, .synopsis, .summary, .entry-content, article")
            ?.select("p")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() && !isNoiseText(it) }
            ?.joinToString("\n")
            ?.trim()
            ?.takeIf { it.length > 20 }

        if (!direct.isNullOrBlank()) return direct

        return document.select("p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !isNoiseText(it) }
            .take(4)
            .joinToString("\n")
            .trim()
            .takeIf { it.length > 20 }
    }

    private fun parseTags(document: Document): List<String> {
        return document.select("a[href*='/genre/'], .genre a, .genres a, .tagcloud a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length in 2..40 && !it.any { ch -> ch.isDigit() } }
            .distinct()
    }

    private fun parseYear(document: Document): Int? {
        return Regex("""(?i)\b(?:Years?|Release Date)\s+.*?\b(20\d{2}|19\d{2})\b""")
            .find(document.text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(20\d{2}|19\d{2})\b""")
                .find(document.select(".info, .spe, .series-info, article").text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun parseStatus(document: Document): ShowStatus? {
        val text = document.text()
        return when {
            Regex("(?i)Status\\s+On-Going|Airing|Ongoing").containsMatchIn(text) -> ShowStatus.Ongoing
            Regex("(?i)Status\\s+Completed|Completed|Finished").containsMatchIn(text) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun collectPlayerCandidates(document: Document): List<String> {
        val candidates = linkedSetOf<String>()

        document.select(
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
            "option[value], [data-video], [data-src], [data-url], [data-iframe], [data-player], [data-player-url], [data-link], [data-file]"
        ).forEach { element ->
            listOf(
                element.attr("value"),
                element.attr("data-video"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-iframe"),
                element.attr("data-player"),
                element.attr("data-player-url"),
                element.attr("data-link"),
                element.attr("data-file")
            ).forEach { value ->
                if (value.isBlank()) return@forEach
                decodeServerValue(value)?.let { decoded ->
                    Jsoup.parse(decoded).select("iframe[src], video[src], source[src], embed[src], a[href]")
                        .forEach { embedded ->
                            candidates.add(embedded.iframeAttr().orEmpty())
                            candidates.add(embedded.attr("src"))
                            candidates.add(embedded.attr("href"))
                        }
                    candidates.add(decoded)
                } ?: candidates.add(value)
            }
        }

        document.select("a[href]").forEach { element ->
            val href = element.attr("abs:href").ifBlank { element.attr("href") }
            if (isLikelyPlayerValue(href)) candidates.add(href)
        }

        val html = document.html()
        Regex("""https?:\\?/\\?/[^"'<>\s]+""")
            .findAll(html)
            .forEach { candidates.add(it.value) }

        Regex("""(?i)(?:src|file|url|source)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(html)
            .forEach { candidates.add(it.groupValues[1]) }

        return candidates
            .map { cleanCandidate(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private suspend fun emitDirect(link: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
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

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return mainUrl
        val fixed = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$mainUrl$trimmed"
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            else -> "$mainUrl/${trimmed.trimStart('/')}"
        }

        return fixed
            .replace("http://www.mynimeku.com", mainUrl, ignoreCase = true)
            .replace("https://mynimeku.com", mainUrl, ignoreCase = true)
            .replace("http://mynimeku.com", mainUrl, ignoreCase = true)
            .substringBefore("#")
    }

    private fun normalizeUrlOrNull(raw: String?, base: String = mainUrl): String? {
        val clean = raw?.trim().orEmpty()
        if (clean.isBlank()) return null
        return runCatching {
            when {
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("/") -> URI(base).resolve(clean).toString()
                clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
                else -> URI(base).resolve(clean).toString()
            }.replace("https://mynimeku.com", mainUrl, ignoreCase = true)
        }.getOrNull()
    }

    private fun resolveUrl(raw: String?, base: String): String? {
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
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
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
        val lower = normalizeUrl(url).lowercase()
        val path = runCatching { URI(lower).path.orEmpty() }.getOrDefault(lower)

        if (!lower.startsWith(mainUrl.lowercase())) return false
        if (
            path.contains("/genre/") ||
            path.contains("/tag/") ||
            path.contains("/season/") ||
            path.contains("/studio/") ||
            path.contains("/producer/") ||
            path.contains("/licensor/") ||
            path.contains("/full-list") ||
            path.contains("/az-list") ||
            path.contains("/latest-series") ||
            path.contains("/page/")
        ) return false

        return path.startsWith("/anime/") ||
            path.startsWith("/series/") ||
            path.startsWith("/episode/")
    }

    private fun isPlayerCandidate(url: String): Boolean {
        val lower = url.lowercase()
        if (isBadUrl(url)) return false
        if (isDirectMedia(url)) return true

        return lower.contains("blogger.com/video.g") ||
            isMyPlayerku(url) ||
            isGoogleDriveMedia(url) ||
            lower.contains("googlevideo.com") ||
            lower.contains("/embed") ||
            lower.contains("embed/") ||
            lower.contains("player") ||
            lower.contains("stream") ||
            lower.contains("video") ||
            lower.contains("drive.google.com") ||
            lower.contains("mp4upload") ||
            lower.contains("filemoon") ||
            lower.contains("streamtape") ||
            lower.contains("dood") ||
            lower.contains("voe.sx") ||
            lower.contains("vidhide") ||
            lower.contains("yourupload")
    }

    private fun isBadUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") ||
            lower.contains("twitter.com") ||
            lower.contains("x.com/") ||
            lower.contains("telegram") ||
            lower.contains("reddit.com") ||
            lower.contains("whatsapp") ||
            lower.contains("tumblr.com") ||
            lower.contains("instagram.com") ||
            lower.contains("youtube.com") ||
            lower.contains("trakteer.id") ||
            lower.contains("mailto:") ||
            lower.contains("/genre/") ||
            lower.contains("/tag/") ||
            lower.contains("/season/") ||
            lower.contains("/studio/") ||
            lower.contains("/full-list") ||
            lower.contains("/az-list") ||
            lower.contains("/wp-content/themes/") ||
            lower.contains("wp-json") ||
            lower.contains("adsbygoogle") ||
            lower.contains("googlesyndication") ||
            lower.contains("doubleclick") ||
            lower.contains("analytics") ||
            lower.contains("cloudflareinsights") ||
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
        val path = runCatching { URI(url).path.orEmpty().lowercase() }
            .getOrDefault(url.substringBefore("?").lowercase())

        return path.endsWith(".m3u8") ||
            path.endsWith(".mp4") ||
            path.endsWith(".webm") ||
            path.endsWith(".mkv") ||
            path.endsWith(".mov") ||
            lower.contains("googlevideo.com/videoplayback") ||
            lower.contains("redirector.googlevideo.com/videoplayback") ||
            isGoogleDriveMedia(url)
    }

    private fun isM3u8Media(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }
            .getOrDefault(url.substringBefore("?").lowercase())
        return path.endsWith(".m3u8") || url.lowercase().contains(".m3u8?")
    }

    private fun isLikelyPlayerValue(value: String): Boolean {
        val clean = cleanCandidate(value)
        if (!isValidCandidate(clean) || isBadUrl(clean)) return false
        if (isPlayerCandidate(clean) || isDirectMedia(clean)) return true
        val lower = clean.lowercase()
        return (clean.startsWith("//") || clean.startsWith("http://", true) || clean.startsWith("https://", true)) &&
            (lower.contains("embed") || lower.contains("player") || lower.contains("stream") || lower.contains("video"))
    }

    private fun decodeServerValue(value: String): String? {
        val clean = value.trim()
        if (!Regex("""^[A-Za-z0-9+/=]{24,}$""").matches(clean)) return null
        return runCatching { String(android.util.Base64.decode(clean, android.util.Base64.DEFAULT)) }
            .getOrNull()
            ?.takeIf {
                it.contains("iframe", true) ||
                    it.contains("video", true) ||
                    it.contains("source", true) ||
                    it.contains("embed", true) ||
                    it.contains(".m3u8", true) ||
                    it.contains(".mp4", true)
            }
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
        val lowerText = titleHints.joinToString(" ").lowercase()

        return when {
            lowerText.contains("type movie") || Regex("""(?i)\bmovie\b""").containsMatchIn(lowerText) -> TvType.AnimeMovie
            lowerText.contains("type ova") || Regex("""(?i)\b(?:ova|special)\b""").containsMatchIn(lowerText) -> TvType.OVA
            lowerUrl.contains("t%3amovie") -> TvType.AnimeMovie
            lowerUrl.contains("t%3aova") || lowerUrl.contains("t%3aspecial") -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun parseEpisodeNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""(?i)\b(?:episode|eps|ep|e)\s*[-:.]?\s*(\d+)\b""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?i)(?:episode|eps|ep)-?(\d+)""")
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
                .replace(Regex("""(?i)^\s*(?:completed|on-going|ongoing|airing|upcoming|hiatus)\s+"""), " ")
                .replace(Regex("""(?i)^\s*(?:tv|ova|ona|movie|special|bd|la|music)\s+"""), " ")
                .replace(Regex("""(?i)^\s*e(?:p|pisode)?\s*[-:.]?\s*\d+\s+"""), " ")
                .replace(Regex("""(?i)^\s*sub\s+"""), " ")
        }

        title = title
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '|')

        return collapseDuplicatedTitle(title)
    }

    private fun cleanEpisodeTitle(raw: String): String {
        return raw
            .replace(Regex("""(?i)subtitle indonesia|sub indo"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '|')
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
            "beranda",
            "daftar anime a-z",
            "advanced search",
            "genre",
            "genre list",
            "login",
            "masuk",
            "daftar",
            "saring",
            "lihat semua hasil",
            "search",
            "prev",
            "next",
            "all"
        )
    }

    private fun isNoiseText(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("laporkan masalah") ||
            lower.contains("bantu kami memperbaiki") ||
            lower.contains("fan discussion") ||
            lower.contains("belum ada komentar") ||
            lower.contains("kirim komentar") ||
            lower.contains("masuk ke akun") ||
            lower.contains("daftar disini") ||
            lower.contains("mynimeku tidak menyimpan file") ||
            lower.contains("bagikan mynimeku")
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
            ?: image?.attr("abs:data-original")?.takeIf { it.isNotBlank() }
            ?: image?.attr("abs:srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
            ?: image?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
    }

    private fun Element?.iframeAttr(): String? {
        return this?.attr("data-litespeed-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("src")?.takeIf { it.isNotBlank() }
    }
}
