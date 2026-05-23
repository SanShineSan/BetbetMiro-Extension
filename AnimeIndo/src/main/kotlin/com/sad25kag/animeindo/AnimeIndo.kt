package com.sad25kag.animeindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class AnimeIndo : MainAPI() {
    override var mainUrl = "https://anime-indo.lol"
    override var name = "AnimeIndo"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Episode Terbaru",
        "$mainUrl/movie/" to "Movie",
        "$mainUrl/genres/action/" to "Action",
        "$mainUrl/genres/adventure/" to "Adventure",
        "$mainUrl/genres/comedy/" to "Comedy",
        "$mainUrl/genres/demons/" to "Demons",
        "$mainUrl/genres/donghua/" to "Donghua",
        "$mainUrl/genres/drama/" to "Drama",
        "$mainUrl/genres/fantasy/" to "Fantasy",
        "$mainUrl/genres/game/" to "Game",
        "$mainUrl/genres/historical/" to "Historical",
        "$mainUrl/genres/horror/" to "Horror",
        "$mainUrl/genres/isekai/" to "Isekai",
        "$mainUrl/genres/magic/" to "Magic",
        "$mainUrl/genres/martial-arts/" to "Martial Arts",
        "$mainUrl/genres/military/" to "Military",
        "$mainUrl/genres/mystery/" to "Mystery",
        "$mainUrl/genres/psychological/" to "Psychological",
        "$mainUrl/genres/reincarnation/" to "Reincarnation",
        "$mainUrl/genres/romance/" to "Romance",
        "$mainUrl/genres/school/" to "School",
        "$mainUrl/genres/sci-fi/" to "Sci-Fi",
        "$mainUrl/genres/seinen/" to "Seinen",
        "$mainUrl/genres/slice-of-life/" to "Slice of Life",
        "$mainUrl/genres/sports/" to "Sports",
        "$mainUrl/genres/super-power/" to "Super Power",
        "$mainUrl/genres/supernatural/" to "Supernatural",
        "$mainUrl/genres/thriller/" to "Thriller",
        "$mainUrl/genres/vampire/" to "Vampire"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isMovie = request.data.contains("/movie/")
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        
        val document = app.get(url).document

        val home = if (isMovie) {
            document.select("table.otable tr").mapNotNull { table ->
                val link = table.selectFirst("td.vithumb a[href]") ?: return@mapNotNull null
                val href = link.attr("href")
                val poster = link.selectFirst("img")?.let { 
                    it.attr("data-original").ifBlank { it.attr("src") } 
                }?.let { fixUrl(it) }

                val desc = table.selectFirst("td.videsc") ?: return@mapNotNull null
                val title = desc.selectFirst("a[href]")?.text()?.trim() ?: return@mapNotNull null

                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }
        } else {
            document.select("div.menu a, div.animemenu a, div.list-anime-parent a, table.otable tr").mapNotNull { element ->
                if (element.tagName() == "tr") {
                    val link = element.selectFirst("td.vithumb a[href]") ?: return@mapNotNull null
                    val href = link.attr("href")
                    val poster = link.selectFirst("img")?.let { it.attr("data-original").ifBlank { it.attr("src") } }?.let { fixUrl(it) }
                    val title = element.selectFirst("td.videsc a")?.text()?.trim() ?: return@mapNotNull null
                    newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) { this.posterUrl = poster }
                } else {
                    val inner = element.selectFirst("div.list-anime") ?: return@mapNotNull null
                    val href = element.attr("href")
                    val title = inner.selectFirst("p")?.text()?.trim() ?: return@mapNotNull null
                    val poster = inner.selectFirst("img")?.let { img ->
                        img.attr("data-original").ifBlank { img.attr("src") }
                    }?.takeUnless { it.contains("loading") }
                    val epNum = inner.selectFirst("span.eps")?.text()?.trim()?.toIntOrNull()
                    
                    newAnimeSearchResponse(title, fixUrl(episodeToAnimeUrl(href)), TvType.Anime) {
                        this.posterUrl = fixUrl(poster ?: "")
                        addSub(epNum)
                    }
                }
            }.distinctBy { it.url }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun episodeToAnimeUrl(url: String): String {
        val slug = url.trimEnd('/').substringAfterLast("/")
        val animeSlug = Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE).replace(slug, "")
        return "$mainUrl/anime/$animeSlug/"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.menu a[href], div.animemenu a[href], div.list-anime-parent a[href]").mapNotNull { a ->
            val inner = a.selectFirst("div.list-anime") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = inner.selectFirst("p")?.text()?.trim() ?: return@mapNotNull null
            val poster = inner.selectFirst("img")?.let { img ->
                val dataOrg = img.attr("data-original")
                if (dataOrg.isNotBlank()) dataOrg else img.attr("src")
            }?.takeUnless { it.contains("loading") }

            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) { 
                this.posterUrl = fixUrl(poster ?: "") 
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val isEpisode = !url.contains("/anime/")
        val episodeDoc = if (isEpisode) app.get(url).document else null

        val animeUrl = episodeDoc?.selectFirst("div.navi a[href*=/anime/]")?.attr("href")
            ?.let { fixUrl(it) }
            ?: if (url.contains("/anime/")) url
            else episodeToAnimeUrl(url)

        val document = app.get(animeUrl).document

        val title = document.selectFirst("h1.title, h2.title, h1, h2, .entry-title")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*Sub\\s*Indo.*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: throw ErrorLoadingException("Judul tidak ditemukan")

        val poster = document.selectFirst("div.detail img, td.vithumb img, .thumb img")
            ?.let { it.attr("src").ifBlank { it.attr("data-original") } }
            ?.let { fixUrl(it) }

        val description = document.selectFirst("div.detail p, p.des, .entry-content p")?.text()?.trim()
        
        val rawGenres = document.select("div.detail li a, .genredesc a").map { it.text().trim() }.filter { it.isNotBlank() }
        val mappedGenres = rawGenres.map { AnimeIndoTagCategory.getCategoryByTag(it) }.distinct()

        val episodes = document.select("div.ep a[href]").mapNotNull { a ->
            val href = a.attr("href")
            val epText = a.text().trim()
            val ep = epText.toIntOrNull()
                ?: Regex("(\\d+)").find(href.trimEnd('/').substringAfterLast("/"))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(fixUrl(href)) { this.name = "Episode $epText"; this.episode = ep }
        }.sortedBy { it.episode }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), null, true)

        return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            addEpisodes(DubStatus.Subbed, episodes)
            plot = description
            this.tags = mappedGenres
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val serverUrls = mutableListOf<String>()

        document.selectFirst("iframe#tontonin")?.attr("src")?.ifBlank { null }?.let { serverUrls.add(it) }
        document.select("a.server[data-video]").forEach { a ->
            val url = a.attr("data-video").ifBlank { null } ?: return@forEach
            if (!serverUrls.contains(url)) serverUrls.add(url)
        }

        serverUrls.forEach { url ->
            val fullUrl = if (url.startsWith("/")) "$mainUrl$url" else url
            if (fullUrl.contains("btube3.php")) {
                try {
                    val playerDoc = app.get(fullUrl).document
                    val videoSrc = playerDoc.selectFirst("source[src]")?.attr("src") ?: playerDoc.selectFirst("video")?.attr("src")
                    if (!videoSrc.isNullOrBlank()) {
                        callback(newExtractorLink("AnimeIndo", "B-TUBE", videoSrc) {
                            this.quality = Qualities.P1080.value
                            this.referer = "https://www.blogger.com/"
                        })
                    }
                } catch (_: Exception) {}
            } else if (fullUrl.contains("xtwap.top")) {
                try {
                    val html = app.get(fullUrl).text
                    val fileMatch = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(html)
                    val filePath = fileMatch?.groupValues?.getOrNull(1)
                    if (!filePath.isNullOrBlank()) {
                        val videoUrl = if (filePath.startsWith("/")) "https://xtwap.top$filePath" else filePath
                        callback(newExtractorLink("AnimeIndo", "CEPAT", videoUrl) {
                            this.quality = Qualities.P1080.value
                            this.referer = fullUrl
                        })
                    }
                } catch (_: Exception) {}
            } else {
                loadExtractor(fullUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }
}

enum class AnimeIndoTagCategory(val title: String, val tagsList: List<String>) {
    ACTION_ADVENTURE("Action & Adventure", listOf("Action", "Adventure", "Martial Arts", "Super Power", "Military")),
    COMEDY("Comedy", listOf("Comedy", "Gag Humor", "Parody")),
    DRAMA_ROMANCE("Drama & Romance", listOf("Drama", "Romance", "Boys Love", "Girls Love", "School")),
    FANTASY_SCIFI("Fantasy & Sci-Fi", listOf("Fantasy", "Sci-Fi", "Supernatural", "Isekai", "Magic", "Demons", "Vampire", "Mecha", "Space", "Time Travel", "Reincarnation")),
    MYSTERY_HORROR("Mystery & Horror", listOf("Mystery", "Thriller", "Suspense", "Detective", "Police", "Psychological", "Horror", "Gore")),
    SLICE_OF_LIFE("Slice of Life", listOf("Slice of Life", "Iyashikei", "Kids", "Workplace")),
    SPORTS_GAMES("Sports & Games", listOf("Sports", "Racing", "Strategy Game", "Game")),
    ARTS_CULTURE("Arts & Music", listOf("Music", "Idol", "Historical", "Performing Arts")),
    MATURE("Mature & Ecchi", listOf("Ecchi", "Harem", "Reverse Harem")),
    DEMOGRAPHICS("Demographics", listOf("Shounen", "Shoujo", "Seinen", "Josei")),
    OTHER("Other", listOf("Donghua"));

    companion object {
        fun getCategoryByTag(tag: String): String {
            return entries.find { category ->
                category.tagsList.any { it.equals(tag, ignoreCase = true) }
            }?.title ?: tag
        }
    }
}
