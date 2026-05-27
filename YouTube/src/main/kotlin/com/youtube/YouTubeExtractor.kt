package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

object YouTubeExtractor {
    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = linkedSetOf<String>()
        if (data.startsWith("http", true)) {
            candidates.add(data)
        }

        val videoId = Regex("""(?:v=|youtu\.be/|shorts/|live/)([A-Za-z0-9_-]{11})""")
            .find(data)
            ?.groupValues
            ?.getOrNull(1)
        if (videoId != null) {
            candidates.add("${YouTubeSeeds.MAIN_URL}/watch?v=$videoId")
            candidates.add("${YouTubeSeeds.MAIN_URL}/embed/$videoId")
            candidates.add("https://youtu.be/$videoId")
        }

        var found = false
        candidates.forEach { url ->
            runCatching {
                loadExtractor(url, null, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            }
        }
        return found
    }
}
