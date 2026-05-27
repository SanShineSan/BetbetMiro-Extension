package com.kingbokep

data class KingBokepLoadData(
    val url: String? = null,
    val id: String? = null,
    val title: String? = null
)

data class KingBokepServer(
    val name: String,
    val url: String,
    val referer: String,
    val hlsCandidate: Boolean = false
)
