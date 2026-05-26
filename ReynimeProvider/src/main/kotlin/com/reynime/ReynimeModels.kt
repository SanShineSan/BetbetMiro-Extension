package com.reynime

data class ReynimeSearchItem(
    val title: String,
    val url: String,
    val poster: String? = null
)

data class ReynimeEpisodeData(
    val name: String,
    val url: String
)
