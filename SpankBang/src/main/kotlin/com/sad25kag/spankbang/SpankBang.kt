package com.sad25kag.spankbang

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SpankBang : MainAPI() {
    override var mainUrl = "https://spankbang.party"
    override var name = "SpankBang"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Referer" to "$mainUrl/",
        "Cookie" to "age_pass=1; cookie_consent_required=0; show_cookie_consent_modal=0; cfc_ok=00|1|en|spankbang|master|0"
    )

    private val streamHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "$mainUrl/s/verified+creators/?o=trending&p=m" to "Creators",
        "$mainUrl/s/asian/" to "Asian",
        "$mainUrl/s/japanese/" to "Japanese",
        "$mainUrl/s/amateur/" to "Amateur",
        "$mainUrl/s/big+tits/" to "Big Tits",
        "$mainUrl/s/milf/" to "MILF",
        "$mainUrl/s/anal/" to "Anal",
        "$mainUrl/s/blowjob/" to "Blowjob",
        "$mainUrl/s/lesbian/" to "Lesbian",
        "$mainUrl/s/hentai/" to "Hentai",
        "$mainUrl/s/vr/" to "VR"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(pageUrl(request.data, page), headers = siteHeaders).document
        val results = document.parseVideoItems()
        return newHomePageResponse(request.name, results, hasNext = document.hasNextPage())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = withContext(Dispatchers.IO) {
            URLEncoder.encode(query.trim(), "UTF-8")
        }
        if (encodedQuery.isBlank()) return emptyList()

        val document = app.get("$mainUrl/s/$encodedQuery/", headers = siteHeaders).document
        return document.parseVideoItems()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = siteHeaders).document
        val html = document.html()

        val title = document.selectFirst("h1, [data-testid=video-title]")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content")
                ?.substringBefore(": Porn")
                ?.trim()
            ?: return null

        val poster = listOfNotNull(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            streamField(html, "cover_image"),
            streamField(html, "thumbnail")
        ).firstOrNull { it.isNotBlank() }?.absoluteUrl()

        val plot = listOfNotNull(
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("meta[name=description]")?.attr("content")
        ).firstOrNull { it.isNotBlank() }?.trim()

        val tags = parsePageTags(html).ifEmpty {
            document.select("a[href^=/s/]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && it.length <= 40 }
                .distinct()
                .take(12)
        }

        val durationMinutes = document.selectFirst("meta[property=og:video:duration]")
            ?.attr("content")
            ?.toIntOrNull()
            ?.let { seconds -> (seconds + 59) / 60 }
            ?: streamField(html, "length")?.toIntOrNull()?.let { seconds -> (seconds + 59) / 60 }

        val actors = document.select("a[href*=/channel/], a[href*=/pornstar/], a[href*=/profile/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length in 2..60 }
            .distinct()
            .take(8)
            .map { Actor(it) }

        val recommendations = document.parseVideoItems().filter { it.url != url }.take(24)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.duration = durationMinutes
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.takeIf { it.startsWith("http", true) } ?: return false
        val html = app.get(pageUrl, headers = siteHeaders).text
        val emitted = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, rawLabel: String?) {
            val videoUrl = rawUrl?.cleanStreamUrl()?.absoluteUrl() ?: return
            if (!videoUrl.startsWith("http", true)) return
            if (!emitted.add(videoUrl)) return

            val label = rawLabel?.cleanLabel().orEmpty().ifBlank {
                qualityLabelFromUrl(videoUrl).ifBlank { "Auto" }
            }
            val quality = getQualityFromName(label)

            if (videoUrl.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = videoUrl,
                    referer = "$mainUrl/",
                    headers = streamHeaders
                ).forEach { link ->
                    callback(link)
                }
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name $label",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = "$mainUrl/"
                        this.headers = streamHeaders
                    }
                )
            }
        }

        for ((label, urls) in parseStreamData(html)) {
            for (url in urls) {
                emitDirect(url, label)
            }
        }

        // Fallback for future markup changes: direct media URLs still embedded in the page.
        val mediaRegex = Regex("""https?://[^'"<>\\\s]+?\.(?:m3u8|mp4|webm)(?:\?[^'"<>\\\s]+)?""", RegexOption.IGNORE_CASE)
        for (match in mediaRegex.findAll(html)) {
            emitDirect(match.value, qualityLabelFromUrl(match.value))
        }

        return emitted.isNotEmpty()
    }

    private fun Document.parseVideoItems(): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        fun collectFromList(list: Element?) {
            list?.children()?.forEach { element ->
                val isVideoItem =
                    element.attr("data-testid").equals("video-item", true) ||
                        element.hasClass("js-video-item")

                if (isVideoItem) {
                    element.toSearchResponse()?.let { response -> results[response.url] = response }
                }
            }
        }

        // Keep each homepage/category section scoped to its primary grid only.
        // The page also contains global recommendation/creator grids; parsing the full document
        // makes different categories show the same videos.
        val primaryList = selectFirst("#search_page [data-testid=search-result] [data-testid=video-list]")
            ?: selectFirst("#home > [data-testid=video-list]")
            ?: selectFirst("#playlist_page [data-testid=video-list]")

        collectFromList(primaryList)

        if (results.isEmpty()) {
            val fallbackList = selectFirst("#inner_content [data-testid=video-list], #inner_content .js-media-list")
            collectFromList(fallbackList)
        }

        if (results.isEmpty()) {
            val fallbackScope = selectFirst("#search_page, #home, #playlist_page") ?: this
            fallbackScope.select("a[href*=/video/]").forEach { anchor ->
                anchor.toFallbackSearchResponse()?.let { response -> results[response.url] = response }
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = selectFirst("a[href*=/video/]") ?: return null
        val href = anchor.attr("href").trim().absoluteUrl()
        if (!href.contains("/video/")) return null

        val title = listOf(
            selectFirst("a[title]")?.attr("title"),
            anchor.attr("title"),
            selectFirst("img[alt]")?.attr("alt"),
            selectFirst("[data-testid=title]")?.text(),
            selectFirst("p a span")?.text(),
            anchor.text()
        ).firstCleanTitle() ?: return null

        val poster = listOf(
            selectFirst("img[src]")?.attr("src"),
            selectFirst("img[data-src]")?.attr("data-src"),
            selectFirst("img[data-original]")?.attr("data-original"),
            selectFirst("source[data-src]")?.attr("data-src")
        ).firstOrNull { !it.isNullOrBlank() }?.absoluteUrl()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun Element.toFallbackSearchResponse(): SearchResponse? {
        val href = attr("href").trim().absoluteUrl()
        if (!href.contains("/video/")) return null

        val title = listOf(attr("title"), selectFirst("img[alt]")?.attr("alt"), text()).firstCleanTitle()
            ?: return null
        val poster = selectFirst("img[src], img[data-src]")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }.absoluteUrl()
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun pageUrl(url: String, page: Int): String {
        if (page <= 1) return url
        val normalized = url.trimEnd('/')
        val queryIndex = normalized.indexOf('?')
        return if (queryIndex >= 0) {
            val path = normalized.substring(0, queryIndex).trimEnd('/')
            val query = normalized.substring(queryIndex)
            "$path/$page/$query"
        } else {
            "$normalized/$page/"
        }
    }

    private fun Document.hasNextPage(): Boolean {
        return select("a[href]").any { element ->
            val text = element.text().trim()
            text.equals("Next", true) || text == "›" || text == "»" || text.toIntOrNull() != null
        }
    }

    private fun parseStreamData(html: String): List<Pair<String, List<String>>> {
        val block = Regex(
            """var\s+stream_data\s*=\s*\{(.*?)\}\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.getOrNull(1).orEmpty()

        if (block.isBlank()) return emptyList()

        return Regex(
            """['"]([^'"]+)['"]\s*:\s*\[(.*?)]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(block).mapNotNull { match ->
            val key = match.groupValues[1].trim()
            val urls = Regex("""['"](https?://[^'"]+)['"]""")
                .findAll(match.groupValues[2])
                .map { it.groupValues[1] }
                .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains(".webm", true) }
                .toList()

            if (urls.isEmpty()) null else key to urls
        }.toList()
    }

    private fun streamField(html: String, key: String): String? {
        return Regex("""['"]${Regex.escape(key)}['"]\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.cleanStreamUrl()
    }

    private fun parsePageTags(html: String): List<String> {
        return Regex("""var\s+page_matadata\s*=\s*\[(.*?)]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { raw ->
                Regex("""['"]([^'"]+)['"]""").findAll(raw)
                    .map { it.groupValues[1].trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(12)
                    .toList()
            }
            .orEmpty()
    }

    private fun qualityLabelFromUrl(url: String): String {
        return Regex("""(2160|1440|1080|720|480|360|320|240)p""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.value
            .orEmpty()
    }

    private fun String.cleanStreamUrl(): String {
        return htmlUnescape()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.cleanLabel(): String {
        return replace("m3u8_", "", true)
            .replace("_", " ")
            .replace("main", "Auto", true)
            .replace("mpd", "Dash", true)
            .trim()
    }

    private fun String.absoluteUrl(): String {
        val value = trim().cleanStreamUrl()
        if (value.startsWith("//")) return "https:$value"
        return if (value.startsWith("http", true)) value else fixUrl(value)
    }

    private fun List<String?>.firstCleanTitle(): String? {
        return firstOrNull { value ->
            !value.isNullOrBlank() &&
                !value.equals("HD", true) &&
                !value.equals("Exclusive", true) &&
                !value.equals("Videos", true)
        }?.htmlUnescape()?.trim()
    }

    private fun String.htmlUnescape(): String {
        return org.jsoup.parser.Parser.unescapeEntities(this, false)
    }
}
