package com.nodrakorid

internal data class NoDrakorIDCategory(
    val path: String,
    val name: String
)

internal data class NoDrakorIDServer(
    val label: String,
    val url: String,
    val referer: String,
    val source: String = "html"
)

internal data class NoDrakorIDAjaxPlayer(
    val postId: String,
    val type: String,
    val nume: String,
    val label: String
)
