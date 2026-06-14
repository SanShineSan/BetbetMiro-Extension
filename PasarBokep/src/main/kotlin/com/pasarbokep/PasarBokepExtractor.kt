package com.pasarbokep

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URLDecoder

object PasarBokepExtractor {
    private const val MAX_PAGE_HOPS = 2
    private const val MAX_EMBEDS = 28
    private const val MAX_DIRECT = 24

    private val playableSelector = listOf(
        "meta[itemprop=embedURL]", "meta[property=og:video]", "meta[property=og:video:url]", "meta[property=og:video:secure_url]",
        "video[src]", "video[data-src]", "video[data-video]", "video[poster]", "video source[src]", "source[src]", "source[data-src]",
        "iframe[src]", "iframe[data-src]", "iframe[data-litespeed-src]", "iframe[data-lazy-src]", "iframe[data-original]", "iframe[srcdoc]",
        "embed[src]", "object[data]", "a[href]",
        "[data-src]", "[data-litespeed-src]", "[data-lazy-src]", "[data-original]", "[data-video]", "[data-file]", "[data-url]",
        "[data-link]", "[data-href]", "[data-embed]", "[data-iframe]", "[data-player]", "[data-play]", "[data-frame]", "[data-html]", "[data-content]"
    ).joinToString(",")

    private val attrNames = listOf(
        "content", "data-litespeed-src", "data-lazy-src", "data-original", "data-video", "data-video-url", "data-file", "data-url",
        "data-link", "data-href", "data-embed", "data-iframe", "data-player", "data-play", "data-frame", "data-src", "data-html", "data-content",
        "data", "srcdoc", "src", "href", "value"
    )

    private val keyValueRegex = Regex(
        """(?is)(?:data-playlist|playlist|hlsUrl|hls_url|hls|file|fileUrl|file_url|source|src|url|embed|embedUrl|embed_url|iframe|video|videoUrl|video_url|stream|streamUrl|contentUrl|content_url)\s*[:=]\s*['\"]([^'\"]+)['\"]"""
    )
    private val htmlAttributeRegex = Regex(
        """(?is)(?:src|href|content|data-(?:src|litespeed-src|lazy-src|original|video|video-url|file|url|link|href|embed|iframe|player|play|frame|html|content))\s*=\s*['\"]([^'\"]+)['\"]"""
    )
    private val directRegex = Regex(
        """(?is)(?:https?:)?//[^'\"<>\s]+?(?:\.m3u8|\.mp4|\.webm|\.mkv|videoplayback|googlevideo|get_video|playlist|master)[^'\"<>\s]*"""
    )
    private val knownHostRegex = Regex(
        """(?is)(?:https?:)?//[^'\"<>\s]+?(?:streamsb|sbembed|sbbrisk|sbfull|sblanh|sbplay|sbthe|sbspeed|waaw|dood|d000d|streamtape|stape|filemoon|filelions|streamwish|wishfast|vidhide|vidguard|voe\.sx|mixdrop|mp4upload|lulustream|luluvdo|uqload|short\.ink)[^'\"<>\s]*"""
    )
    private val encodedUrlRegex = Regex("""https?%3A%2F%2F[^'\"<>\s]+""", RegexOption.IGNORE_CASE)
    private val atobRegex = Regex("""(?is)atob\s*\(\s*['\"]([A-Za-z0-9+/=_-]{16,})['\"]\s*\)""")
    private val base64StringRegex = Regex("""['\"]([A-Za-z0-9+/=_-]{32,})['\"]""")

    suspend fun resolve(
        pageUrl: String,
        mainUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val seenPages = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        return resolvePage(pageUrl, mainUrl, mainUrl, 0, seenPages, emitted, subtitleCallback, callback)
    }

    private suspend fun resolvePage(
        pageUrl: String,
        mainUrl: String,
        referer: String,
        depth: Int,
        seenPages: MutableSet<String>,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (depth > MAX_PAGE_HOPS || !seenPages.add(pageUrl)) return false

        val response = runCatching {
            app.get(pageUrl, headers = PasarBokepUtils.headers, referer = referer, timeout = 25L)
        }.getOrNull() ?: return false

        val document = response.document
        val rawHtml = response.text.ifBlank { document.html() }
        collectSubtitles(pageUrl, document, subtitleCallback)

        val candidates = extractCandidates(document, rawHtml, pageUrl, mainUrl)
        val directLinks = candidates.filter { PasarBokepUtils.isDirectVideo(it) }.take(MAX_DIRECT)
        val embedLinks = candidates.filterNot { PasarBokepUtils.isDirectVideo(it) }.take(MAX_EMBEDS)

        var found = false

        directLinks.forEach { link ->
            found = emitDirect(link, pageUrl, emitted, callback) || found
        }
        if (found) return true

        embedLinks.forEach { embed ->
            found = runExtractor(embed, pageUrl, emitted, subtitleCallback, callback) || found
            if (!found && depth < MAX_PAGE_HOPS) {
                found = resolvePage(embed, mainUrl, pageUrl, depth + 1, seenPages, emitted, subtitleCallback, callback) || found
            }
        }
        if (found) return true

        // Some WordPress players inject StreamSB/player requests only after the page runs in WebView.
        webViewCandidates(pageUrl, referer).forEach { captured ->
            when {
                PasarBokepUtils.isDirectVideo(captured) -> found = emitDirect(captured, pageUrl, emitted, callback) || found
                PasarBokepUtils.isPotentialExtractor(captured, mainUrl) -> found = runExtractor(captured, pageUrl, emitted, subtitleCallback, callback) || found
            }
            if (found) return@forEach
        }
        if (found) return true

        return runExtractor(pageUrl, referer, emitted, subtitleCallback, callback)
    }

