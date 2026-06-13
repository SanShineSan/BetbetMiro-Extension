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
        "$mainUrl/genre/film-semi/page/%d/" to "18+",
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

    private val tmdbApiKey = "b030404650f279792a8d3287232358e3"
    private val tmdbImageBase = "https://image.tmdb.org/t/p/w185"

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
            val seriesUrl = document.extractSeriesUrl(pageUrl)
            if (!seriesUrl.isNullOrBlank() && seriesUrl != pageUrl) {
                val seriesDocument = runCatching { app.get(seriesUrl, headers = headers, referer = pageUrl).document }.getOrNull()
                if (seriesDocument != null) return seriesDocument.toSeriesOrMovieLoadResponse(seriesUrl)
            }
            return document.toEpisodeLoadResponse(pageUrl)
        }

        return document.toSeriesOrMovieLoadResponse(pageUrl)
    }

    private suspend fun Document.toSeriesOrMovieLoadResponse(pageUrl: String): LoadResponse {
        val title = extractDetailTitle(pageUrl)
            ?: throw ErrorLoadingException("Title not found")
        val detailScopes = detailScopes()
        val sourcePoster = extractPoster(pageUrl)
        val sourcePlot = extractPlot()
        val sourceTags = detailScopes.extractScopedLinks("/genre/")
        val sourceActors = detailScopes.extractScopedPeople()
        val status = detailScopes.joinToString(" ") { it.text() }.toShowStatus()
            ?: selectFirst(".status, .dtstatus, [class*=status]")?.text()?.toShowStatus()
        val sourceYear = detailScopes.extractYear()
        val sourceTotalEpisodes = extractTotalEpisodes()
        val initialType = when {
            pageUrl.contains("/movie/", true) || pageUrl.isRootMovieUrl() -> TvType.Movie
            pageUrl.contains("/tv/", true) -> TvType.AsianDrama
            else -> TvType.Movie
        }
        val meta = fetchTmdbMetadata(title, sourceYear, initialType)
        val episodes = extractEpisodes(pageUrl, sourceTotalEpisodes)
        val recommendations = extractRecommendations(pageUrl)
        val type = when {
            pageUrl.contains("/movie/", true) || pageUrl.isRootMovieUrl() -> TvType.Movie
            pageUrl.contains("/tv/", true) || episodes.isNotEmpty() || (sourceTotalEpisodes ?: 0) > 1 -> TvType.AsianDrama
            else -> TvType.Movie
        }
        val poster = sourcePoster ?: meta?.poster
        val plot = sourcePlot ?: meta?.plot
        val tags = sourceTags.ifEmpty { meta?.genres.orEmpty() }
        val actors = sourceActors.ifEmpty { meta?.actors.orEmpty() }
        val year = sourceYear ?: meta?.year
        val finalStatus = status ?: meta?.status

        return if (type == TvType.Movie || episodes.isEmpty()) {
            val rawPlayData = extractMoviePlayData(pageUrl)
            val playData = rawPlayData?.toPlaybackData(pageUrl) ?: pageUrl.toPlaybackData(pageUrl)
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
                showStatus = finalStatus
                this.actors = actors.map { ActorData(Actor(it)) }
                this.recommendations = recommendations
            }
        }
    }

    private suspend fun Document.toEpisodeLoadResponse(url: String): LoadResponse {
        val title = extractDetailTitle(url)
            ?: url.substringAfterLast('/').replace('-', ' ').cleanTitle()
        val poster = extractPoster(url)
        val sourcePlot = extractPlot()
        val detailScopes = detailScopes()
        val sourceTags = detailScopes.extractScopedLinks("/genre/")
        val sourceActors = detailScopes.extractScopedPeople()
        val year = detailScopes.extractYear()
        val meta = fetchTmdbMetadata(title.removeEpisodeSuffix(), year, TvType.AsianDrama)
        val rawPlayData = extractMoviePlayData(url)
        val playData = rawPlayData?.toPlaybackData(url) ?: url.toPlaybackData(url)
        val plot = sourcePlot ?: meta?.plot
        val tags = sourceTags.ifEmpty { meta?.genres.orEmpty() }
        val actors = sourceActors.ifEmpty { meta?.actors.orEmpty() }
        return newMovieLoadResponse(title.cleanEpisodeTitle(null), url, TvType.Movie, playData) {
            posterUrl = poster ?: meta?.poster
            backgroundPosterUrl = poster ?: meta?.poster
            plot?.let { this.plot = it }
            this.tags = tags
            this.year = year ?: meta?.year
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

        suspend fun resolvePlayer(playerUrl: String, label: String, refererUrl: String) {
            val fixed = playerUrl.absoluteUrl(refererUrl) ?: return
            val lower = fixed.lowercase()
            when {
                fixed.isEvidenceMediaUrl() -> emitDirect(fixed, label, refererUrl)
                lower.contains("vidmoly.biz") -> delivered += resolveVidmoly(fixed, subtitleCallback, callback, emitted)
                lower.contains("strcloud.in") -> delivered += resolveStrcloud(fixed, subtitleCallback, callback, emitted)
                lower.contains("justplay.cam") || lower.contains("nzn3.org") -> delivered += resolveJustplay(fixed, subtitleCallback, callback, emitted)
            }
        }

        resolvePlayer(pageUrl, name, initialReferer)
        if (delivered > 0 && pageUrl.isEvidenceMediaUrl()) return true

        val pageText = runCatching { app.get(pageUrl, headers = headers, referer = initialReferer).text }.getOrNull().orEmpty()
        if (pageText.isBlank()) return delivered > 0
        val document = Jsoup.parse(pageText, pageUrl)

        val serverPages = document.extractServerPages(pageUrl).ifEmpty { listOf("Server" to pageUrl) }
        for ((serverLabel, serverPage) in serverPages) {
            val serverText = if (serverPage == pageUrl) pageText else runCatching {
                app.get(serverPage, headers = headers, referer = pageUrl).text
            }.getOrNull().orEmpty()
            if (serverText.isBlank()) continue
            val serverDocument = Jsoup.parse(serverText, serverPage)
            val iframePlayers = serverDocument.extractEvidencePlayers(serverPage)
            for (player in iframePlayers) {
                resolvePlayer(player, serverLabel, serverPage)
            }
        }

        return delivered > 0
    }

    private suspend fun resolveVidmoly(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emitted: MutableSet<String>
    ): Int {
        var delivered = 0
        if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) delivered++
        val text = runCatching { app.get(embedUrl, headers = headers, referer = mainUrl).text }.getOrNull().orEmpty()
        if (text.isBlank()) return delivered
        Regex("""https?://[^'\"<>\s]+?\.m3u8[^'\"<>\s]*""", RegexOption.IGNORE_CASE).findAll(text.replace("\\/", "/")).forEach { match ->
            val link = match.value.cleanMediaUrl()
            if (emitted.add(link)) {
                callback(
                    newExtractorLink("Vidmoly", "Vidmoly", link, ExtractorLinkType.M3U8) {
                        quality = link.qualityFromUrl()
                        referer = "https://vidmoly.biz/"
                        headers = link.playbackHeaders("https://vidmoly.biz/")
                    }
                )
                delivered++
            }
        }
        Regex("""https?://[^'\"<>\s]+?\.vtt[^'\"<>\s]*""", RegexOption.IGNORE_CASE).findAll(text.replace("\\/", "/")).forEach { match ->
            val subUrl = match.value.cleanMediaUrl()
            val lang = subUrl.substringAfterLast('_').substringBefore('.').ifBlank { "Subtitle" }
            subtitleCallback(SubtitleFile(lang, subUrl))
        }
        return delivered
    }

    private suspend fun resolveStrcloud(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emitted: MutableSet<String>
    ): Int {
        var delivered = 0
        if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) delivered++
        val text = runCatching { app.get(embedUrl, headers = headers, referer = mainUrl).text }.getOrNull().orEmpty()
        if (text.isBlank()) return delivered

        Regex("""https?://[^'\"<>\s]+?\.vtt[^'\"<>\s]*""", RegexOption.IGNORE_CASE).findAll(text.replace("\\/", "/")).forEach { match ->
            val subUrl = match.value.cleanMediaUrl()
            val lang = subUrl.substringAfterLast('/').substringBefore('.').ifBlank { "Subtitle" }
            subtitleCallback(SubtitleFile(lang, subUrl))
        }

        val candidates = extractStrcloudVideoCandidates(text)
        candidates.forEach { candidate ->
            val fixed = candidate.cleanMediaUrl()
            val finalUrl = runCatching {
                app.get(fixed, headers = fixed.playbackHeaders(embedUrl), referer = embedUrl).url
            }.getOrDefault(fixed).cleanMediaUrl()
            val output = if (finalUrl.isEvidenceMediaUrl()) finalUrl else fixed
            if (output.isEvidenceMediaUrl() && emitted.add(output)) {
                callback(
                    newExtractorLink("Strcloud", "Strcloud", output, ExtractorLinkType.VIDEO) {
                        quality = output.qualityFromUrl()
                        referer = "https://strcloud.in/"
                        headers = output.playbackHeaders("https://strcloud.in/")
                    }
                )
                delivered++
            }
        }
        return delivered
    }

    private fun extractStrcloudVideoCandidates(text: String): List<String> {
        val clean = text.replace("\\/", "/")
        val candidates = mutableListOf<String>()

        Regex("""(?i)(?:https?:)?//strcloud\.in/get_video\?[^'\"<>\s]+""").findAll(clean).forEach { match ->
            val url = match.value.let { if (it.startsWith("//")) "https:$it" else it }
            candidates.add(url)
        }
        Regex("""(?i)/strcloud\.in/get_video\?[^'\"<>\s]+""").findAll(clean).forEach { match ->
            candidates.add("https:/" + match.value)
        }

        Regex("""(?i)get_video\?([a-z]+)[^+]{0,80}\+\s*(?:''\s*\+\s*)?\(['\"]([^'\"]+)['\"]\)((?:\.substring\(\d+\))+)?""").findAll(clean).forEach { match ->
            val prefix = match.groupValues[1]
            val payload = applySubstringChain(match.groupValues[2], match.groupValues.getOrNull(3).orEmpty())
            candidates.add("https://strcloud.in/get_video?$prefix$payload&stream=1")
        }

        Regex("""https?://[^'\"<>\s]+?\.mp4[^'\"<>\s]*""", RegexOption.IGNORE_CASE).findAll(clean).forEach { candidates.add(it.value) }
        return candidates.distinct()
    }

    private fun applySubstringChain(raw: String, chain: String): String {
        var output = raw
        Regex("""\.substring\((\d+)\)""").findAll(chain).forEach { match ->
            val start = match.groupValues[1].toIntOrNull() ?: 0
            output = if (start <= output.length) output.substring(start) else ""
        }
        return output
    }

    private suspend fun resolveJustplay(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emitted: MutableSet<String>
    ): Int {
        var delivered = 0
        if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) delivered++
        val code = Regex("""/(?:e|epu)/([A-Za-z0-9_-]+)""").find(embedUrl)?.groupValues?.getOrNull(1)
            ?: return delivered
        val details = runCatching { app.get("https://justplay.cam/api/videos/$code/embed/details", headers = headers, referer = embedUrl).text }.getOrNull().orEmpty()
        val nznEmbed = runCatching { JSONObject(details).optString("embed_frame_url") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "https://nzn3.org/epu/$code"
        if (loadExtractor(nznEmbed, "https://justplay.cam/", subtitleCallback, callback)) delivered++
        val playbackText = runCatching {
            app.post(
                "https://nzn3.org/api/videos/$code/embed/playback",
                data = emptyMap<String, String>(),
                headers = headers + mapOf("Accept" to "application/json"),
                referer = nznEmbed
            ).text
        }.getOrNull().orEmpty()
        Regex("""https?://[^'\"<>\s]+?\.m3u8[^'\"<>\s]*""", RegexOption.IGNORE_CASE).findAll(playbackText.replace("\\/", "/")).forEach { match ->
            val link = match.value.cleanMediaUrl()
            if (emitted.add(link)) {
                callback(
                    newExtractorLink("JustPlay", "JustPlay", link, ExtractorLinkType.M3U8) {
                        quality = link.qualityFromUrl()
                        referer = "https://nzn3.org/"
                        headers = link.playbackHeaders("https://nzn3.org/")
                    }
                )
                delivered++
            }
        }
        return delivered
    }

    private fun Document.extractServerPages(pageUrl: String): List<Pair<String, String>> {
        return select(".muvipro-player-tabs a[href], .gmr-embed-responsive a[href], .player a[href], .server a[href], a[href*='?player=']")
            .mapNotNull { element ->
                val label = element.text().cleanTitle().ifBlank { "Server" }
                val href = element.attr("href").absoluteUrl(pageUrl) ?: return@mapNotNull null
                if (href.contains("/eps/", true) || href.contains("?player=", true)) label to href else null
            }
            .distinctBy { it.second }
    }

    private fun Document.extractEvidencePlayers(pageUrl: String): List<String> {
        val allowedHosts = listOf("justplay.cam", "nzn3.org", "vidmoly.biz", "strcloud.in")
        return select("iframe[src], embed[src], video[src], source[src]")
            .mapNotNull { it.attr("src").absoluteUrl(pageUrl) }
            .filter { url -> allowedHosts.any { url.contains(it, true) } || url.isEvidenceMediaUrl() }
            .distinct()
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
            selectFirst(".synopsis, .desc, .summary, .storyline, .sinopsis, div.entry-content[itemprop='description'], .entry-content p")?.text(),
            selectFirst("meta[property=og:description]")?.attr("content"),
            selectFirst("meta[name=description]")?.attr("content")
        ).mapNotNull { it?.cleanText()?.stripDetailMetadata() }
            .firstOrNull { it.isValidPlot() }
    }

    private suspend fun fetchTmdbMetadata(title: String, year: Int?, preferredType: TvType): TmdbMeta? {
        val query = URLEncoder.encode(title.removeEpisodeSuffix().cleanTitle(), "UTF-8")
        if (query.isBlank()) return null
        val types = if (preferredType == TvType.Movie) listOf("movie", "tv") else listOf("tv", "movie")
        for (type in types) {
            val searchUrl = "https://api.themoviedb.org/3/search/$type?api_key=$tmdbApiKey&query=$query&page=1"
            val searchText = runCatching { app.get(searchUrl, headers = headers, referer = mainUrl).text }.getOrNull().orEmpty()
            val results = runCatching { JSONObject(searchText).optJSONArray("results") }.getOrNull() ?: continue
            if (results.length() == 0) continue
            val best = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                .firstOrNull { item ->
                    val date = item.optString(if (type == "movie") "release_date" else "first_air_date")
                    year == null || date.startsWith(year.toString())
                } ?: results.optJSONObject(0) ?: continue
            val id = best.optInt("id", 0).takeIf { it > 0 } ?: continue
            val detailUrl = "https://api.themoviedb.org/3/$type/$id?api_key=$tmdbApiKey&append_to_response=credits"
            val detailText = runCatching { app.get(detailUrl, headers = headers, referer = mainUrl).text }.getOrNull().orEmpty()
            val detail = runCatching { JSONObject(detailText) }.getOrNull() ?: continue
            val plot = detail.optString("overview").cleanText().takeIf { it.isValidPlot() }
            val genres = detail.optJSONArray("genres")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() } }
            }.orEmpty()
            val cast = detail.optJSONObject("credits")?.optJSONArray("cast")?.let { array ->
                (0 until minOf(array.length(), 12)).mapNotNull { idx ->
                    array.optJSONObject(idx)?.optString("name")?.takeIf { it.isLikelyPersonName() }
                }
            }.orEmpty()
            val date = detail.optString(if (type == "movie") "release_date" else "first_air_date")
            val metaYear = date.substringBefore('-').toIntOrNull()
            val status = detail.optString("status").toShowStatus()
            val poster = detail.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }?.let { "$tmdbImageBase$it" }
            return TmdbMeta(plot, genres, cast, metaYear, status, poster)
        }
        return null
    }

    private data class TmdbMeta(
        val plot: String?,
        val genres: List<String>,
        val actors: List<String>,
        val year: Int?,
        val status: ShowStatus?,
        val poster: String?
    )

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

    private suspend fun Document.extractEpisodes(baseUrl: String, expectedTotal: Int? = null): List<Episode> {
        val episodeContainers = select(
            ".gmr-listseries, .episodios, .se-c, .episodelist, .episode-list, .les-content, .eplister, .listing"
        ).filterNot { it.isInsideNoiseBlock() }

        val episodeLinks = if (episodeContainers.isNotEmpty()) {
            episodeContainers.flatMap { it.select("a[href*='/eps/']") }
        } else {
            select("article a[href*='/eps/'], .entry-content a[href*='/eps/'], .post a[href*='/eps/'], a[href*='/eps/']")
                .filterNot { it.isInsideNoiseBlock() }
        }

        val parsed = episodeLinks
            .mapNotNull { it.toEpisode(baseUrl) }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })

        val total = expectedTotal ?: extractTotalEpisodes()
        val validated = parsed.toMutableList()
        if (total != null && total > parsed.size && total <= 200) {
            validated.addAll(discoverAvailableEpisodes(baseUrl, total, parsed))
        }
        return validated.distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })
    }

    private suspend fun discoverAvailableEpisodes(baseUrl: String, total: Int, existing: List<Episode>): List<Episode> {
        val existingNumbers = existing.mapNotNull { it.episode }.toSet()
        val slug = when {
            baseUrl.contains("/tv/", true) -> baseUrl.trimEnd('/').substringAfterLast('/')
            baseUrl.contains("/eps/", true) -> baseUrl.trimEnd('/').substringAfterLast('/').replace(Regex("(?i)-episode-?\\d+.*$"), "")
            else -> return emptyList()
        }
        if (slug.isBlank()) return emptyList()

        val discovered = mutableListOf<Episode>()
        for (number in 1..total) {
            if (number in existingNumbers) continue
            val episodeUrl = "$mainUrl/eps/$slug-episode-$number/"
            val text = runCatching { app.get(episodeUrl, headers = headers, referer = baseUrl).text }.getOrNull().orEmpty()
            if (!text.isValidEpisodePage(number, slug)) continue
            discovered.add(newEpisode(episodeUrl.toPlaybackData(baseUrl)) {
                name = "Episode $number"
                episode = number
            })
        }
        return discovered
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


    private fun String.stripDetailMetadata(): String {
        var output = this.cleanText()
        val hardMarkers = listOf(
            " By:", " Posted on:", " Views:", " Tagline:", " Genre:", " Quality:",
            " Year:", " Duration:", " Country:", " Release:", " Last Air Date:",
            " Number Of Episode:", " Network:", " Cast:", " Director:", " Stars:"
        )
        hardMarkers.forEach { marker ->
            val index = output.indexOf(marker, ignoreCase = true)
            if (index > 0) output = output.substring(0, index).trim()
        }
        return output.replace(Regex("\\s+"), " ").trim()
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

    private fun String.isLikelyPlayerUrl(pageUrl: String): Boolean {
        val lower = lowercase()
        if (isEvidenceMediaUrl()) return true
        if (isMediaAssetUrl()) return false
        if (!startsWith("http", true) && !startsWith("/")) return false
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) return false
        if (lower.contains("/genre/") || lower.contains("/country/") || lower.contains("/blog-category/") ||
            lower.contains("/tag/") || lower.contains("/cast/") || lower.contains("/actor/") || lower.contains("/director/") ||
            lower.contains("/creator/") || lower.contains("/quality/") || lower.contains("/year/") || lower.contains("/page/")) return false
        val allowedExternal = listOf("justplay.cam", "nzn3.org", "vidmoly.biz", "strcloud.in")
        if (allowedExternal.any { lower.contains(it) }) return true
        return lower.contains("/eps/") || lower.contains("/player") || lower.contains("/embed") || lower.contains("/watch") || lower.contains("?player")
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

    private fun Document.extractSeriesUrl(baseUrl: String): String? {
        return selectFirst("a.gmr-all-serie[href], a[href*='/tv/']")
            ?.attr("href")
            ?.absoluteUrl(baseUrl)
            ?.takeIf { it.contains("/tv/", true) }
    }

    private fun Document.extractTotalEpisodes(): Int? {
        val text = listOfNotNull(
            selectFirst("meta[property=og:description]")?.attr("content"),
            selectFirst("meta[name=description]")?.attr("content"),
            body()?.text()
        ).joinToString(" ")
        return Regex("(?i)(?:total\\s*)?(\\d{1,4})\\s*episode").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }


    private fun String.isValidEpisodePage(number: Int, slug: String): Boolean {
        if (isBlank()) return false
        val lower = lowercase()
        if (lower.contains("error 404") || lower.contains("page not found") || lower.contains("nothing found")) return false
        val hasEpisodeIdentity = lower.contains("$slug-episode-$number") ||
            lower.contains("${slug.replace('-', ' ')} episode $number") ||
            lower.contains("episode $number")
        val hasPlayerEvidence = lower.contains("?player=2") || lower.contains("?player=3") ||
            lower.contains("server 1") || lower.contains("server 2") || lower.contains("server 3") ||
            lower.contains("vidmoly.biz") || lower.contains("strcloud.in") || lower.contains("justplay.cam") ||
            lower.contains("<iframe")
        return hasEpisodeIdentity && hasPlayerEvidence
    }

    private fun String.isValidPlot(): Boolean {
        if (isBlank() || length < 40) return false
        val lower = lowercase()
        val seo = listOf(
            "subtitle indonesia", "streaming drakor", "download video", "drakor-id",
            "nonton ", "full episode", "gambar lebih jernih", "server 2", "server 3"
        )
        return seo.none { lower.contains(it) }
    }

    private fun String.removeEpisodeSuffix(): String {
        return replace(Regex("(?i)\\s+S\\d+\\s*Eps?\\d+.*$"), "")
            .replace(Regex("(?i)\\s+Episode\\s*\\d+.*$"), "")
            .cleanTitle()
    }

    private fun String.isEvidenceMediaUrl(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") ||
            lower.contains(".mkv") || lower.contains("videoplayback") || lower.contains("/get_video?")
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
