package com.sad25kag.gomunime

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder

private data class HlsSource(
    val url: String,
    val referer: String,
    val quality: Int = Qualities.Unknown.value
)

private data class DirectSource(
    val url: String,
    val referer: String,
    val quality: Int = Qualities.Unknown.value
)

suspend fun loadGomunimeLinks(
    data: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val response = app.get(
        data,
        referer = HOME_URL,
        headers = defaultHeaders(HOME_URL),
        allowRedirects = true,
        timeout = 20L
    )

    val visited = linkedSetOf<String>()
    val playerLinks = linkedSetOf<String>()
    val hlsLinks = linkedMapOf<String, HlsSource>()
    val directVideos = linkedMapOf<String, DirectSource>()

    collectFromPageFast(
        document = response.document,
        html = response.text,
        baseUrl = response.url,
        referer = data,
        outPlayers = playerLinks,
        outHls = hlsLinks,
        outDirect = directVideos
    )

    // Kalau halaman episode sudah membawa direct source, jangan muter jauh-jauh.
    if (emitCollected(hlsLinks, directVideos, callback)) return true

    val prioritizedPlayers = playerLinks
        .filterNot { isBadUrl(it) }
        .distinct()
        .sortedWith(compareBy<String> { playerPriority(it) }.thenBy { it.length })
        .take(MAX_PLAYER_CANDIDATES)

    for (player in prioritizedPlayers) {
        crawlPlayerFast(
            url = player,
            referer = data,
            visited = visited,
            outHls = hlsLinks,
            outDirect = directVideos,
            depth = 0
        )

        // Stop cepat setelah stream ditemukan. Ini yang bikin loading tidak muter kelamaan.
        if (emitCollected(hlsLinks, directVideos, callback)) return true
    }

    // Last resort ringan: pakai extractor host prioritas saja, bukan semua link halaman.
    var fallbackDelivered = false
    prioritizedPlayers
        .filter { isExtractorFriendlyHost(it) }
        .take(6)
        .forEach { player ->
            val delivered = runCatching {
                loadExtractor(player, data, subtitleCallback, callback)
            }.getOrDefault(false)
            fallbackDelivered = delivered || fallbackDelivered
        }

    return fallbackDelivered
}

private suspend fun crawlPlayerFast(
    url: String,
    referer: String,
    visited: MutableSet<String>,
    outHls: MutableMap<String, HlsSource>,
    outDirect: MutableMap<String, DirectSource>,
    depth: Int
) {
    if (depth > MAX_CRAWL_DEPTH) return

    val fixedUrl = normalizeUrl(url, referer)
    if (fixedUrl in visited || isBadUrl(fixedUrl)) return
    visited.add(fixedUrl)

    if (fixedUrl.isHlsLike()) {
        outHls[fixedUrl] = HlsSource(fixedUrl, referer, qualityFromUrl(fixedUrl))
        return
    }

    if (fixedUrl.isDirectVideo()) {
        outDirect[fixedUrl] = DirectSource(fixedUrl, referer, qualityFromUrl(fixedUrl))
        return
    }

    val response = runCatching {
        app.get(
            fixedUrl,
            referer = referer,
            headers = defaultHeaders(referer),
            allowRedirects = true,
            timeout = 18L
        )
    }.getOrNull() ?: return

    val currentUrl = response.url
    val html = response.text
    val trimmed = html.trimStart()

    if (trimmed.startsWith("#EXTM3U")) {
        outHls[currentUrl] = HlsSource(currentUrl, referer, qualityFromUrl(currentUrl))
        return
    }

    val nextPlayers = linkedSetOf<String>()

    collectFromPageFast(
        document = response.document,
        html = html,
        baseUrl = currentUrl,
        referer = fixedUrl,
        outPlayers = nextPlayers,
        outHls = outHls,
        outDirect = outDirect
    )

    if (outHls.isNotEmpty() || outDirect.isNotEmpty()) return

    val unpacked = runCatching {
        if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
    }.getOrNull()

    if (!unpacked.isNullOrBlank()) {
        collectFromTextFast(
            text = unpacked,
            baseUrl = currentUrl,
            referer = fixedUrl,
            outPlayers = nextPlayers,
            outHls = outHls,
            outDirect = outDirect
        )
    }

    if (outHls.isNotEmpty() || outDirect.isNotEmpty()) return

    extractBase64DecodedTexts(html).forEach { decoded ->
        collectFromTextFast(
            text = decoded,
            baseUrl = currentUrl,
            referer = fixedUrl,
            outPlayers = nextPlayers,
            outHls = outHls,
            outDirect = outDirect
        )
    }

    if (outHls.isNotEmpty() || outDirect.isNotEmpty()) return

    nextPlayers
        .filterNot { it in visited }
        .filterNot { isBadUrl(it) }
        .sortedWith(compareBy<String> { playerPriority(it) }.thenBy { it.length })
        .take(4)
        .forEach { next ->
            crawlPlayerFast(
                url = next,
                referer = currentUrl,
                visited = visited,
                outHls = outHls,
                outDirect = outDirect,
                depth = depth + 1
            )
            if (outHls.isNotEmpty() || outDirect.isNotEmpty()) return
        }
}

