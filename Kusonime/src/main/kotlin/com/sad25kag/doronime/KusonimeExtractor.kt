package com.kusonime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.kusonime.KusonimeUtils.cleanEscaped
import com.kusonime.KusonimeUtils.fixQuality
import com.kusonime.KusonimeUtils.isDownloadHost
import com.kusonime.KusonimeUtils.isSubtitleUrl
import com.kusonime.KusonimeUtils.isVideoUrl
import com.kusonime.KusonimeUtils.normalizeUrl
import com.kusonime.KusonimeUtils.originOf
import com.kusonime.KusonimeUtils.shouldSkipUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object KusonimeExtractor {
    suspend fun loadLinks(
        data: String,
        mainUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(data, referer = "$mainUrl/", headers = KusonimeUtils.headers, timeout = 30L)
        }.getOrNull() ?: return false

        val document = response.document
        val pageText = response.text
        var found = false

        collectSubtitles(pageText, data, mainUrl, subtitleCallback)
        collectIframeUrls(document, data, mainUrl, pageText).forEach { iframe ->
            if (resolveExtractorOrDirect(iframe, data, mainUrl, null, subtitleCallback, callback)) found = true
        }
        collectDownloadSources(document, data, mainUrl).forEach { source ->
            if (resolveExtractorOrDirect(source.url, data, mainUrl, source.qualityName, subtitleCallback, callback)) found = true
        }
        collectDirectUrls(pageText, data, mainUrl).forEach { direct ->
            if (emitDirect(direct, data, callback, "Kusonime")) found = true
        }

        return found
    }

    private suspend fun resolveExtractorOrDirect(
        url: String,
        referer: String,
        mainUrl: String,
        qualityName: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = url.cleanEscaped().trim()
        if (clean.isBlank() || shouldSkipUrl(clean)) return false
        if (emitDirect(clean, referer, callback, qualityName ?: "Kusonime")) return true
        if (clean.contains("pixeldrain.com", true) && resolvePixeldrain(clean, referer, qualityName, callback)) return true

        var found = false
        runCatching {
            loadExtractor(clean, referer, subtitleCallback) { link ->
                found = true
                callback.invoke(link)
            }
        }
        if (found) return true

        // Fallback: emit known download hosts directly.
        // Fires only when loadExtractor returns nothing — ensures all collected
        // download links (GDrive, krakenfiles, acefile, hxfile, terabox, megaup, etc.)
        // are visible to the user even without a CloudStream extractor.
        if (isDownloadHost(clean)) {
            val quality = (qualityName ?: "").fixQuality().takeIf { it > 0 }
                ?: clean.fixQuality().takeIf { it > 0 }
                ?: Qualities.Unknown.value
            val hostLabel = when {
                clean.contains("usercontent.google.com", true) || clean.contains("drive.google.com", true) -> "GDrive"
                clean.contains("mega.nz", true) || clean.contains("mega.co.nz", true) -> "Mega"
                clean.contains("mediafire.com", true) -> "Mediafire"
                clean.contains("terabox", true) -> "Terabox"
                clean.contains("krakenfiles.com", true) -> "Krakenfiles"
                clean.contains("acefile.co", true) -> "Acefile"
                clean.contains("hxfile.co", true) -> "Hxfile"
                clean.contains("megaup.net", true) -> "Megaup"
                clean.contains("gofile.io", true) -> "Gofile"
                clean.contains("qiwi.gg", true) -> "Qiwi"
                clean.contains("buzzheavier.com", true) -> "Buzzheavier"
                clean.contains("racaty", true) -> "Racaty"
                else -> "Kusonime"
            }
            val displayName = listOf(hostLabel, qualityName)
                .filterNotNull()
                .distinct()
                .joinToString(" ")
                .trim()
            callback.invoke(
                newExtractorLink(hostLabel, displayName, clean) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = mapOf("Referer" to referer, "Origin" to originOf(referer))
                }
            )
            return true
        }

        return false
    }

    private fun collectDownloadSources(document: Document, baseUrl: String, mainUrl: String): List<KusonimeSource> {
        val sources = linkedMapOf<String, KusonimeSource>()
        document.select("a[href]").forEach { anchor ->
            val href = normalizeUrl(anchor.attr("href"), baseUrl, mainUrl)
            if (href.isBlank() || shouldSkipUrl(href) || !isDownloadHost(href)) return@forEach
            val row = anchor.closestDownloadRow()
            val rowText = row?.text()?.cleanEscaped().orEmpty()
            val qualityName = Regex("""(?i)(2160P|1440P|1080P|720P|480P|360P|4K|FHD|HD)""")
                .find(rowText)?.value
                ?: Regex("""(?i)(2160P|1440P|1080P|720P|480P|360P|4K|FHD|HD)""").find(anchor.parent()?.text().orEmpty())?.value
            val label = listOfNotNull(qualityName, anchor.text().cleanEscaped().takeIf { it.isNotBlank() })
                .joinToString(" ")
                .ifBlank { "Kusonime" }
            sources[href] = KusonimeSource(href, label, qualityName)
        }
        return sources.values.toList()
    }

    private fun Element.closestDownloadRow(): Element? {
        return sequenceOf(this, parent(), parent()?.parent(), parent()?.parent()?.parent())
            .filterNotNull()
            .firstOrNull { element ->
                val text = element.text()
                Regex("""(?i)(2160P|1440P|1080P|720P|480P|360P|4K|FHD|HD)""").containsMatchIn(text) ||
                    text.contains("Google", true) ||
                    text.contains("Pixeldrain", true) ||
                    text.contains("Mega", true) ||
                    text.contains("Hxfile", true)
            }
    }

    private fun collectIframeUrls(document: Document, baseUrl: String, mainUrl: String, rawText: String): List<String> {
        val urls = linkedSetOf<String>()
        document.select("iframe[src], iframe[data-src], [data-embed], [data-iframe], [data-player], [data-video], [data-url], option[value]").forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-player") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("value") }
            normalizeUrl(raw, baseUrl, mainUrl).takeIf { it.isNotBlank() && !shouldSkipUrl(it) }?.let { urls.add(it) }
        }

        val clean = rawText.cleanEscaped()
        listOf(
            Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:src|file|url|link|embed|iframe)["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s<>]+/(?:embed|e|v|d)/[^"'\s<>]+""", RegexOption.IGNORE_CASE)
        ).forEach { regex ->
            regex.findAll(clean).forEach { match ->
                val raw = match.groupValues.getOrNull(1).takeIf { !it.isNullOrBlank() } ?: match.value
                normalizeUrl(raw, baseUrl, mainUrl).takeIf { it.isNotBlank() && !shouldSkipUrl(it) }?.let { urls.add(it) }
            }
        }
        return urls.toList()
    }

    private fun collectDirectUrls(text: String, baseUrl: String, mainUrl: String): List<String> {
        val direct = linkedSetOf<String>()
        val clean = text.cleanEscaped()
        Regex("""https?:\\?/\\?/[^\"'\\\s<>]+""", RegexOption.IGNORE_CASE).findAll(clean).forEach { match ->
            val url = match.value.replace("\\/", "/")
            normalizeUrl(url, baseUrl, mainUrl).takeIf { isVideoUrl(it) }?.let { direct.add(it) }
        }
        Regex("""[\"'](?:file|url|src|source|video|link|hls|m3u8|mp4)[\"']\s*[:=]\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .forEach { match ->
                val url = normalizeUrl(match.groupValues.getOrNull(1), baseUrl, mainUrl)
                if (isVideoUrl(url)) direct.add(url)
            }
        Regex("""https?://[^\"'\s<>]+\.(?:m3u8|mp4|mkv|webm)(?:\?[^\"'\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .forEach { match -> direct.add(match.value.cleanEscaped()) }
        return direct.toList()
    }

    private suspend fun collectSubtitles(
        text: String,
        baseUrl: String,
        mainUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        Regex("""https?://[^\"'\s<>]+\.(?:srt|vtt|ass)(?:\?[^\"'\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text.cleanEscaped())
            .map { normalizeUrl(it.value, baseUrl, mainUrl) }
            .filter { isSubtitleUrl(it) }
            .distinct()
            .forEach { subtitleCallback.invoke(newSubtitleFile("Indonesia", it)) }
    }

    private suspend fun resolvePixeldrain(
        url: String,
        referer: String,
        qualityName: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = url.substringBefore("?").trimEnd('/')
        val singleId = Regex("""pixeldrain\.com/(?:u|api/file)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.getOrNull(1)
        if (!singleId.isNullOrBlank()) {
            return emitDirect("https://pixeldrain.com/api/file/$singleId", referer, callback, qualityName ?: "Pixeldrain")
        }

        val listId = Regex("""pixeldrain\.com/l/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.getOrNull(1) ?: return false
        val list = runCatching {
            app.get(
                "https://pixeldrain.com/api/list/$listId",
                referer = url,
                headers = KusonimeUtils.headers,
                timeout = 20L
            ).parsedSafe<PixeldrainList>()
        }.getOrNull() ?: return false

        val wantedQuality = qualityName?.fixQuality()?.takeIf { it > 0 }
        val files = list.files.orEmpty()
            .filter { file -> file.id?.isNotBlank() == true }
            .filter { file ->
                val name = file.name.orEmpty()
                isVideoUrl(name) || name.contains(Regex("""\.(mp4|mkv|webm)$""", RegexOption.IGNORE_CASE))
            }
        val selected = if (wantedQuality != null) {
            files.filter { it.name.orEmpty().fixQuality() == wantedQuality }.ifEmpty { files }
        } else {
            files
        }

        var found = false
        selected.forEach { file ->
            val fileId = file.id ?: return@forEach
            val displayQuality = file.name.orEmpty().fixQuality().takeIf { it > 0 } ?: wantedQuality ?: Qualities.Unknown.value
            val displayName = listOf("Pixeldrain", if (displayQuality > 0) "${displayQuality}p" else null)
                .filterNotNull()
                .joinToString(" ")
            callback.invoke(
                newExtractorLink(
                    "Pixeldrain",
                    displayName,
                    "https://pixeldrain.com/api/file/$fileId"
                ) {
                    this.referer = url
                    this.quality = displayQuality
                    this.headers = mapOf("Referer" to url, "Origin" to "https://pixeldrain.com")
                }
            )
            found = true
        }
        return found
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        qualityName: String = "Kusonime"
    ): Boolean {
        val clean = url.cleanEscaped().trim()
        if (clean.isBlank() || !isVideoUrl(clean) && !clean.contains("pixeldrain.com/api/file/", true)) return false
        val quality = qualityName.fixQuality().takeIf { it > 0 } ?: clean.fixQuality().takeIf { it > 0 } ?: Qualities.Unknown.value
        val directHeaders = mapOf("Referer" to referer, "Origin" to originOf(referer))

        return if (clean.contains(".m3u8", true)) {
            generateM3u8(
                source = "Kusonime",
                streamUrl = clean,
                referer = referer,
                quality = quality,
                headers = directHeaders
            ).forEach(callback)
            true
        } else {
            callback.invoke(
                newExtractorLink(
                    "Kusonime",
                    "Kusonime ${if (quality > 0) "${quality}p" else "Direct"}",
                    clean
                ) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = directHeaders
                }
            )
            true
        }
    }

    private data class KusonimeSource(
        val url: String,
        val label: String,
        val qualityName: String?
    )

    private data class PixeldrainList(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("files") val files: ArrayList<PixeldrainFile>? = arrayListOf()
    )

    private data class PixeldrainFile(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("mime_type") val mimeType: String? = null
    )
}
