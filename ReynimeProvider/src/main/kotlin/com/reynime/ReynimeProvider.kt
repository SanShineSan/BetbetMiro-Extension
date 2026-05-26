package com.reynime

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.DubStatus
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
import com.lagradost.cloudstream3.fixUrlNull
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
import java.util.Base64

class ReynimeProvider : MainAPI() {
    override var mainUrl = "https://reynime.my.id"
    override var name = "Reynime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "latest" to "Update Terbaru",
        "series" to "Daftar Donghua",
        "popular" to "Populer",
        "added" to "Terbaru Ditambahkan",
        "status/ongoing" to "Ongoing",
        "status/completed" to "Completed",
        "type/movie" to "Movie",
        "type/ova" to "OVA",
        "genre/action" to "Action",
        "genre/adventure" to "Adventure",
        "genre/comedy" to "Comedy",
        "genre/drama" to "Drama",
        "genre/fantasy" to "Fantasy",
        "genre/martial-arts" to "Martial Arts",
        "genre/romance" to "Romance",
        "genre/mystery" to "Mystery",
        "genre/sci-fi" to "Sci-Fi",
        "genre/supernatural" to "Supernatural",
        "genre/thriller" to "Thriller",
        "genre/historical" to "Historical",
        "genre/isekai" to "Isekai",
        "genre/xianxia" to "Xianxia",
        "genre/xuanhuan" to "Xuanhuan",
        "genre/wuxia" to "Wuxia",
        "genre/donghua" to "Donghua"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private data class SeedSeries(
        val id: Int,
        val title: String,
        val slug: String,
        val status: String = "ongoing",
        val type: TvType = TvType.Anime,
        val genres: Set<String> = emptySet(),
        val latestEpisode: Int = 1
    )

