package com.sad25kag.avtub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class AVTub : MainAPI() {
    override var mainUrl = "https://avpinay.com"
    override var name = "AVTub"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"

    private val mobileUa =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

    private val siteHeaders = mapOf(
        "User-Agent" to desktopUa,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/vivamax/2026/?filter=random" to "Vivamax 2026",
        "$mainUrl/category/vivamax/2025/?filter=random" to "Vivamax 2025",
        "$mainUrl/category/vivamax/2024/?filter=random" to "Vivamax 2024",
        "$mainUrl/category/vivamax/2023/?filter=random" to "Vivamax 2023",
        "$mainUrl/category/vivamax/2022/?filter=random" to "Vivamax 2022",
        "$mainUrl/category/vivamax/2021/?filter=random" to "Vivamax 2021"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(
            request.data.toPagedUrl(page),
            headers = siteHeaders
        ).document

        val results = document.parseCards()

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }

        val results = app.get(url, headers = siteHeaders)
            .document
            .parseCards()

        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return app.get("$mainUrl/?s=$encoded", headers = siteHeaders)
            .document
            .parseCards()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = siteHeaders).document

        val title = document.firstText(
            "h1",
            "meta[property=og:title]::content",
            "title"
        )?.cleanTitle() ?: return null

        val poster = document.firstAttr(
            "meta[property=og:image]", "content",
            "video", "poster",
            ".post-thumbnail img", "src",
            "img.wp-post-image", "src"
        )?.let { fixUrlNull(it) }

        val plot = document.firstText(
            "meta[property=og:description]::content",
            "meta[name=description]::content",
            ".entry-content p",
            ".post-content p"
        )

        val tags = document.select("a[rel=tag], .tags a, a[href*=/tag/], a[href*=/category/]")
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            .filterNot { it.equals("vivamax", ignoreCase = true) }
            .distinct()

        val actors = document.select("a[href*=/model/], a[href*=/pornstar/], a[href*=/actor/], .models a, .actors a")
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            .distinct()
            .map { Actor(it) }

        val playbackData = document.extractPlaybackLinks()
            .joinToString("|||")
            .ifBlank { url }

        val recommendations = document.parseCards()
            .filterNot { it.url == url }

        return newMovieLoadResponse(title, url, TvType.NSFW, playbackData) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawLinks = if (data.startsWith(mainUrl) && !data.isDirectVideo()) {
            app.get(data, headers = siteHeaders)
                .document
                .extractPlaybackLinks()
        } else {
            data.split("|||")
        }

        var emitted = false

        rawLinks.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinct()
            .forEach { link ->
                emitted = resolveVideoLink(
                    link = link,
                    pageReferer = mainUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                ) || emitted
            }

        return emitted
    }

    private suspend fun resolveVideoLink(
        link: String,
        pageReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = fixUrlNull(link) ?: return false
        val host = runCatching { URL(fixed).host.lowercase(Locale.ROOT) }
            .getOrDefault("")

        return when {
            fixed.isDirectVideo() -> {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixed,
                        type = INFER_TYPE
                    ) {
                        this.referer = pageReferer
                        this.quality = Qualities.Unknown.value
                    }
                )
                true
            }

            host.contains("minochinos.com") -> {
                resolveMinochinos(
                    embedUrl = fixed,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            host.contains("ystream.id") -> {
                resolveYstream(
                    embedUrl = fixed,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            else -> {
                loadExtractor(fixed, pageReferer, subtitleCallback, callback)
                true
            }
        }
    }

    private suspend fun resolveYstream(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val code = Regex("""/e/([^/?#]+)/?""")
            .find(embedUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        val detailsUrl = "https://ystream.id/api/videos/$code/embed/details"
        val details = runCatching {
            app.get(
                detailsUrl,
                headers = mapOf(
                    "User-Agent" to mobileUa,
                    "Accept" to "*/*",
                    "Referer" to embedUrl,
                    "X-Embed-Origin" to "avpinay.com",
                    "X-Embed-Parent" to embedUrl,
                    "X-Embed-Referer" to "$mainUrl/"
                )
            ).text
        }.getOrNull()

        val nested = details?.extractJsonString("embed_frame_url")
        if (!nested.isNullOrBlank()) {
            // HAR shows ystream.id delegates playback to the returned nzn3.org frame.
            // If a CloudStream extractor supports that host, this keeps the proven handoff intact.
            loadExtractor(nested, embedUrl, subtitleCallback, callback)
            return true
        }

        loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
        return true
    }

    private suspend fun resolveMinochinos(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to mobileUa,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to "$mainUrl/"
                )
            ).text
        }.getOrNull() ?: return false

        val unpacked = html.unpackPackerScripts().joinToString("\n")
        val combined = "$html\n$unpacked"
        val base = embedUrl.origin()

        val hlsLinks = listOf("hls4", "hls2", "hls3")
            .mapNotNull { key ->
                Regex("""["']$key["']\s*:\s*["']([^"']+)""")
                    .find(combined)
                    ?.groupValues
                    ?.getOrNull(1)
            } + Regex("""(?i)(https?:\\?/\\?/[^"'<>\\]+\.m3u8(?:\?[^"'<>\\]*)?)""")
            .findAll(combined)
            .map { it.groupValues[1] }
            .toList()

        val directLinks = hlsLinks
            .map { it.replace("\\/", "/") }
            .map { it.absoluteUrl(base) }
            .filter { it.contains(".m3u8", ignoreCase = true) }
            .distinct()

        if (directLinks.isEmpty()) {
            loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
            return true
        }

        directLinks.forEach { videoUrl ->
            callback.invoke(
                newExtractorLink(
                    source = "Minochinos",
                    name = "Minochinos HLS",
                    url = videoUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return true
    }

    private fun Document.parseCards(): List<SearchResponse> {
        val selectors = listOf(
            "article.thumb-block.video-preview-item",
            "article.thumb-block",
            "article.video-preview-item",
            ".video-preview-item",
            ".thumb-block"
        )

        return selectors.asSequence()
            .map { selector -> select(selector).mapNotNull { it.toSearchResult() } }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[title][href], a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (href == "$mainUrl/" || href.contains("/wp-content/")) return null

        val title = listOfNotNull(
            anchor.attr("title"),
            selectFirst(".title, h2, h3")?.text(),
            selectFirst("img")?.attr("alt"),
            anchor.text()
        ).firstOrNull { it.trim().isNotBlank() }
            ?.cleanTitle()
            ?: return null

        val poster = firstAttr(
            "img[data-src]", "data-src",
            "img[data-original]", "data-original",
            "img[data-lazy-src]", "data-lazy-src",
            "img", "src"
        )?.let { fixUrlNull(it) }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun Document.extractPlaybackLinks(): List<String> {
        val tagLinks = select("iframe[src], embed[src], video source[src], video[src], a[href*=.m3u8], a[href*=.mp4]")
            .mapNotNull { element ->
                element.attr("src")
                    .ifBlank { element.attr("href") }
                    .trim()
                    .takeIf(String::isNotBlank)
            }

        val html = outerHtml()
        val scriptLinks = listOf(
            Regex("""(?i)(?:file|src|source|video_url|videoUrl)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)(?:\?[^"']*)?)["']"""),
            Regex("""(?i)(https?:\\?/\\?/[^"'<>\\]+\.(?:m3u8|mp4)(?:\?[^"'<>\\]*)?)"""),
            Regex("""(?i)<iframe[^>]+src=["']([^"']+)["']""")
        ).flatMap { regex ->
            regex.findAll(html).map { it.groupValues[1].replace("\\/", "/") }.toList()
        }

        return (tagLinks + scriptLinks)
            .mapNotNull { fixUrlNull(it) }
            .filterNot { it.contains("/ads", ignoreCase = true) }
            .distinct()
    }

    private fun Document.firstText(vararg selectors: String): String? {
        selectors.forEach { selector ->
            val value = if (selector.endsWith("::content")) {
                selectFirst(selector.removeSuffix("::content"))?.attr("content")
            } else {
                selectFirst(selector)?.text()
            }?.trim()

            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun Element.firstAttr(vararg selectorAttr: String): String? {
        var index = 0
        while (index + 1 < selectorAttr.size) {
            val selector = selectorAttr[index]
            val attr = selectorAttr[index + 1]
            val value = selectFirst(selector)?.attr(attr)?.trim()
            if (!value.isNullOrBlank()) return value
            index += 2
        }
        return null
    }

    private fun String.toPagedUrl(page: Int): String {
        if (page <= 1) return this
        val base = substringBefore("?").trimEnd('/')
        val query = substringAfter("?", "")
        return buildString {
            append(base)
            append("/page/")
            append(page)
            append("/")
            if (query.isNotBlank()) {
                append("?")
                append(query)
            }
        }
    }

    private fun String.cleanTitle(): String = replace(Regex("""\s+"""), " ")
        .replace(" - AVPinay", "", ignoreCase = true)
        .replace("AVPinay", "", ignoreCase = true)
        .trim(' ', '-', '|')

    private fun String.isDirectVideo(): Boolean =
        contains(".m3u8", ignoreCase = true) || contains(".mp4", ignoreCase = true)

    private fun String.origin(): String = runCatching {
        val url = URL(this)
        "${url.protocol}://${url.host}"
    }.getOrDefault(mainUrl)

    private fun String.absoluteUrl(base: String): String = when {
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true) -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> base.trimEnd('/') + this
        else -> base.trimEnd('/') + "/" + this
    }

    private fun String.extractJsonString(key: String): String? =
        Regex("""["']${Regex.escape(key)}["']\s*:\s*["']([^"']+)""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")

    private data class JsReadResult(val value: String, val nextIndex: Int)

    private fun String.unpackPackerScripts(): List<String> {
        val outputs = mutableListOf<String>()
        val marker = "eval(function(p,a,c,k,e,d)"
        var searchFrom = 0

        while (true) {
            val start = indexOf(marker, searchFrom)
            if (start < 0) break

            val argsStart = indexOf("}('", start)
            if (argsStart < 0) break

            var index = argsStart + 3
            val payload = readSingleQuotedJsString(index) ?: break
            index = payload.nextIndex
            index = skipSeparators(index)

            val radixMatch = Regex("""\d+""").find(this, index) ?: break
            val radix = radixMatch.value.toIntOrNull() ?: break
            index = radixMatch.range.last + 1
            index = skipSeparators(index)

            val countMatch = Regex("""\d+""").find(this, index) ?: break
            val count = countMatch.value.toIntOrNull() ?: break
            index = countMatch.range.last + 1

            val keyQuote = indexOf("'", index)
            if (keyQuote < 0) break

            val keys = readSingleQuotedJsString(keyQuote + 1) ?: break
            val dictionary = keys.value.split("|")

            outputs.add(unpackPacker(payload.value, radix, count, dictionary))
            searchFrom = keys.nextIndex
        }

        return outputs
    }

    private fun String.readSingleQuotedJsString(startIndex: Int): JsReadResult? {
        val builder = StringBuilder()
        var index = startIndex

        while (index < length) {
            val char = this[index]
            when {
                char == '\\' && index + 1 < length -> {
                    val next = this[index + 1]
                    builder.append(
                        when (next) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> next
                        }
                    )
                    index += 2
                }

                char == '\'' -> return JsReadResult(builder.toString(), index + 1)

                else -> {
                    builder.append(char)
                    index++
                }
            }
        }

        return null
    }

    private fun String.skipSeparators(startIndex: Int): Int {
        var index = startIndex
        while (index < length && (this[index] == ',' || this[index].isWhitespace())) {
            index++
        }
        return index
    }

    private fun unpackPacker(
        payload: String,
        radix: Int,
        count: Int,
        dictionary: List<String>
    ): String {
        var result = payload

        for (index in count - 1 downTo 0) {
            val replacement = dictionary.getOrNull(index)
            if (!replacement.isNullOrEmpty()) {
                val token = index.toString(radix)
                result = result.replace(
                    Regex("""\b${Regex.escape(token)}\b"""),
                    replacement
                )
            }
        }

        return result
    }
}
