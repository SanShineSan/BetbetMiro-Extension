package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class WatchWrestling : MainAPI() {
    override var mainUrl = "https://watchwrestling.ae"
    override var name = "WatchWrestling"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/149.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "${mainUrl}/wwe" to "WWE",
        "${mainUrl}/wwe-raw" to "WWE Raw",
        "${mainUrl}/wwe-smackdown" to "WWE Smackdown",
        "${mainUrl}/main-events" to "WWE Main Event",
        "${mainUrl}/wwe-nxt-show" to "WWE NXT",
        "${mainUrl}/wwe-ppv61" to "WWE PPV",
        "${mainUrl}/wwe-totaldvas29" to "WWE Total Divas",
        "${mainUrl}/impact-wrestlingss31" to "IMPACT Wrestling",
        "${mainUrl}/ufc42" to "UFC",
        "${mainUrl}/ufc-ppv" to "UFC PPV",
        "${mainUrl}/njpw52" to "NJPW",
        "${mainUrl}/roh25" to "ROH",
        "${mainUrl}/aew66" to "AEW (All Elite Wrestling)",
        "${mainUrl}/other-wrestling31" to "Other Wrestling",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(pageUrl).document
        val home = document.select("div.loop-content div.item").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Live) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("${mainUrl}/?s=$encoded").document

        return document.select("div.loop-content div.item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Live) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("img.size-full")?.attr("src"))
        val description = document.selectFirst("div.entry-content p:nth-child(1)")?.text()?.trim()
        val tags = document.select("div#extras a").map { it.text() }
        val recommendations = document.select("div.item").mapNotNull { it.toRecommendationResult() }

        return newMovieLoadResponse(title, url, TvType.Live, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Live) { this.posterUrl = posterUrl }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        val document = app.get(data).document
        val emitted = AtomicInteger(0)
        val serverLinks = extractServerLinks(document)

        if (serverLinks.isEmpty()) return@coroutineScope false

        val jobs = serverLinks.map { server ->
            launch {
                val resolved = resolveServerLink(server, data, subtitleCallback, callback)
                if (resolved > 0) emitted.addAndGet(resolved)
            }
        }

        jobs.joinAll()
        emitted.get() > 0
    }

    private data class ServerLink(
        val name: String,
        val url: String,
    )

    private fun extractServerLinks(document: org.jsoup.nodes.Document): List<ServerLink> {
        val hiddenHtml = buildString {
            document.select("script").forEach { script ->
                Regex("<textarea[^>]*>([\\s\\S]*?)</textarea>", RegexOption.IGNORE_CASE)
                    .findAll(script.data())
                    .forEach { appendLine(cleanEmbeddedHtml(it.groupValues[1])) }
            }
        }

        val innerDoc = if (hiddenHtml.isNotBlank()) org.jsoup.Jsoup.parse(hiddenHtml) else document
        val links = mutableListOf<ServerLink>()

        innerDoc.select("div.episodeRepeater").forEach { block ->
            val hostTitle = block.selectFirst("h1")?.text()?.cleanServerName() ?: "Server"
            block.select("a[href]").forEach { linkElement ->
                val videoUrl = normalizeUrl(linkElement.attr("href"))
                val partLabel = linkElement.text().trim().ifBlank { "Part" }
                if (videoUrl.isPlayableCandidate()) {
                    links.add(ServerLink("$hostTitle-$partLabel", videoUrl))
                }
            }
        }

        if (links.isEmpty()) {
            innerDoc.select("a.responsive_custom_btn[href], a[href*='snaptik.ae'], iframe[src], source[src], video[src]").forEach { element ->
                val videoUrl = normalizeUrl(element.attr("href").ifBlank { element.attr("src") })
                if (videoUrl.isPlayableCandidate()) {
                    links.add(ServerLink(element.text().trim().ifBlank { "Server" }, videoUrl))
                }
            }
        }

        return links.distinctBy { it.url }
    }

    private suspend fun resolveServerLink(
        server: ServerLink,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val url = normalizeUrl(server.url)
        return when {
            url.contains("snaptik.ae/read.php", ignoreCase = true) ->
                resolveSnaptik(server.name, url, pageUrl, subtitleCallback, callback)

            url.contains("fastvid.xyz", ignoreCase = true) ->
                resolveIframePage(server.name, url, pageUrl, subtitleCallback, callback)

            url.contains("linux-developers.top/vgroupWRSc/vsecureWRSc", ignoreCase = true) ->
                resolveLinuxSecure(server.name, url, subtitleCallback, callback)

            url.contains(".m3u8", ignoreCase = true) ->
                emitM3u8(server.name, url, pageUrl, callback)

            else -> loadCustomExtractor(server.name, url, pageUrl, subtitleCallback, callback)
        }
    }

    private suspend fun resolveSnaptik(
        name: String,
        url: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val id = url.queryParam("id")
        val host = url.queryParam("host")

        if (!id.isNullOrBlank() && !host.isNullOrBlank() && host.startsWith("tuberep_", ignoreCase = true)) {
            val mirror = host.substringAfterLast("_").filter { it.isDigit() }
            if (mirror.isNotBlank()) {
                val secureUrl = "https://451nj1za7g9v2kexgxatdrh.linux-developers.top/vgroupWRSc/vsecureWRSc/?line=$id$mirror&waiting=C&background=grey"
                val resolved = resolveLinuxSecure(name, secureUrl, subtitleCallback, callback)
                if (resolved > 0) return resolved
            }
        }

        val document = runCatching { app.get(url, referer = pageUrl).document }.getOrNull() ?: return 0
        val iframe = document.selectFirst("iframe[src]")?.attr("src")?.let { normalizeUrl(it) } ?: return 0
        return resolveServerLink(ServerLink(name, iframe), url, subtitleCallback, callback)
    }

    private suspend fun resolveIframePage(
        name: String,
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val direct = loadCustomExtractor(name, url, referer, subtitleCallback, callback)
        if (direct > 0) return direct

        val document = runCatching { app.get(url, referer = referer).document }.getOrNull() ?: return 0
        val iframe = document.selectFirst("iframe[src]")?.attr("src")?.let { normalizeUrl(it) } ?: return 0
        return resolveServerLink(ServerLink(name, iframe), url, subtitleCallback, callback)
    }

    private suspend fun resolveLinuxSecure(
        name: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val document = runCatching { app.get(url, referer = url).document }.getOrNull() ?: return 0
        val html = document.html()
        val m3u8 = Regex("""src:\s*['\"]([^'\"]+\.m3u8[^'\"]*)""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""<source[^>]+src=['\"]([^'\"]+\.m3u8[^'\"]*)""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

        return if (!m3u8.isNullOrBlank()) {
            emitM3u8(name, normalizeUrl(m3u8), url, callback)
        } else {
            loadCustomExtractor(name, url, url, subtitleCallback, callback)
        }
    }

    private suspend fun emitM3u8(
        name: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to userAgent,
            "Accept" to "*/*",
        )
        var count = 0
        M3u8Helper.generateM3u8(name, url, referer = referer, headers = headers).forEach { link ->
            count++
            callback.invoke(link)
        }
        return count
    }

    private suspend fun loadCustomExtractor(
        name: String? = null,
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        var count = 0
        loadExtractor(url, referer, subtitleCallback) { link ->
            if (link.url.isNotBlank() && (link.url.startsWith("http://") || link.url.startsWith("https://"))) {
                count++
                callback.invoke(link)
            }
        }
        return count
    }

    private fun String.cleanServerName(): String = this
        .replace("Watch ", "", ignoreCase = true)
        .replace("HD", "", ignoreCase = true)
        .replace("720P", "", ignoreCase = true)
        .trim()
        .ifBlank { "Server" }

    private fun cleanEmbeddedHtml(html: String): String = html
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("\\/", "/")
        .replace("&amp;", "&")

    private fun normalizeUrl(url: String): String {
        val cleaned = url.trim().removeSurrounding("\"").removeSurrounding("'").replace("&amp;", "&")
        return when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> fixUrl(cleaned)
            else -> cleaned
        }
    }

    private fun String.isPlayableCandidate(): Boolean {
        if (isBlank()) return false
        if (equals("about:blank", ignoreCase = true)) return false
        if (contains("javascript:", ignoreCase = true)) return false
        if (contains("google", ignoreCase = true) || contains("doubleclick", ignoreCase = true)) return false
        return startsWith("http://") || startsWith("https://") || startsWith("//")
    }

    private fun String.queryParam(name: String): String? = runCatching {
        substringAfter("?", "")
            .split("&")
            .firstOrNull { it.substringBefore("=") == name }
            ?.substringAfter("=", "")
            ?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()
}
