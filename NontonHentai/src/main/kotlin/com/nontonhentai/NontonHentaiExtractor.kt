package com.nontonhentai

import com.nontonhentai.NontonHentaiUtils.absoluteUrl
import com.nontonhentai.NontonHentaiUtils.cleanText
import com.nontonhentai.NontonHentaiUtils.decodePossibleBase64
import com.nontonhentai.NontonHentaiUtils.decodeUrl
import com.nontonhentai.NontonHentaiUtils.isEpisodeUrl
import com.nontonhentai.NontonHentaiUtils.isPseudoUrl
import com.nontonhentai.NontonHentaiUtils.qualityFromText
import com.nontonhentai.NontonHentaiUtils.videoHeaders
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

object NontonHentaiExtractor {
    private const val MAX_PAGE_HOPS = 6
    private const val MAX_SERVER_HOPS = 8

    private val keyValueMediaRegex = Regex(
        """(?i)[\"']?(?:file|src|source|hls|playlist|video|videoUrl|hlsUrl)[\"']?\s*[=:]\s*['\"]([^'\"]+)['\"]"""
    )
    private val keyValueServerRegex = Regex(
        """(?i)[\"']?(?:src|embed|embed_url|iframe|player)[\"']?\s*[=:]\s*['\"]([^'\"]+)['\"]"""
    )
    private val iframeRegex = Regex("""(?i)<iframe[^>]+src=['\"]([^'\"]+)['\"]""")
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
            app.get(normalizedPage, headers = NontonHentaiUtils.siteHeaders, referer = referer).document
        }.getOrNull() ?: return false

        collectSubtitles(normalizedPage, document, subtitleCallback)

        // Direct media on the current page wins. Do not keep crawling ads/scripts after a video is emitted.
        for (media in extractMedia(normalizedPage, normalizedPage, document)) {
            if (emitMedia(providerName, media.name, media.url, media.referer, seenLinks, emitLink)) return true
        }

        // Prefer source-backed player/ajax entries. Stop on first working HLS/MP4 callback.
        for (server in extractAjaxServers(mainUrl, normalizedPage, document)) {
            if (resolveServer(providerName, server, seenPages, seenLinks, subtitleCallback, emitLink, 0)) return true
        }

        for (server in extractServers(normalizedPage, document)) {
            if (resolveServer(providerName, server, seenPages, seenLinks, subtitleCallback, emitLink, 0)) return true
        }

        document.select(".eplister li a[href], .episodelist li a[href], .episode-list a[href], a[href*='-episode-']")
            .mapNotNull { absoluteUrl(normalizedPage, it.attr("href")) }
            .firstOrNull { isEpisodeUrl(it) && it != normalizedPage }
            ?.let { episodeUrl ->
                if (resolvePage(providerName, mainUrl, episodeUrl, normalizedPage, seenPages, seenLinks, subtitleCallback, callback, depth + 1)) return true
            }

        return runCatching {
            loadExtractor(normalizedPage, normalizedPage, subtitleCallback) { link ->
                if (seenLinks.add(link.url)) callback(link)
            }
        }.getOrDefault(false)
    }

    private suspend fun resolveServer(
        providerName: String,
        server: NontonHentaiServer,
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
            return emitMedia(providerName, server.name, normalizedServer, server.referer, seenLinks, callback)
        }

        // Known CloudStream extractors first. If one emits a link, stop here.
        var extractorEmitted = false
        runCatching {
            loadExtractor(normalizedServer, server.referer, subtitleCallback) { link ->
                if (seenLinks.add(link.url)) {
                    extractorEmitted = true
                    callback(link)
                }
            }
        }
        if (extractorEmitted) return true

        if (!seenPages.add(normalizedServer)) return false
        val embedText = runCatching {
            app.get(normalizedServer, headers = NontonHentaiUtils.videoHeaders(server.referer), referer = server.referer).text
        }.getOrNull() ?: return false

        for (unpackedText in unpackPlayerTexts(embedText)) {
            val embedDocument = Jsoup.parse(unpackedText, normalizedServer)
            collectSubtitles(normalizedServer, embedDocument, subtitleCallback)

            // HAR-backed path: episode iframe -> hentaicop.com/play.php -> var p -> kodeRHS -> JWPlayer sources[].file (.m3u8).
            for (item in extractMedia(normalizedServer, normalizedServer, embedDocument)) {
                if (emitMedia(providerName, item.name.ifBlank { server.name }, item.url, item.referer, seenLinks, callback)) return true
            }

            for (item in extractMediaFromText(normalizedServer, normalizedServer, unpackedText)) {
                if (emitMedia(providerName, item.name.ifBlank { server.name }, item.url, item.referer, seenLinks, callback)) return true
            }
        }

        // Only after no direct media is found, follow a small set of real player/embed servers.
        for (unpackedText in unpackPlayerTexts(embedText)) {
            val embedDocument = Jsoup.parse(unpackedText, normalizedServer)
            val nestedServers = (extractServers(normalizedServer, embedDocument) +
                extractServersFromText(normalizedServer, server.name, unpackedText))
                .filterNot { it.url == normalizedServer || isPseudoUrl(it.url) }
                .filter { isResolvablePlayerUrl(it.url) }
                .distinctBy { it.url }
                .take(4)

            for (nested in nestedServers) {
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
        var emitted = false
        val mediaHeaders = hlsHeaders(referer)
        val directHeaders = videoHeaders(referer)

        if (url.contains(".m3u8", true) || isLikelyHlsCandidate(url)) {
            val links = runCatching {
                generateM3u8(
                    source = providerName,
                    streamUrl = url,
                    referer = "",
                    headers = mediaHeaders
                )
            }.getOrDefault(emptyList())
            links.forEach { link ->
                if (seenLinks.add(link.url)) {
                    emitted = true
                    callback(link)
                }
            }
            if (emitted) return true

            if (seenLinks.add(url)) {
                callback(
                    newExtractorLink(
                        providerName,
                        name.ifBlank { "$providerName HLS" },
                        url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = ""
                        this.quality = qualityFromText(url).let { if (it == Qualities.Unknown.value) Qualities.Unknown.value else it }
                        this.headers = mediaHeaders
                    }
                )
                return true
            }
        }

        if (url.contains(".mp4", true) || url.contains("googlevideo", true) || url.contains("videoplayback", true)) {
            if (seenLinks.add(url)) {
                callback(
                    newExtractorLink(
                        providerName,
                        name.ifBlank { "$providerName MP4" },
                        url,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = qualityFromText(url).let { if (it == Qualities.Unknown.value) Qualities.Unknown.value else it }
                        this.headers = directHeaders
                    }
                )
                return true
            }
        }

        return false
    }

    private fun hlsHeaders(referer: String): Map<String, String> {
        val origin = NontonHentaiUtils.originOf(referer).orEmpty()
        return mapOf(
            "User-Agent" to NontonHentaiUtils.USER_AGENT,
            "Accept" to "*/*",
            "Origin" to origin
        ).filterValues { it.isNotBlank() }
    }

    private suspend fun collectSubtitles(
        pageUrl: String,
        document: Document,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        document.select("track[kind=subtitles], track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val subUrl = absoluteUrl(pageUrl, element.attr("src").ifBlank { element.attr("href") }) ?: return@forEach
            val label = cleanText(element.attr("label").ifBlank { element.text().ifBlank { "Subtitle" } })
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }

    private suspend fun extractAjaxServers(mainUrl: String, pageUrl: String, document: Document): List<NontonHentaiServer> {
        val servers = linkedSetOf<NontonHentaiServer>()
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val actions = listOf("player_ajax", "doo_player_ajax", "load_player", "hentaicop_player_ajax", "nontonhentai_player_ajax")

        document.select("[data-post][data-nume], [data-post][data-number], .dooplay_player_option, .player-option, li[data-post]")
            .forEachIndexed { index, element ->
                val post = element.attr("data-post")
                val nume = element.attr("data-nume").ifBlank { element.attr("data-number") }.ifBlank { "1" }
                val type = element.attr("data-type").ifBlank { "iframe" }
                if (post.isBlank()) return@forEachIndexed
                val name = cleanText(element.text()).ifBlank { "Ajax ${index + 1}" }

                for (action in actions) {
                    val text = runCatching {
                        app.post(
                            ajaxUrl,
                            data = mapOf("action" to action, "post" to post, "nume" to nume, "type" to type),
                            headers = NontonHentaiUtils.siteHeaders,
                            referer = pageUrl
                        ).text
                    }.getOrNull().orEmpty()
                    if (text.isBlank()) continue

                    servers.addAll(extractServersFromText(pageUrl, name, text))
                    extractMediaFromText(pageUrl, pageUrl, text).forEach { media ->
                        servers.add(NontonHentaiServer(media.name.ifBlank { name }, media.url, media.referer))
                    }
                    if (servers.isNotEmpty()) break
                }
            }
        return servers.distinctBy { it.url }
    }

    private fun extractServers(pageUrl: String, document: Document): List<NontonHentaiServer> {
        val servers = linkedSetOf<NontonHentaiServer>()
        val raw = normalizedHtml(document)

        document.select("iframe[src], embed[src]").forEachIndexed { index, iframe ->
            val url = absoluteUrl(pageUrl, iframe.attr("src")) ?: return@forEachIndexed
            if (isPseudoUrl(url) || !isResolvablePlayerUrl(url)) return@forEachIndexed
            val name = cleanText(iframe.attr("title")).ifBlank { cleanText(iframe.parent()?.text()).ifBlank { "Server ${index + 1}" } }
            servers.add(NontonHentaiServer(name, url, pageUrl))
        }

        document.select("select.mirror option, select option, .mirror option").forEachIndexed { index, option ->
            val value = option.attr("value").ifBlank { option.attr("data-src") }.ifBlank { option.attr("data-embed") }
            val decoded = decodePossibleBase64(value) ?: return@forEachIndexed
            val iframeUrl = iframeRegex.find(decoded)?.groupValues?.getOrNull(1)
            val candidate = absoluteUrl(pageUrl, iframeUrl ?: decoded) ?: return@forEachIndexed
            if (isPseudoUrl(candidate) || !isResolvablePlayerUrl(candidate)) return@forEachIndexed
            val name = cleanText(option.text()).ifBlank { "Mirror ${index + 1}" }
            servers.add(NontonHentaiServer(name, candidate, pageUrl))
        }

        document.select("[data-src], [data-url], [data-embed], [data-iframe], [data-link], [data-video]").forEachIndexed { index, element ->
            val value = element.attr("data-src")
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-link") }
                .ifBlank { element.attr("data-video") }
            val decoded = decodePossibleBase64(value) ?: value
            val iframeUrl = iframeRegex.find(decoded)?.groupValues?.getOrNull(1)
            val url = absoluteUrl(pageUrl, iframeUrl ?: decoded) ?: return@forEachIndexed
            if (isPseudoUrl(url) || !isResolvablePlayerUrl(url)) return@forEachIndexed
            val name = cleanText(element.text()).ifBlank { cleanText(element.attr("data-name")).ifBlank { "Server ${index + 1}" } }
            servers.add(NontonHentaiServer(name, url, pageUrl))
        }

        extractServersFromText(pageUrl, "Script", raw).forEach { servers.add(it) }
        return servers.distinctBy { it.url }.take(8)
    }

    private fun extractServersFromText(pageUrl: String, fallbackName: String, text: String): List<NontonHentaiServer> {
        val servers = linkedSetOf<NontonHentaiServer>()
        val raw = normalizedHtml(text)

        iframeRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEachIndexed
            if (!isPseudoUrl(url) && isResolvablePlayerUrl(url)) servers.add(NontonHentaiServer("$fallbackName ${index + 1}", url, pageUrl))
        }

        keyValueServerRegex.findAll(raw).forEachIndexed { index, match ->
            val decoded = decodePossibleBase64(match.groupValues[1]) ?: match.groupValues[1]
            val iframeUrl = iframeRegex.find(decoded)?.groupValues?.getOrNull(1)
            val url = absoluteUrl(pageUrl, iframeUrl ?: decoded) ?: return@forEachIndexed
            if (!isPseudoUrl(url) && !isDirectMedia(url) && isResolvablePlayerUrl(url)) {
                servers.add(NontonHentaiServer("$fallbackName ${index + 1}", url, pageUrl))
            }
        }

        encodedHttpRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, decodeUrl(match.value)) ?: return@forEachIndexed
            if (!isPseudoUrl(url) && !isDirectMedia(url) && isResolvablePlayerUrl(url)) servers.add(NontonHentaiServer("Encoded ${index + 1}", url, pageUrl))
        }

        return servers.distinctBy { it.url }.take(8)
    }

    private fun extractMedia(pageUrl: String, referer: String, document: Document): List<NontonHentaiMedia> {
        val media = linkedSetOf<NontonHentaiMedia>()
        val raw = normalizedHtml(document)

        document.select("video[src], video source[src], source[src]").forEachIndexed { index, source ->
            val url = absoluteUrl(pageUrl, source.attr("src")) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(NontonHentaiMedia("Source ${index + 1}", url, referer))
        }

        media.addAll(extractMediaFromText(pageUrl, referer, raw))
        return media
            .filter { isDirectMedia(it.url) || isLikelyHlsCandidate(it.url) }
            .distinctBy { it.url }
    }

    private fun extractMediaFromText(pageUrl: String, referer: String, text: String): List<NontonHentaiMedia> {
        val media = linkedSetOf<NontonHentaiMedia>()
        val raw = normalizedHtml(text)

        keyValueMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val decoded = decodePossibleBase64(match.groupValues[1]) ?: match.groupValues[1]
            val iframeUrl = iframeRegex.find(decoded)?.groupValues?.getOrNull(1)
            val url = absoluteUrl(pageUrl, iframeUrl ?: decoded) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(NontonHentaiMedia("File ${index + 1}", url, referer))
        }

        quotedMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(NontonHentaiMedia("Media ${index + 1}", url, referer))
        }

        bareMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.value) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(NontonHentaiMedia("Direct ${index + 1}", url, referer))
        }

        encodedHttpRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, decodeUrl(match.value)) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(NontonHentaiMedia("Encoded ${index + 1}", url, referer))
        }

        return media.distinctBy { it.url }
    }

    private fun unpackPlayerTexts(text: String): List<String> {
        val texts = linkedSetOf(normalizedHtml(text))

        texts.toList().forEach { source ->
            packedPageRegex.findAll(source).forEach { match ->
                decodeReversedBase64(match.groupValues[1])?.let { decodedPage ->
                    texts.add(normalizedHtml(decodedPage))
                }
            }
        }

        texts.toList().forEach { source ->
            rhsScriptRegex.findAll(source).forEach { match ->
                decodeBase64(match.groupValues[1])?.let { decodedScript ->
                    texts.add(normalizedHtml(decodedScript))
                }
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

    private fun isResolvablePlayerUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (isPseudoUrl(lower)) return false
        if (isDirectMedia(lower) || isLikelyHlsCandidate(lower)) return true
        return lower.contains("hentaicop.com/play.php") ||
            lower.contains("/play.php") ||
            lower.contains("nontonhentai.net/play.php") ||
            lower.contains("hepidrive") ||
            lower.contains("/embed") ||
            lower.contains("/player") ||
            lower.contains("iframe")
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
}
