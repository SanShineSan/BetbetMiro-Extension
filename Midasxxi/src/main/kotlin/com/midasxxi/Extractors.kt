package com.midasxxi

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
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

open class Playcinematic(
    private val extractorName: String = "Playcinematic",
    private val extractorMainUrl: String = "https://playcinematic.com"
) : ExtractorApi() {
    override var name = extractorName
    override var mainUrl = extractorMainUrl
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = normalizeUrl(url, mainUrl)
        val origin = getOrigin(fixedUrl)
        val response = runCatching {
            app.get(
                fixedUrl,
                referer = referer ?: origin,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
        }.getOrNull() ?: return

        val candidates = linkedSetOf<String>()
        val html = response.text.cleanEscaped()

        response.document.select(
            "video[src], video source[src], source[src], iframe[src], iframe[data-src], embed[src], object[data], [data-src], [data-file], [data-video], [data-url]"
        ).forEach { element ->
            val raw = element.attr("data-file")
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .trim()

            if (raw.isNotBlank()) candidates.add(normalizeUrl(raw, fixedUrl))
        }

        extractPlayableUrls(html).forEach { candidates.add(normalizeUrl(it, fixedUrl)) }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach { candidates.add(normalizeUrl(it, fixedUrl)) }
        }

        val emitted = linkedSetOf<String>()
        candidates
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter { it.startsWith("http", true) }
            .filterNot { isNoiseUrl(it) }
            .forEach { stream ->
                if (!emitted.add(stream)) return@forEach

                if (stream.contains(".m3u8", true)) {
                    generateM3u8(
                        source = name,
                        streamUrl = stream,
                        referer = fixedUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to fixedUrl,
                            "Origin" to origin
                        )
                    ).forEach(callback)
                } else if (
                    stream.contains("/stream/k/", true) ||
                    stream.contains(".mp4", true) ||
                    stream.contains(".webm", true) ||
                    stream.contains(".mkv", true)
                ) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = stream,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = fixedUrl
                            this.quality = getQualityFromName(stream).takeIf {
                                it != Qualities.Unknown.value
                            } ?: qualityFromUrl(stream)
                            this.headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to fixedUrl,
                                "Origin" to origin
                            )
                        }
                    )
                }
            }
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val results = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?(?:\.(?:m3u8|mp4|webm|mkv|txt)|/stream/k/[^"'\\\s<>]+)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .forEach { results.add(it) }

        Regex(
            """//[^"'\\\s<>]+?(?:\.(?:m3u8|mp4|webm|mkv|txt)|/stream/k/[^"'\\\s<>]+)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped()}" }
            .forEach { results.add(it) }

        Regex(
            """(?:file|src|source|url|videoUrl|video_url|hls|hlsUrl|hls_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .filter { isPlayableCandidate(it) }
            .forEach { results.add(it) }

        return results.toList()
    }

    private fun isPlayableCandidate(url: String): Boolean {
        return url.contains("/stream/k/", true) ||
            url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true) ||
            url.contains(".mkv", true) ||
            url.contains(".txt", true)
    }

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped().trim()
        if (clean.isBlank()) return ""
        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> getOrigin(baseUrl).trimEnd('/') + clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
    }

    private fun getOrigin(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }

    private fun isNoiseUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("analytics") ||
            value.contains("tracking") ||
            value.contains("/ads/") ||
            value.contains("vast") ||
            value.contains("preroll")
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

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }
}

class Midasfilm : Playcinematic("Midasfilm", "https://midasfilm.com")
