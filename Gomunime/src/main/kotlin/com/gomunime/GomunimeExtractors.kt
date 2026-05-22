package com.gomunime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
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

suspend fun loadGomunimeLinks(
    data: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val headers = defaultHeaders(data)

    val response = app.get(
        data,
        referer = "https://gomunime.top/",
        headers = headers,
        allowRedirects = true
    )

    val visited = linkedSetOf<String>()
    val players = linkedSetOf<String>()
    val hlsLinks = linkedMapOf<String, HlsSource>()
    val directVideos = linkedMapOf<String, Pair<String, Int>>()

    collectFromPage(
        document = response.document,
        html = response.text,
        baseUrl = response.url,
        referer = data,
        outPlayers = players,
        outHls = hlsLinks,
        outDirect = directVideos
    )

    response.document.select("select.mirror option[value], option[value]").forEach { option ->
        val raw = option.attr("value").trim()
        if (raw.isBlank()) return@forEach

        decodeMirrorValue(raw, response.url)?.let { decoded ->
            val fixed = normalizeUrl(decoded, response.url)

            when {
                fixed.isHlsLike() -> hlsLinks[fixed] = HlsSource(fixed, data, qualityFromUrl(fixed))
                fixed.isDirectVideo() -> directVideos[fixed] = data to qualityFromUrl(fixed)
                isLikelyPlayerUrl(fixed) -> players.add(fixed)
            }
        }
    }

    players.toList().forEach { player ->
        crawlPlayer(
            url = player,
            referer = data,
            visited = visited,
            outPlayers = players,
            outHls = hlsLinks,
            outDirect = directVideos,
            depth = 0
        )
    }

    hlsLinks.values.forEach { hls ->
        callback(
            newExtractorLink(
                source = "Gomunime",
                name = "Gomunime ${qualityName(hls.quality)}",
                url = hls.url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = hls.referer
                this.quality = hls.quality
                this.headers = defaultHeaders(hls.referer)
            }
        )
    }

    directVideos.forEach { (videoUrl, dataPair) ->
        val referer = dataPair.first
        val quality = dataPair.second

        callback(
            newExtractorLink(
                source = "Gomunime",
                name = "Gomunime ${qualityName(quality)}",
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = quality
                this.headers = defaultHeaders(referer)
            }
        )
    }

    if (hlsLinks.isNotEmpty() || directVideos.isNotEmpty()) {
        return true
    }

    var fallback = false

    players.forEach { player ->
        loadExtractor(player, data, subtitleCallback, callback)
        fallback = true
    }

    return fallback
}

private suspend fun crawlPlayer(
    url: String,
    referer: String,
    visited: MutableSet<String>,
    outPlayers: MutableSet<String>,
    outHls: MutableMap<String, HlsSource>,
    outDirect: MutableMap<String, Pair<String, Int>>,
    depth: Int
) {
    if (depth > 6) return

    val fixedUrl = normalizeUrl(url, referer)
    if (fixedUrl in visited) return
    visited.add(fixedUrl)

    val response = runCatching {
        app.get(
            fixedUrl,
            referer = referer,
            headers = defaultHeaders(referer),
            allowRedirects = true
        )
    }.getOrNull() ?: return

    val currentUrl = response.url
    val html = response.text
    val trimmed = html.trimStart()

    if (trimmed.startsWith("#EXTM3U")) {
        outHls[currentUrl] = HlsSource(
            url = currentUrl,
            referer = referer,
            quality = qualityFromUrl(currentUrl)
        )
        return
    }

    val nextPlayers = linkedSetOf<String>()

    collectFromPage(
        document = response.document,
        html = html,
        baseUrl = currentUrl,
        referer = fixedUrl,
        outPlayers = nextPlayers,
        outHls = outHls,
        outDirect = outDirect
    )

    extractBase64DecodedTexts(html).forEach { decoded ->
        collectFromText(
            text = decoded,
            baseUrl = currentUrl,
            referer = fixedUrl,
            outPlayers = nextPlayers,
            outHls = outHls,
            outDirect = outDirect
        )
    }

    nextPlayers
        .filter { it !in visited }
        .forEach { next ->
            crawlPlayer(
                url = next,
                referer = currentUrl,
                visited = visited,
                outPlayers = outPlayers,
                outHls = outHls,
                outDirect = outDirect,
                depth = depth + 1
            )
        }
}

