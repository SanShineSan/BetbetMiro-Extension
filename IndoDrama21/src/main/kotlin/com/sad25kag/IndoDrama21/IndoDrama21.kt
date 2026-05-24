package com.sad25kag.IndoDrama21

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

@CloudstreamPlugin
class IndoDrama21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(IndoDrama21())
    }
}

class IndoDrama21 : MainAPI() {
    override var mainUrl = "http://89.124.111.64"
    override var name = "IndoDrama21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "" to "Drama Baru",
        "group:dramaindo/ongoing/|ongoing/|series/ongoing/" to "Ongoing",
        "group:dramaindo/complete/|complete/|series/complete/" to "Complete",
        "group:tv-show/|dramaindo/tv-show/|series/type/tv-show/" to "TV Show",
        "group:movie/|dramaindo/movie/|series/type/movie/" to "Movie",
        "group:ost/|dramaindo/ost/" to "OST",
        "drama-list/" to "Drama List",

        "group:series/genre/action/|series/genre/adventure/|genre/action/|genre/adventure/" to "Action & Adventure",
        "group:series/genre/drama/|series/genre/life/|series/genre/family/|genre/drama/|genre/life/" to "Drama & Life",
        "group:series/genre/romance/|series/genre/friendship/|series/genre/school/|genre/romance/|genre/school/" to "Romance & Youth",
        "group:series/genre/comedy/|series/genre/variety-show/|genre/comedy/" to "Comedy & Variety",
        "group:series/genre/historical/|series/genre/fantasy/|series/genre/wuxia/|series/genre/supernatural/|genre/historical/|genre/fantasy/" to "Historical & Fantasy",
        "group:series/genre/crime/|series/genre/law/|series/genre/mystery/|series/genre/thriller/|series/genre/suspense/|genre/crime/|genre/mystery/|genre/thriller/" to "Crime & Thriller",
        "group:series/genre/medical/|series/genre/business/|series/genre/military/|genre/medical/|genre/business/" to "Medical & Career",

