package com.sad25kag.Cineby

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Cineby : MainAPI() {
    override var mainUrl = "https://www.cineby.sc"
    override var name = "Cineby"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "" to "Home",

        "?type=movie" to "Movies",
        "?type=tv" to "Series",
        "?sort=trending" to "Trending Today",
        "?sort=top" to "TOP 10 Today",
        "?sort=top-rated" to "Top Rated",
        "?platform=netflix" to "Only on Netflix",

        "?type=movie&sort=trending" to "Trending Movies",
        "?type=tv&sort=trending" to "Trending Series",
        "?type=movie&sort=top-rated" to "Top Rated Movies",
        "?type=tv&sort=top-rated" to "Top Rated Series",

        "?genre=Action" to "Action",
        "?genre=Adventure" to "Adventure",
        "?genre=Animation" to "Animation",
        "?genre=Comedy" to "Comedy",
        "?genre=Crime" to "Crime",
        "?genre=Documentary" to "Documentary",
        "?genre=Drama" to "Drama",
        "?genre=Family" to "Family",
        "?genre=Fantasy" to "Fantasy",
        "?genre=History" to "History",
        "?genre=Horror" to "Horror",
        "?genre=Music" to "Music",
        "?genre=Mystery" to "Mystery",
        "?genre=Romance" to "Romance",
        "?genre=Science%20Fiction" to "Science Fiction",
        "?genre=Sci-Fi%20%26%20Fantasy" to "Sci-Fi & Fantasy",
        "?genre=Thriller" to "Thriller",
        "?genre=War" to "War",
        "?genre=Western" to "Western",

        "?type=movie&genre=Action" to "Action Movies",
        "?type=movie&genre=Comedy" to "Comedy Movies",
        "?type=movie&genre=Horror" to "Horror Movies",
        "?type=movie&genre=Science%20Fiction" to "Sci-Fi Movies",
        "?type=movie&genre=Animation" to "Animation Movies",

        "?type=tv&genre=Drama" to "Drama Series",
        "?type=tv&genre=Comedy" to "Comedy Series",
        "?type=tv&genre=Mystery" to "Mystery Series",
        "?type=tv&genre=Sci-Fi%20%26%20Fantasy" to "Sci-Fi Series",
        "?type=tv&genre=Action%20%26%20Adventure" to "Action & Adventure Series"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
        "Referer" to mainUrl
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = buildPageUrl(request.data, page)

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        if (request.data.isBlank()) {
            val rows = parseHomeRows(document)

            if (rows.isNotEmpty()) {
                return newHomePageResponse(
                    rows,
                    hasNext = false
                )
            }
        }

        val filter = getFilterKeyword(request.data)
        var items = parseCards(document).distinctBy { it.url }

        if (!filter.isNullOrBlank()) {
            val filtered = items.filter {
                it.name.contains(filter, ignoreCase = true)
            }

            if (filtered.isNotEmpty()) {
                items = filtered
            }
        }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNextPage(document, page)
        )
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val clean = path.trim().trim('/')

        return when {
            page <= 1 && clean.isBlank() -> mainUrl
            page <= 1 && clean.startsWith("?") -> "$mainUrl/$clean"
            page <= 1 -> "$mainUrl/$clean"

            clean.isBlank() -> "$mainUrl/?page=$page"

            clean.startsWith("?") -> {
                val query = clean.removePrefix("?")
                "$mainUrl/?page=$page&$query"
            }

            clean.contains("?") -> {
                val base = clean.substringBefore("?").trim('/')
                val query = clean.substringAfter("?")
                "$mainUrl/$base?page=$page&$query"
            }

            else -> "$mainUrl/$clean?page=$page"
        }
    }

    private fun hasNextPage(
        document: Document,
        page: Int
    ): Boolean {
        return document.selectFirst(
            "a[rel=next], " +
                "a.next, " +
                ".pagination a:contains(Next), " +
                "a[href*='page=${page + 1}'], " +
                "button:contains(${page + 1})"
        ) != null
    }

    private fun getFilterKeyword(path: String): String? {
        val raw = when {
            path.contains("genre=", true) -> path.substringAfter("genre=").substringBefore("&")
            path.contains("platform=", true) -> path.substringAfter("platform=").substringBefore("&")
            else -> return null
        }

        return runCatching {
            URLDecoder.decode(raw, "UTF-8")
        }.getOrDefault(raw)
            .replace("+", " ")
            .replace("%26", "&")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun parseHomeRows(document: Document): List<HomePageList> {
        val rows = mutableListOf<HomePageList>()

        document.select("h1, h2, h3").forEach { heading ->
            val title = heading.text().cleanTitle()

            if (
                title.isBlank() ||
                title.equals("Cineby", true) ||
                title.equals("Movies", true) ||
                title.equals("Series", true) ||
                title.equals("See More", true)
            ) {
                return@forEach
            }

            val items = linkedMapOf<String, SearchResponse>()
            var sibling = heading.nextElementSibling()
            var guard = 0

            while (sibling != null && guard < 12) {
                if (sibling.`is`("h1, h2, h3")) break

                sibling.parseCardsFromElement().forEach {
                    items[it.url] = it
                }

                sibling = sibling.nextElementSibling()
                guard++
            }

            if (items.isEmpty()) {
                heading.parent()?.parseCardsFromElement()?.forEach {
                    items[it.url] = it
                }
            }

            if (items.isNotEmpty()) {
                rows.add(
                    HomePageList(
                        title,
                        items.values.toList().take(24),
                        isHorizontalImages = false
                    )
                )
            }
        }

        if (rows.isEmpty()) {
            val fallback = parseCards(document)

            if (fallback.isNotEmpty()) {
                rows.add(
                    HomePageList(
                        "Cineby",
                        fallback.take(30),
                        isHorizontalImages = false
                    )
                )
            }
        }

        return rows.distinctBy { it.name }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a), " +
                "li:has(a), " +
                ".swiper-slide:has(a), " +
                ".card:has(a), " +
                ".movie:has(a), " +
                ".item:has(a), " +
                ".result:has(a), " +
                ".grid div:has(a), " +
                ".list div:has(a), " +
                "main div:has(a[href*='/movie/']), " +
                "main div:has(a[href*='/tv/']), " +
                "main a[href*='/movie/'], " +
                "main a[href*='/tv/']"
        ).forEach { element ->
            element.toSearchResult()?.let {
                results[it.url] = it
            }
        }

        if (results.isEmpty()) {
            document.select(
                "a[href*='/movie/'], " +
                    "a[href*='/tv/'], " +
                    "a[href]:has(img)"
            ).forEach { element ->
                element.toSearchResult()?.let {
                    results[it.url] = it
                }
            }
        }

        return results.values.toList()
    }

    private fun Element.parseCardsFromElement(): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        select(
            "article:has(a), " +
                "li:has(a), " +
                ".swiper-slide:has(a), " +
                ".card:has(a), " +
                ".movie:has(a), " +
                ".item:has(a), " +
                ".result:has(a), " +
                "div:has(a[href*='/movie/']), " +
                "div:has(a[href*='/tv/']), " +
                "a[href*='/movie/'], " +
                "a[href*='/tv/']"
        ).forEach { element ->
            element.toSearchResult()?.let {
                results[it.url] = it
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                "a[href*='/movie/'], " +
                    "a[href*='/tv/'], " +
                    "a[href]:has(img), " +
                    "a[href]"
            ) ?: return null
        }

        val href = normalizeUrl(anchor.attr("href"), mainUrl)

        if (href.isBlank()) return null
        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val title = listOf(
            selectFirst("h1")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".title")?.text(),
            selectFirst("[class*=title]")?.text(),
            selectFirst(".name")?.text(),
            selectFirst("[class*=name]")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text()
        ).firstOrNull {
            !it.isNullOrBlank() && !isBadTitle(it)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val poster = fixUrlNull(image?.getImageAttr())
            ?.takeIf { !isBadImage(it) }

        val score = Regex("""(?<!\d)(\d(?:\.\d)?|10(?:\.0)?)(?!\d)""")
            .find(text())
            ?.groupValues
            ?.getOrNull(1)

        val type = if (href.contains("/tv/", true)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return newMovieSearchResponse(
            title,
            href,
            type
        ) {
            posterUrl = poster
            posterHeaders = headers
            this.score = Score.from10(score)
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()

        if (path.isBlank()) return true

        val allowed = listOf(
            "movie/",
            "tv/"
        )

        if (allowed.any { path.startsWith(it) || path.contains("/$it") }) {
            return false
        }

        val blockedPrefixes = listOf(
            "search",
            "genre",
            "category",
            "person",
            "actor",
            "actors",
            "privacy",
            "terms",
            "contact",
            "dmca",
            "login",
            "register",
            "assets",
            "_next",
            "_nuxt",
            "favicon",
            "api"
        )

        return blockedPrefixes.any {
            path == it.trimEnd('/') || path.startsWith(it)
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val keyword = query.trim()

        if (keyword.isBlank()) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }

        val encoded = URLEncoder.encode(keyword, "UTF-8")

        val attempts = listOf(
            if (page <= 1) "$mainUrl/search?q=$encoded" else "$mainUrl/search?q=$encoded&page=$page",
            if (page <= 1) "$mainUrl/?search=$encoded" else "$mainUrl/?search=$encoded&page=$page",
            if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/?s=$encoded&page=$page",
            if (page <= 1) "$mainUrl/?keyword=$encoded" else "$mainUrl/?keyword=$encoded&page=$page",
            if (page <= 1) mainUrl else "$mainUrl/?page=$page"
        )

        var bestResults: List<SearchResponse> = emptyList()
        var hasNext = false

        for (url in attempts) {
            val document = runCatching {
                app.get(
                    url,
                    headers = headers,
                    timeout = 30L
                ).document
            }.getOrNull() ?: continue

            val results = parseCards(document)
                .filter { it.name.contains(keyword, ignoreCase = true) }
                .distinctBy { it.url }

            if (results.isNotEmpty()) {
                bestResults = results
                hasNext = hasNextPage(document, page)
                break
            }
        }

        return newSearchResponseList(
            bestResults,
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeUrl(url, mainUrl)

        val document = app.get(
            fixedUrl,
            headers = headers,
            timeout = 30L
        ).document

        val title = getTitle(document, fixedUrl)
        val poster = getPoster(document)
        val plot = getPlot(document)
        val tags = getTags(document)
        val rating = getRating(document)
        val actors = getActors(document)

        val recommendations = parseCards(document)
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }
            .take(18)

        return if (fixedUrl.contains("/tv/", true)) {
            val episodes = parseEpisodes(document, fixedUrl)

            newTvSeriesLoadResponse(
                title,
                fixedUrl,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                posterHeaders = headers
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(
                title,
                fixedUrl,
                TvType.Movie,
                fixedUrl
            ) {
                posterUrl = poster
                posterHeaders = headers
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }

    private fun getTitle(
        document: Document,
        url: String
    ): String {
        val h1 = document.selectFirst("h1")
            ?.text()
            ?.cleanTitle()
            .orEmpty()

        val metaTitle = document.selectFirst("meta[property=og:title], meta[name=twitter:title]")
            ?.attr("content")
            ?.cleanTitle()
            .orEmpty()

        return h1
            .ifBlank { metaTitle }
            .ifBlank {
                url.substringAfterLast("/")
                    .substringBefore("?")
                    .replace("-", " ")
                    .cleanTitle()
                    .replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                    }
            }
            .ifBlank { name }
    }

    private fun getPlot(document: Document): String? {
        val selectors = listOf(
            "meta[name=description]",
            "meta[property=og:description]",
            ".overview",
            ".desc",
            ".description",
            "[class*=overview]",
            "[class*=desc]",
            "[class*=summary]",
            "p"
        )

        selectors.forEach { selector ->
            document.select(selector).forEach { element ->
                val text = element.attr("content")
                    .ifBlank { element.text() }
                    .cleanTitle()

                if (
                    text.length > 40 &&
                    !text.equals("Cineby", true) &&
                    !text.contains("does not store", true)
                ) {
                    return text
                }
            }
        }

        return null
    }

    private fun getTags(document: Document): List<String> {
        return document.select(
            "a[href*='genre'], " +
                "a[href*='category'], " +
                "[class*=genre], " +
                "[class*=tag]"
        ).map { it.text().cleanTitle() }
            .filter {
                it.isNotBlank() &&
                    !isBadTitle(it) &&
                    it.length <= 30
            }
            .distinct()
            .take(20)
    }

    private fun getRating(document: Document): String? {
        return Regex("""(?<!\d)(\d(?:\.\d)?|10(?:\.0)?)(?!\d)""")
            .find(document.text())
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun getActors(document: Document): List<Actor> {
        return document.select(
            "[class*=actor] a, " +
                "section:contains(Actors) a, " +
                "h2:contains(Actors) ~ * a, " +
                "h3:contains(Actors) ~ * a"
        ).mapNotNull { element ->
            val actorName = element.text().cleanTitle()
            if (actorName.isBlank() || isBadTitle(actorName)) return@mapNotNull null

            Actor(actorName)
        }.distinctBy { it.name }
            .take(20)
    }

    private fun parseEpisodes(
        document: Document,
        currentUrl: String
    ): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        val currentSeason = currentUrl.substringAfter("/tv/", "")
            .split("/")
            .getOrNull(1)
            ?.substringBefore("?")
            ?.toIntOrNull()
            ?: 1

        val currentEpisode = currentUrl.substringAfter("/tv/", "")
            .split("/")
            .getOrNull(2)
            ?.substringBefore("?")
            ?.toIntOrNull()
            ?: 1

        episodes[currentUrl] = newEpisode(currentUrl) {
            name = "Episode $currentEpisode"
            season = currentSeason
            episode = currentEpisode
        }

        document.select("a[href*='/tv/']").forEachIndexed { index, element ->
            val href = normalizeUrl(element.attr("href"), currentUrl)

            if (href.isBlank() || !href.startsWith(mainUrl) || isBlockedUrl(href)) {
                return@forEachIndexed
            }

            val parts = href.substringAfter("/tv/", "")
                .substringBefore("?")
                .split("/")

            val seasonNumber = parts.getOrNull(1)?.toIntOrNull() ?: currentSeason
            val episodeNumber = parts.getOrNull(2)?.toIntOrNull()
                ?: parseEpisodeNumber(element.text())
                ?: index + 1

            val episodeName = element.text()
                .cleanTitle()
                .takeIf {
                    it.isNotBlank() &&
                        !it.equals("Watching", true) &&
                        !it.equals("See More", true)
                } ?: "Episode $episodeNumber"

            episodes[href] = newEpisode(
                if (href.contains("play=true", true)) href else "$href?play=true"
            ) {
                name = episodeName
                season = seasonNumber
                episode = episodeNumber
            }
        }

        return episodes.values
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
                    .thenBy { it.name }
            )
    }

    private fun parseEpisodeNumber(text: String): Int? {
        return Regex("""(?i)(?:episode|ep)?\s*(\d{1,4})""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
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
            timeout = 30L
        )

        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        document.select(
            "video[src], " +
                "video source[src], " +
                "source[src], " +
                "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src], " +
                "embed[src], " +
                "a[href]"
        ).forEach { element ->
            val href = element.attr("href")
            val raw = element.attr("data-litespeed-src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("src") }
                .ifBlank { href }
                .trim()

            if (raw.isBlank()) return@forEach

            if (
                raw.startsWith("#") ||
                raw.startsWith("javascript", true) ||
                raw.contains("mailto:", true) ||
                raw.contains("facebook.com", true) ||
                raw.contains("twitter.com", true) ||
                raw.contains("telegram", true) ||
                raw.contains("whatsapp", true)
            ) {
                return@forEach
            }

            if (
                element.tagName().equals("video", true) ||
                element.tagName().equals("source", true) ||
                element.tagName().equals("iframe", true) ||
                isLikelyPlayable(raw)
            ) {
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        extractPlayableUrls(html).forEach { raw ->
            addCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        if (directLinks.isEmpty() && embedLinks.isEmpty()) {
            collectScriptTexts(document).forEach { scriptText ->
                extractPlayableUrls(scriptText).forEach { raw ->
                    addCandidate(raw, pageUrl, directLinks, embedLinks)
                }
            }
        }

        var found = false

        directLinks.distinct().forEach { link ->
            emitDirectLink(
                link = link,
                referer = pageUrl,
                callback = callback
            )
            found = true
        }

        embedLinks.distinct().forEach { embed ->
            val success = loadExtractor(
                embed,
                pageUrl,
                subtitleCallback,
                callback
            )

            if (success) {
                found = true
            } else {
                resolveNestedLinks(embed, pageUrl).forEach { nested ->
                    val fixed = normalizeUrl(nested, embed)
                        .cleanEscaped()
                        .replace(".txt", ".m3u8")

                    when {
                        isAdUrl(fixed) -> Unit

                        isHlsLike(fixed) ||
                            fixed.contains(".mp4", true) ||
                            fixed.contains(".webm", true) -> {
                            emitDirectLink(
                                link = fixed,
                                referer = embed,
                                callback = callback
                            )
                            found = true
                        }

                        fixed.startsWith("http", true) -> {
                            val nestedSuccess = loadExtractor(
                                fixed,
                                embed,
                                subtitleCallback,
                                callback
                            )

                            if (nestedSuccess) found = true
                        }
                    }
                }
            }
        }

        return found
    }

    private suspend fun collectScriptTexts(document: Document): List<String> {
        val scriptUrls = document.select("script[src]")
            .mapNotNull {
                normalizeUrl(it.attr("src"), mainUrl).takeIf { url ->
                    url.isNotBlank() &&
                        url.startsWith("http", true) &&
                        (
                            url.contains("/_next/", true) ||
                                url.contains("/_nuxt/", true) ||
                                url.contains(".js", true)
                            )
                }
            }
            .distinct()
            .take(12)

        return scriptUrls.mapNotNull { scriptUrl ->
            runCatching {
                app.get(
                    scriptUrl,
                    headers = headers,
                    referer = mainUrl,
                    timeout = 30L
                ).text.cleanEscaped()
            }.getOrNull()
        }
    }

    private suspend fun resolveNestedLinks(
        url: String,
        referer: String
    ): List<String> {
        val text = runCatching {
            app.get(
                url,
                headers = headers,
                referer = referer,
                timeout = 30L
            ).text.cleanEscaped()
        }.getOrNull().orEmpty()

        if (text.isBlank()) return emptyList()

        return extractPlayableUrls(text)
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

        if (fixed.isBlank() || isAdUrl(fixed)) return

        when {
            isHlsLike(fixed) ||
                fixed.contains(".mp4", true) ||
                fixed.contains(".webm", true) -> directLinks.add(fixed)

            fixed.startsWith("http", true) &&
                isKnownHost(fixed) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("player", true) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("embed", true) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isAdUrl(link)) return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isHlsLike(link)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                this.referer = referer
                this.quality = getQualityFromName(link).takeIf {
                    it != Qualities.Unknown.value
                } ?: qualityFromUrl(link)
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to mainUrl,
                    "Accept" to "*/*"
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
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map {
                runCatching {
                    URLDecoder.decode(it.value, "UTF-8")
                }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|video|videoSource|videoUrl|video_url|playUrl|play_url|hls|hlsUrl|hls_url|embedUrl|embed_url|contentUrl)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains(".webm", true) ||
                    isKnownHost(it)
            }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:embed|player|stream|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|lulustream|lulu|hglink|hgcloud|majorplay|jeniusplay|vidsrc|vidsrcme|vidsrc.to|vidsrc.xyz|vidlink|multiembed|2embed|superembed)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun isKnownHost(url: String): Boolean {
        val value = url.lowercase()

        return listOf(
            "embed",
            "player",
            "stream",
            "filemoon",
            "streamwish",
            "wishfast",
            "dood",
            "streamtape",
            "vidhide",
            "vidguard",
            "voe",
            "mixdrop",
            "mp4upload",
            "lulustream",
            "lulu",
            "hglink",
            "hgcloud",
            "majorplay",
            "jeniusplay",
            "vidsrc",
            "vidlink",
            "multiembed",
            "2embed",
            "superembed"
        ).any { value.contains(it) }
    }

    private fun isLikelyPlayable(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true) ||
            url.contains(".txt", true) ||
            isKnownHost(url)
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            (
                url.contains("majorplay", true) &&
                    url.contains("config", true) &&
                    url.contains(".json", true)
                )
    }

    private fun getPoster(document: Document): String? {
        return fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "meta[name=twitter:image], " +
                    "video[poster], " +
                    ".poster img, " +
                    ".thumb img, " +
                    ".cover img, " +
                    "[class*=poster] img, " +
                    "[class*=cover] img, " +
                    "article img, " +
                    "main img, " +
                    "img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    element.hasAttr("poster") -> element.attr("poster")
                    else -> element.getImageAttr()
                }
            }
        )?.takeIf { !isBadImage(it) }
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? {
            if (value.isNullOrBlank()) return null

            return value
                .split(",")
                .map { it.trim().substringBefore(" ") }
                .lastOrNull {
                    it.isNotBlank() &&
                        !isBadImage(it)
                }
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
            ?: attr("data-original").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }

        return raw
            ?.trim()
            ?.takeIf { !isBadImage(it) }
    }

    private fun normalizeUrl(
        url: String,
        baseUrl: String
    ): String {
        val clean = url.cleanEscaped().trim()

        return when {
            clean.isBlank() -> ""
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"

            clean.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""")
                    .find(baseUrl)
                    ?.value
                    ?: mainUrl

                "$origin$clean"
            }

            else -> runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrDefault("$mainUrl/${clean.trimStart('/')}")
        }
    }

    private fun isBadTitle(text: String): Boolean {
        val value = text.cleanTitle().lowercase()

        return value.isBlank() ||
            value == "cineby" ||
            value == "home" ||
            value == "movies" ||
            value == "series" ||
            value == "tv show" ||
            value == "movie" ||
            value == "see more" ||
            value == "watch now" ||
            value == "play" ||
            value == "play now" ||
            value == "next" ||
            value == "previous" ||
            value == "prev" ||
            value == "share" ||
            value == "login" ||
            value == "register"
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
            value.contains("banner") ||
            value.endsWith(".svg")
    }

    private fun isAdUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains("vast") ||
            value.contains("preroll") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("/ads/") ||
            value.contains("advert") ||
            value.contains("banner") ||
            value.contains("tracking") ||
            value.contains("analytics") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp")
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

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u003F", "?")
            .replace("\\u002D", "-")
            .replace("\\u005C", "\\")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\\"", "\"")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+"""), " ")
            .replace("See More", "", ignoreCase = true)
            .replace("Watch Now", "", ignoreCase = true)
            .replace("Play Now", "", ignoreCase = true)
            .replace("Cineby", "", ignoreCase = true)
            .trim(' ', '-', '|', ':', '·')
            .trim()
    }
}