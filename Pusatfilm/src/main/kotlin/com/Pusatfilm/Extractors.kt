package com.pusatfilm

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.util.Locale

open class Kotakajaib : ExtractorApi() {
    override val name = "Kotakajaib"
    override val mainUrl = "https://kotakajaib.me"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val realReferer = referer ?: mainUrl
        val document = app.get(url, referer = realReferer).document
        val html = document.outerHtml()
        val collected = linkedSetOf<String>()

        document.select(
            "iframe[src], iframe[data-src], iframe[data-lazy-src], source[src], video[src], " +
                "a[href], a[data-frame], a[data-src], a[data-url], a[data-link], " +
                "button[data-frame], button[data-src], button[data-url], button[data-link], " +
                "li[data-frame], li[data-src], li[data-url], option[value]"
        ).forEach { element ->
            listOf("data-frame", "data-src", "data-lazy-src", "data-url", "data-link", "href", "src", "value")
                .mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                .flatMap { expandCandidate(it, url) }
                .forEach { collected.add(it) }
        }

        extractUrlsFromText(html, url).forEach { collected.add(it) }

        collected
            .filter { it.isPlayableCandidate() && it != url }
            .distinct()
            .forEach { link ->
                when {
                    link.isDirectVideo() -> emitDirectLink(link, realReferer, callback)
                    else -> {
                        val loaded = runCatching {
                            loadExtractor(link, url, subtitleCallback, callback)
                        }.getOrDefault(false)

                        if (!loaded) {
                            runCatching {
                                val nestedDoc = app.get(link, referer = url).document
                                nestedDoc.select("iframe[src], iframe[data-src], a[href], source[src], video[src]")
                                    .flatMap { element ->
                                        listOf("src", "data-src", "data-url", "data-link", "href")
                                            .mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                                            .flatMap { expandCandidate(it, link) }
                                    }
                                    .plus(extractUrlsFromText(nestedDoc.outerHtml(), link))
                                    .filter { it.isPlayableCandidate() && it != link }
                                    .distinct()
                                    .forEach { nested ->
                                        if (nested.isDirectVideo()) {
                                            emitDirectLink(nested, link, callback)
                                        } else {
                                            loadExtractor(nested, link, subtitleCallback, callback)
                                        }
                                    }
                            }
                        }
                    }
                }
            }
    }

    private suspend fun emitDirectLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.cleanupUrl()
        val quality = Regex("(?i)(\\d{3,4})p").find(cleanUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value

        callback.invoke(
            if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
                newExtractorLink(
                    source = name,
                    name = name,
                    url = cleanUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = quality
                }
            } else {
                newExtractorLink(
                    source = name,
                    name = name,
                    url = cleanUrl
                ) {
                    this.referer = referer
                    this.quality = quality
                }
            }
        )
    }

    private fun extractUrlsFromText(text: String, referer: String): List<String> {
        val normalized = text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val directUrls = Regex("""(?i)(https?:)?//[^\s"'<>]+(?:m3u8|mp4|webm|mkv|avi|embed|file|stream|video|player)[^\s"'<>]*""")
            .findAll(normalized)
            .mapNotNull { it.value.normalizeUrl(referer) }
            .toList()

        val encodedUrls = Regex("""(?i)https?%3A%2F%2F[^\s"'<>]+""")
            .findAll(normalized)
            .mapNotNull { safeUrlDecode(it.value).normalizeUrl(referer) }
            .toList()

        val quotedPayloads = Regex("""["']([A-Za-z0-9+/=_-]{40,})["']""")
            .findAll(normalized)
            .flatMap { match -> expandCandidate(match.groupValues[1], referer).asSequence() }
            .toList()

        return (directUrls + encodedUrls + quotedPayloads)
            .map { it.cleanupUrl() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun expandCandidate(value: String, referer: String): List<String> {
        val output = linkedSetOf<String>()
        val cleaned = value.trim().cleanupUrl()
        if (cleaned.isBlank() || cleaned == "#" || cleaned.startsWith("javascript:", true)) return emptyList()

        cleaned.normalizeUrl(referer)?.let { output.add(it) }
        safeUrlDecode(cleaned).normalizeUrl(referer)?.let { output.add(it) }

        safeCloudstreamBase64(cleaned)?.let { decoded ->
            decoded.normalizeUrl(referer)?.let { output.add(it) }
            extractUrlsFromText(decoded, referer).forEach { output.add(it) }
        }

        safeAndroidBase64(cleaned)?.let { decoded ->
            decoded.normalizeUrl(referer)?.let { output.add(it) }
            extractUrlsFromText(decoded, referer).forEach { output.add(it) }
        }

        return output.toList()
    }

    private fun String.normalizeUrl(referer: String): String? {
        val value = trim().cleanupUrl()
        if (value.isBlank() || value == "#") return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> mainUrl.trimEnd('/') + value
            value.startsWith("./") -> referer.substringBeforeLast('/') + value.removePrefix(".")
            else -> null
        }?.cleanupUrl()
    }

    private fun String.cleanupUrl(): String {
        return trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .substringBefore("\"")
            .substringBefore("'")
            .substringBefore("<")
            .removeSuffix("\\")
            .trim()
    }

    private fun String.isPlayableCandidate(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (!startsWith("http")) return false
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) return false
        if (lower.contains("facebook.com") || lower.contains("telegram") || lower.contains("discord")) return false
        if (lower.contains("wp-content") && !lower.contains(".mp4") && !lower.contains(".m3u8")) return false
        return lower.contains("kotakajaib") || lower.contains("embed") || lower.contains("player") ||
            lower.contains("stream") || lower.contains("file") || lower.contains("video") ||
            lower.contains("m3u8") || lower.contains("mp4") || lower.contains("webm") ||
            lower.contains("hydrax") || lower.contains("rapidplay") || lower.contains("turbovid") ||
            lower.contains("gdplay") || lower.contains("gdrive") || lower.contains("dood") ||
            lower.contains("streamtape") || lower.contains("filemoon") || lower.contains("vidhide") ||
            lower.contains("voe") || lower.contains("mixdrop") || lower.contains("upstream") ||
            lower.contains("sb") || lower.contains("fembed") || lower.contains("vidsrc")
    }

    private fun String.isDirectVideo(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") || lower.contains("videoplayback")
    }

    private fun safeUrlDecode(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun safeCloudstreamBase64(value: String): String? {
        return runCatching { base64Decode(value) }.getOrNull()
    }

    private fun safeAndroidBase64(value: String): String? {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching {
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
        }.getOrNull()
    }
}


class PusatfilmHydrax : VidHidePro() {
    override var name = "Hydrax"
    override var mainUrl = "https://playhydrax.com"
}

class PusatfilmDood : DoodLaExtractor() {
    override var name = "Dood"
    override var mainUrl = "https://dood.la"
}
