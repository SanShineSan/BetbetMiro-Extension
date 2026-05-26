package com.reynime

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

object ReynimeUtils {
    private val rootRegex = Regex("""^https?://[^/]+""")
    private val duplicateSlashRegex = Regex("""(?<!:)//+""")

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    fun normalizeUrl(value: String?, baseUrl: String, mainUrl: String): String {
        val clean = value.orEmpty().cleanEscaped().trim().trim('"', '\'')
        if (clean.isBlank() || clean.equals("null", true)) return ""
        val normalized = when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> (rootRegex.find(baseUrl)?.value ?: mainUrl) + clean
            clean.startsWith("data:", true) -> ""
            clean.startsWith("javascript:", true) -> ""
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
        return normalized.replace(duplicateSlashRegex, "/")
    }

    fun normalizePoster(value: String?, baseUrl: String, mainUrl: String): String? {
        val url = normalizeUrl(value, baseUrl, mainUrl)
            .replace("http://", "https://")
            .replace("https://myanimelist.net/images/", "https://cdn.myanimelist.net/images/")
        return url.takeIf { it.isImageUrl() }
    }

    fun extractSeriesId(url: String): Int? {
        return Regex("""/series/(\d+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?:series_id|seriesId|id)=([0-9]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun extractSeriesSlug(url: String): String? {
        return Regex("""/series/\d+/([^/?#]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun extractEpisodeNumber(text: String?, fallback: String? = null): Int? {
        val value = text.orEmpty().cleanEscaped()
        listOf(
            Regex("""(?:episode|eps?|ep|bab)\s*[-:#]?\s*(\d{1,4})""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|[^\d])(\d{1,4})(?:\s*(?:sub|subtitle|indo|hd|$))""", RegexOption.IGNORE_CASE),
            Regex("""^\s*(\d{1,4})\s*$""")
        ).forEach { regex ->
            regex.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return fallback?.filter { it.isDigit() }?.toIntOrNull()
    }

    fun buildEpisodeData(
        pageUrl: String,
        seriesId: Int,
        episode: Int,
        episodeId: Int? = null,
        title: String? = null,
        seedSlug: String? = null
    ): String {
        val params = linkedMapOf(
            "seriesId" to seriesId.toString(),
            "episode" to episode.toString()
        )
        episodeId?.let { params["episodeId"] = it.toString() }
        title?.takeIf { it.isNotBlank() }?.let { params["title"] = encode(it) }
        seedSlug?.takeIf { it.isNotBlank() }?.let { params["seedSlug"] = encode(it) }
        return pageUrl.substringBefore("#") + "#reynime=" + params.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    fun parsePlaybackData(data: String, mainUrl: String): ReynimePlaybackData {
        val base = data.substringBefore("#").ifBlank { data.substringBefore("?") }
        val hash = data.substringAfter("#reynime=", "")
        val query = listOf(
            hash,
            data.substringAfter("?", "").substringBefore("#"),
            data.substringAfter("#", "")
        ).filter { it.isNotBlank() }.joinToString("&")

        fun value(vararg keys: String): String? {
            keys.forEach { key ->
                Regex("""(?:^|&)${Regex.escape(key)}=([^&]+)""", RegexOption.IGNORE_CASE)
                    .find(query)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { return decode(it).takeIf { decoded -> decoded.isNotBlank() } }
            }
            return null
        }

        val fromPath = Regex("""/watch/(\d+)/(\d+)""", RegexOption.IGNORE_CASE).find(data)
        val directWatchId = Regex("""/watch/(\d+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE).find(data)?.groupValues?.getOrNull(1)

        val seriesId = value("seriesId", "series_id", "sid") ?: fromPath?.groupValues?.getOrNull(1)
        val episode = value("episode", "episodeNumber", "episode_number", "ep") ?: fromPath?.groupValues?.getOrNull(2)
        val episodeId = value("episodeId", "episode_id", "pid", "id") ?: directWatchId?.takeIf { seriesId == null || fromPath == null }
        val pageUrl = if (base.startsWith("http", true)) base else "$mainUrl/watch/${episodeId ?: seriesId.orEmpty()}"

        return ReynimePlaybackData(
            pageUrl = pageUrl,
            seriesId = seriesId,
            episodeNumber = episode,
            episodeId = episodeId,
            title = value("title", "name"),
            seedSlug = value("seedSlug", "slug")
        )
    }

    fun decodeBase64Payloads(text: String): List<String> {
        val output = linkedSetOf<String>()
        Regex("""(?:atob|Base64\.decode|base64_decode)\s*\(\s*["']([A-Za-z0-9+/=_-]{16,})["']\s*\)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.groupValues[1] }
            .forEach { encoded -> decodeBase64(encoded)?.let(output::add) }

        Regex("""["']([A-Za-z0-9+/=_-]{40,})["']""")
            .findAll(text)
            .map { it.groupValues[1] }
            .take(40)
            .forEach { encoded ->
                decodeBase64(encoded)?.takeIf { it.contains("http", true) || it.contains("iframe", true) || it.contains("video", true) }
                    ?.let(output::add)
            }
        return output.toList()
    }

    private fun decodeBase64(value: String): String? {
        return runCatching {
            val clean = value.replace('-', '+').replace('_', '/')
            val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
            String(Base64.getDecoder().decode(padded))
        }.getOrNull()
    }

    fun String.cleanEscaped(): String = this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003f", "?")
        .replace("\\u002F", "/")
        .replace("&amp;", "&")
        .replace("\\\"", "\"")
        .replace("\\n", " ")
        .trim()

    fun String.cleanTitle(): String = cleanEscaped()
        .replace(Regex("""\s+[-|]\s+Reynime\s*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    fun String.slugify(): String = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun String.isImageUrl(): Boolean = contains(Regex("""\.(?:jpg|jpeg|png|webp|avif)(?:[?#].*)?$""", RegexOption.IGNORE_CASE))

    fun String.isSubtitleUrl(): Boolean = contains(Regex("""\.(?:srt|vtt|ass)(?:[?#].*)?$""", RegexOption.IGNORE_CASE))

    fun String.isDirectVideoUrl(): Boolean = contains(Regex("""\.(?:m3u8|mp4|mkv|webm)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)) || contains("/hls/", true)

    fun shouldSkipUrl(url: String): Boolean {
        val value = url.lowercase()
        if (value.isBlank()) return true
        return value.startsWith("mailto:") || value.startsWith("tel:") ||
            value.contains("google-analytics") || value.contains("googletagmanager") ||
            value.contains("/favicon") || value.contains(".css") || value.contains(".js") ||
            value.contains(".svg") || value.contains(".woff") || value.contains(".ttf") ||
            value.contains("/ads") || value.contains("doubleclick") || value.contains("facebook.com/sharer") ||
            value.isImageUrl()
    }
}
