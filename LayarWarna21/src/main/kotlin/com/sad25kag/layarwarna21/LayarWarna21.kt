package com.sad25kag.layarwarna21

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

class LayarWarna21 : MainAPI() {
    override var mainUrl = "https://hisgloryco.com"
    override var name = "LayarWarna21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/" to "Film Online Terbaru",
        "/film-terbaik/" to "Film Terbaik",
        "/genre/action/" to "Action",
        "/genre/drama/" to "Drama",
        "/genre/horror/" to "Horror",
        "/genre/comedy/" to "Comedy",
        "/genre/thriller/" to "Thriller",
        "/genre/romance/" to "Romance",
        "/genre/film-indonesia/" to "Film Indonesia",
        "/country/indonesia/" to "Indonesia",
        "/country/usa/" to "USA",
        "/country/japan/" to "Japan",
        "/year/2026/" to "Tahun 2026",
        "/year/2025/" to "Tahun 2025",
        "/year/2024/" to "Tahun 2024",
        "/year/2023/" to "Tahun 2023"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = try {
            app.get(url, headers = headers, referer = mainUrl).document
        } catch (_: Throwable) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = parseCards(document)
        return newHomePageResponse(request.name, items, hasNext = hasNext(document, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val slug = keyword.slugify()
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/search/$slug/"
        )
        val results = linkedMapOf<String, SearchResponse>()
        urls.forEach { url ->
            val document = try {
                app.get(url, headers = headers, referer = mainUrl).document
            } catch (_: Throwable) {
                return@forEach
            }
            parseCards(document)
                .filter { item ->
                    item.name.contains(keyword, ignoreCase = true) ||
                        item.url.contains(slug, ignoreCase = true) ||
                        keyword.length <= 3
                }
                .forEach { item -> results[item.url.key()] = item }
            if (results.isNotEmpty()) return results.values.take(60)
        }
        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val detailUrl = fixUrl(url, mainUrl) ?: return null
        val response = try {
            app.get(detailUrl, headers = headers, referer = mainUrl)
        } catch (_: Throwable) {
            return null
        }
        val document = response.document
        val pageText = clean(document.text())
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1[itemprop=name], h1")?.text()
                ?: document.selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content")
                ?: titleFromUrl(detailUrl)
        )
        if (title.isBlank()) return null

