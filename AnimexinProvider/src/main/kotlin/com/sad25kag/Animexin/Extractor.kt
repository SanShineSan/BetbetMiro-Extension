package com.sad25kag.Animexin

fun Http(url: String): String {
    return if (url.startsWith("//")) {
        "https:$url"
    } else {
        url
    }
}
