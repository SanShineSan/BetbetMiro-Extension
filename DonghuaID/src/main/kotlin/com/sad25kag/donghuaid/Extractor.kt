package com.sad25kag.donghuaid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class DonghuaIDGeoDailyMotion : DonghuaIDDailyMotion() {
    override val name = "GeoDailyMotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class DonghuaIDDailyMotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = extractId(url) ?: return
        val embedder = URLEncoder.encode(referer ?: "https://donghuaid.live/", "UTF-8")
        val metadataUrls = listOf(
            "https://www.dailymotion.com/player/metadata/video/$id",
            "https://geo.dailymotion.com/video/$id.json?legacy=true&embedder=$embedder&geo=1",
        )

        val emitted = linkedSetOf<String>()
        for (metadataUrl in metadataUrls) {
            val response = runCatching {
                app.get(
                    metadataUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json,text/plain,*/*",
                    ),
                    referer = url,
                ).text
            }.getOrNull().orEmpty()
            if (response.isBlank()) continue

            Regex(""""url"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""", RegexOption.IGNORE_CASE)
                .findAll(response)
                .map { it.groupValues[1].decodePlayerText() }
                .filter { it.contains(".m3u8", true) }
                .distinct()
                .forEach { streamUrl ->
                    if (emitted.add(streamUrl.substringBefore("#"))) {
                        M3u8Helper.generateM3u8(name, streamUrl, url).forEach(callback)
                    }
                }

            Regex(""""label"\s*:\s*"([^"]+)"[^\[]+\[\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .findAll(response)
                .forEach { match ->
                    val lang = match.groupValues[1]
                    val subUrl = match.groupValues[2].decodePlayerText()
                    if (subUrl.startsWith("http")) subtitleCallback(SubtitleFile(url = subUrl, lang = lang))
                }
        }
    }

    private fun extractId(url: String): String? {
        val clean = url.substringBefore("#")
        Regex("""[?&]video=([A-Za-z0-9]+)""").find(clean)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""(?:/embed/video/|/video/)([A-Za-z0-9]+)""").find(clean)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""dai\.ly/([A-Za-z0-9]+)""").find(clean)?.groupValues?.getOrNull(1)?.let { return it }
        return runCatching {
            URI(clean.substringBefore("?")).path.substringAfterLast('/').takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
        }.getOrNull()
    }
}

class DonghuaIDOkRuSSL : DonghuaIDOdnoklassniki() {
    override var name = "OkRuSSL"
    override var mainUrl = "https://ok.ru"
}

class DonghuaIDOkRuHTTP : DonghuaIDOdnoklassniki() {
    override var name = "OkRuHTTP"
    override var mainUrl = "http://ok.ru"
}

open class DonghuaIDOdnoklassniki : ExtractorApi() {
    override var name = "Odnoklassniki"
    override var mainUrl = "https://odnoklassniki.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val embedUrl = url
            .replace("/video/", "/videoembed/")
            .replace("/videoembed/videoembed/", "/videoembed/")
        val headers = mapOf(
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )
        val body = runCatching {
            app.get(embedUrl, headers = headers, referer = referer ?: mainUrl).text
        }.getOrNull().orEmpty().decodePlayerText()

        val videos = Regex(""""videos"\s*:\s*(\[[^]]+])""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::parseVideos)
            .orEmpty()

        videos.forEach { video ->
            val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            val quality = video.name.uppercase()
                .replace("MOBILE", "144p")
                .replace("LOWEST", "240p")
                .replace("LOW", "360p")
                .replace("SD", "480p")
                .replace("HD", "720p")
                .replace("FULL", "1080p")
                .replace("QUAD", "1440p")
                .replace("ULTRA", "2160p")

            callback(
                newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    this.quality = getQualityFromName(quality)
                    this.headers = headers
                }
            )
        }
    }

    private fun parseVideos(value: String): List<OkRuVideo> {
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val label = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    if (label.isNotBlank() && url.isNotBlank()) add(OkRuVideo(label, url))
                }
            }
        }.getOrDefault(emptyList())
    }

    private data class OkRuVideo(val name: String, val url: String)
}

class DonghuaIDTurboVidhls : ExtractorApi() {
    override val name = "Turbo"
    override val mainUrl = "https://turbovidhls.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        val html = runCatching {
            app.get(url, headers = headers, referer = referer ?: "https://donghuaid.live/").text
        }.getOrNull().orEmpty().decodePlayerText()

        val id = Regex("""/t/([A-Za-z0-9]+)""").find(url)?.groupValues?.getOrNull(1)
        val directUrls = linkedSetOf<String>()
        directUrls.addAll(collectDirectMedia(html, url))
        if (!id.isNullOrBlank()) {
            directUrls.add("https://cdn.turboviplay.com/data3/$id/$id.m3u8")
        }

        directUrls.forEach { streamUrl ->
            when {
                streamUrl.contains(".m3u8", true) -> M3u8Helper.generateM3u8(name, streamUrl, url).forEach(callback)
                else -> callback(
                    newExtractorLink(name, name, streamUrl, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = getQualityFromName(streamUrl)
                        this.headers = headers + mapOf("Referer" to url)
                    }
                )
            }
        }
    }
}

class DonghuaIDTurboSPlayer : DonghuaIDDirectMediaExtractor() {
    override var name = "TurboSPlayer"
    override var mainUrl = "https://g272.turbosplayer.com"
}

class DonghuaIDShortIcu : DonghuaIDGenericPlayerExtractor() {
    override var name = "ShortIcu"
    override var mainUrl = "https://short.icu"
}

class DonghuaIDShortInk : DonghuaIDGenericPlayerExtractor() {
    override var name = "ShortInk"
    override var mainUrl = "https://short.ink"
}

class DonghuaIDAbyssPlayer : DonghuaIDGenericPlayerExtractor() {
    override var name = "AbyssPlayer"
    override var mainUrl = "https://abyssplayer.com"
}

class DonghuaIDVidhideVip : DonghuaIDGenericPlayerExtractor() {
    override var name = "VidhideVip"
    override var mainUrl = "https://vidhidevip.com"
}

class DonghuaIDVectorX : DonghuaIDGenericPlayerExtractor() {
    override var name = "VectorX"
    override var mainUrl = "https://vectorx.top"
}


open class DonghuaIDFileLions : ExtractorApi() {
    override var name = "FileLions"
    override var mainUrl = "https://callistanise.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val embedUrl = url.toFileLionsEmbedUrl()
        val origin = embedUrl.originUrl()
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Origin" to origin,
            "Referer" to embedUrl,
        )
        val emitted = linkedSetOf<String>()

        suspend fun emitFromText(rawText: String, baseUrl: String) {
            collectDirectMedia(rawText, baseUrl).forEach { mediaUrl ->
                val key = mediaUrl.substringBefore("#")
                if (!emitted.add(key)) return@forEach
                when {
                    mediaUrl.contains(".m3u8", true) -> M3u8Helper.generateM3u8(name, mediaUrl, embedUrl).forEach(callback)
                    else -> callback(
                        newExtractorLink(name, name, mediaUrl, ExtractorLinkType.VIDEO) {
                            this.referer = embedUrl
                            this.quality = getQualityFromName(mediaUrl)
                            this.headers = headers + mapOf("Referer" to embedUrl)
                        }
                    )
                }
            }
        }

        val html = runCatching {
            app.get(embedUrl, headers = headers, referer = referer ?: "https://donghuaid.live/").text
        }.getOrNull().orEmpty().decodePlayerText()

        emitFromText(html, embedUrl)
        if (emitted.isNotEmpty()) return

        val dlUrls = linkedSetOf<String>()
        Regex("""["']([^"']*/dl\?[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { it.groupValues[1].decodePlayerText().toAbsoluteUrl(embedUrl) }
            .forEach { dlUrls.add(it) }

        val fileCode = embedUrl.fileLionsCode()
        if (!fileCode.isNullOrBlank()) {
            Regex("""(?:hash|hash_str)\s*[:=]\s*["']?([A-Za-z0-9_-]+-\d+-[A-Fa-f0-9]+)""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .forEach { hash ->
                    dlUrls.add("$origin/dl?op=view&file_code=$fileCode&hash=$hash&embed=1&referer=donghuaid.live&adb=0&hls4=1")
                }
        }

        for (dlUrl in dlUrls.take(6)) {
            val dlText = runCatching {
                app.get(dlUrl, headers = headers + mapOf("Accept" to "*/*"), referer = embedUrl).text
            }.getOrNull().orEmpty().decodePlayerText()
            emitFromText(dlText, embedUrl)
            if (emitted.isNotEmpty()) return
        }

        collectNestedPlayerUrls(html, embedUrl)
            .filterNot { it.normalizedKey() == embedUrl.normalizedKey() }
            .distinctBy { it.normalizedKey() }
            .take(8)
            .forEach { nestedUrl ->
                runCatching { loadExtractor(nestedUrl, embedUrl, subtitleCallback, callback) }
            }
    }
}

class DonghuaIDFileLionsCallistanise : DonghuaIDFileLions() {
    override var name = "FileLions"
    override var mainUrl = "https://callistanise.com"
}

class DonghuaIDFileLionsTo : DonghuaIDFileLions() {
    override var name = "FileLionsTo"
    override var mainUrl = "https://filelions.to"
}

class DonghuaIDFileLionsLive : DonghuaIDFileLions() {
    override var name = "FileLionsLive"
    override var mainUrl = "https://filelions.live"
}

class DonghuaIDFileLionsSite : DonghuaIDFileLions() {
    override var name = "FileLionsSite"
    override var mainUrl = "https://filelions.site"
}

class DonghuaIDFileLionsOnline : DonghuaIDFileLions() {
    override var name = "FileLionsOnline"
    override var mainUrl = "https://filelions.online"
}

open class DonghuaIDGenericPlayerExtractor : ExtractorApi() {
    override var name = "DonghuaIDPlayer"
    override var mainUrl = "https://donghuaid.live"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        val body = runCatching {
            app.get(url, headers = headers, referer = referer ?: "https://donghuaid.live/").text
        }.getOrNull().orEmpty().decodePlayerText()
        if (body.isBlank()) return

        val emitted = linkedSetOf<String>()
        collectDirectMedia(body, url).forEach { mediaUrl ->
            if (!emitted.add(mediaUrl.substringBefore("#"))) return@forEach
            when {
                mediaUrl.contains(".m3u8", true) -> M3u8Helper.generateM3u8(name, mediaUrl, url).forEach(callback)
                else -> callback(
                    newExtractorLink(name, name, mediaUrl, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = getQualityFromName(mediaUrl)
                        this.headers = headers + mapOf("Referer" to url)
                    }
                )
            }
        }

        val nestedPlayers = collectNestedPlayerUrls(body, url)
            .filterNot { it.normalizedKey() == url.normalizedKey() }
            .distinctBy { it.normalizedKey() }
            .take(12)
        nestedPlayers.forEach { nestedUrl ->
            runCatching { loadExtractor(nestedUrl, url, subtitleCallback, callback) }
        }
    }
}

open class DonghuaIDDirectMediaExtractor : ExtractorApi() {
    override var name = "DonghuaIDDirect"
    override var mainUrl = "https://donghuaid.live"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val finalUrl = url.decodePlayerText()
        when {
            finalUrl.contains(".m3u8", true) -> M3u8Helper.generateM3u8(name, finalUrl, referer ?: mainUrl).forEach(callback)
            finalUrl.isDirectMediaLike() -> callback(
                newExtractorLink(name, name, finalUrl, ExtractorLinkType.VIDEO) {
                    this.referer = referer ?: mainUrl
                    this.quality = getQualityFromName(finalUrl)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to (referer ?: mainUrl),
                    )
                }
            )
        }
    }
}

private fun collectNestedPlayerUrls(text: String, base: String): List<String> {
    val urls = linkedSetOf<String>()
    val parsed = Jsoup.parse(text)
    parsed.select("iframe[src], embed[src], video[src], source[src], meta[itemprop=embedUrl][content]").forEach { node ->
        val src = node.attr("src").ifBlank { node.attr("content") }
        src.toAbsoluteUrl(base)?.let { if (it.isPlayablePageLike()) urls.add(it) }
    }
    Regex("""(?:src|file|url|source|video|embed|iframe)\s*[:=]\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .mapNotNull { it.groupValues[1].decodePlayerText().toAbsoluteUrl(base) }
        .filter { it.isPlayablePageLike() }
        .forEach { urls.add(it) }
    return urls.toList()
}

private fun collectDirectMedia(text: String, base: String): List<String> {
    val normalized = text.decodePlayerText()
    val urls = linkedSetOf<String>()
    Regex("""https?:\\?/\\?/[^'\"<>()\\\s]+""", RegexOption.IGNORE_CASE)
        .findAll(normalized)
        .map { it.value.replace("\\/", "/") }
        .forEach { urls.add(it) }
    Regex("""https?://[^'\"<>()\s]+""", RegexOption.IGNORE_CASE)
        .findAll(normalized)
        .map { it.value.trimEnd(',', ';', ')') }
        .forEach { urls.add(it) }
    return urls.mapNotNull { it.toAbsoluteUrl(base) }
        .filter { it.isDirectMediaLike() }
        .distinct()
}

private fun String.decodePlayerText(): String {
    var value = this
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u0026", "&")
        .replace("\\&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("\\\\", "\\")
        .replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
            Integer.parseInt(match.groupValues[1], 16).toChar().toString()
        }
    repeat(2) {
        value = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
    return value.trim()
}

private fun String.toAbsoluteUrl(baseUrl: String): String? {
    val value = trim().trim('"', '\'').replace("\\/", "/")
    if (value.isBlank() || value.startsWith("javascript:", true) || value == "#" || value.startsWith("data:", true)) return null
    if (value.startsWith("//")) return "https:$value"
    if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
    return runCatching { URI(baseUrl).resolve(value).toString() }.getOrNull()
}

private fun String.toFileLionsEmbedUrl(): String {
    val clean = decodePlayerText()
    val code = clean.fileLionsCode()
    if (code.isNullOrBlank()) return clean
    val origin = clean.originUrl()
    return "$origin/embed/$code"
}

private fun String.fileLionsCode(): String? {
    val clean = decodePlayerText().substringBefore("?").substringBefore("#")
    Regex("""/(?:embed|v|f|d)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
        .find(clean)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return it }
    return null
}

private fun String.originUrl(): String = runCatching {
    val uri = URI(this)
    "${uri.scheme}://${uri.host}"
}.getOrDefault("https://donghuaid.live")


private fun String.isDirectMediaLike(): Boolean {
    val value = lowercase(Locale.ROOT).substringBefore("#")
    return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".m4s") || value.contains(".webm") || value.contains(".mkv") || value.contains("videoplayback") || value.contains("/stream/")
}

private fun String.isPlayablePageLike(): Boolean {
    val value = decodePlayerText().lowercase(Locale.ROOT).substringBefore("#")
    if (!value.startsWith("http://") && !value.startsWith("https://")) return false
    if (isDirectMediaLike()) return true
    if (value.contains("google-analytics") || value.contains("googletagmanager") || value.contains("doubleclick")) return false
    val path = value.substringBefore("?")
    val assetExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".css", ".js", ".woff", ".woff2", ".ttf", ".ico")
    return assetExtensions.none { path.endsWith(it) }
}

private fun String.normalizedKey(): String = substringBefore("#").trimEnd('/').lowercase(Locale.ROOT)
