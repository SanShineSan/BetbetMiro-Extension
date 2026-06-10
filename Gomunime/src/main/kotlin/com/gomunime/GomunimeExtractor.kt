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
        """(?i)['"]?(?:file|src|source|sources|url|hls|playlist|videoUrl|videoSrc|streamUrl|hlsUrl|video_url|embedUrl|embed_url|contentUrl|content_url|iframeUrl|iframe_url|playerUrl|player_url|downloadUrl|download_url|defaultStreamingUrl|link|href)['"]?\s*[:=]\s*['"]([^'"]+)['"]"""
    )
    private val iframeRegex = Regex("""(?i)<iframe[^>]+(?:src|data-src)=['"]([^'"]+)['"]""")
    private val dataAttrRegex = Regex(
        """(?i)data-(?:src|url|embed|iframe|link|href|file|server|player|video|em|id|nume|post|hash|token)\s*=\s*['"]([^'"]+)['"]"""
    )
    private val onclickRegex = Regex("""(?i)(?:open|load|player|server|embed|go|window\.location|location\.href)[^'"]*['"]([^'"]+)['"]""")
    private val quotedPlayableRegex = Regex(
        """(?i)['"]((?:https?:)?//[^'"<>\s\\]+?(?:\.m3u8|\.mp4|\.webm|videoplayback|googlevideo\.com|blogger\.com/video\.g|blogger\.googleusercontent\.com|xtwap\.top|cepat|btube|b-tube|anime-indo\.lol|gdplayer|gdriveplayer|drive\.google|dailymotion|ok\.ru|pixeldrain|mega\.nz|mp4upload|filemoon|streamtape|vidhide|vidguard|voe|dood)[^'"<>\s\\]*)['"]"""
    )
    private val barePlayableRegex = Regex(
        """(?i)(?:https?:)?//[^\s'"<>\\]+?(?:\.m3u8|\.mp4|\.webm|videoplayback|googlevideo\.com|blogger\.com/video\.g|blogger\.googleusercontent\.com|xtwap\.top|cepat|btube|b-tube|anime-indo\.lol|gdplayer|gdriveplayer|drive\.google|dailymotion|ok\.ru|pixeldrain|mega\.nz|mp4upload|filemoon|streamtape|vidhide|vidguard|voe|dood)[^\s'"<>\\]*"""
    )
    private val relativePlayableRegex = Regex(
        """(?i)['"]((?:/|\.\./|\./)[^'"]*(?:play\.php|btube|b-tube|cepat|xtwap|gdplayer|gdriveplayer|player|embed|iframe|source|server|video|ajax|\.m3u8|\.mp4)[^'"]*)['"]"""
    )
    private val encodedHttpRegex = Regex("""https?%3A%2F%2F[^\s'"<>]+""", RegexOption.IGNORE_CASE)

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
        if (!visited.add(normalizedUrl) || depth > 5) return false

        if (normalizedUrl.isDirectMedia() || normalizedUrl.isBloggerVideo()) {
            return emitCandidate(
                providerName,
                GomunimeMediaCandidate(normalizedUrl, serverName(normalizedUrl).ifBlank { providerName }, referer, normalizedUrl.isLikelyHls()),
                subtitleCallback,
                callback
            )
        }

        var found = resolveKnownHost(providerName, normalizedUrl, referer, subtitleCallback, callback)

        if (!normalizedUrl.contains("gomunime", true)) {
            runCatching {
                loadExtractor(normalizedUrl, referer, subtitleCallback) { link ->
                    if (link.url.isPlayableMediaUrl() || link.url.isLikelyHls()) {
                        found = true
                        callback(link)
                    }
                }
            }
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

        val servers = extractServers(mainUrl, normalizedUrl, document)
        for (server in servers) {
            val sourceName = server.name.ifBlank { serverName(server.url).ifBlank { providerName } }
            if (resolveKnownHost(sourceName, server.url, normalizedUrl, subtitleCallback, callback)) {
                found = true
                continue
            }
            if (loadFromUrl(sourceName, mainUrl, server.url, normalizedUrl, subtitleCallback, callback, visited, depth + 1)) {
                found = true
            }
        }

        return found
    }

    private suspend fun resolveKnownHost(
        sourceName: String,
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url
        val lower = fixedUrl.lowercase()

        return when {
            lower.contains("blogger.com/video.g") -> emitCandidate(
                sourceName,
                GomunimeMediaCandidate(fixedUrl, "Blogger", referer, false),
                subtitleCallback,
                callback
            )
            lower.contains("btube") || lower.contains("b-tube") || lower.contains("anime-indo.lol") -> resolveBTube(sourceName, fixedUrl, referer, subtitleCallback, callback)
            lower.contains("xtwap.top") || lower.contains("cepat") -> resolveXtwap(sourceName, fixedUrl, referer, subtitleCallback, callback)
            lower.contains("gdplayer") || lower.contains("gdriveplayer") || lower.contains("drive.google") || lower.contains("/gdrive") -> resolveGenericEmbed(sourceName, fixedUrl, referer, subtitleCallback, callback)
            else -> false
        }
    }

    private suspend fun resolveBTube(
        sourceName: String,
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val response = try {
            app.get(url, headers = pageHeaders(url), referer = referer)
        } catch (_: Throwable) {
            return false
        }

        val document = response.document
        val raw = normalizedText(response.text)
        collectSubtitles(url, document, subtitleCallback)

        document.select("source[src], video[src], video source[src], a[href$=.mp4], a[href$=.m3u8]").forEach { element ->
            val media = normalizeMediaUrl(url, url, element.attr("src").ifBlank { element.attr("href") }) ?: return@forEach
            if (media.isPlayableMediaUrl() || media.isLikelyHls()) {
                emitDirect(sourceName, "B-TUBE", media, url, callback)
                found = true
            }
        }

        for (media in extractUrlsFromText(url, url, raw).filter { it.isPlayableMediaUrl() || it.isLikelyHls() }) {
            emitDirect(sourceName, "B-TUBE", media, url, callback)
            found = true
        }

        if (!found) {
            runCatching {
                loadExtractor(url, referer, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            }
        }

        return found
    }

    private suspend fun resolveXtwap(
        sourceName: String,
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (url.contains("xtwap.top/play.php", true)) {
            emitDirect(sourceName, "CEPAT", url, referer, callback, forceHls = true)
            return true
        }

        val response = try {
            app.get(url, headers = pageHeaders(url), referer = referer)
        } catch (_: Throwable) {
            return false
        }

        val document = response.document
        val raw = normalizedText(response.text)
        collectSubtitles(url, document, subtitleCallback)
        var found = false

        val fileValues = linkedSetOf<String>()
        Regex("""(?i)["']file["']\s*:\s*["']([^"']+)["']""").findAll(raw).forEach { fileValues.add(it.groupValues[1]) }
        Regex("""(?i)["']src["']\s*:\s*["']([^"']+)["']""").findAll(raw).forEach { fileValues.add(it.groupValues[1]) }
        Regex("""(?i)["']url["']\s*:\s*["']([^"']+)["']""").findAll(raw).forEach { fileValues.add(it.groupValues[1]) }

        for (value in fileValues) {
            val media = when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("http", true) -> value
                value.startsWith("/") -> originOf(url).trimEnd('/') + value
                else -> runCatching { URI(url).resolve(value).toString() }.getOrNull() ?: value
            }
            if (media.isPlayableMediaUrl() || media.isLikelyHls()) {
                emitDirect(sourceName, "CEPAT", media, url, callback, forceHls = media.isLikelyHls())
                found = true
            }
        }

        for (media in extractUrlsFromText(url, url, raw).filter { it.isPlayableMediaUrl() || it.isLikelyHls() }) {
            emitDirect(sourceName, "CEPAT", media, url, callback, forceHls = media.isLikelyHls())
            found = true
        }

        if (!found) {
            runCatching {
                loadExtractor(url, referer, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            }
        }

        return found
    }

    private suspend fun resolveGenericEmbed(
        sourceName: String,
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        runCatching {
            loadExtractor(url, referer, subtitleCallback) { link ->
                if (link.url.isPlayableMediaUrl() || link.url.isLikelyHls()) {
                    found = true
                    callback(link)
                }
            }
        }
        if (found) return true

        val document = try {
            app.get(url, headers = pageHeaders(url), referer = referer).document
        } catch (_: Throwable) {
            return false
        }

        collectSubtitles(url, document, subtitleCallback)
        for (candidate in extractMediaCandidates(sourceName, originOf(url), url, document)) {
            if (emitCandidate(sourceName, candidate, subtitleCallback, callback)) found = true
        }

        document.select("iframe[src], iframe[data-src], embed[src]").forEach { frame ->
            val iframe = normalizeMediaUrl(originOf(url), url, frame.attr("src").ifBlank { frame.attr("data-src") }) ?: return@forEach
            runCatching {
                loadExtractor(iframe, url, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
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
            emitDirect(sourceName, candidate.name, candidate.url, candidate.referer, callback, forceHls = true)
            emitted = true

            val directReferer = mediaReferer(candidate.url, candidate.referer)
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
            emitDirect(sourceName, candidate.name, candidate.url, candidate.referer, callback)
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

    private suspend fun emitDirect(
        sourceName: String,
        linkName: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        forceHls: Boolean = false
    ) {
        val directReferer = mediaReferer(url, referer)
        val type = if (forceHls || url.isLikelyHls()) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(
            newExtractorLink(sourceName, linkName, url, type) {
                this.referer = directReferer
                this.quality = qualityFromUrl(url)
                this.headers = mediaHeaders(url, directReferer)
            }
        )
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

        document.select("iframe[src], iframe[data-src], embed[src], #tontonin[src], source[src], video[src]").forEach { element ->
            val src = element.attr("src").ifBlank { element.attr("data-src") }
            resolveServerValue(mainUrl, pageUrl, src)?.let { servers.add(GomunimeServer(serverName(it), it)) }
        }

        document.select("a[href], button, [onclick], [value], [data-src], [data-url], [data-embed], [data-iframe], [data-link], [data-href], [data-file], [data-server], [data-player], [data-video], [data-em], [data-id], [data-nume], [data-post], [data-hash], [data-token]").forEach { element ->
            val label = GomunimeUtils.cleanText(element.text()).ifBlank { element.attr("title").ifBlank { element.attr("aria-label") } }
            val name = label.takeIf { it.isServerLabel() } ?: element.attr("class").ifBlank { "Server" }

            listOf(
                "href", "value", "data-src", "data-url", "data-embed", "data-iframe", "data-link", "data-href", "data-file", "data-server", "data-player", "data-video", "data-em", "data-id", "data-nume", "data-post", "data-hash", "data-token"
            ).forEach { attr ->
                val value = element.attr(attr)
                resolveServerValue(mainUrl, pageUrl, value)?.let { resolved ->
                    if (label.isServerLabel() || resolved.isPotentialServerUrl()) servers.add(GomunimeServer(name, resolved))
                }
            }

            onclickRegex.findAll(element.attr("onclick")).forEach { match ->
                resolveServerValue(mainUrl, pageUrl, match.groupValues[1])?.let { servers.add(GomunimeServer(name, it)) }
            }
        }

        Regex("""(?i)<script[^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(raw)
            .map { it.groupValues[1] }
            .forEach { script ->
                extractUrlsFromText(mainUrl, pageUrl, script).forEach { servers.add(GomunimeServer(serverName(it), it)) }
                extractBase64Payloads(mainUrl, pageUrl, script).forEach { servers.add(GomunimeServer(serverName(it), it)) }
            }

        extractUrlsFromText(mainUrl, pageUrl, raw).forEach { servers.add(GomunimeServer(serverName(it), it)) }
        extractBase64Payloads(mainUrl, pageUrl, raw).forEach { servers.add(GomunimeServer(serverName(it), it)) }

        return servers.distinctBy { it.url }
            .filter { it.url.isPotentialServerUrl() || it.url.isDirectMedia() || it.url.isBloggerVideo() }
            .filterNot { it.url.isNoiseUrl() }
    }

    private fun extractMediaCandidates(sourceName: String, mainUrl: String, referer: String, document: Document): List<GomunimeMediaCandidate> {
        val candidates = linkedSetOf<GomunimeMediaCandidate>()
        val raw = normalizedHtml(document)

        document.select("video[src], video source[src], source[src]").forEach { source ->
            normalizeMediaUrl(mainUrl, referer, source.attr("src"))?.let { url ->
                if (url.isPotentialMediaCandidate()) candidates.add(GomunimeMediaCandidate(url, "$sourceName Video", referer, url.isLikelyHls()))
            }
        }

        document.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[property=og:video:iframe], meta[itemprop=contentUrl], meta[itemprop=embedUrl], link[rel=video_src]").forEach { meta ->
            normalizeMediaUrl(mainUrl, referer, meta.attr("content").ifBlank { meta.attr("href") })?.let { url ->
                if (url.isPotentialMediaCandidate()) candidates.add(GomunimeMediaCandidate(url, "$sourceName Meta", referer, url.isLikelyHls()))
            }
        }

        extractUrlsFromText(mainUrl, referer, raw).forEach { url ->
            if (url.isPotentialMediaCandidate()) candidates.add(GomunimeMediaCandidate(url, serverName(url).ifBlank { "$sourceName Stream" }, referer, url.isLikelyHls()))
        }

        extractBase64Payloads(mainUrl, referer, raw).forEach { url ->
            if (url.isPotentialMediaCandidate()) candidates.add(GomunimeMediaCandidate(url, serverName(url).ifBlank { "$sourceName Base64" }, referer, url.isLikelyHls()))
        }

        return candidates.distinctBy { it.url }.filterNot { it.url.isNoiseUrl() }
    }

    private fun resolveServerValue(mainUrl: String, pageUrl: String, value: String?): String? {
        val raw = value.orEmpty().trim()
        if (raw.isBlank() || raw == "#") return null

        decodeBase64Html(raw)?.let { decoded ->
            iframeRegex.find(decoded)?.groupValues?.getOrNull(1)?.let { src ->
                return absoluteUrl(pageUrl, src) ?: absoluteUrl(mainUrl, src)
            }
            keyValueMediaRegex.find(decoded)?.groupValues?.getOrNull(1)?.let { media ->
                return normalizeMediaUrl(mainUrl, pageUrl, media)
            }
            quotedPlayableRegex.find(decoded)?.groupValues?.getOrNull(1)?.let { media ->
                return normalizeMediaUrl(mainUrl, pageUrl, media)
            }
            if (decoded.startsWith("http", true) || decoded.startsWith("//") || decoded.startsWith("/")) {
                return absoluteUrl(pageUrl, decoded) ?: absoluteUrl(mainUrl, decoded)
            }
        }

        return normalizeMediaUrl(mainUrl, pageUrl, raw)
            ?: absoluteUrl(pageUrl, raw)
            ?: absoluteUrl(mainUrl, raw)
    }

    private fun extractUrlsFromText(mainUrl: String, pageUrl: String, text: String): List<String> {
        val fixed = normalizedText(text)
        val urls = linkedSetOf<String>()

        keyValueMediaRegex.findAll(fixed)
            .mapNotNull { normalizeMediaUrl(mainUrl, pageUrl, it.groupValues[1]) }
            .forEach { urls.add(it) }

        dataAttrRegex.findAll(fixed)
            .mapNotNull { resolveServerValue(mainUrl, pageUrl, it.groupValues[1]) }
            .forEach { urls.add(it) }

        iframeRegex.findAll(fixed)
            .mapNotNull { absoluteUrl(pageUrl, it.groupValues[1]) ?: absoluteUrl(mainUrl, it.groupValues[1]) }
            .forEach { urls.add(it) }

        quotedPlayableRegex.findAll(fixed)
            .mapNotNull { normalizeMediaUrl(mainUrl, pageUrl, it.groupValues[1]) }
            .forEach { urls.add(it) }

        barePlayableRegex.findAll(fixed)
            .mapNotNull { normalizeMediaUrl(mainUrl, pageUrl, it.value) }
            .forEach { urls.add(it) }

        relativePlayableRegex.findAll(fixed)
            .mapNotNull { normalizeMediaUrl(mainUrl, pageUrl, it.groupValues[1]) }
            .forEach { urls.add(it) }

        encodedHttpRegex.findAll(fixed)
            .mapNotNull { normalizeMediaUrl(mainUrl, pageUrl, decodeUrl(it.value)) }
            .forEach { urls.add(it) }

        return urls.distinct().filterNot { it.isNoiseUrl() }
    }

    private fun extractBase64Payloads(mainUrl: String, pageUrl: String, text: String): List<String> {
        val urls = linkedSetOf<String>()
        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""").findAll(text)
            .mapNotNull { decodeBase64Html(it.groupValues[1]) }
            .forEach { decoded -> urls.addAll(extractUrlsFromText(mainUrl, pageUrl, decoded)) }

        Regex("""(?i)['"]([A-Za-z0-9+/=_-]{80,})['"]""").findAll(text)
            .mapNotNull { decodeBase64Html(it.groupValues[1]) }
            .filter { it.contains("http", true) || it.contains("iframe", true) || it.contains("file", true) }
            .forEach { decoded -> urls.addAll(extractUrlsFromText(mainUrl, pageUrl, decoded)) }

        return urls.distinct()
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

    private fun normalizedHtml(document: Document): String = normalizedText(document.html())

    private fun normalizedText(text: String): String {
        var output = text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("&amp;", "&")
        repeat(2) {
            output = decodeUrl(output)
        }
        return decodeUnicodeEscapes(output)
    }

    private fun normalizeMediaUrl(mainUrl: String, referer: String, value: String?): String? {
        val raw = normalizedText(
            value.orEmpty()
                .trim()
                .trim('"', '\'', ',', ';')
        )

        if (
            raw.isBlank() || raw == "#" || raw.equals("null", true) ||
            raw.startsWith("about:", true) || raw.startsWith("blob:", true) ||
            raw.startsWith("data:", true) || raw.startsWith("intent:", true) ||
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
        when {
            url.contains("gofile.io", true) -> headers["Origin"] = "https://gofile.io"
            url.contains("xtwap.top", true) -> headers["Origin"] = "https://xtwap.top"
            url.contains("anime-indo.lol", true) -> headers["Origin"] = "https://anime-indo.lol"
        }
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
        if (referer.contains("xtwap.top", true)) headers["Origin"] = "https://xtwap.top"
        return headers
    }

    private fun serverName(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("anime-indo.lol") || lower.contains("btube") || lower.contains("b-tube") -> "B-TUBE"
            lower.contains("xtwap.top") || lower.contains("cepat") -> "CEPAT"
            lower.contains("gdplayer") || lower.contains("gdriveplayer") || lower.contains("drive.google") -> "GDRIVE"
            lower.contains("googlevideo") || lower.contains("videoplayback") -> "GoogleVideo"
            lower.contains("blogger") -> "Blogger"
            lower.contains("dailymotion") -> "Dailymotion"
            lower.contains("ok.ru") -> "OK.ru"
            lower.contains("pixeldrain") -> "Pixeldrain"
            lower.contains("mega.nz") -> "Mega"
            else -> "Embed"
        }
    }

    private fun String.isServerLabel(): Boolean {
        val lower = lowercase()
        return lower.contains("b-tube") || lower.contains("btube") || lower.contains("cepat") || lower.contains("gdrive") ||
            lower.contains("server") || lower.contains("mirror") || lower.contains("stream") || lower.contains("download")
    }

    private fun String.isPotentialServerUrl(): Boolean {
        val lower = lowercase()
        if (isNoiseUrl()) return false
        if (startsWith(GomunimeSeeds.MAIN_URL, true) && !lower.contains("player") && !lower.contains("embed") && !lower.contains("ajax") && !lower.contains("source") && !lower.contains("server") && !lower.contains("video")) return false
        return listOf(
            "btube", "b-tube", "anime-indo.lol", "xtwap.top", "cepat", "gdplayer", "gdriveplayer", "drive.google",
            "blogger", "googlevideo", "videoplayback", "dailymotion", "ok.ru", "pixeldrain", "mega.nz", "mp4upload",
            "filemoon", "streamtape", "vidhide", "vidguard", "voe", "dood", ".m3u8", ".mp4", ".webm",
            "/embed/", "/player/", "/iframe/", "play.php", "admin-ajax.php"
        ).any { lower.contains(it) }
    }

    private fun String.isPotentialMediaCandidate(): Boolean {
        return isDirectMedia() || isBloggerVideo() || isGoogleVideoUrl()
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
            lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") ||
            lower.contains("=image/") || lower.contains("mime=image")
        ) return false
        if (lower.endsWith(".php") && !lower.contains("xtwap.top/play.php")) return false
        if (lower.contains("googlevideo.com")) return lower.contains("videoplayback") || lower.contains("mime=video")
        if (lower.contains("blogger.googleusercontent.com")) return lower.contains(".mp4") || lower.contains(".webm") || lower.contains("mime=video")
        return true
    }

    private fun String.isBloggerVideo(): Boolean {
        val lower = lowercase()
        return lower.contains("blogger.com/video.g")
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("facebook.com") || lower.contains("telegram") || lower.contains("instagram") || lower.contains("twitter.com") ||
            lower.contains("doubleclick") || lower.contains("googlesyndication") || lower.endsWith(".css") || lower.contains(".css?") ||
            lower.endsWith(".js") || lower.contains(".js?") || lower.endsWith(".ico") || lower.endsWith(".svg") ||
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif")
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

    private fun originOf(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(GomunimeSeeds.MAIN_URL)
    }

    private data class ResolvedVideo(
        val url: String,
        val quality: Int
    )
}
