package com.layarbokep

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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
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

class LayarBokep : MainAPI() {
    override var mainUrl = "https://layarbokep-mobile.ubuntumysec.workers.dev"
    override var name = "LayarBokep"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "category/amateur" to "Amateur",
        "category/asia" to "Asia",
        "category/barat" to "Barat",
        "category/cosplay" to "Cosplay",
        "category/japan" to "Japan",
        "category/jav" to "JAV",
        "category/korea" to "Korea",
        "category/viral" to "Viral",
        "category/indo" to "Indo",
        "category/bokep-indo" to "Bokep Indo",
        "category/uncensored" to "Uncensored",
        "tag/hd" to "HD",
        "tag/full-video" to "Full Video"
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
        val documents = request.data.buildCandidateUrls(page)
            .mapNotNull { url ->
                runCatching {
                    app.get(
                        url,
                        headers = headers,
                        referer = mainUrl,
                        timeout = 25L
                    ).document
                }.getOrNull()
            }

        val items = documents
            .flatMap { document ->
                parseCards(document)
            }
            .filterNot { it.name.isBadTitle() }
            .distinctBy { it.url }

        val hasNext = documents.any { document ->
            hasNextPage(document, page)
        } || items.isNotEmpty()

        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNext
        )
    }

    private fun String.buildCandidateUrls(page: Int): List<String> {
        val clean = trim().trim('/')
        val candidates = linkedSetOf<String>()

        fun addPath(path: String) {
            val p = path.trim('/')
            if (page <= 1) {
                candidates.add(if (p.isBlank()) mainUrl else "$mainUrl/$p")
                candidates.add(if (p.isBlank()) "$mainUrl/" else "$mainUrl/$p/")
            } else {
                candidates.add(if (p.isBlank()) "$mainUrl/page/$page" else "$mainUrl/$p/page/$page")
                candidates.add(if (p.isBlank()) "$mainUrl/page/$page/" else "$mainUrl/$p/page/$page/")
                candidates.add(if (p.isBlank()) "$mainUrl/?paged=$page" else "$mainUrl/$p/?paged=$page")
            }
        }

        when {
            clean.isBlank() -> addPath("")
            clean.startsWith("http", true) -> candidates.add(clean)
            clean.startsWith("?") -> {
                if (page <= 1) candidates.add("$mainUrl/$clean")
                else candidates.add("$mainUrl/page/$page/$clean")
            }
            else -> {
                addPath(clean)

                val slug = clean.substringAfterLast("/")
                if (slug.isNotBlank()) {
                    addPath("category/$slug")
                    addPath("kategori/$slug")
                    addPath("tag/$slug")
                }
            }
        }

        return candidates.toList()
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a[rel=next], " +
                ".pagination a:contains(Next), " +
                ".pagination a:contains(Berikutnya), " +
                ".page-numbers.next, " +
                "a[href*='/page/${page + 1}'], " +
                "a[href*='paged=${page + 1}'], " +
                "a[href*='page=${page + 1}']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a[href]), " +
                ".video-item:has(a[href]), " +
                ".video-card:has(a[href]), " +
                ".post:has(a[href]), " +
                ".item:has(a[href]), " +
                ".grid article:has(a[href]), " +
                ".content article:has(a[href]), " +
                ".card:has(a[href]), " +
                ".entry:has(a[href])"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        if (results.isEmpty()) {
            document.select("a[href]:has(img)").forEach { element ->
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
                ".title a[href], " +
                    ".entry-title a[href], " +
                    "h2 a[href], " +
                    "h3 a[href], " +
                    "a[href]:has(img), " +
                    "a[href]"
            ) ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (href.isBlockedNavigationUrl()) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val title = listOf(
            selectFirst(".title")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            anchor.attr("aria-label"),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text(),
            href.substringAfterLast("/").replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() && !it.isBadTitle()
        }?.cleanTitle() ?: return null

        if (title.length < 3 || title.isBadTitle()) return null

        val poster = fixUrlNull(image?.getImageAttr())
            ?.takeIf { !it.isBadImage() }

        return newMovieSearchResponse(
            title,
            href,
            TvType.NSFW
        ) {
            posterUrl = poster
        }
    }

    private fun String.isBlockedNavigationUrl(): Boolean {
        val path = substringAfter(mainUrl).trim('/').lowercase()

        if (path.isBlank()) return true

        val blockedPrefixes = listOf(
            "category/",
            "kategori/",
            "tag/",
            "page/",
            "search",
            "privacy",
            "dmca",
            "contact",
            "terms",
            "login",
            "register",
            "wp-content/",
            "wp-json/",
            "wp-admin/",
            "feed"
        )

        val definitelyPlayable = listOf(
            "video/",
            "watch/",
            "post/",
            "movie/"
        ).any { path.startsWith(it) }

        if (definitelyPlayable) return false

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
        val urls = listOf(
            if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded",
            if (page <= 1) "$mainUrl/search/$encoded" else "$mainUrl/search/$encoded/page/$page",
            if (page <= 1) "$mainUrl/?search=$encoded" else "$mainUrl/page/$page/?search=$encoded"
        )

        var bestResults = emptyList<SearchResponse>()
        var next = false

        for (url in urls) {
            val document = runCatching {
                app.get(
                    url,
                    headers = headers,
                    referer = mainUrl,
                    timeout = 25L
                ).document
            }.getOrNull() ?: continue

            val parsed = parseCards(document)
                .filterNot { it.name.isBadTitle() }
                .distinctBy { it.url }

            if (parsed.isNotEmpty()) {
                bestResults = parsed
                next = hasNextPage(document, page)
                break
            }
        }

        return newSearchResponseList(
            bestResults,
            hasNext = next
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(
            url,
            headers = headers,
            referer = mainUrl,
            timeout = 25L
        )

        val document = response.document

        val title = listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[itemprop=name]")?.attr("content"),
            document.selectFirst("h1.entry-title")?.text(),
            document.selectFirst("h1")?.text(),
            document.selectFirst(".video-title")?.text(),
            document.selectFirst(".entry-title")?.text(),
            url.substringAfterLast("/").replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() && !it.isBadTitle()
        }?.cleanTitle() ?: return null

        val poster = getPoster(document)

        val plot = listOf(
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst(".entry-content p")?.text(),
            document.selectFirst(".entry-content")?.text(),
            document.selectFirst(".description")?.text(),
            document.selectFirst(".video-description")?.text()
        ).firstOrNull {
            !it.isNullOrBlank() && !it.isBadTitle()
        }?.trim()

        val tags = document.select(
            "a[href*='/category/'], " +
                "a[href*='/kategori/'], " +
                "a[href*='/tag/'], " +
                ".tags a, " +
                ".category a"
        ).map { it.text().trim().cleanTitle() }
            .filter { it.isNotBlank() && !it.isBadTitle() }
            .distinct()

        val recommendations = document.select(
            ".related article:has(a[href]), " +
                ".related-posts article:has(a[href]), " +
                ".related a[href]:has(img), " +
                "article:has(a[href]), " +
                ".post:has(a[href]), " +
                ".item:has(a[href])"
        ).mapNotNull { element ->
            element.toSearchResult()
        }.filter { it.url != url }
            .distinctBy { it.url }
            .take(24)

        return newMovieLoadResponse(
            title,
            url,
            TvType.NSFW,
            url
        ) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
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

        collectCandidatesFromDocument(
            document = document,
            baseUrl = pageUrl,
            directLinks = directLinks,
            embedLinks = embedLinks
        )

        collectAjaxPlayers(
            document = document,
            pageUrl = pageUrl,
            directLinks = directLinks,
            embedLinks = embedLinks
        )

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

        val decodedOnce = runCatching {
            URLDecoder.decode(html, "UTF-8")
        }.getOrDefault(html)

        if (decodedOnce != html) {
            extractPlayableUrls(decodedOnce.cleanEscaped()).forEach { raw ->
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        decodeBase64Fragments(html).forEach { decoded ->
            collectCandidatesFromDocument(
                document = Jsoup.parse(decoded),
                baseUrl = pageUrl,
                directLinks = directLinks,
                embedLinks = embedLinks
            )
            extractPlayableUrls(decoded).forEach { raw ->
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        extractSubtitleUrls(html, pageUrl).forEach { subtitle ->
            subtitleCallback(subtitle)
        }

        var found = false

        directLinks
            .filterNot { it.isJunkLink() }
            .distinct()
            .sortedWith(
                compareBy<String> { if (it.isHlsLike()) 0 else 1 }
                    .thenBy { it.length }
            )
            .forEach { link ->
                emitDirectLink(
                    link = link,
                    referer = pageUrl,
                    callback = callback
                )
                found = true
            }

        if (found) return true

        val prioritizedEmbeds = embedLinks
            .filterNot { it.isJunkLink() }
            .filterNot { it == pageUrl }
            .distinct()
            .sortedWith(
                compareBy<String> { hostPriority(it) }
                    .thenBy { it.length }
            )

        for (embed in prioritizedEmbeds.take(12)) {
            val success = loadExtractor(
                embed,
                pageUrl,
                subtitleCallback,
                callback
            )

            if (success) return true

            val nestedLinks = resolveNestedLinks(embed, pageUrl)

            for (nested in nestedLinks) {
                val fixed = normalizeUrl(nested, embed).replace(".txt", ".m3u8")

                when {
                    fixed.isJunkLink() -> Unit

                    fixed.isDirectVideoUrl() -> {
                        emitDirectLink(
                            link = fixed,
                            referer = embed,
                            callback = callback
                        )
                        return true
                    }

                    fixed.startsWith("http", true) -> {
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

    private suspend fun collectAjaxPlayers(
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
                "[data-player][data-id], " +
                "[data-embed][data-id]"
        )

        val ajaxUrls = linkedSetOf(
            "$mainUrl/wp-admin/admin-ajax.php",
            "$mainUrl/admin-ajax.php",
            "$mainUrl/ajax.php",
            "$mainUrl/api/player",
            "$mainUrl/player"
        )

        options.forEach { option ->
            val post = option.attr("data-post").trim()
                .ifBlank { option.attr("data-id").trim() }
            val nume = option.attr("data-nume").trim()
                .ifBlank { option.attr("data-player").trim() }
            val type = option.attr("data-type").trim()
                .ifBlank { option.attr("data-embed").trim() }

            if (post.isBlank()) return@forEach

            val payloads = listOf(
                mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume, "type" to type),
                mapOf("action" to "player_ajax", "id" to post, "server" to nume, "type" to type),
                mapOf("id" to post, "server" to nume, "type" to type)
            )

            ajaxUrls.forEach { ajaxUrl ->
                payloads.forEach { payload ->
                    val text = runCatching {
                        app.post(
                            ajaxUrl,
                            data = payload.filterValues { it.isNotBlank() },
                            headers = headers + mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                "Origin" to mainUrl,
                                "Accept" to "application/json,text/html,text/plain,*/*"
                            ),
                            referer = pageUrl,
                            timeout = 12L
                        ).text.cleanEscaped()
                    }.getOrNull().orEmpty()

                    if (text.isBlank()) return@forEach

                    parsePlayerPayload(
                        text = text,
                        baseUrl = pageUrl,
                        directLinks = directLinks,
                        embedLinks = embedLinks
                    )
                }
            }
        }

        document.select(
            "[data-src], [data-file], [data-video], [data-url], [data-embed], [data-iframe], [data-play], [data-token]"
        ).forEach { element ->
            val raw = element.attr("data-file")
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-play") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-token") }
                .trim()

            addCandidate(raw, pageUrl, directLinks, embedLinks)
        }
    }

    private fun parsePlayerPayload(
        text: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        extractPlayableUrls(text).forEach { raw ->
            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }

        val decoded = runCatching {
            URLDecoder.decode(text, "UTF-8")
        }.getOrDefault(text)

        if (decoded != text) {
            extractPlayableUrls(decoded).forEach { raw ->
                addCandidate(raw, baseUrl, directLinks, embedLinks)
            }
        }

        Jsoup.parse(text).select(
            "iframe[src], iframe[data-src], video[src], source[src], embed[src], object[data], a[href], " +
                "[data-src], [data-video], [data-file], [data-url], [data-embed], [data-iframe]"
        ).forEach { element ->
            val raw = element.attr("data-video")
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }
    }

    private fun collectCandidatesFromDocument(
        document: Document,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        document.select(
            "meta[itemprop=embedURL], " +
                "meta[property=og:video], " +
                "meta[property=og:video:url], " +
                "meta[property=og:video:secure_url], " +
                "meta[name=twitter:player], " +
                "video[src], video[data-src], video[data-video], video[data-file], video[poster], " +
                "video source[src], source[src], source[data-src], " +
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], iframe[data-lazy-src], " +
                "embed[src], object[data], a[href], " +
                "[data-src], [data-video], [data-file], [data-url], [data-embed], [data-iframe]"
        ).forEach { element ->
            val raw = element.attr("content")
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-lazy-src") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            val label = element.text().lowercase()

            if (
                raw.isBlank() ||
                raw.startsWith("#") ||
                raw.startsWith("javascript", true) ||
                label.contains("report") ||
                label.contains("share")
            ) {
                return@forEach
            }

            if (
                element.tagName().equals("meta", true) ||
                element.tagName().equals("video", true) ||
                element.tagName().equals("source", true) ||
                element.tagName().equals("iframe", true) ||
                element.tagName().equals("embed", true) ||
                element.tagName().equals("object", true) ||
                raw.isDirectVideoUrl() ||
                raw.isKnownEmbedHost() ||
                raw.contains("embed", true) ||
                raw.contains("player", true)
            ) {
                addCandidate(raw, baseUrl, directLinks, embedLinks)
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

        if (fixed.isBlank() || fixed.isJunkLink()) return

        when {
            fixed.isDirectVideoUrl() -> directLinks.add(fixed)
            fixed.startsWith("http", true) && fixed.isKnownEmbedHost() -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && fixed.contains("embed", true) -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && fixed.contains("player", true) -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && fixed.contains("stream", true) -> embedLinks.add(fixed)
        }
    }

    private suspend fun resolveNestedLinks(
        url: String,
        referer: String
    ): List<String> {
        if (url.isJunkLink()) return emptyList()

        val response = runCatching {
            app.get(
                url,
                headers = headers + mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Origin" to mainUrl
                ),
                referer = referer,
                timeout = 15L
            )
        }.getOrNull() ?: return emptyList()

        val text = response.text.cleanEscaped()
        val results = linkedSetOf<String>()

        collectCandidatesFromDocument(
            document = response.document,
            baseUrl = url,
            directLinks = results,
            embedLinks = results
        )

        extractPlayableUrls(text).forEach { results.add(normalizeUrl(it, url).replace(".txt", ".m3u8")) }

        val unpacked = runCatching {
            if (!getPacked(text).isNullOrEmpty()) getAndUnpack(text) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach {
                results.add(normalizeUrl(it, url).replace(".txt", ".m3u8"))
            }
        }

        decodeBase64Fragments(text).forEach { decoded ->
            extractPlayableUrls(decoded).forEach {
                results.add(normalizeUrl(it, url).replace(".txt", ".m3u8"))
            }
        }

        return results
            .filterNot { it.isJunkLink() }
            .distinct()
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = link.cleanEscaped().replace(".txt", ".m3u8")

        if (fixed.isBlank() || fixed.isJunkLink()) return

        val linkHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Origin" to originOf(referer),
            "Accept" to "*/*"
        )

        if (fixed.contains(".m3u8", true)) {
            generateM3u8(
                source = name,
                streamUrl = fixed,
                referer = referer,
                headers = linkHeaders
            ).forEach(callback)
        } else {
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
                    } ?: fixed.qualityFromUrl()
                    this.headers = linkHeaders
                }
            )
        }
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val clean = text.cleanEscaped()
        val urls = linkedSetOf<String>()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { it.isJunkLink() }
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
            .filterNot { it.isJunkLink() }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt|jeniusplay|majorplay|dood|streamtape|filemoon|vidhide|voe|mixdrop|streamwish|wishfast|hglink)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map {
                runCatching {
                    URLDecoder.decode(it.value, "UTF-8")
                }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { it.isJunkLink() }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoSource|videoUrl|video_url|playUrl|play_url|hls|hlsUrl|hls_url|stream|streamUrl|stream_url|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter {
                it.isDirectVideoUrl() ||
                    it.isKnownEmbedHost() ||
                    it.contains("embed", true) ||
                    it.contains("player", true)
            }
            .filterNot { it.isJunkLink() }
            .forEach { urls.add(it) }

        Regex(
            """(?:data-file|data-video|data-url|data-src|data-embed|data-iframe|content)=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter {
                it.isDirectVideoUrl() ||
                    it.isKnownEmbedHost() ||
                    it.contains("embed", true) ||
                    it.contains("player", true)
            }
            .filterNot { it.isJunkLink() }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:jeniusplay|majorplay|dood|streamtape|filemoon|vidhide|voe|mixdrop|streamwish|wishfast|hglink|embed|player|stream)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { it.isJunkLink() }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun extractSubtitleUrls(text: String, baseUrl: String): List<SubtitleFile> {
        val clean = text.cleanEscaped()
        val subtitles = mutableListOf<SubtitleFile>()

        Regex(
            """"(?:label|lang|language)"\s*:\s*"([^"]+)"[^}]*?"(?:file|path|url|src)"\s*:\s*"([^"]+\.(?:vtt|srt|ass)(?:\?[^"]*)?)"""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val label = match.groupValues[1].ifBlank { "Subtitle" }
            val link = normalizeUrl(match.groupValues[2], baseUrl)
            subtitles.add(SubtitleFile(label, link))
        }

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:vtt|srt|ass)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            subtitles.add(SubtitleFile("Subtitle", match.value.cleanEscaped()))
        }

        return subtitles.distinctBy { it.url }
    }

    private fun decodeBase64Fragments(text: String): List<String> {
        val results = mutableListOf<String>()

        Regex("""["']([A-Za-z0-9+/=]{40,})["']""")
            .findAll(text)
            .map { it.groupValues[1] }
            .take(20)
            .forEach { token ->
                runCatching {
                    val decoded = String(Base64.getDecoder().decode(token))
                    if (
                        decoded.contains("http", true) ||
                            decoded.contains("iframe", true) ||
                            decoded.contains("video", true) ||
                            decoded.contains("source", true)
                    ) {
                        results.add(decoded.cleanEscaped())
                    }
                }
            }

        return results
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? {
            if (value.isNullOrBlank()) return null

            return value
                .split(",")
                .map { it.trim().substringBefore(" ") }
                .lastOrNull {
                    it.isNotBlank() && !it.isBadImage()
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
            ?.takeIf { !it.isBadImage() }
    }

    private fun getPoster(document: Document): String? {
        return fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "meta[name=twitter:image], " +
                    "meta[itemprop=thumbnailUrl], " +
                    "video[poster], " +
                    ".poster img, " +
                    ".thumb img, " +
                    ".player img, " +
                    "img.wp-post-image, " +
                    "article img, " +
                    "img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    element.hasAttr("poster") -> element.attr("poster")
                    else -> element.getImageAttr()
                }
            }
        )?.takeIf { !it.isBadImage() }
    }

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped().trim()

        return when {
            clean.isBlank() -> ""
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> originOf(baseUrl).trimEnd('/') + clean
            else -> runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrDefault(clean)
        }
    }

    private fun originOf(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(mainUrl)
    }

    private fun hostPriority(url: String): Int {
        val value = url.lowercase()

        return when {
            value.contains("jeniusplay") -> 0
            value.contains("majorplay") -> 1
            value.contains("hglink") -> 2
            value.contains("streamwish") || value.contains("wishfast") -> 3
            value.contains("filemoon") -> 4
            value.contains("vidhide") -> 5
            value.contains("voe.") -> 6
            value.contains("mixdrop") -> 7
            value.contains("streamtape") -> 8
            value.contains("dood") -> 9
            value.contains("embed") -> 20
            value.contains("player") -> 21
            value.contains("stream") -> 22
            else -> 50
        }
    }

    private fun String.isDirectVideoUrl(): Boolean {
        return contains(".m3u8", true) ||
            contains(".mp4", true) ||
            contains(".webm", true)
    }

    private fun String.isHlsLike(): Boolean {
        return contains(".m3u8", true)
    }

    private fun String.isKnownEmbedHost(): Boolean {
        val value = lowercase()

        return listOf(
            "jeniusplay",
            "majorplay",
            "dood",
            "streamtape",
            "filemoon",
            "vidhide",
            "voe.",
            "mixdrop",
            "streamwish",
            "wishfast",
            "hglink",
            "hlswish",
            "lulustream",
            "mp4upload"
        ).any { value.contains(it) }
    }

    private fun String.isJunkLink(): Boolean {
        val value = lowercase()

        return value.isBlank() ||
            value.startsWith("#") ||
            value.startsWith("javascript") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("mailto:") ||
            value.contains("googletagmanager") ||
            value.contains("google-analytics") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("adskeeper") ||
            value.contains("adsterra") ||
            value.contains("/ads/") ||
            value.contains("banner") ||
            value.contains("analytics") ||
            value.contains("tracking")
    }

    private fun String.isBadImage(): Boolean {
        val value = lowercase()

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

    private fun String.isBadTitle(): Boolean {
        val value = trim().lowercase()

        return value.isBlank() ||
            value == "home" ||
            value == "next" ||
            value == "previous" ||
            value == "search" ||
            value == "cari" ||
            value == "category" ||
            value == "tag" ||
            value == "download" ||
            value == "play" ||
            value == "watch" ||
            value == "login" ||
            value == "register" ||
            value.contains("privacy") ||
            value.contains("dmca") ||
            value.contains("contact")
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

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+-\s+LayarBokep.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
