package com.gomunime

import com.gomunime.GomunimeUtils.absoluteUrl
import com.gomunime.GomunimeUtils.decodeBase64Html
import com.gomunime.GomunimeUtils.decodeUrl
import com.gomunime.GomunimeUtils.episodeNumber
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
    private val keyValueMediaRegex = Regex(
        """(?i)(?:file|src|source|url|hls|playlist|videoUrl|hlsUrl|video_url|embed_url|iframe_url)\s*[:=]\s*['\"]([^'\"]+)['\"]"""
    )
    private val dataAttrRegex = Regex(
        """(?i)data-(?:src|url|embed|iframe|link|server|player|id|nume|post)\s*=\s*['\"]([^'\"]+)['\"]"""
    )
    private val iframeRegex = Regex(
        """(?i)<iframe[^>]+(?:src|data-src)=['\"]([^'\"]+)['\"]"""
    )
    private val quotedMediaRegex = Regex(
        """(?i)['\"]((?:https?:)?//[^'\"<>\s\\]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^'\"<>\s\\]+|videoplayback[^'\"<>\s\\]*)(?:\?[^'\"<>\s\\]*)?)['\"]"""
    )
    private val bareMediaRegex = Regex(
        """(?i)(?:https?:)?//[^\s'\"<>\\]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^\s'\"<>\\]+|videoplayback[^\s'\"<>\\]*)(?:\?[^\s'\"<>\\]*)?"""
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

        fun emit(link: ExtractorLink) {
            val key = link.url.substringBefore("?token=").substringBefore("&token=")
            if (emitted.add(key)) callback(link)
        }

        return loadFromUrl(providerName, mainUrl, data, data, subtitleCallback, ::emit, visited, depth = 0)
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
        if (!visited.add(normalizedUrl)) return false

        if (normalizedUrl.isDirectMedia()) {
            return emitCandidate(
                providerName,
                GomunimeMediaCandidate(normalizedUrl, providerName, referer, normalizedUrl.isLikelyHls()),
                subtitleCallback,
                callback
            )
        }

        // Let Cloudstream extractor try first for real embed pages.
        var found = false
        try {
            if (
                loadExtractor(normalizedUrl, referer, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            ) {
                found = true
            }
        } catch (_: Throwable) {
            // Continue with local parser fallback.
        }

        val document = try {
            app.get(normalizedUrl, headers = GomunimeUtils.headers, referer = referer).document
        } catch (_: Throwable) {
            return found
        }

        collectSubtitles(normalizedUrl, document, subtitleCallback)

        val pageCandidates = extractMediaCandidates(providerName, mainUrl, normalizedUrl, document)
        for (candidate in pageCandidates) {
            if (emitCandidate(providerName, candidate, subtitleCallback, callback)) found = true
        }

        val servers = extractServers(mainUrl, normalizedUrl, document)
        for (server in servers) {
            if (loadFromUrl(server.name.ifBlank { providerName }, mainUrl, server.url, normalizedUrl, subtitleCallback, callback, visited, depth + 1)) {
                found = true
            }
        }

        // When provider-test sends an anime detail page as episode data, follow the first real episode page.
        if (!found && depth == 0) {
            val episodeLinks = extractEpisodeLinks(mainUrl, normalizedUrl, document)
            for (episodeUrl in episodeLinks.take(6)) {
                if (loadFromUrl(providerName, mainUrl, episodeUrl, normalizedUrl, subtitleCallback, callback, visited, depth + 1)) {
                    found = true
                    break
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

        if (candidate.isHls || candidate.url.contains(".m3u8", true)) {
            val links = try {
                generateM3u8(
                    source = sourceName,
                    streamUrl = candidate.url,
                    referer = candidate.referer,
                    headers = GomunimeUtils.headers
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
            callback(
                newExtractorLink(
                    sourceName,
                    candidate.name,
                    candidate.url,
                    ExtractorLinkType.VIDEO
                ) {
                    referer = candidate.referer
                    quality = qualityFromUrl(candidate.url)
                    headers = GomunimeUtils.headers
                }
            )
            return true
        }

        val extracted = try {
            loadExtractor(candidate.url, candidate.referer, subtitleCallback) { link ->
                emitted = true
                callback(link)
            }
        } catch (_: Throwable) {
            false
        }

        return emitted || extracted
    }

    private fun extractServers(mainUrl: String, pageUrl: String, document: Document): List<GomunimeServer> {
        val servers = linkedSetOf<GomunimeServer>()
        val raw = normalizedHtml(document)

        document.select("select.mirror option[value], .mirror option[value], option[value], select option[value]").forEach { option ->
            val name = GomunimeUtils.cleanText(option.text()).ifBlank { "Server" }
            resolveServerValue(mainUrl, pageUrl, option.attr("value"))?.let { servers.add(GomunimeServer(name, it)) }
        }

        document.select("iframe[src], iframe[data-src], embed[src]").forEach { frame ->
            val src = absoluteUrl(pageUrl, frame.attr("src").ifBlank { frame.attr("data-src") }) ?: absoluteUrl(mainUrl, frame.attr("src").ifBlank { frame.attr("data-src") })
            if (!src.isNullOrBlank()) servers.add(GomunimeServer(frame.attr("title").ifBlank { "Embed" }, src))
        }

        document.select("[data-src], [data-url], [data-embed], [data-iframe], [data-link], [data-server], [data-player], [data-id], [data-nume], [data-post]").forEach { element ->
            val name = GomunimeUtils.cleanText(element.text()).ifBlank { element.attr("class").ifBlank { "Server" } }
            listOf("data-src", "data-url", "data-embed", "data-iframe", "data-link", "data-server", "data-player").forEach { attr ->
                resolveServerValue(mainUrl, pageUrl, element.attr(attr))?.let { servers.add(GomunimeServer(name, it)) }
            }
        }

        dataAttrRegex.findAll(raw)
            .mapNotNull { resolveServerValue(mainUrl, pageUrl, it.groupValues[1]) }
            .forEach { servers.add(GomunimeServer("Embed", it)) }

        iframeRegex.findAll(raw)
            .mapNotNull { absoluteUrl(pageUrl, it.groupValues[1]) ?: absoluteUrl(mainUrl, it.groupValues[1]) }
            .forEach { servers.add(GomunimeServer("Iframe", it)) }

        keyValueMediaRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, pageUrl, it.groupValues[1]) }
            .forEach { servers.add(GomunimeServer("Script", it)) }

        // Generic embed URLs that are not direct media but are often resolved by loadExtractor.
        Regex("""(?i)['\"]((?:https?:)?//[^'"]+(?:embed|player|stream|drive|dood|streamtape|filemoon|vidhide|vidguard|voe|mp4upload|uqload|krakenfiles|filelions|btube|gdrive)[^'"]*)['\"]""")
            .findAll(raw)
            .mapNotNull { absoluteUrl(pageUrl, it.groupValues[1]) ?: absoluteUrl(mainUrl, it.groupValues[1]) }
            .forEach { servers.add(GomunimeServer("Embed", it)) }

        return servers.distinctBy { it.url }
            .filterNot { it.url.isNoiseUrl() }
    }

    private fun extractMediaCandidates(sourceName: String, mainUrl: String, referer: String, document: Document): List<GomunimeMediaCandidate> {
        val candidates = linkedSetOf<GomunimeMediaCandidate>()
        val raw = normalizedHtml(document)

        document.select("video[src], video source[src], source[src]").forEach { source ->
            normalizeMediaUrl(mainUrl, referer, source.attr("src"))?.let { url ->
                candidates.add(GomunimeMediaCandidate(url, "$sourceName Video", referer, url.isLikelyHls()))
            }
        }

        keyValueMediaRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, referer, it.groupValues[1]) }
            .forEach { url ->
                candidates.add(GomunimeMediaCandidate(url, "$sourceName Stream", referer, url.isLikelyHls()))
            }

        quotedMediaRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, referer, it.groupValues[1]) }
            .forEach { url ->
                candidates.add(GomunimeMediaCandidate(url, "$sourceName Direct", referer, url.isLikelyHls()))
            }

        bareMediaRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, referer, it.value) }
            .forEach { url ->
                candidates.add(GomunimeMediaCandidate(url, "$sourceName Bare", referer, url.isLikelyHls()))
            }

        encodedHttpRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, referer, decodeUrl(it.value)) }
            .forEach { url ->
                candidates.add(GomunimeMediaCandidate(url, "$sourceName Encoded", referer, url.isLikelyHls()))
            }

        return candidates.distinctBy { it.url }
            .filterNot { it.url.isNoiseUrl() }
    }

    private fun extractEpisodeLinks(mainUrl: String, pageUrl: String, document: Document): List<String> {
        val selectors = listOf(
            "a[href*='episode-']",
            "a[href*='-eps-']",
            "a[href*='/episode/']",
            "a[href*='?episode=']",
            "a[href]:contains(Episode)",
            ".eplister a[href]",
            ".episodes a[href]",
            ".episode-list a[href]",
            ".episodelist a[href]",
            ".episode a[href]",
            ".eps a[href]",
            ".daftar-episode a[href]",
            ".list-episode a[href]",
            "[class*=episode] a[href]",
            "[id*=episode] a[href]"
        ).joinToString(", ")

        return document.select(selectors)
            .mapNotNull { absoluteUrl(pageUrl, it.attr("href")) ?: absoluteUrl(mainUrl, it.attr("href")) }
            .filter { it != pageUrl && it.contains("gomunime", true) }
            .filter { episodeNumber(it) != null || it.contains("episode", true) || it.contains("eps", true) }
            .distinct()
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
            raw.startsWith("/") -> absoluteUrl(mainUrl, raw)
            else -> runCatching { URI(referer).resolve(raw).toString() }.getOrNull()
        }
    }

    private fun String.isLikelyHls(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") || lower.contains("playlist") || lower.contains("hls") || lower.contains("master") || lower.contains("btube")
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains("googlevideo.com") ||
            lower.contains("videoplayback")
    }

    private fun String.isDirectVideo(): Boolean {
        val lower = lowercase()
        return lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains("googlevideo.com") ||
            lower.contains("videoplayback")
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
            lower.endsWith(".js") ||
            lower.endsWith(".ico") ||
            lower.endsWith(".svg") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp")
    }

    private fun qualityFromUrl(url: String): Int {
        val text = url.lowercase()
        val itag = Regex("""itag=(\d+)""").find(text)?.groupValues?.getOrNull(1)
        return when {
            itag in setOf("37", "96", "137", "248", "299") -> Qualities.P1080.value
            itag in setOf("22", "59", "136", "247", "298") -> Qualities.P720.value
            itag in setOf("18", "134", "244") -> Qualities.P360.value
            text.contains("1080") -> Qualities.P1080.value
            text.contains("720") -> Qualities.P720.value
            text.contains("480") -> Qualities.P480.value
            text.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
