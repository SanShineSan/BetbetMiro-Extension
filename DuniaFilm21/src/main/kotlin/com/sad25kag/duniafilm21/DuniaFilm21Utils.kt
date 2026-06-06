package com.sad25kag.duniafilm21

import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

internal fun String.cleanDf21(): String {
    return replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#038;", "&")
        .replace("&#8217;", "'")
        .replace("&#8211;", "-")
        .replace("&#8212;", "-")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

internal fun String.cleanTitleDf21(): String {
    return cleanDf21()
        .replace(Regex("""(?i)\s*[-|]\s*(DuniaFilm21|Dunia Film 21|Nonton Film).*$"""), "")
        .replace(Regex("""(?i)^\s*Nonton\s+(Film|Movie|Serial|Drama)\s+"""), "")
        .replace(Regex("""(?i)\s+(Subtitle\s+Indonesia|Sub\s+Indo|Streaming|Download).*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

internal fun String?.absUrlDf21(baseUrl: String): String? {
    val raw = this?.cleanDf21()?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (raw.startsWith("javascript:", true) || raw.startsWith("data:", true) || raw.startsWith("about:", true) || raw == "#") return null
    if (raw.contains("\${") || raw.startsWith(",")) return null

    return when {
        raw.startsWith("//") -> "${runCatching { URI(baseUrl).scheme }.getOrNull() ?: "https"}:$raw"
        raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
        else -> runCatching { URI(baseUrl).resolve(raw).toString() }.getOrNull()
    }
}

internal fun String.urlEncodeDf21(): String = URLEncoder.encode(trim(), "UTF-8")

internal fun String.sameHostDf21(baseUrl: String): Boolean {
    return runCatching {
        val baseHost = URI(baseUrl).host.orEmpty().removePrefix("www.")
        val host = URI(this).host.orEmpty().removePrefix("www.")
        host.equals(baseHost, ignoreCase = true)
    }.getOrDefault(false)
}

internal fun String.isVideoUrlDf21(): Boolean {
    val lower = lowercase()
    return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mpd") || lower.contains(".txt")
}

internal fun String.isSubtitleUrlDf21(): Boolean {
    val lower = lowercase()
    return lower.contains(".srt") || lower.contains(".vtt") || lower.contains(".ass")
}

internal fun String.isNoiseUrlDf21(): Boolean {
    val lower = lowercase()
    return lower.contains("facebook.com") ||
        lower.contains("instagram.com") ||
        lower.contains("twitter.com") ||
        lower.contains("t.me") ||
        lower.contains("telegram") ||
        lower.contains("youtube.com") ||
        lower.contains("youtu.be") ||
        lower.contains("googletagmanager") ||
        lower.contains("google-analytics") ||
        lower.contains("googlesyndication") ||
        lower.contains("doubleclick") ||
        lower.contains("histats") ||
        lower.contains("cdn.jsdelivr.net") ||
        lower.contains("cdnjs.cloudflare.com") ||
        lower.contains("fonts.googleapis.com") ||
        lower.contains("fonts.gstatic.com") ||
        lower.contains("static.cloudflareinsights.com") ||
        lower.endsWith(".css") ||
        lower.endsWith(".js") ||
        lower.endsWith(".ico") ||
        lower.endsWith(".svg") ||
        lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".png") ||
        lower.endsWith(".webp")
}

internal fun String.posterCandidateScoreDf21(): Int {
    val lower = lowercase()
    var score = 0
    if (lower.contains("poster")) score += 12
    if (lower.contains("thumb")) score += 10
    if (lower.contains("cover")) score += 8
    if (lower.contains("upload")) score += 5
    if (lower.contains(".webp")) score += 4
    if (lower.contains(".jpg") || lower.contains(".jpeg")) score += 3
    if (lower.contains("logo") || lower.contains("favicon") || lower.contains("blank") || lower.contains("placeholder")) score -= 50
    return score
}

internal fun String.qualityDf21(): Int {
    val value = lowercase()
    val number = Regex("""(2160|1440|1080|720|480|360|240)""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return when (number) {
        2160 -> Qualities.P2160.value
        1440 -> Qualities.P1080.value
        1080 -> Qualities.P1080.value
        720 -> Qualities.P720.value
        480 -> Qualities.P480.value
        360 -> Qualities.P360.value
        240 -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }
}

internal fun decodeBase64Df21(raw: String): String? {
    return runCatching { String(Base64.getDecoder().decode(raw), Charsets.UTF_8) }.getOrNull()
}

internal fun String.isSeriesLikeDf21(): Boolean {
    val lower = lowercase()
    return lower.contains("/series") ||
        lower.contains("/tv") ||
        lower.contains("drama-korea") ||
        lower.contains("drakor") ||
        lower.contains("season") ||
        lower.contains("episode") ||
        lower.contains("eps")
}
