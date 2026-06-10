package com.gomunime

import com.gomunime.GomunimeUtils.absoluteUrl
import com.gomunime.GomunimeUtils.decodeBase64Html
import com.gomunime.GomunimeUtils.decodeUrl
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URI

object GomunimeExtractor {
    private const val BLOGGER_REFERER = "https://www.blogger.com/"

    private val keyValueMediaRegex = Regex(
        """(?i)['\"]?(?:file|src|source|url|hls|playlist|videoUrl|videoSrc|streamUrl|hlsUrl|video_url|embedUrl|embed_url|contentUrl|content_url|iframeUrl|iframe_url|playerUrl|player_url|downloadUrl|download_url|defaultStreamingUrl)['\"]?\s*[:=]\s*['\"]([^'\"]+)['\"]"""
    )
    private val jwPlayerFileRegex = Regex(
        """(?i)(?:jwplayer\s*\([^)]*\)\s*\.\s*setup\s*\(|sources\s*:\s*\[|file\s*:)"""
    )
    private val dataAttrRegex = Regex(
        """(?i)data-(?:src|url|embed|iframe|link|server|player|video|em|id|nume|post|hash|token)\s*=\s*['\"]([^'\"]+)['\"]"""
    )
    private val iframeRegex = Regex(
        """(?i)<iframe[^>]+(?:src|data-src)=['\"]([^'\"]+)['\"]"""
    )
    private val quotedMediaRegex = Regex(
        """(?i)['\"]((?:https?:)?//[^'\"<>\s\\]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^'\"<>\s\\]+|videoplayback[^'\"<>\s\\]*|blogger\.com/video\.g[^'\"<>\s\\]*|xtwap\.top/play\.php[^'\"<>\s\\]*)(?:\?[^'\"<>\s\\]*)?)['\"]"""
    )
    private val bareMediaRegex = Regex(
        """(?i)(?:https?:)?//[^\s'\"<>\\]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^\s'\"<>\\]+|videoplayback[^\s'\"<>\\]*|blogger\.com/video\.g[^\s'\"<>\\]*|xtwap\.top/play\.php[^\s'\"<>\\]*)(?:\?[^\s'\"<>\\]*)?"""
    )
    private val encodedHttpRegex = Regex(
        """https?%3A%2F%2F[^\s'\"<>]+""",
        RegexOption.IGNORE_CASE
    )

    suspend fun loadLinks(
        providerName: String,
        mainUrl: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var found = false

        fun emit(link: ExtractorLink) {
            val key = link.url.substringBefore("?token=").substringBefore("&token=")
            if (emitted.add(key)) callback(link)
        }

        for (candidateUrl in buildDataUrlCandidates(mainUrl, data)) {
            if (loadFromUrl(providerName, mainUrl, candidateUrl, data, subtitleCallback, ::emit, visited, depth = 0)) {
                found = true
            }
        }

        return found
    }

    private suspend fun loadFromUrl(
        providerName: String,
        mainUrl: String,
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String>,
        depth: Int
    ): Boolean {
        val normalizedUrl = absoluteUrl(mainUrl, url) ?: return false
        if (!visited.add(normalizedUrl) || depth > 4) return false

        if (normalizedUrl.isDirectMedia() || normalizedUrl.isBloggerVideo()) {
            return emitCandidate(
                providerName,
                GomunimeMediaCandidate(normalizedUrl, providerName, referer, normalizedUrl.isLikelyHls()),
                subtitleCallback,
                callback
            )
        }

        var found = false

        try {
            loadExtractor(normalizedUrl, referer, subtitleCallback) { link ->
                if (link.url.isPlayableMediaUrl() || link.url.isLikelyHls()) {
                    found = true
                    callback(link)
                }
            }
        } catch (_: Throwable) {
            // Continue with local HAR-backed parser fallback.
        }

        val document = try {
            app.get(normalizedUrl, headers = pageHeaders(normalizedUrl), referer = referer).document
        } catch (_: Throwable) {
            return found
        }

        collectSubtitles(normalizedUrl, document, subtitleCallback)

        for (candidate in extractMediaCandidates(providerName, mainUrl, normalizedUrl, document)) {
            if (emitCandidate(providerName, candidate, subtitleCallback, callback)) found = true
        }

        if (found && normalizedUrl.isKnownInlinePlayerHost()) return true

        for (server in extractServers(mainUrl, normalizedUrl, document)) {
            if (loadFromUrl(server.name.ifBlank { providerName }, mainUrl, server.url, normalizedUrl, subtitleCallback, callback, visited, depth + 1)) {
                found = true
            }
        }

        return found
    }

