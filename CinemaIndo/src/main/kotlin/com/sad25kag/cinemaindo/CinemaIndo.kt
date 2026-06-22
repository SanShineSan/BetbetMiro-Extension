package com.sad25kag.cinemaindo

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
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
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

class CinemaIndo : MainAPI() {
    override var mainUrl = "https://tv.cinemaindo.pw"
    override var name = "CinemaIndo"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
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
        "Cache-Control" to "no-cache"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/latest" to "Film Terbaru",
        "$mainUrl/series/top-series-today" to "Top Series Today",
        "$mainUrl/series/latest" to "Series Terbaru",
        "$mainUrl/series/complete" to "Series Complete",
        "$mainUrl/genre/family" to "Family",
        "$mainUrl/genre/action" to "Action",
        "$mainUrl/genre/horror" to "Horror",
        "$mainUrl/genre/romance" to "Romance",
        "$mainUrl/genre/comedy" to "Comedy",
        "$mainUrl/country/south-korea" to "South Korea",
        "$mainUrl/country/thailand" to "Thailand",
        "$mainUrl/country/india" to "India",
        "$mainUrl/release" to "Release"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = normalizeUrl(request.data, mainUrl)
        val document = runCatching {
            app.get(url, headers = headers + mapOf("Referer" to "$mainUrl/"), referer = mainUrl).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        fetchCategoryApi(document, url, page)?.let { apiPage ->
            if (apiPage.items.isNotEmpty()) {
                return newHomePageResponse(request.name, apiPage.items, hasNext = apiPage.hasNext)
            }
        }

        val fallbackUrl = buildPagedUrl(request.data, page)
        val fallbackDocument = if (fallbackUrl == url) {
            document
        } else {
            runCatching {
                app.get(fallbackUrl, headers = headers + mapOf("Referer" to "$mainUrl/"), referer = mainUrl).document
            }.getOrNull() ?: document
        }

        val results = parseCards(fallbackDocument, fallbackUrl).distinctBy { it.url }
        return newHomePageResponse(request.name, results, hasNext = hasNextPage(fallbackDocument, page))
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
        for (url in urls) {
            val document = runCatching {
                app.get(url, headers = headers + mapOf("Referer" to "$mainUrl/"), referer = mainUrl).document
            }.getOrNull() ?: continue

            parseCards(document, url).forEach { item -> results[item.url] = item }
            if (results.isNotEmpty()) break
        }
        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val page = normalizeUrl(url, mainUrl).takeIf { it.startsWith("http", true) } ?: return null
        val response = runCatching {
            app.get(page, headers = headers + mapOf("Referer" to "$mainUrl/"), referer = mainUrl)
        }.getOrNull() ?: return null

        val document = response.document
        val html = response.text.ifBlank { document.html() }
        val title = document.selectFirst("h1.entry-title, h1[itemprop=name], .single-title h1, .post-title h1, h1")
            ?.text()
            ?.cleanTitle()
            ?.ifBlank { null }
            ?: titleFromUrl(page)
        if (title.isBlank()) return null

        val poster = findPoster(document)
        val tags = document.select("a[href*='/genre/'], a[rel=tag]")
            .map { it.text().trim() }
            .filter { it.length in 2..40 }
            .distinct()
            .take(20)

        val actors = document.select("a[href*='/cast/'], a[href*='/actor/'], a[href*='/director/']")
            .map { it.text().trim() }
            .filter { it.length in 2..60 }
            .distinct()
            .take(24)

        val description = document.selectFirst(
            "meta[property=og:description], meta[name=description], " +
                ".entry-content p, .post-content p, .description, .desc, .sinopsis, [itemprop=description]"
        )?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
            ?.cleanText()
            ?.takeIf { it.length > 10 }

        val year = document.selectFirst("a[href*='/year/']")?.text()
            ?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""\b(19|20)\d{2}\b""").find(document.text())?.value?.toIntOrNull()

        val rating = document.selectFirst("[itemprop=ratingValue], .rating, .score, .imdb, .vote")
            ?.text()
            ?.replace(",", ".")
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value }

        val duration = Regex("""(?i)(\d{1,3})\s*(?:min|menit|m)\b""")
            .find(document.text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val trailer = document.selectFirst("a[href*='youtube.com'], a[href*='youtu.be']")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }

        val episodes = parseEpisodes(document, page)
        val type = inferType(page, document.text(), episodes)
        val recommendations = parseRecommendations(document, page)

        return if (type == TvType.TvSeries && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, page, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addScore(rating)
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val watchLink = findMovieWatchLink(document, page) ?: page
            newMovieLoadResponse(title, page, TvType.Movie, watchLink) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addScore(rating)
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
        val watchUrl = normalizeUrl(data, mainUrl).takeIf { it.startsWith("http", true) } ?: return false
        val seen = linkedSetOf<String>()
        val visitedPages = linkedSetOf<String>()
        var found = false

        suspend fun emit(candidate: String, referer: String): Boolean {
            val fixed = normalizeUrl(candidate, referer).takeIf { it.startsWith("http", true) } ?: return false
            val cleanKey = fixed.substringBefore("#")
            if (!seen.add(cleanKey) || fixed.isNoiseUrl()) return false

            if (fixed.isDirectMedia()) {
                callback(
                    newExtractorLink(name, name, fixed, if (fixed.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.headers = headers + mapOf("Referer" to referer)
                    }
                )
                return true
            }

            var localFound = false
            runCatching {
                loadExtractor(fixed, referer, subtitleCallback) { link ->
                    val linkKey = link.url.substringBefore("#")
                    if (seen.add(linkKey)) {
                        localFound = true
                        callback(link)
                    }
                }
            }
            return localFound
        }

        fun shouldInspectPlayer(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            if (lower.isNoiseUrl()) return false
            if (lower.isDirectMedia()) return false
            return lower.contains("player") ||
                lower.contains("iframe") ||
                lower.contains("stream") ||
                lower.contains("embed") ||
                lower.contains("/get") ||
                lower.contains("/proxy") ||
                lower.contains("s3.player.biz.id") ||
                lower.contains("apikey.web.id") ||
                lower.contains("abyssplayer") ||
                lower.contains("playeriframe.sbs")
        }

        suspend fun inspectPage(pageUrl: String, referer: String, depth: Int = 0) {
            val fixedPage = normalizeUrl(pageUrl, referer).takeIf { it.startsWith("http", true) } ?: return
            if (depth > 3 || !visitedPages.add(fixedPage) || fixedPage.isNoiseUrl()) return

            val response = runCatching {
                app.get(fixedPage, headers = headers + mapOf("Referer" to referer), referer = referer)
            }.getOrNull() ?: return

            val document = response.document
            val html = response.text.ifBlank { document.html() }
            collectSubtitles(document, fixedPage, subtitleCallback)

            val candidates = linkedSetOf<String>()
            collectCinemaIndoAjaxPlayers(document, fixedPage).forEach { candidates.add(it) }
            collectServerCandidates(document, html, fixedPage).forEach { candidates.add(it) }
            collectDooplayAjax(document, html, fixedPage).forEach { candidates.add(it) }

            candidates.forEach { candidate ->
                if (emit(candidate, fixedPage)) {
                    found = true
                } else {
                    val normalized = normalizeUrl(candidate, fixedPage)
                    if (shouldInspectPlayer(normalized)) {
                        inspectPage(normalized, fixedPage, depth + 1)
                    }
                }
            }
        }

        inspectPage(watchUrl, "$mainUrl/")
        return found
    }

    private data class CategoryApiPage(
        val items: List<SearchResponse>,
        val hasNext: Boolean
    )

    private val knownCategoryApiTokens = mapOf(
        "/api/movies/list/latest" to "OUdYQjdQUWM0MENjT2ZWTFF0Sm1CQTM4TGtBMno3U21RenBDUnRDT20wbz06OmNjOTYwOTIxZWQxM2VhMzlkNjM2MTA1NTgwYjU0Mzg4",
        "/api/movies/list/release" to "OUdYQjdQUWM0MENjT2ZWTFF0Sm1CQTM4TGtBMno3U21RenBDUnRDT20wbz06OmNjOTYwOTIxZWQxM2VhMzlkNjM2MTA1NTgwYjU0Mzg4",
        "/api/series/list/top-series-today" to "Tk80TjM3bWtjQU5ENitIdTJMKzlVeGtza3VEMzQ3TUI1ajJBbkJoNTg4N0RYeThxN0xvQTE3cXhoTHpVc0o1azo6ZjRhZDNjOTE2MmQzYmYyNjIwZDkzMTU5Yjc5OWJhMTc",
        "/api/series/list/latest-series" to "UUdjK3d6Umc3MnVJTjF1SlVQeGZ5U2JpUHdFMmZzQ29ualJrNndvMFFzUT06OmJhMTQ4NmUzNDE3YjE4OWZlNzAxYTA1N2MyYTNhOTQw",
        "/api/series/status/complete" to "UVVRNURFL0hocXh2T0UwZzVlNlpSQjM3ZmRMaXY3ZWw0VXBaYkV0MnBlUT06Ojc1MTYxODJiZWI0MzdiNjI2YzBjZDViMmZmOTcxMWMz"
    )

    private suspend fun fetchCategoryApi(document: Document, pageUrl: String, page: Int): CategoryApiPage? {
        val apiPath = categoryApiPath(document, pageUrl) ?: return null
        val token = knownCategoryApiTokens[apiPath] ?: return null
        val apiUrl = "$mainUrl/api/v1/${token}/${page.coerceAtLeast(1)}"
        val body = runCatching {
            app.get(apiUrl, headers = headers + mapOf("Referer" to pageUrl), referer = pageUrl).text
        }.getOrNull().orEmpty()

        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!json.optString("status").equals("success", true)) return null

        val items = mutableListOf<SearchResponse>()
        val results = json.optJSONArray("results") ?: return CategoryApiPage(emptyList(), hasNext = false)
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            apiItemToSearchResult(item)?.let { items.add(it) }
        }

        val currentPage = json.optInt("current_page", page)
        val maxPage = json.optInt("max_page", currentPage)
        return CategoryApiPage(items.distinctBy { it.url }, hasNext = currentPage < maxPage)
    }

    private fun categoryApiPath(document: Document, pageUrl: String): String? {
        val container = document.selectFirst("#tax-container[data-posttype], .home-fetch-container[data-posttype]")
        val fromContainer = container?.let { element ->
            categoryApiPathFromData(
                postType = element.attr("data-posttype"),
                type = element.attr("data-type"),
                taxonomy = element.attr("data-taxonomy"),
                value = element.attr("data-value"),
                genre = element.attr("data-genre"),
                country = element.attr("data-country"),
                year = element.attr("data-year"),
                artist = element.attr("data-artist"),
                director = element.attr("data-director")
            )
        }

        if (fromContainer != null) return fromContainer

        val path = runCatching { URI(pageUrl).path.trim('/') }.getOrDefault("")
        return when {
            path == "latest" -> "/api/movies/list/latest"
            path == "release" -> "/api/movies/list/release"
            path == "series/top-series-today" -> "/api/series/list/top-series-today"
            path == "series/latest" -> "/api/series/list/latest-series"
            path == "series/complete" -> "/api/series/status/complete"
            path.startsWith("genre/") -> "/api/movies/filter/genre/${path.substringAfter("genre/")}"
            path.startsWith("country/") -> "/api/movies/filter/country/${path.substringAfter("country/")}"
            else -> null
        }
    }

    private fun categoryApiPathFromData(
        postType: String,
        type: String,
        taxonomy: String,
        value: String,
        genre: String,
        country: String,
        year: String,
        artist: String,
        director: String
    ): String? {
        val apiType = type.ifBlank { if (postType.equals("series", true)) "series" else "movies" }
        return when {
            genre.isNotBlank() -> "/api/$apiType/filter/genre/${genre.lowercase(Locale.ROOT)}"
            country.isNotBlank() -> "/api/$apiType/filter/country/${country.lowercase(Locale.ROOT)}"
            year.isNotBlank() -> "/api/$apiType/filter/year/$year"
            artist.isNotBlank() -> "/api/$apiType/filter/artist/${artist.lowercase(Locale.ROOT)}"
            director.isNotBlank() -> "/api/$apiType/filter/director/${director.lowercase(Locale.ROOT)}"
            taxonomy.equals("list", true) && value.isNotBlank() -> "/api/$apiType/list/$value"
            postType.equals("series", true) && taxonomy.equals("status", true) && value.isNotBlank() -> "/api/$apiType/$taxonomy/$value"
            taxonomy.isNotBlank() && value.isNotBlank() -> "/api/$apiType/filter/$taxonomy/$value"
            else -> null
        }
    }

    private fun apiItemToSearchResult(item: JSONObject): SearchResponse? {
        val title = item.optString("title").cleanTitle().ifBlank { return null }
        val slug = item.optString("slug").trim().trim('/')
        if (slug.isBlank()) return null

        val type = item.optString("type")
        val isSeries = type.equals("series", true)
        val href = if (isSeries) {
            normalizeUrl("/tv/$slug", mainUrl)
        } else {
            normalizeUrl("/$slug", mainUrl)
        }
        if (!href.startsWith("http", true)) return null

        val poster = item.optString("thumbnail")
            .takeIf { it.isNotBlank() }
            ?.let { normalizeUrl(it, mainUrl) }
            ?.takeIf { it.startsWith("http", true) }

        val year = item.optString("year").toIntOrNull()
        val rating = item.optString("rating")
            .replace(",", ".")
            .toDoubleOrNull()

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                rating?.let { this.score = com.lagradost.cloudstream3.Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
                rating?.let { this.score = com.lagradost.cloudstream3.Score.from10(it) }
            }
        }
    }

    private suspend fun collectCinemaIndoAjaxPlayers(document: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val base = getBaseUrl(pageUrl)
        val isEpisodePage = pageUrl.contains("/eps/", true)

        document.select("#player-list a.getplayer[data-value], a.getplayer[data-value]").forEach { option ->
            val id = option.attr("data-value").trim()
            if (id.isBlank()) return@forEach

            val slug = option.attr("data-slug")
                .ifBlank { pageUrl.substringBefore("?").trimEnd('/').substringAfterLast("/") }
                .trim()
            val server = option.attr("data-server")
                .ifBlank { option.text() }
                .trim()
            val poster = option.attr("data-poster").trim()
            val postId = option.attr("data-post-id")
                .ifBlank { if (isEpisodePage) "" else slug }

            val body = runCatching {
                app.post(
                    "$base/ajax/player",
                    data = mapOf(
                        "id" to id,
                        "post_id" to postId,
                        "poster" to poster,
                        "server" to server,
                        "slug" to slug,
                        "action" to "player_content"
                    ),
                    headers = ajaxHeaders(pageUrl),
                    referer = pageUrl
                ).text
            }.getOrNull().orEmpty().trim()

            if (body.startsWith("http", true) || body.startsWith("//")) {
                normalizeUrl(body, pageUrl).takeIf { it.startsWith("http", true) }?.let { links.add(it) }
            }
            collectLinksFromHtml(body, pageUrl).forEach { links.add(it) }
        }

        return links.toList()
    }

    private suspend fun collectDooplayAjax(document: Document, html: String, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val base = getBaseUrl(pageUrl)
        val ajaxUrl = "$base/wp-admin/admin-ajax.php"
        val options = document.select(
            "li.dooplay_player_option, .dooplay_player_option, .dooplay_player, " +
                "[data-post][data-nume][data-type], [data-post][data-type], [data-id][data-nume]"
        )

        options.forEach { option ->
            val post = option.attr("data-post").ifBlank { option.attr("data-id") }
            val nume = option.attr("data-nume").ifBlank { option.attr("data-index").ifBlank { "1" } }
            val type = option.attr("data-type").ifBlank { if (html.contains("tvshows", true)) "tv" else "movie" }
            if (post.isBlank()) return@forEach

            val body = runCatching {
                app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    headers = ajaxHeaders(pageUrl),
                    referer = pageUrl
                ).text
            }.getOrNull().orEmpty()

            collectLinksFromHtml(body, pageUrl).forEach { links.add(it) }
        }

        return links.toList()
    }

    private fun collectServerCandidates(document: Document, html: String, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()

        document.select(
            "#player iframe[src], #player iframe[data-src], .player iframe[src], .player iframe[data-src], " +
                "[id*=player] iframe[src], [class*=player] iframe[src], iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                "embed[src], video[src], video source[src], source[src]"
        ).forEach { element ->
            val value = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-litespeed-src") }
            normalizeUrl(value, pageUrl).takeIf { it.startsWith("http", true) }?.let { links.add(it) }
        }

        document.select(
            ".mirror option[value], .server option[value], .servers option[value], .player option[value], " +
                "select option[value], option[value], a.getplayer[data-value], [data-value], [data-src], [data-url], [data-link], [data-embed], [data-iframe]"
        ).forEach { element ->
            listOf("value", "data-value", "data-src", "data-url", "data-link", "data-embed", "data-iframe").forEach { attr ->
                val value = element.attr(attr).trim()
                if (value.isNotBlank()) {
                    decodeServerValue(value, pageUrl).forEach { links.add(it) }
                }
            }
        }

        collectLinksFromHtml(html, pageUrl).forEach { links.add(it) }
        return links.filterNot { it.isNoiseUrl() }.toList()
    }

    private fun collectLinksFromHtml(html: String, baseUrl: String): List<String> {
        val normalized = normalizeEncodedText(html)
        val links = linkedSetOf<String>()
        val parsed = runCatching { Jsoup.parse(normalized, baseUrl) }.getOrNull()

        parsed?.select("iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], video[src], source[src]")?.forEach { element ->
            val value = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-litespeed-src") }
            normalizeUrl(value, baseUrl).takeIf { it.startsWith("http", true) }?.let { links.add(it) }
        }

        Regex("""(?i)<(?:iframe|embed)[^>]+(?:src|data-src|data-litespeed-src)=['"]([^'"]+)['"]""")
            .findAll(normalized)
            .mapNotNull { normalizeUrl(it.groupValues[1], baseUrl).takeIf { url -> url.startsWith("http", true) } }
            .forEach { links.add(it) }

        Regex("""(?i)(?:embed_url|iframe_url|player_url|file|source|src|url|link|hls)\s*[:=]\s*['"]([^'"]+)['"]""")
            .findAll(normalized)
            .mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }
            .forEach { links.add(it) }

        Regex("""(?i)['"]((?:https?:)?//[^'"]+(?:embed|player|stream|dailymotion|ok\.ru|rumble|dood|filemoon|mega|streamtape|vidhide|vidguard|voe|mp4upload|uqload|filelions|gdplayer|gdriveplayer|hubcloud|/e/|/v/|/d/|\.m3u8|\.mp4)[^'"]*)['"]""")
            .findAll(normalized)
            .mapNotNull { normalizeUrl(it.groupValues[1], baseUrl).takeIf { url -> url.startsWith("http", true) } }
            .forEach { links.add(it) }

        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""")
            .findAll(normalized)
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .forEach { decoded -> collectLinksFromHtml(decoded, baseUrl).forEach { links.add(it) } }

        return links.toList()
    }

    private fun decodeServerValue(value: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val candidates = linkedSetOf<String>()
        val cleaned = normalizeEncodedText(value)
        candidates.add(cleaned)
        decodeUrl(cleaned)?.let { candidates.add(it) }
        decodeBase64(cleaned)?.let { candidates.add(it) }
        decodeBase64(cleaned.replace("-", "+").replace("_", "/"))?.let { candidates.add(it) }

        candidates.forEach { candidate ->
            val text = normalizeEncodedText(candidate)
            if (text.startsWith("http", true) || text.startsWith("//")) {
                normalizeUrl(text, baseUrl).takeIf { it.startsWith("http", true) }?.let { links.add(it) }
            }
            collectLinksFromHtml(text, baseUrl).forEach { links.add(it) }
        }

        return links.toList()
    }

    private fun decodePossibleUrl(value: String, baseUrl: String): String? {
        val cleaned = normalizeEncodedText(value)
        val direct = normalizeUrl(cleaned, baseUrl).takeIf { it.startsWith("http", true) }
        if (direct != null) return direct
        val decodedUrl = decodeUrl(cleaned)?.let { normalizeUrl(it, baseUrl).takeIf { url -> url.startsWith("http", true) } }
        if (decodedUrl != null) return decodedUrl
        return decodeBase64(cleaned)?.let { decoded ->
            Regex("""(?i)(?:https?:)?//[^\s'"<>]+""").find(decoded)?.value?.let { normalizeUrl(it, baseUrl) }
        }
    }

    private fun collectSubtitles(document: Document, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val url = normalizeUrl(element.attr("src").ifBlank { element.attr("href") }, baseUrl)
            if (!url.startsWith("http", true)) return@forEach
            val label = element.attr("label")
                .ifBlank { element.attr("srclang") }
                .ifBlank { element.text() }
                .ifBlank { "Subtitle" }
            subtitleCallback(SubtitleFile(label.cleanText(), url))
        }
    }

    private fun parseCards(document: Document, baseUrl: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select(
            "article.item, article:has(a):has(img), .item:has(a):has(img), .items article, " +
                ".movie:has(a):has(img), .film:has(a):has(img), .ml-item:has(a):has(img), .result-item:has(a):has(img), " +
                ".post:has(a):has(img), .card:has(a):has(img)"
        ).forEach { element ->
            element.toSearchResult(baseUrl)?.let { results[it.url] = it }
        }

        if (results.isEmpty()) {
            document.select("a[href]:has(img)").forEach { element ->
                element.toSearchResult(baseUrl)?.let { results[it.url] = it }
            }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val anchor = if (`is`("a[href]")) {
            this
        } else {
            selectFirst("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], .title a[href], a[href]:has(img), a[href]")
                ?: return null
        }

        val href = normalizeUrl(anchor.attr("href"), baseUrl)
            .takeIf { it.startsWith("http", true) && isContentUrl(it) }
            ?: return null

        val image = selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[data-wpfc-original-src], img[src], img[srcset]")
            ?: anchor.selectFirst("img")
        val title = listOf(
            selectFirst("h1, h2, h3, .entry-title, .title, .name")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text(),
            titleFromUrl(href)
        ).firstOrNull { isUsefulTitle(it) }?.cleanTitle() ?: return null

        val poster = image?.imageUrl(baseUrl)
        val type = if (href.contains("/tv", true) || href.contains("series", true) || text().contains("series", true)) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        val seasonData = document.selectFirst("script#season-data")
            ?.let { it.data().ifBlank { it.html() }.ifBlank { it.text() } }
            ?.trim()

        if (!seasonData.isNullOrBlank()) {
            runCatching {
                val seasons = JSONObject(seasonData)
                val keys = seasons.keys()
                while (keys.hasNext()) {
                    val seasonKey = keys.next()
                    val seasonNumber = seasonKey.toIntOrNull()
                    val episodeArray = seasons.optJSONArray(seasonKey) ?: continue
                    for (index in 0 until episodeArray.length()) {
                        val item = episodeArray.optJSONObject(index) ?: continue
                        val slug = item.optString("slug").trim().trim('/')
                        if (slug.isBlank()) continue

                        val episodeNumber = item.optInt("episode_no", index + 1).takeIf { it > 0 } ?: (index + 1)
                        val href = normalizeUrl("/eps/$slug", mainUrl)
                        val title = item.optString("title")
                            .cleanText()
                            .ifBlank { "Season ${seasonNumber ?: 1} Episode $episodeNumber" }

                        episodes[href] = newEpisode(href) {
                            name = title
                            episode = episodeNumber
                            season = seasonNumber ?: item.optInt("s", 0).takeIf { it > 0 }
                        }
                    }
                }
            }
        }

        if (episodes.isNotEmpty()) {
            return episodes.values.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 9999 })
        }

        document.select(
            ".episodes a[href], .episode-list a[href], .list-episodes a[href], .episodios a[href], " +
                ".season a[href], .seasons a[href], .tvseason a[href], [class*=episode] a[href], [id*=episode] a[href], a[href*='/eps/']"
        ).forEachIndexed { index, element ->
            val href = normalizeUrl(element.attr("href"), baseUrl)
            if (!href.startsWith("http", true) || !isContentUrl(href)) return@forEachIndexed
            val joined = "${element.text()} $href".lowercase(Locale.ROOT)
            if (!joined.contains("episode") && !joined.contains("eps") && !joined.contains("season") && !joined.contains("/eps")) return@forEachIndexed
            val epNo = extractEpisodeNumber(element.text(), href) ?: (index + 1)
            episodes[href] = newEpisode(href) {
                name = element.text().cleanText().ifBlank { "Episode $epNo" }
                episode = epNo
                season = extractSeasonNumber(element.text(), href)
            }
        }
        return episodes.values.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 9999 })
    }

    private fun findMovieWatchLink(document: Document, baseUrl: String): String? {
        val localHosts = listOf(getBaseUrl(baseUrl), mainUrl)
        return document.select(
            "a[href*='/watch/'], a[href*='/stream/'], a[href*='/play/'], a[href*='player'], " +
                "a[href*='nonton'], .player a[href], .watch a[href], .play a[href], [class*=watch] a[href]"
        ).mapNotNull { element ->
            val href = normalizeUrl(element.attr("href"), baseUrl)
            href.takeIf { candidate ->
                candidate.startsWith("http", true) &&
                    localHosts.any { host -> candidate.startsWith(host, true) } &&
                    isContentUrl(candidate)
            }
        }.firstOrNull()
    }

    private fun parseRecommendations(document: Document, currentUrl: String): List<SearchResponse> {
        return document.select(".related, .recommend, .rekomendasi, .owl-carousel, section")
            .flatMap { section -> parseCards(Jsoup.parseBodyFragment(section.html(), currentUrl), currentUrl) }
            .distinctBy { it.url }
            .filterNot { it.url == currentUrl }
            .take(16)
    }

    private fun findPoster(document: Document): String? {
        return fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], figure img, .poster img, .post-thumbnail img, .thumb img, .content-thumbnail img, img[itemprop=image]"
            )?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.imageUrl(mainUrl) }
        )?.fixImageQuality()
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a.next, a[rel=next], .pagination a:contains(Next), .page-numbers.next, " +
                "a[href*='/page/${page + 1}/'], a[href*='paged=${page + 1}'], a[href*='page=${page + 1}']"
        ) != null
    }

    private fun buildPagedUrl(data: String, page: Int): String {
        val raw = if (data.contains("%d")) data.format(page) else data
        return normalizeUrl(raw, mainUrl)
    }

    private fun ajaxHeaders(referer: String): Map<String, String> {
        return headers + mapOf(
            "Accept" to "*/*",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to getBaseUrl(referer),
            "Referer" to referer
        )
    }

    private fun inferType(url: String, text: String, episodes: List<Episode>): TvType {
        val lower = "$url $text".lowercase(Locale.ROOT)
        return when {
            episodes.isNotEmpty() -> TvType.TvSeries
            lower.contains("tv-series") || lower.contains("series") || lower.contains("episode") || lower.contains("season") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun isContentUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT).substringBefore("#")
        if (!lower.startsWith(mainUrl.lowercase(Locale.ROOT)) && !lower.contains(URI(mainUrl).host.lowercase(Locale.ROOT))) return false
        return listOf(
            "/genre/", "/country/", "/year/", "/tag/", "/category/", "/page/", "/search/", "/privacy", "/contact", "/dmca", "/disclaimer", "/login", "/register"
        ).none { lower.contains(it) }
    }

    private fun isUsefulTitle(value: String?): Boolean {
        val text = value?.cleanText().orEmpty()
        if (text.length < 2) return false
        return !listOf("home", "movie", "movies", "series", "tv series", "genre", "country", "watch", "trailer", "play", "nonton").any {
            text.equals(it, true)
        }
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val raw = attr("data-src")
            .ifBlank { attr("data-original") }
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-wpfc-original-src") }
            .ifBlank { attr("srcset").substringBefore(" ") }
            .ifBlank { attr("content") }
            .ifBlank { attr("src") }
        return normalizeUrl(raw, baseUrl).takeIf { it.startsWith("http", true) }?.fixImageQuality()
    }

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val cleaned = normalizeEncodedText(url).trim()
        if (cleaned.isBlank() || cleaned.equals("#", true) || cleaned.startsWith("javascript:", true)) return ""
        val unescaped = decodeUrl(cleaned) ?: cleaned
        return when {
            unescaped.startsWith("//") -> "https:$unescaped"
            unescaped.startsWith("http://", true) || unescaped.startsWith("https://", true) -> unescaped
            else -> runCatching { URI(baseUrl).resolve(unescaped).toString() }.getOrDefault("")
        }
    }

    private fun normalizeEncodedText(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003c", "<")
            .replace("\\u003e", ">")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun String.cleanText(): String {
        return this
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return cleanText()
            .replace(Regex("""(?i)^\s*permalink\s+ke:\s*"""), "")
            .replace(Regex("""(?i)\s+subtitle\s+indonesia.*$"""), "")
            .replace(Regex("""(?i)\s+sub\s+indo.*$"""), "")
            .replace(Regex("""(?i)\s+season\s+\d+.*$"""), "")
            .replace(Regex("""(?i)\s+episode\s+\d+.*$"""), "")
            .trim()
    }

    private fun String.fixImageQuality(): String {
        val match = Regex("""-\d*x\d*(?=\.)""").find(this)?.value
        return if (match != null) replace(match, "") else this
    }

    private fun titleFromUrl(url: String): String {
        return url.substringBeforeLast("?")
            .trimEnd('/')
            .substringAfterLast('/')
            .replace("-", " ")
            .cleanTitle()
    }

    private fun extractEpisodeNumber(name: String, href: String): Int? {
        return (
            Regex("""(?i)(?:eps?|episode)\s*[-:.]?\s*(\d{1,4})""").find("$name $href")?.groupValues?.getOrNull(1)
                ?: Regex("""(?i)(?:/|-)(\d{1,4})(?:/|$)""").find(href)?.groupValues?.getOrNull(1)
        )?.toIntOrNull()
    }

    private fun extractSeasonNumber(name: String, href: String): Int? {
        return (
            Regex("""(?i)(?:season|s)\s*[-:.]?\s*(\d{1,3})""").find("$name $href")?.groupValues?.getOrNull(1)
                ?: Regex("""(?i)(?:season-|/season/)(\d{1,3})""").find(href)?.groupValues?.getOrNull(1)
        )?.toIntOrNull()
    }

    private fun slugify(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
    }

    private fun decodeUrl(value: String): String? {
        return runCatching { URLDecoder.decode(value, "UTF-8") }
            .getOrNull()
            ?.takeIf { it != value && it.isNotBlank() }
    }

    private fun decodeBase64(value: String): String? {
        val cleaned = value.trim().trim('\'', '"')
        if (cleaned.length < 12 || !cleaned.matches(Regex("""[A-Za-z0-9+/=_-]+"""))) return null
        val padded = cleaned.padEnd(cleaned.length + ((4 - cleaned.length % 4) % 4), '=')
        return runCatching { String(Base64.getDecoder().decode(padded)) }
            .getOrNull()
            ?.takeIf { it.contains("<iframe", true) || it.contains("http", true) || it.contains("//") }
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase(Locale.ROOT).substringBefore("?")
        return lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".webm") || contains("/hls/", true)
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.contains("google-analytics") ||
            lower.contains("facebook.com/tr") ||
            lower.contains("twitter.com") ||
            lower.contains("/ads") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".css") ||
            lower.endsWith(".js")
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }
}
