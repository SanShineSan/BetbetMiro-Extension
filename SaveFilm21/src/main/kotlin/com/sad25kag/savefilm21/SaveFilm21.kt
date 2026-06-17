package com.sad25kag.savefilm21

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

class SaveFilm21 : MainAPI() {
    companion object {
        private const val DEFAULT_MAIN_URL = "https://new13.savefilm21info.com"
    }

    override var mainUrl = DEFAULT_MAIN_URL
    override var name = "SaveFilm21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Referer" to "$DEFAULT_MAIN_URL/"
    )

    override val mainPage = mainPageOf(
        "/" to "Film Terbaru",
        "/tv/" to "Series / TV Show",
        "/genre/action/" to "Action",
        "/genre/romance/" to "Romance",
        "/genre/comedy/" to "Comedy",
        "/genre/thriller/" to "Thriller",
        "/genre/adventure/" to "Adventure",
        "/genre/horror/" to "Horror",
        "/genre/drama/" to "Drama",
        "/genre/science-fiction/" to "Science Fiction",
        "/genre/crime/" to "Crime",
        "/genre/fantasy/" to "Fantasy",
        "/genre/mystery/" to "Mystery",
        "/genre/animation/" to "Animation",
        "/genre/war/" to "War",
        "/country/usa/" to "USA",
        "/country/korea/" to "Korea",
        "/country/china/" to "China",
        "/country/thailand/" to "Thailand",
        "/country/japan/" to "Japan",
        "/country/india/" to "India",
        "/country/philippines/" to "Philippines",
        "/year/2026/" to "Tahun 2026",
        "/year/2025/" to "Tahun 2025",
        "/year/2024/" to "Tahun 2024",
        "/year/2023/" to "Tahun 2023",
        "/year/2022/" to "Tahun 2022",
        "/year/2021/" to "Tahun 2021",
        "/year/2020/" to "Tahun 2020"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = runCatching {
            app.get(buildPageUrl(request.data, page), headers = baseHeaders, referer = mainUrl).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val items = parseListing(document)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && hasNextPage(document, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/?s=$encoded&post_type%5B%5D=post&post_type%5B%5D=tv",
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded"
        )

        val results = linkedMapOf<String, SearchResponse>()
        for (url in searchUrls) {
            val document = runCatching {
                app.get(url, headers = baseHeaders, referer = mainUrl).document
            }.getOrNull() ?: continue
            for (item in parseListing(document)) {
                if (item.name.contains(keyword, ignoreCase = true) || keyword.length <= 3) {
                    results[contentKey(item.url)] = item
                }
            }
            if (results.isNotEmpty()) break
        }
        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = url.toAbsoluteUrl(mainUrl) ?: return null
        val response = runCatching {
            app.get(pageUrl, headers = baseHeaders, referer = mainUrl)
        }.getOrNull() ?: return null
        val document = response.document
        val pageText = cleanText(document.text())
        val rawTitle = document.selectFirst("h1.entry-title, h1, .entry-title, meta[property=og:title], title")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        val title = cleanTitle(rawTitle).ifBlank { titleFromUrl(pageUrl) }
        if (title.isBlank()) return null

        val poster = findPoster(document, pageUrl)
        val tags = document
            .select("strong:contains(Genre) ~ a, .gmr-moviedata a[href*='/genre/'], .gmr-movie-on a, a[href*='/genre/']")
            .map { cleanText(it.text()).substringBefore("(").trim() }
            .filter { it.length in 2..40 && !it.equals("Trailer", true) && !it.equals("Tonton", true) }
            .distinct()
            .take(20)
        val actors = document
            .select("span[itemprop=actors] a, a[href*='/cast/'], a[href*='/actor/'], a[href*='/director/'], [itemprop=director] a")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..60 }
            .distinct()
            .take(24)
        val year = document.selectFirst("a[href*='/year/'], time[datetime]")?.text()?.firstYear()
            ?: title.firstYear()
            ?: pageText.firstYear()
        val rating = document.selectFirst("[itemprop=ratingValue], .gmr-rating-item, .rating, .score, .imdb, .vote")
            ?.text()
            ?.replace(',', '.')
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        val duration = Regex("""(?i)(\d{1,3})\s*(?:min|menit|m)\b""").find(pageText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val description = cleanDescription(
            document.selectFirst("meta[property=og:description], meta[name=description], div[itemprop=description] > p, .entry-content p, .post-content p, .description, .desc, .sinopsis, .storyline, [itemprop=description]")
                ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        )
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup, a[href*='youtube.com'], a[href*='youtu.be']")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
        val episodes = parseEpisodes(document, pageUrl)
        val tvType = inferType(pageUrl, title, pageText, episodes.size)

        return if (tvType == TvType.TvSeries && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                addActors(actors)
                addTrailer(trailer)
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                addActors(actors)
                addTrailer(trailer)
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.toAbsoluteUrl(mainUrl) ?: data
        var emitted = false
        val visited = linkedSetOf<String>()

        val serverUrls = listOf(
            pageUrl.substringBefore("?"),
            "${pageUrl.substringBefore("?")}?player=2",
            "${pageUrl.substringBefore("?")}?player=3",
            "${pageUrl.substringBefore("?")}?player=4",
            "${pageUrl.substringBefore("?")}?player=5"
        )

        for (serverUrl in serverUrls) {
            val response = runCatching {
                app.get(serverUrl, headers = baseHeaders, referer = mainUrl)
            }.getOrNull() ?: continue
            val document = response.document
            val html = response.text

            val playerUrls = mutableListOf<String>()

            document.select("a[href*='sf21.'], a[href*='vidplayer'], a[href*='rpmvid'], a[href*='mplay'], a[href*='nontonfree']").forEach { el ->
                el.attr("href").toAbsoluteUrl(serverUrl)?.let { playerUrls.add(it) }
            }

            val playerRegex = Regex("""(https?://[^\s"'<>]+(?:vidplayer|rpmvid|mplay|nontonfree|sf21)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
            playerRegex.findAll(html).forEach { match ->
                match.groupValues.getOrNull(1)?.cleanHtml()?.let { playerUrls.add(it) }
            }

            document.select("iframe[src], iframe[data-src]").forEach { el ->
                el.firstAttr("src", "data-src").toAbsoluteUrl(serverUrl)?.let { playerUrls.add(it) }
            }

            document.select("video source[src], source[src]").forEach { el ->
                el.attr("src").toAbsoluteUrl(serverUrl)?.let { playerUrls.add(it) }
            }

            document.select("track[src], a[href*='.srt'], a[href*='.vtt']").forEach { el ->
                val sub = el.firstAttr("src", "href").toAbsoluteUrl(serverUrl)
                if (sub != null && sub.isSubtitleUrl()) subtitleCallback(newSubtitleFile("Indonesian", sub))
            }

            for (rawUrl in playerUrls.distinct()) {
                if (!visited.add(rawUrl)) continue
                if (rawUrl.isDirectVideoUrl()) {
                    emitDirect(rawUrl, serverUrl, callback)
                    emitted = true
                    continue
                }
                if (rawUrl.isSubtitleUrl()) {
                    subtitleCallback(newSubtitleFile("Indonesian", rawUrl))
                    continue
                }
                var extractorEmitted = false
                runCatching {
                    loadExtractor(rawUrl, serverUrl, subtitleCallback) { link ->
                        extractorEmitted = true
                        emitted = true
                        callback(link)
                    }
                }
                if (!extractorEmitted) {
                    val playerResponse = runCatching {
                        app.get(rawUrl, headers = baseHeaders + mapOf("Referer" to serverUrl), referer = serverUrl, timeout = 15000L)
                    }.getOrNull() ?: continue
                    val playerHtml = playerResponse.text
                    val directRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|webm)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
                    directRegex.findAll(playerHtml).forEach { m ->
                        val direct = m.groupValues.getOrNull(1)?.cleanHtml() ?: return@forEach
                        if (visited.add(direct)) {
                            emitDirect(direct, rawUrl, callback)
                            emitted = true
                        }
                    }
                    val iframeRegex = Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    iframeRegex.findAll(playerHtml).forEach { m ->
                        val nested = m.groupValues.getOrNull(1)?.cleanHtml()?.toAbsoluteUrl(rawUrl) ?: return@forEach
                        if (visited.add(nested) && !nested.isNoiseUrl()) {
                            runCatching {
                                loadExtractor(nested, rawUrl, subtitleCallback) { link ->
                                    emitted = true
                                    callback(link)
                                }
                            }
                        }
                    }
                }
            }
        }

        return emitted
    }

    private suspend fun emitDirect(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val type = if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(
            newExtractorLink(name, name, url, type) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        for (element in document.select(cardSelector)) {
            element.toSearchResult()?.let { results[contentKey(it.url)] = it }
        }
        if (results.size < 6) {
            for (anchor in document.select("article a[href], .post a[href], .item a[href], .movie a[href], .film a[href], .ml-item a[href], .result-item a[href], .entry-title a[href]")) {
                anchor.toSearchResult()?.let { results[contentKey(it.url)] = it }
            }
        }
        return results.values.take(80)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (`is`("a[href]")) this else selectFirst("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], .title a[href], a[href][title], a[href]") ?: return null
        val href = anchor.attr("href").toAbsoluteUrl(mainUrl) ?: return null
        if (!isContentUrl(href)) return null
        val container = anchor.bestContainer()
        val image = container.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[data-wpfc-original-src], img[src]:not([src^='data:'])")
            ?: anchor.selectFirst("img[data-src], img[data-original], img[src]:not([src^='data:'])")
        val title = listOf(
            container.selectFirst("h1, h2, h3, .entry-title, .title, .name")?.text(),
            anchor.attr("aria-label"),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text().takeUnless { it.equals("Tonton", true) || it.equals("Trailer", true) },
            titleFromUrl(href)
        ).firstOrNull { isUsefulTitle(it) }?.let { cleanTitle(it) } ?: return null
        val poster = image?.imageUrl(mainUrl) ?: container.styleImage(mainUrl) ?: anchor.findNearbyImage(mainUrl)
        val text = cleanText(container.text())
        val type = inferType(href, title, text, 0)
        val year = title.firstYear() ?: text.firstYear()
        val score = container.selectFirst(".gmr-rating-item, .rating, .score, .imdb, .vote")?.text()?.replace(',', '.')
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()
        val selector = listOf(
            "a[href*='/eps/']",
            "div.vid-episodes a[href]",
            "div.gmr-listseries a[href]",
            ".episode-list a[href]",
            ".episodes a[href]",
            ".eplister li a[href]",
            "[class*=episode] a[href]",
            "[id*=episode] a[href]",
            "[class*=season] a[href]",
            "[id*=season] a[href]"
        ).joinToString(",")

        document.select(selector).forEachIndexed { index, element ->
            val href = element.attr("href").toAbsoluteUrl(mainUrl) ?: return@forEachIndexed
            if (!isContentUrl(href) || contentKey(href) == contentKey(baseUrl)) return@forEachIndexed
            val rawName = cleanText(
                element.text().ifBlank { element.attr("title") }
            ).ifBlank { "Episode ${index + 1}" }
            if (rawName.contains("trailer", true) || rawName.contains("download", true)) return@forEachIndexed
            val episodeNumber = Regex("""(?i)(?:episode|eps|ep)\s*(\d+)""").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""\b(\d{1,4})\b""").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val seasonNumber = Regex("""(?i)(?:season|s)\s*(\d+)""").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(?i)/eps/[^/]+-season-(\d+)-""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            episodes[contentKey(href)] = newEpisode(href).apply {
                name = rawName
                episode = episodeNumber
                season = seasonNumber
                posterUrl = element.selectFirst("img[data-src], img[src]:not([src^='data:'])")?.imageUrl(mainUrl)
            }
        }
        return episodes.values.sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: Int.MAX_VALUE })
    }

    private fun buildPageUrl(data: String, page: Int): String {
        val base = data.toAbsoluteUrl(mainUrl) ?: mainUrl
        if (page <= 1) return base
        return base.trimEnd('/') + "/page/$page/"
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.select("a.next, .next a, .pagination a, .nav-links a, .page-numbers a").any { element ->
            val text = element.text().trim()
            val href = element.attr("href")
            text.equals("Next", true) || text == "›" || text == "»" || Regex("""\b${page + 1}\b""").containsMatchIn(text) || href.contains("/page/${page + 1}/")
        }
    }

    private fun inferType(url: String, title: String, text: String, episodeCount: Int): TvType {
        val value = "$url $title $text"
        return when {
            episodeCount > 1 -> TvType.TvSeries
            url.contains("/tv/", true) || url.contains("/eps/", true) -> TvType.TvSeries
            value.contains("TV Show", true) || value.contains("Series", true) || value.contains("Season", true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun findPoster(document: Document, baseUrl: String): String? {
        return document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.toAbsoluteUrl(baseUrl)
            ?: document.selectFirst("figure.pull-left img[data-src], figure.pull-left img[src]:not([src^='data:']), .poster img[data-src], .thumb img[data-src], .content-thumbnail img[data-src], img[itemprop=image][data-src], article img[data-src]")?.imageUrl(baseUrl)
            ?: document.selectFirst("figure.pull-left img, .poster img, .thumb img, .content-thumbnail img, img[itemprop=image], article img")?.imageUrl(baseUrl)
    }

    private fun Element.bestContainer(): Element {
        var current = this
        repeat(6) {
            val parent = current.parent() ?: return current
            val hasImage = parent.select("img").isNotEmpty()
            val hasTitle = parent.select("h1, h2, h3, .entry-title, .title, .name").isNotEmpty()
            if (hasImage || hasTitle) current = parent else return current
        }
        return current
    }

    private fun Element.firstAttr(vararg names: String): String? {
        return names.asSequence().map { attr(it) }.firstOrNull { it.isNotBlank() }
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val candidates = listOf(
            attr("data-src"),
            attr("data-original"),
            attr("data-lazy-src"),
            attr("data-wpfc-original-src"),
            attr("src").takeUnless { it.startsWith("data:") },
            attr("srcset").split(" ").firstOrNull()?.takeIf { it.isNotBlank() }
        )
        val raw = candidates.firstOrNull { !it.isNullOrBlank() } ?: return null
        return raw.toAbsoluteUrl(baseUrl)?.fixImageQuality()
    }

    private fun Element.styleImage(baseUrl: String): String? {
        return Regex("""url\((["']?)(.*?)\1\)""", RegexOption.IGNORE_CASE).find(attr("style"))
            ?.groupValues
            ?.getOrNull(2)
            ?.toAbsoluteUrl(baseUrl)
    }

    private fun Element.findNearbyImage(baseUrl: String): String? {
        return parent()?.selectFirst("img[data-src], img[src]:not([src^='data:'])")?.imageUrl(baseUrl)
    }

    private fun String?.toAbsoluteUrl(baseUrl: String): String? {
        val raw = this?.trim()?.trim('"', '\'', ' ', '\n', '\r', '\t')?.cleanHtml()?.takeIf { it.isNotBlank() } ?: return null
        val decoded = decodeBase64(raw)?.takeIf { it.startsWith("http", true) || it.startsWith("//") || it.startsWith("/") }
        val candidate = decoded ?: raw
        val fixed = when {
            candidate.startsWith("//") -> "https:$candidate"
            candidate.startsWith("http://", true) || candidate.startsWith("https://", true) -> candidate
            candidate.startsWith("/") -> getBaseUrl(baseUrl).trimEnd('/') + candidate
            candidate.startsWith("?") -> baseUrl.substringBefore("?") + candidate
            else -> runCatching { URI(baseUrl).resolve(candidate).toString() }.getOrNull()
        } ?: return null
        return httpsify(fixed)
    }

    private fun getBaseUrl(url: String): String {
        return runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: mainUrl
    }

    private fun decodeBase64(value: String?): String? {
        val clean = value?.trim()?.trim('"', '\'', ' ', '\n', '\r', '\t') ?: return null
        if (clean.length < 8 || clean.length % 4 == 1) return null
        return runCatching { String(Base64.getDecoder().decode(clean), Charsets.UTF_8) }.getOrNull()
    }

    private fun cleanTitle(value: String?): String {
        return cleanText(value)
            .replace(Regex("(?i)^download\\s+streaming\\s+film\\s+"), "")
            .replace(Regex("(?i)^download\\s+nonton\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)^tonton\\s+"), "")
            .substringBefore(" - Savefilm21")
            .substringBefore(" - SaveFilm21")
            .substringBefore(" \u2013 SaveFilm21")
            .trim()
    }

    private fun cleanDescription(value: String?): String? {
        return cleanText(value)
            .replace(Regex("""(?i)^sinopsis\s*:?\s*"""), "")
            .takeIf { it.length > 20 }
    }

    private fun cleanText(value: String?): String {
        return value.orEmpty().replace(Regex("\\s+"), " ").trim()
    }

    private fun String.cleanHtml(): String {
        return replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
    }

    private fun String.fixImageQuality(): String {
        return replace(Regex("-\\d+x\\d+(?=\\.(?:jpg|jpeg|png|webp))", RegexOption.IGNORE_CASE), "")
    }

    private fun String.firstYear(): Int? {
        return Regex("""\b(19|20)\d{2}\b""").find(this)?.value?.toIntOrNull()
    }

    private fun titleFromUrl(url: String): String {
        return url.trimEnd('/').substringAfterLast('/').replace('-', ' ').replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
        }
    }

    private fun isUsefulTitle(value: String?): Boolean {
        val text = cleanTitle(value)
        if (text.length < 2) return false
        val bad = listOf("tonton", "trailer", "download", "genre", "negara", "tahun", "beranda", "pasang iklan", "tweet", "sharer")
        return bad.none { text.equals(it, true) }
    }

    private fun isContentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (!host.contains("savefilm21info.com")) return false
        val path = uri.path.orEmpty().trim('/').lowercase(Locale.ROOT)
        if (path.isBlank()) return false
        if (path.matches(Regex("""(?:page/\d+|genre/.+|country/.+|year/.+|category/.+|tag/.+|author/.+|search/.+|tv|quality/.+|network/.+|director/.+|cast/.+)"""))) return false
        if (path.contains("wp-content") || path.contains("wp-admin") || path.contains("pasang-iklan")) return false
        return !url.isNoiseUrl()
    }

    private fun contentKey(url: String): String {
        return url.substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)
    }

    private fun String.isDirectVideoUrl(): Boolean {
        return contains(Regex("""(?i)\.(m3u8|mp4|webm|mkv|mpd)(?:\?|$)"""))
    }

    private fun String.isSubtitleUrl(): Boolean {
        return contains(Regex("""(?i)\.(srt|vtt|ass)(?:\?|$)"""))
    }

    private fun String.isNoiseUrl(): Boolean {
        val value = lowercase(Locale.ROOT)
        return value.contains("youtube.com") || value.contains("youtu.be") ||
            value.contains("facebook.com") || value.contains("twitter.com") ||
            value.contains("instagram.com") || value.contains("telegram") ||
            value.contains("api.whatsapp") || value.contains("t.me/share") ||
            value.contains("/wp-content/") || value.contains("/wp-json/") ||
            value.contains("/wp-admin/") ||
            value.endsWith(".jpg") || value.endsWith(".jpeg") ||
            value.endsWith(".png") || value.endsWith(".webp") || value.endsWith(".gif") ||
            value.startsWith("javascript:") || value.startsWith("mailto:") ||
            value.startsWith("#") || value.startsWith("data:") ||
            value.contains("pasang-iklan") || value.contains("slot") ||
            value.contains("campaign.") || value.contains("bit.ly")
    }

    private val cardSelector = listOf(
        "article.item",
        "article",
        ".gmr-item-modulepost",
        ".gmr-box-content",
        ".content-thumbnail",
        ".ml-item",
        ".result-item",
        ".movie",
        ".film",
        ".post"
    ).joinToString(",")
}
