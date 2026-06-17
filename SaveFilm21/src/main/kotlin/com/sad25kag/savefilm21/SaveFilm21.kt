package com.sad25kag.savefilm21

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
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
import com.lagradost.cloudstream3.utils.amap
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

class SaveFilm21 : MainAPI() {
    companion object {
        const val DEFAULT_MAIN_URL = "https://new13.savefilm21info.com"
    }

    override var mainUrl = DEFAULT_MAIN_URL
    override var name = "SaveFilm21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Referer" to "$mainUrl/"
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
        val document = try {
            app.get(pageUrl(request.data, page), headers = headers, referer = mainUrl).document
        } catch (_: Throwable) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val list = parseListing(document)
        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty() && hasNextPage(document, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded&post_type%5B%5D=post&post_type%5B%5D=tv",
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded"
        )
        val results = linkedMapOf<String, SearchResponse>()
        urls.forEach { url ->
            val document = try {
                app.get(url, headers = headers, referer = mainUrl).document
            } catch (_: Throwable) {
                return@forEach
            }
            parseListing(document).forEach { item ->
                if (item.name.contains(keyword, ignoreCase = true) || keyword.length <= 3) {
                    results[contentKey(item.url)] = item
                }
            }
            if (results.isNotEmpty()) return results.values.take(60)
        }
        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val page = fixUrl(url, mainUrl) ?: return null
        val response = try {
            app.get(page, headers = headers, referer = mainUrl)
        } catch (_: Throwable) {
            return null
        }
        val document = response.document
        val html = response.text.ifBlank { document.html() }.cleanHtml()
        val rawTitle = document.selectFirst("h1.entry-title, h1, .entry-title, meta[property=og:title], title")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        val title = cleanTitle(rawTitle).ifBlank { titleFromUrl(page) }
        if (title.isBlank()) return null

