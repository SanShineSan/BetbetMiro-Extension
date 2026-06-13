package com.nodrakorid

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NoDrakorIDSf21VidPlayer : ExtractorApi() {
    override var name = "Sf21VidPlayer"
    override var mainUrl = "https://sf21.vidplayer.live"
    override var requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = NoDrakorIDUtils.decodeKnownRedirect(url)
        val videoId = extractVideoId(pageUrl) ?: return
        val sourceReferer = referer ?: NoDrakorIDSepeda.MAIN_URL
        val sourceHost = NoDrakorIDUtils.hostOf(sourceReferer).ifBlank { "richemmerson.com" }
        val apiUrl = "$mainUrl/api/v1/video?id=$videoId&w=421&h=935&r=$sourceHost"

        val encrypted = runCatching {
            app.get(
                apiUrl,
                referer = "$mainUrl/",
                headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT
                ),
                timeout = 20L
            ).text
        }.getOrNull()?.trim().orEmpty()
        if (encrypted.isBlank()) return

        val decrypted = decryptSf21(encrypted) ?: return
        val root = runCatching { mapper.readTree(decrypted) }.getOrNull() ?: return
        emitSubtitles(root, subtitleCallback)

        val emitted = linkedSetOf<String>()
        sf21PrimaryCandidates(root).forEach { rawUrl ->
            val videoUrl = absoluteToOrigin(mainUrl, rawUrl) ?: return@forEach
            if (!isSf21MediaUrl(videoUrl) || !emitted.add(normalizeMediaKey(videoUrl))) return@forEach
            emitHls(videoUrl, "$name HLS", callback)
        }
    }

    private fun extractVideoId(url: String): String? {
        val hash = url.substringAfter('#', "").substringBefore('&').substringBefore('?').trim()
        if (hash.length > 1) return hash
        Regex("""[?&]id=([^&#]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return url.trimEnd('/').substringAfterLast('/').takeIf { it.length > 1 && !it.contains('.') }
    }

    private fun emitSubtitles(root: JsonNode, subtitleCallback: (SubtitleFile) -> Unit) {
        val subtitles = root.path("subtitles")
        if (!subtitles.isObject) return
        subtitles.fields().forEach { entry ->
            val subUrl = absoluteToOrigin(mainUrl, entry.value.asText("")) ?: return@forEach
            subtitleCallback.invoke(SubtitleFile(entry.key, subUrl))
        }
    }

    /**
     * HAR evidence shows sf21 plays its own /hls/.../master.m3u8 URL.
     * Internal API names such as Tiktok/Google/Cloudflare are not exposed as CloudStream source labels.
     * Non-sf21 fallback fields are intentionally not emitted while the source-backed sf21 HLS exists,
     * to avoid turning CDN/internal route names into user-facing playback sources.
     */
    private fun sf21PrimaryCandidates(root: JsonNode): List<String> {
        val config = parseStreamingConfig(root.path("streamingConfig"))
        val primary = root.path("hlsVideoTiktok").asText("").takeIf { it.isNotBlank() && !isDisabled(config, "Tiktok") }
        val withParams = primary?.let { applyStreamingParams(it, config, "Tiktok") }
        if (!withParams.isNullOrBlank()) return listOf(withParams)

        return listOf(
            root.path("source").asText(""),
            root.path("hlsVideoGoogle").asText(""),
            root.path("cf").asText("")
        ).filter { it.isNotBlank() }
    }

    private fun parseStreamingConfig(node: JsonNode): JsonNode? {
        if (node.isObject) return node
        val text = node.asText("").takeIf { it.isNotBlank() } ?: return null
        return runCatching { mapper.readTree(text) }.getOrNull()
    }

    private fun isDisabled(config: JsonNode?, label: String): Boolean {
        val node = config?.path("adjust")?.path(label) ?: return false
        return node.path("disabled").asBoolean(false) ||
            node.path("status").asText("").equals("false", ignoreCase = true) ||
            node.path("enabled").asText("").equals("false", ignoreCase = true)
    }

    private fun applyStreamingParams(rawUrl: String, config: JsonNode?, label: String): String {
        val params = config?.path("adjust")?.path(label)?.path("params") ?: return rawUrl
        if (!params.isObject) return rawUrl
        var output = rawUrl
        params.fields().forEach { entry ->
            val key = entry.key.takeIf { it.isNotBlank() } ?: return@forEach
            val value = entry.value.asText("").takeIf { it.isNotBlank() } ?: return@forEach
            if (!Regex("""[?&]${Regex.escape(key)}=""", RegexOption.IGNORE_CASE).containsMatchIn(output)) {
                output += if (output.contains('?')) "&$key=$value" else "?$key=$value"
            }
        }
        return output
    }

    private fun isSf21MediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (isAdUrl(lower)) return false
        if (!NoDrakorIDUtils.looksDirectVideo(url) && !lower.contains("cf-master")) return false
        return NoDrakorIDUtils.hostOf(url).contains("sf21.vidplayer.live") ||
            lower.contains("/hls/") ||
            lower.contains("/v4/")
    }

    private suspend fun emitHls(url: String, displayName: String, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to USER_AGENT
        )
        if (NoDrakorIDUtils.isHls(url) || url.contains("cf-master", true)) {
            runCatching {
                M3u8Helper.generateM3u8(displayName, url, referer = "$mainUrl/", headers = headers).forEach(callback)
            }
        } else {
            runCatching {
                callback(
                    newExtractorLink(
                        source = name,
                        name = displayName,
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = getQualityFromName(url).takeIf { it != Qualities.Unknown.value } ?: Qualities.Unknown.value
                        this.headers = headers
                    }
                )
            }
        }
    }

    private fun decryptSf21(hex: String): String? {
        val clean = hex.trim().removePrefix("0x").filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (clean.length < 2 || clean.length % 2 != 0) return null
        return runCatching {
            val bytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec("kiemtienmua911ca".toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec("1234567890oiuytr".toByteArray(Charsets.UTF_8))
            )
            String(cipher.doFinal(bytes), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun isAdUrl(url: String): Boolean {
        return listOf(
            "googletagmanager", "doubleclick", "google-analytics", "yandex", "histats", "jcphi",
            "dtscout", "dtscdn", "marketdeathly", "wriestunvote", "brisknessdebtordismiss",
            "polosanitizertrusting", "festivitynextrocker", "cardboardcrispyrover", "/ad?", "ads"
        ).any { url.contains(it) }
    }
}

class NoDrakorIDMinochinos : ExtractorApi() {
    override var name = "Minochinos"
    override var mainUrl = "https://minochinos.com"
    override var requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = normalizeEmbedUrl(NoDrakorIDUtils.decodeKnownRedirect(url)) ?: return
        val html = runCatching {
            app.get(
                pageUrl,
                referer = referer ?: NoDrakorIDSepeda.MAIN_URL,
                headers = NoDrakorIDUtils.browserHeaders,
                timeout = 20L
            ).text
        }.getOrNull().orEmpty()
        if (html.isBlank()) return

        val normalized = NoDrakorIDUtils.decodeHtml(NoDrakorIDUtils.decodeUrlRepeated(html)).replace("\\/", "/")
        val unpacked = runCatching {
            if (!getPacked(normalized).isNullOrEmpty()) getAndUnpack(normalized) else ""
        }.getOrDefault("").replace("\\/", "/")
        val scan = "$normalized\n$unpacked"
        val links = parseLinksObject(scan)
        val emitted = linkedSetOf<String>()

        jwPlayerSources(scan, links).forEach { rawUrl ->
            val videoUrl = absoluteToOrigin(mainUrl, rawUrl) ?: return@forEach
            if (!isMinochinosHls(videoUrl) || !emitted.add(normalizeMediaKey(videoUrl))) return@forEach
            emitMinochinosHls(videoUrl, pageUrl, callback)
        }
    }

    private fun normalizeEmbedUrl(url: String): String? {
        if (url.contains("/embed/", true)) return url.substringBefore('?').substringBefore('#')
        Regex("""[?&]file_code=([^&#]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)?.let { code ->
            return "$mainUrl/embed/$code"
        }
        val slug = url.trimEnd('/').substringAfterLast('/').substringBefore('?').substringBefore('#')
        return slug.takeIf { it.length > 4 && !it.contains('.') }?.let { "$mainUrl/embed/$it" }
    }

    private fun parseLinksObject(text: String): Map<String, String> {
        val body = Regex("""(?s)\b(?:var|let|const)\s+links\s*=\s*\{(.*?)\}\s*;""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
            .orEmpty()
        if (body.isBlank()) return emptyMap()
        return Regex("""["']?([A-Za-z0-9_\-]+)["']?\s*:\s*["']([^"']+)["']""")
            .findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /**
     * Minochinos is Packed JS + JW Player. The valid video is the JW Player sources[].file
     * expression, normally links.hls4 (/stream/.../master.m3u8). Ads, direct premium layers,
     * slides/thumbnails, and random script URLs are ignored.
     */
    private fun jwPlayerSources(text: String, links: Map<String, String>): List<String> {
        val output = linkedSetOf<String>()
        Regex("""(?s)sources\s*:\s*\[\s*\{[^}]*?file\s*:\s*([^,}\]]+)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { match ->
                resolveJwFileExpression(match.groupValues[1], links).forEach { output += it }
            }
        Regex("""(?s)jwplayer\([^)]*\)\.setup\s*\(.*?file\s*:\s*["']([^"']*master\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { output += it.groupValues[1] }

        if (output.isEmpty()) {
            listOf("hls4", "hls2").mapNotNull { links[it] }.forEach { output += it }
        }
        return output.toList()
    }

    private fun resolveJwFileExpression(expression: String, links: Map<String, String>): List<String> {
        val output = linkedSetOf<String>()
        expression.split("||").map { it.trim().trim(';') }.forEach { part ->
            when {
                part.startsWith("links.") -> {
                    val key = part.removePrefix("links.").takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
                    links[key]?.takeIf { it.isNotBlank() }?.let { output += it }
                }
                part.startsWith("\"") || part.startsWith("'") -> {
                    val value = part.trim(' ', '\'', '"')
                    if (value.isNotBlank()) output += value
                }
            }
            if (output.isNotEmpty()) return output.toList()
        }
        return output.toList()
    }

    private fun isMinochinosHls(url: String): Boolean {
        val lower = url.lowercase()
        if (isAdUrl(lower)) return false
        if (!lower.contains("master.m3u8")) return false
        val host = NoDrakorIDUtils.hostOf(url)
        return host.contains("minochinos.com") || lower.contains("/stream/")
    }

    private suspend fun emitMinochinosHls(url: String, pageUrl: String, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "Referer" to pageUrl,
            "User-Agent" to USER_AGENT
        )
        runCatching {
            M3u8Helper.generateM3u8("$name HLS", url, referer = pageUrl, headers = headers).forEach(callback)
        }
    }

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            "/ad?", "yandex", "histats", "doubleclick", "googletagmanager", "dtscout", "dtscdn", "jcphi",
            "marketdeathly", "wriestunvote", "brisknessdebtordismiss", "polosanitizertrusting", "festivitynextrocker",
            "cardboardcrispyrover", "shopee", "earnvids", "slides", "thumbnail", "pixibay"
        ).any { lower.contains(it) }
    }
}

private fun absoluteToOrigin(origin: String, raw: String?): String? {
    val value = NoDrakorIDUtils.cleanUrlText(raw ?: return null)
    if (value.isBlank() || value == "#" || value.startsWith("javascript:", true) || value.startsWith("mailto:", true)) return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://", true) || value.startsWith("https://", true) -> value
        value.startsWith("/") -> origin.trimEnd('/') + value
        else -> origin.trimEnd('/') + "/" + value.trimStart('/')
    }.trim()
}

private fun normalizeMediaKey(url: String): String = NoDrakorIDUtils.decodeKnownRedirect(url)
    .substringBefore('#')
    .trim()
