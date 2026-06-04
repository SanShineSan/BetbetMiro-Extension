package com.sad25kag.animemovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeMovies : MainAPI() {
    override var mainUrl = "https://animemovies.org"
    override var name = "AnimeMovies"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Episode Terbaru",
        "$mainUrl/anime?status=Ongoing" to "Anime Ongoing",
        "$mainUrl/anime?status=Completed" to "Anime Completed",
        "$mainUrl/anime?type=Movie" to "Anime Movie",
        "$mainUrl/anime?sort=popular" to "Anime Populer",
        "$mainUrl/genre/action" to "Action",
        "$mainUrl/genre/romance" to "Romance",
        "$mainUrl/genre/comedy" to "Comedy",
        "$mainUrl/genre/fantasy" to "Fantasy",
        "$mainUrl/genre/horror" to "Horror",
        "$mainUrl/genre/sci-fi" to "Sci-Fi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page), headers = siteHeaders).document
        val items = document.parseAnimeItems(preferMovie = request.name.contains("Movie", true))
        return newHomePageResponse(request.name, items, hasNext = document.hasNextPage(page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = withContext(Dispatchers.IO) {
            URLEncoder.encode(query.trim(), "UTF-8")
        }
        if (encoded.isBlank()) return emptyList()

        val results = linkedMapOf<String, SearchResponse>()
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/anime?search=$encoded",
            "$mainUrl/anime?q=$encoded",
            "$mainUrl/search?q=$encoded"
        )

        for (url in urls) {
            val document = runCatching { app.get(url, headers = siteHeaders).document }.getOrNull() ?: continue
            for (item in document.parseAnimeItems()) {
                results[item.url] = item
            }
            if (results.isNotEmpty()) break
        }

        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, headers = siteHeaders).document
        val isWatchPage = fixedUrl.contains("/watch/", true)

        val title = document.bestTitle()
            ?.cleanTitle()
            ?.ifBlank { null }
            ?: fixedUrl.slugTitle()

        val poster = document.bestPoster()
        val plot = document.bestPlot()
        val year = document.text().parseYear()
        val tags = document.select("a[href*=/genre/], a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length <= 40 }
            .distinct()
            .take(20)
        val status = document.text().toShowStatus()
        val recommendations = document.parseAnimeItems().filter { it.url != fixedUrl }.take(24)

        val episodeLinks = document.select("a[href*=/watch/], a[href*='/watch/']")
            .mapNotNull { a -> a.toEpisodeOrNull() }
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.episode ?: Int.MAX_VALUE }, { it.name ?: "" }))

        val episodes = if (episodeLinks.isNotEmpty()) {
            episodeLinks
        } else if (isWatchPage) {
            listOf(newEpisode(fixedUrl) {
                this.name = title.parseEpisodeName() ?: title
                this.episode = title.parseEpisodeNumber() ?: fixedUrl.parseEpisodeNumber()
                this.posterUrl = poster
            })
        } else {
            emptyList()
        }

        val type = when {
            title.contains("Movie", true) || fixedUrl.contains("movie", true) -> TvType.AnimeMovie
            title.contains("OVA", true) || title.contains("Special", true) -> TvType.OVA
            episodes.size <= 1 && !title.contains("Season", true) && title.contains("Movie", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.showStatus = status
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            val data = episodes.firstOrNull()?.data ?: fixedUrl
            newMovieLoadResponse(title, fixedUrl, type, data) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.takeIf { it.startsWith("http", true) } ?: return false
        val startDocument = app.get(pageUrl, headers = siteHeaders, referer = "$mainUrl/").document
        val queue = ArrayDeque<ServerCandidate>()
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var hasLinks = false

        for (candidate in startDocument.extractServerCandidates(pageUrl)) {
            queue.add(candidate)
        }

        suspend fun emitDirect(url: String, label: String?, referer: String) {
            val fixed = normalizeMediaUrl(url) ?: return
            if (!emitted.add(fixed)) return
            val qualityLabel = label?.cleanServerLabel().orEmpty().ifBlank { fixed.qualityLabelFromUrl() }
            val quality = qualityLabel.takeIf { it.isNotBlank() }?.let { getQualityFromName(it) }
                ?: fixed.parseQuality()
                ?: Qualities.Unknown.value

            if (fixed.contains(".m3u8", true)) {
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = fixed,
                    referer = referer,
                    headers = siteHeaders
                )
                for (link in links) {
                    callback(link)
                    hasLinks = true
                }
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = listOf(name, qualityLabel).filter { it.isNotBlank() }.joinToString(" "),
                        url = fixed,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = quality
                        this.headers = siteHeaders
                    }
                )
                hasLinks = true
            }
        }

        suspend fun emitExtractor(url: String, label: String?, referer: String) {
            val fixed = normalizeMediaUrl(url) ?: return
            if (fixed.isDirectMedia()) {
                emitDirect(fixed, label, referer)
                return
            }
            if (!emitted.add(fixed)) return

            loadExtractor(fixed, referer, subtitleCallback) { link ->
                hasLinks = true
                callback(link)
            }
        }

        var guard = 0
        while (queue.isNotEmpty() && guard < 80) {
            guard++
            val candidate = queue.removeFirst()
            val fixed = normalizeMediaUrl(candidate.url) ?: continue
            if (!visited.add(fixed)) continue

            if (fixed.isDirectMedia() || !fixed.startsWith(mainUrl, true)) {
                emitExtractor(fixed, candidate.label, candidate.referer ?: pageUrl)
                continue
            }

            val nested = runCatching {
                app.get(fixed, headers = siteHeaders, referer = candidate.referer ?: pageUrl).document
            }.getOrNull()
            if (nested != null) {
                for (next in nested.extractServerCandidates(fixed)) {
                    queue.add(next)
                }
            }
        }

        return hasLinks
    }

    private fun buildPageUrl(data: String, page: Int): String {
        if (page <= 1) return data
        val separator = if (data.contains("?")) "&" else "?"
        return "${data.trimEnd('/')}$separator${"page=$page"}"
    }

    private fun Document.parseAnimeItems(preferMovie: Boolean = false): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val scopes = listOfNotNull(
            selectFirst("main"),
            selectFirst("#app"),
            selectFirst("body")
        ).distinct()

        for (scope in scopes) {
            val cards = scope.select(
                "article, .anime-card, .anime-item, .episode-card, .card, .grid > div, .list > div, " +
                    "li:has(a[href*=/anime/]), li:has(a[href*=/watch/]), " +
                    "a[href*=/anime/], a[href*=/watch/]"
            )
            for (card in cards) {
                val response = card.toSearchResponse(preferMovie) ?: continue
                results[response.url] = response
            }
            if (results.isNotEmpty()) break
        }

        return results.values.toList()
    }

    private fun Element.toSearchResponse(preferMovie: Boolean = false): SearchResponse? {
        val card = bestCard()
        val detailAnchor = card.selectFirst("a[href*=/anime/], a[href*='/anime/']")
        val watchAnchor = card.selectFirst("a[href*=/watch/], a[href*='/watch/']")
        val anchor = detailAnchor ?: watchAnchor ?: if (tagName().equals("a", true)) this else null ?: return null
        val href = anchor.attr("href").trim().toAbsoluteUrl() ?: return null
        if (!href.isAnimeOrWatchUrl()) return null
        if (href.contains("/genre/", true) || href.contains("/jadwal", true)) return null

        val title = listOf(
            card.selectFirst("h1, h2, h3, .title, .judul, .anime-title, .entry-title")?.text(),
            anchor.attr("title"),
            card.selectFirst("img[alt]")?.attr("alt"),
            anchor.text(),
            card.text()
        ).firstCleanTitle() ?: return null

        if (title.isBlockedTitle()) return null

        val poster = card.bestImage()?.toAbsoluteUrl()
        val episode = card.text().parseEpisodeNumber() ?: href.parseEpisodeNumber()
        val tvType = when {
            preferMovie || title.contains("Movie", true) || href.contains("movie", true) -> TvType.AnimeMovie
            title.contains("OVA", true) || title.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, tvType) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private fun Element.bestCard(): Element {
        if (selectFirst("img") != null && selectFirst("a[href]") != null) return this
        return parents().firstOrNull { parent ->
            parent.selectFirst("img") != null && parent.selectFirst("a[href]") != null && parent.text().length in 2..1200
        } ?: this
    }

    private fun Element.toEpisodeOrNull(): Episode? {
        val href = attr("href").trim().toAbsoluteUrl() ?: return null
        if (!href.contains("/watch/", true)) return null
        val rawText = text().trim().ifBlank { attr("title") }.ifBlank { href.slugTitle() }
        val episodeNumber = rawText.parseEpisodeNumber() ?: href.parseEpisodeNumber()
        return newEpisode(href) {
            this.name = rawText.parseEpisodeName() ?: episodeNumber?.let { "Episode $it" } ?: rawText.cleanTitle()
            this.episode = episodeNumber
        }
    }

    private fun Document.extractServerCandidates(referer: String): List<ServerCandidate> {
        val results = linkedMapOf<String, ServerCandidate>()

        fun add(rawUrl: String?, label: String? = null) {
            val fixed = rawUrl?.trim()?.trim('"', '\'', ' ')?.htmlDecode()?.unescapeJs()?.toAbsoluteUrl() ?: return
            if (fixed.isBlank() || fixed == mainUrl || fixed == referer) return
            if (fixed.startsWith("javascript:", true) || fixed.startsWith("data:", true)) return
            if (!fixed.isPotentialPlayerUrl()) return
            results[fixed] = ServerCandidate(fixed, label, referer)
        }

        for (iframe in select("iframe[src], embed[src]")) {
            add(iframe.attr("src"), iframe.attr("title").ifBlank { iframe.attr("name") })
        }

        for (source in select("video[src], source[src]")) {
            add(source.attr("src"), source.attr("label").ifBlank { source.attr("res") })
        }

        val dataSelector = "[data-src], [data-url], [data-link], [data-iframe], [data-embed], [data-player], [data-video], [data-file]"
        for (element in select(dataSelector)) {
            val label = element.text().ifBlank { element.attr("aria-label") }.ifBlank { element.attr("title") }
            val attrs = listOf("data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file")
            for (attr in attrs) add(element.attr(attr), label)
        }

        for (option in select("option[value]")) {
            val value = option.attr("value")
            add(value, option.text())
            decodePossibleBase64(value)?.let { decoded ->
                val parsed = Jsoup.parse(decoded)
                for (candidate in parsed.extractServerCandidates(referer)) {
                    add(candidate.url, option.text().ifBlank { candidate.label.orEmpty() })
                }
            }
        }

        val html = html().htmlDecode().unescapeJs()
        val urlRegex = Regex("""https?:\/\/[^'"<>\s\\]+""", RegexOption.IGNORE_CASE)
        for (match in urlRegex.findAll(html)) {
            add(match.value, match.value.qualityLabelFromUrl())
        }

        val keyRegex = Regex("""(?:src|url|link|file|iframe|embed|player|video)\s*[:=]\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
        for (match in keyRegex.findAll(html)) {
            add(match.groupValues.getOrNull(1), match.value.qualityLabelFromUrl())
        }

        val b64Regex = Regex("""(?:atob\(|base64_decode\(|['\"])([A-Za-z0-9+/=_-]{32,})(?:['\"]|\))""")
        for (match in b64Regex.findAll(html)) {
            val decoded = decodePossibleBase64(match.groupValues[1]) ?: continue
            val parsed = Jsoup.parse(decoded)
            for (candidate in parsed.extractServerCandidates(referer)) {
                add(candidate.url, candidate.label)
            }
        }

        return results.values.toList()
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        return selectFirst("a[rel=next], .pagination a[href*='page=${page + 1}'], a[href*='page=${page + 1}']") != null
    }

    private fun Document.bestTitle(): String? {
        return selectFirst("h1, .title h1, .entry-title, .anime-title")?.text()?.trim()
            ?: selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content")?.trim()
            ?: selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
    }

    private fun Document.bestPoster(): String? {
        return listOfNotNull(
            selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content"),
            selectFirst(".poster img, .thumb img, .thumbnail img, article img, main img")?.bestImage()
        ).firstOrNull { it.isNotBlank() }?.toAbsoluteUrl()
    }

    private fun Document.bestPlot(): String? {
        return selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst(".sinopsis, .synopsis, .description, .desc, .entry-content p, article p")?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun Element.bestImage(): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        return image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: image?.attr("srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.takeIf { it.isNotBlank() }
    }

    private fun String.toAbsoluteUrl(): String? {
        val raw = trim().trim('"', '\'', ' ')
        if (raw.isBlank() || raw == "#") return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> mainUrl.trimEnd('/') + raw
            raw.startsWith("./") -> mainUrl.trimEnd('/') + "/" + raw.removePrefix("./")
            raw.startsWith("../") -> mainUrl.trimEnd('/') + "/" + raw.substringAfterLast("../")
            raw.contains(".") && !raw.contains(" ") -> "https://$raw"
            else -> null
        }
    }

    private fun normalizeMediaUrl(raw: String?): String? {
        return raw?.htmlDecode()?.unescapeJs()?.replace("\\/", "/")?.toAbsoluteUrl()
    }

    private fun String.isAnimeOrWatchUrl(): Boolean {
        return startsWith(mainUrl, true) && (contains("/anime/", true) || contains("/watch/", true))
    }

    private fun String.isPotentialPlayerUrl(): Boolean {
        val lower = lowercase()
        return lower.isDirectMedia() ||
            lower.contains("/embed") ||
            lower.contains("iframe") ||
            lower.contains("player") ||
            lower.contains("vidhide") ||
            lower.contains("filedon") ||
            lower.contains("mega") ||
            lower.contains("ondes") ||
            lower.contains("stream") ||
            lower.contains("dood") ||
            lower.contains("filemoon") ||
            lower.contains("mp4upload") ||
            lower.contains("voe") ||
            lower.contains("mixdrop") ||
            (lower.startsWith(mainUrl.lowercase()) && !lower.contains("/anime/"))
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv")
    }

    private fun String.cleanTitle(): String {
        return htmlDecode()
            .replace(Regex("(?i)\\s*episode\\s*\\d+\\s*subtitle\\s*indonesia.*$"), "")
            .replace(Regex("(?i)\\s*subtitle\\s*indonesia.*$"), "")
            .replace(Regex("(?i)\\s*sub\\s*indo.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':')
    }

    private fun List<String?>.firstCleanTitle(): String? {
        for (item in this) {
            val clean = item?.cleanTitle()?.takeIf { it.length >= 2 } ?: continue
            return clean
        }
        return null
    }

    private fun String.isBlockedTitle(): Boolean {
        val normalized = lowercase().trim()
        return normalized in setOf(
            "beranda", "daftar anime", "genre", "jadwal", "masuk", "daftar", "tonton", "detail",
            "lihat semua", "hubungi kami", "dmca", "kontak", "kebijakan privasi", "anime movies", "animemovies"
        )
    }

    private fun String.slugTitle(): String {
        return substringBefore("?").trimEnd('/').substringAfterLast('/').replace('-', ' ').cleanTitle().ifBlank { name }
    }

    private fun String.parseYear(): Int? {
        return Regex("""\b(?:19|20)\d{2}\b""").find(this)?.value?.toIntOrNull()
    }

    private fun String.parseEpisodeNumber(): Int? {
        val match = Regex("""(?i)(?:episode|ep|e)\s*[-:]?\s*(\d{1,4})""").find(this)
            ?: Regex("""-(\d{1,4})(?:-|$)""").find(this)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.parseEpisodeName(): String? {
        val episode = parseEpisodeNumber() ?: return null
        return "Episode $episode"
    }

    private fun String.toShowStatus(): ShowStatus {
        return when {
            contains("ongoing", true) || contains("sedang tayang", true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private fun String.htmlDecode(): String {
        return Jsoup.parse(this).text()
    }

    private fun String.unescapeJs(): String {
        var output = this
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }
        return output
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
    }

    private fun decodePossibleBase64(value: String?): String? {
        val raw = value?.trim()?.trim('"', '\'', ' ') ?: return null
        if (raw.length < 16) return null
        return runCatching {
            val normalized = raw.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
        }.getOrNull()?.takeIf { it.contains("<iframe", true) || it.contains("http", true) }
    }

    private fun String.parseQuality(): Int? {
        return Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.qualityLabelFromUrl(): String {
        return parseQuality()?.let { "${it}p" }.orEmpty()
    }

    private fun String.cleanServerLabel(): String {
        return htmlDecode().replace(Regex("\\s+"), " ").trim()
    }

    private data class ServerCandidate(
        val url: String,
        val label: String?,
        val referer: String?
    )
}
