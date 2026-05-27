package com.filmlokal

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.filmlokal.FilmLokalUtils.absoluteUrl
import com.filmlokal.FilmLokalUtils.decodeMaybe
import com.filmlokal.FilmLokalUtils.videoHeaders
import org.jsoup.nodes.Document

object FilmLokalExtractor {
    private const val TAG = "FilmLokal"
    private const val MAX_HOPS = 2

    private val keyValueRegex = Regex(
        """(?i)(?:file|src|url|source|hls|hlsUrl|video|videoUrl)\s*[:=]\s*['\"]([^'\"]+)['\"]"""
    )
    private val quotedUrlRegex = Regex(
        """(?i)['\"]((?:https?:)?//[^'\"<>\s]+|/[^'\"<>\s]+)['\"]"""
    )
    private val encodedUrlRegex = Regex("""https?%3A%2F%2F[^'\"<>\s]+""", RegexOption.IGNORE_CASE)

    suspend fun loadLinks(
        providerName: String,
        mainUrl: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val emitted = linkedSetOf<String>()
        return resolvePage(providerName, mainUrl, data, data, 0, emitted, subtitleCallback, callback)
    }

    private suspend fun resolvePage(
        providerName: String,
        mainUrl: String,
        pageUrl: String,
        referer: String,
        depth: Int,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > MAX_HOPS) return false
        val document = runCatching {
            app.get(pageUrl, headers = FilmLokalUtils.siteHeaders, referer = referer).document
        }.onFailure { Log.e(TAG, "GET failed $pageUrl: ${it.message}") }.getOrNull() ?: return false

        collectSubtitles(pageUrl, document, subtitleCallback)
        var found = false

        val direct = extractDirectMedia(pageUrl, document)
        for (url in direct) {
            val emittedNow = emitDirect(providerName, url, pageUrl, emitted, callback)
            found = found || emittedNow
            if (!emittedNow && !url.contains(".m3u8", true) && !url.contains(".mp4", true)) {
                val extractorFound = runExtractor(url, pageUrl, emitted, subtitleCallback, callback)
                found = found || extractorFound
            }
        }

        val embeds = extractEmbeds(pageUrl, document).filterNot { emitted.contains(it) }.take(12)
        for (embed in embeds) {
            val extractorFound = runExtractor(embed, pageUrl, emitted, subtitleCallback, callback)
            found = found || extractorFound
            if (!extractorFound && depth < MAX_HOPS && FilmLokalUtils.originOf(embed) != FilmLokalUtils.originOf(pageUrl)) {
                found = resolvePage(providerName, mainUrl, embed, pageUrl, depth + 1, emitted, subtitleCallback, callback) || found
            }
        }

        if (!found) {
            found = runExtractor(pageUrl, referer, emitted, subtitleCallback, callback)
        }
        return found
    }

    private suspend fun emitDirect(
        providerName: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!emitted.add(url)) return false
        return try {
            when {
                url.contains(".m3u8", true) || url.contains("m3u8", true) -> {
                    val links = generateM3u8(
                        source = providerName,
                        streamUrl = url,
                        referer = referer,
                        headers = videoHeaders(referer)
                    )
                    links.forEach { link ->
                        if (emitted.add(link.url)) callback(link)
                    }
                    links.isNotEmpty()
                }
                url.contains(".mp4", true) || url.contains("googlevideo", true) || url.contains("videoplayback", true) -> {
                    callback(
                        newExtractorLink(providerName, "$providerName MP4", url) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                            this.headers = videoHeaders(referer)
                        }
                    )
                    true
                }
                else -> false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "emitDirect failed: ${e.message}")
            false
        }
    }

    private suspend fun runExtractor(
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        return try {
            loadExtractor(url, referer, subtitleCallback) { link ->
                if (emitted.add(link.url)) {
                    found = true
                    callback(link)
                }
            }
            found
        } catch (e: Throwable) {
            Log.e(TAG, "loadExtractor failed $url: ${e.message}")
            false
        }
    }

    private suspend fun collectSubtitles(pageUrl: String, document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val url = absoluteUrl(pageUrl, element.attr("src").ifBlank { element.attr("href") }) ?: return@forEach
            val label = FilmLokalUtils.cleanText(element.attr("label").ifBlank { element.text().ifBlank { "Subtitle" } })
            runCatching { subtitleCallback(newSubtitleFile(label, url)) }
        }
    }

    private fun extractDirectMedia(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        document.select("video[src], video source[src], source[src]").forEach { source ->
            normalizeUrl(pageUrl, source.attr("src"))?.let { out.add(it) }
        }
        val html = normalizedHtml(document)
        keyValueRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
        quotedUrlRegex.findAll(html)
            .mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }
            .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains("googlevideo", true) || it.contains("videoplayback", true) }
            .forEach { out.add(it) }
        encodedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.value) }.forEach { out.add(it) }
        return out.distinct()
    }

    private fun extractEmbeds(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        document.select("iframe[src], embed[src], a[href]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            val url = normalizeUrl(pageUrl, raw) ?: return@forEach
            val low = url.lowercase()
            if (low.contains("youtube.com") || low.contains("youtu.be")) return@forEach
            if (low.contains("trailer")) return@forEach
            if (low.contains("myvidplay") || low.contains("minochinos") || low.contains("hglink") || low.contains("streamtape") || low.contains("dood") || low.contains("filemoon") || low.contains("vidhide") || low.contains("embed") || low.contains("player")) {
                out.add(url)
            }
        }
        val html = normalizedHtml(document)
        quotedUrlRegex.findAll(html)
            .mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }
            .filterNot { it.contains(".jpg", true) || it.contains(".png", true) || it.contains(".webp", true) || it.contains("youtube", true) }
            .filter { it.contains("embed", true) || it.contains("player", true) || it.contains("myvidplay", true) || it.contains("minochinos", true) || it.contains("hglink", true) || it.contains("streamtape", true) }
            .forEach { out.add(it) }
        return out.distinct()
    }

    private fun normalizedHtml(document: Document): String = document.html()
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\u0026", "&")

    private fun normalizeUrl(pageUrl: String, value: String?): String? {
        val raw = decodeMaybe(value.orEmpty())
            .trim()
            .trim('"', '\'', ',', ';', ' ')
        if (raw.isBlank()) return null
        val low = raw.lowercase()
        if (low == "#" || low == "null" || low == "undefined" || low == "about:blank") return null
        if (low.startsWith("javascript:") || low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("intent:")) return null
        return absoluteUrl(pageUrl, raw)
    }
}
