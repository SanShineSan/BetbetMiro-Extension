package com.putarflix

import com.lagradost.cloudstream3.TvType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

internal object PutarFlixUtils {
    private val badContentPaths = listOf(
        "/category/", "/tag/", "/genre/", "/country/", "/quality/", "/year/",
        "/author/", "/page/", "/wp-content/", "/sample-page/", "#respond"
    )

    private val badExternalHosts = listOf(
        "themoviedb.org", "facebook.com", "twitter.com", "instagram.com", "whatsapp.com",
        "youtube.com", "youtu.be", "t.me", "telegram.me"
    )

    private val shortenerHosts = listOf(
        "semawur.com", "linkduit.net", "safelinku.com", "safelinku.net", "ouo.io", "shrinkme.io",
        "shortlinkto", "adlinkfly", "linksly.co", "droplink.co", "duit.cc", "cuty.io"
    )

    private val playableHosts = listOf(
        "filepress.today", "filepress.store", "filepress.cloud", "drive.google.com", "drive.usercontent.google.com", "googleusercontent.com",
        "streamtape.com", "streamtape.to", "filemoon.sx", "filemoon.to", "doodstream.com",
        "dood.to", "d000d.com", "vidhide", "voe.sx", "voe.ws", "mixdrop.co", "mixdrop.to",
        "streamsb", "sbembed", "sbrapid", "streamwish", "wishfast", "hlswish", "vidmoly",
        "mp4upload", "uqload", "vidoza", "fembed", "filelions", "luluvdo", "streamruby",
        "vidguard", "vidplay", "filepursuit", "filegram", "pixeldrain.com", "krakenfiles.com",
        "emturbovid", "hownetwork", "playeriframe", "p2p", "f16", "jeniusplay", "majorplay",
        "e2e.majorplay", "m3u8.majorplay", "hglink", "ghbrisk", "dhcplay", "streamcasthub",
        "embed4me", "upns.live", "4meplayer", "play.putar.in", "gdplayer", "z.awstream.net", "awstream", "megaplay", "luluvdo", "filedon", "blogger.com", "blogspot", "play.streamplay.co.in", "movearnpre",
        "abysscdn", "vidsrc", "vidsrc.to", "vidsrc.xyz", "streamvid", "streamhub", "videy.co", "cdn", "mcloud", "upstream", "dropboxusercontent.com", "lh3.googleusercontent.com", "googlevideo.com", "ok.ru", "rumble.com", "sbfull", "listeamed", "streamhide", "vidsrcme", "vidlink"
    )

