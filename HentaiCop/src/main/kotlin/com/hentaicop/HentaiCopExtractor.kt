package com.hentaicop

import com.hentaicop.HentaiCopUtils.absoluteUrl
import com.hentaicop.HentaiCopUtils.cleanText
import com.hentaicop.HentaiCopUtils.decodePossibleBase64
import com.hentaicop.HentaiCopUtils.decodeUrl
import com.hentaicop.HentaiCopUtils.isEpisodeUrl
import com.hentaicop.HentaiCopUtils.isPseudoUrl
import com.hentaicop.HentaiCopUtils.qualityFromText
import com.hentaicop.HentaiCopUtils.videoHeaders
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

object HentaiCopExtractor {
    private const val MAX_PAGE_HOPS = 6
    private const val MAX_SERVER_HOPS = 8

    private val keyValueMediaRegex = Regex(
        """(?i)[\"']?(?:file|src|source|url|hls|playlist|video|videoUrl|hlsUrl|embed_url)[\"']?\s*[=:]\s*['\"]([^'\"]+)['\"]"""
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

        var found = false
        val emitLink: (ExtractorLink) -> Unit = { link ->
            if (seenLinks.add(link.url)) callback(link)
        }

        val document = runCatching {
            app.get(normalizedPage, headers = HentaiCopUtils.siteHeaders, referer = referer).document
        }.getOrNull() ?: return false

        collectSubtitles(normalizedPage, document, subtitleCallback)

        extractMedia(normalizedPage, normalizedPage, document).forEach { media ->
            if (emitMedia(providerName, mainUrl, media.name, media.url, media.referer, seenLinks, emitLink)) found = true
        }

        extractAjaxServers(mainUrl, normalizedPage, document).forEach { server ->
            if (resolveServer(providerName, server, seenPages, seenLinks, subtitleCallback, emitLink, 0)) found = true
        }

        extractServers(normalizedPage, document).forEach { server ->
            if (resolveServer(providerName, server, seenPages, seenLinks, subtitleCallback, emitLink, 0)) found = true
        }

        if (!found) {
            document.select(".eplister li a[href], .episodelist li a[href], .episode-list a[href], a[href*='-episode-']")
                .mapNotNull { absoluteUrl(normalizedPage, it.attr("href")) }
                .firstOrNull { isEpisodeUrl(it) && it != normalizedPage }
                ?.let { episodeUrl ->
                    if (resolvePage(providerName, mainUrl, episodeUrl, normalizedPage, seenPages, seenLinks, subtitleCallback, callback, depth + 1)) found = true
                }
        }

        if (!found) {
            val fallback = runCatching {
                loadExtractor(normalizedPage, normalizedPage, subtitleCallback) { link ->
                    if (seenLinks.add(link.url)) {
                        found = true
                        callback(link)
                    }
                }
            }.getOrDefault(false)
            found = found || fallback
        }

        return found
    }

    private suspend fun resolveServer(
        providerName: String,
        server: HentaiCopServer,
        seenPages: MutableSet<String>,
        seenLinks: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        if (depth > MAX_SERVER_HOPS) return false
        var serverFound = false
        val normalizedServer = absoluteUrl(server.referer, server.url) ?: return false
        if (isPseudoUrl(normalizedServer)) return false

        if (isDirectMedia(normalizedServer) || isLikelyHlsCandidate(normalizedServer)) {
            val emitted = emitMedia(providerName, HentaiCopSeeds.MAIN_URL, server.name, normalizedServer, server.referer, seenLinks, callback)
            if (emitted) return true
        }

        runCatching {
            loadExtractor(normalizedServer, server.referer, subtitleCallback) { link ->
                if (seenLinks.add(link.url)) {
                    serverFound = true
                    callback(link)
                }
            }
        }

        if (!seenPages.add(normalizedServer)) return serverFound
        val embedText = runCatching {
            app.get(normalizedServer, headers = HentaiCopUtils.videoHeaders(server.referer), referer = server.referer).text
        }.getOrNull() ?: return serverFound
        unpackPlayerTexts(embedText).forEach { unpackedText ->
            val embedDocument = Jsoup.parse(unpackedText, normalizedServer)

            collectSubtitles(normalizedServer, embedDocument, subtitleCallback)
            extractMedia(normalizedServer, normalizedServer, embedDocument).forEach { item ->
                val emitted = emitMedia(providerName, HentaiCopSeeds.MAIN_URL, item.name.ifBlank { server.name }, item.url, item.referer, seenLinks, callback)
                if (emitted) serverFound = true
            }

            extractMediaFromText(normalizedServer, normalizedServer, unpackedText).forEach { item ->
                val emitted = emitMedia(providerName, HentaiCopSeeds.MAIN_URL, item.name.ifBlank { server.name }, item.url, item.referer, seenLinks, callback)
                if (emitted) serverFound = true
            }

            extractServers(normalizedServer, embedDocument)
                .filterNot { it.url == normalizedServer || isPseudoUrl(it.url) }
                .distinctBy { it.url }
                .forEach { nested ->
                    if (resolveServer(providerName, nested, seenPages, seenLinks, subtitleCallback, callback, depth + 1)) serverFound = true
                }

            extractServersFromText(normalizedServer, server.name, unpackedText)
                .filterNot { it.url == normalizedServer || isPseudoUrl(it.url) }
                .distinctBy { it.url }
                .forEach { nested ->
                    if (resolveServer(providerName, nested, seenPages, seenLinks, subtitleCallback, callback, depth + 1)) serverFound = true
                }
        }

        return serverFound
    }

