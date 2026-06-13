package com.nodrakorid

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NoDrakorID playback resolver.
 *
 * Evidence target: sf21.vidplayer.live HAR from Iron Maiden Burning Ambition 2026.
 * This loadLinks resolver is intentionally narrow: it resolves only sf21 iframe/API/HLS
 * and does not chase unrelated hosts, shorteners, generic anchors, or legacy fallbacks.
 */
internal object NoDrakorIDExtractor {
    private const val EXTRACT_TIMEOUT_MS = 45_000L
    private const val REQUEST_TIMEOUT_MS = 20_000L
    private const val SF21_ORIGIN = "https://sf21.vidplayer.live"
    private const val SF21_REFERER = "$SF21_ORIGIN/"

    private val sf21UrlRegex = Regex(
        """https?:\\?/\\?/sf21\.vidplayer\.live[^\"'<>\s)]+""",
        RegexOption.IGNORE_CASE
    )

    suspend fun extract(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
            extractInternal(data.trim(), subtitleCallback, callback)
        } ?: false
    }

    private suspend fun extractInternal(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        val emitOnce = linkedSetOf<String>()

        val emit: suspend (ExtractorLink) -> Unit = { link ->
            val key = link.url.substringBefore('#')
            if (emitOnce.add(key)) {
                emitted = true
                callback(link)
            }
        }

        val startUrl = NoDrakorIDUtils.decodeKnownRedirect(data)
        if (startUrl.isBlank()) return false

        val sf21Candidates = linkedSetOf<String>()
        if (isSf21Url(startUrl)) {
            sf21Candidates += normalizeSf21Url(startUrl)
        } else if (NoDrakorIDUtils.isNoDrakorUrl(startUrl)) {
            val detailUrl = startUrl.substringBefore('#')
            val document = safeGetDocument(detailUrl, NoDrakorIDSepeda.MAIN_URL) ?: return false
            collectSf21Candidates(detailUrl, document).forEach { sf21Candidates += it }
        } else {
            return false
        }

        for (sf21Url in sf21Candidates) {
            if (resolveSf21(sf21Url, startUrl, subtitleCallback, emit)) {
                emitted = true
            }
        }
        return emitted
    }

    private fun collectSf21Candidates(pageUrl: String, doc: Document): List<String> {
        val output = linkedSetOf<String>()

        doc.select(
            "iframe[src], iframe[data-src], iframe[data-litespeed-src], iframe[srcdoc], " +
                "[data-src], [data-litespeed-src], [data-iframe], [data-embed], [data-player], [data-url], [data-video], [srcdoc]"
        ).forEach { element ->
            for (attr in listOf(
                "src", "data-src", "data-litespeed-src", "srcdoc", "data-iframe", "data-embed",
                "data-player", "data-url", "data-video"
            )) {
                val value = element.attr(attr).takeIf { it.isNotBlank() } ?: continue
                collectSf21FromText(pageUrl, value).forEach { output += it }
            }
        }

        val scripts = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        collectSf21FromText(pageUrl, scripts).forEach { output += it }
        collectSf21FromText(pageUrl, doc.outerHtml()).forEach { output += it }

        return output.toList()
    }

    private fun collectSf21FromText(baseUrl: String, text: String): List<String> {
        val normalized = NoDrakorIDUtils.decodeHtml(NoDrakorIDUtils.decodeUrlRepeated(text))
            .replace("\\/", "/")
            .replace("&amp;", "&")
        val output = linkedSetOf<String>()

        sf21UrlRegex.findAll(normalized).forEach { match ->
            NoDrakorIDUtils.absoluteUrl(baseUrl, match.value)?.let { output += normalizeSf21Url(it) }
        }

        if (normalized.contains("sf21.vidplayer.live", ignoreCase = true) && normalized.contains("<iframe", ignoreCase = true)) {
            val parsed = runCatching { Jsoup.parse(normalized, baseUrl) }.getOrNull()
            parsed?.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]")?.forEach { iframe ->
                val raw = iframe.attr("src")
                    .ifBlank { iframe.attr("data-src") }
                    .ifBlank { iframe.attr("data-litespeed-src") }
                NoDrakorIDUtils.absoluteUrl(baseUrl, raw)?.takeIf(::isSf21Url)?.let { output += normalizeSf21Url(it) }
            }
        }

        return output.toList()
    }

    private suspend fun resolveSf21(
        sf21Url: String,
        sourceReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: suspend (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = normalizeSf21Url(sf21Url)
        val videoId = extractSf21Id(pageUrl) ?: return false
        val sourceHost = runCatching { URI(sourceReferer).host.orEmpty().removePrefix("www.") }
            .getOrNull()
            .orEmpty()
            .takeIf { it.equals("richemmerson.com", ignoreCase = true) }
            ?: "richemmerson.com"

        val apiUrl = "$SF21_ORIGIN/api/v1/video?id=$videoId&w=421&h=935&r=$sourceHost"
        val encrypted = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                app.get(
                    apiUrl,
                    referer = SF21_REFERER,
                    timeout = REQUEST_TIMEOUT_MS,
                    headers = sf21ApiHeaders()
                ).text
            }.getOrNull()
        }?.trim().orEmpty()
        if (encrypted.isBlank()) return false

        val decrypted = decryptSf21Payload(encrypted) ?: return false
        val root = runCatching { mapper.readTree(decrypted) }.getOrNull() ?: return false
        emitSf21Subtitles(root, subtitleCallback)

        val hls = sf21HlsFromHarFlow(root) ?: return false
        return emitSf21Hls(hls, callback)
    }

    private fun sf21HlsFromHarFlow(root: JsonNode): String? {
        val rawPath = root.path("hlsVideoTiktok").asText("").takeIf { it.isNotBlank() } ?: return null
        val absolute = when {
            rawPath.startsWith("http://", true) || rawPath.startsWith("https://", true) -> rawPath
            rawPath.startsWith("//") -> "https:$rawPath"
            rawPath.startsWith("/") -> "$SF21_ORIGIN$rawPath"
            else -> "$SF21_ORIGIN/${rawPath.trimStart('/')}"
        }.replace("\\/", "/")

        val version = sf21VersionParam(root)
        return if (version.isNotBlank() && !Regex("""[?&]v=""").containsMatchIn(absolute)) {
            absolute + if (absolute.contains('?')) "&v=$version" else "?v=$version"
        } else {
            absolute
        }
    }

    private fun sf21VersionParam(root: JsonNode): String {
        val configNode = root.path("streamingConfig")
        val config = when {
            configNode.isObject -> configNode
            configNode.asText("").isNotBlank() -> runCatching { mapper.readTree(configNode.asText()) }.getOrNull()
            else -> null
        } ?: return ""
        return config.path("adjust")
            .path("Tiktok")
            .path("params")
            .path("v")
            .asText("")
    }

    private suspend fun emitSf21Hls(
        hlsUrl: String,
        callback: suspend (ExtractorLink) -> Unit
    ): Boolean {
        val headers = sf21HlsHeaders()
        var emitted = false

        val variants = runCatching {
            M3u8Helper.generateM3u8(
                source = "Sf21 HLS",
                streamUrl = hlsUrl,
                referer = SF21_REFERER,
                headers = headers
            )
        }.getOrDefault(emptyList())
        for (link in variants) {
            emitted = true
            callback(link)
        }

        if (!emitted) {
            callback(
                newExtractorLink(
                    source = "Sf21",
                    name = "Sf21 HLS",
                    url = hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = SF21_REFERER
                    this.quality = getQualityFromName(hlsUrl).takeIf { it != Qualities.Unknown.value } ?: Qualities.Unknown.value
                    this.headers = headers
                }
            )
            emitted = true
        }

        return emitted
    }

    private fun extractSf21Id(url: String): String? {
        val hash = url.substringAfter('#', "")
            .substringBefore('&')
            .substringBefore('?')
            .trim()
        if (hash.matches(Regex("[A-Za-z0-9_-]{4,64}"))) return hash

        Regex("""[?&]id=([A-Za-z0-9_-]{4,64})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.let { return it }

        val slug = url.trimEnd('/')
            .substringAfterLast('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
        return slug.takeIf { it.matches(Regex("[A-Za-z0-9_-]{4,64}")) }
    }

    private fun isSf21Url(url: String): Boolean = runCatching {
        URI(url).host.orEmpty().contains("sf21.vidplayer.live", ignoreCase = true)
    }.getOrDefault(false)

    private fun normalizeSf21Url(raw: String): String = NoDrakorIDUtils.decodeHtml(NoDrakorIDUtils.decodeUrlRepeated(raw))
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .trim(' ', '\n', '\r', '\t', '\'', '"')

    private fun decryptSf21Payload(hex: String): String? {
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

    private fun emitSf21Subtitles(root: JsonNode, subtitleCallback: (SubtitleFile) -> Unit) {
        val subtitles = root.path("subtitles")
        if (!subtitles.isObject) return
        subtitles.fields().forEach { entry ->
            val raw = entry.value.asText("").takeIf { it.isNotBlank() } ?: return@forEach
            val fixed = when {
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> "$SF21_ORIGIN$raw"
                else -> "$SF21_ORIGIN/${raw.trimStart('/')}"
            }.replace("\\/", "/")
            subtitleCallback.invoke(SubtitleFile(entry.key, fixed))
        }
    }

    private suspend fun safeGetDocument(url: String, referer: String): Document? {
        return runCatching {
            app.get(
                url,
                referer = referer,
                timeout = REQUEST_TIMEOUT_MS,
                headers = NoDrakorIDUtils.browserHeaders
            ).document
        }.getOrNull()
    }

    private fun sf21ApiHeaders(): Map<String, String> = mapOf(
        "Accept" to "*/*",
        "Referer" to SF21_REFERER,
        "User-Agent" to USER_AGENT
    )

    private fun sf21HlsHeaders(): Map<String, String> = mapOf(
        "Accept" to "*/*",
        "Referer" to SF21_REFERER,
        "User-Agent" to USER_AGENT
    )
}
