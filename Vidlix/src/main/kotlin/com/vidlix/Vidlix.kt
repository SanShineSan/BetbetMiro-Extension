package com.vidlix

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Vidlix : MainAPI() {
    override var mainUrl = "https://vidlix.net"
    override var name = "Vidlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.AsianDrama,
        TvType.NSFW
    )

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "home:On-going series" to "On-going series",
        "home:Best Action" to "Best Action",
        "home:Best Drama" to "Best Drama",
        "home:Best Sci-Fi" to "Best Sci-Fi",
        "home:Best Comedy" to "Best Comedy",
        "home:Best Fantasy" to "Best Fantasy",
        "$mainUrl/category/film" to "Film",
        "$mainUrl/category/series" to "Series",
        "$mainUrl/tag/action" to "Action",
        "$mainUrl/tag/drama" to "Drama",
        "$mainUrl/tag/sci-fi" to "Sci-Fi",
        "$mainUrl/tag/comedy" to "Comedy",
        "$mainUrl/tag/fantasy" to "Fantasy",
        "$mainUrl/tag/anime" to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = if (request.data.startsWith("home:")) {
            val document = app.get(mainUrl, headers = siteHeaders).document
            val section = request.data.removePrefix("home:")
            parseHomeSection(document, section)
        } else {
            val url = pagedUrl(request.data, page)
            val document = app.get(url, headers = siteHeaders, referer = "$mainUrl/").document
            parseCards(document, defaultTypeFor(request.name))
        }

        return newHomePageResponse(
            HomePageList(request.name, list, isHorizontalImages = true),
            hasNext = list.isNotEmpty() && !request.data.startsWith("home:")
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val searchUrls = listOf(
            "$mainUrl/search/$encoded",
            "$mainUrl/?s=$encoded"
        )

        for (url in searchUrls) {
            val results = runCatching {
                val document = app.get(url, headers = siteHeaders, referer = "$mainUrl/").document
                parseCards(document, TvType.Movie)
            }.getOrDefault(emptyList())
            if (results.isNotEmpty()) {
                return newSearchResponseList(results, hasNext = false)
            }
        }
        return newSearchResponseList(emptyList(), hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return runCatching {
            val document = app.get("$mainUrl/search/$encoded", headers = siteHeaders, referer = "$mainUrl/").document
            parseCards(document, TvType.Movie)
        }.getOrDefault(emptyList())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = siteHeaders, referer = "$mainUrl/").document
        val title = document.selectFirst("h1.title, h1[itemprop=headline], h1")?.text()?.cleanTitle()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.cleanTitle()
            ?: throw ErrorLoadingException("Vidlix: title not found")
        val poster = absoluteUrl(
            document.selectFirst("img.cover")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".cover img, img[itemprop=image], img")?.attr("src")
        )
        val description = document.selectFirst(".deskripsi, [itemprop=description], meta[name=description]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
            ?.trim()
        val tags = document.select("span.tags a, a[rel=tag]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.startsWith("Rating ", true) && !it.matches(Regex("\\d{3,4}X", RegexOption.IGNORE_CASE)) }
            .distinct()
        val year = document.selectFirst(".date, [itemprop=datePublished]")?.text()?.extractYear()
            ?: document.text().extractYear()
        val duration = detailValue(document, "Durasi")?.durationToMinutes()
        val rating = detailValue(document, "Rating")?.substringBefore(" ")?.trim()?.takeIf { it.isNotBlank() }
        val jsProxyUrl = document.selectFirst("script[src*='js_proxy.php']")?.attr("src")?.let { absoluteUrl(it) }
        val proxyDocument = jsProxyUrl?.let { fetchProxyDocument(it, url) }
        val videoCandidates = proxyDocument?.let { extractProxyCandidates(it) }.orEmpty()
        val isSeries = isSeriesPage(document) || videoCandidates.size > 1

        return if (isSeries) {
            val episodes = videoCandidates.mapIndexed { index, candidate ->
                newEpisode(candidate.url) {
                    this.name = candidate.name ?: "Episode ${index + 1}"
                    this.episode = index + 1
                    this.posterUrl = candidate.posterUrl ?: poster
                }
            }.ifEmpty {
                listOf(newEpisode(jsProxyUrl ?: url) {
                    this.name = title
                    this.posterUrl = poster
                })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                duration?.let { this.duration = it }
                rating?.let { addScore(it) }
            }
        } else {
            val data = videoCandidates.joinToString("\n") { it.url }.ifBlank { jsProxyUrl ?: url }
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                duration?.let { this.duration = it }
                rating?.let { addScore(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val emitted = linkedSetOf<String>()
        val visited = linkedSetOf<String>()
        var found = false

        val safeCallback: (ExtractorLink) -> Unit = { link ->
            if (link.url.isNotBlank() && emitted.add(link.url)) {
                found = true
                callback.invoke(link)
            }
        }

        data.split("\n", ",")
            .map { it.trim().trim('[', ']') }
            .filter { it.isNotBlank() }
            .forEach { candidate ->
                if (resolveCandidate(candidate, "$mainUrl/", visited, subtitleCallback, safeCallback)) {
                    found = true
                }
            }

        return found
    }

    private suspend fun resolveCandidate(
        rawUrl: String,
        referer: String,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedUrl = absoluteUrl(rawUrl) ?: return false
        if (!visited.add(fixedUrl.substringBefore("#"))) return false

        return when {
            fixedUrl.contains("/post/") && fixedUrl.contains("vidlix.net") -> {
                val document = app.get(fixedUrl, headers = siteHeaders, referer = referer).document
                val proxyUrl = document.selectFirst("script[src*='js_proxy.php']")?.attr("src")?.let { absoluteUrl(it) }
                    ?: return false
                resolveCandidate(proxyUrl, fixedUrl, visited, subtitleCallback, callback)
            }
            fixedUrl.contains("js_proxy.php") -> {
                val proxyDocument = fetchProxyDocument(fixedUrl, referer) ?: return false
                var found = false
                extractProxyCandidates(proxyDocument).forEach { candidate ->
                    if (resolveCandidate(candidate.url, fixedUrl, visited, subtitleCallback, callback)) found = true
                }
                found
            }
            fixedUrl.contains("abyssplayer.com", true) || fixedUrl.contains("abyss.to", true) -> {
                resolveAbyssPlayer(fixedUrl, referer, callback) || runGenericExtractor(fixedUrl, referer, subtitleCallback, callback)
            }
            isDirectMedia(fixedUrl) -> emitDirect(fixedUrl, referer, callback)
            else -> runGenericExtractor(fixedUrl, referer, subtitleCallback, callback)
        }
    }

    private suspend fun runGenericExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        runCatching {
            loadExtractor(url, referer, subtitleCallback) { link ->
                found = true
                callback.invoke(link)
            }
        }
        return found
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            url.contains(".m3u8", true) -> {
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = url,
                    referer = referer,
                    headers = playbackHeaders("https://abyssplayer.com/")
                )
                links.forEach(callback)
                links.isNotEmpty()
            }
            else -> {
                callback.invoke(
                    newExtractorLink(name, "$name Direct", url, type = ExtractorLinkType.VIDEO) {
                        this.referer = "https://abyssplayer.com/"
                        this.quality = getQualityFromName(url).takeIf { it != Qualities.Unknown.value } ?: Qualities.Unknown.value
                        this.headers = playbackHeaders("https://abyssplayer.com/")
                    }
                )
                true
            }
        }
    }

    private suspend fun resolveAbyssPlayer(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = absoluteUrl(url) ?: return false
        val html = runCatching {
            app.get(pageUrl, headers = siteHeaders, referer = referer).text
        }.getOrNull() ?: return false
        val payload = parseAbyssPayload(html) ?: return false
        val mediaJson = decryptAbyssMedia(payload) ?: return false
        val root = runCatching { JSONObject(mediaJson) }.getOrNull() ?: return false
        val mp4 = root.optJSONObject("mp4") ?: root
        var found = false

        val labels = mutableMapOf<Int, String>()
        mp4.optJSONArray("sources")?.let { sources ->
            for (index in 0 until sources.length()) {
                val source = sources.optJSONObject(index) ?: continue
                if (!source.optBoolean("status", true)) continue
                val resId = source.optInt("res_id", -1)
                val label = source.optString("label").takeIf { it.isNotBlank() }
                if (resId > 0 && label != null) labels[resId] = label
            }
        }

        listOf("fristDatas", "firstDatas", "data", "files").forEach { arrayName ->
            mp4.optJSONArray(arrayName)?.let { items ->
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val videoUrl = listOf("url", "file", "src", "source").firstNotNullOfOrNull { key ->
                        item.optString(key).trim().takeIf { it.isPlayableCandidate() }
                    } ?: continue
                    val label = labels[item.optInt("res_id", -1)]
                        ?: item.optString("label").takeIf { it.isNotBlank() }
                        ?: item.optString("codec").takeIf { it.isNotBlank() }
                        ?: "AbyssPlayer"
                    if (emitAbyssDirect(videoUrl, label, callback)) found = true
                }
            }
        }

        if (found) return true

        mp4.optJSONArray("sources")?.let { sources ->
            for (index in 0 until sources.length()) {
                val source = sources.optJSONObject(index) ?: continue
                if (!source.optBoolean("status", true)) continue
                val direct = listOf("url", "file", "src", "source").firstNotNullOfOrNull { key ->
                    source.optString(key).trim().takeIf { it.isPlayableCandidate() }
                }
                val host = source.optString("url").trimEnd('/')
                val path = source.optString("path").trimStart('/')
                val resolved = direct ?: if (host.isNotBlank() && path.isNotBlank()) "$host/$path" else null
                val label = source.optString("label", "AbyssPlayer")
                if (resolved != null && emitAbyssDirect(resolved, label, callback)) found = true
            }
        }

        return found
    }

    private suspend fun emitAbyssDirect(
        videoUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = absoluteUrl(videoUrl) ?: return false
        callback.invoke(
            newExtractorLink(
                source = "AbyssPlayer",
                name = "AbyssPlayer $label",
                url = fixed,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://abyssplayer.com/"
                this.quality = getQualityFromName(label).takeIf { it != Qualities.Unknown.value }
                    ?: getQualityFromName(fixed)
                this.headers = playbackHeaders("https://abyssplayer.com/")
            }
        )
        return true
    }

    private fun parseAbyssPayload(html: String): AbyssPayload? {
        val encoded = Regex("""(?:const|let|var)?\s*datas\s*=\s*["']([A-Za-z0-9+/=_-]+)["']""")
            .find(html)?.groupValues?.getOrNull(1) ?: return null
        val json = decodeBase64String(encoded) ?: return null
        val node = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val slug = node.optString("slug")
        val md5Id = node.optString("md5_id")
        val userId = node.optString("user_id")
        val media = node.optString("media")
        if (slug.isBlank() || md5Id.isBlank() || userId.isBlank() || media.isBlank()) return null
        return AbyssPayload(slug, md5Id, userId, media)
    }

    private fun decodeBase64String(value: String): String? {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        return runCatching { String(Base64.getDecoder().decode(normalized), Charsets.ISO_8859_1) }
            .getOrElse { runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.ISO_8859_1) }.getOrNull() }
    }

    private fun decryptAbyssMedia(payload: AbyssPayload): String? {
        return runCatching {
            val key = md5Hex("${payload.userId}:${payload.slug}:${payload.md5Id}").toByteArray(Charsets.UTF_8)
            val counter = key.copyOfRange(0, 16)
            val encrypted = ByteArray(payload.media.length) { index -> payload.media[index].code.toByte() }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun parseHomeSection(document: Document, sectionTitle: String): List<SearchResponse> {
        val header = document.select("h2.judulpop, h2.judulterbaru").firstOrNull { it.text().trim().equals(sectionTitle, true) }
            ?: return parseCards(document, defaultTypeFor(sectionTitle))
        val html = buildString {
            var sibling = header.nextSibling()
            while (sibling != null) {
                if (sibling is Element && sibling.tagName() == "h2" && sibling.classNames().any { it == "judulpop" || it == "judulterbaru" }) break
                append(sibling.toString())
                sibling = sibling.nextSibling()
            }
        }
        return parseCards(Jsoup.parse(html, mainUrl), defaultTypeFor(sectionTitle))
    }

    private fun parseCards(document: Document, defaultType: TvType): List<SearchResponse> {
        val seen = linkedSetOf<String>()
        return document.select("a[href*=/post/], article:has(a[href*=/post/]), .post:has(a[href*=/post/]), .item:has(a[href*=/post/]), .video:has(a[href*=/post/]), .masonry:has(a[href*=/post/])")
            .mapNotNull { it.toSearchResult(defaultType) }
            .filter { seen.add(it.url) }
    }

    private fun Element.toSearchResult(defaultType: TvType): SearchResponse? {
        val anchor = when {
            tagName() == "a" && attr("href").contains("/post/") -> this
            else -> selectFirst("a[href*=/post/]")
        } ?: return null
        val href = absoluteUrl(anchor.attr("href")) ?: return null
        if (!href.contains("/post/")) return null
        val text = text().trim()
        val title = selectFirst("h2.title, h3.title, .title, .judul, .name")?.text()?.cleanTitle()
            ?: anchor.attr("title").cleanTitle().ifBlank { null }
            ?: selectFirst("img")?.attr("alt")?.cleanTitle()?.ifBlank { null }
            ?: anchor.text().cleanCardText().ifBlank { null }
            ?: text.cleanCardText().ifBlank { null }
            ?: return null
        val poster = extractImageUrl(this) ?: extractImageUrl(anchor)
        val type = when {
            text.contains("Series", true) || title.contains("Season", true) || defaultType == TvType.TvSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    private suspend fun fetchProxyDocument(proxyUrl: String, referer: String): Document? {
        val js = runCatching {
            app.get(
                proxyUrl,
                headers = siteHeaders + mapOf(
                    "Accept" to "application/javascript,text/javascript,*/*;q=0.8",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = referer
            ).text
        }.getOrNull() ?: return null
        val html = unwrapDocumentWrite(js)
        return Jsoup.parse("$html\n<script>$js</script>", proxyUrl)
    }

    private fun extractProxyCandidates(document: Document): List<VideoCandidate> {
        val candidates = mutableListOf<VideoCandidate>()
        val sourceHtml = document.html().htmlUnescape().unescapeJs()

        document.select("div.video-con[video], [video]").forEachIndexed { index, element ->
            val url = element.attr("video").trim().htmlUnescape()
            val fixedUrl = normalizeMediaUrl(url) ?: return@forEachIndexed
            candidates.add(
                VideoCandidate(
                    url = fixedUrl,
                    name = element.selectFirst(".v-titles .title, .title, [title]")?.text()?.trim().takeUnless { it.isNullOrBlank() }
                        ?: element.attr("title").takeIf { it.isNotBlank() }
                        ?: "Episode ${index + 1}",
                    posterUrl = extractImageUrl(element)
                )
            )
        }

        val mediaAttrs = listOf(
            "src", "href", "video", "data-src", "data-video", "data-url",
            "data-embed", "data-iframe", "data-file", "data-link", "data-player",
            "file", "url"
        )
        document.select("iframe, video, source, a, [src], [href], [video], [data-src], [data-video], [data-url], [data-embed], [data-iframe], [data-file], [data-link], [data-player]").forEach { element ->
            mediaAttrs.forEach { attr ->
                val url = element.attr(attr).trim().htmlUnescape()
                if (url.isPlayableCandidate()) {
                    normalizeMediaUrl(url)?.let { candidates.add(VideoCandidate(it, element.attr("title").ifBlank { null }, extractImageUrl(element))) }
                }
            }
        }

        listOf(
            Regex("""https?://[^'"<>\s\\]+?(?:abyssplayer\.com|abyss\.to)[^'"<>\s\\]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^'"<>\s\\]+?\.(?:m3u8|mp4|webm|mkv)(?:\?[^'"<>\s\\]*)?""", RegexOption.IGNORE_CASE),
            Regex("""(?:video|file|source|src|url)\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        ).forEach { regex ->
            regex.findAll(sourceHtml).forEach { match ->
                val raw = match.groupValues.getOrNull(1).takeIf { !it.isNullOrBlank() } ?: match.value
                if (raw.isPlayableCandidate()) {
                    normalizeMediaUrl(raw)?.let { candidates.add(VideoCandidate(it, null, null)) }
                }
            }
        }

        return candidates.distinctBy { it.url.substringBefore("#") }
    }

    private fun extractImageUrl(element: Element): String? {
        element.selectFirst("img, [style*=background]")?.let { image ->
            listOf("data-src", "data-original", "data-lazy-src", "data-bg", "data-background", "src", "poster").forEach { attr ->
                val value = image.attr(attr).trim().takeIf { it.isNotBlank() && !it.startsWith("data:", true) }
                if (value != null) return absoluteUrl(value)
            }
            Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
                .find(image.attr("style"))?.groupValues?.getOrNull(2)
                ?.takeIf { it.isNotBlank() }
                ?.let { return absoluteUrl(it) }
        }
        Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(element.attr("style"))?.groupValues?.getOrNull(2)
            ?.takeIf { it.isNotBlank() }
            ?.let { return absoluteUrl(it) }
        return null
    }

    private fun normalizeMediaUrl(raw: String): String? {
        val cleaned = raw.trim().htmlUnescape().unescapeJs()
            .replace("\\/", "/")
            .trim(' ', '"', '\'', '`')
        return absoluteUrl(cleaned)
    }

    private fun String.isPlayableCandidate(): Boolean {
        val value = trim().htmlUnescape().unescapeJs().replace("\\/", "/")
        return value.contains("abyssplayer", true) ||
            value.contains("abyss.to", true) ||
            isDirectMedia(value)
    }

    private fun detailValue(document: Document, label: String): String? {
        return document.select("tr").firstOrNull { row ->
            row.select("th,td").firstOrNull()?.text()?.trim()?.equals(label, true) == true
        }?.select("td")?.lastOrNull()?.text()?.trim()
    }

    private fun isSeriesPage(document: Document): Boolean {
        val bodyClass = document.body()?.className().orEmpty()
        return bodyClass.contains("category-series", true) ||
            document.select(".breadcrumb-list a[href*='/category/series'], a[href*='/category/series']").isNotEmpty() ||
            detailValue(document, "Total")?.contains("Episode", true) == true
    }

    private fun defaultTypeFor(name: String): TvType {
        return if (name.contains("series", true) || name.contains("ongoing", true)) TvType.TvSeries else TvType.Movie
    }

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val separator = if (base.contains("?")) "&" else "?"
        return base.trimEnd('/') + "${separator}page=$page"
    }

    private fun unwrapDocumentWrite(script: String): String {
        val chunks = mutableListOf<String>()
        Regex("""document\.(?:write|writeln)\s*\(""", RegexOption.IGNORE_CASE)
            .findAll(script)
            .forEach { match ->
                val expression = readJsCallArgument(script, match.range.last + 1)
                val decoded = decodeJsExpression(expression)
                if (decoded.isNotBlank()) chunks.add(decoded)
            }
        if (chunks.isNotEmpty()) return chunks.joinToString("")

        Regex("""atob\s*\(\s*['"]([A-Za-z0-9+/=_-]+)['"]\s*\)""", RegexOption.IGNORE_CASE)
            .find(script)?.groupValues?.getOrNull(1)
            ?.let { decodeBase64String(it) }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return script.unescapeJs()
    }

    private fun readJsCallArgument(source: String, start: Int): String {
        var quote: Char? = null
        var escaped = false
        var depth = 0
        for (index in start until source.length) {
            val char = source[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
                continue
            }
            when (char) {
                '\'', '"', '`' -> quote = char
                '(' -> depth++
                ')' -> {
                    if (depth == 0) return source.substring(start, index)
                    depth--
                }
            }
        }
        return source.substring(start)
    }

    private fun decodeJsExpression(expression: String): String {
        val trimmed = expression.trim()
        val literals = extractJsStringLiterals(trimmed)
        val joined = if (literals.isNotEmpty()) literals.joinToString("") else trimmed
        val decoded = when {
            trimmed.contains("atob", true) -> literals.firstOrNull()?.let { decodeBase64String(it) } ?: joined
            trimmed.contains("unescape", true) || trimmed.contains("decodeURIComponent", true) -> runCatching { URLDecoder.decode(joined, "UTF-8") }.getOrDefault(joined)
            else -> joined
        }
        return decoded.unescapeJs()
    }

    private fun extractJsStringLiterals(source: String): List<String> {
        val values = mutableListOf<String>()
        var index = 0
        while (index < source.length) {
            val quote = source[index]
            if (quote != '\'' && quote != '"' && quote != '`') {
                index++
                continue
            }
            val builder = StringBuilder()
            index++
            var escaped = false
            while (index < source.length) {
                val char = source[index++]
                when {
                    escaped -> {
                        builder.append('\\').append(char)
                        escaped = false
                    }
                    char == '\\' -> escaped = true
                    char == quote -> break
                    else -> builder.append(char)
                }
            }
            values.add(builder.toString().unescapeJs())
        }
        return values
    }

    private fun String.unescapeJs(): String {
        var value = this
        value = Regex("""\\u([0-9a-fA-F]{4})""").replace(value) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return value
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\n", "")
            .replace("\\r", "")
            .replace("\\t", " ")
            .replace("\\\\", "\\")
            .htmlUnescape()
    }

    private fun absoluteUrl(raw: String?): String? {
        val value = raw?.trim()?.htmlUnescape()?.takeIf { it.isNotBlank() } ?: return null
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        if (value.startsWith("/")) return mainUrl.trimEnd('/') + value
        return mainUrl.trimEnd('/') + "/" + value.trimStart('/')
    }

    private fun String.htmlUnescape(): String {
        return org.jsoup.parser.Parser.unescapeEntities(this, false)
    }

    private fun String.cleanTitle(): String {
        return trim()
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .replace("- Vidlix", "", ignoreCase = true)
            .trim(' ', '-', '|')
    }

    private fun String.cleanCardText(): String {
        return replace(Regex("(?i)\\bTONTON SEKARANG!?\\b"), "")
            .replace(Regex("\\b\\d+(?:\\.\\d+)?K?\\b"), "")
            .replace(Regex("(?i)\\b(Film|Series)\\b"), "")
            .trim()
    }

    private fun String.extractYear(): Int? {
        return Regex("(19|20)\\d{2}").find(this)?.value?.toIntOrNull()
    }

    private fun String.durationToMinutes(): Int? {
        val hours = Regex("(\\d+)\\s*jam", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*menit", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    private fun isDirectMedia(url: String): Boolean {
        val clean = url.substringBefore("?").lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mkv")
    }

    private fun playbackHeaders(referer: String): Map<String, String> {
        return mapOf(
            "Referer" to referer,
            "Origin" to (runCatching { URI(referer).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: "https://abyssplayer.com"),
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*"
        )
    }

    private fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class VideoCandidate(
        val url: String,
        val name: String?,
        val posterUrl: String?
    )

    private data class AbyssPayload(
        val slug: String,
        val md5Id: String,
        val userId: String,
        val media: String
    )
}
