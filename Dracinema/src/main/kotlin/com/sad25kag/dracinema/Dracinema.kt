package com.sad25kag.dracinema

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith("api:", true)) {
            val category = request.data.removePrefix("api:")
            val items = getApiMovies(category, page)
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            return newHomePageResponse(
                HomePageList(request.name, items),
                hasNext = items.isNotEmpty()
            )
        }

        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = commonHeaders, referer = mainUrl).document

        if (request.data.isBlank() && page <= 1) {
            val rows = parseHomeRows(document)
            if (rows.isNotEmpty()) return newHomePageResponse(rows)
        }

        val list = parseCards(document).distinctBy { it.url }
        val hasNext = document.selectFirst(
            "a[rel=next], a:contains(Muat Lebih Banyak), button:contains(Muat Lebih Banyak), " +
                "a[href*='page=${page + 1}'], a[href*='/page/${page + 1}']"
        ) != null

        return newHomePageResponse(request.name, list, hasNext = hasNext)
    }

    private suspend fun getApiMovies(category: String, page: Int): List<DracinemaMovie> {
        val encoded = URLEncoder.encode(category, "UTF-8")
        return runCatching {
            app.get(
                "$mainUrl/api/movie?page=${page.coerceAtLeast(1)}&categories=$encoded",
                headers = apiHeaders,
                referer = "$mainUrl/collections"
            ).parsedSafe<List<DracinemaMovie>>()
        }.getOrNull().orEmpty()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim('/')
        return when {
            cleanPath.isBlank() && page <= 1 -> mainUrl
            cleanPath.isBlank() -> "$mainUrl?page=$page"
            page <= 1 -> "$mainUrl/$cleanPath"
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

    private fun DracinemaMovie.toSearchResult(): SearchResponse? {
        val id = originalBookId.ifBlank { bookId }.takeIf { it.isNotBlank() } ?: return null
        val title = bookName.cleanTitle().takeIf { it.isNotBlank() } ?: return null
        val slug = replacedBookName.ifBlank { bookName }.toSlugSegment().takeIf { it.isNotBlank() } ?: return null
        val href = "$mainUrl/movie/${slug.encodePath()}-$id"

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            posterUrl = cover.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val apiResults = runCatching {
            app.get("$mainUrl/api/search?keyword=$encoded", headers = apiHeaders, referer = mainUrl)
                .parsedSafe<DracinemaSearchResponse>()
                ?.data
                .orEmpty()
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        }.getOrNull().orEmpty()

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

            if (exactResults.isNotEmpty()) return@forEach
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
            "a[href*='/play/'], a[href*='episode'], a[href*='eps'], a[href*='watch'], " +
                "button[data-url], button[data-src], button[data-href], button[data-episode], " +
                "[class*=episode] a[href], [class*=eps] a[href], [class*=episode][data-url], " +
                "[data-episode], [data-episode-id], [data-ep], [data-id]"
        )

        candidates.forEachIndexed { index, element ->
            val text = element.text().trim()
            val raw = listOf(
                element.attr("href"),
                element.attr("data-url"),
                element.attr("data-src"),
                element.attr("data-href"),
                element.attr("data-video"),
                element.attr("data-file")
            ).firstOrNull { it.isNotBlank() }.orEmpty()

            if (raw.contains("/movie/", true)) return@forEach

            val epNum = extractEpisodeNumber(text, element.outerHtml(), raw) ?: (index + 1)
            val fixed = when {
                raw.isBlank() || raw == "#" || raw.startsWith("javascript", true) -> "$fallbackUrl?episode=$epNum"
                else -> raw.absoluteUrl(fallbackUrl) ?: "$fallbackUrl?episode=$epNum"
            }

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

        runCatching {
            app.get(playUrl, headers = playerHeaders + mapOf("Referer" to mainUrl), referer = mainUrl).text
        }.getOrNull()?.let(pageTexts::add)

        val movieKey = playUrl.substringAfter("/play/", "").substringBefore("?").trim('/')
        val basePlayPath = "/play/${movieKey.substringBefore("/")}"
        val rscHeaders = playerHeaders + mapOf(
            "Accept" to "*/*",
            "RSC" to "1",
            "Next-Url" to basePlayPath,
            "Next-Router-State-Tree" to buildNextRouterStateTree(movieKey.substringBefore("/"))
        )

        if (pageTexts.none { it.containsVideoSourceMarker() }) {
            listOf(
                "$playUrl?_rsc=${rscToken(movieKey)}",
                "${playUrl.substringBefore("?").substringBeforeLast("/").takeIf { movieKey.contains("/") } ?: playUrl}?_rsc=${rscToken(movieKey + "base")}"
            ).distinct().forEach { rscUrl ->
                runCatching {
                    app.get(rscUrl, headers = rscHeaders, referer = playUrl).text
                }.getOrNull()?.let(pageTexts::add)
            }
        }

        pageTexts.flatMap { extractDracinemaVideoSources(it) }
            .distinctBy { it.url.stableVideoKey() }
            .forEach { source ->
                val key = source.url.stableVideoKey()
                if (!emitted.add(key)) return@forEach

                if (source.url.isHlsUrl()) {
                    generateM3u8(name, source.url, playUrl).forEach {
                        callback(it)
                        found = true
                    }
                } else {
                    callback(
                        newExtractorLink(name, "Dracinema ${source.quality ?: Qualities.Unknown.value}p", source.url, ExtractorLinkType.VIDEO) {
                            referer = mainUrl
                            quality = source.quality ?: Qualities.P720.value
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "*/*",
                                "Range" to "bytes=0-"
                            )
                        }
                    )
                    found = true
                }
            }

        if (found) return true

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        fun addCandidate(raw: String?, base: String = playUrl) {
            val fixed = raw?.cleanEscaped()?.absoluteUrl(base) ?: return
            if (fixed.isNoiseUrl()) return
            when {
                fixed.isVideoUrl() -> directLinks.add(fixed)
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
            val key = link.stableVideoKey()
            if (!emitted.add(key)) return@forEach
            if (link.isHlsUrl()) {
                generateM3u8(name, link, playUrl).forEach {
                    callback(it)
                    found = true
                }
            } else {
                callback(
                    newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                        referer = mainUrl
                        quality = getQualityFromName(link).takeIf { it != Qualities.Unknown.value } ?: Qualities.Unknown.value
                        headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "*/*", "Range" to "bytes=0-")
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

    private data class DracinemaSearchResponse(
        @JsonProperty("data") val data: List<DracinemaMovie> = emptyList()
    )

    private data class DracinemaMovie(
        @JsonProperty("bookId") val bookId: String = "",
        @JsonProperty("originalBookId") val originalBookId: String = "",
        @JsonProperty("bookName") val bookName: String = "",
        @JsonProperty("cover") val cover: String = "",
        @JsonProperty("introduction") val introduction: String = "",
        @JsonProperty("chapterCount") val chapterCount: Int? = null,
        @JsonProperty("replacedBookName") val replacedBookName: String = ""
    )

    private data class DracinemaVideoSource(val url: String, val quality: Int?)

    private fun String.toPlayUrl(targetEpisode: Int?): String {
        val clean = substringBefore("?episode=").trimEnd('/')
        if (clean.contains("/play/", true)) return clean
        val movieKey = clean.substringAfter("/movie/", "").substringBefore("?").trim('/').takeIf { it.isNotBlank() }
            ?: return clean
        val base = "$mainUrl/play/$movieKey"
        return if (targetEpisode != null && targetEpisode > 1) "$base/$targetEpisode" else base
    }

    private fun extractDracinemaVideoSources(text: String): List<DracinemaVideoSource> {
        val clean = text.cleanEscaped()
        val sources = linkedMapOf<String, DracinemaVideoSource>()

        Regex("""["\\]?quality["\\]?\s*:\s*(\d+)[\s\S]{0,140}?["\\]?url["\\]?\s*:\s*["\\](https?://[^"\\]+)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .forEach { match ->
                val quality = match.groupValues.getOrNull(1)?.toIntOrNull()
                val url = match.groupValues.getOrNull(2)?.cleanEscaped()?.takeIf { it.isVideoUrl() } ?: return@forEach
                sources[url] = DracinemaVideoSource(url, quality)
            }

        Regex("""["\\]?url["\\]?\s*:\s*["\\](https?://[^"\\]+)[\s\S]{0,140}?["\\]?quality["\\]?\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .forEach { match ->
                val url = match.groupValues.getOrNull(1)?.cleanEscaped()?.takeIf { it.isVideoUrl() } ?: return@forEach
                val quality = match.groupValues.getOrNull(2)?.toIntOrNull()
                sources.putIfAbsent(url, DracinemaVideoSource(url, quality))
            }

        Regex("""https?://[^"'\\\s<>\]}]+?(?:\.mp4|\.m3u8|\.webm|/hls|/api/[^"'\\\s<>\]}]*hls)[^"'\\\s<>\]}]*""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.cleanEscaped() }
            .filter { it.isVideoUrl() }
            .forEach { url -> sources.putIfAbsent(url, DracinemaVideoSource(url, getQualityFromName(url).takeIf { q -> q != Qualities.Unknown.value })) }

        return sources.values.toList()
    }

    private fun extractMediaUrls(text: String): List<String> {
        val clean = text.cleanEscaped()
        val urls = linkedSetOf<String>()

        Regex("""https?://[^\"'\s<>)\]}]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.cleanEscaped().trimEnd('.', ',', ';') }
            .filterNot { it.isNoiseUrl() }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun String.containsVideoSourceMarker(): Boolean {
        val lower = lowercase()
        return lower.contains("videourls") ||
            lower.contains("awscdn.netshort.com") ||
            lower.contains("cdn.dramabos.video") ||
            lower.contains("mime_type=video_mp4") ||
            lower.contains(".m3u8") ||
            lower.contains(".mp4")
    }

    private fun String.isVideoUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("awscdn.netshort.com") ||
            lower.contains("cdn.dramabos.video") ||
            lower.contains("flickreels/hls") ||
            lower.contains("mime_type=video_mp4") ||
            lower.contains(".mp4") ||
            lower.contains(".m3u8") ||
            lower.contains(".webm")
    }

    private fun String.isHlsUrl(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") ||
            lower.contains("/hls") ||
            lower.contains("flickreels/hls") ||
            lower.contains("application/vnd.apple.mpegurl")
    }

    private fun String.stableVideoKey(): String {
        return substringBefore("&Expires=")
            .substringBefore("?Expires=")
            .substringBefore("&token=")
            .substringBefore("?token=")
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

    private fun extractEpisodeNumber(text: String, fallback: String, rawUrl: String = ""): Int? {
        return Regex("""(?:episode|eps?|ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?:episode|eps?|ep)[-/]?(\d+)""", RegexOption.IGNORE_CASE).find(fallback)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""data-(?:episode|ep|id)=["']?(\d+)""", RegexOption.IGNORE_CASE).find(fallback)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""/play/[^/?#]+/(\d+)""", RegexOption.IGNORE_CASE).find(rawUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractEpisodeCount(text: String): Int? {
        return Regex("""(?:Episodes?|Episode)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(\d+)\s*Episode""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex(""""totalEps"\s*:\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex(""""chapterCount"\s*:\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
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
        return replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("\\u003f", "?")
            .replace("\\u003F", "?")
            .replace("\\u003a", ":")
            .replace("\\u003A", ":")
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
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
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

    private fun String.toSlugSegment(): String {
        return cleanTitle()
            .replace("/", "-")
            .replace(Regex("""\s+"""), "-")
            .replace(Regex("""-+"""), "-")
            .trim('-')
    }

    private fun String.encodePath(): String {
        return split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
    }

    private fun buildNextRouterStateTree(movieKey: String): String {
        val tree = """["",{"children":["(other)",{"children":["play",{"children":[["movieKey","$movieKey","d"],{"children":["__PAGE__",{},null,null]},null,null,true]}},null,null]},null,null,true]"""
        return URLEncoder.encode(tree, "UTF-8")
    }

    private fun rscToken(seed: String): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val raw = seed.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        return buildString {
            var value = raw
            repeat(5) {
                append(alphabet[value % alphabet.length])
                value = value / alphabet.length + 7
            }
        }
    }
}
