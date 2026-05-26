package com.reynime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*

object ReynimeExtractor {
    suspend fun loadLinks(
        data: String,
        mainUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document

        val iframeLinks = doc.select("iframe")
            .mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } }
            .map { ReynimeUtils.fixUrl(mainUrl, it) }

        iframeLinks.forEach { link ->
            loadExtractor(link, data, subtitleCallback, callback)
        }

        val directVideos = doc.select("video source, source")
            .mapNotNull { it.attr("src").takeIf { s -> s.isNotBlank() } }

        directVideos.forEach { video ->
            callback(
                ExtractorLink(
                    source = "Reynime",
                    name = "Reynime",
                    url = ReynimeUtils.fixUrl(mainUrl, video),
                    referer = data,
                    quality = Qualities.Unknown.value,
                    isM3u8 = video.contains(".m3u8")
                )
            )
        }

        return iframeLinks.isNotEmpty() || directVideos.isNotEmpty()
    }
}
