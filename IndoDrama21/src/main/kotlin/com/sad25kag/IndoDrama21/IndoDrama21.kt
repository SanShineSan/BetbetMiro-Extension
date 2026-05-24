package com.sad25kag.IndoDrama21

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class IndoDramaLoadData(
    val url: String,
    val episode: Int? = null
)

@CloudstreamPlugin
class IndoDrama21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(IndoDrama21())
    }
}

class IndoDrama21 : MainAPI() {
    override var mainUrl = "https://id.indodrama.net"
    override var name = "IndoDrama21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "" to "Drama Baru",
        "dramaindo/ongoing/" to "Ongoing",
        "dramaindo/complete/" to "Complete",
        "series/type/tv-show/" to "TV Show",
        "series/type/movie/" to "Movie",

        "series/country/south-korea/" to "Drakor",
        "series/country/china/" to "Drachin",
        "series/country/japan/" to "Dorama",
        "series/country/thailand/" to "Drama Thai",

        "group:series/genre/action/|series/genre/adventure/|series/genre/martial-arts/|series/genre/wuxia/" to "Action & Adventure",
        "group:series/genre/comedy/|series/genre/family/|series/genre/sitcom/|series/genre/food/" to "Comedy & Family",
        "group:series/genre/drama/|series/genre/life/|series/genre/melodrama/|series/genre/friendship/" to "Drama & Life",
        "group:series/genre/romance/|series/genre/youth/|series/genre/school/" to "Romance & Youth",
        "group:series/genre/fantasy/|series/genre/supernatural/|series/genre/magic/|series/genre/vampire/" to "Fantasy & Supernatural",
        "group:series/genre/mystery/|series/genre/thriller/|series/genre/suspense/|series/genre/detective/|series/genre/investigation/" to "Mystery & Thriller",
        "group:series/genre/crime/|series/genre/law/|series/genre/police/|series/genre/political/" to "Crime & Law",
        "group:series/genre/medical/|series/genre/business/|series/genre/music/" to "Career & Medical",
        "series/genre/historical/" to "Historical",
        "series/type/ost/" to "OST"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val paths = groupPaths(request.data)
        val items = linkedMapOf<String, SearchResponse>()
        var hasNext = false

        paths.forEach { path ->
            val url = buildPageUrl(path, page)
            val document = runCatching {
                app.get(
                    url,
                    headers = headers,
                    timeout = 30L
                ).document
            }.getOrNull() ?: return@forEach

            parseCards(document).forEach { item ->
                items[item.url] = item
            }

            hasNext = hasNext || hasNextPage(document, page)
        }