private fun collectFromPage(
    document: Document,
    html: String,
    baseUrl: String,
    referer: String,
    outPlayers: MutableSet<String>,
    outHls: MutableMap<String, HlsSource>,
    outDirect: MutableMap<String, Pair<String, Int>>
) {
    document.select(
        "iframe[src], " +
            "embed[src], " +
            "source[src], " +
            "video[src], " +
            "a[href], " +
            "[data-url], " +
            "[data-src], " +
            "[data-link], " +
            "[data-video], " +
            "[data-file]"
    ).forEach { element ->
        val raw = element.attr("src")
            .ifBlank { element.attr("href") }
            .ifBlank { element.attr("data-url") }
            .ifBlank { element.attr("data-src") }
            .ifBlank { element.attr("data-link") }
            .ifBlank { element.attr("data-video") }
            .ifBlank { element.attr("data-file") }
            .trim()

        if (raw.isBlank()) return@forEach

        val fixed = normalizeUrl(raw, baseUrl)

        when {
            fixed.isHlsLike() -> outHls[fixed] = HlsSource(fixed, referer, qualityFromUrl(fixed))
            fixed.isDirectVideo() -> outDirect[fixed] = referer to qualityFromUrl(fixed)
            isLikelyPlayerUrl(fixed) -> outPlayers.add(fixed)
        }
    }

    document.select("select.mirror option[value], option[value]").forEach { option ->
        val raw = option.attr("value").trim()
        if (raw.isBlank()) return@forEach

        decodeMirrorValue(raw, baseUrl)?.let { decoded ->
            val fixed = normalizeUrl(decoded, baseUrl)

            when {
                fixed.isHlsLike() -> outHls[fixed] = HlsSource(fixed, referer, qualityFromUrl(fixed))
                fixed.isDirectVideo() -> outDirect[fixed] = referer to qualityFromUrl(fixed)
                isLikelyPlayerUrl(fixed) -> outPlayers.add(fixed)
            }
        }
    }

    collectFromText(
        text = html,
        baseUrl = baseUrl,
        referer = referer,
        outPlayers = outPlayers,
        outHls = outHls,
        outDirect = outDirect
    )
}

private fun collectFromText(
    text: String,
    baseUrl: String,
    referer: String,
    outPlayers: MutableSet<String>,
    outHls: MutableMap<String, HlsSource>,
    outDirect: MutableMap<String, Pair<String, Int>>
) {
    extractHlsUrls(text, baseUrl).forEach { hls ->
        outHls[hls] = HlsSource(hls, referer, qualityFromUrl(hls))
    }

    extractPossibleUrls(text).forEach { raw ->
        val fixed = normalizeUrl(raw, baseUrl)

        when {
            fixed.isHlsLike() -> outHls[fixed] = HlsSource(fixed, referer, qualityFromUrl(fixed))
            fixed.isDirectVideo() -> outDirect[fixed] = referer to qualityFromUrl(fixed)
            isLikelyPlayerUrl(fixed) -> outPlayers.add(fixed)
        }
    }

    extractFileVariables(text).forEach { raw ->
        val fixed = normalizeUrl(raw, baseUrl)

        when {
            fixed.isHlsLike() -> outHls[fixed] = HlsSource(fixed, referer, qualityFromUrl(fixed))
            fixed.isDirectVideo() -> outDirect[fixed] = referer to qualityFromUrl(fixed)
            isLikelyPlayerUrl(fixed) -> outPlayers.add(fixed)
        }
    }
}