        val poster = findPoster(document, page)
        val text = cleanText(document.text())
        val tags = document.select("strong:contains(Genre) ~ a, .gmr-moviedata a[href*='/genre/'], .gmr-movie-on a, a[href*='/genre/']")
            .map { cleanText(it.text()).substringBefore("(").trim() }
            .filter { it.length in 2..40 && !it.equals("Trailer", true) && !it.equals("Tonton", true) }
            .distinct()
            .take(20)
        val actors = document.select("span[itemprop=actors] a, a[href*='/cast/'], a[href*='/actor/'], a[href*='/director/'], [itemprop=director] a")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..60 }
            .distinct()
            .take(24)
        val year = document.selectFirst("a[href*='/year/'], time[datetime]")?.text()?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
        val rating = document.selectFirst("[itemprop=ratingValue], .gmr-rating-item, .rating, .score, .imdb, .vote")?.text()?.replace(",", ".")
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        val duration = Regex("""(?i)(\d{1,3})\s*(?:min|menit|m)\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val description = cleanDescription(
            document.selectFirst("meta[property=og:description], meta[name=description], div[itemprop=description] > p, .entry-content p, .post-content p, .description, .desc, .sinopsis, .storyline, [itemprop=description]")
                ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        )
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup, a[href*='youtube.com'], a[href*='youtu.be']")?.attr("href")?.takeIf { it.isNotBlank() }
        val episodes = parseEpisodes(document, page)
        val tvType = inferType(page, title, text, episodes.size)

        return if (tvType == TvType.TvSeries && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, page, TvType.TvSeries, episodes) {
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
            newMovieLoadResponse(title, page, TvType.Movie, page) {
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
        val page = fixUrl(data, mainUrl) ?: data
        val response = try {
            app.get(page, headers = headers, referer = mainUrl)
        } catch (_: Throwable) {
            return false
        }
        val directBase = getBaseUrl(response.url)
        val document = response.document
        val html = response.text.ifBlank { document.html() }.cleanHtml()
        val visited = linkedSetOf<String>()
        var foundCandidate = false

        fun emitSubtitle(raw: String?, referer: String = page) {
            val url = raw.normalizeUrl(referer) ?: return
            if (url.isSubtitleUrl()) {
                subtitleCallback(newSubtitleFile("Indonesian", url))
            }
        }

        fun emitDirect(raw: String, referer: String): Boolean {
            val url = raw.normalizeUrl(referer) ?: return false
            val type = if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(name, name, url, type) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        suspend fun resolve(raw: String?, referer: String = page, depth: Int = 0): Boolean {
            val normalized = raw.normalizeUrl(referer) ?: return false
            if (!visited.add(normalized)) return false
            if (normalized.isNoiseUrl()) return false
            if (normalized.isSubtitleUrl()) {
                subtitleCallback(newSubtitleFile("Indonesian", normalized))
                return true
            }
            if (normalized.isDirectVideoUrl()) {
                return emitDirect(normalized, referer)
            }

            var emitted = false
            runCatching {
                loadExtractor(normalized, referer, subtitleCallback, callback)
                emitted = true
            }

            if (depth >= 2 || normalized.isKnownExternalHost()) return emitted

            val nestedResponse = runCatching {
                app.get(
                    normalized,
                    headers = headers + mapOf("Referer" to referer),
                    referer = referer,
                    timeout = 15000L
                )
            }.getOrNull() ?: return emitted

            val contentType = nestedResponse.headers["Content-Type"].orEmpty().lowercase(Locale.ROOT)
            if (contentType.startsWith("video/") || contentType.contains("mpegurl") || contentType.contains("dash")) {
                return emitDirect(normalized, referer) || emitted
            }

            val nestedHtml = nestedResponse.text.cleanHtml()
            collectCandidates(nestedHtml, normalized).forEach { candidate ->
                if (resolve(candidate, normalized, depth + 1)) emitted = true
            }
            return emitted
        }

        document.select("track[src], a[href*='.srt'], a[href*='.vtt']").forEach { element ->
            emitSubtitle(element.firstAttr("src", "href"), page)
        }

        // Muvipro/GMR player flow: player id -> wp-admin/admin-ajax.php?action=muvipro_player_content.
        val muviproId = document.selectFirst("div#muvipro_player_content_id, [id=muvipro_player_content_id]")?.attr("data-id")
        if (!muviproId.isNullOrBlank()) {
            document.select("div.tab-content-ajax[id], [class*=tab-content-ajax][id]").amap { tab ->
                val tabId = tab.attr("id")
                if (tabId.isNotBlank()) {
                    val ajaxDoc = runCatching {
                        app.post(
                            "$directBase/wp-admin/admin-ajax.php",
                            data = mapOf(
                                "action" to "muvipro_player_content",
                                "tab" to tabId,
                                "post_id" to muviproId
                            ),
                            headers = ajaxHeaders(page),
                            referer = page
                        ).document
                    }.getOrNull()
                    ajaxDoc?.let { doc ->
                        doc.select("iframe[src], iframe[data-src], source[src], video source[src], a[href]").forEach { element ->
                            if (resolve(element.firstAttr("src", "data-src", "href"), page)) foundCandidate = true
                        }
                    }
                }
            }
        }

        // Dooplay-compatible flow: option carries post/nume/type -> doo_player_ajax JSON/HTML.
        document.select(".dooplay_player_option, li[data-post][data-nume], [data-post][data-nume][data-type]").amap { option ->
            val post = option.attr("data-post")
            val nume = option.attr("data-nume")
            val type = option.attr("data-type").ifBlank { "movie" }
            if (post.isNotBlank() && nume.isNotBlank()) {
                val ajaxBody = runCatching {
                    app.post(
                        "$directBase/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to post,
                            "nume" to nume,
                            "type" to type
                        ),
                        headers = ajaxHeaders(page),
                        referer = page
                    ).text
                }.getOrNull().orEmpty()
                extractFromAjaxBody(ajaxBody).forEach { candidate ->
                    if (resolve(candidate, page)) foundCandidate = true
                }
            }
        }

        // Direct tabs/iframes/download anchors visible on page.
        document.select("ul.muvipro-player-tabs li a[href], .muvipro-player-tabs a[href], .gmr-embed-responsive iframe[src], iframe[src], iframe[data-src], video source[src], source[src], .gmr-download-list a[href], a[href*='.mp4'], a[href*='.m3u8']").amap { element ->
            val candidate = element.firstAttr("src", "data-src", "href")
            if (resolve(candidate, page)) foundCandidate = true
        }

        collectCandidates(html, page).forEach { candidate ->
            if (resolve(candidate, page)) foundCandidate = true
        }

        return foundCandidate
    }

    private fun pageUrl(data: String, page: Int): String {
        val base = fixUrl(data, mainUrl) ?: mainUrl
        if (page <= 1) return base
        return base.trimEnd('/') + "/page/$page/"
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select(cardSelector).forEach { element ->
            element.toSearchResult()?.let { results[contentKey(it.url)] = it }
        }
        if (results.size < 6) {
            document.select("article a[href], .post a[href], .item a[href], .movie a[href], .film a[href], .ml-item a[href], .result-item a[href], .entry-title a[href]").forEach { anchor ->
                anchor.toSearchResult()?.let { results[contentKey(it.url)] = it }
            }
        }
        return results.values.take(80)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (`is`("a[href]")) this else selectFirst("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], .title a[href], a[href][title], a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!isContentUrl(href)) return null
        val container = anchor.bestContainer()
        val image = container.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[data-wpfc-original-src], img[src], img[srcset]") ?: anchor.selectFirst("img")
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
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
        val score = container.selectFirst(".gmr-rating-item, .rating, .score, .imdb, .vote")?.text()?.replace(",", ".")
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
        document.select("div.vid-episodes a[href], div.gmr-listseries a[href], .episode-list a[href], .episodes a[href], .eplister li a[href], [class*=episode] a[href], [id*=episode] a[href], [class*=season] a[href], [id*=season] a[href]")
            .forEachIndexed { index, element ->
                val href = fixUrl(element.attr("href"), mainUrl) ?: return@forEachIndexed
                if (!isContentUrl(href) || contentKey(href) == contentKey(baseUrl)) return@forEachIndexed
                val rawName = cleanText(element.text()).ifBlank { element.attr("title") }.ifBlank { "Episode ${index + 1}" }
                if (rawName.contains("trailer", true) || rawName.contains("download", true)) return@forEachIndexed
                val episodeNumber = Regex("""(?i)(?:episode|eps|ep)\s*(\d+)""").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""\b(\d{1,4})\b""").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val seasonNumber = Regex("""(?i)(?:season|s)\s*(\d+)""").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                episodes[contentKey(href)] = newEpisode(href) {
                    name = rawName
                    episode = episodeNumber
                    season = seasonNumber
                    posterUrl = element.selectFirst("img")?.imageUrl(mainUrl)
                }
            }
        return episodes.values.sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: Int.MAX_VALUE })
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        val nextText = document.select("a.next, .next a, .pagination a, .nav-links a, .page-numbers a")
            .any { element ->
                val text = element.text().trim()
                val href = element.attr("href")
                text.equals("Next", true) || text == "›" || text == "»" || Regex("""\b${page + 1}\b""").containsMatchIn(text) || href.contains("/page/${page + 1}/")
            }
        return nextText || document.text().contains(Regex("""(?m)\b${page + 1}\b"""))
    }

    private fun inferType(url: String, title: String, text: String, episodeCount: Int): TvType {
        val data = "$url $title $text"
        return when {
            episodeCount > 1 -> TvType.TvSeries
            url.contains("/tv/", true) -> TvType.TvSeries
            data.contains("TV Show", true) || data.contains("Series", true) || data.contains("Season", true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun findPoster(document: Document, baseUrl: String): String? {
        return document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.normalizeUrl(baseUrl)
            ?: document.selectFirst("figure.pull-left img, .poster img, .thumb img, .content-thumbnail img, img[itemprop=image], article img")?.imageUrl(baseUrl)
    }

    private fun extractFromAjaxBody(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val out = linkedSetOf<String>()
        runCatching {
            val json = JSONObject(body)
            listOf("embed_url", "embed", "html", "iframe", "url", "player", "data").forEach { key ->
                json.optString(key).takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        val decoded = decodeBase64(body)
        if (!decoded.isNullOrBlank()) out.add(decoded)
        out.addAll(collectCandidates(body, mainUrl))
        return out.toList()
    }

    private fun collectCandidates(source: String, baseUrl: String): List<String> {
        val out = linkedSetOf<String>()
        val sources = buildList {
            add(source.cleanHtml())
            decodeBase64(source)?.let { add(it.cleanHtml()) }
            Regex("""atob\(["']([^"']+)["']\)""", RegexOption.IGNORE_CASE).findAll(source).forEach { match ->
                decodeBase64(match.groupValues.getOrNull(1)).takeIf { !it.isNullOrBlank() }?.let { add(it.cleanHtml()) }
            }
        }
        sources.forEach { html ->
            candidatePatterns.forEach { regex ->
                regex.findAll(html).mapNotNull { it.groupValues.getOrNull(1) }.forEach { candidate ->
                    candidate.unescapeCandidate().normalizeUrl(baseUrl)?.let(out::add)
                }
            }
            Jsoup.parse(html).select("iframe[src], iframe[data-src], source[src], video source[src], track[src], a[href]").forEach { element ->
                element.firstAttr("src", "data-src", "href").normalizeUrl(baseUrl)?.let(out::add)
            }
        }
        return out.filterNot { it.isNoiseUrl() }
    }

    private fun ajaxHeaders(referer: String): Map<String, String> {
        return headers + mapOf(
            "Referer" to referer,
            "Origin" to getBaseUrl(referer),
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )
    }

    private fun getBaseUrl(url: String): String {
        return runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: mainUrl
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
        val raw = firstAttr("data-src", "data-original", "data-lazy-src", "data-wpfc-original-src", "src")
            ?: attr("srcset").split(" ").firstOrNull()?.takeIf { it.isNotBlank() }
        return raw?.normalizeUrl(baseUrl)?.fixImageQuality()
    }

    private fun Element.styleImage(baseUrl: String): String? {
        val style = attr("style")
        return Regex("""url\((['\"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE).find(style)?.groupValues?.getOrNull(2)?.normalizeUrl(baseUrl)
    }

    private fun Element.findNearbyImage(baseUrl: String): String? {
        return parent()?.selectFirst("img")?.imageUrl(baseUrl)
    }

    private fun String?.normalizeUrl(baseUrl: String): String? {
        val raw = this?.trim()?.trim('"', '\'', ' ', '\n', '\r', '\t')?.unescapeCandidate()?.takeIf { it.isNotBlank() } ?: return null
        val decoded = decodeBase64(raw)?.takeIf { it.startsWith("http", true) || it.startsWith("//") || it.startsWith("/") }
        val candidate = decoded ?: raw
        return when {
            candidate.startsWith("//") -> "https:$candidate"
            candidate.startsWith("http://") || candidate.startsWith("https://") -> candidate
            candidate.startsWith("/") -> getBaseUrl(baseUrl).trimEnd('/') + candidate
            candidate.startsWith("?", true) -> baseUrl.substringBefore("?") + candidate
            candidate.startsWith("iframe", true) || candidate.startsWith("<") -> collectCandidates(candidate, baseUrl).firstOrNull()
            else -> runCatching { URI(baseUrl).resolve(candidate).toString() }.getOrNull()
        }?.let { httpsify(it) }
    }

    private fun decodeBase64(value: String?): String? {
        val clean = value?.trim()?.trim('"', '\'', ' ', '\n', '\r', '\t') ?: return null
        if (clean.length < 8 || clean.length % 4 == 1) return null
        return runCatching { String(Base64.getDecoder().decode(clean)) }.getOrNull()
    }

    private fun cleanTitle(value: String?): String {
        return cleanText(value)
            .replace(Regex("(?i)^download\\s+streaming\\s+film\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)^tonton\\s+"), "")
            .substringBefore(" - Savefilm21")
            .substringBefore(" - SaveFilm21")
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
            .replace("\u0026", "&")
    }

    private fun String.unescapeCandidate(): String {
        return cleanHtml()
            .replace("\\u003d", "=")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
    }

    private fun String.fixImageQuality(): String {
        return replace(Regex("-\\d+x\\d+(?=\\.(?:jpg|jpeg|png|webp))", RegexOption.IGNORE_CASE), "")
    }

    private fun isUsefulTitle(value: String?): Boolean {
        val text = cleanTitle(value)
        if (text.length < 2) return false
        val bad = listOf("tonton", "trailer", "download", "genre", "negara", "tahun", "beranda", "pasang iklan", "tweet", "sharer")
        return bad.none { text.equals(it, true) }
    }

    private fun titleFromUrl(url: String): String {
        return url.trimEnd('/').substringAfterLast('/').replace('-', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private fun isContentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (!host.contains("savefilm21info.com")) return false
        val path = uri.path.orEmpty().trim('/').lowercase(Locale.ROOT)
        if (path.isBlank()) return false
        if (path.matches(Regex("""(?:page/\d+|genre/.+|country/.+|year/.+|category/.+|tag/.+|author/.+|search/.+|tv)"""))) return false
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

    private fun String.isKnownExternalHost(): Boolean {
        val host = runCatching { URI(this).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return host.isNotBlank() && !host.contains("savefilm21info.com")
    }

    private fun String.isNoiseUrl(): Boolean {
        val value = lowercase(Locale.ROOT)
        return value.contains("youtube.com") || value.contains("youtu.be") || value.contains("facebook.com") || value.contains("twitter.com") ||
            value.contains("instagram.com") || value.contains("telegram") || value.contains("/wp-content/") || value.contains("/wp-json/") ||
            value.endsWith(".jpg") || value.endsWith(".jpeg") || value.endsWith(".png") || value.endsWith(".webp") || value.endsWith(".gif") ||
            value.startsWith("javascript:") || value.startsWith("mailto:") || value.startsWith("#") || value.contains("pasang-iklan")
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

    private val candidatePatterns = listOf(
        Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""(?:data-src|data-embed|data-video|data-url|data-link|data-file)=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""(?:file|src|source|video|videoUrl|video_url|hls\d*|url|embed|embed_url|embed_frame_url)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""["']((?:https?:)?//[^"']+(?:\.m3u8|\.mp4|\.webm|\.mpd)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE),
        Regex("""["']((?:https?:)?//[^"']*(?:gdriveplayer\.(?:io|to|me)|dood|streamtape|filemoon|vidhide|vidguard|voe|mixdrop|streamwish|wishfast|mp4upload|uqload|streamlare|filelions|gdplayer|abyss|pixeldrain|krakenfiles)[^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""["']((?:/[^"']*)/(?:embed|player|stream|get|watch|video|dl)[^"']*)["']""", RegexOption.IGNORE_CASE)
    )
}
