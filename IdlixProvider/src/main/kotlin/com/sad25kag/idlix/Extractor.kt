package com.sad25kag.idlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

class Jeniusplay : ExtractorApi() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val document = app.get(url, referer = referer).document
        val hash = url.split("/").last().substringAfter("data=")

        val m3uLink = app.post(
            url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
            data = mapOf("hash" to hash, "r" to "${referer.orEmpty()}"),
            referer = referer,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        ).parsed<ResponseSource>().videoSource.replace(".txt", ".m3u8")

        generateM3u8(name, m3uLink, mainUrl).forEach(callback)

        document.select("script").forEach { script ->
            if (script.data().contains("eval(function(p,a,c,k,e,d)")) {
                val unpacked = getAndUnpack(script.data())
                val subData = unpacked.substringAfter("\"tracks\": [").substringBefore("],")
                    .ifBlank { unpacked.substringAfter("\"tracks\":[").substringBefore("],") }
                AppUtils.tryParseJson<List<Tracks>>("[$subData]")?.forEach { subtitle ->
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            getLanguage(subtitle.label ?: ""),
                            subtitle.file,
                        ),
                    )
                }
            }
        }
    }

    data class ResponseSource(
        @param:JsonProperty("hls") val hls: Boolean,
        @param:JsonProperty("videoSource") val videoSource: String,
        @param:JsonProperty("securedLink") val securedLink: String?,
    )

    data class Tracks(
        @param:JsonProperty("kind") val kind: String?,
        @param:JsonProperty("file") val file: String,
        @param:JsonProperty("label") val label: String?,
    )

    private fun getLanguage(str: String): String = when {
        str.contains("indonesia", true) || str.contains("bahasa", true) -> "Indonesian"
        else -> str
    }
}

class Majorplay : ExtractorApi() {
    override var name = "Majorplay"
    override var mainUrl = "https://e2e.majorplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val streamReferer = referer ?: url.originOfUrl()
        val response = app.get(
            url,
            referer = streamReferer,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "User-Agent" to IDLIX_USER_AGENT,
            ),
        )
        val document = response.document
        val mediaUrls = mutableListOf<String>()

        document.select("source[src], video[src]").forEach { source ->
            source.attr("src")
                .fixAgainst(url)
                ?.takeIf { it.isLikelyMediaUrl() }
                ?.let(mediaUrls::add)
        }

        document.select("script").forEach { script ->
            mediaUrls.addAll(extractMediaUrls(script.data(), url))
        }

        emitMajorplayMedia(mediaUrls.distinct(), url, streamReferer, callback)

        val scripts = document.selectFirst("script:containsData(subtitles)")?.data() ?: return
        val subRegex = Regex("""\\"label\\":\\"([^\\"]*?)\\"[^}]*?\\"path\\":\\"([^\\"]*?)\\"""")

        for (match in subRegex.findAll(scripts)) {
            val label = match.groupValues[1]
            val vttUrl = match.groupValues[2]
                .unescapeMajorplayPayload()
                .fixAgainst(url)
                ?: continue

            subtitleCallback.invoke(newSubtitleFile(label, vttUrl))
        }
    }

    private suspend fun emitMajorplayMedia(
        initialUrls: List<String>,
        iframeUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val queue = initialUrls.distinct().toMutableList()
        val visited = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val mediaUrl = queue.removeAt(0)
            if (!visited.add(mediaUrl)) continue

            if (mediaUrl.isConfigJsonUrl()) {
                val nestedText = runCatching {
                    app.get(
                        mediaUrl,
                        referer = iframeUrl,
                        headers = mapOf(
                            "Referer" to iframeUrl,
                            "Origin" to iframeUrl.originOfUrl(),
                            "User-Agent" to IDLIX_USER_AGENT,
                            "Accept" to "*/*",
                        ),
                    ).text
                }.getOrNull().orEmpty()

                queue.addAll(extractMediaUrls(nestedText, mediaUrl).filterNot { it in visited || it in queue })
                continue
            }

            if (mediaUrl.contains(".m3u8", true)) {
                var emitted = false
                runCatching {
                    generateM3u8(name, mediaUrl, iframeUrl).forEach { link ->
                        callback(link)
                        emitted = true
                    }
                }.onFailure { error ->
                    Log.d(name, "M3U8 helper failed for $mediaUrl: ${error.message}")
                }
                if (!emitted) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = mediaUrl,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = iframeUrl
                            this.quality = Qualities.Unknown.value
                        },
                    )
                }
            } else if (mediaUrl.contains(".mp4", true)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = mediaUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = iframeUrl
                        this.quality = Qualities.Unknown.value
                    },
                )
            }
        }
    }

    private fun extractMediaUrls(text: String, baseUrl: String): List<String> {
        val normalized = text.unescapeMajorplayPayload()
        val urls = mutableListOf<String>()
        val patterns = listOf(
            Regex("""["'](?:file|src|url|link|source|hls)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\\\s<>]+(?:m3u8|mp4|(?:config|data)-[^"'\\\s<>]+?\.json)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE),
        )

        patterns.forEach { regex ->
            regex.findAll(normalized).forEach { match ->
                val candidate = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
                candidate
                    .unescapeMajorplayPayload()
                    .fixAgainst(baseUrl)
                    ?.takeIf { it.isLikelyMediaUrl() }
                    ?.let(urls::add)
            }
        }

        return urls.distinct()
    }

    private fun String.isLikelyMediaUrl(): Boolean {
        val value = lowercase()
        return value.contains(".m3u8") || value.contains(".mp4") || isConfigJsonUrl()
    }

    private fun String.isConfigJsonUrl(): Boolean {
        val value = lowercase()
        return value.contains(".json") &&
            (value.contains("config-") || value.contains("data-") || value.contains("/config") || value.contains("/data"))
    }

    private fun String.unescapeMajorplayPayload(): String {
        return replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003D", "=", ignoreCase = true)
            .replace("\\u003F", "?", ignoreCase = true)
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\\"", "\"")
            .trim()
    }

    private fun String.fixAgainst(baseUrl: String): String? {
        val value = trim()
        if (value.isBlank()) return null
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return runCatching { URI(baseUrl).resolve(value).toString() }.getOrNull()
    }

    private fun String.originOfUrl(): String {
        return runCatching {
            val uri = URI(this)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return@runCatching mainUrl
            "$scheme://$host"
        }.getOrDefault(mainUrl)
    }
}

internal const val IDLIX_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