    private suspend fun emitMedia(
        providerName: String,
        mainUrl: String,
        name: String,
        url: String,
        referer: String,
        seenLinks: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isPseudoUrl(url)) return false
        var emitted = false
        val mediaHeaders = hlsHeaders(mainUrl)
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

    private fun hlsHeaders(mainUrl: String): Map<String, String> {
        val origin = HentaiCopUtils.originOf(mainUrl).orEmpty()
        return mapOf(
            "User-Agent" to HentaiCopUtils.USER_AGENT,
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

    private suspend fun extractAjaxServers(mainUrl: String, pageUrl: String, document: Document): List<HentaiCopServer> {
        val servers = linkedSetOf<HentaiCopServer>()
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val actions = listOf("player_ajax", "doo_player_ajax", "load_player", "hentaicop_player_ajax")

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
                            headers = HentaiCopUtils.siteHeaders,
                            referer = pageUrl
                        ).text
                    }.getOrNull().orEmpty()
                    if (text.isBlank()) continue

                    servers.addAll(extractServersFromText(pageUrl, name, text))
                    extractMediaFromText(pageUrl, pageUrl, text).forEach { media ->
                        servers.add(HentaiCopServer(media.name.ifBlank { name }, media.url, media.referer))
                    }
                    if (servers.isNotEmpty()) break
                }
            }
        return servers.distinctBy { it.url }
    }

    private fun extractServers(pageUrl: String, document: Document): List<HentaiCopServer> {
        val servers = linkedSetOf<HentaiCopServer>()
        val raw = normalizedHtml(document)

        document.select("iframe[src], embed[src]").forEachIndexed { index, iframe ->
            val url = absoluteUrl(pageUrl, iframe.attr("src")) ?: return@forEachIndexed
            if (isPseudoUrl(url)) return@forEachIndexed
            val name = cleanText(iframe.attr("title")).ifBlank { "Server ${index + 1}" }
            servers.add(HentaiCopServer(name, url, pageUrl))
        }

        document.select("select.mirror option, select option, .mirror option").forEachIndexed { index, option ->
            val value = option.attr("value").ifBlank { option.attr("data-src") }.ifBlank { option.attr("data-embed") }
            val decoded = decodePossibleBase64(value) ?: return@forEachIndexed
            val iframeUrl = iframeRegex.find(decoded)?.groupValues?.getOrNull(1)
            val candidate = absoluteUrl(pageUrl, iframeUrl ?: decoded) ?: return@forEachIndexed
            if (isPseudoUrl(candidate)) return@forEachIndexed
            val name = cleanText(option.text()).ifBlank { "Mirror ${index + 1}" }
            servers.add(HentaiCopServer(name, candidate, pageUrl))
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
            if (isPseudoUrl(url)) return@forEachIndexed
            val name = cleanText(element.text()).ifBlank { cleanText(element.attr("data-name")).ifBlank { "Server ${index + 1}" } }
            servers.add(HentaiCopServer(name, url, pageUrl))
        }

        extractServersFromText(pageUrl, "Script", raw).forEach { servers.add(it) }
        return servers.distinctBy { it.url }
    }

    private fun extractServersFromText(pageUrl: String, fallbackName: String, text: String): List<HentaiCopServer> {
        val servers = linkedSetOf<HentaiCopServer>()
        val raw = normalizedHtml(text)

        iframeRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) servers.add(HentaiCopServer("$fallbackName ${index + 1}", url, pageUrl))
        }

        keyValueMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEachIndexed
            if (!isPseudoUrl(url) && url.startsWith("http", true) && !isDirectMedia(url)) {
                servers.add(HentaiCopServer("$fallbackName ${index + 1}", url, pageUrl))
            }
        }

        encodedHttpRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, decodeUrl(match.value)) ?: return@forEachIndexed
            if (!isPseudoUrl(url) && !isDirectMedia(url)) servers.add(HentaiCopServer("Encoded ${index + 1}", url, pageUrl))
        }

        return servers.distinctBy { it.url }
    }

    private fun extractMedia(pageUrl: String, referer: String, document: Document): List<HentaiCopMedia> {
        val media = linkedSetOf<HentaiCopMedia>()
        val raw = normalizedHtml(document)

        document.select("video[src], video source[src], source[src]").forEachIndexed { index, source ->
            val url = absoluteUrl(pageUrl, source.attr("src")) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(HentaiCopMedia("Source ${index + 1}", url, referer))
        }

        media.addAll(extractMediaFromText(pageUrl, referer, raw))
        return media
            .filter { isDirectMedia(it.url) || isLikelyHlsCandidate(it.url) }
            .distinctBy { it.url }
    }

    private fun extractMediaFromText(pageUrl: String, referer: String, text: String): List<HentaiCopMedia> {
        val media = linkedSetOf<HentaiCopMedia>()
        val raw = normalizedHtml(text)

        keyValueMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val decoded = decodePossibleBase64(match.groupValues[1]) ?: match.groupValues[1]
            val iframeUrl = iframeRegex.find(decoded)?.groupValues?.getOrNull(1)
            val url = absoluteUrl(pageUrl, iframeUrl ?: decoded) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(HentaiCopMedia("File ${index + 1}", url, referer))
        }

        quotedMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(HentaiCopMedia("Media ${index + 1}", url, referer))
        }

        bareMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.value) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(HentaiCopMedia("Direct ${index + 1}", url, referer))
        }

        encodedHttpRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, decodeUrl(match.value)) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(HentaiCopMedia("Encoded ${index + 1}", url, referer))
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
