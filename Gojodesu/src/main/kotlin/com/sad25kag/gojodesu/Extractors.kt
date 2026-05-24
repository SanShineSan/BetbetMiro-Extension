package com.sad25kag.gojodesu

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLDecoder

class Kotakajaib : ExtractorApi() {
    override val name = "KotakAjaib"
    override val mainUrl = "https://kotakajaib.me"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/file/", "/embed/")
        val document = app.get(
            embedUrl,
            referer = referer ?: "https://gojodesu.com/",
            headers = mapOf("User-Agent" to USER_AGENT),
        ).document

        var delivered = false
        val trackedCallback: (ExtractorLink) -> Unit = { link ->
            delivered = true
            callback(link)
        }

        document.select("button.server-item[data-frame], [data-frame]").forEach { button ->
            val decoded = button.attr("data-frame").decodeBase64Url() ?: return@forEach
            val iframeUrl = httpsify(URLDecoder.decode(decoded, "UTF-8"))
            val ok = runCatching {
                loadExtractor(iframeUrl, embedUrl, subtitleCallback, trackedCallback)
            }.getOrDefault(false)
            if (ok) delivered = true
        }

        if (!delivered) {
            document.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]").forEach { iframe ->
                val raw = iframe.attr("data-litespeed-src")
                    .ifBlank { iframe.attr("data-src") }
                    .ifBlank { iframe.attr("src") }
                    .trim()
                if (raw.isBlank()) return@forEach
                val fixed = when {
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("http", true) -> raw
                    raw.startsWith("/") -> "$mainUrl$raw"
                    else -> "$mainUrl/$raw"
                }
                runCatching { loadExtractor(fixed, embedUrl, subtitleCallback, trackedCallback) }
            }
        }
    }

    private fun String.decodeBase64Url(): String? {
        val cleaned = trim().replace('-', '+').replace('_', '/')
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT))
        }.getOrNull()
    }
}