    private fun extractCandidates(document: Document, html: String, pageUrl: String, mainUrl: String): List<String> {
        val results = linkedSetOf<String>()

        fun add(raw: String?, base: String = pageUrl) {
            val fixed = PasarBokepUtils.absoluteUrl(raw, base)
                ?.replace(".txt", ".m3u8")
                ?.replace(" ", "%20")
                ?: return
            if (fixed == pageUrl) return
            if (PasarBokepUtils.shouldSkipUrl(fixed)) return
            if (PasarBokepUtils.isBadMediaAsset(fixed) && !PasarBokepUtils.isDirectVideo(fixed)) return
            if (PasarBokepUtils.isDirectVideo(fixed) || PasarBokepUtils.isPotentialExtractor(fixed, mainUrl) || PasarBokepUtils.isKnownHost(fixed)) {
                results.add(fixed)
            }
        }

        document.select(playableSelector).forEach { element ->
            attrNames.forEach { attr ->
                val value = element.attr(attr)
                if (value.isNotBlank()) {
                    if (attr == "srcdoc" || attr == "data-html" || attr == "data-content") {
                        scanText(value, pageUrl, mainUrl).forEach { add(it) }
                    } else {
                        add(value)
                    }
                }
            }
        }

        scanText(html, pageUrl, mainUrl).forEach { add(it) }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()
        if (!unpacked.isNullOrBlank()) {
            scanText(unpacked, pageUrl, mainUrl).forEach { add(it) }
        }

        val decodedOnce = runCatching { URLDecoder.decode(html, "UTF-8") }.getOrDefault(html)
        if (decodedOnce != html) {
            scanText(decodedOnce, pageUrl, mainUrl).forEach { add(it) }
        }

        atobRegex.findAll(html).forEach { match ->
            decodeBase64(match.groupValues[1]).forEach { decoded ->
                scanText(decoded, pageUrl, mainUrl).forEach { add(it) }
            }
        }

        base64StringRegex.findAll(html).take(80).forEach { match ->
            decodeBase64(match.groupValues[1]).forEach { decoded ->
                if (decoded.contains("http", true) || decoded.contains("iframe", true) || decoded.contains("m3u8", true)) {
                    scanText(decoded, pageUrl, mainUrl).forEach { add(it) }
                }
            }
        }

        return results
            .distinct()
            .sortedWith(compareBy<String> { if (PasarBokepUtils.isDirectVideo(it)) 0 else 1 }.thenBy { hostPriority(it) })
    }