        val poster = poster(document)
        val description = cleanDescription(
            document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
                ?: document.selectFirst(".entry-content p, .post-content p, .sinopsis, .synopsis, [itemprop=description], .desc, .description")?.text()
        )
        val tags = document.select("a[href*='/genre/']")
            .map { clean(it.text()) }
            .filter { it.length in 2..40 && !it.equals("Trailer", true) }
            .distinct()
            .take(20)
        val actors = document.select("a[href*='/cast/'], a[href*='/actor/'], a[href*='/director/'], [itemprop=actors] a, [itemprop=director] a")
            .map { clean(it.text()) }
            .filter { it.length in 2..60 }
            .distinct()
            .take(30)
        val year = document.selectFirst("a[href*='/year/']")?.text()?.year()
            ?: title.year()
            ?: pageText.year()
        val duration = Regex("""(?i)(\d{1,3})\s*(?:min|menit|minutes|m)\b""")
            .find(pageText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val trailer = document.selectFirst("a[href*='youtube.com'], a[href*='youtu.be']")?.attr("href")?.takeIf { it.isNotBlank() }
        val episodes = parseEpisodes(document, detailUrl)
        val recommendations = parseRecommendations(document, detailUrl)
        val type = inferType(detailUrl, title, tags, pageText, episodes.isNotEmpty())

        return if (type == TvType.TvSeries && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, detailUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val playableData = firstWatchLink(document, detailUrl) ?: detailUrl
            newMovieLoadResponse(title, detailUrl, TvType.Movie, playableData) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
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
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(startUrl to mainUrl)
        var found = false
        var rounds = 0

        suspend fun emitDirect(rawUrl: String, referer: String, sourceName: String = name): Boolean {
            val url = fixUrl(rawUrl, referer)?.cleanUrl() ?: return false
            if (!url.isMedia() || url.isNoise()) return false
            val key = url.substringBefore("#")
            if (!emitted.add(key)) return false
            callback(
                newExtractorLink(
                    sourceName,
                    sourceName,
                    url,
                    if (url.isM3u8()) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = url.quality()
                    this.headers = mediaHeaders(referer)
                }
            )
            return true
        }

        suspend fun emitExtractor(rawUrl: String, referer: String): Boolean {
            val url = fixUrl(rawUrl, referer)?.cleanUrl() ?: return false
            if (url.isNoise()) return false
            if (url.isMedia()) return emitDirect(url, referer)
            var localFound = false
            try {
                loadExtractor(url, referer, subtitleCallback) { link ->
                    val key = link.url.substringBefore("#")
                    if (!link.url.isNoise() && emitted.add(key)) {
                        localFound = true
                        callback(link)
                    }
                }
            } catch (_: Throwable) {
            }
            return localFound
        }

        suspend fun inspectPage(rawUrl: String, referer: String): List<String> {
            val pageUrl = fixUrl(rawUrl, referer)?.cleanUrl() ?: return emptyList()
            if (!visited.add(pageUrl)) return emptyList()
            if (pageUrl.isMedia()) {
                if (emitDirect(pageUrl, referer)) found = true
                return emptyList()
            }

            val response = try {
                app.get(pageUrl, headers = headers + mapOf("Referer" to referer), referer = referer)
            } catch (_: Throwable) {
                return emptyList()
            }
            val html = normalize(response.text.ifBlank { response.document.html() })
            val document = response.document
            collectSubtitles(document, pageUrl, subtitleCallback)

            val links = linkedSetOf<String>()
            collectServerOptionLinks(document, html, pageUrl).forEach(links::add)
            collectDooplayPlayerLinks(document, html, pageUrl).forEach(links::add)
            collectElementLinks(document, pageUrl).forEach(links::add)
            collectLinksFromHtml(html, pageUrl).forEach(links::add)
            return links.filterNot { it.isNoise() }
        }

        while (queue.isNotEmpty() && rounds < 36) {
            rounds++
            val (url, referer) = queue.removeFirst()
            if (emitExtractor(url, referer)) found = true
            inspectPage(url, referer).forEach { next ->
                when {
                    next.isMedia() -> if (emitDirect(next, url)) found = true
                    shouldFollow(next) -> queue.add(next to url)
                    else -> if (emitExtractor(next, url)) found = true
                }
            }
        }
        return found
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select(cardSelector).forEach { card ->
            card.toSearchResult()?.let { results[it.url.key()] = it }
        }
        if (results.size < 6) {
            document.select("article a[href], .post a[href], .item a[href], .movie a[href], .film a[href], .ml-item a[href], .result-item a[href], .poster a[href], .box a[href], h2 a[href], h3 a[href]")
                .forEach { anchor -> anchor.toSearchResult()?.let { results[it.url.key()] = it } }
        }
        return results.values.take(90)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (`is`("a[href]")) this else selectFirst("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], .title a[href], .name a[href], a[href][title], a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!isContentUrl(href)) return null
        val container = anchor.bestContainer()
        val image = container.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[data-litespeed-src], img[src], img[srcset]") ?: anchor.selectFirst("img")
        val title = listOf(
            container.selectFirst("h1, h2, h3, .entry-title, .title, .name")?.text(),
            anchor.attr("aria-label"),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text(),
            titleFromUrl(href)
        ).filterNotNull().firstOrNull { it.usefulTitle() }?.let { cleanTitle(it) } ?: return null
        val poster = image?.imageUrl() ?: container.styleImage() ?: anchor.findNearbyImage()
        val text = clean(container.text())
        val tvType = inferType(href, title, emptyList(), text, false)
        val year = title.year() ?: text.year()
        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()
        document.select("[class*=episode] a[href], [id*=episode] a[href], .episodes a[href], .episode-list a[href], .episodios a[href], .season a[href], .tvseason a[href], a[href*='/episode/'], a[href*='/eps/'], a[href*='-episode-']")
            .forEachIndexed { index, element ->
                val href = fixUrl(element.attr("href"), baseUrl) ?: return@forEachIndexed
                if (!isContentUrl(href)) return@forEachIndexed
                val evidence = clean("${element.text()} $href").lowercase(Locale.ROOT)
                if (!evidence.contains("episode") && !evidence.contains("eps") && !evidence.contains("season")) return@forEachIndexed
                val rawName = clean(element.text())
                val number = Regex("""(?i)(?:episode|eps|ep|e)\s*[-:.]?\s*(\d{1,4})""")
                    .find("$rawName $href")
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: (index + 1)
                val season = Regex("""(?i)(?:season|s)\s*[-:.]?\s*(\d{1,3})""")
                    .find("$rawName $href")
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                episodes[href.key()] = newEpisode(href) {
                    name = rawName.ifBlank { "Episode $number" }
                    episode = number
                    this.season = season
                }
            }
        return episodes.values.sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 9999 })
    }

