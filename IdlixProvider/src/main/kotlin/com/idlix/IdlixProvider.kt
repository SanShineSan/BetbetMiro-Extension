package com.idlix

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode as CsEpisode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer

class IdlixProvider : MainAPI() {
    override var mainUrl = base64Decode("aHR0cHM6Ly96MS5pZGxpeGt1LmNvbQ==")
    override var name = "Idlix"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?page=%d&limit=36&sort=createdAt" to "Movie Terbaru",
        "$mainUrl/api/series?page=%d&limit=36&sort=createdAt" to "TV Series Terbaru",

        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&type=movie" to "Browse Movie",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&type=series" to "Browse Series",

        "$mainUrl/api/browse?page=%d&limit=36&sort=popular" to "Popular",
        "$mainUrl/api/browse?page=%d&limit=36&sort=rating" to "Rating Tertinggi",

        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=netflix" to "Netflix",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=disney-plus" to "Disney+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=hbo" to "HBO",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=prime-video" to "Amazon Prime",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=apple-tv-plus" to "Apple TV+",

        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=action" to "Action",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=adventure" to "Adventure",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=animation" to "Animation",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=comedy" to "Comedy",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=crime" to "Crime",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=documentary" to "Documentary",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=drama" to "Drama",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=family" to "Family",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=fantasy" to "Fantasy",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=history" to "History",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=horror" to "Horror",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=mystery" to "Mystery",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=romance" to "Romance",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=science-fiction" to "Sci-Fi",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=thriller" to "Thriller",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=war" to "War"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data.format(page.coerceAtLeast(1))

        val response = runCatching {
            app.get(url, timeout = 10000L).parsedSafe<ApiResponse>()
        }.getOrNull()

        val home = response?.data.orEmpty()
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/api/search?q=$encoded&page=${page.coerceAtLeast(1)}&limit=12"

        val response = runCatching {
            app.get(url, timeout = 10000L).parsedSafe<SearchApiResponse>()
        }.getOrNull()

        val results = response?.results.orEmpty()
            .mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                val slug = item.slug ?: return@mapNotNull null
                val poster = item.posterPathFinal?.toTmdbImage("w342")
                val year = (item.releaseDateFinal ?: item.firstAirDateFinal)
                    ?.substringBefore("-")
                    ?.toIntOrNull()

                val link = when (item.contentTypeFinal) {
                    "movie" -> "$mainUrl/api/movies/$slug"
                    "tv_series", "series" -> "$mainUrl/api/series/$slug"
                    else -> {
                        if (!item.firstAirDateFinal.isNullOrBlank()) {
                            "$mainUrl/api/series/$slug"
                        } else {
                            "$mainUrl/api/movies/$slug"
                        }
                    }
                }

