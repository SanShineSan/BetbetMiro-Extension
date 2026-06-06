package com.sad25kag.duniafilm21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class DuniaFilm21Provider : MainAPI() {
    companion object {
        const val DEFAULT_MAIN_URL = "http://207.180.246.102"
    }

    override var mainUrl = DEFAULT_MAIN_URL
    override var name = "DuniaFilm21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Update Terbaru",
        "$mainUrl/best-rating/" to "Best Rating",
        "$mainUrl/Genre/film-action-terbaru/" to "Action",
        "$mainUrl/Genre/adventure/" to "Adventure",
        "$mainUrl/Genre/comedy/" to "Comedy",
        "$mainUrl/Genre/crime/" to "Crime",
        "$mainUrl/Genre/drama/" to "Drama",
        "$mainUrl/Genre/fantasy/" to "Fantasy",
        "$mainUrl/Genre/mystery/" to "Mystery",
        "$mainUrl/Genre/romance/" to "Romance",
        "$mainUrl/Genre/science-fiction/" to "Science Fiction",
        "$mainUrl/Genre/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.toPageUrl(page)
        val document = DuniaFilm21Network.getDocument(url, mainUrl)
        val defaultType = typeFromRequest(request.name)
        val items = DuniaFilm21Parser.parseHomeItems(this, document, url, defaultType)
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = DuniaFilm21Parser.hasNextPage(document, page)
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = query.urlEncodeDf21()
        val document = DuniaFilm21Network.getDocument("$mainUrl/?s=$encoded", mainUrl)
        return DuniaFilm21Parser.parseHomeItems(this, document, "$mainUrl/?s=$encoded", TvType.Movie)
            .filter { it.name.contains(query, ignoreCase = true) || query.length <= 4 }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = DuniaFilm21Network.getDocument(url, mainUrl)
        val title = DuniaFilm21Parser.parseTitle(document).ifBlank { return null }
        val poster = DuniaFilm21Parser.parsePoster(document, url)
        val plot = DuniaFilm21Parser.parsePlot(document)
        val tags = DuniaFilm21Parser.parseTags(document)
        val year = DuniaFilm21Parser.parseYear(document, title)
        val type = DuniaFilm21Parser.inferType(title, url, TvType.Movie)
        val episodes = DuniaFilm21Parser.parseEpisodes(this, document, url)

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, type, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return DuniaFilm21Extractor.load(data, subtitleCallback, callback)
    }

    private fun typeFromRequest(name: String): TvType {
        val lower = name.lowercase()
        return when {
            lower.contains("drama") -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }

    private fun String.toPageUrl(page: Int): String {
        val base = trimEnd('/')
        return if (page <= 1) "$base/" else "$base/page/$page/"
    }
}
