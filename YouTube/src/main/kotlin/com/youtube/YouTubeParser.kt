package com.youtube

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.youtube.YouTubeUtils.bestThumbnail
import com.youtube.YouTubeUtils.cleanText
import com.youtube.YouTubeUtils.durationMinutes
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.stream.StreamInfoItem

object YouTubeParser {
    fun parseInfoItems(items: List<InfoItem>): List<SearchResponse> {
        return items
            .mapNotNull { item ->
                if (item.infoType != InfoType.STREAM) return@mapNotNull null
                val stream = item as? StreamInfoItem ?: return@mapNotNull null
                toSearchResponse(stream)
            }
            .distinctBy { it.url }
            .take(50)
    }

    fun toSearchResponse(stream: StreamInfoItem): SearchResponse? {
        val title = cleanText(stream.name).takeIf { it.isNotBlank() } ?: return null
        val url = stream.url.takeIf { it.isNotBlank() } ?: return null
        return newMovieSearchResponse(title, url, TvType.Others) {
            posterUrl = bestThumbnail(stream.thumbnails)
        }
    }

    fun streamTags(stream: StreamInfoItem): List<String> {
        return listOfNotNull(
            stream.uploaderName?.takeIf { it.isNotBlank() },
            YouTubeUtils.formatCompact(stream.viewCount)?.let { "👀 $it" },
            durationMinutes(stream.duration).takeIf { it > 0 }?.let { "${it}m" }
        )
    }
}
