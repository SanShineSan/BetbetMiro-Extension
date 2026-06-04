package com.sad25kag.filmanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FilmAnime : MainAPI() {
    override var mainUrl = "https://filmanime.id"
    override var name = "FilmAnime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Update Terbaru",
        "$mainUrl/anime/" to "Daftar Anime",
        "$mainUrl/ongoing/" to "Ongoing",
        "$mainUrl/completed/" to "Completed",
        "$mainUrl/movie/" to "Movie",
        "$mainUrl/genre/action/" to "Action",
        "$mainUrl/genre/comedy/" to "Comedy",
        "$mainUrl/genre/fantasy/" to "Fantasy"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = buildPageCandidates(request.data, page)
        val results = linkedMapOf<String, SearchResponse>()
        var hasNext = false
        for (url in urls) {
            val document = runCatching { app.get(url, headers = siteHeaders, referer = "$mainUrl/").document }.getOrNull() ?: continue
            document.parseItems().forEach { results[it.url] = it }
            if (document.hasNextPage(page)) hasNext = true
            if (results.isNotEmpty()) break
        }
        return newHomePageResponse(request.name, results.values.toList(), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val encoded = withContext(Dispatchers.IO) { URLEncoder.encode(cleanQuery, "UTF-8") }
        val results = linkedMapOf<String, SearchResponse>()
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/anime/?s=$encoded",
            "$mainUrl/index.php?s=$encoded",
            "$mainUrl/"
        )

        for (url in urls) {
            val document = runCatching { app.get(url, headers = siteHeaders, referer = "$mainUrl/").document }.getOrNull() ?: continue
            val parsed = document.parseItems().filter { item ->
                item.name.contains(cleanQuery, ignoreCase = true) || url != "$mainUrl/"
            }
            for (item in parsed) results[item.url] = item
            if (results.isNotEmpty() && url != "$mainUrl/") break
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, headers = siteHeaders, referer = "$mainUrl/").document
        val title = document.bestTitle()?.cleanTitle()?.ifBlank { null } ?: fixedUrl.slugTitle()
        val poster = document.bestPoster()
        val plot = document.bestPlot()
        val tags = document.select("a[href*='genre'], a[href*='tag'], .genre a, .genres a, .tax a, .info a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length <= 40 }
            .filterNot { it.isBlockedTitle() }
            .distinct()
            .take(20)
        val year = document.text().parseYear()
        val recommendations = document.parseItems().filter { it.url != fixedUrl }.distinctBy { it.url }.take(24)

        val episodes = document.select(
            ".eplister a[href], .episodelist a[href], .episode-list a[href], .episodes a[href], .les-content a[href], " +
                "a[href*='episode'], a[href*='eps'], a[href*='nonton'], a[href*='watch'], a[href*='/stream/']"
        ).mapNotNull { it.toEpisodeOrNull() }
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.episode ?: Int.MAX_VALUE }, { it.name ?: "" }))
            .ifEmpty {
                listOf(newEpisode(fixedUrl) {
                    this.name = title.parseEpisodeName() ?: title
                    this.episode = title.parseEpisodeNumber() ?: fixedUrl.parseEpisodeNumber()
                    this.posterUrl = poster
                })
            }

        val type = when {
            title.contains("Movie", true) || fixedUrl.contains("movie", true) -> TvType.AnimeMovie
            title.contains("OVA", true) || title.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }
        val status = document.text().parseStatus()

        return newAnimeLoadResponse(title, fixedUrl, type) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.takeIf { it.startsWith("http", true) } ?: return false
        val queue = ArrayDeque<ServerCandidate>()
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var hasLinks = false

        fun enqueue(candidate: ServerCandidate) {
            val normalized = normalizeMediaUrl(candidate.url) ?: return
            if (normalized.length < 8) return
            queue.add(ServerCandidate(normalized, candidate.label, candidate.referer))
        }

        suspend fun enqueueFromText(text: String?, referer: String) {
            text?.extractCandidatesFromText(referer)?.forEach { enqueue(it) }
        }

        suspend fun enqueueFromUrl(url: String, referer: String) {
            val response = runCatching { app.get(url, headers = playerHeaders(url), referer = referer) }.getOrNull() ?: return
            response.document.extractCandidates(url).forEach { enqueue(it) }
            enqueueFromText(response.text, url)
        }

        suspend fun emitDirect(url: String, label: String?, referer: String) {
            val fixed = normalizeMediaUrl(url) ?: return
            val key = fixed.normalizedMediaKey()
            if (!emitted.add(key)) return
            val qualityLabel = label?.cleanLabel().orEmpty().ifBlank { fixed.qualityLabelFromUrl() }
            val quality = qualityLabel.takeIf { it.isNotBlank() }?.let { getQualityFromName(it) }
                ?: fixed.parseQuality()
                ?: Qualities.Unknown.value

            if (fixed.contains(".m3u8", true)) {
                val links = M3u8Helper.generateM3u8(name, fixed, referer, headers = siteHeaders)
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
                        this.headers = siteHeaders + mapOf("Range" to "bytes=0-")
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
            val key = fixed.normalizedMediaKey()
            if (!emitted.add(key)) return
            loadExtractor(fixed, referer, subtitleCallback) { link ->
                callback(link)
                hasLinks = true
            }
        }

        enqueueFromUrl(pageUrl, "$mainUrl/")
        pageUrl.altPlayerUrls().forEach { enqueue(ServerCandidate(it, null, pageUrl)) }

        var guard = 0
        while (queue.isNotEmpty() && guard < 140) {
            guard++
            val candidate = queue.removeFirst()
            val fixed = normalizeMediaUrl(candidate.url) ?: continue
            if (!visited.add(fixed.normalizedMediaKey())) continue
            val referer = candidate.referer ?: pageUrl

            if (fixed.isDirectMedia()) {
                emitDirect(fixed, candidate.label, referer)
                continue
            }

            if (!fixed.startsWith(mainUrl, true)) {
                emitExtractor(fixed, candidate.label, referer)
                if (fixed.shouldInlineResolve()) enqueueFromUrl(fixed, referer)
                continue
            }

            enqueueFromUrl(fixed, referer)
        }

        return hasLinks
    }

    private fun buildPageCandidates(data: String, page: Int): List<String> {
        if (page <= 1) return listOf(data)
        val clean = data.trimEnd('/')
        val sep = if (clean.contains("?")) "&" else "?"
        return listOf(
            "$clean/page/$page/",
            "$clean$sep${"page=$page"}",
            "$clean$sep${"paged=$page"}"
        )
    }

    private fun Document.parseItems(): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val selectors = listOf(
            ".listupd .bs", ".bs", ".bsx", ".utao", ".animepost", ".anime-item", ".series-item",
            ".episode-item", ".post", ".post-item", ".item", ".card", ".col", "article", "li"
        )
        for (selector in selectors) {
            for (card in select(selector)) {
                val response = card.toSearchResponse() ?: continue
                results[response.url] = response
            }
            if (results.size >= 6) break
        }
        if (results.isEmpty()) {
            for (anchor in select("a[href]")) {
                val response = anchor.toSearchResponse() ?: continue
                results[response.url] = response
            }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val card = bestCard()
        val anchor = card.selectFirst("a[href*='/anime/'], a[href*='/episode/'], a[href*='/nonton/'], a[href*='/watch/'], a[href*='/movie/'], a[href*='/ova/'], a[href]")
            ?: if (tagName().equals("a", true)) this else null
            ?: return null
        val href = anchor.attr("href").toAbsoluteUrl() ?: return null
        if (!href.startsWith(mainUrl, true)) return null
        if (href.isBlockedUrl()) return null

        val title = listOf(
            card.selectFirst("h1, h2, h3, h4, h5, h6, .title, .judul, .tt, .entry-title")?.text(),
            anchor.attr("title"),
            card.selectFirst("img[alt]")?.attr("alt"),
            anchor.text(),
            card.text()
        ).firstCleanTitle() ?: return null
        if (title.isBlockedTitle()) return null

        val poster = card.bestImage()?.toAbsoluteUrl()
        val episode = card.text().parseEpisodeNumber() ?: href.parseEpisodeNumber()
        val tvType = when {
            title.contains("Movie", true) || href.contains("movie", true) -> TvType.AnimeMovie
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
        val href = attr("href").toAbsoluteUrl() ?: return null
        if (!href.startsWith(mainUrl, true) || href.isBlockedUrl()) return null
        val rawText = text().trim().ifBlank { attr("title") }.ifBlank { href.slugTitle() }
        val ep = rawText.parseEpisodeNumber() ?: href.parseEpisodeNumber()
        return newEpisode(href) {
            this.name = rawText.parseEpisodeName() ?: ep?.let { "Episode $it" } ?: rawText.cleanTitle()
            this.episode = ep
            this.posterUrl = bestCard().bestImage()?.toAbsoluteUrl()
        }
    }

    private fun Document.extractCandidates(referer: String): List<ServerCandidate> {
        val results = linkedMapOf<String, ServerCandidate>()
        fun add(raw: String?, label: String? = null) {
            val fixed = raw?.trim()?.trim('"', '\'', ' ', ',', ')', ']', '}')?.basicHtmlDecode()?.unescapeJs()?.toAbsoluteUrl() ?: return
            if (fixed.isBlank() || fixed == mainUrl || fixed == referer) return
            if (fixed.startsWith("javascript:", true) || fixed.startsWith("data:", true)) return
            if (!fixed.isPotentialPlayerUrl()) return
            results[fixed] = ServerCandidate(fixed, label, referer)
        }

        for (iframe in select("iframe[src], embed[src]")) add(iframe.attr("src"), iframe.attr("title").ifBlank { iframe.attr("name") })
        for (source in select("video[src], source[src]")) add(source.attr("src"), source.attr("label").ifBlank { source.attr("res") })
        for (element in select("[data-src], [data-url], [data-link], [data-iframe], [data-embed], [data-player], [data-video], [data-file], [data-stream], [data-id]")) {
            val label = element.text().ifBlank { element.attr("title") }.ifBlank { element.attr("aria-label") }
            val attrs = listOf("data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file", "data-stream")
            for (attr in attrs) add(element.attr(attr), label)
        }
        for (option in select("option[value]")) {
            add(option.attr("value"), option.text())
            decodePossibleBase64(option.attr("value"))?.extractCandidatesFromText(referer)?.forEach { add(it.url, option.text()) }
        }
        html().extractCandidatesFromText(referer).forEach { add(it.url, it.label) }
        return results.values.toList()
    }

    private fun String.extractCandidatesFromText(referer: String): List<ServerCandidate> {
        val results = linkedMapOf<String, ServerCandidate>()
        fun add(raw: String?, label: String? = null) {
            val fixed = raw?.trim()?.trim('"', '\'', ' ', ',', ')', ']', '}')?.basicHtmlDecode()?.unescapeJs()?.toAbsoluteUrl() ?: return
            if (fixed.isBlank() || fixed == mainUrl || fixed == referer) return
            if (fixed.startsWith("javascript:", true) || fixed.startsWith("data:", true)) return
            if (!fixed.isPotentialPlayerUrl()) return
            results[fixed] = ServerCandidate(fixed, label, referer)
        }

        val variants = linkedSetOf(this, basicHtmlDecode(), unescapeJs(), basicHtmlDecode().unescapeJs())
        runCatching { getAndUnpack(this) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { unpacked ->
            variants.add(unpacked)
            variants.add(unpacked.basicHtmlDecode().unescapeJs())
        }

        for (source in variants) {
            val cleaned = source.basicHtmlDecode().unescapeJs()
            Regex("""(?:src|url|link|file|iframe|embed|player|video|stream|source|contentUrl|embedUrl)\s*[\"']?\s*[:=]\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
                .findAll(cleaned).forEach { add(it.groupValues.getOrNull(1), it.value.qualityLabelFromUrl()) }
            Regex("""https?://[^\"'<>&\s\\]+""", RegexOption.IGNORE_CASE)
                .findAll(cleaned).forEach { add(it.value, it.value.qualityLabelFromUrl()) }
            Regex("""(?:atob\(|base64_decode\(|[\"'])([A-Za-z0-9+/=_-]{32,})(?:[\"']|\))""")
                .findAll(cleaned).forEach { match ->
                    val decoded = decodePossibleBase64(match.groupValues[1]) ?: return@forEach
                    decoded.extractCandidatesFromText(referer).forEach { add(it.url, it.label) }
                }
        }
        return results.values.toList()
    }

    private fun String.altPlayerUrls(): List<String> {
        val clean = trimEnd('/')
        val slug = clean.substringAfterLast('/').substringBefore('?')
        if (slug.isBlank()) return emptyList()
        return listOf(
            "$mainUrl/watch/$slug/",
            "$mainUrl/nonton/$slug/",
            "$mainUrl/episode/$slug/",
            "$mainUrl/player/$slug/",
            "$mainUrl/embed/$slug/"
        )
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        val next = page + 1
        return selectFirst("a[rel=next], .pagination a[href*='page/$next'], .pagination a[href*='paged=$next'], a.next") != null
    }

    private fun Document.bestTitle(): String? {
        return selectFirst("h1, .title h1, .entry-title, .anime-title, .judul, .post-title")?.text()?.trim()
            ?: selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content")?.trim()
            ?: selectFirst("title")?.text()?.substringBefore("|")?.substringBefore(" - ")?.trim()
    }

    private fun Document.bestPoster(): String? {
        return listOfNotNull(
            selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content"),
            selectFirst(".poster img, .cover img, .thumb img, .thumbnail img, article img, main img")?.bestImage()
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
            raw.startsWith("?") -> "${mainUrl.trimEnd('/')}/$raw"
            raw.startsWith("./") -> mainUrl.trimEnd('/') + "/" + raw.removePrefix("./")
            raw.contains(".") && !raw.contains(" ") -> "https://$raw"
            raw.length > 2 && !raw.contains(" ") -> mainUrl.trimEnd('/') + "/" + raw.trimStart('/')
            else -> null
        }
    }

    private fun normalizeMediaUrl(raw: String?): String? {
        return raw?.basicHtmlDecode()?.unescapeJs()?.replace("\\/", "/")?.toAbsoluteUrl()
    }

    private fun String.isPotentialPlayerUrl(): Boolean {
        val lower = lowercase()
        return lower.isDirectMedia() ||
            lower.contains("/embed") || lower.contains("iframe") || lower.contains("player") || lower.contains("stream") ||
            lower.contains("vidhide") || lower.contains("filedon") || lower.contains("mega") || lower.contains("dood") ||
            lower.contains("filemoon") || lower.contains("mp4upload") || lower.contains("voe") || lower.contains("mixdrop") ||
            lower.contains("streamtape") || lower.contains("pixeldrain") || lower.contains("gdrive") || lower.contains("googlevideo.com/videoplayback") ||
            lower.contains("blogger.com/video") || lower.contains("drive.google") || lower.startsWith(mainUrl.lowercase())
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") ||
            lower.contains("googlevideo.com/videoplayback") || lower.contains("mime=video") || lower.contains("cloudflarestorage.com")
    }

    private fun String.shouldInlineResolve(): Boolean {
        val lower = lowercase()
        return lower.contains("vidhide") || lower.contains("filedon") || lower.contains("dood") || lower.contains("filemoon") ||
            lower.contains("stream") || lower.contains("player") || lower.contains("embed") || lower.startsWith(mainUrl.lowercase())
    }

    private fun String.normalizedMediaKey(): String {
        return substringBefore("&Expires=").substringBefore("?Expires=").substringBefore("&X-Amz-Signature=")
    }

    private fun String.cleanTitle(): String {
        return htmlDecode()
            .replace(Regex("(?i)\\s*sub\\s*indo.*$"), "")
            .replace(Regex("(?i)\\s*nonton\\s*"), "")
            .replace(Regex("(?i)\\s*episode\\s*\\d+.*$"), "")
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
            "home", "beranda", "anime", "daftar anime", "ongoing", "completed", "movie", "genre", "jadwal rilis",
            "login", "register", "nonton", "previous", "next", "film anime", "filmanime", "privacy policy", "dmca"
        )
    }

    private fun String.isBlockedUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("/genre/") || lower.contains("/tag/") || lower.contains("/category/") || lower.contains("/page/") ||
            lower.contains("login") || lower.contains("register") || lower.contains("wp-admin") || lower.contains("privacy") || lower.contains("dmca") ||
            lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".webp")
    }

    private fun String.slugTitle(): String = substringBefore("?").trimEnd('/').substringAfterLast('/').replace('-', ' ').cleanTitle().ifBlank { name }

    private fun String.parseYear(): Int? = Regex("""\b(?:19|20)\d{2}\b""").find(this)?.value?.toIntOrNull()

    private fun String.parseStatus(): ShowStatus? {
        return when {
            contains("Completed", true) || contains("Tamat", true) -> ShowStatus.Completed
            contains("Ongoing", true) || contains("Berjalan", true) -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun String.parseEpisodeNumber(): Int? {
        val match = Regex("""(?i)(?:episode|eps|ep)\s*[-:]?\s*(\d{1,4})""").find(this)
            ?: Regex("""\bEps?\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE).find(this)
            ?: Regex("""-(\d{1,4})(?:-|$)""").find(this)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.parseEpisodeName(): String? = parseEpisodeNumber()?.let { "Episode $it" }

    private fun String.htmlDecode(): String = Jsoup.parse(this).text()

    private fun String.basicHtmlDecode(): String {
        return replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#x22;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun String.unescapeJs(): String {
        var output = this
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }
        return output.replace("\\/", "/").replace("\\\"", "\"").replace("\\'", "'")
    }

    private fun decodePossibleBase64(value: String?): String? {
        val raw = value?.trim()?.trim('"', '\'', ' ') ?: return null
        if (raw.length < 16) return null
        return runCatching {
            val normalized = raw.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
        }.getOrNull()?.takeIf { it.contains("http", true) || it.contains("<iframe", true) || it.contains("source", true) }
    }

    private fun String.parseQuality(): Int? = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    private fun String.qualityLabelFromUrl(): String = parseQuality()?.let { "${it}p" }.orEmpty()
    private fun String.cleanLabel(): String = htmlDecode().replace(Regex("\\s+"), " ").trim()

    private fun playerHeaders(url: String): Map<String, String> {
        val lower = url.lowercase()
        return siteHeaders + when {
            lower.contains(mainUrl.removePrefix("https://")) -> mapOf("Referer" to "$mainUrl/")
            else -> mapOf("Referer" to "$mainUrl/")
        }
    }

    private data class ServerCandidate(
        val url: String,
        val label: String?,
        val referer: String?
    )
}
