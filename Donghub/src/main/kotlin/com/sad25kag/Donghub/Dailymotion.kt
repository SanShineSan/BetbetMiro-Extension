package com.sad25kag.Donghub

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import java.net.URI

class Geodailymotion : Dailymotion() {
    override val name = "GeoDailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class Dailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false

    private val baseUrl = "https://www.dailymotion.com"
    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url) ?: return
        val id = getVideoId(embedUrl) ?: return
        val metaDataUrl = "$baseUrl/player/metadata/video/$id"
        val response = app.get(metaDataUrl, referer = embedUrl).text

        Regex(""""url"\s*:\s*"([^"]+)"""")
            .findAll(response)
            .map { it.groupValues[1].replace("\/", "/") }
            .filter { it.contains(".m3u8", true) }
            .distinct()
            .forEach { videoUrl ->
                generateM3u8(name, videoUrl, embedUrl).forEach(callback)
            }

        val subtitlesRegex = Regex(""""subtitles"\s*:\s*\{[^}]*"data"\s*:\s*(\[[^\]]*\])""")
        subtitlesRegex.findAll(response).forEach { subtitleMatch ->
            val subtitleJson = subtitleMatch.groupValues[1]
            val subRegex = Regex("""\{\s*"label"\s*:\s*"([^"]+)",\s*"urls"\s*:\s*\["([^"]+)"""")
            subRegex.findAll(subtitleJson).forEach { match ->
                subtitleCallback(
                    SubtitleFile(
                        match.groupValues[1],
                        match.groupValues[2].replace("\/", "/")
                    )
                )
            }
        }
    }

    private fun getEmbedUrl(url: String): String? {
        return when {
            url.contains("/embed/") || url.contains("/video/") -> url
            url.contains("geo.dailymotion.com") && url.contains("video=") -> {
                val videoId = url.substringAfter("video=").substringBefore("&")
                "$baseUrl/embed/video/$videoId"
            }
            else -> null
        }
    }

    private fun getVideoId(url: String): String? {
        return runCatching {
            val path = URI(url).path
            val id = path.substringAfter("/video/")
                .substringBefore("_")
                .substringBefore("?")
            if (id.matches(videoIdRegex)) id else null
        }.getOrNull()
    }
}
