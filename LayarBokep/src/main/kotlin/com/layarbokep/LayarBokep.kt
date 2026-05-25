package com.layarbokep

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

open class HtmlMediaExtractor : ExtractorApi() {
    override var name = "HTML Media"
    override var mainUrl = ""
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = url.replace(" ", "%20")
        val domain = getOrigin(pageUrl).ifBlank { mainUrl }
        val usedReferer = referer ?: domain

        val response = runCatching {
            app.get(
                pageUrl,
                referer = usedReferer,
                headers = defaultMediaHeaders(usedReferer),
                timeout = 20L
            )
        }.getOrNull() ?: return

        val html = response.text.cleanEscaped()

        if (html.trimStart().startsWith("#EXTM3U")) {
            emitMediaLink(
                source = name,
                streamUrl = pageUrl,
                referer = usedReferer,
                callback = callback
            )
            return
        }

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        response.document.select(
            "video source[src], source[src], video[src], video[data-src], video[data-file], " +
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], iframe[data-lazy-src], " +
                "embed[src], object[data], a[href], [data-src], [data-file], [data-video], [data-url]"
        ).forEach { element ->
            val raw = element.attr("data-file")
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-lazy-src") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            addExtractorCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        extractPlayableUrls(html).forEach { raw ->
            addExtractorCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach { raw ->
                addExtractorCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        decodeBase64Fragments(html).forEach { decoded ->
            extractPlayableUrls(decoded).forEach { raw ->
                addExtractorCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        extractSubtitleUrls(html, pageUrl).forEach { sub ->
            subtitleCallback(sub)
        }

        directLinks
            .filterNot { isJunkLink(it) }
            .distinct()
            .forEach { link ->
                emitMediaLink(
                    source = name,
                    streamUrl = link,
                    referer = pageUrl,
                    callback = callback
                )
            }

        if (directLinks.isNotEmpty()) return

        embedLinks
            .filterNot { it == pageUrl }
            .filterNot { isJunkLink(it) }
            .distinct()
            .take(8)
            .forEach { embed ->
                // PERBAIKAN: Gunakan fungsi bawaan Cloudstream terlebih dahulu untuk membongkar player
                if (embed.startsWith("http", true)) {
                    val success = runCatching {
                        loadExtractor(embed, pageUrl, subtitleCallback, callback)
                    }.getOrDefault(false)
                    if (success) return@forEach
                }

                // Fallback jika tidak didukung oleh Cloudstream Extractors bawaan
                val nested = runCatching {
                    app.get(
                        embed,
                        referer = pageUrl,
                        headers = defaultMediaHeaders(pageUrl),
                        timeout = 15L
                    ).text.cleanEscaped()
                }.getOrNull().orEmpty()

                extractPlayableUrls(nested).forEach { raw ->
                    val fixed = normalizeUrl(raw, embed).replace(".txt", ".m3u8")
                    if (fixed.isDirectVideoUrl()) {
                        emitMediaLink(
                            source = name,
                            streamUrl = fixed,
                            referer = embed,
                            callback = callback
                        )
                    }
                }

                val nestedUnpacked = runCatching {
                    if (!getPacked(nested).isNullOrEmpty()) getAndUnpack(nested) else null
                }.getOrNull()

                if (!nestedUnpacked.isNullOrBlank()) {
                    extractPlayableUrls(nestedUnpacked.cleanEscaped()).forEach { raw ->
                        val fixed = normalizeUrl(raw, embed).replace(".txt", ".m3u8")
                        if (fixed.isDirectVideoUrl()) {
                            emitMediaLink(
                                source = name,
                                streamUrl = fixed,
                                referer = embed,
                                callback = callback
                            )
                        }
                    }
                }

                extractSubtitleUrls(nested, embed).forEach { sub ->
                    subtitleCallback(sub)
                }
            }
    }
}

// PERBAIKAN: Membuat kerangka khusus agar semua clone Jeniusplay bisa mengeksekusi panggilan AJAX POST
open class JeniusplayExtractor : HtmlMediaExtractor() {
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        super.getUrl(url, referer, subtitleCallback, callback)

        val pageUrl = url.replace(" ", "%20")
        val hash = pageUrl.substringAfter("data=", pageUrl.substringAfterLast("/"))
            .substringBefore("&")
            .substringBefore("?")
            .trim()

        if (hash.isBlank()) return

        val headers = defaultMediaHeaders(referer ?: pageUrl) + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )

        val origin = getOrigin(pageUrl).ifBlank { mainUrl }

        val endpoints = listOf(
            "$origin/player/ajax.php",
            "$origin/ajax.php",
            "$origin/player/index.php"
        )

        endpoints.forEach { endpoint ->
            val text = runCatching {
                app.post(
                    url = endpoint,
                    data = mapOf("hash" to hash, "r" to (referer ?: pageUrl)),
                    referer = pageUrl,
                    headers = headers,
                    timeout = 15L
                ).text.cleanEscaped()
            }.getOrNull().orEmpty()

            extractPlayableUrls(text).forEach { raw ->
                val fixed = normalizeUrl(raw, pageUrl).replace(".txt", ".m3u8")
                if (fixed.isDirectVideoUrl()) {
                    emitMediaLink(
                        source = name,
                        streamUrl = fixed,
                        referer = pageUrl,
                        callback = callback
                    )
                }
            }
            extractSubtitleUrls(text, pageUrl).forEach(subtitleCallback)
        }
    }
}