private fun decodeMirrorValue(
    raw: String,
    baseUrl: String
): String? {
    val clean = raw.cleanEscapedUrl()

    if (clean.startsWith("http", true) || clean.startsWith("//")) {
        return clean
    }

    val urlDecoded = runCatching {
        URLDecoder.decode(clean, "UTF-8")
    }.getOrNull()

    if (!urlDecoded.isNullOrBlank() && (urlDecoded.startsWith("http", true) || urlDecoded.contains("src=", true))) {
        return extractIframeSrc(urlDecoded) ?: urlDecoded
    }

    val base64Decoded = runCatching {
        base64Decode(clean).trim()
    }.getOrNull()

    if (!base64Decoded.isNullOrBlank()) {
        return extractIframeSrc(base64Decoded)
            ?: if (base64Decoded.startsWith("http", true) || base64Decoded.startsWith("//")) {
                base64Decoded
            } else {
                null
            }
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
        .map {
            runCatching {
                URLDecoder.decode(it.value, "UTF-8")
            }.getOrDefault(it.value)
        }
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

    return urls.toList()
}

private fun extractPossibleUrls(text: String): List<String> {
    val urls = linkedSetOf<String>()

    Regex("""https?:\\?/\\?/[^"'\\\s<>]+""")
        .findAll(text)
        .map { it.value.cleanEscapedUrl() }
        .forEach { urls.add(it) }

    Regex("""['"]((?:https?:)?//[^'"]+)['"]""")
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscapedUrl() }
        .forEach { urls.add(it) }

    Regex("""(?:href|src|file|url|source|hls|video)\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscapedUrl() }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun extractFileVariables(text: String): List<String> {
    val urls = linkedSetOf<String>()

    Regex("""file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .forEach { urls.add(it.cleanEscapedUrl()) }

    Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .forEach { urls.add(it.cleanEscapedUrl()) }

    Regex("""source\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .forEach { urls.add(it.cleanEscapedUrl()) }

    return urls.toList()
}

private fun extractBase64DecodedTexts(text: String): List<String> {
    val decoded = linkedSetOf<String>()

    Regex("""atob\(["']([^"']{12,})["']\)""")
        .findAll(text)
        .mapNotNull { match ->
            runCatching {
                base64Decode(match.groupValues[1]).trim()
            }.getOrNull()
        }
        .filter {
            it.contains("http", true) ||
                it.contains(".m3u8", true) ||
                it.contains("play.php", true) ||
                it.contains("src=", true)
        }
        .forEach { decoded.add(it.cleanEscapedUrl()) }

    Regex("""["']([A-Za-z0-9+/=]{40,})["']""")
        .findAll(text)
        .mapNotNull { match ->
            runCatching {
                base64Decode(match.groupValues[1]).trim()
            }.getOrNull()
        }
        .filter {
            it.contains("http", true) ||
                it.contains(".m3u8", true) ||
                it.contains("play.php", true) ||
                it.contains("src=", true)
        }
        .forEach { decoded.add(it.cleanEscapedUrl()) }

    return decoded.toList()
}

private fun String.isHlsLike(): Boolean {
    return contains(".m3u8", true) ||
        contains("play.php?", true)
}

private fun String.isDirectVideo(): Boolean {
    return contains(".mp4", true) ||
        contains("googlevideo.com/videoplayback", true) ||
        contains("lh3.googleusercontent.com", true)
}

private fun isLikelyPlayerUrl(url: String): Boolean {
    return url.contains("googlevideo", true) ||
        url.contains("blogger", true) ||
        url.contains("blogspot", true) ||
        url.contains("mp4upload", true) ||
        url.contains("stream", true) ||
        url.contains("desustream", true) ||
        url.contains("kotakajaib", true) ||
        url.contains("turbosplayer", true) ||
        url.contains("drive.google", true) ||
        url.contains("lh3.googleusercontent", true) ||
        url.contains("/embed/", true) ||
        url.contains("/player/", true) ||
        url.contains("/file/", true) ||
        url.contains("play.php", true) ||
        url.contains(".mp4", true) ||
        url.contains(".m3u8", true)
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
                ?: "https://gomunime.top"
            "$origin$clean"
        }
        else -> {
            runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrElse {
                val origin = Regex("""^https?://[^/]+""")
                    .find(baseUrl)
                    ?.value
                    ?: "https://gomunime.top"
                "$origin/$clean"
            }
        }
    }
}

private fun defaultHeaders(referer: String): Map<String, String> {
    val origin = Regex("""^https?://[^/]+""")
        .find(referer)
        ?.value
        ?: "https://gomunime.top"

    return mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Origin" to origin,
        "Accept" to "*/*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )
}

private fun String.cleanEscapedUrl(): String {
    return this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
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