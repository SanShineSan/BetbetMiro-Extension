package com.astronime

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class AstronimeProvider : MainAPI() {
    override var mainUrl = "https://astronime.id"
    override var name = "Astronime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "/" to "Latest Episode",
        "/" to "Some Movie Choices",
        "/" to "New Complete Anime",
        "/" to "Most Viewed",
        "/" to "Currently Airing",
        "/terbaru/" to "Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(
                HomePageList(request.name, emptyList(), request.horizontalImages),
                hasNext = false
            )
        }

        val document = app.get(fixUrl(request.data), referer = mainUrl, timeout = 30).document
        document.setBaseUri(fixUrl(request.data))
        val cards = document.parseHomeSection(request.name)

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = cards,
                isHorizontalImages = request.horizontalImages
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery", referer = mainUrl, timeout = 30).document
        document.setBaseUri("$mainUrl/?s=$encodedQuery")
        return document.parseSearchResults(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = mainUrl, timeout = 30).document
        document.setBaseUri(url)

        val hasPlayer = document.hasPlayerOptions()
        if (hasPlayer && !document.isMovieLikePage()) {
            val parentUrl = document.parentAnimeUrl()
            if (!parentUrl.isNullOrBlank() && parentUrl != url) {
                val parentDocument = app.get(parentUrl, referer = url, timeout = 30).document
                parentDocument.setBaseUri(parentUrl)
                return parentDocument.toAnimeOrMovieLoadResponse(parentUrl)
            }
        }

        return document.toAnimeOrMovieLoadResponse(url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playableUrl = resolvePlayableUrl(data)
        val document = app.get(playableUrl, referer = mainUrl, timeout = 30).document
        document.setBaseUri(playableUrl)

        val options = document.playerOptions()
        val directSources = document.extractPlayerSources(playableUrl)
        var emitted = false

        if (options.isNotEmpty()) {
            for (option in options) {
                val iframeUrls = requestPlayerIframe(option, playableUrl)
                for (iframeUrl in iframeUrls) {
                    if (iframeUrl.resolveSource(option.label, playableUrl, subtitleCallback, callback)) {
                        emitted = true
                    }
                }
            }
        }

        for (source in directSources) {
            if (source.resolveSource("Direct", playableUrl, subtitleCallback, callback)) {
                emitted = true
            }
        }

        return emitted
    }

    private suspend fun resolvePlayableUrl(data: String): String {
        val document = app.get(data, referer = mainUrl, timeout = 30).document
        document.setBaseUri(data)
        if (document.hasPlayerOptions()) return data
        return document.firstEpisodeUrl() ?: data
    }

    private suspend fun Document.toAnimeOrMovieLoadResponse(url: String): LoadResponse {
        val title = animeTitle()
        val poster = animePoster()
        val plot = animePlotWithDetails()
        val tags = animeGenres()
        val year = releaseYear()
        val status = animeStatus()
        val type = animeTvType()
        val episodes = episodeList()

        if (type == TvType.AnimeMovie) {
            val playUrl = episodes.firstOrNull()?.data ?: firstEpisodeUrl() ?: url
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, playUrl) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private suspend fun requestPlayerIframe(option: PlayerOption, refererUrl: String): List<String> {
        return runCatching {
            val responseText = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "player_ajax",
                    "post" to option.post,
                    "nume" to option.nume,
                    "type" to option.type
                ),
                referer = refererUrl,
                headers = mapOf(
                    "Origin" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                timeout = 30
            ).text
            Jsoup.parse(responseText, mainUrl).extractPlayerSources(refererUrl)
        }.getOrDefault(emptyList())
    }

    private suspend fun String.resolveSource(
        label: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sourceUrl = this
        return when {
            sourceUrl.contains(".m3u8", ignoreCase = true) -> {
                callback.emitDirect(label, sourceUrl, ExtractorLinkType.M3U8, refererUrl)
                true
            }
            sourceUrl.contains(".mp4", ignoreCase = true) || sourceUrl.contains("/stream-vid/", ignoreCase = true) -> {
                callback.emitDirect(label, sourceUrl, ExtractorLinkType.VIDEO, refererUrl)
                true
            }
            sourceUrl.contains("turbovidhls.com", ignoreCase = true) || sourceUrl.contains("turboviplay.com", ignoreCase = true) -> {
                resolveTurbovid(label, sourceUrl, refererUrl, callback)
            }
            sourceUrl.contains("abyssplayer.com", ignoreCase = true) -> {
                val custom = resolveAbyss(label, sourceUrl, callback)
                if (!custom) loadExtractor(sourceUrl, refererUrl, subtitleCallback, callback)
                custom
            }
            else -> {
                loadExtractor(sourceUrl, refererUrl, subtitleCallback, callback)
                false
            }
        }
    }

    private suspend fun resolveTurbovid(
        label: String,
        sourceUrl: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerDocument = app.get(sourceUrl, referer = refererUrl, timeout = 30).document
        playerDocument.setBaseUri(sourceUrl)
        val mediaUrls = playerDocument.extractPlayerSources(sourceUrl)
            .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) }
            .distinct()

        mediaUrls.forEach { mediaUrl ->
            val type = if (mediaUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.emitDirect(label.ifBlank { "Turbovid" }, mediaUrl, type, sourceUrl)
        }
        return mediaUrls.isNotEmpty()
    }

    private suspend fun resolveAbyss(
        label: String,
        sourceUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val headers = mapOf(
                "User-Agent" to userAgent,
                "Origin" to "https://playhydrax.com",
                "Referer" to "https://playhydrax.com/"
            )
            val document = app.get(sourceUrl, headers = headers, timeout = 30).document
            val scriptData = document.select("script").joinToString("\n") { it.data() }
            val encrypted = Regex("""const\s+datas\s*=\s*"([^"]+)""", RegexOption.IGNORE_CASE)
                .find(scriptData)
                ?.groupValues
                ?.getOrNull(1)
                ?: return@runCatching false

            val response = app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = headers,
                requestBody = """{"text":"$encrypted"}""".toRequestBody("application/json".toMediaType()),
                timeout = 30
            ).text

            val result = JSONObject(response).optJSONObject("result") ?: return@runCatching false
            val sources = result.optJSONArray("sources") ?: return@runCatching false
            var emitted = false
            for (i in 0 until sources.length()) {
                val item = sources.optJSONObject(i) ?: continue
                if (!item.optBoolean("status", true)) continue
                val mediaUrl = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                val type = if (mediaUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.emitDirect(label.ifBlank { "Hydrax" }, mediaUrl, type, "https://playhydrax.com/")
                emitted = true
            }
            emitted
        }.getOrDefault(false)
    }

    private suspend fun ((ExtractorLink) -> Unit).emitDirect(
        label: String,
        mediaUrl: String,
        linkType: ExtractorLinkType,
        refererUrl: String
    ) {
        invoke(
            newExtractorLink(
                source = name,
                name = listOf(name, label).filter { it.isNotBlank() }.distinct().joinToString(" - "),
                url = mediaUrl,
                type = linkType
            ) {
                quality = mediaUrl.extractQuality()
                referer = refererUrl
                headers = mapOf("Referer" to refererUrl)
            }
        )
    }

    private fun Document.parseHomeSection(sectionName: String): List<SearchResponse> {
        val candidates = when {
            sectionName.equals("Terbaru", true) -> select("article.anime")
            sectionName.contains("Episode", true) -> findSectionCandidates(sectionName, "article.anime, .animepost, .animposx")
            sectionName.contains("Movie", true) -> findSectionCandidates(sectionName, "article.anime, .animepost, .animposx, a[href]")
            sectionName.contains("Complete", true) -> findSectionCandidates(sectionName, "article.anime, .animepost, .animposx, a[href*='/anime/']")
            sectionName.contains("Most", true) -> findSectionCandidates(sectionName, "article.anime, .animepost, .animposx, .post-show a[href], a[href*='/anime/']")
            sectionName.contains("Airing", true) -> findSectionCandidates(sectionName, "article.anime, .animepost, .animposx, li, a[href]")
            else -> select("article.anime, .animepost, .animposx, a[href]")
        }

        return candidates.mapNotNull { it.toSearchResult(sectionName, requirePoster = true) }
            .distinctBy { it.url }
            .take(30)
    }

    private fun Document.findSectionCandidates(sectionName: String, selector: String): List<Element> {
        val heading = select("h1, h2, h3, h4, .widget-title, .section-title, .title")
            .firstOrNull { it.ownText().cleanText().equals(sectionName, true) || it.text().cleanText().equals(sectionName, true) }
            ?: select("*:matchesOwn((?i)^${Regex.escape(sectionName)}$)").firstOrNull()

        var parent = heading?.parent()
        repeat(5) {
            if (parent != null && parent!!.select("a[href]").size >= 2) {
                val items = parent!!.select(selector)
                if (items.isNotEmpty()) return items
            }
            parent = parent?.parent()
        }
        return select(selector)
    }

    private fun Document.parseSearchResults(query: String): List<SearchResponse> {
        val searchTokens = query.searchTokens()
        if (searchTokens.isEmpty()) return emptyList()

        val candidates = searchResultCandidates()
        return candidates
            .mapNotNull { it.toSearchResult(requirePoster = false) }
            .filter { it.matchesSearchQuery(searchTokens) }
            .distinctBy { it.url }
            .take(30)
    }

    private fun Document.searchResultCandidates(): List<Element> {
        val heading = select("h1, h2, h3, h4, .widget-title, .section-title, .title")
            .firstOrNull {
                val text = it.text().cleanText()
                text.contains("Hasil ditemukan", true) || text.contains("Search", true)
            }
            ?: select("*:matchesOwn((?i)Hasil\\s+ditemukan.*)").firstOrNull()

        var parent = heading?.parent()
        repeat(5) {
            if (parent != null) {
                val items = parent!!.select("article.anime, .animepost, .animposx, a[href*='/anime/'], a[href]")
                if (items.isNotEmpty()) return items
            }
            parent = parent?.parent()
        }

        return select("article.anime, .animepost, .animposx, a[href*='/anime/']")
    }

    private fun SearchResponse.matchesSearchQuery(tokens: List<String>): Boolean {
        val haystack = listOf(name, url.substringAfterLast('/').replace('-', ' '))
            .joinToString(" ")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return tokens.all { token -> haystack.contains(token) }
    }

    private fun String.searchTokens(): List<String> {
        return lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    private fun Element.toSearchResult(sectionName: String? = null, requirePoster: Boolean = false): SearchResponse? {
        val anchor = if (tagName().equals("a", true)) this else selectFirst("a[href]") ?: return null
        val href = anchor.absUrl("href").ifBlank { fixUrlNull(anchor.attr("href")) } ?: return null
        if (!href.isValidContentUrl()) return null

        val poster = imageFrom(this) ?: imageFrom(anchor)
        if (requirePoster && poster.isNullOrBlank()) return null

        val rawTitle = titleFrom(anchor, this)
        val title = rawTitle.toCleanCardTitle(href)
        if (title.isBlank() || title.isNoiseTitle()) return null

        val episode = rawTitle.extractEpisodeNumber() ?: href.extractEpisodeNumber()
        val type = when {
            sectionName?.contains("Movie", true) == true -> TvType.AnimeMovie
            rawTitle.contains("Movie", true) -> TvType.AnimeMovie
            rawTitle.contains("OVA", true) || href.contains("ova", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, type) {
            posterUrl = poster
            addSub(episode)
        }
    }

    private suspend fun Document.toPlayablePostMovie(url: String): LoadResponse {
        val title = episodeParentTitle() ?: animeTitle()
        val poster = episodePoster() ?: animePoster()
        val plot = episodePlot() ?: animePlotWithDetails()
        val tags = animeGenres()
        return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = releaseYear()
        }
    }

    private fun Document.animeTitle(): String {
        return selectFirst(".infoanime.widget_senction h1.entry-title, .infoanime h1.entry-title, h1.entry-title, h1[itemprop=name], meta[property=og:title]")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
            ?.cleanText()
            ?.removePrefixIgnoreCase("Nonton Anime")
            ?.replace("- Astronime", "", ignoreCase = true)
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown Title"
    }

    private fun Document.episodeParentTitle(): String? {
        return selectFirst(".episodeinf h2[itemprop=partOfSeries], .episodeinf .areatitle h2.entry-title, .authorbox a[href*='/anime/']")
            ?.text()
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
    }

    private fun Document.animePoster(): String? {
        val image = selectFirst(".infoanime.widget_senction .thumb img, .infoanime .thumb img, article.anime img[itemprop=image], meta[property=og:image]")
        return when {
            image == null -> null
            image.tagName().equals("meta", true) -> image.attr("content").toImageUrl()
            else -> imageFrom(image)
        }
    }

    private fun Document.episodePoster(): String? {
        val image = selectFirst(".episodeinf .thumb img, .authorbox img, meta[property=og:image]")
        return when {
            image == null -> null
            image.tagName().equals("meta", true) -> image.attr("content").toImageUrl()
            else -> imageFrom(image)
        }
    }

    private fun Document.episodePlot(): String? {
        return selectFirst(".episodeinf .entry-content, .entry-content.entry-content-single, [itemprop=description]")
            ?.text()
            ?.cleanText()
            ?.takeIf { it.length > 20 }
    }

    private fun Document.animePlotWithDetails(): String? {
        val plot = selectFirst(".infoanime.widget_senction .entry-content.entry-content-single, .infoanime .entry-content, [itemprop=description]")
            ?.text()
            ?.cleanText()
            ?.takeIf { it.length > 20 }

        val details = animeDetails().entries.joinToString("\n") { "${it.key}: ${it.value}" }
            .takeIf { it.isNotBlank() }

        return listOfNotNull(plot, details).joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun Document.animeGenres(): List<String> {
        return select(".genre-info a[href*='/genre/'], a[href*='/genre/']")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() && !it.matches(Regex(".*\\d+$")) }
            .distinct()
    }

    private fun Document.releaseYear(): Int? {
        val text = selectFirst(".anime.infoanime .spe")?.text().orEmpty() + " " + selectFirst(".alternati")?.text().orEmpty()
        return text.extractYear()
    }

    private fun Document.animeStatus(): ShowStatus? {
        val text = selectFirst(".alternati")?.text().orEmpty() + " " + selectFirst(".anime.infoanime .spe")?.text().orEmpty()
        return when {
            text.contains("Ongoing", true) || text.contains("Currently", true) || text.contains("Airing", true) -> ShowStatus.Ongoing
            text.contains("Completed", true) || text.contains("Finished", true) || text.contains("Tamat", true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun Document.animeTvType(): TvType {
        val typeText = selectFirst(".alternati .type")?.text()?.cleanText().orEmpty()
        val title = animeTitle()
        return when {
            typeText.contains("Movie", true) || title.contains("Movie", true) -> TvType.AnimeMovie
            typeText.contains("OVA", true) || title.contains("OVA", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun Document.isMovieLikePage(): Boolean {
        return selectFirst(".episodeinf .alternati .type, .alternati .type")?.text()?.contains("Movie", true) == true
    }

    private fun Document.hasPlayerOptions(): Boolean {
        return select(".east_player_option[data-post][data-nume], [id^=player-option][data-post][data-nume]").isNotEmpty()
    }

    private fun Document.playerOptions(): List<PlayerOption> {
        return select(".east_player_option[data-post][data-nume], [id^=player-option][data-post][data-nume]")
            .mapNotNull { element ->
                val post = element.attr("data-post").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val nume = element.attr("data-nume").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val type = element.attr("data-type").takeIf { it.isNotBlank() } ?: "urliframe"
                val label = element.text().cleanText()
                PlayerOption(post, nume, type, label)
            }
            .distinctBy { "${it.post}:${it.nume}:${it.type}" }
            .sortedWith(compareBy<PlayerOption> { it.priority() }.thenBy { it.nume.toIntOrNull() ?: Int.MAX_VALUE })
    }

    private fun PlayerOption.priority(): Int {
        val lower = label.lowercase()
        return when {
            lower.contains("turbo") -> 0
            lower.contains("hydrax") -> 1
            lower.contains("abyss") -> 1
            lower.contains("apollo") -> 2
            else -> 3
        }
    }

    private fun Document.parentAnimeUrl(): String? {
        return selectFirst(".authorbox a[href*='/anime/'], a:contains(Semua Episode)[href*='/anime/'], .breadcrumb a[href*='/anime/']")
            ?.absUrl("href")
            ?.takeIf { it.startsWith(mainUrl) }
    }

    private fun Document.firstEpisodeUrl(): String? {
        return select(".lstepsiode a[href], .listeps a[href], .epsleft a[href], .epsright a[href]")
            .mapNotNull { it.absUrl("href").ifBlank { fixUrlNull(it.attr("href")) } }
            .firstOrNull { it.isValidContentUrl() }
    }

    private fun Document.episodeList(): List<Episode> {
        val items = select(".lstepsiode li, .listeps li").mapNotNull { item ->
            val anchor = item.selectFirst(".epsleft a[href], a[href]") ?: return@mapNotNull null
            val href = anchor.absUrl("href").ifBlank { fixUrlNull(anchor.attr("href")) } ?: return@mapNotNull null
            if (!href.isValidContentUrl()) return@mapNotNull null

            val numberText = item.selectFirst(".eps a")?.text()?.cleanText().orEmpty()
            val title = anchor.text().cleanText().ifBlank { "Episode ${numberText.ifBlank { href.extractEpisodeNumber()?.toString().orEmpty() }}" }
            val episodeNumber = numberText.toIntOrNull() ?: title.extractEpisodeNumber() ?: href.extractEpisodeNumber()

            EpisodeHolder(
                episode = episodeNumber,
                data = newEpisode(href) {
                    name = title
                    episode = episodeNumber
                }
            )
        }.distinctBy { it.data.data }

        return items.sortedWith(compareBy<EpisodeHolder> { it.episode ?: Int.MAX_VALUE }.thenBy { it.data.name ?: "" })
            .map { it.data }
    }

    private fun Document.animeDetails(): Map<String, String> {
        return select(".anime.infoanime .spe span").mapNotNull { span ->
            val key = span.selectFirst("b")?.text()?.cleanText()?.trim(':') ?: return@mapNotNull null
            val value = span.ownText().cleanText().ifBlank {
                span.text().removePrefix(key).cleanText().trim(':')
            }
            if (key.isBlank() || value.isBlank()) null else key to value
        }.toMap()
    }

    private fun Document.extractPlayerSources(refererUrl: String): List<String> {
        val urls = linkedSetOf<String>()

        select("iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-original-src]").forEach { iframe ->
            listOf("src", "data-src", "data-lazy-src", "data-original-src").forEach { attr ->
                iframe.attr(attr).normalizeSourceUrl(refererUrl)?.let(urls::add)
            }
        }

        select("video[src], source[src], a[href]").forEach { element ->
            element.attr("src").normalizeSourceUrl(refererUrl)?.let(urls::add)
            element.attr("href").normalizeSourceUrl(refererUrl)?.let { url ->
                if (url.contains(".m3u8", true) || url.contains(".mp4", true) || url.contains("/stream-vid/", true)) urls.add(url)
            }
        }

        select("input[value], div[data-src], div[data-link], div[data-embed], div[data-hash], div[data-url], button[data-src], button[data-link], button[data-embed]").forEach { element ->
            listOf("value", "data-src", "data-link", "data-embed", "data-hash", "data-url").forEach { attr ->
                element.attr(attr).normalizeSourceUrl(refererUrl)?.let(urls::add)
            }
        }

        val scriptText = select("script").joinToString("\n") { it.data() }
            .replace("\\/", "/")
            .replace("\\u0026", "&")

        Regex("""https?:\\?/\\?/[^'"\s<>]+""")
            .findAll(scriptText)
            .map { it.value.replace("\\/", "/") }
            .forEach { it.normalizeSourceUrl(refererUrl)?.let(urls::add) }

        Regex("""(?:file|src|url|source)\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .findAll(scriptText)
            .map { it.groupValues[1] }
            .forEach { it.normalizeSourceUrl(refererUrl)?.let(urls::add) }

        return urls.filter { it.startsWith("http") }
            .filter { !it.isIgnoredPlayerUrl() }
            .distinct()
    }

    private fun titleFrom(anchor: Element, container: Element): String {
        val candidates = listOf(
            container.selectFirst(".dataver2 .title")?.text(),
            container.selectFirst("img[alt]")?.attr("alt"),
            anchor.attr("alt"),
            anchor.attr("title"),
            anchor.selectFirst("h1, h2, h3, h4, h5, .title, .judul, .entry-title, .post-title, .name")?.text(),
            container.selectFirst("h1, h2, h3, h4, h5, .title, .judul, .entry-title, .post-title, .name")?.text(),
            anchor.text(),
            container.text()
        )

        return candidates.firstOrNull { !it.isNullOrBlank() }
            ?.cleanText()
            ?: ""
    }

    private fun imageFrom(element: Element): String? {
        val image = if (element.tagName().equals("img", true)) element else element.selectFirst("img") ?: return null
        val attrs = listOf("data-lazy-src", "data-src", "data-original", "data-cfsrc", "src")
        attrs.forEach { attr ->
            image.attr(attr).toImageUrl()?.let { return it }
        }
        return image.attr("srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ")?.toImageUrl()
    }

    private fun String.toImageUrl(): String? {
        val raw = trim().replace("&amp;", "&")
        if (raw.isBlank() || raw.startsWith("data:", true) || raw.contains("svg%20", true)) return null
        return fixUrlNull(raw)
    }

    private fun String.normalizeSourceUrl(refererUrl: String): String? {
        val raw = trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript", true) || raw.startsWith("data:", true)) return null

        val fixed = when {
            raw.startsWith("http", true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("www.") -> "https://$raw"
            raw.startsWith("/") -> absoluteFrom(refererUrl, raw)
            raw.contains(".m3u8", true) || raw.contains(".mp4", true) || raw.contains("embed", true) -> fixUrl(raw)
            else -> return null
        }

        if (fixed == refererUrl || fixed == mainUrl) return null
        return fixed
    }

    private fun absoluteFrom(baseUrl: String, path: String): String {
        return runCatching {
            val uri = URI(baseUrl)
            "${uri.scheme}://${uri.host}$path"
        }.getOrDefault(fixUrl(path))
    }

    private fun String.isValidContentUrl(): Boolean {
        val lower = lowercase()
        if (!startsWith(mainUrl)) return false
        if (isIgnoredUrl()) return false
        if (lower == mainUrl || lower == "$mainUrl/") return false
        return lower.contains("/anime/") || lower.contains("episode") || lower.matches(Regex("https://astronime\\.id/[a-z0-9-]+/?$"))
    }

    private fun String.isIgnoredUrl(): Boolean {
        val lower = lowercase()
        return ignoredPathParts.any { lower.contains(it) } || lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".webp")
    }

    private fun String.isIgnoredPlayerUrl(): Boolean {
        val lower = lowercase()
        return ignoredPlayerParts.any { lower.contains(it) }
    }

    private fun String.toCleanCardTitle(href: String): String {
        return cleanText()
            .removePrefixIgnoreCase("Streaming Anime")
            .replace(Regex("(?i)\\s+Episode\\s*\\d+.*$"), "")
            .replace(Regex("(?i)\\s+Ep\\.?\\s*\\d+.*$"), "")
            .replace(Regex("(?i)\\s+Sub Indo.*$"), "")
            .ifBlank { href.substringAfter(mainUrl).trim('/').replace('-', ' ').cleanText() }
    }

    private fun String.cleanText(): String {
        return Jsoup.parse(this).text()
            .replace(Regex("\\s+"), " ")
            .replace("Nonton Anime", "", ignoreCase = true)
            .replace("Subtitle Indonesia", "", ignoreCase = true)
            .trim(' ', '-', '|', ':')
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String {
        return replace(Regex("^${Regex.escape(prefix)}\\s+", RegexOption.IGNORE_CASE), "").trim()
    }

    private fun String.isNoiseTitle(): Boolean {
        val value = cleanText().lowercase()
        return value in noiseTitles || value.matches(Regex("^[0-9]+$")) || value.length < 3
    }

    private fun String.extractEpisodeNumber(): Int? {
        return Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(this.cleanText())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun String.extractYear(): Int? {
        return Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun String.extractQuality(): Int {
        val lower = lowercase()
        return when {
            lower.contains("1080") -> 1080
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            else -> Qualities.Unknown.value
        }
    }

    private data class PlayerOption(
        val post: String,
        val nume: String,
        val type: String,
        val label: String
    )

    private data class EpisodeHolder(
        val episode: Int?,
        val data: Episode
    )

    private val userAgent = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

    private val noiseTitles = setOf(
        "home",
        "anime list",
        "jadwal rilis",
        "link rusak",
        "faq",
        "genre",
        "season",
        "studio",
        "search",
        "next",
        "previous",
        "lihat semua",
        "download batch",
        "batch",
        "trailer",
        "expand",
        "turn off light"
    )

    private val ignoredPathParts = listOf(
        "/genre/",
        "/season/",
        "/studio/",
        "/tag/",
        "/author/",
        "/wp-content/",
        "/privacy",
        "/dmca",
        "/faq",
        "/link-rusak",
        "#respond",
        "facebook.com",
        "twitter.com",
        "telegram",
        "whatsapp",
        "youtube.com",
        "youtu.be"
    )

    private val ignoredPlayerParts = listOf(
        "google",
        "googletagmanager",
        "google-analytics",
        "gstatic",
        "histats",
        "dtscout",
        "dtscdn",
        "mrktmtrcs",
        "pixel.morphify",
        "pagead2",
        "facebook",
        "twitter",
        "youtube",
        "favicon",
        ".css",
        ".js",
        ".svg",
        ".jpg",
        ".png",
        ".webp"
    )
}