        "group:country/korea/|series/country/korea/|drama-korea/" to "Korea",
        "group:country/china/|series/country/china/|drama-china/" to "China",
        "group:country/japan/|series/country/japan/|drama-japan/" to "Japan",
        "group:country/thailand/|series/country/thailand/|drama-thailand/" to "Thailand"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val paths = request.data.removePrefix("group:")
            .split("|")
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(request.data) }

        val items = linkedMapOf<String, SearchResponse>()
        var hasNext = false

        for (path in paths) {
            val document = runCatching {
                app.get(
                    buildPageUrl(path, page),
                    headers = headers,
                    timeout = 25L
                ).document
            }.getOrNull() ?: continue

            parseCards(document).forEach { item ->
                if (!isBadTitle(item.name)) items[item.url] = item
            }

            hasNext = hasNext || hasNextPage(document, page)

            if (items.size >= 36 && request.data.startsWith("group:")) break
        }

        return newHomePageResponse(
            request.name,
            items.values.toList(),
            hasNext = hasNext
        )
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val clean = path.trim()

        return when {
            clean.startsWith("http", true) -> appendPage(clean, page)
            clean.isBlank() && page <= 1 -> mainUrl
            clean.isBlank() -> "$mainUrl/page/$page/"
            clean.startsWith("?") && page <= 1 -> "$mainUrl/$clean"
            clean.startsWith("?") -> "$mainUrl/page/$page/$clean"
            page <= 1 -> "$mainUrl/${clean.trim('/')}"
            else -> "$mainUrl/${clean.trim('/')}/page/$page/"
        }
    }

    private fun appendPage(url: String, page: Int): String {
        if (page <= 1) return url
        return when {
            url.contains("/page/", true) -> url
            url.contains("?") -> "$url&page=$page"
            else -> "${url.trimEnd('/')}/page/$page/"
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a.next, " +
                "a[rel=next], " +
                ".pagination a:contains(Next), " +
                ".nav-links a:contains(Next), " +
                ".page-numbers.next, " +
                "a[href*='/page/${page + 1}/'], " +
                "a[href*='page=${page + 1}']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a):has(img), " +
                ".post:has(a):has(img), " +
                ".item:has(a):has(img), " +
                ".result-item:has(a):has(img), " +
                ".listupd article:has(a), " +
                ".serieslist article:has(a), " +
                ".ml-item:has(a), " +
                ".bs:has(a), " +
                ".content article:has(a):has(img), " +
                ".film-list article:has(a), " +
                ".grid article:has(a)"
        ).forEach { element ->
            element.toSearchResult()?.let { item -> results[item.url] = item }
        }

        if (results.isEmpty()) {
            document.select("h2 a[href], h3 a[href], .entry-title a[href], .title a[href], a[href]:has(img)")
                .forEach { element ->
                    element.toSearchResult()?.let { item -> results[item.url] = item }
                }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                "h2 a[href], " +
                    "h3 a[href], " +
                    ".entry-title a[href], " +
                    ".title a[href], " +
                    "a[href]:has(img), " +
                    "a[href]"
            ) ?: return null
        }

        val href = normalizeUrl(
            anchor.attr("abs:href").ifBlank { anchor.attr("href") },
            mainUrl
        )

        if (href.isBlank() || isBlockedUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val title = listOf(
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst(".title")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text(),
            href.substringAfterLast("/").replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() && !isBadTitle(it)
        }?.cleanTitle() ?: return null

        if (title.length < 2 || isBadTitle(title)) return null

        val poster = fixUrlNull(image?.getImageAttr())
            ?.takeIf { !isBadImage(it) }

        val type = guessType(href, text(), title)

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.trim('/').lowercase() }
            .getOrDefault(url.substringAfter(mainUrl).trim('/').lowercase())

        if (path.isBlank()) return true

        val blockedExact = setOf(
            "drama-list",
            "dramaindo",
            "ongoing",
            "complete",
            "movie",
            "tv-show",
            "ost",
            "privacy",
            "dmca",
            "contact",
            "sitemap"
        )

        val blockedPrefixes = listOf(
            "series/genre/",
            "genre/",
            "country/",
            "series/country/",
            "tag/",
            "author/",
            "page/",
            "search",
            "wp-content/",
            "wp-json/",
            "wp-admin/",
            "feed"
        )

        return path in blockedExact || blockedPrefixes.any { path.startsWith(it) }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val attempts = listOf(
            if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded",
            if (page <= 1) "$mainUrl/search/$encoded/" else "$mainUrl/search/$encoded/page/$page/",
            if (page <= 1) "$mainUrl/?search=$encoded" else "$mainUrl/page/$page/?search=$encoded"
        )

        for (url in attempts) {
            val document = runCatching {
                app.get(url, headers = headers, timeout = 25L).document
            }.getOrNull() ?: continue

            val results = parseCards(document)
                .filterNot { isBadTitle(it.name) }
                .distinctBy { it.url }

            if (results.isNotEmpty()) {
                return newSearchResponseList(results, hasNext = hasNextPage(document, page))
            }
        }

        return newSearchResponseList(emptyList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = normalizeUrl(url, mainUrl)
        val document = app.get(
            pageUrl,
            headers = headers,
            referer = mainUrl,
            timeout = 25L
        ).document

        val title = listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("h1.entry-title, h1")?.text(),
            pageUrl.substringAfterLast("/").replace("-", " ")
        ).firstOrNull { !it.isNullOrBlank() && !isBadTitle(it) }
            ?.cleanTitle()
            ?: name

        val poster = getPoster(document)
        val text = document.text()
        val metadata = parseMetadata(document)

        val plot = document.selectFirst(
            ".entry-content p, " +
                ".sinopsis p, " +
                ".sinopsis, " +
                ".desc, " +
                ".description, " +
                ".summary, " +
                "article p"
        )?.text()?.trim()?.takeIf { it.length > 30 }

        val year = firstNumber(metadata["Year"] ?: metadata["Tahun"])
            ?: extractYear(title)
            ?: extractYear(text)

        val duration = parseDuration(metadata["Duration"] ?: text)
        val rating = metadata["Score"] ?: metadata["Rating"] ?: parseRating(text)

        val tags = (metadata["Genre"]?.split(",").orEmpty() +
            document.select("a[href*='/series/genre/'], a[href*='/genre/'], a[href*='/tag/']").map { it.text().trim() })
            .map { it.cleanTitle() }
            .filter { it.isNotBlank() && !isBadTitle(it) }
            .distinct()

        val actors = (metadata["Cast"]?.split(",").orEmpty() +
            document.select("a[href*='/cast/'], a[href*='/actor/'], a[href*='/stars/']").map { it.text().trim() })
            .map { it.cleanTitle() }
            .filter { it.isNotBlank() && !isBadTitle(it) }
            .distinct()
            .map { Actor(it) }

        val trailer = document.selectFirst("iframe[src*='youtube'], a[href*='youtube'], a[href*='youtu.be']")
            ?.let { it.attr("src").ifBlank { it.attr("href") } }
            ?.takeIf { it.isNotBlank() }

        val recommendations = document.select(
            ".related article:has(a), " +
                ".serieslist article:has(a), " +
                ".listupd article:has(a), " +
                "article:has(a):has(img)"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != pageUrl }

        val episodes = parseEpisodeLinks(document, pageUrl, poster)
        val typeText = metadata["Type"].orEmpty()
        val isMovie = typeText.contains("movie", true) ||
            title.contains("movie", true) ||
            tags.any { it.contains("movie", true) } ||
            (episodes.size <= 1 && !text.contains("Episode", true) && !text.contains("Eps", true))

        return if (isMovie) {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newTvSeriesLoadResponse(title, pageUrl, TvType.AsianDrama, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun parseMetadata(document: Document): Map<String, String> {
        val map = linkedMapOf<String, String>()

        document.select("li, p, span, div").forEach { element ->
            val text = element.text().trim().replace(Regex("""\s+"""), " ")
            val parts = text.split(":", limit = 2)

            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()

                if (
                    key.length in 2..35 &&
                    value.isNotBlank() &&
                    key.matches(Regex("""(?i)(title|original title|genre|cast|year|duration|type|episode|country|director|rating|score|tahun)"""))
                ) {
                    map[key] = value
                }
            }
        }

        return map
    }

    private fun parseEpisodeLinks(document: Document, currentUrl: String, poster: String?): List<Episode> {
        val links = linkedMapOf<String, Episode>()

        document.select(
            "a[href*='episode'], " +
                "a[href*='eps'], " +
                "a[href*='ep-'], " +
                "a[href*='download'], " +
                ".episodios a[href], " +
                ".episodes a[href], " +
                ".eplister a[href], " +
                ".les-content a[href], " +
                ".download a[href], " +
                ".entry-content a[href]"
        ).forEachIndexed { index, element ->
            val href = normalizeUrl(
                element.attr("abs:href").ifBlank { element.attr("href") },
                currentUrl
            )

            if (href.isBlank() || isBlockedUrl(href)) return@forEachIndexed

            val label = element.text().trim()
            if (label.contains("trailer", true) || href.contains("youtube", true)) return@forEachIndexed

            val epNumber = extractEpisodeNumber(label, href) ?: index + 1

            links[href] = newEpisode(href) {
                name = label.ifBlank { "Episode $epNumber" }.cleanTitle()
                episode = epNumber
                posterUrl = poster
            }
        }

        if (links.isEmpty()) {
            links[currentUrl] = newEpisode(currentUrl) {
                name = "Episode 1"
                episode = 1
                posterUrl = poster
            }
        }

        return links.values.sortedBy { it.episode ?: 1 }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = normalizeUrl(data, mainUrl)
        val response = app.get(
            pageUrl,
            headers = headers,
            referer = mainUrl,
            timeout = 25L
        )

        val document = response.document
        val html = response.text.cleanEscaped()
        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        collectDooplayAjaxLinks(document, pageUrl, directLinks, embedLinks)
        collectCandidatesFromDocument(document, pageUrl, directLinks, embedLinks)

        extractPlayableUrls(html).forEach { raw ->
            addCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach { raw ->
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        val decoded = runCatching { URLDecoder.decode(html, "UTF-8") }.getOrDefault(html)
        if (decoded != html) {
            extractPlayableUrls(decoded.cleanEscaped()).forEach { raw ->
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        var found = false

        for (link in directLinks.distinct().sortedWith(compareBy<String> { if (isHlsLike(it)) 0 else 1 }.thenBy { hostPriority(it) })) {
            emitDirectLink(link, pageUrl, callback)
            found = true
        }

        if (found) return true

        for (embed in prioritizeEmbeds(embedLinks).take(12)) {
            val success = loadExtractor(embed, pageUrl, subtitleCallback, callback)
            if (success) return true

            val nestedLinks = resolveNestedLinks(embed, pageUrl)
            for (nested in nestedLinks) {
                val fixed = normalizeUrl(nested, embed).replace(".txt", ".m3u8")

                when {
                    isAdUrl(fixed) || shouldSkipUrl(fixed) -> Unit
                    isHlsLike(fixed) || fixed.contains(".mp4", true) || fixed.contains(".webm", true) -> {
                        emitDirectLink(fixed, embed, callback)
                        return true
                    }
                    fixed.startsWith("http", true) -> {
                        val nestedSuccess = loadExtractor(fixed, embed, subtitleCallback, callback)
                        if (nestedSuccess) return true
                    }
                }
            }
        }

        return false
    }

    private suspend fun collectDooplayAjaxLinks(
        document: Document,
        pageUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val options = document.select(
            ".dooplay_player_option[data-post][data-nume][data-type], " +
                ".player-option[data-post][data-nume][data-type], " +
                "li[data-post][data-nume][data-type], " +
                "div[data-post][data-nume][data-type], " +
                "span[data-post][data-nume][data-type]"
        )

        if (options.isEmpty()) return

        val ajaxBase = getBaseUrl(pageUrl)
        val ajaxUrl = "$ajaxBase/wp-admin/admin-ajax.php"

        options.forEach { option ->
            val post = option.attr("data-post").trim()
            val nume = option.attr("data-nume").trim()
            val type = option.attr("data-type").trim()

            if (post.isBlank() || nume.isBlank() || type.isBlank()) return@forEach

            val ajaxText = runCatching {
                app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    headers = headers + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Origin" to ajaxBase
                    ),
                    referer = pageUrl,
                    timeout = 15L
                ).text.cleanEscaped()
            }.getOrNull().orEmpty()

            if (ajaxText.isBlank()) return@forEach

            val embed = runCatching {
                AppUtils.parseJson<DooplayAjaxResponse>(ajaxText).embedUrlFinal
            }.getOrNull()

            if (!embed.isNullOrBlank()) {
                parsePlayerPayload(embed, pageUrl, directLinks, embedLinks)
            }

            parsePlayerPayload(ajaxText, pageUrl, directLinks, embedLinks)
        }
    }

    private fun collectCandidatesFromDocument(
        document: Document,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        document.select(
            "meta[property=og:video], " +
                "meta[property=og:video:url], " +
                "meta[property=og:video:secure_url], " +
                "meta[itemprop=embedURL], " +
                "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src], " +
                "embed[src], " +
                "object[data], " +
                "source[src], " +
                "video[src], " +
                "video source[src], " +
                "a[href], " +
                "[data-src], " +
                "[data-file], " +
                "[data-video], " +
                "[data-url], " +
                "[data-embed], " +
                "[data-iframe]"
        ).forEach { element ->
            val raw = element.attr("content")
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            val label = element.text().lowercase()

            if (
                raw.isBlank() ||
                raw.startsWith("#") ||
                raw.startsWith("javascript", true) ||
                label.contains("trailer") ||
                raw.contains("youtube.com", true) ||
                raw.contains("youtu.be", true)
            ) return@forEach

            if (isLikelyPlayable(raw) || isLikelyDownloadText(label) || element.tagName().lowercase() in setOf("iframe", "embed", "object", "source", "video", "meta")) {
                addCandidate(raw, baseUrl, directLinks, embedLinks)
            }
        }
    }

    private fun parsePlayerPayload(
        text: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (text.isBlank()) return

        val clean = text.cleanEscaped()
        val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val htmlDecoded = decoded
            .replace("\\\"", "\"")
            .replace("\\/", "/")

        listOf(clean, decoded, htmlDecoded).distinct().forEach { payload ->
            extractPlayableUrls(payload).forEach { raw ->
                addCandidate(raw, baseUrl, directLinks, embedLinks)
            }

            Jsoup.parse(payload).select(
                "iframe[src], iframe[data-src], video[src], source[src], embed[src], object[data], a[href], [data-src], [data-file], [data-video], [data-url]"
            ).forEach { element ->
                val raw = element.attr("data-file")
                    .ifBlank { element.attr("data-video") }
                    .ifBlank { element.attr("data-url") }
                    .ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("data") }
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("href") }
                addCandidate(raw, baseUrl, directLinks, embedLinks)
            }
        }
    }

    private suspend fun resolveNestedLinks(url: String, referer: String): List<String> {
        if (shouldSkipUrl(url)) return emptyList()

        val response = runCatching {
            app.get(
                url,
                headers = headers + mapOf("Origin" to getBaseUrl(referer)),
                referer = referer,
                timeout = 18L
            )
        }.getOrNull() ?: return emptyList()

        val text = response.text.cleanEscaped()
        val results = linkedSetOf<String>()

        collectCandidatesFromDocument(response.document, url, results, results)
        results.addAll(extractPlayableUrls(text))

        val unpacked = runCatching {
            if (!getPacked(text).isNullOrEmpty()) getAndUnpack(text) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            results.addAll(extractPlayableUrls(unpacked.cleanEscaped()))
        }

        val decoded = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
        if (decoded != text) results.addAll(extractPlayableUrls(decoded.cleanEscaped()))

        return results
            .map { normalizeUrl(it, url).replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .distinct()
    }

    private fun addCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (raw.isBlank()) return

        val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl)
            .replace(".txt", ".m3u8")
            .trim()

        if (fixed.isBlank() || isAdUrl(fixed) || shouldSkipUrl(fixed)) return

        when {
            isHlsLike(fixed) || fixed.contains(".mp4", true) || fixed.contains(".webm", true) -> directLinks.add(fixed)
            fixed.contains("drive.google.com", true) && fixed.contains("/file/d/", true) -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && isKnownHost(fixed) -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && (fixed.contains("embed", true) || fixed.contains("player", true) || fixed.contains("stream", true)) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(link: String, referer: String, callback: (ExtractorLink) -> Unit) {
        if (isAdUrl(link) || shouldSkipUrl(link)) return

        if (isHlsLike(link)) {
            generateM3u8(
                source = name,
                streamUrl = link,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to getBaseUrl(referer)
                )
            ).forEach(callback)
            return
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(link).takeIf {
                    it != Qualities.Unknown.value
                } ?: qualityFromUrl(link)
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to getBaseUrl(referer)
                )
            }
        )
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt|drive|gdrive|hxfile|terabox|filemoon|streamwish|dood|streamtape|vidhide|voe|mixdrop|mp4upload)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoSource|videoUrl|video_url|embedUrl|embed_url|contentUrl|stream|streamUrl|stream_url|download_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains(".webm", true) || isKnownHost(it) }
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:gd|gdrive|drive|hxfile|terabox|dood|streamtape|filemoon|vidhide|vidguard|mp4upload|okru|odnoklassniki|sendvid|streamwish|wishfast|voe|filelions|lulustream|mixdrop|krakenfiles|acefile|mega)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun prioritizeEmbeds(links: Collection<String>): List<String> {
        return links
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .distinct()
            .sortedWith(compareBy<String> { hostPriority(it) }.thenBy { it.length })
    }

    private fun hostPriority(url: String): Int {
        val value = url.lowercase()
        return when {
            value.contains("drive.google") || value.contains("gdrive") -> 0
            value.contains("terabox") -> 1
            value.contains("hxfile") -> 2
            value.contains("filemoon") -> 3
            value.contains("streamwish") || value.contains("wishfast") -> 4
            value.contains("dood") -> 5
            value.contains("streamtape") -> 6
            value.contains("vidhide") || value.contains("vidguard") -> 7
            value.contains("voe") -> 8
            value.contains("mixdrop") -> 9
            value.contains("mp4upload") -> 10
            value.contains("ok.ru") || value.contains("odnoklassniki") -> 11
            value.contains("embed") -> 20
            value.contains("player") -> 21
            value.contains("stream") -> 22
            else -> 50
        }
    }

    private fun isKnownHost(url: String): Boolean {
        val value = url.lowercase()
        return listOf(
            "drive.google",
            "googleusercontent",
            "gd",
            "gdrive",
            "hxfile",
            "terabox",
            "dood",
            "streamtape",
            "filemoon",
            "vidhide",
            "vidguard",
            "mp4upload",
            "ok.ru",
            "odnoklassniki",
            "sendvid",
            "streamwish",
            "wishfast",
            "voe",
            "filelions",
            "lulustream",
            "mixdrop",
            "krakenfiles",
            "acefile",
            "mega.nz"
        ).any { value.contains(it) }
    }

    private fun isLikelyPlayable(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true) ||
            isKnownHost(url)
    }

    private fun isLikelyDownloadText(text: String): Boolean {
        return text.contains("download") ||
            text.contains("stream") ||
            text.contains("nonton") ||
            text.contains("server") ||
            text.contains("play") ||
            text.contains("360p") ||
            text.contains("480p") ||
            text.contains("540p") ||
            text.contains("720p") ||
            text.contains("1080p") ||
            text.contains("gd") ||
            text.contains("gdrive") ||
            text.contains("terabox") ||
            text.contains("hxfile")
    }

    private fun shouldSkipUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("mailto:") ||
            value.contains("youtube.com") ||
            value.contains("youtu.be") ||
            value.contains("trailer") ||
            value.contains("/ads/") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("googletagmanager") ||
            value.contains("recaptcha") ||
            value.contains("cloudflareinsights")
    }

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped().trim()
        return when {
            clean.isBlank() -> ""
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "${getBaseUrl(baseUrl)}$clean"
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }

    private fun getPoster(document: Document): String? {
        return fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "meta[name=twitter:image], " +
                    ".poster img, " +
                    ".thumb img, " +
                    ".post-thumbnail img, " +
                    "article img, " +
                    "img.wp-post-image, " +
                    "img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    else -> element.getImageAttr()
                }
            }
        )?.takeIf { !isBadImage(it) }
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? {
            if (value.isNullOrBlank()) return null
            return value.split(",")
                .map { it.trim().substringBefore(" ") }
                .lastOrNull { it.isNotBlank() && !isBadImage(it) }
        }

        val raw = fromSrcSet(attr("data-srcset"))
            ?: fromSrcSet(attr("data-lazy-srcset"))
            ?: fromSrcSet(attr("srcset"))
            ?: attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:data-full").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }

        return raw?.trim()?.takeIf { !isBadImage(it) }
    }

    private fun isBadImage(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() ||
            value.startsWith("data:image") ||
            value.contains("blank") ||
            value.contains("placeholder") ||
            value.contains("default") ||
            value.contains("no-image") ||
            value.contains("noimage") ||
            value.contains("loader") ||
            value.contains("loading") ||
            value.contains("lazy") ||
            value.contains("spacer") ||
            value.contains("logo") ||
            value.contains("favicon") ||
            value.endsWith(".svg")
    }

    private fun guessType(url: String, text: String, title: String): TvType {
        val value = "$url $text $title"
        return when {
            value.contains("movie", true) -> TvType.Movie
            value.contains("film", true) -> TvType.Movie
            value.contains("variety", true) -> TvType.TvSeries
            value.contains("tv show", true) -> TvType.TvSeries
            else -> TvType.AsianDrama
        }
    }

    private fun firstNumber(text: String?): Int? {
        return Regex("""\b(19|20)\d{2}\b""").find(text.orEmpty())?.value?.toIntOrNull()
    }

    private fun extractYear(text: String?): Int? = firstNumber(text)

    private fun parseDuration(text: String?): Int? {
        return Regex("""(\d+)\s*(?:min|menit)""", RegexOption.IGNORE_CASE)
            .find(text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseRating(text: String): String? {
        return Regex("""\b([0-9](?:\.[0-9])?|10(?:\.0)?)\b""")
            .findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull()
    }

    private fun extractEpisodeNumber(text: String, href: String): Int? {
        return Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find("$text $href")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true)
    }

    private fun isAdUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("vast") ||
            value.contains("preroll") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("popads") ||
            value.contains("adskeeper") ||
            value.contains("adsterra") ||
            value.contains("/ads/") ||
            value.contains("banner") ||
            value.contains("tracking") ||
            value.contains("analytics")
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("540", true) -> Qualities.P480.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun isBadTitle(text: String): Boolean {
        val value = text.cleanTitle().lowercase()
        return value.isBlank() ||
            value == "home" ||
            value == "download" ||
            value == "trailer" ||
            value == "drama list" ||
            value == "genre" ||
            value == "country" ||
            value == "search" ||
            value == "login" ||
            value == "register" ||
            value.contains("privacy") ||
            value.contains("dmca") ||
            value.contains("advertise")
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u003F", "?")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\\"", "\"")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+Dramaindo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+IndoDrama.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+NontonDrama.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s*Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', ':')
            .trim()
    }
}

data class DooplayAjaxResponse(
    val embed_url: String? = null,
    val embedUrl: String? = null,
    val url: String? = null
) {
    val embedUrlFinal: String?
        get() = embed_url ?: embedUrl ?: url
}
