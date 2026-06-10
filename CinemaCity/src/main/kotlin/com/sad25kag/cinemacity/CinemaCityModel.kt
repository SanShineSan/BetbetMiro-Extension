package com.sad25kag.cinemacity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemaCityVideo(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("subtitle") val subtitle: String? = null,
    @JsonProperty("subtitles") val subtitles: String? = null,
    @JsonProperty("title") val title: String? = null
) {
    val playableUrl: String
        get() = url?.takeIf { it.isNotBlank() } ?: file.orEmpty()

    val subtitleText: String
        get() = subtitles?.takeIf { it.isNotBlank() } ?: subtitle.orEmpty()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemaCityVideoWrapper(
    @JsonProperty("data") val data: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("subtitle") val subtitle: String? = null
)

data class CinemaCityEpisodeSource(
    val title: String,
    val season: Int?,
    val episode: Int?,
    val data: String,
    val poster: String? = null
)

data class CinemaCityResolvedVideo(
    val title: String?,
    val url: String,
    val subtitles: String = ""
)
