package com.sad25kag.Indo18

import com.lagradost.cloudstream3.Actor
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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

@CloudstreamPlugin
class Indo18Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Indo18())
    }
}

class Indo18 : MainAPI() {
    override var mainUrl = "https://www.indo18.com"
    override var name = "Indo18"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "" to "Newest",
        "?filter=latest" to "Latest",
        "?filter=popular" to "Best",
        "?filter=most-viewed" to "Most Viewed",
        "?filter=longest" to "Longest",
        "?filter=random" to "Random",

        "category/artis/" to "Artis",
        "category/babes/" to "Babes",
        "category/bang/" to "Bang",
        "category/bang-pov/" to "Bang POV",
        "category/bispak/" to "Bispak",
        "category/cam/" to "CAM",
        "category/janda/" to "Janda",
        "category/jilbab/" to "Jilbab",
        "category/live-show/" to "Live Show",
        "category/mahasiswi/" to "Mahasiswi",
        "category/masturbasi/" to "Masturbasi",
        "category/ngintip/" to "Ngintip",
        "category/pembantu/" to "Pembantu",
        "category/pns/" to "PNS",
        "category/scandal/" to "Scandal"
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
            timeout = 25L
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
        val clean = path.trim()

        return when {
            page <= 1 && clean.isBlank() -> mainUrl
            page <= 1 && clean.startsWith("?") -> "$mainUrl/$clean"
            page <= 1 -> "$mainUrl/${clean.trim('/')}"

            clean.isBlank() -> "$mainUrl/page/$page"
            clean.startsWith("?") -> "$mainUrl/page/$page$clean"

            clean.contains("?") -> {
                val base = clean.substringBefore("?").trim('/')
                val query = clean.substringAfter("?")
                "$mainUrl/$base/page/$page?$query"
            }

            else -> "$mainUrl/${clean.trim('/')}/page/$page"
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
                ".page-numbers.next, " +
                "a[href*='/page/${page + 1}'], " +
                "a[href*='page=${page + 1}']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a), " +
                ".post:has(a), " +
                ".item:has(a), " +
                ".video:has(a), " +
                ".video-item:has(a), " +
                ".content article:has(a), " +
                ".grid article:has(a), " +
                ".card:has(a), " +
                "h2:has(a), " +
                "h3:has(a), " +
                "main a[href]"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        if (results.isEmpty()) {
            document.select(
                "a[href]:has(img), " +
                    "h2 a[href], " +
                    "h3 a[href], " +
                    "main a[href], " +
                    ".content a[href]"
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
                    "a[href]:has(img), " +
                    "a[href]"
            ) ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")

        val title = listOf(
            selectFirst("h1")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst(".title")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Newest", true) &&
                !it.equals("Best", true) &&
                !it.equals("Most viewed", true) &&
                !it.equals("Longest", true) &&
                !it.equals("Random", true) &&
                !it.equals("Download complete video now!", true) &&
                !it.equals("Share", true) &&
                !it.equals("Home", true) &&
                !it.equals("Categories", true) &&
                !it.equals("Actors", true) &&
                !it.equals("Tags", true) &&
                !it.equals("Next", true) &&
                !it.equals("Last", true)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null
        if (isUnsafeTitle(title)) return null

        val poster = fixUrlNull(image?.getImageAttr())
            ?.takeIf { !isBadImage(it) }

        val score = Regex("""(\d{1,3})%""")
            .find(text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.div(10.0)
            ?.toString()

        return newMovieSearchResponse(
            title,
            href,
            TvType.NSFW
        ) {
            posterUrl = poster
            this.score = Score.from10(score)
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()

        if (path.isBlank()) return true

        val blockedPrefixes = listOf(
            "category/",
            "categories",
            "actors",
            "actor/",
            "tags",
            "tag/",
            "page/",
            "search",
            "content-removal",
            "privacy",
            "dmca",
            "contact",
            "wp-content",
            "wp-json",
            "wp-admin",
            "feed",
            "login",
            "register",
            "reset-password"
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
            if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page?s=$encoded",
            if (page <= 1) "$mainUrl/search/$encoded/" else "$mainUrl/search/$encoded/page/$page/",
            if (page <= 1) "$mainUrl/?search=$encoded" else "$mainUrl/page/$page?search=$encoded"
        )

        var bestResults: List<SearchResponse> = emptyList()
        var hasNext = false

        for (url in attempts) {
            val document = runCatching {
                app.get(
                    url,
                    headers = headers,
                    timeout = 25L
                ).document
            }.getOrNull() ?: continue

            val results = parseCards(document)
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
        val document = app.get(
            url,
            headers = headers,
            timeout = 25L
        ).document

        val title = document.selectFirst("h1, h1.entry-title")
            ?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/")
                .replace("-", " ")
                .cleanTitle()

        val poster = getPoster(document)
        val text = document.text()

        val views = Regex("""(?i)(?:views?|ditonton)\s*:?\s*([\d.,kmb]+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)

        val rating = Regex("""(\d{1,3})%""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.div(10.0)
            ?.toString()

        val tags = document.select(
            "a[href*='/category/'], " +
                "a[href*='/tag/']"
        ).map { it.text().trim() }
            .filter {
                it.isNotBlank() &&
                    !it.equals("Home", true) &&
                    !it.equals("Categories", true) &&
                    !isUnsafeTitle(it)
            }
            .distinct()

        val related = document.select(
            "article:has(a), " +
                ".related a[href], " +
                "h2 a[href], " +
                "h3 a[href], " +
                "main a[href]"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(
            title,
            url,
            TvType.NSFW,
            url
        ) {
            posterUrl = poster
            plot = views?.let { "Views: $it" }
            this.tags = tags
            this.score = Score.from10(rating)
            recommendations = related
            addActors(emptyList<Actor>())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(
            data,
            headers = headers,
            referer = mainUrl,
            timeout = 20L
        )

        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        document.select(
            "video[src], " +
                "video[data-src], " +
                "video[data-video], " +
                "video[poster], " +
                "video source[src], " +
                "source[src], " +
                "source[data-src], " +
                "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src], " +
                "iframe[data-lazy-src], " +
                "iframe[data-original], " +
                "embed[src], " +
                "object[data], " +
                "a[href], " +
                "[data-src], " +
                "[data-video], " +
                "[data-file], " +
                "[data-url], " +
                "[data-embed], " +
                "[data-iframe]"
        ).forEach { element ->
            val href = element.attr("href")
            val raw = element.attr("data-litespeed-src")
                .ifBlank { element.attr("data-lazy-src") }
                .ifBlank { element.attr("data-original") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { href }
                .trim()

            val label = element.text().lowercase()

            if (
                raw.startsWith("#") ||
                raw.startsWith("javascript", true) ||
                raw.contains("facebook.com", true) ||
                raw.contains("twitter.com", true) ||
                raw.contains("telegram", true) ||
                raw.contains("whatsapp", true) ||
                raw.contains("mailto:", true) ||
                raw.contains("jomblo.org", true) ||
                label.contains("content removal") ||
                label.contains("share") ||
                label.contains("copy the link") ||
                label.contains("download complete video now")
            ) {
                return@forEach
            }

            if (
                element.tagName().equals("video", true) ||
                element.tagName().equals("source", true) ||
                element.tagName().equals("iframe", true) ||
                element.tagName().equals("embed", true) ||
                element.tagName().equals("object", true) ||
                isLikelyPlayable(raw) ||
                isLikelyPlayableText(label)
            ) {
                addCandidate(raw, data, directLinks, embedLinks)
            }
        }

        extractPlayableUrls(html).forEach { raw ->
            addCandidate(raw, data, directLinks, embedLinks)
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach { raw ->
                addCandidate(raw, data, directLinks, embedLinks)
            }
        }

        val decodedOnce = runCatching {
            URLDecoder.decode(html, "UTF-8")
        }.getOrDefault(html)

        if (decodedOnce != html) {
            extractPlayableUrls(decodedOnce.cleanEscaped()).forEach { raw ->
                addCandidate(raw, data, directLinks, embedLinks)
            }
        }

        directLinks
            .filterNot { isAdUrl(it) }
            .distinct()
            .sortedWith(
                compareBy<String> { if (isHlsLike(it)) 0 else 1 }
                    .thenBy { hostPriority(it) }
            )
            .forEach { link ->
                emitDirectLink(
                    link = link,
                    referer = data,
                    callback = callback
                )
            }

        if (directLinks.isNotEmpty()) return true

        prioritizeEmbeds(embedLinks)
            .take(10)
            .forEach { embed ->
                val success = loadExtractor(
                    embed,
                    data,
                    subtitleCallback,
                    callback
                )

                if (success) return true

                resolveNestedLinks(embed, data).forEach { nested ->
                    val fixed = normalizeUrl(nested, embed)
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
                            return true
                        }

                        fixed.startsWith("http", true) &&
                            !shouldSkipUrl(fixed) -> {
                            val nestedSuccess = loadExtractor(
                                fixed,
                                embed,
                                subtitleCallback,
                                callback
                            )

                            if (nestedSuccess) return true
                        }
                    }
                }
            }

        return false
    }

    private suspend fun resolveNestedLinks(
        url: String,
        referer: String
    ): List<String> {
        if (shouldSkipUrl(url)) return emptyList()

        val text = runCatching {
            app.get(
                url,
                headers = headers,
                referer = referer,
                timeout = 12L
            ).text.cleanEscaped()
        }.getOrNull().orEmpty()

        if (text.isBlank()) return emptyList()

        val unpacked = runCatching {
            if (!getPacked(text).isNullOrEmpty()) getAndUnpack(text) else null
        }.getOrNull()

        return buildList {
            addAll(extractPlayableUrls(text))
            if (!unpacked.isNullOrBlank()) {
                addAll(extractPlayableUrls(unpacked.cleanEscaped()))
            }
        }
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
            isHlsLike(fixed) ||
                fixed.contains(".mp4", true) ||
                fixed.contains(".webm", true) -> directLinks.add(fixed)

            fixed.startsWith("http", true) &&
                isKnownHost(fixed) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("embed", true) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("player", true) -> embedLinks.add(fixed)
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
            .filterNot { shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
            .filterNot { isAdUrl(it) }
            .filterNot { shouldSkipUrl(it) }
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
            .filterNot { shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoSource|videoUrl|video_url|playUrl|play_url|hls|hlsUrl|hls_url|embedUrl|embed_url|contentUrl)\s*[:=]\s*["']([^"']+)["']""",
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
            .filterNot { shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:data-file|data-video|data-url|data-src|data-embed|data-iframe)=["']([^"']+)["']""",
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
            .filterNot { shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:embed|player|stream|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|lulustream|lulu|hglink|hgcloud|majorplay|jeniusplay|pornhub|xvideos|xhamster|redtube|spankbang)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isAdUrl(it) }
            .filterNot { shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun prioritizeEmbeds(links: Collection<String>): List<String> {
        return links
            .filterNot { isAdUrl(it) }
            .filterNot { shouldSkipUrl(it) }
            .distinct()
            .sortedWith(
                compareBy<String> { hostPriority(it) }
                    .thenBy { it.length }
            )
    }

    private fun hostPriority(url: String): Int {
        val value = url.lowercase()

        return when {
            value.contains("majorplay") -> 0
            value.contains("jeniusplay") -> 1
            value.contains("hglink") -> 2
            value.contains("hgcloud") -> 3
            value.contains("lulustream") || value.contains("luluvdoo") || value.contains("lulu") -> 4
            value.contains("streamwish") || value.contains("wishfast") -> 5
            value.contains("filemoon") -> 6
            value.contains("vidhide") -> 7
            value.contains("vidguard") -> 8
            value.contains("voe") -> 9
            value.contains("mixdrop") -> 10
            value.contains("mp4upload") -> 11
            value.contains("streamtape") -> 12
            value.contains("dood") -> 13
            value.contains("embed") -> 30
            value.contains("player") -> 31
            value.contains("stream") -> 32
            else -> 50
        }
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
            "luluvdoo",
            "lulu",
            "hglink",
            "hgcloud",
            "majorplay",
            "jeniusplay",
            "pornhub",
            "xvideos",
            "xhamster",
            "redtube",
            "spankbang"
        ).any { value.contains(it) }
    }

    private fun isLikelyPlayable(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true) ||
            url.contains(".txt", true) ||
            isKnownHost(url)
    }

    private fun isLikelyPlayableText(text: String): Boolean {
        return text.contains("download") ||
            text.contains("stream") ||
            text.contains("watch") ||
            text.contains("server") ||
            text.contains("play") ||
            text.contains("mp4") ||
            text.contains("720p") ||
            text.contains("1080p")
    }

    private fun shouldSkipUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("mailto:") ||
            value.contains("content-removal") ||
            value.contains("privacy") ||
            value.contains("dmca") ||
            value.contains("jomblo.org") ||
            value.contains("copy") ||
            value.contains("share")
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
                    "meta[name=twitter:image], " +
                    "video[poster], " +
                    ".poster img, " +
                    ".thumb img, " +
                    "article img, " +
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

    private fun isUnsafeTitle(text: String): Boolean {
        val value = text.lowercase()

        return value.contains("smp") ||
            value.contains("sma") ||
            value.contains("smk") ||
            value.contains("sekolah") ||
            value.contains("pelajar") ||
            value.contains("siswi") ||
            value.contains("anak kecil") ||
            value.contains("dibawah umur") ||
            value.contains("underage") ||
            value.contains("rape") ||
            value.contains("dipaksa") ||
            value.contains("pemerkosaan") ||
            value.contains("incest") ||
            value.contains("sedarah")
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            (
                url.contains("majorplay", true) &&
                    url.contains("config", true) &&
                    url.contains(".json", true)
                )
    }

    private fun isAdUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains("vast") ||
            value.contains("preroll") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("ads") ||
            value.contains("banner") ||
            value.contains("jomblo.org") ||
            value.contains("content-removal") ||
            value.contains("popads") ||
            value.contains("onclick") ||
            value.contains("adsterra") ||
            value.contains("tracking") ||
            value.contains("analytics")
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1440", true) -> Qualities.P1440.value
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
            .replace(Regex("""\s+\|\s+INDO18\.COM.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+INDO18\.COM.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s*Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}