package com.gojodesu

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.loadExtractor

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
        val response = app.get(url, referer = referer ?: mainUrl)
        val document = response.document
        val html = response.text

        val directM3u8 = extractM3u8Urls(html)
        directM3u8.forEach { m3u8 ->
            generateM3u8(
                name = name,
                streamUrl = normalizeUrl(m3u8),
                referer = url
            ).forEach(callback)
        }

        val frames = linkedSetOf<String>()

        document.select("ul#dropdown-server li a[data-frame], a[data-frame]").forEach { a ->
            val encodedFrame = a.attr("data-frame").trim()
            if (encodedFrame.isBlank()) return@forEach

            val frameUrl = runCatching {
                base64Decode(encodedFrame).trim()
            }.getOrNull()

            if (!frameUrl.isNullOrBlank()) {
                frames.add(normalizeUrl(frameUrl))
            }
        }

        document.select("iframe[src], embed[src]").forEach { iframe ->
            val frameUrl = iframe.attr("src").trim()
            if (frameUrl.isNotBlank()) {
                frames.add(normalizeUrl(frameUrl))
            }
        }

        frames.forEach { frame ->
            val frameResponse = runCatching {
                app.get(frame, referer = url)
            }.getOrNull()

            val frameM3u8 = frameResponse?.text?.let { extractM3u8Urls(it) }.orEmpty()

            if (frameM3u8.isNotEmpty()) {
                frameM3u8.forEach { m3u8 ->
                    generateM3u8(
                        name = name,
                        streamUrl = normalizeUrl(m3u8),
                        referer = frame
                    ).forEach(callback)
                }
            } else {
                loadExtractor(
                    frame,
                    url,
                    subtitleCallback,
                    callback
                )
            }
        }
    }

    private fun extractM3u8Urls(text: String): List<String> {
        return Regex("""https?://[^"'\\\s<>]+\.m3u8[^"'\\\s<>]*""")
            .findAll(text)
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .toList()
    }

    private fun normalizeUrl(url: String): String {
        val cleanUrl = url.trim().replace("\\/", "/")

        return when {
            cleanUrl.startsWith("http", ignoreCase = true) -> cleanUrl
            cleanUrl.startsWith("//") -> "https:$cleanUrl"
            cleanUrl.startsWith("/") -> "$mainUrl$cleanUrl"
            else -> "$mainUrl/$cleanUrl"
        }
    }
}