                if (link.contains("/api/movies/")) {
                    newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                        this.quality = getSearchQuality(item.quality)
                        this.score = Score.from10(item.voteAverage)
                    }
                } else {
                    newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                        this.quality = getSearchQuality(item.quality)
                        this.score = Score.from10(item.voteAverage)
                    }
                }
            }
            .distinctBy { it.url }

        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val data = app.get(url, timeout = 10000L).parsedSafe<DetailResponse>()
            ?: throw ErrorLoadingException("Invalid JSON")

        val title = data.title ?: data.name ?: "Unknown"
        val poster = data.posterPathFinal?.toTmdbImage("w500")
        val backdrop = data.backdropPathFinal?.toTmdbImage("w780")
        val logo = data.logoPathFinal?.toTmdbImage("w500")
        val year = (data.releaseDateFinal ?: data.firstAirDateFinal)
            ?.substringBefore("-")
            ?.toIntOrNull()

        val tags = data.genres.orEmpty()
            .mapNotNull { it.name }
            .distinct()

        val actors = data.cast.orEmpty().mapNotNull { cast ->
            val actorName = cast.name ?: return@mapNotNull null
            Actor(
                actorName,
                cast.profilePathFinal?.toTmdbImage("w185")
            )
        }

        val trailer = data.trailerUrlFinal
        val rating = data.voteAverageFinal?.toString()
        val recommendations = getRecommendations(data)

        return if (!data.seasons.isNullOrEmpty()) {
            val episodes = getEpisodes(data)

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logo
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                addTrailer(trailer)
                addTMDbId(data.tmdbIdFinal)
                addImdbId(data.imdbIdFinal)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LoadData(
                    id = data.id.orEmpty(),
                    type = "movie"
                ).toJson()
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logo
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                addTrailer(trailer)
                addTMDbId(data.tmdbIdFinal)
                addImdbId(data.imdbIdFinal)
                this.recommendations = recommendations
            }
        }
    }

    private suspend fun getRecommendations(data: DetailResponse): List<SearchResponse> {
        val slug = data.slug ?: return emptyList()

        val relatedUrl = if (!data.seasons.isNullOrEmpty()) {
            "$mainUrl/api/series/$slug/related"
        } else {
            "$mainUrl/api/movies/$slug/related"
        }

        return runCatching {
            app.get(relatedUrl, referer = mainUrl, timeout = 10000L)
                .parsedSafe<ApiResponse>()
                ?.data
                ?.mapNotNull { it.toSearchResponse() }
                ?.distinctBy { it.url }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private suspend fun getEpisodes(data: DetailResponse): List<CsEpisode> {
        val episodes = mutableListOf<CsEpisode>()
        val firstSeason = data.firstSeasonFinal

        firstSeason?.episodes.orEmpty().forEach { episodeData ->
            episodes.add(episodeData.toCloudstreamEpisode(firstSeason?.seasonNumberFinal))
        }

        data.seasons.orEmpty().forEach { season ->
            val seasonNum = season.seasonNumberFinal ?: return@forEach

            if (seasonNum == firstSeason?.seasonNumberFinal) return@forEach

            val slug = data.slug ?: return@forEach
            val seasonUrl = "$mainUrl/api/series/$slug/season/$seasonNum"

            val seasonData = runCatching {
                app.get(seasonUrl, referer = mainUrl, timeout = 10000L)
                    .parsedSafe<SeasonWrapper>()
                    ?.season
            }.getOrNull()

            seasonData?.episodes.orEmpty().forEach { episodeData ->
                episodes.add(episodeData.toCloudstreamEpisode(seasonNum))
            }
        }

        return episodes
            .filter { it.data.isNotBlank() }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<CsEpisode> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            )
    }

    private fun com.idlix.Episode.toCloudstreamEpisode(seasonNumber: Int?): CsEpisode {
        return newEpisode(
            LoadData(
                id = id.orEmpty(),
                type = "episode"
            ).toJson()
        ) {
            this.name = name
            this.season = seasonNumber
            this.episode = episodeNumberFinal
            this.description = overview
            this.runTime = runtime
            this.score = Score.from10(voteAverageFinal?.toString())
            this.posterUrl = stillPathFinal?.toTmdbImage("w300")
        }
    }

    private fun ApiItem.toSearchResponse(): SearchResponse? {
        val title = title ?: return null
        val slugValue = slug ?: return null
        val poster = posterPathFinal?.toTmdbImage("w342")
        val year = (releaseDateFinal ?: firstAirDateFinal)
            ?.substringBefore("-")
            ?.toIntOrNull()

        val type = contentTypeFinal

        val link = when (type) {
            "movie" -> "$mainUrl/api/movies/$slugValue"
            "tv_series", "series" -> "$mainUrl/api/series/$slugValue"
            else -> {
                if (!firstAirDateFinal.isNullOrBlank()) {
                    "$mainUrl/api/series/$slugValue"
                } else {
                    "$mainUrl/api/movies/$slugValue"
                }
            }
        }

        return if (link.contains("/api/movies/")) {
            newMovieSearchResponse(
                title,
                link,
                TvType.Movie
            ) {
                this.posterUrl = poster
                this.year = year
                this.quality = quality
                this.score = Score.from10(voteAverageFinal)
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                link,
                TvType.TvSeries
            ) {
                this.posterUrl = poster
                this.year = year
                this.quality = quality
                this.score = Score.from10(voteAverageFinal)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = runCatching {
            AppUtils.parseJson<LoadData>(data)
        }.getOrNull() ?: return false

        val playInfoUrl = "$mainUrl/api/watch/play-info/${parsed.type}/${parsed.id}"

        val responseText = runCatching {
            app.get(
                playInfoUrl,
                referer = mainUrl,
                timeout = 10000L
            ).text
        }.getOrNull() ?: return false

        val response = runCatching {
            AppUtils.parseJson<Res>(responseText)
        }.getOrNull()

        val claim = response?.claim.orEmpty()
        val redeemUrl = response?.redeemUrlFinal

        val candidates = linkedSetOf<String>()
        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        extractPlayableUrls(responseText).forEach { candidates.add(it) }

        if (!redeemUrl.isNullOrBlank()) {
            val body = """
                {
                    "claim": "$claim"
                }
            """.trimIndent().toRequestBody("application/json".toMediaType())

            val redeemText = runCatching {
                app.post(
                    redeemUrl,
                    requestBody = body,
                    referer = mainUrl,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json"
                    ),
                    timeout = 10000L
                ).text
            }.getOrNull().orEmpty()

            val iframe = runCatching {
                AppUtils.parseJson<Iframe>(redeemText)
            }.getOrNull()

            iframe?.streamUrlFinal
                ?.takeIf { it.isNotBlank() }
                ?.let { candidates.add(it) }

            iframe?.subtitles.orEmpty().forEach { subtitle ->
                val subPath = subtitle.pathFinal ?: return@forEach
                subtitleCallback(
                    newSubtitleFile(
                        subtitle.labelFinal,
                        normalizeUrl(subPath, mainUrl)
                    )
                )
            }

            extractPlayableUrls(redeemText).forEach { candidates.add(it) }
        }

        candidates.forEach { raw ->
            val fixed = normalizeUrl(raw, mainUrl).replace(".txt", ".m3u8")

            when {
                fixed.contains(".m3u8", true) || fixed.contains(".mp4", true) -> directLinks.add(fixed)
                fixed.startsWith("http", true) -> embedLinks.add(fixed)
            }
        }

        var found = false

        directLinks.forEach { link ->
            emitDirectLink(
                link = link,
                referer = mainUrl,
                callback = callback
            )
            found = true
        }

        embedLinks.forEach { embed ->
            val success = loadExtractor(
                embed,
                mainUrl,
                subtitleCallback,
                callback
            )

            if (success) {
                found = true
            } else {
                val nested = resolveNestedLinks(embed)

                nested.forEach { nestedLink ->
                    val fixed = normalizeUrl(nestedLink, embed).replace(".txt", ".m3u8")

                    when {
                        fixed.contains(".m3u8", true) || fixed.contains(".mp4", true) -> {
                            emitDirectLink(
                                link = fixed,
                                referer = embed,
                                callback = callback
                            )
                            found = true
                        }

                        fixed.startsWith("http", true) -> {
                            val nestedSuccess = loadExtractor(
                                fixed,
                                embed,
                                subtitleCallback,
                                callback
                            )
                            if (nestedSuccess) found = true
                        }
                    }
                }
            }
        }

        return found
    }

    private suspend fun resolveNestedLinks(url: String): List<String> {
        val response = runCatching {
            app.get(
                url,
                referer = mainUrl,
                timeout = 10000L
            )
        }.getOrNull() ?: return emptyList()

        return extractPlayableUrls(response.text)
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (link.contains(".m3u8", true)) {
            generateM3u8(
                source = name,
                streamUrl = link,
                referer = referer
            ).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(link).takeIf {
                        it != Qualities.Unknown.value
                    } ?: qualityFromUrl(link)
                }
            )
        }
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.txt)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map {
                runCatching {
                    URLDecoder.decode(it.value, "UTF-8")
                }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped() }
            .forEach { urls.add(it) }

        Regex(
            """(?:url|src|file|source|embedUrl|videoSource|videoUrl)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains(".txt", true) ||
                    it.contains("jeniusplay", true) ||
                    it.contains("majorplay", true) ||
                    it.contains("embed", true) ||
                    it.contains("player", true)
            }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun normalizeUrl(
        url: String,
        baseUrl: String
    ): String {
        val clean = url.cleanEscaped()

        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""")
                    .find(baseUrl)
                    ?.value
                    ?: mainUrl
                "$origin$clean"
            }
            else -> runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrDefault(clean)
        }
    }

    private fun String.toTmdbImage(size: String): String {
        return if (startsWith("http", true)) {
            this
        } else {
            "https://image.tmdb.org/t/p/$size$this"
        }
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}

fun getSearchQuality(check: String?): SearchQuality? {
    val s = check ?: return null
    val u = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()

    val patterns = listOf(
        Regex("\\b(4k|ds4k|uhd|2160p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.FourK,
        Regex("\\b(hdts|hdcam|hdtc)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HdCam,
        Regex("\\b(camrip|cam[- ]?rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
        Regex("\\b(cam)\\b", RegexOption.IGNORE_CASE) to SearchQuality.Cam,
        Regex("\\b(web[- ]?dl|webrip|webdl)\\b", RegexOption.IGNORE_CASE) to SearchQuality.WebRip,
        Regex("\\b(bluray|bdrip|blu[- ]?ray)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1440p|qhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1080p|fullhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(720p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.SD,
        Regex("\\b(hdrip|hdtv)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(dvd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.DVD,
        Regex("\\b(hq)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HQ,
        Regex("\\b(rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip
    )

    for ((regex, quality) in patterns) {
        if (regex.containsMatchIn(u)) return quality
    }

    return null
}