package com.sad25kag.animeindo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

open class GdrivePlayerTo : ExtractorApi() {
    override var name = "GdrivePlayer"
    override var mainUrl = "https://gdriveplayer.to"
    override val requiresReferer = true

    private val htmlHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    private fun normalizeEscapedText(text: String): String {
        return text
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
    }

    private fun normalizePlayerUrl(rawUrl: String, baseUrl: String): String? {
        val cleaned = rawUrl
            .trim()
            .trim('"', '\'', '`')
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .takeIf { it.isNotBlank() }
            ?: return null

        return when {
            cleaned.startsWith("http://", true) || cleaned.startsWith("https://", true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "$mainUrl$cleaned"
            cleaned.startsWith("hlsplaylist.php", true) -> "$mainUrl/$cleaned"
            else -> {
                val cleanBase = baseUrl.substringBefore("?").substringBefore("#")
                val baseDir = if (cleanBase.substringAfter("://", "").contains("/")) {
                    cleanBase.substringBeforeLast("/")
                } else {
                    mainUrl
                }
                "$baseDir/$cleaned"
            }
        }.substringBefore("#").trim()
    }

    private fun collectHlsPlaylistUrls(text: String, baseUrl: String): List<String> {
        val normalized = normalizeEscapedText(text)
        val candidates = linkedSetOf<String>()

        val patterns = listOf(
            Regex("""(?i)(?:https?:)?//gdriveplayer\.to/hlsplaylist\.php[^"'<>\\\s]+"""),
            Regex("""(?i)/hlsplaylist\.php\?[^"'<>\\\s]+"""),
            Regex("""(?i)(?<![A-Za-z0-9_./-])hlsplaylist\.php\?[^"'<>\\\s]+"""),
            Regex("""(?i)(?:file|url|src)\s*[:=]\s*["']([^"']*hlsplaylist\.php[^"']*)["']""")
        )

        for (pattern in patterns) {
            for (match in pattern.findAll(normalized)) {
                val raw = match.groups[1]?.value ?: match.value
                normalizePlayerUrl(raw, baseUrl)?.let { candidates.add(it) }
            }
        }

        return candidates.toList()
    }

    private fun collectSubtitleUrls(text: String, baseUrl: String): List<String> {
        val normalized = normalizeEscapedText(text)
        val candidates = linkedSetOf<String>()

        val patterns = listOf(
            Regex("""(?i)(?:https?:)?//gdriveplayer\.to/subproxy\.php[^"'<>\\\s]+"""),
            Regex("""(?i)/subproxy\.php\?[^"'<>\\\s]+"""),
            Regex("""(?i)(?<![A-Za-z0-9_./-])subproxy\.php\?[^"'<>\\\s]+"""),
            Regex("""(?i)(?:tracks|captions|subtitles|file)\s*[:=]\s*["']([^"']*subproxy\.php[^"']*)["']""")
        )

        for (pattern in patterns) {
            for (match in pattern.findAll(normalized)) {
                val raw = match.groups[1]?.value ?: match.value
                normalizePlayerUrl(raw, baseUrl)?.let { candidates.add(it) }
            }
        }

        return candidates.toList()
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
            "Referer" to playerUrl,
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val playerUrl = normalizePlayerUrl(url, mainUrl) ?: return
        val pageReferer = referer ?: mainUrl
        val emitted = linkedSetOf<String>()

        suspend fun emitPlaylist(rawPlaylistUrl: String): Boolean {
            val playlistUrl = normalizePlayerUrl(rawPlaylistUrl, playerUrl) ?: return false
            if (!playlistUrl.contains("hlsplaylist.php", true) && !playlistUrl.contains(".m3u8", true)) return false
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

        if (playerUrl.contains("hlsplaylist.php", true) || playerUrl.contains(".m3u8", true)) {
            emitPlaylist(playerUrl)
            return
        }

        val playerText = runCatching {
            app.get(
                playerUrl,
                referer = pageReferer,
                headers = htmlHeaders + mapOf("Referer" to pageReferer)
            ).text
        }.getOrNull() ?: return

        for (subtitleUrl in collectSubtitleUrls(playerText, playerUrl)) {
            subtitleCallback(SubtitleFile("Indonesian", subtitleUrl))
        }

        for (playlistUrl in collectHlsPlaylistUrls(playerText, playerUrl)) {
            emitPlaylist(playlistUrl)
        }
        if (emitted.isNotEmpty()) return

        val unpacked = runCatching { getAndUnpack(playerText) }.getOrDefault("")
        if (unpacked.isNotBlank()) {
            for (subtitleUrl in collectSubtitleUrls(unpacked, playerUrl)) {
                subtitleCallback(SubtitleFile("Indonesian", subtitleUrl))
            }
            for (playlistUrl in collectHlsPlaylistUrls(unpacked, playerUrl)) {
                emitPlaylist(playlistUrl)
            }
        }
    }
}
