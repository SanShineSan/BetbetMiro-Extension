package com.reynime

import java.net.URI

object ReynimeUtils {
    fun fixUrl(base: String, url: String): String {
        return try {
            URI(base).resolve(url).toString()
        } catch (_: Exception) {
            url
        }
    }
}