class Jeniusplay : JeniusplayExtractor() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"
}

class Majorplay : JeniusplayExtractor() {
    override var name = "Majorplay"
    override var mainUrl = "https://majorplay.net"
}

class E2eMajorplay : JeniusplayExtractor() {
    override var name = "Majorplay E2E"
    override var mainUrl = "https://e2e.majorplay.net"
}

class M3u8Majorplay : JeniusplayExtractor() {
    override var name = "Majorplay M3U8"
    override var mainUrl = "https://m3u8.majorplay.net"
}

class DoodStream : HtmlMediaExtractor() {
    override var name = "Dood"
    override var mainUrl = "https://doodstream.com"
}

class StreamTape : HtmlMediaExtractor() {
    override var name = "StreamTape"
    override var mainUrl = "https://streamtape.com"
}

class FileMoon : HtmlMediaExtractor() {
    override var name = "FileMoon"
    override var mainUrl = "https://filemoon.sx"
}

class Vidhide : HtmlMediaExtractor() {
    override var name = "Vidhide"
    override var mainUrl = "https://vidhide.com"
}

class Voe : HtmlMediaExtractor() {
    override var name = "Voe"
    override var mainUrl = "https://voe.sx"
}

class Mixdrop : HtmlMediaExtractor() {
    override var name = "Mixdrop"
    override var mainUrl = "https://mixdrop.co"
}

class StreamWish : HtmlMediaExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.to"
}

class WishFast : HtmlMediaExtractor() {
    override var name = "WishFast"
    override var mainUrl = "https://wishfast.top"
}

class Hglink : HtmlMediaExtractor() {
    override var name = "Hglink"
    override var mainUrl = "https://hglink.to"
}

private fun addExtractorCandidate(
    raw: String,
    baseUrl: String,
    directLinks: MutableSet<String>,
    embedLinks: MutableSet<String>
) {
    if (raw.isBlank()) return

    val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl)
        .replace(".txt", ".m3u8")
        .trim()

    if (fixed.isBlank() || isJunkLink(fixed)) return

    when {
        fixed.isDirectVideoUrl() -> directLinks.add(fixed)
        fixed.startsWith("http", true) && isKnownEmbedHost(fixed) -> embedLinks.add(fixed)
        fixed.startsWith("http", true) && fixed.contains("embed", true) -> embedLinks.add(fixed)
        fixed.startsWith("http", true) && fixed.contains("player", true) -> embedLinks.add(fixed)
        fixed.startsWith("http", true) && fixed.contains("stream", true) -> embedLinks.add(fixed)
    }
}

