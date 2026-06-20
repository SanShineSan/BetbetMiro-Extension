package com.sad25kag.ayononton

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * AyoNonton Provider — ayononton.live
 *
 * Source family: WordPress + MuviPro/idmuvi theme using the Terbit21 player API.
 * Main resolver rule: never use play-ads.php as a final player. It exposes a loading
 * placeholder; the usable player is the real t21 endpoint behind the iframe/server type.
 */
class AyoNonton : MainAPI() {

    override var mainUrl = "https://ayononton.live"
    override var name = "AyoNonton"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val t21Api = "https://t21.press"

    override val mainPage = mainPageOf(
        "$mainUrl/latest/"              to "Terbaru",
        "$mainUrl/populer/"             to "Populer",
        "$mainUrl/trending-minggu-ini/" to "Trending Minggu Ini",
        "$mainUrl/rating/"              to "Rating Tertinggi",
        "$mainUrl/genre/action/"        to "Aksi",
        "$mainUrl/genre/drama/"         to "Drama",
        "$mainUrl/genre/comedy/"        to "Komedi",
        "$mainUrl/genre/horror/"        to "Horor",
        "$mainUrl/genre/thriller/"      to "Thriller",
        "$mainUrl/genre/romance/"       to "Romansa",
        "$mainUrl/genre/animation/"     to "Animasi",
        "$mainUrl/genre/sci-fi/"        to "Sci-Fi",
        "$mainUrl/genre/biography/"     to "Biografi",
        "$mainUrl/genre/crime/"         to "Kriminal",
        "$mainUrl/genre/fantasy/"       to "Fantasi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val doc = app.get(url, headers = siteHeaders(url)).document
        val items = parseListingPage(doc)
        val hasMore = doc.selectFirst("a.next.page-numbers, .pagination .next, a[rel=next]") != null

        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = hasMore,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.encodeUri()}"
        return app.get(url, headers = siteHeaders(url)).document.let(::parseListingPage)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = siteHeaders(url)).document
        val slug = slugFrom(url)
        val rawTitle = doc.selectFirst("h1[itemprop=name], h1.entry-title, h1")?.text()?.trim().orEmpty()
            .ifBlank { slug }
        val title = rawTitle.replace(Regex("""\s*\(\d{4}\)\s*$"""), "").trim().ifBlank { rawTitle }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img[itemprop=image], .gmr-movie-data img, .content-thumbnail img")?.let(::imageFrom)
        val description = doc.selectFirst(
            "div.entry-content.clearfix, div.entry-content[itemprop=description], div.entry-content, div#synopsis, div.gmr-sinopsis"
        )?.text()?.trim()

        val genres = mutableListOf<String>()
        var year: Int? = null
        var duration: String? = null
        var rating: String? = null

        doc.select("div.gmr-moviedata, div.gmr-movie-data").forEach { div ->
            val label = div.selectFirst("strong")?.text()?.lowercase()?.trim().orEmpty()
            val value = div.text().removePrefix(div.selectFirst("strong")?.text().orEmpty()).trim()
            when {
                label.contains("genre") -> div.select("a[href*=/genre/]").forEach { genres.add(it.text()) }
                label.contains("tahun") -> year = value.take(4).toIntOrNull()
                label.contains("durasi") -> duration = value
                label.contains("rating") -> rating = div.selectFirst("span[itemprop=ratingValue], .imdb-rating, span")
                    ?.text()?.trim()?.ifBlank { null }
            }
        }

        if (year == null) {
            year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        val episodes = parseEpisodes(doc, url, poster, description)
        val isSeries = episodes.size > 1 || looksLikeSeries(title, doc)
        val playData = "$mainUrl/||$slug"

        return if (isSeries) {
            val finalEpisodes = episodes.ifEmpty {
                listOf(newEpisode(playData) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = poster
                    this.description = description
                })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres.distinct()
                this.score = Score.from10(rating)
                this.duration = duration?.filter { it.isDigit() }?.toIntOrNull()
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, playData) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres.distinct()
                this.score = Score.from10(rating)
                this.duration = duration?.filter { it.isDigit() }?.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val slug = slugFrom(data.substringAfterLast("||"))
        if (slug.isBlank()) return false

        val detailReferer = "$mainUrl/$slug/"
        var loaded = false

        val apiHtml = runCatching {
            app.post(
                "$t21Api/data.php",
                headers = formHeaders(detailReferer, mainUrl),
                data = mapOf("movie" to slug),
            ).text
        }.getOrNull()

        if (!apiHtml.isNullOrBlank() && apiHtml.trim() != "none") {
            val servers = parseServerEntries(apiHtml, slug)
            for (server in servers) {
                val found = when (server.kind) {
                    PlayerKind.HYDRAX -> loadHydrax(server.url, detailReferer, server.name, subtitleCallback, callback)
                    PlayerKind.P2P,
                    PlayerKind.GDFRAME,
                    PlayerKind.OTHER -> loadPlayerPage(server.url, detailReferer, server.name, subtitleCallback, callback)
                }
                loaded = found || loaded
            }
        }

        loaded = loadDownloadLinks(slug, callback) || loaded
        return loaded
    }

    private fun parseListingPage(doc: Document): List<SearchResponse> {
        return doc.select("article.item, article.item-infinite").mapNotNull { art ->
            val anchor = art.selectFirst("a[itemprop=url], .entry-title a[href], a[href]") ?: return@mapNotNull null
            val href = anchor.absUrl("href").ifBlank { normalizeUrl(doc.location(), anchor.attr("href")) }
                ?.takeIf { it.contains(mainUrl) } ?: return@mapNotNull null
            val rawTitle = anchor.attr("title").ifBlank { anchor.text() }
            val title = cleanTitle(rawTitle).ifBlank { slugFrom(href).replace('-', ' ') }
            val poster = bestPoster(art)
            val type = if (looksLikeSeries(title, art)) TvType.TvSeries else TvType.Movie

            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }
    }

    private fun parseEpisodes(doc: Document, detailUrl: String, poster: String?, description: String?): List<Episode> {
        val selector = listOf(
            ".gmr-listseries a[href]",
            ".gmr-episode a[href]",
            ".episode-list a[href]",
            ".eplister a[href]",
            "a[href*='/episode/']",
            "a[href*='/eps/']"
        ).joinToString(",")

        return doc.select(selector).mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { normalizeUrl(detailUrl, a.attr("href")) } ?: return@mapNotNull null
            if (!href.contains(mainUrl) || href == detailUrl) return@mapNotNull null
            val text = a.attr("title").ifBlank { a.text() }.trim()
            val number = episodeNumber(text.ifBlank { href })
            newEpisode(href) {
                this.name = text.ifBlank { "Episode ${number ?: 1}" }
                this.season = 1
                this.episode = number
                this.posterUrl = poster
                this.description = description
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun parseServerEntries(apiHtml: String, slug: String): List<ServerEntry> {
        val doc = Jsoup.parse(apiHtml, t21Api)
        return doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { normalizeUrl(t21Api, a.attr("href")) } ?: return@mapNotNull null
            if (href.contains("#download", true) || a.attr("rel").contains("download", true)) return@mapNotNull null
            val cssClass = a.attr("class").uppercase().trim()
            val label = a.text().trim().ifBlank { cssClass.ifBlank { "SERVER" } }
            val iframe = href.substringAfter("iframe=", "").substringBefore("&").lowercase()
            val probe = "$cssClass $iframe ${a.text()}".lowercase()
            val kind = when {
                probe.contains("hydrax") -> PlayerKind.HYDRAX
                probe.contains("gdframe") || probe.contains("gd-frame") -> PlayerKind.GDFRAME
                probe.contains("p2p") || probe.contains("drive") -> PlayerKind.P2P
                else -> PlayerKind.OTHER
            }
            val realUrl = when (kind) {
                PlayerKind.HYDRAX -> "$t21Api/g-hydrax.php?movie=$slug"
                PlayerKind.GDFRAME -> "$t21Api/gdframe.php?movie=$slug"
                PlayerKind.P2P -> "$t21Api/p2p.php?movie=$slug"
                PlayerKind.OTHER -> href.takeIf { !it.contains("play-ads.php", true) } ?: "$t21Api/p2p.php?movie=$slug"
            }
            ServerEntry(label, realUrl, kind)
        }.distinctBy { it.url }
    }

    private suspend fun loadHydrax(
        playerUrl: String,
        detailReferer: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching {
            app.get(playerUrl, headers = siteHeaders(playerUrl, detailReferer)).text
        }.getOrNull().orEmpty()
        val iframe = extractIframeUrl(html)
        return if (!iframe.isNullOrBlank()) {
            runCatching { loadExtractor(iframe, playerUrl, subtitleCallback, callback) }.getOrDefault(false)
        } else {
            emitMediaLink(name, "$name [$serverName]", extractMediaUrl(html), playerUrl, callback)
        }
    }

    private suspend fun loadPlayerPage(
        playerUrl: String,
        detailReferer: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching {
            app.get(playerUrl, headers = siteHeaders(playerUrl, detailReferer)).text
        }.getOrNull().orEmpty()

        extractIframeUrl(html)?.let { iframe ->
            val extracted = runCatching { loadExtractor(iframe, playerUrl, subtitleCallback, callback) }.getOrDefault(false)
            if (extracted) return true
        }

        extractMediaUrl(html)?.let { media ->
            if (emitMediaLink(name, "$name [$serverName]", media, playerUrl, callback)) return true
        }

        val resolver = WebViewResolver(
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE),
            userAgent = ua,
            useOkhttp = true,
        )
        val captured = runCatching {
            app.get(
                playerUrl,
                headers = siteHeaders(playerUrl, detailReferer),
                interceptor = resolver,
                timeout = 60L,
            ).url
        }.getOrNull()

        return emitMediaLink(name, "$name [$serverName]", captured, playerUrl, callback)
    }

    private suspend fun loadDownloadLinks(slug: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = runCatching {
            app.post(
                "$t21Api/verifying.php?movie=$slug",
                headers = formHeaders("https://terbit21.tv/get/?movie=$slug", "https://terbit21.tv"),
                data = mapOf("movie" to slug),
            ).text
        }.getOrNull().orEmpty()
        if (html.isBlank()) return false

        val doc = Jsoup.parse(html, t21Api)
        var found = false
        doc.select("table a[href], a[href*='download'], a[href*='openx']").forEach { a ->
            val href = a.absUrl("href").ifBlank { normalizeUrl(t21Api, a.attr("href")) } ?: return@forEach
            if (href.contains("facebook", true) || href.contains("twitter", true) ||
                href.contains("t.me", true) || href.contains("subscene", true)) return@forEach
            val rowText = a.closest("tr")?.text()?.uppercase().orEmpty()
            val quality = qualityFromText(rowText)
            val provider = a.closest("tr")?.selectFirst("td")?.text()?.trim()?.ifBlank { "Download" } ?: "Download"
            callback.invoke(
                newExtractorLink(name, "$name [DL] $provider", href, ExtractorLinkType.VIDEO) {
                    this.referer = "https://terbit21.tv/"
                    this.quality = quality
                    this.headers = siteHeaders(href, "https://terbit21.tv/")
                }
            )
            found = true
        }
        return found
    }

    private suspend fun emitMediaLink(
        source: String,
        label: String,
        rawUrl: String?,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val url = rawUrl?.replace("\\/", "/")?.replace("&amp;", "&")?.trim().orEmpty()
        if (url.isBlank() || !url.startsWith("http", true)) return false
        return if (url.contains(".m3u8", true)) {
            val links = runCatching {
                generateM3u8(
                    source = source,
                    streamUrl = url,
                    referer = referer,
                    headers = siteHeaders(url, referer),
                )
            }.getOrDefault(emptyList())
            if (links.isNotEmpty()) {
                links.forEach(callback)
                true
            } else {
                callback.invoke(
                    newExtractorLink(source, label, url, ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.quality = qualityFromText(url)
                        this.headers = siteHeaders(url, referer)
                    }
                )
                true
            }
        } else {
            callback.invoke(
                newExtractorLink(source, label, url, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = qualityFromText(url)
                    this.headers = siteHeaders(url, referer)
                }
            )
            true
        }
    }

    private fun extractMediaUrl(html: String): String? {
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")
        listOf(
            Regex("""["']file["']\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:src|source)["']\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        ).forEach { regex ->
            regex.find(normalized)?.groupValues?.getOrNull(1)
                ?.takeIf { !it.contains("github.com", true) && !it.contains("loading", true) }
                ?.let { return it }
        }
        return null
    }

    private fun extractIframeUrl(html: String): String? {
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")
        return Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)
            ?.takeIf { !it.contains("ayononton", true) && !it.contains("terbit21.com", true) }
            ?: Regex("""["'](https?://(?:www\.)?playhydrax\.com/[^"'\s]+)["']""", RegexOption.IGNORE_CASE)
                .find(normalized)?.groupValues?.getOrNull(1)
    }

    private fun bestPoster(el: Element): String? {
        el.selectFirst("source[srcset]")?.attr("srcset")
            ?.split(",")?.lastOrNull()?.trim()?.split("\\s+".toRegex())
            ?.firstOrNull { it.startsWith("http") }
            ?.let { return it }
        el.selectFirst("img[itemprop=image], img[data-src], img[data-lazy-src], img[src]")?.let(::imageFrom)?.let { return it }
        return null
    }

    private fun imageFrom(img: Element): String? {
        return img.absUrl("data-src").ifBlank { img.absUrl("data-lazy-src") }.ifBlank { img.absUrl("src") }
            .takeIf { it.startsWith("http") }
    }

    private fun looksLikeSeries(title: String, doc: Document): Boolean {
        return parseEpisodes(doc, doc.location(), null, null).isNotEmpty() ||
            doc.select("div.gmr-moviedata a[href*=/genre/]").any { it.text().contains(Regex("(?i)series|episode|season")) } ||
            title.contains(Regex("""(?i)\b(season|series|s\d+e?\d*|episode)\b"""))
    }

    private fun looksLikeSeries(title: String, element: Element): Boolean {
        return element.attr("itemtype").contains("TVSeries", true) ||
            title.contains(Regex("""(?i)\b(season|series|s\d+e\d+|episode)\b"""))
    }

    private fun cleanTitle(raw: String): String {
        return raw.removePrefix("Nonton Film: ")
            .removePrefix("Nonton Series: ")
            .removePrefix("Nonton ")
            .trim()
    }

    private fun episodeNumber(text: String): Int? {
        return Regex("""(?i)(?:episode|eps?|e)\s*([0-9]+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b([0-9]{1,4})\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun qualityFromText(text: String): Int {
        return when {
            text.contains("2160") || text.contains("4K", true) -> Qualities.P2160.value
            text.contains("1080") -> Qualities.P1080.value
            text.contains("720") -> Qualities.P720.value
            text.contains("540") -> Qualities.P480.value
            text.contains("480") -> Qualities.P480.value
            text.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun slugFrom(url: String): String {
        return url.substringBefore("?").trimEnd('/').substringAfterLast('/').trim()
    }

    private fun normalizeUrl(base: String, raw: String?): String? {
        val value = raw?.trim()?.replace("&amp;", "&")?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { URI(base.ifBlank { mainUrl }).resolve(value).toString() }.getOrNull()
    }

    private fun siteHeaders(url: String, referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer,
            "Origin" to originOf(referer),
        )
    }

    private fun formHeaders(referer: String, origin: String): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html, */*; q=0.01",
            "Referer" to referer,
            "Origin" to origin,
            "Content-Type" to "application/x-www-form-urlencoded",
            "X-Requested-With" to "XMLHttpRequest",
        )
    }

    private fun originOf(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(mainUrl)
    }

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private data class ServerEntry(val name: String, val url: String, val kind: PlayerKind)

    private enum class PlayerKind { HYDRAX, P2P, GDFRAME, OTHER }
}
