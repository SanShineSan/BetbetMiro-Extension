package com.gomunime

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

object GomunimeUtils {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "${GomunimeSeeds.MAIN_URL}/"
    )

    fun cleanText(value: String?): String {
        return value.orEmpty()
            .replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun String.urlEncoded(): String {
        return URLEncoder.encode(this, "UTF-8")
    }

    fun absoluteUrl(baseUrl: String, value: String?): String? {
        val raw = value.orEmpty()
            .trim()
            .trim('"', '\'', ',', ';')
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")

        if (raw.isBlank() || raw == "#" || raw.equals("null", true) || raw.startsWith("javascript:", true)) return null
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("/")) return baseUrl.trimEnd('/') + raw
        return runCatching { URI(baseUrl).resolve(raw).toString() }.getOrNull()
            ?: baseUrl.trimEnd('/') + "/" + raw
    }

    fun pageUrl(mainUrl: String, data: String, page: Int): String {
        val path = data.ifBlank { "/" }
        val normalized = if (path.startsWith("http")) path else mainUrl.trimEnd('/') + "/" + path.trimStart('/')
        if (page <= 1) return normalized

        val separator = if (normalized.contains("?")) "&" else "?"
        return "$normalized${separator}page=$page"
    }

    fun searchUrl(mainUrl: String, query: String): String {
        return "$mainUrl/?s=${query.urlEncoded()}"
    }

    fun isAnimeDetailUrl(url: String): Boolean {
        val normalized = url.lowercase(Locale.ROOT)
        if (!normalized.contains("gomunime.top")) return false
        if (normalized.contains("/genre/")) return false
        if (normalized.contains("/genres/")) return false
        if (normalized.contains("/status/")) return false
        if (normalized.contains("/type/")) return false
        if (normalized.contains("/koleksi/")) return false
        if (normalized.contains("/download")) return false
        if (normalized.contains("/tag/")) return false
        if (normalized.contains("?s=")) return false
        return true
    }

    fun decodeBase64Html(value: String): String? {
        val normalized = value.trim()
        if (normalized.isBlank()) return null

        return runCatching {
            String(Base64.getDecoder().decode(normalized))
        }.getOrNull()
            ?: runCatching {
                String(Base64.getUrlDecoder().decode(normalized))
            }.getOrNull()
    }

    fun decodeUrl(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    fun episodeNumber(value: String?): Int? {
        return Regex("""(?i)(?:episode|eps|ep)\s*[-:]?\s*(\d+)""").find(value.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?:^|[^\d])(\d{1,4})(?:[^\d]|$)""").find(value.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun typeFromText(value: String?): com.lagradost.cloudstream3.TvType {
        val text = value.orEmpty().lowercase(Locale.ROOT)
        return when {
            text.contains("movie") -> com.lagradost.cloudstream3.TvType.AnimeMovie
            text.contains("ova") || text.contains("ona") || text.contains("special") -> com.lagradost.cloudstream3.TvType.OVA
            else -> com.lagradost.cloudstream3.TvType.Anime
        }
    }

    fun statusFromText(value: String?): com.lagradost.cloudstream3.ShowStatus {
        val text = value.orEmpty().lowercase(Locale.ROOT)
        return when {
            text.contains("ongoing") -> com.lagradost.cloudstream3.ShowStatus.Ongoing
            text.contains("tamat") || text.contains("completed") -> com.lagradost.cloudstream3.ShowStatus.Completed
            else -> com.lagradost.cloudstream3.ShowStatus.Completed
        }
    }
}
