package it.dogior.nsfw

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.random.Random

class Archivebate : MainAPI() {
    override var mainUrl = URL
    override var name = "Archivebate"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasQuickSearch = true
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Videos",
        platform("eW91dHViZQ==") to "YouTube",
        platform("dHdpdGNo") to "Twitch",
        platform("b25seWZhbnM=") to "OnlyFans",
        platform("aW5zdGFncmFt") to "Instagram",
        platform("dGlrdG9r") to "TikTok",
        platform("Ym9uZ2FjYW1z") to "BongaCams",
        platform("Y2FtNA==") to "Cam4",
        platform("Y2Ftc29kYQ==") to "Camsoda",
        platform("Y2hhdHVyYmF0ZQ==") to "Chaturbate",
        platform("c3RyaXBjaGF0") to "Stripchat",
        platform("ZmVtYWxl") to "Female",
        platform("Y291cGxl") to "Couple",
        platform("bWFsZQ==") to "Male",
        platform("dHJhbnM=") to "Trans",
    )

    companion object {
        const val URL = "https://archivebate.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
        private const val PAGE_SIZE_GUARD = 20
        private val durationMap = mutableMapOf<String, String>()
        private val infoMap = mutableMapOf<String, String>()
        private val profilePosterCache = mutableMapOf<String, String?>()

        private fun platform(encoded: String): String = "$URL/platform/$encoded"
    }

    private fun requestHeaders(referer: String = mainUrl): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7",
    )

    private fun livewireHeaders(referer: String, csrf: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/json",
        "X-CSRF-TOKEN" to csrf,
        "X-Livewire" to "true",
        "X-Requested-With" to "XMLHttpRequest",
    )

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun cleanHtml(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return Jsoup.parse(html).text().trim()
    }

    private fun strToMin(duration: String?): Int? {
        if (duration.isNullOrBlank()) return null
        val parts = duration.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] + parts[1] / 60
            3 -> parts[0] * 60 + parts[1] + parts[2] / 60
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pageUrl = rewriteArchivebatePage(request.data, page)
        val items = runCatching { getLivewireCards(pageUrl) }
            .getOrElse { error ->
                Log.e("Archivebate", "Livewire homepage failed for ${request.name}: ${error.message.orEmpty()}")
                emptyList()
            }
            .ifEmpty {
                runCatching {
                    parseHtmlListing(app.get(pageUrl, headers = requestHeaders(pageUrl)).document)
                }.getOrElse { error ->
                    Log.e("Archivebate", "HTML homepage fallback failed for ${request.name}: ${error.message.orEmpty()}")
                    emptyList()
                }
            }

        if (items.isEmpty()) return null

        return newHomePageResponse(
            HomePageList(request.name, items, true),
            items.size >= PAGE_SIZE_GUARD,
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val apiItems = runCatching { searchProfiles(query) }.getOrElse { error ->
            Log.e("Archivebate", "Profile search failed: ${error.message.orEmpty()}")
            emptyList()
        }
        if (apiItems.isNotEmpty()) return apiItems

        val searchUrls = listOf(
            "$mainUrl/search/${encode(query)}/",
            "$mainUrl/?search=${encode(query)}",
            "$mainUrl/?q=${encode(query)}",
        )

        for (url in searchUrls) {
            val items = runCatching { getLivewireCards(url) }.getOrDefault(emptyList())
            val filtered = items.filter { response ->
                response.name.contains(query, ignoreCase = true) || response.url.contains(query, ignoreCase = true)
            }
            if (filtered.isNotEmpty()) return filtered
            if (items.isNotEmpty()) return items
        }

        val latest = runCatching { getLivewireCards("$mainUrl/") }.getOrDefault(emptyList())
        val filteredLatest = latest.filter { response ->
            response.name.contains(query, ignoreCase = true) || response.url.contains(query, ignoreCase = true)
        }
        return filteredLatest.ifEmpty { latest }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/profile/")) return getModelProfile(url)

        val data = getVideoData(url)
        return newMovieLoadResponse(data.title, url, TvType.NSFW, data.playData.ifBlank { url }) {
            this.plot = data.info.orEmpty().ifBlank { infoMap[url].orEmpty() }
            this.posterUrl = data.poster
            this.duration = strToMin(durationMap[url] ?: data.duration)
            if (!data.profileName.isNullOrBlank() && !data.profileUrl.isNullOrBlank()) {
                this.recommendations = listOf(
                    newMovieSearchResponse(data.profileName, data.profileUrl, TvType.NSFW) {
                        this.posterUrl = data.profilePoster
                    }
                )
            }
        }
    }

    private suspend fun getModelProfile(url: String): LoadResponse? {
        val model = url.substringAfterLast("/").replace("-", " ").trim().ifBlank { "Profile" }
        val items = search(model).take(20).map {
            newEpisode(it.url) {
                this.name = it.name
                this.posterUrl = it.posterUrl
                this.runTime = strToMin(durationMap[it.url])
            }
        }
        val modelPoster = getModelPoster(url)
        return newTvSeriesLoadResponse(model.replaceFirstChar { it.titlecase() }, url, TvType.NSFW, items) {
            this.posterUrl = modelPoster
            this.plot = "Latest videos"
        }
    }

    private suspend fun getModelPoster(url: String): String? {
        profilePosterCache[url]?.let { return it }
        if (profilePosterCache.containsKey(url)) return null

        val poster = runCatching {
            val photosPage = app.get("$url/photos", headers = requestHeaders(url)).document
            val photoUrl = photosPage.select("img.default_thumbnail, img[src]").mapNotNull { image ->
                image.absUrl("src").ifBlank { image.attr("src") }.ifBlank { null }
            }
            if (photoUrl.isEmpty()) null else photoUrl[Random.nextInt(0, photoUrl.size)]
        }.getOrNull()

        profilePosterCache[url] = poster
        return poster
    }

    private suspend fun searchProfiles(query: String): List<SearchResponse> {
        val boot = app.get(mainUrl, headers = requestHeaders())
        val body = app.get(
            "$mainUrl/api/v1/search?query=${encode(query)}",
            cookies = boot.cookies,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "Accept" to "application/json, text/plain, */*",
                "X-Requested-With" to "XMLHttpRequest",
            ),
        ).text

        val data = runCatching { JSONObject(body).optJSONArray("data") }
            .getOrNull()
            ?: runCatching { JSONArray(body) }.getOrNull()
            ?: return emptyList()

        return (0 until data.length()).mapNotNull { index ->
            val raw = data.opt(index) ?: return@mapNotNull null
            val item = when (raw) {
                is JSONObject -> raw
                is String -> JSONObject().put("username", raw)
                else -> return@mapNotNull null
            }
            val username = item.optString("username").ifBlank {
                item.optString("name").ifBlank { item.optString("title") }
            }.ifBlank { return@mapNotNull null }
            val profileUrl = item.optString("url")
                .ifBlank { item.optString("link") }
                .ifBlank { "$mainUrl/profile/$username" }
            val poster = item.optString("avatar")
                .ifBlank { item.optString("image") }
                .ifBlank { item.optString("poster") }
                .ifBlank { null }
            newMovieSearchResponse(username, normalizeUrl(profileUrl), TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }

    private suspend fun getLivewireCards(url: String): List<SearchResponse> {
        val fragment = fetchLivewireFragment(url) ?: return emptyList()
        return parseHtmlListing(Jsoup.parse(fragment, mainUrl))
    }

    private suspend fun fetchLivewireFragment(url: String): String? {
        val page = app.get(url, headers = requestHeaders(url))
        val finalUrl = url
        val baseUrl = mainUrl
        val doc = page.document
        val csrf = doc.selectFirst("meta[name='csrf-token']")?.attr("content")?.ifBlank { null } ?: return null

        val wire = doc.getAllElements().firstOrNull { it.hasAttr("wire:initial-data") } ?: return null

        val initialData = decodeWireData(wire.attr("wire:initial-data")).ifBlank { return null }
        val method = wire.attr("wire:init").ifBlank { defaultLivewireMethod(url) }
        val state = JSONObject(initialData)
        val fingerprint = state.getJSONObject("fingerprint")
        val serverMemo = state.getJSONObject("serverMemo")
        val component = fingerprint.getString("name")

        val payload = JSONObject()
            .put("fingerprint", fingerprint)
            .put("serverMemo", serverMemo)
            .put(
                "updates",
                JSONArray().put(
                    JSONObject()
                        .put("type", "callMethod")
                        .put(
                            "payload",
                            JSONObject()
                                .put("id", "lw1")
                                .put("method", method)
                                .put("params", JSONArray()),
                        ),
                ),
            )

        val livewireUrl = "$baseUrl/livewire/message/$component"
        val response = app.post(
            livewireUrl,
            cookies = page.cookies,
            headers = livewireHeaders(finalUrl, csrf),
            requestBody = payload.toString().toRequestBody("application/json".toMediaType()),
        ).text

        return JSONObject(response).optJSONObject("effects")?.optString("html")?.takeIf { it.isNotBlank() }
    }

    private fun decodeWireData(value: String): String {
        return value
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun defaultLivewireMethod(url: String): String {
        return if (url.contains("/platform/")) "load_platform_videos" else "loadVideos"
    }

    private fun rewriteArchivebatePage(url: String, page: Int): String {
        val cleanUrl = url.ifBlank { "$mainUrl/" }
        if (page <= 1) return cleanUrl

        val parts = cleanUrl.split("?", limit = 2)
        val base = parts.firstOrNull().orEmpty().trimEnd('/')
        val query = parts.getOrNull(1).orEmpty()
        val params = linkedMapOf<String, String>()

        query.split("&")
            .filter { it.isNotBlank() }
            .forEach { pair ->
                val key = pair.substringBefore("=").trim()
                val value = pair.substringAfter("=", "").trim()
                if (key.isNotBlank() && key != "page") params[key] = value
            }

        params["page"] = page.toString()
        val newQuery = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        return if (newQuery.isNotBlank()) "$base?$newQuery" else "$base?page=$page"
    }

    private fun parseHtmlListing(doc: Document): List<SearchResponse> {
        val candidates = doc.select("section.video_item, article, .video_item, .post")
        return candidates.mapNotNull { item -> parseHtmlCard(item) }.distinctBy { it.url }
    }

    private fun parseHtmlCard(item: Element): SearchResponse? {
        val linkElement = item.selectFirst("a[href*='/watch/'], h2.entry-title a, h3.entry-title a, a[rel=bookmark]") ?: return null
        val link = linkElement.absUrl("href").ifBlank { normalizeUrl(linkElement.attr("href")) }
        if (link.isBlank() || !link.startsWith("http")) return null

        val profile = item.selectFirst("a[href*='/profile/']")
        val rawTitle = listOfNotNull(
            profile?.text(),
            item.selectFirst(".title, .entry-title")?.text(),
            item.select("div.info.d-flex > div").lastOrNull()?.text(),
            linkElement.text(),
        ).firstOrNull { it.isNotBlank() }
        val title = rawTitle?.trim()?.ifBlank { null } ?: link.substringAfterLast("/").replace("-", " ").ifBlank { "Archivebate Video" }

        val poster = item.selectFirst("video.video-splash-mov[poster], video[poster], img[data-src], img[src]")?.let { media ->
            media.absUrl("poster")
                .ifBlank { media.absUrl("data-src") }
                .ifBlank { media.absUrl("src") }
                .ifBlank { media.attr("poster") }
                .ifBlank { media.attr("data-src") }
                .ifBlank { media.attr("src") }
                .ifBlank { null }
        }
        val duration = item.selectFirst(".duration, .video-duration, div.duration.text-white > span")?.text().orEmpty()
        if (duration.isNotBlank()) durationMap[link] = duration
        return newMovieSearchResponse(title, link, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private suspend fun getVideoData(url: String): VideoInfo {
        val doc = app.get(url, headers = requestHeaders(url)).document
        val title = doc.selectFirst("meta[property='og:title'], meta[name='twitter:title']")?.attr("content")
            ?.substringBefore(" - ")
            ?.ifBlank { null }
            ?: doc.selectFirst("h1, .entry-title, title")?.text()?.substringBefore(" - ")?.ifBlank { null }
            ?: "Archivebate Video"
        val info = doc.selectFirst("meta[name='description'], meta[property='og:description']")?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst(".entry-content, .entry-summary, .info")?.text().orEmpty()
        val poster = doc.selectFirst("meta[property='og:image'], meta[name='twitter:image']")?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst("div.player")?.attr("style")?.substringAfter("url(")?.substringBefore(")")?.trim('"', '\'', ' ')
            ?: doc.selectFirst("video[poster], img[src]")?.let { media -> media.absUrl("poster").ifBlank { media.absUrl("src") } }
        val playData = extractPlayableCandidates(doc, url).firstOrNull().orEmpty()
        val profile = doc.selectFirst("a[href*='/profile/']")
        val profileUrl = profile?.absUrl("href")?.ifBlank { null }
        val profilePoster = if (poster.isNullOrBlank() && !profileUrl.isNullOrBlank()) {
            getModelPoster(profileUrl)
        } else {
            null
        }
        return VideoInfo(
            title = title,
            playData = playData,
            info = info,
            poster = poster,
            duration = Regex("\\b(\\d{1,2}:\\d{2}(?::\\d{2})?)\\b").find(doc.text())?.groupValues?.getOrNull(1),
            profileName = profile?.text()?.ifBlank { null },
            profileUrl = profileUrl,
            profilePoster = profilePoster,
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val candidates = if (data.startsWith(mainUrl)) {
            val doc = app.get(data, headers = requestHeaders(data)).document
            extractPlayableCandidates(doc, data)
        } else {
            listOf(data)
        }

        var emitted = false
        for (candidate in candidates.map { normalizeUrl(it) }.distinct()) {
            if (candidate.isBlank()) continue

            val referer = determineRefererForHost(candidate, data)
            emitted = loadExtractor(candidate, referer = referer, subtitleCallback, callback) || emitted
        }

        if (!emitted) Log.e("Archivebate", "No extractor callback emitted for: $data")
        return emitted
    }

    private fun determineRefererForHost(candidate: String, detailPage: String): String {
        val normalized = normalizeUrl(candidate).lowercase()
        val archivebateHost = mainUrl.removePrefix("https://").removePrefix("http://")
        val safeDetailPage = detailPage.takeIf { it.startsWith("http") } ?: mainUrl

        return when {
            normalized.contains(archivebateHost) -> safeDetailPage
            safeDetailPage.startsWith(mainUrl) -> safeDetailPage
            else -> mainUrl
        }
    }

    private fun extractPlayableCandidates(doc: Document, referer: String): List<String> {
        val candidates = mutableListOf<String>()

        candidates += extractDirectMedia(doc)
        candidates += extractPlayerIframes(doc)
        candidates += extractScriptMedia(doc)

        if (candidates.isEmpty()) {
            doc.selectFirst("iframe.video-frame[src], iframe[src]")
                ?.absUrl("src")
                ?.ifBlank { doc.selectFirst("iframe.video-frame[src], iframe[src]")?.attr("src").orEmpty() }
                ?.let { normalizeUrl(it) }
                ?.takeIf { it.isNotBlank() }
                ?.let { candidates += it }
        }

        return candidates
            .map { normalizeUrl(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf(referer) }
    }

    private fun extractDirectMedia(doc: Document): List<String> {
        return doc.select("video[src], source[src]").mapNotNull { media ->
            val src = media.absUrl("src").ifBlank { media.attr("src") }
            normalizeUrl(src).takeIf { isDirectMediaUrl(it) }
        }
    }

    private fun extractPlayerIframes(doc: Document): List<String> {
        return doc.select("iframe.video-frame[src], iframe[src]").mapNotNull { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
            normalizeUrl(src).takeIf { isPlayableIframe(it) }
        }
    }

    private fun extractScriptMedia(doc: Document): List<String> {
        val candidates = mutableListOf<String>()

        doc.select("script[src], script").forEach { script: Element ->
            val html = script.outerHtml()

            candidates += Regex("""https?:\\?/\\?/[^\"'\\s<>]+?\.(?:m3u8|mp4)(?:[^\"'\\s<>]*)""")
                .findAll(html)
                .map { normalizeUrl(it.value) }
                .toList()

            candidates += Regex("""(?:file|src|url)\s*[:=]\s*[\"']([^\"']+?\.(?:m3u8|mp4)[^\"']*)[\"']""")
                .findAll(html)
                .map { match -> normalizeUrl(match.groupValues.getOrNull(1).orEmpty()) }
                .filter { isDirectMediaUrl(it) }
                .toList()
        }

        return candidates.distinct()
    }

    private fun normalizeUrl(url: String): String {
        val cleaned = url
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim('"', '\'', ' ', '\n', '\t')

        return when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "$mainUrl$cleaned"
            else -> cleaned
        }
    }

    private fun isDirectMediaUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val normalized = normalizeUrl(url).lowercase()
        return Regex("""\.(?:m3u8|mp4)(?:[?#].*)?$""").containsMatchIn(normalized)
    }

    private fun isPlayableIframe(url: String): Boolean {
        val normalized = normalizeUrl(url).lowercase()
        return listOf(
            "mixdrop", "streamtape", "dood", "voe.sx", "filemoon",
            "mp4upload", "streamlare", "vidhide", "player", "embed",
        ).any { normalized.contains(it) }
    }

    data class VideoInfo(
        val title: String,
        val playData: String,
        val info: String?,
        val poster: String?,
        val duration: String?,
        val profileName: String?,
        val profileUrl: String?,
        val profilePoster: String?,
    )
}
