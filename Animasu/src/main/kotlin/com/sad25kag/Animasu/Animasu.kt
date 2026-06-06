package com.sad25kag.Animasu

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Animasu : MainAPI() {

    override var mainUrl = "https://v1.animasu.work"
    override var name = "Animasu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val MAX_TOP_LEVEL_CANDIDATES = 18
        private const val MAX_DOWNLOAD_CANDIDATES = 8
        private const val MAX_NESTED_CANDIDATES = 10
        private const val MAX_RESOLVE_DEPTH = 1
        private const val MAX_VISITED_LINKS = 34
        private const val MAX_NESTED_TEXT_BYTES = 1_000_000L
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"

        fun getType(t: String?): TvType {
            if (t == null) return TvType.Anime

            return when {
                t.contains("Tv", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            if (t == null) return ShowStatus.Completed

            return when {
                t.contains("Sedang Tayang", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "urutan=update" to "Baru diupdate",
        "status=&tipe=&urutan=publikasi" to "Baru ditambahkan",
        "status=&tipe=&urutan=populer" to "Terpopuler",
        "status=&tipe=&urutan=rating" to "Rating Tertinggi",
        "status=&tipe=Movie&urutan=update" to "Movie Terbaru",
        "genre%5B%5D=aksi&status=&tipe=&urutan=update" to "Aksi",
        "genre%5B%5D=petualangan&status=&tipe=&urutan=update" to "Petualangan",
        "genre%5B%5D=komedi&status=&tipe=&urutan=update" to "Komedi",
        "genre%5B%5D=drama&status=&tipe=&urutan=update" to "Drama",
        "genre%5B%5D=fantasi&status=&tipe=&urutan=update" to "Fantasi",
        "genre%5B%5D=isekai&status=&tipe=&urutan=update" to "Isekai",
        "genre%5B%5D=romansa&status=&tipe=&urutan=update" to "Romansa",
        "genre%5B%5D=sci-fi&status=&tipe=&urutan=update" to "Sci-Fi",
        "genre%5B%5D=supranatural&status=&tipe=&urutan=update" to "Supranatural",
        "genre%5B%5D=donghua&status=&tipe=&urutan=update" to "Donghua",
    )

    private fun animasuHeaders(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
        )
    }

    private suspend fun getAnimasuDocument(url: String, referer: String = mainUrl) = app.get(
        url,
        referer = referer,
        headers = animasuHeaders(referer),
    ).document

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = getAnimasuDocument("$mainUrl/pencarian/?${request.data}&halaman=$page")

        val home = document.select("div.listupd div.bs")
            .mapNotNull { it.toSearchResultOrNull() }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        return getAnimasuDocument("$mainUrl/?s=$encodedQuery")
            .select("div.listupd div.bs")
            .mapNotNull { it.toSearchResultOrNull() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getAnimasuDocument(url)

        val title = document.selectFirst("div.infox h1")
            ?.text()
            .orEmpty()
            .replace("Sub Indo", "")
            .trim()
            .ifBlank { document.selectFirst("h1")?.text()?.trim().orEmpty() }

        val poster = document.selectFirst("div.bigcontent img")?.getImageAttr()
            ?: document.selectFirst(".thumb img, .poster img, img.wp-post-image")?.getImageAttr()

        val table = document.selectFirst("div.infox div.spe")
        val type = getType(table?.selectFirst("span:contains(Jenis:)")?.ownText())
        val year = table?.selectFirst("span:contains(Rilis:)")
            ?.ownText()
            ?.substringAfterLast(",")
            ?.trim()
            ?.toIntOrNull()
        val status = table?.selectFirst("span:contains(Status:) font")?.text()
        val trailer = document.selectFirst("div.trailer iframe")?.attr("src")

        val episodes = document.select("ul#daftarepisode > li").mapNotNull { item ->
            val aTag = item.selectFirst("a") ?: return@mapNotNull null
            val link = fixUrl(aTag.attr("href"))
            val name = aTag.text().trim()
            val episode = Regex("Episode\\s?(\\d+)")
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            newEpisode(link) {
                this.name = name.takeIf { it.isNotBlank() }
                this.episode = episode
            }
        }.reversed().ifEmpty {
            if (type == TvType.AnimeMovie) {
                listOf(newEpisode(url) { this.name = title })
            } else {
                emptyList()
            }
        }

        val rawTags = table?.select("span:contains(Genre:) a")
            ?.map { it.text().trim() }
            ?: emptyList()

        return newAnimeLoadResponse(
            title,
            url,
            type
        ) {
            posterUrl = poster
            this.year = year

            addEpisodes(
                DubStatus.Subbed,
                episodes
            )

            showStatus = getStatus(status)
            plot = document.select("div.sinopsis p").text()
            this.tags = rawTags.map { tag ->
                AnimasuTagCategory.getCategoryByTag(tag)
            }.distinct()
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = fixUrl(data)
        val document = app.get(
            episodeUrl,
            referer = mainUrl,
            headers = animasuHeaders(mainUrl)
        ).document

        val candidates = linkedSetOf<Pair<String, String?>>()
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()

        fun addCandidate(value: String?, label: String? = "Animasu") {
            if (value.isNullOrBlank()) return
            decodeServerUrls(value).forEach { candidate ->
                candidates.add(candidate to label)
            }
        }

        document.select("#pembed iframe[src], .player-embed iframe[src]").forEach { iframe ->
            addCandidate(iframe.attr("abs:src").ifBlank { iframe.attr("src") }, "Default")
        }

        document.select(".mobius > .mirror > option, .mobius select.mirror option").forEach { option ->
            addCandidate(option.attr("value"), option.text())
        }

        document.select("iframe[src], iframe[data-src], embed[src], source[src], video[src]").forEach { element ->
            addCandidate(
                element.attr("data-src")
                    .ifBlank { element.attr("abs:src") }
                    .ifBlank { element.attr("src") },
                "Animasu"
            )
        }

        document.select("select option[value], option[value]").forEach { option ->
            addCandidate(option.attr("value"), option.text().trim().ifBlank { "Animasu" })
        }

        document.select("[data-src], [data-lazy-src], [data-url], [data-link], [data-video], [data-embed], [data-player], [data-file]").forEach { element ->
            val label = element.text().trim().ifBlank { "Animasu" }
            addCandidate(element.attr("data-src"), label)
            addCandidate(element.attr("data-lazy-src"), label)
            addCandidate(element.attr("data-url"), label)
            addCandidate(element.attr("data-link"), label)
            addCandidate(element.attr("data-video"), label)
            addCandidate(element.attr("data-embed"), label)
            addCandidate(element.attr("data-player"), label)
            addCandidate(element.attr("data-file"), label)
        }

        extractKnownVideoUrls(document.html()).forEach { candidates.add(it to "Animasu") }

        val countedCallback: (ExtractorLink) -> Unit = { link ->
            if (emitted.add(link.url)) callback(link)
        }

        val topLevelCandidates = candidates
            .mapNotNull { (url, label) -> normalizeAnyUrl(url, episodeUrl)?.let { it to label } }
            .filterNot { (url, _) -> isNoiseFrame(url) }
            .distinctBy { it.first }
            .take(MAX_TOP_LEVEL_CANDIDATES)

        for ((url, label) in topLevelCandidates) {
            try {
                resolveVideoCandidate(
                    url = url,
                    label = label,
                    referer = episodeUrl,
                    visited = visited,
                    subtitleCallback = subtitleCallback,
                    callback = countedCallback,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w("Animasu", "Failed resolving server: $url", error)
            }
        }

        if (emitted.isEmpty()) {
            val downloadCandidates = document.select("a[href*='mirror'], a[href*='drive'], a[href*='pixeldrain'], a[href*='mp4'], a[href*='m3u8'], .download a[href], .download-eps a[href], .dlbox a[href], .soraddlx a[href], .soraurlx a[href], .entry-content a[href]")
                .mapNotNull { element ->
                    element.attr("abs:href").ifBlank { element.attr("href") }
                        .takeIf { it.isNotBlank() }
                        ?.let { normalizeAnyUrl(it, episodeUrl) }
                }
                .filterNot { isNoiseFrame(it) }
                .distinct()
                .take(MAX_DOWNLOAD_CANDIDATES)

            for (url in downloadCandidates) {
                try {
                    resolveVideoCandidate(
                        url = url,
                        label = "Download",
                        referer = episodeUrl,
                        visited = visited,
                        subtitleCallback = subtitleCallback,
                        callback = countedCallback,
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.w("Animasu", "Failed resolving download: $url", error)
                }
            }
        }

        return emitted.isNotEmpty()
    }

    private suspend fun resolveVideoCandidate(
        url: String,
        label: String?,
        referer: String,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0,
    ) {
        val fixed = normalizeAnyUrl(url, referer)
            ?.replace(".txt", ".m3u8")
            ?: return

        if (visited.size >= MAX_VISITED_LINKS || !visited.add(fixed) || isNoiseFrame(fixed)) return

        val sourceLabel = label?.takeIf { it.isNotBlank() } ?: "Animasu"
        val labelQuality = getIndexQuality(sourceLabel)
        val urlQuality = getIndexQuality(fixed)
        val directQuality = when {
            labelQuality != Qualities.Unknown.value -> labelQuality
            urlQuality != Qualities.Unknown.value -> urlQuality
            else -> Qualities.Unknown.value
        }

        when {
            fixed.contains("blogger.com/video.g", ignoreCase = true) -> {
                if (emitBloggerVideo(fixed, sourceLabel, referer, callback)) return
            }
            fixed.contains(".m3u8", true) -> {
                M3u8Helper.generateM3u8(
                    sourceLabel,
                    fixed,
                    referer = referer,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer),
                ).forEach(callback)
                return
            }
            fixed.contains(".mp4", true) || fixed.contains(".webm", true) -> {
                callback(
                    newExtractorLink(
                        sourceLabel,
                        sourceLabel,
                        fixed,
                        ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = referer
                        this.quality = directQuality
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
                    }
                )
                return
            }
        }

        if (shouldUseExtractor(fixed)) {
            try {
                if (loadFixedExtractor(fixed, sourceLabel, referer, subtitleCallback, callback)) return
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
            }
        }

        if (depth >= MAX_RESOLVE_DEPTH || !shouldReadNestedPage(fixed)) return

        val response = runCatching {
            app.get(
                fixed,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
            )
        }.getOrNull() ?: return

        val contentType = response.headers["Content-Type"].orEmpty().lowercase()
        val contentLength = response.headers["Content-Length"]?.toLongOrNull()
        if (shouldSkipBodyRead(contentType, contentLength)) return

        val body = runCatching { response.text.cleanEscaped() }.getOrNull() ?: return
        val nested = linkedSetOf<String>()
        nested.addAll(extractKnownVideoUrls(body))

        val nestedDocument = Jsoup.parse(body, fixed)
        nestedDocument.select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href], [data-src], [data-url], [data-link], [data-video], [data-embed], [data-player], [data-file]").forEach { element ->
            element.attr("data-src")
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-link") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-player") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("abs:src") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("abs:href") }
                .ifBlank { element.attr("href") }
                .takeIf { it.isNotBlank() }
                ?.let { decodeServerUrls(it) }
                ?.forEach { candidate -> normalizeAnyUrl(candidate, fixed)?.let { nested.add(it) } }
        }

        val nestedCandidates = nested.asSequence()
            .filterNot { isNoiseFrame(it) }
            .distinct()
            .take(MAX_NESTED_CANDIDATES)
            .toList()

        for (nestedUrl in nestedCandidates) {
            resolveVideoCandidate(
                url = nestedUrl,
                label = sourceLabel,
                referer = fixed,
                visited = visited,
                subtitleCallback = subtitleCallback,
                callback = callback,
                depth = depth + 1,
            )
        }
    }

    private fun decodeServerUrls(value: String): List<String> {
        val decodedValues = linkedSetOf<String>()
        val cleanValue = value.trim().htmlUnescape().cleanEscaped()
        if (cleanValue.isBlank()) return emptyList()

        decodedValues.add(cleanValue)
        runCatching { URLDecoder.decode(cleanValue, "UTF-8") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { decodedValues.add(it.htmlUnescape().cleanEscaped()) }

        decodeBase64Value(cleanValue)
            ?.takeIf { it.isNotBlank() }
            ?.let { decodedValues.add(it.htmlUnescape().cleanEscaped()) }

        val results = linkedSetOf<String>()
        decodedValues.forEach { decoded ->
            val beforeCount = results.size
            val parsed = Jsoup.parse(decoded)
            parsed.select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href]").forEach { element ->
                element.attr("data-src")
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("href") }
                    .takeIf { it.isNotBlank() }
                    ?.let(results::add)
            }
            extractKnownVideoUrls(decoded).forEach(results::add)
            if (results.size == beforeCount && looksLikeVideoCandidate(decoded)) results.add(decoded)
        }

        return results.toList()
    }

    private fun decodeBase64Value(value: String): String? {
        val normalized = value.trim()
        if (normalized.length < 8) return null

        return runCatching { base64Decode(normalized) }.getOrNull()
            ?: runCatching {
                val fixed = normalized
                    .replace('-', '+')
                    .replace('_', '/')
                    .let { raw ->
                        val padding = (4 - raw.length % 4) % 4
                        raw + "=".repeat(padding)
                    }

                String(Base64.decode(fixed, Base64.DEFAULT))
            }.getOrNull()
    }

    private fun looksLikeVideoCandidate(value: String): Boolean {
        val cleaned = value.cleanEscaped().trim()
        val lower = cleaned.lowercase()
        return cleaned.startsWith("http://", true) ||
            cleaned.startsWith("https://", true) ||
            cleaned.startsWith("//") ||
            cleaned.startsWith("/") ||
            lower.contains("<iframe") ||
            lower.contains("<source") ||
            lower.contains("<video") ||
            isDirectMediaUrl(lower) ||
            supportedHosts.any { lower.contains(it, ignoreCase = true) }
    }

    private fun extractKnownVideoUrls(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()

        val decodedText = rawText.cleanEscaped()
        val urls = linkedSetOf<String>()

        Jsoup.parse(decodedText).select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href]").forEach { element ->
            element.attr("data-src")
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .takeIf { it.isNotBlank() }
                ?.let { normalizeKnownVideoUrl(it) }
                ?.let { urls.add(it) }
        }

        Regex("""https?:\\?/\\?/[^\"'<>\\\s]+""", RegexOption.IGNORE_CASE)
            .findAll(decodedText)
            .mapNotNull { normalizeKnownVideoUrl(it.value) }
            .forEach { urls.add(it) }

        Regex("""(?i)(?:file|url|src|embed|video|videoUrl|video_url|hls|hlsUrl|embedUrl|embed_url|source)\s*[:=]\s*[\"']([^\"']+)[\"']""")
            .findAll(decodedText)
            .mapNotNull { normalizeKnownVideoUrl(it.groupValues[1]) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun normalizeKnownVideoUrl(url: String): String? {
        val absolute = normalizeAnyUrl(url, mainUrl) ?: return null
        return absolute
            .takeIf { candidate -> supportedHosts.any { candidate.contains(it, ignoreCase = true) } || isDirectMediaUrl(candidate) }
            ?.let { fixUrl(it) }
    }

    private fun normalizeAnyUrl(url: String, baseUrl: String): String? {
        val fixed = url.cleanEscaped().trim('"', '\'', ' ', '\n', '\r', '\t')
        if (fixed.isBlank() || fixed == "#" || fixed.startsWith("javascript:", true)) return null

        return when {
            fixed.startsWith("//") -> "https:$fixed"
            fixed.startsWith("http://", true) || fixed.startsWith("https://", true) -> fixed
            fixed.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: mainUrl
                origin.trimEnd('/') + fixed
            }
            else -> runCatching { URI(baseUrl).resolve(fixed).toString() }.getOrNull()
        }
    }

    private fun shouldUseExtractor(url: String): Boolean {
        val value = url.lowercase()
        return supportedHosts.any { host -> value.contains(host) }
    }

    private fun shouldReadNestedPage(url: String): Boolean {
        val value = url.lowercase()
        return Regex("""^https?://[^/]*animasu\.work/""").containsMatchIn(value) ||
            value.contains("/embed", true) ||
            value.contains("/player", true) ||
            value.contains("/video", true) ||
            value.contains("/v/", true) ||
            value.contains("archivd.net", true) ||
            value.contains("new.uservideo.xyz", true) ||
            value.contains("mirrored.to", true) ||
            value.contains("apk.miuiku.com", true)
    }

    private fun shouldSkipBodyRead(contentType: String, contentLength: Long?): Boolean {
        return contentType.startsWith("video/") ||
            contentType.startsWith("audio/") ||
            contentType.contains("octet-stream") ||
            contentType.contains("application/vnd.apple.mpegurl") ||
            contentType.contains("application/x-mpegurl") ||
            contentType.contains("mpegurl") ||
            (contentLength != null && contentLength > MAX_NESTED_TEXT_BYTES)
    }

    private fun isNoiseFrame(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() ||
            value.startsWith("#") ||
            value.startsWith("javascript") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("youtube.com") ||
            value.contains("youtu.be") ||
            value.contains("trailer") ||
            value.contains("banner") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("analytics") ||
            value.contains("tracking") ||
            value.contains("popads") ||
            value.contains("/wp-content/themes/", true) ||
            value.contains("/wp-content/plugins/", true)
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true)
    }

    private fun String.cleanEscaped(): String {
        return this
            .htmlUnescape()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u003D", "=")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")
            .trim()
    }

    private fun String.htmlUnescape(): String {
        return this
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private val supportedHosts = listOf(
        "archivd.net",
        "new.uservideo.xyz",
        "uservideo",
        "vidhidepro.com",
        "vidhide",
        "blogger.com/video.g",
        "googlevideo.com/videoplayback",
        "dailymotion.com",
        "geo.dailymotion.com",
        "dai.ly",
        "ok.ru",
        "odnoklassniki.ru",
        "rumble.com",
        "streamruby.com",
        "streamruby.net",
        "rubyvidhub.com",
        "vidguard",
        "filelions",
        "streamwish",
        "wishfast",
        "dood",
        "d000d",
        "filemoon",
        "streamtape",
        "mixdrop",
        "voe.sx",
        "mp4upload",
        "pixeldrain.com",
        "mirrored.to",
        "apk.miuiku.com",
    )

    private suspend fun emitBloggerVideo(
        url: String,
        quality: String?,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videos = extractBloggerDirectVideos(url, referer)
        if (videos.isEmpty()) return false

        videos.forEach { videoUrl ->
            callback.invoke(
                newExtractorLink(
                    "Blogger",
                    "Blogger ${quality.orEmpty()}".trim(),
                    videoUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://www.blogger.com/"
                    this.quality = qualityFromBloggerUrl(videoUrl).takeIf { it != Qualities.Unknown.value }
                        ?: getIndexQuality(quality)
                    this.headers = mapOf("Referer" to "https://www.blogger.com/")
                }
            )
        }

        return true
    }

    private suspend fun extractBloggerDirectVideos(url: String, referer: String): List<String> {
        val token = Regex("""[?&]token=([^&]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val page = runCatching {
            app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
        }.getOrNull() ?: return emptyList()

        val html = page.text
        val cookies = page.cookies
        val fSid = Regex("""FdrFJe":"(-?\d+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()
        val bl = Regex("""cfb2h":"([^"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()
        val hl = Regex("""lang="([^"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.ifBlank { null }
            ?: "id"

        val rpcId = "WcwnYd"
        val reqId = (System.currentTimeMillis() % 90000L + 10000L).toString()
        val payload = """[[["$rpcId","[\"$token\",null,0]",null,"generic"]]]"""
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute" +
            "?rpcids=$rpcId&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=$hl&_reqid=$reqId&rt=c"

        val response = runCatching {
            app.post(
                apiUrl,
                data = mapOf("f.req" to payload),
                referer = url,
                cookies = cookies,
                headers = mapOf(
                    "Origin" to "https://www.blogger.com",
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "X-Same-Domain" to "1"
                )
            ).text
        }.getOrNull() ?: return emptyList()

        val decoded = decodeBloggerEscapes(response)

        return Regex("""https://[^\s"'\\]+""")
            .findAll(decoded)
            .map { it.value }
            .filter { it.contains("googlevideo.com/videoplayback", ignoreCase = true) }
            .map { decodeBloggerEscapes(it) }
            .distinct()
            .toList()
    }

    private fun decodeBloggerEscapes(input: String): String {
        var output = input
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }

        return output
            .replace("\\/", "/")
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\=", "=")
            .replace("\\&", "&")
            .replace("\\\"", "\"")
    }

    private fun qualityFromBloggerUrl(url: String): Int {
        val itag = Regex("""[?&]itag=(\d+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return when (itag) {
            37, 96, 137, 248, 299 -> Qualities.P1080.value
            22, 59, 136, 247, 298 -> Qualities.P720.value
            18, 134, 244 -> Qualities.P360.value
            135 -> Qualities.P480.value
            36 -> Qualities.P240.value
            17 -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: String?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false

        loadExtractor(
            url,
            referer,
            subtitleCallback
        ) { link ->
            emitted = true
            callback.invoke(
                newExtractorLink(
                    link.name,
                    link.name,
                    link.url,
                    link.type
                ) {
                    this.referer = link.referer
                    this.quality =
                        if (
                            link.type == ExtractorLinkType.M3U8 ||
                            link.name == "Uservideo"
                        ) {
                            link.quality
                        } else {
                            getIndexQuality(quality)
                        }
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }

        return emitted
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]")
            .find(str ?: "")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element.toSearchResultOrNull(): AnimeSearchResponse? {
        val rawHref = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val href = getProperAnimeLink(rawHref)
        val title = this.select("div.tt").text().trim()
            .ifBlank { this.selectFirst("a")?.attr("title")?.trim().orEmpty() }
        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())
        val epNum = this.selectFirst("span.epx")
            ?.text()
            ?.filter { it.isDigit() }
            ?.toIntOrNull()

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> title.substringBefore("-episode")
                (title.contains("-movie")) -> title.substringBefore("-movie")
                else -> title
            }
            "$mainUrl/anime/$title"
        }
    }
}

enum class AnimasuTagCategory(val title: String, val tagsList: List<String>) {
    ACTION_ADVENTURE("Action & Adventure", listOf("Action", "Adventure", "Martial Arts", "Samurai", "Super Power", "Survival", "Military")),
    COMEDY("Comedy", listOf("Comedy", "Gag Humor", "Parody")),
    DRAMA_ROMANCE("Drama & Romance", listOf("Drama", "Romance", "Boys Love", "Girls Love", "School")),
    FANTASY_SCIFI("Fantasy & Sci-Fi", listOf("Fantasy", "Sci-Fi", "Supernatural", "Isekai", "Magic", "Demons", "Vampire", "Mecha", "Space", "Time Travel", "Mythology")),
    MYSTERY_HORROR("Mystery & Horror", listOf("Mystery", "Thriller", "Suspense", "Detective", "Police", "Psychological", "Horror", "Gore")),
    SLICE_OF_LIFE("Slice of Life", listOf("Slice of Life", "Iyashikei", "Kids", "Workplace")),
    SPORTS_GAMES("Sports & Games", listOf("Sports", "Racing", "Strategy Game", "Game")),
    ARTS_CULTURE("Arts & Music", listOf("Music", "Idol", "Historical", "Performing Arts")),
    MATURE("Mature & Ecchi", listOf("Ecchi", "Harem", "Reverse Harem")),
    DEMOGRAPHICS("Demographics", listOf("Shounen", "Shoujo", "Seinen", "Josei")),
    MC_PERSONALITY_GOOD("MC: Kepribadian Baik", listOf("Ambisi", "Berjuang", "Beruntung", "Blakblakan", "Ceria", "Jenius", "Optimis", "Pemimpin", "Polos", "Semangat", "Setia", "Sopan", "Totalitas")),
    MC_PERSONALITY_QUIRKY("MC: Sifat Negatif/Eksentrik", listOf("Anti-Sosial", "Berisik", "Cerewet", "Ceroboh", "Kejam", "Licik", "Mencolok", "Menyebalkan", "Mesum", "Narsis", "Pemalas", "Pemalu", "Penakut", "Pendiam", "Pesimis", "Slengekan", "Suram")),
    MC_IDENTITY("MC: Identitas & Profesi", listOf("Anak-Anak", "Berbisnis", "Bounty Hunter", "Cewek", "Cowok", "Dewa", "Iblis", "Loli", "Monster", "Vampir")),
    MC_TROPE("MC: Trope Anime", listOf("Badass", "Couple", "Dikagumi", "Disepelekan", "Ditakuti", "Legenda", "Overpower", "Terkutuk", "Tsundere", "Yandere", "Zero To Hero"));

    companion object {
        fun getCategoryByTag(tag: String): String {
            return entries.find { category ->
                category.tagsList.any { it.equals(tag, ignoreCase = true) }
            }?.title ?: tag
        }
    }
}
