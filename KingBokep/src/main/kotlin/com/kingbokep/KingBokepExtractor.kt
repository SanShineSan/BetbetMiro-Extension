package com.kingbokep

import com.kingbokep.KingBokepUtils.absoluteUrl
import com.kingbokep.KingBokepUtils.cleanText
import com.kingbokep.KingBokepUtils.decodeLoadData
import com.kingbokep.KingBokepUtils.decodeUrl
import com.kingbokep.KingBokepUtils.isPseudoUrl
import com.kingbokep.KingBokepUtils.qualityFromText
import com.kingbokep.KingBokepUtils.videoHeaders
import com.lagradost.api.Log
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

object KingBokepExtractor {
    private const val MAX_PAGE_HOPS = 2
    private const val MAX_EMBEDS_PER_PAGE = 6

    private val keyValueMediaRegex = Regex(
        """(?is)(?:data-playlist|playlist|hlsUrl|hls|file|source|src|url)\s*[:=]\s*['\"]([^'\"]+)['\"]"""
    )
    private val quotedMediaRegex = Regex(
        """(?is)['\"]((?:https?:)?//[^'\"<>\s]+?(?:\.m3u8|\.mp4|videoplayback|googlevideo)[^'\"<>\s]*)['\"]"""
    )
    private val bareMediaRegex = Regex(
        """(?is)(?:https?:)?//[^\s'\"<>]+?(?:\.m3u8|\.mp4|videoplayback|googlevideo)[^\s'\"<>]*"""
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
        val startUrl = resolveDataToUrl(mainUrl, data) ?: return false
        val seenPages = linkedSetOf<String>()
        val seenLinks = linkedSetOf<String>()
        return resolvePage(providerName, mainUrl, startUrl, subtitleCallback, callback, seenPages, seenLinks, 0)
    }

    private suspend fun resolvePage(
        providerName: String,
        mainUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        seenPages: MutableSet<String>,
        seenLinks: MutableSet<String>,
        depth: Int
    ): Boolean {
        if (depth > MAX_PAGE_HOPS || !seenPages.add(pageUrl)) return false

        val document = try {
            app.get(pageUrl, headers = KingBokepUtils.siteHeaders, referer = if (depth == 0) mainUrl else pageUrl).document
        } catch (e: Throwable) {
            Log.e("KingBokep", "Failed to open page: ${e.message}")
            return false
        }

        collectSubtitles(pageUrl, document, subtitleCallback)

        var found = false
        extractServers(pageUrl, document).forEach { server ->
            val serverFound = emitMedia(
                providerName = providerName,
                label = server.name,
                url = server.url,
                referer = server.referer,
                hlsCandidate = server.hlsCandidate,
                subtitleCallback = subtitleCallback,
                callback = callback,
                seenLinks = seenLinks
            )
            if (serverFound) found = true
        }
        if (found) return true

        extractEmbeds(pageUrl, document).take(MAX_EMBEDS_PER_PAGE).forEach { embed ->
            if (seenLinks.add("extractor:$embed")) {
                try {
                    var emitted = false
                    loadExtractor(embed, pageUrl, subtitleCallback) { link ->
                        if (seenLinks.add("out:${link.url}")) {
                            emitted = true
                            found = true
                            callback(link)
                        }
                    }
                    if (!emitted && depth < MAX_PAGE_HOPS) {
                        val nested = resolvePage(providerName, mainUrl, embed, subtitleCallback, callback, seenPages, seenLinks, depth + 1)
                        if (nested) found = true
                    }
                } catch (e: Throwable) {
                    Log.e("KingBokep", "Embed failed: ${e.message}")
                }
            }
        }
        if (found) return true

        try {
            loadExtractor(pageUrl, pageUrl, subtitleCallback) { link ->
                if (seenLinks.add("out:${link.url}")) {
                    found = true
                    callback(link)
                }
            }
        } catch (_: Throwable) {
        }

        return found
    }

