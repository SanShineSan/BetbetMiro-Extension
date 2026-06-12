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
        "full-list/mix/o:popular/" to "Popular",
        "full-list/mix/s:completed~t:BD,LA,MOVIE,MUSIC,ONA,OVA,SPECIAL,TV/" to "Completed",
        "full-list/mix/s:on-going~t:BD,LA,MOVIE,MUSIC,ONA,OVA,SPECIAL,TV/" to "On-Going",
        "latest-series/" to "Latest",
        "full-list/mix/t:TV/" to "TV",
        "full-list/mix/t:BD/" to "BD",
        "full-list/mix/t:MOVIE/" to "Movie",
        "full-list/mix/t:ONA/" to "ONA",
        "full-list/mix/t:OVA/" to "OVA",
        "full-list/mix/t:SPECIAL/" to "Special",
        "full-list/mix/t:LA/" to "LA"
    )

    private data class CardData(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType,
        val episode: Int?
    )

    private data class PlayerCandidate(
        val url: String,
        val label: String? = null,
        val type: String? = null
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
                val title = parseDetailTitle(document, directUrl)
                val sourceType = parseInfoValue(document, "Type")
                if (title.isBlank() || title.equals(name, true)) null else CardData(
                    title = cleanTitle(title),
                    url = directUrl,
                    poster = parsePoster(document, directUrl),
                    type = detectType(directUrl, sourceType, title),
                    episode = null
                )
            }.getOrNull()
        }

        return directHits.distinctBy { it.url }.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeUrl(url)
        val document = app.get(fixedUrl, headers = defaultHeaders()).document
        val isSeriesPage = fixedUrl.contains("/series/", ignoreCase = true)

        val rawTitle = parseDetailTitle(document, fixedUrl)
        val title = cleanTitle(rawTitle).ifBlank {
            throw ErrorLoadingException("Judul Mynimeku tidak ditemukan")
        }

        val poster = parsePoster(document, fixedUrl)
        val description = parseDescription(document)
        val tags = parseTags(document)
        val year = parseYear(document)
        val status = parseStatus(document)
        val sourceType = parseInfoValue(document, "Type")
        val type = detectType(fixedUrl, sourceType, rawTitle)
        val episodes = parseEpisodeList(document, fixedUrl)
        val recommendations = collectRecommendations(document)
            .distinctBy { it.url }
            .map { it.toSearchResponse() }

        return when {
            episodes.isNotEmpty() || (isSeriesPage && type != TvType.AnimeMovie) -> {
                newAnimeLoadResponse(title, fixedUrl, type) {
                    posterUrl = poster
                    backgroundPosterUrl = poster
                    posterHeaders = imageHeaders(fixedUrl)
                    plot = description
                    this.tags = tags
                    this.year = year
                    showStatus = status
                    this.recommendations = recommendations
                    if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
                }
            }

            else -> {
                val movieCandidates = collectPlayerCandidatesDetailed(document)
                val movieData = encodePlayerData(fixedUrl, movieCandidates).takeIf { movieCandidates.isNotEmpty() } ?: fixedUrl
                newMovieLoadResponse(title, fixedUrl, TvType.AnimeMovie, movieData) {
                    posterUrl = poster
                    backgroundPosterUrl = poster
                    posterHeaders = imageHeaders(fixedUrl)
                    plot = description
                    this.tags = tags
                    this.year = year
                    this.recommendations = recommendations
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val decodedBundle = decodePlayerData(data)
        val requestData = decodedBundle?.first ?: normalizeUrl(data)
        val emittedKeys = linkedSetOf<String>()
        var emitted = false

        fun callbackOnce(link: ExtractorLink) {
            val key = "${link.source.lowercase()}|${canonicalLink(link.url)}"
            if (emittedKeys.add(key)) {
                emitted = true
                callback(link)
            }
        }

        suspend fun processCandidate(candidate: PlayerCandidate, baseUrl: String = requestData, referer: String = requestData) {
            val url = resolveUrl(candidate.url, baseUrl) ?: return
            if (!isPlayerCandidate(url)) return
            val qualityHint = candidate.label ?: candidate.url

            when {
                isMyPlayerku(url) -> resolveMyPlayerku(url, referer, subtitleCallback, ::callbackOnce)
                isGoogleDriveMedia(url) -> emitGoogleDrive(url, qualityHint, ::callbackOnce)
                isDirectMedia(url) || isWorkersMedia(url) -> emitDirect(url, referer, qualityHint, ::callbackOnce)
                else -> runCatching {
                    loadExtractor(url, referer, subtitleCallback, ::callbackOnce)
                }
            }
        }

        decodedBundle?.second?.forEach { processCandidate(it, requestData, requestData) }
        if (emitted) return true

        val page = runCatching {
            app.get(
                requestData,
                referer = mainUrl,
                headers = pageHeaders(mainUrl),
                timeout = 25L
            ).document
        }.getOrNull()

        if (page != null) {
            val candidates = collectPlayerCandidatesDetailed(page)
            candidates.forEach { processCandidate(it, requestData, requestData) }
        }

        processCandidate(PlayerCandidate(requestData, "Fallback"), mainUrl, mainUrl)
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
                referer = referer,
                headers = defaultHeaders(referer),
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
                isDirectMedia(resolved) || isWorkersMedia(resolved) -> {
                    emitDirect(resolved, playerUrl, playerTitle, callback)
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

    private fun isWorkersMedia(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host.endsWith("workers.dev")
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
        val sourceItems = document.select(
            "article.mynimeku-mix-feed__item, article.mynimeku-update-feed__item, " +
                "li.mynimeku-update-widget__item"
        ).mapNotNull { it.toCardData() }

        if (sourceItems.isNotEmpty()) {
            return sourceItems
                .filterNot { isNavigationTitle(it.title) }
                .distinctBy { it.url }
        }

        return document.select(
            "a.mynimeku-mix-feed__series-title[href*='/series/'], " +
                "a.mynimeku-update-feed__series-title[href*='/series/'], " +
                "a.mynimeku-update-widget__series-title[href*='/series/'], " +
                "a.komik-series-chapter-item[href*='/episode/']"
        ).mapNotNull { it.toCardData() }
            .filterNot { isNavigationTitle(it.title) }
            .distinctBy { it.url }
    }

    private fun collectRecommendations(document: Document): List<CardData> {
        return document.select(
            "article.mynimeku-mix-feed__item, article.mynimeku-update-feed__item, " +
                "li.mynimeku-update-widget__item"
        ).mapNotNull { it.toCardData() }
            .filterNot { isNavigationTitle(it.title) }
            .distinctBy { it.url }
    }

    private fun Element.toCardData(): CardData? {
        val hrefElement = when {
            tagName().equals("a", true) -> this
            else -> selectFirst(
                "a.mynimeku-mix-feed__series-title[href*='/series/'], " +
                    "a.mynimeku-update-feed__series-title[href*='/series/'], " +
                    "a.mynimeku-update-widget__series-title[href*='/series/'], " +
                    "a.mynimeku-mix-feed__cover[href*='/series/'], " +
                    "a.mynimeku-update-feed__cover[href*='/series/'], " +
                    "a.mynimeku-update-widget__cover[href*='/series/'], " +
                    "a.komik-series-chapter-item[href*='/episode/']"
            )
        } ?: return null

        val fixedHref = normalizeUrl(hrefElement.attr("abs:href").ifBlank { hrefElement.attr("href") })
        if (!isContentUrl(fixedHref) || isBadUrl(fixedHref)) return null

        val rawTitle = selectFirst(
            ".mynimeku-mix-feed__series-title, .mynimeku-update-feed__series-title, " +
                ".mynimeku-update-widget__series-title, .komik-series-chapter-item__title"
        )?.text()?.trim().orEmpty().ifBlank {
            hrefElement.takeUnless { it.hasClass("mynimeku-mix-feed__cover") || it.hasClass("mynimeku-update-feed__cover") || it.hasClass("mynimeku-update-widget__cover") }
                ?.text()
                ?.trim()
                .orEmpty()
        }.ifBlank {
            selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
        }.ifBlank {
            fixedHref.trimEnd('/').substringAfterLast('/').replace("-", " ")
        }

        val title = cleanTitle(rawTitle)
        if (title.length < 2 || isNavigationTitle(title) || isNoiseText(title)) return null

        val episodeText = selectFirst(
            ".mynimeku-update-feed__latest-pill, .mynimeku-update-widget__latest-pill, " +
                ".komik-series-chapter-item__num"
        )?.text()?.trim().orEmpty()
        val episode = parseEpisodeNumber(episodeText) ?: parseEpisodeNumber(rawTitle) ?: parseEpisodeNumber(fixedHref)
        val sourceType = selectFirst(".mynimeku-mix-feed__type, .mynimeku-update-feed__badge, .mynimeku-update-widget__type")
            ?.text()
            ?.trim()
        val type = detectType(fixedHref, sourceType, rawTitle)
        val poster = selectBestImage(this, fixedHref)

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
            posterHeaders = imageHeaders(url)
            episode?.let { addSub(it) }
        }
    }

    private fun parseEpisodeList(document: Document, referer: String): List<Episode> {
        val anchors = document.select(
            "a.komik-series-chapter-item[href*='/episode/'], " +
                ".komik-series-chapter-list a[href*='/episode/'], " +
                ".komik-series-chapters a[href*='/episode/']"
        ).filter {
            val href = normalizeUrl(it.attr("abs:href").ifBlank { it.attr("href") })
            isContentUrl(href) && !href.equals(referer, true) && !isBadUrl(href)
        }

        return anchors.mapNotNull { anchor ->
            val href = normalizeUrl(anchor.attr("abs:href").ifBlank { anchor.attr("href") })
            val numberText = anchor.attr("data-episode-number").ifBlank {
                anchor.selectFirst(".komik-series-chapter-item__num")?.text()?.trim().orEmpty()
            }
            val titleText = anchor.selectFirst(".komik-series-chapter-item__title")?.text()?.trim().orEmpty()
            val rawTitle = titleText.ifBlank {
                anchor.attr("data-episode-search-text").ifBlank {
                    href.trimEnd('/').substringAfterLast('/').replace("-", " ")
                }
            }
            val episode = numberText.toIntOrNull()
                ?: parseEpisodeNumber(rawTitle)
                ?: parseEpisodeNumber(href)
            val title = cleanEpisodeTitle(rawTitle)

            if (episode == null && title.length < 2) return@mapNotNull null

            newEpisode(href) {
                name = title.ifBlank { "Episode ${episode ?: 1}" }
                this.episode = episode
            }
        }.distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })
    }

    private fun parseDescription(document: Document): String? {
        val text = document.selectFirst(".komik-series-entry")?.text()?.trim()
            ?: document.selectFirst(".komik-series-hero__synopsis .komik-series-entry")?.text()?.trim()
            ?: document.selectFirst(".komik-series-hero__synopsis")?.text()?.trim()?.removePrefix("Sinopsis")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        return text
            ?.replace(Regex("""(?i)^sinopsis\s*"""), "")
            ?.trim()
            ?.takeIf { it.length > 20 && !isNoiseText(it) }
    }

    private fun parseTags(document: Document): List<String> {
        return document.select(".komik-series-taxonomy a[href*='/genre/'], a.mynimeku-mix-feed__genre[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length in 2..40 && !isNoiseText(it) }
            .distinct()
    }

    private fun parseYear(document: Document): Int? {
        val release = parseInfoValue(document, "Years") ?: parseInfoValue(document, "Release Date") ?: parseInfoValue(document, "Released")
        return Regex("""\b(20\d{2}|19\d{2})\b""")
            .find(release.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(20\d{2}|19\d{2})\b""")
                .find(document.select(".komik-series-info, .komik-series-card").text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun parseStatus(document: Document): ShowStatus? {
        val status = parseInfoValue(document, "Status") ?: document.select(".mynimeku-mix-feed__status, .mynimeku-update-feed__badge, .mynimeku-update-widget__status").text()
        return when {
            status.contains("on-going", true) || status.contains("ongoing", true) || status.contains("airing", true) -> ShowStatus.Ongoing
            status.contains("completed", true) || status.contains("finished", true) || status.contains("tamat", true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun parseDetailTitle(document: Document, url: String): String {
        return document.selectFirst("h1.komik-series-hero__title")?.text()?.trim().orEmpty()
            .ifBlank { document.selectFirst("h1.mynimeku-episode-head__title")?.text()?.trim().orEmpty() }
            .ifBlank {
                document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.substringBefore(" - MyNimeku")
                    ?.substringBefore(" – MyNimeku")
                    ?.substringBefore(" | MyNimeku")
                    ?.trim()
                    .orEmpty()
            }
            .ifBlank {
                document.title()
                    .substringBefore(" - MyNimeku")
                    .substringBefore(" – MyNimeku")
                    .substringBefore(" | MyNimeku")
                    .trim()
            }
            .ifBlank { url.trimEnd('/').substringAfterLast('/').replace("-", " ") }
    }

    private fun parsePoster(document: Document, base: String): String? {
        return selectBestImage(document.selectFirst(".komik-series-hero__cover") ?: document, base)
            ?: normalizeUrlOrNull(document.selectFirst("meta[property=og:image]")?.attr("content"), base)
            ?: normalizeUrlOrNull(document.selectFirst("meta[name=twitter:image]")?.attr("content"), base)
    }

    private fun parseInfoValue(document: Document, label: String): String? {
        val wanted = label.lowercase()

        document.select(".komik-series-info tr, .komik-series-card tr").forEach { row ->
            val key = row.selectFirst("th")?.text()?.trim(':', ' ')?.lowercase()
            if (key == wanted || key?.startsWith(wanted) == true) {
                val value = row.selectFirst("td")?.text()?.trim()
                if (!value.isNullOrBlank()) return value
            }
        }

        document.select(".komik-series-info li, .komik-series-info div, .komik-series-card li, .komik-series-card div").forEach { element ->
            val key = element.selectFirst(".komik-series-info__label, .komik-series-meta__label, .label, strong, b")
                ?.text()
                ?.trim(':', ' ')
                ?.lowercase()
            if (key == wanted || key?.startsWith(wanted) == true) {
                val value = element.selectFirst(".komik-series-info__value, .komik-series-meta__value, .value")
                    ?.text()
                    ?.trim()
                    ?: element.text().replace(Regex("""(?i)^\s*${Regex.escape(label)}\s*:?\s*"""), "").trim()
                if (value.isNotBlank() && !value.equals(label, true)) return value
            }
        }

        val text = document.select(".komik-series-info, .komik-series-card").text()
        return Regex("""(?i)\b${Regex.escape(label)}\s+([^\n\r]+?)(?=\s+(?:Status|Type|Japanese|Synonyms|English|Rating|Release Date|Episodes|Duration|Season|Studio|Producer|Years|Rate)\b|$)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun selectBestImage(element: Element, base: String): String? {
        return element.select("img").asSequence()
            .mapNotNull { it.imageAttr() }
            .mapNotNull { normalizeUrlOrNull(it, base) }
            .firstOrNull { isValidImageUrl(it) }
    }

    private fun isValidImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http") &&
            !lower.startsWith("data:") &&
            !lower.contains("svg%3csvg") &&
            !lower.endsWith(".svg") &&
            !lower.contains("icon-mynimeku") &&
            !lower.contains("/assets/img/preview") &&
            !isAdUrl(lower)
    }

    private fun playerCandidatePriority(url: String): Int {
        val lower = url.lowercase()
        return when {
            isGoogleDriveMedia(url) -> 1000
            lower.contains("players.myplayerku.my.id/drive/") -> 950
            isWorkersMedia(url) -> 900
            lower.contains("players.myplayerku.my.id/public/") -> 850
            lower.contains("players.myplayerku.my.id/proxy/") -> 800
            isDirectMedia(url) -> 750
            isMyPlayerku(url) -> 700
            lower.contains("googlevideo.com/videoplayback") -> 650
            lower.contains("drive.google.com") -> 600
            else -> 0
        }
    }

    private fun collectPlayerCandidates(document: Document): List<String> {
        return collectPlayerCandidatesDetailed(document).map { it.url }
    }

    private fun collectPlayerCandidatesDetailed(document: Document): List<PlayerCandidate> {
        val candidates = arrayListOf<PlayerCandidate>()

        document.select(".mynimeku-episode-server-btn[data-player-url], [data-player-url]").forEach { element ->
            val url = element.attr("data-player-url").trim()
            val label = element.attr("data-player-host").ifBlank { element.text() }.trim()
            val type = element.attr("data-player-type").trim()
            if (url.isNotBlank()) candidates.add(PlayerCandidate(url, label, type))
        }

        document.select(
            "iframe[src], iframe[data-src], iframe[data-litespeed-src], iframe[data-lazy-src], " +
                "video[src], video[data-src], source[src], embed[src], object[data]"
        ).forEach { element ->
            listOf(
                element.iframeAttr(),
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-litespeed-src"),
                element.attr("data-lazy-src"),
                element.attr("data")
            ).filter { !it.isNullOrBlank() }
                .forEach { candidates.add(PlayerCandidate(it!!, element.attr("title").ifBlank { element.attr("aria-label") })) }
        }

        document.select(
            "option[value], [data-video], [data-src], [data-url], [data-iframe], " +
                "[data-player], [data-link], [data-file]"
        ).forEach { element ->
            val values = listOf(
                element.attr("value"),
                element.attr("data-video"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-iframe"),
                element.attr("data-player"),
                element.attr("data-link"),
                element.attr("data-file")
            )

            values.filter { it.isNotBlank() }.forEach { value ->
                decodeServerValue(value)?.let { decoded ->
                    Jsoup.parse(decoded).select("iframe[src], video[src], source[src], embed[src], a[href]")
                        .forEach { embedded ->
                            listOf(embedded.iframeAttr(), embedded.attr("src"), embedded.attr("href"))
                                .filter { !it.isNullOrBlank() }
                                .forEach { candidates.add(PlayerCandidate(it!!, element.text())) }
                        }
                    candidates.add(PlayerCandidate(decoded, element.text()))
                } ?: candidates.add(PlayerCandidate(value, element.text()))
            }
        }

        document.select("a[href]").forEach { element ->
            val href = element.attr("abs:href").ifBlank { element.attr("href") }
            val label = element.text().trim()
            if (isLikelyPlayerValue(href) || href.contains("file.mydriveku.my.id/api/v1", true)) {
                candidates.add(PlayerCandidate(href, label))
                toMyPlayerkuCandidate(href)?.let { converted -> candidates.add(PlayerCandidate(converted, label)) }
            }
        }

        val html = document.html()
        Regex("""https?:\\?/\\?/[^"'<>\s]+""")
            .findAll(html)
            .forEach { candidates.add(PlayerCandidate(it.value)) }

        Regex("""(?i)(?:src|file|url|source)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(html)
            .forEach { candidates.add(PlayerCandidate(it.groupValues[1])) }

        return candidates
            .mapNotNull { candidate ->
                val clean = cleanCandidate(candidate.url)
                if (clean.isBlank()) null else candidate.copy(url = clean)
            }
            .filter { !isBadUrl(it.url) }
            .distinctBy { canonicalLink(it.url) + "|" + (it.label ?: "") }
            .sortedWith(
                compareByDescending<PlayerCandidate> { playerCandidatePriority(it.url) + qualityPriority(it.label.orEmpty()) }
                    .thenBy { it.url.lowercase() }
            )
    }

    private fun qualityPriority(label: String): Int {
        return when {
            label.contains("1080", true) -> 30
            label.contains("720", true) -> 20
            label.contains("480", true) -> 10
            label.contains("360", true) -> 5
            else -> 0
        }
    }

    private fun encodePlayerData(referer: String, candidates: List<PlayerCandidate>): String {
        if (candidates.isEmpty()) return referer
        val encoded = candidates
            .take(24)
            .joinToString("||") { candidate ->
                listOf(candidate.url, candidate.label.orEmpty(), candidate.type.orEmpty())
                    .joinToString("~~") { java.net.URLEncoder.encode(it, "UTF-8") }
            }
        return "mynimekuplayers::$referer:::$encoded"
    }

    private fun decodePlayerData(data: String): Pair<String, List<PlayerCandidate>>? {
        val prefix = "mynimekuplayers::"
        if (!data.startsWith(prefix)) return null
        val parts = data.removePrefix(prefix).split(":::", limit = 2)
        if (parts.size != 2) return null
        val referer = normalizeUrl(parts[0])
        val candidates = parts[1].split("||")
            .mapNotNull { raw ->
                val fields = raw.split("~~")
                val url = fields.getOrNull(0)?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                if (url.isBlank()) return@mapNotNull null
                PlayerCandidate(
                    url = url,
                    label = fields.getOrNull(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() },
                    type = fields.getOrNull(2)?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
                )
            }
        return referer to candidates
    }

    private fun toMyPlayerkuCandidate(url: String): String? {
        val lower = url.lowercase()
        val token = url.substringAfter("/api/v1/", "").trim('/')
        if (token.isBlank()) return null
        return when {
            lower.contains("file.mydriveku.my.id/api/v1/drive/") -> "https://players.myplayerku.my.id/drive/${token.substringAfter("drive/")}"
            lower.contains("file.mydriveku.my.id/api/v1/public/") -> "https://players.myplayerku.my.id/public/${token.substringAfter("public/")}"
            lower.contains("file.mydriveku.my.id/api/v1/private/") -> "https://players.myplayerku.my.id/private/${token.substringAfter("private/")}"
            else -> null
        }
    }

    private suspend fun emitDirect(link: String, referer: String, qualityHint: String = link, callback: (ExtractorLink) -> Unit): Boolean {
        if (!(isDirectMedia(link) || isWorkersMedia(link)) || isBadUrl(link)) return false

        callback(
            newExtractorLink(
                source = if (isWorkersMedia(link)) "MyPlayerku Workers" else name,
                name = if (isWorkersMedia(link)) "MyPlayerku Workers" else name,
                url = link,
                type = if (isM3u8Media(link)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(qualityHint).takeIf { it != Qualities.Unknown.value }
                    ?: getQualityFromName(link).takeIf { it != Qualities.Unknown.value }
                    ?: when {
                        qualityHint.contains("1080", true) || link.contains("1080", true) -> Qualities.P1080.value
                        qualityHint.contains("720", true) || link.contains("720", true) -> Qualities.P720.value
                        qualityHint.contains("480", true) || link.contains("480", true) -> Qualities.P480.value
                        qualityHint.contains("360", true) || link.contains("360", true) -> Qualities.P360.value
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
            isWorkersMedia(url) ||
            lower.contains("/embed") ||
            lower.contains("embed/") ||
            lower.contains("player") ||
            lower.contains("stream") ||
            lower.contains("video") ||
            lower.contains("drive.google.com") ||
            lower.contains("file.mydriveku.my.id/api/v1") ||
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
            lower.contains("guidepaparazzisurface") ||
            lower.contains("odqghulazoz") ||
            lower.contains("wpadmngr") ||
            lower.contains("admanager") ||
            lower.contains("slot") ||
            lower.contains("judi") ||
            lower.contains("promo") ||
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
            isGoogleDriveMedia(url) ||
            isWorkersMedia(url)
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

    private fun detectType(url: String, vararg titleHints: String?): TvType {
        val lowerUrl = url.lowercase()
        val hints = titleHints.filterNotNull().joinToString(" ").lowercase()

        return when {
            Regex("""(?i)\bmovie\b""").containsMatchIn(hints) || lowerUrl.contains("t:movie") || lowerUrl.contains("t%3amovie") -> TvType.AnimeMovie
            Regex("""(?i)\b(?:ova|special|ona)\b""").containsMatchIn(hints) || lowerUrl.contains("t:ova") || lowerUrl.contains("t:special") || lowerUrl.contains("t:ona") -> TvType.OVA
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
            "all",
            "series",
            "completed",
            "on-going",
            "latest",
            "tv",
            "bd",
            "movie",
            "ona",
            "ova",
            "special",
            "la",
            "music"
        )
    }

    private fun isNoiseText(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("iklan") ||
            lower.contains("ads") ||
            lower.contains("slot") ||
            lower.contains("judi") ||
            lower.contains("promo") ||
            lower.contains("fan talk") ||
            lower.contains("fan discussion") ||
            lower.contains("ruang ngobrol") ||
            lower.contains("simpan nama") ||
            lower.contains("komentar saya berikutnya") ||
            lower.contains("laporkan masalah") ||
            lower.contains("bantu kami memperbaiki") ||
            lower.contains("fan discussion") ||
            lower.contains("belum ada komentar") ||
            lower.contains("kirim komentar") ||
            lower.contains("masuk ke akun") ||
            lower.contains("daftar disini") ||
            lower.contains("mynimeku tidak menyimpan file") ||
            lower.contains("bagikan mynimeku")
    }

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("ads") ||
            lower.contains("slot") ||
            lower.contains("judi") ||
            lower.contains("promo") ||
            lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.contains("guidepaparazzisurface") ||
            lower.contains("odqghulazoz") ||
            lower.contains("wpadmngr") ||
            lower.contains("admanager")
    }

    private fun imageHeaders(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "referer" to referer,
            "User-Agent" to USER_AGENT,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
        )
    }

    private fun pageHeaders(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    private fun defaultHeaders(referer: String = mainUrl): Map<String, String> = pageHeaders(referer)

    private fun Element.imageAttr(): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        return listOf(
            image?.attr("abs:data-src"),
            image?.attr("abs:data-lazy-src"),
            image?.attr("abs:data-original"),
            image?.attr("abs:srcset")?.substringBefore(" "),
            image?.attr("abs:src"),
            image?.attr("data-src"),
            image?.attr("data-lazy-src"),
            image?.attr("data-original"),
            image?.attr("srcset")?.substringBefore(" "),
            image?.attr("src")?.substringBefore(" ")
        ).map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:", true) }
    }

    private fun Element?.iframeAttr(): String? {
        return this?.attr("data-litespeed-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("src")?.takeIf { it.isNotBlank() }
    }
}