    private suspend fun emitCandidate(
        sourceName: String,
        candidate: GomunimeMediaCandidate,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false

        if (candidate.url.isBloggerVideo()) {
            for (video in extractBloggerDirectVideos(candidate.url, candidate.referer)) {
                callback(
                    newExtractorLink(sourceName, candidate.name, video.url, ExtractorLinkType.VIDEO) {
                        val directReferer = mediaReferer(video.url, candidate.url)
                        referer = directReferer
                        quality = video.quality
                        headers = mediaHeaders(video.url, directReferer)
                    }
                )
                emitted = true
            }
            if (emitted) return true
        }

        if (candidate.isHls || candidate.url.isLikelyHls()) {
            val directReferer = mediaReferer(candidate.url, candidate.referer)

            // HAR 2026-06-10 shows XTWAP exposes playable HLS as /play.php?n=...
            // without a .m3u8 extension. Emit the master URL directly first so
            // callback > 0 even when M3u8Helper cannot pre-expand Cloudflare-backed playlists.
            callback(
                newExtractorLink(sourceName, candidate.name, candidate.url, ExtractorLinkType.M3U8) {
                    referer = directReferer
                    quality = qualityFromUrl(candidate.url)
                    headers = mediaHeaders(candidate.url, directReferer)
                }
            )
            emitted = true

            val links = try {
                generateM3u8(
                    source = sourceName,
                    streamUrl = candidate.url,
                    referer = directReferer,
                    headers = mediaHeaders(candidate.url, directReferer)
                )
            } catch (_: Throwable) {
                emptyList()
            }

            links.forEach { link ->
                emitted = true
                callback(link)
            }
            if (emitted) return true
        }

        if (candidate.url.isDirectVideo()) {
            val directReferer = mediaReferer(candidate.url, candidate.referer)
            callback(
                newExtractorLink(sourceName, candidate.name, candidate.url, ExtractorLinkType.VIDEO) {
                    referer = directReferer
                    quality = qualityFromUrl(candidate.url)
                    headers = mediaHeaders(candidate.url, directReferer)
                }
            )
            return true
        }

        val extracted = try {
            loadExtractor(candidate.url, candidate.referer, subtitleCallback) { link ->
                if (link.url.isPlayableMediaUrl() || link.url.isLikelyHls()) {
                    emitted = true
                    callback(link)
                }
            }
        } catch (_: Throwable) {
            false
        }

        return emitted || extracted
    }

    private fun buildDataUrlCandidates(mainUrl: String, data: String): List<String> {
        val fixed = absoluteUrl(mainUrl, data) ?: data
        val clean = fixed.substringBefore("#")
        val noSlash = clean.trimEnd('/')
        val candidates = linkedSetOf<String>()

        candidates.add(clean)
        candidates.add(noSlash)
        candidates.add("$noSlash/")

        val lower = noSlash.lowercase()
        if (lower.contains("-episode-") && !lower.contains("subtitle-indonesia") && !lower.contains("sub-indo")) {
            candidates.add("$noSlash-subtitle-indonesia/")
            candidates.add("$noSlash-sub-indo/")
        }

        return candidates.filter { it.isNotBlank() }.distinct()
    }

