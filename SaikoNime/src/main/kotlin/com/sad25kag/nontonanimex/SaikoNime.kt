package com.sad25kag.saikonime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class SaikoNime : MainAPI() {
    override var mainUrl = "https://saikonime.ink"
    override var name = "SaikoNime (Limited-NoLogin)"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.6",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "home:latest" to "Baru Diupdate",
        "home:popular" to "Trending Anime",
        "home:completed" to "Selesai Tayang",
        "home:movies" to "Top Movies",
        "genre:action:Action" to "Action",
        "genre:adventure:Adventure" to "Adventure",
        "genre:aksi:Aksi" to "Aksi",
        "genre:donghua:Donghua" to "Donghua",
        "genre:fantasi:Fantasi" to "Fantasi",
        "genre:komedi:Komedi" to "Komedi",
        "genre:romansa:Romansa" to "Romansa",
        "genre:shounen:Shounen" to "Shounen",
        "genre:isekai:Isekai" to "Isekai",
    )

    private data class SaikoVideo(
        val title: String,
        val slug: String,
        val description: String? = null,
        val thumbnail: String? = null,
        val banner: String? = null,
        val category: String? = null,
        val status: String? = null,
        val animeType: String? = null,
        val releaseDate: String? = null,
        val duration: String? = null,
        val episodeCount: Int? = null,
        val sources: List<SaikoSource> = emptyList(),
        val raw: String = "",
    )

    private data class SaikoEpisode(
        val number: Int,
        val title: String,
        val url: String,
        val thumbnail: String? = null,
    )

    private data class SaikoSource(
        val name: String,
        val url: String,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val home = fetchHomeRsc()
        val selected = when {
            request.data == "home:latest" -> parseVideoArray(home, "latest")
            request.data == "home:popular" -> parseVideoArray(home, "popular")
            request.data == "home:completed" -> parseSectionVideos(home, "Just Finished")
                .ifEmpty { parseVideoArray(home, "topRated") }
                .filter { it.status.equals("Completed", true) || it.episodeCount == 0 || it.raw.contains("\"status\":\"Completed\"", true) }
            request.data == "home:movies" -> parseSectionVideos(home, "Top Movies")
                .ifEmpty { parseAllVideos(home).filter { it.animeType.equals("Movie", true) || it.episodeCount == 0 } }
            request.data.startsWith("genre:") -> {
                val parts = request.data.split(":")
                val slug = parts.getOrNull(1).orEmpty()
                val label = parts.getOrNull(2).orEmpty()
                val fromGenrePage = runCatching { parseAllVideos(fetchRsc("$mainUrl/genre/$slug", "$mainUrl/")) }.getOrDefault(emptyList())
                fromGenrePage.ifEmpty { parseAllVideos(home).filter { it.matchesGenre(slug, label) } }
            }
            else -> parseAllVideos(home)
        }

        val results = selected
            .distinctBy { it.slug.normalizedKey() }
            .map { it.toSearchResponse() }

        return newHomePageResponse(request.name, results, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = query.cleanText()
        if (normalizedQuery.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(normalizedQuery, "UTF-8")
        val browseResults = runCatching {
            parseAllVideos(fetchRsc("$mainUrl/browse?keyword=$encoded", "$mainUrl/"))
        }.getOrDefault(emptyList())

        val fallbackResults = parseAllVideos(fetchHomeRsc()).filter { video ->
            video.title.contains(normalizedQuery, true) ||
                video.description?.contains(normalizedQuery, true) == true ||
                video.category?.contains(normalizedQuery, true) == true ||
                video.raw.contains(normalizedQuery, true)
        }

        return (browseResults + fallbackResults)
            .distinctBy { it.slug.normalizedKey() }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = url.toAbsoluteUrl() ?: url
        val rsc = fetchRsc(fixedUrl, "$mainUrl/")
        val slug = fixedUrl.extractAnimeSlug()
        val video = parsePrimaryVideo(rsc, slug)
            ?: parseAllVideos(fetchHomeRsc()).firstOrNull { it.slug == slug }
            ?: return null

        val episodes = parseEpisodes(rsc, video.slug)
            .distinctBy { it.number }
            .sortedBy { it.number }

        val recommendations = parseAllVideos(rsc)
            .filterNot { it.slug == video.slug }
            .distinctBy { it.slug.normalizedKey() }
            .take(16)
            .map { it.toSearchResponse() }

        val poster = video.thumbnail ?: video.banner
        val tvType = when {
            video.animeType.equals("Movie", true) -> TvType.AnimeMovie
            video.episodeCount == 0 && video.sources.isNotEmpty() -> TvType.AnimeMovie
            fixedUrl.contains("/episode/", true) -> TvType.Anime
            else -> TvType.Anime
        }

        return if (episodes.isNotEmpty() && !fixedUrl.contains("/episode/", true) && !video.animeType.equals("Movie", true)) {
            newTvSeriesLoadResponse(video.title, fixedUrl, TvType.Anime, episodes.map { episode ->
                newEpisode(episode.url) {
                    this.name = episode.title
                    this.episode = episode.number
                    this.posterUrl = episode.thumbnail ?: poster
                }
            }) {
                this.posterUrl = poster
                this.plot = video.description
                this.tags = video.category?.split(",")?.map { it.cleanText() }?.filter { it.isNotBlank() }
                this.recommendations = recommendations
                this.showStatus = video.status.toShowStatus()
                this.year = video.releaseDate?.extractYear()
            }
        } else {
            val title = if (fixedUrl.contains("/episode/", true)) {
                rsc.readString("videoTitle") ?: video.title
            } else video.title

            newMovieLoadResponse(title, fixedUrl, tvType, fixedUrl) {
                this.posterUrl = poster
                this.plot = video.description
                this.tags = video.category?.split(",")?.map { it.cleanText() }?.filter { it.isNotBlank() }
                this.recommendations = recommendations
                this.year = video.releaseDate?.extractYear()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = data.substringBefore("|SAIKO|").toAbsoluteUrl() ?: data.substringBefore("|SAIKO|")
        val rsc = fetchRsc(pageUrl, "$mainUrl/")
        val emitted = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, label: String? = null, referer: String = pageUrl): Boolean {
            val videoUrl = rawUrl?.decodeEmbedText()?.toAbsoluteUrl(referer)?.takeIf { it.isDirectMediaLike() } ?: return false
            if (!emitted.add(videoUrl.substringBefore("#"))) return true
            val sourceName = listOfNotNull(name, label?.cleanText()?.takeIf { it.isNotBlank() }).joinToString(" - ")
            val type = if (videoUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(sourceName, sourceName, videoUrl, type) {
                    this.referer = referer
                    this.quality = getQualityFromName(label ?: videoUrl)
                    this.headers = browserHeaders + mapOf(
                        "Referer" to referer,
                        "Origin" to originOf(referer),
                    )
                },
            )
            return true
        }

        val countedCallback: (ExtractorLink) -> Unit = { link ->
            if (emitted.add(link.url.substringBefore("#"))) callback.invoke(link)
        }

        val sourceCandidates = parsePlaybackSources(rsc)
            .ifEmpty { collectPlayerCandidates(Jsoup.parse(rsc), pageUrl).map { SaikoSource(hostLabel(it), it) } }
            .distinctBy { it.url.substringBefore("#").normalizedKey() }

        for (source in sourceCandidates.take(40)) {
            val sourceUrl = source.url.decodeEmbedText().toAbsoluteUrl(pageUrl) ?: continue
            if (emitDirect(sourceUrl, source.name, pageUrl)) continue

            runCatching { loadExtractor(sourceUrl, pageUrl, subtitleCallback, countedCallback) }
            if (emitted.isNotEmpty()) continue

            val playerHtml = runCatching {
                app.get(sourceUrl, headers = browserHeaders + mapOf("Referer" to pageUrl), referer = pageUrl).text
            }.getOrNull().orEmpty()

            if (playerHtml.isBlank()) continue

            val unpacked = runCatching { getAndUnpack(playerHtml) }.getOrNull().orEmpty()
            val nested = collectUrlsFromText(playerHtml + "\n" + unpacked, sourceUrl)
            for (nestedUrl in nested.take(30)) {
                if (emitDirect(nestedUrl, source.name, sourceUrl)) continue
                val fixedNested = nestedUrl.toAbsoluteUrl(sourceUrl) ?: continue
                runCatching { loadExtractor(fixedNested, sourceUrl, subtitleCallback, countedCallback) }
            }
        }

        return emitted.isNotEmpty()
    }

    private suspend fun fetchHomeRsc(): String = fetchRsc(mainUrl, "$mainUrl/")

    private suspend fun fetchRsc(url: String, referer: String): String {
        val fixedUrl = url.toAbsoluteUrl() ?: url
        val rscUrl = fixedUrl.withRscQuery()
        val rscText = runCatching {
            app.get(rscUrl, headers = rscHeaders(fixedUrl, referer), referer = referer).text
        }.getOrDefault("")

        if (rscText.length > 500 && (rscText.contains("\"video\":{") || rscText.contains("\"latest\":[") || rscText.contains("\"sources\":[") || rscText.contains("\"episodes\":["))) {
            return rscText
        }

        return runCatching {
            app.get(fixedUrl, headers = browserHeaders + mapOf("Referer" to referer), referer = referer).text
        }.getOrDefault(rscText)
    }

    private fun rscHeaders(targetUrl: String, referer: String): Map<String, String> {
        return browserHeaders + mapOf(
            "Accept" to "*/*",
            "RSC" to "1",
            "Referer" to referer,
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
            "Next-Router-State-Tree" to buildRouterState(targetUrl),
        )
    }

    private fun buildRouterState(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val path = uri?.path?.trim('/').orEmpty()
        val state = when {
            path.isBlank() -> """["",{"children":["(main)",{"children":["__PAGE__",{},null,"refetch"]},null,null]},null,null]"""
            path.startsWith("anime/") && path.contains("/episode/") -> {
                val slug = path.substringAfter("anime/").substringBefore("/episode/")
                val number = path.substringAfter("/episode/").substringBefore('/').ifBlank { "1" }
                """["",{"children":["(main)",{"children":["anime",{"children":[["slug","$slug","d"],{"children":["episode",{"children":[["episodeNum","$number","d"],{"children":["__PAGE__",{},null,"refetch"]},null,null]},null,null]},null,null]},null,null]},null,null]},null,null]"""
            }
            path.startsWith("anime/") -> {
                val slug = path.substringAfter("anime/").substringBefore('/')
                """["",{"children":["(main)",{"children":["anime",{"children":[["slug","$slug","d"],{"children":["__PAGE__",{},null,"refetch"]},null,null]},null,null]},null,null]},null,null]"""
            }
            path.startsWith("genre/") -> {
                val slug = path.substringAfter("genre/").substringBefore('/')
                """["",{"children":["(main)",{"children":["genre",{"children":[["slug","$slug","d"],{"children":["__PAGE__",{},null,"refetch"]},null,null]},null,null]},null,null]},null,null]"""
            }
            path.startsWith("browse") -> """["",{"children":["(main)",{"children":["browse",{"children":["__PAGE__",{},null,"refetch"]},null,null]},null,null]},null,null]"""
            else -> """["",{"children":["(main)",{"children":["__PAGE__",{},null,"refetch"]},null,null]},null,null]"""
        }
        return URLEncoder.encode(state, "UTF-8")
    }

    private fun parsePrimaryVideo(text: String, expectedSlug: String?): SaikoVideo? {
        val fromComponent = extractAllObjectsAfterKey(text, "video")
            .mapNotNull { parseVideoObject(it) }
            .firstOrNull { expectedSlug == null || it.slug == expectedSlug }
        if (fromComponent != null) return fromComponent

        return parseAllVideos(text).firstOrNull { expectedSlug == null || it.slug == expectedSlug }
            ?: parseAllVideos(text).firstOrNull()
    }

    private fun parseAllVideos(text: String): List<SaikoVideo> {
        val videos = mutableListOf<SaikoVideo>()
        listOf("latest", "popular", "topRated", "videos").forEach { key ->
            extractAllArraysAfterKey(text, key).forEach { array -> videos.addAll(parseVideoObjects(array)) }
        }
        extractAllObjectsAfterKey(text, "video").forEach { obj -> parseVideoObject(obj)?.let { videos.add(it) } }
        return videos.distinctBy { it.slug.normalizedKey() }
    }

    private fun parseVideoArray(text: String, key: String): List<SaikoVideo> {
        val array = extractArrayAfterKey(text, key)?.first ?: return emptyList()
        return parseVideoObjects(array)
    }

    private fun parseSectionVideos(text: String, sectionTitle: String): List<SaikoVideo> {
        val arrays = extractAllArraysAfterKeyWithEnd(text, "videos")
        for ((array, end) in arrays) {
            val tail = text.substring(end, minOf(text.length, end + 500))
            if (tail.contains("\"title\":\"$sectionTitle\"", true)) return parseVideoObjects(array)
        }
        return emptyList()
    }

    private fun parseVideoObjects(array: String): List<SaikoVideo> {
        return extractObjects(array).mapNotNull { parseVideoObject(it) }
    }

    private fun parseVideoObject(obj: String): SaikoVideo? {
        val title = obj.readString("title") ?: return null
        val slug = obj.readString("slug") ?: return null
        val sources = extractArrayAfterKey(obj, "sources")?.let { parseSourcesFromArray(it.first) }.orEmpty()
        return SaikoVideo(
            title = cleanTitle(title) ?: title,
            slug = slug,
            description = obj.readString("description")?.takeIf { it.isNotBlank() },
            thumbnail = obj.readString("thumbnailUrl")?.toAbsoluteUrl(),
            banner = obj.readString("bannerUrl")?.toAbsoluteUrl(),
            category = obj.readString("category")?.takeIf { it.isNotBlank() },
            status = obj.readString("status")?.takeIf { it.isNotBlank() },
            animeType = obj.readString("animeType")?.takeIf { it.isNotBlank() },
            releaseDate = obj.readString("releaseDate")?.takeIf { it.isNotBlank() },
            duration = obj.readString("duration")?.takeIf { it.isNotBlank() },
            episodeCount = obj.readInt("episodeCount"),
            sources = sources.ifEmpty {
                obj.readString("videoUrl")?.takeIf { it.isNotBlank() }?.let { listOf(SaikoSource("Direct", it)) }.orEmpty()
            },
            raw = obj,
        )
    }

    private fun parseEpisodes(text: String, animeSlug: String): List<SaikoEpisode> {
        return extractAllArraysAfterKey(text, "episodes")
            .flatMap { array ->
                extractObjects(array).mapNotNull { obj ->
                    val number = obj.readInt("number") ?: obj.readString("slug")?.toEpisodeNumber() ?: return@mapNotNull null
                    val rawTitle = obj.readString("title") ?: "Episode $number"
                    SaikoEpisode(
                        number = number,
                        title = cleanTitle(rawTitle) ?: rawTitle,
                        url = "$mainUrl/anime/$animeSlug/episode/$number",
                        thumbnail = obj.readString("thumbnailUrl")?.toAbsoluteUrl(),
                    )
                }
            }
            .distinctBy { it.number }
    }

    private fun parsePlaybackSources(text: String): List<SaikoSource> {
        val currentPlayerIndex = text.indexOf("\"videoId\":")
        if (currentPlayerIndex >= 0) {
            val sources = extractArrayAfterKey(text, "sources", currentPlayerIndex)?.first?.let { parseSourcesFromArray(it) }.orEmpty()
            if (sources.isNotEmpty()) return sources
        }

        val videoObject = extractObjectAfterKey(text, "video")
        if (videoObject != null) {
            val sources = extractArrayAfterKey(videoObject, "sources")?.first?.let { parseSourcesFromArray(it) }.orEmpty()
            if (sources.isNotEmpty()) return sources
            videoObject.readString("videoUrl")?.takeIf { it.isNotBlank() }?.let { return listOf(SaikoSource("Direct", it)) }
        }

        return extractAllArraysAfterKey(text, "sources")
            .asSequence()
            .map { parseSourcesFromArray(it) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    private fun parseSourcesFromArray(array: String): List<SaikoSource> {
        return extractObjects(array).mapNotNull { obj ->
            val rawUrl = obj.readString("url") ?: return@mapNotNull null
            val url = rawUrl.decodeEmbedText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sourceName = obj.readString("name") ?: hostLabel(url)
            SaikoSource(sourceName, url)
        }
    }

    private fun SaikoVideo.toSearchResponse(): SearchResponse {
        val tvType = if (animeType.equals("Movie", true) || episodeCount == 0 && sources.isNotEmpty()) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, "$mainUrl/anime/$slug", tvType) {
            this.posterUrl = thumbnail ?: banner
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun SaikoVideo.matchesGenre(slug: String, label: String): Boolean {
        val normalizedSlug = slug.normalizeSlug()
        val normalizedLabel = label.normalizeSlug()
        val normalizedCategory = category.orEmpty().normalizeSlug()
        return normalizedCategory.split(',', ' ').any { it == normalizedSlug || it == normalizedLabel } ||
            normalizedCategory.contains(normalizedSlug) ||
            normalizedCategory.contains(normalizedLabel) ||
            raw.contains("\"slug\":\"$slug\"", true) ||
            raw.contains("\"name\":\"$label\"", true)
    }

    private fun extractAllArraysAfterKey(text: String, key: String): List<String> = extractAllArraysAfterKeyWithEnd(text, key).map { it.first }

    private fun extractAllArraysAfterKeyWithEnd(text: String, key: String): List<Pair<String, Int>> {
        val arrays = mutableListOf<Pair<String, Int>>()
        var searchFrom = 0
        while (searchFrom < text.length) {
            val pair = extractArrayAfterKey(text, key, searchFrom) ?: break
            arrays.add(pair)
            searchFrom = pair.second + 1
        }
        return arrays
    }

    private fun extractArrayAfterKey(text: String, key: String, startIndex: Int = 0): Pair<String, Int>? {
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\\[").find(text, startIndex) ?: return null
        val start = text.indexOf('[', match.range.first)
        val end = findBalancedEnd(text, start, '[', ']') ?: return null
        return text.substring(start, end + 1) to end
    }

    private fun extractAllObjectsAfterKey(text: String, key: String): List<String> {
        val objects = mutableListOf<String>()
        var searchFrom = 0
        while (searchFrom < text.length) {
            val obj = extractObjectAfterKey(text, key, searchFrom) ?: break
            objects.add(obj.first)
            searchFrom = obj.second + 1
        }
        return objects
    }

    private fun extractObjectAfterKey(text: String, key: String, startIndex: Int = 0): Pair<String, Int>? {
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\\{").find(text, startIndex) ?: return null
        val start = text.indexOf('{', match.range.first)
        val end = findBalancedEnd(text, start, '{', '}') ?: return null
        return text.substring(start, end + 1) to end
    }

    private fun extractObjectAfterKey(text: String, key: String): String? = extractObjectAfterKey(text, key, 0)?.first

    private fun extractObjects(array: String): List<String> {
        val objects = mutableListOf<String>()
        var index = 0
        while (index < array.length) {
            val start = array.indexOf('{', index)
            if (start < 0) break
            val end = findBalancedEnd(array, start, '{', '}') ?: break
            objects.add(array.substring(start, end + 1))
            index = end + 1
        }
        return objects
    }

    private fun findBalancedEnd(text: String, start: Int, open: Char, close: Char): Int? {
        if (start !in text.indices || text[start] != open) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val char = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        }
        return null
    }

    private fun String.readString(key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        return pattern.find(this)?.groupValues?.getOrNull(1)?.jsonUnescape()?.decodeEmbedText()?.cleanText()?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun String.readInt(key: String): Int? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\\d+)")
        return pattern.find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.jsonUnescape(): String {
        val out = StringBuilder(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c == '\\' && i + 1 < length) {
                when (val n = this[i + 1]) {
                    '\\' -> out.append('\\')
                    '"' -> out.append('"')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        val hex = substring(i + 2, minOf(i + 6, length))
                        if (hex.length == 4) {
                            hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                            i += 4
                        } else out.append(n)
                    }
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun collectPlayerCandidates(document: Document, referer: String): LinkedHashSet<String> {
        val candidates = linkedSetOf<String>()
        document.select("iframe[src], embed[src], video[src], source[src]").forEach { element ->
            element.attr("src").takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        }
        document.select("select option[value], .server option[value], .mirror option[value], button[value], [data-src], [data-url], [data-link], [data-iframe], [data-embed], [data-player], [data-video], [data-file], [data-stream]").forEach { element ->
            listOf("value", "data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file", "data-stream")
                .mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                .forEach { raw ->
                    candidates.add(raw)
                    decodePossibleBase64(raw)?.let { decoded -> collectUrlsFromText(decoded, referer).forEach { candidates.add(it) } }
                    runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()?.let { decoded -> collectUrlsFromText(decoded, referer).forEach { candidates.add(it) } }
                }
        }
        collectUrlsFromText(document.html(), referer).forEach { candidates.add(it) }
        return candidates
    }

    private fun collectUrlsFromText(text: String, base: String): List<String> {
        val normalized = text.decodeEmbedText()
        val urls = linkedSetOf<String>()
        Regex("""<(?:iframe|embed|source|video)[^>]+(?:src|data-src)=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { urls.add(it.groupValues[1]) }
        Regex("""(?:src|file|url|source|embed|iframe|data-url|data-src|data-file|data-player|data-stream)\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { urls.add(it.groupValues[1]) }
        Regex("""atob\(['"]([^'"]+)['"]\)""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { decodePossibleBase64(it.groupValues[1]) }
            .forEach { decoded -> collectUrlsFromText(decoded, base).forEach { urls.add(it) } }
        Regex("""https?://[^'"<>()\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map { it.value.trimEnd(',', ';') }
            .forEach { urls.add(it) }
        return urls.mapNotNull { it.toAbsoluteUrl(base) }.filter { it.isPotentialPlayer() }
    }

    private fun decodePossibleBase64(value: String): String? {
        val clean = value.trim().trim('"', '\'')
        if (clean.length < 8 || clean.contains("<")) return null
        return runCatching { base64Decode(clean) }.getOrNull()
            ?: runCatching {
                val fixed = clean.replace('-', '+').replace('_', '/').let { raw -> raw + "=".repeat((4 - raw.length % 4) % 4) }
                String(android.util.Base64.decode(fixed, android.util.Base64.DEFAULT))
            }.getOrNull()
    }

    private fun cleanTitle(raw: String?): String? = raw?.htmlUnescape()?.cleanText()
        ?.replace(Regex("""(?i)\s*[-–|]\s*Saikonime.*$"""), "")
        ?.replace(Regex("""(?i)\s*Subtitle\s+Indonesia.*$"""), "")
        ?.takeIf { it.isNotBlank() }

    private fun String.cleanText(): String = htmlUnescape()
        .replace("\u00a0", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.htmlUnescape(): String = Jsoup.parse(this).text()

    private fun String.decodeEmbedText(): String = htmlUnescape()
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u003A", ":")
        .replace("\\u003D", "=")
        .replace("\\u0026", "&")
        .replace("&quot;", "\"")
        .replace("&#038;", "&")
        .replace("&amp;", "&")
        .trim()

    private fun String.toAbsoluteUrl(base: String = mainUrl): String? {
        val clean = decodeEmbedText().trim().trim('"', '\'', ' ', '\n', '\r', '\t')
        if (clean.isBlank() || clean.startsWith("javascript", true) || clean.startsWith("#")) return null
        return runCatching { URI(base).resolve(clean).toString() }.getOrNull()
    }

    private fun String.withRscQuery(): String {
        val clean = substringBefore("#")
        return if (clean.contains("?")) "$clean&_rsc=cs" else "$clean?_rsc=cs"
    }

    private fun String.normalizedKey(): String = substringBefore("#").substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)

    private fun String.normalizeSlug(): String = lowercase(Locale.ROOT)
        .replace("†", "")
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')

    private fun String.extractAnimeSlug(): String? = Regex("""/anime/([^/?#]+)""", RegexOption.IGNORE_CASE)
        .find(this)?.groupValues?.getOrNull(1)

    private fun String.toEpisodeNumber(): Int? = Regex("""(?i)(?:episode|eps?|ep)\s*-?\s*(\d+)""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("""/episode/(\d+)""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("""\b(\d{1,4})\b""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String.extractYear(): Int? = Regex("""\b(20\d{2}|19\d{2})\b""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String?.toShowStatus(): ShowStatus? {
        val value = this?.lowercase(Locale.ROOT).orEmpty()
        return when {
            value.contains("completed") || value.contains("selesai") -> ShowStatus.Completed
            value.contains("ongoing") || value.contains("tayang") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun String.isDirectMediaLike(): Boolean {
        val value = lowercase(Locale.ROOT)
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".webm") || value.contains(".mkv") || value.contains("videoplayback")
    }

    private fun String.isPotentialPlayer(): Boolean {
        val value = lowercase(Locale.ROOT)
        if (isDirectMediaLike()) return true
        return listOf(
            "iframe", "embed", "player", "stream", "vidhide", "vidhidepro", "filemoon", "streamwish", "streamruby", "dood",
            "mp4upload", "blogger", "googlevideo", "ok.ru", "okcdn", "dailymotion", "dmcdn", "mega.nz", "krakenfiles", "acefile",
            "short.icu", "short.ink", "callistanise", "playhydrax", "hydrax", "gdriveplayer", "bestx.stream"
        ).any { value.contains(it) }
    }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun hostLabel(url: String): String = runCatching { URI(url).host ?: url }.getOrDefault(url)
}