    fun cleanText(value: String?): String {
        return Jsoup.parse(value.orEmpty()).text()
            .replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':', '–')
            .trim()
    }

    fun cleanTitle(value: String?): String {
        return cleanText(value)
            .replace(Regex("(?i)\\s*[-|]\\s*PUTARFLIX.*$"), "")
            .replace(Regex("(?i)^Nonton\\s+(?:Film|Movie|Series)\\s+(.+)$"), "\$1")
            .replace(Regex("(?i)\\s+Sub\\s+Indo.*$"), "")
            .trim()
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun absoluteUrl(base: String, value: String?): String? {
        val raw = cleanUrlText(value)
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript:", true) || raw.startsWith("mailto:", true)) return null
        return runCatching {
            val normalized = if (raw.startsWith("//")) "https:$raw" else raw
            URI(base).resolve(normalized).toString()
        }.getOrNull()
    }

    fun pageUrl(path: String, page: Int): String {
        val fixed = absoluteUrl(PutarFlixSeeds.MAIN_URL, path) ?: PutarFlixSeeds.MAIN_URL
        if (page <= 1) return fixed
        return fixed.trimEnd('/') + "/page/$page/"
    }

    fun hostOf(url: String): String? {
        return runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()
    }

    fun originOf(url: String): String? {
        return runCatching {
            val uri = URI(url)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return@runCatching null
            "$scheme://$host"
        }.getOrNull()
    }

    fun isPutarFlixUrl(url: String): Boolean {
        return hostOf(url) == hostOf(PutarFlixSeeds.MAIN_URL)
    }

    fun isPutarFlixPlayerPage(url: String): Boolean {
        if (!isPutarFlixUrl(url)) return false
        val lower = url.lowercase()
        return lower.contains("?player=") || lower.contains("&player=")
    }

    fun isShortenerUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return shortenerHosts.any { host == it || host.endsWith(".$it") }
    }

    fun isKnownPlayableHost(url: String): Boolean {
        if (looksDirectVideo(url)) return true
        val host = hostOf(url) ?: return false
        return playableHosts.any { host == it || host.contains(it) || host.endsWith(".$it") }
    }

    fun isFilePressUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host.contains("filepress.") && url.contains("/file/", true)
    }


    /**
     * True only for URLs that ExoPlayer can reasonably consume directly.
     * Do NOT include drive.google.com/uc or export=download here: those are
     * landing/redirect pages and can cause infinite loading if emitted as video.
     */
    fun isFinalStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        val host = hostOf(lower).orEmpty()
        if (looksDirectVideo(lower)) return true
        if ("videoplayback" in lower) return true
        if (".m3u8" in lower || ".mp4" in lower || ".mpd" in lower || ".webm" in lower) return true
        if ("googlevideo.com" in host) return true
        if ("googleusercontent.com" in host && "drive.google.com" !in host) return true
        if ("drive.usercontent.google.com" in host) return true
        return false
    }

    fun isDirectDownloadUrl(url: String): Boolean {
        val lower = url.lowercase()
        val host = hostOf(lower).orEmpty()
        if (isFinalStreamUrl(lower)) return true
        if (("download" in lower || "export=download" in lower || "uc?" in lower) && ("drive.google.com" in host || "filepress" in host)) return true
        return false
    }

    fun isGoogleDriveLandingUrl(url: String): Boolean {
        val lower = url.lowercase()
        return "drive.google.com/file/d/" in lower ||
            "drive.google.com/open?" in lower ||
            "drive.google.com/uc?" in lower ||
            "drive.google.com/uc?id=" in lower
    }

    fun extractGoogleDriveId(url: String): String? {
        val decoded = decodeUrlRepeated(url)
        val uri = runCatching { URI(decoded) }.getOrNull()
        val queryId = uri?.rawQuery.orEmpty()
            .split("&")
            .firstOrNull { it.substringBefore("=").equals("id", true) }
            ?.substringAfter("=", "")
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodeUrlRepeated)
        if (!queryId.isNullOrBlank()) return queryId

        return listOf(
            Regex("""/file/d/([^/?#]+)""", RegexOption.IGNORE_CASE),
            Regex("""/d/([^/?#]+)""", RegexOption.IGNORE_CASE),
            Regex("""[?&]id=([^&#]+)""", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { regex ->
            regex.find(decoded)?.groupValues?.getOrNull(1)?.let(::decodeUrlRepeated)
        }?.takeIf { it.isNotBlank() }
    }

    fun googleDriveDownloadUrl(id: String): String {
        return "https://drive.usercontent.google.com/download?id=${encode(id)}&export=download&confirm=t"
    }

    fun isHtmlLandingUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (isFinalStreamUrl(lower)) return false
        if (isPutarFlixUrl(lower)) return true
        if (isShortenerUrl(lower)) return true
        if (isFilePressUrl(lower)) return true
        if (isGoogleDriveLandingUrl(lower)) return true
        if (lower.endsWith(".html") || lower.endsWith(".php")) return true
        return false
    }

    fun isContentUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith(PutarFlixSeeds.MAIN_URL)) return false
        if (badContentPaths.any { it in lower }) return false
        return lower.contains("/eps/") || lower.contains("/episode/") || lower.contains("/tv/") || Regex("https?://[^/]+/[^/?#]+/?$").containsMatchIn(lower)
    }

    fun isInternalNavigation(url: String): Boolean {
        if (!isPutarFlixUrl(url)) return false
        if (looksDirectVideo(url)) return false
        val lower = url.lowercase()
        return isContentUrl(url) || badContentPaths.any { it in lower } || lower.trimEnd('/') == PutarFlixSeeds.MAIN_URL
    }

    fun isRejectedVideoCandidate(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.isBlank()) return true
        if (badExternalHosts.any { it in lower }) return true
        if (lower.contains("/trailer") || lower.contains("/embed/trailer")) return true
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".css") || lower.endsWith(".js")) return true
        if (lower.contains("wp-content") && !looksDirectVideo(lower)) return true
        return false
    }

    fun typeFrom(url: String, title: String? = null, hint: String? = null): TvType {
        val value = listOf(url, title.orEmpty(), hint.orEmpty()).joinToString(" ").lowercase()
        return when {
            "/eps/" in value -> TvType.TvSeries
            "/tv/" in value -> TvType.TvSeries
            "episode" in value || Regex("\\bs\\d+\\s*e\\d+").containsMatchIn(value) -> TvType.TvSeries
            "season" in value || "tv show" in value || "series" in value -> TvType.TvSeries
            "korea" in value || "dramaqu" in value || "drakorkita" in value -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }

    fun pickImage(base: String, image: Element?, container: Element? = null): String? {
        val candidates = buildList {
            if (image != null) {
                add(image.attr("data-src"))
                add(image.attr("data-lazy-src"))
                add(image.attr("data-original"))
                add(image.attr("src"))
                add(image.attr("srcset").split(",").lastOrNull()?.trim()?.substringBefore(" ").orEmpty())
            }
            container?.select("img")?.forEach {
                add(it.attr("data-src"))
                add(it.attr("data-lazy-src"))
                add(it.attr("data-original"))
                add(it.attr("src"))
                add(it.attr("srcset").split(",").lastOrNull()?.trim()?.substringBefore(" ").orEmpty())
            }
        }
        return candidates.asSequence()
            .mapNotNull { absoluteUrl(base, it) }
            .firstOrNull { it.startsWith("http") }
    }

    fun extractMetaImage(base: String, doc: Document): String? {
        val raw = listOfNotNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("meta[name=twitter:image]")?.attr("content"),
            doc.selectFirst(".poster img, .cover img, article img, img")?.attr("src")
        ).firstOrNull { it.isNotBlank() }
        return absoluteUrl(base, raw)
    }

    fun extractYear(text: String?): Int? {
        val value = text.orEmpty()
        return Regex("\\((19|20)\\d{2}\\)|\\b((19|20)\\d{2})\\b")
            .find(value)
            ?.value
            ?.filter { it.isDigit() }
            ?.take(4)
            ?.toIntOrNull()
    }

    fun extractDuration(text: String?): Int? {
        return Regex("(?i)(\\d{2,3})\\s*(min|minute|minutes)")
            .find(text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    fun extractRating(text: String?): String? {
        return Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:votes|/10|out of 10)?")
            .find(text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
    }

    fun episodeNumber(text: String?): Int? {
        val clean = cleanText(text).lowercase()
        return listOf(
            Regex("episode\\s*(\\d+)"),
            Regex("eps?\\s*(\\d+)"),
            Regex("e(\\d+)"),
            Regex("\\b(\\d+)\\b")
        ).firstNotNullOfOrNull { it.find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    fun seasonNumber(text: String?): Int? {
        val clean = cleanText(text).lowercase()
        return listOf(
            Regex("season\\s*(\\d+)"),
            Regex("s(\\d+)")
        ).firstNotNullOfOrNull { it.find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    fun extractLabelNear(element: Element): String {
        return cleanText(
            element.attr("title").ifBlank { element.attr("aria-label") }
                .ifBlank { element.text() }
                .ifBlank { element.parent()?.text().orEmpty() }
        ).ifBlank { "PutarFlix" }
    }

    fun looksDirectVideo(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore("?")
        if (path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".mpd") || path.endsWith(".webm")) return true
        if ("videoplayback" in lower || "get_video" in lower || "playlist.m3u8" in lower || "master.m3u8" in lower) return true
        // Rumble/edge-cdn style HLS can expose a .tar URL with r_file=chunklist.m3u8 in the query.
        if (lower.contains("r_file=chunklist.m3u8") || lower.contains("application/vnd.apple.mpegurl")) return true
        return false
    }

    fun cleanUrlText(value: String?): String {
        return decodeUrlRepeated(value.orEmpty().trim())
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&#38;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("\\/", "/")
            .replace("\\\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
            .replace("\\u002f", "/")
            .trim(' ', '"', '\'', '`', ',', ';', ')', ']', '}')
    }

    fun decodeBase64Payloads(value: String): List<String> {
        val candidates = linkedSetOf<String>()
        val normalized = value
            .replace("\\/", "/")
            .replace("\\\\/", "/")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")

        Regex("""(?i)atob\s*\(\s*["']([A-Za-z0-9+/=_-]{16,})["']\s*\)""")
            .findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .forEach { candidates += it }

        Regex("""["']([A-Za-z0-9+/=_-]{32,})["']""")
            .findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filter { token -> token.length % 4 != 1 && token.any { it in "+/=_-" } }
            .take(24)
            .forEach { candidates += it }

        return candidates.mapNotNull { token ->
            val padded = token + "=".repeat((4 - token.length % 4) % 4)
            runCatching { String(Base64.getDecoder().decode(padded)) }
                .recoverCatching { String(Base64.getUrlDecoder().decode(padded)) }
                .mapCatching { cleanUrlText(it) }
                .getOrNull()
                ?.takeIf { decoded -> decoded.contains("http", true) || decoded.contains("iframe", true) || decoded.contains("source", true) }
        }.distinct()
    }

    fun decodeKnownRedirect(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val rawQuery = uri.rawQuery.orEmpty()
        val encodedFromQuery = rawQuery.split("&")
            .firstOrNull { it.substringBefore("=").lowercase() in listOf("url", "u", "go", "target", "link") }
            ?.substringAfter("=", "")
            ?.takeIf { it.isNotBlank() }

        val encoded: String = encodedFromQuery
            ?: (if (isShortenerUrl(url)) uri.rawPath.orEmpty().substringAfterLast('/').takeIf { it.length > 12 } else null)
            ?: return url

        val decodedParam = decodeUrlRepeated(encoded)
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
            .replace("\\u002f", "/")
            .trim()
        if (decodedParam.startsWith("http", true) || decodedParam.startsWith("//")) {
            return absoluteUrl(url, decodedParam) ?: decodedParam
        }

        val padded = decodedParam + "=".repeat((4 - decodedParam.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded)) }
            .recoverCatching { String(Base64.getUrlDecoder().decode(padded)) }
            .mapCatching { decodeUrlRepeated(it).replace("\\/", "/") }
            .mapCatching { absoluteUrl(url, it) ?: it }
            .getOrDefault(url)
    }

    fun decodeUrlRepeated(value: String): String {
        var current = value
        repeat(3) {
            val decoded = runCatching { URLDecoder.decode(current, "UTF-8") }.getOrDefault(current)
            if (decoded == current) return current
            current = decoded
        }
        return current
    }

    fun extractUrlsFromText(base: String, value: String): List<String> {
        val normalized = cleanUrlText(value)
        val pools = (listOf(normalized, decodeUrlRepeated(normalized)) + decodeBase64Payloads(normalized)).distinct()
        val candidates = linkedSetOf<String>()
        val urlRegex = Regex("""https?:\\?/\\?/[^\"'<>)\]\[\s]+""", RegexOption.IGNORE_CASE)
        val protocolLessRegex = Regex("""(?<!:)//[^\"'<>)\]\[\s]+""", RegexOption.IGNORE_CASE)
        val encodedUrlRegex = Regex("""https?%3A%2F%2F[^\"'<>)\]\[\s]+""", RegexOption.IGNORE_CASE)
        pools.forEach { pool ->
            urlRegex.findAll(pool).forEach { candidates += it.value }
            protocolLessRegex.findAll(pool).forEach { candidates += it.value }
            encodedUrlRegex.findAll(pool).forEach { candidates += it.value }
        }
        return candidates.mapNotNull { raw ->
            val cleaned = cleanUrlText(raw)
            absoluteUrl(base, cleaned)
        }.distinct()
    }
}
