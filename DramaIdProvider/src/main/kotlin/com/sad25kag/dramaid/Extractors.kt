package com.sad25kag.dramaid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

class DramaIdHalahgan : ExtractorApi() {
    override val name = "Halahgan"
    override val mainUrl = "https://stordl.halahgan.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = normalizeUrl(url, mainUrl) ?: return
        val id = fixedUrl.substringAfterLast("/").substringBefore("?").trim()
        if (id.isBlank()) return

        val pageReferer = "$mainUrl/"
        val quality = qualityFromUrl(fixedUrl)

        val streamApis = listOf(
            "$mainUrl/streaming/$id?action=stream-url&id=$id",
            "$mainUrl/streaming//$id?action=stream-url&id=$id"
        )

        var stream: String? = null
        for (api in streamApis) {
            stream = resolveApi(api, pageReferer)
            if (stream != null) break
        }

        if (stream == null) {
            stream = resolveFromStreamingPage("$mainUrl/streaming/$id")
                ?: resolveFromStreamingPage("$mainUrl/streaming//$id")
        }

        if (stream != null) {
            emit(stream, "Stream", pageReferer, quality, callback)
        }

        val nameParam = fixedUrl.substringAfter("?", "")
            .split("&")
            .firstOrNull { it.substringBefore("=") == "name" }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }

        val fileApis = listOf(
            buildString {
                append("$mainUrl/streaming/$id?action=file-url&id=$id")
                if (nameParam != null) append("&name=").append(nameParam)
            },
            buildString {
                append("$mainUrl/$id?action=file-url&id=$id")
                if (nameParam != null) append("&name=").append(nameParam)
            }
        )

        for (api in fileApis) {
            val download = resolveApi(api, fixedUrl)
            if (download != null && download != stream) {
                emit(download, "Download", fixedUrl, quality, callback)
                break
            }
        }
    }

    private suspend fun resolveApi(apiUrl: String, referer: String): String? {
        val response = runCatching {
            app.get(
                apiUrl,
                referer = referer,
                headers = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "User-Agent" to USER_AGENT,
                )
            ).text
        }.getOrNull() ?: return null

        return parseCandidateUrls(response)
            .firstOrNull()
            ?.jsonUrlDecode()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun resolveFromStreamingPage(streamingUrl: String): String? {
        val document = runCatching {
            app.get(streamingUrl, referer = "$mainUrl/", headers = mapOf("User-Agent" to USER_AGENT)).document
        }.getOrNull() ?: return null

        document.selectFirst("video source[src], video[src]")?.let { source ->
            val sourceUrl = source.attr("abs:src").ifBlank { source.attr("src") }
            if (sourceUrl.isNotBlank()) return sourceUrl
        }

        parseCandidateUrls(document.html()).firstOrNull()?.let { return it }

        val api = Regex("""STREAM_URL_API\s*=\s*["']([^"']+)["']""")
            .find(document.html())
            ?.groupValues
            ?.getOrNull(1)
            ?.let { normalizeUrl(it, streamingUrl) }
            ?: return null

        return resolveApi(api, streamingUrl)
    }

    private fun emit(
        url: String,
        label: String,
        refererUrl: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = "$name $label",
                url = url,
                type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE,
            ) {
                referer = refererUrl
                this.quality = quality
                headers = mapOf(
                    "Referer" to refererUrl,
                    "Range" to "bytes=0-",
                    "User-Agent" to USER_AGENT,
                )
            }
        )
    }
}

class DramaIdBerkasDrive : ExtractorApi() {
    override val name = "BerkasDrive"
    override val mainUrl = "https://dl.berkasdrive.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = normalizeUrl(url, mainUrl) ?: return
        val document = app.get(
            fixedUrl,
            referer = referer ?: "https://drama-id.com/",
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        val emitted = linkedSetOf<String>()

        fun addDirect(sourceUrl: String, label: String) {
            if (!emitted.add(sourceUrl)) return
            emitDirect(sourceUrl, label, fixedUrl, callback)
        }

        for (element in document.select("video source[src], video[src], .daftar_server li[data-url], [data-url], [data-src], source[src]")) {
            val sourceUrl = listOf(
                element.attr("abs:src"),
                element.attr("src"),
                element.attr("data-url"),
                element.attr("data-src"),
            ).firstOrNull { it.isNotBlank() }
                ?.let { normalizeUrl(it, fixedUrl) }
                ?: continue

            if (sourceUrl.isMediaUrl()) {
                addDirect(sourceUrl, element.text().ifBlank { "Server" })
            } else {
                loadExtractor(sourceUrl, fixedUrl, subtitleCallback, callback)
            }
        }

        for (sourceUrl in parseCandidateUrls(document.html())) {
            val normalized = normalizeUrl(sourceUrl, fixedUrl) ?: continue
            if (normalized.isMediaUrl()) {
                addDirect(
                    normalized,
                    normalized.substringAfterLast("/").substringBefore("?").ifBlank { "Server" }
                )
            } else {
                loadExtractor(normalized, fixedUrl, subtitleCallback, callback)
            }
        }

        decodeBerkasDriveId(fixedUrl)?.let { resolverUrl ->
            loadExtractor(resolverUrl, fixedUrl, subtitleCallback, callback)
        }
    }

