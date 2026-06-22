package com.nodrakorid

import com.lagradost.cloudstream3.USER_AGENT
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

internal object NoDrakorIDUtils {
    private const val ACTIVE_HOST = "178.128.210.29"
    private const val LEGACY_HOST = "richemmerson.com"

    val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    fun encode(value: String): String = URLEncoder.encode(value.trim(), "UTF-8")

    fun pageUrl(path: String, page: Int): String {
        val cleanPath = if (path.isBlank()) "/" else path
        val base = absoluteUrl(NoDrakorIDSepeda.MAIN_URL, cleanPath) ?: NoDrakorIDSepeda.MAIN_URL
        if (page <= 1) return base
        return "${base.trimEnd('/')}/page/$page/"
    }

    fun absoluteUrl(base: String, raw: String?): String? {
        val value = cleanUrlText(raw ?: return null)
        if (value.isBlank() || value == "#" || value.startsWith("javascript:", true) || value.startsWith("mailto:", true)) return null
        val resolved = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("/") -> NoDrakorIDSepeda.MAIN_URL.trimEnd('/') + value
            else -> runCatching { URI(base).resolve(value).toString() }.getOrNull()
        }?.trim() ?: return null
        return if (isNoDrakorUrl(resolved)) fetchUrl(resolved) else resolved
    }

    private fun fetchUrl(raw: String): String {
        val uri = runCatching { URI(cleanUrlText(raw)) }.getOrNull() ?: return raw
        val host = uri.host.orEmpty().removePrefix("www.").lowercase()
        if (!isSourceHost(host)) return raw
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return NoDrakorIDSepeda.MAIN_URL.trimEnd('/') + path + query
    }

    fun cleanTitle(raw: String?): String = cleanText(raw)
        .replace(Regex("(?i)^permalink\\s+to:\\s*"), "")
        .replace(Regex("(?i)^nonton\\s+(film\\s+)?"), "")
        .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
        .replace(Regex("(?i)\\s+sub\\s+indo.*$"), "")
        .replace(Regex("(?i)\\s+-\\s+NODRAKOR.*$"), "")
        .trim(' ', '-', '|')

    fun cleanText(raw: String?): String = raw.orEmpty()
        .replace("\u00a0", " ")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun cleanUrlText(raw: String): String = decodeHtml(raw)
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003a", ":")
        .replace("\\u002f", "/")
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .trim(' ', '\n', '\r', '\t', '"', '\'', '`')

    fun decodeHtml(raw: String): String = raw
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&#38;", "&")
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    fun decodeUrlRepeated(raw: String, rounds: Int = 4): String {
        var current = cleanUrlText(raw)
        repeat(rounds) {
            val decoded = runCatching { URLDecoder.decode(current, "UTF-8") }.getOrElse { current }
            if (decoded == current) return current
            current = decoded
        }
        return current
    }

    fun decodeKnownRedirect(raw: String): String {
        val fixed = decodeUrlRepeated(raw)
        val urlParam = Regex("""[?&](?:url|u|to|target|link|redirect|redirect_to|r)=([^&]+)""", RegexOption.IGNORE_CASE)
            .find(fixed)?.groupValues?.getOrNull(1)
        val decoded = urlParam?.let { decodeUrlRepeated(it) }
        return if (!decoded.isNullOrBlank() && decoded.startsWith("http", true)) decoded else fixed
    }

    fun pickImage(base: String, image: Element?, container: Element? = null): String? {
        val values = listOfNotNull(
            image?.attr("abs:src"),
            image?.attr("data-src"),
            image?.attr("data-lazy-src"),
            image?.attr("data-original"),
            image?.attr("data-wpfc-original-src"),
            image?.attr("src"),
            container?.attr("data-bg"),
            container?.attr("data-background"),
            container?.attr("style")?.let { Regex("url\\(([^)]+)\\)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1) }
        )
        return values.firstNotNullOfOrNull { absoluteUrl(base, it) }
    }

    fun extractMetaImage(base: String, doc: org.jsoup.nodes.Document): String? {
        val content = listOf(
            "meta[property=og:image]",
            "meta[name=twitter:image]",
            "meta[itemprop=image]"
        ).firstNotNullOfOrNull { selector -> doc.selectFirst(selector)?.attr("content")?.takeIf { it.isNotBlank() } }
        return absoluteUrl(base, content) ?: pickImage(base, doc.selectFirst(".poster img, .thumb img, .image img, article img, img.wp-post-image"), null)
    }

    fun isNoDrakorUrl(url: String): Boolean = runCatching { isSourceHost(URI(url).host.orEmpty()) }.getOrDefault(false)

    private fun isSourceHost(host: String): Boolean {
        val clean = host.removePrefix("www.").lowercase()
        return clean == ACTIVE_HOST || clean == LEGACY_HOST
    }

    fun isContentUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!isNoDrakorUrl(url)) return false
        val path = runCatching { URI(url).path.orEmpty().trimEnd('/') }.getOrDefault("")
        if (path.isBlank() || path == "/") return false
        if (listOf(
                "/genre/", "/country/", "/year/", "/tag/", "/page/", "/category/",
                "/cast/", "/director/", "/author/", "/feed/", "/wp-", "/dmca", "/privacy", "/contact"
            ).any { lower.contains(it) }
        ) return false
        if (listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".css", ".js", ".ico").any { lower.substringBefore('?').endsWith(it) }) return false
        return path.substringAfterLast('/').isNotBlank()
    }

    fun typeFrom(url: String, title: String, text: String = ""): com.lagradost.cloudstream3.TvType {
        val probe = "$url $title $text".lowercase()
        return when {
            probe.contains("/tv/") || probe.contains("/series/") || probe.contains("tv show") || Regex("(?i)eps?\\s*\\d+").containsMatchIn(probe) -> com.lagradost.cloudstream3.TvType.TvSeries
            probe.contains("korea") || probe.contains("drakor") || probe.contains("drama") -> com.lagradost.cloudstream3.TvType.AsianDrama
            else -> com.lagradost.cloudstream3.TvType.Movie
        }
    }

    fun extractYear(text: String?): Int? = Regex("""\b(19\d{2}|20\d{2})\b""").find(text.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun extractDuration(text: String?): Int? {
        val probe = text.orEmpty()
        Regex("""(?i)(\d{2,3})\s*(?:min|menit|minute)""").find(probe)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val hourMin = Regex("""(?i)(\d+)\s*h\s*(\d+)\s*m""").find(probe)
        if (hourMin != null) {
            val h = hourMin.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
            val m = hourMin.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            return h * 60 + m
        }
        return null
    }

    fun extractRating(text: String?): Double? = Regex("""\b(\d(?:\.\d{1,3})?|10(?:\.0+)?)\b""").find(text.orEmpty())?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    fun episodeNumber(text: String?): Int? = Regex("""(?i)(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""").find(text.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
    fun seasonNumber(text: String?): Int? = Regex("""(?i)(?:season|s)\s*[-:]?\s*(\d+)""").find(text.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun hasUnsupportedOnlyPlayer(html: String): Boolean {
        val lower = html.lowercase()
        val hasUnsupported = listOf("watch.asiaplayer.site", "bulsis.net/go/").any { lower.contains(it) }
        val hasSupported = listOf("sf21.vidplayer.live", "minochinos.com", "dintezuvio.com", ".m3u8", ".mp4").any { lower.contains(it) }
        return hasUnsupported && !hasSupported
    }
}
