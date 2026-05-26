package com.reynime

import com.lagradost.cloudstream3.TvType

data class ReynimeSeries(
    val id: Int,
    val title: String,
    val slug: String,
    val poster: String? = null,
    val kind: String = "Donghua",
    val status: String = "Ongoing",
    val type: TvType = TvType.Anime,
    val latestEpisode: Int = 1,
    val firstEpisode: Int = 1,
    val year: Int? = null,
    val score: Double? = null,
    val genres: Set<String> = emptySet(),
    val description: String = "Streaming Donghua subtitle Indonesia di Reynime.",
    val featured: Boolean = false,
    val updated: Boolean = false
)

data class ReynimeApiEpisode(
    val id: Int,
    val episode: Int,
    val title: String,
    val poster: String? = null,
    val description: String? = null,
    val urls: List<String> = emptyList()
)

data class ReynimeBackendEpisode(
    val id: String? = null,
    val seriesId: String? = null,
    val episodeNumber: String? = null,
    val title: String? = null,
    val poster: String? = null,
    val description: String? = null,
    val urls: List<String> = emptyList()
)

data class ReynimePlaybackData(
    val pageUrl: String,
    val seriesId: String? = null,
    val episodeNumber: String? = null,
    val episodeId: String? = null,
    val title: String? = null,
    val seedSlug: String? = null
)
