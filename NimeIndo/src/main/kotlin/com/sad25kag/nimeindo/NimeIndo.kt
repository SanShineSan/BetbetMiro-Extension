package com.sad25kag.nimeindo

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class NimeIndo : MainAPI() {
    override var mainUrl = "https://v1.nimeindo.org"
    override var name = "NimeIndo"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val chromeUa = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"

    private val siteHeaders = mapOf(
        "User-Agent" to chromeUa,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Sec-Ch-Ua" to "\"Google Chrome\";v=\"125\", \"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Rilisan Terbaru",
        "$mainUrl/anime-ongoing/" to "Anime Sedang Tayang",
        "$mainUrl/donghua-ongoing/" to "Donghua Sedang Tayang",
        "$mainUrl/anime/" to "Anime List",
        "$mainUrl/donghua/" to "Donghua",
        "$mainUrl/jadwal-rilis/" to "Jadwal Rilis",
        "$mainUrl/genres/action/" to "Action",
        "$mainUrl/genres/fantasy/" to "Fantasy",
        "$mainUrl/genres/comedy/" to "Comedy"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = siteHeaders, referer = "$mainUrl/").document
        val items = document.parseCards().distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = document.hasNextPage(page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/index.php?s=$encoded",
            "$mainUrl/search/$encoded/"
        )
        val results = linkedMapOf<String, SearchResponse>()
        for (url in urls) {
            val document = runCatching { app.get(url, headers = siteHeaders, referer = "$mainUrl/").document }.getOrNull() ?: continue
            val parsed = document.parseCards()
            parsed.filter { it.name.contains(cleanQuery, ignoreCase = true) }.forEach { item ->
                results[item.url] = item
            }
            if (results.isNotEmpty()) break
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = absoluteUrl(url, mainUrl) ?: return null
        val document = app.get(fixedUrl, headers = siteHeaders, referer = "$mainUrl/").document
        val seriesDocument = document.resolveSeriesDocument(fixedUrl)
        val metaDocument = seriesDocument ?: document

        val title = metaDocument.seriesTitle()
            ?: document.seriesTitle()
            ?: document.bestTitle()?.cleanTitle()
            ?: fixedUrl.slugTitle()
        val poster = metaDocument.bestPoster() ?: document.bestPoster()
        val plot = metaDocument.bestPlot() ?: document.bestPlot()
        val tags = metaDocument.genreTags().ifEmpty { document.genreTags() }
        val year = metaDocument.parseYear() ?: document.parseYear()
        val type = metaDocument.tvType(title, fixedUrl)
        val recommendations = document.parseCards().filter { it.url != fixedUrl }.distinctBy { it.url }.take(16)

        val parsedEpisodes = (seriesDocument?.parseEpisodes(poster).orEmpty() + document.parseEpisodes(poster))
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.episode ?: Int.MAX_VALUE }, { it.name.orEmpty() }))
        val generatedFromKnownRange = parsedEpisodes.generatedMissingEpisodes(poster)
        val episodes = (parsedEpisodes + generatedFromKnownRange)
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.episode ?: Int.MAX_VALUE }, { it.name.orEmpty() }))
            .ifEmpty { document.generatedEpisodesFromCurrent(fixedUrl, poster) }
            .ifEmpty { document.singlePlayableEpisode(fixedUrl, poster, title) }

        return newAnimeLoadResponse(title, fixedUrl, type) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = absoluteUrl(data, mainUrl) ?: return false
        val response = runCatching { app.get(pageUrl, headers = siteHeaders, referer = "$mainUrl/") }.getOrNull() ?: return false
        val document = response.document
        val candidates = linkedSetOf<PlayerCandidate>()
        val emitted = linkedSetOf<String>()
        var hasLinks = false

        fun addCandidate(raw: String?, label: String? = null, referer: String = pageUrl) {
            val fixed = absoluteUrl(raw?.basicHtmlDecode()?.unescapeJs(), referer) ?: return
            if (fixed.isNoiseUrl()) return
            if (!fixed.isPlayableCandidate()) return
            candidates.add(PlayerCandidate(fixed, label?.cleanLabel(), referer))
        }

        document.select(".player-embed iframe[src], #pembed iframe[src], iframe[src], embed[src], video[src], source[src]").forEach { element ->
            addCandidate(element.attr("src"), element.attr("title").ifBlank { element.attr("name") }, pageUrl)
        }
        document.select("video source[src], track[src]").forEach { element ->
            if (element.tagName().equals("track", true)) {
                absoluteUrl(element.attr("src"), pageUrl)?.let { subtitleCallback(SubtitleFile(element.attr("label").ifBlank { "Subtitle" }, it)) }
            } else {
                addCandidate(element.attr("src"), element.attr("label").ifBlank { element.attr("res") }, pageUrl)
            }
        }
        document.select("select.mirror option[value], option[value]").forEach { option ->
            val label = option.text().cleanLabel()
            val value = option.attr("value").trim()
            if (value.isBlank()) return@forEach
            addCandidate(value, label, pageUrl)
            decodePossibleBase64(value)?.let { decoded ->
                Jsoup.parse(decoded, pageUrl).select("iframe[src], embed[src], video[src], source[src]").forEach { addCandidate(it.attr("src"), label, pageUrl) }
                decoded.extractUrls().forEach { addCandidate(it, label, pageUrl) }
            }
        }
        response.text.basicHtmlDecode().unescapeJs().extractUrls().forEach { addCandidate(it, null, pageUrl) }

        suspend fun emitDirect(rawUrl: String, sourceLabel: String, referer: String): Boolean {
            val fixed = absoluteUrl(rawUrl, referer) ?: return false
            val key = fixed.normalizedMediaKey()
            if (!emitted.add(key)) return false
            val label = sourceLabel.ifBlank { fixed.qualityLabelFromUrl().ifBlank { name } }
            val mediaHeaders = mediaHeaders(fixed, referer)
            val mediaReferer = mediaReferer(fixed, referer)

            if (fixed.contains(".m3u8", true)) {
                val links = runCatching { M3u8Helper.generateM3u8(label, fixed, mediaReferer, headers = mediaHeaders) }.getOrDefault(emptyList())
                links.forEach { callback(it) }
                if (links.isNotEmpty()) return true
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = fixed,
                    type = if (fixed.contains(".m3u8", true)) INFER_TYPE else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mediaReferer
                    this.quality = fixed.parseQuality() ?: Qualities.Unknown.value
                    this.headers = mediaHeaders
                }
            )
            return true
        }

        suspend fun resolveCandidate(candidate: PlayerCandidate) {
            val fixed = candidate.url
            val label = candidate.label.orEmpty().ifBlank { fixed.hostLabel() }
            if (fixed.isDirectMedia()) {
                if (emitDirect(fixed, label, candidate.referer)) hasLinks = true
                return
            }

            if (fixed.isDailymotion()) {
                resolveDailymotion(fixed, candidate.referer).forEach { media ->
                    if (emitDirect(media, "Dailymotion", fixed)) hasLinks = true
                }
                if (hasLinks) return
            }

            if (fixed.isBlogger()) {
                resolveBlogger(fixed, candidate.referer).forEach { media ->
                    if (emitBloggerVideoLink(media, candidate.label, emitted, callback)) hasLinks = true
                }
                if (hasLinks) return
            }

            runCatching {
                loadExtractor(fixed, candidate.referer, subtitleCallback) { link ->
                    val key = link.url.normalizedMediaKey()
                    if (emitted.add(key)) {
                        hasLinks = true
                        callback(link)
                    }
                }
            }

            if (!hasLinks && fixed.shouldInspectInline()) {
                val text = runCatching { app.get(fixed, headers = playerHeaders(candidate.referer), referer = candidate.referer).text }.getOrNull().orEmpty()
                text.basicHtmlDecode().unescapeJs().extractUrls().forEach { found ->
                    if (found.isDirectMedia()) {
                        if (emitDirect(found, label, fixed)) hasLinks = true
                    }
                }
            }
        }

        candidates.distinctBy { it.url.normalizedMediaKey() }.take(24).forEach { candidate ->
            runCatching { resolveCandidate(candidate) }
        }
        return hasLinks
    }

    private fun buildPageUrl(data: String, page: Int): String {
        if (page <= 1) return data
        return data.trimEnd('/') + "/page/$page/"
    }

    private fun Document.parseCards(): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val selectors = listOf(
            "article.bs .bsx",
            ".listupd article.bs",
            ".listupd .bsx",
            ".bsx",
            ".animepost",
            ".postbody article"
        )
        selectors.flatMap { select(it) }.forEach { card ->
            card.toSearchResponseStrict()?.let { results[it.url] = it }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResponseStrict(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = absoluteUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!href.isContentUrl()) return null

        val rawTitle = listOf(
            selectFirst(".tt h2, .tt, h2, h3")?.text(),
            anchor.attr("title"),
            selectFirst("img[alt]")?.attr("alt"),
            selectFirst(".eggtitle")?.text()
        ).firstOrNull { !it.isNullOrBlank() } ?: return null
        val title = rawTitle.cleanCardTitle().takeIf { it.isValidContentTitle() } ?: return null
        val poster = bestImage()?.let { absoluteUrl(it, mainUrl) }
        val episode = listOf(
            selectFirst(".eggepisode")?.text(),
            selectFirst(".epx")?.text(),
            text(),
            href
        ).firstNotNullOfOrNull { it?.parseEpisodeNumber() }
        val tvType = when {
            title.contains("Movie", true) || href.contains("movie", true) -> TvType.AnimeMovie
            title.contains("OVA", true) || title.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, tvType) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private suspend fun Document.resolveSeriesDocument(currentUrl: String): Document? {
        if (currentUrl.contains("/anime/")) return this
        val seriesUrl = selectFirst(".naveps a[aria-label*='Semua'], .naveps .nvsc a[href], .ts-breadcrumb a[href*='/anime/']")
            ?.attr("href")
            ?.let { absoluteUrl(it, currentUrl) }
            ?.takeIf { it.contains("/anime/") }
            ?: return null
        return runCatching { app.get(seriesUrl, headers = siteHeaders, referer = currentUrl).document }.getOrNull()
    }

    private fun Document.parseEpisodes(defaultPoster: String?): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()
        select(".epcheck .eplister ul li a[href], .eplister ul li a[href], .eplister li a[href]").forEach { anchor ->
            anchor.toEpisodeStrict(defaultPoster)?.let { episodes[it.data] = it }
        }
        return episodes.values.toList()
    }

    private fun Element.toEpisodeStrict(defaultPoster: String?): Episode? {
        val href = absoluteUrl(attr("href"), mainUrl) ?: return null
        if (!href.isEpisodeUrl()) return null
        val number = selectFirst(".epl-num")?.text()?.parseEpisodeNumberLoose() ?: href.parseEpisodeNumber()
        val rawName = listOf(
            selectFirst(".epl-title")?.text(),
            attr("title"),
            text()
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        val cleanName = rawName.cleanEpisodeTitle(number)
        return newEpisode(href) {
            this.name = cleanName
            this.episode = number
            this.posterUrl = bestImage()?.let { absoluteUrl(it, mainUrl) } ?: defaultPoster
        }
    }

    private fun Document.currentEpisode(url: String, poster: String?, seriesTitle: String): Episode {
        val pageTitle = selectFirst("h1.entry-title, .entry-title")?.text()?.cleanTitle()
            ?: url.slugTitle()
        val number = pageTitle.parseEpisodeNumber() ?: url.parseEpisodeNumber()
        return newEpisode(url) {
            this.name = number?.let { "Episode $it" } ?: pageTitle.removePrefix(seriesTitle).cleanTitle().ifBlank { "Episode" }
            this.episode = number
            this.posterUrl = poster
        }
    }

    private fun Document.generatedEpisodesFromCurrent(url: String, poster: String?): List<Episode> {
        val current = url.parseEpisodeNumber() ?: return emptyList()
        val prefix = url.substringBeforeLast("-episode-")
        if (prefix == url || current !in 1..2000) return emptyList()
        return (1..current).map { episodeNumber ->
            val episodeUrl = "$prefix-episode-$episodeNumber/"
            newEpisode(episodeUrl) {
                this.name = "Episode $episodeNumber"
                this.episode = episodeNumber
                this.posterUrl = poster
            }
        }
    }

    private fun List<Episode>.generatedMissingEpisodes(poster: String?): List<Episode> {
        val maxEpisode = mapNotNull { it.episode }.maxOrNull() ?: return emptyList()
        if (maxEpisode !in 1..2000) return emptyList()
        val sampleUrl = firstOrNull { it.data.parseEpisodeNumber() != null }?.data ?: return emptyList()
        val prefix = sampleUrl.substringBeforeLast("-episode-")
        if (prefix == sampleUrl) return emptyList()
        val existing = mapNotNull { it.episode }.toSet()
        return (1..maxEpisode).filterNot { it in existing }.map { episodeNumber ->
            val episodeUrl = "$prefix-episode-$episodeNumber/"
            newEpisode(episodeUrl) {
                this.name = "Episode $episodeNumber"
                this.episode = episodeNumber
                this.posterUrl = poster
            }
        }
    }

    private fun Document.singlePlayableEpisode(url: String, poster: String?, title: String): List<Episode> {
        if (!url.isEpisodeUrl() && !hasEmbeddedPlayer()) return emptyList()
        return listOf(currentEpisode(url, poster, title))
    }

    private fun Document.hasEmbeddedPlayer(): Boolean {
        return selectFirst(".player-embed iframe[src], #pembed iframe[src], iframe[src*='dailymotion'], iframe[src*='blogger.com/video.g'], iframe[src*='filedon'], iframe[src*='ok.ru'], iframe[src*='short.icu'], iframe[src*='turbovidhls']") != null
    }

    private fun Document.seriesTitle(): String? {
        val candidates = listOfNotNull(
            selectFirst(".single-info .infox h2[itemprop=partOfSeries]")?.text(),
            selectFirst(".infox h2[itemprop=partOfSeries]")?.text(),
            selectFirst(".ts-breadcrumb a[href*='/anime/']")?.text(),
            selectFirst("h1.entry-title")?.text(),
            selectFirst(".entry-title")?.text()
        )
        return candidates
            .map { it.cleanTitle().removeEpisodeSuffix() }
            .firstOrNull { it.isValidContentTitle() }
    }

    private fun Document.bestTitle(): String? {
        val candidates = listOfNotNull(
            selectFirst(".single-info .infox h2[itemprop=partOfSeries]")?.text(),
            selectFirst(".infox h2[itemprop=partOfSeries]")?.text(),
            selectFirst("h1.entry-title")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst("meta[property=og:title]")?.attr("content"),
            selectFirst("meta[name=twitter:title]")?.attr("content"),
            selectFirst("title")?.text()
        )
        return candidates
            .map { it.substringBefore("»").substringBefore("|").cleanTitle().removeEpisodeSuffix() }
            .firstOrNull { it.isValidContentTitle() }
    }

    private fun Document.bestPoster(): String? {
        val raw = listOfNotNull(
            selectFirst(".single-info .thumb img")?.bestImage(),
            selectFirst(".bigcontent .thumb img")?.bestImage(),
            selectFirst(".thumb img")?.bestImage(),
            selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content"),
            selectFirst("article img")?.bestImage()
        ).firstOrNull { it.isNotBlank() }
        return raw?.let { absoluteUrl(it, mainUrl) }
    }

    private fun Document.bestPlot(): String? {
        val candidates = mutableListOf<String>()
        select(".entry-content[itemprop=description] p, .entry-content > p, .entry-content p").forEach { paragraph ->
            val text = paragraph.text().cleanText()
            if (text.isNotBlank()) candidates.add(text)
        }
        listOfNotNull(
            selectFirst(".single-info .desc, .infox .desc, .desc.mindes")?.text(),
            selectFirst("meta[property=og:description]")?.attr("content"),
            selectFirst("meta[name=description]")?.attr("content")
        ).forEach { candidates.add(it.cleanText()) }
        return candidates.firstOrNull { it.isRealSynopsis() }
    }

    private fun Document.genreTags(): List<String> {
        return select(".single-info .genxed a, .genxed a")
            .map { it.text().cleanLabel() }
            .filter { it.isRealGenre() }
            .distinct()
            .take(20)
    }

    private fun Document.parseYear(): Int? {
        val fromSpe = select(".spe span").firstNotNullOfOrNull { span ->
            val text = span.text()
            if (text.contains("Dirilis", true) || text.contains("Released", true)) text.parseYear() else null
        }
        return fromSpe ?: text().parseYear()
    }

    private fun Document.tvType(title: String, url: String): TvType {
        val typeText = select(".spe span").firstOrNull { it.text().contains("Tipe", true) }?.text().orEmpty()
        return when {
            title.contains("Movie", true) || url.contains("movie", true) || typeText.contains("Movie", true) -> TvType.AnimeMovie
            title.contains("OVA", true) || title.contains("Special", true) || typeText.contains("OVA", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        return selectFirst("a[rel=next], .pagination a[href*='/page/${page + 1}/'], .hpage a[href*='/page/${page + 1}/']") != null
    }

    private suspend fun resolveDailymotion(url: String, referer: String): List<String> {
        val id = url.extractDailymotionId() ?: return emptyList()
        val embedder = URLEncoder.encode(mainUrl + "/", "UTF-8")
        val playerId = url.extractDailymotionPlayerId()
        val apiUrls = linkedSetOf(
            "https://geo.dailymotion.com/video/$id.json?legacy=true&embedder=$embedder&geo=1&player-id=${playerId ?: "default"}&locale=id",
            "https://geo.dailymotion.com/video/$id.json?legacy=true&embedder=$embedder&geo=1&player-id=default&locale=id",
            "https://geo.dailymotion.com/video/$id.json?legacy=true&embedder=$embedder&geo=1&locale=id"
        )
        val results = linkedSetOf<String>()
        for (apiUrl in apiUrls) {
            val text = runCatching {
                app.get(apiUrl, headers = dailymotionHeaders(), referer = url).text
            }.getOrNull().orEmpty().basicHtmlDecode().unescapeJs()
            text.extractUrls()
                .filter { it.contains(".m3u8", true) || it.contains("/cdn/manifest/video/", true) || it.contains("/manifest/", true) }
                .forEach(results::add)
            if (results.isNotEmpty()) break
        }
        if (results.isEmpty()) {
            val playerHtml = runCatching { app.get(url, headers = dailymotionHeaders(), referer = referer).text }.getOrNull().orEmpty().basicHtmlDecode().unescapeJs()
            playerHtml.extractUrls()
                .filter { it.contains(".m3u8", true) || it.contains("/cdn/manifest/video/", true) || it.contains("/manifest/", true) }
                .forEach(results::add)
        }
        return results.toList()
    }

    private suspend fun resolveBlogger(url: String, referer: String): List<String> {
        return extractBloggerDirectVideos(url, referer)
    }

    private suspend fun extractBloggerDirectVideos(url: String, referer: String): List<String> {
        val token = Regex("""[?&]token=([^&]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val page = runCatching {
            app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to chromeUa,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
        }.getOrNull() ?: return emptyList()

        val html = page.text
        val cookies = page.cookies
        val fSid = Regex("""FdrFJe":"(-?\d+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""FdrFJe["']?\s*:\s*["'](-?\d+)""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
            ?: return emptyList()
        val bl = Regex("""cfb2h":"([^"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""boq_bloggeruiserver_[0-9A-Za-z._-]+""")
                .find(html)
                ?.value
            ?: return emptyList()
        val hl = Regex("""lang="([^"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.ifBlank { null }
            ?: "id"

        val rpcId = "WcwnYd"
        val reqId = (System.currentTimeMillis() % 90000L + 10000L).toString()
        val payload = """[[["$rpcId","[\"$token\",null,0]",null,"generic"]]]"""
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute" +
            "?rpcids=$rpcId&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=$hl&_reqid=$reqId&rt=c"

        val response = runCatching {
            app.post(
                apiUrl,
                data = mapOf("f.req" to payload),
                referer = url,
                cookies = cookies,
                headers = mapOf(
                    "Origin" to "https://www.blogger.com",
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "X-Same-Domain" to "1"
                )
            ).text
        }.getOrNull() ?: return emptyList()

        val decoded = decodeBloggerEscapes(response)
        return Regex("""https://[^\s"'\\]+""")
            .findAll(decoded)
            .map { it.value }
            .filter { it.contains("googlevideo.com/videoplayback", ignoreCase = true) }
            .map { decodeBloggerEscapes(it) }
            .distinct()
            .toList()
    }

    private fun decodeBloggerEscapes(input: String): String {
        var output = input
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }
        return output
            .replace("\\/", "/")
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\=", "=")
            .replace("\\&", "&")
            .replace("\\\"", "\"")
    }

    private fun qualityFromBloggerUrl(url: String): Int {
        val itag = Regex("""[?&]itag=(\d+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return when (itag) {
            37, 96, 137, 248, 299 -> Qualities.P1080.value
            22, 59, 136, 247, 298 -> Qualities.P720.value
            18, 134, 244 -> Qualities.P360.value
            135 -> Qualities.P480.value
            36 -> Qualities.P240.value
            17 -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }

    private fun emitBloggerVideoLink(
        videoUrl: String,
        quality: String?,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = videoUrl.trim()
        if (fixed.isBlank()) return false
        val key = fixed.normalizedMediaKey()
        if (!emitted.add(key)) return false
        callback(
            newExtractorLink(
                source = "Blogger",
                name = "Blogger ${quality.orEmpty()}".trim(),
                url = fixed,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://www.blogger.com/"
                this.quality = qualityFromBloggerUrl(fixed).takeIf { it != Qualities.Unknown.value }
                    ?: quality?.parseQuality()
                    ?: Qualities.Unknown.value
                this.headers = mapOf("Referer" to "https://www.blogger.com/")
            }
        )
        return true
    }

    private fun mediaHeaders(url: String, referer: String): Map<String, String> {
        val lower = url.lowercase(Locale.ROOT)
        val base = mapOf(
            "User-Agent" to chromeUa,
            "Accept" to "*/*",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        return when {
            lower.contains("googlevideo.com") || lower.contains("videoplayback") -> mapOf("Referer" to "https://www.blogger.com/")
            lower.contains("dmcdn.net") || lower.contains("dailymotion.com") || lower.contains("cdndirector.dailymotion.com") -> base + mapOf("Referer" to "https://geo.dailymotion.com/", "Origin" to "https://geo.dailymotion.com")
            else -> base + mapOf("Referer" to referer, "Origin" to origin(referer), "Range" to "bytes=0-")
        }
    }

    private fun mediaReferer(url: String, referer: String): String {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            lower.contains("dmcdn.net") || lower.contains("dailymotion.com") || lower.contains("cdndirector.dailymotion.com") -> "https://geo.dailymotion.com/"
            lower.contains("googlevideo.com") || lower.contains("videoplayback") -> "https://www.blogger.com/"
            else -> referer
        }
    }

    private fun playerHeaders(referer: String): Map<String, String> = siteHeaders + mapOf("Referer" to referer, "Origin" to origin(referer))

    private fun dailymotionHeaders(): Map<String, String> = mapOf(
        "User-Agent" to chromeUa,
        "Accept" to "application/json,text/plain,*/*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://geo.dailymotion.com/",
        "Origin" to "https://geo.dailymotion.com"
    )

    private fun bloggerHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to chromeUa,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Google Chrome\";v=\"125\", \"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Referer" to referer
    )

    private fun Element.bestImage(): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        return image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: image?.attr("srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.takeIf { it.isNotBlank() }
    }

    private fun absoluteUrl(raw: String?, base: String): String? {
        val value = raw?.trim()?.trim('"', '\'', ' ', ',', ')', ']', '}')?.basicHtmlDecode()?.unescapeJs()?.replace("\\/", "/") ?: return null
        if (value.isBlank() || value == "#" || value.startsWith("javascript:", true) || value.startsWith("data:", true)) return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("/") -> origin(base).trimEnd('/') + value
            value.startsWith("?") -> origin(base).trimEnd('/') + "/$value"
            value.contains(".") && !value.contains(" ") -> "https://$value"
            else -> null
        }
    }

    private fun origin(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(mainUrl)
    }

    private fun String.extractUrls(): List<String> {
        val text = basicHtmlDecode().unescapeJs().replace("\\/", "/")
        return Regex("""https?://[^"'<>\s\\]+""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value.trimEnd(',', ';', ')', ']', '}') }
            .toList()
    }

    private fun decodePossibleBase64(value: String?): String? {
        val raw = value?.trim()?.trim('"', '\'', ' ') ?: return null
        if (raw.length < 16) return null
        return runCatching {
            val normalized = raw.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(Base64.decode(padded, Base64.DEFAULT))
        }.getOrNull()?.takeIf { it.contains("http", true) || it.contains("<iframe", true) }
    }

    private fun String.cleanText(): String = Jsoup.parse(this).text().replace(Regex("\\s+"), " ").trim()

    private fun String.cleanTitle(): String = cleanText()
        .replace(Regex("(?i)^Nonton\\s+Anime\\s+"), "")
        .replace(Regex("(?i)\\s+Sub\\s+Indo.*$"), "")
        .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
        .replace(Regex("(?i)\\s+Episode\\s+\\d+.*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '|', ':', '»')

    private fun String.cleanCardTitle(): String = cleanText()
        .replace(Regex("(?i)^Completed\\s+"), "")
        .replace(Regex("(?i)^Sub\\s+"), "")
        .replace(Regex("(?i)^TV\\s+Ep\\s+\\d+\\s+Sub\\s+"), "")
        .replace(Regex("(?i)^ONA\\s+Ep\\s+\\d+\\s+Sub\\s+"), "")
        .replace(Regex("(?i)^Movie\\s+"), "")
        .cleanTitle()

    private fun String.removeEpisodeSuffix(): String = replace(Regex("(?i)\\s+Episode\\s+\\d+.*$"), "").trim()

    private fun String.cleanEpisodeTitle(number: Int?): String {
        val clean = cleanText()
        val fromNumber = number?.let { "Episode $it" }
        return when {
            clean.isBlank() -> fromNumber ?: "Episode"
            clean.equals("Episode", true) && fromNumber != null -> fromNumber
            clean.contains("Anime List", true) || clean.contains("Batalkan", true) -> fromNumber ?: "Episode"
            else -> clean.ifBlank { fromNumber ?: "Episode" }
        }
    }

    private fun String.cleanLabel(): String = cleanText().trim()

    private fun String.isValidContentTitle(): Boolean {
        val normalized = lowercase(Locale.ROOT).trim()
        if (normalized.length < 2) return false
        return normalized !in blockedTitles && blockedTitles.none { normalized == it }
    }

    private fun String.isRealSynopsis(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (length < 40) return false
        val hardSeo = listOf(
            "tonton streaming", "nonton anime", "nonton ", "download ", "subtitle indonesia di nimeindo", "full episode",
            "streaming anime", "kualitas hd gratis", "jangan lupa mengklik tombol like", "selalu update di nimeindo", "kamu juga bisa"
        )
        if (hardSeo.any { lower.contains(it) }) return false
        val seoHits = listOf("subtitle indonesia", "episode", "streaming", "download", "nimeindo").count { lower.contains(it) }
        return seoHits < 2
    }

    private fun String.isRealGenre(): Boolean {
        val normalized = lowercase(Locale.ROOT).trim()
        if (normalized.length < 2 || normalized.length > 40) return false
        if (normalized in blockedTitles) return false
        return !normalized.contains("episode") && !normalized.contains("anime list")
    }

    private fun String.isContentUrl(): Boolean {
        val lower = lowercase(Locale.ROOT).substringBefore("?")
        if (!lower.startsWith(mainUrl.lowercase(Locale.ROOT))) return false
        if (lower.endsWith("/anime/") || lower.endsWith("/donghua/") || lower.endsWith("/anime-ongoing/") || lower.endsWith("/donghua-ongoing/") || lower.endsWith("/jadwal-rilis/")) return false
        if (lower.contains("/genres/") || lower.contains("/genre/") || lower.contains("/studio/") || lower.contains("/country/") || lower.contains("/season/") || lower.contains("/cast/") || lower.contains("/director/")) return false
        return lower.contains("/anime/") || lower.isEpisodeUrl()
    }

    private fun String.isEpisodeUrl(): Boolean {
        val lower = lowercase(Locale.ROOT).substringBefore("?")
        return lower.startsWith(mainUrl.lowercase(Locale.ROOT)) && Regex("""-episode-\d+/?$""").containsMatchIn(lower)
    }

    private fun String.isPlayableCandidate(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return isDirectMedia() || lower.contains("dailymotion.com") || lower.contains("geo.dailymotion.com") || lower.contains("dmcdn.net") ||
            lower.contains("ok.ru/videoembed") || lower.contains("filedon.co") || lower.contains("blogger.com/video.g") ||
            lower.contains("short.icu") || lower.contains("turbovidhls.com") || lower.contains("/embed") || lower.contains("iframe") || lower.contains("player")
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") || lower.contains("googlevideo.com/videoplayback") || lower.contains("mime=video")
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("doubleclick") || lower.contains("google-analytics") || lower.contains("googletagmanager") || lower.contains("/ads") ||
            lower.contains("/cdn-cgi/") || lower.contains("facebook.com") || lower.contains("twitter.com") || lower.contains("whatsapp://") || lower.contains("telegram")
    }

    private fun String.shouldInspectInline(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("filedon") || lower.contains("short.icu") || lower.contains("turbovidhls") || lower.contains("blogger.com")
    }

    private fun String.extractDailymotionId(): String? {
        return Regex("""(?i)[?&]video=([A-Za-z0-9]+)""").find(this)?.groupValues?.getOrNull(1)
            ?: Regex("""(?i)/(?:embed/)?video/([A-Za-z0-9]+)""").find(this)?.groupValues?.getOrNull(1)
            ?: Regex("""(?i)/cdn/manifest/video/([A-Za-z0-9]+)""").find(this)?.groupValues?.getOrNull(1)
    }

    private fun String.extractDailymotionPlayerId(): String? {
        return Regex("""(?i)geo\.dailymotion\.com/player/([A-Za-z0-9_-]+)\.html""").find(this)?.groupValues?.getOrNull(1)
    }

    private fun String.isDailymotion(): Boolean = contains("dailymotion.com", true) || contains("dmcdn.net", true) || contains("geo.dailymotion.com", true)
    private fun String.isBlogger(): Boolean = contains("blogger.com/video.g", true)

    private fun String.normalizedMediaKey(): String = substringBefore("&Expires=").substringBefore("?Expires=").substringBefore("&X-Amz-Signature=").substringBefore("&range=")

    private fun String.parseEpisodeNumber(): Int? {
        return Regex("""(?i)(?:episode|ep)\s*[-:]?\s*(\d{1,4})""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""-episode-(\d{1,4})""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.parseEpisodeNumberLoose(): Int? = Regex("""\b(\d{1,4})\b""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    private fun String.parseYear(): Int? = Regex("""\b(?:19|20)\d{2}\b""").find(this)?.value?.toIntOrNull()
    private fun String.parseQuality(): Int? = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    private fun String.qualityLabelFromUrl(): String = parseQuality()?.let { "${it}p" }.orEmpty()
    private fun String.hostLabel(): String = runCatching { URI(this).host.removePrefix("www.").substringBefore('.') }.getOrDefault(name).replaceFirstChar { it.uppercase() }
    private fun String.slugTitle(): String = substringBefore("?").trimEnd('/').substringAfterLast('/').replace('-', ' ').cleanTitle().ifBlank { name }

    private fun String.basicHtmlDecode(): String {
        return replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#x22;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun String.unescapeJs(): String {
        var output = this
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }
        return output.replace("\\/", "/").replace("\\\"", "\"").replace("\\'", "'")
    }

    private val blockedTitles = setOf(
        "home", "beranda", "nimeindo", "anime list", "donghua", "anime", "genres", "genre", "jadwal rilis", "anime ongoing", "donghua ongoing",
        "rilisan terbaru", "anime sedang tayang", "donghua sedang tayang", "terpopuler", "terpopuler hari ini", "previous", "next", "prev", "login", "register",
        "batalkan balasar", "batalkan balasan", "tidak ada", "semua episode", "pilih server video"
    )

    private data class PlayerCandidate(
        val url: String,
        val label: String?,
        val referer: String
    )
}
