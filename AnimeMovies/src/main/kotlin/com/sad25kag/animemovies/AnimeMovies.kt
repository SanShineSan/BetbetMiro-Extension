package com.sad25kag.animemovies

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

class AnimeMovies : MainAPI() {
    override var mainUrl = "https://animemovies.org"
    override var name = "AnimeMovies"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Episode Terbaru",
        "$mainUrl/anime" to "Daftar Anime",
        "$mainUrl/genre/action" to "Action",
        "$mainUrl/genre/romance" to "Romance",
        "$mainUrl/genre/comedy" to "Comedy",
        "$mainUrl/genre/fantasy" to "Fantasy",
        "$mainUrl/genre/horror" to "Horror",
        "$mainUrl/genre/sci-fi" to "Sci-Fi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = buildPageCandidates(request.data, page)
        val results = linkedMapOf<String, SearchResponse>()
        var hasNext = false

        for (url in urls) {
            val document = try {
                app.get(url, headers = headers, referer = mainUrl).document
            } catch (_: Throwable) {
                continue
            }

            parseListing(document).forEach { results[it.url] = it }
            hasNext = hasNextPage(document, page)
            if (results.isNotEmpty()) break
        }

        return newHomePageResponse(
            request.name,
            results.values.toList(),
            hasNext = hasNext
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val slug = keyword.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        val urls = listOf(
            "$mainUrl/anime?search=$encoded",
            "$mainUrl/anime?q=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/anime"
        )

        val results = linkedMapOf<String, SearchResponse>()

        for (url in urls) {
            val document = try {
                app.get(url, headers = headers, referer = mainUrl).document
            } catch (_: Throwable) {
                continue
            }

            parseListing(document)
                .filter { item ->
                    item.name.contains(keyword, ignoreCase = true) ||
                        item.url.contains(slug, ignoreCase = true) ||
                        keyword.length <= 3
                }
                .forEach { results[it.url] = it }

            if (results.isNotEmpty()) break
        }

        if (results.isEmpty() && keyword.length <= 4) {
            val document = try {
                app.get(mainUrl, headers = headers, referer = mainUrl).document
            } catch (_: Throwable) {
                null
            }

            document?.let { parseListing(it).forEach { item -> results[item.url] = item } }
        }

        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url, mainUrl) ?: return null
        val document = try {
            app.get(fixedUrl, headers = headers, referer = mainUrl).document
        } catch (_: Throwable) {
            return null
        }

        if (isWatchUrl(fixedUrl)) {
            val animeUrl = document.selectFirst("a[href*='/anime/']")
                ?.attr("href")
                ?.let { fixUrl(it, fixedUrl) }
                ?: animeUrlFromWatchUrl(fixedUrl)

            if (!animeUrl.isNullOrBlank() && animeUrl != fixedUrl) {
                return load(animeUrl)
            }
        }

        val title = cleanTitle(
            document.selectFirst("h1, .entry-title, .anime-title, meta[property=og:title]")
                ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        ).ifBlank { titleFromUrl(fixedUrl) }

        if (title.isBlank()) return null

        val poster = findPoster(document, fixedUrl)

        val text = cleanText(document.text())
        val tags = document.select("a[href*='/genre/']")
            .map { cleanText(it.text()).substringBefore("(").trim() }
            .filter { it.length in 2..40 && !it.equals("Genre", true) }
            .distinct()
            .take(20)

        val description = cleanText(
            document.selectFirst(
                "meta[name=description], meta[property=og:description], .description, .synopsis, .sinopsis, .entry-content p, article p, p"
            )?.let {
                if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
            }
        )

