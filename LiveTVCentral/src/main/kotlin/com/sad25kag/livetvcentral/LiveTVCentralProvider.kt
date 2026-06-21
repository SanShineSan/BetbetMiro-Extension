package com.sad25kag.livetvcentral

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class LiveTVCentralProvider : MainAPI() {
    override var mainUrl = "https://livetvcentral.com"
    override var name = "LiveTVCentral"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "indonesia" to "Indonesia",
        "malaysia" to "Malaysia",
        "singapore" to "Singapore",
        "philippines" to "Philippines",
        "thailand" to "Thailand",
        "vietnam" to "Vietnam",
        "japan" to "Japan",
        "south-korea" to "South Korea",
        "india" to "India",
        "united-states" to "United States"
    )

    // Sitemap-sourced channel list cache, keyed by country slug
    private val countryCache = ConcurrentHashMap<String, List<LiveChannel>>()
    // Flag to avoid fetching sitemap more than once per session
    @Volatile private var sitemapLoaded = false

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache"
    )

    // ─── MainPage ────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val countrySlug = request.data

        // Load sitemap once to populate all country caches
        if (!sitemapLoaded) {
            runCatching { loadSitemap() }.onFailure { logError(it) }
        }

        val channels = countryCache[countrySlug] ?: emptyList()
        val start = ((page.coerceAtLeast(1) - 1) * PAGE_SIZE).coerceAtLeast(0)
        val pageItems = channels.drop(start).take(PAGE_SIZE).map { it.toSearchResponse() }

        return newHomePageResponse(
            HomePageList(request.name, pageItems, isHorizontalImages = true),
            hasNext = channels.size > start + PAGE_SIZE
        )
    }

    // ─── Search ───────────────────────────────────────────────────────────────────

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim().lowercase(Locale.ROOT)
        if (keyword.length < 2) return emptyList()

        if (!sitemapLoaded) {
            runCatching { loadSitemap() }.onFailure { logError(it) }
        }

        return countryCache.values
            .flatten()
            .filter { it.title.lowercase(Locale.ROOT).contains(keyword) }
            .distinctBy { it.channelUrl }
            .take(SEARCH_LIMIT)
            .map { it.toSearchResponse() }
    }

    // ─── Load ────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        // url is the real /tv/SLUG URL
        val channelUrl = url.substringBefore("?")

        val document = runCatching {
            app.get(channelUrl, headers = siteHeaders, referer = "$mainUrl/", timeout = 25L).document
        }.getOrElse { throw ErrorLoadingException("Failed to load: $channelUrl") }

        // Title: from breadcrumb last item, og:title, or h1
        val title = document.selectFirst("ol.breadcrumb li:last-child, .breadcrumb li:last-child")
            ?.text()?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.replace(Regex("""[\u00bb\u00ab\u300a\u300b]\s*|【[^】]*】|\s*Live\s*TV\s*Central.*""", RegexOption.IGNORE_CASE), "")
                ?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: channelUrl.slugTitle()

        // Poster: channel logo from /imgs/tvs/ pattern (evidence: img/tvs/581.jpg)
        val poster = document.selectFirst("img[src*='/imgs/tvs/']")?.absUrl("src")
            ?: document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.toAbsoluteUrl(mainUrl)

        // Description: meta description or first paragraph
        val description = document.selectFirst("meta[name=description]")
            ?.attr("content")?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst(".channel-description, .description, article p, main p")
                ?.text()?.trim()

        // Country: from breadcrumb second-to-last link pointing to /country/
        val countryLink = document.select("ol.breadcrumb a[href*='/country/'], .breadcrumb a[href*='/country/']").lastOrNull()
        val countrySlug = countryLink?.attr("href")?.substringAfterLast("/country/")?.trimEnd('/') ?: ""
        val countryName = countryLink?.text()?.trim() ?: ""

        // Collect playback candidates from detail page
        val playbackData = buildPlaybackData(document, channelUrl)

        return newLiveStreamLoadResponse(
            name = title,
            url = channelUrl,
            dataUrl = playbackData
        ).apply {
            posterUrl = poster
            plot = buildString {
                if (countryName.isNotBlank()) append(countryName).append("\n")
                if (!description.isNullOrBlank()) append(description)
            }.trim()
        }
    }

    // ─── LoadLinks ───────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is a pipe-separated list of candidate URLs: "url1|url2|..."
        // First entry is the channel URL (referer), rest are playback candidates
        val parts = data.split("|").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return false

        val channelUrl = parts.first()
        val candidates = parts.drop(1).distinct()

        // If no candidates were pre-resolved, re-fetch the detail page
        val resolved = candidates.ifEmpty {
            runCatching {
                val doc = app.get(channelUrl, headers = siteHeaders, referer = "$mainUrl/", timeout = 25L).document
                extractPlaybackCandidates(doc, channelUrl)
            }.getOrDefault(emptyList())
        }

        var emitted = false

        for (candidate in resolved.take(MAX_LINKS_PER_CHANNEL)) {
            when {
                // Direct m3u8
                candidate.lowercase().contains(".m3u8") -> {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name (HLS)",
                            url = candidate,
                            type = ExtractorLinkType.M3U8
                        ) {
                            quality = Qualities.Unknown.value
                            referer = channelUrl
                            headers = playbackHeaders(channelUrl)
                        }
                    )
                    emitted = true
                }
                // Direct mp4
                candidate.lowercase().substringBefore("?").endsWith(".mp4") -> {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name (MP4)",
                            url = candidate,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            quality = Qualities.Unknown.value
                            referer = channelUrl
                            headers = playbackHeaders(channelUrl)
                        }
                    )
                    emitted = true
                }
                // Known embeds: try CloudStream extractor
                candidate.isKnownEmbedHost() -> {
                    val didLoad = runCatching {
                        loadExtractor(candidate, channelUrl, subtitleCallback, callback)
                    }.getOrDefault(false)
                    if (didLoad) emitted = true
                }
                // Fallback: emit as VIDEO link so CloudStream can open in WebView
                else -> {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name (Web)",
                            url = candidate,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            quality = Qualities.Unknown.value
                            referer = channelUrl
                            headers = playbackHeaders(channelUrl)
                        }
                    )
                    emitted = true
                }
            }
        }

        return emitted
    }

    // ─── Sitemap loader ──────────────────────────────────────────────────────────

    /**
     * Fetches /sitemap.html once and populates countryCache for all supported countries.
     *
     * Evidence-backed sitemap structure (2026-06-21):
     *   <ul><li>
     *     <a href="/country/SLUG">X Tv Channels</a>  ← country heading
     *     <ul><li><a href="/tv/SLUG">Channel Title</a></li>...</ul>
     *   </li></ul>
     *
     * Channel URLs: /tv/SLUG
     * No poster available in sitemap — loaded lazily on detail page.
     */
    private suspend fun loadSitemap() {
        val document = app.get(
            "$mainUrl/sitemap.html",
            headers = siteHeaders,
            referer = "$mainUrl/",
            timeout = 30L
        ).document

        val tempMap = mutableMapOf<String, MutableList<LiveChannel>>()

        // Supported country slugs set for fast lookup
        val supportedSlugs = countries.map { it.slug }.toSet()

        // Walk sitemap: each country block is a <li> containing an <a href="/country/SLUG">
        // followed by a nested <ul> of channel links
        document.select("li").forEach { li ->
            val countryLink = li.selectFirst("> a[href*='/country/']")
                ?: li.selectFirst("a[href*='/country/']") ?: return@forEach

            val countrySlug = countryLink.attr("href")
                .substringAfterLast("/country/").trimEnd('/')
                .lowercase(Locale.ROOT)

            if (countrySlug !in supportedSlugs) return@forEach

            val countryName = countries.firstOrNull { it.slug == countrySlug }?.name
                ?: countryLink.text().trim()
                    .replace(Regex("\\s*[Tt]v\\s*[Cc]hannels?"), "")
                    .trim()

            val country = Country(countrySlug, countryName)
            val list = tempMap.getOrPut(countrySlug) { mutableListOf() }

            // Channel links are nested <a href="/tv/SLUG">
            li.select("a[href*='/tv/']").forEach { channelLink ->
                val channelPath = channelLink.attr("href")
                if (!channelPath.startsWith("/tv/")) return@forEach
                val channelUrl = "$mainUrl$channelPath"
                val title = channelLink.text().trim().ifBlank { channelPath.slugTitle() }
                if (title.isBlank()) return@forEach

                list += LiveChannel(
                    title = title,
                    country = country,
                    channelUrl = channelUrl,
                    posterUrl = null // loaded on demand from detail page
                )
            }
        }

        // Push into cache; keep original sitemap order
        tempMap.forEach { (slug, channels) ->
            countryCache[slug] = channels.distinctBy { it.channelUrl }
        }

        sitemapLoaded = true
    }

    // ─── Detail page helpers ─────────────────────────────────────────────────────

    /**
     * Builds playback data string from detail page.
     * Format: "channelUrl|candidate1|candidate2|..."
     *
     * Evidence from /tv/sctv:
     * - Player iframe/embed URL appears as plain <a href="..."> in body
     * - "Open Live Stream" link: <a href="https://www.sctv.co.id/live-streaming">
     * - Other channels listed at bottom as related: <a href="/tv/..."><img ...>
     */
    private fun buildPlaybackData(document: Document, channelUrl: String): String {
        val candidates = extractPlaybackCandidates(document, channelUrl)
        return (listOf(channelUrl) + candidates).joinToString("|")
    }

    private fun extractPlaybackCandidates(document: Document, channelUrl: String): List<String> {
        val candidates = linkedSetOf<String>()

        // 1. Direct media URLs in page text
        val pageText = document.html()
        MEDIA_URL_REGEX.findAll(pageText).forEach { match ->
            match.value.unescapeJs().toAbsoluteUrl(channelUrl)?.let { candidates.add(it) }
        }

        // 2. All <a href> that look like player/stream pages (not navigation/social)
        document.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") || href.startsWith("mailto:") ||
                href.startsWith("tel:") || href.startsWith("javascript:")) return@forEach

            val abs = href.toAbsoluteUrl(channelUrl) ?: return@forEach

            // Skip: same-site navigation, social, footer links
            if (SKIP_PATTERNS.any { abs.lowercase().contains(it) }) return@forEach

            // Include: embed/player/stream URLs from other domains OR direct media
            val absLower = abs.lowercase(Locale.ROOT)
            when {
                abs.isDirectMedia() -> candidates.add(abs)
                !abs.startsWith(mainUrl) && PLAYER_HINTS.any { absLower.contains(it) } -> candidates.add(abs)
                abs.isKnownEmbedHost() -> candidates.add(abs)
                a.text().contains("Open Live Stream", ignoreCase = true) ||
                    a.attr("title").contains("Open", ignoreCase = true) -> candidates.add(abs)
            }
        }

        // 3. iframes
        document.select("iframe[src]").forEach { iframe ->
            iframe.attr("src").toAbsoluteUrl(channelUrl)
                ?.takeIf { it.isNotBlank() && !it.startsWith(mainUrl) }
                ?.let { candidates.add(it) }
        }

        // 4. video/source tags
        document.select("video[src], source[src]").forEach { el ->
            el.attr("src").toAbsoluteUrl(channelUrl)?.let { candidates.add(it) }
        }

        return candidates.toList()
    }

    // ─── SearchResponse helper ─────────────────────────────────────────────────────

    private fun LiveChannel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(
            name = "${title} • ${country.name}",
            url = channelUrl, // real /tv/SLUG URL
            type = TvType.Live
        ).apply {
            posterUrl = this@toSearchResponse.posterUrl
        }
    }

    private fun playbackHeaders(referer: String): Map<String, String> {
        val origin = referer.originOrNull()
        return buildMap {
            put("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,video/mp4,*/*")
            put("User-Agent", USER_AGENT)
            if (referer.isNotBlank()) put("Referer", referer)
            if (!origin.isNullOrBlank()) put("Origin", origin)
        }
    }

    // ─── Data classes ───────────────────────────────────────────────────────────────

    data class Country(
        val slug: String,
        val name: String
    )

    private data class LiveChannel(
        val title: String,
        val country: Country,
        val channelUrl: String,
        val posterUrl: String?
    )

    // ─── Constants ────────────────────────────────────────────────────────────────

    companion object {
        private const val PAGE_SIZE = 48
        private const val SEARCH_LIMIT = 80
        private const val MAX_LINKS_PER_CHANNEL = 8

        private val countries = listOf(
            Country("indonesia", "Indonesia"),
            Country("malaysia", "Malaysia"),
            Country("singapore", "Singapore"),
            Country("philippines", "Philippines"),
            Country("thailand", "Thailand"),
            Country("vietnam", "Vietnam"),
            Country("japan", "Japan"),
            Country("south-korea", "South Korea"),
            Country("india", "India"),
            Country("united-states", "United States")
        )

        // Known embed/player host fragments that CloudStream extractors may handle
        val KNOWN_EMBED_HOSTS = listOf(
            "youtube.com", "youtu.be",
            "dailymotion.com", "dai.ly",
            "facebook.com/video", "fb.watch",
            "vidio.com", "vimeo.com",
            "streamable.com", "ok.ru",
            "filmon.com", "ustream.tv",
            "twitch.tv", "kick.com"
        )

        private val PLAYER_HINTS = listOf(
            "embed", "player", "stream", "live", "watch", "video"
        )

        private val SKIP_PATTERNS = listOf(
            "/country/", "/about", "/privacy", "/dmca", "/sitemap",
            "/addtv", "/contact", "facebook.com/LiveTv",
            "twitter.com", "instagram.com", "pinterest.",
            "livetvcentral.com/imgs/", "#"
        )

        private val MEDIA_URL_REGEX = Regex(
            """https?:\\?/\\?/[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""",
            RegexOption.IGNORE_CASE
        )
    }
}