    private fun extractServers(mainUrl: String, pageUrl: String, document: Document): List<GomunimeServer> {
        val servers = linkedSetOf<GomunimeServer>()
        val raw = normalizedHtml(document)

        document.select("select.mirror option[value], .mirror option[value], option[value], select option[value]").forEach { option ->
            val name = GomunimeUtils.cleanText(option.text()).ifBlank { option.attr("label").ifBlank { "Server" } }
            resolveServerValue(mainUrl, pageUrl, option.attr("value"))?.let { servers.add(GomunimeServer(name, it)) }
        }

        document.select("iframe[src], iframe[data-src], embed[src]").forEach { frame ->
            val src = absoluteUrl(pageUrl, frame.attr("src").ifBlank { frame.attr("data-src") })
                ?: absoluteUrl(mainUrl, frame.attr("src").ifBlank { frame.attr("data-src") })
            if (!src.isNullOrBlank()) servers.add(GomunimeServer(frame.attr("title").ifBlank { serverName(src) }, src))
        }

        document.select("a[href*='gofile'], a[href*='drive'], a[href*='blogger'], a[href*='btube'], a[href*='anime-indo'], a[href*='xtwap'], a[href*='gdplayer'], a[href*='mp4upload'], a[href*='filemoon'], a[href*='streamtape'], a[href*='vidhide'], a[href*='vidguard'], a[href*='voe']").forEach { anchor ->
            val href = absoluteUrl(pageUrl, anchor.attr("href")) ?: absoluteUrl(mainUrl, anchor.attr("href"))
            if (!href.isNullOrBlank()) servers.add(GomunimeServer(GomunimeUtils.cleanText(anchor.text()).ifBlank { serverName(href) }, href))
        }

        document.select("[value], [data-src], [data-url], [data-embed], [data-iframe], [data-link], [data-server], [data-player], [data-video], [data-em], [data-id], [data-nume], [data-post], [data-hash], [data-token]").forEach { element ->
            val name = GomunimeUtils.cleanText(element.text()).ifBlank { element.attr("class").ifBlank { "Server" } }
            listOf(
                "value", "data-src", "data-url", "data-embed", "data-iframe", "data-link", "data-server", "data-player", "data-video", "data-em", "data-id", "data-nume", "data-post", "data-hash", "data-token"
            ).forEach { attr ->
                resolveServerValue(mainUrl, pageUrl, element.attr(attr))?.let { servers.add(GomunimeServer(name, it)) }
            }
        }

        Regex("""(?i)<script[^>]+type=['\"]application/ld\+json['\"][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(raw)
            .map { it.groupValues[1] }
            .forEach { json ->
                keyValueMediaRegex.findAll(json)
                    .mapNotNull { normalizeMediaUrl(mainUrl, pageUrl, it.groupValues[1]) }
                    .forEach { servers.add(GomunimeServer(serverName(it), it)) }
            }

        Regex("""(?i)atob\(['\"]([^'\"]+)['\"]\)""")
            .findAll(raw)
            .mapNotNull { decodeBase64Html(it.groupValues[1]) }
            .forEach { decoded ->
                iframeRegex.findAll(decoded)
                    .mapNotNull { absoluteUrl(pageUrl, it.groupValues[1]) ?: absoluteUrl(mainUrl, it.groupValues[1]) }
                    .forEach { servers.add(GomunimeServer("Base64", it)) }
                keyValueMediaRegex.findAll(decoded)
                    .mapNotNull { normalizeMediaUrl(mainUrl, pageUrl, it.groupValues[1]) }
                    .forEach { servers.add(GomunimeServer("Base64", it)) }
            }

        dataAttrRegex.findAll(raw)
            .mapNotNull { resolveServerValue(mainUrl, pageUrl, it.groupValues[1]) }
            .forEach { servers.add(GomunimeServer(serverName(it), it)) }

        iframeRegex.findAll(raw)
            .mapNotNull { absoluteUrl(pageUrl, it.groupValues[1]) ?: absoluteUrl(mainUrl, it.groupValues[1]) }
            .forEach { servers.add(GomunimeServer(serverName(it), it)) }

        keyValueMediaRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, pageUrl, it.groupValues[1]) }
            .forEach { servers.add(GomunimeServer(serverName(it), it)) }

        Regex("""(?i)['\"]((?:https?:)?//[^'\"]+(?:embed|player|stream|drive|gofile|dood|streamtape|filemoon|vidhide|vidguard|voe|mp4upload|uqload|krakenfiles|filelions|btube|anime-indo|xtwap|cepat|gdplayer|bubarindpr|blogger|googlevideo|videoplayback)[^'\"]*)['\"]""")
            .findAll(raw)
            .mapNotNull { absoluteUrl(pageUrl, it.groupValues[1]) ?: absoluteUrl(mainUrl, it.groupValues[1]) }
            .forEach { servers.add(GomunimeServer(serverName(it), it)) }

        return servers.distinctBy { it.url }
            .filterNot { it.url.isNoiseUrl() }
    }

