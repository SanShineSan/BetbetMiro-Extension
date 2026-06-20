package com.sad25kag.film21

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

class Film21 : MainAPI() {
    companion object {
        private const val DEFAULT_MAIN_URL = "https://tv13.filem21.net"
    }

    override var mainUrl = DEFAULT_MAIN_URL
    override var name = "Film21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Referer" to "$DEFAULT_MAIN_URL/"
    )

    override val mainPage = mainPageOf(
        "https://tv13.filem21.net/genre/drama-korea/?utm_source=film21&utm_medium=drama-korea" to "Drama Korea",
        "https://tv13.filem21.net/genre/drama-china/?utm_source=film21&utm_medium=drama-china" to "Drama China",
        "https://tv13.filem21.net/genre/drama-thailand/?utm_source=film21&utm_medium=drama-thailand" to "Drama Thailand",
        "https://tv13.filem21.net/genre/drama-jepang/?utm_source=film21&utm_medium=drama-jepang" to "Drama Jepang",
        "https://tv13.filem21.net/country/usa/" to "USA",
        "https://tv13.filem21.net/country/japan/" to "Japan",
        "https://tv13.filem21.net/country/thailand/" to "Thailand",
        "https://tv13.filem21.net/country/india/" to "India",
        "https://tv13.filem21.net/country/indonesia/" to "Indonesia",
        "https://tv13.filem21.net/country/philippines/" to "Philippines",
        "https://tv13.filem21.net/country/korea/" to "Korea",
        "https://tv13.filem21.net/country/china/" to "China"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = runCatching {
            app.get(buildPageUrl(request.data, page), headers = baseHeaders, referer = mainUrl).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val items = parseListing(document)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && hasNextPage(document, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/?s=$encoded&post_type%5B%5D=post&post_type%5B%5D=tv"
        )

        val results = linkedMapOf<String, SearchResponse>()
        for (url in searchUrls) {
            val document = runCatching {
                app.get(url, headers = baseHeaders, referer = mainUrl).document
            }.getOrNull() ?: continue
            for (item in parseListing(document)) {
                if (item.name.contains(keyword, ignoreCase = true) || keyword.length <= 3) {
                    results[contentKey(item.url)] = item
                }
            }
            if (results.isNotEmpty()) break
        }
        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = url.toAbsoluteUrl(mainUrl) ?: return null
        val response = runCatching {
            app.get(pageUrl, headers = baseHeaders, referer = mainUrl)
        }.getOrNull() ?: return null
        val document = response.document
        val detailRoot = document.detailRoot()
        val detailText = cleanText(
            detailRoot.selectFirst(".entry-content.entry-content-single, .entry-content, .gmr-movie-data, .content-moviedata")?.text()
                ?: detailRoot.text()
        )
        val rawTitle = listOf(
            detailRoot.selectFirst(".gmr-movie-data-top h1.entry-title[itemprop=name], h1.entry-title[itemprop=name], h1.entry-title, h1[itemprop=name], .entry-title[itemprop=name]")?.text(),
            document.selectFirst("meta[property=og:image]")?.attr("alt"),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("title")?.text()
        ).firstOrNull { isUsefulTitle(it) }
        val title = cleanTitle(rawTitle).ifBlank { titleFromUrl(pageUrl) }
        if (title.isBlank()) return null

        val poster = findPoster(document, pageUrl)
        val tags = parseDetailTags(document)
        val actors = parseDetailActors(document)
        val year = parseDetailYear(document)
            ?: title.firstYear()
            ?: detailText.firstYear()
        val rating = parseDetailRating(document)
        val duration = parseDetailDuration(document) ?: Regex("""(?i)(\d{1,3})\s*(?:min|menit|m)\b""")
            .find(detailText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val description = parseDetailDescription(document)
        val trailer = detailRoot.selectFirst("a[href*='youtube.com'], a[href*='youtu.be'], .trailer a[href], .gmr-trailer-popup[href]")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
        val episodes = parseEpisodes(document, pageUrl)
        val tvType = inferType(pageUrl, title, detailText, episodes.size)

        return if (tvType == TvType.TvSeries) {
            val finalEpisodes = episodes.ifEmpty {
                listOf(newEpisode(pageUrl).apply {
                    name = "Episode 1"
                    episode = 1
                    posterUrl = poster
                })
            }
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, finalEpisodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                addActors(actors)
                addTrailer(trailer)
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                addActors(actors)
                addTrailer(trailer)
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.toAbsoluteUrl(mainUrl) ?: data
        var emitted = false
        val visited = linkedSetOf<String>()

        val firstResponse = runCatching {
            app.get(pageUrl, headers = baseHeaders + mapOf("Referer" to mainUrl), referer = mainUrl)
        }.getOrNull()

        if (firstResponse != null) {
            emitted = resolveMuviproAjaxTabs(
                document = firstResponse.document,
                pageUrl = pageUrl,
                visited = visited,
                subtitleCallback = subtitleCallback,
                callback = callback
            ) || emitted

            val pageCandidates = collectPlayerCandidates(firstResponse.document, firstResponse.text, pageUrl)
            for (candidate in pageCandidates) {
                emitted = resolvePlayerUrl(candidate, pageUrl, 0, visited, subtitleCallback, callback) || emitted
            }
        }

        val serverUrls = buildServerCandidates(pageUrl)
        for (serverUrl in serverUrls) {
            if (!visited.add(serverUrl)) continue
            val response = if (serverUrl.substringBefore("#") == pageUrl.substringBefore("#")) {
                firstResponse
            } else {
                runCatching {
                    app.get(serverUrl, headers = baseHeaders + mapOf("Referer" to mainUrl), referer = mainUrl)
                }.getOrNull()
            } ?: continue

            val candidates = collectPlayerCandidates(response.document, response.text, serverUrl)
            for (candidate in candidates) {
                emitted = resolvePlayerUrl(candidate, serverUrl, 0, visited, subtitleCallback, callback) || emitted
            }
        }

        return emitted
    }

    private suspend fun resolveMuviproAjaxTabs(
        document: Document,
        pageUrl: String,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val player = document.selectFirst("#muvipro_player_content_id[data-id], .muvipro_player_content[data-id]")
        val postId = player?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: Regex("""<article[^>]+id=["']post-(\d+)["']""", RegexOption.IGNORE_CASE)
                .find(document.html())
                ?.groupValues
                ?.getOrNull(1)
        if (postId.isNullOrBlank()) return false

        val tabs = linkedSetOf<String>()
        document.select("#muvipro_player_content_id .tab-content-ajax[id], .muvipro-player-tabs a[href^=#]").forEach { element ->
            val tab = element.attr("id").ifBlank { element.attr("href").removePrefix("#") }
            if (tab.matches(Regex("""p\d+""", RegexOption.IGNORE_CASE))) tabs.add(tab)
        }
        if (tabs.isEmpty()) {
            (1..8).mapTo(tabs) { "p$it" }
        }

        var emitted = false
        val ajaxUrl = "${getBaseUrl(pageUrl).trimEnd('/')}/wp-admin/admin-ajax.php"
        for (tab in tabs) {
            val ajaxResponse = runCatching {
                app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to tab,
                        "post_id" to postId
                    ),
                    headers = ajaxHeaders(pageUrl),
                    referer = pageUrl
                )
            }.getOrNull() ?: continue

            val candidates = collectPlayerCandidates(ajaxResponse.document, ajaxResponse.text, pageUrl)
            for (candidate in candidates) {
                emitted = resolvePlayerUrl(candidate, pageUrl, 0, visited, subtitleCallback, callback) || emitted
            }
        }
        return emitted
    }

    private fun ajaxHeaders(referer: String): Map<String, String> {
        return baseHeaders + mapOf(
            "Accept" to "*/*",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to mainUrl,
            "Referer" to referer
        )
    }

    private fun buildServerCandidates(pageUrl: String): List<String> {
        val clean = pageUrl.substringBefore("#")
        return listOf(
            clean,
            clean.substringBefore("?") + "?player=1",
            clean.substringBefore("?") + "?player=2",
            clean.substringBefore("?") + "?player=3",
            clean.substringBefore("?") + "?player=4",
            clean.substringBefore("?") + "?player=5"
        ).distinct()
    }

    private suspend fun resolvePlayerUrl(
        rawUrl: String,
        referer: String,
        depth: Int,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = rawUrl.cleanHtml().toAbsoluteUrl(referer) ?: return false
        if (url.isNoiseUrl()) return false
        if (url.isSubtitleUrl()) {
            subtitleCallback(newSubtitleFile("Indonesian", url))
            return false
        }
        if (url.isDirectVideoUrl()) {
            emitDirect(url, referer, callback)
            return true
        }

        var emitted = false
        runCatching {
            loadExtractor(url, referer, subtitleCallback) { link ->
                emitted = true
                callback(link)
            }
        }
        if (emitted || depth >= 2) return emitted
        if (!visited.add(url)) return emitted

        val response = runCatching {
            app.get(url, headers = baseHeaders + mapOf("Referer" to referer), referer = referer, timeout = 15000L)
        }.getOrNull() ?: return emitted
        val contentType = response.headers["Content-Type"].orEmpty().lowercase(Locale.ROOT)
        if (contentType.startsWith("video/") || contentType.contains("mpegurl") || contentType.contains("dash")) {
            emitDirect(url, referer, callback)
            return true
        }
        val html = response.text
        val document = response.document
        val nestedCandidates = collectPlayerCandidates(document, html, url)
        for (nested in nestedCandidates) {
            emitted = resolvePlayerUrl(nested, url, depth + 1, visited, subtitleCallback, callback) || emitted
        }
        return emitted
    }

    private fun collectPlayerCandidates(document: Document, html: String, baseUrl: String): List<String> {
        val candidates = linkedSetOf<String>()

        document.select("iframe[src], iframe[data-src], iframe[data-lazy-src], embed[src]").forEach { element ->
            element.firstAttr("src", "data-src", "data-lazy-src")?.toAbsoluteUrl(baseUrl)?.let { candidates.add(it) }
        }
        document.select("video source[src], video[src], source[src]").forEach { element ->
            element.firstAttr("src")?.toAbsoluteUrl(baseUrl)?.let { candidates.add(it) }
        }
        document.select("track[src], a[href*='.srt'], a[href*='.vtt'], a[href*='.ass']").forEach { element ->
            element.firstAttr("src", "href")?.toAbsoluteUrl(baseUrl)?.let { candidates.add(it) }
        }
        document.select("a[href], button, div, li, span").forEach { element ->
            val attrs = listOf("href", "data-src", "data-href", "data-url", "data-link", "data-file", "data-video", "data-embed", "data-player", "data-id")
            attrs.forEach { attr ->
                element.attr(attr).takeIf { it.isNotBlank() }?.let { raw ->
                    raw.cleanHtml().toAbsoluteUrl(baseUrl)?.let { absolute ->
                        if (absolute.isPlayableCandidate()) candidates.add(absolute)
                    }
                }
            }
        }

        val urlRegex = Regex("""(?i)(https?:)?//[^\s"'<>]+""")
        urlRegex.findAll(html).forEach { match ->
            match.value.cleanHtml().toAbsoluteUrl(baseUrl)?.let { absolute ->
                if (absolute.isPlayableCandidate() || absolute.isSubtitleUrl()) candidates.add(absolute)
            }
        }

        val directRegex = Regex("""(?i)["']([^"']+\.(?:m3u8|mp4|webm|mkv|mpd)(?:\?[^"']*)?)["']""")
        directRegex.findAll(html).forEach { match ->
            match.groupValues.getOrNull(1)?.cleanHtml()?.toAbsoluteUrl(baseUrl)?.let { candidates.add(it) }
        }

        val base64Regex = Regex("""(?i)(?:atob|Base64\.decode)\(["']([A-Za-z0-9+/=]{16,})["']\)|["']([A-Za-z0-9+/=]{40,})["']""")
        base64Regex.findAll(html).forEach { match ->
            listOfNotNull(match.groupValues.getOrNull(1), match.groupValues.getOrNull(2)).forEach { encoded ->
                decodeBase64(encoded)?.cleanHtml()?.toAbsoluteUrl(baseUrl)?.let { decoded ->
                    if (decoded.isPlayableCandidate() || decoded.isDirectVideoUrl()) candidates.add(decoded)
                }
            }
        }

        return candidates.filterNot { it.isNoiseUrl() }.distinct()
    }

    private suspend fun emitDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val type = when {
            url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        callback(
            newExtractorLink(name, name, url, type) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        for (element in document.select(cardSelector)) {
            element.toSearchResult()?.let { results[contentKey(it.url)] = it }
        }
        if (results.size < 8) {
            val fallbackSelector = listOf(
                ".content-thumbnail > a[href]:has(img)",
                ".other-content-thumbnail > a[href]:has(img)",
                "h1.entry-title a[href]",
                "h2.entry-title a[href]",
                "h3.entry-title a[href]",
                ".entry-title a[href]",
                "a[itemprop=url][href]:has(img)",
                "a[rel=bookmark][href]:has(img)"
            ).joinToString(",")
            for (anchor in document.select(fallbackSelector)) {
                anchor.toSearchResult()?.let { results[contentKey(it.url)] = it }
            }
        }
        return results.values.take(80)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val container = if (isCardLike()) this else cardContainer()
        val anchor = container.selectFirst(
            listOf(
                "h1.entry-title a[href]",
                "h2.entry-title a[href]",
                "h3.entry-title a[href]",
                ".entry-title a[href]",
                ".gmr-watch-movie a[href]",
                ".content-thumbnail > a[href]:has(img)",
                ".other-content-thumbnail > a[href]:has(img)",
                "a[itemprop=url][href]",
                "a[rel=bookmark][href]"
            ).joinToString(",")
        ) ?: if (`is`("a[href]")) this else selectFirst("a[href]:has(img), a[href]") ?: return null

        val href = anchor.attr("href").toAbsoluteUrl(mainUrl) ?: return null
        if (!isContentUrl(href)) return null

        val image = container.selectFirst(
            ".content-thumbnail img[data-src], .content-thumbnail img[data-original], .content-thumbnail img[data-lazy-src], .content-thumbnail img[data-wpfc-original-src], .content-thumbnail img[src]:not([src^='data:']), " +
                ".other-content-thumbnail img[data-src], .other-content-thumbnail img[data-original], .other-content-thumbnail img[data-lazy-src], .other-content-thumbnail img[src]:not([src^='data:']), " +
                "img[itemprop=image][data-src], img[itemprop=image][src]:not([src^='data:']), img[data-src], img[data-original], img[data-lazy-src], img[src]:not([src^='data:'])"
        ) ?: anchor.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[src]:not([src^='data:'])")

        val entryTitle = container.selectFirst("h1.entry-title a, h2.entry-title a, h3.entry-title a, .entry-title a")?.text()
        val titleFromAnchorAttr = anchor.attr("title")
            .replace(Regex("""(?i)^permalink\s+ke:\s*"""), "")
            .trim()
        val anchorText = cleanText(anchor.text())
        val title = listOf(
            entryTitle,
            titleFromAnchorAttr,
            anchor.attr("aria-label"),
            image?.attr("alt"),
            anchorText.takeUnless { it.isBlank() || it.isBadLabel() },
            titleFromUrl(href)
        ).firstOrNull { isUsefulTitle(it) }?.let { cleanTitle(it) } ?: return null

        val poster = image?.imageUrl(mainUrl) ?: container.styleImage(mainUrl) ?: anchor.findNearbyImage(mainUrl)
        val text = cleanText(container.text())
        val type = inferType(href, title, text, 0)
        val year = title.firstYear() ?: text.firstYear()
        val score = container.selectFirst(".rating, .score, .imdb, .vote, .nilai, .gmr-rating-item")?.text()?.replace(',', '.')
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

        return if (type == TvType.TvSeries || type == TvType.AsianDrama) {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()
        val selector = listOf(
            "a[href*='/eps/']",
            "a[href*='episode']",
            "div.vid-episodes a[href]",
            "div.list-episode a[href]",
            "div.gmr-listseries a[href]",
            ".episode-list a[href]",
            ".episodes a[href]",
            ".eplister li a[href]",
            "[class*=episode] a[href]",
            "[id*=episode] a[href]",
            "[class*=season] a[href]",
            "[id*=season] a[href]"
        ).joinToString(",")

        document.select(selector).forEachIndexed { index, element ->
            val href = element.attr("href").toAbsoluteUrl(mainUrl) ?: return@forEachIndexed
            if (!isContentUrl(href) || contentKey(href) == contentKey(baseUrl)) return@forEachIndexed
            val rawName = cleanText(element.text().ifBlank { element.attr("title") }).ifBlank { "Episode ${index + 1}" }
            if (rawName.contains("trailer", true) || rawName.contains("download", true)) return@forEachIndexed
            val episodeNumber = Regex("""(?i)(?:episode|eps|ep)\s*(\d+)""").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""\b(\d{1,4})\b""").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(?i)/eps/[^/]+(?:episode|eps|ep)?-?(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val seasonNumber = Regex("""(?i)(?:season|s)\s*(\d+)""").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(?i)/season-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            episodes[contentKey(href)] = newEpisode(href).apply {
                name = rawName
                episode = episodeNumber
                season = seasonNumber
                posterUrl = element.selectFirst("img[data-src], img[src]:not([src^='data:'])")?.imageUrl(mainUrl)
            }
        }
        return episodes.values.sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: Int.MAX_VALUE })
    }

    private fun buildPageUrl(data: String, page: Int): String {
        val base = data.toAbsoluteUrl(mainUrl)?.substringBefore("?") ?: mainUrl
        if (page <= 1) return base
        return base.trimEnd('/') + "/page/$page/"
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.select("a.next, .next a, .pagination a, .nav-links a, .page-numbers a").any { element ->
            val text = element.text().trim()
            val href = element.attr("href")
            text.equals("Next", true) || text == "›" || text == "»" || Regex("""\b${page + 1}\b""").containsMatchIn(text) || href.contains("/page/${page + 1}/")
        }
    }

    private fun inferType(url: String, title: String, text: String, episodeCount: Int): TvType {
        val value = "$url $title $text"
        return when {
            episodeCount > 1 -> TvType.TvSeries
            url.contains("/tv/", true) || url.contains("/eps/", true) -> TvType.TvSeries
            value.contains("TV Show", true) || value.contains("Serial", true) || value.contains("Series", true) || value.contains("Season", true) || value.contains("Eps:", true) -> TvType.TvSeries
            value.contains("Drama Korea", true) || value.contains("Drama China", true) || value.contains("Drama Thailand", true) || value.contains("Drama Jepang", true) -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }

    private fun Document.detailRoot(): Element {
        return selectFirst("article[id^=post-], article, main, #primary, .site-main, .gmr-single") ?: body()
    }

    private fun parseDetailDescription(document: Document): String? {
        val root = document.detailRoot()
        val explicit = root.selectFirst(
            ".entry-content.entry-content-single > p, .entry-content[itemprop=description] > p, .entry-content > p, div[itemprop=description] > p, .description, .desc, .sinopsis, .storyline"
        )?.text()
        return cleanDescription(explicit)
            ?: cleanDescription(document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content"))
    }

    private fun parseDetailTags(document: Document): List<String> {
        val result = linkedSetOf<String>()
        document.select(".entry-content .gmr-moviedata, .content-moviedata .gmr-moviedata, .gmr-movie-data .gmr-moviedata").forEach { row ->
            val label = cleanText(row.selectFirst("strong")?.text()).lowercase(Locale.ROOT).trim(':', ' ')
            if (label == "genre") {
                row.select("a[href*='/genre/']").forEach { element ->
                    val value = cleanText(element.text()).substringBefore("(").trim()
                    if (value.length in 2..40 && !value.isBadLabel()) result.add(value)
                }
            }
        }
        if (result.isEmpty()) {
            document.select("meta[property=article:section], meta[property=article:tag]").forEach { meta ->
                val value = cleanText(meta.attr("content"))
                if (value.length in 2..40 && !value.isBadLabel()) result.add(value)
            }
        }
        return result.take(20)
    }

    private fun parseDetailActors(document: Document): List<String> {
        val result = linkedSetOf<String>()
        document.select(".entry-content .gmr-moviedata, .content-moviedata .gmr-moviedata, .gmr-movie-data .gmr-moviedata").forEach { row ->
            val label = cleanText(row.selectFirst("strong")?.text()).lowercase(Locale.ROOT).trim(':', ' ')
            if (label == "pemain" || label == "cast" || label == "aktor" || label == "actors") {
                row.select("a[href*='/cast/'], span[itemprop=actors] a, [itemprop=actors] a").forEach { element ->
                    val value = cleanText(element.text())
                    if (value.length in 2..60 && !value.isBadLabel()) result.add(value)
                }
            }
        }
        if (result.isEmpty()) {
            document.detailRoot().select("span[itemprop=actors] a, a[href*='/cast/'], .cast a, .actors a").forEach { element ->
                val value = cleanText(element.text())
                if (value.length in 2..60 && !value.isBadLabel()) result.add(value)
            }
        }
        return result.take(24)
    }

    private fun parseDetailYear(document: Document): Int? {
        document.select(".entry-content .gmr-moviedata, .content-moviedata .gmr-moviedata, .gmr-movie-data .gmr-moviedata").forEach { row ->
            val label = cleanText(row.selectFirst("strong")?.text()).lowercase(Locale.ROOT).trim(':', ' ')
            if (label == "tahun" || label == "year") {
                row.text().firstYear()?.let { return it }
            }
        }
        return document.detailRoot().selectFirst("time[datetime]")?.text()?.firstYear()
    }

    private fun parseDetailDuration(document: Document): Int? {
        document.select(".entry-content .gmr-moviedata, .content-moviedata .gmr-moviedata, .gmr-movie-data .gmr-moviedata").forEach { row ->
            val label = cleanText(row.selectFirst("strong")?.text()).lowercase(Locale.ROOT).trim(':', ' ')
            if (label == "durasi" || label == "duration") {
                Regex("""(?i)(\d{1,3})\s*(?:min|menit|m)\b""").find(row.text())?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun parseDetailRating(document: Document): Double? {
        return document.detailRoot().selectFirst("[itemprop=ratingValue], .gmr-meta-rating, .gmr-rating, .rating, .score, .imdb, .vote, .nilai")
            ?.text()
            ?.replace(',', '.')
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
    }

    private fun findPoster(document: Document, baseUrl: String): String? {
        return document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.toAbsoluteUrl(baseUrl)
            ?: document.selectFirst("figure img[data-src], .poster img[data-src], .thumb img[data-src], .content-thumbnail img[data-src], img[itemprop=image][data-src], article img[data-src]")?.imageUrl(baseUrl)
            ?: document.selectFirst("figure img, .poster img, .thumb img, .content-thumbnail img, img[itemprop=image], article img")?.imageUrl(baseUrl)
    }

    private fun Element.isCardLike(): Boolean {
        if (tagName().equals("article", true)) return true
        return hasClass("gmr-box-content") || hasClass("gmr-slider-content") || hasClass("item-infinite") ||
            hasClass("result-item") || hasClass("ml-item") || hasClass("movie") || hasClass("film") ||
            hasClass("box-item") || hasClass("grid-item")
    }

    private fun Element.cardContainer(): Element {
        var current = this
        repeat(8) {
            if (current.isCardLike()) return current
            current = current.parent() ?: return current
        }
        return current
    }

    private fun Element.firstAttr(vararg names: String): String? {
        return names.asSequence().map { attr(it) }.firstOrNull { it.isNotBlank() }
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val candidates = listOf(
            attr("data-src"),
            attr("data-original"),
            attr("data-lazy-src"),
            attr("data-wpfc-original-src"),
            attr("src").takeUnless { it.startsWith("data:") },
            attr("srcset").split(" ").firstOrNull()?.takeIf { it.isNotBlank() }
        )
        val raw = candidates.firstOrNull { !it.isNullOrBlank() } ?: return null
        return raw.toAbsoluteUrl(baseUrl)?.fixImageQuality()
    }

    private fun Element.styleImage(baseUrl: String): String? {
        return Regex("""url\((["']?)(.*?)\1\)""", RegexOption.IGNORE_CASE).find(attr("style"))
            ?.groupValues
            ?.getOrNull(2)
            ?.toAbsoluteUrl(baseUrl)
    }

    private fun Element.findNearbyImage(baseUrl: String): String? {
        return parent()?.selectFirst("img[data-src], img[src]:not([src^='data:'])")?.imageUrl(baseUrl)
    }

    private fun String?.toAbsoluteUrl(baseUrl: String): String? {
        val raw = this?.trim()?.trim('"', '\'', ' ', '\n', '\r', '\t')?.cleanHtml()?.takeIf { it.isNotBlank() } ?: return null
        val decoded = decodeBase64(raw)?.takeIf { it.startsWith("http", true) || it.startsWith("//") || it.startsWith("/") }
        val candidate = decoded ?: raw
        val fixed = when {
            candidate.startsWith("//") -> "https:$candidate"
            candidate.startsWith("http://", true) || candidate.startsWith("https://", true) -> candidate
            candidate.startsWith("/") -> getBaseUrl(baseUrl).trimEnd('/') + candidate
            candidate.startsWith("?") -> baseUrl.substringBefore("?") + candidate
            else -> runCatching { URI(baseUrl).resolve(candidate).toString() }.getOrNull()
        } ?: return null
        return httpsify(fixed)
    }

    private fun getBaseUrl(url: String): String {
        return runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: mainUrl
    }

    private fun decodeBase64(value: String?): String? {
        val clean = value?.trim()?.trim('"', '\'', ' ', '\n', '\r', '\t') ?: return null
        if (clean.length < 8 || clean.length % 4 == 1) return null
        return runCatching { String(Base64.getDecoder().decode(clean), Charsets.UTF_8) }.getOrNull()
    }

    private fun cleanTitle(value: String?): String {
        return cleanText(value)
            .substringBefore(" - Film21")
            .substringBefore(" – Film21")
            .substringBefore(" | Film21")
            .replace(Regex("""(?i)^film21\s+"""), "")
            .replace(Regex("""(?i)^download\s+streaming\s+film\s+"""), "")
            .replace(Regex("""(?i)^download\s+nonton\s+"""), "")
            .replace(Regex("""(?i)^nonton\s+film\s+"""), "")
            .replace(Regex("""(?i)^nonton\s+"""), "")
            .replace(Regex("""(?i)^tonton\s+film\s+"""), "")
            .replace(Regex("""(?i)^tonton\s+"""), "")
            .replace(Regex("""(?i)\s+(?:lk21|film21)?\s*(?:sub\s*indo|subtitle\s+indonesia).*$"""), "")
            .replace(Regex("""(?i)\s+terbaru$"""), "")
            .trim()
    }

    private fun cleanDescription(value: String?): String? {
        var text = cleanText(value)
            .replace(Regex("""(?i)^sinopsis\s*:?\s*"""), "")
            .replace(Regex("""(?i)\s*\[\.\.\.\]\s*$"""), "")
            .replace(Regex("""(?i)\s*\[…\]\s*$"""), "")
            .trim()
        if (text.startsWith("Download Streaming Film", ignoreCase = true) || text.startsWith("Download Nonton", ignoreCase = true)) {
            text = text.substringAfter("–", text).trim()
            if (text.startsWith("Download", ignoreCase = true)) {
                text = text.substringAfter(" - ", text).trim()
            }
        }
        return text.takeIf { it.length > 20 }
    }

    private fun cleanText(value: String?): String {
        return Parser.unescapeEntities(value.orEmpty(), false).replace(Regex("\\s+"), " ").trim()
    }

    private fun String.cleanHtml(): String {
        return Parser.unescapeEntities(this, false)
            .replace("\\/", "/")
            .replace("&#038;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u003f", "?")
    }

    private fun String.fixImageQuality(): String {
        return replace(Regex("-\\d+x\\d+(?=\\.(?:jpg|jpeg|png|webp))", RegexOption.IGNORE_CASE), "")
    }

    private fun String.firstYear(): Int? {
        return Regex("""\b(19|20)\d{2}\b""").find(this)?.value?.toIntOrNull()
    }

    private fun titleFromUrl(url: String): String {
        return url.trimEnd('/').substringAfterLast('/').replace('-', ' ').replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
        }
    }

    private fun isUsefulTitle(value: String?): Boolean {
        val text = cleanTitle(value)
        if (text.length < 2) return false
        return !text.isBadLabel()
    }

    private fun String.isBadLabel(): Boolean {
        val value = lowercase(Locale.ROOT).trim()
        val bad = listOf("tonton", "tonton film", "trailer", "download", "genre", "negara", "tahun", "beranda", "pasang iklan", "tweet", "sharer", "home", "dmca", "iklan", "lihat semua film", "lihat semua serial tv")
        return bad.any { value == it } || value.startsWith("lihat semua") ||
            value.startsWith("negara:") || value.startsWith("genre:") || value.startsWith("tahun:") ||
            value.startsWith("quality:") || value.startsWith("kualitas:")
    }

    private fun isContentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (!host.contains("filem21.net")) return false
        val path = uri.path.orEmpty().trim('/').lowercase(Locale.ROOT)
        if (path.isBlank()) return false
        if (path.matches(Regex("""(?:page/\d+|genre/.+|country/.+|year/.+|category/.+|tag/.+|author/.+|search/.+|tv|quality/.+|network/.+|director/.+|cast/.+)"""))) return false
        if (path.contains("wp-content") || path.contains("wp-admin") || path.contains("pasang-iklan") || path.contains("dmca")) return false
        return !url.isNoiseUrl()
    }

    private fun contentKey(url: String): String {
        return url.substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)
    }

    private fun String.isPlayableCandidate(): Boolean {
        val value = lowercase(Locale.ROOT)
        if (isNoiseUrl()) return false
        if (isDirectVideoUrl()) return true
        return value.contains("embed") || value.contains("player") || value.contains("stream") || value.contains("watch") ||
            value.contains("drive.google") || value.contains("googlevideo") || value.contains("blogger.com/video") ||
            value.contains("filemoon") || value.contains("dood") || value.contains("mixdrop") || value.contains("streamsb") ||
            value.contains("streamtape") || value.contains("voe.sx") || value.contains("vidguard") || value.contains("vidhide") ||
            value.contains("lulustream") || value.contains("dropgalaxy") || value.contains("mp4upload") || value.contains("short.ink") ||
            value.contains("short.icu") || value.contains("veev.to") || value.contains("hgcloud.to") ||
            value.contains("dm21.") || value.contains("embed4me") || value.contains("upns.live") ||
            value.contains("playerp2p") || value.contains("vidplayer") || value.contains("sf21.") ||
            value.contains("master.txt") || value.contains("index-") || value.contains("/hls") ||
            value.contains("m3u8") || value.contains("mp4")
    }

    private fun String.isDirectVideoUrl(): Boolean {
        return contains(Regex("""(?i)\.(m3u8|mp4|webm|mkv|mpd)(?:\?|$)"""))
    }

    private fun String.isSubtitleUrl(): Boolean {
        return contains(Regex("""(?i)\.(srt|vtt|ass)(?:\?|$)"""))
    }

    private fun String.isNoiseUrl(): Boolean {
        val value = lowercase(Locale.ROOT)
        return value.contains("youtube.com") || value.contains("youtu.be") ||
            value.contains("facebook.com") || value.contains("twitter.com") || value.contains("x.com/") ||
            value.contains("instagram.com") || value.contains("telegram") || value.contains("api.whatsapp") ||
            value.contains("t.me/share") || value.contains("/wp-content/") || value.contains("/wp-json/") ||
            value.contains("/wp-admin/") || value.endsWith(".jpg") || value.endsWith(".jpeg") ||
            value.endsWith(".png") || value.endsWith(".webp") || value.endsWith(".gif") ||
            value.startsWith("javascript:") || value.startsWith("mailto:") || value.startsWith("#") ||
            value.startsWith("data:") || value.contains("pasang-iklan") || value.contains("slot") ||
            value.contains("gacor") || value.contains("koko88") || value.contains("casino") || value.contains("judi") ||
            value.contains("campaign.") || value.contains("doubleclick") || value.contains("googlesyndication") ||
            value.contains("google-analytics") || value.contains("bit.ly")
    }

    private val cardSelector = listOf(
        "article.item-infinite",
        "article.item",
        "article.has-post-thumbnail",
        ".gmr-box-content.gmr-box-archive",
        ".gmr-box-content:has(.entry-title)",
        ".gmr-slider-content",
        ".result-item",
        ".ml-item",
        ".movie",
        ".film",
        ".box-item",
        ".grid-item"
    ).joinToString(",")
}
