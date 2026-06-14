package com.sad25kag.terbit21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Terbit21Provider : MainAPI() {
    override var mainUrl = "https://tv.movieon21.mov"
    override var name = "Terbit21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var activeBaseUrl = mainUrl

    private val sourceHeaders = mapOf(
        "User-Agent" to MOBILE_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
    )

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "tv|series|drama-serial|dramaserial" to "TV Shows",
        "film-action-terbaru|genre/action" to "Action",
        "adventure|genre/adventure" to "Adventure",
        "animation|genre/animation" to "Animation",
        "comedy|genre/comedy" to "Comedy",
        "crime|genre/crime" to "Crime",
        "drama|genre/drama" to "Drama",
        "fantasy|genre/fantasy" to "Fantasy",
        "film-horror-terbaru|genre/horror" to "Horror",
        "mystery|genre/mystery" to "Mystery",
        "romance|genre/romance" to "Romance",
        "science-fiction|genre/sci-fi|genre/science-fiction" to "Science Fiction",
        "thriller|genre/thriller" to "Thriller",
        "country/korea" to "Korea",
        "country/china" to "China",
        "country/usa" to "USA",
        "best-rating|rating" to "Best Rating",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = findWorkingBase()
        val home = request.data.split('|')
            .map { it.trim() }
            .ifEmpty { listOf("") }
            .firstNotNullOfOrNull { path ->
                val document = runCatching {
                    app.get(buildPageUrl(path, page, base), headers = headersFor(base), referer = "$base/", timeout = 30L).document
                }.getOrNull()
                document?.parseCards(base)?.takeIf { it.isNotEmpty() }
            }
            ?: emptyList()

        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = true,
                )
            ),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val base = findWorkingBase()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val slug = keyword.slugify()
        val urls = listOf(
            "$base/?s=$encoded",
            "$base/page/1/?s=$encoded",
            "$base/search/$encoded/",
            "$base/search/$slug/",
            "$base/?s=$encoded&post_type[]=post&post_type[]=tv",
        )

        val results = linkedMapOf<String, SearchResponse>()
        urls.forEach { url ->
            runCatching {
                app.get(url, headers = headersFor(base), referer = "$base/", timeout = 30L).document.parseCards(base)
            }.getOrDefault(emptyList()).forEach { item ->
                if (keyword.length <= 3 || item.name.contains(keyword, true) || item.url.contains(slug, true)) {
                    results[item.url.substringBefore('?')] = item
                }
            }
            if (results.isNotEmpty()) return results.values.take(60)
        }
        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse {
        val base = getBaseUrl(url).takeIf { it.isNotBlank() } ?: findWorkingBase()
        val pageUrl = url.fixUrlMaybe(base) ?: url
        val document = app.get(pageUrl, headers = headersFor(base), referer = "$base/", timeout = 30L).document

        val title = document.selectFirst("h1.entry-title[itemprop=name], h1.entry-title, h1[itemprop=name], h1, meta[property=og:title], title")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
            ?.cleanTitle()
            ?: pageUrl.titleFromUrl()
            ?: name

        val poster = findPoster(document, base)
        val description = document.selectFirst("meta[property=og:description], meta[name=description], div[itemprop=description] > p, [itemprop=description], .entry-content.entry-content-single > p, .entry-content > p, .sinopsis, .synopsis, .storyline, .description, .desc")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
            ?.trim()

        val tags = document.parseMetaLinks("Genre")
            .ifEmpty {
                document.select("a[href*='/genre/'], a[href*='/category/']")
                    .map { it.text().trim() }
                    .filter { it.length in 2..40 }
                    .distinct()
            }

        val actors = document.select("span[itemprop=actors] a, .gmr-moviedata:contains(Cast:) a, .gmr-moviedata:contains(Actor:) a, a[href*='/cast/'], a[href*='/actor/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val text = document.text()
        val year = document.select("a[href*='/year/'], .gmr-moviedata:contains(Year:) a")
            .asSequence()
            .mapNotNull { YEAR_REGEX.find(it.text())?.value?.toIntOrNull() }
            .firstOrNull()
            ?: YEAR_REGEX.find(title)?.value?.toIntOrNull()
            ?: YEAR_REGEX.find(text)?.value?.toIntOrNull()

        val rating = document.selectFirst("div.gmr-meta-rating span[itemprop=ratingValue], .gmr-rating-item, [itemprop=ratingValue], .rating, .score, .imdb")
            ?.text()
            ?.trim()

        val duration = document.selectFirst("div.gmr-duration-item, span[property=duration], .gmr-moviedata:contains(Duration:)")
            ?.text()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()
            ?: DURATION_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val trailer = document.selectFirst("a.gmr-trailer-popup[href*='youtube'], a[href*='youtube.com/watch'], a[href*='youtu.be'], iframe[src*='youtube.com/embed']")
            ?.let { el -> el.attr("href").ifBlank { el.attr("src") } }
            ?.takeIf { it.contains("youtube", ignoreCase = true) || it.contains("youtu.be", ignoreCase = true) }

        val recommendations = document.parseCards(base)
            .filterNot { it.url == pageUrl }
            .distinctBy { it.url }

        val episodes = document.parseEpisodes(base)
        val isSeries = pageUrl.contains("/tv/", true) || pageUrl.contains("/series/", true) || episodes.isNotEmpty()

        return if (isSeries && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addActors(actors)
                addScore(rating)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addActors(actors)
                addScore(rating)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val base = getBaseUrl(data).takeIf { it.isNotBlank() } ?: activeBaseUrl
        val startUrl = data.fixUrlMaybe(base) ?: data
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(startUrl to "$base/")
        var delivered = false
        var rounds = 0

        while (queue.isNotEmpty() && rounds < 30) {
            rounds++
            val (currentUrl, referer) = queue.removeFirst()
            val fixedUrl = currentUrl.fixUrlMaybe(referer) ?: continue
            val key = fixedUrl.substringBefore("#")
            if (!visited.add(key)) continue

            when {
                fixedUrl.isDirectM3u8() -> {
                    if (emitM3u8(fixedUrl, referer, callback)) delivered = true
                    continue
                }
                fixedUrl.isDirectVideo() -> {
                    if (emitDirectVideo(fixedUrl, referer, callback)) delivered = true
                    continue
                }
                fixedUrl.contains(T21_HOST, true) && fixedUrl.contains("play-ads.php", true) -> {
                    val slug = queryValue(fixedUrl, "movie") ?: movieSlugFromUrl(startUrl)
                    if (slug != null && resolveT21Provider(slug, fixedUrl, subtitleCallback, callback)) delivered = true
                }
                fixedUrl.contains(SF21_HOST, ignoreCase = true) -> {
                    if (resolveSf21(fixedUrl, referer, callback)) delivered = true
                }
                else -> {
                    val success = runCatching {
                        loadExtractor(fixedUrl, referer, subtitleCallback, callback)
                    }.getOrDefault(false)
                    if (success) delivered = true
                }
            }

            val response = runCatching {
                app.get(fixedUrl, headers = headersFor(getBaseUrl(fixedUrl)) + mapOf("Referer" to referer), referer = referer, timeout = 30L)
            }.getOrNull() ?: continue

            val document = response.document
            val html = response.text.ifBlank { document.html() }.decodeHtml()
            collectSubtitles(document, fixedUrl, subtitleCallback)
            document.collectPlayerUrls(fixedUrl, html).forEach { next ->
                if (!next.isNoiseUrl()) queue.add(next to fixedUrl)
            }
        }

        return delivered
    }

    private suspend fun resolveSf21(
        playerUrl: String,
        sourceReferer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val resolver = WebViewResolver(
            SF21_M3U8_REGEX,
            userAgent = MOBILE_USER_AGENT,
            useOkhttp = true,
        )

        val captured = runCatching {
            app.get(
                playerUrl,
                headers = mapOf(
                    "User-Agent" to MOBILE_USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                    "Referer" to sourceReferer,
                ),
                interceptor = resolver,
                timeout = 45L,
            ).url
        }.getOrNull()

        val m3u8 = captured?.takeIf { it.contains(".m3u8", ignoreCase = true) } ?: return false
        return emitM3u8(m3u8, "$SF21_ORIGIN/", callback, SF21_ORIGIN)
    }

    private suspend fun resolveT21Provider(
        slug: String,
        providerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val iframe = queryValue(providerUrl, "iframe").orEmpty().lowercase(Locale.ROOT)
        return when {
            iframe.contains("hydra") -> resolveT21Hydra(slug, providerUrl, subtitleCallback, callback)
            else -> resolveT21Utama(slug, providerUrl, callback)
        }
    }

    private suspend fun resolveT21Utama(
        slug: String,
        providerUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val p2pUrl = "$T21_ORIGIN/p2p.php?movie=$slug"
        val response = runCatching {
            app.post(
                "$T21_ORIGIN/540.php?movie=$slug",
                data = mapOf("r" to providerUrl, "d" to T21_HOST),
                headers = headersFor(T21_ORIGIN) + mapOf(
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to T21_ORIGIN,
                    "Referer" to p2pUrl,
                ),
                referer = p2pUrl,
                timeout = 30L,
            ).text
        }.getOrNull() ?: return false

        var emitted = false
        FILE_JSON_REGEX.findAll(response.decodeHtml()).forEach { match ->
            val file = match.groupValues.getOrNull(1)?.decodeHtml()?.fixUrlMaybe(T21_ORIGIN) ?: return@forEach
            if (file.isDirectM3u8() && emitM3u8(file, "$T21_ORIGIN/", callback, T21_ORIGIN)) emitted = true
        }
        return emitted
    }

    private suspend fun resolveT21Hydra(
        slug: String,
        providerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val urls = linkedSetOf<String>()
        listOf(providerUrl, "$T21_ORIGIN/g-hydrax.php?movie=$slug").forEach { page ->
            runCatching {
                app.get(page, headers = headersFor(T21_ORIGIN) + mapOf("Referer" to providerUrl), referer = providerUrl, timeout = 30L).text
            }.getOrNull()?.let { html ->
                HYDRAX_URL_REGEX.findAll(html.decodeHtml())
                    .map { it.value.fixUrlMaybe(T21_ORIGIN) }
                    .filterNotNull()
                    .forEach(urls::add)
            }
        }

        var found = false
        urls.forEach { hydraUrl ->
            runCatching {
                loadExtractor(hydraUrl, "$T21_ORIGIN/", subtitleCallback) { link ->
                    if (!link.url.isNoiseUrl()) {
                        found = true
                        callback(link)
                    }
                }
            }
        }
        return found
    }

    private fun Document.parseCards(base: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        select(CARD_SELECTOR).forEach { it.toSearchResult(base)?.let { item -> results[item.url.contentKey()] = item } }
        if (results.size < 6) {
            select("article a[href], .post a[href], .item a[href], .movie a[href], .film a[href], .ml-item a[href], .result-item a[href], .poster a[href], .box a[href], h2 a[href], h3 a[href]")
                .forEach { it.toSearchResult(base)?.let { item -> results[item.url.contentKey()] = item } }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(base: String): SearchResponse? {
        val titleAnchor = if (`is`("a[href]")) this else selectFirst("h2.entry-title > a[href], h2 a[href], h3 a[href], .entry-title a[href], .title a[href], .name a[href], a[itemprop=url][href], a[href][title], a[href]")
            ?: return null

        val href = titleAnchor.attr("href").fixUrlMaybe(base) ?: return null
        if (!href.isContentUrl(base)) return null

        val container = bestContainer(titleAnchor)
        val title = listOf(
            container.selectFirst("h1, h2, h3, .entry-title, .title, .name, .data-title")?.text(),
            titleAnchor.text(),
            titleAnchor.attr("title"),
            titleAnchor.attr("aria-label"),
            href.titleFromUrl(),
        ).firstOrNull { !it.isNullOrBlank() }?.cleanTitle().orEmpty()
        if (title.isBlank()) return null

        val poster = (container.selectFirst("div.content-thumbnail img, img.wp-post-image, img[itemprop=image], img[data-src], img[data-original], img[data-lazy-src], img[data-litespeed-src], img[srcset], img[src]")
            ?: titleAnchor.selectFirst("img"))
            ?.getImageAttr(base)
            ?.fixUrlMaybe(base)
            ?.fixImageQuality()

        val rating = container.selectFirst("div.gmr-rating-item, .rating, .score, .imdb")?.text()?.trim()
        val tvType = if (href.contains("/tv/", true) || href.contains("/series/", true)) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.score = Score.from10(rating?.replace(',', '.')?.toDoubleOrNull())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.score = Score.from10(rating?.replace(',', '.')?.toDoubleOrNull())
            }
        }
    }

    private fun Document.parseEpisodes(base: String): List<Episode> {
        val result = linkedMapOf<String, Episode>()

        select("div.gmr-listseries a.button.button-shadow[href], .gmr-listseries a[href], .eplister a[href], .episodelist a[href], .episode-list a[href], a[href*='/eps/'][href], a[href*='/episode/'][href], a[href*='episode='][href]")
            .forEachIndexed { index, element ->
                val href = element.attr("href").fixUrlMaybe(base) ?: return@forEachIndexed
                if (!href.isEpisodeUrl()) return@forEachIndexed

                val rawName = element.text().ifBlank { element.attr("title") }.ifBlank { href.titleFromUrl().orEmpty() }.cleanTitle()
                if (rawName.isBlank()) return@forEachIndexed

                val episodeNumber = EPISODE_NUMBER_REGEX.find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: EPISODE_NUMBER_REGEX.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: NUMBER_REGEX.find(rawName)?.value?.toIntOrNull()
                    ?: (index + 1)

                val seasonNumber = SEASON_NUMBER_REGEX.find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: SEASON_NUMBER_REGEX.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

                result[href] = newEpisode(href) {
                    this.name = rawName
                    this.episode = episodeNumber
                    this.season = seasonNumber
                }
            }

        return result.values.toList()
    }

    private fun Document.collectPlayerUrls(referer: String, html: String = html()): List<String> {
        val base = getBaseUrl(referer)
        val urls = linkedSetOf<String>()

        select("iframe[src], iframe[data-src], iframe[data-litespeed-src]").forEach { iframe ->
            iframe.getIframeAttr()?.fixUrlMaybe(base)?.let { urls.add(it) }
        }

        select("a[href], source[src], video[src], track[src]").forEach { link ->
            val raw = link.attr("href").ifBlank { link.attr("src") }
            val fixed = raw.fixUrlMaybe(base) ?: return@forEach
            if (fixed.isPlayerLike() || fixed.isDirectMedia()) urls.add(fixed)
        }

        URL_REGEX.findAll(html.decodeHtml())
            .map { it.value.cleanUrl() }
            .filter { it.isPlayerLike() || it.isDirectMedia() }
            .mapNotNull { it.fixUrlMaybe(base) }
            .forEach { urls.add(it) }

        return urls
            .filterNot { it.contains("youtube", ignoreCase = true) || it.isNoiseUrl() }
            .filterNot { it == referer || it == base || it == "$base/" }
            .toList()
    }

    private fun collectSubtitles(document: Document, referer: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt], a[href*='.srt'], a[href*='.vtt']").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            val url = raw.fixUrlMaybe(referer) ?: return@forEach
            subtitleCallback(SubtitleFile(element.attr("label").ifBlank { "Subtitle" }, url))
        }
    }

    private fun Document.parseMetaLinks(label: String): List<String> {
        return select("div.gmr-moviedata")
            .firstOrNull { it.selectFirst("strong")?.text()?.contains(label, ignoreCase = true) == true }
            ?.select("a")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()
    }

    private fun Element.getImageAttr(base: String): String {
        return attr("abs:data-src").ifBlank { attr("abs:data-original") }
            .ifBlank { attr("abs:data-lazy-src") }
            .ifBlank { attr("abs:data-litespeed-src") }
            .ifBlank { attr("abs:srcset").substringBefore(" ") }
            .ifBlank { attr("abs:src") }
            .ifBlank { attr("data-src") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-litespeed-src") }
            .ifBlank { attr("srcset").substringBefore(" ") }
            .ifBlank { attr("src") }
            .fixUrlMaybe(base)
            .orEmpty()
    }

    private fun Element.getIframeAttr(): String? {
        return attr("data-litespeed-src").ifBlank { attr("data-src") }
            .ifBlank { attr("src") }
            .takeIf { it.isNotBlank() }
    }

    private fun findPoster(document: Document, base: String): String? {
        return document.selectFirst("figure.pull-left img, figure img[itemprop=image], img[itemprop=image], meta[property=og:image], .poster img, .thumb img, .content-thumbnail img")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.getImageAttr(base) }
            ?.fixUrlMaybe(base)
            ?.fixImageQuality()
    }

    private suspend fun findWorkingBase(): String {
        activeBaseUrl.takeIf { it.isNotBlank() }?.let { current ->
            val ok = runCatching {
                val doc = app.get("${current.trimEnd('/')}/", headers = headersFor(current), referer = "$current/", timeout = 12L).document
                doc.parseCards(current).isNotEmpty() || doc.text().contains("movie", true) || doc.text().contains("film", true)
            }.getOrDefault(false)
            if (ok) return current
        }

        SOURCE_CANDIDATES.forEach { candidate ->
            val ok = runCatching {
                val doc = app.get("${candidate.trimEnd('/')}/", headers = headersFor(candidate), referer = "$candidate/", timeout = 12L).document
                doc.parseCards(candidate).isNotEmpty() || doc.text().contains("movie", true) || doc.text().contains("film", true)
            }.getOrDefault(false)
            if (ok) {
                activeBaseUrl = candidate.trimEnd('/')
                mainUrl = activeBaseUrl
                return activeBaseUrl
            }
        }

        return activeBaseUrl.ifBlank { mainUrl }
    }

    private fun headersFor(base: String): Map<String, String> = sourceHeaders + mapOf("Referer" to "${base.trimEnd('/')}/")

    private fun buildPageUrl(path: String, page: Int, base: String): String {
        val cleanPath = path.trim('/')
        val cleanBase = base.trimEnd('/')
        return when {
            cleanPath.isBlank() && page <= 1 -> "$cleanBase/"
            cleanPath.isBlank() -> "$cleanBase/page/$page/"
            page <= 1 -> "$cleanBase/$cleanPath/"
            else -> "$cleanBase/$cleanPath/page/$page/"
        }
    }

    private suspend fun emitM3u8(
        m3u8: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        origin: String = getBaseUrl(referer),
    ): Boolean {
        val headers = mapOf(
            "Referer" to referer,
            "Origin" to origin,
            "User-Agent" to MOBILE_USER_AGENT,
        )
        M3u8Helper.generateM3u8(name, m3u8, referer, headers = headers).forEach(callback)
        return true
    }

    private suspend fun emitDirectVideo(url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        callback(
            newExtractorLink(
                name,
                name,
                url,
                ExtractorLinkType.VIDEO,
            ) {
                this.referer = referer
                this.quality = url.qualityFromUrl()
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to MOBILE_USER_AGENT,
                )
            }
        )
        return true
    }

    private fun bestContainer(anchor: Element): Element {
        return anchor.parents().firstOrNull { parent ->
            val cls = parent.className().lowercase(Locale.ROOT)
            parent.tagName().equals("article", true) || cls.contains("item") || cls.contains("post") || cls.contains("movie") || cls.contains("film") || cls.contains("card") || cls.contains("box")
        } ?: anchor
    }

    private fun String.fixUrlMaybe(base: String = activeBaseUrl): String? {
        val cleaned = cleanUrl()
        if (cleaned.isBlank() || cleaned.startsWith("javascript:", true) || cleaned.startsWith("#")) return null
        val fixedBase = base.trimEnd('/')
        return when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("http", true) -> cleaned
            cleaned.startsWith("/") -> fixedBase + cleaned
            else -> "$fixedBase/$cleaned"
        }
    }

    private fun String.cleanUrl(): String {
        return trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .trim('"', '\'', ' ', '\n', '\r', '\t')
    }

    private fun String.decodeHtml(): String {
        return replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("%2F", "/")
            .replace("%3A", ":")
            .replace("%3F", "?")
            .replace("%3D", "=")
            .replace("%26", "&")
    }

    private fun String.cleanTitle(): String {
        return replace("Permalink to:", "", ignoreCase = true)
            .replace("Nonton Film", "", ignoreCase = true)
            .replace("Streaming Film", "", ignoreCase = true)
            .replace("Sub Indo Full Movie", "", ignoreCase = true)
            .replace("| Terbit21", "", ignoreCase = true)
            .replace("| LK21", "", ignoreCase = true)
            .replace("| MovieOn21", "", ignoreCase = true)
            .trim()
    }

    private fun String.fixImageQuality(): String {
        return replace(Regex("-\\d+x\\d+(?=\\.[a-zA-Z]{3,4}(?:$|[?]))"), "")
    }

    private fun String.slugify(): String {
        return lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun String.titleFromUrl(): String? {
        return runCatching { URI(this).path.trim('/').substringAfterLast('/') }
            .getOrNull()
            ?.replace('-', ' ')
            ?.replace('_', ' ')
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.contentKey(): String = substringBefore('?').trimEnd('/').lowercase(Locale.ROOT)

    private fun String.isContentUrl(base: String): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (lower.contains("/tag/") || lower.contains("/country/") || lower.contains("/year/") || lower.contains("/genre/") || lower.contains("/category/") || lower.contains("/page/") || lower.endsWith("/privacy-policy")) return false
        val host = runCatching { URI(this).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        val baseHost = runCatching { URI(base).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        if (host.isNotBlank() && baseHost.isNotBlank() && host != baseHost) return false
        return lower.contains("/tv/") || lower.contains("/series/") || lower.contains("/movie/") || lower.contains("/film/") || lower.contains("/streaming-film/") || lower.count { it == '/' } >= 3
    }

    private fun String.isEpisodeUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (lower.contains("/tag/") || lower.contains("/country/") || lower.contains("/year/") || lower.contains("/genre/") || lower.contains("/category/")) return false
        return lower.contains("/eps/") || lower.contains("/episode/") || lower.contains("episode=") || EPISODE_NUMBER_REGEX.containsMatchIn(lower)
    }

    private fun String.isPlayerLike(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(SF21_HOST) || lower.contains(T21_HOST) || lower.contains("play-ads.php") || lower.contains("p2p.php") || lower.contains("g-hydrax.php") || lower.contains("playhydrax") || lower.contains("hydrax") || lower.contains("vidplayer") || lower.contains("embed") || lower.contains("player") || lower.contains("stream") || lower.contains("download") || lower.contains("safelink")
    }

    private fun String.isDirectMedia(): Boolean = isDirectM3u8() || isDirectVideo()
    private fun String.isDirectM3u8(): Boolean = contains(".m3u8", true)
    private fun String.isDirectVideo(): Boolean = contains(".mp4", true) || contains(".mkv", true)

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("doubleclick") || lower.contains("googlesyndication") || lower.contains("google-analytics") || lower.contains("facebook.com") || lower.contains("twitter.com") || lower.contains("instagram.com") || lower.contains("youtube.com") || lower.contains("youtu.be") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".css") || lower.endsWith(".js")
    }

    private fun String.qualityFromUrl(): Int {
        return when {
            contains("2160", true) || contains("4k", true) -> Qualities.P2160.value
            contains("1080", true) -> Qualities.P1080.value
            contains("720", true) -> Qualities.P720.value
            contains("480", true) -> Qualities.P480.value
            contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun movieSlugFromUrl(url: String): String? {
        queryValue(url, "movie")?.let { return it }
        val path = runCatching { URI(url).path.orEmpty().trim('/') }.getOrDefault("")
        return path.substringAfterLast('/').takeIf { it.isNotBlank() && !it.endsWith(".php", true) }
    }

    private fun queryValue(url: String, key: String): String? = runCatching {
        URI(url).rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
            val name = part.substringBefore('=')
            if (!name.equals(key, true)) null else URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }
    }.getOrNull()

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(activeBaseUrl)
    }

    companion object {
        private const val SF21_HOST = "sf21.vidplayer.live"
        private const val SF21_ORIGIN = "https://sf21.vidplayer.live"
        private const val T21_HOST = "t21.press"
        private const val T21_ORIGIN = "https://t21.press"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

        private val SOURCE_CANDIDATES = listOf(
            "https://tv.movieon21.mov",
            "https://162.244.95.227",
            "https://terbit21.cyou",
            "https://terbit21.pm",
            "https://terbit21.sbs",
            "https://terbit21.skin",
        )

        private const val CARD_SELECTOR =
            "article.item, article, .post, .item, .movie, .film, .ml-item, .result-item, .poster, .box, .content-thumbnail"

        private val SF21_M3U8_REGEX =
            Regex("""https://sf21\.vidplayer\.live/hls/[^'"<>\s]+\.m3u8[^'"<>\s]*""", RegexOption.IGNORE_CASE)
        private val HYDRAX_URL_REGEX =
            Regex("""https?://(?:www\.)?playhydrax\.com/?\?v=[^\s'"<>\\]+""", RegexOption.IGNORE_CASE)
        private val FILE_JSON_REGEX = Regex("""(?i)"file"\s*:\s*"([^"]+)""", RegexOption.IGNORE_CASE)
        private val URL_REGEX = Regex("""https?:\/\/[^\s'"<>\\]+""", RegexOption.IGNORE_CASE)
        private val YEAR_REGEX = Regex("""(?:19|20)\d{2}""")
        private val DURATION_REGEX = Regex("""(?i)(\d{1,3})\s*(?:min|menit|minutes|m)\b""")
        private val EPISODE_NUMBER_REGEX = Regex("""(?:Episode|Eps|Ep|E)[\s.-]*(\d+)""", RegexOption.IGNORE_CASE)
        private val SEASON_NUMBER_REGEX = Regex("""(?:Season|S)[\s.-]*(\d+)""", RegexOption.IGNORE_CASE)
        private val NUMBER_REGEX = Regex("""\d+""")
    }
}
