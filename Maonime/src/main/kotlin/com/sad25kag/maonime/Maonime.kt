package com.sad25kag.maonime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class Maonime : MainAPI() {
    override var mainUrl = "https://maonime.com"
    override var name = "Maonime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val maodriveUrl = "https://maodrive.xyz"

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "" to "Anime Terbaru",
        "anime/" to "Daftar Anime",
        "season/spring-2026/" to "Spring 2026",
        "genres/action/" to "Action",
        "genres/comedy/" to "Comedy",
        "genres/fantasy/" to "Fantasy",
        "genres/romance/" to "Romance",
        "genres/school/" to "School",
        "genres/shounen/" to "Shounen",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = browserHeaders).document
        val results = parseMaonimeCards(document).distinctBy { it.url.normalizedKey() }
        val hasNext = document.selectFirst("link[rel=next], .hpage a.r[href], .pagination a.next[href], a.next.page-numbers[href]") != null
        return newHomePageResponse(request.name, results, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val routes = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/anime/?s=$encoded",
        )

        return routes.flatMap { url ->
            runCatching {
                val document = app.get(url, headers = browserHeaders).document
                parseMaonimeCards(document)
            }.getOrDefault(emptyList())
        }.distinctBy { it.url.normalizedKey() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders).document
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, .title-section h1, h1[itemprop=name]")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ) ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl()
            ?: document.selectFirst(".bigcontent .thumb img, .thumb img, .tb img.wp-post-image, img.wp-post-image")?.imageUrl()

        val plot = document.select(".entry-content p")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() && !it.contains("Download ", true) && !it.contains("Watch ", true) }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.cleanText()

        val tags = document.select(".genxed a[href*='/genres/'], .info-content a[href*='/genres/'], a[rel=tag], a[href*='/genres/']")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = document.select(".spe span")
            .firstOrNull { it.text().contains("Released:", true) }
            ?.text()
            ?.substringAfter(":")
            ?.trim()
            ?.toIntOrNull()

        val episodes = parseEpisodes(document).distinctBy { it.data.normalizedKey() }
        val recommendations = parseMaonimeCards(document)
            .filterNot { it.url.normalizedKey() == url.normalizedKey() }
            .take(16)

        val isSeriesPage = url.contains("/anime/", true) || episodes.size > 1
        return if (isSeriesPage) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                this.showStatus = detectStatus(document)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Anime, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
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
        val document = app.get(data, headers = browserHeaders).document
        val emitted = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, label: String? = null, referer: String = data): Boolean {
            val videoUrl = rawUrl?.decodeEmbedText()?.toAbsoluteUrl(referer)?.takeIf { it.isDirectMediaLike() } ?: return false
            if (!emitted.add(videoUrl.substringBefore("#"))) return true
            val sourceName = listOfNotNull(name, label?.cleanText()?.takeIf { it.isNotBlank() }).joinToString(" - ")
            val headers = browserHeaders + mapOf(
                "Referer" to referer,
                "Origin" to originOf(referer),
            )
            val type = if (videoUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(sourceName, sourceName, videoUrl, type) {
                    this.referer = referer
                    this.quality = getQualityFromName(label ?: videoUrl)
                    this.headers = headers
                },
            )
            return true
        }

        val candidates = collectPlayerCandidates(document, data)
        for (candidate in candidates.take(30)) {
            val playerUrl = candidate.decodeEmbedText().toAbsoluteUrl(data) ?: continue
            if (emitDirect(playerUrl, hostLabel(playerUrl), data)) continue

            if (playerUrl.contains("maodrive.xyz/", true)) {
                resolveMaodrive(playerUrl, data, emitted, callback)
                continue
            }

            val before = emitted.size
            val countedCallback: (ExtractorLink) -> Unit = { link ->
                emitted.add(link.url.substringBefore("#"))
                callback.invoke(link)
            }
            runCatching { loadExtractor(playerUrl, data, subtitleCallback, countedCallback) }
            if (emitted.size > before) continue

            val playerHtml = runCatching {
                app.get(playerUrl, headers = browserHeaders + mapOf("Referer" to data), referer = data).text
            }.getOrNull() ?: continue
            val unpacked = runCatching { getAndUnpack(playerHtml) }.getOrNull().orEmpty()
            val nested = collectUrlsFromText(playerHtml + "\n" + unpacked, playerUrl)
            for (nestedUrl in nested.take(15)) {
                if (emitDirect(nestedUrl, hostLabel(playerUrl), playerUrl)) continue
                val fixedNested = nestedUrl.toAbsoluteUrl(playerUrl) ?: continue
                if (fixedNested.contains("maodrive.xyz/", true)) {
                    resolveMaodrive(fixedNested, playerUrl, emitted, callback)
                } else {
                    runCatching { loadExtractor(fixedNested, playerUrl, subtitleCallback, countedCallback) }
                }
            }
        }
        return emitted.isNotEmpty()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim().trimStart('/')
        if (cleanPath.isBlank()) return if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val base = if (cleanPath.startsWith("http", true)) cleanPath.trimEnd('/') else "$mainUrl/$cleanPath".trimEnd('/')
        return if (page <= 1) "$base/" else "$base/page/$page/"
    }

    private fun parseMaonimeCards(document: Document): List<SearchResponse> {
        val roots = listOf(
            document.select(".listupd article, .listupd .bsx"),
            document.select(".bs .bsx, article.bs"),
            document.select(".serieslist.pop ul li, .ongoingseries ul li"),
        )
        return roots.asSequence()
            .flatMap { it.asSequence() }
            .mapNotNull { it.toMaonimeCard() }
            .distinctBy { it.url.normalizedKey() }
            .toList()
    }

    private fun Element.toMaonimeCard(): SearchResponse? {
        val anchor = selectFirst(".bsx a[href], a.series[href], h2 a[href], h3 a[href], h4 a[href], a[href*='/anime/'], a[href*='episode']") ?: return null
        val href = anchor.attr("href").toAbsoluteUrl() ?: return null
        if (!href.contains(mainUrl, true)) return null

        val rawTitle = anchor.attr("title").cleanText().takeIf { it.length > 2 }
            ?: selectFirst(".tt, .eggtitle, h2, h3, h4, .limit")?.text()?.cleanText()?.takeIf { it.length > 2 }
            ?: anchor.text().cleanText().takeIf { it.length > 2 }
            ?: return null
        val title = cleanTitle(rawTitle) ?: rawTitle
        val poster = selectFirst("img")?.imageUrl() ?: anchor.selectFirst("img")?.imageUrl()
        return newMovieSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        val anchors = document.select(
            ".eplister li a[href], .episodelist li a[href], .naveps a[href*='episode'], a[rel=next][href], a[rel=prev][href]"
        )
        return anchors.mapNotNull { anchor ->
            val href = anchor.attr("href").toAbsoluteUrl() ?: return@mapNotNull null
            if (!href.contains("episode", true)) return@mapNotNull null
            val title = anchor.selectFirst(".epl-title, .playinfo h3, h3, .title")?.text()?.cleanText()
                ?: anchor.text().cleanText().takeIf { it.isNotBlank() }
                ?: href.substringAfter(mainUrl).trim('/').replace('-', ' ')
            val ep = anchor.selectFirst(".epl-num, .epx")?.text()?.toEpisodeNumber()
                ?: title.toEpisodeNumber()
                ?: href.toEpisodeNumber()
            newEpisode(href) {
                this.name = cleanTitle(title) ?: title
                this.episode = ep
                this.posterUrl = anchor.selectFirst("img")?.imageUrl()
            }
        }
    }

    private fun collectPlayerCandidates(document: Document, referer: String): LinkedHashSet<String> {
        val candidates = linkedSetOf<String>()
        document.select("#pembed iframe[src], .player-embed iframe[src], #embed_holder iframe[src], iframe[src]").forEach { iframe ->
            iframe.attr("src").takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        }
        document.select("select.mirror option[value], .mirror option[value]").forEach { option ->
            val value = option.attr("value").trim()
            if (value.isBlank()) return@forEach
            candidates.add(value)
            val decoded = runCatching { base64Decode(value) }.getOrNull()
            if (!decoded.isNullOrBlank()) {
                collectUrlsFromText(decoded, referer).forEach { candidates.add(it) }
            }
        }
        collectUrlsFromText(document.html(), referer).forEach { candidates.add(it) }
        return candidates
    }

    private suspend fun resolveMaodrive(
        playerUrl: String,
        pageReferer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        val fixedPlayerUrl = playerUrl.toAbsoluteUrl(maodriveUrl) ?: return
        val response = runCatching {
            app.get(
                fixedPlayerUrl,
                headers = browserHeaders + mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl),
                referer = "$mainUrl/",
            ).text
        }.getOrNull().orEmpty()
        val unpacked = runCatching { getAndUnpack(response) }.getOrNull().orEmpty()
        val sourceText = response + "\n" + unpacked
        val videoId = Regex("""/videos/([A-Za-z0-9_-]+)""").find(fixedPlayerUrl)?.groupValues?.getOrNull(1)

        val sources = linkedMapOf<String, String>()
        Regex("""\{[^{}]*['\"]label['\"]\s*:\s*['\"]([^'\"]+)['\"][^{}]*['\"]file['\"]\s*:\s*['\"]([^'\"]+)['\"][^{}]*} """.trim(), RegexOption.IGNORE_CASE)
            .findAll(sourceText)
            .forEach { match -> sources[match.groupValues[1]] = match.groupValues[2] }
        Regex("""\{[^{}]*['\"]file['\"]\s*:\s*['\"]([^'\"]+)['\"][^{}]*['\"]label['\"]\s*:\s*['\"]([^'\"]+)['\"][^{}]*} """.trim(), RegexOption.IGNORE_CASE)
            .findAll(sourceText)
            .forEach { match -> sources[match.groupValues[2]] = match.groupValues[1] }
        Regex("""['\"]file['\"]\s*:\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(sourceText)
            .forEach { match -> sources[qualityFromUrl(match.groupValues[1]) ?: "Maodrive"] = match.groupValues[1] }

        // HAR evidence: Maodrive JWPlayer emits /stream/{quality}/{id}/__001.
        if (!videoId.isNullOrBlank()) {
            sources.putIfAbsent("360p", "/stream/360/$videoId/__001")
            sources.putIfAbsent("720p", "/stream/720/$videoId/__001")
        }

        for ((label, raw) in sources) {
            val url = raw.toAbsoluteUrl(fixedPlayerUrl) ?: continue
            if (!emitted.add(url.substringBefore("#"))) continue
            callback.invoke(
                newExtractorLink(name, "$name - Maodrive $label", url, ExtractorLinkType.VIDEO) {
                    this.referer = fixedPlayerUrl
                    this.quality = getQualityFromName(label)
                    this.headers = browserHeaders + mapOf(
                        "Referer" to fixedPlayerUrl,
                        "Origin" to maodriveUrl,
                        "Range" to "bytes=0-",
                    )
                },
            )
        }
    }

    private fun collectUrlsFromText(text: String, base: String): List<String> {
        val normalized = text.decodeEmbedText()
        val urls = linkedSetOf<String>()
        Regex("""<(?:iframe|embed|source|video)[^>]+(?:src|data-src)=['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { urls.add(it.groupValues[1]) }
        Regex("""(?:src|file|url|source|embed|iframe|data-url|data-src|data-file|data-player)\s*[:=]\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { urls.add(it.groupValues[1]) }
        Regex("""https?://[^'\"<>()\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map { it.value.trimEnd(',', ';') }
            .forEach { urls.add(it) }
        Regex("""/(?:stream|videos)/[^'\"<>()\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { it.value.toAbsoluteUrl(base) }
            .forEach { urls.add(it) }
        return urls.filter { it.isPotentialPlayer() }
    }

    private fun detectStatus(document: Document): ShowStatus? {
        val status = document.select(".spe span")
            .firstOrNull { it.text().contains("Status:", true) }
            ?.text()
            ?.substringAfter(":")
            ?.trim()
            ?.lowercase(Locale.ROOT)
        return when {
            status == null -> null
            status.contains("completed") || status.contains("selesai") -> ShowStatus.Completed
            status.contains("ongoing") || status.contains("airing") || status.contains("tayang") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun Element.imageUrl(): String? {
        val raw = attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
            ?: attr("data-original").takeIf { it.isNotBlank() }
        return raw?.toAbsoluteUrl()
    }

    private fun cleanTitle(raw: String?): String? {
        return raw?.htmlUnescape()?.cleanText()
            ?.replace(Regex("""(?i)\s*[-–|]\s*Maonime.*$"""), "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.cleanText(): String = htmlUnescape()
        .replace("\u00a0", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.htmlUnescape(): String = Jsoup.parse(this).text()

    private fun String.decodeEmbedText(): String = htmlUnescape()
        .replace("\\/", "/")
        .replace("&quot;", "\"")
        .replace("&#038;", "&")
        .replace("&amp;", "&")
        .trim()

    private fun String.toAbsoluteUrl(base: String = mainUrl): String? {
        val clean = trim().trim('"', '\'')
        if (clean.isBlank() || clean.startsWith("javascript", true) || clean.startsWith("#")) return null
        return runCatching { URI(base).resolve(clean).toString() }.getOrNull()
    }

    private fun String.normalizedKey(): String = substringBefore("#").trimEnd('/').lowercase(Locale.ROOT)

    private fun String.toEpisodeNumber(): Int? = Regex("""(?i)(?:episode|eps?|ep)\s*(\d+)""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("""\b(\d{1,4})\b""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String.isDirectMediaLike(): Boolean {
        val value = lowercase(Locale.ROOT)
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".webm") || value.contains(".mkv") || value.contains("/stream/")
    }

    private fun String.isPotentialPlayer(): Boolean {
        val value = lowercase(Locale.ROOT)
        if (isDirectMediaLike()) return true
        return listOf(
            "maodrive.xyz", "iframe", "embed", "player", "streamsb", "vidhide", "filemoon", "streamtape", "dood", "mp4upload", "blogger", "googlevideo"
        ).any { value.contains(it) }
    }

    private fun qualityFromUrl(url: String): String? = Regex("""/(\d{3,4})(?:/|p\b)""").find(url)?.groupValues?.getOrNull(1)?.let { "${it}p" }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun hostLabel(url: String): String = runCatching { URI(url).host ?: url }.getOrDefault(url)
}