    private fun parseRecommendations(document: Document, currentUrl: String): List<SearchResponse> =
        document.select(".related, .recommend, .rekomendasi, section, .owl-carousel, .swiper-wrapper")
            .flatMap { section -> section.select(cardSelector).mapNotNull { it.toSearchResult() } }
            .distinctBy { it.url.key() }
            .filterNot { it.url.key() == currentUrl.key() }
            .take(18)

    private fun firstWatchLink(document: Document, baseUrl: String): String? {
        val direct = document.select("a[href]")
            .firstNotNullOfOrNull { anchor ->
                val href = fixUrl(anchor.attr("href"), baseUrl) ?: return@firstNotNullOfOrNull null
                val text = clean("${anchor.text()} ${anchor.attr("class")} ${anchor.attr("title")} $href").lowercase(Locale.ROOT)
                if ((text.contains("watch") || text.contains("play") || text.contains("stream")) && isContentUrl(href)) href else null
            }
        return direct
    }

    private fun collectServerOptionLinks(document: Document, html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val values = linkedSetOf<String>()
        document.select(".mobius option[value], select option[value], .mirror option[value], option[value], [data-video], [data-src], [data-embed], [data-iframe], [data-url], [data-link], [data-href], [data-player]")
            .forEach { element ->
                listOf("value", "data-video", "data-src", "data-embed", "data-iframe", "data-url", "data-link", "data-href", "data-player")
                    .map { element.attr(it) }
                    .filter { it.isNotBlank() }
                    .forEach(values::add)
            }
        Regex("""(?i)<option[^>]+value=['"]([^'"]+)['"][^>]*>""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .forEach(values::add)
        values.forEach { value ->
            decodeCandidates(value).forEach { decoded ->
                extractUrlsFromValue(decoded, baseUrl).forEach(links::add)
                collectLinksFromHtml(decoded, baseUrl).forEach(links::add)
            }
        }
        return links.toList()
    }

    private suspend fun collectDooplayPlayerLinks(document: Document, html: String, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val options = document.select("li.dooplay_player_option[data-post], .dooplay_player_option[data-post], [data-post][data-nume], [data-post][data-type], [data-id][data-nume]")
        if (options.isEmpty()) return emptyList()
        val actionNames = listOf("doo_player_ajax", "dooplayer_ajax", "player_ajax")
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        options.forEach { option ->
            val post = option.attr("data-post").ifBlank { option.attr("data-id") }
            if (post.isBlank()) return@forEach
            val nume = option.attr("data-nume").ifBlank { option.attr("data-index").ifBlank { option.attr("data-server").ifBlank { "1" } } }
            val type = option.attr("data-type").ifBlank { if (html.contains("tvshow", true)) "tv" else "movie" }
            actionNames.forEach { action ->
                val body = try {
                    app.post(
                        ajaxUrl,
                        data = mapOf(
                            "action" to action,
                            "post" to post,
                            "nume" to nume,
                            "type" to type
                        ),
                        headers = ajaxHeaders(pageUrl),
                        referer = pageUrl
                    ).text
                } catch (_: Throwable) {
                    ""
                }
                if (body.isNotBlank()) collectLinksFromHtml(body, pageUrl).forEach(links::add)
            }
        }
        return links.toList()
    }

    private fun collectLinksFromHtml(html: String, baseUrl: String): List<String> {
        val normalized = normalize(html)
        val links = linkedSetOf<String>()
        runCatching { Jsoup.parse(normalized, baseUrl) }.getOrNull()?.let { doc ->
            collectElementLinks(doc, baseUrl).forEach(links::add)
            collectServerOptionLinks(doc, normalized, baseUrl).forEach(links::add)
        }
        Regex("""(?i)<(?:iframe|embed)[^>]+(?:src|data-src|data-litespeed-src)=['"]([^'"]+)['"]""")
            .findAll(normalized)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .forEach(links::add)
        Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+(?:embed|player|stream|drive|dood|filemoon|vidhide|vidguard|streamtape|voe|mp4upload|uqload|krakenfiles|filelions|gdriveplayer|ok\.ru|dailymotion|rumble|mega|/e/|/v/|/d/)[^\s'"<>\\]*""")
            .findAll(normalized)
            .mapNotNull { fixUrl(it.value.trim('"', '\'', '\\'), baseUrl) }
            .forEach(links::add)
        Regex("""(?i)["'](?:embed_url|iframe_url|player_url|url|src|file|source|link|video)["']\s*[:=]\s*["']([^"']+)["']""")
            .findAll(normalized)
            .flatMap { decodeCandidates(it.groupValues[1]).asSequence() }
            .flatMap { extractUrlsFromValue(it, baseUrl).asSequence() }
            .forEach(links::add)
        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""")
            .findAll(normalized)
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .forEach { decoded -> collectLinksFromHtml(decoded, baseUrl).forEach(links::add) }
        directMedia(normalized, baseUrl).forEach(links::add)
        return links.toList()
    }

    private fun collectElementLinks(document: Document, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select("#player iframe[src], #player iframe[data-src], .player iframe[src], .movieplay iframe[src], [id*=player] iframe[src], [class*=player] iframe[src], iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], video[src], video source[src], source[src], a[href*='embed'], a[href*='player'], a[href*='stream'], a[href*='drive'], a[href*='dood'], a[href*='filemoon'], a[href*='vidhide'], a[href*='vidguard'], a[href*='streamtape'], a[href*='voe'], a[href*='mp4upload'], a[href*='uqload'], a[href*='krakenfiles'], a[href*='filelions'], a[href*='gdriveplayer'], a[href*='.mp4'], a[href*='.m3u8']")
            .forEach { element ->
                listOf("src", "data-src", "data-litespeed-src", "data-url", "data-link", "href")
                    .map { element.attr(it) }
                    .firstOrNull { it.isNotBlank() }
                    ?.let { fixUrl(it, baseUrl) }
                    ?.takeIf { !it.isNoise() }
                    ?.let(links::add)
            }
        return links.toList()
    }

    private fun collectSubtitles(document: Document, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val url = fixUrl(element.attr("src").ifBlank { element.attr("href") }, baseUrl) ?: return@forEach
            val label = clean(element.attr("label").ifBlank { element.attr("srclang").ifBlank { element.text().ifBlank { "Subtitle" } } })
            subtitleCallback(SubtitleFile(label, url))
        }
    }

    private fun directMedia(html: String, baseUrl: String): List<String> =
        Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+\.(?:m3u8|mp4)(?:\?[^\s'"<>\\]*)?""")
            .findAll(html)
            .mapNotNull { fixUrl(it.value, baseUrl) }
            .filterNot { it.isNoise() }
            .toList()