private fun collectFromPageFast(
    document: Document,
    html: String,
    baseUrl: String,
    referer: String,
    outPlayers: MutableSet<String>,
    outHls: MutableMap<String, HlsSource>,
    outDirect: MutableMap<String, DirectSource>
) {
    // Selector dipersempit. Versi lama scan semua a[href], itu yang bikin loading seperti nunggu warung buka.
    document.select(
        "iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
            "embed[src], source[src], video[src], video source[src], " +
            "[data-url], [data-src], [data-link], [data-video], [data-file]"
    ).forEach { element ->
        val candidates = listOf(
            element.attr("src"),
            element.attr("data-src"),
            element.attr("data-litespeed-src"),
            element.attr("data-url"),
            element.attr("data-link"),
            element.attr("data-video"),
            element.attr("data-file")
        )

        candidates.forEach { raw ->
            addCandidate(raw, baseUrl, referer, outPlayers, outHls, outDirect)
        }
    }

    document.select(
        "a[href*='kotakajaib'], a[href*='turbosplayer'], a[href*='gdriveplayer'], " +
            "a[href*='desustream'], a[href*='anime-indo'], a[href*='yourupload'], " +
            "a[href*='mp4upload'], a[href*='drive.google'], a[href*='blogger'], " +
            "a[href*='blogspot'], a[href*='play.php'], a[href*='/embed/'], " +
            "a[href*='/player/'], a[href*='/file/']"
    ).forEach { anchor ->
        addCandidate(anchor.attr("href"), baseUrl, referer, outPlayers, outHls, outDirect)
    }

    document.select("select.mirror option[value], option[value], [data-frame]").forEach { option ->
        val raw = option.attr("value")
            .ifBlank { option.attr("data-frame") }
            .trim()

        if (raw.isBlank()) return@forEach

        decodeMirrorValue(raw, baseUrl)?.let { decoded ->
            addCandidate(decoded, baseUrl, referer, outPlayers, outHls, outDirect)
        }
    }

    collectFromTextFast(html, baseUrl, referer, outPlayers, outHls, outDirect)
}

private fun collectFromTextFast(
    text: String,
    baseUrl: String,
    referer: String,
    outPlayers: MutableSet<String>,
    outHls: MutableMap<String, HlsSource>,
    outDirect: MutableMap<String, DirectSource>
) {
    extractHlsUrls(text, baseUrl).forEach { hls ->
        outHls[hls] = HlsSource(hls, referer, qualityFromUrl(hls))
    }

    extractFileVariables(text).forEach { raw ->
        addCandidate(raw, baseUrl, referer, outPlayers, outHls, outDirect)
    }

    extractPriorityUrls(text).forEach { raw ->
        addCandidate(raw, baseUrl, referer, outPlayers, outHls, outDirect)
    }
}

private fun addCandidate(
    raw: String?,
    baseUrl: String,
    referer: String,
    outPlayers: MutableSet<String>,
    outHls: MutableMap<String, HlsSource>,
    outDirect: MutableMap<String, DirectSource>
) {
    if (raw.isNullOrBlank()) return

    val fixed = normalizeUrl(raw, baseUrl)
    if (isBadUrl(fixed)) return

    when {
        fixed.isHlsLike() -> outHls[fixed] = HlsSource(fixed, referer, qualityFromUrl(fixed))
        fixed.isDirectVideo() -> outDirect[fixed] = DirectSource(fixed, referer, qualityFromUrl(fixed))
        isLikelyPlayerUrl(fixed) -> outPlayers.add(fixed)
    }
}

