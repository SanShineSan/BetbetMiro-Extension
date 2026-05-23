package com.idlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLDecoder

class Jeniusplay : ExtractorApi() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace(" ", "%20")
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to (referer ?: mainUrl)
        )

        val response = runCatching {
            app.get(
                cleanUrl,
                referer = referer ?: mainUrl,
                headers = headers,
                timeout = 30L
            )
        }.getOrNull() ?: return

        val html = response.text.cleanEscaped()
        val found = linkedSetOf<String>()

        extractStreamUrls(html).forEach {
            found.add(normalizeUrl(it, cleanUrl))
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractStreamUrls(unpacked.cleanEscaped()).forEach {
                found.add(normalizeUrl(it, cleanUrl))
            }
        }

        if (found.isEmpty()) {
            val hash = cleanUrl.substringAfter("data=", cleanUrl.substringAfterLast("/"))
                .substringBefore("&")
                .substringBefore("?")
                .trim()

            val endpoints = listOf(
                "$mainUrl/player/ajax.php?data=$hash&do=getVideo",
                "$mainUrl/player/index.php?data=$hash&do=getVideo"
            )

            endpoints.forEach { endpoint ->
                val postText = runCatching {
                    app.post(
                        url = endpoint,
                        data = mapOf(
                            "hash" to hash,
                            "r" to (referer ?: "")
                        ),
                        referer = cleanUrl,
                        headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest"),
                        timeout = 30L
                    ).text.cleanEscaped()
                }.getOrNull().orEmpty()

                extractStreamUrls(postText).forEach {
                    found.add(normalizeUrl(it, cleanUrl))
                }
            }
        }

        found
            .filterNot { isAdUrl(it) }
            .forEach { stream ->
                emitVideo(
                    source = name,
                    streamUrl = stream,
                    referer = cleanUrl,
                    callback = callback
                )
            }
    }

    data class ResponseSource(
        @JsonProperty("videoSource") val videoSource: String? = null
    )
}

class Majorplay : MajorplayBase() {
    override var name = "Majorplay"
    override var mainUrl = "https://e2e.majorplay.net"
}

class MajorplayNet : MajorplayBase() {
    override var name = "Majorplay"
    override var mainUrl = "https://majorplay.net"
}

open class MajorplayBase : ExtractorApi() {
    override var name = "Majorplay"
    override var mainUrl = "https://e2e.majorplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = url.replace(" ", "%20")
        val domain = runCatching {
            "https://${URI(fixedUrl).host}"
        }.getOrDefault(mainUrl)

        val response = runCatching {
            app.get(
                fixedUrl,
                referer = referer ?: domain,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to (referer ?: domain),
                    "Accept" to "*/*"
                ),
                timeout = 30L
            )
        }.getOrNull() ?: return

        val body = response.text.cleanEscaped()

        if (body.trimStart().startsWith("#EXTM3U")) {
            emitVideo(
                source = name,
                streamUrl = fixedUrl,
                referer = referer ?: domain,
                callback = callback
            )
            return
        }

        val document = response.document
        val streams = linkedSetOf<String>()

        document.select("video source[src], source[src], video[src]").forEach { source ->
            val src = source.attr("src")
                .ifBlank { source.attr("abs:src") }
                .trim()

            if (src.isNotBlank()) {
                streams.add(normalizeUrl(src, fixedUrl))
            }
        }

        extractStreamUrls(body).forEach {
            streams.add(normalizeUrl(it, fixedUrl))
        }

        extractMajorplayConfigUrls(body).forEach { configUrl ->
            resolveMajorplayConfig(
                configUrl = normalizeUrl(configUrl, fixedUrl),
                referer = referer ?: domain,
                callback = callback
            )
        }

        val unpacked = runCatching {
            if (!getPacked(body).isNullOrEmpty()) getAndUnpack(body) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractStreamUrls(unpacked.cleanEscaped()).forEach {
                streams.add(normalizeUrl(it, fixedUrl))
            }

            extractMajorplayConfigUrls(unpacked.cleanEscaped()).forEach { configUrl ->
                resolveMajorplayConfig(
                    configUrl = normalizeUrl(configUrl, fixedUrl),
                    referer = referer ?: domain,
                    callback = callback
                )
            }
        }

        streams
            .filterNot { isAdUrl(it) }
            .forEach { stream ->
                emitVideo(
                    source = name,
                    streamUrl = stream,
                    referer = referer ?: domain,
                    callback = callback
                )
            }

        extractSubtitles(body, domain, subtitleCallback)

        val scripts = document.selectFirst("script:containsData(subtitles)")?.data().orEmpty()
        extractSubtitles(scripts, domain, subtitleCallback)
    }

    private suspend fun resolveMajorplayConfig(
        configUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isAdUrl(configUrl)) return

        val response = runCatching {
            app.get(
                configUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "*/*"
                ),
                timeout = 30L
            )
        }.getOrNull() ?: return

        val text = response.text.cleanEscaped()

        if (text.trimStart().startsWith("#EXTM3U")) {
            emitVideo(
                source = name,
                streamUrl = configUrl,
                referer = referer,
                callback = callback
            )
            return
        }

        extractStreamUrls(text)
            .map { normalizeUrl(it, configUrl) }
            .filterNot { isAdUrl(it) }
            .forEach { stream ->
                emitVideo(
                    source = name,
                    streamUrl = stream,
                    referer = configUrl,
                    callback = callback
                )
            }
    }

    private fun extractSubtitles(
        text: String,
        domain: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val clean = text.cleanEscaped()

        Regex(
            """"(?:lang|label)"\s*:\s*"([^"]+)"[^}]*?"(?:path|url|file)"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val label = match.groupValues[1].ifBlank { "Subtitle" }
            val rawUrl = match.groupValues[2].cleanEscaped()

            val subUrl = when {
                rawUrl.startsWith("http", true) -> rawUrl
                rawUrl.startsWith("//") -> "https:$rawUrl"
                else -> domain.trimEnd('/') + "/" + rawUrl.trimStart('/')
            }

            subtitleCallback(
                newSubtitleFile(
                    label,
                    subUrl
                )
            )
        }

        Regex(
            """\\"(?:lang|label)\\":\\"([^\\"]+)\\"[^}]*?\\"(?:path|url|file)\\":\\"([^\\"]+)\\"""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val label = match.groupValues[1].ifBlank { "Subtitle" }
            val rawUrl = match.groupValues[2].cleanEscaped()

            val subUrl = when {
                rawUrl.startsWith("http", true) -> rawUrl
                rawUrl.startsWith("//") -> "https:$rawUrl"
                else -> domain.trimEnd('/') + "/" + rawUrl.trimStart('/')
            }

            subtitleCallback(
                newSubtitleFile(
                    label,
                    subUrl
                )
            )
        }
    }
}

