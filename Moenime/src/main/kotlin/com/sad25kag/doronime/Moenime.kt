package com.sad25kag.moenime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class Moenime : MainAPI() {
    override var mainUrl = "https://moenime.com"
    override var name = "Moenime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val maodriveUrl = "https://maodrive.biz.id"
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

            if (playerUrl.isMaodriveUrl()) {
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
                if (fixedNested.isMaodriveUrl()) {
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
        val absolute = value.toAbsoluteUrl(referer) ?: value
        if (absolute.isPotentialPlayer()) candidates.add(value)
    }

    private fun String.isPotentialPlayer(): Boolean = this.contains(".mp4") || this.contains(".m3u8") || this.contains("maodrive") || this.contains("player")

    private fun String.isMaonimeContentUrl(): Boolean = this.contains("moenime.com", true) || this.contains("/anime/", true) || this.contains("/episode/", true)
    private fun String.isMaonimeEpisodeUrl(): Boolean = this.contains("/episode/", true) || this.contains("ep=", true)
    private fun String.isMaodriveUrl(): Boolean = this.contains(maodriveUrl, true) || this.contains("maodrive", true)
    private fun String.normalizedKey(): String = this.trim().lowercase().replace(Regex("""https?://"""), "").trimEnd('/')

    private fun String.toEpisodeNumber(): Int? {
        val num = Regex("""(?:episode|ep|eps?)\s*(\d+)""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.get(1)
            ?: Regex("""\b(\d{1,3})\b""").find(this)?.groupValues?.get(1)
        return num?.toIntOrNull()
    }

    private fun cleanTitle(title: String?): String? {
        return title?.replace(Regex("""\s*\(Subtitle Indonesia\)|\s*Subtitle Indonesia|\s*Sub Indo""", RegexOption.IGNORE_CASE), "")
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
    }

    private fun hostLabel(url: String): String? {
        return try { URI.create(url).host?.replace(Regex("""^www\.|\.com?$"""), "") } catch (e: Exception) { null }
    }

    private fun originOf(url: String): String {
        return try { "https://" + URI.create(url).host } catch (e: Exception) { mainUrl }
    }

    private fun detectStatus(document: Document): ShowStatus? {
        val statusText = document.selectFirst(".spe span, .info-content span, .infox span")?.text()?.lowercase() ?: ""
        return when {
            statusText.contains("ongoing") || statusText.contains("sedang tayang") -> ShowStatus.Ongoing
            statusText.contains("completed") || statusText.contains("selesai") -> ShowStatus.Completed
            else -> null
        }
    }

    private suspend fun emitMaodriveFromPage(html: String, referer: String, emitted: MutableSet<String>, callback: (ExtractorLink) -> Unit) {
        val maodriveMatch = Regex("""https?://maodrive\.biz\.id/[^'"\s>]+""").find(html)
        maodriveMatch?.value?.let { url ->
            resolveMaodrive(url, referer, emitted, callback)
        }
    }

    private suspend fun resolveMaodrive(playerUrl: String, pageReferer: String, emitted: MutableSet<String>, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val response = app.get(playerUrl, referer = pageReferer).text
            val directMatch = Regex("""['"](https?://[^'"]+\.mp4[^'"]*)['"]""").find(response) ?: Regex("""file['"]?\s*:\s*['"]([^'"]+)""").find(response)
            directMatch?.groupValues?.get(1)?.let { direct ->
                val cleanDirect = direct.toAbsoluteUrl(playerUrl)
                if (cleanDirect != null && emitted.add(cleanDirect)) {
                    callback(newExtractorLink(name, "Maodrive", cleanDirect, ExtractorLinkType.VIDEO) {
                        this.referer = playerUrl
                    })
                }
            }
        }
    }

    private fun String.cleanText(): String = replace(Regex("""\s+"""), " ").trim()

    private fun Element.imageUrl(base: String): String? {
        listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-img", "src", "poster").forEach { attr ->
            val value = attr(attr).trim()
            if (value.isNotBlank()) return value.toAbsoluteUrl(base)
        }
        listOf("srcset", "data-srcset").forEach { attr ->
            attr(attr)
                .split(',')
                .map { it.trim().substringBefore(' ').trim() }
                .firstOrNull { it.isNotBlank() }
                ?.let { return it.toAbsoluteUrl(base) }
        }
        return null
    }

    private fun String.decodeUrlText(): String = runCatching {
        URLDecoder.decode(this.replace("+", " "), "UTF-8")
    }.getOrDefault(this)

    private fun String.toAbsoluteUrl(): String? = toAbsoluteUrl(mainUrl)

    private fun String.toAbsoluteUrl(base: String): String? {
        val value = decodeUrlText().trim()
        if (value.isBlank()) return null
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> mainUrl.trimEnd('/') + value
            else -> base.trimEnd('/').substringBeforeLast('/', mainUrl) + "/" + value
        }
    }

    private fun getQualityFromName(name: String): Int {
        return Regex("""(2160|1440|1080|720|480|360|240)""").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun getAndUnpack(html: String): String = ""
    private fun collectUrlsFromText(text: String, base: String): List<String> = emptyList()
    private fun String.maodriveOrigin(): String? = maodriveUrl
    private fun qualityFromUrl(url: String): String? = null
    private fun base64Decode(s: String): String? = null
    private fun String.decodeLoose(): String? = this
    private fun String.isDirectMediaLike(): Boolean = this.contains(".mp4") || this.contains(".m3u8") || this.contains("/stream/")
}
