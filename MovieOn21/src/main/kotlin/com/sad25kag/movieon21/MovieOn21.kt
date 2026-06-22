package com.sad25kag.movieon21

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

class MovieOn21 : MainAPI() {
    override var mainUrl = "https://tv.movieon21.mov"
    override var name = "MovieOn21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.NSFW
    )

    private val t21BaseUrl = "https://t21.press"

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/" to "Featured & Upload Terbaru",
        "/rating/" to "IMDb Rating",
        "/genre/action/" to "Action",
        "/genre/adventure/" to "Adventure",
        "/genre/animation/" to "Animation",
        "/genre/comedy/" to "Comedy",
        "/genre/crime/" to "Crime",
        "/genre/drama/" to "Drama",
        "/genre/horror/" to "Horror",
        "/genre/romance/" to "Romance",
        "/genre/sci-fi/" to "Sci-fi",
        "/genre/thriller/" to "Thriller",
        "/genre/adult/" to "Adult",
        "/country/usa/" to "USA",
        "/country/indonesia/" to "Indonesia",
        "/country/india/" to "India",
        "/country/japan/" to "Japan",
        "/country/korea/" to "Korea",
        "/country/thailand/" to "Thailand",
        "/year/2026/" to "Tahun 2026",
        "/year/2025/" to "Tahun 2025",
        "/year/2024/" to "Tahun 2024",
        "/year/2023/" to "Tahun 2023"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pagedUrl(request.data, page)
        val document = try {
            app.get(url, headers = defaultHeaders, referer = mainUrl).document
        } catch (_: Throwable) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = parseSearchItems(document)
        return newHomePageResponse(request.name, items, hasNext = hasNextPage(document, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val slug = slugify(keyword)
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/search/$slug/"
        )
        val results = linkedMapOf<String, SearchResponse>()
        urls.forEach { url ->
            val document = try {
                app.get(url, headers = defaultHeaders, referer = mainUrl).document
            } catch (_: Throwable) {
                return@forEach
            }
            parseSearchItems(document)
                .filter { item ->
                    item.name.contains(keyword, ignoreCase = true) ||
                        item.url.contains(slug, ignoreCase = true) ||
                        keyword.length <= 3
                }
                .forEach { item -> results[item.url.contentKey()] = item }
            if (results.isNotEmpty()) return results.values.take(60)
        }
        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url, mainUrl) ?: return null
        val response = try {
            app.get(pageUrl, headers = defaultHeaders, referer = mainUrl)
        } catch (_: Throwable) {
            return null
        }
        val document = response.document
        val text = cleanText(document.text())

        val rawTitle = document.selectFirst("h1.entry-title, h1[itemprop=name], h1, meta[property=og:title], title")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        val title = cleanTitle(rawTitle).ifBlank { titleFromUrl(pageUrl) }
        if (title.isBlank()) return null

        val poster = findPoster(document, pageUrl)
        val tags = document.select("a[href*='/genre/'], .genres a, .genre a")
            .map { cleanText(it.text()).substringBefore("(").trim() }
            .filter { it.length in 2..40 && !it.equals("Trailer", true) && !it.equals("Tonton", true) }
            .distinct()
            .take(24)
        val actors = document.select("a[href*='/cast/'], a[href*='/actor/'], a[href*='/director/'], [itemprop=actors] a, [itemprop=director] a")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..60 }
            .distinct()
            .take(30)
        val year = document.selectFirst("a[href*='/year/']")?.text()?.let { yearFromText(it) }
            ?: yearFromText(title)
            ?: yearFromText(text)
        val rating = document.selectFirst("[itemprop=ratingValue], .rating, .score, .imdb, .vote, .gmr-rating-item")
            ?.text()
            ?.replace(',', '.')
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        val duration = Regex("""(?i)(\d{1,3})\s*(?:min|menit|minutes|m)\b""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val description = cleanDescription(
            document.selectFirst("meta[property=og:description], meta[name=description], [itemprop=description], .entry-content p, .post-content p, .sinopsis, .synopsis, .storyline, .description, .desc")
                ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        )
        val trailer = document.selectFirst("a[href*='youtube.com'], a[href*='youtu.be']")?.attr("href")?.takeIf { it.isNotBlank() }
        val episodes = parseEpisodes(document, pageUrl)
        val recommendations = parseRecommendations(document, pageUrl)
        val type = inferTvType(pageUrl, title, tags, text, episodes.isNotEmpty())

        return if (type == TvType.TvSeries && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieLoadResponse(title, pageUrl, type, pageUrl) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
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
        val startUrl = fixUrl(data, mainUrl) ?: return false
        if (startUrl.usesT21PlaybackFlow()) {
            return resolveT21Playback(startUrl, subtitleCallback, callback)
        }

        val emitted = linkedSetOf<String>()
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(startUrl to "$mainUrl/")
        var found = false
        var rounds = 0

        suspend fun emitDirect(rawUrl: String, referer: String, sourceName: String = name): Boolean {
            val fixed = fixUrl(rawUrl, referer)?.cleanupUrl() ?: return false
            if (!fixed.isPlayableMedia() || fixed.isAdOrPlaceholderMedia()) return false
            val key = fixed.substringBefore("#")
            if (!emitted.add(key)) return false
            callback(
                newExtractorLink(
                    sourceName,
                    sourceName,
                    fixed,
                    if (fixed.isM3u8Like()) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = fixed.playbackReferer(referer)
                    this.quality = fixed.qualityFromUrl()
                    this.headers = playbackHeaders(fixed, referer)
                }
            )
            return true
        }

        suspend fun emitExtractor(rawUrl: String, referer: String): Boolean {
            val fixed = fixUrl(rawUrl, referer)?.cleanupUrl() ?: return false
            if (fixed.isNoiseUrl()) return false
            if (fixed.isPlayableMedia()) return emitDirect(fixed, referer)
            var localFound = false
            try {
                loadExtractor(fixed, referer, subtitleCallback) { link ->
                    val key = link.url.substringBefore("#")
                    if (!link.url.isAdOrPlaceholderMedia() && emitted.add(key)) {
                        localFound = true
                        callback(link)
                    }
                }
            } catch (_: Throwable) {
            }
            return localFound
        }

        suspend fun inspectPage(rawUrl: String, referer: String): List<String> {
            val url = fixUrl(rawUrl, referer)?.cleanupUrl() ?: return emptyList()
            if (!visited.add(url)) return emptyList()
            if (url.isPlayableMedia()) {
                if (emitDirect(url, referer)) found = true
                return emptyList()
            }

            val response = try {
                app.get(url, headers = defaultHeaders + mapOf("Referer" to referer), referer = referer)
            } catch (_: Throwable) {
                return emptyList()
            }
            val document = response.document
            val html = normalize(response.text.ifBlank { document.html() })
            collectSubtitles(document, url, subtitleCallback)

            val links = linkedSetOf<String>()
            collectDooplayAjax(document, html, url).forEach(links::add)
            collectElementLinks(document, url).forEach(links::add)
            collectLinksFromHtml(html, url).forEach(links::add)
            collectGdrivePlayerLinks(html, url).forEach(links::add)
            return links.filterNot { it.isNoiseUrl() }
        }

        while (queue.isNotEmpty() && rounds < 42) {
            rounds++
            val (url, referer) = queue.removeFirst()
            if (emitExtractor(url, referer)) found = true
            inspectPage(url, referer).forEach { next ->
                when {
                    next.isPlayableMedia() -> if (emitDirect(next, url)) found = true
                    shouldFollow(next) -> queue.add(next to url)
                    else -> if (emitExtractor(next, url)) found = true
                }
            }
        }
        return found
    }

    private suspend fun resolveT21Playback(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val detailHtml = if (data.contains("movieon21.mov", true)) {
            try {
                app.get(data, headers = defaultHeaders, referer = "$mainUrl/").text
            } catch (_: Throwable) {
                ""
            }
        } else {
            ""
        }
        val slug = movieSlugFromEvidence(data, detailHtml) ?: return false
        val providersHtml = try {
            app.post(
                "$t21BaseUrl/data.php",
                data = mapOf("movie" to slug),
                headers = t21DataHeaders(),
                referer = "$mainUrl/"
            ).text
        } catch (_: Throwable) {
            ""
        }

        val providers = parseT21Providers(providersHtml).ifEmpty { parseT21Providers(detailHtml) }
        val visitedProviders = linkedSetOf<String>()
        var found = false

        providers
            .filter { provider -> provider.isUtama || provider.isHydra }
            .forEach { provider ->
                val providerKey = "${provider.kind}:${provider.url.substringBefore("#").lowercase(Locale.ROOT)}"
                if (!visitedProviders.add(providerKey)) return@forEach
                found = when {
                    provider.isHydra ->
                        resolveT21Hydra(slug, provider.url, subtitleCallback, callback) || found
                    provider.isUtama ->
                        resolveT21Utama(slug, provider.url, provider.displayName.ifBlank { "Utama" }, callback) || found
                    else -> found
                }
            }

        if (providers.isEmpty()) {
            found = resolveT21Utama(slug, "$t21BaseUrl/play-ads.php?movie=$slug&iframe=p2p", "Utama", callback) || found
            found = resolveT21Hydra(slug, "$t21BaseUrl/play-ads.php?movie=$slug&iframe=g-hydrax", subtitleCallback, callback) || found
        }

        return found
    }

    private suspend fun resolveT21Utama(
        slug: String,
        providerUrl: String,
        sourceLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val p2pUrl = "$t21BaseUrl/p2p.php?movie=$slug"
        val response = try {
            app.post(
                "$t21BaseUrl/540.php?movie=$slug",
                data = mapOf(
                    "r" to providerUrl,
                    "d" to "t21.press"
                ),
                headers = t21PlayerHeaders(p2pUrl),
                referer = p2pUrl
            ).text
        } catch (_: Throwable) {
            return false
        }

        var emitted = false
        extractT21JsonFiles(response).forEach { rawFile ->
            val file = fixUrl(rawFile, t21BaseUrl)?.cleanupUrl() ?: return@forEach
            if (!file.isT21UtamaMedia()) return@forEach
            callback(
                newExtractorLink(
                    name,
                    "$name $sourceLabel",
                    file,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "$t21BaseUrl/"
                    this.quality = file.qualityFromUrl().takeIf { it != Qualities.Unknown.value } ?: Qualities.P480.value
                    this.headers = playbackHeaders(file, "$t21BaseUrl/")
                }
            )
            emitted = true
        }
        return emitted
    }

    private suspend fun resolveT21Hydra(
        slug: String,
        providerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val hydraPageUrl = "$t21BaseUrl/g-hydrax.php?movie=$slug"
        val hydraPage = try {
            app.get(
                hydraPageUrl,
                headers = t21PageHeaders(providerUrl),
                referer = providerUrl
            ).text
        } catch (_: Throwable) {
            ""
        }

        val hydraUrls = linkedSetOf<String>()
        collectHydraUrls(hydraPage, hydraPageUrl).forEach(hydraUrls::add)

        if (hydraUrls.isEmpty()) {
            runCatching {
                val playAdsPage = app.get(
                    providerUrl,
                    headers = t21PageHeaders("$mainUrl/"),
                    referer = "$mainUrl/"
                ).text
                collectHydraUrls(playAdsPage, providerUrl).forEach(hydraUrls::add)
            }
        }

        var found = false
        hydraUrls.forEach { hydraUrl ->
            try {
                loadExtractor(hydraUrl, "$t21BaseUrl/", subtitleCallback) { link ->
                    if (!link.url.isAdOrPlaceholderMedia()) {
                        found = true
                        callback(link)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return found
    }

    private data class T21Provider(
        val displayName: String,
        val className: String,
        val url: String
    ) {
        val normalized: String = "$className $displayName".uppercase(Locale.ROOT)
        val iframe: String = queryValue(url, "iframe").orEmpty().lowercase(Locale.ROOT)
        val isHydra: Boolean = iframe.contains("hydra") || normalized.contains("HYDRA")
        val isUtama: Boolean = !isHydra && (
            iframe == "p2p" ||
                normalized.contains("P2P") ||
                normalized.contains("UTAMA") ||
                normalized.contains("DRIVE")
            )
        val kind: String = when {
            isHydra -> "hydra"
            isUtama -> "utama"
            else -> "skip"
        }

        companion object {
            private fun queryValue(url: String, key: String): String? = runCatching {
                URI(url).rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
                    val name = part.substringBefore('=')
                    if (!name.equals(key, true)) null else URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                }
            }.getOrNull()
        }
    }

    private fun parseT21Providers(html: String): List<T21Provider> =
        Jsoup.parse(html, t21BaseUrl)
            .select("a[href]")
            .mapNotNull { anchor ->
                val href = fixUrl(anchor.attr("href"), t21BaseUrl) ?: return@mapNotNull null
                if (!href.contains("play-ads.php", true)) return@mapNotNull null
                val label = cleanText(anchor.text()).ifBlank { cleanText(anchor.attr("title")) }
                val className = cleanText(anchor.attr("class"))
                val provider = T21Provider(label, className, href)
                if (provider.isUtama || provider.isHydra) provider else null
            }

    private fun movieSlugFromEvidence(url: String, html: String): String? {
        queryValue(url, "movie")?.takeIf { it.isNotBlank() }?.let { return it }
        val decodedHtml = normalize(html)
        listOf(
            Regex("""(?i)play-ads\.php\?movie=([^&'"<>\s]+)"""),
            Regex("""(?i)movie['"]?\s*[:=]\s*['"]([^'"<>\s]+)['"]"""),
            Regex("""(?i)/s/([^/'"<>\s]+)/"""),
            Regex("""(?i)%2Fs%2F([^%&'"<>\s]+)%2F""")
        ).forEach { regex ->
            regex.find(decodedHtml)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val path = runCatching { URI(url).path.orEmpty().trim('/') }.getOrDefault("")
        val streamingSlug = Regex("""(?i)(?:^|/)streaming-film/([^/?#]+)""")
            .find(path)
            ?.groupValues
            ?.getOrNull(1)
        if (!streamingSlug.isNullOrBlank()) return streamingSlug
        return path.substringAfterLast('/').takeIf { it.isNotBlank() && !it.endsWith(".php", true) }
    }

    private fun queryValue(url: String, key: String): String? = runCatching {
        URI(url).rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
            val name = part.substringBefore('=')
            if (!name.equals(key, true)) null else urlDecode(part.substringAfter('=', ""))
        }
    }.getOrNull()

    private fun extractT21JsonFiles(json: String): List<String> =
        Regex("""(?i)\"file\"\s*:\s*\"([^\"]+)\"""")
            .findAll(json)
            .map { normalize(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .toList()

    private fun collectHydraUrls(html: String, baseUrl: String): List<String> =
        Regex("""(?i)https?://(?:www\.)?playhydrax\.com/?\?v=[^\s'"<>\\]+""")
            .findAll(html)
            .mapNotNull { fixUrl(it.value, baseUrl) }
            .filterNot { it.isAdOrPlaceholderMedia() }
            .distinctBy { it.substringBefore("#") }
            .toList()

    private fun t21DataHeaders(): Map<String, String> = defaultHeaders + mapOf(
        "Accept" to "*/*",
        "Content-Type" to "application/x-www-form-urlencoded",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    private fun t21PlayerHeaders(referer: String): Map<String, String> = defaultHeaders + mapOf(
        "Accept" to "*/*",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to t21BaseUrl,
        "Referer" to referer
    )

    private fun t21PageHeaders(referer: String): Map<String, String> = defaultHeaders + mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Origin" to origin(referer),
        "Referer" to referer
    )

    private fun String.usesT21PlaybackFlow(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("movieon21.mov") || lower.contains("t21.press")
    }

    private fun pagedUrl(path: String, page: Int): String {
        val fixed = fixUrl(path, mainUrl) ?: mainUrl
        if (page <= 1) return fixed
        return fixed.trimEnd('/') + "/page/$page/"
    }

    private fun parseSearchItems(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select(cardSelector).forEach { card ->
            card.toSearchResult()?.let { results[it.url.contentKey()] = it }
        }
        if (results.size < 6) {
            document.select("article a[href], .post a[href], .item a[href], .movie a[href], .film a[href], .ml-item a[href], .result-item a[href], .poster a[href], .box a[href]")
                .forEach { anchor -> anchor.toSearchResult()?.let { results[it.url.contentKey()] = it } }
        }
        return results.values.take(90)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (`is`("a[href]")) this else selectFirst("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], .title a[href], .name a[href], a[href][title], a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!isContentUrl(href)) return null

        val container = anchor.bestContainer()
        val image = container.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[data-litespeed-src], img[data-wpfc-original-src], img[src], img[srcset]") ?: anchor.selectFirst("img")
        val title = listOf(
            container.selectFirst("h1, h2, h3, .entry-title, .title, .name, .data-title")?.text(),
            anchor.attr("aria-label"),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text(),
            titleFromUrl(href)
        ).filterNotNull().firstOrNull { it.isUsefulTitle() }?.let { cleanTitle(it) } ?: return null
        val poster = image?.imageUrl(mainUrl) ?: container.styleImage(mainUrl) ?: anchor.findNearbyImage(mainUrl)
        val text = cleanText(container.text())
        val tvType = inferTvType(href, title, emptyList(), text, false)
        val year = yearFromText(title) ?: yearFromText(text)
        val score = container.selectFirst("[itemprop=ratingValue], .rating, .score, .imdb, .vote, .gmr-rating-item")
            ?.text()
            ?.replace(',', '.')
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, tvType) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()
        document.select("[class*=episode] a[href], [id*=episode] a[href], .episodios a[href], .episodes a[href], .episode-list a[href], .season a[href], .tvseason a[href], .gmr-listseries a[href], a[href*='/episode/'], a[href*='/eps/']")
            .forEachIndexed { index, element ->
                val href = fixUrl(element.attr("href"), baseUrl) ?: return@forEachIndexed
                val combined = cleanText("${element.text()} $href").lowercase(Locale.ROOT)
                if (!combined.contains("episode") && !combined.contains("eps") && !combined.contains("season") && !combined.contains("/tv/")) return@forEachIndexed
                val rawName = cleanText(element.text())
                val ep = Regex("""(?i)(?:episode|eps|ep|e)\s*[-:.]?\s*(\d{1,4})""")
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
                episodes[href.contentKey()] = newEpisode(href) {
                    name = rawName.ifBlank { "Episode $ep" }
                    episode = ep
                    this.season = season
                }
            }
        return episodes.values.sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 9999 })
    }

    private fun parseRecommendations(document: Document, currentUrl: String): List<SearchResponse> =
        document.select(".related, .recommend, .rekomendasi, .idmuvi-rp, section, .owl-carousel, .swiper-wrapper")
            .flatMap { section -> section.select(cardSelector).mapNotNull { it.toSearchResult() } }
            .distinctBy { it.url.contentKey() }
            .filterNot { it.url.contentKey() == currentUrl.contentKey() }
            .take(18)

    private suspend fun collectDooplayAjax(document: Document, html: String, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val options = document.select("li.dooplay_player_option, .dooplay_player_option, .dooplay_player, .gmr-player-nav [data-post], [data-post][data-nume], [data-post][data-type], [data-id][data-nume]")
        options.forEach { option ->
            val post = option.attr("data-post").ifBlank { option.attr("data-id") }
            val nume = option.attr("data-nume").ifBlank { option.attr("data-index").ifBlank { option.attr("data-server").ifBlank { "1" } } }
            val type = option.attr("data-type").ifBlank { sourceType(document, html) }
            if (post.isBlank()) return@forEach
            ajaxActions.forEach { action ->
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
                collectLinksFromHtml(body, pageUrl).forEach(links::add)
            }
        }
        return links.toList()
    }

    private fun collectLinksFromHtml(html: String, baseUrl: String): List<String> {
        val normalized = normalize(html)
        val links = linkedSetOf<String>()
        val parsed = runCatching { Jsoup.parse(normalized, baseUrl) }.getOrNull()
        parsed?.let { collectElementLinks(it, baseUrl).forEach(links::add) }
        directMedia(normalized, baseUrl).forEach(links::add)
        iframeLinks(normalized, baseUrl).forEach(links::add)
        embeddedLinks(normalized, baseUrl).forEach(links::add)
        base64Links(normalized, baseUrl).forEach(links::add)
        Regex("(?i)\\\"(?:embed_url|iframe_url|player_url|url|src|file|source|link|m3u8|hls|video)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .findAll(normalized)
            .mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }
            .forEach(links::add)
        Regex("""(?i)(?:embed_url|iframe_url|player_url|url|src|file|source|link|m3u8|hls|video)\s*[:=]\s*['"]([^'"]+)['"]""")
            .findAll(normalized)
            .mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }
            .forEach(links::add)
        return links.toList()
    }

    private fun collectElementLinks(document: Document, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select(
            "#player iframe[src], #player iframe[data-src], .player iframe[src], .movieplay iframe[src], [id*=player] iframe[src], [class*=player] iframe[src], " +
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], video[src], video source[src], source[src], track[src], " +
                "a[href*='embed'], a[href*='player'], a[href*='stream'], a[href*='drive'], a[href*='gofile'], a[href*='dood'], a[href*='streamtape'], " +
                "a[href*='filemoon'], a[href*='vidhide'], a[href*='vidguard'], a[href*='voe'], a[href*='mp4upload'], a[href*='uqload'], a[href*='krakenfiles'], " +
                "a[href*='filelions'], a[href*='hubcloud'], a[href*='gdplayer'], a[href*='gdriveplayer'], a[href*='sht'], a[href*='short'], a[href*='.mp4'], a[href*='.m3u8'], a[title*=Download]"
        ).forEach { element ->
            val value = listOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-litespeed-src"),
                element.attr("data-url"),
                element.attr("data-link"),
                element.attr("href")
            ).firstOrNull { it.isNotBlank() }
            fixUrl(value, baseUrl)?.let { if (!it.isNoiseUrl()) links.add(it) }
        }
        return links.toList()
    }

    private fun collectSubtitles(document: Document, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val url = fixUrl(element.attr("src").ifBlank { element.attr("href") }, baseUrl) ?: return@forEach
            val label = cleanText(element.attr("label").ifBlank { element.attr("srclang").ifBlank { element.text().ifBlank { "Subtitle" } } })
            subtitleCallback(SubtitleFile(label, url))
        }
    }

    private fun iframeLinks(html: String, baseUrl: String): List<String> =
        Regex("""(?i)<(?:iframe|embed)[^>]+(?:src|data-src|data-litespeed-src)=['"]([^'"]+)['"]""")
            .findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .toList()

    private fun embeddedLinks(html: String, baseUrl: String): List<String> =
        Regex("""(?i)['"]((?:https?:)?//[^'"]+(?:embed|player|stream|drive|gofile|dood|streamtape|filemoon|vidhide|vidguard|voe|mp4upload|uqload|krakenfiles|filelions|gdplayer|gdriveplayer|hlsplaylist|stream121|hubcloud|short|sht|/e/|/v/|/d/)[^'"]*)['"]""")
            .findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .toList()

    private fun collectGdrivePlayerLinks(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val normalized = normalize(html)
        Regex("""(?i)(?:https?:)?//gdriveplayer\.io/hlsplaylist\.php\?[^\s'"<>\\]+""")
            .findAll(normalized)
            .mapNotNull { fixUrl(it.value, baseUrl) }
            .forEach(links::add)
        Regex("""(?i)(?:https?:)?//lowhls\d+\.stream121\.space/video/data/[^\s'"<>\\]+""")
            .findAll(normalized)
            .mapNotNull { fixUrl(it.value, baseUrl) }
            .forEach(links::add)
        Regex("""(?i)hlsplaylist\.php\?[^\s'"<>\\]+""")
            .findAll(normalized)
            .mapNotNull { fixUrl(it.value, "http://gdriveplayer.io/") }
            .forEach(links::add)
        return links.toList()
    }

    private fun base64Links(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""")
            .findAll(html)
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .forEach { decoded -> collectLinksFromHtml(decoded, baseUrl).forEach(links::add) }
        Regex("""(?i)Base64\.decode\(['"]([^'"]+)['"]\)""")
            .findAll(html)
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .forEach { decoded -> collectLinksFromHtml(decoded, baseUrl).forEach(links::add) }
        return links.toList()
    }

    private fun directMedia(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        Regex("""(?i)['"]((?:https?:)?//[^'"]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^'"]+|videoplayback[^'"]*|/hls/[^'"]+|/stream/[^'"]+|/video/data/[^'"]+|hlsplaylist\.php\?[^'"]+)(?:\?[^'"]*)?)['"]""")
            .findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .filter { it.isPlayableMedia() }
            .forEach(links::add)
        Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^\s'"<>\\]+|videoplayback[^\s'"<>\\]*|/hls/[^\s'"<>\\]+|/stream/[^\s'"<>\\]+|/video/data/[^\s'"<>\\]+|hlsplaylist\.php\?[^\s'"<>\\]+)(?:\?[^\s'"<>\\]*)?""")
            .findAll(html)
            .mapNotNull { fixUrl(it.value, baseUrl) }
            .filter { it.isPlayableMedia() }
            .forEach(links::add)
        Regex("""https?%3A%2F%2F[^\s'"<>]+""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { fixUrl(urlDecode(it.value), baseUrl) }
            .filter { it.isPlayableMedia() }
            .forEach(links::add)
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
            directMedia(html, baseUrl).firstOrNull()?.let { return it }
            iframeLinks(html, baseUrl).firstOrNull()?.let { return it }
            embeddedLinks(html, baseUrl).firstOrNull()?.let { return it }
            if (html.startsWith("http", true) || html.startsWith("//")) fixUrl(html, baseUrl)?.let { return it }
        }
        return null
    }

    private fun playbackHeaders(url: String, referer: String): Map<String, String> {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            lower.contains("seneng.org") ->
                defaultHeaders + mapOf(
                    "Accept" to "*/*",
                    "Origin" to t21BaseUrl,
                    "Referer" to "$t21BaseUrl/"
                )
            lower.contains("stream121.space") || lower.contains("gdriveplayer.io/hlsplaylist.php") ->
                defaultHeaders + mapOf(
                    "Accept" to "*/*",
                    "Origin" to "null"
                )
            else ->
                defaultHeaders + mapOf(
                    "Accept" to "*/*",
                    "Origin" to origin(referer),
                    "Referer" to referer
                )
        }
    }

    private fun String.playbackReferer(fallback: String): String =
        when {
            contains("seneng.org", true) -> "$t21BaseUrl/"
            contains("stream121.space", true) -> ""
            contains("gdriveplayer.io/hlsplaylist.php", true) -> ""
            else -> fallback
        }

    private fun sourceType(document: Document, html: String): String {
        val dataType = document.selectFirst("[data-type]")?.attr("data-type")?.lowercase(Locale.ROOT)
        if (!dataType.isNullOrBlank()) return dataType
        return Regex("""(?i)['"]type['"]\s*:\s*['"](movie|tv|episode)['"]""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT)
            ?: "movie"
    }

    private fun ajaxHeaders(referer: String): Map<String, String> = defaultHeaders + mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to mainUrl,
        "Referer" to referer
    )

    private fun shouldFollow(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return !lower.isNoiseUrl() && (
            lower.contains("movieon21.mov") ||
                lower.contains("t21.press") ||
                lower.contains("terbit21") ||
                lower.contains("embed") ||
                lower.contains("player") ||
                lower.contains("stream") ||
                lower.contains("drive") ||
                lower.contains("gofile") ||
                lower.contains("dood") ||
                lower.contains("streamtape") ||
                lower.contains("filemoon") ||
                lower.contains("vidhide") ||
                lower.contains("vidguard") ||
                lower.contains("voe") ||
                lower.contains("mp4upload") ||
                lower.contains("uqload") ||
                lower.contains("hubcloud") ||
                lower.contains("gdplayer") ||
                lower.contains("gdriveplayer") ||
                lower.contains("hlsplaylist.php") ||
                lower.contains("stream121.space") ||
                lower.contains("seneng.org") ||
                lower.contains("playhydrax.com") ||
                lower.contains("abyss.to") ||
                lower.contains("lowhls") ||
                lower.contains("krakenfiles") ||
                lower.contains("filelions") ||
                lower.contains("sht") ||
                lower.contains("short")
            )
    }

    private fun inferTvType(url: String, title: String, tags: List<String>, text: String, hasEpisodes: Boolean): TvType {
        val clean = cleanText("$title ${tags.joinToString(" ")} $text").lowercase(Locale.ROOT)
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return when {
            hasEpisodes || path.contains("/episode/") || path.contains("/eps/") || clean.contains("season") || clean.contains("episode") -> TvType.TvSeries
            clean.contains("adult") || clean.contains("18+") || clean.contains("semi") || clean.contains("erotic") -> TvType.NSFW
            clean.contains("korea") || clean.contains("japan") || clean.contains("china") || clean.contains("thailand") || clean.contains("india") || clean.contains("indonesia") -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }

    private fun isContentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.host.orEmpty().contains("movieon21.mov", true)) return false
        val path = uri.path.orEmpty().trim('/').lowercase(Locale.ROOT)
        if (path.isBlank()) return false
        if (!path.contains("streaming-film/")) return false
        if (url.contains("?s=", true) || url.contains("youtube.com", true) || url.contains("youtu.be", true)) return false
        return true
    }

    private fun hasNextPage(document: Document, page: Int): Boolean =
        document.selectFirst("a.next, .pagination a:contains(Next), .pagination a:contains(»), .page-numbers.next, a[href*='/page/${page + 1}/']") != null

    private fun findPoster(document: Document, baseUrl: String): String? {
        val selectors = listOf(
            "meta[property=og:image]",
            "meta[name=twitter:image]",
            ".poster img",
            ".thumb img",
            ".cover img",
            "figure img",
            ".entry-content img",
            "img[itemprop=image]",
            "article img"
        )
        selectors.forEach { selector ->
            val element = document.selectFirst(selector) ?: return@forEach
            if (element.tagName().equals("meta", true)) {
                fixUrl(element.attr("content"), baseUrl)?.takeIf { it.isImageLike() }?.let { return it.cleanImageUrl() }
            } else {
                element.imageUrl(baseUrl)?.let { return it.cleanImageUrl() }
            }
        }
        return document.body()?.styleImage(baseUrl)?.let { it.cleanImageUrl() }
    }

    private fun Element.bestContainer(): Element {
        var current: Element? = this
        repeat(8) {
            val node = current ?: return this
            val hasImage = node.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[data-litespeed-src], img[data-wpfc-original-src], img[src], img[srcset]") != null
            val hasTitle = node.selectFirst("h1, h2, h3, .entry-title, .title, .name") != null
            if (hasImage && hasTitle) return node
            current = node.parent()
        }
        return closest("article, .post, .item, .movie, .film, .card, .ml-item, .result-item, .owl-item, .swiper-slide, li, .col, .box") ?: this
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val values = listOf(
            attr("data-src"),
            attr("data-original"),
            attr("data-lazy-src"),
            attr("data-lazy"),
            attr("data-litespeed-src"),
            attr("data-wpfc-original-src"),
            attr("src"),
            attr("srcset").substringBefore(" ")
        )
        return values.mapNotNull { fixUrl(it, baseUrl) }.firstOrNull { it.isImageLike() && !it.isAdImage() }?.cleanImageUrl()
    }

    private fun Element.styleImage(baseUrl: String): String? {
        val style = attr("style") + " " + select("[style]").joinToString(" ") { it.attr("style") }
        return Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(style)
            ?.groupValues
            ?.getOrNull(2)
            ?.let { fixUrl(it, baseUrl) }
            ?.takeIf { it.isImageLike() && !it.isAdImage() }
            ?.cleanImageUrl()
    }

    private fun Element.findNearbyImage(baseUrl: String): String? =
        selectFirst("img")?.imageUrl(baseUrl)
            ?: parent()?.selectFirst("img")?.imageUrl(baseUrl)
            ?: parent()?.parent()?.selectFirst("img")?.imageUrl(baseUrl)

    private fun fixUrl(value: String?, baseUrl: String): String? {
        val raw = urlDecode(
            value.orEmpty()
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim()
                .trim('"', '\'', ',', ';')
        )
        if (raw.isBlank() || raw == "#" || raw.equals("null", true)) return null
        if (raw.startsWith("javascript:", true) || raw.startsWith("mailto:", true) || raw.startsWith("tel:", true) || raw.startsWith("data:", true) || raw.startsWith("blob:", true) || raw.startsWith("about:", true)) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> origin(baseUrl) + raw
            else -> runCatching { URI(baseUrl).resolve(raw).toString() }.getOrElse { origin(baseUrl) + "/" + raw.trimStart('/') }
        }
    }

    private fun origin(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun String.isUsefulTitle(): Boolean {
        val text = cleanTitle(this)
        if (text.length < 2) return false
        val lower = text.lowercase(Locale.ROOT)
        return lower !in setOf("home", "beranda", "watch", "tonton", "trailer", "kategori", "tahun", "negara", "sharer", "tweet", "next", "previous") &&
            !lower.contains("slot") &&
            !lower.contains("togel") &&
            !lower.contains("banner") &&
            !lower.contains("pasang iklan")
    }

    private fun cleanTitle(value: String?): String = cleanText(value)
        .replace(Regex("(?i)^nonton\\s+film\\s+"), "")
        .replace(Regex("(?i)^nonton\\s+"), "")
        .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
        .replace(Regex("(?i)\\s+sub\\s+indo.*$"), "")
        .replace(Regex("(?i)\\s+download\\s+.*$"), "")
        .replace(Regex("(?i)\\s*[-–|]\\s*movieon21.*$"), "")
        .replace(Regex("(?i)\\s*[-–|]\\s*terbit21.*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanDescription(value: String?): String = cleanText(value)
        .replace(Regex("(?i)^nonton\\s+"), "")
        .replace(Regex("(?i)\\s*[-–|]\\s*movieon21.*$"), "")
        .replace(Regex("(?i)\\s*[-–|]\\s*terbit21.*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanText(value: String?): String = value.orEmpty().replace("\u00a0", " ").replace(Regex("\\s+"), " ").trim()

    private fun titleFromUrl(url: String): String {
        val slug = runCatching { URI(url).path.trim('/').substringAfterLast('/') }.getOrDefault(url.substringAfterLast("/"))
            .substringBefore("?")
            .replace(Regex("(?i)-subtitle-indonesia.*$"), "")
            .replace(Regex("(?i)-sub-indo.*$"), "")
        return slug.split("-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }
            .let { cleanTitle(it) }
    }

    private fun yearFromText(value: String?): Int? = Regex("""\b(19|20)\d{2}\b""")
        .find(value.orEmpty())
        ?.value
        ?.toIntOrNull()

    private fun slugify(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun normalize(value: String): String = urlDecode(value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&"))
    private fun urlDecode(value: String): String = try { URLDecoder.decode(value, "UTF-8") } catch (_: Throwable) { value }

    private fun decodeBase64(value: String): String? {
        val raw = value.trim()
        if (raw.length < 8) return null
        val normalized = raw.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try {
            String(Base64.getDecoder().decode(padded))
        } catch (_: Throwable) {
            try { String(Base64.getUrlDecoder().decode(padded)) } catch (_: Throwable) { null }
        }
    }

    private fun String.cleanupUrl(): String = replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .trim()
        .trim('"', '\'', ',', ';')

    private fun String.cleanImageUrl(): String = replace(Regex("""-\d+x\d+(?=\.)"""), "")
    private fun String.contentKey(): String = substringBefore("#").substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)

    private fun String.isImageLike(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp") || lower.contains("/images/") || lower.contains("image.tmdb.org") || lower.contains("rts.gdn")
    }

    private fun String.isAdImage(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("slot") || lower.contains("togel") || lower.contains("bet") || lower.contains("dewa") || lower.contains("logo") || lower.contains("favicon") || lower.contains("banner")
    }

    private fun String.isPlayableMedia(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (isAdOrPlaceholderMedia()) return false
        if (
            lower.endsWith(".html") ||
            lower.endsWith(".htm") ||
            (lower.endsWith(".php") && !lower.contains("hlsplaylist.php")) ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.contains("mime=image") ||
            lower.contains("=image/")
        ) return false

        return lower.isM3u8Like() ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains("/video/data/") ||
            lower.contains("videoplayback") ||
            lower.contains("mime=video") ||
            (lower.contains("googlevideo.com") && lower.contains("videoplayback"))
    }

    private fun String.isM3u8Like(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".m3u8") ||
            lower.contains("m3u8") ||
            lower.contains("hlsplaylist.php") ||
            lower.contains("/hls/") ||
            lower.contains("/stream/")
    }

    private fun String.isAdOrPlaceholderMedia(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("loading.mp4") ||
            lower.contains("terbit21org/terbit21") ||
            lower.contains("raw.githubusercontent.com/terbit21org") ||
            lower.contains("github.com/terbit21org") ||
            lower.contains("googlevideo.com") ||
            lower.contains("youtube.com") ||
            lower.contains("youtu.be") ||
            lower.contains("shopee") ||
            lower.contains("jwpltx.com") ||
            lower.contains("morphify.net") ||
            lower.contains("googlesyndication") ||
            lower.contains("doubleclick") ||
            lower.contains("histats") ||
            lower.contains("tracking") ||
            lower.contains("analytics") ||
            lower.contains("pagead") ||
            lower.contains("/ads") ||
            lower.contains("ads.") ||
            lower.contains("/ping.gif") ||
            lower.contains("cdn-cgi/rum") ||
            lower.contains("challenge-platform")
    }

    private fun String.isT21UtamaMedia(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return !isAdOrPlaceholderMedia() && lower.contains("seneng.org/") && isM3u8Like()
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (isAdOrPlaceholderMedia()) return true
        return lower.contains("facebook.com") ||
            lower.contains("telegram") ||
            lower.contains("twitter.com") ||
            lower.contains("x.com") ||
            lower.contains("whatsapp") ||
            lower.contains("mailto:") ||
            lower.contains("youtube.com") ||
            lower.contains("youtu.be") ||
            lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.contains("google-analytics") ||
            lower.contains("/wp-content/") ||
            lower.contains("/wp-json/") ||
            lower.contains(".css") ||
            lower.contains(".js") ||
            lower.contains("favicon") ||
            lower.contains("logo") ||
            lower.contains("banner") ||
            lower.contains("slot") ||
            lower.contains("togel") ||
            lower.contains("bet")
    }

    private fun String.qualityFromUrl(): Int {
        val lower = lowercase(Locale.ROOT)
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1440") || lower.contains("2k") -> Qualities.P1440.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private val ajaxActions = listOf(
        "doo_player_ajax",
        "doo_ajax_player",
        "player_ajax",
        "muvipro_player_content",
        "gmr_load_player"
    )

    private val cardSelector = listOf(
        "article",
        ".post",
        ".item",
        ".movie",
        ".film",
        ".ml-item",
        ".result-item",
        ".owl-item",
        ".swiper-slide",
        ".poster",
        ".thumbnail",
        ".box",
        ".col"
    ).joinToString(",")
}
