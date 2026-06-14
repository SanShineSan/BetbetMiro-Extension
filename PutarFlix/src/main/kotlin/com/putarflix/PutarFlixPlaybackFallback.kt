package com.putarflix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

internal object PutarFlixPlaybackFallback {
    private const val EXTRACT_TIMEOUT_MS = 45_000L
    private const val REQUEST_TIMEOUT_MS = 15_000L
    private const val MAX_DEPTH = 4

    private data class Candidate(
        val url: String,
        val referer: String,
        val label: String
    )

    suspend fun extract(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
            extractInternal(data, subtitleCallback, callback)
        } ?: false
    }

    private suspend fun extractInternal(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startUrl = PutarFlixUtils.decodeKnownRedirect(data.trim())
        if (startUrl.isBlank()) return false

        val candidates = linkedSetOf<Candidate>()
        if (PutarFlixUtils.isPutarFlixUrl(startUrl)) {
            val base = startUrl.substringBefore("#").substringBefore("?")
            val pages = buildList {
                add(base)
                PutarFlixSeeds.playerNumbers.forEach { number ->
                    if (number != "1") add("$base?player=$number")
                }
            }.distinct()

            for (pageUrl in pages) {
                val doc = safeGetDocument(pageUrl, PutarFlixSeeds.MAIN_URL) ?: continue
                candidates += collectMirrorCandidates(pageUrl, doc)
            }
        } else {
            candidates += Candidate(startUrl, PutarFlixSeeds.MAIN_URL, "PutarFlix Fallback")
        }

        var found = false
        val visited = linkedSetOf<String>()
        for (candidate in candidates.distinctBy { normalizeCandidate(it.url) }) {
            val resolved = resolveCandidate(
                url = candidate.url,
                referer = candidate.referer,
                label = candidate.label,
                subtitleCallback = subtitleCallback,
                callback = callback,
                visited = visited,
                depth = 0
            )
            if (resolved) found = true
        }
        return found
    }

    private fun collectMirrorCandidates(pageUrl: String, doc: Document): Set<Candidate> {
        val output = linkedSetOf<Candidate>()
        val html = doc.outerHtml()

        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            val absolute = PutarFlixUtils.absoluteUrl(pageUrl, href) ?: return@forEach
            val label = PutarFlixUtils.extractLabelNear(anchor).ifBlank { "PutarFlix Mirror" }
            addDecodedCandidate(output, absolute, pageUrl, label)
        }

        extractUrlLikeValues(html).forEach { raw ->
            val absolute = PutarFlixUtils.absoluteUrl(pageUrl, raw) ?: return@forEach
            addDecodedCandidate(output, absolute, pageUrl, "PutarFlix Mirror")
        }

        PutarFlixUtils.decodeBase64Payloads(html).forEach { decoded ->
            extractUrlLikeValues(decoded).forEach { raw ->
                val absolute = PutarFlixUtils.absoluteUrl(pageUrl, raw) ?: return@forEach
                addDecodedCandidate(output, absolute, pageUrl, "PutarFlix Encoded Mirror")
            }
        }

        return output
    }

    private fun addDecodedCandidate(
        output: MutableSet<Candidate>,
        rawUrl: String,
        referer: String,
        label: String
    ) {
        val cleaned = cleanEscapedUrl(rawUrl)
        val decoded = decodeWrappedUrl(cleaned) ?: PutarFlixUtils.decodeKnownRedirect(cleaned)
        listOf(cleaned, decoded).map(::cleanEscapedUrl).distinct().forEach { url ->
            if (isPlaybackCandidate(url)) {
                output += Candidate(url, referer, label)
            }
        }
    }

    private suspend fun resolveCandidate(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String>,
        depth: Int
    ): Boolean {
        val fixedUrl = cleanEscapedUrl(decodeWrappedUrl(url) ?: PutarFlixUtils.decodeKnownRedirect(url))
        if (fixedUrl.isBlank() || depth > MAX_DEPTH || fixedUrl in visited) return false
        visited += fixedUrl

        if (PutarFlixUtils.looksDirectVideo(fixedUrl) || PutarFlixUtils.isFinalStreamUrl(fixedUrl)) {
            return emitDirect(fixedUrl, referer, label, callback)
        }

        if (PutarFlixUtils.isFilePressUrl(fixedUrl)) {
            val filePressResolved = resolveFilePress(
                url = fixedUrl,
                referer = referer,
                label = label.ifBlank { "FilePress" },
                subtitleCallback = subtitleCallback,
                callback = callback,
                visited = visited,
                depth = depth + 1
            )
            if (filePressResolved) return true
        }

        if (PutarFlixUtils.isGoogleDriveLandingUrl(fixedUrl)) {
            val driveId = PutarFlixUtils.extractGoogleDriveId(fixedUrl)
            if (!driveId.isNullOrBlank()) {
                return emitDirect(PutarFlixUtils.googleDriveDownloadUrl(driveId), referer, label.ifBlank { "Google Drive" }, callback)
            }
        }

        val loaded = safeLoadExtractor(fixedUrl, referer, subtitleCallback, callback)
        if (loaded) return true

        if (!isPlaybackCandidate(fixedUrl)) return false
        val doc = safeGetDocument(fixedUrl, referer) ?: return false
        val nested = collectMirrorCandidates(fixedUrl, doc)
            .filterNot { normalizeCandidate(it.url) == normalizeCandidate(fixedUrl) }
            .take(12)

        var found = false
        for (candidate in nested) {
            val resolved = resolveCandidate(
                url = candidate.url,
                referer = fixedUrl,
                label = candidate.label.ifBlank { label },
                subtitleCallback = subtitleCallback,
                callback = callback,
                visited = visited,
                depth = depth + 1
            )
            if (resolved) found = true
        }
        return found
    }

    private suspend fun resolveFilePress(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String>,
        depth: Int
    ): Boolean {
        if (depth > MAX_DEPTH) return false
        val origin = PutarFlixUtils.originOf(url) ?: return false
        val fileId = Regex("""/file/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() } ?: return false

        val candidates = linkedSetOf<Candidate>()
        safeGetDocument(url, referer)?.let { doc ->
            candidates += collectMirrorCandidates(url, doc)
            parseFilePressValues(url, doc.outerHtml(), label).forEach { candidates += Candidate(it, url, label) }
        }

        val nextIds = linkedSetOf(fileId)
        val firstStepEndpoints = listOf(
            "$origin/api/file/downlaod/",
            "$origin/api/file/download/",
            "$origin/api/file/",
            "$origin/api/download/"
        )
        val firstStepMethods = listOf("publicDownlaod", "publicDownload", "download", "telegramDownload")

        for (endpoint in firstStepEndpoints) {
            for (method in firstStepMethods) {
                val response = postFilePressJson(endpoint, url, fileId, method)
                    ?: postFilePressForm(endpoint, url, mapOf("id" to fileId, "method" to method))
                    ?: continue
                parseFilePressResponse(url, response, label, candidates, nextIds)
            }
        }

        val secondStepEndpoints = listOf(
            "$origin/api/file/downlaod2/",
            "$origin/api/file/download2/",
            "$origin/api/file/downlaod/",
            "$origin/api/file/download/"
        )
        for (nextId in nextIds.distinct().take(8)) {
            for (endpoint in secondStepEndpoints) {
                val response = postFilePressJson(endpoint, url, nextId, "publicDownlaod")
                    ?: postFilePressJson(endpoint, url, nextId, "publicDownload")
                    ?: postFilePressForm(endpoint, url, mapOf("id" to nextId, "method" to "publicDownlaod"))
                    ?: postFilePressForm(endpoint, url, mapOf("id" to nextId, "method" to "publicDownload"))
                    ?: continue
                parseFilePressResponse(url, response, label, candidates, linkedSetOf())
            }
        }

        var found = false
        for (candidate in candidates.distinctBy { normalizeCandidate(it.url) }.take(20)) {
            val target = cleanEscapedUrl(candidate.url)
            if (target == url || target in visited) continue
            val resolved = resolveCandidate(
                url = target,
                referer = url,
                label = candidate.label.ifBlank { label },
                subtitleCallback = subtitleCallback,
                callback = callback,
                visited = visited,
                depth = depth + 1
            )
            if (resolved) found = true
        }
        return found
    }

    private fun parseFilePressResponse(
        baseUrl: String,
        response: String,
        label: String,
        candidates: MutableSet<Candidate>,
        nextIds: MutableSet<String>
    ) {
        parseFilePressValues(baseUrl, response, label).forEach { value ->
            when {
                value.startsWith("http", true) || value.startsWith("//") -> {
                    val absolute = PutarFlixUtils.absoluteUrl(baseUrl, value) ?: value
                    candidates += Candidate(absolute, baseUrl, label)
                }
                looksLikeGoogleDriveId(value) -> {
                    candidates += Candidate(PutarFlixUtils.googleDriveDownloadUrl(value), baseUrl, label.ifBlank { "Google Drive" })
                    candidates += Candidate("https://drive.google.com/file/d/$value/view", baseUrl, label.ifBlank { "Google Drive" })
                }
                looksLikeFilePressId(value) -> nextIds += value
            }
        }
    }

    private fun parseFilePressValues(baseUrl: String, text: String, label: String): List<String> {
        val clean = cleanEscapedUrl(text)
        val values = linkedSetOf<String>()

        extractUrlLikeValues(clean).forEach { raw ->
            val absolute = PutarFlixUtils.absoluteUrl(baseUrl, raw) ?: raw
            values += decodeWrappedUrl(absolute) ?: absolute
        }

        val keys = listOf(
            "data", "id", "file_id", "fileId", "driveId", "googleDriveId", "gdrive",
            "source", "src", "link", "url", "file", "download", "download_url", "downloadLink",
            "video", "videoUrl", "video_url", "stream", "streamUrl", "hls", "hlsUrl"
        )
        keys.forEach { key ->
            Regex("""["']${Regex.escape(key)}["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(clean)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .map(::cleanEscapedUrl)
                .forEach { values += it }

            Regex("""["']${Regex.escape(key)}["']\s*:\s*([A-Za-z0-9_-]{8,160})""", RegexOption.IGNORE_CASE)
                .findAll(clean)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .forEach { values += it }
        }

        PutarFlixUtils.decodeBase64Payloads(clean)
            .filter { it != clean }
            .forEach { decoded ->
                parseFilePressValues(baseUrl, decoded, label).forEach { values += it }
            }
        return values.map(::cleanEscapedUrl).filter { it.isNotBlank() }.distinct()
    }

    private suspend fun safeLoadExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        val trackedCallback: (ExtractorLink) -> Unit = { link ->
            if (!PutarFlixUtils.isHtmlLandingUrl(link.url) || PutarFlixUtils.isFinalStreamUrl(link.url)) {
                emitted = true
                callback(link)
            }
        }
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching { loadExtractor(url, referer, subtitleCallback, trackedCallback) }.getOrDefault(false)
        }
        return emitted
    }

    private suspend fun safeGetDocument(url: String, referer: String): Document? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                app.get(
                    url = url,
                    referer = referer,
                    timeout = REQUEST_TIMEOUT_MS,
                    headers = browserHeaders(referer)
                ).document
            }.getOrNull()
        }
    }

    private suspend fun postFilePressJson(url: String, referer: String, id: String, method: String): String? {
        val body = """{"id":"${escapeJson(id)}","method":"${escapeJson(method)}"}"""
            .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                app.post(
                    url = url,
                    referer = referer,
                    timeout = REQUEST_TIMEOUT_MS,
                    headers = apiHeaders(referer, true),
                    requestBody = body
                ).text
            }.getOrNull()
        }
    }

    private suspend fun postFilePressForm(url: String, referer: String, data: Map<String, String>): String? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                app.post(
                    url = url,
                    referer = referer,
                    timeout = REQUEST_TIMEOUT_MS,
                    headers = apiHeaders(referer, false),
                    data = data
                ).text
            }.getOrNull()
        }
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = cleanEscapedUrl(url)
        val headers = browserHeaders(referer)
        val type = when {
            fixed.substringBefore("?").endsWith(".m3u8", true) -> ExtractorLinkType.M3U8
            fixed.substringBefore("?").endsWith(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        if (type == ExtractorLinkType.M3U8) {
            val generated = runCatching {
                generateM3u8(
                    source = label.ifBlank { "PutarFlix" },
                    streamUrl = fixed,
                    referer = referer,
                    headers = headers
                )
            }.getOrNull()
            if (!generated.isNullOrEmpty()) {
                generated.forEach(callback)
                return true
            }
        }

        callback(
            newExtractorLink(
                source = "PutarFlix",
                name = label.ifBlank { "PutarFlix" },
                url = fixed,
                type = type
            ) {
                this.referer = referer
                this.quality = getQualityFromName(label).takeIf { it > 0 } ?: getQualityFromName(fixed)
                this.headers = headers
            }
        )
        return true
    }

    private fun isPlaybackCandidate(url: String): Boolean {
        val clean = cleanEscapedUrl(url)
        if (clean.isBlank() || PutarFlixUtils.isRejectedVideoCandidate(clean)) return false
        return PutarFlixUtils.looksDirectVideo(clean) ||
            PutarFlixUtils.isFinalStreamUrl(clean) ||
            PutarFlixUtils.isKnownPlayableHost(clean) ||
            PutarFlixUtils.isDirectDownloadUrl(clean) ||
            PutarFlixUtils.isShortenerUrl(clean) ||
            clean.contains("filepress", true) ||
            clean.contains("drive.google.com", true)
    }

    private fun extractUrlLikeValues(text: String): List<String> {
        val clean = cleanEscapedUrl(text)
        val values = linkedSetOf<String>()
        val patterns = listOf(
            Regex("""https?:\\?/\\?/[^\"'<>)\]\[\s]+""", RegexOption.IGNORE_CASE),
            Regex("""(?<!:)//[^\"'<>)\]\[\s]+""", RegexOption.IGNORE_CASE),
            Regex("""https?%3A%2F%2F[^\"'<>)\]\[\s]+""", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { regex ->
            regex.findAll(clean).map { it.value }.forEach { values += cleanEscapedUrl(it) }
        }
        return values.toList()
    }

    private fun decodeWrappedUrl(url: String): String? {
        val uri = runCatching { URI(cleanEscapedUrl(url)) }.getOrNull() ?: return null
        val rawQuery = uri.rawQuery.orEmpty()
        val encoded = rawQuery.split("&")
            .firstOrNull { it.substringBefore("=").lowercase() in listOf("url", "u", "go", "target", "link", "file", "src") }
            ?.substringAfter("=", "")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val decoded = PutarFlixUtils.decodeUrlRepeated(encoded)
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
            .replace("\\u002f", "/")
            .trim()

        if (decoded.startsWith("http", true) || decoded.startsWith("//")) {
            return PutarFlixUtils.absoluteUrl(url, decoded)
        }

        val padded = decoded + "=".repeat((4 - decoded.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded)) }
            .recoverCatching { String(Base64.getUrlDecoder().decode(padded)) }
            .mapCatching { PutarFlixUtils.decodeUrlRepeated(it).replace("\\/", "/") }
            .mapCatching { PutarFlixUtils.absoluteUrl(url, it) ?: it }
            .getOrNull()
    }

    private fun cleanEscapedUrl(value: String): String {
        val decoded = runCatching { URLDecoder.decode(value.trim(), "UTF-8") }.getOrDefault(value.trim())
        return decoded
            .replace("\\/", "/")
            .replace("\\\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&#38;", "&")
            .replace("&quot;", "\"")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
            .replace("\\u002f", "/")
            .trim(' ', '"', '\'', '`', ',', ';', ')', ']', '}')
    }

    private fun normalizeCandidate(url: String): String = cleanEscapedUrl(decodeWrappedUrl(url) ?: PutarFlixUtils.decodeKnownRedirect(url))

    private fun looksLikeGoogleDriveId(value: String): Boolean {
        val clean = value.trim()
        if (!Regex("^[A-Za-z0-9_-]{20,140}$").matches(clean)) return false
        if (Regex("^[a-f0-9]{24}$", RegexOption.IGNORE_CASE).matches(clean)) return false
        return true
    }

    private fun looksLikeFilePressId(value: String): Boolean {
        return Regex("^[A-Za-z0-9_-]{8,160}$").matches(value.trim())
    }

    private fun browserHeaders(referer: String): Map<String, String> {
        val origin = PutarFlixUtils.originOf(referer) ?: PutarFlixSeeds.MAIN_URL
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer,
            "Origin" to origin
        )
    }

    private fun apiHeaders(referer: String, json: Boolean): Map<String, String> {
        val origin = PutarFlixUtils.originOf(referer) ?: PutarFlixSeeds.MAIN_URL
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Content-Type" to if (json) "application/json; charset=UTF-8" else "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to referer,
            "Origin" to origin
        )
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
