package com.sad25kag.nontondrakor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class NontonDrakor : MainAPI() {
    override var mainUrl = "https://nonton.drakor-id.top"
    override var name = "NontonDrakor"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Episode Terbaru",
        "$mainUrl/blog-category/film/page/%d/" to "Film",
        "$mainUrl/blog-category/tv-series/page/%d/" to "Series",
        "$mainUrl/genre/drama-korea/page/%d/" to "Drama Korea",
        "$mainUrl/genre/c-drama/page/%d/" to "C-Drama",
        "$mainUrl/country/korea/page/%d/" to "Korea",
        "$mainUrl/country/china/page/%d/" to "China",
        "$mainUrl/genre/action/page/%d/" to "Action",
        "$mainUrl/genre/comedy/page/%d/" to "Comedy",
        "$mainUrl/genre/crime/page/%d/" to "Crime",
        "$mainUrl/genre/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/genre/mystery/page/%d/" to "Mystery",
        "$mainUrl/genre/romance/page/%d/" to "Romance",
        "$mainUrl/genre/thriller/page/%d/" to "Thriller",
        "$mainUrl/genre/film-india/page/%d/" to "Film India"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data.replace("/page/%d/", "/").replace("%d", "")
        } else {
            request.data.format(page)
        }
        val document = app.get(url, headers = headers, referer = "$mainUrl/").document
        val results = document.toSearchResults()
        val hasNext = results.isNotEmpty() && document.select(
            "a[href*='/page/${page + 1}/'], a.next, .next a[href], .pagination a[href*='/page/${page + 1}/'], .nav-links a[href*='/page/${page + 1}/']"
        ).isNotEmpty()
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv",
            "$mainUrl/page/1/?s=$encoded"
        )
        val results = linkedMapOf<String, SearchResponse>()
        for (url in urls) {
            val text = runCatching { app.get(url, headers = headers, referer = "$mainUrl/").text }.getOrNull().orEmpty()
            if (text.isBlank()) continue
            Jsoup.parse(text, mainUrl).toSearchResults().forEach { results[it.url] = it }
            if (results.isNotEmpty()) break
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = url.absoluteUrl(mainUrl) ?: url
        val document = app.get(pageUrl, headers = headers, referer = "$mainUrl/").document

        if (pageUrl.isEpisodeUrl()) {
            return document.toEpisodeLoadResponse(pageUrl)
        }

        val title = document.extractDetailTitle(pageUrl)
            ?: throw ErrorLoadingException("Title not found")
        val detailScopes = document.detailScopes()
        val poster = document.extractPoster(pageUrl)
        val plot = document.extractPlot()
        val tags = detailScopes.extractScopedLinks("/genre/")
        val actors = detailScopes.extractScopedPeople()
        val status = detailScopes.joinToString(" ") { it.text() }.toShowStatus()
            ?: document.selectFirst(".status, .dtstatus, [class*=status]")?.text()?.toShowStatus()
        val year = detailScopes.extractYear()
        val episodes = document.extractEpisodes(pageUrl)
        val recommendations = document.extractRecommendations(pageUrl)

        val type = when {
            pageUrl.contains("/movie/", true) -> TvType.Movie
            pageUrl.contains("/tv/", true) || episodes.isNotEmpty() -> TvType.AsianDrama
            else -> TvType.Movie
        }

        return if (type == TvType.Movie || episodes.isEmpty()) {
            val rawPlayData = document.extractMoviePlayData(pageUrl)
            val playData = rawPlayData?.toPlaybackData(pageUrl) ?: pageUrl
            newMovieLoadResponse(title, pageUrl, TvType.Movie, playData) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot?.let { this.plot = it }
                this.tags = tags
                this.year = year
                this.actors = actors.map { ActorData(Actor(it)) }
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title, pageUrl, TvType.AsianDrama, episodes) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot?.let { this.plot = it }
                this.tags = tags
                this.year = year
                showStatus = status
                this.actors = actors.map { ActorData(Actor(it)) }
                this.recommendations = recommendations
            }
        }
    }

    private suspend fun Document.toEpisodeLoadResponse(url: String): LoadResponse {
        val title = extractDetailTitle(url)
            ?: url.substringAfterLast('/').replace('-', ' ').cleanTitle()
        val poster = extractPoster(url)
        val plot = extractPlot()
        val detailScopes = detailScopes()
        val tags = detailScopes.extractScopedLinks("/genre/")
        val actors = detailScopes.extractScopedPeople()
        val rawPlayData = extractMoviePlayData(url)
        val playData = rawPlayData?.toPlaybackData(url) ?: url
        return newMovieLoadResponse(title, url, TvType.Movie, playData) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot?.let { this.plot = it }
            this.tags = tags
            this.actors = actors.map { ActorData(Actor(it)) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playbackData = data.parsePlaybackData()
        val pageUrl = playbackData.first.absoluteUrl(mainUrl) ?: playbackData.first
        val initialReferer = playbackData.second.absoluteUrl(mainUrl) ?: "$mainUrl/"
        val emitted = linkedSetOf<String>()
        var delivered = 0

        suspend fun resolve(raw: String?, sourceName: String = name, refererUrl: String = pageUrl, depth: Int = 0) {
            val fixed = raw.decodeCandidate()?.absoluteUrl(refererUrl)?.cleanMediaUrl() ?: return
            if (fixed.isBlank() || !fixed.isLikelyPlayerUrl(pageUrl) || !emitted.add(fixed)) return

            if (fixed.isDirectMedia()) {
                val type = if (fixed.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(
                    newExtractorLink(sourceName, sourceName, fixed, type) {
                        quality = fixed.qualityFromUrl()
                        referer = refererUrl
                        headers = fixed.playbackHeaders(refererUrl)
                    }
                )
                delivered++
                return
            }

            if (!fixed.startsWith("http", true)) return

            if (loadExtractor(fixed, refererUrl, subtitleCallback, callback)) {
                delivered++
                return
            }

            if (depth >= 2 || !fixed.isInspectableHost()) return

            val hostText = runCatching {
                app.get(fixed, headers = headers + fixed.playbackHeaders(refererUrl), referer = refererUrl).text
            }.getOrNull().orEmpty()
            if (hostText.isBlank()) return

            val unpacked = runCatching { getAndUnpack(hostText) }.getOrNull().orEmpty()
            val hostDocument = Jsoup.parse(hostText, fixed)
            hostDocument.collectPlayerCandidates().forEach { candidate ->
                resolve(candidate, fixed.hostLabel(), fixed, depth + 1)
            }
            collectTextCandidates(hostText + "\n" + unpacked).forEach { candidate ->
                resolve(candidate, fixed.hostLabel(), fixed, depth + 1)
            }
        }

        resolve(pageUrl, name, initialReferer)
        if (delivered > 0 && pageUrl.isDirectMedia()) return true

        val pageText = app.get(pageUrl, headers = headers, referer = initialReferer).text
        val document = Jsoup.parse(pageText, pageUrl)

        document.resolveAjaxPlayers(pageUrl).forEach { (label, candidate) ->
            resolve(candidate, label.ifBlank { name }, pageUrl)
        }

        document.collectPlayerCandidates().forEach { candidate ->
            resolve(candidate, name, pageUrl)
        }

        collectTextCandidates(pageText + "\n" + runCatching { getAndUnpack(pageText) }.getOrNull().orEmpty()).forEach { candidate ->
            resolve(candidate, name, pageUrl)
        }

        return delivered > 0
    }

    private suspend fun Document.resolveAjaxPlayers(pageUrl: String): List<Pair<String, String>> {
        val candidates = mutableListOf<Pair<String, String>>()
        select(
            "#playeroptionsul li[data-post], .dooplay_player_option[data-post], .player-option[data-post], " +
                ".options li[data-post], li[data-nume][data-type], [data-post][data-nume], [data-id][data-nume]"
        ).forEach { option ->
            val post = option.attr("data-post").ifBlank { option.attr("data-id") }
            val nume = option.attr("data-nume").ifBlank { option.attr("data-num") }.ifBlank { "1" }
            val type = option.attr("data-type").ifBlank { "movie" }
            if (post.isNotBlank()) {
                val ajaxText = runCatching {
                    app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume, "type" to type),
                        headers = headers + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json, text/javascript, */*; q=0.01"
                        ),
                        referer = pageUrl
                    ).text
                }.getOrNull().orEmpty()
                extractAjaxCandidates(ajaxText).forEach { candidates.add(option.text().cleanTitle() to it) }
            }
        }
        return candidates.distinctBy { it.second }
    }

    private fun Document.toSearchResults(): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        select(
            "article, .ml-item, .item, .movie-item, .module, .owl-item, .result-item, " +
                ".latest .item, .archive .item, .poster, .card, .post, .list-item, .film, .tvshows"
        ).forEach { card ->
            card.toSearchResult()?.let { results[it.url] = it }
        }

        if (results.isEmpty()) {
            select("h1 a[href], h2 a[href], h3 a[href], h4 a[href], .entry-title a[href], .post-title a[href]").forEach { anchor ->
                (anchor.closest("article, .item, .movie-item, .module, .result-item, .post, .card") ?: anchor.parent() ?: anchor)
                    .toSearchResult()?.let { results[it.url] = it }
            }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchors = select("h1 a[href], h2 a[href], h3 a[href], h4 a[href], .entry-title a[href], .post-title a[href], .title a[href], .name a[href], a[href]")
            .filter { it.attr("href").absoluteUrl(mainUrl)?.isContentUrl() == true }
            .sortedBy { it.anchorPriority() }
        val anchor = anchors.firstOrNull() ?: return null
        val href = anchor.attr("href").absoluteUrl(mainUrl) ?: return null
        if (!href.isContentUrl()) return null

        val title = listOf(
            selectFirst("h1, h2, h3, h4, .entry-title, .post-title, .mli-title, .title, .name")?.text(),
            anchor.text(),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.attr("title")
        ).firstCleanTitle() ?: return null

        val poster = selectFirst("img")?.imageUrl(href)
        return when {
            href.isEpisodeUrl() || href.contains("/movie/", true) || href.isRootMovieUrl() ->
                newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
            else ->
                newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
        }
    }

    private fun Document.extractDetailTitle(pageUrl: String): String? {
        return listOf(
            selectFirst("h1.entry-title, h1.post-title, .sheader h1, .data h1, .movie-title h1, h1")?.text(),
            selectFirst("meta[property=og:title]")?.attr("content"),
            selectFirst("title")?.text()
        ).firstCleanTitle()
            ?: pageUrl.substringAfterLast('/').replace('-', ' ').cleanTitle().takeIf { it.isNotBlank() }
    }

    private fun Document.extractPoster(baseUrl: String): String? {
        return listOfNotNull(
            selectFirst("meta[property=og:image]")?.imageUrl(baseUrl),
            selectFirst(".poster img, .thumb img, .image img, .movie-poster img, .gmr-movie-data figure img, .sheader img, img.wp-post-image")?.imageUrl(baseUrl),
            selectFirst("video[poster]")?.imageUrl(baseUrl)
        ).firstOrNull { it.isValidImageUrl() }
    }

    private fun Document.extractPlot(): String? {
        return listOf(
            selectFirst("meta[property=og:description]")?.attr("content"),
            selectFirst("meta[name=description]")?.attr("content"),
            selectFirst(".synopsis, .desc, .summary, .storyline, .sinopsis, div.entry-content[itemprop='description'], .entry-content p")?.text()
        ).firstOrNull { !it.isNullOrBlank() }?.cleanText()
    }

    private fun Document.detailScopes(): List<Element> {
        val primarySelectors = listOf(
            ".gmr-moviedata", ".gmr-movie-data", ".moviedata", ".movie-data", ".movie-info",
            ".sheader .data", ".sheader", ".mvic-desc", ".mvici-left", ".mvici-right",
            ".postmeta", ".metadata", ".extra", ".single-info", ".entry-header", ".data"
        )
        val primary = primarySelectors.mapNotNull { selectFirst(it) }
            .filterNot { it.isInsideNoiseBlock() || it.`is`("header, footer, nav, .menu, .navbar, .related, .recommendations, .sidebar") }
            .distinct()
        if (primary.isNotEmpty()) return primary

        val scopedGenres = select("article .genres, article .sgeneros, .entry-content .genres, .entry-content .sgeneros, .movie-info .genres, .movie-data .genres")
            .filterNot { it.isInsideNoiseBlock() }
            .distinct()
        if (scopedGenres.isNotEmpty()) return scopedGenres

        return listOfNotNull(selectFirst("article, .single, .post, .entry-content"))
            .filterNot { it.isInsideNoiseBlock() }
    }

    private fun List<Element>.extractScopedLinks(pathNeedle: String): List<String> {
        return flatMap { scope ->
            scope.select("a[href*='$pathNeedle'], [itemprop='genre'] a[href]")
                .filterNot { it.isInsideNoiseBlock() }
                .filter { it.isDetailMetaLink("genre") }
                .map { it.text().cleanText() }
        }.filter { it.isNotBlank() && it.length <= 40 && !it.equals("Genre", true) }
            .distinct()
            .take(8)
    }

    private fun List<Element>.extractScopedPeople(): List<String> {
        val peoplePaths = listOf("/cast/", "/actor/", "/actors/", "/star/", "/stars/")
        return flatMap { scope ->
            val itemPropActors = scope.select("[itemprop='actors'] a, [itemprop='actor'] a").map { it.text().cleanText() }
            val linkedPeople = scope.select("a[href]")
                .filter { element -> peoplePaths.any { element.attr("href").contains(it, true) } }
                .filterNot { it.isInsideNoiseBlock() }
                .map { it.text().cleanText() }
            itemPropActors + linkedPeople
        }.filter { it.isLikelyPersonName() }
            .distinct()
            .take(20)
    }

    private fun List<Element>.extractYear(): Int? {
        selectYearFromLinks(this)?.let { return it }
        val scopedText = joinToString(" ") { it.text() }
        return Regex("(?:19|20)\\d{2}").find(scopedText)?.value?.toIntOrNull()
    }

    private fun selectYearFromLinks(scopes: List<Element>): Int? {
        return scopes.asSequence()
            .flatMap { it.select("a[href*='/year/'], a[href*='movieyear'], a[href*='/release/']").asSequence() }
            .mapNotNull { Regex("(?:19|20)\\d{2}").find(it.text())?.value?.toIntOrNull() }
            .firstOrNull()
    }

    private fun Document.extractEpisodes(baseUrl: String): List<Episode> {
        val episodeContainers = select(
            ".gmr-listseries, .episodios, .se-c, .episodelist, .episode-list, .les-content, .eplister, .listing"
        ).filterNot { it.isInsideNoiseBlock() }

        val episodeLinks = if (episodeContainers.isNotEmpty()) {
            episodeContainers.flatMap { it.select("a[href*='/eps/']") }
        } else {
            select("article a[href*='/eps/'], .entry-content a[href*='/eps/'], .post a[href*='/eps/'], a[href*='/eps/']")
                .filterNot { it.isInsideNoiseBlock() }
        }

        return episodeLinks
            .mapNotNull { it.toEpisode(baseUrl) }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })
    }

    private fun Document.extractMoviePlayData(pageUrl: String): String? {
        val candidates = mutableListOf<String>()

        select(
            ".muvipro-player-tabs a[href], .player a[href], .play a[href], .server a[href], .embed a[href], " +
                ".btn-play[href], .button-play[href], a[href*='/player/'], a[href*='/watch/'], a[href*='/embed/'], " +
                "iframe[src], embed[src], video[src], video source[src], source[src]"
        ).filterNot { it.isInsideNoiseBlock() }.forEach { element ->
            listOf("href", "src", "data-src").forEach { attr -> candidates.add(element.attr(attr)) }
        }

        select(
            ".player [data-src], .play [data-src], .server [data-src], .embed [data-src], .muvipro-player-tabs [data-src], " +
                "[data-player], [data-video], [data-file], [data-stream], [data-iframe], [data-embed], " +
                ".player [data-url], .play [data-url], .server [data-url], .embed [data-url], " +
                ".player [data-link], .play [data-link], .server [data-link], .embed [data-link]"
        ).filterNot { it.isInsideNoiseBlock() }.forEach { element ->
            listOf(
                "data-src", "data-url", "data-link", "data-href", "data-iframe", "data-embed",
                "data-player", "data-video", "data-file", "data-stream"
            ).forEach { attr -> candidates.add(element.attr(attr)) }
        }

        select("a[href*='/eps/']").filterNot { it.isInsideNoiseBlock() }.forEach { element ->
            val text = element.text().lowercase()
            val inPlayerScope = element.parents().any { it.`is`(".player, .play, .muvipro-player-tabs, .server, .embed") }
            if (inPlayerScope || text.contains("watch") || text.contains("putar") || text.contains("play")) {
                candidates.add(element.attr("href"))
            }
        }

        return candidates.asSequence()
            .mapNotNull { it.absoluteUrl(pageUrl) }
            .filter { it.isLikelyPlayerUrl(pageUrl) }
            .filterNot { it.contains("youtube", true) || it.contains("youtu.be", true) }
            .distinct()
            .firstOrNull()
    }

    private fun Document.extractRecommendations(pageUrl: String): List<SearchResponse> {
        return select(".related, .recommendations, .owl-carousel, .section, .latest, .archive")
            .flatMap { scope -> scope.toSearchResultsLike(pageUrl) }
            .filterNot { it.url == pageUrl }
            .distinctBy { it.url }
            .take(20)
    }

    private fun Element.toSearchResultsLike(pageUrl: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        select("article, .item, .movie-item, .module, .owl-item, .result-item, .post, .card, .poster").forEach { card ->
            card.toSearchResult()?.let { if (it.url != pageUrl) results[it.url] = it }
        }
        return results.values.toList()
    }

    private fun Element.toEpisode(baseUrl: String): Episode? {
        val href = attr("href").absoluteUrl(baseUrl) ?: return null
        if (!href.startsWith(mainUrl) || !href.isEpisodeUrl()) return null
        val rawText = attr("title").ifBlank { text() }
        val epNum = Regex("(?i)(?:EP|Episode|Eps?\\.?|Ep\\.?|S\\d+\\s*Eps?)\\s*(\\d+)").find(rawText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)(?:episode|eps?)[-_ ]?(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val cleaned = rawText.cleanTitle().cleanEpisodeTitle(epNum)
        return newEpisode(href.toPlaybackData(baseUrl)) {
            name = cleaned.ifBlank { "Episode ${epNum ?: ""}".trim() }
            episode = epNum
            posterUrl = selectFirst("img")?.imageUrl(baseUrl)
        }
    }

    private fun Element.collectPlayerCandidates(): List<String> {
        val candidates = mutableListOf<String>()
        select("iframe[src], embed[src], video[src], video source[src], source[src], track[src]").forEach { element ->
            listOf("src", "data-src").forEach { attr -> candidates.add(element.attr(attr)) }
        }
        select(".muvipro-player-tabs a[href], .player a[href], .play a[href], .server a[href], .embed a[href], a[href*='/player/'], a[href*='/embed/'], a[href*='/watch/']").forEach { element ->
            candidates.add(element.attr("href"))
        }
        select("[data-src], [data-url], [data-link], [data-href], [data-iframe], [data-embed], [data-player], [data-video], [data-file], [data-stream], option[value]").forEach { element ->
            listOf(
                "data-src", "data-url", "data-link", "data-href", "data-iframe", "data-embed", "data-player", "data-video",
                "data-file", "data-stream", "value"
            ).forEach { attr -> candidates.add(element.attr(attr)) }
        }
        return candidates.filter { it.isNotBlank() }.distinct()
    }

    private fun collectTextCandidates(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val candidates = mutableListOf<String>()
        val decodedText = raw.replace("\\/", "/")
        MEDIA_URL_REGEX.findAll(decodedText).forEach { candidates.add(it.value) }
        IFRAME_REGEX.findAll(decodedText).forEach { candidates.add(it.groupValues[1]) }
        PLAYER_VALUE_REGEX.findAll(decodedText).forEach { candidates.add(it.groupValues[1]) }
        Regex("""(?:atob|Base64\.decode)\(['\"]([^'\"]+)['\"]\)""", RegexOption.IGNORE_CASE)
            .findAll(raw)
            .forEach { match -> base64DecodeSafe(match.groupValues[1])?.let { candidates.add(it) } }
        return candidates.flatMap { candidate ->
            val decoded = candidate.decodeCandidate()
            if (decoded != null && decoded.contains("http", true) && decoded != candidate) listOf(candidate, decoded) else listOf(candidate)
        }.distinct()
    }

    private fun extractAjaxCandidates(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val candidates = mutableListOf<String>()
        runCatching { JSONObject(raw) }.getOrNull()?.let { collectJsonCandidates(it, candidates) }
        runCatching { JSONArray(raw) }.getOrNull()?.let { collectJsonCandidates(it, candidates) }
        candidates.add(raw)
        candidates.addAll(collectTextCandidates(raw))
        return candidates.distinct()
    }

    private fun collectJsonCandidates(value: Any?, out: MutableList<String>) {
        when (value) {
            is JSONObject -> value.keys().forEach { key -> collectJsonCandidates(value.opt(key), out) }
            is JSONArray -> for (i in 0 until value.length()) collectJsonCandidates(value.opt(i), out)
            is String -> if (value.contains("http", true) || value.contains("iframe", true) || value.contains("m3u8", true)) out.add(value)
        }
    }

    private fun Element.anchorPriority(): Int {
        val text = text().lowercase()
        val href = attr("href").lowercase()
        return when {
            tagName().matches(Regex("h[1-4]", RegexOption.IGNORE_CASE)) -> 0
            parent()?.tagName()?.matches(Regex("h[1-4]", RegexOption.IGNORE_CASE)) == true -> 0
            hasClass("title") || hasClass("entry-title") || hasClass("post-title") -> 1
            href.contains("/movie/") || href.contains("/tv/") || href.contains("/eps/") -> 2
            text.contains("watch movie") || text.contains("trailer") -> 9
            else -> 5
        }
    }

    private fun List<String?>.firstCleanTitle(): String? {
        return mapNotNull { it?.cleanTitle()?.takeIf { cleaned -> cleaned.isNotBlank() && !cleaned.isNoisyTitle() } }
            .firstOrNull()
    }

    private fun Element.textOrContent(): String {
        return attr("content").ifBlank { text() }.trim()
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val raw = attr("content")
            .ifBlank { attr("data-src") }
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("data-poster") }
            .ifBlank { attr("poster") }
            .ifBlank { attr("srcset").srcSetFirst() }
            .ifBlank { attr("src") }
        return raw.absoluteUrl(baseUrl)?.takeIf { it.isValidImageUrl() }
    }

    private fun String.srcSetFirst(): String {
        return split(",").map { it.trim().split(Regex("\\s+")).firstOrNull().orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun String?.absoluteUrl(baseUrl: String): String? {
        val raw = this?.trim()?.replace("&amp;", "&")?.replace("\\/", "/") ?: return null
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript", true) || raw.startsWith("mailto:", true)) return null
        val normalized = if (raw.startsWith("//")) "https:$raw" else raw
        return runCatching { URI(baseUrl).resolve(normalized).toString() }.getOrNull()
    }

    private fun String?.decodeCandidate(): String? {
        val raw = this?.trim()?.trim(' ', '\'', '"') ?: return null
        if (raw.isBlank()) return null
        val clean = Jsoup.parse(raw).text()
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .trim(' ', '\'', '"')
        if (clean.startsWith("http", true) || clean.startsWith("//") || clean.startsWith("/") || clean.startsWith("./")) return clean
        if (clean.length > 16 && clean.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) {
            base64DecodeSafe(clean)?.let { decoded ->
                if (decoded.contains("http", true) || decoded.contains("iframe", true)) return decoded
            }
        }
        val urlInside = Regex("https?://[^'\"<>\\s]+", RegexOption.IGNORE_CASE).find(clean)?.value
        return urlInside ?: clean
    }

    private fun base64DecodeSafe(raw: String): String? {
        return runCatching { base64Decode(raw) }.getOrNull()
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("(?i)^\\s*Permalink\\s+(?:to|ke)\\s*:\\s*"), "")
            .replace(Regex("(?i)^\\s*Watch\\s+Movie\\s*"), "")
            .replace(Regex("(?i)^\\s*Trailer\\s*"), "")
            .replace("Drakor ID", "", ignoreCase = true)
            .replace("Nonton", "", ignoreCase = true)
            .replace("Streaming", "", ignoreCase = true)
            .replace("Subtitle Indonesia", "", ignoreCase = true)
            .replace("Sub Indo", "", ignoreCase = true)
            .replace("Watch Movie", "", ignoreCase = true)
            .replace(Regex("(?i)\\|.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', '⋆', ':')
    }

    private fun String.cleanEpisodeTitle(epNum: Int?): String {
        val lower = lowercase()
        if (epNum != null && (lower.matches(Regex("s\\d+\\s*eps?\\s*\\d+", RegexOption.IGNORE_CASE)) || lower.matches(Regex("eps?\\s*\\d+", RegexOption.IGNORE_CASE)))) {
            return "Episode $epNum"
        }
        return this
    }

    private fun String.cleanText(): String {
        return Jsoup.parse(this).text().replace(Regex("\\s+"), " ").trim()
    }

    private fun String.cleanMediaUrl(): String {
        val cleaned = replace("\\/", "/").replace("&amp;", "&").trim(' ', '\'', '"')
        return runCatching { URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
    }

    private fun String.isContentUrl(): Boolean {
        val path = runCatching { URI(this).path.lowercase() }.getOrNull() ?: return false
        return path.startsWith("/eps/") || path.startsWith("/movie/") || path.startsWith("/tv/") || isRootMovieUrl()
    }

    private fun String.isRootMovieUrl(): Boolean {
        val path = runCatching { URI(this).path.lowercase().trim('/') }.getOrNull() ?: return false
        if (path.isBlank() || path.contains('/')) return false
        if (path.length < 4 || path.matches(Regex("\\d{4}"))) return false
        val blockedExact = setOf(
            "index-movie", "cara-download", "menampilkan-subtitle-setelah-download", "order-by-title",
            "best-rating", "hd", "home", "tv-shows", "c-drama", "drakor", "film-india",
            "contact", "privacy-policy", "privacy", "dmca", "disclaimer", "terms", "term-of-service", "sitemap"
        )
        val blockedPrefixes = listOf(
            "genre", "country", "blog-category", "tag", "cast", "actor", "actors", "creator",
            "director", "star", "stars", "quality", "year", "page", "search", "category",
            "wp-admin", "wp-content", "wp-json", "author", "feed"
        )
        if (path in blockedExact) return false
        if (blockedPrefixes.any { path == it || path.startsWith("$it-") }) return false
        return true
    }

    private fun String.isEpisodeUrl(): Boolean {
        val path = runCatching { URI(this).path.lowercase() }.getOrNull() ?: lowercase()
        return path.startsWith("/eps/") || path.startsWith("/episode/")
    }

    private fun String.toShowStatus(): ShowStatus? {
        val lower = lowercase()
        return when {
            lower.contains("completed") || lower.contains("tamat") || lower.contains("end") || lower.contains("complete") -> ShowStatus.Completed
            lower.contains("ongoing") || lower.contains("on-going") || lower.contains("berjalan") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase()
        return lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".webm") ||
            lower.contains(".mkv") || lower.contains("videoplayback")
    }

    private fun String.qualityFromUrl(): Int {
        val lower = lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.isInspectableHost(): Boolean {
        val lower = lowercase()
        return startsWith("http", true) && !lower.contains("youtube.com") && !lower.contains("youtu.be") && !isDirectMedia()
    }

    private fun String.isLikelyPlayerUrl(pageUrl: String): Boolean {
        val lower = lowercase()
        if (isDirectMedia()) return true
        if (isMediaAssetUrl()) return false
        if (!startsWith("http", true) && !startsWith("/")) return false
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) return false
        if (lower.contains("/genre/") || lower.contains("/country/") || lower.contains("/blog-category/") ||
            lower.contains("/tag/") || lower.contains("/cast/") || lower.contains("/actor/") || lower.contains("/director/") ||
            lower.contains("/creator/") || lower.contains("/quality/") || lower.contains("/year/") || lower.contains("/page/")) return false
        val host = runCatching { URI(this).host.orEmpty() }.getOrDefault("")
        val pageHost = runCatching { URI(pageUrl).host.orEmpty() }.getOrDefault("")
        if (host.isNotBlank() && pageHost.isNotBlank() && host != pageHost) return true
        return lower.contains("/eps/") || lower.contains("/player") || lower.contains("/embed") || lower.contains("/watch") ||
            lower.contains("admin-ajax") || lower.contains("?player") || lower.contains("data=")
    }

    private fun String.playbackHeaders(refererUrl: String): Map<String, String> {
        val map = mutableMapOf(
            "Referer" to refererUrl,
            "User-Agent" to USER_AGENT
        )
        refererUrl.origin()?.let { map["Origin"] = it }
        return map
    }

    private fun String.toPlaybackData(refererUrl: String): String {
        return if (this == refererUrl) {
            this
        } else {
            JSONObject().put("url", this).put("referer", refererUrl).toString()
        }
    }

    private fun String.parsePlaybackData(): Pair<String, String> {
        return runCatching {
            val json = JSONObject(this)
            val url = json.optString("url").ifBlank { this }
            val referer = json.optString("referer").ifBlank { "$mainUrl/" }
            url to referer
        }.getOrDefault(this to "$mainUrl/")
    }

    private fun Element.isDetailMetaLink(label: String): Boolean {
        val lowerHref = attr("href").lowercase()
        if (!lowerHref.contains("/$label/")) return false

        val classTrail = parents().take(4).joinToString(" ") { it.className().lowercase() }
        if (classTrail.contains(label) || classTrail.contains("meta") || classTrail.contains("data") ||
            classTrail.contains("info") || classTrail.contains("sgeneros") || classTrail.contains("genre")) {
            return true
        }

        val localText = listOfNotNull(
            parent()?.ownText(),
            parent()?.previousElementSibling()?.text(),
            parent()?.parent()?.ownText()
        ).joinToString(" ").lowercase()

        return localText.contains(label)
    }

    private fun String.origin(): String? {
        return runCatching {
            val uri = URI(this)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            "$scheme://$host"
        }.getOrNull()
    }

    private fun String.hostLabel(): String {
        return runCatching { URI(this).host?.removePrefix("www.")?.substringBefore('.')?.replaceFirstChar { it.uppercase() } }
            .getOrNull()
            ?: name
    }

    private fun String.isMediaAssetUrl(): Boolean {
        val path = runCatching { URI(this).path.lowercase() }.getOrDefault(lowercase())
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
            path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".svg") ||
            path.endsWith(".ico") || path.contains("/wp-content/uploads/") &&
            !path.contains(".m3u8") && !path.contains(".mp4")
    }

    private fun String.isValidImageUrl(): Boolean {
        val lower = lowercase()
        return startsWith("http", true) && !lower.startsWith("data:") && !lower.contains("blank") &&
            !lower.contains("placeholder") && !lower.endsWith(".svg")
    }

    private fun String.isNoisyTitle(): Boolean {
        val lower = lowercase()
        return lower.isBlank() || lower == "watch movie" || lower == "trailer" || lower == "hd" || lower == "play" ||
            lower.startsWith("permalink to:") || lower.startsWith("permalink ke:")
    }

    private fun String.isLikelyPersonName(): Boolean {
        if (isBlank() || length < 2 || length > 60) return false
        val lower = lowercase()
        if (lower.contains("watch") || lower.contains("trailer") || lower.contains("episode") || lower.contains("season")) return false
        return true
    }

    private fun Element.isInsideNoiseBlock(): Boolean {
        return parents().any { it.`is`("header, footer, nav, .menu, .navbar, .related, .recommendations, .sidebar") }
    }

    companion object {
        private val MEDIA_URL_REGEX = Regex("""https?://[^'"<>()\s]+?(?:\.mp4|\.m3u8|\.webm|\.mkv|videoplayback)[^'"<>()\s]*""", RegexOption.IGNORE_CASE)
        private val IFRAME_REGEX = Regex("""<(?:iframe|embed)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val PLAYER_VALUE_REGEX = Regex(
            """(?i)(?:file|url|src|source|link|embed_url|iframe)\s*[:=]\s*["']([^"']+(?:https?:\\?/\\?/|/embed/|/player/|\.m3u8|\.mp4)[^"']*)["']"""
        )
    }
}
