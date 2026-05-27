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

object JavFCExtractor {
    private val keyValueUrlRegex = Regex(
        """(?i)(?:src|file|url|source|hlsUrl)\s*[:=]\s*['"]((?:https?:)?//[^'"]+|/[^'"]+)['"]"""
    )
    private val quotedMediaRegex = Regex(
        """(?i)['"]((?:https?:)?//[^'"<>\\\s]+?\.(?:m3u8|mp4)(?:\?[^'"<>\\\s]*)?)['"]"""
    )
    private val bareMediaRegex = Regex(
        """(?i)(?:https?:)?//[^\s'"<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s'"<>\\]*)?"""
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

        val directUrls = extractDirectUrls(mainUrl, document)
        val embedUrls = extractEmbedUrls(mainUrl, document)
        val code = data.substringAfterLast('/').substringBeforeLast('.').substringBefore('?')

        directUrls.forEach { url ->
            try {
                if (url.contains(".m3u8", ignoreCase = true)) {
                    val links = generateM3u8(
                        source = providerName,
                        streamUrl = url,
                        referer = mainUrl,
                        headers = JavFCUtils.headers
                    )
                    links.forEach { link ->
                        found = true
                        callback(link)
                    }
                } else {
                    found = true
                    callback(
                        newExtractorLink(providerName, "$providerName MP4", url) {
                            referer = mainUrl
                            quality = Qualities.Unknown.value
                            headers = JavFCUtils.headers
                        }
                    )
                }
            } catch (e: Throwable) {
                Log.e("JavFC", "Direct media failed: ${e.message}")
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
                if (subApi.name.equals("SubtitleCat", ignoreCase = true)) {
                    subApi.getUrl(
                        url = code,
                        referer = mainUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            } catch (e: Throwable) {
                Log.e("JavFC", "SubtitleCat failed: ${e.message}")
            }
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

    private fun extractDirectUrls(mainUrl: String, document: Document): List<String> {
        val raw = normalizedHtml(document)
        val direct = linkedSetOf<String>()

        document.select("video[src], video source[src], source[src]").forEach { source ->
            normalizeMediaUrl(mainUrl, source.attr("src"))?.let { direct.add(it) }
        }

        keyValueUrlRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, it.groupValues[1]) }
            .filter { it.contains(".m3u8", ignoreCase = true) || it.contains(".mp4", ignoreCase = true) }
            .forEach { direct.add(it) }

        quotedMediaRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, it.groupValues[1]) }
            .forEach { direct.add(it) }

        bareMediaRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, it.value) }
            .forEach { direct.add(it) }

        return direct.distinct()
    }

    private fun extractEmbedUrls(mainUrl: String, document: Document): List<String> {
        val raw = normalizedHtml(document)
        val embeds = linkedSetOf<String>()

        document.select("iframe[src], embed[src]").forEach { iframe ->
            absoluteUrl(mainUrl, iframe.attr("src"))?.let { embeds.add(it) }
        }

        keyValueUrlRegex.findAll(raw)
            .mapNotNull { normalizeMediaUrl(mainUrl, it.groupValues[1]) }
            .filterNot { it.contains(".m3u8", ignoreCase = true) || it.contains(".mp4", ignoreCase = true) }
            .forEach { embeds.add(it) }

        return embeds.filter { it.startsWith("http") }.distinct()
    }

    private fun normalizedHtml(document: Document): String {
        return document.html()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
    }

    private fun normalizeMediaUrl(mainUrl: String, value: String?): String? {
        val raw = value.orEmpty()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .trim()
            .trim('"', '\'', ',', ';')

        if (raw.isBlank() || raw.equals("null", ignoreCase = true)) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> absoluteUrl(mainUrl, raw)
            else -> null
        }
    }
}
