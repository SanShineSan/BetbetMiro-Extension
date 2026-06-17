package com.sad25kag

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DramaIndo : MainAPI() {
    override var mainUrl = "https://dramaindo.my"
    override var name = "DramaIndo"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Drama Terbaru",
        "movie" to "Movie Korea",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildArchiveUrl(request.data, page), referer = mainUrl).document
        val cards = document.parseCards()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = cards,
                isHorizontalImages = false,
            ),
            hasNext = document.hasNextPage(page),
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cleanQuery = query.trim().replace(Regex("\\s+"), "+")
        val url = if (page <= 1) "$mainUrl/?s=$cleanQuery" else "$mainUrl/page/$page/?s=$cleanQuery"

        return app.get(url, referer = mainUrl)
            .document
            .parseCards()
            .toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = mainUrl).document
        val pageTitle = document.selectFirst("h1.entry-title, h1[itemprop=name], h1")
            ?.text()
            ?.cleanTitle()
            ?: throw ErrorLoadingException("DramaIndo: title tidak ditemukan")
        val poster = document.posterUrl()
        val plot = document.plotText()
        val genres = document.genreList()
        val playableLinks = document.extractPlayableLinks()
        val seriesTitle = document.infoValue("Title")
            ?: document.findSeriesTitle(url)
            ?: pageTitle.removeEpisodeSuffix()
        val seriesUrl = document.findSeriesUrl(url, seriesTitle)

        val isEpisodePost = pageTitle.hasEpisodeNumber() || (playableLinks.isNotEmpty() && seriesTitle != pageTitle)
        if (isEpisodePost && seriesTitle.isNotBlank()) {
            val seeds = buildList {
                add(EpisodeSeed(pageTitle, url, poster, pageTitle.episodeNumber()))
                if (!seriesUrl.isNullOrBlank() && seriesUrl != url) {
                    runCatching {
                        addAll(app.get(seriesUrl, referer = url).document.parseEpisodeSeeds(seriesTitle))
                    }
                }
                addAll(document.parseEpisodeSeeds(seriesTitle))
            }

            val episodes = seeds
                .filter { it.url.isNotBlank() }
                .distinctBy { it.url }
                .sortedWith(compareBy<EpisodeSeed> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
                .map { it.toEpisode(seriesTitle) }

            return newTvSeriesLoadResponse(
                seriesTitle.cleanTitle(),
                seriesUrl ?: url,
                TvType.AsianDrama,
                episodes,
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        }

        val listingEpisodes = document.parseEpisodeSeeds(pageTitle)
        if (listingEpisodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(
                pageTitle,
                url,
                TvType.AsianDrama,
                listingEpisodes
                    .distinctBy { it.url }
                    .sortedWith(compareBy<EpisodeSeed> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
                    .map { it.toEpisode(pageTitle) },
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        }

        return newMovieLoadResponse(pageTitle, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val links = if (data.isDramaIndoStreamHost()) {
            listOf(SourceLink(data, data.streamSourceName(), null))
        } else {
            runCatching { app.get(data, referer = mainUrl).document.extractPlayableLinks() }.getOrDefault(emptyList())
        }

        var callbackCount = 0
        for (source in links) {
            if (source.url.isDramaIndoStreamHost()) {
                DramaIndoStreamResolver.resolve(source.name, source.url, data) { link ->
                    callbackCount++
                    callback(link)
                }
            } else {
                loadExtractor(source.url, data, subtitleCallback) { link ->
                    callbackCount++
                    callback(link)
                }
            }
        }

        return callbackCount > 0
    }

    private fun buildArchiveUrl(path: String, page: Int): String {
        return if (path.isBlank()) {
            if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        } else {
            val cleanPath = path.trim('/').trim()
            if (page <= 1) "$mainUrl/$cleanPath/" else "$mainUrl/$cleanPath/page/$page/"
        }
    }

    private fun Document.parseCards(): List<SearchResponse> {
        val cards = select("article.item, article.item-infinite, article[class*=item]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        if (cards.isNotEmpty()) return cards

        return select("article, div[class*=post], div[class*=movie], div[class*=item]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(".entry-title a[href], h1 a[href], h2 a[href], h3 a[href]")
            ?: select("a[href]").firstOrNull { link ->
                val text = link.text().cleanTitle()
                text.isNotBlank() && !text.isGenericNavigationText()
            }
            ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null

        val title = anchor.text().ifBlank { anchor.attr("title") }.cleanTitle()
        if (title.isBlank() || title.isGenericNavigationText()) return null

        val poster = selectFirst("img")?.imageUrl()
        val tvType = if (title.isMovieTitle()) TvType.Movie else TvType.AsianDrama

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = poster
        }
    }

    private fun Document.parseEpisodeSeeds(seriesTitle: String): List<EpisodeSeed> {
        val cards = select("article.item, article.item-infinite, article[class*=item]")
            .mapNotNull { it.toEpisodeSeed(seriesTitle) }
            .distinctBy { it.url }
        if (cards.isNotEmpty()) return cards

        return select(".entry-title a[href], h1 a[href], h2 a[href], h3 a[href]")
            .mapNotNull { anchor ->
                val title = anchor.text().ifBlank { anchor.attr("title") }.cleanTitle()
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                if (!href.startsWith(mainUrl) || !title.hasEpisodeNumber()) return@mapNotNull null
                EpisodeSeed(title, href, anchor.closest("article")?.selectFirst("img")?.imageUrl(), title.episodeNumber())
            }
            .distinctBy { it.url }
    }

    private fun Element.toEpisodeSeed(seriesTitle: String): EpisodeSeed? {
        val anchor = selectFirst(".entry-title a[href], h1 a[href], h2 a[href], h3 a[href]")
            ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = anchor.text().ifBlank { anchor.attr("title") }.cleanTitle()
        if (!href.startsWith(mainUrl) || !title.hasEpisodeNumber()) return null

        return EpisodeSeed(
            title = title,
            url = href,
            poster = selectFirst("img")?.imageUrl(),
            episode = title.episodeNumber(),
        )
    }

    private fun Document.extractPlayableLinks(): List<SourceLink> {
        val sources = linkedSetOf<SourceLink>()

        select(".entry-content iframe[src], iframe[src]").forEachIndexed { index, iframe ->
            val href = fixUrlNull(iframe.attr("src")) ?: return@forEachIndexed
            if (href.isDramaIndoStreamHost()) {
                sources.add(
                    SourceLink(
                        url = href,
                        name = href.streamSourceName().ifBlank { "Streaming ${index + 1}" },
                        quality = null,
                    )
                )
            }
        }

        select(".entry-content a[href], article a[href]").forEach { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
            if (!href.isSupportedHost()) return@forEach

            val parentText = anchor.parent()?.text()?.cleanText().orEmpty()
            sources.add(
                SourceLink(
                    url = href,
                    name = anchor.text().cleanText().ifBlank { href.hostName() },
                    quality = parentText.qualityFromText() ?: anchor.text().qualityFromText(),
                )
            )
        }

        return sources.distinctBy { it.url }
    }

    private fun Document.infoValue(label: String): String? {
        val regex = Regex("(?i)^${Regex.escape(label)}\\s*:")
        return select(".entry-content li, .entry-content p, li, p")
            .firstOrNull { regex.containsMatchIn(it.text().cleanText()) }
            ?.text()
            ?.replace(regex, "")
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
    }

    private fun Document.genreList(): List<String> {
        return infoValue("Genres")
            ?.split(",")
            ?.map { it.cleanTitle() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    private fun Document.posterUrl(): String? {
        return selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
            ?: selectFirst(".entry-content img[src*='wp-content'], article img[src*='wp-content'], .post img[src*='wp-content'], img")?.imageUrl()
    }

    private fun Document.plotText(): String? {
        select(".entry-content p").forEach { paragraph ->
            val text = paragraph.text().cleanText()
            if (text.contains("Sinopsis", ignoreCase = true)) {
                val sinopsis = text
                    .replace(Regex("(?i)^\\s*Sinopsis\\s*"), "")
                    .replace(Regex("(?i)^\\s*Sinopsis\\s*[:：-]?\\s*"), "")
                    .cleanText()
                if (sinopsis.length > 40 && !sinopsis.isDirtyPlot()) return sinopsis
            }
        }

        val clone = selectFirst(".entry-content.entry-content-single, .entry-content")?.clone() ?: return null
        clone.select("iframe, script, style, ul, table, h1, h2, center, .sharedaddy, .fb-comments, .gmr-rating").forEach { it.remove() }
        val text = clone.text()
            .substringAfter("Sinopsis", "")
            .substringBefore("Details")
            .substringBefore("Download")
            .cleanText()
        return text.takeIf { it.length > 40 && !it.isDirtyPlot() }
    }

    private fun Document.findSeriesTitle(currentUrl: String): String? {
        return select(".gmr-movie-on a[href], article.hentry a[href], .entry-content a[href]")
            .firstOrNull { link ->
                val href = fixUrlNull(link.attr("href")) ?: return@firstOrNull false
                val text = link.text().cleanTitle()
                href.startsWith(mainUrl) &&
                    href != currentUrl &&
                    !href.isEpisodePermalink() &&
                    text.isNotBlank() &&
                    !text.hasEpisodeNumber() &&
                    !text.isGenericNavigationText()
            }
            ?.text()
            ?.cleanTitle()
    }

    private fun Document.findSeriesUrl(currentUrl: String, seriesTitle: String): String? {
        return select(".gmr-movie-on a[href], .entry-content a[href], a[href]")
            .mapNotNull { link ->
                val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                val text = link.text().cleanTitle()
                if (!href.startsWith(mainUrl) || href == currentUrl || href.isEpisodePermalink()) return@mapNotNull null
                if (text.equals(seriesTitle, ignoreCase = true)) href else null
            }
            .firstOrNull()
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        val nextPage = page + 1
        return select("a[href]").any { link ->
            val href = link.attr("href")
            val text = link.text().cleanText()
            href.contains("/page/$nextPage/") || text == nextPage.toString()
        }
    }

    private fun Element.imageUrl(): String? {
        val image = attr("data-src").ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("src") }
            .ifBlank { attr("data-srcset").substringBefore(" ") }
            .ifBlank { attr("srcset").substringBefore(" ") }
        return fixUrlNull(image)
    }

    private fun EpisodeSeed.toEpisode(seriesTitle: String): Episode {
        val displayName = title.cleanEpisodeDisplayName(seriesTitle, episode)
        return newEpisode(url) {
            this.name = displayName
            this.episode = episode
            this.posterUrl = poster
        }
    }

    private fun String.cleanText(): String {
        return replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return cleanText()
            .replace(Regex("(?i)^Permalink\\s+to:\\s*"), "")
            .replace(Regex("(?i)^Watch\\s+"), "")
            .replace(Regex("(?i)\\s+Sub\\s+Indo\\s*$"), "")
            .cleanText()
    }

    private fun String.cleanEpisodeDisplayName(seriesTitle: String, episode: Int?): String {
        val cleanSeries = seriesTitle.cleanTitle()
        val clean = cleanTitle()
            .replace(Regex("(?i)^${Regex.escape(cleanSeries)}\\s*"), "")
            .cleanTitle()
        return when {
            episode != null && clean.equals("Episode $episode", ignoreCase = true) -> "Episode $episode"
            episode != null && clean.isBlank() -> "Episode $episode"
            else -> clean.ifBlank { episode?.let { "Episode $it" } ?: this.cleanTitle() }
        }
    }

    private fun String.removeEpisodeSuffix(): String {
        return replace(Regex("(?i)\\s+Episode\\s+\\d+(?:\\s*-\\s*\\d+)?(?:\\s*\\[END])?.*$"), "")
            .cleanTitle()
    }

    private fun String.hasEpisodeNumber(): Boolean {
        return Regex("(?i)\\bEpisode\\s+\\d+").containsMatchIn(this)
    }

    private fun String.episodeNumber(): Int? {
        return Regex("(?i)\\bEpisode\\s+(\\d+)").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.isMovieTitle(): Boolean {
        return Regex("(?i)\\b(Movie|Film Korea|Film)\\b").containsMatchIn(this)
    }

    private fun String.isEpisodePermalink(): Boolean {
        return Regex("/20\\d{2}/").containsMatchIn(this) || Regex("(?i)episode-?\\d+").containsMatchIn(this)
    }

    private fun String.isDirtyPlot(): Boolean {
        val lower = lowercase()
        return lower.startsWith("sharer tweet") || lower.contains("no votes nonton drama")
    }

    private fun String.isGenericNavigationText(): Boolean {
        val text = cleanText().lowercase()
        return text in setOf(
            "home",
            "watch",
            "watch movie",
            "more movie",
            "list drama",
            "movie",
            "sharer",
            "tweet",
            "view more",
            "no more posts available",
            "proudly powered by wordpress",
        )
    }

    private fun String.isSupportedHost(): Boolean {
        val lower = lowercase()
        if (!lower.startsWith("http") || lower.startsWith(mainUrl.lowercase())) return false
        return supportedHosts.any { lower.contains(it) }
    }

    private fun String.isDramaIndoStreamHost(): Boolean {
        val lower = lowercase()
        return streamHosts.any { lower.contains(it) }
    }

    private fun String.streamSourceName(): String {
        val lower = lowercase()
        return when {
            lower.contains("drakorkita.stream") -> "Drakorkita"
            lower.contains("nuna.upns.pro") -> "NunaUpns"
            else -> hostName()
        }
    }

    private fun String.qualityFromText(): Int? {
        return Regex("(?i)(2160|1080|720|480|360)p").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.hostName(): String {
        return substringAfter("://")
            .substringBefore("/")
            .removePrefix("www.")
    }

    private data class EpisodeSeed(
        val title: String,
        val url: String,
        val poster: String?,
        val episode: Int?,
    )

    private data class SourceLink(
        val url: String,
        val name: String,
        val quality: Int?,
    )

    private companion object {
        val streamHosts = listOf(
            "drakorkita.stream",
            "nuna.upns.pro",
        )

        val supportedHosts = streamHosts + listOf(
            "krakenfiles.com",
            "gofile.io",
            "megaup.net",
            "bysetayico.com",
            "filemoon",
            "filekeeper.net",
            "send.now",
            "buzzheavier.com",
            "upfion.com",
            "mirrored.to",
            "1024tera.com",
        )
    }
}

class ErrorLoadingException(message: String) : RuntimeException(message)