// ─── Extension functions ──────────────────────────────────────────────────────────────────

private fun String.toAbsoluteUrl(baseUrl: String): String? {
    val value = trim().trim('"', '\'', ' ')
    if (value.isBlank() || value.startsWith("javascript:", true) || value.startsWith("#")) return null
    return runCatching {
        when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            else -> URI(baseUrl).resolve(value).toString()
        }
    }.getOrNull()
}

private fun String.isDirectMedia(): Boolean {
    val lower = substringBefore("#").substringBefore("?").lowercase(Locale.ROOT)
    return lower.endsWith(".m3u8") || lower.endsWith(".mp4")
}

private fun String.isKnownEmbedHost(): Boolean {
    val lower = lowercase(Locale.ROOT)
    return LiveTVCentralProvider.KNOWN_EMBED_HOSTS.any { lower.contains(it) }
}

private fun String.slugTitle(): String {
    return substringBefore("?").substringBefore("#")
        .trimEnd('/')
        .substringAfterLast('/')
        .replace('-', ' ')
        .replace('_', ' ')
        .trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
}

private fun String.unescapeJs(): String {
    return replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
}

private fun String.originOrNull(): String? {
    return runCatching {
        val uri = URI(this)
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()
}

private val String.KNOWN_EMBED_HOSTS: List<String>
    get() = LiveTVCentralProvider.KNOWN_EMBED_HOSTS
