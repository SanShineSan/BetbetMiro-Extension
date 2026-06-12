package com.sad25kag.maonime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class Maonime : MainAPI() {
    override var mainUrl = "https://maonime.com"
    override var name = "Maonime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val maodriveUrl = "https://maodrive.xyz"
    private val maodriveUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "anime/" to "Daftar Anime",
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
        val hasNext = document.selectFirst("link[rel=next], .hpage a.r[href], .pagination a.next[href], a.next.page-numbers[href], .nav-links a.next[href]") != null
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
            document.selectFirst("h1.entry-title, .entry-title, .title-section h1, h1[itemprop=name], h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ) ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl(url)
            ?: document.selectFirst(".bigcontent .thumb img, .thumb img, .tb img.wp-post-image, img.wp-post-image, .post-thumb img, .entry-content img")?.imageUrl(url)

        val plot = document.select(".entry-content p, .postbody .entry-content p, .synopsis p, .sinopsis p, .desc p")
            .map { it.text().cleanText() }
            .filter {
                it.isNotBlank() &&
                    !it.contains("Download ", true) &&
                    !it.contains("Watch ", true) &&
                    !it.contains("Streaming ", true)
            }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.cleanText()

        val tags = document.select(".genxed a[href*='/genres/'], .info-content a[href*='/genres/'], a[rel=tag], a[href*='/genres/']")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = document.select(".spe span, .info-content span, .infox span")
            .firstOrNull { it.text().contains("Released:", true) || it.text().contains("Rilis:", true) || it.text().contains("Year:", true) }
            ?.text()
            ?.substringAfter(":")
            ?.trim()
            ?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(document.select(".spe, .info-content, .infox").text())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val episodes = parseEpisodes(document).distinctBy { it.data.normalizedKey() }
        val recommendations = parseMaonimeCards(document)
            .filterNot { it.url.normalizedKey() == url.normalizedKey() }
            .take(16)

        val hasPlayer = collectPlayerCandidates(document, url).isNotEmpty()
        val isSeriesPage = episodes.isNotEmpty() && !url.isMaonimeEpisodeUrl() && (url.contains("/anime/", true) || episodes.size > 1 || !hasPlayer)
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

        // HAR-backed fast path: Maonime episode pages expose exactly one Maodrive iframe.
        // Emit the verified /stream/360/{id}/__001 MP4 directly before generic extractor probing,
        // so playback does not depend on CloudStream having a Maodrive extractor.
        emitMaodriveFromPage(document.html(), data, emitted, callback)
        if (emitted.isNotEmpty()) return true

        suspend fun emitDirect(rawUrl: String?, label: String? = null, referer: String = data): Boolean {
            val videoUrl = rawUrl?.decodeUrlText()?.toAbsoluteUrl(referer)?.takeIf { it.isDirectMediaLike() } ?: return false
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
        for (candidate in candidates.take(40)) {
            val playerUrl = candidate.decodeUrlText().toAbsoluteUrl(data) ?: continue
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
            for (nestedUrl in nested.take(25)) {
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
            document.select(".listupd article, .listupd .bsx, .bs .bsx, article.bs"),
            document.select(".serieslist.pop ul li, .ongoingseries ul li, .postbody article, .post article"),
            document.select("article, .bsx, .bs, .post, .post-item, .animepost, .result, .item"),
        )
        return roots.asSequence()
            .flatMap { it.asSequence() }
            .mapNotNull { it.toMaonimeCard() }
            .distinctBy { it.url.normalizedKey() }
            .toList()
    }

    private fun Element.toMaonimeCard(): SearchResponse? {
        val anchor = selectFirst(
            ".bsx a[href], a.series[href], .tt a[href], h2 a[href], h3 a[href], h4 a[href], .limit a[href], a[href*='/anime/'], a[href*='episode']"
        ) ?: selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").toAbsoluteUrl() ?: return null
        if (!href.isMaonimeContentUrl()) return null

        val rawTitle = anchor.attr("title").cleanText().takeIf { it.length > 2 }
            ?: selectFirst(".tt, .eggtitle, h2, h3, h4, .limit, .entry-title, .post-title")?.text()?.cleanText()?.takeIf { it.length > 2 }
            ?: anchor.text().cleanText().takeIf { it.length > 2 }
            ?: selectFirst("img")?.attr("alt")?.cleanText()?.takeIf { it.length > 2 }
            ?: return null
        val title = cleanTitle(rawTitle) ?: rawTitle
        val poster = selectFirst("img")?.imageUrl(href) ?: anchor.selectFirst("img")?.imageUrl(href)
        return newMovieSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        val anchors = document.select(
            listOf(
                ".eplister li a[href]",
                ".episodelist li a[href]",
                ".episode-list a[href]",
                ".episodes a[href]",
                ".epslist a[href]",
                ".epcheck a[href]",
                ".naveps a[href*='episode']",
                ".bxcl ul li a[href]",
                ".entry-content a[href*='episode']",
                "article a[href*='episode']",
            ).joinToString(",")
        )
        return anchors.mapNotNull { anchor ->
            val href = anchor.attr("href").toAbsoluteUrl() ?: return@mapNotNull null
            if (!href.isMaonimeEpisodeUrl()) return@mapNotNull null
            val title = anchor.selectFirst(".epl-title, .playinfo h3, h3, .title, .ep-title, .eptitle")?.text()?.cleanText()
                ?: anchor.attr("title").cleanText().takeIf { it.isNotBlank() }
                ?: anchor.text().cleanText().takeIf { it.isNotBlank() }
                ?: href.substringAfter(mainUrl).trim('/').replace('-', ' ')
            val ep = anchor.selectFirst(".epl-num, .epx, .num, .ep-num")?.text()?.toEpisodeNumber()
                ?: title.toEpisodeNumber()
                ?: href.toEpisodeNumber()
            newEpisode(href) {
                this.name = cleanTitle(title) ?: title
                this.episode = ep
                this.posterUrl = anchor.selectFirst("img")?.imageUrl(href)
            }
        }
    }

    private fun collectPlayerCandidates(document: Document, referer: String): LinkedHashSet<String> {
        val candidates = linkedSetOf<String>()
        document.select("#pembed iframe[src], .player-embed iframe[src], #embed_holder iframe[src], .player iframe[src], .embed iframe[src], iframe[src], iframe[data-src]").forEach { iframe ->
            iframe.attr("src").addCandidateValue(candidates, referer)
            iframe.attr("data-src").addCandidateValue(candidates, referer)
        }
        document.select("select.mirror option[value], .mirror option[value], select option[value]").forEach { option ->
            option.attr("value").addCandidateValue(candidates, referer)
        }
        document.select("[data-src], [data-url], [data-file], [data-player], [data-video], [data-iframe], [data-embed], [data-href], [value]").forEach { element ->
            listOf("data-src", "data-url", "data-file", "data-player", "data-video", "data-iframe", "data-embed", "data-href", "value")
                .forEach { attr -> element.attr(attr).addCandidateValue(candidates, referer) }
        }
        collectUrlsFromText(document.html(), referer).forEach { candidates.add(it) }
        return candidates
    }

    private fun String.addCandidateValue(candidates: MutableSet<String>, referer: String) {
        val value = trim()
        if (value.isBlank()) return

        fun addIfPlayerCandidate(raw: String?) {
            val candidate = raw?.trim()?.takeIf { it.isNotBlank() } ?: return
            val absolute = candidate.toAbsoluteUrl(referer) ?: candidate
            if (absolute.isPotentialPlayer()) candidates.add(candidate)
        }

        addIfPlayerCandidate(value)
        decodeLoose()?.takeIf { it.isNotBlank() && it != value }?.let { decoded ->
            addIfPlayerCandidate(decoded)
            collectUrlsFromText(decoded, referer).forEach { candidates.add(it) }
        }
        val decodedBase64 = runCatching { base64Decode(value) }.getOrNull()
        if (!decodedBase64.isNullOrBlank()) {
            addIfPlayerCandidate(decodedBase64)
            collectUrlsFromText(decodedBase64, referer).forEach { candidates.add(it) }
        }
    }

    private suspend fun resolveMaodrive(
        playerUrl: String,
        pageReferer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        val fixedPlayerUrl = playerUrl.toAbsoluteUrl(maodriveUrl) ?: return
        val videoId = Regex("""/videos/([A-Za-z0-9_-]+)""").find(fixedPlayerUrl)?.groupValues?.getOrNull(1)

        val response = runCatching {
            app.get(
                fixedPlayerUrl,
                headers = browserHeaders + mapOf("Referer" to pageReferer),
                referer = pageReferer,
            ).text
        }.getOrNull().orEmpty()
        val unpacked = runCatching { getAndUnpack(response) }.getOrNull().orEmpty()
        val sourceText = response + "\n" + unpacked

        val sources = linkedMapOf<String, String>()

        fun addMedia(label: String?, raw: String?) {
            val url = raw?.decodeUrlText()?.toAbsoluteUrl(fixedPlayerUrl) ?: return
            if (!url.isDirectMediaLike()) return
            val safeLabel = label?.cleanText()?.takeIf { it.isNotBlank() }
                ?: qualityFromUrl(url)
                ?: "Maodrive"
            sources.putIfAbsent(safeLabel, url)
        }

        Regex("""\{[^{}]*['\"]label['\"]\s*:\s*['\"]([^'\"]+)['\"][^{}]*['\"]file['\"]\s*:\s*['\"]([^'\"]+)['\"][^{}]*}""", RegexOption.IGNORE_CASE)
            .findAll(sourceText)
            .forEach { match -> addMedia(match.groupValues[1], match.groupValues[2]) }
        Regex("""\{[^{}]*['\"]file['\"]\s*:\s*['\"]([^'\"]+)['\"][^{}]*['\"]label['\"]\s*:\s*['\"]([^'\"]+)['\"][^{}]*}""", RegexOption.IGNORE_CASE)
            .findAll(sourceText)
            .forEach { match -> addMedia(match.groupValues[2], match.groupValues[1]) }
        Regex("""['\"]file['\"]\s*:\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(sourceText)
            .forEach { match -> addMedia(qualityFromUrl(match.groupValues[1]), match.groupValues[1]) }
        collectUrlsFromText(sourceText, fixedPlayerUrl)
            .filter { it.isDirectMediaLike() }
            .forEach { media -> addMedia(qualityFromUrl(media), media) }

        // HAR evidence: Maodrive playback succeeds through /stream/360/{id}/__001 with
        // Referer=https://maodrive.xyz/videos/{id}, Range=bytes=0-, and no Origin header.
        if (!videoId.isNullOrBlank()) {
            val absoluteStreamUrl = "$maodriveUrl/stream/360/$videoId/__001"
            addMedia("360p", absoluteStreamUrl)
        }

        for ((label, url) in sources) {
            emitMaodriveDirect(url, label, fixedPlayerUrl, emitted, callback)
        }
    }

    private suspend fun emitMaodriveFromPage(
        html: String,
        pageReferer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        val normalized = html.decodeHtmlSource()
        val ids = linkedSetOf<String>()

        Regex("""https?://(?:www\.)?maodrive\.xyz/videos/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { match -> ids.add(match.groupValues[1]) }
        Regex("""(?:src|value|data-src|data-url|data-file|data-player|data-video|data-iframe|data-embed)\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { match ->
                val raw = match.groupValues[1].decodeUrlText()
                Regex("""https?://(?:www\.)?maodrive\.xyz/videos/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                    .find(raw)?.groupValues?.getOrNull(1)?.let { ids.add(it) }
                runCatching { base64Decode(raw) }.getOrNull()?.decodeUrlText()?.let { decoded ->
                    Regex("""https?://(?:www\.)?maodrive\.xyz/videos/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                        .find(decoded)?.groupValues?.getOrNull(1)?.let { ids.add(it) }
                }
            }

        ids.forEach { id ->
            val playerUrl = "$maodriveUrl/videos/$id"
            val streamUrl = "$maodriveUrl/stream/360/$id/__001"
            emitMaodriveDirect(streamUrl, "360p", playerUrl, emitted, callback)
        }
    }

    private suspend fun emitMaodriveDirect(
        url: String,
        label: String,
        playerUrl: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (!url.isDirectMediaLike()) return
        if (!emitted.add(url.substringBefore("#"))) return
        val type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback.invoke(
            newExtractorLink(name, "$name - Maodrive $label", url, type) {
                this.referer = playerUrl
                this.quality = getQualityFromName(label)
                this.headers = maodriveVideoHeaders(playerUrl)
            },
        )
    }

    private fun maodriveVideoHeaders(playerUrl: String): Map<String, String> = mapOf(
        "User-Agent" to maodriveUserAgent,
        "Accept" to "*/*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept-Encoding" to "identity;q=1, *;q=0",
        "Referer" to playerUrl,
        "Range" to "bytes=0-",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-Mode" to "no-cors",
        "Sec-Fetch-Dest" to "video",
    )

    private fun collectUrlsFromText(text: String, base: String): List<String> {
        val normalized = text.decodeHtmlSource()
        val urls = linkedSetOf<String>()
        Regex("""<(?:iframe|embed|source|video)[^>]+(?:src|data-src)=['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { urls.add(it.groupValues[1]) }
        Regex("""(?:src|file|url|source|embed|iframe|data-url|data-src|data-file|data-player|data-video|data-iframe|data-embed)\s*[:=]\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { urls.add(it.groupValues[1]) }
        Regex("""atob\(['\"]([^'\"]+)['\"]\)""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { runCatching { base64Decode(it.groupValues[1]) }.getOrNull() }
            .forEach { decoded -> collectUrlsFromText(decoded, base).forEach { urls.add(it) } }
        Regex("""https?://[^'\"<>()\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map { it.value.trimEnd(',', ';') }
            .forEach { urls.add(it) }
        Regex("""/(?:stream|videos)/[^'\"<>()\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { it.value.toAbsoluteUrl(base) }
            .forEach { urls.add(it) }
        return urls.mapNotNull { it.decodeUrlText().toAbsoluteUrl(base) }
            .filter { it.isPotentialPlayer() }
            .distinctBy { it.normalizedKey() }
    }

    private fun detectStatus(document: Document): ShowStatus? {
        val status = document.select(".spe span, .info-content span, .infox span")
            .firstOrNull { it.text().contains("Status:", true) || it.text().contains("Status", true) }
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

    private fun Element.imageUrl(base: String = mainUrl): String? {
        val raw = attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("data-original").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
        return raw?.toAbsoluteUrl(base)
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

    private fun String.decodeUrlText(): String = decodeHtmlSource().trim()

    private fun String.decodeHtmlSource(): String = replace("\\/", "/")
        .replace("\\u002F", "/", true)
        .replace("\\u003A", ":", true)
        .replace("\\u003D", "=", true)
        .replace("\\u003F", "?", true)
        .replace("\\u0026", "&", true)
        .replace("\\u0025", "%", true)
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#039;", "'")
        .replace("&#038;", "&")
        .replace("&amp;", "&")

    private fun String.decodeLoose(): String? {
        return runCatching { URLDecoder.decode(this, "UTF-8") }.getOrNull()
    }

    private fun String.toAbsoluteUrl(base: String = mainUrl): String? {
        val clean = trim().trim('"', '\'')
        if (clean.isBlank() || clean.startsWith("javascript", true) || clean.startsWith("#")) return null
        return runCatching { URI(base).resolve(clean).toString() }.getOrNull()
    }

    private fun String.normalizedKey(): String = substringBefore("#").trimEnd('/').lowercase(Locale.ROOT)

    private fun String.toEpisodeNumber(): Int? = Regex("""(?i)(?:episode|eps?|ep)[\s\-_]*(\d{1,4})""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("""\b(\d{1,4})\b""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String.isDirectMediaLike(): Boolean {
        val value = lowercase(Locale.ROOT)
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".webm") || value.contains(".mkv") || value.contains("/stream/")
    }

    private fun String.isPotentialPlayer(): Boolean {
        val value = lowercase(Locale.ROOT)
        if (isDirectMediaLike()) return true
        if (listOf("/embed", "/iframe", "/player", "/e/", "/v/").any { value.contains(it) }) return true
        return listOf(
            "maodrive.xyz", "iframe", "embed", "player",
            "streamsb", "streamtape", "streamwish", "streamhide", "streamruby", "streamlare", "streamhub", "streamvid",
            "vidhide", "vidguard", "vidmoly", "vidcloud", "vidoza",
            "filemoon", "filelions", "dood", "mp4upload", "blogger", "googlevideo", "ok.ru", "uqload", "mixdrop", "voe.sx", "short.ink"
        ).any { value.contains(it) }
    }

    private fun String.isMaonimeContentUrl(): Boolean {
        val value = normalizedKey()
        if (!value.isSameMaonimeHost()) return false
        val path = value.pathLower()
        if (path.isBlank() || path == "/") return false
        return !listOf("/genres/", "/genre/", "/season/", "/tag/", "/page/", "/category/").any { path.contains(it) }
    }

    private fun String.isMaonimeEpisodeUrl(): Boolean {
        val value = normalizedKey()
        if (!value.isSameMaonimeHost()) return false
        val path = value.pathLower()
        return path.contains("episode") || Regex("""[-/](?:ep|eps)[-/]?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(path)
    }

    private fun String.isSameMaonimeHost(): Boolean = runCatching {
        val host = URI(this).host?.removePrefix("www.") ?: return@runCatching false
        val mainHost = URI(mainUrl).host?.removePrefix("www.") ?: return@runCatching false
        host == mainHost
    }.getOrDefault(false)

    private fun String.pathLower(): String = runCatching {
        URI(this).path.orEmpty().lowercase(Locale.ROOT)
    }.getOrDefault(this)

    private fun qualityFromUrl(url: String): String? = Regex("""/(\d{3,4})(?:/|p\b)""").find(url)?.groupValues?.getOrNull(1)?.let { "${it}p" }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun hostLabel(url: String): String = runCatching { URI(url).host ?: url }.getOrDefault(url)
}
