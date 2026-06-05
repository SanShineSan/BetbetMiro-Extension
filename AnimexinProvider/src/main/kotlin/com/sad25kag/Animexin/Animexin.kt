package com.sad25kag.Animexin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Animexin : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "Animexin"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Cartoon)

    override val mainPage = mainPageOf(
        "anime/?status=&order=latest&page=" to "Recently Updated",
        "anime/?order=popular&page=" to "Popular",
        "anime/?status=&type=ona&page=" to "Donghua",
        "anime/?status=&type=movie&page=" to "Movies",
        "genre/action/page/" to "Action",
        "genre/adventure/page/" to "Adventure",
        "genre/demon/page/" to "Demon",
        "genre/fantasy/page/" to "Fantasy",
        "genre/historical/page/" to "Historical",
        "genre/martial-arts/page/" to "Martial Arts",
        "genre/romance/page/" to "Romance",
        "genre/supernatural/page/" to "Supernatural"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.endsWith("page/") -> "$mainUrl/${request.data}$page/"
            request.data.endsWith("page=") -> "$mainUrl/${request.data}$page"
            else -> "$mainUrl/${request.data}&page=$page"
        }
        val document = app.get(url).document
        val home = document.select("div.listupd article, article.bs, article, .bs, .bsx")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }.let { fixUrl(it) }
        if (!href.startsWith(mainUrl) || href.contains("/episode-", true)) return null

        val title = anchor.attr("title").ifBlank {
            selectFirst(".tt, .ttls, h2, h3, h4, .post-title, .entry-title")?.text()
        }.ifBlank { anchor.text() }.trim()
        if (title.isBlank()) return null

        val poster = selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-lazy-src") }
                .ifBlank { img.attr("src") }
                .ifBlank { img.attr("abs:src") }
        }?.let { fixUrlNull(it) }

        val typeText = text()
        val tvType = if (typeText.contains("movie", true)) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title.cleanTitle(), href, tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val queryTokens = query.lowercase().split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length > 1 }
        val results = linkedMapOf<String, SearchResponse>()

        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/2/?s=$encoded",
            "$mainUrl/page/3/?s=$encoded"
        )

        urls.forEach { url ->
            runCatching {
                val document = app.get(url).document
                document.select("div.listupd article, article.bs, article, .bs, .bsx")
                    .mapNotNull { it.toSearchResult() }
                    .filter { result ->
                        if (queryTokens.isEmpty()) true else {
                            val haystack = "${result.name} ${result.url.substringAfterLast('/')}".lowercase()
                            queryTokens.all { haystack.contains(it) }
                        }
                    }
                    .forEach { results[it.url] = it }
            }
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()?.cleanTitle()
            ?: fixedUrl.substringAfterLast('/').replace('-', ' ').trim().ifBlank { name }

        val poster = document.selectFirst("div.thumb img, .thumb img, .ime img, .poster img, meta[property=og:image]")?.let { element ->
            element.attr("content").ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-lazy-src") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("abs:src") }
        }?.let { fixUrlNull(it) }

        val description = document.selectFirst("div.entry-content, .entry-content, .desc, .synopsis")
            ?.text()?.trim()?.cleanDescription()

        val tags = document.select("a[href*=/genre/], .genres a, .genre a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val typeText = document.select(".spe, .info, .meta, .type").text()
        val isMovie = typeText.contains("movie", true) || tags.any { it.equals("Movie", true) }
        val episodes = document.parseEpisodes(poster)

        return if (!isMovie && episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, fixedUrl, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            val movieData = document.selectFirst(".eplister li a[href], .episodelist li a[href], a[href*='-episode-']")
                ?.attr("abs:href")
                ?.ifBlank { document.selectFirst(".eplister li a[href], .episodelist li a[href], a[href*='-episode-']")?.attr("href") }
                ?.let { fixUrl(it) }
                ?: fixedUrl

            newMovieLoadResponse(title, fixedUrl, TvType.AnimeMovie, movieData) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    private fun Document.parseEpisodes(poster: String?): List<Episode> {
        val episodeSelectors = listOf(
            "div.eplister > ul > li",
            "div.eplister li",
            ".episodelist li",
            "ul li"
        ).joinToString(",")

        return select(episodeSelectors).mapNotNull { item ->
            val anchor = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }.let { fixUrl(it) }
            if (!href.contains("-episode-", true)) return@mapNotNull null

            val titleText = item.selectFirst(".epl-title, .entry-title, .title, h3, h4")?.text()?.trim()
                ?: anchor.text().trim()
            val number = Regex("(?i)episode\\s*(\\d+)").find(titleText)?.groupValues?.getOrNull(1)
                ?.toIntOrNull()
                ?: Regex("(?i)-episode-(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val date = item.selectFirst(".epl-date, .date, time")?.text()?.trim().orEmpty()

            newEpisode(href) {
                this.name = if (number != null) "Episode $number" else titleText.cleanTitle().ifBlank { "Episode" }
                this.episode = number
                this.posterUrl = poster
                if (date.isNotBlank()) this.description = "Rilis: $date"
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startUrl = fixUrl(data)
        val visited = mutableSetOf<String>()
        val candidates = linkedSetOf<String>()
        var loaded = false

        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            loaded = true
            callback(link)
        }

        suspend fun collectFromPage(url: String, depth: Int = 0) {
            val normalizedPage = normalizeUrl(url) ?: return
            if (!visited.add(normalizedPage) || depth > 2) return

            val response = runCatching { app.get(normalizedPage, referer = mainUrl) }.getOrNull() ?: return
            val text = response.text
            val doc = runCatching { response.document }.getOrNull() ?: Jsoup.parse(text)

            collectFromDocument(doc, candidates)
            collectFromText(text, candidates)

            doc.select(".mobius option, #mobius option, select.mirror option, select option, option")
                .forEach { option ->
                    val value = option.attr("value").trim()
                    if (value.isNotBlank()) collectFromValue(value, candidates)
                }
        }

        collectFromPage(startUrl)

        val initialCandidates = candidates.toList()
        initialCandidates.forEach { candidate ->
            val url = normalizeUrl(candidate) ?: return@forEach
            if (shouldFollowForMoreLinks(url)) {
                collectFromPage(url, 1)
            }
        }

        candidates.mapNotNull { normalizeUrl(it) }
            .filter { isPlayableCandidate(it) }
            .distinct()
            .forEach { playerUrl ->
                runCatching {
                    loadExtractor(playerUrl, startUrl, subtitleCallback, wrappedCallback)
                }
            }

        return loaded
    }

    private fun collectFromDocument(document: Document, output: MutableSet<String>) {
        document.select("iframe[src], embed[src], video[src], source[src], a[href]").forEach { element ->
            listOf("src", "href").forEach { attr ->
                val value = element.attr(attr).trim()
                if (value.isNotBlank()) collectFromValue(value, output)
            }
        }

        document.select("[data-src], [data-video], [data-url], [data-file], [data-link], [data-embed]")
            .forEach { element ->
                listOf("data-src", "data-video", "data-url", "data-file", "data-link", "data-embed").forEach { attr ->
                    val value = element.attr(attr).trim()
                    if (value.isNotBlank()) collectFromValue(value, output)
                }
            }
    }

    private fun collectFromValue(rawValue: String, output: MutableSet<String>) {
        val value = rawValue.trim().cleanEscapes()
        if (value.isBlank()) return

        if (value.contains("<iframe", true) || value.contains("<video", true) || value.contains("<source", true)) {
            val parsed = Jsoup.parse(value)
            collectFromDocument(parsed, output)
            collectFromText(value, output)
        }

        safeBase64Decode(value)?.let { decoded ->
            if (decoded.isNotBlank() && decoded != value) {
                collectFromValue(decoded, output)
            }
        }

        normalizeUrl(value)?.let { output.add(it) }
        collectFromText(value, output)
    }

    private fun collectFromText(text: String, output: MutableSet<String>) {
        val cleaned = text.cleanEscapes()
        urlRegex.findAll(cleaned).forEach { match ->
            normalizeUrl(match.value)?.let { output.add(it) }
        }
        protocolRelativeRegex.findAll(cleaned).forEach { match ->
            normalizeUrl(match.value)?.let { output.add(it) }
        }
    }

    private fun normalizeUrl(rawUrl: String): String? {
        var url = rawUrl.trim()
            .trim('"', '\'', '`')
            .substringBefore("\\\"")
            .substringBefore("\"")
            .substringBefore("'")
            .cleanEscapes()

        if (url.isBlank()) return null
        if (url.startsWith("//")) url = "https:$url"
        if (url.startsWith("/")) url = fixUrl(url)
        if (!url.startsWith("http", true)) return null

        return url
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .trim()
    }

    private fun isPlayableCandidate(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        if (lower.contains(mainHost()) && !shouldFollowForMoreLinks(lower)) return false

        return playerHostHints.any { lower.contains(it) } || directMediaHints.any { lower.contains(it) }
    }

    private fun shouldFollowForMoreLinks(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(mainHost()) && (
            lower.contains("/embed") ||
            lower.contains("player") ||
            lower.contains("ajax") ||
            lower.contains("wp-admin") ||
            lower.contains("wp-content") ||
            lower.contains("utils") ||
            lower.contains("stream")
        )
    }

    private fun safeBase64Decode(value: String): String? {
        val candidate = value.trim()
        if (candidate.length < 8 || !candidate.matches(Regex("^[A-Za-z0-9+/=_%.-]+$"))) return null
        return runCatching { base64Decode(candidate) }
            .getOrNull()
            ?.takeIf { it.contains("http", true) || it.contains("iframe", true) || it.contains("video", true) }
    }

    private fun mainHost(): String = runCatching { URI(mainUrl).host.removePrefix("www.") }.getOrDefault("animexin.dev")

    private fun String.cleanTitle(): String = this
        .replace(Regex("(?i)subtitle\\s+indonesia"), "")
        .replace(Regex("(?i)english\\s+sub"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.cleanDescription(): String = this
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.cleanEscapes(): String = this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .replace("%3A", ":")
        .replace("%2F", "/")

    companion object {
        private val urlRegex = Regex("""https?:\\?/\\?/[^"'\\< >\n\r\t]+""")
        private val protocolRelativeRegex = Regex("""(?<!:)//[A-Za-z0-9][^"'\\< >\n\r\t]+""")

        private val playerHostHints = listOf(
            "dailymotion.com",
            "dai.ly",
            "odysee.com",
            "mega.nz",
            "rumble.com",
            "dood",
            "doodstream",
            "ok.ru",
            "streamwish",
            "wishfast",
            "filelions",
            "vidhide",
            "vidhidepro",
            "vidguard",
            "streamtape",
            "mixdrop",
            "mp4upload",
            "yourupload",
            "uqload",
            "krakenfiles",
            "abyss",
            "filemoon",
            "lulustream",
            "voe.sx",
            "player",
            "embed"
        )

        private val directMediaHints = listOf(
            ".m3u8",
            ".mp4",
            ".webm",
            ".mkv"
        )
    }
}
