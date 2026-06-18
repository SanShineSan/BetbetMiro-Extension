package com.sad25kag.animeindo

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

    private val htmlHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5"
    )

    private fun normalizeEscapedText(text: String): String {
        var normalized = text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        repeat(2) {
            normalized = runCatching { URLDecoder.decode(normalized, "UTF-8") }
                .getOrDefault(normalized)
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
        }
        return normalized
    }

    private fun normalizePlayerUrl(rawUrl: String?, baseUrl: String): String? {
        val cleaned = rawUrl
            ?.trim()
            ?.trim('"', '\'', '`', ';')
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val decoded = runCatching { URLDecoder.decode(cleaned, "UTF-8") }
            .getOrDefault(cleaned)
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
            .trim('"', '\'', '`', ';')

        if (decoded == "#" || decoded.startsWith("javascript:", true)) return null

        val fixed = when {
            decoded.startsWith("http://", true) || decoded.startsWith("https://", true) -> decoded
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("/") -> "$mainUrl$decoded"
            decoded.startsWith("hlsplaylist.php", true) || decoded.startsWith("subproxy.php", true) ||
                decoded.startsWith("embed.php", true) || decoded.startsWith("embed2.php", true) -> "$mainUrl/$decoded"
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
        return lower.contains("gdriveplayer.to/") || lower.contains("/embed.php?") || lower.contains("/embed2.php?")
    }

    private fun collectUrls(text: String, baseUrl: String, patterns: List<Regex>): List<String> {
        val normalized = normalizeEscapedText(text)
        val candidates = linkedSetOf<String>()

        for (pattern in patterns) {
            for (match in pattern.findAll(normalized)) {
                val raw = match.groups[1]?.value ?: match.value
                normalizePlayerUrl(raw, baseUrl)?.let { candidates.add(it) }
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
                Regex("""(?i)(?:file|url|src)\s*[:=]\s*["']([^"']*(?:hlsplaylist\.php|\.m3u8)[^"']*)["']"""),
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

    private fun collectEmbedUrls(text: String, baseUrl: String): List<String> {
        return collectUrls(
            text,
            baseUrl,
            listOf(
                Regex("""(?i)(?:https?:)?//[^"'<>\\\s]*gdriveplayer[^"'<>\\\s]*/embed2?\.php\?[^"'<>\\\s]+"""),
                Regex("""(?i)/embed2?\.php\?[^"'<>\\\s]+"""),
                Regex("""(?i)(?<![A-Za-z0-9_./-])embed2?\.php\?[^"'<>\\\s]+"""),
                Regex("""(?i)(?:src|href|data-video|data-url|data-iframe|data-src|data-link|file|url)\s*[:=]\s*["']([^"']*(?:gdriveplayer[^"']*/embed2?\.php|/embed2?\.php)[^"']*)["']"""),
                Regex("""(?i)(?:https?:)?//drive\.google\.com/file/d/[^/"'<>\\\s]+/preview(?:\?[^"'<>\\\s]+)?"""),
                Regex("""(?i)https?://[^"'<>\\\s]+/file/d/[^/"'<>\\\s]+/preview(?:\?[^"'<>\\\s]+)?""")
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

        fun emitPlaylist(rawPlaylistUrl: String, playerUrl: String): Boolean {
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
        while (index < queue.size && index < 12) {
            val (currentUrl, pageReferer) = queue[index]
            index += 1

            if (!seenPages.add(currentUrl.substringBefore("#"))) continue

            if (isHlsUrl(currentUrl)) {
                emitPlaylist(currentUrl, pageReferer)
                continue
            }

            if (currentUrl.contains("drive.google.com/file/d/", true)) {
                runCatching { loadExtractor(currentUrl, pageReferer, subtitleCallback, countedCallback) }
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

            val unpacked = runCatching { getAndUnpack(pageText) }.getOrDefault("")
            val scanText = if (unpacked.isBlank()) pageText else "$pageText\n$unpacked"

            for (subtitleUrl in collectSubtitleUrls(scanText, currentUrl)) {
                subtitleCallback(SubtitleFile("Indonesian", subtitleUrl))
            }

            for (playlistUrl in collectHlsUrls(scanText, currentUrl)) {
                emitPlaylist(playlistUrl, currentUrl)
            }
            if (emitted.isNotEmpty()) return

            val nested = linkedSetOf<String>()
            nested.addAll(collectEmbedUrls(scanText, currentUrl))
            nested.addAll(collectNestedUrls(scanText, currentUrl))

            for (nestedUrl in nested) {
                val fixedUrl = normalizePlayerUrl(nestedUrl, currentUrl) ?: continue
                if (isHlsUrl(fixedUrl)) {
                    emitPlaylist(fixedUrl, currentUrl)
                } else if (fixedUrl.contains("drive.google.com/file/d/", true)) {
                    runCatching { loadExtractor(fixedUrl, currentUrl, subtitleCallback, countedCallback) }
                } else if (isGdrivePlayerUrl(fixedUrl) && seenPages.none { it.equals(fixedUrl.substringBefore("#"), true) }) {
                    queue.add(fixedUrl to currentUrl)
                }
            }

            if (emitted.isNotEmpty()) return
        }
    }
}