    private fun extractMediaCandidates(sourceName: String, mainUrl: String, referer: String, document: Document): List<GomunimeMediaCandidate> {
        val candidates = linkedSetOf<GomunimeMediaCandidate>()
        val raw = normalizedHtml(document)

        document.select("video[src], video source[src], source[src]").forEach { source ->
            normalizeMediaUrl(mainUrl, referer, source.attr("src"))?.let { url ->
                if (url.isPotentialMediaCandidate()) {
                    candidates.add(GomunimeMediaCandidate(url, "$sourceName Video", referer, url.isLikelyHls()))
                }
            }
        }

        document.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[property=og:video:iframe], meta[itemprop=contentUrl], meta[itemprop=embedUrl], link[rel=video_src]").forEach { meta ->
            normalizeMediaUrl(mainUrl, referer, meta.attr("content").ifBlank { meta.attr("href") })?.let { url ->
                if (url.isPotentialMediaCandidate()) {
                    candidates.add(GomunimeMediaCandidate(url, "$sourceName Meta", referer, url.isLikelyHls()))
                }
            }
        }

        if (jwPlayerFileRegex.containsMatchIn(raw)) {
            keyValueMediaRegex.findAll(raw)
                .mapNotNull { normalizeMediaUrl(mainUrl, referer, it.groupValues[1]) }
                .forEach { url ->
                    if (url.isPotentialMediaCandidate()) {
                        candidates.add(GomunimeMediaCandidate(url, "$sourceName JWPlayer", referer, url.isLikelyHls()))
                    }
                }
        }

        keyValueMediaRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, referer, it.groupValues[1]) }
            .forEach { url ->
                if (url.isPotentialMediaCandidate()) {
                    candidates.add(GomunimeMediaCandidate(url, "$sourceName Stream", referer, url.isLikelyHls()))
                }
            }

        quotedMediaRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, referer, it.groupValues[1]) }
            .forEach { url ->
                if (url.isPotentialMediaCandidate()) {
                    candidates.add(GomunimeMediaCandidate(url, "$sourceName Direct", referer, url.isLikelyHls()))
                }
            }

        bareMediaRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, referer, it.value) }
            .forEach { url ->
                if (url.isPotentialMediaCandidate()) {
                    candidates.add(GomunimeMediaCandidate(url, "$sourceName Bare", referer, url.isLikelyHls()))
                }
            }

        encodedHttpRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, referer, decodeUrl(it.value)) }
            .forEach { url ->
                if (url.isPotentialMediaCandidate()) {
                    candidates.add(GomunimeMediaCandidate(url, "$sourceName Encoded", referer, url.isLikelyHls()))
                }
            }

        return candidates.distinctBy { it.url }
            .filterNot { it.url.isNoiseUrl() }
    }

    private fun resolveServerValue(mainUrl: String, pageUrl: String, value: String?): String? {
        val raw = value.orEmpty().trim()
        if (raw.isBlank() || raw == "#") return null

        val decodedHtml = decodeBase64Html(raw)
        if (!decodedHtml.isNullOrBlank()) {
            iframeRegex.find(decodedHtml)?.groupValues?.getOrNull(1)?.let { src ->
                return absoluteUrl(pageUrl, src) ?: absoluteUrl(mainUrl, src)
            }
            keyValueMediaRegex.find(decodedHtml)?.groupValues?.getOrNull(1)?.let { media ->
                return normalizeMediaUrl(mainUrl, pageUrl, media)
            }
            quotedMediaRegex.find(decodedHtml)?.groupValues?.getOrNull(1)?.let { media ->
                return normalizeMediaUrl(mainUrl, pageUrl, media)
            }
            if (decodedHtml.startsWith("http", true) || decodedHtml.startsWith("//")) {
                return absoluteUrl(pageUrl, decodedHtml) ?: absoluteUrl(mainUrl, decodedHtml)
            }
        }

        return normalizeMediaUrl(mainUrl, pageUrl, raw)
            ?: absoluteUrl(pageUrl, raw)
            ?: absoluteUrl(mainUrl, raw)
    }

    private suspend fun collectSubtitles(
        baseUrl: String,
        document: Document,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        document.select("track[kind=subtitles], track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val subUrl = absoluteUrl(baseUrl, element.attr("src").ifBlank { element.attr("href") }) ?: return@forEach
            val label = GomunimeUtils.cleanText(
                element.attr("label").ifBlank {
                    element.attr("srclang").ifBlank {
                        element.text().ifBlank { "Subtitle" }
                    }
                }
            )
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }

    private fun normalizedHtml(document: Document): String {
        return decodeUrl(
            document.html()
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
        )
    }

    private fun normalizeMediaUrl(mainUrl: String, referer: String, value: String?): String? {
        val raw = decodeUrl(
            value.orEmpty()
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim()
                .trim('"', '\'', ',', ';')
        )

        if (
            raw.isBlank() ||
            raw == "#" ||
            raw.equals("null", true) ||
            raw.startsWith("about:", true) ||
            raw.startsWith("blob:", true) ||
            raw.startsWith("data:", true) ||
            raw.startsWith("intent:", true) ||
            raw.startsWith("javascript:", true)
        ) return null

        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> absoluteUrl(referer, raw) ?: absoluteUrl(mainUrl, raw)
            else -> runCatching { URI(referer).resolve(raw).toString() }.getOrNull()
        }
    }

    private suspend fun extractBloggerDirectVideos(url: String, referer: String?): List<ResolvedVideo> {
        val fixedUrl = if (url.startsWith("//")) "https:$url" else decodeUrl(url)

        if (fixedUrl.contains("googlevideo.com/videoplayback", true) || fixedUrl.contains("videoplayback", true)) {
            return listOf(ResolvedVideo(fixedUrl, qualityFromUrl(fixedUrl)))
        }

        val token = Regex("[?&]token=([^&]+)")
            .find(fixedUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val page = try {
            app.get(
                fixedUrl,
                referer = referer ?: BLOGGER_REFERER,
                headers = pageHeaders(fixedUrl)
            )
        } catch (_: Throwable) {
            return emptyList()
        }

        val html = page.text
        val cookies = page.cookies
        val fSid = Regex("FdrFJe\":\"(-?\\d+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: ""
        val bl = Regex("cfb2h\":\"([^\"]+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()
        val hl = Regex("lang=\"([^\"]+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.ifBlank { null }
            ?: "en-US"
        val reqId = (10000..99999).random()
        val rpcId = "WcwnYd"
        val payload = """[[["$rpcId","[\"$token\",\"\",0]",null,"generic"]]]"""
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute" +
            "?rpcids=$rpcId&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=$hl&_reqid=$reqId&rt=c"

        val response = try {
            app.post(
                apiUrl,
                data = mapOf("f.req" to payload),
                referer = fixedUrl,
                cookies = cookies,
                headers = mapOf(
                    "Origin" to "https://www.blogger.com",
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "X-Same-Domain" to "1",
                    "User-Agent" to GomunimeUtils.USER_AGENT
                )
            ).text
        } catch (_: Throwable) {
            return emptyList()
        }

        val decoded = decodeUnicodeEscapes(response)
        return Regex("""https://[^\s"']+""")
            .findAll(decoded)
            .map { it.value }
            .plus(Regex("""https://[^\s"']+""").findAll(response).map { it.value })
            .map { normalizeVideoUrl(it) }
            .filter {
                it.contains("googlevideo.com/videoplayback", true) ||
                    it.contains("blogger.googleusercontent.com", true)
            }
            .distinct()
            .map { videoUrl -> ResolvedVideo(videoUrl, qualityFromUrl(videoUrl)) }
            .toList()
    }

    private fun decodeUnicodeEscapes(input: String): String {
        val unicodeRegex = Regex("""\\u([0-9a-fA-F]{4})""")
        var output = input
        repeat(2) {
            output = unicodeRegex.replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }
        return output
            .replace("\\/", "/")
            .replace("\\=", "=")
            .replace("\\&", "&")
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
    }

    private fun normalizeVideoUrl(input: String): String {
        return decodeUnicodeEscapes(input)
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("\\", "")
    }

    private fun pageHeaders(url: String): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to GomunimeUtils.USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        if (url.contains("gofile.io", true)) headers["Origin"] = "https://gofile.io"
        return headers
    }

    private fun mediaReferer(url: String, fallback: String): String {
        return when {
            url.isGoogleVideoUrl() -> ""
            url.contains("xtwap.top/play.php", true) -> ""
            url.contains("blogger.googleusercontent.com", true) -> BLOGGER_REFERER
            else -> fallback
        }
    }

    private fun mediaHeaders(url: String, referer: String): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to GomunimeUtils.USER_AGENT,
            "Accept" to "*/*"
        )
        if (referer.isNotBlank()) headers["Referer"] = referer
        if (referer.contains("blogger.com", true) && !url.isGoogleVideoUrl()) headers["Origin"] = "https://www.blogger.com"
        return headers
    }

    private fun serverName(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("anime-indo.lol") || lower.contains("btube") -> "B-TUBE"
            lower.contains("xtwap.top") || lower.contains("cepat") -> "CEPAT"
            lower.contains("gdplayer") || lower.contains("gdriveplayer") -> "GDRIVE"
            lower.contains("googlevideo") || lower.contains("videoplayback") -> "GoogleVideo"
            lower.contains("blogger") -> "Blogger"
            else -> "Embed"
        }
    }

    private fun String.isPotentialMediaCandidate(): Boolean {
        return isDirectMedia() || isBloggerVideo() || isGoogleVideoUrl()
    }

    private fun String.isKnownInlinePlayerHost(): Boolean {
        val lower = lowercase()
        return lower.contains("anime-indo.lol/btube") ||
            lower.contains("xtwap.top/cepat") ||
            lower.contains("xtwap.top/play.php") ||
            lower.contains("gdplayer.to/x/") ||
            lower.contains("gdriveplayer.to/embed")
    }

    private fun String.isLikelyHls(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") ||
            lower.contains("playlist") ||
            lower.contains("hls") ||
            lower.contains("master") ||
            lower.contains("xtwap.top/play.php")
    }

    private fun String.isDirectMedia(): Boolean = isPlayableMediaUrl() || isLikelyHls()

    private fun String.isDirectVideo(): Boolean = isPlayableMediaUrl() && !isLikelyHls()

    private fun String.isGoogleVideoUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("googlevideo.com/videoplayback") || lower.contains("videoplayback")
    }

    private fun String.isPlayableMediaUrl(): Boolean {
        val lower = lowercase()
        val hasVideoSignature = lower.contains("mime=video") ||
            lower.contains("videoplayback") ||
            lower.contains(".mp4") ||
            lower.contains(".m3u8") ||
            lower.contains(".webm") ||
            lower.contains("xtwap.top/play.php")

        if (!hasVideoSignature) return false

        if (
            lower.endsWith(".html") ||
            lower.endsWith(".htm") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.contains("=image/") ||
            lower.contains("mime=image")
        ) return false

        if (lower.endsWith(".php") && !lower.contains("xtwap.top/play.php")) return false

        if (lower.contains("googlevideo.com")) {
            return lower.contains("videoplayback") || lower.contains("mime=video")
        }

        if (lower.contains("blogger.googleusercontent.com")) {
            return lower.contains(".mp4") || lower.contains(".webm") || lower.contains("mime=video")
        }

        return true
    }

    private fun String.isBloggerVideo(): Boolean {
        val lower = lowercase()
        return lower.contains("blogger.com/video.g")
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("facebook.com") ||
            lower.contains("telegram") ||
            lower.contains("instagram") ||
            lower.contains("twitter.com") ||
            lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.endsWith(".css") ||
            lower.contains(".css?") ||
            lower.endsWith(".js") ||
            lower.contains(".js?") ||
            lower.endsWith(".ico") ||
            lower.endsWith(".svg") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif")
    }

    private fun qualityFromUrl(url: String): Int {
        val text = url.lowercase()
        val itag = Regex("""[?&]itag=(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when {
            itag in setOf(37, 96, 137, 248, 299) -> Qualities.P1080.value
            itag in setOf(22, 59, 136, 247, 298) -> Qualities.P720.value
            itag in setOf(18, 134, 244) -> Qualities.P360.value
            itag in setOf(59, 135) -> Qualities.P480.value
            itag == 36 -> Qualities.P240.value
            itag == 17 -> Qualities.P144.value
            text.contains("1080") -> Qualities.P1080.value
            text.contains("720") -> Qualities.P720.value
            text.contains("480") -> Qualities.P480.value
            text.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private data class ResolvedVideo(
        val url: String,
        val quality: Int
    )
}