        return newHomePageResponse(
            request.name,
            items.values.toList(),
            hasNext = hasNext
        )
    }

    private fun groupPaths(data: String): List<String> {
        return if (data.startsWith("group:")) {
            data.removePrefix("group:")
                .split("|")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        } else {
            listOf(data)
        }
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val clean = path.trim('/')

        return when {
            clean.isBlank() && page <= 1 -> mainUrl
            clean.isBlank() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$clean/"
            else -> "$mainUrl/$clean/page/$page/"
        }
    }

    private fun hasNextPage(
        document: Document,
        page: Int
    ): Boolean {
        return document.selectFirst(
            "a.next, " +
                "a[rel=next], " +
                ".pagination a:contains(Next), " +
                ".nav-links a:contains(Next), " +
                ".page-numbers.next, " +
                "a[href*='/page/${page + 1}/']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a), " +
                ".post:has(a), " +
                ".item:has(a), " +
                ".result-item:has(a), " +
                ".listupd article:has(a), " +
                ".serieslist article:has(a), " +
                ".bs:has(a), " +
                ".ml-item:has(a), " +
                ".content article:has(a)"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        if (results.isEmpty()) {
            document.select("h2 a[href], h3 a[href], a[href]:has(img)").forEach { element ->
                element.toSearchResult()?.let { item ->
                    results[item.url] = item
                }
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                "h2 a[href], " +
                    "h3 a[href], " +
                    ".entry-title a[href], " +
                    ".title a[href], " +
                    "a[href]:has(img), " +
                    "a[href]"
            ) ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")

        val title = listOf(
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst(".title")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Download", true) &&
                !it.equals("Trailer", true) &&
                !it.equals("Home", true) &&
                !it.equals("Drama List", true)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val poster = fixUrlNull(image?.getImageAttr())
            ?.takeIf { !isBadImage(it) }

        val type = guessType(
            url = href,
            text = text(),
            title = title
        )

        return if (type == TvType.Movie) {
            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                href,
                type
            ) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()

        if (path.isBlank()) return true

        val blockedPrefixes = listOf(
            "drama-list",
            "dramaindo",
            "series/genre",
            "genre",
            "tag",
            "author",
            "page",
            "search",
            "wp-content",
            "wp-json",
            "wp-admin",
            "privacy",
            "dmca",
            "contact",
            "sitemap",
            "feed"
        )

        return blockedPrefixes.any {
            path == it.trimEnd('/') || path.startsWith(it)
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val keyword = query.trim()

        if (keyword.isBlank()) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val results = parseCards(document)
            .distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = hasNextPage(document, page)
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val title = document.selectFirst("h1.entry-title, h1")
            ?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/")
                .replace("-", " ")
                .cleanTitle()

        val poster = getPoster(document)

        val plot = document.selectFirst(
            ".entry-content p, " +
                ".sinopsis p, " +
                ".sinopsis, " +
                ".desc, " +
                ".description, " +
                ".summary, " +
                "article p"
        )?.text()
            ?.trim()
            ?.takeIf { it.length > 30 }

        val htmlText = document.text()
        val metadata = parseMetadata(document)
        val year = metadata["Year"]?.toIntOrNull()
            ?: metadata["Tahun"]?.toIntOrNull()
            ?: extractYear(title)
            ?: extractYear(htmlText)

        val duration = parseDuration(metadata["Duration"] ?: htmlText)
        val rating = metadata["Score"] ?: parseRating(htmlText)

        val tags = (metadata["Genre"]?.split(",").orEmpty() +
            document.select("a[href*='/series/genre/'], a[href*='/genre/'], a[href*='/tag/']")
                .map { it.text().trim() })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = (metadata["Cast"]?.split(",").orEmpty() +
            document.select("a[href*='/cast/'], a[href*='/actor/']")
                .map { it.text().trim() })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { Actor(it) }

        val recommendations = document.select(
            ".related article:has(a), " +
                ".serieslist article:has(a), " +
                ".listupd article:has(a), " +
                "article:has(a)"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        val episodes = parseEpisodeLinks(document, url, poster)
        val typeText = metadata["Type"].orEmpty()
        val type = when {
            typeText.contains("movie", true) -> TvType.Movie
            title.contains("movie", true) -> TvType.Movie
            tags.any { it.contains("Movie", true) } -> TvType.Movie
            episodes.size <= 1 && metadata["Episode"].isNullOrBlank() -> TvType.Movie
            else -> TvType.AsianDrama
        }

        return if (type == TvType.Movie && episodes.size <= 1) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            val finalEpisodes = episodes.ifEmpty {
                listOf(
                    newEpisode(url) {
                        name = title
                        episode = 1
                        posterUrl = poster
                        description = plot
                        duration?.let { runTime = it }
                    }
                )
            }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.AsianDrama,
                finalEpisodes
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }

    private fun parseMetadata(document: Document): Map<String, String> {
        val map = linkedMapOf<String, String>()

        document.select("li, p, span, div").forEach { element ->
            val text = element.text().trim()
            val parts = text.split(":", limit = 2)

            if (parts.size == 2) {
                val key = parts[0].trim()
                    .replace(Regex("""\s+"""), " ")
                val value = parts[1].trim()
                    .replace(Regex("""\s+"""), " ")

                if (
                    key.length in 2..30 &&
                    value.isNotBlank() &&
                    key.matches(Regex("""(?i)(title|original title|genre|cast|year|duration|type|episode|country|director|rating|score|tahun)"""))
                ) {
                    map[key] = value
                }
            }
        }

        return map
    }

    private fun parseEpisodeLinks(
        document: Document,
        currentUrl: String,
        poster: String?
    ): List<Episode> {
        val episodes = linkedMapOf<Int, Episode>()

        document.select("h2, h3, h4, h5, strong, b").forEach { element ->
            val text = element.text().trim()
            val epNumber = extractEpisodeNumber(text, currentUrl)

            if (
                epNumber != null &&
                text.contains("episode", true) &&
                !text.contains("download", true)
            ) {
                episodes[epNumber] = newEpisode(
                    IndoDramaLoadData(currentUrl, epNumber).toJson()
                ) {
                    name = "Episode $epNumber"
                    episode = epNumber
                    posterUrl = poster
                }
            }
        }

        val metadataCount = parseMetadata(document)["Episode"]
            ?.replace(Regex("""\D+"""), "")
            ?.toIntOrNull()
            ?: 0

        if (episodes.isEmpty() && metadataCount > 1) {
            for (episodeNumber in 1..metadataCount) {
                episodes[episodeNumber] = newEpisode(
                    IndoDramaLoadData(currentUrl, episodeNumber).toJson()
                ) {
                    name = "Episode $episodeNumber"
                    episode = episodeNumber
                    posterUrl = poster
                }
            }
        }

        if (episodes.isEmpty()) {
            episodes[1] = newEpisode(
                IndoDramaLoadData(currentUrl, null).toJson()
            ) {
                name = "Movie"
                episode = 1
                posterUrl = poster
            }
        }

        return episodes.values.sortedBy { it.episode ?: 1 }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = runCatching {
            parseJson<IndoDramaLoadData>(data)
        }.getOrNull()

        val pageUrl = loadData?.url ?: data
        val selectedEpisode = loadData?.episode

        val response = app.get(
            pageUrl,
            headers = headers,
            referer = mainUrl,
            timeout = 30L
        )

        val html = response.text.cleanEscaped()
        val scopedHtml = selectedEpisode
            ?.let { extractEpisodeScopeHtml(html, it) }
            ?.takeIf { it.isNotBlank() }
            ?: html

        val document = Jsoup.parse(scopedHtml, pageUrl)

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        collectPlayableCandidates(
            document = document,
            html = scopedHtml,
            baseUrl = pageUrl,
            directLinks = directLinks,
            embedLinks = embedLinks
        )

        var found = false

        directLinks
            .filterNot { isAdUrl(it) }
            .distinct()
            .sortedWith(compareBy<String> { if (isHlsLike(it)) 0 else 1 }.thenBy { hostPriority(it) })
            .forEach { link ->
                emitDirectLink(
                    link = link,
                    referer = pageUrl,
                    callback = callback
                )
                found = true
            }

        for (embed in embedLinks.distinct().sortedBy { hostPriority(it) }.take(24)) {
            val success = loadExtractor(
                embed,
                pageUrl,
                subtitleCallback,
                callback
            )

            if (success) {
                found = true
                continue
            }

            if (isGoogleDrive(embed)) {
                emitGoogleDriveFallback(
                    url = embed,
                    referer = pageUrl,
                    callback = callback
                )
                found = true
                continue
            }

            resolveNestedLinks(embed, pageUrl).forEach { nested ->
                val fixed = normalizeUrl(nested, embed).replace(".txt", ".m3u8")

                when {
                    isAdUrl(fixed) -> Unit

                    isHlsLike(fixed) ||
                        fixed.contains(".mp4", true) ||
                        fixed.contains(".webm", true) -> {
                        emitDirectLink(
                            link = fixed,
                            referer = embed,
                            callback = callback
                        )
                        found = true
                    }

                    isGoogleDrive(fixed) -> {
                        emitGoogleDriveFallback(
                            url = fixed,
                            referer = embed,
                            callback = callback
                        )
                        found = true
                    }

                    fixed.startsWith("http", true) -> {
                        val nestedSuccess = loadExtractor(
                            fixed,
                            embed,
                            subtitleCallback,
                            callback
                        )

                        if (nestedSuccess) found = true
                    }
                }
            }
        }

        return found
    }

    private fun collectPlayableCandidates(
        document: Document,
        html: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        document.select(
            "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src], " +
                "embed[src], " +
                "source[src], " +
                "video[src], " +
                "video source[src], " +
                "a[href]"
        ).forEach { element ->
            val raw = element.attr("data-litespeed-src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            val label = element.parent()?.text()?.lowercase().orEmpty() + " " + element.text().lowercase()

            if (
                raw.isNotBlank() &&
                !raw.startsWith("#") &&
                !raw.startsWith("javascript", true) &&
                !label.contains("trailer") &&
                !raw.contains("youtube.com", true) &&
                !raw.contains("youtu.be", true) &&
                (isLikelyPlayable(raw) || isLikelyDownloadText(label))
            ) {
                addCandidate(raw, baseUrl, directLinks, embedLinks)
            }
        }

        extractPlayableUrls(html).forEach { url ->
            addCandidate(url, baseUrl, directLinks, embedLinks)
        }

        val decodedOnce = runCatching {
            URLDecoder.decode(html, "UTF-8")
        }.getOrDefault(html)

        if (decodedOnce != html) {
            extractPlayableUrls(decodedOnce).forEach { url ->
                addCandidate(url, baseUrl, directLinks, embedLinks)
            }
        }
    }

    private fun extractEpisodeScopeHtml(
        html: String,
        episode: Int
    ): String {
        val regex = Regex(
            """(?is)<h[1-6][^>]*>\s*(?:<[^>]+>\s*)*Episode\s*$episode(?:\D[^<]*)?</h[1-6]>(.*?)(?=<h[1-6][^>]*>\s*(?:<[^>]+>\s*)*Episode\s*\d+|<h[1-6][^>]*>\s*Popular Post|$)"""
        )

        return regex.find(html)
            ?.value
            ?.takeIf { it.contains("href=", true) || it.contains("iframe", true) }
            ?: ""
    }
    private suspend fun resolveNestedLinks(
        url: String,
        referer: String
    ): List<String> {
        val text = runCatching {
            app.get(
                url,
                headers = headers,
                referer = referer,
                timeout = 30L
            ).text.cleanEscaped()
        }.getOrNull().orEmpty()

        if (text.isBlank()) return emptyList()

        return extractPlayableUrls(text)
    }

    private fun addCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (raw.isBlank()) return

        val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl)
            .replace(".txt", ".m3u8")

        if (fixed.isBlank() || isAdUrl(fixed)) return

        when {
            isHlsLike(fixed) ||
                fixed.contains(".mp4", true) ||
                fixed.contains(".webm", true) -> directLinks.add(fixed)

            fixed.startsWith("http", true) &&
                (isKnownHost(fixed) || fixed.contains("embed", true) || fixed.contains("player", true)) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isAdUrl(link)) return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isHlsLike(link)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                this.referer = referer
                this.quality = getQualityFromName(link).takeIf {
                    it != Qualities.Unknown.value
                } ?: qualityFromUrl(link)
            }
        )
    }

    private suspend fun emitGoogleDriveFallback(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = googleDriveId(url) ?: return
        val links = listOf(
            "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t",
            "https://drive.google.com/uc?export=download&id=$id"
        )

        links.forEachIndexed { index, directUrl ->
            callback(
                newExtractorLink(
                    source = "$name GDrive",
                    name = if (index == 0) "$name GDrive" else "$name GDrive UC",
                    url = directUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Accept" to "*/*"
                    )
                }
            )
        }
    }

    private fun isGoogleDrive(url: String): Boolean {
        return url.contains("drive.google.com", true) ||
            url.contains("docs.google.com", true)
    }

    private fun googleDriveId(url: String): String? {
        return Regex("""/file/d/([^/?#]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""[?&]id=([^&#]+)""")
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
    }

    private fun hostPriority(url: String): Int {
        val value = url.lowercase()

        return when {
            value.contains("drive.google") || value.contains("docs.google") -> 0
            value.contains("gsharer") -> 1
            value.contains("hxfile") -> 2
            value.contains("terabox") -> 3
            value.contains("filemoon") -> 4
            value.contains("streamwish") || value.contains("wishfast") -> 5
            value.contains("vidhide") -> 6
            value.contains("mp4upload") -> 7
            value.contains("dood") -> 8
            value.contains("streamtape") -> 9
            value.contains("mixdrop") -> 10
            else -> 50
        }
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.txt)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map {
                runCatching {
                    URLDecoder.decode(it.value, "UTF-8")
                }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoSource|videoUrl|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains(".webm", true) ||
                    isKnownHost(it)
            }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:gd|gdrive|drive|hxfile|uptobox|acefile|anonfiles|krakenfiles|mega|terabox|dood|streamtape|filemoon|vidhide|mp4upload|okru|odnoklassniki|sendvid|streamwish|wishfast|voe|filelions|lulustream|mixdrop)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun isKnownHost(url: String): Boolean {
        val value = url.lowercase()

        return listOf(
            "drive.google",
            "gd",
            "gdrive",
            "gsharer",
            "gdtot",
            "hubdrive",
            "gdflix",
            "hxfile",
            "uptobox",
            "acefile",
            "mega.nz",
            "terabox",
            "dood",
            "streamtape",
            "filemoon",
            "vidhide",
            "mp4upload",
            "ok.ru",
            "odnoklassniki",
            "sendvid",
            "streamwish",
            "wishfast",
            "voe",
            "filelions",
            "lulustream",
            "mixdrop"
        ).any { value.contains(it) }
    }

    private fun isLikelyPlayable(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true) ||
            isKnownHost(url)
    }

    private fun isLikelyDownloadText(text: String): Boolean {
        return text.contains("download") ||
            text.contains("stream") ||
            text.contains("nonton") ||
            text.contains("360p") ||
            text.contains("480p") ||
            text.contains("540p") ||
            text.contains("720p") ||
            text.contains("1080p") ||
            text.contains("gd") ||
            text.contains("hxfile") ||
            text.contains("uptobox")
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
                    ?: mainUrl
                "$origin$clean"
            }

            else -> runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrDefault(clean)
        }
    }

    private fun getPoster(document: Document): String? {
        return fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    ".poster img, " +
                    ".thumb img, " +
                    ".post-thumbnail img, " +
                    "article img, " +
                    "img.wp-post-image, " +
                    "img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    else -> element.getImageAttr()
                }
            }
        )?.takeIf { !isBadImage(it) }
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? {
            if (value.isNullOrBlank()) return null

            return value
                .split(",")
                .map { it.trim().substringBefore(" ") }
                .lastOrNull {
                    it.isNotBlank() &&
                        !isBadImage(it)
                }
        }

        val raw = fromSrcSet(attr("data-srcset"))
            ?: fromSrcSet(attr("data-lazy-srcset"))
            ?: fromSrcSet(attr("srcset"))
            ?: attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:data-full").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }

        return raw
            ?.trim()
            ?.takeIf { !isBadImage(it) }
    }

    private fun isBadImage(url: String): Boolean {
        val value = url.lowercase()

        return value.isBlank() ||
            value.startsWith("data:image") ||
            value.contains("blank") ||
            value.contains("placeholder") ||
            value.contains("default") ||
            value.contains("no-image") ||
            value.contains("noimage") ||
            value.contains("loader") ||
            value.contains("loading") ||
            value.contains("lazy") ||
            value.contains("spacer") ||
            value.contains("logo") ||
            value.contains("favicon") ||
            value.endsWith(".svg")
    }

    private fun guessType(
        url: String,
        text: String,
        title: String
    ): TvType {
        val value = "$url $text $title"

        return when {
            value.contains("movie", true) -> TvType.Movie
            value.contains("variety", true) -> TvType.TvSeries
            value.contains("tv show", true) -> TvType.TvSeries
            value.contains("episode", true) -> TvType.AsianDrama
            value.contains("drama", true) -> TvType.AsianDrama
            else -> TvType.AsianDrama
        }
    }

    private fun extractYear(text: String?): Int? {
        return Regex("""\b(19|20)\d{2}\b""")
            .find(text.orEmpty())
            ?.value
            ?.toIntOrNull()
    }

    private fun parseDuration(text: String?): Int? {
        return Regex("""(\d+)\s*(?:min|menit)""", RegexOption.IGNORE_CASE)
            .find(text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseRating(text: String): String? {
        return Regex("""\b([0-9](?:\.[0-9])?|10(?:\.0)?)\b""")
            .findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull()
    }

    private fun extractEpisodeNumber(
        text: String,
        href: String
    ): Int? {
        return Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find("$text $href")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true)
    }

    private fun isAdUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains("vast") ||
            value.contains("preroll") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("ads")
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("540", true) -> Qualities.P480.value
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

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+Dramaindo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s*Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}