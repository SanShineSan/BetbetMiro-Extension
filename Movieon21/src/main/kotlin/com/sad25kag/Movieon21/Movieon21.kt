package com.sad25kag.Movieon21

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

@CloudstreamPlugin
class Movieon21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Movieon21())
    }
}

class Movieon21 : MainAPI() {
    override var mainUrl = "https://tv.movieon21.mov"
    override var name = "Movieon21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "" to "Film Upload Terbaru",
        "terpopuler/" to "Terpopuler",
        "trending/" to "Trending",
        "rating/" to "IMDb Rating",
        "terbaru/" to "Baru Diupload",

        "genre/action/" to "Action",
        "genre/adventure/" to "Adventure",
        "genre/animation/" to "Animation",
        "genre/biography/" to "Biography",
        "genre/comedy/" to "Comedy",
        "genre/crime/" to "Crime",
        "genre/documentary/" to "Documentary",
        "genre/drama/" to "Drama",
        "genre/family/" to "Family",
        "genre/fantasy/" to "Fantasy",
        "genre/film-noir/" to "Film-Noir",
        "genre/history/" to "History",
        "genre/horror/" to "Horror",
        "genre/music/" to "Music",
        "genre/mystery/" to "Mystery",
        "genre/romance/" to "Romance",
        "genre/sci-fi/" to "Sci-Fi",
        "genre/sport/" to "Sport",
        "genre/thriller/" to "Thriller",
        "genre/war/" to "War",
        "genre/western/" to "Western",

        "country/usa/" to "USA",
        "country/australia/" to "Australia",
        "country/china/" to "China",
        "country/france/" to "France",
        "country/germany/" to "Germany",
        "country/hong-kong/" to "Hong Kong",
        "country/indonesia/" to "Indonesia",
        "country/india/" to "India",
        "country/uk/" to "UK",
        "country/italy/" to "Italy",
        "country/japan/" to "Japan",
        "country/canada/" to "Canada",
        "country/korea/" to "Korea",
        "country/malaysia/" to "Malaysia",
        "country/mexico/" to "Mexico",
        "country/philippines/" to "Philippines",
        "country/russia/" to "Russia",
        "country/taiwan/" to "Taiwan",
        "country/thailand/" to "Thailand",

        "year/2026/" to "2026",
        "year/2025/" to "2025",
        "year/2024/" to "2024",
        "year/2023/" to "2023",
        "year/2022/" to "2022",
        "year/2021/" to "2021",
        "year/2020/" to "2020",
        "year/2019/" to "2019",
        "year/2018/" to "2018",
        "year/2017/" to "2017",
        "year/2016/" to "2016",
        "year/2015/" to "2015"
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
        val url = buildPageUrl(request.data, page)

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val items = parseCards(document)
            .distinctBy { it.url }

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
        val clean = path.trim('/')

