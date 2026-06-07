package com.sad25kag.cinemax21

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeCinemaOS
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeDrama
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeIdlix
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeKisskh
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeMapple
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeMoviebox
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeMoviebox2
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokePlayer4U
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeRiveStream
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeSuperembed
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeVidfast
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeVidlink
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeVidsrc
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeVidsrccc
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeVixsrc
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeWatchsomuch
import com.sad25kag.cinemax21.Cinemax21ProviderExtractor.invokeWyzie
import java.net.URLEncoder
import org.json.JSONObject

open class Cinemax21Provider : TmdbProvider() {
    override var name = "CineMax21"
    override val hasMainPage = true
    override var lang = "id"
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.AsianDrama,
    )

    val wpRedisInterceptor by lazy { CloudflareKiller() }

    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        const val gdbot = "https://gdtot.pro"
        const val anilistAPI = "https://graphql.anilist.co"
        const val malsyncAPI = "https://api.malsync.moe"
        const val jikanAPI = "https://api.jikan.moe/v4"

        private const val apiKey = "b030404650f279792a8d3287232358e3"

        const val gomoviesAPI = "https://gomovies-online.cam"
        const val idlixAPI = "https://tv10.idlixku.com"
        const val vidsrcccAPI = "https://vidsrc.cc"
        const val vidSrcAPI = "https://vidsrc.net"
        const val xprimeAPI = "https://backend.xprime.tv"
        const val watchSomuchAPI = "https://watchsomuch.tv"
        const val mappleAPI = "https://mapple.uk"
        const val vidlinkAPI = "https://vidlink.pro"
        const val vidfastAPI = "https://vidfast.pro"
        const val wyzieAPI = "https://sub.wyzie.ru"
        const val vixsrcAPI = "https://vixsrc.to"
        const val vidsrccxAPI = "https://vidsrc.cx"
        const val superembedAPI = "https://multiembed.mov"
        const val vidrockAPI = "https://vidrock.net"
        const val cinemaOSApi = "https://cinemaos.tech"
        const val Player4uApi = "https://player4u.xyz"
        const val RiveStreamAPI = "https://rivestream.org"

        fun getType(t: String?): TvType = when (t) {
            "movie" -> TvType.Movie
            else -> TvType.TvSeries
        }

        fun getStatus(t: String?): ShowStatus = when (t) {
            "Returning Series" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override val mainPage = mainPageOf(
        "$tmdbAPI/trending/movie/day?api_key=$apiKey&region=US&without_genres=16" to "Trending Movies",
        "$tmdbAPI/trending/tv/day?api_key=$apiKey&region=US&without_genres=16" to "Trending TV Shows",
        "$tmdbAPI/movie/now_playing?api_key=$apiKey&region=US&without_genres=16" to "Now Playing Movies",
        "$tmdbAPI/movie/popular?api_key=$apiKey&region=US&without_genres=16" to "Popular Movies",
        "$tmdbAPI/tv/popular?api_key=$apiKey&region=US&without_genres=16" to "Popular TV Shows",
        "$tmdbAPI/movie/top_rated?api_key=$apiKey&region=US&without_genres=16" to "Top Rated Movies",
        "$tmdbAPI/tv/top_rated?api_key=$apiKey&region=US&without_genres=16" to "Top Rated TV Shows",
        "$tmdbAPI/discover/movie?api_key=$apiKey&sort_by=popularity.desc&primary_release_date.gte=2020-01-01&without_genres=16" to "Popular Movies (2020+)",
        "$tmdbAPI/discover/tv?api_key=$apiKey&sort_by=popularity.desc&first_air_date.gte=2020-01-01&without_genres=16" to "Popular TV Shows (2020+)",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=id&without_genres=16&sort_by=popularity.desc" to "Indonesian Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=id&without_genres=16&sort_by=popularity.desc" to "Indonesian Series",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=id&with_genres=27&without_genres=16&sort_by=popularity.desc" to "Indonesian Horror",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=tl&without_genres=16&sort_by=popularity.desc" to "Philippines Series",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=tl&without_genres=16&sort_by=popularity.desc" to "Philippines Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=vi&without_genres=16&sort_by=popularity.desc" to "Vietnamese Series",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=vi&without_genres=16&sort_by=popularity.desc" to "Vietnamese Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&sort_by=popularity.desc&without_genres=16" to "Korean Dramas",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=zh&sort_by=popularity.desc&without_genres=16" to "Chinese Dramas",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ja&sort_by=popularity.desc&without_genres=16" to "Japanese Series",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=th&sort_by=popularity.desc&without_genres=16" to "Thai Series",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=hi&sort_by=popularity.desc&without_genres=16" to "Indian Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ko&sort_by=popularity.desc&without_genres=16" to "Korean Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=zh&sort_by=popularity.desc&without_genres=16" to "Chinese Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ja&sort_by=popularity.desc&without_genres=16" to "Japanese Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=213&sort_by=popularity.desc&first_air_date.gte=2020-01-01&without_genres=16" to "Netflix Originals",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_watch_providers=8&watch_region=US&sort_by=popularity.desc&primary_release_date.gte=2020-01-01&without_genres=16" to "Netflix Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=49&sort_by=popularity.desc&first_air_date.gte=2020-01-01&without_genres=16" to "HBO Originals",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_watch_providers=384|1899&watch_region=US&sort_by=popularity.desc&primary_release_date.gte=2020-01-01&without_genres=16" to "HBO Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2739&sort_by=popularity.desc&without_genres=16" to "Disney+ Series",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_watch_providers=337&watch_region=US&sort_by=popularity.desc&without_genres=16" to "Disney+ Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_watch_providers=9&watch_region=US&sort_by=popularity.desc&primary_release_date.gte=2020-01-01&without_genres=16" to "Prime Video Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_watch_providers=9&watch_region=US&sort_by=popularity.desc&first_air_date.gte=2020-01-01&without_genres=16" to "Prime Video Series",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_watch_providers=350&watch_region=US&sort_by=popularity.desc&primary_release_date.gte=2020-01-01&without_genres=16" to "Apple TV+ Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_watch_providers=350&watch_region=US&sort_by=popularity.desc&first_air_date.gte=2020-01-01&without_genres=16" to "Apple TV+ Series",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_watch_providers=15&watch_region=US&sort_by=popularity.desc&primary_release_date.gte=2020-01-01&without_genres=16" to "Hulu Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_watch_providers=15&watch_region=US&sort_by=popularity.desc&first_air_date.gte=2020-01-01&without_genres=16" to "Hulu Series",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_watch_providers=531&watch_region=US&sort_by=popularity.desc&primary_release_date.gte=2020-01-01&without_genres=16" to "Paramount+ Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_watch_providers=531&watch_region=US&sort_by=popularity.desc&first_air_date.gte=2020-01-01&without_genres=16" to "Paramount+ Series",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_watch_providers=386|387&watch_region=US&sort_by=popularity.desc&primary_release_date.gte=2020-01-01&without_genres=16" to "Peacock Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_watch_providers=386|387&watch_region=US&sort_by=popularity.desc&first_air_date.gte=2020-01-01&without_genres=16" to "Peacock Series",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_watch_providers=283&watch_region=US&with_genres=16&sort_by=popularity.desc" to "Crunchyroll",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_watch_providers=158&watch_region=ID&sort_by=popularity.desc" to "Viu",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_watch_providers=373&watch_region=ID&sort_by=popularity.desc" to "WeTV",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_watch_providers=581&watch_region=ID&sort_by=popularity.desc" to "iQIYI",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ja&with_genres=16&sort_by=popularity.desc" to "Japanese Anime",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=zh&with_genres=16&sort_by=popularity.desc" to "Chinese Donghua",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&with_genres=16&sort_by=popularity.desc" to "Korean Animation",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=28&sort_by=popularity.desc&without_genres=16" to "Action Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=12&sort_by=popularity.desc&without_genres=16" to "Adventure Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=35&sort_by=popularity.desc&without_genres=16" to "Comedy Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=80&sort_by=popularity.desc&without_genres=16" to "Crime Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=99&sort_by=popularity.desc&without_genres=16" to "Documentary Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=18&sort_by=popularity.desc&without_genres=16" to "Drama Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=14&sort_by=popularity.desc&without_genres=16" to "Fantasy Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=27&sort_by=popularity.desc&without_genres=16" to "Horror Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=9648&sort_by=popularity.desc&without_genres=16" to "Mystery Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=10749&sort_by=popularity.desc&without_genres=16" to "Romance Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=878&sort_by=popularity.desc&without_genres=16" to "Sci-Fi Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=53&sort_by=popularity.desc&without_genres=16" to "Thriller Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=10752&sort_by=popularity.desc&without_genres=16" to "War Movies",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=37&sort_by=popularity.desc&without_genres=16" to "Western Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_genres=10759&sort_by=popularity.desc&without_genres=16" to "Action & Adventure TV",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_genres=35&sort_by=popularity.desc&without_genres=16" to "Comedy TV",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_genres=80&sort_by=popularity.desc&without_genres=16" to "Crime TV",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_genres=99&sort_by=popularity.desc&without_genres=16" to "Documentary TV",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_genres=18&sort_by=popularity.desc&without_genres=16" to "Drama TV",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_genres=10751&sort_by=popularity.desc&without_genres=16" to "Family TV",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_genres=9648&sort_by=popularity.desc&without_genres=16" to "Mystery TV",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_genres=10765&sort_by=popularity.desc&without_genres=16" to "Sci-Fi & Fantasy TV",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_genres=10768&sort_by=popularity.desc&without_genres=16" to "War & Politics TV",
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500/$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    private fun JSONObject.putNullable(name: String, value: Any?) {
        if (value != null) put(name, value)
    }

    private fun JSONObject.optIntNullable(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    private fun JSONObject.optStringNullable(name: String): String? {
        return if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
    }

    private fun Data.toJson(): String {
        return JSONObject().apply {
            putNullable("id", id)
            putNullable("type", type)
            putNullable("aniId", aniId)
            putNullable("malId", malId)
        }.toString()
    }

    private fun LinkData.toJson(): String {
        return JSONObject().apply {
            putNullable("id", id)
            putNullable("imdbId", imdbId)
            putNullable("tvdbId", tvdbId)
            putNullable("type", type)
            putNullable("season", season)
            putNullable("episode", episode)
            putNullable("aniId", aniId)
            putNullable("animeId", animeId)
            putNullable("title", title)
            putNullable("year", year)
            putNullable("orgTitle", orgTitle)
            put("isAnime", isAnime)
            putNullable("airedYear", airedYear)
            putNullable("lastSeason", lastSeason)
            putNullable("epsTitle", epsTitle)
            putNullable("jpTitle", jpTitle)
            putNullable("date", date)
            putNullable("airedDate", airedDate)
            put("isAsian", isAsian)
            put("isBollywood", isBollywood)
            put("isCartoon", isCartoon)
        }.toString()
    }

    private fun parseData(json: String): Data {
        val obj = JSONObject(json)
        return Data(
            id = obj.optIntNullable("id"),
            type = obj.optStringNullable("type"),
            aniId = obj.optStringNullable("aniId"),
            malId = obj.optIntNullable("malId"),
        )
    }

    private fun parseLinkData(json: String): LinkData {
        val obj = JSONObject(json)
        return LinkData(
            id = obj.optIntNullable("id"),
            imdbId = obj.optStringNullable("imdbId"),
            tvdbId = obj.optIntNullable("tvdbId"),
            type = obj.optStringNullable("type"),
            season = obj.optIntNullable("season"),
            episode = obj.optIntNullable("episode"),
            aniId = obj.optStringNullable("aniId"),
            animeId = obj.optStringNullable("animeId"),
            title = obj.optStringNullable("title"),
            year = obj.optIntNullable("year"),
            orgTitle = obj.optStringNullable("orgTitle"),
            isAnime = obj.optBoolean("isAnime", false),
            airedYear = obj.optIntNullable("airedYear"),
            lastSeason = obj.optIntNullable("lastSeason"),
            epsTitle = obj.optStringNullable("epsTitle"),
            jpTitle = obj.optStringNullable("jpTitle"),
            date = obj.optStringNullable("date"),
            airedDate = obj.optStringNullable("airedDate"),
            isAsian = obj.optBoolean("isAsian", false),
            isBollywood = obj.optBoolean("isBollywood", false),
            isCartoon = obj.optBoolean("isCartoon", false),
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val adultQuery = if (settingsForProvider.enableAdult) {
            ""
        } else {
            "&without_keywords=190370|13059|226161|195669"
        }
        val type = if (request.data.contains("/movie", ignoreCase = true)) "movie" else "tv"
        val pageUrl = "${request.data}$adultQuery&page=$page"

        val home = app.get(pageUrl)
            .parsedSafe<Results>()
            ?.results
            ?.mapNotNull { media -> media.toSearchResponse(type) }
            .orEmpty()

        return newHomePageResponse(
            HomePageList(request.name, home),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        val mediaTypeValue = mediaType ?: type ?: return null
        val titleValue = title ?: name ?: originalTitle ?: return null
        val data = Data(id = id, type = mediaTypeValue).toJson()
        val poster = getImageUrl(posterPath)
        val scoreValue = Score.from10(voteAverage)

        return if (getType(mediaTypeValue) == TvType.TvSeries) {
            newTvSeriesSearchResponse(titleValue, data, TvType.TvSeries) {
                this.posterUrl = poster
                this.score = scoreValue
            }
        } else {
            newMovieSearchResponse(titleValue, data, TvType.Movie) {
                this.posterUrl = poster
                this.score = scoreValue
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return app.get("$tmdbAPI/search/multi?api_key=$apiKey&language=en-US&query=$encodedQuery&page=1&include_adult=${settingsForProvider.enableAdult}")
            .parsedSafe<Results>()
            ?.results
            ?.mapNotNull { media -> media.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = try {
            if (url.startsWith("https://www.themoviedb.org/")) {
                val segments = url.removeSuffix("/").split("/")
                val id = segments.lastOrNull()?.toIntOrNull()
                val type = when {
                    url.contains("/movie/") -> "movie"
                    url.contains("/tv/") -> "tv"
                    else -> null
                }
                Data(id = id, type = type)
            } else {
                parseData(url)
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("Invalid URL or JSON data: ${e.message}")
        }

        val type = getType(data.type)
        val append = "alternative_titles,credits,external_ids,keywords,videos,recommendations"
        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&append_to_response=$append&include_video_language=id,en"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&append_to_response=$append&include_video_language=id,en"
        }
        val res = app.get(resUrl).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid Json Response")

        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val orgTitle = res.originalTitle ?: res.originalName ?: return null
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        val genres = res.genres?.mapNotNull { it.name }

        val isCartoon = genres?.contains("Animation") ?: false
        val isAnime = isCartoon && (res.originalLanguage == "zh" || res.originalLanguage == "ja")
        val isAsian = !isAnime && (res.originalLanguage == "zh" || res.originalLanguage == "ko")
        val isBollywood = res.productionCountries?.any { it.name == "India" } ?: false

        val keywords = res.keywords?.results?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { res.keywords?.keywords?.mapNotNull { it.name }.orEmpty() }

        val actors = res.credits?.cast?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: cast.originalName
                    ?: return@mapNotNull null, getImageUrl(cast.profilePath)
                ), roleString = cast.character
            )
        } ?: return null
        val recommendations =
            res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }

        val trailer = res.videos?.results
            ?.filter { it.site == "YouTube" && it.key?.isNotBlank() == true && it.type == "Trailer" }
            ?.sortedByDescending { it.type == "Trailer" }
            ?.map { "https://www.youtube.com/watch?v=${it.key}" }
            ?.take(1)

        return if (type == TvType.TvSeries) {
            val lastSeason = res.lastEpisodeToAir?.seasonNumber
            val episodes = res.seasons?.mapNotNull { season ->
                app.get("$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                        newEpisode(
                            data = LinkData(
                                data.id,
                                res.externalIds?.imdbId,
                                res.externalIds?.tvdbId,
                                data.type,
                                eps.seasonNumber,
                                eps.episodeNumber,
                                title = title,
                                year = season.airDate?.split("-")?.first()?.toIntOrNull(),
                                orgTitle = orgTitle,
                                isAnime = isAnime,
                                airedYear = year,
                                lastSeason = lastSeason,
                                epsTitle = eps.name,
                                jpTitle = res.alternativeTitles?.results?.find { it.iso31661 == "JP" }?.title,
                                date = season.airDate,
                                airedDate = res.releaseDate
                                    ?: res.firstAirDate,
                                isAsian = isAsian,
                                isBollywood = isBollywood,
                                isCartoon = isCartoon
                            ).toJson()
                        ) {
                            this.name =
                                eps.name + if (isUpcoming(eps.airDate)) " • [UPCOMING]" else ""
                            this.season = eps.seasonNumber
                            this.episode = eps.episodeNumber
                            this.posterUrl = getImageUrl(eps.stillPath)
                            this.score = Score.from10(eps.voteAverage)
                            this.description = eps.overview
                        }.apply {
                            this.addDate(eps.airDate)
                        }
                    }
            }?.flatten() ?: listOf()
            newTvSeriesLoadResponse(
                title,
                url,
                when {
                    isAnime -> TvType.Anime
                    isAsian -> TvType.AsianDrama
                    isCartoon -> TvType.Cartoon
                    else -> TvType.TvSeries
                },
                episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags = keywords.takeIf { it.isNotEmpty() } ?: genres
                this.score = Score.from10(res.voteAverage?.toString())
                this.showStatus = getStatus(res.status)
                this.recommendations = recommendations
                this.actors = actors
                this.contentRating = fetchContentRating(data.id, "US")
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.externalIds?.imdbId)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                when {
                    isAnime -> TvType.AnimeMovie
                    isAsian -> TvType.AsianDrama
                    isCartoon -> TvType.Cartoon
                    else -> TvType.Movie
                },
                LinkData(
                    data.id,
                    res.externalIds?.imdbId,
                    res.externalIds?.tvdbId,
                    data.type,
                    title = title,
                    year = year,
                    orgTitle = orgTitle,
                    isAnime = isAnime,
                    jpTitle = res.alternativeTitles?.results?.find { it.iso31661 == "JP" }?.title,
                    airedDate = res.releaseDate
                        ?: res.firstAirDate,
                    isAsian = isAsian,
                    isBollywood = isBollywood
                ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.comingSoon = isUpcoming(releaseDate)
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = keywords.takeIf { it.isNotEmpty() } ?: genres
                this.score = Score.from10(res.voteAverage?.toString())
                this.recommendations = recommendations
                this.actors = actors
                this.contentRating = fetchContentRating(data.id, "US")
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.externalIds?.imdbId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val res = parseLinkData(data)

        runAllAsync(
            {
                invokeIdlix(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeMoviebox2(
                    res.title ?: return@runAllAsync,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeDrama(
                    res.title ?: return@runAllAsync,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeKisskh(
                    res.title ?: return@runAllAsync,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeMoviebox(
                    res.title ?: return@runAllAsync,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeVidlink(res.id, res.season, res.episode, callback)
            },
            {
                invokeVidsrccc(
                    res.id,
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeVixsrc(res.id, res.season, res.episode, callback)
            },
            {
                invokeCinemaOS(
                    res.imdbId,
                    res.id,
                    res.title,
                    res.season,
                    res.episode,
                    res.year,
                    callback,
                    subtitleCallback
                )
            },
            {
                if (!res.isAnime) invokePlayer4U(
                    res.title,
                    res.season,
                    res.episode,
                    res.year,
                    callback
                )
            },
            {
                if (!res.isAnime) invokeRiveStream(res.id, res.season, res.episode, callback)
            },
            {
                invokeVidsrc(
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeWatchsomuch(
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback
                )
            },
            {
                invokeVidfast(res.id, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeMapple(res.id, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeWyzie(res.id, res.season, res.episode, subtitleCallback)
            },
            {
                invokeSuperembed(
                    res.id,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            }
        )

        return true
    }

    data class LinkData(
        val id: Int? = null,
        val imdbId: String? = null,
        val tvdbId: Int? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val aniId: String? = null,
        val animeId: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val orgTitle: String? = null,
        val isAnime: Boolean = false,
        val airedYear: Int? = null,
        val lastSeason: Int? = null,
        val epsTitle: String? = null,
        val jpTitle: String? = null,
        val date: String? = null,
        val airedDate: String? = null,
        val isAsian: Boolean = false,
        val isBollywood: Boolean = false,
        val isCartoon: Boolean = false,
    )

    data class Data(
        val id: Int? = null,
        val type: String? = null,
        val aniId: String? = null,
        val malId: Int? = null,
    )

    data class Results(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class Genres(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class Keywords(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class KeywordResults(
        @JsonProperty("results") val results: ArrayList<Keywords>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: ArrayList<Keywords>? = arrayListOf(),
    )

    data class Seasons(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("air_date") val airDate: String? = null,
    )

    data class Cast(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class Episodes(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class MediaDetailEpisodes(
        @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
    )

    data class Trailers(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("site") val site: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class ResultsTrailer(
        @JsonProperty("results") val results: List<Trailers>? = null,
    )

    data class AltTitles(
        @JsonProperty("iso_3166_1") val iso31661: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class ResultsAltTitles(
        @JsonProperty("results") val results: ArrayList<AltTitles>? = arrayListOf(),
    )

    data class ExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("tvdb_id") val tvdbId: Int? = null,
    )

    data class Credits(
        @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class ResultsRecommendations(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class LastEpisodeToAir(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class ProductionCountries(
        @JsonProperty("name") val name: String? = null,
    )

    data class MediaDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Any? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: KeywordResults? = null,
        @JsonProperty("last_episode_to_air") val lastEpisodeToAir: LastEpisodeToAir? = null,
        @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
        @JsonProperty("videos") val videos: ResultsTrailer? = null,
        @JsonProperty("external_ids") val externalIds: ExternalIds? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
        @JsonProperty("alternative_titles") val alternativeTitles: ResultsAltTitles? = null,
        @JsonProperty("production_countries") val productionCountries: ArrayList<ProductionCountries>? = arrayListOf(),
    )
}
