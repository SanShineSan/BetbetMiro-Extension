package com.sad25kag

import com.google.gson.JsonParser
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BysetayicoFileMoon : Filesim() {
    override val mainUrl = "https://bysetayico.com"
    override val name = "FileMoon"
}

class DrakorkitaStream : DramaIndoEncryptedStream(
    extractorName = "Drakorkita",
    extractorMainUrl = "https://drakorkita.stream",
)

class NunaUpnsStream : DramaIndoEncryptedStream(
    extractorName = "NunaUpns",
    extractorMainUrl = "https://nuna.upns.pro",
)

open class DramaIndoEncryptedStream(
    private val extractorName: String,
    private val extractorMainUrl: String,
) : ExtractorApi() {
    override val name = extractorName
    override val mainUrl = extractorMainUrl
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        DramaIndoStreamResolver.resolve(
            extractorName = name,
            url = url,
            referer = referer ?: "https://dramaindo.my/",
            callback = callback,
        )
    }
}

object DramaIndoStreamResolver {
    private const val AES_KEY = "kiemtienmua911ca"
    private const val AES_IV = "1234567890oiuytr"

    suspend fun resolve(
        extractorName: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        val videoId = uri.fragment
            ?.substringBefore("&")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return false

        val baseUrl = "${uri.scheme ?: "https"}://$host"
        val playerReferer = "$baseUrl/"
        val refererHost = runCatching { URI(referer).host?.removePrefix("www.") }
            .getOrNull()
            .takeUnless { it.isNullOrBlank() }
            ?: "dramaindo.my"

        val apiUrl = "$baseUrl/api/v1/video?id=$videoId&w=421&h=935&r=$refererHost"
        val encrypted = runCatching {
            app.get(
                apiUrl,
                referer = playerReferer,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to baseUrl,
                    "Referer" to playerReferer,
                ),
            ).text
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return false

        val decrypted = decryptHex(encrypted) ?: return false
        val streamUrl = parseVideoSource(decrypted) ?: return false
        val headers = mapOf(
            "Referer" to playerReferer,
            "Origin" to baseUrl,
        )

        val generated = runCatching {
            generateM3u8(
                source = extractorName,
                streamUrl = streamUrl,
                referer = playerReferer,
                headers = headers,
            )
        }.getOrDefault(emptyList())

        if (generated.isNotEmpty()) {
            generated.forEach(callback)
            return true
        }

        callback(
            newExtractorLink(extractorName, "$extractorName HLS", streamUrl, ExtractorLinkType.M3U8) {
                this.referer = playerReferer
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
        return true
    }

    private fun parseVideoSource(json: String): String? {
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        return listOf("source", "cf")
            .mapNotNull { key ->
                runCatching { obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()
            }
            .firstOrNull { source ->
                source.contains(".m3u8", ignoreCase = true) ||
                    source.contains("master", ignoreCase = true) ||
                    source.contains("cf-master", ignoreCase = true)
            }
    }

    private fun decryptHex(hex: String): String? {
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            String(cipher.doFinal(hexToBytes(hex)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.trim()
        require(cleanHex.length % 2 == 0)
        return ByteArray(cleanHex.length / 2) { index ->
            cleanHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
