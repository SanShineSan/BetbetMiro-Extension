package com.pusatfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class Pusatfilm : MainAPI() {
    override var mainUrl = "https://v3.pusatfilm21info.com"
    override var name = "Pusatfilm"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "film-terbaru/page/%d/" to "Film Terbaru",
        "trending/page/%d/" to "Film Trending",
        "genre/action/page/%d/" to "Film Action",
        "series-netflix/page/%d/" to "Series Netflix",
        "drama-korea/page/%d/" to "Drama Korea",
        "west-series/page/%d/" to "West Series",
        "drama-china/page/%d/" to "Drama China"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val home = document.select("article.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name, home, isHorizontalImages = true)),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get(
            "$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv",
            timeout = 50L
        ).document

        return document.select("article.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.cleanTitle()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.cleanTitle()
            ?: ""

        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())
            ?.fixImageQuality()
            ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val tags = document.select("strong:contains(Genre) ~ a, a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a, a[href*='/year/']")
            .firstOrNull()
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val description = document.selectFirst("div[itemprop=description] > p, .entry-content p")
            ?.text()
            ?.trim()

        val recommendations = document.select("div.idmuvi-rp ul li")
            .mapNotNull { it.toRecommendResult() }
            .distinctBy { it.url }
            .take(12)

        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")

        val score = Score.from10(
            document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue], div.gmr-rating-item")
                ?.text()
                ?.trim()
                ?.toDoubleOrNull()
        )

        val actors = document.select("div.gmr-moviedata").lastOrNull()
            ?.select("span[itemprop=actors]")
            ?.map { it.select("a").text() }
            ?.filter { it.isNotBlank() }

        val duration = document.selectFirst("div.gmr-moviedata span[property=duration]")
            ?.text()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()

        val episodeElements = document.select(
            "div.vid-episodes a[href*='/eps/'], div.gmr-listseries a[href*='/eps/'], a[href*='/eps/']"
        )
        val tvType = if (url.contains("/tv/") || episodeElements.isNotEmpty()) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = episodeElements
                .mapNotNull { eps ->
                    val href = fixUrlNull(eps.attr("href")) ?: return@mapNotNull null
                    if (!href.contains("/eps/")) return@mapNotNull null
                    val rawName = eps.text().trim()
                    val episode = rawName.toIntOrNull()
                        ?: Regex("""(?i)(?:episode|eps|e)[-\s]*(\d+)""").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""(?i)episode-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val season = Regex("""(?i)season[-\s]*(\d+)""").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""(?i)season-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val name = when {
                        rawName.isBlank() && episode != null -> "Episode $episode"
                        rawName.all { it.isDigit() } -> "Episode $rawName"
                        rawName.isBlank() -> "Episode"
                        else -> rawName
                    }
                    newEpisode(href) {
                        this.name = name
                        this.season = season
                        this.episode = episode
                    }
                }
                .distinctBy { it.data }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        val candidates = linkedSetOf<String>()

        document.select(
            "div.gmr-embed-responsive iframe, div.movieplay iframe, iframe, source[src], video[src], " +
                "li.pull-right a[href], a[href*='kotakajaib.me/file/'], a[title*=Download], " +
                "a:contains(Download), a:contains(download), span.textdownload"
        ).forEach { element ->
            val rawValues = when (element.tagName()) {
                "span" -> listOfNotNull(element.parent()?.attr("href"))
                else -> listOf(
                    element.attr("src"),
                    element.attr("data-src"),
                    element.attr("data-litespeed-src"),
                    element.attr("data-url"),
                    element.attr("data-link"),
                    element.attr("href")
                )
            }

            rawValues
                .mapNotNull { it.takeIf { value -> value.isNotBlank() } }
                .flatMap { expandCandidate(it, data) }
                .forEach(candidates::add)
        }

        extractUrlsFromText(document.outerHtml(), data).forEach(candidates::add)

        var emitted = false

        suspend fun emitFromExtractor(url: String, referer: String): Boolean {
            var localEmitted = false
            val loaded = runCatching {
                loadExtractor(url, referer, subtitleCallback) { link ->
                    localEmitted = true
                    emitted = true
                    callback(link)
                }
            }.getOrDefault(false)
            return loaded || localEmitted
        }

        suspend fun emitCandidate(rawUrl: String, referer: String) {
            val fixed = rawUrl.cleanupUrl().takeIf { it.isPlayableCandidate() } ?: return
            if (fixed.isDirectVideo()) {
                emitDirectLink(fixed, referer, callback)
                emitted = true
                return
            }

            val loaded = emitFromExtractor(fixed, referer)
            if (loaded) return

            if (fixed.contains("kotakajaib.me/file/")) {
                resolveKotakajaibApi(fixed).forEach { nested ->
                    if (nested.isDirectVideo()) {
                        emitDirectLink(nested, fixed, callback)
                        emitted = true
                    } else {
                        emitFromExtractor(nested, fixed)
                    }
                }
            }
        }

        candidates
            .distinct()
            .filter { it.isPlayableCandidate() }
            .take(20)
            .forEach { candidate ->
                emitCandidate(candidate, data)
            }

        return emitted
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("h2.entry-title > a, h2 a[href], h3 a[href], .entry-title a[href]")
            ?: return null

        val title = titleElement.text().trim().cleanTitle().ifBlank {
            selectFirst("a > img")?.attr("alt")?.trim()?.cleanTitle().orEmpty()
        }
        if (title.isBlank()) return null

        val href = fixUrlNull(titleElement.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null

        val posterUrl = fixUrlNull(selectFirst("a > img, img")?.getImageAttr())?.fixImageQuality()
        val ratingText = selectFirst("div.gmr-rating-item")?.ownText()?.trim()
        val qualityElement = selectFirst("div.gmr-qual, div.gmr-quality-item > a")
        val quality = qualityElement?.text()?.trim()?.replace("-", "").orEmpty()
        val isSeries = href.contains("/tv/") ||
            qualityElement?.hasClass("tag-episode") == true ||
            quality.matches(Regex("""(?i)s\d+\s*e\d+"""))

        return if (isSeries) {
            val episode = Regex("""(?i)e\s*(\d+)""")
                .find(quality)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: Regex("""Episode\s?([0-9]+)""")
                    .find(title)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                ?: select("div.gmr-numbeps > span").text().toIntOrNull()

            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (quality.isNotBlank()) addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = selectFirst("a > span.idmuvi-rp-title")?.text()?.trim()?.cleanTitle() ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("a > img")?.getImageAttr())?.fixImageQuality()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    private suspend fun resolveKotakajaibApi(fileUrl: String): List<String> {
        val base = runCatching { getBaseUrl(fileUrl) }.getOrDefault("https://kotakajaib.me")
        val fileId = fileUrl.substringAfter("/file/").substringBefore("?").substringBefore("/")
        if (fileId.isBlank()) return emptyList()

        val apiUrl = "$base/api/file/$fileId/download"
        val apiText = runCatching {
            app.get(apiUrl, referer = "$base/").text
        }.getOrNull()
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?.replace("&amp;", "&")
            ?: return emptyList()

        val regexUrls = Regex("""https?://[^"'\s<>]+""")
            .findAll(apiText)
            .map { it.value.cleanupUrl() }
            .toList()

        val jsonUrls = runCatching {
            tryParseJson<Map<String, Any?>>(apiText)?.values
                ?.mapNotNull { it?.toString()?.cleanupUrl()?.takeIf { value -> value.startsWith("http") } }
                ?: emptyList()
        }.getOrDefault(emptyList())

        return (regexUrls + jsonUrls)
            .flatMap { expandCandidate(it, fileUrl) }
            .filter { it.isPlayableCandidate() }
            .distinct()
    }

    private suspend fun emitDirectLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.cleanupUrl()
        val quality = Regex("(?i)(\\d{3,4})p").find(cleanUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value

        callback.invoke(
            if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
                newExtractorLink(
                    source = name,
                    name = name,
                    url = cleanUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = quality
                }
            } else {
                newExtractorLink(
                    source = name,
                    name = name,
                    url = cleanUrl
                ) {
                    this.referer = referer
                    this.quality = quality
                }
            }
        )
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-litespeed-src") -> attr("abs:data-litespeed-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return replace(regex, "")
    }

    private fun extractUrlsFromText(text: String, referer: String): List<String> {
        val normalized = text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val directUrls = Regex("""(?i)(https?:)?//[^\s"'<>]+(?:m3u8|mp4|webm|mkv|embed|file|stream|video|player)[^\s"'<>]*""")
            .findAll(normalized)
            .mapNotNull { it.value.normalizeUrl(referer) }
            .toList()

        val encodedUrls = Regex("""(?i)https?%3A%2F%2F[^\s"'<>]+""")
            .findAll(normalized)
            .mapNotNull { safeUrlDecode(it.value).normalizeUrl(referer) }
            .toList()

        val quotedPayloads = Regex("""["']([A-Za-z0-9+/=_-]{40,})["']""")
            .findAll(normalized)
            .flatMap { match -> expandCandidate(match.groupValues[1], referer).asSequence() }
            .toList()

        return (directUrls + encodedUrls + quotedPayloads)
            .map { it.cleanupUrl() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun expandCandidate(value: String, referer: String): List<String> {
        val output = linkedSetOf<String>()
        val cleaned = value.trim().cleanupUrl()
        if (cleaned.isBlank() || cleaned == "#" || cleaned.startsWith("javascript:", true)) return emptyList()

        cleaned.normalizeUrl(referer)?.let { output.add(it) }
        safeUrlDecode(cleaned).normalizeUrl(referer)?.let { output.add(it) }

        safeBase64Decode(cleaned)?.let { decoded ->
            decoded.normalizeUrl(referer)?.let { output.add(it) }
            extractUrlsFromText(decoded, referer).forEach { output.add(it) }
        }

        return output.toList()
    }

    private fun String.normalizeUrl(referer: String): String? {
        val value = trim().cleanupUrl()
        if (value.isBlank() || value == "#") return null

        return runCatching {
            when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("http://") || value.startsWith("https://") -> value
                value.startsWith("/") -> {
                    val base = getBaseUrl(referer.takeIf { it.startsWith("http") } ?: mainUrl)
                    "$base$value"
                }
                value.startsWith("./") -> referer.substringBeforeLast('/') + value.removePrefix(".")
                else -> httpsify(fixUrl(value))
            }
        }.getOrNull()?.cleanupUrl()
    }

    private fun String.cleanupUrl(): String {
        return trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .substringBefore("\"")
            .substringBefore("'")
            .substringBefore("<")
            .removeSuffix("\\")
            .trim()
    }

    private fun String.isPlayableCandidate(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (!startsWith("http")) return false
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) return false
        if (lower.contains("facebook.com") || lower.contains("telegram") || lower.contains("discord")) return false
        if (lower.contains("wp-content") && !lower.contains(".mp4") && !lower.contains(".m3u8")) return false

        return lower.contains("kotakajaib") || lower.contains("embed") || lower.contains("player") ||
            lower.contains("stream") || lower.contains("file") || lower.contains("video") ||
            lower.contains("m3u8") || lower.contains("mp4") || lower.contains("webm") ||
            lower.contains("hydrax") || lower.contains("rapidplay") || lower.contains("turbovid") ||
            lower.contains("gdplay") || lower.contains("gdrive") || lower.contains("dood") ||
            lower.contains("streamtape") || lower.contains("filemoon") || lower.contains("vidhide") ||
            lower.contains("voe") || lower.contains("mixdrop") || lower.contains("upstream") ||
            lower.contains("sb") || lower.contains("fembed") || lower.contains("vidsrc")
    }

    private fun String.isDirectVideo(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".m3u8") || lower.contains(".mp4") ||
            lower.contains(".webm") || lower.contains("videoplayback")
    }

    private fun safeUrlDecode(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun safeBase64Decode(value: String): String? {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching {
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
        }.getOrNull()
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("(?i)^nonton\\s+film\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .replace(Regex("(?i)\\s+sub\\s+indo.*$"), "")
            .trim()
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
