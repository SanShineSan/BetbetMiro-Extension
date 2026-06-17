package com.sad25kag.drakorasia

import android.util.Base64
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

open class DrakorAsiaAbyssExtractor : ExtractorApi() {
    override var name = "DrakorAsia AbyssPlayer"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = url.toAbyssPlayerUrl() ?: return
        resolveAbyssPlayer(pageUrl, callback)
    }

    protected suspend fun resolveAbyssPlayer(pageUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = runCatching {
            app.get(
                url = pageUrl.replace("abyss.to/", "abyssplayer.com/"),
                headers = abyssHeaders(),
                referer = ABYSS_SERVICE_REFERER
            ).text
        }.getOrNull().orEmpty()

        if (html.isBlank()) return false

        val encoded = extractAbyssDatas(html)
        if (!encoded.isNullOrBlank() && resolveAbyssWithDecoder(encoded, callback)) return true

        return resolveAbyssLocalSources(html, callback)
    }

    private suspend fun resolveAbyssWithDecoder(encoded: String, callback: (ExtractorLink) -> Unit): Boolean {
        val response = runCatching {
            app.post(
                url = ABYSS_DECODER_API,
                headers = abyssHeaders(),
                requestBody = """{"text":"${encoded.escapeJson()}"}"""
                    .toRequestBody("application/json".toMediaType())
            ).text
        }.getOrNull().orEmpty()

        if (response.isBlank()) return false

        val root = runCatching { JsonParser().parse(response).asJsonObject }.getOrNull() ?: return false
        val result = root.obj("result") ?: root
        val sources = result.array("sources") ?: result.obj("mp4")?.array("sources") ?: return false

        var emitted = false
        sources.mapNotNull { it.asObjectOrNull() }.forEach { source ->
            if (!source.bool("status", true)) return@forEach
            val videoUrl = source.toAbyssSourceUrl() ?: return@forEach
            if (!videoUrl.isAbyssPlayableCandidate()) return@forEach

            val label = source.string("label")
                ?: source.string("quality")
                ?: source.string("name")
                ?: source.int("res_id")?.toAbyssQualityLabel()
                ?: "Auto"

            if (emitAbyssLink(videoUrl, label, callback)) emitted = true
        }

        return emitted
    }

    private suspend fun resolveAbyssLocalSources(html: String, callback: (ExtractorLink) -> Unit): Boolean {
        val payload = parseAbyssPayload(html) ?: return false
        val mediaJson = decryptAbyssMedia(payload) ?: return false
        val root = runCatching { JsonParser().parse(mediaJson).asJsonObject }.getOrNull() ?: return false
        val sources = root.obj("mp4")?.array("sources") ?: return false

        var emitted = false
        sources.mapNotNull { it.asObjectOrNull() }.forEach { source ->
            if (!source.bool("status", true)) return@forEach
            val videoUrl = source.toAbyssSourceUrl() ?: return@forEach
            if (!videoUrl.isAbyssPlayableCandidate()) return@forEach

            val label = source.string("label")
                ?: source.string("quality")
                ?: source.string("name")
                ?: source.int("res_id")?.toAbyssQualityLabel()
                ?: "Auto"

            if (emitAbyssLink(videoUrl, label, callback)) emitted = true
        }

        return emitted
    }

    private suspend fun emitAbyssLink(
        videoUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = videoUrl.replace("\\/", "/").let { if (it.startsWith("//")) "https:$it" else it }
        if (!fixed.isAbyssPlayableCandidate()) return false

        val type = when {
            fixed.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            fixed.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        val quality = getQualityFromName(label).let {
            if (it == Qualities.Unknown.value) inferQuality(fixed) else it
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name ${label.cleanTitle().ifBlank { "Auto" }}",
                url = fixed,
                type = type
            ) {
                this.quality = quality
                this.referer = ABYSS_REFERER
                this.headers = mapOf(
                    "Referer" to ABYSS_REFERER,
                    "Origin" to ABYSS_ORIGIN,
                    "User-Agent" to USER_AGENT
                )
            }
        )
        return true
    }

    protected fun String.toAbyssPlayerUrl(): String? {
        val clean = replace("\\/", "/").trim().trim('"', '\'', ',', ';')
        if (clean.isAbyssPlayerPage()) return clean.replace("abyss.to/", "abyssplayer.com/")
        if (!clean.contains("short.ink", true)) return null

        val id = clean.substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }
            ?: return null

        return "https://abyssplayer.com/$id"
    }

    private fun String.isAbyssPlayerPage(): Boolean {
        val value = lowercase()
        return value.contains("abyssplayer.com/") || value.contains("abyss.to/")
    }

    private fun extractAbyssDatas(html: String): String? {
        return Regex("""(?:const|let|var)?\s*datas\s*=\s*["']([^"']+)["']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseAbyssPayload(html: String): DrakorAsiaAbyssPayload? {
        val encoded = extractAbyssDatas(html) ?: return null
        val json = runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.ISO_8859_1)
        }.getOrNull() ?: return null

        val node = runCatching { JsonParser().parse(json).asJsonObject }.getOrNull() ?: return null
        val slug = node.string("slug").orEmpty()
        val md5Id = node.string("md5_id").orEmpty()
        val userId = node.string("user_id").orEmpty()
        val media = node.string("media").orEmpty()

        if (slug.isBlank() || md5Id.isBlank() || userId.isBlank() || media.isBlank()) return null
        return DrakorAsiaAbyssPayload(slug, md5Id, userId, media)
    }

    private fun decryptAbyssMedia(payload: DrakorAsiaAbyssPayload): String? {
        return runCatching {
            val key = md5Hex("${payload.userId}:${payload.slug}:${payload.md5Id}")
                .toByteArray(Charsets.UTF_8)
            val counter = key.copyOfRange(0, 16)
            val encrypted = ByteArray(payload.media.length) { index ->
                payload.media[index].code.toByte()
            }

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun JsonObject.toAbyssSourceUrl(): String? {
        val base = listOf(
            string("file"),
            string("src"),
            string("link"),
            string("url")
        ).firstOrNull { !it.isNullOrBlank() }
            ?.replace("\\/", "/")
            ?.let { if (it.startsWith("//")) "https:$it" else it }
            ?.trim()
            ?: return null

        val path = string("path")?.replace("\\/", "/")?.trim('/')
        val combined = if (!path.isNullOrBlank() && (base.startsWith("http", true) || base.startsWith("//"))) {
            base.trimEnd('/') + "/" + path
        } else {
            base
        }

        return combined.takeIf { it.startsWith("http", true) || it.startsWith("//") }
    }

    private fun String.isAbyssPlayableCandidate(): Boolean {
        return isSupportedContainerCandidate() || isAbyssStreamPathCandidate()
    }

    private fun String.isSupportedContainerCandidate(): Boolean {
        val value = lowercase().substringBefore("#").substringBefore("?")
        return value.contains(".m3u8") ||
            value.contains(".mp4") ||
            value.contains(".webm") ||
            value.contains(".mkv") ||
            value.contains(".mpd") ||
            value.contains("videoplayback")
    }

    private fun String.isAbyssStreamPathCandidate(): Boolean {
        val value = replace("\\/", "/").lowercase().substringBefore("#").substringBefore("?")
        if (!value.contains(".sssrr.org/")) return false
        if (value.contains(".fd") || value.contains("/sora/")) return false

        val path = runCatching { URI(value).path.orEmpty() }.getOrDefault("")
        if (path.isBlank() || path == "/") return false

        return Regex("""/[0-9a-f]/[0-9a-f]/[0-9a-f]/[0-9a-f]{16,}\.\d{6,}\.\d+(?:$|/)""")
            .containsMatchIn(path)
    }

    private fun Int.toAbyssQualityLabel(): String {
        return when (this) {
            5 -> "1080p"
            4 -> "720p"
            3 -> "480p"
            2 -> "360p"
            else -> "Auto"
        }
    }

    private fun inferQuality(url: String): Int {
        return when {
            url.contains("2160", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun abyssHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to ABYSS_SERVICE_ORIGIN,
            "Referer" to ABYSS_SERVICE_REFERER,
            "Accept" to "application/json,text/plain,*/*"
        )
    }

    private fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun String.cleanTitle(): String {
        return replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? {
        return takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun JsonObject.obj(key: String): JsonObject? {
        return get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun JsonObject.array(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.string(key: String): String? {
        return get(key)?.takeIf { !it.isJsonNull }?.asString
    }

    private fun JsonObject.int(key: String): Int? {
        return runCatching { get(key)?.takeIf { !it.isJsonNull }?.asInt }.getOrNull()
    }

    private fun JsonObject.bool(key: String, default: Boolean): Boolean {
        return runCatching { get(key)?.takeIf { !it.isJsonNull }?.asBoolean }.getOrNull() ?: default
    }

    private data class DrakorAsiaAbyssPayload(
        val slug: String,
        val md5Id: String,
        val userId: String,
        val media: String
    )

    companion object {
        private const val ABYSS_REFERER = "https://abyssplayer.com/"
        private const val ABYSS_ORIGIN = "https://abyssplayer.com"
        private const val ABYSS_SERVICE_ORIGIN = "https://playhydrax.com"
        private const val ABYSS_SERVICE_REFERER = "https://playhydrax.com/"
        private const val ABYSS_DECODER_API = "https://enc-dec.app/api/dec-abyss"
    }
}

class DrakorAsiaShortInkExtractor : DrakorAsiaAbyssExtractor() {
    override var name = "DrakorAsia ShortInk"
    override var mainUrl = "https://short.ink"
}
