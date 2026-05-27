package com.filmlokal

data class FilmLokalCard(
    val title: String,
    val url: String,
    val poster: String? = null,
    val quality: String? = null,
    val rating: String? = null,
    val duration: String? = null
)

data class FilmLokalEpisode(
    val name: String,
    val url: String,
    val poster: String? = null
)
