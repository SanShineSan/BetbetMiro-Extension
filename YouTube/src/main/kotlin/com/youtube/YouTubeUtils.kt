package com.youtube

import com.lagradost.cloudstream3.utils.Qualities
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.stream.StreamInfo

object YouTubeUtils {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    fun bestThumbnail(thumbnails: List<Image>?): String? {
        return thumbnails
            ?.mapNotNull { it.url }
            ?.lastOrNull { it.isNotBlank() }
    }

    fun bestAvatar(channelInfo: ChannelInfo): String? {
        return bestThumbnail(channelInfo.avatars)
    }

    fun bestBanner(channelInfo: ChannelInfo): String? {
        return bestThumbnail(channelInfo.banners)
    }

    fun bestPoster(streamInfo: StreamInfo): String? {
        return bestThumbnail(streamInfo.thumbnails)
    }

    fun durationMinutes(seconds: Long): Int {
        if (seconds <= 0L) return 0
        return (seconds / 60L).toInt().coerceAtLeast(1)
    }

    fun formatCompact(count: Long): String? {
        if (count < 0L) return null
        return when {
            count >= 1_000_000_000L -> "${String.format("%.1f", count / 1_000_000_000.0)}B"
            count >= 1_000_000L -> "${String.format("%.1f", count / 1_000_000.0)}M"
            count >= 1_000L -> "${String.format("%.1f", count / 1_000.0)}K"
            else -> count.toString()
        }
    }

    fun cleanText(value: String?): String {
        return value.orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun qualityFromResolution(resolution: String?): Int {
        val number = resolution.orEmpty()
            .substringBefore("p")
            .filter { it.isDigit() }
            .toIntOrNull()
            ?: return Qualities.Unknown.value

        return number.takeIf { it > 0 } ?: Qualities.Unknown.value
    }

    fun canonicalWatchUrl(url: String): String {
        val id = Regex("""(?:v=|youtu\.be/|shorts/|live/|embed/)([A-Za-z0-9_-]{11})""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
        return if (id != null) "${YouTubeSeeds.MAIN_URL}/watch?v=$id" else url
    }
}
