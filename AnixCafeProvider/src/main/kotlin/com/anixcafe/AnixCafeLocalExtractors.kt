package com.anixcafe

import android.util.Log
import com.lagradost.api.Log as CsLog
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class AnixCafeGeoDailymotion : AnixCafeDailymotion() {
    override val name = "GeoDailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class AnixCafeDailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false
    private val baseUrl = "https://www.dailymotion.com"
    private val geoBaseUrl = "https://geo.dailymotion.com"
    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url) ?: return

        if (embedUrl.contains("geo.dailymotion.com", true)) {
            val loaded = resolveGeoPlayer(embedUrl, referer, subtitleCallback, callback)
            if (loaded) return
        }

        val id = getVideoId(embedUrl) ?: return
        resolveMetadataVideo(id, embedUrl, subtitleCallback, callback)
    }

    private suspend fun resolveGeoPlayer(
        embedUrl: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val accessId = getGeoAccessId(embedUrl) ?: return false
        val embedder = URLEncoder.encode(referer ?: "https://anixcafe.com/", "UTF-8")
        val metadataUrl = "$geoBaseUrl/video/$accessId.json?legacy=true&embedder=$embedder"
        val response = runCatching {
            app.get(
                metadataUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Accept" to "application/json,text/plain,*/*",
                )
            ).text
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(response) }.getOrNull() ?: return false
        emitSubtitles(json, subtitleCallback)

        val urls = extractQualityUrls(json)
        if (urls.isNotEmpty()) {
            urls.forEach { videoUrl ->
                getStream(videoUrl, "GeoDailymotion", embedUrl, callback)
            }
            return true
        }

        val canonicalId = json.optString("id").trim().takeIf { it.matches(videoIdRegex) } ?: return false
        return resolveMetadataVideo(canonicalId, embedUrl, subtitleCallback, callback)
    }

    private suspend fun resolveMetadataVideo(
        id: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val metaDataUrl = "$baseUrl/player/metadata/video/$id"
        val response = runCatching {
            app.get(
                metaDataUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "application/json,text/plain,*/*",
                )
            ).text
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(response) }.getOrNull()
        val urls = if (json != null) {
            emitSubtitles(json, subtitleCallback)
            extractQualityUrls(json)
        } else {
            Regex(""""url"\s*:\s*"([^"]+)"""")
                .findAll(response)
                .map { it.groupValues[1].replace("\\/", "/") }
                .filter { it.contains(".m3u8", true) }
                .distinct()
                .toList()
        }

        urls.forEach { videoUrl ->
            getStream(videoUrl, this.name, referer, callback)
        }

        return urls.isNotEmpty()
    }

    private fun extractQualityUrls(json: JSONObject): List<String> {
        val urls = linkedSetOf<String>()
        val qualities = json.optJSONObject("qualities")

        qualities?.keys()?.forEach { quality ->
            val entries = qualities.optJSONArray(quality) ?: return@forEach
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val type = item.optString("type").lowercase()
                val url = item.optString("url").trim()
                    .replace("\\/", "/")
                    .takeIf { it.isNotBlank() }
                    ?: continue

                if (type.contains("mpegurl") || type.contains("x-mpegurl") || url.contains(".m3u8", true)) {
                    urls.add(url)
                }
            }
        }

        return urls.toList()
    }

    private fun emitSubtitles(json: JSONObject, subtitleCallback: (SubtitleFile) -> Unit) {
        val subtitles = json.optJSONObject("subtitles")
        subtitles?.keys()?.forEach { lang ->
            val value = subtitles.opt(lang)
            val entries = when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("data") ?: JSONArray().put(value)
                else -> JSONArray()
            }

            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val label = item.optString("label", lang).ifBlank { lang }
                val urls = item.optJSONArray("urls")
                if (urls != null) {
                    for (urlIndex in 0 until urls.length()) {
                        val subUrl = urls.optString(urlIndex).trim()
                        if (subUrl.isNotBlank()) subtitleCallback(SubtitleFile(url = subUrl, lang = label))
                    }
                } else {
                    val subUrl = item.optString("url").trim()
                    if (subUrl.isNotBlank()) subtitleCallback(SubtitleFile(url = subUrl, lang = label))
                }
            }
        }
    }

    private fun getEmbedUrl(url: String): String? {
        if (url.contains("geo.dailymotion.com", true)) return url
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (url.contains("dai.ly", true)) return url
        return null
    }

    private fun getGeoAccessId(url: String): String? {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        return listOf(
            Regex("""(?i)[?&]video=([A-Za-z0-9]+)"""),
            Regex("""(?i)/video/([A-Za-z0-9]+)\.json"""),
            Regex("""(?i)/video/([A-Za-z0-9]+)"""),
        ).firstNotNullOfOrNull { regex -> regex.find(decoded)?.groupValues?.getOrNull(1) }
            ?.takeIf { it.matches(videoIdRegex) }
    }

    private fun getVideoId(url: String): String? {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val id = when {
            decoded.contains("dai.ly", true) -> URI(decoded).path.trim('/').substringBefore("/")
            decoded.contains("geo.dailymotion.com", true) -> getGeoAccessId(decoded).orEmpty()
            else -> URI(decoded).path.substringAfterLast("/")
        }

        return if (id.matches(videoIdRegex)) id else null
    }

    private suspend fun getStream(
        streamLink: String,
        name: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        generateM3u8(
            source = name,
            streamUrl = streamLink,
            referer = referer,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer,
            )
        ).forEach(callback)
    }
}

