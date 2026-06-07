package com.sad25kag.Animekhor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

open class Animekhor : MainAPI() {
    override var mainUrl = "https://animekhor.org"
    override var name = "Animekhor"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Latest Release",
        "donghua-series/" to "Donghua Series",
        "comic-series/" to "Comic Series",
        "anime/?order=&status=&type=" to "Anime List",
        "anime/?status=completed&type=&order=update" to "Completed",
        "anime/?status=&type=ona&sub=&order=update" to "Donghua Recently Updated",
        "anime/?type=comic&order=update" to "Comic Recently Updated"
    )

    private fun buildPageUrl(data: String, page: Int): String {
        val clean = data.trim()
        if (clean.isBlank()) {
            return if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        }

        val normalized = clean.trimStart('/')
        return when {
            normalized.contains("?") -> {
                val separator = if (normalized.contains("?") && !normalized.endsWith("?") && !normalized.endsWith("&")) "&" else ""
                "$mainUrl/$normalized${separator}page=$page"
            }
            page <= 1 -> "$mainUrl/${normalized.trimEnd('/')}/"
            else -> "$mainUrl/${normalized.trimEnd('/')}/page/$page/"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page)).document
        val home = document.select(CARD_SELECTOR)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select("li.next a, a.next, a[rel=next], .pagination a.next, .hpage a.r")
            .isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("div.bsx > a[href], .bsx > a[href], a[href*='/anime/'], a[href*='episode'], a[href*='subtitles-english'], a[href*='subtitles-indonesian'], a[href]")
            ?: return null
        val href = fixUrl(link.attr("href").takeIf { it.isNotBlank() } ?: return null)
        if (!isContentUrl(href)) return null

        val title = link.attr("title").ifBlank {
            selectFirst("h2, h3, .tt, .entry-title, .post-title, .title, .ep-title, .series-title")?.text()?.trim().orEmpty()
        }.ifBlank {
            selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }.ifBlank {
            link.text().trim()
        }.ifBlank {
            text().trim()
        }.replace(Regex("\\s+"), " ").trim()

        if (title.length < 2) return null

        val posterUrl = selectFirst("div.bsx > a img, .bsx img, .thumb img, .poster img, img")
            ?.getsrcAttribute()
            ?.let { fixUrlNull(it) }

        val tvType = if (isSeriesUrl(href)) TvType.Anime else TvType.Movie

        return if (tvType == TvType.Anime) {
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    private fun isContentUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith(mainUrl)) return false
        if (
            lower.contains("/genre/") || lower.contains("/genres/") ||
            lower.contains("/tag/") || lower.contains("/schedule") ||
            lower.contains("/bookmarks") || lower.contains("/history") ||
            lower.contains("filter-search") || lower.endsWith("/anime/") ||
            lower.endsWith("/donghua-series/") || lower.endsWith("/comic-series/") ||
            lower.endsWith(mainUrl) || lower == "$mainUrl/"
        ) return false

        return isSeriesUrl(lower) || isEpisodeUrl(lower)
    }

    private fun isSeriesUrl(url: String): Boolean {
        return url.lowercase().contains("/anime/")
    }

    private fun isEpisodeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("episode") ||
            lower.contains("movie-subtitles") ||
            lower.contains("subtitles-english") ||
            lower.contains("subtitles-indonesian")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val queryParts = query.lowercase()
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.length >= 2 }

        if (queryParts.isEmpty()) return emptyList()

        val searchResponse = mutableListOf<SearchResponse>()

        suspend fun collectSearchPage(url: String, strictTitleFilter: Boolean = true): Boolean {
            val document = runCatching { app.get(url).document }.getOrNull() ?: return false
            val results = document.select(CARD_SELECTOR)
                .mapNotNull { it.toSearchResult() }
                .filter { result ->
                    if (!strictTitleFilter) return@filter true
                    val name = result.name.lowercase()
                    queryParts.all { name.contains(it) }
                }

            results.forEach { result ->
                if (searchResponse.none { it.url == result.url }) searchResponse.add(result)
            }

            return results.isNotEmpty()
        }

        collectSearchPage("$mainUrl/?s=$encodedQuery", strictTitleFilter = false)
        for (page in 2..3) {
            val hasResults = collectSearchPage("$mainUrl/page/$page/?s=$encodedQuery", strictTitleFilter = false)
            if (!hasResults) break
        }

        if (searchResponse.isNotEmpty()) return searchResponse.distinctBy { it.url }

        for (page in 1..5) {
            val hasResults = collectSearchPage("$mainUrl/anime/?order=&status=&type=&page=$page")
            if (!hasResults && page > 1) break
        }

        return searchResponse.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1, .entry-title")
            ?.text()?.trim()?.ifBlank { null }
            ?: throw ErrorLoadingException("Judul tidak ditemukan")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".thumb img, .poster img, .entry-content img, .postbody img")?.getsrcAttribute()

        val description = document.selectFirst("div.entry-content, .entry-content, .contentdeks, .entry-content-single")?.text()?.trim()
        val type = document.selectFirst(".spe, .info-content, .infox, .mindesc")?.text().orEmpty()

        val episodes = document.select(
            "div.eplister ul li a[href], div.episodelist ul li a[href], div.bixbox.bxcl ul li a[href], .eplister a[href], .episodelist a[href], .episode-list a[href], .episodelist a[href*='episode'], ul li a[href*='episode'], a[href*='subtitles-english'], a[href*='subtitles-indonesian']"
        ).mapNotNull { anchor ->
            val href = fixUrl(anchor.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null)
            if (!isEpisodeUrl(href) || !isContentUrl(href)) return@mapNotNull null
            val raw = anchor.text().trim().ifBlank { href.substringAfterLast("/").replace("-", " ") }
            newEpisode(href) {
                this.name = raw
                this.episode = parseEpisodeNumber(raw, href)
                this.posterUrl = fixUrlNull(poster.orEmpty())
            }
        }.distinctBy { it.data }.reversed()

        return if (episodes.isNotEmpty() && !type.contains("Movie", true) && isSeriesUrl(url)) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster.orEmpty())
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster.orEmpty())
                this.plot = description
            }
        }
    }

    private fun parseEpisodeNumber(text: String, url: String): Int? {
        return Regex("""(?i)\b(?:episode|eps|ep)\s*-?\s*(\d+)\b""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)(?:episode|eps|ep)-?(\d+)""")
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val candidates = linkedSetOf<String>()

        fun addCandidate(raw: String?) {
            normalizePlayerUrl(raw)?.let { candidates.add(it) }
        }

        fun addDecodedMirror(value: String) {
            if (value.isBlank()) return

            val decoded = runCatching { base64Decode(value) }.getOrNull() ?: return
            val decodedDocument = Jsoup.parse(decoded)

            decodedDocument.select("iframe[src]").forEach { iframe ->
                addCandidate(iframe.attr("src"))
            }

            Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(decoded)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .forEach(::addCandidate)
        }

        document.select("#pembed iframe[src], .player-embed iframe[src], .video-content iframe[src], .player-area iframe[src], .embed-responsive iframe[src], iframe[src]").forEach { iframe ->
            addCandidate(iframe.attr("src"))
        }

        document.select(".mobius select.mirror option[value], select.mirror option[value], option[value]").forEach { option ->
            addDecodedMirror(option.attr("value"))
        }

        document.select("div.server-item a[data-hash], .server-item a[data-hash], a[data-hash]").forEach { server ->
            addDecodedMirror(server.attr("data-hash"))
        }

        document.select("script").forEach { script ->
            val dataScript = script.data().ifBlank { script.html() }
            Regex("""(?i)<iframe[^>]+src=["']([^"']+)["']""")
                .findAll(dataScript)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .forEach(::addCandidate)
            Regex("""(?i)(?:file|src)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                .findAll(dataScript)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .forEach(::addCandidate)
        }

        var found = false
        candidates.forEach { url ->
            if (isOkruUrl(url)) return@forEach

            val referer = if (isAnimekhorPlayer(url)) mainUrl else data
            try {
                loadExtractor(url = url, referer = referer, subtitleCallback = subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            } catch (_: Throwable) {
            }
        }

        return found
    }

    private fun normalizePlayerUrl(raw: String?): String? {
        val cleaned = raw?.trim()
            ?.replace("&amp;", "&")
            ?.replace("\\/", "/")
            ?.takeIf { it.isNotBlank() } ?: return null

        val fixed = when {
            cleaned.startsWith("//") -> httpsify(cleaned)
            cleaned.startsWith("/") -> "$mainUrl$cleaned"
            cleaned.startsWith("http", true) -> cleaned
            else -> return null
        }

        return fixed.takeUnless { isBadPlayerCandidate(it) }
    }

    private fun isAnimekhorPlayer(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("animekhor.p2pstream.vip") || lower.contains("animekhor.upns.live")
    }

    private fun isBadPlayerCandidate(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("acceptable.a-ads.com") ||
            lower.contains("/anime/") || lower.contains("/genre/") ||
            lower.contains("/genres/") || lower.contains("/tag/") ||
            lower.contains("/schedule") || lower.contains("facebook.com") ||
            lower.contains("twitter.com") || lower.contains("telegram") ||
            lower.contains("whatsapp") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") ||
            lower.endsWith(".css") || lower.endsWith(".js")
    }

    private fun isOkruUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("ok.ru") || lower.contains("odnoklassniki")
    }

    private fun Element.getsrcAttribute(): String {
        val src = attr("src")
        val dataSrc = attr("data-src")
        val dataLazySrc = attr("data-lazy-src")
        return src.takeIf { it.startsWith("http") }
            ?: dataSrc.takeIf { it.startsWith("http") }
            ?: dataLazySrc.takeIf { it.startsWith("http") }
            ?: ""
    }

    companion object {
        private const val CARD_SELECTOR =
            "div.listupd > article, article.bs, div.bs, div.bsx, .listupd .bsx, .listupd article, .postbody article, .post, .hentry"
    }
}