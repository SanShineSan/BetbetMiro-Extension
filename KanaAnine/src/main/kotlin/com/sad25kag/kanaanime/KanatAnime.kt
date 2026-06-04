package com.sad25kag.kanaanime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class KanatAnime : MainAPI() {
    override var mainUrl = "https://www.kanatanime.net"
    override var name = "KanatAnime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val jsonMapper = jacksonObjectMapper()

    private val apiHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    private val htmlHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "otakudesu:ongoing" to "Anime Ongoing",
        "otakudesu:complete" to "Anime Complete",
        "otakudesu:schedule" to "Jadwal Rilis",
        "donghua:latest_updates" to "Donghua Update",
        "donghua:ongoing_series" to "Donghua Ongoing",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = when (request.data) {
            "otakudesu:ongoing" -> apiData("/api/otakudesu/home").array("ongoing").mapNotNull { it.toOtakudesuAnimeSearch() }
            "otakudesu:complete" -> apiData("/api/otakudesu/home").array("complete").mapNotNull { it.toOtakudesuAnimeSearch() }
            "otakudesu:schedule" -> parseScheduleItems()
            "donghua:latest_updates" -> apiData("/api/donghua/home").array("latest_updates").mapNotNull { it.toDonghuaEpisodeSearch() }
            "donghua:ongoing_series" -> apiData("/api/donghua/home").array("ongoing_series").mapNotNull { it.toDonghuaEpisodeSearch() }
            else -> emptyList()
        }.distinctBy { it.url.normalizedKey() }

        return newHomePageResponse(request.name, results, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val results = linkedMapOf<String, SearchResponse>()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")

        val apiRoutes = listOf(
            "/api/otakudesu/search?keyword=$encoded" to "otakudesu",
            "/api/otakudesu/search?q=$encoded" to "otakudesu",
            "/api/donghua/search?keyword=$encoded" to "donghua",
            "/api/donghua/search?q=$encoded" to "donghua",
        )

        for ((path, source) in apiRoutes) {
            runCatching {
                parseFlexibleItems(apiData(path), source).forEach { results[it.url] = it }
            }
        }

        if (results.isEmpty()) {
            fallbackHomeItems()
                .filter { it.name.contains(cleanQuery, ignoreCase = true) }
                .forEach { results[it.url] = it }
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val route = parseInternalRoute(url) ?: return newMovieLoadResponse(url.substringAfterLast('/'), url, TvType.Anime, url)

        return when (route.source) {
            "otakudesu" -> when (route.kind) {
                "anime" -> loadOtakudesuAnime(route.slug, url)
                "episode" -> loadEpisodeMovie("otakudesu", route.slug, url)
                else -> null
            }
            "donghua" -> loadEpisodeMovie("donghua", route.slug, url)
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val route = parseInternalRoute(data)
        var emitted = false
        val emittedKeys = linkedSetOf<String>()

        fun mark(url: String): Boolean = emittedKeys.add(url.substringBefore("#"))

        suspend fun emitDirect(rawUrl: String, referer: String, label: String = name): Boolean {
            val clean = rawUrl.cleanMediaUrl().toAbsoluteUrl(mainUrl)
            if (!clean.isDirectMedia()) return false
            if (!mark(clean)) return true

            callback(newExtractorLink(name, label, clean, if (clean.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = getQualityFromName("$label $clean")
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                )
            })
            emitted = true
            return true
        }

        suspend fun inspectCandidate(rawUrl: String?, referer: String, label: String = name, depth: Int = 0) {
            if (rawUrl.isNullOrBlank() || depth > 2) return
            val url = rawUrl.cleanMediaUrl().toAbsoluteUrl(mainUrl)
            if (url.isBlank()) return

            if (emitDirect(url, referer, label)) return

            runCatching {
                if (loadExtractor(url, referer, subtitleCallback, callback)) emitted = true
            }

            if (depth >= 2 || url.shouldNotRefetch()) return

            val response = runCatching { app.get(url, headers = htmlHeaders, referer = referer, timeout = 20000L) }.getOrNull() ?: return
            val html = response.text
            val document = response.document

            document.select("iframe[src], embed[src], video[src], source[src]")
                .mapNotNull { it.attr("src").ifBlank { null } }
                .forEach { inspectCandidate(it, response.url, label, depth + 1) }

            extractMediaUrls(html).forEach { inspectCandidate(it, response.url, label, depth + 1) }
        }

        when (route?.source) {
            "donghua" -> {
                val episode = apiData("/api/donghua/episode/${route.slug}")
                inspectCandidate(episode.string("hls_url"), data, "KanatAnime Donghua HLS")
                episode.array("servers").forEach { server ->
                    inspectCandidate(server.string("src"), data, server.string("name") ?: "KanatAnime Server")
                }
            }
            "otakudesu" -> {
                val episode = apiData("/api/otakudesu/episode/${route.slug}")
                inspectCandidate(episode.string("streamUrl"), data, "KanatAnime Default")

                val mirrors = episode.get("mirrors")
                if (mirrors != null && mirrors.isObject) {
                    val fields = mirrors.fields()
                    while (fields.hasNext()) {
                        val entry = fields.next()
                        val qualityName = entry.key.removePrefix("m")
                        if (entry.value.isArray) {
                            entry.value.forEach { mirror ->
                                val content = mirror.string("content")
                                val mirrorName = mirror.string("name") ?: "Mirror"
                                if (!content.isNullOrBlank()) {
                                    val resolved = resolveOtakudesuMirror(content, route.slug)
                                    inspectCandidate(resolved, data, "$mirrorName $qualityName")
                                }
                            }
                        }
                    }
                }

                episode.array("downloads").forEach { quality ->
                    val qualityLabel = quality.string("quality") ?: "Download"
                    quality.array("links").forEach { link ->
                        inspectCandidate(link.string("url"), data, "${link.string("name") ?: "Link"} $qualityLabel")
                    }
                }
            }
            else -> inspectCandidate(data, mainUrl, name)
        }

        return emitted
    }

    private suspend fun apiData(path: String): JsonNode {
        val text = app.get("$mainUrl$path", headers = apiHeaders, referer = "$mainUrl/").text
        val root = jsonMapper.readTree(text)
        return root.get("data") ?: root
    }

    private suspend fun resolveOtakudesuMirror(content: String, episodeSlug: String): String? {
        val encoded = URLEncoder.encode(content, "UTF-8")
        val path = "/api/otakudesu/resolve-mirror?content=$encoded&episode=$episodeSlug"
        return runCatching { apiData(path).string("url") }.getOrNull()
    }

    private suspend fun parseScheduleItems(): List<SearchResponse> {
        val data = apiData("/api/otakudesu/schedule")
        if (!data.isArray) return emptyList()
        val results = mutableListOf<SearchResponse>()
        data.forEach { day ->
            val dayName = day.string("day")
            day.array("anime_list").forEach { item ->
                item.toOtakudesuAnimeSearch(dayName)?.let(results::add)
            }
        }
        return results
    }

    private suspend fun fallbackHomeItems(): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        runCatching {
            val otaku = apiData("/api/otakudesu/home")
            results += otaku.array("ongoing").mapNotNull { it.toOtakudesuAnimeSearch() }
            results += otaku.array("complete").mapNotNull { it.toOtakudesuAnimeSearch() }
        }
        runCatching {
            val donghua = apiData("/api/donghua/home")
            results += donghua.array("latest_updates").mapNotNull { it.toDonghuaEpisodeSearch() }
            results += donghua.array("ongoing_series").mapNotNull { it.toDonghuaEpisodeSearch() }
        }
        return results.distinctBy { it.url.normalizedKey() }
    }

    private fun parseFlexibleItems(node: JsonNode, source: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        fun walk(current: JsonNode) {
            when {
                current.isArray -> current.forEach { walk(it) }
                current.isObject -> {
                    val title = current.string("title") ?: current.string("judul") ?: current.string("name")
                    val slug = current.string("slug")
                    if (!title.isNullOrBlank() && !slug.isNullOrBlank()) {
                        val poster = current.string("image") ?: current.string("thumb") ?: current.string("poster")
                        val response = if (source == "donghua" || title.contains("Episode", true)) {
                            createSearch(title, episodeUrl(source, slug), poster, TvType.Anime)
                        } else {
                            createSearch(title, animeUrl(source, slug), poster, TvType.Anime)
                        }
                        results.add(response)
                    }
                    current.fields().forEachRemaining { entry ->
                        if (entry.value.isArray || entry.value.isObject) walk(entry.value)
                    }
                }
            }
        }
        walk(node)
        return results.distinctBy { it.url.normalizedKey() }
    }

    private fun JsonNode.toOtakudesuAnimeSearch(prefix: String? = null): SearchResponse? {
        val title = string("title") ?: string("judul") ?: return null
        val slug = string("slug") ?: return null
        val displayTitle = prefix?.let { "$title • $it" } ?: title
        val poster = string("image") ?: string("poster") ?: string("thumb")
        return createSearch(displayTitle, animeUrl("otakudesu", slug), poster, TvType.Anime)
    }

    private fun JsonNode.toDonghuaEpisodeSearch(): SearchResponse? {
        val title = string("title") ?: return null
        val slug = string("slug") ?: return null
        val poster = string("thumb") ?: string("image") ?: string("poster")
        return createSearch(title, episodeUrl("donghua", slug), poster, TvType.Anime)
    }

    private fun createSearch(title: String, url: String, poster: String?, type: TvType): SearchResponse {
        return newAnimeSearchResponse(title.cleanText(), url, type) {
            posterUrl = poster?.toProxyImage()
            addDubStatus(isDub = false, episodes = episodeNumber(title))
        }
    }

    private suspend fun loadOtakudesuAnime(slug: String, url: String): LoadResponse? {
        val data = apiData("/api/otakudesu/anime/$slug")
        val title = data.string("title") ?: data.string("judul") ?: return null
        val poster = data.string("image")?.toProxyImage()
        val plot = data.string("synopsis")?.cleanText()
        val tags = data.array("genres").mapNotNull { it.asCleanText() }
        val status = statusFromText(data.string("status"))
        val year = yearFromText(data.string("tanggal_rilis"))
        val typeText = data.string("tipe") ?: ""
        val type = when {
            typeText.contains("Movie", true) || title.contains("Movie", true) -> TvType.AnimeMovie
            typeText.contains("OVA", true) || title.contains("OVA", true) -> TvType.OVA
            else -> TvType.Anime
        }

        val episodes = data.array("episodes").mapNotNull { ep ->
            val epTitle = ep.string("title") ?: return@mapNotNull null
            val epSlug = ep.string("slug") ?: return@mapNotNull null
            newEpisode(episodeUrl("otakudesu", epSlug)) {
                name = epTitle.cleanText()
                episode = episodeNumber(epTitle)
            }
        }.sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title.cleanText(), url, type) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.showStatus = status
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newMovieLoadResponse(title.cleanText(), url, type, episodeUrl("otakudesu", slug)) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    private suspend fun loadEpisodeMovie(source: String, slug: String, url: String): LoadResponse? {
        val endpoint = if (source == "donghua") "/api/donghua/episode/$slug" else "/api/otakudesu/episode/$slug"
        val data = apiData(endpoint)
        val title = data.string("title") ?: slug.replace('-', ' ').cleanText()
        return newMovieLoadResponse(title.cleanText(), url, TvType.Anime, episodeUrl(source, slug)) {
            posterUrl = null
            plot = "Streaming via ${if (source == "donghua") "Donghua" else "Otakudesu"} KanatAnime"
        }
    }

    private fun extractMediaUrls(source: String): List<String> {
        val normalized = source.replace("\\/", "/").htmlUnescape().decodeUnicodeEscapes()
        val urls = linkedSetOf<String>()

        Regex("""https?://[^'"<>\s\\]+(?:\.m3u8|\.mp4|\.webm|\.mkv|videoplayback)[^'"<>\s\\]*""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { urls.add(it.value) }

        Regex("""(?:file|url|src|source|iframe|embed)\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { match -> urls.add(match.groupValues[1]) }

        Jsoup.parse(normalized).select("iframe[src], embed[src], video[src], source[src]")
            .mapNotNull { it.attr("src").ifBlank { null } }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun parseInternalRoute(rawUrl: String): InternalRoute? {
        val path = runCatching { URI(rawUrl).path }.getOrNull()?.trim('/') ?: rawUrl.trim('/')
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.size < 3) return null
        val source = parts[0]
        val kind = parts[1]
        val slug = parts.drop(2).joinToString("/")
        if (source !in setOf("otakudesu", "donghua")) return null
        return InternalRoute(source, kind, slug)
    }

    private fun animeUrl(source: String, slug: String): String = "$mainUrl/$source/anime/$slug"
    private fun episodeUrl(source: String, slug: String): String = "$mainUrl/$source/episode/$slug"

    private fun JsonNode.string(name: String): String? = get(name)?.asCleanText()

    private fun JsonNode.array(name: String): List<JsonNode> {
        val node = get(name) ?: return emptyList()
        if (!node.isArray) return emptyList()
        val list = mutableListOf<JsonNode>()
        node.forEach { list.add(it) }
        return list
    }

    private fun JsonNode.asCleanText(): String? {
        if (isMissingNode || isNull) return null
        return asText().cleanText().takeIf { it.isNotBlank() }
    }

    private fun String.toProxyImage(): String {
        val fixed = toAbsoluteUrl(mainUrl)
        val encoded = URLEncoder.encode(fixed, "UTF-8")
        return "$mainUrl/api/images?url=$encoded&w=300&q=85"
    }

    private fun String.cleanText(): String = htmlUnescape()
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.cleanMediaUrl(): String = trim()
        .trim('\'', '"')
        .replace("\\/", "/")
        .htmlUnescape()
        .decodeUnicodeEscapes()
        .trim()

    private fun String.htmlUnescape(): String = replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#x27;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun String.decodeUnicodeEscapes(): String {
        return Regex("""\\u([0-9a-fA-F]{4})""").replace(this) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
    }

    private fun String.toAbsoluteUrl(base: String): String {
        val value = trim()
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http", true) -> value
            value.startsWith("/") -> base.trimEnd('/') + value
            else -> value
        }
    }

    private fun String.isDirectMedia(): Boolean {
        val value = lowercase(Locale.ROOT).substringBefore("#")
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".webm") ||
            value.contains(".mkv") || value.contains("videoplayback")
    }

    private fun String.shouldNotRefetch(): Boolean {
        val host = runCatching { URI(this).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        return listOf("ok.ru", "dailymotion.com", "rumble.com", "mega.nz", "abyssplayer.com", "rubyvidhub.com")
            .any { host.contains(it) }
    }

    private fun String.normalizedKey(): String = substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)

    private fun episodeNumber(text: String?): Int? = text?.let {
        Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun yearFromText(text: String?): Int? = text?.let {
        Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull()
    }

    private fun statusFromText(text: String?): ShowStatus? = when {
        text.isNullOrBlank() -> null
        text.contains("Completed", true) || text.contains("Complete", true) || text.contains("Tamat", true) -> ShowStatus.Completed
        text.contains("Ongoing", true) || text.contains("Berjalan", true) -> ShowStatus.Ongoing
        else -> null
    }

    private data class InternalRoute(val source: String, val kind: String, val slug: String)
}