    private fun emitDirect(
        url: String,
        label: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = "$name ${label.cleanLabel()}",
                url = url,
                type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                referer = refererUrl
                quality = qualityFromUrl(url)
                headers = mapOf(
                    "Referer" to refererUrl,
                    "Range" to "bytes=0-",
                    "User-Agent" to USER_AGENT,
                )
            }
        )
    }
}

private fun decodeBerkasDriveId(url: String): String? {
    if (!url.contains("dl.berkasdrive.com/streaming", true)) return null
    return url.substringAfter("?", "")
        .split("&")
        .firstOrNull { it.substringBefore("=") == "id" }
        ?.substringAfter("=")
        ?.let { URLDecoder.decode(it, "UTF-8") }
        ?.let(::decodeBase64)
        ?.let { normalizeUrl(it, "https://drama-id.com/") }
}

private fun decodeBase64(value: String): String? {
    val clean = value.trim().replace("\\s".toRegex(), "")
    if (clean.isBlank()) return null
    val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
    return runCatching { String(Base64.getDecoder().decode(padded)) }.getOrNull()
}

private fun normalizeUrl(raw: String, baseUrl: String): String? {
    val clean = Jsoup.parse(raw).text()
        .trim()
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .takeIf { it.isNotBlank() && !it.startsWith("javascript:", true) && !it.startsWith("data:", true) }
        ?: return null

    return when {
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
        else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
    }
}

private fun String.jsonUrlDecode(): String {
    return replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003D", "=")
        .replace("\\u003f", "?")
        .replace("\\u003F", "?")
        .replace("\\u002F", "/")
}

private fun parseCandidateUrls(text: String): List<String> {
    val output = linkedSetOf<String>()
    val clean = text.jsonUrlDecode().replace("&amp;", "&")
    val jsonKeys = listOf(
        "url",
        "file",
        "src",
        "source",
        "video",
        "videoUrl",
        "streamUrl",
        "stream_url",
        "downloadUrl",
        "download_url",
        "hls",
        "hlsUrl",
        "hls_url"
    )
    val keyPattern = jsonKeys.joinToString("|")

    runCatching {
        val obj = JSONObject(clean)
        for (key in jsonKeys) {
            obj.optString(key).trim().takeIf { it.isNotBlank() }?.let { output.add(it) }
        }
    }

    Regex("""["'](?:$keyPattern)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(clean)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .forEach { output.add(it) }

    Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
        .findAll(clean)
        .map { it.value }
        .filter { it.isMediaUrl() || it.isKnownResolverCandidate() }
        .forEach { output.add(it) }

    Regex("""https?%3A%2F%2F[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
        .findAll(clean)
        .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
        .filter { it.isMediaUrl() || it.isKnownResolverCandidate() }
        .forEach { output.add(it) }

    return output.map { it.jsonUrlDecode() }.filter { it.isNotBlank() }
}

private fun String.isMediaUrl(): Boolean {
    return Regex("""(?i)\.(mp4|m3u8)(?:$|[?#&])""").containsMatchIn(this)
}

private fun String.isKnownResolverCandidate(): Boolean {
    val value = lowercase()
    return value.contains("stordl.halahgan.com") ||
        value.contains("dl.berkasdrive.com") ||
        value.contains("berkasdrive.com") ||
        value.contains("halahgan.com") ||
        value.contains("/streaming") ||
        value.contains("/embed/") ||
        value.contains("/player/") ||
        value.contains("filemoon") ||
        value.contains("streamwish") ||
        value.contains("wishfast") ||
        value.contains("dood") ||
        value.contains("streamtape") ||
        value.contains("vidhide") ||
        value.contains("vidguard") ||
        value.contains("voe.") ||
        value.contains("mixdrop") ||
        value.contains("mp4upload") ||
        value.contains("lulustream") ||
        value.contains("lulu") ||
        value.contains("krakenfiles") ||
        value.contains("acefile") ||
        value.contains("drive.google") ||
        value.contains("ok.ru")
}

private fun qualityFromUrl(value: String): Int {
    return Regex("""\b(2160|1440|1080|720|480|360|240)p?\b""", RegexOption.IGNORE_CASE)
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: Qualities.Unknown.value
}

private fun String.cleanLabel(): String {
    return replace(Regex("""\s+"""), " ").trim().ifBlank { "Server" }
}
