package com.youtube

import org.schabi.newpipe.extractor.stream.StreamInfoItem

data class YouTubeCategory(
    val name: String,
    val data: String,
    val mode: YouTubeCategoryMode,
    val fallbackQuery: String = data
)

enum class YouTubeCategoryMode {
    Channel,
    Search
}
