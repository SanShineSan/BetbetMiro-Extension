package com.dgs

import com.dgs.DGSUtils.absoluteUrl
import com.dgs.DGSUtils.cleanText
import com.dgs.DGSUtils.decodePossibleBase64
import com.dgs.DGSUtils.decodeUrl
import com.dgs.DGSUtils.isPseudoUrl
import com.dgs.DGSUtils.qualityFromText
import com.dgs.DGSUtils.videoHeaders
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object DGSExtractor {
    private const val MAX_PAGE_HOPS = 4
    private const val MAX_SERVER_HOPS = 4

    private val iframeRegex = Regex("""(?i)<iframe[^>]+src=['\"]([^'\"]+)['\"]""")
    private val keyValueMediaRegex = Regex(
        """(?i)[\"']?(?:file|src|source|sources|url|hls|playlist|video|videoUrl|hlsUrl|mp4|embed_url)[\"']?\s*[:=]\s*[\"']([^\"']+)[\"']"""
    )
    private val quotedMediaRegex = Regex(
        """(?i)['\"]((?:https?:)?//[^'\"<>\s\\]+?(?:\.m3u8|\.mp4|googlevideo\.com/[^'\"<>\s\\]+|videoplayback[^'\"<>\s\\]*)(?:\?[^'\"<>\s\\]*)?)['\"]"""
    )
    private val bareMediaRegex = Regex(
        """(?i)(?:https?:)?//[^\s'\"<>\\]+?(?:\.m3u8|\.mp4|googlevideo\.com/[^\s'\"<>\\]+|videoplayback[^\s'\"<>\\]*)(?:\?[^\s'\"<>\\]*)?"""
    )
    private val encodedHttpRegex = Regex("""https?%3A%2F%2F[^\s'\"<>]+""", RegexOption.IGNORE_CASE)
    private val packedPageRegex = Regex("""(?s)var\s+p\s*=\s*[\"']([^\"']+)[\"']""")
    private val rhsScriptRegex = Regex("""(?s)var\s+kodeRHS\s*=\s*[\"']([^\"']+)[\"']""")

    suspend fun loadLinks(
        providerName: String,
        mainUrl: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isPseudoUrl(data)) return false
        val seenLinks = linkedSetOf<String>()
        val seenPages = linkedSetOf<String>()
        return resolvePage(providerName, mainUrl, data, data, seenPages, seenLinks, subtitleCallback, callback, 0)
    }

    private suspend fun resolvePage(
        providerName: String,
        mainUrl: String,
        pageUrl: String,
        referer: String,
        seenPages: MutableSet<String>,
        seenLinks: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        if (depth > MAX_PAGE_HOPS) return false
        val normalizedPage = absoluteUrl(referer, pageUrl) ?: return false
        if (isPseudoUrl(normalizedPage) || !seenPages.add(normalizedPage)) return false

        val emitLink: (ExtractorLink) -> Unit = { link ->
            if (seenLinks.add(link.url)) callback(link)
        }

        val document = runCatching {
            app.get(normalizedPage, headers = DGSUtils.playerHeaders(referer), referer = referer).document
        }.getOrNull() ?: return false

        collectSubtitles(normalizedPage, document, subtitleCallback)

        extractMedia(normalizedPage, normalizedPage, document).forEach { media ->
            if (emitMedia(providerName, media.name, media.url, media.referer, seenLinks, emitLink)) return true
        }

        extractServers(normalizedPage, document)
            .filter { isLikelyPlayerServer(it.url) }
            .distinctBy { it.url }
            .forEach { server ->
                if (resolveServer(providerName, server, seenPages, seenLinks, subtitleCallback, emitLink, 0)) return true
            }

        val fallback = runCatching {
            loadExtractor(normalizedPage, normalizedPage, subtitleCallback) { link ->
                if (seenLinks.add(link.url)) callback(link)
            }
        }.getOrDefault(false)
        if (fallback) return true

        return false
    }

    private suspend fun resolveServer(
        providerName: String,
        server: DGSServer,
        seenPages: MutableSet<String>,
        seenLinks: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        if (depth > MAX_SERVER_HOPS) return false
        val normalizedServer = absoluteUrl(server.referer, server.url) ?: return false
        if (isPseudoUrl(normalizedServer)) return false

        if (isDirectMedia(normalizedServer) || isLikelyHlsCandidate(normalizedServer)) {
            if (emitMedia(providerName, server.name, normalizedServer, server.referer, seenLinks, callback)) return true
        }

        runCatching {
            loadExtractor(normalizedServer, server.referer, subtitleCallback) { link ->
                if (seenLinks.add(link.url)) callback(link)
            }
        }
        if (seenLinks.isNotEmpty()) return true

        if (!seenPages.add(normalizedServer)) return false
        val embedText = runCatching {
            app.get(normalizedServer, headers = DGSUtils.playerHeaders(server.referer), referer = server.referer).text
        }.getOrNull() ?: return false

        unpackPlayerTexts(embedText).forEach { unpackedText ->
            val embedDocument = Jsoup.parse(unpackedText, normalizedServer)
            collectSubtitles(normalizedServer, embedDocument, subtitleCallback)

            extractMedia(normalizedServer, normalizedServer, embedDocument).forEach { item ->
                if (emitMedia(providerName, item.name.ifBlank { server.name }, item.url, item.referer, seenLinks, callback)) return true
            }

            extractMediaFromText(normalizedServer, normalizedServer, unpackedText).forEach { item ->
                if (emitMedia(providerName, item.name.ifBlank { server.name }, item.url, item.referer, seenLinks, callback)) return true
            }

            extractServers(normalizedServer, embedDocument)
                .filter { it.url != normalizedServer && isLikelyPlayerServer(it.url) }
                .distinctBy { it.url }
                .forEach { nested ->
                    if (resolveServer(providerName, nested, seenPages, seenLinks, subtitleCallback, callback, depth + 1)) return true
                }

            extractServersFromText(normalizedServer, server.name, unpackedText)
                .filter { it.url != normalizedServer && isLikelyPlayerServer(it.url) }
                .distinctBy { it.url }
                .forEach { nested ->
                    if (resolveServer(providerName, nested, seenPages, seenLinks, subtitleCallback, callback, depth + 1)) return true
                }
        }

        return false
    }

    private suspend fun emitMedia(
        providerName: String,
        name: String,
        url: String,
        referer: String,
        seenLinks: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isPseudoUrl(url)) return false
        val headers = videoHeaders(referer)

        if (url.contains(".m3u8", true) || isLikelyHlsCandidate(url)) {
            val generated = runCatching {
                generateM3u8(
                    source = providerName,
                    streamUrl = url,
                    referer = referer,
                    headers = headers
                )
            }.getOrDefault(emptyList())

            generated.forEach { link ->
                if (seenLinks.add(link.url)) {
                    callback(link)
                    return true
                }
            }

            if (seenLinks.add(url)) {
                callback(
                    newExtractorLink(providerName, name.ifBlank { "$providerName HLS" }, url, ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.quality = qualityFromText(url)
                        this.headers = headers
                    }
                )
                return true
            }
        }

        if (url.contains(".mp4", true) || url.contains("googlevideo", true) || url.contains("videoplayback", true)) {
            if (seenLinks.add(url)) {
                callback(
                    newExtractorLink(providerName, name.ifBlank { "$providerName MP4" }, url, ExtractorLinkType.VIDEO) {
                        this.referer = referer
                        this.quality = qualityFromText(url).let { if (it == Qualities.Unknown.value) Qualities.Unknown.value else it }
                        this.headers = headers
                    }
                )
                return true
            }
        }

        return false
    }

    private suspend fun collectSubtitles(pageUrl: String, document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[kind=subtitles], track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val subUrl = absoluteUrl(pageUrl, element.attr("src").ifBlank { element.attr("href") }) ?: return@forEach
            val label = cleanText(element.attr("label").ifBlank { element.text().ifBlank { "Subtitle" } })
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }

    private fun extractServers(pageUrl: String, document: Document): List<DGSServer> {
        val servers = linkedSetOf<DGSServer>()
        val raw = normalizedHtml(document)

        document.select("iframe[src], embed[src]").forEachIndexed { index, iframe ->
            val url = absoluteUrl(pageUrl, iframe.attr("src")) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) servers.add(DGSServer(cleanText(iframe.attr("title")).ifBlank { "Server ${index + 1}" }, url, pageUrl))
        }

        document.select("[data-src], [data-url], [data-embed], [data-iframe], [data-link], [data-video], [data-file]").forEachIndexed { index, element ->
            val value = element.attr("data-src")
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-link") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
            val decoded = decodePossibleBase64(value) ?: value
            val iframeUrl = iframeRegex.find(decoded)?.groupValues?.getOrNull(1)
            val url = absoluteUrl(pageUrl, iframeUrl ?: decoded) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) servers.add(DGSServer(cleanText(element.text()).ifBlank { "Server ${index + 1}" }, url, pageUrl))
        }

        extractServersFromText(pageUrl, "Script", raw).forEach { servers.add(it) }
        return servers.distinctBy { it.url }
    }

    private fun extractServersFromText(pageUrl: String, fallbackName: String, text: String): List<DGSServer> {
        val servers = linkedSetOf<DGSServer>()
        val raw = normalizedHtml(text)

        iframeRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) servers.add(DGSServer("$fallbackName ${index + 1}", url, pageUrl))
        }

        encodedHttpRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, decodeUrl(match.value)) ?: return@forEachIndexed
            if (!isPseudoUrl(url) && !isDirectMedia(url)) servers.add(DGSServer("Encoded ${index + 1}", url, pageUrl))
        }

        return servers.distinctBy { it.url }
    }

    private fun extractMedia(pageUrl: String, referer: String, document: Document): List<DGSMedia> {
        val media = linkedSetOf<DGSMedia>()
        val raw = normalizedHtml(document)

        document.select("video[src], video source[src], source[src]").forEachIndexed { index, source ->
            val url = absoluteUrl(pageUrl, source.attr("src")) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(DGSMedia("Source ${index + 1}", url, referer))
        }

        media.addAll(extractMediaFromText(pageUrl, referer, raw))
        return media
            .filter { isDirectMedia(it.url) || isLikelyHlsCandidate(it.url) }
            .distinctBy { it.url }
    }

    private fun extractMediaFromText(pageUrl: String, referer: String, text: String): List<DGSMedia> {
        val media = linkedSetOf<DGSMedia>()
        val raw = normalizedHtml(text)

        keyValueMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val decoded = decodePossibleBase64(match.groupValues[1]) ?: match.groupValues[1]
            val iframeUrl = iframeRegex.find(decoded)?.groupValues?.getOrNull(1)
            val url = absoluteUrl(pageUrl, iframeUrl ?: decoded) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(DGSMedia("File ${index + 1}", url, referer))
        }

        quotedMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(DGSMedia("Media ${index + 1}", url, referer))
        }

        bareMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.value) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(DGSMedia("Direct ${index + 1}", url, referer))
        }

        encodedHttpRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, decodeUrl(match.value)) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(DGSMedia("Encoded ${index + 1}", url, referer))
        }

        return media.distinctBy { it.url }
    }

    private fun unpackPlayerTexts(text: String): List<String> {
        val texts = linkedSetOf(normalizedHtml(text))

        texts.toList().forEach { source ->
            packedPageRegex.findAll(source).forEach { match ->
                decodeReversedBase64(match.groupValues[1])?.let { decodedPage -> texts.add(normalizedHtml(decodedPage)) }
            }
        }

        texts.toList().forEach { source ->
            rhsScriptRegex.findAll(source).forEach { match ->
                decodeBase64(match.groupValues[1])?.let { decodedScript -> texts.add(normalizedHtml(decodedScript)) }
            }
        }

        return texts.toList()
    }

    private fun decodeReversedBase64(value: String): String? {
        val reversed = value.trim().reversed()
        return decodeBase64(reversed)?.let { decodeUrl(it) }
    }

    private fun decodeBase64(value: String): String? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        return runCatching {
            val padded = raw.padEnd(raw.length + ((4 - raw.length % 4) % 4), '=')
            String(java.util.Base64.getDecoder().decode(padded))
        }.getOrNull()
    }

    private fun normalizedHtml(document: Document): String = normalizedHtml(document.html())

    private fun normalizedHtml(text: String): String {
        return text
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
            .replace("\\\"", "\"")
    }

    private fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase()
        if (isPseudoUrl(lower)) return false
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains("googlevideo.com") || lower.contains("videoplayback")
    }

    private fun isLikelyHlsCandidate(url: String): Boolean {
        val lower = url.lowercase()
        if (isPseudoUrl(lower)) return false
        if (lower.contains(".mp4") || lower.contains("googlevideo") || lower.contains("videoplayback")) return false
        return lower.contains("m3u8") || lower.contains("playlist") || lower.contains("hls") || lower.contains("master")
    }

    private fun isLikelyPlayerServer(url: String): Boolean {
        val lower = url.lowercase()
        if (isPseudoUrl(lower)) return false
        if (isDirectMedia(lower) || isLikelyHlsCandidate(lower)) return true
        if (listOf("ads", "analytics", "doubleclick", "googletag", "facebook", "twitter", "popunder", "banner").any { lower.contains(it) }) return false
        return listOf("embed", "player", "iframe", "stream", "video", "media", "file", "source").any { lower.contains(it) }
    }
}
