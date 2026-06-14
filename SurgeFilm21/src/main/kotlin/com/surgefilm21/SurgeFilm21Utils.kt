package com.surgefilm21

import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import java.net.URLDecoder
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
        .replace(Regex("""(?i)^\s*Nonton\s+(?:Film\s+|Series\s+)?"""), "")
        .replace(Regex("""(?i)\s+Subtitle\s+Indonesia.*$"""), "")
        .replace(Regex("""(?i)\s+Sub\s+Indo.*$"""), "")
        .replace(Regex("""(?i)\s+Full\s+Movie.*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

internal fun String?.absUrlSf21(baseUrl: String): String? {
    val raw = this?.cleanSf21()?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (raw.startsWith("javascript:", true) || raw.startsWith("data:", true) || raw.startsWith("about:", true) || raw == "#") return null
    if (raw.contains("\${") || raw.contains(" ") || raw.startsWith(",")) return null

    return when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
        raw.startsWith("/") -> runCatching { URI(baseUrl).resolve(raw).toString() }.getOrNull()
        else -> runCatching { URI(baseUrl).resolve(raw).toString() }.getOrNull()
    }
}

internal fun String.urlEncodeSf21(): String = URLEncoder.encode(this.trim(), "UTF-8")

internal fun String.urlDecodeSf21(): String = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)

internal fun String.isSf21Host(): Boolean {
    val host = runCatching { URI(this).host.orEmpty().lowercase() }.getOrDefault("")
    return host == "surgafilm21.website" || host == "www.surgafilm21.website" || host == "surgafilm21.homes" || host == "www.surgafilm21.homes"
}

internal fun String.isCatalogUrlSf21(): Boolean {
    if (!isSf21Host()) return false
    val path = runCatching { URI(this).path.orEmpty().trim('/').lowercase() }.getOrDefault("")
    if (path.isBlank()) return false

    val rejectExact = setOf(
        "series", "populer", "popular", "latest", "recommendation", "rekomendasi",
        "top-imdb", "filter", "filter-movie", "watchlist", "amp"
    )
    if (path in rejectExact) return false

    val rejectPrefixes = listOf(
        "genre/", "country/", "year/", "page/", "assets/", "search", "tag/",
        "privacy", "dmca", "contact", "series/ongoing", "series/completed",
        "series/asian", "series/west", "series/episode/"
    )
    if (rejectPrefixes.any { path.startsWith(it) }) return false

    return if (path.startsWith("series/")) {
        true
    } else {
        Regex("""-(?:19|20)\d{2}/?$""").containsMatchIn(path)
    }
}

internal fun String.isHlsManifestUrlSf21(): Boolean {
    val lower = lowercase().substringBefore("#")
    return lower.contains(".m3u8") ||
        lower.contains("/master.txt") ||
        lower.contains("/index") && lower.contains(".txt") && lower.contains("/hls")
}

internal fun String.isVideoUrlSf21(): Boolean {
    val lower = lowercase()
    return isHlsManifestUrlSf21() || lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mpd")
}

internal fun String.isNoiseUrlSf21(): Boolean {
    val lower = lowercase()
    return lower.contains("facebook.com") ||
        lower.contains("api.whatsapp.com") ||
        lower.contains("twitter.com") ||
        lower.contains("t.me/share") ||
        lower.contains("pinterest.com") ||
        lower.contains("ads.d21.media") ||
        lower.contains("pixel.morphify.net") ||
        lower.contains("poster.sf21.space/aff") ||
        lower.contains("static.cloudflareinsights.com") ||
        lower.contains("cdnjs.cloudflare.com") ||
        lower.contains("cdn.jsdelivr.net") ||
        lower.contains("fonts.googleapis.com") ||
        lower.contains("fonts.gstatic.com") ||
        lower.contains("cdn.tailwindcss.com") ||
        lower.contains("ui-avatars.com") ||
        lower.contains("googletagmanager.com") ||
        lower.contains("google-analytics") ||
        lower.contains("histats.com") ||
        lower.contains("mc.yandex.ru") ||
        lower.contains("sf21.team") ||
        lower.contains("telegram") ||
        lower.contains("instagram") ||
        lower.contains("youtube.com") ||
        lower.contains("doubleclick") ||
        lower.contains("googlesyndication") ||
        lower.endsWith(".css") ||
        lower.endsWith(".js") ||
        lower.endsWith(".ico") ||
        lower.endsWith(".svg") ||
        lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".png") ||
        lower.endsWith(".webp") ||
        lower.endsWith(".gif") ||
        lower.endsWith(".woff") ||
        lower.endsWith(".woff2")
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
    return lower.contains("/series/") || lower.contains("/tv/") || lower.contains("season") || lower.contains("episode") || lower.contains("eps")
}
