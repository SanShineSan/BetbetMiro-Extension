package com.sad25kag.animeindo

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

open class GdrivePlayerTo : ExtractorApi() {
    override var name = "GdrivePlayer"
    override var mainUrl = "https://gdriveplayer.to"
    override val requiresReferer = true

    private val activeEmbedKey2 = "sfhasgi783dhq92t7"

    private val htmlHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5"
    )

    private fun safeDecode(value: String, maxRounds: Int = 1): String {
        var current = value
        repeat(maxRounds) {
            val decoded = runCatching { URLDecoder.decode(current, "UTF-8") }.getOrDefault(current)
            if (decoded == current) return@repeat
            current = decoded
        }
        return current
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun shouldDecodeWholeUrl(value: String): Boolean {
        val lower = value.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//")) return false
        return lower.contains("http%3a%2f%2f") ||
            lower.contains("https%3a%2f%2f") ||
            lower.contains("%2f")
    }

    private fun scanVariants(text: String): List<String> {
        val normalized = text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        val variants = linkedSetOf(normalized)
        variants.add(safeDecode(normalized, 1))
        variants.add(safeDecode(normalized, 2))
        return variants.filter { it.isNotBlank() }
    }

    private fun queryValue(url: String, key: String): String? {
        val query = url.substringAfter("?", "").substringBefore("#")
        if (query.isBlank()) return null
        return query.split("&")
            .firstOrNull { part -> part.substringBefore("=").equals(key, ignoreCase = true) }
            ?.substringAfter("=", "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalizePlayerUrl(rawUrl: String?, baseUrl: String): String? {
        val cleaned = rawUrl
            ?.trim()
            ?.trim('"', '\'', '`', ';')
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val decoded = if (shouldDecodeWholeUrl(cleaned)) {
            safeDecode(cleaned, 2).trim('"', '\'', '`', ';')
        } else {
            cleaned.trim().trim('"', '\'', '`', ';')
        }

        if (decoded == "#" || decoded.startsWith("javascript:", true) || decoded.startsWith("mailto:", true)) return null

        val fixed = when {
            decoded.startsWith("http://", true) || decoded.startsWith("https://", true) -> decoded
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("/") -> "$mainUrl$decoded"
            decoded.startsWith("hlsplaylist.php", true) || decoded.startsWith("subproxy.php", true) ||
                decoded.startsWith("embed.php", true) || decoded.startsWith("embed2.php", true) ||
                decoded.startsWith("download.php", true) || decoded.startsWith("file.js", true) -> "$mainUrl/$decoded"
            else -> {
                val cleanBase = baseUrl.substringBefore("#").substringBefore("?")
                val baseDir = if (cleanBase.substringAfter("://", "").contains("/")) {
                    cleanBase.substringBeforeLast("/")
                } else {
                    mainUrl
                }
                "$baseDir/$decoded"
            }
        }

        return fixed.substringBefore("#")
            .trim()
            .trimEnd(',', ')', ']', '}')
            .takeIf { it.isNotBlank() }
    }

    private fun isHlsUrl(url: String): Boolean {
        return url.contains("hlsplaylist.php", true) || url.contains(".m3u8", true)
    }

    private fun isGdrivePlayerUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("gdriveplayer.to/") ||
            lower.contains("/embed.php?") ||
            lower.contains("/embed2.php?") ||
            lower.contains("/download.php?") ||
            lower.contains("/file.js")
    }

    private fun isDownloadUrl(url: String): Boolean {
        return url.contains("/download.php?", ignoreCase = true) && url.contains("link=", ignoreCase = true)
    }

    private fun buildEmbed2FromDownload(downloadUrl: String): String? {
        if (!isDownloadUrl(downloadUrl)) return null
        val link = queryValue(downloadUrl, "link") ?: return null
        val key = queryValue(downloadUrl, "key") ?: ""
        val key2 = queryValue(downloadUrl, "key2") ?: activeEmbedKey2
        val noAdult = queryValue(downloadUrl, "no_adult") ?: "yes"
        return "$mainUrl/embed2.php?link=$link&key=$key&key2=$key2&no_adult=$noAdult"
    }

    private fun decodeBase64Url(value: String): String? {
        val decoded = safeDecode(value, 1)
            .replace('-', '+')
            .replace('_', '/')
            .replace(" ", "+")
            .trim()
        val padded = decoded + "=".repeat((4 - decoded.length % 4) % 4)
        return runCatching { String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8) }
            .getOrNull()
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalizeDriveUrl(url: String): String {
        return url.replace("/view", "/preview")
            .substringBefore("?")
            .let { clean ->
                if (clean.contains("/preview", true)) clean else clean.trimEnd('/') + "/preview"
            }
    }

    private fun collectUrls(text: String, baseUrl: String, patterns: List<Regex>): List<String> {
        val candidates = linkedSetOf<String>()

        for (scan in scanVariants(text)) {
            for (pattern in patterns) {
                for (match in pattern.findAll(scan)) {
                    val raw = match.groups[1]?.value ?: match.value
                    normalizePlayerUrl(raw, baseUrl)?.let { candidates.add(it) }
                }
            }
        }

        return candidates.toList()
    }

    private fun collectHlsUrls(text: String, baseUrl: String): List<String> {
        return collectUrls(
            text,
            baseUrl,
            listOf(
                Regex("""(?i)(?:https?:)?//gdriveplayer\.to/hlsplaylist\.php[^"'<>\\\s]+"""),
                Regex("""(?i)/hlsplaylist\.php\?[^"'<>\\\s]+"""),
                Regex("""(?i)(?<![A-Za-z0-9_./-])hlsplaylist\.php\?[^"'<>\\\s]+"""),
                Regex("""(?i)(?:file|url|src|sources)\s*[:=]\s*["']([^"']*(?:hlsplaylist\.php|\.m3u8)[^"']*)["']"""),
                Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+\.m3u8(?:\?[^"'<>\\\s]+)?""")
            )
        ).filter { isHlsUrl(it) }
    }

    private fun collectSubtitleUrls(text: String, baseUrl: String): List<String> {
        return collectUrls(
            text,
            baseUrl,
            listOf(
                Regex("""(?i)(?:https?:)?//gdriveplayer\.to/subproxy\.php[^"'<>\\\s]+"""),
                Regex("""(?i)/subproxy\.php\?[^"'<>\\\s]+"""),
                Regex("""(?i)(?<![A-Za-z0-9_./-])subproxy\.php\?[^"'<>\\\s]+"""),
                Regex("""(?i)(?:tracks|captions|subtitles|file)\s*[:=]\s*["']([^"']*subproxy\.php[^"']*)["']""")
            )
        )
    }

    private fun collectDriveUrls(text: String, baseUrl: String): List<String> {
        val drives = linkedSetOf<String>()
        collectUrls(
            text,
            baseUrl,
            listOf(
                Regex("""(?i)(https?://drive\.google\.com/file/d/[^/"'<>\\\s]+/(?:view|preview)(?:\?[^"'<>\\\s]+)?)"""),
                Regex("""(?i)(https?://[^"'<>\\\s]+/file/d/[^/"'<>\\\s]+/(?:view|preview)(?:\?[^"'<>\\\s]+)?)""")
            )
        ).forEach { drives.add(normalizeDriveUrl(it)) }

        collectSubtitleUrls(text, baseUrl).forEach { subProxy ->
            queryValue(subProxy, "u")?.let { encoded ->
                decodeBase64Url(encoded)?.takeIf { it.contains("drive.google.com/file/d/", true) }?.let { drive ->
                    drives.add(normalizeDriveUrl(drive))
                }
            }
        }

        return drives.toList()
    }

    private fun collectEmbedUrls(text: String, baseUrl: String): List<String> {
        return collectUrls(
            text,
            baseUrl,
            listOf(
                Regex("""(?i)(?:https?:)?//[^"'<>\\\s]*gdriveplayer[^"'<>\\\s]*/embed2?\.php\?[^"'<>\\\s]+"""),
                Regex("""(?i)(?:https?:)?//[^"'<>\\\s]*gdriveplayer[^"'<>\\\s]*/download\.php\?[^"'<>\\\s]+"""),
                Regex("""(?i)/embed2?\.php\?[^"'<>\\\s]+"""),
                Regex("""(?i)/download\.php\?[^"'<>\\\s]+"""),
                Regex("""(?i)(?<![A-Za-z0-9_./-])embed2?\.php\?[^"'<>\\\s]+"""),
                Regex("""(?i)(?<![A-Za-z0-9_./-])download\.php\?[^"'<>\\\s]+"""),
                Regex("""(?i)(?:src|href|data-video|data-url|data-iframe|data-src|data-link|file|url)\s*[:=]\s*["']([^"']*(?:gdriveplayer[^"']*/(?:embed2?\.php|download\.php)|/(?:embed2?\.php|download\.php))[^"']*)["']"""),
                Regex("""(?i)(?:src|href)\s*=\s*["']([^"']*file\.js[^"']*)["']""")
            )
        )
    }

    private fun collectNestedUrls(text: String, baseUrl: String): List<String> {
        return collectUrls(
            text,
            baseUrl,
            listOf(
                Regex("""(?i)(?:src|href|data-video|data-url|data-iframe|data-src|data-link|data-href|data-file|file|url)\s*[:=]\s*["']([^"']+)["']"""),
                Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+""")
            )
        ).filter { candidate ->
            val lower = candidate.lowercase()
            lower.contains("gdriveplayer.to/") ||
                lower.contains("drive.google.com/file/d/") ||
                lower.contains("hlsplaylist.php") ||
                lower.contains(".m3u8")
        }
    }

    private fun qualityFromText(text: String): Int {
        return Regex("""(?i)(?:^|[^0-9])(240|360|480|720|1080|1440|2160)p?(?:[^0-9]|$)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun playlistHeaders(playerUrl: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to playerUrl
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val firstUrl = normalizePlayerUrl(url, mainUrl) ?: return
        val firstReferer = referer ?: mainUrl
        val emitted = linkedSetOf<String>()
        val seenPages = linkedSetOf<String>()
        val queue = mutableListOf(firstUrl to firstReferer)

        buildEmbed2FromDownload(firstUrl)?.let { embedUrl ->
            if (queue.none { it.first.equals(embedUrl, true) }) queue.add(embedUrl to firstReferer)
        }

        suspend fun emitPlaylist(rawPlaylistUrl: String, playerUrl: String): Boolean {
            val playlistUrl = normalizePlayerUrl(rawPlaylistUrl, playerUrl) ?: return false
            if (!isHlsUrl(playlistUrl)) return false
            if (!emitted.add(playlistUrl.substringBefore("#"))) return true

            callback(
                newExtractorLink(name, "$name HLS", playlistUrl, type = ExtractorLinkType.M3U8) {
                    this.quality = qualityFromText(playlistUrl)
                    this.referer = playerUrl
                    this.headers = playlistHeaders(playerUrl)
                }
            )
            return true
        }

        val countedCallback: (ExtractorLink) -> Unit = { link ->
            emitted.add(link.url.substringBefore("#"))
            callback(link)
        }

        var index = 0
        while (index < queue.size && index < 20) {
            val (currentUrl, pageReferer) = queue[index]
            index += 1

            if (!seenPages.add(currentUrl.substringBefore("#"))) continue

            buildEmbed2FromDownload(currentUrl)?.let { embedUrl ->
                if (seenPages.none { it.equals(embedUrl.substringBefore("#"), true) } &&
                    queue.none { it.first.equals(embedUrl, true) }
                ) {
                    queue.add(embedUrl to pageReferer)
                }
            }

            if (isHlsUrl(currentUrl)) {
                emitPlaylist(currentUrl, pageReferer)
                continue
            }

            if (currentUrl.contains("drive.google.com/file/d/", true)) {
                runCatching { loadExtractor(normalizeDriveUrl(currentUrl), pageReferer, subtitleCallback, countedCallback) }
                if (emitted.isNotEmpty()) return
                continue
            }

            val pageText = runCatching {
                app.get(
                    currentUrl,
                    referer = pageReferer,
                    headers = htmlHeaders + mapOf("Referer" to pageReferer)
                ).text
            }.getOrNull() ?: continue

            if (pageText.startsWith("#EXTM3U")) {
                emitPlaylist(currentUrl, pageReferer)
                if (emitted.isNotEmpty()) return
                continue
            }

            val unpacked = runCatching { getAndUnpack(pageText) }.getOrDefault("")
            val scanText = if (unpacked.isBlank()) pageText else "$pageText\n$unpacked"

            for (subtitleUrl in collectSubtitleUrls(scanText, currentUrl)) {
                subtitleCallback(SubtitleFile("Indonesian", subtitleUrl))
            }

            for (playlistUrl in collectHlsUrls(scanText, currentUrl)) {
                emitPlaylist(playlistUrl, currentUrl)
            }
            if (emitted.isNotEmpty()) return

            for (driveUrl in collectDriveUrls(scanText, currentUrl)) {
                runCatching { loadExtractor(driveUrl, currentUrl, subtitleCallback, countedCallback) }
                if (emitted.isNotEmpty()) return
            }

            val nested = linkedSetOf<String>()
            nested.addAll(collectEmbedUrls(scanText, currentUrl))
            nested.addAll(collectNestedUrls(scanText, currentUrl))

            for (nestedUrl in nested) {
                val fixedUrl = normalizePlayerUrl(nestedUrl, currentUrl) ?: continue
                val embedUrl = buildEmbed2FromDownload(fixedUrl)
                if (embedUrl != null && seenPages.none { it.equals(embedUrl.substringBefore("#"), true) }) {
                    queue.add(embedUrl to currentUrl)
                }

                if (isHlsUrl(fixedUrl)) {
                    emitPlaylist(fixedUrl, currentUrl)
                } else if (fixedUrl.contains("drive.google.com/file/d/", true)) {
                    runCatching { loadExtractor(normalizeDriveUrl(fixedUrl), currentUrl, subtitleCallback, countedCallback) }
                } else if (isGdrivePlayerUrl(fixedUrl) && seenPages.none { it.equals(fixedUrl.substringBefore("#"), true) }) {
                    queue.add(fixedUrl to currentUrl)
                } else {
                    runCatching { loadExtractor(fixedUrl, currentUrl, subtitleCallback, countedCallback) }
                }
            }

            if (emitted.isNotEmpty()) return
        }
    }
}