    private val seedSeries = listOf(
        SeedSeries(1, "Battle Through the Heaven S5", "battle-through-the-heaven-s5", "ongoing", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "xuanhuan", "donghua"), 170),
        SeedSeries(4, "Perfect World", "perfect-world", "ongoing", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "xuanhuan", "donghua"), 200),
        SeedSeries(6, "Throne of Seal", "throne-of-seal", "ongoing", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "donghua"), 140),
        SeedSeries(29, "Tales of Herding Gods", "tales-of-herding-gods", "ongoing", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "xianxia", "donghua"), 40),
        SeedSeries(33, "Sword of Coming", "sword-of-coming", "completed", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "wuxia", "donghua"), 26),
        SeedSeries(64, "Throne of Ten Thousand Swords", "throne-of-ten-thousand-swords", "ongoing", TvType.Anime, setOf("action", "fantasy", "martial-arts", "wuxia", "donghua"), 30),
        SeedSeries(72, "Ascendants of the Nine Suns", "ascendants-of-the-nine-suns", "ongoing", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "donghua"), 20),
        SeedSeries(83, "Beyond Time's Gaze", "beyond-times-gaze", "ongoing", TvType.Anime, setOf("fantasy", "romance", "mystery", "supernatural", "donghua"), 16),
        SeedSeries(87, "Way of Choices", "way-of-choices", "completed", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "romance", "donghua"), 12),
        SeedSeries(119, "Shrouding the Heavens: The Imperial Path", "shrouding-the-heavens-the-imperial-path", "ongoing", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "xianxia", "donghua"), 20)
    )

    private fun seedUrl(seed: SeedSeries): String = "$mainUrl/series/${seed.id}/${seed.slug}"

    private fun placeholderPoster(title: String): String {
        val encoded = URLEncoder.encode(title.take(38), "UTF-8").replace("+", "%20")
        return "https://placehold.co/300x450/111827/FFFFFF.jpg?text=$encoded"
    }

    private fun SeedSeries.toSeedSearchResponse(): SearchResponse {
        return newAnimeSearchResponse(title, seedUrl(this), type) {
            posterUrl = placeholderPoster(title)
        }
    }

    private fun fallbackItemsFor(data: String): List<SearchResponse> {
        val clean = data.trim('/').lowercase()
        val slug = clean.substringAfterLast('/').trim()
        val filtered = when {
            clean == "popular" -> seedSeries.take(10)
            clean == "added" || clean == "latest" || clean == "series" || clean.isBlank() -> seedSeries
            clean.startsWith("status/") -> seedSeries.filter { it.status.equals(slug, true) }
            clean.startsWith("type/movie") -> seedSeries.filter { it.type == TvType.AnimeMovie }
            clean.startsWith("type/ova") -> seedSeries.filter { it.type == TvType.OVA }
            clean.startsWith("genre/") -> seedSeries.filter { slug in it.genres }
            else -> seedSeries
        }
        return (if (filtered.isNotEmpty()) filtered else seedSeries.take(8))
            .map { it.toSeedSearchResponse() }
    }

    private fun findSeedFromUrl(url: String): SeedSeries? {
        val value = url.lowercase()
        return seedSeries.firstOrNull { seed ->
            value.contains("/series/${seed.id}") || value.contains(seed.slug.lowercase())
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val seededItems = fallbackItemsFor(request.data)
        val candidates = buildPageCandidates(request.data, page)

        for (url in candidates) {
            val response = runCatching {
                app.get(url, headers = headers, timeout = 10L)
            }.getOrNull() ?: continue

            val document = response.document
            val items = parseCards(document)
                .ifEmpty { parseCards(Jsoup.parse(response.text.cleanEscaped())) }
                .distinctBy { it.url }

            if (items.isNotEmpty()) {
                val hasNext = document.selectFirst(
                    "a[rel=next], a.next, .pagination a:contains(Next), a[href*='page=${page + 1}'], button:contains(Load More)"
                ) != null || response.text.contains("page\":${page + 1}")

                return newHomePageResponse(request.name, items, hasNext = hasNext)
            }
        }
        return newHomePageResponse(request.name, seededItems.distinctBy { it.url }, hasNext = false)
    }

    private fun buildPageCandidates(data: String, page: Int): List<String> {
        val clean = data.trim('/').trim()
        val slug = clean.substringAfterLast('/').trim()

        fun paged(url: String): String {
            if (page <= 1) return url
            return if (url.contains("?")) "$url&page=$page" else "${url.trimEnd('/')}/page/$page"
        }

        val candidates = linkedSetOf<String>()

        when {
            clean == "latest" || clean.isBlank() -> {
                candidates.add(paged(mainUrl))
                candidates.add("$mainUrl/series?page=$page")
            }
            clean == "series" -> {
                candidates.add(paged("$mainUrl/series"))
                candidates.add("$mainUrl/api/series?page=$page")
            }
            clean == "popular" -> {
                candidates.add("$mainUrl/series?sort=popular&page=$page")
            }
            clean == "added" -> {
                candidates.add("$mainUrl/series?sort=latest&page=$page")
            }
            clean.startsWith("status/") -> {
                candidates.add("$mainUrl/series?status=$slug&page=$page")
            }
            clean.startsWith("type/") -> {
                candidates.add("$mainUrl/series?type=$slug&page=$page")
            }
            clean.startsWith("genre/") -> {
                candidates.add("$mainUrl/series?genre=$slug&page=$page")
            }
            else -> {
                candidates.add(paged("$mainUrl/$clean"))
            }
        }
        return candidates.toList()
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a):has(img), .card:has(a):has(img), .series-card:has(a):has(img), a[href*='/series/']:has(img)"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }

        parseNextDataCards(document.html()).forEach { results[it.url] = it }

        return results.values
            .filter { it.name.isNotBlank() && !isBadTitle(it.name) }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) this else selectFirst("a[href*='/series/'], a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl) || isBlockedCatalogUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val poster = fixUrlNull(image?.getImageAttr())

        val title = listOf(
            selectFirst("h1, h2, h3, .title, .name")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            href.substringAfterLast("/").replace("-", " ")
        ).firstOrNull { !it.isNullOrBlank() && !isBadTitle(it) }?.cleanTitle() ?: return null

        return newAnimeSearchResponse(title, href, getTypeFromText("$href ${text()} $title")) {
            posterUrl = poster
        }
    }

    private fun parseNextDataCards(text: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val clean = text.cleanEscaped()

        Regex("""\{[^{}]*?\}""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(clean)
            .forEach { blockMatch ->
                val block = blockMatch.value
                val slug = extractJsonString(block, "slug") ?: extractJsonString(block, "href") ?: return@forEach

                if (!slug.contains("/series/", true) && !slug.matches(Regex("""[A-Za-z0-9_-]+"""))) return@forEach

                val title = listOfNotNull(
                    extractJsonString(block, "title"),
                    extractJsonString(block, "name"),
                    slug.substringAfterLast("/").replace("-", " ")
                ).firstOrNull { it.isNotBlank() && !isBadTitle(it) }?.cleanTitle() ?: return@forEach

                val rawPoster = extractJsonString(block, "poster") ?: extractJsonString(block, "cover")
                val href = if (slug.startsWith("http", true)) slug else "$mainUrl/series/$slug"

                if (!href.startsWith(mainUrl) || isBlockedCatalogUrl(href)) return@forEach

                results[href] = newAnimeSearchResponse(title, href, getTypeFromText("$title $href")) {
                    posterUrl = rawPoster?.let { normalizeUrl(it, mainUrl) }
                }
            }
        return results.values.toList()
    }

    private fun extractJsonString(text: String, key: String): String? {
        return Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.replace("\\/", "/")?.replace("\\\"", "\"")?.trim()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val attempts = listOf("$mainUrl/search?q=$encoded", "$mainUrl/?s=$encoded")

        for (url in attempts) {
            val response = runCatching { app.get(url, headers = headers, timeout = 10L) }.getOrNull() ?: continue
            val fromHtml = parseCards(response.document)
            val fromRaw = parseNextDataCards(response.text.cleanEscaped())
            val results = (fromHtml + fromRaw).distinctBy { it.url }
            if (results.isNotEmpty()) return results
        }

        return seedSeries
            .filter { seed -> seed.title.contains(keyword, true) || seed.slug.contains(keyword.replace(" ", "-"), true) }
            .ifEmpty { seedSeries.take(6) }
            .map { it.toSeedSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = runCatching { app.get(url, headers = headers, timeout = 15L) }.getOrNull() ?: return fallbackLoad(url)
        val document = response.document
        val html = response.text.cleanEscaped()

        val title = document.selectFirst("h1, .text-2xl, .text-3xl, .font-bold")?.text()?.cleanTitle()
            ?: url.substringAfterLast("/").replace("-", " ").cleanTitle()

        val poster = document.selectFirst("img.h-full, img.w-full, .poster img, .cover img")?.getImageAttr()?.let { normalizeUrl(it, url) }
        val description = document.selectFirst(".synopsis, .description, article p")?.text()?.cleanTitle()
        val tags = document.select(".genre a, .genres a, .tags a").map { it.text().trim() }.distinct()
        val actors = document.select(".studio a, .producer a").map { Actor(it.text().trim()) }.distinctBy { it.name }
        
        val episodes = parseEpisodes(document, html, url, poster)

        return newAnimeLoadResponse(title, url, getTypeFromText("$title ${tags.joinToString(" ")} $url")) {
            engName = title
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = description
            this.tags = tags
            this.episodes = hashMapOf(DubStatus.Subbed to episodes)
            addActors(actors)
        }
    }

    private suspend fun fallbackLoad(url: String): LoadResponse {
        val seed = findSeedFromUrl(url)
        val title = seed?.title ?: url.substringAfterLast("/").replace("-", " ").cleanTitle().ifBlank { name }
        val poster = placeholderPoster(title)
        val baseUrl = seed?.let { seedUrl(it) } ?: url.substringBefore("#")
        val episodes = (1..12).map { ep -> newEpisode("$baseUrl#episode-$ep") { name = "Episode $ep"; episode = ep; posterUrl = poster } }

        return newAnimeLoadResponse(title, baseUrl, seed?.type ?: TvType.Anime) {
            engName = title
            posterUrl = poster
            plot = "Streaming Donghua subtitle Indonesia di Reynime."
            tags = seed?.genres?.map { it.replace("-", " ").cleanTitle() } ?: listOf("Donghua")
            this.episodes = hashMapOf(DubStatus.Subbed to episodes)
        }
    }

    private fun parseEpisodes(document: Document, html: String, pageUrl: String, poster: String?): List<Episode> {
        val results = linkedMapOf<String, Episode>()

        // 1. Ambil dari tombol/link episode
        document.select("a[href*='/episode'], a[href*='/watch'], button[data-url]").forEachIndexed { index, element ->
            val href = fixUrlNull(element.attr("href").ifBlank { element.attr("data-url") }) ?: return@forEachIndexed
            if (!href.startsWith(mainUrl) || href == pageUrl) return@forEachIndexed

            val text = element.text().trim()
            val epNumber = extractEpisodeNumber(text, href) ?: (index + 1)

            results[href] = newEpisode(href) {
                name = text.cleanTitle().ifBlank { "Episode $epNumber" }
                episode = epNumber
                posterUrl = poster
            }
        }

        // 2. Ambil dari text biasa (Client-side rendering SPA)
        Regex("""\bEP\s*(\d{1,4})\b|\bEpisode\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
            .findAll(document.text())
            .mapNotNull { match -> match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull() }
            .distinct().sorted().forEach { epNumber ->
                val epUrl = "$pageUrl#episode-$epNumber"
                if (!results.containsKey(epUrl)) {
                    results[epUrl] = newEpisode(epUrl) { name = "Episode $epNumber"; episode = epNumber; posterUrl = poster }
                }
            }

        return results.values.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.substringBefore("#")
        val response = runCatching { app.get(pageUrl, headers = headers, referer = mainUrl, timeout = 15L) }.getOrNull() ?: return false
        
        val document = response.document
        val html = response.text.cleanEscaped()
        var isLinkFound = false

        // STEP 1: Coba tangkap iframe yang tertanam (Cara paling cepat & efisien)
        document.select("iframe[src], iframe[data-src]").forEach {
            val src = it.attr("src").ifBlank { it.attr("data-src") }
            if (src.isNotBlank() && src.startsWith("http")) {
                if (loadExtractor(src, pageUrl, subtitleCallback, callback)) isLinkFound = true
            }
        }

        // STEP 2: Coba cari data Base64 pada elemen HTML (Sering dipakai streaming site)
        document.select("[data-url], [data-file], [data-video]").forEach { el ->
            val attr = el.attr("data-url").ifBlank { el.attr("data-file") }.ifBlank { el.attr("data-video") }
            if (attr.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
                val decoded = runCatching { String(Base64.getDecoder().decode(attr)) }.getOrNull()
                if (decoded != null && decoded.startsWith("http")) {
                    if (loadExtractor(decoded, pageUrl, subtitleCallback, callback)) isLinkFound = true
                }
            }
        }

        // STEP 3: Fallback ke Regex bawaanmu jika gagal di atas
        if (!isLinkFound) {
            val urls = extractPlayableUrls(html)
            urls.forEach { url ->
                if (isDirectVideo(url)) {
                    emitDirectLink(url, pageUrl, callback)
                    isLinkFound = true
                } else if (isLikelyEmbed(url)) {
                    if (loadExtractor(url, pageUrl, subtitleCallback, callback)) isLinkFound = true
                }
            }
        }

        extractSubtitles(html, pageUrl, subtitleCallback)
        return isLinkFound
    }

    private suspend fun emitDirectLink(link: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (isBadMediaUrl(link)) return false

        if (isHlsLike(link)) {
            val isGenerated = runCatching {
                generateM3u8(name, link, referer, mapOf("User-Agent" to USER_AGENT, "Referer" to referer)).forEach(callback)
                true
            }.getOrDefault(false)
            if (isGenerated) return true
        }

        callback(
            newExtractorLink(name, name, link, if (isHlsLike(link)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = qualityFromUrl(link).takeIf { it != Qualities.Unknown.value } ?: Qualities.P720.value
            }
        )
        return true
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex("""https?://[^"'\s<>]+?(?:embed|player|stream|watch|video|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload)[^"'\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(clean).map { it.value }.filterNot { isBadMediaUrl(it) }.forEach { urls.add(it) }
            
        Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4|webm)(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean).map { it.value }.filterNot { isBadMediaUrl(it) }.forEach { urls.add(it) }

        return urls.toList()
    }

    private suspend fun extractSubtitles(text: String, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        Regex(""""(?:label|lang)"\s*:\s*"([^"]+)"[^{}]{0,100}"(?:file|url)"\s*:\s*"([^"]+\.(?:vtt|srt|ass)[^"]*)""", RegexOption.IGNORE_CASE)
            .findAll(text).forEach {
                val label = it.groupValues[1]
                val url = normalizeUrl(it.groupValues[2], baseUrl)
                subtitleCallback(newSubtitleFile(if (label.contains("id", true)) "Indonesian" else label, url))
            }
    }

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped().trim()
        return when {
            clean.isBlank() -> ""
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> Regex("""^https?://[^/]+""").find(baseUrl)?.value?.let { "$it$clean" } ?: "$mainUrl$clean"
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
    }

    private fun isDirectVideo(url: String) = isHlsLike(url) || url.contains(".mp4", true) || url.contains(".webm", true)
    private fun isHlsLike(url: String) = url.contains(".m3u8", true)
    private fun isLikelyEmbed(url: String) = url.contains("embed") || url.contains("filemoon") || url.contains("streamwish") || url.contains("dood") || url.contains("vidhide")

    private fun isBadMediaUrl(url: String) = url.contains("doubleclick") || url.contains("googlesyndication") || url.contains("adsterra") || url.contains("popads")

    private fun isBlockedCatalogUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.trim('/').lowercase() }.getOrDefault(url.lowercase())
        return path.isBlank() || path.startsWith("genre") || path.startsWith("tag") || path.startsWith("login") || path.startsWith("api")
    }

    private fun getTypeFromText(text: String) = if (text.contains("movie", true)) TvType.AnimeMovie else TvType.Anime

    private fun extractEpisodeNumber(text: String, href: String) = Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
        .find("$text $href")?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun qualityFromUrl(url: String) = when {
        url.contains("1080") -> Qualities.P1080.value
        url.contains("720") -> Qualities.P720.value
        url.contains("480") -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    private fun Element.getImageAttr() = attr("data-srcset").substringBefore(" ").takeIf { it.isNotBlank() } ?: attr("abs:src")

    private fun isBadTitle(title: String) = title.lowercase().trim().let { it.isBlank() || it == "home" || it == "search" }
    private fun String.cleanEscaped() = replace("\\/", "/").replace("\\u0026", "&").trim()
    private fun String.cleanTitle() = replace("\\\"", "\"").replace(Regex("""\s+[-|]\s+Reynime\s*$""", RegexOption.IGNORE_CASE), "").trim()
}
