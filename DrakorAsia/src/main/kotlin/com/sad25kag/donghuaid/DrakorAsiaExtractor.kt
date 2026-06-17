package com.sad25kag.drakorasia

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Base64
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object DrakorAsiaExtractor {
    const val ANDROID_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

    private val abyssHeaders = mapOf(
        "User-Agent" to ANDROID_UA,
        "Accept" to "*/*",
        "Origin" to "https://abyssplayer.com",
        "Referer" to "https://abyssplayer.com/"
    )

    suspend fun resolve(
        pageUrl: String,
        mainUrl: String,
        pageHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(pageUrl, referer = mainUrl, headers = pageHeaders).document
        val seenLinks = linkedSetOf<String>()
        var emitted = false

        val countingCallback: (ExtractorLink) -> Unit = { link ->
            if (seenLinks.add(link.url)) {
                emitted = true
                callback(link)
            }
        }

        val servers = collectServers(document, pageUrl)
        for (server in servers) {
            when {
                server.url.contains("abyssplayer.com", true) -> {
                    DrakorAsiaAbyssPlayer().getUrl(server.url, pageUrl, subtitleCallback, countingCallback)
                }
                isDirectMedia(server.url) -> {
                    emitDirect(
                        source = "DrakorAsia",
                        name = server.name.ifBlank { "DrakorAsia Direct" },
                        url = server.url,
                        referer = server.referer,
                        headers = pageHeaders,
                        callback = countingCallback
                    )
                }
                server.url.contains("/video/down.php", true) || server.url.contains("katong.usbx.me", true) -> {
                    emitDirect(
                        source = "DrakorAsia",
                        name = "DrakorAsia Download Server",
                        url = server.url,
                        referer = pageUrl,
                        headers = pageHeaders,
                        callback = countingCallback,
                        forceVideo = true
                    )
                }
                else -> {
                    runCatching {
                        loadExtractor(server.url, server.referer, subtitleCallback, countingCallback)
                    }
                }
            }
        }

        return emitted
    }

    private fun collectServers(document: Document, pageUrl: String): List<ServerCandidate> {
        val html = document.outerHtml()
        val servers = mutableListOf<ServerCandidate>()

        document.select("select.selectServ option[value], #selectServ option[value]").forEach { option ->
            val value = option.attr("value").trim()
            if (value.isNotBlank()) {
                servers.add(
                    ServerCandidate(
                        name = option.text().trim().ifBlank { "Video Server" },
                        url = normalizeUrl(value, pageUrl),
                        referer = pageUrl
                    )
                )
            }
        }

        document.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]").forEach { iframe ->
            iframe.frameUrl()?.let { frame ->
                servers.add(ServerCandidate("Iframe", normalizeUrl(frame, pageUrl), pageUrl))
            }
        }

        document.select(".mobius option[value], .mirror option[value], select option[value]").forEach { option ->
            val value = option.attr("value").trim()
            if (value.isBlank()) return@forEach
            if (value.startsWith("http", true) || value.startsWith("//")) {
                servers.add(ServerCandidate(option.text().trim(), normalizeUrl(value, pageUrl), pageUrl))
            } else {
                decodeOption(value)?.let { decoded ->
                    val decodedDocument = Jsoup.parse(decoded, pageUrl)
                    decodedDocument.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]").forEach { iframe ->
                        iframe.frameUrl()?.let { frame ->
                            servers.add(ServerCandidate(option.text().trim(), normalizeUrl(frame, pageUrl), pageUrl))
                        }
                    }
                    urlRegex.findAll(decoded.cleanEscaped()).forEach { match ->
                        servers.add(ServerCandidate(option.text().trim(), normalizeUrl(match.value, pageUrl), pageUrl))
                    }
                }
            }
        }

        document.select("a.download[href], a[href*='/video/down.php'], a[href*='katong.usbx.me'], a[href*='.m3u8'], a[href*='.mp4']").forEach { link ->
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isNotBlank()) {
                servers.add(ServerCandidate(link.text().trim(), normalizeUrl(href, pageUrl), pageUrl))
            }
        }

        directMediaRegex.findAll(html.cleanEscaped()).forEach { match ->
            servers.add(ServerCandidate("Direct", normalizeUrl(match.value, pageUrl), pageUrl))
        }

        return servers
            .filter { it.url.isNotBlank() && !it.url.startsWith("javascript:", true) }
            .distinctBy { it.url.substringBefore('#') }
    }

    internal suspend fun emitDirect(
        source: String,
        name: String,
        url: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        forceVideo: Boolean = false
    ) {
        val cleanUrl = url.cleanEscaped()
        if (cleanUrl.isBlank()) return

        if (!forceVideo && cleanUrl.contains(".m3u8", true)) {
            val generated = runCatching {
                generateM3u8(
                    source = source,
                    streamUrl = cleanUrl,
                    referer = headers["Referer"] ?: referer,
                    headers = headers
                )
            }.getOrDefault(emptyList())

            if (generated.isNotEmpty()) {
                generated.forEach(callback)
                return
            }
        }

        callback.invoke(
            newExtractorLink(
                source = source,
                name = name,
                url = cleanUrl,
                type = if (!forceVideo && cleanUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = headers["Referer"] ?: referer
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
    }

    internal fun abyssVideoHeaders(): Map<String, String> = abyssHeaders

    internal fun String.cleanEscaped(): String {
        return replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003c", "<")
            .replace("\\u003e", ">")
    }

    private fun Element.frameUrl(): String? {
        return attr("abs:data-litespeed-src").ifBlank { attr("abs:data-src") }
            .ifBlank { attr("abs:src") }
            .ifBlank { attr("data-litespeed-src") }
            .ifBlank { attr("data-src") }
            .ifBlank { attr("src") }
            .takeIf { it.isNotBlank() }
    }

    private fun decodeOption(value: String): String? {
        val cleaned = value.replace(Regex("\\s+"), "")
        return runCatching {
            String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun normalizeUrl(url: String, referer: String): String {
        val cleaned = url.trim().cleanEscaped()
        return when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> referer.substringBefore("/20").trimEnd('/') + cleaned
            else -> cleaned
        }
    }

    private fun isDirectMedia(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains("sssrr.org", true) ||
            url.contains("videoplayback", true) ||
            url.contains("googlevideo", true)
    }

    private val urlRegex = Regex("https?:\\/\\/[^\\s\"'<>]+|//[^\\s\"'<>]+")
    private val directMediaRegex = Regex("https?:\\/\\/[^\\s\"'<>]+(?:\\.m3u8|\\.mp4|/video/down\\.php|sssrr\\.org)[^\\s\"'<>]*", RegexOption.IGNORE_CASE)

    private data class ServerCandidate(
        val name: String,
        val url: String,
        val referer: String
    )
}

class DrakorAsiaAbyssPlayer : ExtractorApi() {
    override var name = "AbyssPlayer"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageHeaders = mapOf(
            "User-Agent" to DrakorAsiaExtractor.ANDROID_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Referer" to (referer ?: "https://www.drakorasia.site/")
        )

        val document = runCatching {
            app.get(url, headers = pageHeaders, referer = referer ?: "https://www.drakorasia.site/").document
        }.getOrNull() ?: return

        val scriptData = document.select("script").joinToString("\n") { it.data() }
        val encrypted = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""")
            .find(scriptData)
            ?.groupValues
            ?.getOrNull(1)
            ?: return

        val response = runCatching {
            app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = DrakorAsiaExtractor.abyssVideoHeaders(),
                requestBody = """{"text":"$encrypted"}""".toRequestBody("application/json".toMediaType())
            ).text
        }.getOrNull() ?: return

        val root = runCatching { JSONObject(response) }.getOrNull() ?: return
        val result = root.optJSONObject("result") ?: root
        val sources = result.optJSONArray("sources") ?: return
        val videoHeaders = DrakorAsiaExtractor.abyssVideoHeaders()

        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            if (!source.optBoolean("status", true)) continue

            val sourceUrl = source.optString("url").trim().cleanEscaped()
            if (sourceUrl.isBlank()) continue

            DrakorAsiaExtractor.emitDirect(
                source = name,
                name = source.optString("quality").ifBlank { name },
                url = sourceUrl,
                referer = "$mainUrl/",
                headers = videoHeaders,
                callback = callback,
                forceVideo = !sourceUrl.contains(".m3u8", true)
            )
        }
    }

    private fun String.cleanEscaped(): String {
        return replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003c", "<")
            .replace("\\u003e", ">")
    }
}
