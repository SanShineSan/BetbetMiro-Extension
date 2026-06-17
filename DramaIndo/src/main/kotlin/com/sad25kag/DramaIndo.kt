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
        val home = document.parseCards()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false,
            ),
            hasNext = document.hasNextPage(page),
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cleanQuery = query.trim().replace(Regex("\\s+"), "+")
        val url = if (page <= 1) {
            "$mainUrl/?s=$cleanQuery"
        } else {
            "$mainUrl/page/$page/?s=$cleanQuery"
        }

        return app.get(url, referer = mainUrl)
            .document
            .parseCards()
            .toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = mainUrl).document
        val pageTitle = document.selectFirst("h1.entry-title, h1")?.text()?.cleanText()
            ?: throw ErrorLoadingException("DramaIndo: title tidak ditemukan")
        val poster = document.posterUrl()
        val plot = document.plotText()
        val playableLinks = document.extractPlayableLinks()
        val seriesTitle = document.infoValue("Title")
            ?: document.findSeriesTitle(url)
            ?: pageTitle.removeEpisodeSuffix()
        val seriesUrl = document.findSeriesUrl(url, seriesTitle)
        val genres = document.infoValue("Genres")
            ?.split(",")
            ?.map { it.cleanText() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val isEpisodePost = pageTitle.hasEpisodeNumber() || (playableLinks.isNotEmpty() && seriesTitle != pageTitle)
        if (isEpisodePost && seriesTitle.isNotBlank()) {
            val seeds = buildList {
                add(EpisodeSeed(pageTitle, url, poster, pageTitle.episodeNumber()))
                if (!seriesUrl.isNullOrBlank() && seriesUrl != url) {
                    runCatching {
                        addAll(app.get(seriesUrl, referer = url).document.parseEpisodeSeeds())
                    }
                }
                addAll(document.parseEpisodeSeeds())
            }

            val episodes = seeds
                .filter { it.url.isNotBlank() }
                .distinctBy { it.url }
                .sortedWith(compareBy<EpisodeSeed> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
                .map { seed -> seed.toEpisode() }

            return newTvSeriesLoadResponse(
                seriesTitle,
                seriesUrl ?: url,
                TvType.AsianDrama,
                episodes,
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        }

        val listingEpisodes = document.parseEpisodeSeeds()
        if (listingEpisodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(
                pageTitle,
                url,
                TvType.AsianDrama,
                listingEpisodes
                    .distinctBy { it.url }
                    .sortedWith(compareBy<EpisodeSeed> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
                    .map { it.toEpisode() },
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
        val document = app.get(data, referer = mainUrl).document
        val links = document.extractPlayableLinks()
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
        val primary = select("article.item, article.item-infinite, article[class*=item]").mapNotNull { it.toSearchResult() }
        if (primary.isNotEmpty()) return primary.distinctBy { it.url }

        return select("article, div[class*=post], div[class*=movie], div[class*=item]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2.entry-title a[href], h2 a[href], h3 a[href], .entry-title a[href], a[title][href]")
            ?: select("a[href]").firstOrNull { link ->
                val text = link.text().cleanText()
                text.isNotBlank() && !text.isGenericNavigationText()
            }
            ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null

        val title = anchor.attr("title").ifBlank { anchor.text() }.cleanTitle()
        if (title.isBlank() || title.isGenericNavigationText()) return null

        val poster = selectFirst("img")?.imageUrl()
        val tvType = if (title.isMovieTitle()) TvType.Movie else TvType.AsianDrama

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = poster
        }
    }

    private fun Document.parseEpisodeSeeds(): List<EpisodeSeed> {
        val primary = select("article.item, article.item-infinite, article[class*=item]").mapNotNull { it.toEpisodeSeed() }
        if (primary.isNotEmpty()) return primary.distinctBy { it.url }

        return select("h2.entry-title a[href], h2 a[href], h3 a[href], .entry-title a[href]")
            .mapNotNull { anchor ->
                val title = anchor.text().cleanTitle()
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                if (!href.startsWith(mainUrl) || !title.hasEpisodeNumber()) return@mapNotNull null
                EpisodeSeed(title, href, anchor.parent()?.selectFirst("img")?.imageUrl(), title.episodeNumber())
            }
            .distinctBy { it.url }
    }

    private fun Element.toEpisodeSeed(): EpisodeSeed? {
        val anchor = selectFirst("h2.entry-title a[href], h2 a[href], h3 a[href], .entry-title a[href], a[title][href]")
            ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = anchor.attr("title").ifBlank { anchor.text() }.cleanTitle()
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

        select("div.entry-content iframe[src], iframe[src]").forEachIndexed { index, iframe ->
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

        select("a[href]").forEach { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
            if (!href.isSupportedHost()) return@forEach

            sources.add(
                SourceLink(
                    url = href,
                    name = anchor.text().cleanText().ifBlank { href.hostName() },
                    quality = anchor.parent()?.text()?.qualityFromText() ?: anchor.text().qualityFromText(),
                )
            )
        }

        return sources.distinctBy { it.url }
    }

    private fun Document.infoValue(label: String): String? {
        val prefix = "$label:"
        return select("li, p")
            .firstOrNull { it.text().cleanText().startsWith(prefix, ignoreCase = true) }
            ?.text()
            ?.substringAfter(":")
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
    }

    private fun Document.posterUrl(): String? {
        return selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
            ?: selectFirst("article img[src*='wp-content'], .entry-content img[src*='wp-content'], .post img[src*='wp-content'], img")?.imageUrl()
    }

    private fun Document.plotText(): String? {
        val contentText = selectFirst("div.entry-content.entry-content-single, .entry-content, article")
            ?.text()
            ?.cleanText()
            .orEmpty()

        return contentText
            .substringAfter("Sinopsis", contentText)
            .substringBefore("Details", contentText)
            .substringBefore("Download", contentText)
            .cleanText()
            .takeIf { it.length > 40 }
            ?: select("p").firstOrNull { it.text().cleanText().length > 80 }?.text()?.cleanText()
    }

    private fun Document.findSeriesTitle(currentUrl: String): String? {
        return select("article.hentry a[href], div.entry-content a[href], a[href]")
            .firstOrNull { link ->
                val href = fixUrlNull(link.attr("href")) ?: return@firstOrNull false
                val text = link.text().cleanText()
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
        return select("a[href]")
            .mapNotNull { link ->
                val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                val text = link.text().cleanText()
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

    private fun EpisodeSeed.toEpisode(): Episode {
        return newEpisode(url) {
            this.name = title
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
            .removePrefix("Watch ")
            .cleanText()
    }

    private fun String.removeEpisodeSuffix(): String {
        return replace(Regex("(?i)\\s+Episode\\s+\\d+(?:\\s*-\\s*\\d+)?(?:\\s*\\[END])?.*$"), "")
            .cleanText()
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
