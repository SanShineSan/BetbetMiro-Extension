package com.surgefilm21

import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

internal fun String.cleanSf21(): String {
    return replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#038;", "&")
        .replace("&#8217;", "'")
        .replace("&#8211;", "-")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

internal fun String.cleanTitleSf21(): String {
    return cleanSf21()
        .replace(Regex("""(?i)\s*-\s*Surgafilm21.*$"""), "")
        .replace(Regex("""(?i)\s*Nonton\s+Film\s+"""), "")
        .replace(Regex("""(?i)\s+Subtitle\s+Indonesia.*$"""), "")
        .replace(Regex("""(?i)\s+Sub\s+Indo.*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

internal fun String?.absUrlSf21(baseUrl: String): String? {
    val raw = this?.cleanSf21()?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (raw.startsWith("javascript:", true) || raw.startsWith("data:", true) || raw == "#") return null

    return when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
        raw.startsWith("/") -> runCatching { URI(baseUrl).resolve(raw).toString() }.getOrNull()
        else -> runCatching { URI(baseUrl).resolve(raw).toString() }.getOrNull()
    }
}

internal fun String.urlEncodeSf21(): String = URLEncoder.encode(this.trim(), "UTF-8")

internal fun String.isVideoUrlSf21(): Boolean {
    val lower = lowercase()
    return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mpd")
}

internal fun String.isNoiseUrlSf21(): Boolean {
    val lower = lowercase()
    return lower.contains("facebook.com") ||
        lower.contains("telegram") ||
        lower.contains("instagram") ||
        lower.contains("twitter.com") ||
        lower.contains("youtube.com") ||
        lower.contains("doubleclick") ||
        lower.contains("googlesyndication") ||
        lower.contains("google-analytics") ||
        lower.endsWith(".css") ||
        lower.endsWith(".js") ||
        lower.endsWith(".ico") ||
        lower.endsWith(".svg") ||
        lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".png") ||
        lower.endsWith(".webp")
}

internal fun String.posterCandidateScoreSf21(): Int {
    val lower = lowercase()
    var score = 0
    if (lower.contains("poster")) score += 12
    if (lower.contains("thumb")) score += 10
    if (lower.contains("backdrop")) score += 8
    if (lower.contains("upload")) score += 5
    if (lower.contains(".webp")) score += 4
    if (lower.contains(".jpg") || lower.contains(".jpeg")) score += 3
    if (lower.contains("logo") || lower.contains("favicon") || lower.contains("blank") || lower.contains("placeholder")) score -= 50
    return score
}

internal fun String.qualitySf21(): Int {
    val value = lowercase()
    val number = Regex("""(\d{3,4})""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
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

internal fun decodeBase64Sf21(raw: String): String? {
    return runCatching { String(Base64.getDecoder().decode(raw)) }.getOrNull()
}

internal fun String.isSeriesLikeSf21(): Boolean {
    val lower = lowercase()
    return lower.contains("/series") || lower.contains("/tv/") || lower.contains("season") || lower.contains("episode") || lower.contains("eps")
}
