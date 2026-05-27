package com.javfc

import com.javfc.JavFCUtils.absoluteUrl
import com.javfc.JavFCUtils.cleanText
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getExtractorApiFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URLDecoder

object JavFCExtractor {
    private val classicPlayerSrcRegex = Regex(
        """(?is)\bsrc\s*:\s*['\"`]([^'\"`]+)['\"`]"""
    )
    private val keyValueUrlRegex = Regex(
        """(?is)(?:src|file|url|source|hlsUrl|videoUrl|playlist)\s*[:=]\s*['\"`]([^'\"`]+)['\"`]"""
    )
    private val quotedMediaRegex = Regex(
        """(?i)['\"`]((?:https?:)?//[^'\"<>\\\s]+?\.(?:m3u8|mp4)(?:\?[^'\"<>\\\s]*)?)['\"`]"""
    )
    private val bareMediaRegex = Regex(
        """(?i)(?:https?:)?//[^\s'\"<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s'\"<>\\]*)?"""
    )
    private val encodedHttpRegex = Regex(
        """(?i)https?%3A%2F%2F[^'\"<>\\\s]+"""
    )

    suspend fun loadLinks(
        providerName: String,
        mainUrl: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            headers = JavFCUtils.headers,
            referer = mainUrl
        ).document

        var found = false

        collectSubtitles(mainUrl, document, subtitleCallback)

        val playerUrls = extractPlayerUrls(mainUrl, document)
        val directUrls = extractDirectUrls(mainUrl, document)
        val embedUrls = extractEmbedUrls(mainUrl, document, playerUrls)
        val code = data.substringAfterLast('/').substringBeforeLast('.').substringBefore('?')

        (playerUrls + directUrls).distinct().forEach { url ->
            try {
                if (url.contains(".mp4", ignoreCase = true)) {
                    found = true
                    callback(
                        newExtractorLink(providerName, "$providerName MP4", url) {
                            referer = mainUrl
                            quality = Qualities.Unknown.value
                            headers = JavFCUtils.headers
                        }
                    )
                } else {
                    var emitted = false

                    try {
                        generateM3u8(
                            source = providerName,
                            streamUrl = url,
                            referer = mainUrl,
                            headers = JavFCUtils.headers
                        ).forEach { link ->
                            emitted = true
                            found = true
                            callback(link)
                        }
                    } catch (e: Throwable) {
                        Log.e("JavFC", "HLS candidate failed: ${e.message}")
                    }

                    if (!emitted) {
                        loadExtractor(url, mainUrl, subtitleCallback) { link ->
                            found = true
                            callback(link)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e("JavFC", "Direct/player media failed: ${e.message}")
            }
        }

        embedUrls.forEach { embed ->
            try {
                loadExtractor(embed, mainUrl, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            } catch (e: Throwable) {
                Log.e("JavFC", "Embed extractor failed: ${e.message}")
            }
        }

        if (code.isNotBlank()) {
            try {
                val subApi = getExtractorApiFromName("SubtitleCat")
                if (subApi != null && subApi.name.equals("SubtitleCat", ignoreCase = true)) {
                    subApi.getUrl(
                        url = code,
                        referer = mainUrl,
                        subtitleCallback = subtitleCallback,
                        callback = { link ->
                            found = true
                            callback(link)
                        }
                    )
                }
            } catch (e: Throwable) {
                Log.e("JavFC", "SubtitleCat failed: ${e.message}")
            }
        }

        if (!found) {
            Log.e("JavFC", "No playable media found for: $data")
        }

        return found
    }

    private suspend fun collectSubtitles(
        mainUrl: String,
        document: Document,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        document.select("track[kind=subtitles], track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val subUrl = absoluteUrl(mainUrl, element.attr("src").ifBlank { element.attr("href") }) ?: return@forEach
            val label = cleanText(element.attr("label").ifBlank { element.text().ifBlank { "Subtitle" } })
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }

    private fun extractPlayerUrls(mainUrl: String, document: Document): List<String> {
        val direct = linkedSetOf<String>()
        val playerRaw = normalizedHtml(
            document.select("#player-div script, #player-div, .player script, .player, script:containsData(src:), script:containsData(hlsUrl), script:containsData(videoUrl), script:containsData(playlist)")
                .joinToString("\n") { it.html() }
        )

        classicPlayerSrcRegex.findAll(playerRaw)
            .mapNotNull { normalizeMediaUrl(mainUrl, it.groupValues[1]) }
            .forEach { direct.add(it) }

        keyValueUrlRegex.findAll(playerRaw)
            .mapNotNull { normalizeMediaUrl(mainUrl, it.groupValues[1]) }
            .forEach { direct.add(it) }

        encodedHttpRegex.findAll(playerRaw)
            .mapNotNull { normalizeMediaUrl(mainUrl, it.value) }
            .forEach { direct.add(it) }

        return direct.distinct()
    }

    private fun extractDirectUrls(mainUrl: String, document: Document): List<String> {
        val raw = normalizedHtml(document.html())
        val direct = linkedSetOf<String>()

        document.select("video[src], video source[src], source[src]").forEach { source ->
            normalizeMediaUrl(mainUrl, source.attr("src"))?.let { direct.add(it) }
        }

        keyValueUrlRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, it.groupValues[1]) }
            .forEach { direct.add(it) }

        quotedMediaRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, it.groupValues[1]) }
            .forEach { direct.add(it) }

        bareMediaRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, it.value) }
            .forEach { direct.add(it) }

        encodedHttpRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, it.value) }
            .forEach { direct.add(it) }

        return direct.distinct()
    }

    private fun extractEmbedUrls(mainUrl: String, document: Document, knownPlayerUrls: List<String>): List<String> {
        val raw = normalizedHtml(document.html())
        val embeds = linkedSetOf<String>()

        document.select("iframe[src], embed[src]").forEach { iframe ->
            absoluteUrl(mainUrl, iframe.attr("src"))?.let { embeds.add(it) }
        }

        keyValueUrlRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, it.groupValues[1]) }
            .filterNot { value ->
                knownPlayerUrls.any { it == value } ||
                    value.contains(".m3u8", ignoreCase = true) ||
                    value.contains(".mp4", ignoreCase = true)
            }
            .forEach { embeds.add(it) }

        return embeds.filter { it.startsWith("http") }.distinct()
    }

    private fun normalizedHtml(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("%2F", "/", ignoreCase = true)
            .replace("%3A", ":", ignoreCase = true)
            .replace("%3F", "?", ignoreCase = true)
            .replace("%26", "&", ignoreCase = true)
            .replace("%3D", "=", ignoreCase = true)
    }

    private fun normalizeMediaUrl(mainUrl: String, value: String?): String? {
        val cleaned = value.orEmpty()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .trim()
            .trim('"', '\'', '`', ',', ';')

        val raw = decodeUrlCandidate(cleaned)
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .trim()
            .trim('"', '\'', '`', ',', ';')

        if (raw.isBlank() || raw.equals("null", ignoreCase = true)) return null
        if (raw.startsWith("data:", ignoreCase = true)) return null
        if (raw.startsWith("blob:", ignoreCase = true)) return null

        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> absoluteUrl(mainUrl, raw)
            else -> null
        }
    }

    private fun decodeUrlCandidate(value: String): String {
        if (!value.contains("%")) return value

        var current = value.replace("+", "%2B")
        repeat(2) {
            val decoded = try {
                URLDecoder.decode(current, "UTF-8")
            } catch (_: Throwable) {
                current
            }
            if (decoded == current) return current
            current = decoded
        }
        return current
    }
}