private fun emitCollected(
    hlsLinks: MutableMap<String, HlsSource>,
    directVideos: MutableMap<String, DirectSource>,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var delivered = false

    hlsLinks.values
        .distinctBy { it.url }
        .sortedByDescending { it.quality }
        .forEach { hls ->
            callback(
                newExtractorLink(
                    source = "Gomunime",
                    name = "Gomunime ${qualityName(hls.quality)}",
                    url = hls.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = hls.referer
                    this.quality = hls.quality
                    this.headers = defaultHeaders(hls.referer, includeOrigin = false)
                }
            )
            delivered = true
        }

    directVideos.values
        .distinctBy { it.url }
        .sortedByDescending { it.quality }
        .forEach { video ->
            callback(
                newExtractorLink(
                    source = "Gomunime",
                    name = "Gomunime ${qualityName(video.quality)}",
                    url = video.url,
                    type = if (video.url.contains(".m3u8", true) || video.url.contains("play.php?", true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    this.referer = video.referer
                    this.quality = video.quality
                    this.headers = defaultHeaders(video.referer, includeOrigin = false)
                }
            )
            delivered = true
        }

    if (delivered) {
        hlsLinks.clear()
        directVideos.clear()
    }

    return delivered
}

private fun decodeMirrorValue(
    raw: String,
    baseUrl: String
): String? {
    val clean = raw.cleanEscapedUrl()

    if (clean.startsWith("http", true) || clean.startsWith("//")) return clean

    val urlDecoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrNull()
    if (!urlDecoded.isNullOrBlank()) {
        if (urlDecoded.startsWith("http", true) || urlDecoded.startsWith("//")) return urlDecoded
        extractIframeSrc(urlDecoded)?.let { return it }
    }

    val base64Decoded = decodeBase64Flexible(clean)
    if (!base64Decoded.isNullOrBlank()) {
        extractIframeSrc(base64Decoded)?.let { return it }
        if (base64Decoded.startsWith("http", true) || base64Decoded.startsWith("//")) return base64Decoded
    }

    return if (clean.contains("src=", true)) {
        extractIframeSrc(clean)
    } else {
        normalizeUrl(clean, baseUrl)
    }
}

private fun extractIframeSrc(text: String): String? {
    return Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
}

private fun extractHlsUrls(
    text: String,
    baseUrl: String
): List<String> {
    val urls = linkedSetOf<String>()

    Regex("""https?://[^"'\\\s<>]+\.m3u8[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map { it.value.cleanEscapedUrl() }
        .forEach { urls.add(it) }

    Regex("""https?://[^"'\\\s<>]+play\.php\?[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map { it.value.cleanEscapedUrl() }
        .forEach { urls.add(it) }

    Regex("""https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|play\.php)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
        .map { it.cleanEscapedUrl() }
        .forEach { urls.add(it) }

    Regex("""["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { normalizeUrl(it, baseUrl) }
        .forEach { urls.add(it) }

    Regex("""["']([^"']*play\.php\?[^"']*)["']""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { normalizeUrl(it, baseUrl) }
        .forEach { urls.add(it) }

    return urls.filterNot { isBadUrl(it) }.toList()
}

private fun extractPriorityUrls(text: String): List<String> {
    val urls = linkedSetOf<String>()

    Regex(
        """https?://[^"'\\\s<>]+?(?:kotakajaib|turbosplayer|gdriveplayer|desustream|anime-indo|yourupload|mp4upload|drive\.google|blogger|blogspot|play\.php|/embed/|/player/|/file/)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(text)
        .map { it.value.cleanEscapedUrl() }
        .forEach { urls.add(it) }

    Regex("""['"]((?:https?:)?//[^'"]+)['"]""")
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscapedUrl() }
        .filter { isLikelyPlayerUrl(it) || it.isHlsLike() || it.isDirectVideo() }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun extractFileVariables(text: String): List<String> {
    val urls = linkedSetOf<String>()

    Regex("""(?:file|src|url|source|hls|video|embedUrl|contentUrl)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscapedUrl() }
        .filter { it.isHlsLike() || it.isDirectVideo() || isLikelyPlayerUrl(it) }
        .forEach { urls.add(it) }

    Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .forEach { urls.add(it.cleanEscapedUrl()) }

    return urls.toList()
}

private fun extractBase64DecodedTexts(text: String): List<String> {
    val decoded = linkedSetOf<String>()

    Regex("""atob\(["']([^"']{12,})["']\)""")
        .findAll(text)
        .mapNotNull { decodeBase64Flexible(it.groupValues[1]) }
        .filter { it.contains("http", true) || it.contains(".m3u8", true) || it.contains("play.php", true) || it.contains("src=", true) }
        .forEach { decoded.add(it.cleanEscapedUrl()) }

    Regex("""["']([A-Za-z0-9+/_=-]{40,})["']""")
        .findAll(text)
        .mapNotNull { decodeBase64Flexible(it.groupValues[1]) }
        .filter { it.contains("http", true) || it.contains(".m3u8", true) || it.contains("play.php", true) || it.contains("src=", true) }
        .forEach { decoded.add(it.cleanEscapedUrl()) }

    return decoded.toList()
}

private fun decodeBase64Flexible(value: String): String? {
    val cleaned = value.trim()
        .replace("-", "+")
        .replace("_", "/")
        .replace("\\s".toRegex(), "")

    if (cleaned.isBlank()) return null

    val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)

    return runCatching {
        String(Base64.decode(padded, Base64.DEFAULT))
    }.getOrNull()
}

private fun String.isHlsLike(): Boolean {
    val lower = lowercase()
    return lower.contains(".m3u8") || lower.contains("play.php?")
}

private fun String.isDirectVideo(): Boolean {
    val lower = lowercase()
    return lower.contains(".mp4") ||
        lower.contains("googlevideo.com/videoplayback") ||
        lower.contains("lh3.googleusercontent.com")
}

private fun isLikelyPlayerUrl(url: String): Boolean {
    val lower = url.lowercase()

    return lower.contains("gdriveplayer") ||
        lower.contains("embed2.php") ||
        lower.contains("anime-indo.lol") ||
        lower.contains("yup.php") ||
        lower.contains("yourupload") ||
        lower.contains("blogger") ||
        lower.contains("blogspot") ||
        lower.contains("mp4upload") ||
        lower.contains("desustream") ||
        lower.contains("kotakajaib") ||
        lower.contains("turbosplayer") ||
        lower.contains("drive.google") ||
        lower.contains("lh3.googleusercontent") ||
        lower.contains("/embed/") ||
        lower.contains("/player/") ||
        lower.contains("/file/") ||
        lower.contains("play.php") ||
        lower.contains(".mp4") ||
        lower.contains(".m3u8")
}

private fun isExtractorFriendlyHost(url: String): Boolean {
    val lower = url.lowercase()
    return lower.contains("yourupload") ||
        lower.contains("mp4upload") ||
        lower.contains("drive.google") ||
        lower.contains("blogger") ||
        lower.contains("blogspot") ||
        lower.contains("kotakajaib") ||
        lower.contains("turbosplayer") ||
        lower.contains("gdriveplayer")
}

private fun playerPriority(url: String): Int {
    val lower = url.lowercase()
    return when {
        lower.contains(".m3u8") || lower.contains("play.php") -> 0
        lower.contains("kotakajaib") -> 1
        lower.contains("turbosplayer") -> 2
        lower.contains("gdriveplayer") -> 3
        lower.contains("desustream") -> 4
        lower.contains("blogger") || lower.contains("blogspot") -> 5
        lower.contains("yourupload") || lower.contains("mp4upload") -> 6
        else -> 9
    }
}

private fun isBadUrl(url: String): Boolean {
    val lower = url.lowercase()

    return lower.isBlank() ||
        lower == "#" ||
        lower.startsWith("javascript:") ||
        lower.startsWith("data:") ||
        lower.startsWith("mailto:") ||
        lower.contains("facebook.com") ||
        lower.contains("twitter.com") ||
        lower.contains("x.com/") ||
        lower.contains("telegram") ||
        lower.contains("whatsapp") ||
        lower.contains("adsbygoogle") ||
        lower.contains("doubleclick") ||
        lower.contains("googlesyndication") ||
        lower.contains("googletagmanager") ||
        lower.contains("google-analytics") ||
        lower.contains("histats") ||
        lower.contains("cloudflareinsights") ||
        lower.endsWith(".css") ||
        lower.endsWith(".js") ||
        lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".png") ||
        lower.endsWith(".webp") ||
        lower.endsWith(".gif") ||
        lower.endsWith(".svg")
}

private fun normalizeUrl(
    url: String,
    baseUrl: String
): String {
    val clean = url.cleanEscapedUrl()

    return when {
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> {
            val origin = Regex("""^https?://[^/]+""")
                .find(baseUrl)
                ?.value
                ?: HOME_URL.trimEnd('/')
            "$origin$clean"
        }
        else -> {
            runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrElse {
                "${HOME_URL.trimEnd('/')}/$clean"
            }
        }
    }
}

private fun defaultHeaders(
    referer: String,
    includeOrigin: Boolean = true
): Map<String, String> {
    val origin = Regex("""^https?://[^/]+""")
        .find(referer)
        ?.value
        ?: HOME_URL.trimEnd('/')

    val headers = linkedMapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "*/*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    if (includeOrigin) headers["Origin"] = origin

    return headers
}

private fun String.cleanEscapedUrl(): String {
    return this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003f", "?")
        .replace("\\u002F", "/")
        .replace("&amp;", "&")
        .trim()
}

private fun qualityFromUrl(url: String): Int {
    return when {
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
        Regex("""itag=(37|96|137)""").containsMatchIn(url) -> Qualities.P1080.value
        Regex("""itag=(22|59)""").containsMatchIn(url) -> Qualities.P720.value
        Regex("""itag=18""").containsMatchIn(url) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

private fun qualityName(quality: Int): String {
    return when (quality) {
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value -> "720p"
        Qualities.P480.value -> "480p"
        Qualities.P360.value -> "360p"
        else -> "HLS"
    }
}

private const val HOME_URL = "https://gomunime.top/"
private const val MAX_CRAWL_DEPTH = 3
private const val MAX_PLAYER_CANDIDATES = 10
