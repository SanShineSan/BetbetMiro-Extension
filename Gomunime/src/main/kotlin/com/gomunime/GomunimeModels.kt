package com.gomunime

data class GomunimeServer(
    val name: String,
    val url: String,
)

data class GomunimeMediaCandidate(
    val url: String,
    val name: String,
    val referer: String,
    val isHls: Boolean,
)
