package com.surgefilm21

import com.lagradost.cloudstream3.TvType

data class SurgeFilm21Item(
    val title: String,
    val url: String,
    val poster: String? = null,
    val type: TvType = TvType.Movie,
    val year: Int? = null
)

data class SurgeFilm21Episode(
    val name: String,
    val url: String,
    val episode: Int? = null,
    val season: Int? = null
)

data class SurgeFilm21Video(
    val url: String,
    val label: String = "Auto",
    val quality: String? = null,
    val type: String? = null,
    val referer: String? = null
)

data class SurgeFilm21Section(
    val data: String,
    val name: String,
    val fallbackQuery: String
)
