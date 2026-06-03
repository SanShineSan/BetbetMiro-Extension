package com.PornhoarderPlugin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

open class PornhoarderPlaymogo : ExtractorApi() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = true

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = normalizeUrl(url, mainUrl)
        val html = app.get(
            embedUrl,
            referer = referer ?: mainUrl,
            headers = headers
        ).text

        directMediaRegex.find(html.cleanEscaped())?.groupValues?.getOrNull(1)?.let { direct ->
            emitVideo(direct, embedUrl, callback)
            return
        }

        val passMatch = passMd5Regex.find(html) ?: return
        val passPath = passMatch.value.cleanEscaped()
        val expiry = passMatch.groupValues.getOrNull(1).orEmpty()
        val token = passMatch.groupValues.getOrNull(2).orEmpty()
        val passUrl = normalizeUrl(passPath, mainUrl)
        val base = app.get(
            passUrl,
            referer = embedUrl,
            headers = headers
        ).text.trim().trim('"', '\'', ' ', '\n', '\r', '\t')
            .takeIf { it.startsWith("http", ignoreCase = true) } ?: return

        val finalUrl = buildString {
            append(base)
            if (token.isNotBlank() && expiry.isNotBlank()) {
                append(if (contains('?')) "&" else "?")
                append("token=").append(token)
                append("&expiry=").append(expiry).append("000")
            }
        }

        emitVideo(finalUrl, embedUrl, callback)
    }


    private fun normalizeUrl(url: String, base: String = mainUrl): String {
        val cleaned = url.cleanEscaped().trim()
        return when {
            cleaned.startsWith("http://", ignoreCase = true) ||
                cleaned.startsWith("https://", ignoreCase = true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> base.trimEnd('/') + cleaned
            else -> base.trimEnd('/') + "/" + cleaned.trimStart('/')
        }
    }

    private suspend fun emitVideo(
        videoUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!videoUrl.startsWith("http", ignoreCase = true)) return
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "video/mp4,video/*;q=0.9,*/*;q=0.8"
                )
            }
        )
    }

    private companion object {
        val passMd5Regex = Regex("""/pass_md5/([^/'"\s<>]+)/([^/'"\s<>]+)""")
        val directMediaRegex = Regex("""['"](https?://[^'"\s<>]+(?:\.mp4|\.m3u8)[^'"\s<>]*)['"]""", RegexOption.IGNORE_CASE)
    }
}

private fun String.cleanEscaped(): String {
    return replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\u0026", "&")
}


class PornhoarderDirtyVideo : ExtractorApi() {
    override val name = "DirtyVideo"
    override val mainUrl = "https://dirtyvideo.fun"
    override val requiresReferer = true

    private val mediaRegex = Regex(
        """['\"](https?://[^'\"\s<>]+(?:\.m3u8|\.mp4)(?:[^'\"\s<>]*)?)['\"]""",
        RegexOption.IGNORE_CASE
    )

    private val obfRegex = Regex("""\"obf_link\"\s*:\s*\"#?([0-9a-fA-F]+)\""", RegexOption.IGNORE_CASE)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = normalizeUrl(url, mainUrl)
        val html = app.get(
            embedUrl,
            referer = referer ?: mainUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        ).text.cleanEscaped()

        val direct = mediaRegex.findAll(html)
            .mapNotNull { normalizeUrl(it.groupValues[1], embedUrl).takeIf { media -> media.isPlayableMedia() } }
            .distinct()
            .firstOrNull()

        if (direct != null) {
            emitMedia(direct, embedUrl, callback)
            return
        }

        obfRegex.find(html)?.groupValues?.getOrNull(1)?.let { encoded ->
            val decoded = decodeDirtyVideoObf(encoded)
            val stream = normalizeUrl(decoded, mainUrl).let { if (it.contains(".m3u8", true)) it else "$it.mp4.m3u8" }
            if (stream.isPlayableMedia()) emitMedia(stream, embedUrl, callback)
        }
    }

    private fun normalizeUrl(url: String, base: String = mainUrl): String {
        val cleaned = url.cleanEscaped().trim()
        return when {
            cleaned.startsWith("http://", ignoreCase = true) ||
                cleaned.startsWith("https://", ignoreCase = true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> base.trimEnd('/') + cleaned
            else -> base.trimEnd('/') + "/" + cleaned.trimStart('/')
        }
    }

    private fun decodeDirtyVideoObf(value: String): String {
        val builder = StringBuilder()
        var index = 0
        while (index + 3 <= value.length) {
            val part = value.substring(index, index + 3)
            builder.append(part.toIntOrNull(16)?.toChar() ?: break)
            index += 3
        }
        return builder.toString()
    }

    private suspend fun emitMedia(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to mainUrl
        )
        if (url.contains(".m3u8", true)) {
            generateM3u8(name, url, mainUrl, headers = headers).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }
}

private fun String.isPlayableMedia(): Boolean {
    val lower = lowercase()
    return startsWith("http", true) &&
        (lower.contains(".m3u8") || lower.contains(".mp4")) &&
        !lower.contains("vast") &&
        !lower.contains("ads")
}
