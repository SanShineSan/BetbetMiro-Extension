package com.sad25kag.nontonanimex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64

class NontonAnimeX : MainAPI() {
    override var mainUrl = "https://nontonanimex.com"
    override var name = "NontonAnimeX"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/terbaru/page/%d" to "Anime Terbaru",
        "$mainUrl/complete/page/%d" to "Anime Complete",
        "$mainUrl/donghua/terbaru/page/%d" to "Donghua Terbaru",
        "$mainUrl/donghua/status/completed/page/%d" to "Donghua Complete",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page), headers = siteHeaders).document
        val results = document.select(
            ".main-col .listbox .xrelated, .main-col .xrelated, .asw-item, " +
                "article, .post, .bs, .listupd article"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        val hasNext = document.select(
            "a.next, .pagination a[href*=/page/${page + 1}], a[href$=/page/${page + 1}], " +
                "a[href*=\"paged=${page + 1}\"]"
        ).isNotEmpty()

        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/anime-list?s=$encoded",
            "$mainUrl/donghua/anime-list?s=$encoded",
        )

        val results = linkedMapOf<String, SearchResponse>()

        for (url in searchUrls) {
            runCatching {
                val document = app.get(url, headers = siteHeaders).document
                document.select(
                    ".main-col .listbox .xrelated, .main-col .xrelated, .asw-item, " +
                        "article, .post, .bs, .listupd article, .search-results article"
                ).mapNotNull { it.toSearchResult() }.forEach { results[it.url] = it }
            }
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(fixUrlSafe(url), headers = siteHeaders, referer = "$mainUrl/")
        val document = response.document
        val fixedUrl = response.url

        val title = document.selectFirst("h2, h1.tlpost, h1.title-post, h1.entry-title, h1")
            ?.ownText()
            ?.ifBlank { document.selectFirst("h2, h1.tlpost, h1.title-post, h1.entry-title, h1")?.text() }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.substringBefore("|")
                ?.cleanTitle()
                ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let(::fixUrlSafe)
            ?: document.selectFirst(".ppcov img, .ccov img, .post-body img, .entry-content img, img")?.imageUrl()

        val plot = document.selectFirst(".sinops, .sinopsis, .entry-content, .post-body p")
            ?.text()
            ?.cleanPlot()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.cleanPlot()

        val genres = document.select(
            ".genres a, .post-body a[href*=/genre/], .post-body a[href*=/genres/], " +
                ".infolt a[href*=/genre/], .infolt a[href*=/genres/]"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() && !it.isBlockedContentTitle() }
            .distinct()

        val status = document.statusFromInfo()
        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(document.select(".infolt, .post-body, time").text())
            ?.value
            ?.toIntOrNull()

        val recommendations = document.select(".asw-item, .xrelated, article, .post")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        val episodes = document.select(
            ".slist a[href], .ulinklist a[href], #selecteps option[value], " +
                "select[name=episode] option[value], select.episode option[value]"
        ).mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })

        val type = when {
            title.contains("Movie", true) || fixedUrl.contains("/movie/", true) -> TvType.AnimeMovie
            title.contains("OVA", true) || title.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
                this.showStatus = status
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                posterUrl = poster
                this.plot = plot
                this.tags = genres
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
        val pageUrl = fixUrlSafe(data)
        val response = app.get(pageUrl, headers = siteHeaders, referer = "$mainUrl/")
        val html = response.text
        val document = response.document
        var emitted = false
        val emittedUrls = linkedSetOf<String>()

        fun emit(link: ExtractorLink) {
            if (link.url.cleanMediaUrl().isBlockedMediaUrl()) return
            if (emittedUrls.add(link.url.substringBefore("#"))) {
                callback(link)
                emitted = true
            }
        }

        suspend fun emitDirect(url: String, referer: String, label: String = name): Boolean {
            val clean = url.cleanMediaUrl().substringBefore("#")
            if (clean.isBlockedMediaUrl()) return false
            if (!clean.isPlayableMediaUrl()) return false
            if (!emittedUrls.add(clean)) return true

            val type = if (clean.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, label, clean, type) {
                this.referer = referer
                this.quality = qualityFromName("$label $clean")
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*"
                )
            })
            emitted = true
            return true
        }

        suspend fun inspectCandidate(candidate: VideoCandidate, depth: Int = 0) {
            if (depth > 3) return

            val candidateUrl = fixUrlSafe(candidate.url.cleanMediaUrl())
            if (candidateUrl.isBlank()) return

            val sourceLabel = resolvePlaybackLabel(candidateUrl, candidate.label) ?: return
            val sourceReferer = playbackReferer(candidateUrl, sourceLabel, pageUrl)

            if (emitDirect(candidateUrl, sourceReferer, sourceLabel)) return

            runCatching {
                loadExtractor(candidateUrl, sourceReferer, subtitleCallback) { link ->
                    emit(link)
                }
            }

            if (emitted) return

            val iframePage = runCatching {
                app.get(candidateUrl, headers = siteHeaders + mapOf("Referer" to sourceReferer), referer = sourceReferer, timeout = 20000L)
            }.getOrNull() ?: return

            val iframeHtml = iframePage.text
            val unpacked = runCatching { getAndUnpack(iframeHtml) }.getOrDefault("")
            val combined = "$iframeHtml\n$unpacked"

            extractMediaCandidates(combined, candidateUrl, sourceLabel).forEach {
                if (it.url != candidateUrl) inspectCandidate(it, depth + 1)
            }

            Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
                .findAll(combined.decodeEscaped())
                .map { it.value.cleanMediaUrl() }
                .filter { it.isPlayableMediaUrl() }
                .distinct()
                .forEach { emitDirect(it, playbackReferer(it, sourceLabel, candidateUrl), sourceLabel) }
        }

        val candidates = linkedMapOf<String, VideoCandidate>()

        fun addCandidate(rawUrl: String, rawLabel: String) {
            val cleaned = rawUrl.cleanMediaUrl()
            val label = cleanSourceLabel(rawLabel)
            val resolvedLabel = resolvePlaybackLabel(cleaned, label) ?: return
            candidates[cleaned] = VideoCandidate(cleaned, resolvedLabel)
        }

        extractMediaCandidates(html, pageUrl, name).forEach { addCandidate(it.url, it.label) }

        document.select("iframe[src], video[src], source[src], embed[src]").forEach { element ->
            element.attr("src").takeIf { it.isNotBlank() }?.let {
                val label = element.attr("title")
                    .ifBlank { element.attr("data-title") }
                    .ifBlank { element.attr("aria-label") }
                    .ifBlank { element.attr("class") }
                    .ifBlank { name }
                addCandidate(it, label)
            }
        }

        document.select("select.mirvid option[value], select[name=mirvid] option[value]").forEach { option ->
            val label = option.text().trim().ifBlank { name }
            val raw = option.attr("value").trim()
            if (raw.isBlank()) return@forEach
            decodeBase64Text(raw)?.let { decoded ->
                extractMediaCandidates(decoded, pageUrl, label).forEach { addCandidate(it.url, it.label) }
            }
            if (raw.startsWith("http", true) || raw.startsWith("//")) {
                addCandidate(raw, label)
            }
        }

        document.select("[data-src], [data-url], [data-link], [data-iframe], [data-embed], [data-player], [data-video], [data-file]").forEach { element ->
            val label = element.attr("data-title")
                .ifBlank { element.attr("title") }
                .ifBlank { element.attr("aria-label") }
                .ifBlank { element.text().trim().take(40) }
                .ifBlank { name }
            listOf("data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file")
                .map { element.attr(it).trim() }
                .filter { it.isNotBlank() }
                .forEach { raw ->
                    decodeBase64Text(raw)?.let { decoded ->
                        extractMediaCandidates(decoded, pageUrl, label).forEach { addCandidate(it.url, it.label) }
                    }
                    if (raw.startsWith("http", true) || raw.startsWith("//")) {
                        addCandidate(raw, label)
                    }
                }
        }

        for (candidate in candidates.values) {
            inspectCandidate(candidate)
        }

        return emitted
    }

    private fun buildPageUrl(url: String, page: Int): String {
        val clean = url.trimEnd('/')
        return if (page <= 1) {
            clean.replace("/page/%d", "").replace("%d", "1")
        } else {
            clean.replace("%d", page.toString())
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = when {
            normalName() == "a" -> this
            else -> selectFirst("a[href]")
        } ?: return null

        val href = fixUrlSafe(link.attr("href").trim())
        if (!href.contains(mainUrl) || href == mainUrl || href.contains("#")) return null
        if (href.isNavigationUrl()) return null

        val rawTitle = selectFirst(".titlelist, .asw-post-ti, h2, h3, h4, .tt, .entry-title")?.text()?.takeIf { it.isNotBlank() }
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: text()

        val title = rawTitle.cleanTitle().takeIf { it.isNotBlank() } ?: return null
        if (title.isBlockedContentTitle()) return null

        val poster = selectFirst("img")?.imageUrl()
        val ep = Regex("""(\d+)\s*(?:Episode|Eps|Ep)?""", RegexOption.IGNORE_CASE)
            .find(selectFirst(".eplist, .ep, .episode")?.text().orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val scoreText = selectFirst(".starlist, .score, .rating")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
            addDubStatus("Sub Indo", ep)
            scoreText?.toDoubleOrNull()?.let { this.score = Score.from10(it) }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val rawHref = when {
            normalName() == "option" -> attr("value")
            else -> attr("href")
        }.trim().ifBlank { return null }

        val href = fixUrlSafe(rawHref)
        if (!href.contains(mainUrl)) return null
        if (href.isNavigationUrl()) return null

        val label = text().cleanTitle().ifBlank { "Episode" }
        if (label.isBlockedPlaybackLabel()) return null
        if (!href.isLikelyEpisodeUrl() && !label.hasEpisodeMarker()) return null

        val number = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(label)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""")
                .find(label)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        return newEpisode(href) {
            name = label
            episode = number
        }
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("src"),
            attr("abs:data-src"),
            attr("abs:src"),
        ).firstOrNull { it.isNotBlank() }?.let(::fixUrlSafe)
    }

    private fun Document.statusFromInfo(): ShowStatus? {
        val text = select(".infolt, .post-body, .cinfo").text()
        return when {
            text.contains("Completed", true) || text.contains("Complete", true) || text.contains("Tamat", true) -> ShowStatus.Completed
            text.contains("Ongoing", true) || text.contains("On-Going", true) -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun extractMediaCandidates(source: String, referer: String, label: String): List<VideoCandidate> {
        val cleanSource = source.decodeEscaped()
        val candidates = linkedMapOf<String, VideoCandidate>()

        fun add(rawUrl: String?, rawLabel: String = label) {
            val fixed = rawUrl?.trim()?.trim('"', '\'', ' ', ';')?.cleanMediaUrl().orEmpty()
            if (fixed.isBlank()) return
            if (!fixed.startsWith("http", true) && !fixed.startsWith("//") && !fixed.startsWith("/")) return
            val resolvedLabel = resolvePlaybackLabel(fixed, rawLabel) ?: return
            candidates[fixed] = VideoCandidate(fixed, resolvedLabel)
        }

        Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleanSource)
            .forEach { add(it.groupValues.getOrNull(1), label) }

        Regex("""<(?:video|source|embed)[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleanSource)
            .forEach { add(it.groupValues.getOrNull(1), label) }

        Regex("""(?:file|source|src|url|video|link|embed|iframe)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleanSource)
            .forEach { add(it.groupValues.getOrNull(1), label) }

        Regex("""(?:data-src|data-url|data-link|data-iframe|data-embed|data-player|data-video|data-file)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleanSource)
            .forEach { add(it.groupValues.getOrNull(1), label) }

        Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(cleanSource)
            .map { it.value.cleanMediaUrl() }
            .forEach { add(it, label) }

        return candidates.values.toList()
    }

    private fun resolvePlaybackLabel(url: String, label: String): String? {
        val cleanUrl = url.cleanMediaUrl().lowercase()
        val cleanLabel = label.cleanTitleForCompare()
        return when {
            cleanUrl.contains("dailymotion.com") || cleanUrl.contains("geo.dailymotion") ||
                cleanLabel.contains("dailymotion") -> "GeoDailymotion"
            cleanUrl.contains("minochinos") || cleanLabel.contains("minochinos") -> "Minochinos"
            cleanUrl.contains("rumble.com") || cleanLabel.contains("rumble") -> "Rumble"
            cleanUrl.contains("emturbovid") || cleanUrl.contains("turbovid") ||
                cleanLabel.contains("emturbovid") || cleanLabel.contains("turbovid") -> "Emturbovid"
            cleanUrl.contains("mega.nz/file/") || cleanUrl.contains("mega.nz/embed/") -> "Mega"
            cleanUrl.contains("krakenfiles.com") || cleanUrl.contains("krakencloud.net/play/video/") ||
                cleanLabel == "kfiles" && cleanUrl.contains("kraken") -> "KFiles"
            else -> null
        }
    }

    private fun playbackReferer(url: String, label: String, fallback: String): String {
        val cleanUrl = url.lowercase()
        return when {
            label == "KFiles" || cleanUrl.contains("krakencloud.net") || cleanUrl.contains("krakenfiles.com") -> "https://krakenfiles.com/"
            label == "Mega" || cleanUrl.contains("mega.nz") -> "https://mega.nz/"
            else -> fallback
        }
    }

    private fun String.isLikelyVideoHost(): Boolean {
        return resolvePlaybackLabel(this, "") != null
    }

    private fun String.isPlayableMediaUrl(): Boolean {
        val value = lowercase()
        return !value.isBlockedMediaUrl() && (
            value.contains(".m3u8") ||
                value.contains(".mp4") ||
                value.contains(".webm") ||
                value.contains(".mkv") ||
                value.contains("krakencloud.net/play/video/")
        )
    }

    private fun String.isBlockedMediaUrl(): Boolean {
        val value = lowercase().substringBefore("?")
        return listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".ico",
            ".css", ".js", ".woff", ".woff2", ".ttf", ".eot"
        ).any { value.endsWith(it) } || listOf(
            "shopee", "ads", "banner", "doubleclick", "googlesyndication",
            "googleadservices", "adservice", "analytics", "blogger.com",
            "blogger.googleusercontent.com", "googlevideo.com", "youtube.googleapis.com"
        ).any { lowercase().contains(it) }
    }

    private fun String.isNavigationUrl(): Boolean {
        val value = lowercase()
        return listOf(
            "/genre/", "/genres/", "/tag/", "/status/", "/page/", "/jadwal-rilis",
            "anime-list", "bookmark", "schedule", "versi-text", "versi-gambar", "filter",
            "popular", "populer", "login", "daftar", "register", "mainkan-game", "game",
            "responsive-stream", "#"
        ).any { value.contains(it) }
    }

    private fun String.isLikelyEpisodeUrl(): Boolean {
        val value = lowercase()
        return value.contains("/episode/") || value.contains("episode-") || value.contains("-episode") ||
            Regex("""/ep-?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(value)
    }

    private fun String.hasEpisodeMarker(): Boolean {
        return Regex("""\b(?:episode|eps|ep)\s*\d+\b""", RegexOption.IGNORE_CASE).containsMatchIn(this) ||
            Regex("""\b\d+\s*(?:episode|eps|ep)\b""", RegexOption.IGNORE_CASE).containsMatchIn(this)
    }

    private fun String.isBlockedContentTitle(): Boolean {
        val value = cleanTitleForCompare()
        return value.isBlank() || value.length < 2 || listOf(
            "home", "bookmark", "schedule", "versi text", "versi gambar", "filter donghua",
            "populer", "popular", "login", "daftar", "register", "mainkan game", "nontonanimex",
            "responsive stream", "putar di browser"
        ).any { value == it || value.contains(it) }
    }

    private fun String.isBlockedPlaybackLabel(): Boolean {
        val value = cleanTitleForCompare()
        return value.isBlank() || listOf(
            "responsive stream", "nontonanimex", "putar di browser", "browser", "wrapper",
            "container", "player", "embed", "mirror", "lapor", "lampu", "komentar"
        ).any { value == it || value.contains(it) }
    }

    private fun cleanSourceLabel(value: String): String {
        val cleaned = value.cleanTitle().trim()
        return if (cleaned.isBlockedPlaybackLabel()) name else cleaned
    }

    private fun String.cleanTitleForCompare(): String {
        return cleanTitle()
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return htmlUnescape()
            .replace(Regex("""(?i)\s*\|\s*Nonton Anime.*$"""), "")
            .replace(Regex("""(?i)\s*Subtitle\s+Indonesia.*$"""), "")
            .replace(Regex("""(?i)\s*Sub\s+Indo.*$"""), "")
            .replace(Regex("""(?i)^Download\s+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanPlot(): String {
        return htmlUnescape()
            .replace(Regex("""(?i)Download\s+dan\s+Streaming\s+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanMediaUrl(): String {
        return htmlUnescape()
            .decodeEscaped()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("&amp;", "&")
            .trim()
            .trimEnd(',', ';', ')', ']', '}')
    }

    private fun String.decodeEscaped(): String {
        var output = this
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
            output = output
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\u0026", "&")
                .replace("\\u003d", "=")
                .replace("\\u003c", "<")
                .replace("\\u003e", ">")
        }
        return output
    }

    private fun String.htmlUnescape(): String {
        return Jsoup.parse(this).text()
            .replace("\u00a0", " ")
            .trim()
    }

    private fun decodeBase64Text(value: String): String? {
        val cleaned = value.trim()
            .replace("\\/", "/")
            .replace("\\n", "")
            .replace("\\r", "")
        if (cleaned.length < 12) return null
        return runCatching {
            val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
            Base64.getDecoder().decode(padded).toString(Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.contains("<", true) || it.contains("http", true) }
    }

    private fun qualityFromName(value: String): Int {
        return Regex("""\b(2160|1440|1080|720|480|360|240)\b""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun fixUrlSafe(url: String): String {
        val cleaned = url.trim().htmlUnescape().decodeEscaped()
        return when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("http://", true) || cleaned.startsWith("https://", true) -> cleaned
            cleaned.startsWith("/") -> "$mainUrl$cleaned"
            else -> "$mainUrl/${cleaned.trimStart('/')}"
        }
    }

    private data class VideoCandidate(
        val url: String,
        val label: String = ""
    )
}
