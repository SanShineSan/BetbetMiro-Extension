package com.dgs

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

object DGSUtils {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "${DGSSeeds.MAIN_URL}/"
    )

    val headers: Map<String, String> = siteHeaders

    fun playerHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to referer
    ).filterValues { it.isNotBlank() }

    fun videoHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Origin" to originOf(referer).orEmpty(),
        "Referer" to referer
    ).filterValues { it.isNotBlank() }

    fun cleanText(value: String?): String {
        return value.orEmpty()
            .replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    fun decodeUrl(value: String): String {
        val cleaned = value
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
            .replace("\\\"", "\"")
        return runCatching { URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
    }

    fun absoluteUrl(baseUrl: String, value: String?): String? {
        val raw = decodeUrl(value.orEmpty())
            .trim()
            .trim('"', '\'', ',', ';')
        if (isPseudoUrl(raw)) return null
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw
        return runCatching { URI(baseUrl).resolve(raw).toString() }.getOrNull()
            ?: originOf(baseUrl)?.trimEnd('/')?.plus("/")?.plus(raw.trimStart('/'))
    }

    fun pageUrl(mainUrl: String, data: String, page: Int): String {
        val path = data.ifBlank { "/home/" }
        val normalized = if (path.startsWith("http", true)) path else mainUrl.trimEnd('/') + "/" + path.trimStart('/')
        if (page <= 1) return normalized
        return when {
            normalized.endsWith("/") -> normalized + "page/$page/"
            normalized.contains("?") -> normalized + "&page=$page"
            else -> normalized.trimEnd('/') + "/page/$page/"
        }
    }

    fun searchUrl(mainUrl: String, query: String): String = "$mainUrl/search/${query.urlEncoded()}/"

    fun isDGSUrl(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault(url.lowercase(Locale.ROOT))
        return host == "deepgoretube.site" || host.endsWith(".deepgoretube.site")
    }

    fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT).substringBefore('#')
        if (!isDGSUrl(lower)) return false
        val path = runCatching { URI(lower).path.orEmpty() }.getOrDefault("").trim('/')
        if (path.isBlank()) return false
        if (path.startsWith("video/")) return true
        if (path.contains('/')) return false
        return !isCatalogPageUrl(lower) && !isBlockedUrl(lower)
    }

    fun isCatalogPageUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT).substringBefore('#')
        return listOf(
            "/home", "/category/", "/categories/", "/tag/", "/tags/", "/search", "/latest", "/popular",
            "/members", "/channels", "/playlist", "/playlists", "/pornstars", "/models", "/page/"
        ).any { lower.contains(it) }
    }

    fun isBlockedUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return listOf(
            "/login", "/signup", "/register", "/user/", "/users/", "/account", "/upload", "/feedback",
            "/terms", "/privacy", "/dmca", "/contact", "/about", "/advert", "/static/", "/wp-",
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".css", ".js"
        ).any { lower.contains(it) }
    }

    fun isUsablePosterUrl(url: String?): Boolean {
        val lower = url.orEmpty().trim().lowercase(Locale.ROOT)
        if (isPseudoUrl(lower)) return false
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (lower.startsWith("data:") || lower.contains("base64,")) return false
        if (lower.endsWith(".svg") || lower.contains("placeholder") || lower.contains("blank.gif") || lower.contains("no-image")) return false
        return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
            lower.contains(".webp") || lower.contains("/thumb") || lower.contains("/upload") || lower.contains("image")
    }

    fun cleanTitle(title: String?): String {
        return cleanText(title)
            .replace(Regex("(?i)\\s+-\\s+deepgoretube.*$"), "")
            .replace(Regex("(?i)\\s+-\\s+dgs.*$"), "")
            .trim()
    }

    fun titleFromSlug(url: String): String {
        return url.substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }
            .trim()
    }

    fun qualityFromText(value: String?): Int {
        val raw = value.orEmpty()
        Regex("(2160|1440|1080|720|480|360|240)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return com.lagradost.cloudstream3.utils.Qualities.Unknown.value
    }

    fun decodePossibleBase64(value: String): String? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        val unescaped = decodeUrl(raw)
        when {
            unescaped.startsWith("http", true) || unescaped.startsWith("//") -> return unescaped
            unescaped.startsWith("<iframe", true) -> return unescaped
            unescaped.contains("iframe", true) && unescaped.contains("src", true) -> return unescaped
        }
        return runCatching {
            val padded = unescaped.padEnd(unescaped.length + ((4 - unescaped.length % 4) % 4), '=')
            String(Base64.getDecoder().decode(padded))
        }.getOrNull()
    }

    fun isPseudoUrl(value: String?): Boolean {
        val raw = value.orEmpty().trim().lowercase(Locale.ROOT)
        return raw.isBlank() || raw == "#" || raw == "null" || raw == "undefined" ||
            raw.startsWith("javascript:") || raw.startsWith("about:") || raw.startsWith("data:") ||
            raw.startsWith("blob:") || raw.startsWith("intent:") || raw == "about:blank"
    }

    fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme ?: return@runCatching null
        val host = uri.host ?: return@runCatching null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()
}