        return when {
            clean.isBlank() && page <= 1 -> mainUrl
            clean.isBlank() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$clean/"
            else -> "$mainUrl/$clean/page/$page/"
        }
    }

    private fun hasNextPage(
        document: Document,
        page: Int
    ): Boolean {
        return document.selectFirst(
            "a.next, " +
                "a[rel=next], " +
                ".pagination a:contains(Next), " +
                ".nav-links a:contains(Next), " +
                ".page-numbers.next, " +
                "a[href*='/page/${page + 1}/']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a), " +
                ".post:has(a), " +
                ".ml-item:has(a), " +
                ".movie-item:has(a), " +
                ".result-item:has(a), " +
                ".items article:has(a), " +
                ".content article:has(a), " +
                ".grid article:has(a), " +
                ".box:has(a), " +
                ".item:has(a)"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        if (results.isEmpty()) {
            document.select(
                "h2 a[href], " +
                    "h3 a[href], " +
                    "a[href]:has(img)"
            ).forEach { element ->
                element.toSearchResult()?.let { item ->
                    results[item.url] = item
                }
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
                    "a[href]:contains(Tonton), " +
                    "a[href]:has(img), " +
                    "a[href]"
            ) ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")

        val title = listOf(
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst(".title")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Tonton", true) &&
                !it.equals("Nonton Movie", true) &&
                !it.equals("Trailer", true) &&
                !it.equals("Download", true) &&
                !it.equals("Home", true) &&
                !it.equals("Film Lainnya", true)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val poster = fixUrlNull(image?.getImageAttr())
            ?.takeIf { !isBadImage(it) }

        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ) {
            posterUrl = poster
            year = extractYear(title) ?: extractYear(text())
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()

        if (path.isBlank()) return true

        val blockedPrefixes = listOf(
            "genre/",
            "country/",
            "year/",
            "quality/",
            "resolusi/",
            "author/",
            "page/",
            "search",
            "dmca",
            "faq",
            "pasang-iklan",
            "wp-content/",
            "wp-json/",
            "wp-admin/",
            "tag/",
            "index-movie"
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
        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val results = parseCards(document)
            .distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = hasNextPage(document, page)
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val title = document.selectFirst("h1.entry-title, h1")
            ?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/")
                .replace("-", " ")
                .cleanTitle()

        val poster = getPoster(document)
        val text = document.text()
        val meta = parseMetadata(document)

        val plot = document.selectFirst(
            ".entry-content p, " +
                ".sinopsis p, " +
                ".desc p, " +
                ".description p, " +
                ".entry-content, " +
                ".sinopsis, " +
                ".desc, " +
                ".description"
        )?.text()
            ?.trim()
            ?.takeIf { it.length > 30 }
            ?: Regex("""(?s)$title\s+(.+?)\s+(?:Genre:|Kualitas:|Size:|Tahun:|Durasi:|Negara:)""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

        val year = meta["Tahun"]?.toIntOrNull()
            ?: extractYear(title)
            ?: extractYear(text)

        val duration = parseDuration(meta["Durasi"] ?: text)
        val rating = parseRating(text)

        val tags = document.select(
            "a[href*='/genre/'], " +
                "a[href*='/quality/'], " +
                "a[href*='/resolusi/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Trailer", true) }
            .distinct()

        val actors = document.select(
            "a[href*='/cast/'], " +
                "a[href*='/pemain/'], " +
                "a[href*='/actor/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { Actor(it) }

        val trailer = document.selectFirst(
            "a[href*='youtube.com'], " +
                "a[href*='youtu.be'], " +
                "iframe[src*='youtube.com'], " +
                "iframe[src*='youtu.be']"
        )?.let { element ->
            element.attr("href").ifBlank { element.attr("src") }
        }?.takeIf { it.isNotBlank() }

        val imdbId = Regex("""tt\d{6,10}""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value

        val recommendations = document.select(
            ".related article:has(a), " +
                ".film-terkait article:has(a), " +
                ".items article:has(a), " +
                ".content article:has(a)"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.score = Score.from10(rating)
            this.duration = duration ?: 0
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
            addImdbId(imdbId)
        }
    }

    private fun parseMetadata(document: Document): Map<String, String> {
        val output = linkedMapOf<String, String>()

        document.select("li, p, div, span").forEach { element ->
            val line = element.text()
                .replace(Regex("""\s+"""), " ")
                .trim()

            val parts = line.split(":", limit = 2)
            if (parts.size != 2) return@forEach

            val key = parts[0].trim()
            val value = parts[1].trim()

            if (
                key.matches(Regex("""(?i)(genre|kualitas|quality|size|tahun|durasi|negara|rilis|direksi|pemain)""")) &&
                value.isNotBlank() &&
                value.length < 500
            ) {
                output[key] = value
            }
        }

        return output
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

        collectCandidatesFromDocument(
            document = document,
            baseUrl = pageUrl,
            directLinks = directLinks,
            embedLinks = embedLinks
        )

        collectDooplayAjaxLinks(
            document = document,
            pageUrl = pageUrl,
            directLinks = directLinks,
            embedLinks = embedLinks
        )

        extractPlayableUrls(html).forEach { raw ->
            addCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()
            ?.cleanEscaped()
            ?.let { unpacked ->
                extractPlayableUrls(unpacked).forEach { raw ->
                    addCandidate(raw, pageUrl, directLinks, embedLinks)
                }
            }

        extractBase64Payloads(html).forEach { decoded ->
            parsePlayerResponse(
                text = decoded,
                baseUrl = pageUrl,
                directLinks = directLinks,
                embedLinks = embedLinks
            )
        }

        var found = false

        // 1. Prioritaskan Direct Links (.mp4, .m3u8 langsung)
        directLinks
            .filterNot { isAdUrl(it) }
            .distinct()
            .forEach { link ->
                emitDirectLink(
                    link = link,
                    referer = pageUrl,
                    callback = callback
                )
                found = true
            }

        if (found) return true

        // 2. Jika tidak ada direct link, proses Embed Links (Doodstream, dll)
        prioritizeEmbeds(embedLinks)
            .take(12)
            .forEach { embed ->
                // PERBAIKAN: Gunakan fungsi bawaan Cloudstream secara hati-hati
                val success = runCatching {
                    loadExtractor(
                        embed,
                        pageUrl,
                        subtitleCallback,
                        callback
                    )
                }.getOrDefault(false)

                if (success) {
                    found = true
                } else {
                    // PERBAIKAN: Fallback jika extractor Cloudstream tidak support, kita scrape secara kasar
                    resolveNestedLinks(embed, pageUrl).forEach { nested ->
                        val fixed = normalizeUrl(nested, embed).replace(".txt", ".m3u8")

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
                        }
                    }
                }
            }

        return found
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
                "meta[name=twitter:player], " +
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
                "[data-video], " +
                "[data-file], " +
                "[data-url], " +
                "[data-embed], " +
                "[data-iframe]"
        ).forEach { element ->
            val raw = element.attr("content")
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            val label = element.text().lowercase()

            if (
                raw.startsWith("#") ||
                raw.startsWith("javascript", true) ||
                raw.contains("youtube.com", true) ||
                raw.contains("youtu.be", true) ||
                label.contains("trailer")
            ) {
                return@forEach
            }

            if (
                element.tagName().equals("meta", true) ||
                element.tagName().equals("iframe", true) ||
                element.tagName().equals("embed", true) ||
                element.tagName().equals("object", true) ||
                element.tagName().equals("video", true) ||
                element.tagName().equals("source", true) ||
                isLikelyPlayable(raw) ||
                isLikelyPlayableText(label)
            ) {
                addCandidate(raw, baseUrl, directLinks, embedLinks)
            }
        }
    }

    private suspend fun collectDooplayAjaxLinks(
        document: Document,
        pageUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val options = document.select(
            "ul#playeroptionsul li[data-post][data-nume][data-type], " +
                ".dooplay_player_option[data-post][data-nume][data-type], " +
                ".player-option[data-post][data-nume][data-type], " +
                "li[data-post][data-nume][data-type], " +
                "div[data-post][data-nume][data-type], " +
                "span[data-post][data-nume][data-type]"
        ).distinctBy {
            "${it.attr("data-post")}|${it.attr("data-nume")}|${it.attr("data-type")}"
        }

        if (options.isEmpty()) return

        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

        options.forEach { option ->
            val post = option.attr("data-post").trim()
            val nume = option.attr("data-nume").trim()
            val type = option.attr("data-type").trim()

            if (post.isBlank() || nume.isBlank() || type.isBlank()) return@forEach
            if (nume.contains("trailer", true) || type.contains("trailer", true)) return@forEach

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
                        "Origin" to mainUrl,
                        "Referer" to pageUrl
                    ),
                    referer = pageUrl,
                    timeout = 20L
                ).text.cleanEscaped()
            }.getOrNull().orEmpty()

            if (ajaxText.isBlank()) return@forEach

            parsePlayerResponse(
                text = ajaxText,
                baseUrl = pageUrl,
                directLinks = directLinks,
                embedLinks = embedLinks
            )
        }
    }

    private fun parsePlayerResponse(
        text: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (text.isBlank()) return

        val candidates = linkedSetOf<String>()

        extractPlayableUrls(text).forEach { candidates.add(it) }

        Regex(
            """"(?:embed_url|embedUrl|url|iframe|src|file)"\s*:\s*"((?:\\.|[^"\\])*)"""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { match ->
            candidates.add(
                match.groupValues[1]
                    .replace("\\/", "/")
                    .replace("\\\"", "\"")
                    .cleanEscaped()
            )
        }

        val decoded = runCatching {
            URLDecoder.decode(text, "UTF-8")
        }.getOrDefault(text)

        if (decoded != text) {
            extractPlayableUrls(decoded).forEach { candidates.add(it) }
        }

        extractBase64Payloads(text).forEach { payload ->
            extractPlayableUrls(payload).forEach { candidates.add(it) }
            Jsoup.parse(payload).select(
                "iframe[src], iframe[data-src], source[src], video[src], embed[src], object[data], a[href]"
            ).forEach { element ->
                candidates.add(
                    element.attr("data-src")
                        .ifBlank { element.attr("src") }
                        .ifBlank { element.attr("data") }
                        .ifBlank { element.attr("href") }
                        .trim()
                )
            }
        }

        Jsoup.parse(text).select(
            "iframe[src], iframe[data-src], source[src], video[src], embed[src], object[data], a[href], [data-src], [data-video], [data-file], [data-url]"
        ).forEach { element ->
            candidates.add(
                element.attr("data-video")
                    .ifBlank { element.attr("data-file") }
                    .ifBlank { element.attr("data-url") }
                    .ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("data") }
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("href") }
                    .trim()
            )
        }

        candidates.forEach { raw ->
            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }
    }

    private fun extractBase64Payloads(text: String): List<String> {
        val results = linkedSetOf<String>()

        Regex(
            """(?:atob|base64Decode|Base64\.decode)\(\s*["']([A-Za-z0-9+/=]{24,})["']\s*\)""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { match ->
            runCatching {
                base64Decode(match.groupValues[1])
            }.getOrNull()
                ?.cleanEscaped()
                ?.takeIf { it.isNotBlank() }
                ?.let { results.add(it) }
        }

        Regex(
            """"(?:data|embed|embed_url|iframe|source|payload)"\s*:\s*"([A-Za-z0-9+/=]{32,})"""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { match ->
            runCatching {
                base64Decode(match.groupValues[1])
            }.getOrNull()
                ?.cleanEscaped()
                ?.takeIf { it.isNotBlank() }
                ?.let { results.add(it) }
        }

        return results.toList()
    }

    private fun prioritizeEmbeds(links: Collection<String>): List<String> {
        return links
            .filterNot { isAdUrl(it) }
            .distinct()
            .sortedWith(
                compareBy<String> { hostPriority(it) }
                    .thenBy { it.length }
            )
    }

    private fun hostPriority(url: String): Int {
        val value = url.lowercase()

        return when {
            value.contains("player") -> 0
            value.contains("embed") -> 1
            value.contains("streamwish") || value.contains("wishfast") -> 2
            value.contains("filemoon") -> 3
            value.contains("dood") -> 4
            value.contains("streamtape") -> 5
            value.contains("vidhide") -> 6
            value.contains("vidguard") -> 7
            value.contains("voe") -> 8
            value.contains("mixdrop") -> 9
            value.contains("mp4upload") -> 10
            value.contains("hglink") || value.contains("hgcloud") -> 11
            value.contains("drive.google") || value.contains("gdrive") -> 12
            value.contains("terabox") -> 13
            else -> 50
        }
    }

    // PERBAIKAN: Menambahkan logic Unpack JS dan decode Base64 jika iframe dikunci oleh host
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
        
        val nestedUrls = extractPlayableUrls(text).toMutableList()

        // Coba ekstrak file packed JS (contoh: eval(function(p,a,c,k,e,d)...)
        runCatching {
            if (!getPacked(text).isNullOrEmpty()) {
                val unpacked = getAndUnpack(text)
                nestedUrls.addAll(extractPlayableUrls(unpacked.cleanEscaped()))
            }
        }
        
        return nestedUrls.distinct()
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

        if (fixed.isBlank() || isAdUrl(fixed)) return

        when {
            isHlsLike(fixed) || fixed.contains(".mp4", true) || fixed.contains(".webm", true) -> directLinks.add(fixed)
            fixed.startsWith("http", true) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = link.cleanEscaped().replace(".txt", ".m3u8")
        if (fixed.isBlank() || isAdUrl(fixed)) return

        if (isHlsLike(fixed)) {
            generateM3u8(
                source = name,
                streamUrl = fixed,
                referer = referer
            ).forEach(callback)
            return
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = fixed,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(fixed).takeIf {
                    it != Qualities.Unknown.value
                } ?: qualityFromUrl(fixed)
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
            """(?:file|src|source|url|videoSource|videoUrl|video_url|playUrl|play_url|hls|hlsUrl|hls_url|embedUrl|embed_url|contentUrl|stream|streamUrl|stream_url)\s*[:=]\s*["']([^"']+)["']""",
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
            """(?:data-file|data-video|data-url|data-src|data-embed|data-iframe|content)=["']([^"']+)["']""",
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
            """https?://[^"'\\\s<>]+?(?:embed|player|stream|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|lulustream|lulu|hglink|hgcloud|acefile|krakenfiles|gdrive|drive\.google|ok\.ru|odnoklassniki|terabox|mega|sbembed|streamruby|turbovid)[^"'\\\s<>]*""",
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
            "hglink",
            "hgcloud",
            "acefile",
            "krakenfiles",
            "gdrive",
            "drive.google",
            "ok.ru",
            "odnoklassniki",
            "terabox",
            "mega.nz",
            "sbembed",
            "streamruby",
            "turbovid"
        ).any { value.contains(it) }
    }

    private fun isLikelyPlayable(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true) ||
            isKnownHost(url)
    }

    private fun isLikelyPlayableText(text: String): Boolean {
        return text.contains("download") ||
            text.contains("stream") ||
            text.contains("nonton") ||
            text.contains("watch") ||
            text.contains("server") ||
            text.contains("360p") ||
            text.contains("480p") ||
            text.contains("720p") ||
            text.contains("1080p")
    }

    private fun normalizeUrl(
        url: String,
        baseUrl: String
    ): String {
        val clean = url.cleanEscaped()

        return when {
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
            }.getOrDefault(clean)
        }
    }

    private fun getPoster(document: Document): String? {
        return fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
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
            ?: attr("src").takeIf { it.isNotBlank() }

        return raw
            ?.trim()
            ?.takeIf { !isBadImage(it) }
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

    private fun extractYear(text: String?): Int? {
        return Regex("""\b(19|20)\d{2}\b""")
            .find(text.orEmpty())
            ?.value
            ?.toIntOrNull()
    }

    private fun parseDuration(text: String?): Int? {
        return Regex("""(\d+)\s*(?:min|menit)""", RegexOption.IGNORE_CASE)
            .find(text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseRating(text: String): String? {
        return Regex("""(?:rata-rata\s*)?([0-9](?:\.[0-9])?|10(?:\.0)?)\s+dari\s+10""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""\b([0-9](?:\.[0-9])?|10(?:\.0)?)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
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
            value.contains("ads") ||
            value.contains("banner") ||
            value.contains("ratu89") ||
            value.contains("pasang-iklan")
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
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""^\s*Nonton\s+Film\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Streaming\s+Movie\s+Download.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Movieon21.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
