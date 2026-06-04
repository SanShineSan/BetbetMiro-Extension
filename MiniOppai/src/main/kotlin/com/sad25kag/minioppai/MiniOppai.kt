package com.sad25kag.minioppai

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class MiniOppai : MainAPI() {
    override var mainUrl = "https://minioppai.org"
    override var name = "MiniOppai"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "" to "Rilisan Terbaru",
        "/populars/" to "Popular",
        "/uncensored/" to "Uncensored",
        "/festa/" to "Festa",
        "/schedule/" to "Schedule",
        "/genre/big-oppai/" to "Big Oppai",
        "/genre/vanilla/" to "Vanilla",
        "/genre/milf/" to "MILF",
        "/genre/ntr/" to "NTR",
        "/genre/maid/" to "Maid",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page), headers = browserHeaders).document
        val results = when (request.data) {
            "/populars/" -> parseRankingCards(document).ifEmpty { parseMiniOppaiCards(document) }
            else -> parseMiniOppaiCards(document)
        }.distinctBy { normalizeKey(it.url) }

        val hasNext = document.selectFirst("a.next[href], a[rel=next], .pagination a[href]:contains(Selanjutnya), .nav-links a[href]:contains(Next)") != null
        return newHomePageResponse(request.name, results, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val routes = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/anime-list/?s=$encoded",
        )

        return routes.flatMap { url ->
            runCatching {
                val document = app.get(url, headers = browserHeaders).document
                parseMiniOppaiCards(document) + parseRankingCards(document)
            }.getOrDefault(emptyList())
        }.distinctBy { normalizeKey(it.url) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders).document
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1[itemprop=name], h1, .entry-title")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ) ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl()
            ?: document.selectFirst(".thumb img, .bigcontent img, .animefull img, .postbody img, article img")?.imageUrl()

        val plot = document.selectFirst(".entry-content p, .synopsis, .sinopsis, .desc, .description, meta[name=description]")?.let {
            if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
        }?.cleanText()

        val tags = document.select("a[href*='/genre/'], a[rel=tag]")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = parseEpisodes(document).distinctBy { normalizeKey(it.data) }
        val recommendations = parseMiniOppaiCards(document)
            .filterNot { normalizeKey(it.url) == normalizeKey(url) }
            .take(16)

        return if (episodes.isNotEmpty() && !looksEpisodePage(url, title)) {
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                this.showStatus = detectStatus(document)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
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
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data, headers = browserHeaders).document
        val emitted = linkedSetOf<String>()

        fun mark(url: String): Boolean = emitted.add(url.substringBefore("#"))

        suspend fun emitDirect(rawUrl: String?, label: String? = null, referer: String = data): Boolean {
            val videoUrl = rawUrl?.decodeUrlLike()?.toAbsoluteUrl(referer)?.takeIf { it.isDirectMedia() } ?: return false
            if (!mark(videoUrl)) return true
            val sourceName = listOfNotNull(name, label?.cleanText()?.takeIf { it.isNotBlank() }).joinToString(" - ")
            if (videoUrl.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(
                    sourceName,
                    videoUrl,
                    referer = referer,
                    headers = browserHeaders + mapOf("Referer" to referer),
                ).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(sourceName, sourceName, videoUrl, INFER_TYPE) {
                        this.referer = referer
                        this.quality = getQualityFromName(label ?: videoUrl)
                        this.headers = browserHeaders + mapOf("Referer" to referer)
                    },
                )
            }
            return true
        }

        val candidates = linkedSetOf<String>()
        candidates.addAll(collectStaticPlayers(document, data))
        candidates.addAll(collectAjaxPlayers(document, data))
        candidates.addAll(collectScriptPlayers(document.html(), data))

        for (candidate in candidates.take(40)) {
            val fixed = candidate.decodeUrlLike().toAbsoluteUrl(data) ?: continue
            if (fixed.isDirectMedia()) {
                emitDirect(fixed, hostLabel(fixed), data)
                continue
            }
            if (!fixed.isSupportedPlayerUrl()) continue
            val before = emitted.size
            val countedCallback: (ExtractorLink) -> Unit = { link ->
                emitted.add(link.url.substringBefore("#"))
                callback.invoke(link)
            }
            runCatching { loadExtractor(fixed, data, subtitleCallback, countedCallback) }
            if (emitted.size > before) continue

            val playerHtml = runCatching { app.get(fixed, headers = browserHeaders + mapOf("Referer" to data), referer = data).text }.getOrNull() ?: continue
            val unpacked = runCatching { getAndUnpack(playerHtml) }.getOrNull().orEmpty()
            val nested = collectScriptPlayers(playerHtml + "\n" + unpacked, fixed)
            for (nestedUrl in nested.take(12)) {
                if (emitDirect(nestedUrl, hostLabel(fixed), fixed)) continue
                runCatching { loadExtractor(nestedUrl, fixed, subtitleCallback, countedCallback) }
            }
        }

        return emitted.isNotEmpty()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val base = when {
            path.isBlank() -> mainUrl
            path.startsWith("http", true) -> path.trimEnd('/')
            else -> mainUrl + path
        }.trimEnd('/')
        return if (page <= 1) "$base/" else "$base/page/$page/"
    }

    private fun parseMiniOppaiCards(document: Document): List<SearchResponse> {
        val roots = listOf(
            document.select(".listupd article, .listupd .bs, .listupd .bsx"),
            document.select(".postbody article, .postbody .bsx, main article"),
            document.select("article:has(a[href*='episode']), article:has(a[href*='/anime/'])"),
            document.select(".bixbox .bsx, .serieslist li, .releases article"),
        )
        return roots.asSequence()
            .flatMap { it.asSequence() }
            .mapNotNull { it.toMiniOppaiCard() }
            .distinctBy { normalizeKey(it.url) }
            .toList()
    }

    private fun parseRankingCards(document: Document): List<SearchResponse> {
        return document.select(".popular, .ranking, .topten, .sidebar, aside")
            .flatMap { section -> section.select("a[href*='/anime/'], a[href*='episode']") }
            .mapNotNull { anchor ->
                val href = anchor.attr("href").toAbsoluteUrl() ?: return@mapNotNull null
                val title = anchor.text().cleanText().takeIf { it.length > 2 }
                    ?: anchor.attr("title").cleanText().takeIf { it.length > 2 }
                    ?: return@mapNotNull null
                newMovieSearchResponse(cleanTitle(title) ?: title, href, TvType.NSFW) {
                    this.posterUrl = anchor.selectFirst("img")?.imageUrl()
                    this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                }
            }
            .distinctBy { normalizeKey(it.url) }
    }

    private fun Element.toMiniOppaiCard(): SearchResponse? {
        val anchor = selectFirst("h2 a[href], h3 a[href], .tt a[href], .bsx a[href], a[href*='episode'], a[href*='/anime/']") ?: return null
        val href = anchor.attr("href").toAbsoluteUrl() ?: return null
        val title = anchor.attr("title").cleanText().takeIf { it.length > 2 }
            ?: anchor.selectFirst("h2, h3, .tt, .title")?.text()?.cleanText()?.takeIf { it.length > 2 }
            ?: anchor.text().cleanText().takeIf { it.length > 2 }
            ?: selectFirst("h2, h3, .tt, .title")?.text()?.cleanText()?.takeIf { it.length > 2 }
            ?: return null
        val poster = selectFirst("img")?.imageUrl()
            ?: styleImage()
            ?: anchor.selectFirst("img")?.imageUrl()
        return newMovieSearchResponse(cleanTitle(title) ?: title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        val anchors = document.select(
            ".eplister li a[href], .episodelist a[href], .episode-list a[href], .epslist a[href], .bixbox a[href*='episode'], .entry-content a[href*='episode']"
        )
        return anchors.mapNotNull { anchor ->
            val href = anchor.attr("href").toAbsoluteUrl() ?: return@mapNotNull null
            val rawTitle = anchor.selectFirst(".epl-title, .title, .ep-title, h3")?.text()
                ?: anchor.text()
                ?: return@mapNotNull null
            val title = rawTitle.cleanText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val episodeNumber = Regex("""(?i)(?:episode|eps?|ep)\s*(\d+)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""-(\d+)/?$""").find(href.trimEnd('/'))?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = cleanTitle(title) ?: title
                this.episode = episodeNumber
                this.posterUrl = anchor.selectFirst("img")?.imageUrl()
            }
        }.distinctBy { normalizeKey(it.data) }
    }

    private suspend fun collectAjaxPlayers(document: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val options = document.select("#playeroptionsul li, ul#playeroptionsul li, li.dooplay_player_option, .dooplay_player_option, [data-post][data-nume], [data-post][data-type], [data-id][data-nume]")

        for (option in options) {
            val post = option.attr("data-post").ifBlank { option.attr("data-id") }
            val nume = option.attr("data-nume").ifBlank { option.attr("data-index").ifBlank { option.attr("data-server").ifBlank { "1" } } }
            val type = option.attr("data-type").ifBlank { "movie" }
            if (post.isBlank()) continue
            val actions = listOf("doo_player_ajax", "doo_ajax_player", "player_ajax", "minioppai_player_ajax")
            for (action in actions) {
                val body = runCatching {
                    app.post(
                        ajaxUrl,
                        data = mapOf("action" to action, "post" to post, "nume" to nume, "type" to type),
                        headers = ajaxHeaders(pageUrl),
                        referer = pageUrl,
                    ).text
                }.getOrDefault("")
                links.addAll(collectScriptPlayers(body, pageUrl))
            }
        }
        return links.toList()
    }

    private fun collectStaticPlayers(document: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select("iframe[src], embed[src], video[src], video source[src], source[src]").forEach { element ->
            links.add(element.attr("src"))
        }
        val dataAttrs = listOf("data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file", "data-stream", "data-href")
        document.select("a[href], option[value], button, div, li").forEach { element ->
            val href = element.attr("href").ifBlank { element.attr("value") }
            if (href.isNotBlank()) links.add(href)
            dataAttrs.forEach { attr ->
                element.attr(attr).takeIf { it.isNotBlank() }?.let { links.add(it) }
            }
        }
        return links.mapNotNull { it.toAbsoluteUrl(pageUrl) }.filter { it.isSupportedPlayerUrl() || it.isDirectMedia() }.distinct()
    }

    private fun collectScriptPlayers(raw: String, baseUrl: String): List<String> {
        val html = raw.decodeUrlLike()
        val links = linkedSetOf<String>()
        Jsoup.parse(html).select("iframe[src], embed[src], source[src], video[src], a[href]").forEach { element ->
            val value = element.attr("src").ifBlank { element.attr("href") }
            value.toAbsoluteUrl(baseUrl)?.let { links.add(it) }
        }
        val keyRegex = Regex("""(?i)(?:file|url|src|source|embed|embed_url|player|iframe)\s*[:=]\s*['\"]([^'\"]+)['\"]""")
        keyRegex.findAll(html).forEach { match -> match.groupValues.getOrNull(1)?.toAbsoluteUrl(baseUrl)?.let { links.add(it) } }
        val urlRegex = Regex("""(?i)https?:\\?/\\?/[^'\"<>\s]+""")
        urlRegex.findAll(html).forEach { match -> match.value.toAbsoluteUrl(baseUrl)?.let { links.add(it) } }
        val b64Regex = Regex("""(?i)(?:atob|base64_decode)\(['\"]([A-Za-z0-9+/=_-]{16,})['\"]\)""")
        b64Regex.findAll(html).forEach { match ->
            decodeBase64(match.groupValues[1])?.let { decoded -> links.addAll(collectScriptPlayers(decoded, baseUrl)) }
        }
        return links.filter { it.isSupportedPlayerUrl() || it.isDirectMedia() }.distinct()
    }

    private fun detectStatus(document: Document): ShowStatus? {
        val text = document.select(".info, .spe, .infodetail, .entry-content").text().lowercase()
        return when {
            "ongoing" in text -> ShowStatus.Ongoing
            "completed" in text || "complete" in text -> ShowStatus.Completed
            else -> null
        }
    }

    private fun looksEpisodePage(url: String, title: String): Boolean =
        url.contains("episode", true) || Regex("""(?i)episode\s*\d+""").containsMatchIn(title)

    private fun ajaxHeaders(referer: String): Map<String, String> = browserHeaders + mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to referer,
    )

    private fun cleanTitle(value: String?): String? {
        val text = value?.cleanText()?.takeIf { it.isNotBlank() } ?: return null
        return text
            .substringBefore(" - MiniOppai", text)
            .substringBefore(" | MiniOppai", text)
            .substringBefore(" – MiniOppai", text)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun Element.imageUrl(): String? = attr("data-src").ifBlank { attr("data-lazy-src") }
        .ifBlank { attr("data-original") }
        .ifBlank { attr("src") }
        .toAbsoluteUrl()

    private fun Element.styleImage(): String? = Regex("""url\(['\"]?([^)'\"]+)""")
        .find(attr("style"))?.groupValues?.getOrNull(1)?.toAbsoluteUrl()

    private fun String.cleanText(): String = Jsoup.parse(this).text()
        .replace("\\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.decodeUrlLike(): String = trim()
        .replace("\\/", "/")
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#039;", "'")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("%3A", ":", ignoreCase = true)
        .replace("%2F", "/", ignoreCase = true)
        .replace("%3F", "?", ignoreCase = true)
        .replace("%26", "&", ignoreCase = true)
        .replace("%3D", "=", ignoreCase = true)

    private fun String?.toAbsoluteUrl(base: String = mainUrl): String? {
        val raw = this?.trim()?.trim('"', '\'', ' ')?.decodeUrlLike()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> mainUrl.trimEnd('/') + raw
            raw.startsWith("#") || raw.startsWith("javascript:", true) -> null
            else -> runCatching { URI(base).resolve(raw).toString() }.getOrNull()
        }
    }

    private fun String.isDirectMedia(): Boolean {
        val value = lowercase().substringBefore("#")
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".webm") || value.contains(".mkv") || value.contains("googlevideo.com/videoplayback")
    }

    private fun String.isSupportedPlayerUrl(): Boolean {
        val value = lowercase()
        if (!value.startsWith("http")) return false
        if (isDirectMedia()) return true
        if (value.contains("minioppai.org/wp-content") && !value.contains("player")) return false
        if (Regex("""\.(?:jpg|jpeg|png|webp|gif|svg|css|woff|woff2|ttf)(?:\?|$)""").containsMatchIn(value)) return false
        return listOf(
            "streampoi", "playmogo", "filemoon", "streamtape", "streamruby", "dood", "d000d",
            "vidhide", "voe", "mixdrop", "mp4upload", "streamwish", "vidguard", "luluvdo",
            "sbembed", "savefiles", "pixeldrain", "gofile", "krakenfiles", "blogger", "blogspot",
            "googlevideo", "ok.ru", "vk.com", "sendvid", "uqload", "embed", "player"
        ).any { value.contains(it) }
    }

    private fun hostLabel(url: String): String = runCatching { URI(url).host ?: name }
        .getOrDefault(name)
        .removePrefix("www.")
        .substringBefore('.')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun decodeBase64(value: String): String? = runCatching {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        base64Decode(padded)
    }.getOrNull()

    private fun normalizeKey(url: String): String = url.trim().trimEnd('/').lowercase()
}
