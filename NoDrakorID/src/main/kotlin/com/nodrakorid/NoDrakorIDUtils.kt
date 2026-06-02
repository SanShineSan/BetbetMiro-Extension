package com.nodrakorid

import com.lagradost.cloudstream3.USER_AGENT
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

internal object NoDrakorIDUtils {
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
        val trimmed = base.trimEnd('/')
        return "$trimmed/page/$page/"
    }

    fun absoluteUrl(base: String, raw: String?): String? {
        val value = cleanUrlText(raw ?: return null)
        if (value.isBlank() || value == "#" || value.startsWith("javascript:", true) || value.startsWith("mailto:", true)) return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("/") -> NoDrakorIDSepeda.MAIN_URL.trimEnd('/') + value
            else -> runCatching { URI(base).resolve(value).toString() }.getOrNull()
        }?.trim()
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

    fun isNoDrakorUrl(url: String): Boolean = runCatching { URI(url).host.orEmpty().contains("richemmerson.com", true) }.getOrDefault(false)

    fun isContentUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith(NoDrakorIDSepeda.MAIN_URL)) return false
        val normalized = lower.substringBefore("#").substringBefore("?").trimEnd('/')
        val root = NoDrakorIDSepeda.MAIN_URL.lowercase().trimEnd('/')
        if (normalized == root) return false
        if (listOf(
                "/genre/", "/country/", "/year/", "/tag/", "/page/", "/category/",
                "/cast/", "/director/", "/author/", "/feed/", "/wp-", "/dmca", "/privacy", "/contact"
            ).any { lower.contains(it) }
        ) return false
        if (listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".css", ".js", ".ico").any { normalized.endsWith(it) }) return false
        return normalized.substringAfterLast('/').isNotBlank()
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

    fun extractLabelNear(element: Element?): String {
        if (element == null) return "NoDrakorID"
        return cleanText(
            element.attr("title").ifBlank { element.attr("aria-label") }
                .ifBlank { element.attr("data-name") }
                .ifBlank { element.attr("data-label") }
                .ifBlank { element.attr("data-server") }
                .ifBlank { element.text() }
        ).take(48).ifBlank { "NoDrakorID" }
    }

    fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrNull()

    fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty().removePrefix("www.").lowercase() }.getOrDefault("")

    fun looksDirectVideo(url: String): Boolean {
        val lower = url.lowercase().substringBefore("#")
        return listOf(".m3u8", ".mp4", ".mkv", ".mpd", ".webm").any { lower.substringBefore('?').endsWith(it) } ||
            lower.contains("googlevideo.com/videoplayback") || lower.contains("/get_video?") || lower.contains("videoplayback?")
    }

    fun isHls(url: String): Boolean = url.lowercase().contains(".m3u8") || url.lowercase().contains("application/x-mpegurl")

    fun isBadAssetUrl(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?')
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".css", ".js", ".ico", ".woff", ".ttf").any { path.endsWith(it) } ||
            listOf("doubleclick", "googletagmanager", "google-analytics", "facebook.com", "twitter.com", "youtube.com/embed", "youtube-nocookie.com/embed").any { lower.contains(it) }
    }

    fun isKnownPlayableHost(url: String): Boolean {
        val host = hostOf(url)
        return listOf(
            "jeniusplay", "majorplay", "m3u8play", "e2eplay", "streamwish", "filemoon", "dood", "doodstream", "streamtape", "mp4upload",
            "hglink", "ghbrisk", "dhcplay", "streamcasthub", "dm21", "meplayer", "gdplayer", "filepress", "blogger.com", "googleusercontent",
            "googlevideo", "video.google", "lulu", "lulustream", "vidhide", "vidguard", "voe", "mixdrop", "upstream", "filelions", "vidsrc", "embedwish", "player4u",
            "abyssplayer", "abyss.to", "sssrr.org"
        ).any { host.contains(it) }
    }

    fun isShortenerUrl(url: String): Boolean {
        val host = hostOf(url)
        return listOf("semawur", "safelinku", "ouo", "shrink", "short", "linkvertise", "droplink", "terabox").any { host.contains(it) }
    }

    fun isHtmlLandingUrl(url: String): Boolean = !looksDirectVideo(url) && (isKnownPlayableHost(url) || isShortenerUrl(url) || url.startsWith("http", true))

    fun extractUrlsFromText(base: String, text: String): List<String> {
        val normalized = decodeUrlRepeated(text).replace("\\/", "/")
        val regex = Regex("""https?:\\?/\\?/[^\"'<>\s)\\]+|//[^\"'<>\s)\\]+""", RegexOption.IGNORE_CASE)
        return regex.findAll(normalized).mapNotNull { match ->
            val raw = match.value.replace("\\/", "/").trimEnd(',', ';', '.', ')', ']', '}')
            absoluteUrl(base, raw)
        }.distinct().toList()
    }

    fun decodeBase64Payloads(text: String): List<String> {
        val output = linkedSetOf<String>()
        val regexes = listOf(
            Regex("""atob\(["']([A-Za-z0-9+/=_-]{20,})["']\)""", RegexOption.IGNORE_CASE),
            Regex("""base64[,=:\s]+["']?([A-Za-z0-9+/=_-]{40,})["']?""", RegexOption.IGNORE_CASE),
            Regex("""["']([A-Za-z0-9+/=_-]{80,})["']""")
        )
        regexes.forEach { regex ->
            regex.findAll(text).forEach { match ->
                val value = match.groupValues.getOrNull(1).orEmpty()
                val decoded = runCatching {
                    val fixed = value.replace('-', '+').replace('_', '/')
                    val padded = fixed + "=".repeat((4 - fixed.length % 4) % 4)
                    String(Base64.getDecoder().decode(padded))
                }.getOrNull()
                if (!decoded.isNullOrBlank() && decoded.contains("http", true)) output += decoded
            }
        }
        return output.toList()
    }

    fun videoHeaders(referer: String): Map<String, String> = mapOf(
        "Referer" to referer,
        "User-Agent" to USER_AGENT,
        "Origin" to (originOf(referer) ?: NoDrakorIDSepeda.MAIN_URL)
    )
}
