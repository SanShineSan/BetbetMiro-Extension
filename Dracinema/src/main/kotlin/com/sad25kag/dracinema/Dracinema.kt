package com.sad25kag.dracinema

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Dracinema : MainAPI() {
    override var mainUrl = "https://www.dracinema.com"
    override var name = "Dracinema"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "" to "Beranda",
        "api:Romantis" to "Romantis",
        "api:Balas dendam" to "Balas Dendam",
        "api:Identitas Tersembunyi" to "Identitas Tersembunyi",
        "api:Identitas Rahasia" to "Identitas Rahasia",
        "api:Dari Miskin ke Kaya" to "Dari Miskin ke Kaya",
        "api:Pengkhianatan" to "Pengkhianatan",
        "api:Serangan balik" to "Serangan Balik",
        "api:Terlahir Kembali" to "Terlahir Kembali",
        "api:Miliarder" to "Miliarder",
        "api:Pahlawan Wanita Kuat" to "Pahlawan Wanita Kuat",
        "api:Cinta Setelah Menikah" to "Cinta Setelah Menikah",
        "api:Laki-laki" to "Laki-laki",
        "api:18+" to "18+"
    )

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private val apiHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/collections"
    )

    private val playerHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private val mediaHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*"
    )

    private val hlsHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*"
    )

    private fun rscHeaders(movieKey: String, episode: Int): Map<String, String> {
        val previousEpisode = (episode - 1).coerceAtLeast(1).toString()
        val stateTree = """["",{"children":["(other)",{"children":["play",{"children":[["movieKey","$movieKey","d"],{"children":[["eps","$previousEpisode","d"],{"children":["__PAGE__",{},null,null]},null,null]},null,null]},null,null]},null,null]},null,null,true]"""
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "RSC" to "1",
            "Next-Url" to "/play/$movieKey/$previousEpisode",
            "Next-Router-State-Tree" to URLEncoder.encode(stateTree, "UTF-8"),
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"
        )
    }

    private fun String.hasPlayableEvidence(): Boolean {
        return contains("videoUrls", true) ||
            contains("awscdn.netshort.com", true) ||
            contains("cdn.dramabos.video", true) ||
            contains("flickreels/hls", true)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith("api:", true)) {
            val category = request.data.removePrefix("api:")
            val apiItems = getApiMovies(category, page)
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (apiItems.isNotEmpty()) {
                return newHomePageResponse(
                    HomePageList(request.name, apiItems),
                    hasNext = apiItems.size >= 12
                )
            }

            val fallbackUrl = "$mainUrl/genre/${category.toCategorySlug()}${if (page > 1) "?page=$page" else ""}"
            val fallbackDocument = runCatching {
                app.get(fallbackUrl, headers = commonHeaders, referer = mainUrl).document
            }.getOrNull()

            val fallbackItems = fallbackDocument
                ?.let { parseCards(it).distinctBy { item -> item.url } }
                .orEmpty()

            return newHomePageResponse(
                HomePageList(request.name, fallbackItems),
                hasNext = fallbackDocument?.hasNextPage(page) == true
            )
        }

        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = commonHeaders, referer = mainUrl).document

        if (request.data.isBlank() && page <= 1) {
            val rows = parseHomeRows(document)
            if (rows.isNotEmpty()) return newHomePageResponse(rows)
        }

        val list = parseCards(document).distinctBy { it.url }
        return newHomePageResponse(
            request.name,
            list,
            hasNext = document.hasNextPage(page)
        )
    }

    private suspend fun getApiMovies(category: String, page: Int): List<DracinemaApiMovie> {
        val encoded = URLEncoder.encode(category, "UTF-8")
        val url = "$mainUrl/api/movie?page=${page.coerceAtLeast(1)}&categories=$encoded"
        val text = runCatching {
            app.get(url, headers = apiHeaders, referer = "$mainUrl/collections").text
        }.getOrNull() ?: return emptyList()

        return parseApiMovies(text)
    }

    private fun parseApiMovies(text: String): List<DracinemaApiMovie> {
        return runCatching {
            val trimmed = text.trim()
            val array = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONObject(trimmed).optJSONArray("data") ?: JSONArray()
                else -> JSONArray()
            }

            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val bookId = item.optString("bookId")
                    .takeIf { it.isNotBlank() }
                    ?: item.optString("originalBookId").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val title = listOf(
                    item.optString("bookName"),
                    item.optString("replacedBookName")
                ).firstOrNull { it.isNotBlank() }?.cleanTitle()
                    ?: return@mapNotNull null

                DracinemaApiMovie(
                    bookId = bookId,
                    title = title,
                    poster = item.optString("cover").takeIf { it.isNotBlank() },
                    description = item.optString("introduction").takeIf { it.isNotBlank() },
                    episodeCount = item.optInt("chapterCount").takeIf { it > 0 },
                    tags = item.optJSONArray("typeTwoNames").toStringList()
                )
            }
        }.getOrNull().orEmpty()
    }

    private fun DracinemaApiMovie.toSearchResult(): SearchResponse? {
        val title = this.title.cleanTitle().takeIf { it.length >= 2 } ?: return null
        val slug = title.toUrlSlug().ifBlank { bookId }
        val url = "$mainUrl/movie/$slug-$bookId"

        return newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
            posterUrl = fixUrlNull(poster)
        }
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim('/')
        return when {
            cleanPath.isBlank() && page <= 1 -> mainUrl
            cleanPath.isBlank() -> "$mainUrl?page=$page"
            page <= 1 -> "$mainUrl/$cleanPath"
            cleanPath == "collections" -> "$mainUrl/collections?page=$page"
            cleanPath.startsWith("genre/") -> "$mainUrl/$cleanPath?page=$page"
            else -> "$mainUrl/$cleanPath?page=$page"
        }
    }

    private fun parseHomeRows(document: Document): List<HomePageList> {
        val rows = mutableListOf<HomePageList>()
        document.select("section, main > div, div[class*=section]").forEach { section ->
            val title = section.selectFirst("h1, h2, h3")
                ?.text()?.trim()?.cleanTitle()?.takeIf { it.isNotBlank() }
                ?: return@forEach

            val items = section.select("a[href*='/movie/']")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (items.isNotEmpty()) rows.add(HomePageList(title, items))
        }

        if (rows.isEmpty()) {
            parseCards(document).takeIf { it.isNotEmpty() }?.let {
                rows.add(HomePageList("Beranda", it))
            }
        }
        return rows.distinctBy { it.name }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select(
            "a[href*='/movie/'], article a[href*='/movie/'], .card a[href*='/movie/'], " +
                ".grid a[href*='/movie/'], .swiper-slide a[href*='/movie/'], [class*=movie] a[href*='/movie/']"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) this else selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl) || !href.contains("/movie/", true)) return null

        val rawTitle = listOf(
            selectFirst("h1")?.text()?.trim(),
            selectFirst("h2")?.text()?.trim(),
            selectFirst("h3")?.text()?.trim(),
            selectFirst("[class*=title]")?.text()?.trim(),
            anchor.attr("title").trim(),
            anchor.attr("aria-label").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Putar Sekarang", true) &&
                !it.equals("Tonton Sekarang", true) &&
                !it.equals("Detail Info", true) &&
                !it.equals("Muat Lebih Banyak", true)
        } ?: return null

        val title = rawTitle.cleanTitle().takeIf { it.length >= 2 } ?: return null
        val poster = fixUrlNull(selectFirst("img")?.getImageAttr())

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val apiResults = runCatching {
            app.get(
                "$mainUrl/api/search?keyword=$encoded",
                headers = apiHeaders,
                referer = mainUrl
            ).text
        }.getOrNull()
            ?.let { parseApiMovies(it) }
            ?.mapNotNull { it.toSearchResult() }
            ?.distinctBy { it.url }
            .orEmpty()

        if (apiResults.isNotEmpty()) {
            return newSearchResponseList(apiResults, hasNext = false)
        }

        val pageNumber = page.coerceAtLeast(1)
        val urls = listOf(
            "$mainUrl/search?q=$encoded&page=$pageNumber",
            "$mainUrl/search?keyword=$encoded&page=$pageNumber",
            "$mainUrl/search/$encoded?page=$pageNumber",
            "$mainUrl/collections?search=$encoded&page=$pageNumber",
            "$mainUrl/collections?q=$encoded&page=$pageNumber",
            "$mainUrl/collections?page=$pageNumber",
            "$mainUrl?page=$pageNumber"
        )

        val exactResults = linkedMapOf<String, SearchResponse>()
        val fallbackResults = linkedMapOf<String, SearchResponse>()
        val tokens = keyword.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }

        urls.forEach { url ->
            val document = runCatching {
                app.get(url, headers = commonHeaders, referer = mainUrl).document
            }.getOrNull() ?: return@forEach

            parseCards(document).forEach { item ->
                fallbackResults[item.url] = item
                val haystack = item.name.lowercase()
                if (haystack.contains(keyword.lowercase()) || tokens.any { haystack.contains(it) }) {
                    exactResults[item.url] = item
                }
            }
        }

        val output = exactResults.values.ifEmpty { fallbackResults.values.take(12) }
        return newSearchResponseList(output.toList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = commonHeaders, referer = mainUrl).document
        val title = document.selectFirst("h1, h1[class*=title], meta[property=og:title], meta[name=title]")
            ?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.cleanTitle()?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/").substringBefore("?").replace("-", " ").cleanTitle()

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image], meta[name=twitter:image], img[class*=poster], img[class*=cover], picture img, img")
                ?.let { if (it.hasAttr("content")) it.attr("content") else it.getImageAttr() }
        )

        val description = document.selectFirst(
            "meta[property=og:description], meta[name=description], .description, [class*=description], .synopsis, [class*=synopsis], p"
        )?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.trim()?.takeIf { it.isNotBlank() }

        val tags = document.select("a[href*='/genre/'], [class*=genre] a, [class*=tag] a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = parseEpisodes(document, url)
        val recommendations = parseCards(document).filter { it.url != url }.distinctBy { it.url }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            posterUrl = poster
            plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun parseEpisodes(document: Document, fallbackUrl: String): List<Episode> {
        val episodes = linkedMapOf<Int, Episode>()
        val candidates = document.select(
            "a[href*='episode'], a[href*='eps'], a[href*='play'], a[href*='watch'], " +
                "button[data-url], button[data-src], button[data-href], button[data-episode], " +
                "[class*=episode] a[href], [class*=eps] a[href], [class*=episode][data-url], " +
                "[data-episode], [data-episode-id], [data-ep], [data-id]"
        )

        candidates.forEachIndexed { index, element ->
            val text = element.text().trim()
            val epNum = extractEpisodeNumber(text, element.outerHtml()) ?: (index + 1)
            val raw = listOf(
                element.attr("href"),
                element.attr("data-url"),
                element.attr("data-src"),
                element.attr("data-href"),
                element.attr("data-video"),
                element.attr("data-file")
            ).firstOrNull { it.isNotBlank() }.orEmpty()

            val fixed = when {
                raw.isBlank() || raw == "#" || raw.startsWith("javascript", true) -> "$fallbackUrl?episode=$epNum"
                else -> raw.absoluteUrl(fallbackUrl) ?: "$fallbackUrl?episode=$epNum"
            }

            if (fixed.contains("/movie/", true)) return@forEachIndexed

            val nameText = text.cleanEpisodeName(epNum)
            episodes[epNum] = newEpisode(fixed) {
                name = nameText
                episode = epNum
            }
        }

        if (episodes.isEmpty()) {
            val count = extractEpisodeCount(document.text()) ?: 1
            for (ep in 1..count) {
                episodes[ep] = newEpisode("$fallbackUrl?episode=$ep") {
                    name = "Episode $ep"
                    episode = ep
                }
            }
        }

        return episodes.values.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetEpisode = data.substringAfter("?episode=", "").substringBefore("&").toIntOrNull()
        val playUrl = data.toPlayUrl(targetEpisode)
        val emitted = linkedSetOf<String>()
        var found = false

        val pageTexts = mutableListOf<String>()
        val playUrls = linkedSetOf(playUrl)

        if (targetEpisode == 1 && playUrl.matches(Regex(""".*/play/[^/]+/1/?$"""))) {
            playUrls.add(playUrl.trimEnd('/').substringBeforeLast("/"))
        }

        playUrls.forEach { url ->
            runCatching {
                app.get(url, headers = playerHeaders, referer = mainUrl).text
            }.getOrNull()?.let(pageTexts::add)
        }

        if (pageTexts.none { it.hasPlayableEvidence() }) {
            val movieKey = playUrl.substringAfter("/play/", "").substringBefore("/").substringBefore("?")
            if (movieKey.isNotBlank() && targetEpisode != null && targetEpisode > 0) {
                val rscUrl = "$playUrl?_rsc=1"
                runCatching {
                    app.get(
                        rscUrl,
                        headers = rscHeaders(movieKey, targetEpisode),
                        referer = "$mainUrl/play/$movieKey/${(targetEpisode - 1).coerceAtLeast(1)}"
                    ).text
                }.getOrNull()?.let(pageTexts::add)
            }
        }

        pageTexts.flatMap { extractDracinemaVideoSources(it) }
            .distinctBy { it.url.videoKey() }
            .forEach { source ->
                if (!emitted.add(source.url.videoKey())) return@forEach

                if (source.url.isHlsUrl()) {
                    if (emitHlsLink(source, playUrl, callback)) found = true
                } else {
                    callback(
                        newExtractorLink(name, "Dracinema ${source.quality ?: Qualities.Unknown.value}p", source.url, ExtractorLinkType.VIDEO) {
                            referer = ""
                            quality = source.quality ?: getQualityFromName(source.url).takeIf { it != Qualities.Unknown.value }
                                ?: Qualities.P720.value
                            headers = mediaHeaders
                        }
                    )
                    found = true
                }
            }

        if (found) return true

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        fun addCandidate(raw: String?, base: String = playUrl) {
            val fixed = raw?.cleanEscaped()?.absoluteUrl(base)?.trimMediaUrl() ?: return
            if (fixed.isNoiseUrl()) return
            when {
                fixed.isDracinemaVideoUrl() || fixed.isHlsUrl() -> directLinks.add(fixed)
                fixed.contains(".mp4", true) || fixed.contains(".webm", true) -> directLinks.add(fixed)
                fixed.startsWith("http", true) -> embedLinks.add(fixed)
            }
        }

        pageTexts.forEach { text -> extractMediaUrls(text).forEach { addCandidate(it) } }

        val document = runCatching {
            Jsoup.parse(pageTexts.firstOrNull().orEmpty(), playUrl)
        }.getOrNull()

        document?.select(
            "video source[src], source[src], video[src], iframe[src], embed[src], " +
                "a[href*='.m3u8'], a[href*='.mp4'], a[href*='embed'], a[href*='player'], " +
                "[data-url], [data-src], [data-video], [data-file], [data-href]"
        )?.distinct()?.forEach { element ->
            listOf("src", "href", "data-url", "data-src", "data-video", "data-file", "data-href")
                .map { element.attr(it) }
                .firstOrNull { it.isNotBlank() }
                ?.let { addCandidate(it) }
        }

        directLinks.forEach { link ->
            if (!emitted.add(link.videoKey())) return@forEach
            val source = DracinemaVideoSource(link, getQualityFromName(link).takeIf { it != Qualities.Unknown.value })
            if (link.isHlsUrl()) {
                if (emitHlsLink(source, playUrl, callback)) found = true
            } else {
                callback(
                    newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                        referer = ""
                        quality = source.quality ?: Qualities.Unknown.value
                        headers = mediaHeaders
                    }
                )
                found = true
            }
        }

        embedLinks.forEach { embed ->
            val key = embed.substringBefore("?")
            if (!emitted.add(key)) return@forEach
            runCatching {
                if (loadExtractor(embed, playUrl, subtitleCallback, callback)) found = true
            }
        }

        return found
    }

    private suspend fun emitHlsLink(
        source: DracinemaVideoSource,
        playUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playlist = runCatching {
            app.get(source.url, headers = hlsHeaders).text
        }.getOrNull()

        if (playlist != null && !playlist.trimStart().startsWith("#EXTM3U")) return false

        callback(
            newExtractorLink(name, "Dracinema HLS ${source.quality ?: Qualities.Unknown.value}p", source.url, ExtractorLinkType.M3U8) {
                referer = ""
                quality = source.quality ?: Qualities.P720.value
                headers = hlsHeaders
            }
        )
        return true
    }

    private data class DracinemaApiMovie(
        val bookId: String,
        val title: String,
        val poster: String?,
        val description: String?,
        val episodeCount: Int?,
        val tags: List<String>
    )

    private data class DracinemaVideoSource(val url: String, val quality: Int?)

    private fun String.toPlayUrl(targetEpisode: Int?): String {
        val clean = substringBefore("?episode=").trimEnd('/')
        if (clean.contains("/play/", true)) return clean
        val movieKey = clean.substringAfter("/movie/", "").substringBefore("?").trim('/').takeIf { it.isNotBlank() }
            ?: return clean
        val base = "$mainUrl/play/$movieKey"
        return if (targetEpisode != null && targetEpisode > 0) "$base/$targetEpisode" else base
    }

    private fun extractDracinemaVideoSources(text: String): List<DracinemaVideoSource> {
        val clean = text.cleanEscaped()
        val sources = linkedMapOf<String, DracinemaVideoSource>()

        Regex(""""quality"\s*:\s*(\d+)[\s\S]{0,320}?"url"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .forEach { match ->
                val quality = match.groupValues.getOrNull(1)?.toIntOrNull()
                val url = match.groupValues.getOrNull(2)?.cleanEscaped()?.trimMediaUrl()
                    ?.takeIf { !it.isNoiseUrl() && (it.isDracinemaVideoUrl() || it.isHlsUrl()) }
                    ?: return@forEach
                sources[url] = DracinemaVideoSource(url, quality)
            }

        Regex(""""url"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .forEach { match ->
                val url = match.groupValues.getOrNull(1)?.cleanEscaped()?.trimMediaUrl()
                    ?.takeIf { !it.isNoiseUrl() && (it.isDracinemaVideoUrl() || it.isHlsUrl()) }
                    ?: return@forEach
                sources.putIfAbsent(url, DracinemaVideoSource(url, getQualityFromName(url).takeIf { q -> q != Qualities.Unknown.value }))
            }

        Regex("""https?://cdn\.dramabos\.video/api/flickreels/hls\?[^"'\\\s<>\]}]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.cleanEscaped().trimMediaUrl() }
            .forEach { url -> sources.putIfAbsent(url, DracinemaVideoSource(url, Qualities.P720.value)) }

        Regex("""https?://awscdn\.netshort\.com/[^"'\\\s<>\]}]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.cleanEscaped().trimMediaUrl() }
            .filter { it.isDracinemaVideoUrl() }
            .forEach { url -> sources.putIfAbsent(url, DracinemaVideoSource(url, Qualities.P720.value)) }

        Regex("""https?://[^"'\\\s<>\]}]+?(?:\.mp4|\.m3u8|\.webm)(?:\?[^"'\\\s<>\]}]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.cleanEscaped().trimMediaUrl() }
            .filterNot { it.isNoiseUrl() }
            .forEach { url -> sources.putIfAbsent(url, DracinemaVideoSource(url, getQualityFromName(url).takeIf { q -> q != Qualities.Unknown.value })) }

        return sources.values.toList()
    }

    private fun extractMediaUrls(text: String): List<String> {
        val clean = text.cleanEscaped()
        val urls = linkedSetOf<String>()

        Regex("""https?://[^\"'\s<>)\]}]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.cleanEscaped().trimMediaUrl() }
            .filterNot { it.isNoiseUrl() }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun String.isDracinemaVideoUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("awscdn.netshort.com") ||
            lower.contains("mime_type=video_mp4") ||
            lower.contains("cdn.dramabos.video") ||
            lower.contains("flickreels/hls") ||
            lower.contains(".mp4") ||
            lower.contains(".m3u8") ||
            lower.contains(".webm")
    }

    private fun String.isHlsUrl(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") ||
            lower.contains("flickreels/hls") ||
            lower.matches(Regex(""".*/hls(?:\?.*)?$"""))
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-original") -> attr("abs:data-original")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("src") -> attr("abs:src")
            else -> null
        }
    }

    private fun extractEpisodeNumber(text: String, fallback: String): Int? {
        return Regex("""(?:episode|eps?|ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?:episode|eps?|ep)[-/]?(\d+)""", RegexOption.IGNORE_CASE).find(fallback)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""data-(?:episode|ep|id)=["']?(\d+)""", RegexOption.IGNORE_CASE).find(fallback)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractEpisodeCount(text: String): Int? {
        return Regex("""(\d+)\s*Episode""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.cleanEpisodeName(epNum: Int): String {
        val cleaned = replace(Regex("""^\s*\d+\s*[.:-]?\s*"""), "")
            .replace(Regex("""^\s*(episode|eps?|ep)\s*\d+\s*[.:-]?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return when {
            cleaned.isBlank() -> "Episode $epNum"
            cleaned.equals(epNum.toString(), true) -> "Episode $epNum"
            cleaned.equals("Tonton Sekarang", true) -> "Episode $epNum"
            cleaned.length <= 2 && cleaned.all { it.isDigit() } -> "Episode $epNum"
            else -> "Episode $epNum - $cleaned"
        }
    }

    private fun String.cleanEscaped(): String {
        return replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("\\u003f", "?")
            .replace("\\u003F", "?")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim()
    }

    private fun String.absoluteUrl(baseUrl: String): String? {
        val clean = trim()
        if (clean.isBlank() || clean.startsWith("javascript:", true) || clean.startsWith("data:", true)) return null
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull() ?: fixUrlNull(clean)
        }
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("google") ||
            lower.contains("facebook") ||
            lower.contains("doubleclick") ||
            lower.contains("core.mebilu.com/api/subtitle") ||
            lower.contains("mime_type=text_plain") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".css") ||
            lower.endsWith(".js")
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""\s+-\s+Dracinema.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Full Episode Subtitle Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Nonton\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.toUrlSlug(): String {
        return lowercase()
            .replace(Regex("""[^\p{L}\p{N}]+"""), "-")
            .trim('-')
    }

    private fun String.toCategorySlug(): String {
        return lowercase()
            .replace("+", "plus")
            .replace(Regex("""[^\p{L}\p{N}]+"""), "-")
            .trim('-')
    }

    private fun String.trimMediaUrl(): String {
        return trim()
            .trimEnd(',', ';', '.', ')', ']', '}', '"', '\'')
    }

    private fun String.videoKey(): String {
        return substringBefore("&Expires=")
            .substringBefore("?Expires=")
            .substringBefore("&Signature=")
            .substringBefore("?Signature=")
            .substringBefore("&sig=")
            .substringBefore("?sig=")
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optString(index).trim().takeIf { it.isNotBlank() }
        }
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        return selectFirst(
            "a[rel=next], a:contains(Muat Lebih Banyak), button:contains(Muat Lebih Banyak), " +
                "a[href*='page=${page + 1}'], a[href*='/page/${page + 1}']"
        ) != null
    }
}
