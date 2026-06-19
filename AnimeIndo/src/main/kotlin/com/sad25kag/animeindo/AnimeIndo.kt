package com.sad25kag.animeindo

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class AnimeIndo : MainAPI() {
    override var mainUrl = "https://anime-indo.lol"
    override var name = "AnimeIndo"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Movie)

    private val movieMarker = "animeindo-movie"

    private data class AnimeIndoItem(
        val title: String,
        val url: String,
        val sourceUrl: String,
        val tvType: TvType,
        val poster: String?,
        val episodeNumber: Int?
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Episode Terbaru",
        "$mainUrl/movie/" to "Movie",
        "$mainUrl/genres/action/" to "Action",
        "$mainUrl/genres/adventure/" to "Adventure",
        "$mainUrl/genres/comedy/" to "Comedy",
        "$mainUrl/genres/demons/" to "Demons",
        "$mainUrl/genres/donghua/" to "Donghua",
        "$mainUrl/genres/drama/" to "Drama",
        "$mainUrl/genres/fantasy/" to "Fantasy",
        "$mainUrl/genres/game/" to "Game",
        "$mainUrl/genres/historical/" to "Historical",
        "$mainUrl/genres/horror/" to "Horror",
        "$mainUrl/genres/isekai/" to "Isekai",
        "$mainUrl/genres/magic/" to "Magic",
        "$mainUrl/genres/martial-arts/" to "Martial Arts",
        "$mainUrl/genres/military/" to "Military",
        "$mainUrl/genres/mystery/" to "Mystery",
        "$mainUrl/genres/psychological/" to "Psychological",
        "$mainUrl/genres/reincarnation/" to "Reincarnation",
        "$mainUrl/genres/romance/" to "Romance",
        "$mainUrl/genres/school/" to "School",
        "$mainUrl/genres/sci-fi/" to "Sci-Fi",
        "$mainUrl/genres/seinen/" to "Seinen",
        "$mainUrl/genres/slice-of-life/" to "Slice of Life",
        "$mainUrl/genres/super-power/" to "Super Power",
        "$mainUrl/genres/thriller/" to "Thriller",
        "$mainUrl/genres/vampire/" to "Vampire"
    )

    private val blockedSlugs = setOf(
        "", "anime-indo-lol", "animeindo", "anime-indo", "anime", "list", "list-genre",
        "genre", "genre-list", "movie", "jadwal", "disclaimer", "privacy-police", "privacy-policy"
    )

    private fun buildPageUrl(data: String, page: Int): String {
        val base = data.trimEnd('/')
        return if (page <= 1) data else "$base/page/$page/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isMovie = request.data.contains("/movie/", true)
        val document = app.get(buildPageUrl(request.data, page)).document

        val candidates = document.select(
            ".list-anime, .listupd article, .list-anime-parent > *, .animepost, .bs, .bsx, .post, article, " +
                ".latest a[href], table.otable tr, .item, .ml-item, .movie"
        )

        val items = mutableListOf<AnimeIndoItem>()
        candidates.forEach { element ->
            val item = element.toAnimeIndoItem(preferMovie = isMovie, requireContentUrl = true) ?: return@forEach
            if (items.none { it.url == item.url }) {
                items.add(item)
            }
        }

        val hasNext = document.selectFirst(
            "a.next, a[rel=next], .pagination a[href*='/page/${page + 1}/'], a[href*='/page/${page + 1}/']"
        ) != null

        return newHomePageResponse(
            listOf(HomePageList(request.name, items.map { it.toSearchResponse() }, isHorizontalImages = isMovie)),
            hasNext
        )
    }

    private fun Element.imageAttr(): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        return image?.attr("data-original")?.takeIf { it.isNotBlank() && !it.contains("loading", true) }
            ?: image?.attr("data-src")?.takeIf { it.isNotBlank() && !it.contains("loading", true) }
            ?: image?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("loading", true) }
    }

    private fun Element.bestCard(): Element {
        if (selectFirst("img") != null && selectFirst("a[href]") != null) return this
        return parents().firstOrNull { parent ->
            parent.selectFirst("img") != null && parent.selectFirst("a[href]") != null &&
                parent.text().length in 2..1200
        } ?: this
    }

    private fun normalizeTitle(raw: String): String {
        return raw.replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)\\s*subtitle\\s*indonesia.*$"), "")
            .replace(Regex("(?i)\\s*sub\\s*indo.*$"), "")
            .trim()
            .trim('"')
            .trim()
    }

    private fun isMoviePost(title: String?, url: String): Boolean {
        val value = listOf(title.orEmpty(), url.substringAfterLast('/').replace('-', ' '))
            .joinToString(" ")
            .lowercase()
        return value.contains("movie") || value.contains("film")
    }

    private fun isMovieDetail(document: Document, title: String, url: String): Boolean {
        if (isMoviePost(title, url)) return true

        val text = document.select("div.detail, .spe, .info, .entry-content, main, article")
            .text()
            .replace(Regex("\\s+"), " ")
            .trim()

        return Regex("(?i)\\b(type|tipe|jenis)\\s*:?\\s*movie\\b").containsMatchIn(text) ||
            Regex("(?i)\\bmovie\\b\\s*(?:\\d+\\s*(?:hr|min)|\\d{4})").containsMatchIn(text) ||
            Regex("(?i)\\b(duration|durasi)\\s*:?\\s*\\d+\\s*(?:hr|min|jam|menit)").containsMatchIn(text)
    }

    private fun isBlockedTitle(title: String): Boolean {
        val normalized = title.trim().lowercase()
        return normalized in setOf(
            "list", "genre", "movie", "jadwal", "animeindo", "anime indo", "anime list",
            "genre list", "anime", "popular", "disclaimer", "privacy police", "privacy policy"
        )
    }

    private fun isValidContentUrl(url: String): Boolean {
        val fixed = fixUrl(url).substringBefore("#").trimEnd('/')
        val slug = fixed.substringAfter(mainUrl, "").trim('/').substringAfterLast("/")
        if (!fixed.startsWith(mainUrl)) return false
        if (slug.lowercase() in blockedSlugs) return false
        if (fixed.contains("/genres/", true) || fixed.contains("/genre/", true) || fixed.contains("/tag/", true)) return false
        if (fixed.contains("/page/", true)) return false
        if (fixed.endsWith("/list-genre", true) || fixed.endsWith("/jadwal", true)) return false
        return fixed.contains("/anime/", true) ||
            fixed.contains("/movie/", true) ||
            isEpisodeUrl(fixed) ||
            (!fixed.substringAfter(mainUrl, "").trim('/').contains("/") && slug.length > 3)
    }

    private fun Element.toAnimeIndoItem(
        preferMovie: Boolean = false,
        requireContentUrl: Boolean = true
    ): AnimeIndoItem? {
        val card = bestCard()
        val link = when {
            tagName().equals("a", true) && hasAttr("href") -> this
            card.hasClass("list-anime") -> card.parent()?.takeIf { it.tagName().equals("a", true) && it.hasAttr("href") }
            card.tagName().equals("tr", true) -> card.selectFirst("td.videsc a[href]")
                ?: card.select("a[href]").firstOrNull { a -> isValidContentUrl(a.attr("href")) }
            else -> card.select("a[href]").firstOrNull { a ->
                isValidContentUrl(a.attr("href")) && a.text().trim().isNotBlank()
            } ?: card.parent()?.takeIf { it.tagName().equals("a", true) && it.hasAttr("href") }
                ?: card.select("a[href]").firstOrNull { a -> isValidContentUrl(a.attr("href")) }
                ?: card.selectFirst("a[href]")
        } ?: return null

        val href = link.attr("href").trim()
        if (href.isBlank()) return null
        if (requireContentUrl && !isValidContentUrl(href)) return null

        val fixedHref = fixUrl(href).substringBefore("#")
        val rawTitle = link.attr("title").ifBlank {
            card.selectFirst("td.videsc > a[href], .list-anime p, p, h2, h3, .title, .entry-title")?.text()?.trim().orEmpty()
        }.ifBlank {
            (card.selectFirst("img") ?: link.selectFirst("img"))?.attr("alt")?.trim().orEmpty()
        }.ifBlank {
            link.text().trim()
        }.ifBlank {
            card.selectFirst("h2, h3, .title, .entry-title, p")?.text()?.trim().orEmpty()
        }

        val title = normalizeTitle(rawTitle)
        if (title.length < 2 || isBlockedTitle(title)) return null

        val episodeNumber = parseEpisodeNumber(title).takeIf { isEpisodeUrl(fixedHref) }
        val isMoviePost = isMoviePost(title, fixedHref)
        val isMovieItem = preferMovie || fixedHref.contains("/movie/", true) || isMoviePost
        val resultUrl = when {
            isMovieItem -> "$fixedHref#$movieMarker"
            isEpisodeUrl(fixedHref) -> episodeToAnimeUrl(fixedHref)
            else -> fixedHref
        }
        val poster = (card.imageAttr() ?: link.imageAttr())?.let { fixUrlNull(it) }
        val tvType = when {
            isMovieItem -> TvType.AnimeMovie
            title.contains("ova", true) || title.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return AnimeIndoItem(
            title = cleanupEpisodeTitle(title, episodeNumber, fixedHref),
            url = resultUrl,
            sourceUrl = fixedHref,
            tvType = tvType,
            poster = poster,
            episodeNumber = episodeNumber
        )
    }

    private fun AnimeIndoItem.toSearchResponse(): SearchResponse {
        return newAnimeSearchResponse(title, url, tvType) {
            posterUrl = poster ?: "$mainUrl/wp-content/uploads/2026/01/cropped-animeindo-1.png"
            episodeNumber?.let { addSub(it) }
        }
    }

    private fun cleanupEpisodeTitle(title: String, episodeNumber: Int?, sourceUrl: String): String {
        if (episodeNumber == null || !isEpisodeUrl(sourceUrl)) return title
        return title
            .replace(Regex("(?i)\\s*episode\\s*$episodeNumber(?:\\.0)?\\s*$"), "")
            .replace(Regex("\\s+$episodeNumber\\s*$"), "")
            .trim()
            .takeIf { it.length >= 2 && !isBlockedTitle(it) }
            ?: title
    }

    private fun isEpisodeUrl(url: String): Boolean {
        val slug = url.substringBefore("#").trimEnd('/').substringAfterLast('/')
        return slug.contains(Regex("-episode-\\d+", RegexOption.IGNORE_CASE))
    }

    private fun parseEpisodeNumber(text: String): Int? {
        return Regex("(?:episode\\s*)?(\\d+)(?:\\.\\d+)?", RegexOption.IGNORE_CASE)
            .findAll(text)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun episodeToAnimeUrl(url: String): String {
        val slug = url.substringBefore("#").trimEnd('/').substringAfterLast("/")
        val animeSlug = Regex("-episode-\\d+(?:\\.\\d+)?.*$", RegexOption.IGNORE_CASE).replace(slug, "")
        return "$mainUrl/anime/$animeSlug/"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "-")
        val document = app.get("$mainUrl/search/$encodedQuery/").document
        val results = mutableListOf<AnimeIndoItem>()

        document.select(
            ".list-anime, .listupd article, .list-anime-parent > *, .animepost, .bs, .bsx, article, .post, " +
                ".latest a[href], table.otable tr, .item, .ml-item, main a[href]"
        ).forEach { element ->
            val item = element.toAnimeIndoItem(requireContentUrl = true) ?: return@forEach
            if (item.url.substringBefore("#").trimEnd('/') != mainUrl && results.none { it.url == item.url }) {
                results.add(item)
            }
        }

        return results.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.substringBefore("#")
        val forcedMovie = url.substringAfter("#", "").equals(movieMarker, ignoreCase = true)
        val initialDocument = app.get(cleanUrl).document
        val episodePage = isEpisodeUrl(cleanUrl)
        val initialTitle = normalizeTitle(initialDocument.selectFirst("h1.title, h1.entry-title, h1, h2")?.text().orEmpty())
        val moviePost = forcedMovie || (episodePage && isMoviePost(initialTitle, cleanUrl))
        val animeUrl = if (cleanUrl.contains("/anime/", true) || moviePost) {
            cleanUrl
        } else if (episodePage) {
            initialDocument.selectFirst("div.navi a[href*=/anime/], a[href*=/anime/]")
                ?.attr("href")
                ?.let { fixUrl(it) }
                ?: episodeToAnimeUrl(cleanUrl)
        } else {
            cleanUrl
        }

        val document = if (animeUrl == cleanUrl) initialDocument else app.get(animeUrl).document

        val fallbackTitle = if (episodePage) {
            initialTitle
                .replace(Regex("(?i)\\s*episode\\s*\\d+.*$"), "")
                .trim()
                .takeIf { it.isNotBlank() && !isBlockedTitle(it) }
        } else {
            null
        }

        val title = document.selectFirst("h1.title, h2.title, h1.entry-title, h1, h2, .entry-title")
            ?.text()
            ?.trim()
            ?.removePrefix("#")
            ?.let { normalizeTitle(it) }
            ?.takeIf { it.isNotBlank() && !isBlockedTitle(it) }
            ?: fallbackTitle
            ?: throw ErrorLoadingException("Judul tidak ditemukan")

        val poster = document.selectFirst("div.detail img, td.vithumb img, .thumb img, .poster img, main img, article img")
            ?.imageAttr()
            ?.let { fixUrl(it) }

        val description = document.selectFirst("div.detail p, p.des, .entry-content p, .entry-content, main p, article p")
            ?.text()
            ?.trim()

        val rawGenres = document.select("div.detail li a, .genredesc a, a[href*=/genres/], a[href*=/genre/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val mappedGenres = rawGenres.map { AnimeIndoTagCategory.getCategoryByTag(it) }.distinct()

        val episodes = document.select("div.ep a[href], .episode-list a[href], a[href]")
            .mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                if (!isEpisodeUrl(href)) return@mapNotNull null
                val epText = a.text().trim().ifBlank { href.trimEnd('/').substringAfterLast("/") }
                val ep = parseEpisodeNumber(epText) ?: parseEpisodeNumber(href)
                newEpisode(href) {
                    this.name = ep?.let { "Episode $it" } ?: epText
                    this.episode = ep
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        val movieDetail = forcedMovie || moviePost || isMovieDetail(document, title, animeUrl)
        if (movieDetail || (episodes.isEmpty() && !cleanUrl.contains("/anime/", true) && !episodePage)) {
            val movieWatchLink = when {
                episodePage -> cleanUrl
                hasPlayableInlinePlayer(document) -> cleanUrl
                episodes.isNotEmpty() -> episodes.sortedBy { it.episode ?: Int.MAX_VALUE }.first().data
                else -> findFirstWatchLink(document)
            }
            val movieData = movieWatchLink ?: cleanUrl

            return newMovieLoadResponse(title, cleanUrl, TvType.AnimeMovie, movieData) {
                posterUrl = poster
                plot = description
                this.tags = mappedGenres
            }
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), null, true)

        return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            addEpisodes(DubStatus.Subbed, episodes)
            plot = description
            this.tags = mappedGenres
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    private fun shouldDecodeWholePlayerUrl(value: String): Boolean {
        val lower = value.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//")) return false
        return lower.contains("http%3a%2f%2f") ||
            lower.contains("https%3a%2f%2f") ||
            lower.contains("%2f")
    }

    private fun cleanPlayerUrl(rawUrl: String?): String? {
        var cleaned = rawUrl
            ?.trim()
            ?.replace("&amp;", "&")
            ?.replace("\\/", "/")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        if (shouldDecodeWholePlayerUrl(cleaned)) {
            repeat(2) {
                cleaned = runCatching { URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
                    .replace("&amp;", "&")
                    .replace("\\/", "/")
            }
        }

        cleaned = cleaned.trim().trim('"', '\'', '`', ';')
        if (cleaned == "#" || cleaned.startsWith("javascript:", true) || cleaned.startsWith("mailto:", true)) {
            return null
        }

        return when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "$mainUrl$cleaned"
            else -> cleaned
        }.substringBefore("#").trim()
    }

    private enum class ServerType(val label: String) {
        BTUBE("B-TUBE"),
        GDRIVE("GDRIVE"),
        MP4("MP4"),
        YUP("YUP"),
        CEPAT("CEPAT")
    }

    private data class ServerCandidate(
        val type: ServerType,
        val url: String,
        val label: String? = null
    )

    private fun serverTypeFromLabel(label: String?): ServerType? {
        val lower = label.orEmpty().lowercase()
        return when {
            lower.contains("b-tube") || lower.contains("btube") -> ServerType.BTUBE
            lower.contains("gdrive") || lower.contains("g-drive") || lower.contains("google drive") -> ServerType.GDRIVE
            lower.contains("mp4") -> ServerType.MP4
            lower.contains("yup") -> ServerType.YUP
            lower.contains("cepat") || lower.contains("xtwap") -> ServerType.CEPAT
            else -> null
        }
    }

    private fun serverTypeFromUrl(url: String?): ServerType? {
        val lower = url.orEmpty().lowercase()
        return when {
            lower.contains("btube3.php") || lower.contains("b-tube") || lower.contains("btube") -> ServerType.BTUBE
            lower.contains("gdriveplayer") || lower.contains("gdplayer") -> ServerType.GDRIVE
            lower.contains("mp4upload") -> ServerType.MP4
            lower.contains("yup.php") || lower.contains("/yup") || lower.contains("?yup") || lower.contains("&yup") -> ServerType.YUP
            lower.contains("xtwap") || lower.contains("cepat") -> ServerType.CEPAT
            else -> null
        }
    }

    private fun hasPlayableInlinePlayer(document: Document): Boolean {
        val candidates = mutableListOf<ServerCandidate>()
        collectServerCandidates(document, candidates)
        return candidates.isNotEmpty()
    }

    private fun findFirstWatchLink(document: Document): String? {
        return document.select("div.ep a[href], .episode-list a[href], a[href]")
            .mapNotNull { a ->
                val href = cleanPlayerUrl(a.attr("href")) ?: return@mapNotNull null
                if (!href.startsWith(mainUrl)) return@mapNotNull null
                if (!isEpisodeUrl(href)) return@mapNotNull null
                href
            }
            .distinct()
            .minWithOrNull(compareBy<String> { parseEpisodeNumber(it) ?: Int.MAX_VALUE }.thenBy { it })
    }

    private fun decodeServerTexts(rawValue: String?): List<String> {
        val raw = rawValue?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val decoded = linkedSetOf<String>()
        var current = raw
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .trim()
        decoded.add(current)

        if (shouldDecodeWholePlayerUrl(current)) {
            repeat(2) {
                current = runCatching { URLDecoder.decode(current, "UTF-8") }.getOrDefault(current)
                    .replace("&amp;", "&")
                    .replace("\\/", "/")
                    .trim()
                decoded.add(current)
            }
        }

        val normalizedBase64 = current
            .substringBefore("#")
            .trim()
            .replace('-', '+')
            .replace('_', '/')
        if (normalizedBase64.length >= 12 && normalizedBase64.matches(Regex("^[A-Za-z0-9+/=\\s]+$"))) {
            runCatching {
                val bytes = Base64.decode(normalizedBase64, Base64.DEFAULT)
                String(bytes, Charsets.UTF_8)
                    .replace("&amp;", "&")
                    .replace("\\/", "/")
                    .trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { decoded.add(it) }
        }

        return decoded.toList()
    }

    private fun extractServerUrlsFromText(text: String): List<String> {
        val urls = linkedSetOf<String>()
        Regex("""(?i)(?:https?:)?\\?/\\?/[^\"'<>\s]+""")
            .findAll(text)
            .forEach { urls.add(it.value.replace("\\/", "/")) }
        Regex("""(?i)(?:src|file|url|href|data-video|data-url|data-iframe|data-src|data-link|data-href|data-file)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(text)
            .forEach { urls.add(it.groupValues[1].replace("\\/", "/")) }
        return urls.toList()
    }

    private fun addServerCandidate(
        serverCandidates: MutableList<ServerCandidate>,
        rawUrl: String?,
        label: String? = null,
        forcedType: ServerType? = null
    ) {
        val labelType = forcedType ?: serverTypeFromLabel(label)
        decodeServerTexts(rawUrl).forEach { decodedText ->
            val directUrl = cleanPlayerUrl(decodedText)
            val directType = labelType ?: serverTypeFromUrl(directUrl)
            if (!directUrl.isNullOrBlank() && directType != null) {
                serverCandidates.add(ServerCandidate(directType, directUrl, label))
            }

            extractServerUrlsFromText(decodedText).forEach { embedded ->
                val embeddedUrl = cleanPlayerUrl(embedded) ?: return@forEach
                val embeddedType = labelType ?: serverTypeFromUrl(embeddedUrl)
                if (embeddedType != null) {
                    serverCandidates.add(ServerCandidate(embeddedType, embeddedUrl, label))
                }
            }
        }
    }

    private fun addServerCandidatesFromText(
        serverCandidates: MutableList<ServerCandidate>,
        text: String?,
        label: String? = null,
        forcedType: ServerType? = null
    ) {
        if (text.isNullOrBlank()) return
        decodeServerTexts(text).forEach { decodedText ->
            extractServerUrlsFromText(decodedText).forEach { url ->
                addServerCandidate(serverCandidates, url, label, forcedType)
            }
        }
    }

    private fun movieEpisodeCandidateUrls(detailUrl: String): List<String> {
        val clean = detailUrl.substringBefore("#").trimEnd('/')
        val slug = clean.substringAfter(mainUrl, "")
            .trim('/')
            .removePrefix("anime/")
            .removePrefix("movie/")
            .trim('/')

        if (slug.isBlank() || slug.contains("/")) return emptyList()

        return listOf(
            "$mainUrl/$slug-episode-1/",
            "$mainUrl/$slug-episode-01/",
            "$mainUrl/$slug-movie/"
        )
    }

    private fun collectServerCandidates(document: Document, serverCandidates: MutableList<ServerCandidate>) {
        document.select(
            "#tontonin[src], iframe#tontonin[src], .player iframe[src], .video iframe[src], " +
                "source[src], video[src]"
        ).forEach { element ->
            val label = listOf(element.id(), element.className(), element.parent()?.text().orEmpty())
                .joinToString(" ")
            val type = serverTypeFromLabel(label)
            addServerCandidate(serverCandidates, element.attr("src").ifBlank { element.attr("data-src") }, label, type)
        }

        document.select(
            "a.server[data-video], button.server[data-video], [class*=server][data-video], " +
                "[data-video], [data-url], [data-iframe], [data-src], [data-link], [data-href], " +
                "[data-file], option[value]"
        ).forEach { element ->
            val label = listOf(element.text(), element.attr("title"), element.attr("aria-label"), element.id(), element.className())
                .joinToString(" ")
            val type = serverTypeFromLabel(label)
            addServerCandidate(serverCandidates, element.attr("data-video"), label, type)
            addServerCandidate(serverCandidates, element.attr("data-url"), label, type)
            addServerCandidate(serverCandidates, element.attr("data-iframe"), label, type)
            addServerCandidate(serverCandidates, element.attr("data-src"), label, type)
            addServerCandidate(serverCandidates, element.attr("data-link"), label, type)
            addServerCandidate(serverCandidates, element.attr("data-href"), label, type)
            addServerCandidate(serverCandidates, element.attr("data-file"), label, type)
            addServerCandidate(serverCandidates, element.attr("value"), label, type)
            addServerCandidatesFromText(serverCandidates, element.outerHtml(), label, type)
        }

        document.select(
            ".server[onclick], .servers [onclick], .player [onclick], .video [onclick], " +
                "a.server[href], .server a[href], .servers a[href], div.navi a[href], .navi a[href], " +
                ".download a[href], .downloads a[href], a[href*='download.php?link='], a[href*='gdriveplayer'], " +
                "a[href*='gdplayer'], a[href*='mp4upload'], a[href*='btube'], a[href*='xtwap'], a[href*='yup']"
        ).forEach { element ->
            val label = listOf(element.text(), element.attr("title"), element.attr("aria-label"), element.id(), element.className())
                .joinToString(" ")
            val type = serverTypeFromLabel(label)
            addServerCandidatesFromText(serverCandidates, element.attr("onclick"), label, type)
            addServerCandidate(serverCandidates, element.attr("href"), label, type)
        }

        document.select("#tontonin, .server, .servers, .navi, .download, .downloads, .player, .video")
            .forEach { element ->
                val label = listOf(element.text(), element.id(), element.className()).joinToString(" ")
                val type = serverTypeFromLabel(label)
                addServerCandidatesFromText(serverCandidates, element.outerHtml(), label, type)
            }
    }

    private fun resolveXtwapChildUrl(baseUrl: String, childUrl: String): String {
        val child = childUrl.trim().replace("&amp;", "&")
        return when {
            child.startsWith("http", true) -> child
            child.startsWith("//") -> "https:$child"
            child.startsWith("/") -> "https://xtwap.top$child"
            else -> "https://xtwap.top/$child"
        }
    }

    private fun parseQualityFromXtwap(text: String): Int {
        return Regex("(?i)(?:q=|RESOLUTION=\\d+x)(\\d{3,4})p?")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private suspend fun emitXtwapM3u8Link(
        name: String,
        url: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val quality = parseQualityFromXtwap("$name $url")
        callback(newExtractorLink("AnimeIndo", name, url, type = ExtractorLinkType.M3U8) {
            this.quality = quality
            this.referer = refererUrl
        })
    }

    private suspend fun resolveXtwapLink(
        fullUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerText = app.get(fullUrl, referer = mainUrl).text
            .replace("\r", "")
            .trim()

        if (playerText.startsWith("#EXTM3U")) {
            val lines = playerText.lines().map { it.trim() }.filter { it.isNotBlank() }
            var emitted = false

            lines.forEachIndexed { index, line ->
                if (line.startsWith("#EXT-X-STREAM-INF", true)) {
                    val nextLine = lines.drop(index + 1).firstOrNull { !it.startsWith("#") }
                    if (!nextLine.isNullOrBlank()) {
                        val playlistUrl = resolveXtwapChildUrl(fullUrl, nextLine)
                        val quality = parseQualityFromXtwap("$line $playlistUrl")
                        val label = if (quality > 0) "CEPAT ${quality}p" else "CEPAT"
                        emitXtwapM3u8Link(label, playlistUrl, fullUrl, callback)
                        emitted = true
                    }
                }
            }

            if (!emitted) {
                val labelQuality = parseQualityFromXtwap(fullUrl)
                val label = if (labelQuality > 0) "CEPAT ${labelQuality}p" else "CEPAT"
                emitXtwapM3u8Link(label, fullUrl, fullUrl, callback)
                emitted = true
            }

            return emitted
        }

        val filePath = Regex("""(?i)"file"\s*:\s*"([^"]+)"""").find(playerText)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?: Regex("""(?i)['"]?file['"]?\s*[:=]\s*['"]([^'"]+)['"]""").find(playerText)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("&amp;", "&")

        if (!filePath.isNullOrBlank()) {
            val videoUrl = resolveXtwapChildUrl(fullUrl, filePath)
            val isM3u8 = videoUrl.contains(".m3u8", true)
            if (isM3u8) {
                emitXtwapM3u8Link("CEPAT", videoUrl, fullUrl, callback)
            } else {
                callback(newExtractorLink("AnimeIndo", "CEPAT", videoUrl) {
                    this.quality = parseQualityFromXtwap("$fullUrl $videoUrl")
                    this.referer = fullUrl
                })
            }
            return true
        }

        return false
    }

    private suspend fun emitDirectVideo(
        sourceName: String,
        linkName: String,
        videoUrl: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val isM3u8 = videoUrl.contains(".m3u8", true)
        callback(newExtractorLink(sourceName, linkName, videoUrl,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            this.quality = Qualities.Unknown.value
            this.referer = refererUrl
        })
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.substringBefore("#")
        val document = app.get(cleanData).document
        val serverCandidates = mutableListOf<ServerCandidate>()

        collectServerCandidates(document, serverCandidates)

        if (serverCandidates.isEmpty() && cleanData.contains("/anime/", true)) {
            movieEpisodeCandidateUrls(cleanData).forEach { candidate ->
                try {
                    val candidateDocument = app.get(candidate, referer = cleanData).document
                    collectServerCandidates(candidateDocument, serverCandidates)
                } catch (_: Exception) {
                }
            }
        }

        val distinctServers = serverCandidates
            .distinctBy { "${it.type}:${it.url}" }

        var found = false

        distinctServers.forEach { candidate ->
            val fullUrl = candidate.url
            when (candidate.type) {
                ServerType.YUP -> {
                    try {
                        val playerDoc = app.get(fullUrl, referer = cleanData).document
                        val directVideo = playerDoc.selectFirst("source[src], video[src]")?.attr("src")
                            ?.let { cleanPlayerUrl(it) }
                        if (!directVideo.isNullOrBlank()) {
                            emitDirectVideo("AnimeIndo", "YUP", directVideo, fullUrl, callback)
                            found = true
                            return@forEach
                        }

                        val iframeUrl = playerDoc.selectFirst("#mediaplayer[src], iframe[src]")?.attr("src")
                            ?.let { cleanPlayerUrl(it) }
                        if (!iframeUrl.isNullOrBlank()) {
                            loadExtractor(iframeUrl, fullUrl, subtitleCallback) {
                                found = true
                                callback(it)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                ServerType.BTUBE -> {
                    try {
                        val playerDoc = app.get(
                            fullUrl,
                            referer = cleanData,
                            headers = mapOf("Referer" to cleanData)
                        ).document
                        val directVideo = playerDoc.selectFirst("source[src], video[src]")?.attr("src")
                            ?.let { cleanPlayerUrl(it) }
                        if (!directVideo.isNullOrBlank()) {
                            emitDirectVideo("AnimeIndo", "B-TUBE", directVideo, cleanData, callback)
                            found = true
                            return@forEach
                        }

                        val iframeUrl = playerDoc.selectFirst("iframe[src]")?.attr("src")
                            ?.let { cleanPlayerUrl(it) }
                        if (!iframeUrl.isNullOrBlank()) {
                            loadExtractor(iframeUrl, fullUrl, subtitleCallback) {
                                found = true
                                callback(it)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                ServerType.CEPAT -> {
                    try {
                        if (fullUrl.contains("xtwap", true) || fullUrl.contains("cepat", true)) {
                            if (resolveXtwapLink(fullUrl, callback)) {
                                found = true
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                ServerType.MP4 -> {
                    try {
                        if (fullUrl.endsWith(".mp4", true) || fullUrl.contains(".mp4?", true)) {
                            emitDirectVideo("AnimeIndo", "MP4", fullUrl, cleanData, callback)
                            found = true
                        } else {
                            loadExtractor(fullUrl, cleanData, subtitleCallback) {
                                found = true
                                callback(it)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                ServerType.GDRIVE -> {
                    try {
                        loadExtractor(fullUrl, cleanData, subtitleCallback) {
                            found = true
                            callback(it)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        return found
    }

}

enum class AnimeIndoTagCategory(val title: String, val tagsList: List<String>) {
    ACTION_ADVENTURE("Action & Adventure", listOf("Action", "Adventure", "Martial Arts", "Super Power", "Military")),
    COMEDY("Comedy", listOf("Comedy", "Gag Humor", "Parody")),
    DRAMA_ROMANCE("Drama & Romance", listOf("Drama", "Romance", "Boys Love", "Girls Love", "School")),
    FANTASY_SCIFI("Fantasy & Sci-Fi", listOf("Fantasy", "Sci-Fi", "Supernatural", "Isekai", "Magic", "Demons", "Vampire", "Mecha", "Space", "Time Travel", "Reincarnation")),
    MYSTERY_HORROR("Mystery & Horror", listOf("Mystery", "Thriller", "Suspense", "Detective", "Police", "Psychological", "Horror", "Gore")),
    SLICE_OF_LIFE("Slice of Life", listOf("Slice of Life", "Iyashikei", "Kids", "Workplace")),
    SPORTS_GAMES("Sports & Games", listOf("Sports", "Racing", "Strategy Game", "Game")),
    ARTS_CULTURE("Arts & Music", listOf("Music", "Idol", "Historical", "Performing Arts")),
    MATURE("Mature & Ecchi", listOf("Ecchi", "Echhi", "Harem", "Reverse Harem")),
    DEMOGRAPHICS("Demographics", listOf("Shounen", "Shoujo", "Seinen", "Josei")),
    OTHER("Other", listOf("Donghua"));

    companion object {
        fun getCategoryByTag(tag: String): String {
            return entries.find { category ->
                category.tagsList.any { it.equals(tag, ignoreCase = true) }
            }?.title ?: tag
        }
    }
}
