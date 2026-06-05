package com.sad25kag.anizone

import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class AnizoneProvider : MainAPI() {

    override var mainUrl = "https://anizone.to"
    override var name = "AniZone"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
    )

    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "sort:release-desc" to "Rilis Terbaru",
        "sort:title-asc" to "A-Z",
        "sort:title-desc" to "Z-A",
        "sort:release-asc" to "Rilis Terlama",
        "sort:added-asc" to "Pertama Ditambahkan",
        "sort:added-desc" to "Terakhir Ditambahkan",
        "type:TV Series" to "TV Series",
        "type:OVA" to "OVA",
        "type:Movie" to "Movie",
        "type:Web" to "Web",
        "type:TV Special" to "TV Special",
        "type:Music Video" to "Music Video",
        "type:Other" to "Other",
        "type:Unknown" to "Unknown",
        "tag-name:Action" to "Aksi",
        "tag-name:Adventure" to "Petualangan",
        "tag-name:Comedy" to "Komedi",
        "tag-name:Fantasy" to "Fantasi",
        "tag-name:Manga" to "Manga",
        "tag-name:Novel" to "Novel",
        "tag-name:Romance" to "Romantis",
        "tag-name:Shounen" to "Shounen",
        "tag-name:Seinen" to "Seinen",
        "tag-name:School Life" to "Kehidupan Sekolah"
    )

    private val snapshotAnimeKey = "anime_snapshot_key"
    private val snapshotEpisodeKey = "episode_snapshot_key"
    private val snapshotVideoKey = "video_snapshot_key"

    private var token: String = ""
    private val snapshots = mutableMapOf(
        snapshotAnimeKey to "",
        snapshotEpisodeKey to "",
        snapshotVideoKey to ""
    )

    private var cookies = mutableMapOf<String, String>()
    private val tagPathCache = mutableMapOf<String, String>()

    private fun translateGenre(name: String): String {
        return when (name.trim().lowercase(Locale.ROOT)) {
            "action" -> "Aksi"
            "adventure" -> "Petualangan"
            "comedy" -> "Komedi"
            "daily life", "slice of life" -> "Kehidupan Sehari-hari"
            "fantasy" -> "Fantasi"
            "high school" -> "SMA"
            "historical" -> "Sejarah"
            "manga" -> "Manga"
            "novel" -> "Novel"
            "romance" -> "Romantis"
            "school life" -> "Kehidupan Sekolah"
            "seinen" -> "Seinen"
            "shounen" -> "Shounen"
            "sports" -> "Olahraga"
            "thriller" -> "Thriller"
            "tragedy" -> "Tragedi"
            "violence" -> "Kekerasan"
            else -> name.trim()
        }
    }

    private suspend fun initializeLiveWire(initialSlug: String = "/anime"): Boolean {
        if (!snapshots[snapshotAnimeKey].isNullOrBlank() && token.isNotBlank()) return true

        return try {
            val initReq = app.get("$mainUrl$initialSlug")
            val doc = initReq.document

            val csrfToken = doc.selectFirst("script[data-csrf]")
                ?.attr("data-csrf")
                ?.takeIf { it.isNotBlank() }
                ?: ""

            val snapshot = getSnapshot(doc, *componentNamesForPath(initialSlug))

            if (csrfToken.isBlank() || snapshot.isBlank()) {
                Log.e("AniZone Init", "Inisialisasi gagal: token atau snapshot kosong.")
                false
            } else {
                cookies = initReq.cookies.toMutableMap()
                token = csrfToken
                snapshots[snapshotAnimeKey] = snapshot
                true
            }
        } catch (e: Exception) {
            Log.e("AniZone Init", "Error initializeLiveWire: ${e.message}")
            false
        }
    }

    private fun getSnapshot(doc: Document, vararg componentNames: String): String {
        val snapshots = doc.getAllElements()
            .mapNotNull { element ->
                element.attr("wire:snapshot")
                    .takeIf { it.isNotBlank() }
                    ?.replace("&quot;", "\"")
                    ?.replace("&amp;quot;", "\"")
            }

        if (snapshots.isEmpty()) return ""

        if (componentNames.isNotEmpty()) {
            snapshots.firstOrNull { snapshot ->
                val name = snapshotComponentName(snapshot)
                componentNames.any { wanted -> name == wanted }
            }?.let { return it }
        }

        return snapshots.firstOrNull { snapshotComponentName(it).startsWith("pages.") }
            ?: snapshots.first()
    }

    private fun snapshotComponentName(snapshot: String): String {
        return Regex("""\"name\"\s*:\s*\"([^\"]+)\"""")
            .find(snapshot)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?: ""
    }

    private fun componentNamesForPath(path: String): Array<String> {
        val parts = pathFromUrl(path).split("/").filter { it.isNotBlank() }
        return when {
            parts.size >= 3 && parts.firstOrNull() == "anime" -> arrayOf("pages.episode-show")
            parts.size >= 2 && parts.firstOrNull() == "anime" -> arrayOf("pages.anime-detail")
            parts.firstOrNull() == "tag" -> arrayOf("pages.tag-show", "pages.tag-detail")
            else -> arrayOf("pages.anime-index")
        }
    }

    private fun getSnapshot(json: JSONObject): String {
        return json.getJSONArray("components")
            .getJSONObject(0)
            .getString("snapshot")
            .replace("\\\"", "\"")
    }

    private fun getHtmlFromWire(json: JSONObject): Document {
        return Jsoup.parse(
            json.getJSONArray("components")
                .getJSONObject(0)
                .getJSONObject("effects")
                .getString("html")
                .replace("\\\"", "\"")
                .replace("\\n", "")
        )
    }

    private suspend fun liveWireBuilder(
        snapshotKey: String,
        updates: Map<String, Any?>,
        calls: List<Map<String, Any?>>,
        biscuit: MutableMap<String, String>,
        remember: Boolean,
        refererPath: String = "/anime"
    ): JSONObject {
        if (token.isBlank() || snapshots[snapshotKey].isNullOrBlank()) {
            val initReq = app.get("$mainUrl$refererPath")
            val initDoc = initReq.document
            token = initDoc.selectFirst("script[data-csrf]")?.attr("data-csrf") ?: token
            snapshots[snapshotKey] = getSnapshot(initDoc, *componentNamesForPath(refererPath))
            biscuit.putAll(initReq.cookies)
        }

        val payload = mapOf(
            "_token" to token,
            "components" to listOf(
                mapOf(
                    "snapshot" to snapshots[snapshotKey],
                    "updates" to updates,
                    "calls" to calls
                )
            )
        )

        val jsonString = payload.toJson()
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = jsonString.toRequestBody(mediaType)

        val req = app.post(
            url = "$mainUrl/livewire/update",
            requestBody = requestBody,
            headers = mapOf(
                "X-CSRF-TOKEN" to token,
                "X-Livewire" to ""
            ),
            cookies = biscuit,
            referer = "$mainUrl$refererPath"
        )

        val bodyString = req.text

        if (bodyString.isBlank()) {
            throw Exception("Respons Livewire kosong. HTTP ${req.code}.")
        }

        if (
            bodyString.trim().startsWith("<!DOCTYPE", ignoreCase = true) ||
            bodyString.trim().startsWith("<html", ignoreCase = true)
        ) {
            throw Exception("Livewire tidak mengembalikan JSON. HTTP ${req.code}. URL: ${req.url}")
        }

        val responseJson = JSONObject(bodyString)

        if (remember) {
            snapshots[snapshotKey] = getSnapshot(responseJson)
            biscuit.putAll(req.cookies)
        }

        return responseJson
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            val data = request.data
            val path = when {
                data.startsWith("tag:") -> data.removePrefix("tag:")
                data.startsWith("tag-name:") -> resolveTagPath(data.removePrefix("tag-name:"))
                    ?: return emptyHomePage(request.name)
                else -> "/anime"
            }
            val sort = when {
                data.startsWith("sort:") -> data.removePrefix("sort:").ifBlank { "release-desc" }
                else -> "release-desc"
            }
            val type = when {
                data.startsWith("type:") -> data.removePrefix("type:").trim()
                else -> ""
            }

            buildLiveWireHomePage(path, sort, type, page, request.name)
        } catch (e: Exception) {
            Log.e("AniZone", "Gagal memproses getMainPage: ${e.message}")
            emptyHomePage(request.name)
        }
    }

    private suspend fun buildLiveWireHomePage(
        path: String,
        sort: String,
        type: String,
        page: Int,
        title: String
    ): HomePageResponse {
        var doc = try {
            fetchHomeDocument(path, sort, type, page)
        } catch (e: Exception) {
            if (type.isBlank()) throw e
            Log.e("AniZone", "Type filter Livewire gagal untuk '$type', fallback filter lokal: ${e.message}")
            fetchHomeDocument(path, sort, "", maxOf(page, 5))
        }

        var elements = parseAnimeElements(doc)
            .filter { type.isBlank() || cardMatchesType(it, type) }

        if (elements.isEmpty() && type.isNotBlank()) {
            doc = fetchHomeDocument(path, sort, "", maxOf(page, 5))
            elements = parseAnimeElements(doc).filter { cardMatchesType(it, type) }
        }

        val home = elements.map { toResult(it) }

        return if (home.isEmpty()) {
            emptyHomePage(title)
        } else {
            newHomePageResponse(
                HomePageList(title, home, isHorizontalImages = false),
                hasNext = hasLoadMore(doc)
            )
        }
    }

    private fun typeToSourceValue(type: String): String {
        return when (type.trim().lowercase(Locale.ROOT)) {
            "unknown" -> "1"
            "tv series" -> "2"
            "ova" -> "3"
            "movie" -> "4"
            "other" -> "5"
            "web" -> "6"
            "tv special" -> "7"
            "music video" -> "8"
            else -> type.trim()
        }
    }

    private suspend fun fetchHomeDocument(
        path: String,
        sort: String,
        type: String,
        page: Int
    ): Document {
        snapshots[snapshotAnimeKey] = ""

        val initialReq = app.get("$mainUrl$path")
        val initialDoc = initialReq.document
        val initialToken = initialDoc.selectFirst("script[data-csrf]")?.attr("data-csrf") ?: ""
        if (initialToken.isNotBlank()) token = initialToken
        cookies = initialReq.cookies.toMutableMap()
        snapshots[snapshotAnimeKey] = getSnapshot(initialDoc, *componentNamesForPath(path))

        var doc = initialDoc
        val needsLiveWireUpdate = sort != "release-desc" || type.isNotBlank()

        if (needsLiveWireUpdate && token.isNotBlank() && !snapshots[snapshotAnimeKey].isNullOrBlank()) {
            val updates = mutableMapOf<String, Any?>("sort" to sort)
            if (type.isNotBlank()) updates["type"] = typeToSourceValue(type)

            try {
                val responseJson = liveWireBuilder(
                    snapshotAnimeKey,
                    updates,
                    emptyList(),
                    cookies,
                    true,
                    path
                )
                val updatedDoc = getHtmlFromWire(responseJson)
                if (parseAnimeElements(updatedDoc).isNotEmpty()) {
                    doc = updatedDoc
                }
            } catch (e: Exception) {
                Log.e("AniZone", "Livewire update gagal untuk '$path' sort='$sort' type='$type': ${e.message}")
            }
        }

        for (i in 1 until page) {
            if (!hasLoadMore(doc) || token.isBlank() || snapshots[snapshotAnimeKey].isNullOrBlank()) break

            try {
                val responseJson = liveWireBuilder(
                    snapshotAnimeKey,
                    emptyMap(),
                    listOf(
                        mapOf(
                            "path" to "",
                            "method" to "loadMore",
                            "params" to emptyList<String>()
                        )
                    ),
                    cookies,
                    true,
                    path
                )

                val nextDoc = getHtmlFromWire(responseJson)
                if (parseAnimeElements(nextDoc).isEmpty()) break
                doc = nextDoc
            } catch (e: Exception) {
                Log.e("AniZone", "loadMore gagal untuk '$path': ${e.message}")
                break
            }
        }

        return doc
    }

    private suspend fun resolveTagPath(tagName: String): String? {
        val key = tagName.trim().lowercase(Locale.ROOT)
        if (key.isBlank()) return null
        tagPathCache[key]?.let { return it }

        return try {
            val doc = app.get("$mainUrl/tag").document
            for (link in doc.select("a[href*='/tag/']")) {
                val cleanName = link.text()
                    .replace(Regex("\\s+"), " ")
                    .substringBefore("(")
                    .trim()

                if (cleanName.equals(tagName.trim(), ignoreCase = true)) {
                    val path = pathFromUrl(normalizeUrl(link.attr("href")))
                    tagPathCache[key] = path
                    return path
                }
            }
            null
        } catch (e: Exception) {
            Log.e("AniZone", "Gagal resolve tag '$tagName': ${e.message}")
            null
        }
    }

    private fun emptyHomePage(name: String): HomePageResponse {
        return newHomePageResponse(
            HomePageList(name, emptyList(), isHorizontalImages = false),
            hasNext = false
        )
    }

    private fun hasLoadMore(doc: Document): Boolean {
        return doc.selectFirst("div[x-intersect~=loadMore], .h-12[x-intersect~=loadMore]") != null
    }

    private fun cardMatchesType(element: Element, type: String): Boolean {
        val normalizedType = type.trim().lowercase(Locale.ROOT)
        if (normalizedType.isBlank()) return true
        val ownText = element.text().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        return when (normalizedType) {
            "tv series" -> ownText.contains("tv series")
            "tv special" -> ownText.contains("tv special")
            "music video" -> ownText.contains("music video")
            "movie" -> ownText.contains("movie")
            "ova" -> ownText.contains("ova")
            "web" -> ownText.contains("web")
            "other" -> ownText.contains("other")
            "unknown" -> ownText.contains("unknown")
            else -> ownText.contains(normalizedType)
        }
    }

    private fun parseAnimeElements(doc: Document): List<Element> {
        val gridCards = doc.select("div.grid > div")
            .filter { animeUrlFromElement(it).isNotBlank() }

        if (gridCards.isNotEmpty()) return gridCards.distinctBy { animeUrlFromElement(it) }

        return doc.select("div[wire:key]:has(a[href*='/anime/']), article:has(a[href*='/anime/']), li:has(a[href*='/anime/'])")
            .filter { animeUrlFromElement(it).isNotBlank() }
            .distinctBy { animeUrlFromElement(it) }
    }

    private fun animeUrlFromElement(element: Element): String {
        val href = element.selectFirst("a.inline[href*='/anime/'], a[href*='/anime/']")
            ?.attr("href")
            ?: ""
        return normalizeUrl(href)
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.isBlank() -> ""
            url.startsWith("//") -> "https:$url"
            url.startsWith("http", ignoreCase = true) -> url
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun pathFromUrl(url: String): String {
        val path = url.substringAfter(mainUrl, url)
            .substringBefore("?")
            .substringBefore("#")
            .trim()
        return if (path.startsWith("/")) path else "/$path"
    }

    private fun titleFromElement(post: Element, link: Element?): String {
        return link?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: link?.attr("title")?.takeIf { it.isNotBlank() && it != "displayAnimeTitle" }
            ?: post.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: titleFromXData(post)
            ?: post.selectFirst("h1, h2, h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: "Unknown Title"
    }

    private fun titleFromXData(post: Element): String? {
        val xData = post.attr("x-data").takeIf { it.isNotBlank() } ?: return null
        val fallback = Regex("""getTitle\(this\.anmTitles,\s*(?:'([^']*)'|\"([^\"]*)\")""")
            .find(xData)
            ?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotBlank() }
            ?.let { cleanTitleText(it) }
            ?.takeIf { it.isNotBlank() }

        if (!fallback.isNullOrBlank()) return fallback

        return Regex("""\\u00225\\u0022:\\u0022(.*?)\\u0022""")
            .find(xData)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { cleanTitleText(it) }
            ?.takeIf { it.isNotBlank() }
    }

    private fun cleanTitleText(value: String): String {
        return value
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("\\u0022", "\"")
            .replace("\\/", "/")
            .trim()
            .trim('\"')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun toResult(post: Element): SearchResponse {
        val link = post.selectFirst("a.inline[href*='/anime/'], a[href*='/anime/']")
        val title = titleFromElement(post, link)
        val url = normalizeUrl(link?.attr("href") ?: "")
        val type = if (post.text().contains("Movie", ignoreCase = true)) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        return newMovieSearchResponse(title, url, type) {
            this.posterUrl = post.selectFirst("img")?.attr("src")
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        snapshots[snapshotAnimeKey] = ""
        val initialized = initializeLiveWire()
        if (!initialized) return emptyList()

        val doc = getHtmlFromWire(
            liveWireBuilder(
                snapshotAnimeKey,
                mapOf("search" to query, "sort" to "release-desc"),
                emptyList(),
                cookies,
                false
            )
        )

        return parseAnimeElements(doc).map { toResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val doc = response.document
        val cookie = response.cookies.toMutableMap()
        val slug = pathFromUrl(url)

        val pageToken = doc.select("script[data-csrf]").attr("data-csrf")
        if (pageToken.isNotBlank()) token = pageToken
        snapshots[snapshotEpisodeKey] = getSnapshot(doc, *componentNamesForPath(slug))

        val title = doc.selectFirst("h1")?.text()
            ?: throw NotImplementedError("Unable to find title")

        val bgImage = doc.selectFirst("main img")?.attr("src")
        val synopsis = doc.selectFirst(".sr-only + div, div:has(>h3:contains(Synopsis)) > div")?.text() ?: ""
        val rowLines = doc.select("span.inline-block, span.flex").map { it.text() }
        val releasedYear = rowLines.firstOrNull { it.matches(Regex("""\d{4}""")) }

        val status = when {
            rowLines.any { it.equals("Completed", ignoreCase = true) } -> ShowStatus.Completed
            rowLines.any { it.equals("Ongoing", ignoreCase = true) } -> ShowStatus.Ongoing
            else -> null
        }

        val genres = doc.select("a[wire:navigate][wire:key], div > a[href*='/tag/']").map {
            translateGenre(it.text())
        }.distinct()

        val imdbLink = doc.selectFirst("a[href*='imdb.com']")
        val imdbId = imdbLink?.attr("href")
            ?.substringAfter("title/")
            ?.trimEnd('/', ' ', '?')
            ?.let {
                if (it.startsWith("tt") && it.length > 2) it else "tt0000000"
            }
            ?: "tt0000000"

        Log.d("AniZoneIMDB", "IMDB ID ditemukan di LOAD: $imdbId")

        var currentDoc = doc
        var previousEpisodeCount = parseEpisodeElements(currentDoc).size
        var attempts = 0
        val maxAttempts = 100

        while (hasLoadMore(currentDoc) && attempts < maxAttempts) {
            attempts++

            try {
                val responseJson = liveWireBuilder(
                    snapshotEpisodeKey,
                    emptyMap(),
                    listOf(
                        mapOf(
                            "path" to "",
                            "method" to "loadMore",
                            "params" to emptyList<String>()
                        )
                    ),
                    cookie,
                    true,
                    slug
                )

                val nextDoc = getHtmlFromWire(responseJson)
                val nextCount = parseEpisodeElements(nextDoc).size
                if (nextCount <= previousEpisodeCount) break

                currentDoc = nextDoc
                previousEpisodeCount = nextCount
            } catch (e: Exception) {
                Log.e("AniZone Load", "Error loadMore episode percobaan $attempts: ${e.message}")
                break
            }
        }

        val episodes = parseEpisodeElements(currentDoc).map { elt ->
            val epUrl = normalizeUrl(elt.selectFirst("a[href]")?.attr("href") ?: "")

            newEpisode(epUrl) {
                this.name = elt.selectFirst("h3")?.text()
                    ?.substringAfter(":")
                    ?.trim()
                    ?: elt.selectFirst("h3")?.text()?.trim()

                this.season = 0
                this.posterUrl = elt.selectFirst("img")?.attr("src")
                this.data = "$epUrl|||$imdbId"
                this.date = parseEpisodeDate(elt)
            }
        }

        val tvType = if (rowLines.any { it.equals("Movie", ignoreCase = true) } && episodes.size <= 1) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = bgImage
            this.plot = synopsis
            this.tags = genres
            this.year = releasedYear?.toIntOrNull()
            this.showStatus = status
            addEpisodes(DubStatus.None, episodes)
        }
    }

    private fun parseEpisodeElements(doc: Document): List<Element> {
        return doc.select("ul > li:has(a[href]), li[x-data]:has(a[href])")
            .filter { isEpisodeUrl(it.selectFirst("a[href]")?.attr("href") ?: "") }
            .distinctBy { normalizeUrl(it.selectFirst("a[href]")?.attr("href") ?: "") }
    }

    private fun isEpisodeUrl(href: String): Boolean {
        val parts = pathFromUrl(normalizeUrl(href)).split("/").filter { it.isNotBlank() }
        return parts.size >= 3 && parts.firstOrNull() == "anime"
    }

    private fun parseEpisodeDate(element: Element): Long {
        val dateText = element.select("div.flex-row > span").getOrNull(1)?.text()
            ?: element.selectFirst("span[title] span.line-clamp-1")?.text()
            ?: ""

        return dateText.trim()
            .replace(Regex("\\s+"), "")
            .ifEmpty { null }
            ?.let {
                try {
                    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(it)?.time
                } catch (e: Exception) {
                    Log.e("AniZone", "Gagal parse tanggal '$it': ${e.message}")
                    null
                }
            } ?: 0L
    }

    private fun extractMediaUrls(doc: Document): List<String> {
        val urls = mutableListOf<String>()

        doc.select("media-player[src], video[src], video source[src], source[type*=mpegurl][src], source[src*=.m3u8], a[href*=.m3u8]")
            .forEach { element ->
                element.attr("src").takeIf { it.isNotBlank() }?.let { urls.add(it) }
                element.attr("href").takeIf { it.isNotBlank() }?.let { urls.add(it) }
            }

        val scriptText = doc.select("script").joinToString("\n") { it.data().ifBlank { it.html() } }
        val normalizedScriptText = scriptText
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("&amp;", "&")

        listOf(scriptText, normalizedScriptText).forEach { text ->
            Regex("""https?://[^"'\s<>]+?\.m3u8(?:\?[^"'\s<>]*)?""")
                .findAll(text)
                .map { it.value }
                .forEach { urls.add(it) }

            Regex("""(?<!:)//[^"'\s<>]+?\.m3u8(?:\?[^"'\s<>]*)?""")
                .findAll(text)
                .map { it.value }
                .forEach { urls.add(it) }
        }

        return urls
            .map { it.trim().trim('\'', '"') }
            .filter { it.isNotBlank() && !it.startsWith("blob:", ignoreCase = true) }
            .distinct()
    }

    private fun cleanMediaUrl(url: String): String {
        return normalizeUrl(
            url.trim()
                .trim('\'', '"')
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data.split("|||").firstOrNull()?.takeIf { it.isNotBlank() } ?: return false
        val episodePath = pathFromUrl(episodeUrl)

        Log.d("AniZoneSub", "Mulai loadLinks: $episodeUrl")

        val webReq = app.get(episodeUrl)
        val web = webReq.document
        val cookie = webReq.cookies.toMutableMap()

        val pageToken = web.select("script[data-csrf]").attr("data-csrf")
        if (pageToken.isNotBlank()) token = pageToken
        snapshots[snapshotVideoKey] = getSnapshot(web, *componentNamesForPath(episodePath))

        val emittedUrls = mutableSetOf<String>()
        var emitted = false

        suspend fun emitPlayer(doc: Document, sourceName: String) {
            val mediaUrls = extractMediaUrls(doc)
            if (mediaUrls.isEmpty()) return

            doc.select("track[src], media-player track[src]").forEach {
                val subtitleUrl = it.attr("src").takeIf { src -> src.isNotBlank() } ?: return@forEach
                subtitleCallback.invoke(
                    newSubtitleFile(
                        it.attr("label").ifBlank { "Subtitle" },
                        normalizeUrl(subtitleUrl)
                    )
                )
            }

            // HAR playback evidence from seiryuu.vid-cdn.xyz uses Origin + root Referer without Cookie.
            // Keep media headers close to the browser playback flow so encrypted HLS keys/segments inherit the same access context.
            val baseHeaders = mapOf(
                "Origin" to mainUrl,
                "Accept" to "*/*",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
            )

            mediaUrls.forEach { rawUrl ->
                val masterUrl = cleanMediaUrl(rawUrl)
                if (masterUrl.startsWith("blob:", ignoreCase = true)) return@forEach
                if (!emittedUrls.add(masterUrl)) return@forEach

                callback.invoke(
                    newExtractorLink(
                        sourceName.ifBlank { name },
                        name,
                        masterUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = 0
                        this.headers = baseHeaders
                    }
                )

                emitted = true
            }
        }

        val serverButtons = web.getAllElements()
            .filter { it.attr("wire:click").contains("setVideo") }
            .distinctBy { it.attr("wire:click") }

        val firstName = serverButtons.firstOrNull()?.text()?.trim()
            ?: web.selectFirst("span.truncate")?.text()
            ?: name

        emitPlayer(web, firstName)

        for (button in serverButtons) {
            val videoParam = Regex("""setVideo\(([^)]*)\)""")
                .find(button.attr("wire:click"))
                ?.groupValues
                ?.getOrNull(1)
                ?.split(",")
                ?.firstOrNull()
                ?.trim()
                ?.trim('\'', '"')
                ?.takeIf { it.isNotBlank() }
                ?: continue
            val param: Any = videoParam.toIntOrNull() ?: videoParam

            try {
                val responseJson = liveWireBuilder(
                    snapshotVideoKey,
                    emptyMap(),
                    listOf(
                        mapOf(
                            "path" to "",
                            "method" to "setVideo",
                            "params" to listOf(param)
                        )
                    ),
                    cookie,
                    true,
                    episodePath
                )

                val doc = getHtmlFromWire(responseJson)
                emitPlayer(doc, button.text().trim())
            } catch (e: Exception) {
                Log.e("AniZoneSub", "Gagal resolve server ${button.text()}: ${e.message}")
            }
        }

        return emitted
    }
}
