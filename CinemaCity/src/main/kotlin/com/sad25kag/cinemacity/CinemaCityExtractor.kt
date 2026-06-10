package com.sad25kag.cinemacity

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile

class CinemaCityExtractor(private val parser: CinemaCityParser) {

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videos = resolveVideos(data)
            .distinctBy { it.url }
            .filter { it.url.startsWith("http", true) }

        videos.forEach { video ->
            emitSubtitles(video.subtitles, subtitleCallback)
            callback.invoke(
                newExtractorLink(
                    source = CinemaCityConstans.NAME,
                    name = video.title?.takeIf { it.isNotBlank() } ?: CinemaCityConstans.NAME,
                    url = video.url,
                    type = if (video.url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = "${CinemaCityConstans.MAIN_URL}/"
                    quality = getQualityFromName(video.title ?: video.url)
                }
            )
        }

        return videos.isNotEmpty()
    }

    private suspend fun resolveVideos(data: String): List<CinemaCityResolvedVideo> {
        val direct = parser.parseVideosFromData(data)
        if (direct.isNotEmpty()) return direct

        if (data.startsWith("http", true)) {
            val document = CinemaCityUtils.get(data).document
            val sources = parser.parseEpisodeSources(document.html())
            return sources.flatMap { parser.parseVideosFromData(it.data) }
        }

        return emptyList()
    }

    private fun emitSubtitles(raw: String, subtitleCallback: (SubtitleFile) -> Unit) {
        raw.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { entry ->
                val language = if (entry.contains("]")) {
                    entry.substringBefore("]").substringAfter("[")
                        .replace("(Full)", "")
                        .replace("(SDH)", "")
                        .trim()
                        .ifBlank { "Subtitle" }
                } else {
                    "Subtitle"
                }
                val url = if (entry.contains("]")) entry.substringAfter("]").trim() else entry
                CinemaCityUtils.absoluteUrl(url)?.takeIf { it.startsWith("http", true) }?.let {
                    subtitleCallback.invoke(newSubtitleFile(language, it))
                }
            }
    }
}
