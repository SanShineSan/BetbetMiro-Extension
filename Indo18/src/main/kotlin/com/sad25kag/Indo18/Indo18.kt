package com.sad25kag.Indo18

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
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
import java.util.Base64
import kotlin.random.Random

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

    override val supportedTypes = setOf(TvType.NSFW)

    private data class PageFetch(
        val url: String,
        val document: Document,
        val text: String,
    )

    private val sourceOrigins = listOf(
        "https://www.indo18.com",
        "https://indo18.com",
        "https://indo18.cc",
        "https://indo18.biz.id",
        "https://indo18.link",
    )

    private val streamApiOrigins = listOf(
        "https://rupertisdivingintoocean.com",
        "https://bysezoxexe.com",
        "https://fufafilm.upns.pro",
        "https://fufafilm.strp2p.com",
        "https://myvidplay.com",
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
        "category/perawan/" to "Perawan",
        "category/pns/" to "PNS",
        "category/scandal/" to "Scandal",
        "category/tante/" to "Tante",
        "category/video-indonesia/" to "Video Indonesia",
        "category/video-semi/" to "Video Semi",
        "category/video-viral/" to "Video Viral"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val pageData = fetchPage(url, mainUrl, timeout = 25L)
            ?: return newHomePageResponse(request.name, emptyList<SearchResponse>(), hasNext = false)
        val items = parseCards(pageData.document).distinctBy { canonicalUrl(it.url) }
        return newHomePageResponse(request.name, items, hasNext = hasNextPage(pageData.document, page))
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val clean = path.trim()
        return when {
            page <= 1 && clean.isBlank() -> mainUrl
            page <= 1 && clean.startsWith("?") -> "$mainUrl/$clean"
            page <= 1 -> "$mainUrl/${clean.trim('/')}"
            clean.isBlank() -> "$mainUrl/page/$page"
            clean.startsWith("?") -> "$mainUrl/page/$page/$clean"
            clean.contains("?") -> {
                val base = clean.substringBefore("?").trim('/')
                val query = clean.substringAfter("?")
                "$mainUrl/$base/page/$page?$query"
            }
            else -> "$mainUrl/${clean.trim('/')}/page/$page"
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a.next, a[rel=next], .pagination a:contains(Next), .page-numbers.next, " +
                "a[href*='/page/${page + 1}'], a[href*='page=${page + 1}']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        // Active www.indo18.com currently publishes playable detail pages as root slugs
        // (/title-slug/) instead of the older /v/{slug} format.  Parse card containers first
        // so thumbnails can be recovered, then fall back to raw anchors for the simple listing DOM.
        document.select(
            "article:has(a[href]), .post:has(a[href]), .item:has(a[href]), .video:has(a[href]), " +
                ".video-item:has(a[href]), .thumb-block:has(a[href]), .content article:has(a[href]), " +
                ".grid article:has(a[href]), .card:has(a[href]), li:has(a[href])"
        ).forEach { element ->
            element.toSearchResult()?.let { item -> results[canonicalUrl(item.url)] = item }
        }

        document.select("a[href]").forEach { anchor ->
            anchor.toSearchResult()?.let { item -> results[canonicalUrl(item.url)] = item }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (`is`("a[href]")) this else selectFirst(
            "h2 a[href], h3 a[href], .entry-title a[href], .title a[href], a[href]:has(img), a[href]"
        ) ?: return null

        val href = normalizeContentUrl(fixUrl(anchor.attr("href"), pageBaseUrl(anchor) ?: mainUrl) ?: return null) ?: return null
        if (!isKnownIndo18Url(href)) return null
        if (isBlockedUrl(href)) return null
        if (!isContentUrl(href)) return null

        val baseUrl = pageBaseUrl(anchor) ?: pageBaseUrl(this) ?: mainUrl
        val poster = findPosterUrl(this, anchor, baseUrl)
        val title = listOf(
            selectFirst("h1")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst(".title")?.text(),
            anchor.attr("title"),
            findPosterElement(this, anchor)?.attr("alt"),
            anchor.text()
        ).firstOrNull { isUsableTitle(it) }?.cleanListingTitle() ?: return null

        if (title.length < 2 || isUnsafeTitle(title)) return null

        val score = Regex("""(\d{1,3})%""").find(text())?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.div(10.0)?.toString()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = poster
            this.score = Score.from10(score)
        }
    }

    private fun isUsableTitle(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return false
        return !listOf(
            "Newest", "Best", "Most viewed", "Longest", "Random", "Download complete video now!",
            "More videos", "More video", "Lihat semua", "Putar", "Info", "Tidak Ada",
            "Share", "Home", "Categories", "Actors", "Tags", "Next", "Last"
        ).any { text.equals(it, true) }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().trim('/') }.getOrDefault(url.substringAfter(mainUrl).trim('/')).lowercase()
        if (path.isBlank()) return true
        if (path.startsWith("?") || path.startsWith("#")) return true
        if (isNonContentPath(path)) return true
        if (isContentUrl(url)) return false

        return true
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
            val pageData = fetchPage(url, mainUrl, timeout = 25L) ?: continue
            val results = parseCards(pageData.document).distinctBy { canonicalUrl(it.url) }
            if (results.isNotEmpty()) return newSearchResponseList(results, hasNext = hasNextPage(pageData.document, page))
        }

        return newSearchResponseList(emptyList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeContentUrl(fixUrl(url, mainUrl) ?: url) ?: (fixUrl(url, mainUrl) ?: url)
        val pageData = fetchPage(fixedUrl, mainUrl, timeout = 25L)
            ?: throw ErrorLoadingException("Indo18 detail page unavailable")
        val document = pageData.document
        val effectiveUrl = pageData.url

        val title = document.selectFirst("h1, h1.entry-title")?.text()?.cleanTitle()?.takeIf { it.isNotBlank() }
            ?: effectiveUrl.substringAfterLast("/").replace("-", " ").cleanTitle()

        val poster = getPoster(document, effectiveUrl)
        val text = document.text()
        val views = Regex("""(?i)(?:views?|ditonton)\s*:?\s*([\d.,kmb]+)""").find(text)?.groupValues?.getOrNull(1)
        val rating = Regex("""(\d{1,3})%""").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.div(10.0)?.toString()

        val tags = document.select("a[href*='/category/'], a[href*='/tag/']").map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Home", true) && !it.equals("Categories", true) && !isUnsafeTitle(it) }
            .distinct()

        val related = document.select(
            "article:has(a[href]), .post:has(a[href]), .item:has(a[href]), .video:has(a[href]), " +
                ".video-item:has(a[href]), .thumb-block:has(a[href]), .related a[href], h2 a[href], h3 a[href], main a[href]"
        )
            .mapNotNull { it.toSearchResult() }
            .distinctBy { canonicalUrl(it.url) }
            .filter { canonicalUrl(it.url) != canonicalUrl(effectiveUrl) }

        return newMovieLoadResponse(title, effectiveUrl, TvType.NSFW, effectiveUrl) {
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
        val startUrl = normalizeContentUrl(fixUrl(data, mainUrl) ?: return false) ?: return false
        val emitted = linkedSetOf<String>()
        var found = false

        suspend fun emitDirect(link: String, referer: String): Boolean {
            val fixed = fixUrl(link, referer)?.replace(".txt", ".m3u8") ?: return false
            if (isAdUrl(fixed) || !isPlayableMedia(fixed)) return false

            val key = canonicalUrl(fixed)
            if (!emitted.add(key)) return false

            val linkHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer,
                "Origin" to origin(referer),
                "Accept" to "*/*"
            )

            var generated = false
            if (isHlsLike(fixed)) {
                runCatching {
                    generateM3u8(name, fixed, referer, headers = linkHeaders).forEach { link ->
                        generated = true
                        callback(link)
                    }
                }
            }

            if (!generated) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixed,
                        type = if (isHlsLike(fixed)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = getQualityFromName(fixed).takeIf { it != Qualities.Unknown.value } ?: qualityFromUrl(fixed)
                        this.headers = linkHeaders
                    }
                )
            }

            return true
        }

        suspend fun tryExtractor(link: String, referer: String): Boolean {
            val fixed = fixUrl(link, referer) ?: return false
            if (isAdUrl(fixed) || shouldSkipUrl(fixed)) return false

            var localFound = false
            runCatching {
                loadExtractor(fixed, referer, subtitleCallback) { extractorLink ->
                    val key = canonicalUrl(extractorLink.url)
                    if (emitted.add(key)) {
                        localFound = true
                        callback(extractorLink)
                    }
                }
            }

            return localFound
        }

        suspend fun tryDoodstream(
            link: String,
            referer: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            val doodUrl = normalizeDoodstreamUrl(link, referer) ?: return false
            if (!isDoodstreamHost(doodUrl)) return false

            var extractorFound = false
            runCatching {
                loadExtractor(doodUrl, referer, subtitleCallback) { extractorLink ->
                    val key = canonicalUrl(extractorLink.url)
                    if (emitted.add(key)) {
                        extractorFound = true
                        callback(extractorLink)
                    }
                }
            }

            if (extractorFound) return true

            val response = runCatching {
                app.get(
                    doodUrl,
                    headers = headers + mapOf(
                        "Referer" to referer,
                        "Origin" to origin(doodUrl)
                    ),
                    referer = referer,
                    timeout = 20L
                )
            }.getOrNull() ?: return false

            val html = response.text.cleanEscaped()

            extractPlayableUrls(html, doodUrl).forEach { media ->
                if (media.contains(".m3u8", true) || media.contains(".mp4", true) || media.contains(".webm", true)) {
                    callback(
                        newExtractorLink(
                            source = "$name Doodstream",
                            name = "$name Doodstream",
                            url = media,
                            type = if (isHlsLike(media)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = doodUrl
                            this.quality = qualityFromUrl(media)
                            this.headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to doodUrl,
                                "Origin" to origin(doodUrl),
                                "Accept" to "*/*"
                            )
                        }
                    )
                    return true
                }
            }

            val passPath = Regex("""(?i)(/pass_md5/[^'"\\\s<>]+)""").find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""(?i)pass_md5['"]?\s*[:=]\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)
                    ?.let { if (it.startsWith("/")) it else "/pass_md5/$it" }
                ?: return false

            val doodOrigin = origin(doodUrl)
            val token = passPath.substringAfterLast("/").substringBefore("?")
            val directSeed = runCatching {
                app.get(
                    doodOrigin + passPath,
                    headers = headers + mapOf(
                        "Referer" to doodUrl,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    referer = doodUrl,
                    timeout = 20L
                ).text.cleanEscaped().trim()
            }.getOrNull()?.takeIf { it.startsWith("http", true) } ?: return false

            val finalUrl = if (directSeed.contains("token=", true)) {
                directSeed
            } else {
                directSeed + randomAlphaNum(10) + "?token=$token&expiry=${System.currentTimeMillis()}"
            }

            val key = canonicalUrl(finalUrl)
            if (!emitted.add(key)) return true

            callback(
                newExtractorLink(
                    source = "$name Doodstream",
                    name = "$name Doodstream",
                    url = finalUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = doodUrl
                    this.quality = qualityFromUrl(finalUrl)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to doodUrl,
                        "Origin" to doodOrigin,
                        "Accept" to "*/*"
                    )
                }
            )

            return true
        }

        suspend fun collectFromPage(url: String, referer: String): List<String> {
            val pageData = fetchPage(url, referer, timeout = 20L) ?: return emptyList()

            val document = pageData.document
            val effectiveUrl = pageData.url
            val html = pageData.text.cleanEscaped()
            val links = linkedSetOf<String>()

            extractRedirectTargets(effectiveUrl, referer).forEach { links.add(it) }
            jombloGoCandidates(effectiveUrl).forEach { links.add(it) }
            collectElementLinks(document, effectiveUrl).forEach { links.add(it) }
            extractPlayableUrls(html, effectiveUrl).forEach { links.add(it) }
            extractDoodstreamLinks(html, effectiveUrl).forEach { links.add(it) }
            extractPackedLinks(html, effectiveUrl).forEach { links.add(it) }
            extractEncodedLinks(html, effectiveUrl).forEach { links.add(it) }
            extractTrackingPayloadLinks(html, effectiveUrl).forEach { links.add(it) }
            extractDownloadButtons(document, effectiveUrl).forEach { links.add(it) }
            extractRedirectTargets(html, effectiveUrl).forEach { links.add(it) }
            extractJombloLinks(html, effectiveUrl).forEach { links.add(it) }
            extractStreamApiCandidates(html, effectiveUrl).forEach { links.add(it) }

            return links.filterNot { isAdUrl(it) || shouldSkipUrl(it) }.distinct()
        }

        suspend fun tryStreamApi(link: String, referer: String): Boolean {
            val fixed = fixUrl(link, referer) ?: return false
            if (!isStreamApiHost(fixed)) return false

            val queue = linkedSetOf<String>()
            queue.add(fixed)
            extractStreamApiCandidates(fixed, referer).forEach { queue.add(it) }
            extractStreamApiIds(fixed).forEach { id ->
                streamApiOrigins.forEach { origin ->
                    queue.add("$origin/e/$id")
                    queue.add("$origin/uzfic/$id")
                    queue.add("$origin/api/videos/$id/embed/details")
                    queue.add("$origin/api/videos/$id/embed/settings")
                    queue.add("$origin/api/videos/$id/embed/playback")
                }
            }

            var localFound = false
            val visited = linkedSetOf<String>()
            var index = 0
            while (index < queue.size && visited.size < 40) {
                val target = queue.elementAt(index++)
                val apiUrl = fixUrl(target, fixed) ?: continue
                if (!visited.add(canonicalUrl(apiUrl))) continue

                val response = runCatching {
                    app.get(
                        apiUrl,
                        headers = headers + mapOf(
                            "Referer" to referer,
                            "Origin" to origin(referer),
                            "Accept" to "application/json,text/plain,*/*"
                        ),
                        referer = referer,
                        timeout = 20L
                    )
                }.getOrNull() ?: continue

                val text = response.text.cleanEscaped()
                extractPlayableUrls(text, apiUrl).forEach { media ->
                    if (emitDirect(media, apiUrl)) localFound = true
                }

                extractStreamApiCandidates(text, apiUrl).forEach { nested ->
                    when {
                        emitDirect(nested, apiUrl) -> localFound = true
                        tryExtractor(nested, apiUrl) -> localFound = true
                        !visited.contains(canonicalUrl(nested)) && isStreamApiHost(nested) -> queue.add(nested)
                    }
                }
            }

            return localFound
        }

        suspend fun tryPlaymogo(link: String, referer: String): Boolean {
            val fixed = fixUrl(link, referer) ?: return false
            if (!isPlaymogoHost(fixed)) return false

            var localFound = false
            val pageReferer = if (isJombloHost(referer)) referer else "https://jomblo.org/"
            collectFromPage(fixed, pageReferer).forEach { nested ->
                val target = fixUrl(nested, fixed) ?: return@forEach
                when {
                    emitDirect(target, fixed) -> localFound = true
                    tryStreamApi(target, fixed) -> localFound = true
                    tryDoodstream(target, fixed, subtitleCallback, callback) -> localFound = true
                    tryExtractor(target, fixed) -> localFound = true
                }
            }

            return localFound
        }

        suspend fun tryAny(link: String, referer: String): Boolean {
            val fixed = fixUrl(link, referer) ?: return false
            val targets = linkedSetOf<String>()
            targets.add(fixed)
            jombloGoCandidates(fixed).forEach { targets.add(it) }
            extractRedirectTargets(fixed, referer).forEach { targets.add(it) }

            var localFound = false
            targets.forEach { target ->
                when {
                    emitDirect(target, referer) -> localFound = true
                    tryStreamApi(target, referer) -> localFound = true
                    tryDoodstream(target, referer, subtitleCallback, callback) -> localFound = true
                    tryPlaymogo(target, referer) -> localFound = true
                    tryExtractor(target, referer) -> localFound = true
                }
            }

            return localFound
        }

        val visitedPages = linkedSetOf<String>()
        val candidates = linkedSetOf<String>()

        expandSourceOriginUrls(startUrl).forEach { candidates.add(it) }
        collectFromPage(startUrl, mainUrl).forEach { candidates.add(it) }
        jombloGoCandidates(startUrl).forEach { candidates.add(it) }

        candidates.toList().forEach { candidate ->
            if (tryAny(candidate, startUrl)) found = true
        }

        if (found) return true

        embedLoop@ for (embed in prioritizeEmbeds(candidates)
            .filter { visitedPages.add(canonicalUrl(it)) }
            .take(18)
        ) {
            if (tryAny(embed, startUrl)) {
                found = true
                break@embedLoop
            }

            for (nested in collectFromPage(embed, startUrl)) {
                if (tryAny(nested, embed)) {
                    found = true
                    break@embedLoop
                }
            }
        }

        return found
    }

    private fun collectElementLinks(document: Document, baseUrl: String): List<String> {
        val results = linkedSetOf<String>()

        document.select(
            "meta[itemprop=embedURL], meta[property=og:video], meta[property=og:video:url], " +
                "meta[property=og:video:secure_url], meta[property=og:video:iframe], meta[name=twitter:player], " +
                "video[src], video[data-src], video[data-video], video[poster], video source[src], " +
                "source[src], source[data-src], iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                "iframe[data-lazy-src], iframe[data-original], embed[src], object[data], a[href], button[data-url], " +
                "button[data-video], button[data-file], [data-src], [data-video], [data-file], [data-url], " +
                "[data-embed], [data-iframe], [data-player], [data-href], [data-hls], [data-m3u8], " +
                "[data-mp4], [onclick], script[src]"
        ).forEach { element ->
            val values = listOf(
                element.attr("content"),
                element.attr("data-litespeed-src"),
                element.attr("data-lazy-src"),
                element.attr("data-original"),
                element.attr("data-video"),
                element.attr("data-file"),
                element.attr("data-url"),
                element.attr("data-embed"),
                element.attr("data-iframe"),
                element.attr("data-player"),
                element.attr("data-href"),
                element.attr("data-src"),
                element.attr("data-hls"),
                element.attr("data-m3u8"),
                element.attr("data-mp4"),
                element.attr("poster"),
                element.attr("data"),
                element.attr("src"),
                element.attr("href"),
                element.attr("onclick")
            )

            values.filter { it.isNotBlank() }.forEach { raw ->
                extractPlayableUrls(raw, baseUrl).forEach { results.add(it) }
                extractDoodstreamLinks(raw, baseUrl).forEach { results.add(it) }
                extractRedirectTargets(raw, baseUrl).forEach { results.add(it) }
                extractJombloLinks(raw, baseUrl).forEach { results.add(it) }
                extractStreamApiCandidates(raw, baseUrl).forEach { results.add(it) }
                fixUrl(raw, baseUrl)?.let { fixed ->
                    if (isLikelyPlayable(fixed) || isKnownHost(fixed)) results.add(fixed)
                }
            }
        }

        return results.toList()
    }

    private fun extractDownloadButtons(document: Document, baseUrl: String): List<String> {
        val results = linkedSetOf<String>()

        document.select("a[href], button, [onclick]").forEach { element ->
            val text = element.text().lowercase()
            val raw = element.attr("href").ifBlank { element.attr("data-url") }.ifBlank { element.attr("onclick") }
            if (raw.isBlank()) return@forEach

            val looksLikeSource = text.contains("download") || text.contains("server") || text.contains("watch") ||
                text.contains("play") || isKnownHost(raw)
            if (!looksLikeSource) return@forEach

            extractPlayableUrls(raw, baseUrl).forEach { results.add(it) }
            extractDoodstreamLinks(raw, baseUrl).forEach { results.add(it) }
            extractRedirectTargets(raw, baseUrl).forEach { results.add(it) }
            extractJombloLinks(raw, baseUrl).forEach { results.add(it) }
            fixUrl(raw, baseUrl)?.let { results.add(it) }
        }

        return results.toList()
    }

    private fun extractPackedLinks(html: String, baseUrl: String): List<String> {
        val unpacked = runCatching { if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null }.getOrNull()
        return unpacked?.let { unpackedHtml ->
            (extractPlayableUrls(unpackedHtml.cleanEscaped(), baseUrl) + extractDoodstreamLinks(unpackedHtml.cleanEscaped(), baseUrl)).distinct()
        }.orEmpty()
    }

    private fun extractEncodedLinks(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val clean = html.cleanEscaped()

        runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrNull()?.takeIf { it != clean }?.let {
            extractPlayableUrls(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractDoodstreamLinks(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractRedirectTargets(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractJombloLinks(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
        }

        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""").findAll(clean).mapNotNull { decodeBase64(it.groupValues[1]) }.forEach {
            extractPlayableUrls(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractDoodstreamLinks(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractRedirectTargets(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractJombloLinks(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractStreamApiCandidates(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
        }

        Regex("""(?i)(?:base64|data|hash|embed|source)\s*[:=]\s*['"]([A-Za-z0-9+/=_-]{20,})['"]""").findAll(clean).mapNotNull { decodeBase64(it.groupValues[1]) }.forEach {
            extractPlayableUrls(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractDoodstreamLinks(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractRedirectTargets(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractJombloLinks(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractStreamApiCandidates(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
        }

        return links.toList()
    }

    private fun extractStreamApiCandidates(text: String, baseUrl: String = mainUrl): List<String> {
        val links = linkedSetOf<String>()
        val clean = text.cleanEscaped()
        val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val source = if (decoded != clean) "$clean $decoded" else clean

        Regex(
            """(?i)(?:https?:)?//(?:rupertisdivingintoocean\.com|bysezoxexe\.com|fufafilm\.(?:upns\.pro|strp2p\.com)|myvidplay\.com)[^"'\\\s<>]+""",
            RegexOption.IGNORE_CASE
        ).findAll(source).map { it.value.trimEnd('\\', '"', '\'', ',', ';') }.forEach { raw ->
            fixUrl(raw, baseUrl)?.let { links.add(it) }
        }

        extractStreamApiIds(source).forEach { id ->
            streamApiOrigins.forEach { origin ->
                links.add("$origin/e/$id")
                links.add("$origin/uzfic/$id")
                links.add("$origin/api/videos/$id/embed/details")
                links.add("$origin/api/videos/$id/embed/settings")
                links.add("$origin/api/videos/$id/embed/playback")
            }
        }

        return links.filterNot { isAdUrl(it) || shouldSkipUrl(it) }.distinct()
    }

    private fun extractStreamApiIds(text: String): List<String> {
        val ids = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex("""(?i)/(?:e|uzfic)/([A-Za-z0-9_-]{8,})(?:[/?#]|$)""")
            .findAll(clean)
            .map { it.groupValues[1] }
            .forEach { ids.add(it) }

        Regex("""(?i)/api/videos/([A-Za-z0-9_-]{8,})/embed""")
            .findAll(clean)
            .map { it.groupValues[1] }
            .forEach { ids.add(it) }

        Regex("""(?i)img-place\.com/([A-Za-z0-9_-]{8,})_""")
            .findAll(clean)
            .map { it.groupValues[1] }
            .forEach { ids.add(it) }

        return ids.toList()
    }

    private fun extractDoodstreamLinks(text: String, baseUrl: String = mainUrl): List<String> {
        val links = linkedSetOf<String>()
        val clean = text.cleanEscaped()
        val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val source = if (decoded != clean) "$clean $decoded" else clean

        Regex(
            """(?i)(?:https?:)?//(?:www\.)?(?:doodstream\.(?:com|co|link)|dood\.(?:watch|to|so|la|pm|re|ws|sh|wf|cx|yt|li|one|video)|d0000d\.com|dooood\.com|do0od\.com|doods\.(?:pro|to)|doodcdn\.co|ds2play\.com)/(?:e|d|embed)/[A-Za-z0-9_-]+(?:[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(source).map { it.value }.forEach { raw ->
            normalizeDoodstreamUrl(raw, baseUrl)?.let { links.add(it) }
        }

        Regex(
            """(?i)(?:doodstream\.(?:com|co|link)|dood\.|d0000d\.com|dooood\.com|do0od\.com|doods\.|doodcdn\.co|ds2play\.com)[^A-Za-z0-9_-]+(?:e|d|embed)[^A-Za-z0-9_-]+([A-Za-z0-9_-]{6,})""",
            RegexOption.IGNORE_CASE
        ).findAll(source).forEach { match ->
            val host = match.value.substringBefore(match.groupValues[1]).replace(Regex("""[^A-Za-z0-9.:-]+$"""), "").substringAfterLast("//", match.value.substringBefore(match.groupValues[1]).substringBefore("/"))
            val id = match.groupValues[1]
            normalizeDoodstreamUrl("https://$host/e/$id", baseUrl)?.let { links.add(it) }
        }

        Regex(
            """(?i)(?:src|href|data-src|data-url|data-video|data-file|file|url)\s*[:=]\s*["']([^"']*(?:doodstream\.(?:com|co|link)|dood\.|d0000d\.com|dooood\.com|do0od\.com|doods\.|doodcdn\.co|ds2play\.com)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(source).mapNotNull { it.groupValues.getOrNull(1) }.forEach { raw ->
            fixUrl(raw, baseUrl)?.let { links.add(it) }
        }

        return links.filterNot { isAdUrl(it) || shouldSkipUrl(it) }.distinct()
    }

    private fun extractPlayableUrls(text: String, baseUrl: String = mainUrl): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean).map { it.value }.forEach { fixUrl(it.replace(".txt", ".m3u8"), baseUrl)?.let(urls::add) }

        Regex("""//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean).map { "https:${it.value}" }.forEach { fixUrl(it.replace(".txt", ".m3u8"), baseUrl)?.let(urls::add) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(clean).map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .forEach { fixUrl(it.replace(".txt", ".m3u8"), baseUrl)?.let(urls::add) }

        Regex(
            """(?:file|src|source|url|videoSource|videoUrl|video_url|playUrl|play_url|hls|hlsUrl|hls_url|embed|embedUrl|embed_url|iframe|player|contentUrl|data-file|data-video|data-url|data-src|data-embed|data-iframe|data-player|content)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).mapNotNull { it.groupValues.getOrNull(1) }.forEach { raw ->
            val fixed = fixUrl(raw.replace(".txt", ".m3u8"), baseUrl)
            if (fixed != null && (isLikelyPlayable(fixed) || isKnownHost(fixed))) urls.add(fixed)
        }

        Regex(
            """https?://[^"'\\\s<>]+?(?:embed|player|stream|jomblo|playmogo|filemoon|streamwish|wishfast|doodstream|dood\.|d0000d|dooood|do0od|doods|doodcdn|ds2play|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|lulustream|luluvdoo|lulu|hglink|hgcloud|majorplay|jeniusplay|pornhub|xvideos|xhamster|redtube|spankbang)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).map { it.value }.forEach { fixUrl(it, baseUrl)?.let(urls::add) }

        return urls.filterNot { isAdUrl(it) || shouldSkipUrl(it) }.distinct()
    }

    private fun extractTrackingPayloadLinks(text: String, baseUrl: String = mainUrl): List<String> {
        val clean = text.cleanEscaped()
        val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val source = if (decoded != clean) "$clean $decoded" else clean
        val links = linkedSetOf<String>()

        Regex("""(?i)(?:[?&]|&amp;)md=([A-Za-z0-9_+\-/%=]{24,})""")
            .findAll(source)
            .map { it.groupValues[1].substringBefore("&").substringBefore(" ") }
            .mapNotNull { raw ->
                val value = runCatching { URLDecoder.decode(raw.cleanEscaped(), "UTF-8") }.getOrDefault(raw.cleanEscaped())
                decodeBase64(value)
            }
            .forEach { payload ->
                Regex("(?i)\\\"(?:q|r|url|embed|source)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                    .findAll(payload.cleanEscaped())
                    .map { it.groupValues[1].cleanEscaped() }
                    .forEach { raw ->
                        val target = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                        fixUrl(target, baseUrl)?.let { fixed ->
                            if ((isKnownHost(fixed) || isLikelyPlayable(fixed)) && !isAdUrl(fixed)) links.add(fixed)
                        }
                    }
            }

        return links.distinct()
    }

    private fun extractRedirectTargets(text: String, baseUrl: String = mainUrl): List<String> {
        val clean = text.cleanEscaped()
        val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val source = if (decoded != clean) "$clean $decoded" else clean
        val results = linkedSetOf<String>()

        Regex("""(?i)(?:[?&]|&amp;)s=([^"'\\\s<>]+)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .forEach { raw ->
                val target = runCatching { URLDecoder.decode(raw.cleanEscaped(), "UTF-8") }.getOrDefault(raw.cleanEscaped())
                if (target.startsWith("http", true) || target.startsWith("//")) {
                    fixUrl(target, baseUrl)?.let { results.add(it) }
                }
            }

        Regex("""(?i)(?:url|u|target|redirect|redirect_to|destination|dest)\s*=\s*(https?%3A%2F%2F[^"'\\\s<>]+)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .forEach { raw ->
                val target = runCatching { URLDecoder.decode(raw.cleanEscaped(), "UTF-8") }.getOrDefault(raw.cleanEscaped())
                fixUrl(target, baseUrl)?.let { results.add(it) }
            }

        return results
            .flatMap { listOf(it) + jombloGoCandidates(it) }
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .distinct()
    }

    private fun extractJombloLinks(text: String, baseUrl: String = mainUrl): List<String> {
        val links = linkedSetOf<String>()
        val clean = text.cleanEscaped()
        val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val source = if (decoded != clean) "$clean $decoded" else clean

        Regex("""(?i)https?://(?:www\.)?jomblo\.org/file/[^"'\\\s<>]+""")
            .findAll(source)
            .map { it.value.trimEnd('/', '"', '\'', ',', ';') }
            .forEach { raw ->
                fixUrl(raw, baseUrl)?.let { fixed ->
                    links.add(fixed)
                    jombloGoCandidates(fixed).forEach { links.add(it) }
                }
            }

        return links.filterNot { isAdUrl(it) || shouldSkipUrl(it) }.distinct()
    }

    private fun jombloGoCandidates(raw: String): List<String> {
        val fixed = fixUrl(raw, mainUrl) ?: return emptyList()
        if (!isJombloHost(fixed)) return emptyList()

        val clean = fixed.substringBefore("#").substringBefore("?").trimEnd('/')
        val links = linkedSetOf(clean)
        if (!clean.endsWith("/go", true)) links.add("$clean/go")

        return links.toList()
    }

    private fun isJombloHost(url: String): Boolean {
        return runCatching { URI(url).host.orEmpty().lowercase().endsWith("jomblo.org") }.getOrDefault(false)
    }

    private fun isPlaymogoHost(url: String): Boolean {
        return runCatching { URI(url).host.orEmpty().lowercase().endsWith("playmogo.com") }.getOrDefault(false)
    }

    private fun normalizeDoodstreamUrl(raw: String, baseUrl: String): String? {
        val fixed = fixUrl(raw, baseUrl) ?: return null
        if (!isDoodstreamHost(fixed)) return null
        return fixed
            .replace("/d/", "/e/", ignoreCase = true)
            .replace("/embed/", "/e/", ignoreCase = true)
            .substringBefore("&autoplay")
            .substringBefore("?download")
    }

    private fun randomAlphaNum(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..length).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun prioritizeEmbeds(links: Collection<String>): List<String> {
        return links
            .filterNot { isAdUrl(it) || shouldSkipUrl(it) }
            .filter { isKnownHost(it) || it.startsWith("http", true) }
            .distinctBy { canonicalUrl(it) }
            .sortedWith(compareBy<String> { hostPriority(it) }.thenBy { it.length })
    }

    private fun hostPriority(url: String): Int {
        val value = url.lowercase()
        return when {
            isDoodstreamHost(value) -> 0
            value.contains("jomblo.org") -> 1
            value.contains("playmogo.com") -> 2
            isStreamApiHost(value) -> 3
            value.contains("majorplay") -> 4
            value.contains("jeniusplay") -> 4
            value.contains("hglink") -> 4
            value.contains("hgcloud") -> 5
            value.contains("lulustream") || value.contains("luluvdoo") || value.contains("lulu") -> 6
            value.contains("streamwish") || value.contains("wishfast") -> 7
            value.contains("filemoon") -> 8
            value.contains("vidhide") -> 9
            value.contains("vidguard") -> 10
            value.contains("voe") -> 11
            value.contains("mixdrop") -> 12
            value.contains("mp4upload") -> 13
            value.contains("streamtape") -> 14
            value.contains("embed") -> 30
            value.contains("player") -> 31
            value.contains("stream") -> 32
            else -> 50
        }
    }

    private fun isDoodstreamHost(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("doodstream.com") ||
            value.contains("doodstream.co") ||
            value.contains("doodstream.link") ||
            value.contains("dood.watch") ||
            value.contains("dood.to") ||
            value.contains("dood.so") ||
            value.contains("dood.la") ||
            value.contains("dood.pm") ||
            value.contains("dood.re") ||
            value.contains("dood.ws") ||
            value.contains("dood.sh") ||
            value.contains("dood.wf") ||
            value.contains("dood.cx") ||
            value.contains("dood.yt") ||
            value.contains("dood.li") ||
            value.contains("dood.one") ||
            value.contains("dood.video") ||
            value.contains("d0000d.com") ||
            value.contains("dooood.com") ||
            value.contains("do0od.com") ||
            value.contains("doods.pro") ||
            value.contains("doods.to") ||
            value.contains("doodcdn.co") ||
            value.contains("ds2play.com")
    }

    private fun isStreamApiHost(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("rupertisdivingintoocean.com") ||
            value.contains("bysezoxexe.com") ||
            value.contains("fufafilm.upns.pro") ||
            value.contains("fufafilm.strp2p.com") ||
            value.contains("myvidplay.com")
    }

    private fun isKnownHost(url: String): Boolean {
        val value = url.lowercase()
        return isDoodstreamHost(value) || listOf(
            "jomblo.org", "playmogo.com", "rupertisdivingintoocean.com", "bysezoxexe.com",
            "fufafilm.upns.pro", "fufafilm.strp2p.com", "myvidplay.com", "filemoon", "streamwish", "wishfast", "streamtape",
            "vidhide", "vidguard", "voe", "mixdrop", "mp4upload", "lulustream", "luluvdoo", "lulu",
            "hglink", "hgcloud", "majorplay", "jeniusplay", "pornhub", "xvideos", "xhamster", "redtube", "spankbang",
            "embed", "player", "stream"
        ).any { value.contains(it) }
    }

    private fun isLikelyPlayable(url: String): Boolean {
        return url.contains(".m3u8", true) || url.contains(".mp4", true) || url.contains(".webm", true) ||
            url.contains(".txt", true) || isKnownHost(url)
    }

    private fun isPlayableMedia(url: String): Boolean {
        return url.contains(".m3u8", true) || url.contains(".mp4", true) || url.contains(".webm", true)
    }

    private fun isContentUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().trim('/') }.getOrDefault("").lowercase()
        if (path.isBlank() || isNonContentPath(path) || path.contains('.')) return false
        if (path.startsWith("v/") || path.startsWith("video/") || path.startsWith("watch/")) return true

        // Current source format uses one clean WordPress-like slug at the site root.
        return '/' !in path && path.length > 2
    }

    private fun isNonContentPath(path: String): Boolean {
        val clean = path.trim('/').lowercase()
        val exact = setOf(
            "category", "categories", "actors", "actor", "tags", "tag", "page", "search", "filter",
            "content-removal", "privacy", "dmca", "contact", "wp-content", "wp-json", "wp-admin",
            "feed", "login", "register", "reset-password", "lost-password", "password-reset", "signup", "sign-up", "account", "profile", "cgi-bin", "cdn-cgi"
        )
        if (clean in exact) return true

        return listOf(
            "category/", "categories/", "actors/", "actor/", "tags/", "tag/", "page/", "search/",
            "wp-content/", "wp-json/", "wp-admin/", "feed/", "cdn-cgi/", "cgi-bin/"
        ).any { clean.startsWith(it) }
    }

    private fun normalizeContentUrl(url: String): String? {
        val fixed = fixUrl(url, mainUrl) ?: return null
        return if (isKnownIndo18Url(fixed) && isContentUrl(fixed)) {
            fixed.substringBefore("#").substringBefore("?").trimEnd('/')
        } else {
            fixed
        }
    }

    private fun shouldSkipUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("facebook.com") || value.contains("twitter.com") || value.contains("telegram") ||
            value.contains("whatsapp") || value.contains("mailto:") || value.contains("content-removal") ||
            value.contains("privacy") || value.contains("dmca") || value.contains("copy") || value.contains("share")
    }

    private suspend fun fetchPage(url: String, referer: String = mainUrl, timeout: Long = 25L): PageFetch? {
        for (target in expandSourceOriginUrls(url)) {
            val response = runCatching {
                app.get(
                    target,
                    headers = headers + mapOf(
                        "Referer" to referer,
                        "Origin" to origin(target),
                    ),
                    referer = referer,
                    timeout = timeout,
                )
            }.getOrNull() ?: continue

            val text = response.text
            if (text.isBlank()) continue
            return PageFetch(target, response.document, text)
        }

        return null
    }

    private fun expandSourceOriginUrls(url: String): List<String> {
        val fixed = fixUrl(url, mainUrl) ?: url
        val uri = runCatching { URI(fixed) }.getOrNull() ?: return listOf(fixed)
        val pathAndQuery = buildString {
            append(uri.rawPath?.takeIf { it.isNotBlank() } ?: "/")
            uri.rawQuery?.takeIf { it.isNotBlank() }?.let { append('?').append(it) }
        }

        return if (isKnownIndo18Host(uri.host.orEmpty())) {
            sourceOrigins.map { it.trimEnd('/') + pathAndQuery }.distinct()
        } else {
            listOf(fixed)
        }
    }

    private fun isKnownIndo18Url(url: String): Boolean {
        return runCatching { isKnownIndo18Host(URI(url).host.orEmpty()) }.getOrDefault(false)
    }

    private fun isKnownIndo18Host(host: String): Boolean {
        val clean = host.lowercase().removePrefix("www.")
        return sourceOrigins.any { origin ->
            runCatching { URI(origin).host.orEmpty().lowercase().removePrefix("www.") == clean }.getOrDefault(false)
        }
    }

    private fun pageBaseUrl(element: Element): String? {
        return element.ownerDocument()?.location()?.takeIf { it.isNotBlank() }
    }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        val escaped = url.cleanEscaped()
        val clean = runCatching { URLDecoder.decode(escaped, "UTF-8") }
            .getOrDefault(escaped)
            .trim()
            .trim('\"', '\'', ',', ';')
        if (clean.isBlank() || clean == "#" || clean.startsWith("javascript:", true) || clean.startsWith("mailto:", true)) return null

        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> origin(baseUrl) + clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    private fun origin(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun getPoster(document: Document, baseUrl: String = document.location().ifBlank { mainUrl }): String? {
        val element = document.selectFirst(
            "meta[property=og:image], meta[name=twitter:image], meta[itemprop=thumbnailUrl], link[rel=image_src], " +
                "video[poster], .poster img, .thumb img, article img, picture img, [style*=background]"
        )

        return fixUrl(element?.getImageAttr(), baseUrl)?.takeIf { !isBadImage(it) }
    }

    private fun findPosterUrl(element: Element, anchor: Element, baseUrl: String): String? {
        return fixUrl(findPosterElement(element, anchor)?.getImageAttr(), baseUrl)?.takeIf { !isBadImage(it) }
    }

    private fun findPosterElement(element: Element, anchor: Element): Element? {
        val selector = "img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-full], picture img, " +
            "source[srcset], source[data-srcset], [style*=background], [data-poster], video[poster]"

        val scopes = mutableListOf<Element>()
        scopes.add(element)
        scopes.add(anchor)

        var parent = anchor.parent()
        repeat(4) {
            if (parent != null) scopes.add(parent!!)
            parent = parent?.parent()
        }

        return scopes.asSequence()
            .mapNotNull { scope ->
                when {
                    scope.hasAttr("src") || scope.hasAttr("data-src") || scope.hasAttr("data-lazy-src") ||
                        scope.hasAttr("data-original") || scope.hasAttr("data-full") || scope.hasAttr("srcset") ||
                        scope.hasAttr("data-srcset") || scope.hasAttr("data-poster") || scope.hasAttr("poster") ||
                        scope.attr("style").contains("background", true) -> scope
                    else -> scope.selectFirst(selector)
                }
            }
            .firstOrNull { !it.getImageAttr().isNullOrBlank() }
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? = value?.split(",")?.map { it.trim().substringBefore(" ") }?.lastOrNull { it.isNotBlank() && !isBadImage(it) }

        return attr("content").takeIf { it.isNotBlank() }
            ?: attr("abs:href").takeIf { it.isNotBlank() && hasAttr("rel") }
            ?: fromSrcSet(attr("data-srcset"))
            ?: fromSrcSet(attr("data-lazy-srcset"))
            ?: fromSrcSet(attr("srcset"))
            ?: attr("abs:data-poster").takeIf { it.isNotBlank() }
            ?: attr("abs:poster").takeIf { it.isNotBlank() }
            ?: attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:data-full").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() }
            ?: attr("data-poster").takeIf { it.isNotBlank() }
            ?: attr("poster").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("data-original").takeIf { it.isNotBlank() }
            ?: attr("data-full").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
            ?: Regex("""url\((['\"]?)([^)'\"]+)\1\)""", RegexOption.IGNORE_CASE)
                .find(attr("style"))?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
    }

    private fun isBadImage(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() || value.startsWith("data:image") || value.contains("blank") ||
            value.contains("placeholder") || value.contains("default") || value.contains("no-image") ||
            value.contains("noimage") || value.contains("loader") || value.contains("loading") ||
            value.contains("lazy") || value.contains("spacer") || value.contains("logo") ||
            value.contains("favicon") || value.contains("banner") || value.endsWith(".svg")
    }

    private fun isUnsafeTitle(text: String): Boolean {
        val value = text.lowercase()
        return value.contains("abg") || value.contains("teen") || value.contains("minor") || value.contains("underage") ||
            value.contains("smp") || value.contains("sma") || value.contains("smk") || value.contains("smu") ||
            value.contains("sekolah") || value.contains("pelajar") || value.contains("siswi") ||
            value.contains("anak kecil") || value.contains("dibawah umur") || value.contains("underage") ||
            value.contains("rape") || value.contains("dipaksa") || value.contains("pemerkosaan") ||
            value.contains("incest") || value.contains("sedarah")
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true) || (url.contains("majorplay", true) && url.contains("config", true) && url.contains(".json", true))
    }

    private fun isAdUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("ouo.io") || value.contains("ouo.press") ||
            value.contains("aldidevoiderhisn.cyou") || value.contains("raquetspurrey.rest") || value.contains("tilesremedy.qpon") ||
            value.contains("vast") || value.contains("preroll") || value.contains("doubleclick") ||
            value.contains("googlesyndication") || value.contains("ads") || value.contains("banner") ||
            value.contains("content-removal") || value.contains("popads") || value.contains("onclick") ||
            value.contains("adsterra") || value.contains("tracking") || value.contains("analytics") ||
            value.contains("histats") || value.contains("cloudflareinsights")
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1440", true) -> Qualities.P1440.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("540", true) || url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun decodeBase64(value: String): String? {
        val raw = value.trim()
        if (raw.length < 8) return null

        val normalized = raw.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)

        return runCatching { String(Base64.getDecoder().decode(padded)) }
            .getOrElse { runCatching { String(Base64.getUrlDecoder().decode(padded)) }.getOrNull() }
    }

    private fun canonicalUrl(url: String): String = url.substringBefore("#").substringBefore("?").trimEnd('/').lowercase()

    private fun String?.cleanEscaped(): String {
        return this.orEmpty()
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
        return replace(Regex("""\s+\|\s+INDO18\.(?:COM|CC).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+INDO18\.(?:COM|CC).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s*Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanListingTitle(): String {
        return cleanTitle()
            .replace(Regex("""^\s*\d{1,2}:\d{2}(?::\d{2})?\s+"""), "")
            .replace(Regex("""\s+\d+(?:[.,]\d+)?[KMB]?\s+\d{1,3}%\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
