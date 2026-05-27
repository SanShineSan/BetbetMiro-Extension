package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

object YouTubeExtractor {
    private val service = ServiceList.YouTube

    private val youtubeHeaders = mapOf(
        "User-Agent" to YouTubeUtils.USER_AGENT,
        "Accept" to "*/*",
        "Origin" to YouTubeSeeds.MAIN_URL,
        "Referer" to "${YouTubeSeeds.MAIN_URL}/"
    )

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = normalizeWatchUrl(data)
        var found = false

        val info = try {
            StreamInfo.getInfo(service, watchUrl)
        } catch (_: Throwable) {
            null
        }

        if (info != null) {
            info.hlsUrl?.takeIf { it.isNotBlank() }?.let { hls ->
                callback(
                    ExtractorLink(
                        source = "YouTube",
                        name = "YouTube HLS",
                        url = hls,
                        referer = "${YouTubeSeeds.MAIN_URL}/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                        headers = youtubeHeaders
                    )
                )
                found = true
            }

            info.videoStreams
                .orEmpty()
                .filter { it.url.isNotBlank() }
                .distinctBy { it.url }
                .sortedByDescending { YouTubeUtils.qualityFromResolution(it.resolution) }
                .forEach { stream ->
                    val quality = YouTubeUtils.qualityFromResolution(stream.resolution)
                    val label = stream.resolution.takeIf { it.isNotBlank() } ?: "Video"
                    callback(
                        ExtractorLink(
                            source = "YouTube",
                            name = "YouTube $label",
                            url = stream.url,
                            referer = "${YouTubeSeeds.MAIN_URL}/",
                            quality = quality,
                            isM3u8 = stream.url.contains(".m3u8", ignoreCase = true),
                            headers = youtubeHeaders
                        )
                    )
                    found = true
                }
        }

        if (!found) {
            val candidates = linkedSetOf(watchUrl)
            extractVideoId(watchUrl)?.let { videoId ->
                candidates.add("${YouTubeSeeds.MAIN_URL}/watch?v=$videoId")
                candidates.add("${YouTubeSeeds.MAIN_URL}/embed/$videoId")
                candidates.add("https://youtu.be/$videoId")
            }
            candidates.forEach { url ->
                try {
                    loadExtractor(url, "${YouTubeSeeds.MAIN_URL}/", subtitleCallback) { link ->
                        found = true
                        callback(link)
                    }
                } catch (_: Throwable) {
                    // Keep trying the next canonical YouTube shape.
                }
            }
        }

        return found
    }

    private fun normalizeWatchUrl(url: String): String {
        val trimmed = url.trim()
        val videoId = extractVideoId(trimmed)
        return if (videoId != null) {
            "${YouTubeSeeds.MAIN_URL}/watch?v=$videoId"
        } else {
            trimmed
        }
    }

    private fun extractVideoId(url: String): String? {
        return Regex("""(?:v=|youtu\.be/|shorts/|live/|embed/)([A-Za-z0-9_-]{11})""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
    }
}