    private fun extractServers(pageUrl: String, document: Document): List<KingBokepServer> {
        val servers = linkedSetOf<KingBokepServer>()

        val player = document.selectFirst("video#bokep-player, video[id*=player], video")
        player?.attr("data-playlist")?.let { value ->
            absoluteUrl(pageUrl, value)?.takeIf { !isPseudoUrl(it) }?.let {
                servers.add(KingBokepServer("KingBokep HLS", it, pageUrl, hlsCandidate = true))
            }
        }
        player?.attr("src")?.let { value ->
            absoluteUrl(pageUrl, value)?.takeIf { !isPseudoUrl(it) }?.let {
                servers.add(KingBokepServer("KingBokep Video", it, pageUrl, hlsCandidate = it.contains("m3u8", true)))
            }
        }

        document.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player:stream], meta[itemprop=contentUrl], link[itemprop=contentUrl]").forEach { element ->
            absoluteUrl(pageUrl, element.attr("content").ifBlank { element.attr("href") })?.takeIf { !isPseudoUrl(it) }?.let { url ->
                servers.add(KingBokepServer("KingBokep HLS", url, pageUrl, hlsCandidate = url.contains("m3u8", true)))
            }
        }

        document.select("video source[src], source[src], a[href*='.m3u8'], a[href*='.mp4']").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            absoluteUrl(pageUrl, raw)?.takeIf { !isPseudoUrl(it) }?.let { url ->
                val label = cleanText(element.attr("label").ifBlank { element.text().ifBlank { "KingBokep" } })
                servers.add(KingBokepServer(label, url, pageUrl, hlsCandidate = url.contains("m3u8", true)))
            }
        }

        val rawHtml = normalizedHtml(document)

        keyValueMediaRegex.findAll(rawHtml).forEach { match ->
            val key = match.value.substringBefore(":").substringBefore("=").lowercase()
            val url = absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            if (isPseudoUrl(url)) return@forEach
            val hls = key.contains("playlist") || key.contains("hls") || url.contains("m3u8", true)
            servers.add(KingBokepServer(if (hls) "KingBokep HLS" else "KingBokep Candidate", url, pageUrl, hlsCandidate = hls))
        }

        quotedMediaRegex.findAll(rawHtml)
            .mapNotNull { absoluteUrl(pageUrl, it.groupValues[1]) }
            .filterNot { isPseudoUrl(it) }
            .forEach { url -> servers.add(KingBokepServer("KingBokep Direct", url, pageUrl, hlsCandidate = url.contains("m3u8", true))) }

        bareMediaRegex.findAll(rawHtml)
            .mapNotNull { absoluteUrl(pageUrl, it.value) }
            .filterNot { isPseudoUrl(it) }
            .forEach { url -> servers.add(KingBokepServer("KingBokep Direct", url, pageUrl, hlsCandidate = url.contains("m3u8", true))) }

        encodedHttpRegex.findAll(rawHtml)
            .mapNotNull { absoluteUrl(pageUrl, decodeUrl(it.value)) }
            .filterNot { isPseudoUrl(it) }
            .forEach { url -> servers.add(KingBokepServer("KingBokep Encoded", url, pageUrl, hlsCandidate = url.contains("m3u8", true))) }

        return servers.distinctBy { it.url.substringBefore("#") }
    }

    private fun extractEmbeds(pageUrl: String, document: Document): List<String> {
        val embeds = linkedSetOf<String>()
        document.select("iframe[src], iframe[data-src], embed[src], div[data-embed], button[data-embed], a[data-embed]").forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("href") }
            absoluteUrl(pageUrl, raw)?.takeIf { !isPseudoUrl(it) }?.let { embeds.add(it) }
        }
        return embeds.distinct()
    }

    private suspend fun emitMedia(
        providerName: String,
        label: String,
        url: String,
        referer: String,
        hlsCandidate: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        seenLinks: MutableSet<String>
    ): Boolean {
        if (isPseudoUrl(url) || !seenLinks.add("candidate:$url")) return false

        if (url.contains(".mp4", true) || url.contains("googlevideo", true) || url.contains("videoplayback", true)) {
            val key = "out:$url"
            if (!seenLinks.add(key)) return false
            callback(
                newExtractorLink(providerName, label.ifBlank { "$providerName MP4" }, url, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = qualityFromText(url).let { if (it == 0) Qualities.Unknown.value else it }
                    this.headers = videoHeaders(referer)
                }
            )
            return true
        }

        if (hlsCandidate || url.contains("m3u8", true)) {
            try {
                var emitted = false
                generateM3u8(
                    source = providerName,
                    streamUrl = url,
                    referer = referer,
                    headers = videoHeaders(referer)
                ).forEach { link ->
                    if (seenLinks.add("out:${link.url}")) {
                        emitted = true
                        callback(link)
                    }
                }
                if (emitted) return true
            } catch (e: Throwable) {
                Log.e("KingBokep", "HLS failed: ${e.message}")
            }
        }

        return try {
            var emitted = false
            loadExtractor(url, referer, subtitleCallback) { link ->
                if (seenLinks.add("out:${link.url}")) {
                    emitted = true
                    callback(link)
                }
            }
            emitted
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun collectSubtitles(pageUrl: String, document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            val url = absoluteUrl(pageUrl, raw) ?: return@forEach
            val lang = cleanText(element.attr("label").ifBlank { element.attr("srclang").ifBlank { element.text().ifBlank { "Subtitle" } } })
            try {
                subtitleCallback(newSubtitleFile(lang, url))
            } catch (_: Throwable) {
            }
        }
    }

    private fun resolveDataToUrl(mainUrl: String, data: String): String? {
        val raw = data.trim()
        if (raw.startsWith("http")) return raw
        if (raw.startsWith("{")) {
            val parsed = decodeLoadData(raw) ?: return null
            return parsed.url?.takeIf { it.startsWith("http") }
                ?: parsed.id?.let { "$mainUrl/view/${it.trim('/')}/" }
        }
        return if (raw.isNotBlank()) "$mainUrl/view/${raw.trim('/')}/" else null
    }

    private fun normalizedHtml(document: Document): String {
        return document.html()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }
}
