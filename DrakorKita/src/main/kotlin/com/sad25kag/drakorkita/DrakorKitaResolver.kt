package com.sad25kag.drakorkita

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Base64

object DrakorKitaResolver {
    data class ApiPayload(
        val detailUrl: String,
        val title: String,
        val movieId: String,
        val episodeId: String,
        val serverXid: String,
        val tag: String,
        val c: String,
        val t: String,
        val ver: String,
        val cApiHost: String,
        val isMob: String,
        val isUc: String,
        val mediaType: String
    )

    fun normalizeUrl(url: String, mainUrl: String): String {
        val trimmed = url.trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .replace("\\/", "/")
        return when {
            trimmed.isBlank() -> ""
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> mainUrl.trimEnd('/') + trimmed
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> trimmed
        }
    }

    fun extractEmbedCandidates(document: Document, mainUrl: String): List<String> {
        val candidates = linkedSetOf<String>()

        document.select("iframe[src], embed[src], video[src], source[src]").forEach { element ->
            val src = element.attr("src").ifBlank { element.attr("data-src") }
            normalizeUrl(src, mainUrl).takeIf { it.isNotBlank() }?.let(candidates::add)
        }

        document.select("a[href], button[data-src], button[data-url], div[data-src], div[data-url], li[data-src], li[data-url]").forEach { element ->
            listOf(
                element.attr("href"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-video"),
                element.attr("data-link"),
                element.attr("data-embed")
            ).forEach { raw ->
                val fixed = normalizeUrl(raw, mainUrl)
                if (isPlayableOrEmbed(fixed)) candidates.add(fixed)
            }
        }

        document.select("option[value]").forEach { option ->
            val raw = option.attr("value").trim()
            val direct = normalizeUrl(raw, mainUrl)
            if (isPlayableOrEmbed(direct)) candidates.add(direct)

            decodeBase64(raw)?.let { decoded ->
                val decodedDoc = Jsoup.parse(decoded)
                decodedDoc.select("iframe[src], embed[src], video[src], source[src], a[href]").forEach { element ->
                    val url = normalizeUrl(
                        element.attr("src").ifBlank { element.attr("href") },
                        mainUrl
                    )
                    if (isPlayableOrEmbed(url)) candidates.add(url)
                }
                extractUrlsFromText(decoded, mainUrl).forEach(candidates::add)
            }
        }

        extractUnpackedUrls(document, mainUrl).forEach(candidates::add)
        extractUrlsFromText(document.html(), mainUrl).forEach(candidates::add)

        return candidates.filter { it.isNotBlank() }.distinct()
    }

    fun extractSubtitles(document: Document, mainUrl: String): List<SubtitleFile> {
        return document.select("track[src], a[href$=.srt], a[href$=.vtt]").mapNotNull { element ->
            val url = normalizeUrl(element.attr("src").ifBlank { element.attr("href") }, mainUrl)
            if (url.isBlank()) return@mapNotNull null
            val label = element.attr("srclang")
                .ifBlank { element.attr("label") }
                .ifBlank { element.text() }
                .ifBlank { "Indonesia" }
            SubtitleFile(label, url)
        }.distinctBy { it.url }
    }

    suspend fun resolveApiPlayback(
        providerName: String,
        mainUrl: String,
        payload: ApiPayload,
        ajaxHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = linkedSetOf<String>()
        val cApiHost = payload.cApiHost.trimEnd('/')

        val serverJson = if (payload.episodeId.isNotBlank()) {
            apiGetJson(
                url = "$cApiHost/server_mob.php" +
                    "?is_mob=${payload.isMob}" +
                    "&is_uc=${payload.isUc}" +
                    "&episode_id=${encode(payload.episodeId)}" +
                    "&tag=${encode(payload.tag)}" +
                    "&server_xid=${encode(payload.serverXid)}" +
                    "&c=${encode(payload.c)}" +
                    "&t=${encode(payload.t)}" +
                    "&ver=${encode(payload.ver)}",
                headers = ajaxHeaders,
                referer = payload.detailUrl
            )
        } else {
            null
        }

        val serverData = serverJson?.optJSONObject("data") ?: JSONObject()
        val episodeId = serverData.optString("id").ifBlank { payload.episodeId }
        val serverId = serverData.optString("server_id").ifBlank { payload.serverXid }
        val tag = serverData.optString("tag").ifBlank { payload.tag }
        val quality = serverData.optString("qua").ifBlank { "web" }
        val res = serverData.optString("res").ifBlank { "1080" }

        if (episodeId.isNotBlank()) {
            val videoJson = apiGetJson(
                url = "$cApiHost/video.php" +
                    "?is_mob=${payload.isMob}" +
                    "&is_uc=${payload.isUc}" +
                    "&id=${encode(episodeId)}" +
                    "&qua=${encode(quality)}" +
                    "&server_id=${encode(serverId)}" +
                    "&tag=${encode(tag)}" +
                    "&c=${encode(payload.c)}" +
                    "&t=${encode(payload.t)}" +
                    "&ver=${encode(payload.ver)}",
                headers = ajaxHeaders,
                referer = payload.detailUrl
            )

            videoJson?.let { json ->
                listOf("file", "source", "video", "hls", "url").forEach { field ->
                    val value = json.optString(field)
                    if (value.isNotBlank()) extractUrlsFromText(value, mainUrl).forEach(candidates::add)
                }
                json.optJSONObject("dl")?.let { dl ->
                    dl.keys().forEach { key ->
                        extractUrlsFromText(dl.optString(key), mainUrl).forEach(candidates::add)
                    }
                }
            }

            listOf(
                Triple("video_sb.php", "sb_url", "dqt"),
                Triple("video_hydrax.php", "hydrax_url", "hydrax"),
                Triple("video_p2p.php", "p2p_url", "p2p")
            ).forEach { (endpoint, field, _) ->
                val json = apiGetJson(
                    url = "$cApiHost/$endpoint" +
                        "?is_mob=${payload.isMob}" +
                        "&is_uc=${payload.isUc}" +
                        "&id=${encode(episodeId)}" +
                        "&qua=${encode(quality)}" +
                        "&res=${encode(res)}" +
                        "&server_id=${encode(serverId)}" +
                        "&tag=${encode(tag)}" +
                        "&c=${encode(payload.c)}" +
                        "&t=${encode(payload.t)}" +
                        "&ver=${encode(payload.ver)}",
                    headers = ajaxHeaders,
                    referer = payload.detailUrl
                )
                json?.optString(field)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { candidates.add(normalizeUrl(it, mainUrl)) }
            }
        }

        if (candidates.isEmpty()) return false

        return resolveCandidates(
            providerName = providerName,
            mainUrl = mainUrl,
            pageUrl = payload.detailUrl,
            candidates = candidates.toList(),
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    suspend fun resolveCandidates(
        providerName: String,
        mainUrl: String,
        pageUrl: String,
        candidates: List<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val seen = linkedSetOf<String>()
        var handled = false

        suspend fun resolve(link: String, referer: String, depth: Int) {
            val fixed = normalizeUrl(link, mainUrl)
            if (fixed.isBlank() || !seen.add(fixed) || depth > 2) return

            when {
                fixed.contains(".m3u8", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            providerName,
                            providerName,
                            fixed,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.quality = parseQuality(fixed)
                        }
                    )
                    handled = true
                }
                fixed.contains(".mp4", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            providerName,
                            providerName,
                            fixed,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            this.quality = parseQuality(fixed)
                        }
                    )
                    handled = true
                }
                else -> {
                    runCatching {
                        loadExtractor(fixed, referer, subtitleCallback, callback)
                    }.onSuccess {
                        handled = true
                    }

                    if (shouldScanNestedPage(fixed)) {
                        runCatching {
                            val response = app.get(
                                url = fixed,
                                headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36",
                                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                                ),
                                referer = referer
                            )
                            val doc = response.document
                            extractSubtitles(doc, mainUrl).forEach(subtitleCallback)
                            extractEmbedCandidates(doc, mainUrl).forEach { nested ->
                                resolve(nested, fixed, depth + 1)
                            }
                        }
                    }
                }
            }
        }

        candidates.forEach { resolve(it, pageUrl, 0) }
        return handled
    }


    private fun decodeBase64(value: String): String? {
        val normalized = value.trim()
        if (normalized.isBlank()) return null
        val padded = normalized.padEnd(normalized.length + ((4 - normalized.length % 4) % 4), '=')
        return runCatching {
            String(Base64.getDecoder().decode(padded))
        }.getOrElse {
            runCatching { String(Base64.getUrlDecoder().decode(padded)) }.getOrNull()
        }
    }

    private suspend fun apiGetJson(
        url: String,
        headers: Map<String, String>,
        referer: String
    ): JSONObject? {
        return runCatching {
            JSONObject(
                app.get(
                    url = url,
                    headers = headers,
                    referer = referer
                ).text
            )
        }.getOrNull()
    }

    private fun extractUnpackedUrls(document: Document, mainUrl: String): List<String> {
        val results = linkedSetOf<String>()
        document.select("script").forEach { script ->
            val scriptText = script.data().ifBlank { script.html() }
            if (scriptText.contains("function(p,a,c,k,e,d)")) {
                runCatching { JsUnpacker(scriptText).unpack() }.getOrNull()?.let { unpacked ->
                    extractUrlsFromText(unpacked, mainUrl).forEach(results::add)
                    buildDqtHlsCandidates(unpacked).forEach(results::add)
                }
            }
        }
        return results.toList()
    }

    private fun buildDqtHlsCandidates(unpacked: String): List<String> {
        val direct = Regex("""https?://[^'"\s<>]+\.m3u8[^'"\s<>]*""")
            .find(unpacked)
            ?.value
            ?.replace("\\/", "/")
        if (!direct.isNullOrBlank()) return listOf(direct)

        val tokens = Regex("""['"]([A-Za-z0-9_-]{8,})['"]""")
            .findAll(unpacked)
            .map { it.groupValues[1] }
            .toList()
        val streamId = tokens.firstOrNull { it.length >= 20 }
        val folder = tokens.firstOrNull { it.length >= 12 && it != streamId }
        val expires = Regex("""\b(17\d{8,})\b""")
            .findAll(unpacked)
            .map { it.groupValues[1] }
            .firstOrNull()
        val fileId = Regex("""file_code['"]?\s*[:=]\s*['"]?([A-Za-z0-9_-]+)""")
            .find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""\b([A-Za-z0-9]{10,})_n\b""")
                .find(unpacked)
                ?.groupValues
                ?.getOrNull(1)

        if (streamId.isNullOrBlank() || folder.isNullOrBlank() || expires.isNullOrBlank() || fileId.isNullOrBlank()) {
            return emptyList()
        }

        return listOf("https://dqt.my.id/stream/$streamId/$folder/$expires/$fileId/master.m3u8")
    }

    private fun extractUrlsFromText(text: String, mainUrl: String): List<String> {
        val results = linkedSetOf<String>()
        val normalized = text
            .replace("\\/", "/")
            .replace("&amp;", "&")
        val urlRegex = Regex("""https?://[^'"\\\s<>]+|//[^'"\s<>]+""")
        urlRegex.findAll(normalized).forEach { match ->
            val fixed = normalizeUrl(match.value, mainUrl)
                .trimEnd(',', '.', ';', ')', ']', '}')
            if (isPlayableOrEmbed(fixed)) results.add(fixed)
        }
        return results.toList()
    }

    private fun isPlayableOrEmbed(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        return lower.contains("/embed") ||
            lower.contains("/e/") ||
            lower.contains("/v/") ||
            lower.contains("iframe") ||
            lower.contains("player") ||
            lower.contains("stream") ||
            lower.contains("watch") ||
            lower.contains("abysscdn") ||
            lower.contains("dqt.my.id") ||
            lower.contains("uyeshare") ||
            lower.contains("drakorkita.stream") ||
            lower.contains(".m3u8") ||
            lower.contains(".mp4")
    }

    private fun shouldScanNestedPage(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("dqt.my.id") ||
            lower.contains("abysscdn") ||
            lower.contains("drakorkita.stream") ||
            lower.contains("uyeshare")
    }

    private fun parseQuality(url: String): Int {
        return Regex("""(2160|1440|1080|720|480|360|240)p""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?:^|[^\d])(2160|1440|1080|720|480|360|240)(?:[^\d]|$)""")
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