    private fun decodeCandidates(raw: String): List<String> {
        val values = linkedSetOf<String>()
        fun add(value: String) {
            val clean = normalize(value)
            if (clean.isNotBlank()) values.add(clean)
        }
        add(raw)
        add(urlDecode(raw))
        add(raw.replace("\\/", "/"))
        val base = raw.trim().replace(" ", "+")
        decodeBase64(base)?.let { add(it) }
        urlDecode(base).takeIf { it != base }?.let { decoded -> decodeBase64(decoded)?.let { add(it) } }
        return values.toList()
    }

    private fun extractUrlsFromValue(value: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val decoded = normalize(value)
        runCatching { Jsoup.parse(decoded, baseUrl) }.getOrNull()?.let { document ->
            collectElementLinks(document, baseUrl).forEach(links::add)
        }
        Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+""")
            .findAll(decoded)
            .mapNotNull { fixUrl(it.value.trim('"', '\'', '\\'), baseUrl) }
            .filterNot { it.isNoise() }
            .forEach(links::add)
        return links.toList()
    }

    private fun poster(document: Document): String? =
        document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".poster img, .thumb img, .entry-content img, article img, img[itemprop=image], img[data-src], img[src]")?.imageUrl()

    private fun hasNext(document: Document, page: Int): Boolean {
        if (document.selectFirst("a.next, .nextpostslink, .pagination .next, a[href*='/page/${page + 1}/']") != null) return true
        return document.select(".pagination a, .nav-links a, .page-numbers a")
            .any { it.text().trim() == (page + 1).toString() }
    }

    private fun pageUrl(path: String, page: Int): String {
        val fixed = fixUrl(path, mainUrl) ?: mainUrl
        if (page <= 1) return fixed
        return fixed.trimEnd('/') + "/page/$page/"
    }

    private fun isContentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (!host.endsWith("hisgloryco.com")) return false
        val path = uri.path.orEmpty().trim('/').lowercase(Locale.ROOT)
        if (path.isBlank()) return false
        val blocked = listOf(
            "genre/", "country/", "year/", "tag/", "author/", "category/", "page/", "wp-", "feed", "film-terbaik", "privacy", "dmca"
        )
        return blocked.none { path == it.trimEnd('/') || path.startsWith(it) }
    }

    private fun inferType(url: String, title: String, tags: List<String>, text: String, hasEpisodes: Boolean): TvType {
        val evidence = clean("$url $title ${tags.joinToString(" ")} $text").lowercase(Locale.ROOT)
        return if (hasEpisodes || evidence.contains("tv series") || evidence.contains("season") || evidence.contains("episode")) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
    }

    private fun shouldFollow(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.isMedia() || lower.isNoise()) return false
        return lower.contains("hisgloryco.com") || lower.contains("player") || lower.contains("embed") || lower.contains("stream") || lower.contains("iframe")
    }

    private fun ajaxHeaders(referer: String): Map<String, String> = headers + mapOf(
        "Accept" to "*/*",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to mainUrl,
        "Referer" to referer
    )

    private fun mediaHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Referer" to referer
    )

    private fun Element.bestContainer(): Element {
        var current: Element = this
        repeat(4) {
            val parent = current.parent() ?: return current
            val tag = parent.tagName().lowercase(Locale.ROOT)
            val cls = parent.className().lowercase(Locale.ROOT)
            if (tag in listOf("article", "li") || cls.contains("item") || cls.contains("movie") || cls.contains("film") || cls.contains("post") || cls.contains("card")) {
                return parent
            }
            current = parent
        }
        return this
    }

    private fun Element.imageUrl(): String? {
        val raw = attr("data-src").ifBlank { attr("data-original") }
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-litespeed-src") }
            .ifBlank { attr("src") }
            .ifBlank { attr("srcset").substringBefore(" ") }
        return fixUrl(raw, mainUrl)?.takeIf { !it.isNoise() }
    }

    private fun Element.styleImage(): String? {
        val style = attr("style")
        return Regex("""url\(['"]?([^'")]+)['"]?\)""")
            .find(style)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { fixUrl(it, mainUrl) }
            ?.takeIf { !it.isNoise() }
    }

    private fun Element.findNearbyImage(): String? {
        var current: Element? = this
        repeat(4) {
            current = current?.parent()
            val image = current?.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[src]")?.imageUrl()
            if (!image.isNullOrBlank()) return image
        }
        return null
    }

    private fun cleanTitle(raw: String?): String = clean(raw.orEmpty())
        .replace(Regex("""(?i)\s*[-|]\s*LayarWarna21.*$"""), "")
        .replace(Regex("""(?i)^Nonton\s+Film\s+"""), "")
        .replace(Regex("""(?i)\s+Subtitle\s+Indonesia.*$"""), "")
        .trim()

    private fun cleanDescription(raw: String?): String? = clean(raw.orEmpty())
        .replace(Regex("""(?i)^Sinopsis\s+Film\s+[^:]+:\s*"""), "")
        .takeIf { it.length > 15 }

    private fun clean(raw: String): String = raw
        .replace("\u00a0", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalize(raw: String): String = raw
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#39;", "'")
        .replace("\\/", "/")
        .replace("\\u003c", "<", ignoreCase = true)
        .replace("\\u003e", ">", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\\"", "\"")

    private fun decodeBase64(raw: String): String? = runCatching {
        val candidate = raw.trim()
        if (candidate.length < 12 || !candidate.matches(Regex("""[A-Za-z0-9+/=_-]+"""))) return null
        val padded = candidate.replace('-', '+').replace('_', '/').let { value ->
            value + "=".repeat((4 - value.length % 4) % 4)
        }
        String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun urlDecode(raw: String): String = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    private fun titleFromUrl(url: String): String = runCatching {
        URI(url).path.orEmpty().trim('/').substringAfterLast('/').replace('-', ' ')
    }.getOrDefault(url).split(' ').joinToString(" ") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private fun String.slugify(): String = lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')

    private fun String.year(): Int? = Regex("""\b(19\d{2}|20\d{2})\b""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    private fun String.usefulTitle(): Boolean {
        val title = clean(this)
        if (title.length < 2) return false
        val lower = title.lowercase(Locale.ROOT)
        val blocked = listOf("watch", "trailer", "home", "more movie", "hd", "cam", "server", "click to play", "turn off")
        return blocked.none { lower == it || lower.contains("ambilbonus") }
    }

    private fun String.key(): String = substringBefore("#").substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)