private fun extractPlayableUrls(text: String): List<String> {
    val clean = text.cleanEscaped()
    val urls = linkedSetOf<String>()

    Regex(
        """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
        .filterNot { isJunkLink(it) }
        .forEach { urls.add(it) }

    Regex(
        """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
        .filterNot { isJunkLink(it) }
        .forEach { urls.add(it) }

    Regex(
        """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt|jeniusplay|majorplay|dood|streamtape|filemoon|vidhide|voe|mixdrop|streamwish|wishfast|hglink)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map {
            runCatching {
                URLDecoder.decode(it.value, "UTF-8")
            }.getOrDefault(it.value)
        }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filterNot { isJunkLink(it) }
        .forEach { urls.add(it) }

    Regex(
        """(?:file|src|source|url|videoSource|videoUrl|video_url|playUrl|play_url|hls|hlsUrl|hls_url|stream|streamUrl|stream_url|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filter {
            it.isDirectVideoUrl() ||
                isKnownEmbedHost(it) ||
                it.contains("embed", true) ||
                it.contains("player", true)
        }
        .filterNot { isJunkLink(it) }
        .forEach { urls.add(it) }

    Regex(
        """(?:data-file|data-video|data-url|data-src|data-embed|data-iframe|content)=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filter {
            it.isDirectVideoUrl() ||
                isKnownEmbedHost(it) ||
                it.contains("embed", true) ||
                it.contains("player", true)
        }
        .filterNot { isJunkLink(it) }
        .forEach { urls.add(it) }

    Regex(
        """https?://[^"'\\\s<>]+?(?:jeniusplay|majorplay|dood|streamtape|filemoon|vidhide|voe|mixdrop|streamwish|wishfast|hglink|embed|player|stream)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { it.value.cleanEscaped() }
        .filterNot { isJunkLink(it) }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun extractSubtitleUrls(text: String, baseUrl: String): List<SubtitleFile> {
    val clean = text.cleanEscaped()
    val subtitles = mutableListOf<SubtitleFile>()

    Regex(
        """"(?:label|lang|language)"\s*:\s*"([^"]+)"[^}]*?"(?:file|path|url|src)"\s*:\s*"([^"]+\.(?:vtt|srt|ass)(?:\?[^"]*)?)"""",
        RegexOption.IGNORE_CASE
    ).findAll(clean).forEach { match ->
        val label = match.groupValues[1].ifBlank { "Subtitle" }
        val link = normalizeUrl(match.groupValues[2], baseUrl)
        subtitles.add(SubtitleFile(label, link))
    }

    Regex(
        """https?://[^"'\\\s<>]+?\.(?:vtt|srt|ass)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean).forEach { match ->
        subtitles.add(SubtitleFile("Subtitle", match.value.cleanEscaped()))
    }

    return subtitles.distinctBy { it.url }
}

private fun decodeBase64Fragments(text: String): List<String> {
    val results = mutableListOf<String>()

    Regex("""["']([A-Za-z0-9+/=]{40,})["']""")
        .findAll(text)
        .map { it.groupValues[1] }
        .take(20)
        .forEach { token ->
            runCatching {
                val decoded = String(Base64.getDecoder().decode(token))
                if (
                    decoded.contains("http", true) ||
                        decoded.contains("iframe", true) ||
                        decoded.contains("video", true) ||
                        decoded.contains("source", true)
                ) {
                    results.add(decoded.cleanEscaped())
                }
            }
        }

    return results
}

private suspend fun emitMediaLink(
    source: String,
    streamUrl: String,
    referer: String,
    callback: (ExtractorLink) -> Unit
) {
    val fixed = streamUrl.cleanEscaped().replace(".txt", ".m3u8")

    if (fixed.isBlank() || isJunkLink(fixed)) return

    if (fixed.contains(".m3u8", true)) {
        generateM3u8(
            source = source,
            streamUrl = fixed,
            referer = referer,
            headers = defaultMediaHeaders(referer)
        ).forEach(callback)
    } else {
        callback(
            newExtractorLink(
                source = source,
                name = source,
                url = fixed,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(fixed).takeIf {
                    it != Qualities.Unknown.value
                } ?: qualityFromUrl(fixed)
                this.headers = defaultMediaHeaders(referer)
            }
        )
    }
}

private fun defaultMediaHeaders(referer: String): Map<String, String> {
    return mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Referer" to referer,
        "Origin" to getOrigin(referer)
    )
}

private fun getOrigin(url: String): String {
    return runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault("")
}

private fun normalizeUrl(url: String, baseUrl: String): String {
    val clean = url.cleanEscaped().trim()

    return when {
        clean.isBlank() -> ""
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> getOrigin(baseUrl).trimEnd('/') + clean
        else -> runCatching {
            URI(baseUrl).resolve(clean).toString()
        }.getOrDefault(clean)
    }
}

private fun String.cleanEscaped(): String {
    return this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u002F", "/")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .trim()
}

private fun String.isDirectVideoUrl(): Boolean {
    return contains(".m3u8", true) ||
        contains(".mp4", true) ||
        contains(".webm", true)
}

private fun isKnownEmbedHost(url: String): Boolean {
    val value = url.lowercase()

    return listOf(
        "jeniusplay",
        "majorplay",
        "dood",
        "streamtape",
        "filemoon",
        "vidhide",
        "voe.",
        "mixdrop",
        "streamwish",
        "wishfast",
        "hglink",
        "hlswish",
        "lulustream",
        "mp4upload"
    ).any { value.contains(it) }
}

private fun isJunkLink(url: String): Boolean {
    val value = url.lowercase()

    return value.isBlank() ||
        value.startsWith("#") ||
        value.startsWith("javascript") ||
        value.contains("facebook.com") ||
        value.contains("twitter.com") ||
        value.contains("telegram") ||
        value.contains("whatsapp") ||
        value.contains("mailto:") ||
        value.contains("googletagmanager") ||
        value.contains("google-analytics") ||
        value.contains("doubleclick") ||
        value.contains("googlesyndication") ||
        value.contains("adskeeper") ||
        value.contains("adsterra") ||
        value.contains("/ads/") ||
        value.contains("banner") ||
        value.contains("analytics") ||
        value.contains("tracking")
}

private fun qualityFromUrl(url: String): Int {
    return when {
        url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}
