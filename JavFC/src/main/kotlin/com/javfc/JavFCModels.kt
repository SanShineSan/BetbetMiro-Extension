package com.javfc

data class JavFCVideoCard(
    val title: String,
    val url: String,
    val poster: String? = null,
    val label: String? = null
)

data class JavFCEpisode(
    val name: String,
    val url: String
)
