package com.sad25kag.SobatKeren21

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

@CloudstreamPlugin
class SobatKeren21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SobatKeren21())
    }
}

class SobatKeren21 : MainAPI() {
    override var mainUrl = "https://tv.sobatmov.xyz"
    override var name = "SobatKeren21"
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
        "" to "Terbaru",

        "genre/drama-korea/" to "Drama Korea",
        "genre/drama-china/" to "Drama China",
        "genre/drama-jepang/" to "Drama Jepang",
        "genre/drama-thailand/" to "Drama Thailand",

        "genre/crime/" to "Crime",
        "genre/drama/" to "Drama",
        "genre/family/" to "Family",
        "genre/fantasy/" to "Fantasy",
        "genre/melodrama/" to "Melodrama",
        "genre/mystery/" to "Mystery",
        "genre/romance/" to "Romance",
        "genre/school/" to "School",
        "genre/thriller/" to "Thriller",

        "country/korea/" to "Korea",
        "country/china/" to "China",
        "country/japan/" to "Japan",
        "country/thailand/" to "Thailand",
        "country/taiwan/" to "Taiwan",
        "country/hong-kong/" to "Hong Kong"
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
        val url = buildPageUrl(request.data, page)

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val items = parseCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNextPage(document, page)
        )
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
                ".ml-item:has(a), " +
                ".result-item:has(a), " +
                ".content article:has(a), " +
                ".listupd article:has(a), " +
                ".serieslist article:has(a), " +
                ".bs:has(a), " +
                ".box:has(a)"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        if (results.isEmpty()) {
            document.select(
                "h2 a[href], " +
                    "h3 a[href], " +
                    "a[href]:has(img)"
            ).forEach { element ->
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
                !it.equals("Drama List", true) &&
                !it.equals("Nonton", true)
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
            "genre/",
            "country/",
            "tahun/",
            "tag/",
            "author/",
            "page/",
            "search",
            "wp-content/",
            "wp-json/",
            "wp-admin/",
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
        val text = document.text()
        val meta = parseMetadata(document)

        val plot = document.selectFirst(
            ".entry-content p, " +
                ".sinopsis p, " +
                ".desc p, " +
                ".description p, " +
                ".summary p, " +
                ".entry-content, " +
                ".sinopsis, " +
                ".desc, " +
                ".description, " +
                ".summary"
        )?.text()
            ?.trim()
            ?.takeIf { it.length > 30 }

        val year = meta["Tahun"]?.toIntOrNull()
            ?: meta["Year"]?.toIntOrNull()
            ?: extractYear(title)
            ?: extractYear(text)

        val duration = parseDuration(meta["Durasi"] ?: meta["Duration"] ?: text)
        val rating = meta["Score"] ?: meta["Rating"] ?: parseRating(text)

        val tags = (
            meta["Genre"].orEmpty().split(",") +
                document.select("a[href*='/genre/'], a[href*='/tag/']")
                    .map { it.text().trim() }
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = (
            meta["Pemain"].orEmpty().split(",") +
                meta["Cast"].orEmpty().split(",") +
                document.select("a[href*='/cast/'], a[href*='/actor/'], a[href*='/pemain/']")
                    .map { it.text().trim() }
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { Actor(it) }

        val trailer = document.selectFirst(
            "a[href*='youtube.com'], " +
                "a[href*='youtu.be'], " +
                "iframe[src*='youtube.com'], " +
                "iframe[src*='youtu.be']"
        )?.let { element ->
            element.attr("href").ifBlank { element.attr("src") }
        }?.takeIf { it.isNotBlank() }

        val recommendations = document.select(
            ".related article:has(a), " +
                ".serieslist article:has(a), " +
                ".listupd article:has(a), " +
                ".content article:has(a), " +
                "article:has(a)"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        val episodes = parseEpisodeLinks(document, url, poster)
        val type = guessLoadType(
            url = url,
            text = text,
            title = title,
            tags = tags,
            episodes = episodes
        )

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
                addTrailer(trailer)
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
                type,
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
                addTrailer(trailer)
            }
        }
    }

    private fun parseMetadata(document: Document): Map<String, String> {
        val map = linkedMapOf<String, String>()

        document.select("li, p, div, span").forEach { element ->
            val line = element.text()
                .replace(Regex("""\s+"""), " ")
                .trim()

            val parts = line.split(":", limit = 2)
            if (parts.size != 2) return@forEach

            val key = parts[0].trim()
            val value = parts[1].trim()

            if (
                key.matches(
                    Regex(
                        """(?i)(native title|also known as|screenwriter|director|genres?|genre|tags?|drama|country|episodes?|aired|duration|content rating|tahun|durasi|negara|rilis|jumlah episode|jaringan|pemain|score|rating|type)"""
                    )
                ) &&
                value.isNotBlank() &&
                value.length < 800
            ) {
                map[key] = value
            }
        }

        return map
    }

    private fun parseEpisodeLinks(
        document: Document,
        currentUrl: String,
        poster: String?
    ): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        document.select(
            "a[href*='episode'], " +
                "a[href*='eps'], " +
                "a[href*='download'], " +
                ".episodios a[href], " +
                ".episodes a[href], " +
                ".eplister a[href], " +
                ".les-content a[href], " +
                ".download a[href], " +
                ".entry-content a[href]"
        ).forEachIndexed { index, element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed

            if (!href.startsWith(mainUrl)) return@forEachIndexed
            if (isBlockedUrl(href)) return@forEachIndexed

            val label = element.text().trim()
            val epNumber = extractEpisodeNumber(label, href) ?: index + 1

            episodes[href] = newEpisode(href) {
                name = label.ifBlank { "Episode $epNumber" }.cleanTitle()
                episode = epNumber
                posterUrl = poster
            }
        }

        val text = document.text()

        Regex(
            """Episodes?\s+(\d+)(?:\s*-\s*(\d+))?(?:\s*End)?""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEachIndexed { index, match ->
            val first = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEachIndexed
            val second = match.groupValues.getOrNull(2)?.toIntOrNull()
            val epName = if (second != null) {
                "Episodes $first-$second"
            } else {
                "Episode $first"
            }

            val key = "$currentUrl#episode-$first-${second ?: first}"

            episodes[key] = newEpisode(currentUrl) {
                name = epName
                episode = first
                posterUrl = poster
            }
        }

        if (episodes.isEmpty()) {
            episodes[currentUrl] = newEpisode(currentUrl) {
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
        val cleanData = data.substringBefore("#episode-")

        val response = app.get(
            cleanData,
            headers = headers,
            referer = mainUrl,
            timeout = 30L
        )

        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        document.select(
            "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src], " +
                "embed[src], " +
                "source[src], " +
                "video[src], " +
                "video source[src]"
        ).forEach { element ->
            val raw = element.attr("data-litespeed-src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("src") }
                .trim()

            addCandidate(raw, cleanData, directLinks, embedLinks)
        }

        document.select("a[href]").forEach { element ->
            val href = element.attr("href").trim()
            val label = element.text().lowercase()

            if (
                href.startsWith("#") ||
                href.startsWith("javascript", true) ||
                href.contains("youtube.com", true) ||
                href.contains("youtu.be", true) ||
                label.contains("trailer") ||
                label.contains("subtitle") ||
                label.contains("subscene") ||
                label.contains("indexsubtitle")
            ) {
                return@forEach
            }

            if (isLikelyPlayable(href) || isLikelyPlayableText(label)) {
                addCandidate(href, cleanData, directLinks, embedLinks)
            }
        }

        extractPlayableUrls(html).forEach { raw ->
            addCandidate(raw, cleanData, directLinks, embedLinks)
        }

        var found = false

        directLinks.distinct().forEach { link ->
            emitDirectLink(
                link = link,
                referer = cleanData,
                callback = callback
            )
            found = true
        }

        embedLinks.distinct().forEach { embed ->
            val success = loadExtractor(
                embed,
                cleanData,
                subtitleCallback,
                callback
            )

            if (success) {
                found = true
            } else {
                resolveNestedLinks(embed, cleanData).forEach { nested ->
                    val fixed = normalizeUrl(nested, embed)
                        .replace(".txt", ".m3u8")

                    when {
                        isAdUrl(fixed) -> Unit

                        isHlsLike(fixed) || fixed.contains(".mp4", true) -> {
                            emitDirectLink(
                                link = fixed,
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
        }

        return found
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
            isHlsLike(fixed) || fixed.contains(".mp4", true) -> directLinks.add(fixed)
            fixed.startsWith("http", true) -> embedLinks.add(fixed)
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

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|txt)(?:\?[^"'\\\s<>]*)?""",
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
                    isKnownHost(it)
            }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:zippyshare|fileim|file\.im|gdrive|drive\.google|mp4upload|hxfile|eastream|racaty|solidfiles|userload|uqload|acefile|krakenfiles|dood|streamtape|filemoon|vidhide|vidguard|voe|mixdrop|ok\.ru|odnoklassniki|terabox|mega)[^"'\\\s<>]*""",
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
            "zippyshare",
            "fileim",
            "file.im",
            "gdrive",
            "drive.google",
            "mp4upload",
            "hxfile",
            "eastream",
            "racaty",
            "solidfiles",
            "userload",
            "uqload",
            "acefile",
            "krakenfiles",
            "dood",
            "streamtape",
            "filemoon",
            "vidhide",
            "vidguard",
            "voe",
            "mixdrop",
            "ok.ru",
            "odnoklassniki",
            "terabox",
            "mega.nz"
        ).any { value.contains(it) }
    }

    private fun isLikelyPlayable(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            isKnownHost(url)
    }

    private fun isLikelyPlayableText(text: String): Boolean {
        return text.contains("download") ||
            text.contains("stream") ||
            text.contains("nonton") ||
            text.contains("watch") ||
            text.contains("server") ||
            text.contains("hardsub") ||
            text.contains("raw") ||
            text.contains("360p") ||
            text.contains("480p") ||
            text.contains("540p") ||
            text.contains("720p") ||
            text.contains("1080p") ||
            text.contains("gdrive") ||
            text.contains("mp4upload") ||
            text.contains("hxfile") ||
            text.contains("eastream") ||
            text.contains("acefile")
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
            value.contains("banner") ||
            value.endsWith(".svg")
    }

    private fun guessType(
        url: String,
        text: String,
        title: String
    ): TvType {
        val value = "$url $text $title"

        return when {
            value.contains("movie", true) || value.contains("/movie/", true) -> TvType.Movie
            value.contains("drama korea", true) -> TvType.AsianDrama
            value.contains("drama china", true) -> TvType.AsianDrama
            value.contains("drama jepang", true) -> TvType.AsianDrama
            value.contains("drama thailand", true) -> TvType.AsianDrama
            value.contains("episode", true) -> TvType.AsianDrama
            value.contains("tv show", true) -> TvType.TvSeries
            else -> TvType.AsianDrama
        }
    }

    private fun guessLoadType(
        url: String,
        text: String,
        title: String,
        tags: List<String>,
        episodes: List<Episode>
    ): TvType {
        val value = "$url $text $title ${tags.joinToString(" ")}"

        return when {
            value.contains("movie", true) || value.contains("/movie/", true) -> TvType.Movie
            value.contains("drama korea", true) -> TvType.AsianDrama
            value.contains("drama china", true) -> TvType.AsianDrama
            value.contains("drama jepang", true) -> TvType.AsianDrama
            value.contains("drama thailand", true) -> TvType.AsianDrama
            value.contains("tv show", true) -> TvType.TvSeries
            episodes.size > 1 -> TvType.AsianDrama
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
        return Regex("""(\d+)\s*(?:min|menit|hr)""", RegexOption.IGNORE_CASE)
            .find(text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseRating(text: String): String? {
        return Regex("""(?:score|rating)?\s*([0-9](?:\.[0-9])?|10(?:\.0)?)\s*/\s*10""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""\b([0-9](?:\.[0-9])?|10(?:\.0)?)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
    }

    private fun extractEpisodeNumber(
        text: String,
        href: String
    ): Int? {
        return Regex("""(?:episode|episodes|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find("$text $href")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""Episodes?\s+(\d+)""", RegexOption.IGNORE_CASE)
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
            value.contains("ads") ||
            value.contains("banner")
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
            .replace(Regex("""^\s*Nonton\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\s*Download\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s*Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s*Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Nodrakor.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+SobatKeren21.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}