    private fun String.cleanUrl(): String = normalize(this).trim().trim('"', '\'', '\\')

    private fun String.isM3u8(): Boolean = lowercase(Locale.ROOT).substringBefore("?").endsWith(".m3u8")

    private fun String.isMedia(): Boolean {
        val lower = lowercase(Locale.ROOT).substringBefore("#")
        return lower.substringBefore("?").endsWith(".m3u8") || lower.substringBefore("?").endsWith(".mp4")
    }

    private fun String.quality(): Int {
        val lower = lowercase(Locale.ROOT)
        return Regex("""(2160|1440|1080|720|480|360|240)p?""")
            .find(lower)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: when {
                lower.contains("fhd") -> Qualities.P1080.value
                lower.contains("hd") -> Qualities.P720.value
                lower.contains("sd") -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }
    }

    private fun String.isNoise(): Boolean {
        val lower = lowercase(Locale.ROOT)
        val blocked = listOf(
            "googleads", "googlesyndication", "doubleclick", "facebook.com", "twitter.com", "youtube.com/watch", "youtu.be/", "telegram", "ambilbonus", "vipmerahtoto", "nagagas", "gampangbanget", "emasputih", "birureborn", "wp-content/uploads", ".jpg", ".jpeg", ".png", ".webp", ".gif", ".css", ".js"
        )
        return blocked.any { lower.contains(it) }
    }

    companion object {
        private const val cardSelector = "article, .post, .item, .movie, .film, .ml-item, .result-item, .poster, .box, .owl-item, .swiper-slide"
    }
}
