package com.sad25kag.ayononton

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * AyoNonton — ayononton.live
 *
 * Evidence used for this resolver:
 * - Detail page fetches POST https://t21.press/data.php with body movie={slug}
 * - data.php returns provider anchors:
 *   DRIVE/P2P -> play-ads.php?movie={slug}&iframe=p2p
 *   HYDRAX    -> play-ads.php?movie={slug}&iframe=g-hydrax
 *   GDFRAME   -> play-ads.php?movie={slug}&iframe=gdframe
 * - play-ads.php itself exposes a placeholder loading.mp4, so it is not treated as a final player.
 * - Real flow from HAR:
 *   p2p provider requests POST https://t21.press/540.php?movie={slug}
 *   with Referer https://t21.press/p2p.php?movie={slug},
 *   form r=https://t21.press/play-ads.php?movie={slug}&iframe=p2p and d=t21.press,
 *   returning JSON data[].file such as seneng.org/...m3u8.
 *   final HLS request uses Referer/Origin https://t21.press/.
 * - g-hydrax.php returns iframe src to playhydrax.com/?v=...
 * - gdframe.php / p2p.php may still need WebViewResolver fallback for JS player requests.
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
    private val t21 = "https://t21.press"

    override val mainPage = mainPageOf(
        "$mainUrl/tag/featured/" to "Featured",
        "$mainUrl/latest/" to "Terbaru",
        "$mainUrl/genre/action/" to "Action",
        "$mainUrl/genre/animation/" to "Animation",
        "$mainUrl/genre/horror/" to "Horror",
        "$mainUrl/genre/sci-fi/" to "Sci-Fi",
        "$mainUrl/genre/comedy/" to "Comedy",
        "$mainUrl/genre/romance/" to "Romance",
        "$mainUrl/country/china/" to "China",
        "$mainUrl/country/india/" to "India",
        "$mainUrl/country/japan/" to "Japan",
        "$mainUrl/country/korea/" to "Korea",
        "$mainUrl/country/thailand/" to "Thailand",
        "$mainUrl/year/2026/" to "2026",
        "$mainUrl/quality/bluray/" to "BluRay",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pagedUrl(request.data, page)
        val document = app.get(url, headers = siteHeaders(url)).document
        val items = parseListingPage(document)
        val hasMore = document.selectFirst("a.next.page-numbers, .pagination .next, a[rel=next]") != null

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
        val document = app.get(url, headers = siteHeaders(url)).document
        val slug = url.trimEnd('/').substringAfterLast('/')

        val rawTitle = document.selectFirst("h1[itemprop=name], h1.entry-title, h1")
            ?.text()
            ?.trim()
            ?: slug

        val title = rawTitle
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .trim()
            .ifBlank { rawTitle }

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img[itemprop=image], .gmr-movie-data figure img, .content-thumbnail img")?.let(::imageUrl)

        val description = document.selectFirst(
            "div.entry-content[itemprop=description], div.entry-content.clearfix, div.entry-content, div#synopsis, div.gmr-sinopsis"
        )?.text()?.trim()

        val tags = linkedSetOf<String>()
        val actors = linkedSetOf<String>()
        var year: Int? = null
        var rating: String? = null
        var duration: Int? = null

        document.select("div.gmr-moviedata, div.gmr-movie-data").forEach { div ->
            val label = div.selectFirst("strong")?.text()?.lowercase()?.trim().orEmpty()
            val value = div.text().removePrefix(div.selectFirst("strong")?.text().orEmpty()).trim()
            when {
                label.contains("genre") -> div.select("a[href*=/genre/]").mapTo(tags) { it.text().trim() }
                label.contains("tahun") -> year = value.take(4).toIntOrNull()
                label.contains("durasi") -> duration = value.filter { it.isDigit() }.toIntOrNull()
                label.contains("rating") -> rating = div.selectFirst("span[itemprop=ratingValue], .imdb-rating, span")
                    ?.text()
                    ?.trim()
                    ?.ifBlank { null }
            }
        }

        document.select("[itemprop=actors] a, a[href*=/cast/]").mapTo(actors) { it.text().trim() }

        if (year == null) {
            year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        val episodes = parseEpisodes(document, poster, description, "$mainUrl/||$slug")
        val isSeries = episodes.size > 1 ||
            document.select("div.gmr-moviedata a[href*=/genre/]").any { it.text().contains(Regex("(?i)series|episode|season")) } ||
            title.contains(Regex("""(?i)\b(season|series|s\d+e?\d*)\b"""))

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.ifEmpty {
                listOf(newEpisode("$mainUrl/||$slug") {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = poster
                    this.description = description
                })
            }) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags.filter { it.isNotBlank() }
                this.score = Score.from10(rating)
                this.duration = duration
                this.actors = actors.filter { it.isNotBlank() }.map { ActorData(Actor(it)) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/||$slug") {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags.filter { it.isNotBlank() }
                this.score = Score.from10(rating)
                this.duration = duration
                this.actors = actors.filter { it.isNotBlank() }.map { ActorData(Actor(it)) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val slug = data.substringAfterLast("||", data).trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return false

        val detailReferer = "$mainUrl/$slug/"
        collectSubtitlesFromDetail(detailReferer, subtitleCallback)

        val serverHtml = runCatching {
            app.post(
                "$t21/data.php",
                data = mapOf("movie" to slug),
                headers = t21Headers(detailReferer) + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "X-Requested-With" to "XMLHttpRequest",
                ),
                referer = detailReferer,
            ).text
        }.getOrNull().orEmpty()

        if (serverHtml.isBlank() || serverHtml.trim().equals("none", ignoreCase = true)) {
            return false
        }

        val emitted = linkedSetOf<String>()
        var found = false
        val serverAnchors = Jsoup.parse(serverHtml).select("a[href]").filterNot { anchor ->
            anchor.attr("rel").contains("download", ignoreCase = true) ||
                anchor.attr("href").contains("#download", ignoreCase = true)
        }

        for (anchor in serverAnchors) {
            val playAdsUrl = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (playAdsUrl.isBlank()) continue

            val serverClass = anchor.classNames().joinToString(" ").uppercase()
            val serverText = anchor.text().trim()
            val serverName = serverText.ifBlank { serverClass.ifBlank { "Server" } }
            val iframeType = queryParam(playAdsUrl, "iframe").lowercase()
            val realEndpoint = realPlayerEndpoint(slug, serverClass, iframeType)

            val loaded = when {
                serverClass.contains("HYDRAX") || iframeType.contains("hydrax") ->
                    resolveHydrax(slug, realEndpoint, playAdsUrl, serverName, emitted, subtitleCallback, callback)

                iframeType == "p2p" || serverClass.contains("P2P") || serverClass.contains("DRIVE") ->
                    resolveP2P540(slug, playAdsUrl, serverName, emitted, subtitleCallback, callback) ||
                        resolveJsPlayer(slug, realEndpoint, playAdsUrl, serverName, emitted, subtitleCallback, callback)

                iframeType == "gdframe" || serverClass.contains("GDFRAME") ->
                    resolveJsPlayer(slug, realEndpoint, playAdsUrl, serverName, emitted, subtitleCallback, callback)

                else ->
                    resolvePlayerPage(slug, realEndpoint, playAdsUrl, serverName, emitted, subtitleCallback, callback)
            }

            found = loaded || found
        }

        return found
    }

    private fun parseListingPage(document: Document): List<SearchResponse> {
        return document.select("article.item, article.item-infinite, .gmr-box-content").mapNotNull { item ->
            val anchor = item.selectFirst("a[itemprop=url], .entry-title a, a[href]") ?: return@mapNotNull null
            val href = anchor.absUrl("href").takeIf { it.startsWith(mainUrl) } ?: return@mapNotNull null
            val rawTitle = anchor.attr("title")
                .ifBlank { item.selectFirst(".entry-title a, h2 a, h3 a")?.text().orEmpty() }
                .ifBlank { anchor.text() }
            val title = rawTitle
                .removePrefix("Nonton Film: ")
                .removePrefix("Nonton Series: ")
                .removePrefix("Nonton ")
                .trim()
                .ifBlank { href.trimEnd('/').substringAfterLast('/') }

            val tvType = detectType(title, item.attr("itemtype"))
            val poster = item.selectFirst("source[srcset], img[itemprop=image], img[data-src], img[src]")?.let(::imageUrl)
            if (tvType == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }.distinctBy { it.url }
    }

    private fun parseEpisodes(document: Document, poster: String?, description: String?, fallbackData: String): List<Episode> {
        val anchors = document.select(
            ".gmr-listseries a[href], .gmr-listseries-list a[href], .list-series a[href], .eplister a[href], a[href*=/eps/], a[href*=/episode/]"
        )

        return anchors.mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isBlank() || href == document.location()) return@mapNotNull null
            val text = anchor.attr("title").ifBlank { anchor.text() }.trim()
            val episodeNum = Regex("""(?i)(?:episode|eps?|ep)\s*(\d+)""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: Regex("""\b(\d{1,4})\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(href) {
                this.name = text.ifBlank { "Episode ${episodeNum ?: ""}".trim() }
                this.season = Regex("""(?i)season\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                this.episode = episodeNum
                this.posterUrl = poster
                this.description = description
            }
        }.distinctBy { it.data }.ifEmpty {
            if (document.select("a[href*=/eps/], a[href*=/episode/]").isNotEmpty()) emptyList()
            else emptyList()
        }
    }

    private suspend fun resolveP2P540(
        slug: String,
        playAdsUrl: String,
        serverName: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val p2pReferer = "$t21/p2p.php?movie=$slug"
        val playerJson = runCatching {
            app.post(
                "$t21/540.php?movie=$slug",
                data = mapOf(
                    "r" to playAdsUrl,
                    "d" to "t21.press",
                ),
                headers = ajaxJsonHeaders(p2pReferer),
                referer = p2pReferer,
            ).text
        }.getOrNull().orEmpty()

        if (playerJson.isBlank()) return false

        val root = runCatching { JSONObject(playerJson) }.getOrNull() ?: return false
        if (!root.optBoolean("success", false)) return false

        root.optJSONArray("captions")?.let { captions ->
            for (i in 0 until captions.length()) {
                val caption = captions.optJSONObject(i) ?: continue
                val file = caption.optString("file").replace("\\/", "/").trim()
                if (file.isNotBlank()) {
                    subtitleCallback(newSubtitleFile(subtitleLang(file), file))
                }
            }
        }

        var found = false
        val dataArray = root.optJSONArray("data") ?: JSONArray()
        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val file = item.optString("file").replace("\\/", "/").trim()
            val label = item.optString("label").ifBlank { serverName }
            val type = item.optString("type")
            if (file.isBlank()) continue

            found = emitMedia(
                sourceName = name,
                linkName = "$name [$serverName ${label.cleanLabel()}]".trim(),
                url = file,
                referer = "$t21/",
                typeHint = type,
                emitted = emitted,
                callback = callback,
            ) || found
        }
        return found
    }

    private suspend fun resolveHydrax(
        slug: String,
        realUrl: String,
        playAdsUrl: String,
        serverName: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching {
            app.get(realUrl, headers = t21Headers(playAdsUrl), referer = playAdsUrl).text
        }.getOrNull().orEmpty()

        val hydraxUrl = Regex("""(?i)<iframe[^>]+src=["'](https?://(?:www\.)?playhydrax\.com/[^"']+)["']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""(?i)["'](https?://(?:www\.)?playhydrax\.com/\?v=[^"'\s<>]+)["']""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

        return if (!hydraxUrl.isNullOrBlank()) {
            loadExtractor(hydraxUrl, realUrl, subtitleCallback, callback)
        } else {
            resolvePlayerPage(slug, realUrl, playAdsUrl, serverName, emitted, subtitleCallback, callback)
        }
    }

    private suspend fun resolveJsPlayer(
        slug: String,
        realUrl: String,
        playAdsUrl: String,
        serverName: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val directFound = resolvePlayerPage(slug, realUrl, playAdsUrl, serverName, emitted, subtitleCallback, callback)
        if (directFound) return true

        return runCatching {
            val resolver = WebViewResolver(
                Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4|/[^"'<>\s]+\.fd)[^\s"'<>]*""", RegexOption.IGNORE_CASE),
                userAgent = ua,
                useOkhttp = true,
            )

            val capturedUrl = app.get(
                realUrl,
                headers = t21Headers(playAdsUrl),
                referer = playAdsUrl,
                interceptor = resolver,
                timeout = 60L,
            ).url

            if (capturedUrl.isNotBlank() && looksLikeMedia(capturedUrl)) {
                emitMedia(name, "$name [$serverName]", capturedUrl, realUrl, null, emitted, callback)
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private suspend fun resolvePlayerPage(
        slug: String,
        realUrl: String,
        playAdsUrl: String,
        serverName: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageHtml = runCatching {
            app.get(realUrl, headers = t21Headers(playAdsUrl), referer = playAdsUrl).text
        }.getOrNull().orEmpty()

        if (pageHtml.isBlank()) return false

        collectSubtitlesFromText(realUrl, pageHtml, subtitleCallback)

        var found = false
        extractMediaUrls(pageHtml).forEach { mediaUrl ->
            found = emitMedia(name, "$name [$serverName]", mediaUrl, realUrl, null, emitted, callback) || found
        }

        if (found) return true

        extractIframeUrls(pageHtml).forEach { iframeUrl ->
            if (!iframeUrl.contains("play-ads.php", ignoreCase = true) && !looksLikeAdUrl(iframeUrl)) {
                found = runCatching { loadExtractor(iframeUrl, realUrl, subtitleCallback, callback) }.getOrDefault(false) || found
            }
        }

        return found
    }

    private suspend fun emitMedia(
        sourceName: String,
        linkName: String,
        url: String,
        referer: String,
        typeHint: String?,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val stream = normalizeMediaUrl(url)
        if (stream.isBlank() || !looksLikeMedia(stream) || !emitted.add(stream)) return false

        val headers = videoHeaders(referer)
        val quality = qualityFromLabel(linkName) ?: qualityFromLabel(stream) ?: Qualities.Unknown.value
        val isHls = stream.contains(".m3u8", ignoreCase = true) || typeHint.equals("hls", ignoreCase = true)

        return try {
            if (isHls) {
                val links = generateM3u8(
                    source = sourceName,
                    streamUrl = stream,
                    referer = referer,
                    headers = headers,
                )
                links.forEach(callback)
                links.isNotEmpty()
            } else {
                callback(
                    newExtractorLink(
                        sourceName,
                        linkName,
                        stream,
                        ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = referer
                        this.quality = quality
                        this.headers = headers
                    }
                )
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun collectSubtitlesFromDetail(detailUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        runCatching {
            val document = app.get(detailUrl, headers = siteHeaders(detailUrl)).document
            document.select("track[src], a[href$=.srt], a[href$=.vtt], a[href*='subtitle']")
                .mapNotNull { it.absUrl("src").ifBlank { it.absUrl("href") } }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { subtitleUrl ->
                    subtitleCallback(newSubtitleFile(subtitleLang(subtitleUrl), subtitleUrl))
                }
        }
    }

    private suspend fun collectSubtitlesFromText(pageUrl: String, text: String, subtitleCallback: (SubtitleFile) -> Unit) {
        Regex("""https?://[^'"<>\s]+\.(?:srt|vtt)(?:\?[^'"<>\s]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value }
            .distinct()
            .forEach { subtitleUrl ->
                subtitleCallback(newSubtitleFile(subtitleLang(subtitleUrl), subtitleUrl))
            }

        Jsoup.parse(text, pageUrl).select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { el ->
            val url = el.absUrl("src").ifBlank { el.absUrl("href") }
            if (url.isNotBlank()) subtitleCallback(newSubtitleFile(subtitleLang(url), url))
        }
    }

    private fun extractMediaUrls(text: String): List<String> {
        val normalized = text
            .replace("\\/", "/")
            .replace("&amp;", "&")

        return Regex("""https?://[^'"<>\s)]+(?:\.m3u8|\.mp4|/[^'"<>\s)]+\.fd)(?:\?[^'"<>\s)]*)?""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map { it.value.trim('"', '\'', ' ') }
            .filterNot { it.contains("github.com", ignoreCase = true) && it.contains("loading", ignoreCase = true) }
            .distinct()
            .toList()
    }

    private fun extractIframeUrls(text: String): List<String> {
        return Jsoup.parse(text).select("iframe[src]")
            .mapNotNull { it.absUrl("src").ifBlank { it.attr("src") } }
            .map { it.replace("&amp;", "&") }
            .filter { it.startsWith("http") }
            .distinct()
    }

    private fun realPlayerEndpoint(slug: String, serverClass: String, iframeType: String): String {
        return when {
            serverClass.contains("HYDRAX") || iframeType.contains("hydrax") -> "$t21/g-hydrax.php?movie=$slug"
            serverClass.contains("GDFRAME") || iframeType == "gdframe" -> "$t21/gdframe.php?movie=$slug"
            else -> "$t21/p2p.php?movie=$slug"
        }
    }

    private fun pagedUrl(url: String, page: Int): String {
        if (page <= 1) return url
        return if (url.contains("?")) {
            val base = url.substringBefore("?").trimEnd('/')
            val query = url.substringAfter("?")
            "$base/page/$page/?$query"
        } else {
            url.trimEnd('/') + "/page/$page/"
        }
    }

    private fun detectType(title: String, itemtype: String): TvType {
        if (itemtype.contains("TVSeries", ignoreCase = true)) return TvType.TvSeries
        if (title.contains(Regex("""(?i)\b(season|series|s\d+e\d+|episode)\b"""))) return TvType.TvSeries
        return TvType.Movie
    }

    private fun imageUrl(element: Element): String? {
        if (element.tagName().equals("source", ignoreCase = true)) {
            return bestFromSrcSet(element.attr("srcset"))
        }

        val srcSet = bestFromSrcSet(element.attr("srcset"))
        if (!srcSet.isNullOrBlank()) return srcSet

        return element.absUrl("data-src")
            .ifBlank { element.absUrl("src") }
            .ifBlank { element.attr("data-src") }
            .ifBlank { element.attr("src") }
            .takeIf { it.startsWith("http") }
    }

    private fun bestFromSrcSet(srcSet: String?): String? {
        if (srcSet.isNullOrBlank()) return null
        return srcSet.split(",")
            .mapNotNull { part -> part.trim().split("\\s+".toRegex()).firstOrNull() }
            .lastOrNull { it.startsWith("http") }
    }

    private fun siteHeaders(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer,
        )
    }

    private fun t21Headers(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer,
            "Origin" to originOf(referer),
        )
    }

    private fun ajaxJsonHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to referer,
            "Origin" to t21,
        )
    }

    private fun videoHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Referer" to referer,
            "Origin" to originOf(referer),
        )
    }

    private fun originOf(url: String): String {
        return Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.getOrNull(1) ?: mainUrl
    }

    private fun queryParam(url: String, key: String): String {
        val query = url.substringAfter("?", "").substringBefore("#")
        return query.split("&")
            .firstOrNull { it.substringBefore("=") == key }
            ?.substringAfter("=", "")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?: ""
    }

    private fun normalizeMediaUrl(url: String): String {
        return url
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
    }

    private fun looksLikeMedia(url: String): Boolean {
        val low = url.lowercase()
        return low.contains(".m3u8") ||
            low.contains(".mp4") ||
            low.endsWith(".fd") ||
            low.contains("/sora/") ||
            low.contains("sssrr.org/")
    }

    private fun looksLikeAdUrl(url: String): Boolean {
        val low = url.lowercase()
        return low.contains("omg10.com") ||
            low.contains("redirect") ||
            low.contains("advert") ||
            low.contains("doubleclick") ||
            low.contains("googlesyndication")
    }

    private fun qualityFromLabel(value: String): Int? {
        return when {
            value.contains("2160") || value.contains("4K", ignoreCase = true) -> 2160
            value.contains("1080") -> Qualities.P1080.value
            value.contains("720") -> Qualities.P720.value
            value.contains("540") -> Qualities.P480.value
            value.contains("480") -> Qualities.P480.value
            value.contains("360") -> Qualities.P360.value
            else -> Regex("""(?i)(\d{3,4})p?""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

    private fun subtitleLang(url: String): String {
        return when {
            url.contains("ind", true) || url.contains("id", true) -> "Indonesian"
            url.contains("en", true) -> "English"
            else -> "Subtitle"
        }
    }

    private fun String.cleanLabel(): String {
        return this.replace(Regex("""\s+"""), " ").trim()
    }

    private fun String.encodeUri(): String {
        return URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    }
}