private suspend fun emitVideo(
    source: String,
    streamUrl: String,
    referer: String,
    callback: (ExtractorLink) -> Unit
) {
    val fixedStream = streamUrl
        .cleanEscaped()
        .replace(".txt", ".m3u8")

    if (isAdUrl(fixedStream)) return

    val isHlsLike = fixedStream.contains(".m3u8", true) ||
        isMajorplayConfig(fixedStream)

    callback(
        newExtractorLink(
            source = source,
            name = source,
            url = fixedStream,
            type = if (isHlsLike) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
        ) {
            this.referer = referer
            this.quality = getQualityFromName(fixedStream).takeIf {
                it != Qualities.Unknown.value
            } ?: qualityFromUrl(fixedStream)
        }
    )
}

private fun extractStreamUrls(text: String): List<String> {
    val urls = linkedSetOf<String>()
    val cleanText = text.cleanEscaped()

    Regex(
        """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(cleanText)
        .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
        .filterNot { isAdUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.txt)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(cleanText)
        .map {
            runCatching {
                URLDecoder.decode(it.value, "UTF-8")
            }.getOrDefault(it.value)
        }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filterNot { isAdUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """(?:file|source|src|url|videoSource|videoUrl)\s*[:=]\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(cleanText)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filter {
            it.contains(".m3u8", true) ||
                it.contains(".mp4", true) ||
                it.contains(".txt", true)
        }
        .filterNot { isAdUrl(it) }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun extractMajorplayConfigUrls(text: String): List<String> {
    val urls = linkedSetOf<String>()
    val cleanText = text.cleanEscaped()

    Regex(
        """https?://[^"'\\\s<>]+?(?:majorplay|e2e\.majorplay)[^"'\\\s<>]*?config[^"'\\\s<>]*?\.json(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(cleanText)
        .map { it.value.cleanEscaped() }
        .filterNot { isAdUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """(?:url|src|file|source|videoSource|videoUrl)\s*[:=]\s*["']([^"']*config[^"']*\.json[^"']*)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(cleanText)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscaped() }
        .filterNot { isAdUrl(it) }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun normalizeUrl(
    url: String,
    baseUrl: String
): String {
    val clean = url.cleanEscaped()

    return when {
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> {
            val origin = Regex("""^https?://[^/]+""")
                .find(baseUrl)
                ?.value
                ?: ""
            "$origin$clean"
        }
        else -> {
            runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrElse {
                val origin = Regex("""^https?://[^/]+""")
                    .find(baseUrl)
                    ?.value
                    ?: ""
                if (origin.isNotBlank()) "$origin/$clean" else clean
            }
        }
    }
}

private fun isMajorplayConfig(url: String): Boolean {
    return url.contains("majorplay", true) &&
        url.contains("config", true) &&
        url.contains(".json", true)
}

private fun isAdUrl(url: String): Boolean {
    return url.contains("vast", true) ||
        url.contains("preroll", true) ||
        url.contains("qq288", true) ||
        url.contains("sngine", true) ||
        url.contains("/content/uploads/videos/", true) ||
        url.contains("demo.sngine.com", true)
}

private fun qualityFromUrl(url: String): Int {
    return when {
        url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

private fun String.cleanEscaped(): String {
    return this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .trim()
}