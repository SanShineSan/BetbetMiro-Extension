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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import java.net.URI

object GomunimeExtractor {
    private val keyValueMediaRegex = Regex(
        """(?i)(?:file|src|source|url|hls|playlist)\s*[:=]\s*['"]([^'"]+)['"]"""
    )
    private val quotedMediaRegex = Regex(
        """(?i)['"]((?:https?:)?//[^'"<>\s\\]+?(?:\.m3u8|\.mp4|googlevideo\.com/[^'"<>\s\\]+|videoplayback[^'"<>\s\\]*)(?:\?[^'"<>\s\\]*)?)['"]"""
    )
    private val encodedHttpRegex = Regex(
        """https?%3A%2F%2F[^\s'"<>]+""",
        RegexOption.IGNORE_CASE
    )

    suspend fun loadLinks(
        providerName: String,
        mainUrl: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = GomunimeUtils.headers, referer = mainUrl).document
        var found = false

        collectSubtitles(mainUrl, document, subtitleCallback)

        val servers = extractServers(mainUrl, data, document)
        val directFromPage = extractMediaCandidates(providerName, mainUrl, data, document)

        for (candidate in directFromPage) {
            if (emitCandidate(providerName, candidate, subtitleCallback, callback)) {
                found = true
            }
        }

        for (server in servers) {
            val serverDocument = runCatching {
                app.get(server.url, headers = GomunimeUtils.headers, referer = data).document
            }.getOrNull()

            if (serverDocument != null) {
                collectSubtitles(server.url, serverDocument, subtitleCallback)

                val candidates = extractMediaCandidates(server.name, mainUrl, server.url, serverDocument)
                for (candidate in candidates) {
                    if (emitCandidate(server.name, candidate, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }

            if (runCatching {
                    loadExtractor(server.url, data, subtitleCallback) { link ->
                        found = true
                        callback(link)
                    }
                }.getOrDefault(false)
            ) {
                found = true
            }
        }

        if (!found) {
            if (runCatching {
                    loadExtractor(data, mainUrl, subtitleCallback) { link ->
                        found = true
                        callback(link)
                    }
                }.getOrDefault(false)
            ) {
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

        if (candidate.isHls || candidate.url.contains(".m3u8", true)) {
            val links = runCatching {
                generateM3u8(
                    source = sourceName,
                    streamUrl = candidate.url,
                    referer = candidate.referer,
                    headers = GomunimeUtils.headers
                )
            }.getOrDefault(emptyList())

            links.forEach { link ->
                emitted = true
                callback(link)
            }

            if (emitted) return true
        }

        if (candidate.url.contains(".mp4", true) || candidate.url.contains("googlevideo.com", true) || candidate.url.contains("videoplayback", true)) {
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

        val extracted = runCatching {
            loadExtractor(candidate.url, candidate.referer, subtitleCallback) { link ->
                emitted = true
                callback(link)
            }
        }.getOrDefault(false)

        return emitted || extracted
    }

    private fun extractServers(mainUrl: String, pageUrl: String, document: Document): List<GomunimeServer> {
        val servers = linkedSetOf<GomunimeServer>()

        document.select("select.mirror option[value], .mirror option[value], option[value]").forEach { option ->
            val name = GomunimeUtils.cleanText(option.text()).ifBlank { "Server" }
            val value = option.attr("value").trim()
            val iframe = decodeIframeUrl(value) ?: absoluteUrl(pageUrl, value) ?: absoluteUrl(mainUrl, value)
            if (!iframe.isNullOrBlank()) {
                servers.add(GomunimeServer(name, iframe))
            }
        }

        document.select("iframe[src], embed[src]").forEach { frame ->
            val src = absoluteUrl(pageUrl, frame.attr("src")) ?: absoluteUrl(mainUrl, frame.attr("src"))
            if (!src.isNullOrBlank()) {
                servers.add(GomunimeServer(frame.attr("title").ifBlank { "Embed" }, src))
            }
        }

        val raw = normalizedHtml(document)
        keyValueMediaRegex.findAll(raw)
            .mapNotNull { absoluteUrl(pageUrl, it.groupValues[1]) ?: absoluteUrl(mainUrl, it.groupValues[1]) }
            .filterNot { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains("googlevideo", true) }
            .forEach { servers.add(GomunimeServer("Embed", it)) }

        return servers.distinctBy { it.url }
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

        encodedHttpRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, referer, decodeUrl(it.value)) }
            .forEach { url ->
                candidates.add(GomunimeMediaCandidate(url, "$sourceName Encoded", referer, url.isLikelyHls()))
            }

        return candidates.distinctBy { it.url }
    }

    private fun decodeIframeUrl(value: String): String? {
        val decoded = decodeBase64Html(value) ?: return null
        return Regex("""(?i)<iframe[^>]+src=['"]([^'"]+)['"]""")
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
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
        return document.html()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("%2F", "/", ignoreCase = true)
            .replace("%3A", ":", ignoreCase = true)
            .replace("%3F", "?", ignoreCase = true)
            .replace("%3D", "=", ignoreCase = true)
            .replace("%26", "&", ignoreCase = true)
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

        if (raw.isBlank() || raw.equals("null", true) || raw.startsWith("blob:", true)) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> absoluteUrl(mainUrl, raw)
            else -> runCatching { URI(referer).resolve(raw).toString() }.getOrNull()
        }
    }

    private fun String.isLikelyHls(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") || lower.contains("playlist") || lower.contains("hls")
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
