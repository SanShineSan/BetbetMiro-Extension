package com.sad25kag.kikonime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import java.net.URI

suspend fun ExtractorApi.getUrlResult(
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var emitted = false
    runCatching {
        getUrl(url, referer, subtitleCallback) { link ->
            emitted = true
            callback(link)
        }
    }
    return emitted
}

class KikoGeoDailyMotion : KikoDailyMotion() {
    override val name = "GeoDailyMotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class KikoDailyMotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = extractId(url) ?: return
        val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$id"
        val response = app.get(metadataUrl, referer = url).text

        Regex(""""url"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)""", RegexOption.IGNORE_CASE)
            .findAll(response)
            .map { it.groupValues[1].replace("\\/", "/").replace("\\u0026", "&") }
            .filter { it.contains(".m3u8", true) }
            .distinct()
            .forEach { streamUrl ->
                M3u8Helper.generateM3u8(name, streamUrl, url).forEach(callback)
            }

        Regex(""""label"\s*:\s*"([^"]+)"[^\[]+\[\s*"([^"]+)""", RegexOption.IGNORE_CASE)
            .findAll(response)
            .forEach { match ->
                val lang = match.groupValues[1]
                val subUrl = match.groupValues[2].replace("\\/", "/")
                if (subUrl.startsWith("http")) subtitleCallback(SubtitleFile(url = subUrl, lang = lang))
            }
    }

    private fun extractId(url: String): String? {
        val clean = url.substringBefore("?")
        Regex("""(?:video=|/embed/video/|/video/)([A-Za-z0-9]+)""").find(clean)?.groupValues?.getOrNull(1)?.let { return it }
        return runCatching {
            URI(url).path.substringAfterLast('/').takeIf { it.matches(Regex("[kx][A-Za-z0-9]+")) }
        }.getOrNull()
    }
}

class KikoOkRuSSL : KikoOdnoklassniki() {
    override var name = "OkRuSSL"
    override var mainUrl = "https://ok.ru"
}

class KikoOkRuHTTP : KikoOdnoklassniki() {
    override var name = "OkRuHTTP"
    override var mainUrl = "http://ok.ru"
}

open class KikoOdnoklassniki : ExtractorApi() {
    override var name = "Odnoklassniki"
    override var mainUrl = "https://odnoklassniki.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/video/", "/videoembed/")
        val headers = mapOf(
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )
        val body = app.get(embedUrl, headers = headers, referer = referer ?: mainUrl).text
            .replace("\\&quot;", "\"")
            .replace("\\\\", "\\")
            .replace(Regex("\\\\u([0-9A-Fa-f]{4})")) { match ->
                Integer.parseInt(match.groupValues[1], 16).toChar().toString()
            }

        val videos = Regex(""""videos"\s*:\s*(\[[^]]+])""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::parseVideos)
            .orEmpty()

        videos.forEach { video ->
            val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            val quality = video.name.uppercase()
                .replace("MOBILE", "144p")
                .replace("LOWEST", "240p")
                .replace("LOW", "360p")
                .replace("SD", "480p")
                .replace("HD", "720p")
                .replace("FULL", "1080p")
                .replace("QUAD", "1440p")
                .replace("ULTRA", "2160p")

            callback(
                newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    this.quality = getQualityFromName(quality)
                    this.headers = headers
                }
            )
        }
    }

    private fun parseVideos(value: String): List<OkRuVideo> {
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val label = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    if (label.isNotBlank() && url.isNotBlank()) add(OkRuVideo(label, url))
                }
            }
        }.getOrDefault(emptyList())
    }

    private data class OkRuVideo(val name: String, val url: String)
}
