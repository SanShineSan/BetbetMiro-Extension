package com.kingbokep

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object KingBokepUtils {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "${KingBokepSeeds.MAIN_URL}/"
    )

    fun videoHeaders(referer: String): Map<String, String> {
        val origin = originOf(referer) ?: KingBokepSeeds.MAIN_URL
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to origin,
            "Referer" to referer
        )
    }

    fun cleanText(value: String?): String {
        return value.orEmpty()
            .replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    fun decodeUrl(value: String?): String {
        val raw = value.orEmpty()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
            .trim('"', '\'', ',', ';', ')', '(')

        if (raw.isBlank()) return ""

        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Throwable) {
            raw
        }
    }

    fun absoluteUrl(baseUrl: String, value: String?): String? {
        val raw = decodeUrl(value)
        if (raw.isBlank() || isPseudoUrl(raw)) return null
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw

        val origin = originOf(baseUrl) ?: KingBokepSeeds.MAIN_URL
        if (raw.startsWith("/")) return origin.trimEnd('/') + raw

        return try {
            URI(baseUrl).resolve(raw).toString()
        } catch (_: Throwable) {
            origin.trimEnd('/') + "/" + raw.trimStart('/')
        }
    }

    fun originOf(url: String): String? {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            "$scheme://$host"
        } catch (_: Throwable) {
            null
        }
    }

    fun pageUrl(mainUrl: String, data: String, page: Int): String {
        val raw = if (data.startsWith("http")) data else mainUrl.trimEnd('/') + "/" + data.trimStart('/')
        if (page <= 1) {
            return if (raw.contains("%d")) raw.format(1).replace("/page/1/", "/") else raw
        }
        return when {
            raw.contains("%d") -> raw.format(page)
            raw.contains("?") -> raw + "&page=$page"
            raw.endsWith("/") -> raw + "page/$page/"
            else -> raw.trimEnd('/') + "/page/$page/"
        }
    }

    fun searchUrl(mainUrl: String, query: String): String {
        return "$mainUrl/search/?keyword=${query.urlEncoded()}"
    }

    fun isKingHost(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("kingbokep.tv")
    }

    fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase().substringBefore("#")
        return isKingHost(lower) && lower.contains("/view/") && !isCatalogUrl(lower)
    }

    fun isCatalogUrl(url: String): Boolean {
        val lower = url.lowercase().substringBefore("#")
        return lower.contains("/category/") ||
            lower.contains("/search") ||
            lower.contains("/privacy") ||
            lower.contains("/dmca") ||
            lower.contains("/contact") ||
            lower.contains("/terms") ||
            lower.contains("t.me")
    }

    fun isPseudoUrl(value: String?): Boolean {
        val lower = value.orEmpty().trim().lowercase()
        return lower.isBlank() || lower == "#" || lower == "null" || lower == "undefined" ||
            lower == "about:blank" || lower.startsWith("javascript:") || lower.startsWith("data:") ||
            lower.startsWith("blob:") || lower.startsWith("intent:") || lower.startsWith("mailto:") ||
            lower.startsWith("tel:")
    }


    fun encodeLoadData(data: KingBokepLoadData): String {
        return buildString {
            append('{')
            append("\"url\":\"").append(jsonEscape(data.url.orEmpty())).append("\"")
            data.id?.takeIf { it.isNotBlank() }?.let {
                append(",\"id\":\"").append(jsonEscape(it)).append("\"")
            }
            data.title?.takeIf { it.isNotBlank() }?.let {
                append(",\"title\":\"").append(jsonEscape(it)).append("\"")
            }
            append('}')
        }
    }

    fun decodeLoadData(data: String): KingBokepLoadData? {
        val raw = data.trim()
        if (!raw.startsWith("{")) return null
        return KingBokepLoadData(
            url = readJsonString(raw, "url"),
            id = readJsonString(raw, "id"),
            title = readJsonString(raw, "title")
        )
    }

    private fun readJsonString(json: String, key: String): String? {
        val match = Regex("\\\"" + Regex.escape(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").find(json)
            ?: return null
        return jsonUnescape(match.groupValues[1]).takeIf { it.isNotBlank() }
    }

    private fun jsonEscape(value: String): String {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun jsonUnescape(value: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    '\\' -> out.append('\\')
                    '"' -> out.append('"')
                    '/' -> out.append('/')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> {
                        val hex = value.substring(index + 2, (index + 6).coerceAtMost(value.length))
                        out.append(hex.toIntOrNull(16)?.toChar() ?: next)
                        index += 4
                    }
                    else -> out.append(next)
                }
                index += 2
            } else {
                out.append(current)
                index++
            }
        }
        return out.toString()
    }

    fun qualityFromText(value: String?): Int {
        val lower = value.orEmpty().lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> 2160
            lower.contains("1440") -> 1440
            lower.contains("1080") || lower.contains("fullhd") -> 1080
            lower.contains("720") || lower.contains("hd") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            lower.contains("240") -> 240
            else -> 0
        }
    }

    fun durationMinutes(value: String?): Int? {
        val text = cleanText(value)
        if (text.isBlank()) return null
        val parts = text.split(":").mapNotNull { it.trim().toIntOrNull() }
        if (parts.isEmpty()) return null
        return when (parts.size) {
            3 -> parts[0] * 60 + parts[1] + if (parts[2] > 0) 1 else 0
            2 -> parts[0] + if (parts[1] > 0) 1 else 0
            1 -> parts[0]
            else -> null
        }
    }
}