    private fun scanText(text: String, pageUrl: String, mainUrl: String): List<String> {
        val out = linkedSetOf<String>()
        val variants = linkedSetOf(
            text,
            normalizedHtml(text),
        )
        runCatching { URLDecoder.decode(text, "UTF-8") }.getOrNull()?.let { variants.add(normalizedHtml(it)) }

        variants.forEach { source ->
            keyValueRegex.findAll(source).forEach { out.add(it.groupValues[1]) }
            htmlAttributeRegex.findAll(source).forEach { out.add(it.groupValues[1]) }
            directRegex.findAll(source).forEach { out.add(it.value) }
            knownHostRegex.findAll(source).forEach { out.add(it.value) }
            encodedUrlRegex.findAll(source).forEach { encoded ->
                runCatching { URLDecoder.decode(encoded.value, "UTF-8") }.getOrNull()?.let { out.add(it) }
            }
        }

        return out.mapNotNull { raw ->
            PasarBokepUtils.absoluteUrl(raw, pageUrl)
                ?.takeIf { PasarBokepUtils.isDirectVideo(it) || PasarBokepUtils.isPotentialExtractor(it, mainUrl) || PasarBokepUtils.isKnownHost(it) }
        }
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val clean = PasarBokepUtils.decodeMaybe(url).replace(".txt", ".m3u8")
        if (!emitted.add("direct:$clean")) return false

        if (PasarBokepUtils.isHlsLike(clean)) {
            val generated = runCatching {
                generateM3u8(
                    source = "PasarBokep",
                    streamUrl = clean,
                    referer = referer,
                    headers = PasarBokepUtils.videoHeaders(referer)
                )
            }.getOrNull().orEmpty()

            if (generated.isNotEmpty()) {
                generated.forEach { link ->
                    if (emitted.add("out:${link.url}")) callback(link)
                }
                return true
            }
        }

        callback(
            newExtractorLink(
                source = "PasarBokep",
                name = if (PasarBokepUtils.isHlsLike(clean)) "PasarBokep HLS" else "PasarBokep Video",
                url = clean,
                type = if (PasarBokepUtils.isHlsLike(clean)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = referer
                this.quality = PasarBokepUtils.directVideoQuality(clean).let { if (it == 0) Qualities.Unknown.value else it }
                this.headers = PasarBokepUtils.videoHeaders(referer)
            }
        )
        return true
    }

    private suspend fun runExtractor(
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (PasarBokepUtils.shouldSkipUrl(url) || !emitted.add("extractor:$url")) return false
        return runCatching {
            var hasOutput = false
            val success = loadExtractor(url, referer, subtitleCallback) { link ->
                if (emitted.add("out:${link.url}")) {
                    hasOutput = true
                    callback(link)
                }
            }
            success && hasOutput
        }.getOrDefault(false)
    }

    private suspend fun webViewCandidates(url: String, referer: String): List<String> {
        val out = linkedSetOf<String>()
        val regexes = listOf(
            Regex("""(?i).*(streamsb|sbembed|sbbrisk|sbfull|sblanh|waaw|m3u8|mp4|videoplayback|get_video|streamtape|dood|filemoon|streamwish).*"""),
            Regex("""(?i).*(/embed/|/e/|player|source|playlist|master).*"""),
        )

        regexes.forEach { regex ->
            val response = runCatching {
                app.get(
                    url,
                    headers = PasarBokepUtils.headers,
                    referer = referer,
                    interceptor = WebViewResolver(regex, timeout = 20_000L)
                )
            }.getOrNull() ?: return@forEach

            PasarBokepUtils.absoluteUrl(response.url, url)?.let { out.add(it) }
            extractCandidates(response.document, response.text, url, PasarBokepUtils.originOf(url) ?: url).forEach { out.add(it) }
        }
        return out.toList()
    }

    private suspend fun collectSubtitles(pageUrl: String, document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            val url = PasarBokepUtils.absoluteUrl(raw, pageUrl) ?: return@forEach
            val lang = PasarBokepUtils.cleanText(element.attr("label").ifBlank { element.attr("srclang").ifBlank { element.text().ifBlank { "Subtitle" } } })
            runCatching { subtitleCallback(newSubtitleFile(lang, url)) }
        }
    }

    private fun normalizedHtml(value: String): String {
        return PasarBokepUtils.decodeMaybe(value)
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("\\/", "/")
    }

    private fun decodeBase64(raw: String): List<String> {
        val out = linkedSetOf<String>()
        val cleaned = raw.trim().replace("-", "+").replace("_", "/")
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        listOf(raw, cleaned, padded).forEach { candidate ->
            runCatching { String(Base64.decode(candidate, Base64.DEFAULT)) }.getOrNull()?.let { decoded ->
                out.add(normalizedHtml(decoded))
            }
        }
        return out.toList()
    }

    private fun hostPriority(url: String): Int {
        val lower = url.lowercase()
        return when {
            PasarBokepUtils.isDirectVideo(lower) -> 0
            lower.contains("streamsb") || lower.contains("sbembed") || lower.contains("sbbrisk") || lower.contains("sbfull") || lower.contains("sblanh") || lower.contains("waaw") -> 1
            lower.contains("streamwish") || lower.contains("wishfast") -> 2
            lower.contains("filemoon") -> 3
            lower.contains("vidhide") || lower.contains("vidguard") -> 4
            lower.contains("dood") -> 5
            lower.contains("streamtape") || lower.contains("stape") -> 6
            lower.contains("mp4upload") -> 7
            lower.contains("embed") || lower.contains("player") -> 20
            else -> 50
        }
    }
}

class PasarBokepStreamSB : StreamSB() {
    override var name = "StreamSB"
    override var mainUrl = "https://streamsb.com"
}

class PasarBokepSbrisk : StreamSB() {
    override var name = "Sbrisk"
    override var mainUrl = "https://sbbrisk.com"
}

class PasarBokepSbfull : StreamSB() {
    override var name = "Sbfull"
    override var mainUrl = "https://sbfull.com"
}

class PasarBokepSblanh : StreamSB() {
    override var name = "Sblanh"
    override var mainUrl = "https://sblanh.com"
}

class PasarBokepSbplay : StreamSB() {
    override var name = "Sbplay"
    override var mainUrl = "https://sbplay2.xyz"
}

class PasarBokepWaaw : StreamSB() {
    override var name = "Waaw"
    override var mainUrl = "https://waaw.to"
}

class PasarBokepDood : DoodLaExtractor() {
    override var name = "DoodStream"
    override var mainUrl = "https://doodstream.com"
}

class PasarBokepDoodWf : DoodLaExtractor() {
    override var name = "Dood"
    override var mainUrl = "https://dood.wf"
}

class PasarBokepStreamWish : StreamWishExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.to"
}

class PasarBokepFileMoon : Filesim() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.sx"
}
