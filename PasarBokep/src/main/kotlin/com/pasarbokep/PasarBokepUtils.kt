package com.pasarbokep

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object PasarBokepUtils {
    val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    fun videoHeaders(referer: String): Map<String, String> {
        val origin = originOf(referer) ?: "https://pasarbokep.com"
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to origin,
            "Referer" to referer,
        )
    }

    fun cleanText(input: String?): String {
        return input
            ?.replace("\u00a0", " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
    }

    fun encodeQuery(query: String): String {
        return URLEncoder.encode(query.trim(), "UTF-8")
    }

    fun decodeMaybe(value: String?): String {
        val raw = value.orEmpty()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003f", "?")
            .replace("\\u003a", ":")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .trim()
            .trim('"', '\'', ',', ';', ')', '(')

        if (raw.isBlank()) return ""
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    fun absoluteUrl(rawUrl: String?, baseUrl: String): String? {
        val value = decodeMaybe(rawUrl).takeIf { it.isNotBlank() } ?: return null
        if (isPseudoUrl(value)) return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) -> value
            else -> runCatching { URI(baseUrl).resolve(value).toString() }
                .getOrElse {
                    val origin = originOf(baseUrl) ?: baseUrl.trimEnd('/')
                    if (value.startsWith("/")) origin.trimEnd('/') + value else origin.trimEnd('/') + "/" + value.trimStart('/')
                }
        }
    }

    fun originOf(url: String): String? {
        return runCatching {
            val uri = URI(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            "$scheme://$host"
        }.getOrNull()
    }

    fun updateHost(url: String, mainUrl: String): String {
        return try {
            val original = URI(url)
            val target = URI(mainUrl)
            URI(target.scheme, original.userInfo, target.host, target.port, original.path, original.query, original.fragment).toString()
        } catch (_: Throwable) {
            url
        }
    }

    fun pagedUrl(baseUrl: String, page: Int, mainUrl: String): String {
        val fixedBase = absoluteUrl(baseUrl, mainUrl)?.trimEnd('/') ?: mainUrl
        if (page <= 1) return fixedBase
        if (fixedBase.contains("?")) {
            val separator = if (fixedBase.endsWith("?") || fixedBase.endsWith("&")) "" else "&"
            return "$fixedBase${separator}paged=$page"
        }
        return "$fixedBase/page/$page/"
    }

    fun Element.bestImage(mainUrl: String): String? {
        val img = selectFirst("img") ?: return null
        val raw = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("data-img"),
            img.attr("data-thumb"),
            img.attr("data-poster"),
            img.attr("src"),
            img.attr("data-srcset").substringBefore(" "),
            img.attr("srcset").substringBefore(" "),
        ).firstOrNull { it.isNotBlank() }
        return absoluteUrl(raw, mainUrl)?.takeIf { !isBadMediaAsset(it) }
    }

    fun Document.bestPoster(mainUrl: String): String? {
        val fromMeta = listOf(
            selectFirst("meta[property=og:image]")?.attr("content"),
            selectFirst("meta[name=twitter:image]")?.attr("content"),
            selectFirst("link[rel=image_src]")?.attr("href"),
            selectFirst("video[poster]")?.attr("poster"),
        ).firstOrNull { !it.isNullOrBlank() }
        if (!fromMeta.isNullOrBlank()) return absoluteUrl(fromMeta, mainUrl)?.takeIf { !isBadMediaAsset(it) }

        return selectFirst("article img, .entry-content img, .post-content img, .single img, main img, img")
            ?.let { absoluteUrl(it.attr("data-src").ifBlank { it.attr("src") }, mainUrl) }
            ?.takeIf { !isBadMediaAsset(it) }
    }

    fun Document.hasNextPage(): Boolean {
        return select(
            "a.next, a[rel=next], .page-numbers.next, .pagination a:matchesOwn((?i)next|selanjutnya|berikut|›|»), nav a:matchesOwn((?i)next|selanjutnya|berikut|›|»)"
        ).any { it.attr("href").isNotBlank() || it.text().isNotBlank() }
    }

    fun isLikelyVideoPage(url: String, title: String, mainUrl: String): Boolean {
        val cleanTitle = cleanText(title).lowercase()
        if (cleanTitle.length < 3) return false
        if (PasarBokepSeeds.blockedTitleHints.any { cleanTitle == it || cleanTitle.contains(it) }) return false

        val fixed = absoluteUrl(url, mainUrl) ?: return false
        val lower = fixed.lowercase()
        if (!lower.startsWith(mainUrl.lowercase())) return false
        if (PasarBokepSeeds.blockedPathHints.any { hint -> lower.contains(hint) }) return false
        if (isBadMediaAsset(lower)) return false
        return lower.trimEnd('/') != mainUrl.trimEnd('/').lowercase()
    }

    fun titleFromUrl(url: String): String {
        return url
            .substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
            .ifBlank { "PasarBokep" }
    }

    fun directVideoQuality(url: String): Int {
        val lower = url.lowercase()
        return Regex("(2160|1440|1080|720|480|360|240)p?").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: when {
                "4k" in lower -> Qualities.P2160.value
                "fhd" in lower || "fullhd" in lower -> Qualities.P1080.value
                "hd" in lower -> Qualities.P720.value
                else -> Qualities.Unknown.value
            }
    }

    fun isHlsLike(url: String): Boolean {
        val lower = url.substringBefore('#').lowercase()
        return lower.contains(".m3u8") || lower.contains("/hls/") || lower.contains("playlist.m3u")
    }

    fun isDirectVideo(url: String): Boolean {
        val lower = url.substringBefore('#').lowercase()
        return isHlsLike(lower) ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains(".mkv") ||
            lower.contains(".mov") ||
            lower.contains("googlevideo") ||
            lower.contains("videoplayback") ||
            lower.contains("/get_video")
    }

    fun isPotentialExtractor(url: String, mainUrl: String): Boolean {
        val lower = url.lowercase()
        if (isPseudoUrl(lower)) return false
        if (lower.startsWith(mainUrl.lowercase())) return false
        if (shouldSkipUrl(lower)) return false
        if (isBadMediaAsset(lower)) return false
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//")
    }

    fun isKnownHost(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            "streamsb", "sbembed", "sbbrisk", "sbfull", "sblanh", "sbplay", "sbthe", "sbspeed", "sbfast", "sbface", "waaw",
            "dood", "doodstream", "d000d", "ds2play",
            "streamtape", "stape", "strtape",
            "filemoon", "filelions", "streamwish", "wishfast", "vidhide", "vidguard", "voe.sx", "voe.",
            "mixdrop", "mp4upload", "lulustream", "luluvdo", "lulu", "uqload", "streamruby", "wolfstream", "short.ink",
            "embed", "player", "/e/", "/embed/", "/file/"
        ).any { lower.contains(it) }
    }

    fun shouldSkipUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") ||
            lower.contains("twitter.com") ||
            lower.contains("telegram") ||
            lower.contains("whatsapp") ||
            lower.contains("mailto:") ||
            lower.contains("/wp-login") ||
            lower.contains("/wp-admin") ||
            lower.contains("/contact") ||
            lower.contains("/dmca") ||
            lower.contains("/privacy") ||
            lower.contains("/category/") ||
            lower.contains("/tag/") ||
            lower.contains("/author/") ||
            lower.contains("adsterra") ||
            lower.contains("popads") ||
            lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.contains("google-analytics") ||
            lower.contains("cloudflareinsights") ||
            lower.contains("histats")
    }

    fun isPseudoUrl(value: String?): Boolean {
        val lower = value.orEmpty().trim().lowercase()
        return lower.isBlank() || lower == "#" || lower == "null" || lower == "undefined" ||
            lower == "about:blank" || lower.startsWith("javascript:") || lower.startsWith("data:") ||
            lower.startsWith("blob:") || lower.startsWith("intent:") || lower.startsWith("mailto:") || lower.startsWith("tel:")
    }

    fun isBadMediaAsset(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".css") || lower.endsWith(".js") ||
            lower.endsWith(".ico") || lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf")
    }
}


fun Element.bestImage(mainUrl: String): String? = with(PasarBokepUtils) {
    this@bestImage.bestImage(mainUrl)
}

fun Document.bestPoster(mainUrl: String): String? = with(PasarBokepUtils) {
    this@bestPoster.bestPoster(mainUrl)
}

fun Document.hasNextPage(): Boolean = with(PasarBokepUtils) {
    this@hasNextPage.hasNextPage()
}
