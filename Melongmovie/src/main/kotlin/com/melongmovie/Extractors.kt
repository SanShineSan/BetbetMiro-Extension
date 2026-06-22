package com.melongmovie

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import java.net.URI

class Melongfilmstrp2p : VidStack() {
    override var name = "Melongfilmstrp2p"
    override var mainUrl = "https://melongfilm.strp2p.site"
    override var requiresReferer = true
}

class MelongfilmUpnShare : VidStack() {
    override var name = "MelongfilmUpnShare"
    override var mainUrl = "https://melongfilm.upns.blog"
    override var requiresReferer = true
}

class Melongfilm4MePlayer : VidStack() {
    override var name = "Melongfilm4MePlayer"
    override var mainUrl = "https://melongfilm.4meplayer.com"
    override var requiresReferer = true
}

class Ukokoko : VidStack() {
    override var name = "Ukokoko"
    override var mainUrl = "https://ukokoko.4meplayer.com"
    override var requiresReferer = true
}

class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

open class Dingtezuni : EarnvidsLike() {
    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
}

open class Dintezuvio : EarnvidsLike() {
    override val name = "Earnvids"
    override val mainUrl = "https://dintezuvio.com"
}

open class Minochinos : EarnvidsLike() {
    override val name = "Minochinos"
    override val mainUrl = "https://minochinos.com"
}

open class EarnvidsLike : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = toEmbedUrl(url)
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to mainUrl,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
        )

        val response = runCatching {
            app.get(
                embedUrl,
                referer = referer ?: "$mainUrl/",
                headers = headers,
                timeout = 20L
            )
        }.getOrNull() ?: return

        val candidates = linkedSetOf<String>()
        val body = response.text.cleanEscaped()

        extractM3u8(body).forEach { candidates.add(normalizeExtractorUrl(it, embedUrl)) }

        val unpacked = runCatching {
            if (!getPacked(body).isNullOrEmpty()) getAndUnpack(body) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractM3u8(unpacked.cleanEscaped()).forEach {
                candidates.add(normalizeExtractorUrl(it, embedUrl))
            }
        }

        response.document.selectFirst("script:containsData(sources:)")
            ?.data()
            ?.cleanEscaped()
            ?.let { script ->
                extractM3u8(script).forEach {
                    candidates.add(normalizeExtractorUrl(it, embedUrl))
                }
            }

        candidates
            .filter { it.contains(".m3u8", true) }
            .forEach { stream ->
                generateM3u8(
                    source = name,
                    streamUrl = stream,
                    referer = "$mainUrl/",
                    headers = headers
                ).forEach(callback)
            }
    }

    private fun toEmbedUrl(url: String): String {
        return when {
            url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("/download/") -> url.replace("/download/", "/v/")
            url.contains("/file/") -> url.replace("/file/", "/v/")
            url.contains("/f/") -> url.replace("/f/", "/v/")
            else -> url
        }
    }

    private fun extractM3u8(text: String): List<String> {
        val urls = linkedSetOf<String>()

        Regex("""https?://[^"'\\\s<>]+?\.m3u8(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value.cleanEscaped() }
            .forEach { urls.add(it) }

        Regex(""":\s*["']([^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .forEach { urls.add(it) }

        Regex("""["']([^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .forEach { urls.add(it) }

        return urls.toList()
    }
}

private fun normalizeExtractorUrl(url: String, baseUrl: String): String {
    val clean = url.cleanEscaped()

    return when {
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> {
            val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: ""
            "$origin$clean"
        }
        else -> runCatching {
            URI(baseUrl).resolve(clean).toString()
        }.getOrDefault(clean)
    }
}

private fun String.cleanEscaped(): String {
    return this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&#38;", "&")
        .replace("\\u003d", "=")
        .trim()
}
