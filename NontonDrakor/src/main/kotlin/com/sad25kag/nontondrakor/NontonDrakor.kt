package com.sad25kag.nontondrakor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
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
        "$mainUrl/genre/drama-korea/page/%d/" to "Drama Korea",
        "$mainUrl/tv/page/%d/" to "TV Shows",
        "$mainUrl/genre/c-drama/page/%d/" to "C-Drama",
        "$mainUrl/genre/action/page/%d/" to "Action",
        "$mainUrl/genre/comedy/page/%d/" to "Comedy",
        "$mainUrl/genre/crime/page/%d/" to "Crime",
        "$mainUrl/genre/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/genre/mystery/page/%d/" to "Mystery",
        "$mainUrl/genre/romance/page/%d/" to "Romance",
        "$mainUrl/genre/thriller/page/%d/" to "Thriller",
        "$mainUrl/genre/film-india/page/%d/" to "Film India",
        "$mainUrl/genre/film-semi/page/%d/" to "18+"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.pageUrl(page)
        val document = app.get(url, headers = headers, referer = "$mainUrl/").document
        val results = document.toSearchResults(url)
        val hasNext = results.isNotEmpty() && document.hasNextPage(page)
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv"
        )

        val results = linkedMapOf<String, SearchResponse>()
        for (url in urls) {
            val text = runCatching { app.get(url, headers = headers, referer = "$mainUrl/").text }.getOrNull().orEmpty()
            if (text.isBlank()) continue
            Jsoup.parse(text, url).toSearchResults(url).forEach { results[it.url] = it }
            if (results.isNotEmpty()) break
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = url.absoluteUrl(mainUrl) ?: url
        val document = app.get(pageUrl, headers = headers, referer = "$mainUrl/").document

        if (pageUrl.isEpisodeUrl()) {
            val seriesUrl = document.extractSeriesUrl(pageUrl)
            if (!seriesUrl.isNullOrBlank() && seriesUrl != pageUrl) {
                val seriesDocument = runCatching { app.get(seriesUrl, headers = headers, referer = pageUrl).document }.getOrNull()
                if (seriesDocument != null) return seriesDocument.toSeriesOrMovieLoadResponse(seriesUrl)
            }
            return document.toEpisodeMovieLoadResponse(pageUrl)
        }

        return document.toSeriesOrMovieLoadResponse(pageUrl)
    }

    private suspend fun Document.toSeriesOrMovieLoadResponse(pageUrl: String): LoadResponse {
        val title = extractDetailTitle(pageUrl) ?: pageUrl.slugTitle()
        val poster = extractPoster(pageUrl)
        val plot = extractPlot()
        val tags = extractTags()
        val year = extractYear()
        val actors = extractActors()
        val status = extractStatus()
        val episodes = extractEpisodes(pageUrl)
        val recommendations = extractRecommendations(pageUrl)
        val type = when {
            pageUrl.contains("/tv/", true) || episodes.isNotEmpty() -> TvType.AsianDrama
            else -> TvType.Movie
        }

        return if (type == TvType.Movie || episodes.isEmpty()) {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, extractMoviePlayData(pageUrl) ?: pageUrl.toPlaybackData(pageUrl)) {
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

    private fun Document.toEpisodeMovieLoadResponse(pageUrl: String): LoadResponse {
        val title = extractDetailTitle(pageUrl)?.cleanEpisodeTitle(null) ?: pageUrl.slugTitle().cleanEpisodeTitle(null)
        val poster = extractPoster(pageUrl)
        val plot = extractPlot()
        val tags = extractTags()
        val year = extractYear()
        val actors = extractActors()
        val playData = extractMoviePlayData(pageUrl) ?: pageUrl.toPlaybackData(pageUrl)

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, playData) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot?.let { this.plot = it }
            this.tags = tags
            this.year = year
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

        suspend fun emitDirect(rawUrl: String?, sourceName: String, refererUrl: String, typeHint: ExtractorLinkType? = null) {
            val fixed = rawUrl.decodeCandidate()?.absoluteUrl(refererUrl)?.cleanMediaUrl() ?: return
            if (!fixed.isEvidenceMediaUrl() || !emitted.add(fixed)) return
            val type = typeHint ?: if (fixed.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(sourceName, sourceName, fixed, type) {
                    quality = fixed.qualityFromUrl()
                    referer = refererUrl
                    headers = fixed.playbackHeaders(refererUrl)
                }
            )
            delivered++
        }

        suspend fun resolveWithExtractor(playerUrl: String, refererUrl: String): Int {
            var count = 0
            runCatching {
                loadExtractor(playerUrl, refererUrl, subtitleCallback) { link ->
                    callback(link)
                    count++
                }
            }
            return count
        }

        lateinit var resolvePlayerDocument: suspend (Document, String) -> Unit
        lateinit var resolveAjaxPlayers: suspend (Document, String) -> Unit

        suspend fun resolvePlayer(rawPlayerUrl: String?, refererUrl: String, label: String = name) {
            val playerUrl = rawPlayerUrl.decodeCandidate()?.absoluteUrl(refererUrl)?.cleanMediaUrl() ?: return
            if (playerUrl.isMediaAssetUrl()) return
            if (playerUrl.isEvidenceMediaUrl()) {
                emitDirect(playerUrl, playerUrl.hostLabel().ifBlank { label }, refererUrl)
                return
            }

            val lower = playerUrl.lowercase()
            if (lower.startsWith(mainUrl.lowercase()) || lower.contains("/eps/")) {
                val text = runCatching { app.get(playerUrl, headers = headers, referer = refererUrl).text }.getOrNull().orEmpty()
                if (text.isNotBlank()) resolvePlayerDocument(Jsoup.parse(text, playerUrl), playerUrl)
                return
            }

            // Resolve only players discovered from the active page / iframe evidence.
            delivered += resolveWithExtractor(playerUrl, refererUrl)
            val playerText = runCatching { app.get(playerUrl, headers = headers, referer = refererUrl).text }.getOrNull().orEmpty()
            if (playerText.isNotBlank()) {
                playerText.extractMediaUrls().forEach { emitDirect(it, playerUrl.hostLabel(), playerUrl) }
                playerText.extractSubtitleUrls().forEach { subtitleCallback(SubtitleFile(it.subtitleLabel(), it)) }
            }
        }

        resolveAjaxPlayers = { document: Document, refererUrl: String ->
            document.extractAjaxPlayerPayloads().forEach { payload ->
                val text = runCatching {
                    app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = payload,
                        headers = headers + mapOf(
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                            "X-Requested-With" to "XMLHttpRequest"
                        ),
                        referer = refererUrl
                    ).text
                }.getOrNull().orEmpty()
                if (text.isBlank()) return@forEach
                val ajaxDocument = Jsoup.parse(text, refererUrl)
                ajaxDocument.extractEvidencePlayers(refererUrl).forEach { resolvePlayer(it, refererUrl, "Server") }
                text.extractMediaUrls().forEach { emitDirect(it, name, refererUrl) }
            }
        }

        resolvePlayerDocument = { document: Document, refererUrl: String ->
            document.extractEvidencePlayers(refererUrl).forEach { resolvePlayer(it, refererUrl, it.hostLabel()) }
            document.extractServerPages(refererUrl).forEach { (serverLabel, serverPage) ->
                val serverText = runCatching { app.get(serverPage, headers = headers, referer = refererUrl).text }.getOrNull().orEmpty()
                if (serverText.isBlank()) return@forEach
                val serverDocument = Jsoup.parse(serverText, serverPage)
                serverDocument.extractEvidencePlayers(serverPage).forEach { resolvePlayer(it, serverPage, serverLabel) }
                serverText.extractMediaUrls().forEach { emitDirect(it, serverLabel, serverPage) }
                resolveAjaxPlayers(serverDocument, serverPage)
            }
            document.html().extractMediaUrls().forEach { emitDirect(it, name, refererUrl) }
            document.html().extractSubtitleUrls().forEach { subtitleCallback(SubtitleFile(it.subtitleLabel(), it)) }
            resolveAjaxPlayers(document, refererUrl)
        }

        resolvePlayer(pageUrl, initialReferer, name)
        if (delivered > 0 && pageUrl.isEvidenceMediaUrl()) return true

        val pageText = runCatching { app.get(pageUrl, headers = headers, referer = initialReferer).text }.getOrNull().orEmpty()
        if (pageText.isBlank()) return delivered > 0
        resolvePlayerDocument(Jsoup.parse(pageText, pageUrl), pageUrl)

        return delivered > 0
    }

    private fun String.pageUrl(page: Int): String {
        if (page <= 1) {
            return replace("/page/%d/", "/")
                .replace("/page/%d", "/")
                .replace("%d", "")
        }
        return format(page)
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        return select(
            "a[href*='/page/${page + 1}/'], a.next, .next a[href], .pagination a[href*='/page/${page + 1}/'], " +
                ".nav-links a[href*='/page/${page + 1}/'], .loadmore, [data-page='${page + 1}']"
        ).isNotEmpty()
    }

    private fun Document.toSearchResults(baseUrl: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        select(
            "article, .ml-item, .item, .movie-item, .module, .owl-item, .result-item, .poster, .card, " +
                ".post, .list-item, .film, .tvshows, .movies-list .movie, .grid-item, .box, .swiper-slide"
        ).forEach { card ->
            card.toSearchResult(baseUrl)?.let { results[it.url] = it }
        }

        if (results.isEmpty()) {
            select("h1 a[href], h2 a[href], h3 a[href], h4 a[href], .entry-title a[href], .post-title a[href], .title a[href]").forEach { anchor ->
                (anchor.closest("article, .item, .movie-item, .module, .result-item, .post, .card, .poster") ?: anchor.parent() ?: anchor)
                    .toSearchResult(baseUrl)?.let { results[it.url] = it }
            }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val anchors = select("h1 a[href], h2 a[href], h3 a[href], h4 a[href], .entry-title a[href], .post-title a[href], .title a[href], .name a[href], a[href]")
            .filter { it.attr("href").absoluteUrl(baseUrl)?.isContentUrl() == true }
            .sortedBy { it.anchorPriority() }
        val anchor = anchors.firstOrNull() ?: return null
        val href = anchor.attr("href").absoluteUrl(baseUrl) ?: return null
        if (!href.isContentUrl()) return null

        val title = listOf(
            selectFirst("h1, h2, h3, h4, .entry-title, .post-title, .mli-title, .title, .name")?.text(),
            anchor.attr("title"),
            anchor.text(),
            selectFirst("img[alt]")?.attr("alt")
        ).firstCleanTitle() ?: return null
        val poster = selectFirst("img")?.imageUrl(href)

        return when {
            href.contains("/tv/", true) -> newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
            else -> newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    private fun Element.anchorPriority(): Int {
        val text = text().lowercase()
        val href = attr("href").lowercase()
        return when {
            tagName().matches(Regex("h[1-4]", RegexOption.IGNORE_CASE)) -> 0
            parent()?.tagName()?.matches(Regex("h[1-4]", RegexOption.IGNORE_CASE)) == true -> 0
            hasClass("title") || hasClass("entry-title") || hasClass("post-title") -> 1
            text.equals("watch", true) || text.equals("watch movie", true) || text.equals("trailer", true) -> 9
            href.contains("/movie/") || href.contains("/tv/") || href.contains("/eps/") -> 2
            else -> 5
        }
    }

    private fun Document.extractDetailTitle(pageUrl: String): String? {
        return listOf(
            selectFirst("h1.entry-title, h1.post-title, .sheader h1, .data h1, .movie-title h1, .entry-header h1, h1")?.text(),
            selectFirst("meta[property=og:title]")?.attr("content"),
            selectFirst("title")?.text()
        ).firstCleanTitle() ?: pageUrl.slugTitle().takeIf { it.isNotBlank() }
    }

    private fun Document.extractPoster(baseUrl: String): String? {
        return listOfNotNull(
            selectFirst("meta[property=og:image]")?.imageUrl(baseUrl),
            selectFirst(".poster img, .thumb img, .image img, .movie-poster img, .sheader img, .entry-content img, img.wp-post-image")?.imageUrl(baseUrl),
            selectFirst("video[poster]")?.imageUrl(baseUrl)
        ).firstOrNull { it.isValidImageUrl() }
    }

    private fun Document.extractPlot(): String? {
        return listOf(
            selectFirst(".synopsis, .desc, .summary, .storyline, .sinopsis, div.entry-content[itemprop='description'], .entry-content p")?.text(),
            selectFirst("meta[property=og:description]")?.attr("content"),
            selectFirst("meta[name=description]")?.attr("content")
        ).mapNotNull { it?.cleanText()?.stripDetailMetadata() }
            .firstOrNull { it.isValidPlot() }
    }

    private fun Document.extractTags(): List<String> {
        return select("a[href*='/genre/'], [itemprop='genre'] a[href]")
            .filterNot { it.isInsideNoiseBlock() }
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() && it.length <= 40 && !it.equals("Genre", true) }
            .distinct()
            .take(12)
    }

    private fun Document.extractActors(): List<String> {
        val peoplePaths = listOf("/cast/", "/actor/", "/actors/", "/star/", "/stars/")
        return select("[itemprop='actors'] a, [itemprop='actor'] a, a[href]")
            .filter { element -> peoplePaths.any { element.attr("href").contains(it, true) } }
            .filterNot { it.isInsideNoiseBlock() }
            .map { it.text().cleanText() }
            .filter { it.isLikelyPersonName() }
            .distinct()
            .take(20)
    }

    private fun Document.extractYear(): Int? {
        val linkYear = select("a[href*='/year/'], a[href*='movieyear'], a[href*='/release/']")
            .mapNotNull { Regex("(?:19|20)\\d{2}").find(it.text())?.value?.toIntOrNull() }
            .firstOrNull()
        return linkYear ?: Regex("(?:19|20)\\d{2}").find(text())?.value?.toIntOrNull()
    }

    private fun Document.extractStatus(): ShowStatus? {
        return listOfNotNull(
            selectFirst(".status, .dtstatus, [class*=status]")?.text(),
            text()
        ).firstNotNullOfOrNull { it.toShowStatus() }
    }

    private fun Document.extractEpisodes(baseUrl: String): List<Episode> {
        val episodeContainers = select(
            ".gmr-listseries, .episodios, .se-c, .episodelist, .episode-list, .les-content, .eplister, .listing, .entry-content"
        ).filterNot { it.isInsideNoiseBlock() }
        val episodeLinks = if (episodeContainers.isNotEmpty()) {
            episodeContainers.flatMap { it.select("a[href*='/eps/'], a[href*='/episode/']") }
        } else {
            select("article a[href*='/eps/'], .entry-content a[href*='/eps/'], .post a[href*='/eps/'], a[href*='/eps/'], a[href*='/episode/']")
                .filterNot { it.isInsideNoiseBlock() }
        }

        return episodeLinks.mapNotNull { it.toEpisode(baseUrl) }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })
    }

    private fun Element.toEpisode(baseUrl: String): Episode? {
        val href = attr("href").absoluteUrl(baseUrl) ?: return null
        if (!href.isEpisodeUrl()) return null
        val rawText = attr("title").ifBlank { text() }.ifBlank { href.slugTitle() }
        val epNum = Regex("(?i)(?:EP|Episode|Eps?\\.?|Ep\\.?|S\\d+\\s*Eps?)\\s*(\\d+)").find(rawText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)(?:episode|eps?)[-_ ]?(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val cleaned = rawText.cleanTitle().cleanEpisodeTitle(epNum)
        return newEpisode(href.toPlaybackData(baseUrl)) {
            name = cleaned.ifBlank { "Episode ${epNum ?: ""}".trim() }
            episode = epNum
            posterUrl = selectFirst("img")?.imageUrl(baseUrl)
        }
    }

    private fun Document.extractMoviePlayData(pageUrl: String): String? {
        val candidates = mutableListOf<String>()
        select(
            ".muvipro-player-tabs a[href], .player a[href], .play a[href], .server a[href], .embed a[href], " +
                ".btn-play[href], .button-play[href], a[href*='/player/'], a[href*='/watch/'], a[href*='/embed/'], a[href*='?player='], " +
                "iframe[src], embed[src], video[src], video source[src], source[src]"
        ).filterNot { it.isInsideNoiseBlock() }.forEach { element ->
            listOf("href", "src", "data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file").forEach { attr ->
                candidates.add(element.attr(attr))
            }
            element.attributes().asList().filter { it.key.startsWith("data-", true) }.forEach { candidates.add(it.value) }
        }

        html().extractPlayersFromText().forEach { candidates.add(it) }

        return candidates.asSequence()
            .mapNotNull { it.decodeCandidate()?.absoluteUrl(pageUrl) }
            .filter { it.isLikelyPlayerUrl(pageUrl) }
            .filterNot { it.isMediaAssetUrl() }
            .distinct()
            .firstOrNull()
            ?.toPlaybackData(pageUrl)
    }

    private fun Document.extractRecommendations(pageUrl: String): List<SearchResponse> {
        return select(".related, .recommendations, .owl-carousel, .section, .latest, .archive")
            .flatMap { it.toSearchResultsLike(pageUrl) }
            .filterNot { it.url == pageUrl }
            .distinctBy { it.url }
            .take(20)
    }

    private fun Element.toSearchResultsLike(pageUrl: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        select("article, .item, .movie-item, .module, .owl-item, .result-item, .post, .card, .poster").forEach { card ->
            card.toSearchResult(pageUrl)?.let { if (it.url != pageUrl) results[it.url] = it }
        }
        return results.values.toList()
    }

    private fun Document.extractServerPages(pageUrl: String): List<Pair<String, String>> {
        return select(".muvipro-player-tabs a[href], .gmr-embed-responsive a[href], .player a[href], .server a[href], .embed a[href], a[href*='?player=']")
            .mapNotNull { element ->
                val label = element.text().cleanTitle().ifBlank { element.attr("title").cleanTitle().ifBlank { "Server" } }
                val href = element.attr("href").absoluteUrl(pageUrl) ?: return@mapNotNull null
                if (href.isLikelyPlayerUrl(pageUrl)) label to href else null
            }
            .distinctBy { it.second }
    }

    private fun Document.extractEvidencePlayers(pageUrl: String): List<String> {
        val candidates = mutableListOf<String>()
        select("iframe[src], embed[src], video[src], source[src], video source[src], [data-src], [data-url], [data-link], [data-iframe], [data-embed], [data-player], [data-video], [data-file]")
            .filterNot { it.isInsideNoiseBlock() }
            .forEach { element ->
                listOf("src", "href", "data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file", "data-stream").forEach { attr ->
                    candidates.add(element.attr(attr))
                }
                element.attributes().asList().filter { it.key.startsWith("data-", true) }.forEach { candidates.add(it.value) }
            }
        html().extractPlayersFromText().forEach { candidates.add(it) }
        html().extractMediaUrls().forEach { candidates.add(it) }

        return candidates.asSequence()
            .mapNotNull { it.decodeCandidate()?.absoluteUrl(pageUrl) }
            .filter { it.isLikelyPlayerUrl(pageUrl) || it.isEvidenceMediaUrl() }
            .filterNot { it.isMediaAssetUrl() }
            .distinct()
            .toList()
    }

    private fun Document.extractAjaxPlayerPayloads(): List<Map<String, String>> {
        val payloads = mutableListOf<Map<String, String>>()
        select("[data-post][data-nume], [data-post][data-type], [data-id][data-nume], [data-id][data-type]").forEach { element ->
            val post = element.attr("data-post").ifBlank { element.attr("data-id") }.ifBlank { element.attr("data-movie") }
            val nume = element.attr("data-nume").ifBlank { element.attr("data-server") }.ifBlank { element.attr("data-no") }
            val type = element.attr("data-type").ifBlank { element.attr("data-player") }.ifBlank { "movie" }
            if (post.isNotBlank() && nume.isNotBlank()) {
                payloads.add(mapOf("action" to "muvipro_player_content", "post" to post, "nume" to nume, "type" to type))
            }
        }

        val clean = html().replace("\\/", "/")
        Regex("""(?is)(?:post|post_id|id)\s*[:=]\s*['"]?(\d+)['"]?.{0,160}?(?:nume|server)\s*[:=]\s*['"]?(\d+)['"]?.{0,160}?(?:type)\s*[:=]\s*['"]?([A-Za-z0-9_-]+)['"]?""").findAll(clean).forEach { match ->
            payloads.add(
                mapOf(
                    "action" to "muvipro_player_content",
                    "post" to match.groupValues[1],
                    "nume" to match.groupValues[2],
                    "type" to match.groupValues[3]
                )
            )
        }
        return payloads.distinctBy { it.toString() }
    }

    private fun String.extractPlayersFromText(): List<String> {
        val clean = replace("\\/", "/").replace("&amp;", "&")
        val results = mutableListOf<String>()
        IFRAME_REGEX.findAll(clean).forEach { results.add(it.groupValues[1]) }
        PLAYER_VALUE_REGEX.findAll(clean).forEach { results.add(it.groupValues[1]) }
        Regex("""https?://[^'"<>\s]+/(?:embed|e|epu|player|watch)/[^'"<>\s]+""", RegexOption.IGNORE_CASE).findAll(clean).forEach { results.add(it.value) }
        Regex("""https?://[^'"<>\s]+\?player=[^'"<>\s]+""", RegexOption.IGNORE_CASE).findAll(clean).forEach { results.add(it.value) }
        return results.distinct()
    }

    private fun String.extractMediaUrls(): List<String> {
        return MEDIA_URL_REGEX.findAll(replace("\\/", "/").replace("&amp;", "&"))
            .map { it.value.cleanMediaUrl() }
            .distinct()
            .toList()
    }

    private fun String.extractSubtitleUrls(): List<String> {
        return Regex("""https?://[^'"<>\s]+?\.(?:vtt|srt)(?:\?[^'"<>\s]*)?""", RegexOption.IGNORE_CASE)
            .findAll(replace("\\/", "/").replace("&amp;", "&"))
            .map { it.value.cleanMediaUrl() }
            .distinct()
            .toList()
    }

    private fun Document.extractSeriesUrl(baseUrl: String): String? {
        return selectFirst("a.gmr-all-serie[href], a[href*='/tv/']")
            ?.attr("href")
            ?.absoluteUrl(baseUrl)
            ?.takeIf { it.contains("/tv/", true) }
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
            runCatching { base64Decode(clean) }.getOrNull()?.let { decoded ->
                if (decoded.contains("http", true) || decoded.contains("iframe", true)) return decoded
            }
        }
        return Regex("https?://[^'\"<>\\s]+", RegexOption.IGNORE_CASE).find(clean)?.value ?: clean
    }

    private fun String.isContentUrl(): Boolean {
        val path = runCatching { URI(this).path.lowercase() }.getOrNull() ?: return false
        return path.startsWith("/eps/") || path.startsWith("/episode/") || path.startsWith("/movie/") || path.startsWith("/tv/") || isRootMovieUrl()
    }

    private fun String.isRootMovieUrl(): Boolean {
        val path = runCatching { URI(this).path.lowercase().trim('/') }.getOrNull() ?: return false
        if (path.isBlank() || path.contains('/')) return false
        if (path.length < 4 || path.matches(Regex("\\d{4}"))) return false
        val blockedExact = setOf(
            "index-movie", "cara-download", "menampilkan-subtitle-setelah-download", "order-by-title",
            "best-rating", "hd", "home", "tv", "tv-shows", "c-drama", "drakor", "film-india",
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

    private fun String.isLikelyPlayerUrl(pageUrl: String): Boolean {
        val lower = lowercase()
        if (isEvidenceMediaUrl()) return true
        if (isMediaAssetUrl()) return false
        if (!startsWith("http", true) && !startsWith("/")) return false
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) return false
        if (lower.contains("/genre/") || lower.contains("/country/") || lower.contains("/blog-category/") ||
            lower.contains("/tag/") || lower.contains("/cast/") || lower.contains("/actor/") || lower.contains("/director/") ||
            lower.contains("/creator/") || lower.contains("/quality/") || lower.contains("/year/") || lower.contains("/page/")) return false
        val host = runCatching { URI(this).host?.removePrefix("www.") }.getOrNull().orEmpty()
        val sourceHost = runCatching { URI(pageUrl).host?.removePrefix("www.") }.getOrNull().orEmpty()
        if (host.isNotBlank() && host != sourceHost) return true
        return lower.contains("/eps/") || lower.contains("/episode/") || lower.contains("/player") || lower.contains("/embed") || lower.contains("/watch") || lower.contains("?player")
    }

    private fun String.isEvidenceMediaUrl(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") ||
            lower.contains("videoplayback") || lower.contains("/get_video?")
    }

    private fun String.isMediaAssetUrl(): Boolean {
        val path = runCatching { URI(this).path.lowercase() }.getOrDefault(lowercase())
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp") ||
            path.endsWith(".gif") || path.endsWith(".svg") || path.endsWith(".ico") ||
            path.contains("/wp-content/uploads/") && !path.contains(".m3u8") && !path.contains(".mp4")
    }

    private fun String.playbackHeaders(refererUrl: String): Map<String, String> {
        val map = mutableMapOf("Referer" to refererUrl, "User-Agent" to USER_AGENT)
        refererUrl.origin()?.let { map["Origin"] = it }
        return map
    }

    private fun String.toPlaybackData(refererUrl: String): String {
        return if (this == refererUrl) this else JSONObject().put("url", this).put("referer", refererUrl).toString()
    }

    private fun String.parsePlaybackData(): Pair<String, String> {
        return runCatching {
            val json = JSONObject(this)
            val url = json.optString("url").ifBlank { this }
            val referer = json.optString("referer").ifBlank { "$mainUrl/" }
            url to referer
        }.getOrDefault(this to "$mainUrl/")
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("(?i)^\\s*Permalink\\s+(?:to|ke)\\s*:?\\s*"), "")
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
        if (epNum != null) return "Episode $epNum"
        val found = Regex("(?i)(?:episode|eps?)\\s*(\\d+)").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return if (found != null) "Episode $found" else this
    }

    private fun String.cleanText(): String = Jsoup.parse(this).text().replace(Regex("\\s+"), " ").trim()

    private fun String.stripDetailMetadata(): String {
        var output = cleanText()
        val markers = listOf(
            " By:", " Posted on:", " Views:", " Tagline:", " Genre:", " Quality:", " Year:", " Duration:",
            " Country:", " Release:", " Last Air Date:", " Number Of Episode:", " Network:", " Cast:", " Director:", " Stars:"
        )
        markers.forEach { marker ->
            val index = output.indexOf(marker, ignoreCase = true)
            if (index > 0) output = output.substring(0, index).trim()
        }
        return output.replace(Regex("\\s+"), " ").trim()
    }

    private fun String.cleanMediaUrl(): String {
        val cleaned = replace("\\/", "/").replace("&amp;", "&").trim(' ', '\'', '"')
        return runCatching { URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
    }

    private fun String.srcSetFirst(): String {
        return split(",").map { it.trim().split(Regex("\\s+")).firstOrNull().orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun String.slugTitle(): String {
        return substringBefore('?').trimEnd('/').substringAfterLast('/').replace('-', ' ').cleanTitle()
    }

    private fun List<String?>.firstCleanTitle(): String? {
        return mapNotNull { it?.cleanTitle()?.takeIf { cleaned -> cleaned.isNotBlank() && !cleaned.isNoisyTitle() } }.firstOrNull()
    }

    private fun String.toShowStatus(): ShowStatus? {
        val lower = lowercase()
        return when {
            lower.contains("completed") || lower.contains("tamat") || lower.contains("end") || lower.contains("complete") -> ShowStatus.Completed
            lower.contains("ongoing") || lower.contains("on-going") || lower.contains("berjalan") -> ShowStatus.Ongoing
            else -> null
        }
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
            .getOrNull() ?: name
    }

    private fun String.subtitleLabel(): String {
        val file = substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        return file.substringAfterLast('_').substringAfterLast('-').ifBlank { "Subtitle" }
    }

    private fun String.isValidImageUrl(): Boolean {
        val lower = lowercase()
        return startsWith("http", true) && !lower.startsWith("data:") && !lower.contains("blank") && !lower.contains("placeholder") && !lower.endsWith(".svg")
    }

    private fun String.isNoisyTitle(): Boolean {
        val lower = lowercase()
        return lower.isBlank() || lower == "watch movie" || lower == "watch" || lower == "trailer" || lower == "hd" || lower == "play" ||
            lower.startsWith("permalink to:") || lower.startsWith("permalink ke:")
    }

    private fun String.isValidPlot(): Boolean {
        if (isBlank() || length < 40) return false
        val lower = lowercase()
        val seo = listOf("subtitle indonesia", "streaming drakor", "download video", "drakor-id", "full episode", "gambar lebih jernih")
        return seo.none { lower.contains(it) }
    }

    private fun String.isLikelyPersonName(): Boolean {
        if (isBlank() || length < 2 || length > 60) return false
        val lower = lowercase()
        if (lower.contains("watch") || lower.contains("trailer") || lower.contains("episode") || lower.contains("season")) return false
        return true
    }

    private fun Element.isInsideNoiseBlock(): Boolean {
        return parents().any { it.`is`("header, footer, nav, .menu, .navbar, .sidebar") }
    }

    companion object {
        private val MEDIA_URL_REGEX = Regex("""https?://[^'"<>()\s]+?(?:\.mp4|\.m3u8|\.webm|\.mkv|videoplayback|get_video\?)[^'"<>()\s]*""", RegexOption.IGNORE_CASE)
        private val IFRAME_REGEX = Regex("""<(?:iframe|embed)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val PLAYER_VALUE_REGEX = Regex(
            """(?i)(?:file|url|src|source|link|embed_url|iframe)\s*[:=]\s*["']([^"']+(?:https?:\?/\?/|/embed/|/player/|/watch/|\.m3u8|\.mp4)[^"']*)["']"""
        )
    }
}
