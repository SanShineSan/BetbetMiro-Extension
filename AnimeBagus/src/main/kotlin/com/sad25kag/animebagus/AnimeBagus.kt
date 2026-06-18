package com.sad25kag.animebagus

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class AnimeBagus : MainAPI() {
    override var mainUrl = "https://tv2.animebagus.com"
    override var name = "AnimeBagus"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Movie)

    private val movieMarker = "animebagus-movie"

    private data class PlayerServer(
        val name: String,
        val url: String
    )

    private val playDataPrefix = "animebagus-playdata::"
    private val playDataSeparator = "||"

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Sedang Populer",
        "$mainUrl/ongoing" to "Sedang Tayang",
        "$mainUrl/movies" to "Movie Terbaru",
        "$mainUrl/release" to "Daftar Lengkap Terbaru",
        "$mainUrl/genre/action" to "Action",
        "$mainUrl/genre/adventure" to "Adventure",
        "$mainUrl/genre/comedy" to "Comedy",
        "$mainUrl/genre/drama" to "Drama",
        "$mainUrl/genre/fantasy" to "Fantasy",
        "$mainUrl/genre/isekai" to "Isekai",
        "$mainUrl/genre/romance" to "Romance",
        "$mainUrl/genre/school" to "School",
        "$mainUrl/genre/slice-of-life" to "Slice of Life",
        "$mainUrl/genre/suspense" to "Suspense"
    )

    private fun buildPageUrl(baseUrl: String, page: Int): String {
        if (page <= 1) return baseUrl
        val clean = baseUrl.trimEnd('/')
        return when {
            clean == mainUrl -> "$mainUrl/release/$page"
            else -> "$clean/$page"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url).document
        val forceMovie = request.data.contains("/movies", ignoreCase = true)
        val items = parseCards(document, forceMovie)

        val hasNext = document.select("a[href]").any { anchor ->
            val href = fixUrl(anchor.attr("href")).trimEnd('/')
            val text = anchor.text()
            text.contains("Selanjutnya", ignoreCase = true) ||
                href == "${request.data.trimEnd('/')}/${page + 1}" ||
                href == "$mainUrl/release/${page + 1}"
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = forceMovie)),
            hasNext
        )
    }

    private fun parseCards(document: Document, forceMovie: Boolean): List<SearchResponse> {
        return document.select(
            "a[itemprop=url][href], a.block[href], article a[href], .anime-card a[href], .grid a[href]"
        )
            .mapNotNull { it.toSearchResponse(forceMovie) }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResponse(forceMovie: Boolean = false): SearchResponse? {
        val href = fixUrl(attr("href")).substringBefore("#").trimEnd('/')
        if (!isValidContentUrl(href)) return null

        val poster = selectFirst("img")?.let { img ->
            img.attr("data-src")
                .ifBlank { img.attr("data-original") }
                .ifBlank { img.attr("src") }
                .takeIf { it.isNotBlank() }
                ?.let { fixUrlNull(it) }
        }

        if (poster.isNullOrBlank() && selectFirst("h3, [itemprop=name], img[alt]") == null) return null

        val rawTitle = selectFirst("h3[itemprop=name], [itemprop=name], h3, .title")
            ?.text()
            .orEmpty()
            .ifBlank { selectFirst("img[alt]")?.attr("alt").orEmpty() }
            .ifBlank { attr("title") }
            .ifBlank { text() }

        val title = normalizeTitle(rawTitle)
            .replace(Regex("(?i)^Nonton\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .trim()

        if (title.length < 2 || isBlockedTitle(title)) return null

        val badgeText = select("span").joinToString(" ") { it.text().trim() }
        val tvType = guessTvType("$badgeText $title $href", forceMovie)
        val episode = select("span")
            .mapNotNull { it.text().trim().takeIf { text -> text.matches(Regex("""\d{1,4}""")) }?.toIntOrNull() }
            .firstOrNull()

        val responseUrl = if (tvType == TvType.AnimeMovie || tvType == TvType.Movie) "$href#$movieMarker" else href

        return newAnimeSearchResponse(title, responseUrl, tvType) {
            posterUrl = poster
            if (episode != null && tvType != TvType.AnimeMovie && tvType != TvType.Movie) addSub(episode)
        }
    }

    private fun guessTvType(value: String, forceMovie: Boolean): TvType {
        val lower = value.lowercase()
        return when {
            forceMovie || lower.contains("movie") || lower.contains("film") -> TvType.AnimeMovie
            lower.contains("ova") || lower.contains("special") -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun normalizeTitle(raw: String?): String {
        return raw.orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('"')
            .trim()
    }

    private fun isBlockedTitle(title: String): Boolean {
        val lower = title.lowercase().trim()
        return lower in setOf(
            "animebagus", "layaranime", "home", "sedang populer", "sedang tayang",
            "movie terbaru", "daftar lengkap terbaru", "genre", "search", "privacy", "dmca"
        )
    }

    private fun isValidContentUrl(rawUrl: String): Boolean {
        val fixed = fixUrl(rawUrl).substringBefore("#").trimEnd('/')
        if (!fixed.startsWith(mainUrl)) return false

        val path = fixed.removePrefix(mainUrl).trim('/')
        if (path.isBlank()) return false

        val blockedPrefixes = listOf(
            "genre/", "search", "release", "ongoing", "movies", "data/", "privacy", "dmca",
            "contact", "schedule", "jadwal"
        )

        if (blockedPrefixes.any { path.startsWith(it, ignoreCase = true) }) return false
        if (path.contains("/episode-", ignoreCase = true)) return true
        if (path.contains("/")) return false

        return path.length > 2
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "$mainUrl/search?q=$encoded",
            "$mainUrl/search?s=$encoded"
        )

        urls.forEach { searchUrl ->
            try {
                val results = parseCards(app.get(searchUrl, referer = mainUrl).document, false)
                if (results.isNotEmpty()) return results
            } catch (_: Exception) {
            }
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.substringBefore("#").trimEnd('/')
        val forcedMovie = url.substringAfter("#", "").equals(movieMarker, ignoreCase = true)
        val animeUrl = if (isEpisodeUrl(cleanUrl)) cleanUrl.substringBeforeLast("/episode-") else cleanUrl

        val document = app.get(animeUrl, referer = mainUrl).document
        val html = document.html()

        val title = normalizeTitle(
            document.selectFirst("h1[itemprop=name], h1, h2")?.text()
                ?: jsonStringValue(html, "name")
        )
            .replace(Regex("(?i)^Nonton\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .trim()
            .takeIf { it.isNotBlank() && !isBlockedTitle(it) }
            ?: throw ErrorLoadingException("Judul AnimeBagus tidak ditemukan")

        val poster = document.selectFirst("img[itemprop=image], .poster img, .thumbnail img, picture img, article img")
            ?.let { img ->
                img.attr("data-src")
                    .ifBlank { img.attr("data-original") }
                    .ifBlank { img.attr("src") }
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrlNull(it) }
            }
            ?: jsonStringValue(html, "image")?.let { fixUrlNull(it) }

        val description = jsonStringValue(html, "description")
            ?: document.selectFirst("[itemprop=description], .synopsis, .description, article p, main p")?.text()?.trim()

        val genres = parseJsonGenres(html).ifEmpty {
            document.select("a[href*=/genre/]").mapNotNull { it.text().trim().takeIf(String::isNotBlank) }.distinct()
        }

        val episodes = buildEpisodes(document, animeUrl)
        val isMovie = forcedMovie || isMovieDocument(document, html, title)

        if (isMovie || episodes.isEmpty()) {
            val playCandidates = buildMoviePlayCandidates(document, cleanUrl, animeUrl, episodes)
            return newMovieLoadResponse(title, cleanUrl, TvType.AnimeMovie, encodePlayData(playCandidates)) {
                posterUrl = poster
                plot = description
                tags = genres
            }
        }

        return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
            engName = title
            posterUrl = poster
            plot = description
            tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun isMovieDocument(document: Document, html: String, title: String): Boolean {
        val value = "$title ${document.text()} $html".lowercase()
        return value.contains("anime movie") ||
            value.contains("\"@type\":\"movie\"") ||
            value.contains("\"@type\": \"movie\"") ||
            Regex("""(?i)\b(type|tipe|jenis)\s*:\s*movie\b""").containsMatchIn(value)
    }

    private fun buildEpisodes(document: Document, animeUrl: String): List<Episode> {
        val cleanBase = animeUrl.trimEnd('/')
        val anchors = document.select("a[href]")
            .mapNotNull { anchor ->
                val href = fixUrl(anchor.attr("href")).substringBefore("#").trimEnd('/')
                if (!isEpisodeUrl(href)) return@mapNotNull null
                val number = parseEpisodeNumber(href) ?: parseEpisodeNumber(anchor.text())
                if (number == null || !href.startsWith(cleanBase)) return@mapNotNull null
                number to href
            }
            .distinctBy { it.first }
            .sortedBy { it.first }

        val latest = latestEpisodeCount(document)
        val generated = if (latest != null && latest > anchors.size && latest >= (anchors.maxOfOrNull { it.first } ?: 0)) {
            (1..latest).map { number -> number to "$cleanBase/episode-$number" }
        } else {
            anchors
        }

        return generated.map { (number, href) ->
            newEpisode(href) {
                name = "Episode $number"
                episode = number
            }
        }
    }

    private fun buildMoviePlayCandidates(
        document: Document,
        cleanUrl: String,
        animeUrl: String,
        episodes: List<Episode>
    ): List<String> {
        val candidates = linkedSetOf<String>()
        val cleanMovieUrl = cleanUrl.substringBefore("#").trimEnd('/')
        val cleanAnimeUrl = animeUrl.substringBefore("#").trimEnd('/')

        // Source evidence shows playable server buttons live on episode/player pages,
        // not necessarily on the movie detail metadata page. Keep all candidates in
        // play data so loadLinks can probe them in one pass before declaring callback 0.
        candidates.add(cleanMovieUrl)
        candidates.add(cleanAnimeUrl)
        collectPlayPageUrls(document, cleanAnimeUrl).forEach(candidates::add)
        episodes.map { it.data.substringBefore("#").trimEnd('/') }.forEach(candidates::add)

        listOf(
            "$cleanAnimeUrl/episode-1",
            "$cleanAnimeUrl/episode-01",
            "$cleanAnimeUrl/episode-0"
        ).forEach(candidates::add)

        return candidates.filter { it.isNotBlank() }.distinct()
    }

    private fun encodePlayData(candidates: List<String>): String {
        val cleaned = candidates
            .map { it.substringBefore("#").trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
        return when {
            cleaned.isEmpty() -> ""
            cleaned.size == 1 -> cleaned.first()
            else -> playDataPrefix + cleaned.joinToString(playDataSeparator)
        }
    }

    private fun decodePlayData(data: String): List<String> {
        val clean = data.trim()
        if (!clean.startsWith(playDataPrefix)) return listOf(clean.substringBefore("#").trimEnd('/'))
        return clean.removePrefix(playDataPrefix)
            .split(playDataSeparator)
            .map { it.substringBefore("#").trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun collectPlayPageUrls(document: Document, pageUrl: String): List<String> {
        val cleanBase = pageUrl.substringBefore("#").trimEnd('/')
        val pageSlug = cleanBase.removePrefix(mainUrl).trim('/').substringBefore('/')
        val candidates = linkedSetOf<String>()

        document.select("a[href], button[href], [data-href], [data-url]").forEach { element ->
            val label = element.text().trim()
            listOf(
                element.attr("href"),
                element.attr("data-href"),
                element.attr("data-url")
            ).forEach rawLoop@ { raw ->
                val fixed = cleanPlayerUrl(raw)?.substringBefore("#")?.trimEnd('/') ?: return@rawLoop
                if (!fixed.startsWith(mainUrl)) return@rawLoop
                if (fixed == cleanBase) return@rawLoop

                val path = fixed.removePrefix(mainUrl).trim('/')
                if (path.isBlank()) return@rawLoop
                if (path.startsWith("genre/", true) || path.startsWith("search", true) ||
                    path.startsWith("release", true) || path.startsWith("ongoing", true) ||
                    path.startsWith("movies", true) || path.startsWith("data/", true)) return@rawLoop

                val sameSlug = pageSlug.isNotBlank() && (path == pageSlug || path.startsWith("$pageSlug/", true))
                val looksPlayable = label.contains("nonton", true) ||
                    label.contains("tonton", true) ||
                    label.contains("putar", true) ||
                    label.contains("play", true) ||
                    label.contains("watch", true) ||
                    label.contains("film", true) ||
                    label.contains("movie", true) ||
                    label.contains("terbaru", true) ||
                    path.contains("/episode-", true) ||
                    fixed.contains("player.tikungan.store", true) ||
                    fixed.contains("rockethls.online", true) ||
                    fixed.contains("abysscdn.com", true)

                if (sameSlug && looksPlayable) candidates.add(fixed)
            }
        }

        return candidates.toList()
    }

    private fun latestEpisodeCount(document: Document): Int? {
        val html = document.html()
        return Regex("""(?i)"numberOfEpisodes"\s*:\s*"?(\d{1,5})"?""").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)Terbaru\s*\((\d{1,5})\)""").find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: document.select("a[href]")
                .mapNotNull { parseEpisodeNumber(it.attr("href")) ?: parseEpisodeNumber(it.text()) }
                .maxOrNull()
    }

    private fun isEpisodeUrl(url: String): Boolean {
        return Regex("""(?i)/episode-\d+(?:\.\d+)?/?$""").containsMatchIn(url.substringBefore("#").trimEnd('/'))
    }

    private fun parseEpisodeNumber(value: String): Int? {
        return Regex("""(?i)episode-(\d{1,5})""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)\bEpisode\s+(\d{1,5})\b""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(\d{1,5})\b""").findAll(value).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseJsonGenres(html: String): List<String> {
        val genreBlock = Regex("""(?is)"genre"\s*:\s*\[(.*?)]""").find(html)?.groupValues?.getOrNull(1)
            ?: return emptyList()
        return Regex(""""([^"]+)"""").findAll(genreBlock)
            .mapNotNull { decodeJsonString(it.groupValues[1]).takeIf(String::isNotBlank) }
            .distinct()
            .toList()
    }

    private fun jsonStringValue(text: String, key: String): String? {
        return Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""").find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { decodeJsonString(it) }
            ?.takeIf { it.isNotBlank() }
    }

    private fun decodeJsonString(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .replace("\\\"", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidatePages = decodePlayData(data)
        val servers = mutableListOf<Pair<String, PlayerServer>>()

        candidatePages.forEach { pageUrl ->
            if (pageUrl.isBlank()) return@forEach
            try {
                val document = app.get(pageUrl, referer = mainUrl).document
                collectPlayerServers(document).forEach { servers.add(pageUrl to it) }

                collectPlayPageUrls(document, pageUrl).forEach { nestedPage ->
                    try {
                        val nestedDocument = app.get(nestedPage, referer = pageUrl).document
                        collectPlayerServers(nestedDocument).forEach { servers.add(nestedPage to it) }
                    } catch (_: Exception) {
                    }
                }

                if (!isEpisodeUrl(pageUrl)) {
                    listOf("$pageUrl/episode-1", "$pageUrl/episode-01", "$pageUrl/episode-0").forEach { fallbackPage ->
                        try {
                            val fallbackDocument = app.get(fallbackPage, referer = pageUrl).document
                            collectPlayerServers(fallbackDocument).forEach { servers.add(fallbackPage to it) }
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        var found = false

        servers.distinctBy { it.second.url }.forEach { (sourcePage, server) ->
            val originalUrl = cleanPlayerUrl(server.url) ?: return@forEach
            val directUrl = unwrapTikunganUrl(originalUrl)

            try {
                val resolved = when {
                    directUrl.contains("player.tikungan.store", ignoreCase = true) ->
                        resolveTikunganBox(server.name, originalUrl, directUrl, callback)

                    directUrl.contains("rockethls.online", ignoreCase = true) ||
                        directUrl.contains("nzn3.org", ignoreCase = true) ||
                        server.name.contains("filemoon", ignoreCase = true) ->
                        resolveRocketHls(server.name, originalUrl, directUrl, subtitleCallback, callback)

                    directUrl.contains("abysscdn.com", ignoreCase = true) ||
                        server.name.contains("hydrax", ignoreCase = true) ->
                        resolveGenericServer(server.name.ifBlank { "HYDRAX" }, directUrl, originalUrl, subtitleCallback, callback) ||
                            resolveGenericServer(server.name.ifBlank { "HYDRAX" }, directUrl, "https://player.tikungan.store/", subtitleCallback, callback)

                    directUrl.endsWith(".m3u8", ignoreCase = true) -> {
                        emitM3u8(server.name, directUrl, sourcePage, callback)
                        true
                    }

                    directUrl.endsWith(".mp4", ignoreCase = true) -> {
                        callback(newExtractorLink(name, server.name.ifBlank { "MP4" }, directUrl) {
                            quality = Qualities.Unknown.value
                            referer = sourcePage
                        })
                        true
                    }

                    else -> resolveGenericServer(server.name, directUrl, sourcePage, subtitleCallback, callback)
                }

                if (resolved) found = true
            } catch (_: Exception) {
            }
        }

        return found
    }

    private fun collectPlayerServers(document: Document): List<PlayerServer> {
        val servers = mutableListOf<PlayerServer>()

        document.select("button.select-player[data-url], .select-player[data-url], [data-url]").forEach { element ->
            val label = element.text().trim()
                .ifBlank { element.attr("title").trim() }
                .ifBlank { detectServerName(element.attr("data-url")) }
            addPlayerServer(servers, label, element.attr("data-url"))
        }

        document.select("iframe#player-frame[src], iframe[src], source[src], video[src]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("data-src") }
            val label = element.attr("title").trim().ifBlank { detectServerName(raw) }
            addPlayerServer(servers, label, raw)
        }

        // Active AnimeBagus pages expose servers via select-player buttons, but movie/detail
        // variants can expose playable URLs as plain anchors/text. Scan anchors and full HTML
        // with strict host filtering so loadLinks does not miss BOX/FILEMOON/HYDRAX.
        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            val label = anchor.text().trim().ifBlank { detectServerName(href) }
            addPlayerServer(servers, label, href)
        }

        document.select("script").forEach { script ->
            addPlayerUrlsFromText(servers, script.data().ifBlank { script.html() })
        }

        addPlayerUrlsFromText(servers, document.select("#player, #players, .player, .server, .servers, .the__content, article, main").outerHtml())
        addPlayerUrlsFromText(servers, document.html())

        return servers.distinctBy { it.url }
    }

    private fun addPlayerServer(servers: MutableList<PlayerServer>, label: String, rawUrl: String?) {
        val url = cleanPlayerUrl(rawUrl) ?: return
        if (!isPlayableUrl(url)) return
        servers.add(PlayerServer(label.ifBlank { detectServerName(url) }, url))
    }

    private fun addPlayerUrlsFromText(servers: MutableList<PlayerServer>, raw: String?) {
        if (raw.isNullOrBlank()) return
        val decoded = decodeUrl(raw)
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\/", "/")

        Regex("""(?i)(?:data-url|src|href|url|file)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(decoded)
            .forEach { match ->
                val url = match.groupValues[1]
                addPlayerServer(servers, detectServerName(url), url)
            }

        Regex("""(?i)https?://[^\s"'<>]+""")
            .findAll(decoded)
            .forEach { match ->
                val url = match.value.trimEnd(',', ')', ';')
                addPlayerServer(servers, detectServerName(url), url)
            }
    }

    private fun isPlayableUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false

        return lower.contains("player.tikungan.store") ||
            lower.contains("rockethls.online") ||
            lower.contains("nzn3.org") ||
            lower.contains("abysscdn.com") ||
            lower.contains("filemoon") ||
            lower.contains("hydrax") ||
            lower.contains("r66nv9ed.com") ||
            lower.contains(".m3u8") ||
            lower.contains(".mp4")
    }

    private fun cleanPlayerUrl(rawUrl: String?): String? {
        var cleaned = rawUrl
            ?.trim()
            ?.replace("&amp;", "&")
            ?.replace("\\/", "/")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        cleaned = decodeUrl(cleaned)

        if (cleaned == "#" || cleaned.startsWith("javascript:", true)) return null

        return when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "$mainUrl$cleaned"
            else -> cleaned
        }.substringBefore("#").trim()
    }

    private fun unwrapTikunganUrl(url: String): String {
        if (!url.contains("player.tikungan.store/iframe", ignoreCase = true)) return url

        val encoded = url.substringAfter("?", "")
            .split("&")
            .mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.firstOrNull().equals("url", ignoreCase = true)) parts.getOrNull(1) else null
            }
            .firstOrNull()
            ?: return url

        return cleanPlayerUrl(decodeUrl(encoded)) ?: url
    }

    private fun decodeUrl(value: String): String {
        var decoded = value
        repeat(2) {
            decoded = runCatching { URLDecoder.decode(decoded, "UTF-8") }.getOrDefault(decoded)
                .replace("&amp;", "&")
                .replace("\\/", "/")
        }
        return decoded
    }

    private fun detectServerName(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("rockethls.online") || lower.contains("nzn3.org") || lower.contains("filemoon") -> "FILEMOON"
            lower.contains("abysscdn.com") || lower.contains("hydrax") -> "HYDRAX"
            lower.contains("player.tikungan.store") -> "BOX"
            lower.contains(".m3u8") -> "HLS"
            lower.contains(".mp4") -> "MP4"
            else -> "Server"
        }
    }

    private suspend fun resolveTikunganBox(
        serverName: String,
        originalUrl: String,
        directUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (directUrl.contains("/iframe", ignoreCase = true)) return false

        val refererUrl = if (originalUrl != directUrl) originalUrl else "https://player.tikungan.store/"
        val playerText = app.get(directUrl, referer = refererUrl).text
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val m3u8 = Regex("""(?i)(?:manifestUri|file|src)\s*[:=]\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
            .find(playerText)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                .find(playerText)
                ?.value

        if (m3u8.isNullOrBlank()) return false

        emitM3u8(serverName.ifBlank { "BOX" }, resolveAgainst(directUrl, m3u8), directUrl, callback)
        return true
    }

    private suspend fun resolveRocketHls(
        serverName: String,
        originalUrl: String,
        directUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        val sourceName = serverName.ifBlank { "FILEMOON" }

        listOf(
            originalUrl to mainUrl,
            originalUrl to "https://player.tikungan.store/",
            directUrl to originalUrl,
            directUrl to "https://player.tikungan.store/"
        ).distinct().forEach { (candidate, referer) ->
            if (!emitted && runCatching { runExtractorLink(sourceName, candidate, referer, subtitleCallback, callback) }.getOrDefault(false)) {
                emitted = true
            }
        }

        val code = Regex("""(?i)/e/([^/?#]+)""").find(directUrl)?.groupValues?.getOrNull(1)
        if (!code.isNullOrBlank()) {
            try {
                val detailsUrl = "https://rockethls.online/api/videos/$code/embed/details"
                val details = app.get(detailsUrl, referer = directUrl).text
                val embedFrameUrl = jsonStringValue(details, "embed_frame_url")
                if (!embedFrameUrl.isNullOrBlank()) {
                    listOf(
                        embedFrameUrl to directUrl,
                        embedFrameUrl to "https://rockethls.online/",
                        embedFrameUrl to originalUrl
                    ).distinct().forEach { (candidate, referer) ->
                        if (!emitted && runCatching { runExtractorLink(sourceName, candidate, referer, subtitleCallback, callback) }.getOrDefault(false)) {
                            emitted = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return emitted
    }

    private suspend fun resolveGenericServer(
        serverName: String,
        url: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        if (runExtractorLink(serverName, url, refererUrl, subtitleCallback, callback)) {
            emitted = true
        }

        if (!emitted) {
            try {
                val page = app.get(url, referer = refererUrl).text
                    .replace("&amp;", "&")
                    .replace("\\/", "/")
                extractDirectMediaUrls(page).forEach { mediaUrl ->
                    when {
                        mediaUrl.contains(".m3u8", ignoreCase = true) -> emitM3u8(serverName, mediaUrl, url, callback)
                        mediaUrl.contains(".mp4", ignoreCase = true) -> callback(newExtractorLink(name, serverName.ifBlank { "MP4" }, mediaUrl) {
                            quality = parseQuality("$serverName $mediaUrl")
                            referer = url
                        })
                    }
                    emitted = true
                }
            } catch (_: Exception) {
            }
        }

        return emitted
    }

    private fun extractDirectMediaUrls(text: String): List<String> {
        val decoded = decodeUrl(text)
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\/", "/")
        val urls = linkedSetOf<String>()
        Regex("""(?i)https?://[^\s"'<>]+(?:\.m3u8|\.mp4)[^\s"'<>]*""")
            .findAll(decoded)
            .forEach { urls.add(it.value.trimEnd(',', ')', ';')) }
        Regex("""(?i)(?:manifestUri|file|src|url)\s*[:=]\s*["']([^"']+(?:\.m3u8|\.mp4)[^"']*)["']""")
            .findAll(decoded)
            .forEach { urls.add(resolveAgainst(mainUrl, it.groupValues[1])) }
        return urls.toList()
    }

    private suspend fun runExtractorLink(
        serverName: String,
        url: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        loadExtractor(url, refererUrl, subtitleCallback) {
            emitted = true
            callback(it)
        }
        return emitted
    }

    private suspend fun emitM3u8(
        serverName: String,
        videoUrl: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(newExtractorLink(name, serverName.ifBlank { "HLS" }, videoUrl, type = ExtractorLinkType.M3U8) {
            quality = parseQuality("$serverName $videoUrl")
            referer = refererUrl
        })
    }

    private fun parseQuality(value: String): Int {
        return Regex("""(?i)(\d{3,4})p""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun resolveAgainst(baseUrl: String, childUrl: String): String {
        val cleanChild = childUrl.trim().replace("&amp;", "&").replace("\\/", "/")
        return when {
            cleanChild.startsWith("http", ignoreCase = true) -> cleanChild
            cleanChild.startsWith("//") -> "https:$cleanChild"
            cleanChild.startsWith("/") -> "${baseUrl.substringBefore("://")}://${baseUrl.substringAfter("://").substringBefore("/")}$cleanChild"
            else -> "${baseUrl.substringBeforeLast("/")}/$cleanChild"
        }
    }
}
