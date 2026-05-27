package com.youtube

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.stream.StreamInfo

object YouTubeUtils {
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

    fun isYoutubeWatchUrl(url: String): Boolean {
        return url.contains("youtube.com/watch", true) ||
            url.contains("youtu.be/", true) ||
            url.contains("youtube.com/shorts/", true) ||
            url.contains("youtube.com/live/", true)
    }
}