class AnixCafeOkRuSSL : AnixCafeOdnoklassniki() {
    override var name = "OK.ru"
    override var mainUrl = "https://ok.ru"
}

class AnixCafeOkRuHTTP : AnixCafeOdnoklassniki() {
    override var name = "OK.ru"
    override var mainUrl = "http://ok.ru"
}

open class AnixCafeOdnoklassniki : ExtractorApi() {
    override val name = "OK.ru"
    override val mainUrl = "https://odnoklassniki.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = normalizeEmbedUrl(url)
        val embedReferer = referer ?: "https://anixcafe.com/"
        val embedHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Connection" to "keep-alive",
            "User-Agent" to USER_AGENT,
            "Referer" to embedReferer,
        )
        val mediaHeaders = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Origin" to "https://ok.ru",
            "User-Agent" to USER_AGENT,
            "Referer" to embedUrl,
        )

        val videoReq = app.get(
            embedUrl,
            referer = embedReferer,
            headers = embedHeaders,
        ).text.cleanOkRuPayload()

        val hlsManifest = extractOkRuField(videoReq, "hlsManifestUrl")
        if (!hlsManifest.isNullOrBlank()) {
            val hlsLinks = runCatching {
                M3u8Helper.generateM3u8(
                    source = this.name,
                    streamUrl = hlsManifest,
                    referer = embedUrl,
                    headers = mediaHeaders,
                )
            }.getOrElse { error ->
                Log.w("AnixCafeOkRu", "Failed to resolve OK.ru HLS manifest", error)
                emptyList()
            }

            if (hlsLinks.isNotEmpty()) {
                hlsLinks.forEach(callback)
                return
            }
        }

        val metadataWebm = extractOkRuField(videoReq, "metadataWebmUrl")
        if (!metadataWebm.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = metadataWebm,
                    type = INFER_TYPE,
                ) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mediaHeaders
                }
            )
            return
        }

        val videosStr = Regex(""""videos"\s*:\s*(\[[^\]]*])""")
            .find(videoReq)
            ?.groupValues
            ?.getOrNull(1)
            ?: throw ErrorLoadingException("Video not found")

        val videos = parseOkRuVideos(videosStr).takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("Video not found")

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
                .replace("ULTRA", "4k")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    type = INFER_TYPE,
                ) {
                    this.referer = embedUrl
                    this.quality = getQualityFromName(quality)
                    this.headers = mediaHeaders
                }
            )
        }
    }

    private fun normalizeEmbedUrl(url: String): String {
        return url
            .replace("https://odnoklassniki.ru", "https://ok.ru")
            .replace("http://odnoklassniki.ru", "https://ok.ru")
            .replace("http://ok.ru", "https://ok.ru")
            .replace("/video/", "/videoembed/")
    }

    private fun extractOkRuField(raw: String, field: String): String? {
        return Regex(""""$field"\s*:\s*"([^"]+)""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.cleanOkRuPayload()
            ?.takeIf { it.startsWith("http", true) }
    }

    private fun parseOkRuVideos(value: String): List<OkRuVideo> {
        return runCatching {
            val array = JSONArray(value)
            val results = mutableListOf<OkRuVideo>()

            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val url = item.optString("url").trim()

                if (name.isNotBlank() && url.isNotBlank()) {
                    results.add(OkRuVideo(name = name, url = url))
                }
            }

            results
        }.getOrElse { error ->
            Log.w("AnixCafeOkRu", "Failed to parse OK.ru videos", error)
            emptyList()
        }
    }

    private fun String.cleanOkRuPayload(): String {
        return this
            .replace("\\&quot;", "\"")
            .replace("&quot;", "\"")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("\\&", "&")
            .replace("\\=", "=")
            .replace("\\\\", "\\")
            .replace(Regex("\\\\u([0-9A-Fa-f]{4})")) { matchResult ->
                Integer.parseInt(matchResult.groupValues[1], 16).toChar().toString()
            }
    }

    private data class OkRuVideo(
        val name: String,
        val url: String,
    )
}

class AnixCafeVidguardBembed : AnixCafeVidguard() {
    override val mainUrl = "https://bembed.net"
}

class AnixCafeVidguardListeamed : AnixCafeVidguard() {
    override val mainUrl = "https://listeamed.net"
}

class AnixCafeVidguardVgfplay : AnixCafeVidguard() {
    override val mainUrl = "https://vgfplay.com"
}

open class AnixCafeVidguard : ExtractorApi() {
    override val name = "Vidguard"
    override val mainUrl = "https://vidguard.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(getEmbedUrl(url))
        val script = res.document.select("script:containsData(eval)").firstOrNull()?.data() ?: return
        val jsonText = runJS2(script).takeIf { it.isNotBlank() } ?: return
        val stream = runCatching { JSONObject(jsonText).optString("stream").trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return
        val watchlink = sigDecode(stream) ?: return

        callback.invoke(
            newExtractorLink(
                this.name,
                name,
                watchlink,
            ) {
                this.referer = mainUrl
            }
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun sigDecode(url: String): String? {
        return runCatching {
            val sig = url.substringAfter("sig=", "").substringBefore("&")
                .takeIf { it.isNotBlank() }
                ?: return@runCatching url

            val decodedSig = sig.chunked(2)
                .joinToString("") { (Integer.parseInt(it, 16) xor 2).toChar().toString() }
                .let {
                    val padding = when (it.length % 4) {
                        2 -> "=="
                        3 -> "="
                        else -> ""
                    }
                    String(Base64.decode((it + padding).toByteArray(Charsets.UTF_8)))
                }
                .dropLast(5)
                .reversed()
                .toCharArray()
                .apply {
                    for (i in indices step 2) {
                        if (i + 1 < size) {
                            this[i] = this[i + 1].also { this[i + 1] = this[i] }
                        }
                    }
                }
                .concatToString()
                .dropLast(5)

            url.replace(sig, decodedSig)
        }.getOrElse {
            CsLog.e("Vidguard", "Failed to decode signature: ${it.message}")
            null
        }
    }

    private fun runJS2(hideMyHtmlContent: String): String {
        var result = ""
        val r = Runnable {
            val rhino = Context.enter()
            rhino.optimizationLevel = -1
            val scope: Scriptable = rhino.initSafeStandardObjects()
            scope.put("window", scope, scope)
            try {
                rhino.evaluateString(
                    scope,
                    hideMyHtmlContent,
                    "JavaScript",
                    1,
                    null
                )
                val svgObject = scope.get("svg", scope)
                result = if (svgObject is NativeObject) {
                    NativeJSON.stringify(
                        Context.getCurrentContext(),
                        scope,
                        svgObject,
                        null,
                        null
                    ).toString()
                } else {
                    Context.toString(svgObject)
                }
            } catch (e: Exception) {
                CsLog.e("runJS", "Error executing JavaScript: ${e.message}")
            } finally {
                Context.exit()
            }
        }
        val t = Thread(ThreadGroup("A"), r, "thread_rhino", 8 * 1024 * 1024)
        t.start()
        t.join()
        t.interrupt()
        return result
    }

    private fun getEmbedUrl(url: String): String {
        return url.takeIf { it.contains("/d/") || it.contains("/v/") }
            ?.replace("/d/", "/e/")?.replace("/v/", "/e/") ?: url
    }
}