        val rating = Regex("""(?i)(?:^|\s)(\d+(?:\.\d+)?)\s+\d+\s+Episode""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: document.selectFirst("[itemprop=ratingValue], .rating, .score")
                ?.text()
                ?.replace(",", ".")
                ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

        val episodes = parseEpisodes(document, fixedUrl)
        val type = inferType(text, episodes.size, fixedUrl)

        return newAnimeLoadResponse(title, fixedUrl, type) {
            engName = title
            posterUrl = poster
            plot = description
            this.tags = tags
            rating?.let { this.score = Score.from10(it) }
            addEpisodes(DubStatus.Subbed, episodes.ifEmpty {
                listOf(
                    newEpisode(fixedUrl) {
                        name = "Episode 1"
                        episode = 1
                    }
                )
            })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startUrl = fixUrl(data, mainUrl) ?: return false
        val emitted = linkedSetOf<String>()
        var found = false

        suspend fun emitPlayable(url: String, referer: String, source: String = name): Boolean {
            val fixed = fixUrl(url, referer) ?: return false
            if (!fixed.isPlayableMedia()) return false

            val key = fixed.substringBefore("#")
            if (!emitted.add(key)) return false

            if (fixed.contains(".m3u8", true)) {
                val links = try {
                    generateM3u8(source, fixed, referer, headers = headers + mapOf("Referer" to referer))
                } catch (_: Throwable) {
                    emptyList()
                }

                links.forEach { link ->
                    if (emitted.add(link.url.substringBefore("#"))) {
                        callback(link)
                    }
                }

                if (links.isNotEmpty()) return true
            }

            callback(
                newExtractorLink(source, "$source Direct", fixed, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = qualityFromUrl(fixed)
                    this.headers = headers + mapOf(
                        "Referer" to referer,
                        "Accept" to "*/*"
                    )
                }
            )

            return true
        }

        suspend fun emitExtractor(url: String, referer: String): Boolean {
            val fixed = fixUrl(url, referer) ?: return false
            if (fixed.isNoiseUrl()) return false

            var localFound = false
            try {
                loadExtractor(fixed, referer, subtitleCallback) { link ->
                    val key = link.url.substringBefore("#")
                    if (emitted.add(key)) {
                        localFound = true
                        callback(link)
                    }
                }
            } catch (_: Throwable) {
            }

            return localFound
        }

        val watchUrl = if (isWatchUrl(startUrl)) {
            startUrl
        } else {
            firstWatchUrl(startUrl) ?: startUrl
        }

        val response = try {
            app.get(watchUrl, headers = headers + mapOf("Referer" to mainUrl), referer = mainUrl)
        } catch (_: Throwable) {
            return false
        }

        val document = response.document
        val html = normalizeHtml(response.text.ifBlank { document.html() })

        collectSubtitles(document, watchUrl, subtitleCallback)

        val candidates = linkedSetOf<String>()

        // AnimeMovies stores playback on /watch/ pages. Keep extraction focused to player/embed areas only.
        document.select(
            "#player iframe[src], #player iframe[data-src], .player iframe[src], .player iframe[data-src], " +
                "[class*=player] iframe[src], [class*=player] iframe[data-src], [id*=player] iframe[src], [id*=player] iframe[data-src], " +
                "iframe[src], iframe[data-src], embed[src], video[src], video source[src], source[src], " +
                "[data-url], [data-src], [data-embed], [data-iframe], [data-link], [data-file], [data-video], [data-player]"
        ).forEach { element ->
            listOf(
                "data-src",
                "data-url",
                "data-embed",
                "data-iframe",
                "data-link",
                "data-file",
                "data-video",
                "data-player",
                "src",
                "href"
            ).forEach { attr ->
                val raw = element.attr(attr)
                if (raw.isNotBlank()) decodePossibleUrl(raw, watchUrl)?.let { candidates.add(it) }
            }
        }

        // Limit script scan to player-relevant scripts only; avoid recursive crawling and junk ad links.
        document.select("script").forEach { script ->
            val scriptText = normalizeHtml(script.data().ifBlank { script.html() })
            if (
                scriptText.contains("iframe", true) ||
                scriptText.contains("embed", true) ||
                scriptText.contains("player", true) ||
                scriptText.contains(".m3u8", true) ||
                scriptText.contains(".mp4", true) ||
                scriptText.contains("source", true) ||
                scriptText.contains("file", true) ||
                scriptText.contains("atob", true)
            ) {
                extractDirectMedia(scriptText, watchUrl).forEach { candidates.add(it) }
                extractIframeLinks(scriptText, watchUrl).forEach { candidates.add(it) }
                extractEmbedLinks(scriptText, watchUrl).forEach { candidates.add(it) }
                extractBase64Links(scriptText, watchUrl).forEach { candidates.add(it) }
            }
        }

        extractDirectMedia(html, watchUrl).forEach { candidates.add(it) }

        candidates
            .filterNot { it.isNoiseUrl() }
            .distinct()
            .forEach { candidate ->
                if (candidate.isPlayableMedia()) {
                    if (emitPlayable(candidate, watchUrl)) found = true
                } else {
                    if (emitExtractor(candidate, watchUrl)) found = true
                }
            }

        return found
    }

    private suspend fun firstWatchUrl(animeUrl: String): String? {
        val document = try {
            app.get(animeUrl, headers = headers + mapOf("Referer" to mainUrl), referer = mainUrl).document
        } catch (_: Throwable) {
            return null
        }

        return document.selectFirst("a[href*='/watch/']")
            ?.attr("href")
            ?.let { fixUrl(it, animeUrl) }
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(listingSelector).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }

        if (results.size < 8) {
            document.select("a[href*='/anime/'], a[href*='/watch/']").forEach { anchor ->
                anchor.toSearchResult()?.let { results[it.url] = it }
            }
        }

        return results.values.take(80)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = when {
            `is`("a[href]") -> this
            else -> selectFirst("a[href*='/anime/'], a[href*='/watch/'], a[href]")
        } ?: return null
        val container = anchor.bestCardContainer()

        val href = fixUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!isContentUrl(href)) return null

        val image = container.selectFirst("img[alt], img[src], img[data-src]")
            ?: anchor.selectFirst("img[alt], img[src], img[data-src]")

        val rawTitle = listOf(
            container.selectFirst("h1, h2, h3, h4, .title, .name, .font-semibold, .line-clamp-2")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text(),
            titleFromUrl(href)
        ).firstOrNull { isUsefulTitle(it) } ?: return null

        val title = cleanTitle(rawTitle).ifBlank { titleFromUrl(href) }
        if (!isUsefulTitle(title)) return null

        val poster = image?.imageUrl(mainUrl)
            ?: container.styleImage(mainUrl)

        val epNum = episodeNumber("$title ${container.text()} $href")
        val targetUrl = if (isWatchUrl(href)) {
            animeUrlFromWatchUrl(href) ?: href
        } else {
            href
        }

        return newAnimeSearchResponse(title.removeEpisodeSuffix(), targetUrl, inferType(container.text(), epNum ?: 0, targetUrl)) {
            posterUrl = poster
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        document.select("a[href*='/watch/']").forEachIndexed { index, anchor ->
            val href = fixUrl(anchor.attr("href"), baseUrl) ?: return@forEachIndexed
            val text = cleanText(anchor.text())
            val epNum = episodeNumber("$text $href") ?: (index + 1)

            episodes[href] = newEpisode(href) {
                name = text.ifBlank { "Episode $epNum" }
                episode = epNum
            }
        }

        return episodes.values.sortedBy { it.episode ?: 9999 }
    }

    private fun Element.bestCardContainer(): Element {
        var current: Element? = this
        repeat(7) {
            val node = current ?: return this
            if (
                node.selectFirst("img[src], img[data-src], img[srcset]") != null &&
                node.select("a[href*='/anime/'], a[href*='/watch/']").size <= 6
            ) {
                return node
            }
            current = node.parent()
        }

        return closest("article, .anime-card, .episode-card, .card, .item, .swiper-slide, li") ?: this
    }

    private fun collectElementLinks(document: Document, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()

        document.select(
            "iframe[src], iframe[data-src], embed[src], video[src], video source[src], source[src], " +
                "a[href*='embed'], a[href*='player'], a[href*='stream'], a[href*='drive'], a[href*='gofile'], a[href*='dood'], " +
                "a[href*='streamtape'], a[href*='filemoon'], a[href*='vidhide'], a[href*='vidguard'], a[href*='voe'], " +
                "a[href*='mp4upload'], a[href*='uqload'], a[href*='krakenfiles'], a[href*='filelions'], a[href*='.mp4'], a[href*='.m3u8'], " +
                "[data-url], [data-src], [data-embed], [data-iframe], [data-link], [data-file], [data-video], [data-player]"
        ).forEach { element ->
            listOf(
                "data-src",
                "data-url",
                "data-embed",
                "data-iframe",
                "data-link",
                "data-file",
                "data-video",
                "data-player",
                "src",
                "href"
            ).forEach { attr ->
                val raw = element.attr(attr)
                if (raw.isNotBlank()) decodePossibleUrl(raw, baseUrl)?.let { links.add(it) }
            }
        }

        return links.toList()
    }

    private suspend fun collectSubtitles(document: Document, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.vtt], a[href$=.srt], a[href*='.vtt'], a[href*='.srt']").forEach { element ->
            val subUrl = fixUrl(element.attr("src").ifBlank { element.attr("href") }, baseUrl) ?: return@forEach
            val label = cleanText(element.attr("label").ifBlank { element.attr("srclang").ifBlank { element.text().ifBlank { "Subtitle" } } })
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }

    private fun extractIframeLinks(html: String, baseUrl: String): List<String> {
        return Regex("""(?i)<(?:iframe|embed)[^>]+(?:src|data-src)=['"]([^'"]+)['"]""")
            .findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .toList()
    }

    private fun extractEmbedLinks(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()

        Regex("""(?i)"(?:embed_url|iframe_url|player_url|url|src|file|source)"\s*:\s*"([^"]+)"""")
            .findAll(html)
            .mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }
            .forEach { links.add(it) }

        Regex("""(?i)(?:embed_url|iframe_url|player_url|url|src|file|source)\s*[:=]\s*['"]([^'"]+)['"]""")
            .findAll(html)
            .mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }
            .forEach { links.add(it) }

        Regex("""(?i)['"]((?:https?:)?//[^'"]+(?:embed|player|stream|drive|gofile|dood|streamtape|filemoon|vidhide|vidguard|voe|mp4upload|uqload|krakenfiles|filelions|gdplayer|gdriveplayer|hubcloud|letsembed|vidrush|dropfile|/e/|/v/|/d/)[^'"]*)['"]""")
            .findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .forEach { links.add(it) }

        return links.toList()
    }

    private fun extractBase64Links(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()

        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""")
            .findAll(html)
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .forEach { decoded ->
                extractDirectMedia(decoded, baseUrl).forEach { links.add(it) }
                extractIframeLinks(decoded, baseUrl).forEach { links.add(it) }
                extractEmbedLinks(decoded, baseUrl).forEach { links.add(it) }
            }

        return links.toList()
    }

    private fun extractDirectMedia(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()

        Regex("""(?i)['"]((?:https?:)?//[^'"]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^'"]+|videoplayback[^'"]*)(?:\?[^'"]*)?)['"]""")
            .findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .filter { it.isPlayableMedia() }
            .forEach { links.add(it) }

        Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^\s'"<>\\]+|videoplayback[^\s'"<>\\]*)(?:\?[^\s'"<>\\]*)?""")
            .findAll(html)
            .mapNotNull { fixUrl(it.value, baseUrl) }
            .filter { it.isPlayableMedia() }
            .forEach { links.add(it) }

        Regex("""https?%3A%2F%2F[^\s'"<>]+""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { fixUrl(urlDecode(it.value), baseUrl) }
            .filter { it.isPlayableMedia() }
            .forEach { links.add(it) }

        return links.toList()
    }

    private fun decodePossibleUrl(value: String, baseUrl: String): String? {
        val decoded = urlDecode(value)
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
            .trim('"', '\'', ',', ';')

        fixUrl(decoded, baseUrl)?.let { return it }

        decodeBase64(decoded)?.let { html ->
            extractDirectMedia(html, baseUrl).firstOrNull()?.let { return it }
            extractIframeLinks(html, baseUrl).firstOrNull()?.let { return it }
            extractEmbedLinks(html, baseUrl).firstOrNull()?.let { return it }

            if (html.startsWith("http", true) || html.startsWith("//")) {
                fixUrl(html, baseUrl)?.let { return it }
            }
        }

        return null
    }

    private fun buildPageCandidates(url: String, page: Int): List<String> {
        val fixed = fixUrl(url, mainUrl) ?: url
        if (page <= 1) return listOf(fixed)

        val trimmed = fixed.trimEnd('/')
        return listOf(
            "$trimmed?page=$page",
            "$trimmed/page/$page",
            "$trimmed/page/$page/"
        )
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst("a.next, .pagination a:contains(Next), a[href*='page=${page + 1}'], a[href*='/page/${page + 1}']") != null
    }

    private fun inferType(text: String, episodeCountOrEpisode: Int, url: String): TvType {
        val lower = text.lowercase(Locale.ROOT)
        val path = try {
            URI(url).path.orEmpty().lowercase(Locale.ROOT)
        } catch (_: Throwable) {
            ""
        }

        val hasEpisodeSignal = episodeCountOrEpisode > 0 ||
            lower.contains(" episode") ||
            lower.contains(" eps") ||
            lower.contains("tv") ||
            lower.contains("ongoing") ||
            lower.contains("completed") ||
            path.contains("/watch/")

        return when {
            lower.contains("ova") -> TvType.OVA
            lower.contains("ona") -> TvType.Anime
            Regex("""\\b(movie|film)\\b""").containsMatchIn(lower) && !hasEpisodeSignal -> TvType.AnimeMovie
            else -> TvType.Anime
        }
    }

    private fun isContentUrl(url: String): Boolean {
        val path = try {
            URI(url).path.orEmpty()
        } catch (_: Throwable) {
            return false
        }

        if (!url.contains("animemovies.org", true)) return false
        if (!path.contains("/anime/") && !path.contains("/watch/")) return false
        if (path.contains("/genre/") || path.contains("/jadwal") || path.contains("/login") || path.contains("/register")) return false

        return true
    }

    private fun isWatchUrl(url: String): Boolean {
        return url.contains("/watch/", true)
    }

    private fun animeUrlFromWatchUrl(url: String): String? {
        val slug = try {
            URI(url).path.substringAfter("/watch/").trim('/')
        } catch (_: Throwable) {
            url.substringAfter("/watch/").trim('/')
        }

        if (slug.isBlank()) return null

        val animeSlug = slug
            .replace(Regex("(?i)-episode-\\d+.*$"), "")
            .replace(Regex("(?i)-ep-\\d+.*$"), "")
            .replace(Regex("(?i)-sub(?:title)?-indo.*$"), "")
            .trim('-')

        return animeSlug.takeIf { it.isNotBlank() }?.let { "$mainUrl/anime/$it" }
    }

    private fun episodeNumber(value: String?): Int? {
        val text = value.orEmpty()
        return Regex("""(?i)(?:episode|eps|ep)\s*[-:_]?\s*(\d{1,4})""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)-episode-(\d{1,4})""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)-ep-(\d{1,4})""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun cleanTitle(value: String?): String {
        return cleanText(value)
            .replace(Regex("(?i)^nonton\\s+anime\\s+"), "")
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .replace(Regex("(?i)\\s+sub\\s+indo.*$"), "")
            .replace(Regex("(?i)\\s+episode\\s+\\d+.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.removeEpisodeSuffix(): String {
        return replace(Regex("(?i)\\s+episode\\s+\\d+.*$"), "")
            .replace(Regex("(?i)\\s+ep\\s+\\d+.*$"), "")
            .trim()
    }

    private fun isUsefulTitle(value: String?): Boolean {
        val text = cleanText(value)
        if (text.length < 2) return false

        val lower = text.lowercase(Locale.ROOT)
        return lower !in setOf(
            "home",
            "beranda",
            "daftar anime",
            "genre",
            "jadwal",
            "masuk",
            "daftar",
            "tonton",
            "detail",
            "lihat semua",
            "mulai nonton",
            "previous",
            "next"
        ) && !lower.contains("animemovies")
    }

    private fun findPoster(document: Document, baseUrl: String): String? {
        listOf(
            "meta[property=og:image]",
            "meta[name=twitter:image]",
            "img[alt][src*='_next/image']",
            "img[alt]",
            "img[src]"
        ).forEach { selector ->
            val element = document.selectFirst(selector) ?: return@forEach

            if (element.tagName().equals("meta", true)) {
                fixUrl(element.attr("content"), baseUrl)?.let { normalizeImageUrl(it) }?.takeIf { it.isImageLike() }?.let { return it }
            } else {
                element.imageUrl(baseUrl)?.let { return it }
            }
        }

        return document.body()?.styleImage(baseUrl)
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val candidates = listOf(
            attr("data-src"),
            attr("data-original"),
            attr("data-lazy-src"),
            attr("src"),
            attr("srcset").substringBefore(" ")
        )

        return candidates
            .mapNotNull { fixUrl(it, baseUrl) }
            .map { normalizeImageUrl(it) }
            .firstOrNull { it.isImageLike() && !it.isAdImage() }
    }

    private fun Element.styleImage(baseUrl: String): String? {
        val style = attr("style") + " " + select("[style]").joinToString(" ") { it.attr("style") }

        return Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(style)
            ?.groupValues
            ?.getOrNull(2)
            ?.let { fixUrl(it, baseUrl) }
            ?.let { normalizeImageUrl(it) }
            ?.takeIf { it.isImageLike() && !it.isAdImage() }
    }

    private fun normalizeImageUrl(url: String): String {
        val fixed = url.replace("&amp;", "&")

        if (fixed.contains("/_next/image", true) && fixed.contains("url=", true)) {
            val rawTarget = fixed.substringAfter("url=", "")
                .substringBefore("&")
                .trim()

            if (rawTarget.isNotBlank()) {
                val decoded = urlDecode(rawTarget)
                if (decoded.startsWith("http", true) || decoded.startsWith("//")) {
                    return decoded
                }
            }
        }

        return fixed
    }

    private fun fixUrl(value: String?, baseUrl: String): String? {
        val raw = urlDecode(
            value.orEmpty()
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim()
                .trim('"', '\'', ',', ';')
        )

        if (
            raw.isBlank() ||
            raw == "#" ||
            raw.equals("null", true) ||
            raw.startsWith("javascript:", true) ||
            raw.startsWith("mailto:", true) ||
            raw.startsWith("tel:", true) ||
            raw.startsWith("data:", true) ||
            raw.startsWith("blob:", true) ||
            raw.startsWith("about:", true)
        ) return null

        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> origin(baseUrl) + raw
            else -> try {
                URI(baseUrl).resolve(raw).toString()
            } catch (_: Throwable) {
                origin(baseUrl) + "/" + raw.trimStart('/')
            }
        }
    }

    private fun origin(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (_: Throwable) {
            mainUrl
        }
    }

    private fun titleFromUrl(url: String): String {
        val slug = try {
            URI(url).path.trim('/').substringAfterLast('/')
        } catch (_: Throwable) {
            url.substringAfterLast("/")
        }
            .substringBefore("?")
            .replace(Regex("(?i)-subtitle-indonesia.*$"), "")
            .replace(Regex("(?i)-sub-indo.*$"), "")
            .replace(Regex("(?i)-episode-\\d+.*$"), "")
            .replace(Regex("(?i)-ep-\\d+.*$"), "")

        return slug.split("-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
            }
    }

    private fun normalizeHtml(value: String): String {
        return urlDecode(
            value.replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
        )
    }

    private fun decodeBase64(value: String): String? {
        val raw = value.trim()
        if (raw.length < 8) return null

        val normalized = raw.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)

        return try {
            String(Base64.getDecoder().decode(padded))
        } catch (_: Throwable) {
            try {
                String(Base64.getUrlDecoder().decode(padded))
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun urlDecode(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Throwable) {
            value
        }
    }

    private fun cleanText(value: String?): String {
        return value.orEmpty()
            .replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.isImageLike(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".jpg") ||
            lower.contains(".jpeg") ||
            lower.contains(".png") ||
            lower.contains(".webp") ||
            lower.contains("/_next/image") ||
            lower.contains("wp-content/uploads") ||
            lower.contains("image")
    }

    private fun String.isAdImage(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("logo") ||
            lower.contains("favicon") ||
            lower.contains("placeholder") ||
            lower.contains("ads")
    }

    private fun String.isPlayableMedia(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (
            lower.endsWith(".html") ||
            lower.endsWith(".htm") ||
            lower.endsWith(".php") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.contains("mime=image") ||
            lower.contains("=image/")
        ) return false

        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains("videoplayback") ||
            lower.contains("mime=video") ||
            (lower.contains("googlevideo.com") && lower.contains("videoplayback"))
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("facebook.com") ||
            lower.contains("telegram") ||
            lower.contains("twitter.com") ||
            lower.contains("instagram") ||
            lower.contains("whatsapp") ||
            lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.contains("youtube.com") ||
            lower.contains("youtu.be") ||
            lower.endsWith(".css") ||
            lower.endsWith(".js") ||
            lower.endsWith(".ico") ||
            lower.endsWith(".svg") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp")
    }

    private fun qualityFromUrl(url: String): Int {
        val lower = url.lowercase(Locale.ROOT)

        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            lower.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private val listingSelector = listOf(
        "article",
        ".anime-card",
        ".episode-card",
        ".card",
        ".item",
        ".swiper-slide",
        ".grid a[href]",
        "a[href*='/anime/']",
        "a[href*='/watch/']"
    ).joinToString(", ")
}
