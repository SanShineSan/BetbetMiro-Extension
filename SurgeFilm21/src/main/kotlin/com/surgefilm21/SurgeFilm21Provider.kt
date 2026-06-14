package com.surgefilm21

import com.lagradost.cloudstream3.*

class SurgeFilm21Provider : MainAPI() {
    companion object {
        const val DEFAULT_MAIN_URL = "https://surgafilm21.website"
    }

    override var mainUrl = DEFAULT_MAIN_URL
    override var name = "SurgeFilm21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.AnimeMovie, TvType.Cartoon, TvType.NSFW)

    private val sections = listOf(
        SurgeFilm21Section("/latest/", "Update Terbaru", "latest"),
        SurgeFilm21Section("/series/", "Series Terbaru", "series"),
        SurgeFilm21Section("/series/ongoing/", "Series Ongoing", "series"),
        SurgeFilm21Section("/series/completed/", "Series Completed", "series"),
        SurgeFilm21Section("/genre/romance/", "Romance", "romance"),
        SurgeFilm21Section("/genre/comedy/", "Comedy", "comedy"),
        SurgeFilm21Section("/genre/drama/", "Drama", "drama"),
        SurgeFilm21Section("/genre/action/", "Action", "action"),
        SurgeFilm21Section("/genre/animation/", "Animation", "animation"),
        SurgeFilm21Section("/genre/science-fiction/", "Sci-Fi", "science fiction"),
        SurgeFilm21Section("/genre/horror/", "Horror", "horror"),
        SurgeFilm21Section("/genre/mystery/", "Mystery", "mystery"),
        SurgeFilm21Section("/genre/war/", "War & History", "war"),
        SurgeFilm21Section("/country/thailand/", "Thailand", "thailand"),
        SurgeFilm21Section("/country/china/", "China", "china"),
        SurgeFilm21Section("/country/philippines/", "Philippines", "philippines")
    )

    override val mainPage = mainPageOf(*sections.map { it.data to it.name }.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val section = sections.firstOrNull { it.data == request.data }
        val defaultType = typeFromSection(section?.name ?: request.name)

        val list = section?.let {
            listingPage(it.data, page, defaultType)
                .ifEmpty { searchPage(it.fallbackQuery, page, defaultType) }
        } ?: listingPage("/latest/", page, defaultType)

        return newHomePageResponse(
            HomePageList(request.name, list.distinctBy { it.url }, isHorizontalImages = true),
            hasNext = list.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            val pageItems = searchPage(query, page, TvType.Movie).filterNot { item -> results.any { it.url == item.url } }
            if (pageItems.isEmpty()) break
            results.addAll(pageItems)
        }
        return results
    }

    private suspend fun listingPage(path: String, page: Int, defaultType: TvType): List<SearchResponse> {
        val base = path.absUrlSf21(mainUrl) ?: return emptyList()
        val pageUrl = if (page <= 1) {
            base
        } else {
            base + if (base.contains("?")) "&ajax=load_more&page=$page" else "?ajax=load_more&page=$page"
        }

        return runCatching {
            val document = SurgeFilm21Sepeda.getDocument(pageUrl, base)
            SurgeFilm21Parser.parseHomeItems(this, document, base, defaultType)
                .distinctBy { it.url }
        }.getOrNull().orEmpty()
    }

    private suspend fun searchPage(query: String, page: Int, defaultType: TvType): List<SearchResponse> {
        val encoded = query.urlEncodeSf21()
        val candidates = listOf(
            "$mainUrl/?s=$encoded&page=$page",
            "$mainUrl/search?q=$encoded&page=$page",
            "$mainUrl/page/$page/?s=$encoded",
            "$mainUrl/search/$encoded?page=$page"
        )

        for (url in candidates) {
            val items = runCatching {
                val document = SurgeFilm21Sepeda.getDocument(url, mainUrl)
                SurgeFilm21Parser.parseHomeItems(this, document, url, defaultType)
                    .filter { it.name.contains(query, true) || query.length <= 4 }
                    .distinctBy { it.url }
            }.getOrNull().orEmpty()
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = SurgeFilm21Sepeda.getDocument(url, mainUrl)
        val title = SurgeFilm21Parser.parseTitle(document).ifBlank { return null }
        val poster = SurgeFilm21Parser.parsePoster(document, url)
        val plot = SurgeFilm21Parser.parsePlot(document)
        val tags = SurgeFilm21Parser.parseTags(document)
        val year = SurgeFilm21Parser.parseYear(document, title)
        val episodes = SurgeFilm21Parser.parseEpisodes(this, document, url)
        val inferredType = when {
            episodes.isNotEmpty() || url.contains("/series/", true) -> SurgeFilm21Parser.inferType(title, url, TvType.TvSeries)
            else -> SurgeFilm21Parser.inferType(title, url, TvType.Movie)
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, inferredType, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, inferredType, url) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit): Boolean {
        return SurgeFilm21Extractor.load(data, subtitleCallback, callback)
    }

    private fun typeFromSection(name: String): TvType {
        val lower = name.lowercase()
        return when {
            lower.contains("series") || lower.contains("eps") -> TvType.TvSeries
            lower.contains("animation") -> TvType.Cartoon
            lower.contains("thailand") || lower.contains("china") || lower.contains("philippines") || lower.contains("drama") -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }
}
