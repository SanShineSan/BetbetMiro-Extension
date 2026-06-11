package com.sad25kag.idlix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.UUID

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?page=%d&limit=36&sort=createdAt" to "Film Terbaru",
        "$mainUrl/api/series?page=%d&limit=36&sort=createdAt" to "Serial TV Terbaru",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest" to "Update Terbaru",
        "$mainUrl/api/browse?page=%d&limit=36&sort=popular" to "Populer",
        "$mainUrl/api/browse?page=%d&limit=36&sort=rating" to "Papan Peringkat",
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
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=kids" to "Kids",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=music" to "Music",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=mystery" to "Mystery",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=reality" to "Reality",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=romance" to "Romance",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=science-fiction" to "Science Fiction",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=soap" to "Soap",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=talk" to "Talk",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=thriller" to "Thriller",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=tv-movie" to "TV Movie",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=war" to "War",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=western" to "Western",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2026" to "2026",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2025" to "2025",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2024" to "2024",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2023" to "2023",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2022" to "2022",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2021" to "2021",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2020" to "2020",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=netflix" to "Netflix",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=hbo" to "HBO",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=prime-video" to "Prime Video",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=disney-plus" to "Disney+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=apple-tv-plus" to "Apple TV+",
    )

    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to IDLIX_USER_AGENT,
        )

    private val jsonHeaders: Map<String, String>
        get() = apiHeaders + mapOf("Content-Type" to "application/json")

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = if (request.data.contains("%d")) {
            request.data.format(page.coerceAtLeast(1))
        } else {
            request.data
        }

        val parsed = runCatching {
            app.get(url, headers = apiHeaders, referer = "$mainUrl/", timeout = 10000L)
                .parsedSafe<ApiResponse>()
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val fallbackType = when {
            url.contains("/api/movies", true) -> "movie"
            url.contains("/api/series", true) -> "series"
            else -> null
        }

        val items = parsed.data
            .mapNotNull { it.toSearchResponse(fallbackType) }
            .distinctBy { it.url }

        val totalPages = parsed.pagination?.totalPages ?: 0

        return newHomePageResponse(
            request.name,
            items,
            hasNext = if (totalPages > 0) page < totalPages else items.isNotEmpty(),
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun search(
        query: String,
        page: Int,
    ): SearchResponseList? {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList<SearchResponse>().toNewSearchResponseList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "$mainUrl/api/search?q=$encoded&page=${page.coerceAtLeast(1)}&limit=12"
        val parsed = runCatching {
            app.get(url, headers = apiHeaders, referer = "$mainUrl/", timeout = 10000L)
                .parsedSafe<SearchApiResponse>()
        }.getOrNull() ?: return null

        val results = parsed.results
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val contentUrl = url.toProviderUrl()
        val data = runCatching {
            app.get(contentUrl, headers = apiHeaders, referer = "$mainUrl/", timeout = 10000L)
                .parsedSafe<DetailResponse>()
        }.getOrNull() ?: throw ErrorLoadingException("Invalid IDLIX API response")

        val title = data.title?.takeIf { it.isNotBlank() } ?: "Unknown"
        val poster = data.posterPath?.tmdbPoster("w500")
        val backdrop = data.backdropPath?.tmdbPoster("w780")
        val logo = data.logoPath?.tmdbPoster("w500")
        val year = (data.releaseDate ?: data.firstAirDate)
            ?.substringBefore("-")
            ?.toIntOrNull()

        val tags = data.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }.orEmpty()
        val actors = data.cast?.mapNotNull { cast ->
            cast.name?.takeIf { it.isNotBlank() }?.let { name ->
                Actor(name, cast.profilePath?.tmdbPoster("w185"))
            }
        }.orEmpty()

        val isSeries = !data.seasons.isNullOrEmpty()
        val webUrl = if (isSeries) {
            "$mainUrl/series/${data.slug.orEmpty()}"
        } else {
            "$mainUrl/movie/${data.slug.orEmpty()}"
        }

        val recommendations = loadRecommendations(data, isSeries)

        return if (isSeries) {
            val episodes = loadEpisodes(data)
            newTvSeriesLoadResponse(title, webUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logo
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString())
                this.duration = data.runtime ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
            }
        } else {
            newMovieLoadResponse(
                title,
                webUrl,
                TvType.Movie,
                LoadData(
                    id = data.id.orEmpty(),
                    type = "movie",
                    refererUrl = webUrl,
                ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logo
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString())
                this.duration = data.runtime ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
            }
        }
    }

    private suspend fun loadEpisodes(data: DetailResponse): List<Episode> {
        val slug = data.slug.orEmpty()
        val episodes = mutableListOf<Episode>()

        data.firstSeason?.episodes?.forEach { ep ->
            val id = ep.id ?: return@forEach
            episodes.add(ep.toCloudstreamEpisode(data.firstSeason.seasonNumber ?: 1, id, slug))
        }

        data.seasons.orEmpty().forEach { season ->
            val seasonNumber = season.seasonNumber ?: return@forEach
            if (seasonNumber == data.firstSeason?.seasonNumber) return@forEach

            val seasonData = runCatching {
                app.get(
                    "$mainUrl/api/series/$slug/season/$seasonNumber",
                    headers = apiHeaders,
                    referer = "$mainUrl/series/$slug",
                    timeout = 10000L,
                ).parsedSafe<SeasonWrapper>()?.season
            }.getOrNull()

            seasonData?.episodes?.forEach { ep ->
                val id = ep.id ?: return@forEach
                episodes.add(ep.toCloudstreamEpisode(seasonNumber, id, slug))
            }
        }

        return episodes.sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 },
        )
    }

    private fun com.sad25kag.idlix.Episode.toCloudstreamEpisode(
        seasonNumber: Int,
        id: String,
        seriesSlug: String,
    ): Episode {
        return newEpisode(
            LoadData(
                id = id,
                type = "episode",
                refererUrl = "$mainUrl/series/$seriesSlug/season/$seasonNumber/episode/${episodeNumber ?: 1}",
            ).toJson(),
        ) {
            this.name = name
            this.season = seasonNumber
            this.episode = episodeNumber
            this.description = overview
            this.runTime = runtime
            this.score = Score.from10(voteAverage?.toString())
            addDate(airDate)
            this.posterUrl = stillPath?.tmdbPoster("w300")
        }
    }

    private suspend fun loadRecommendations(
        data: DetailResponse,
        isSeries: Boolean,
    ): List<SearchResponse> {
        val slug = data.slug ?: return emptyList()
        val relatedUrl = if (isSeries) {
            "$mainUrl/api/series/$slug/related"
        } else {
            "$mainUrl/api/movies/$slug/related"
        }

        return runCatching {
            app.get(relatedUrl, headers = apiHeaders, referer = "$mainUrl/", timeout = 10000L)
                .parsedSafe<ApiResponse>()
                ?.data
                ?.mapNotNull { it.toSearchResponse(null) }
                ?.distinctBy { it.url }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsed = runCatching { parseJson<LoadData>(data) }.getOrNull() ?: return false
        if (parsed.id.isBlank() || parsed.type.isBlank()) return false

        val contentReferer = parsed.refererUrl
            ?.takeIf { it.isNotBlank() }
            ?.toProviderUrl()
            ?: "$mainUrl/"

        val playResponse = runCatching {
            app.get(
                "$mainUrl/api/watch/play-info/${parsed.type}/${parsed.id}",
                headers = apiHeaders,
                cookies = playbackCookies,
                referer = "$mainUrl/",
                timeout = 10000L,
            )
        }.getOrNull() ?: return false

        val playInfo = playResponse.parsedSafe<WatchSessionResponse>() ?: return false
        val sessionResult = resolveWatchSession(
            initial = playInfo,
            contentReferer = contentReferer,
            cookies = playbackCookies + playResponse.cookies,
        ) ?: return false

        val claimSession = sessionResult.first
        val sessionCookies = sessionResult.second
        val claim = claimSession.claim?.takeIf { it.isNotBlank() } ?: return false
        val redeemUrl = claimSession.redeemUrl
            ?.takeIf { it.isNotBlank() }
            ?.fixAgainst(mainUrl)
            ?: return false

        val redeemResponse = runCatching {
            app.post(
                redeemUrl,
                headers = jsonHeaders,
                cookies = sessionCookies,
                referer = "$mainUrl/",
                requestBody = mapOf("claim" to claim)
                    .toJson()
                    .toRequestBody("application/json".toMediaType()),
                timeout = 10000L,
            )
        }.getOrNull() ?: return false

        val iframeResponse = redeemResponse.parsedSafe<Iframe>() ?: redeemResponse.text.toIframeFallback()
        var delivered = false

        val candidates = resolveIframeCandidates(iframeResponse, redeemUrl)
        for (streamUrl in candidates) {
            if (emitStream(
                    streamUrl = streamUrl,
                    contentReferer = contentReferer,
                    quality = claimSession.maxHeight?.toQuality() ?: Qualities.Unknown.value,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
            ) {
                delivered = true
            }
        }

        for (subtitle in iframeResponse.subtitles.orEmpty()) {
            val path = subtitle.path?.takeIf { it.isNotBlank() }?.fixAgainst(redeemUrl) ?: continue
            subtitleCallback(
                newSubtitleFile(
                    subtitle.label?.takeIf { it.isNotBlank() } ?: subtitle.lang ?: "Subtitle",
                    path,
                ),
            )
        }

        return delivered
    }

    private suspend fun resolveWatchSession(
        initial: WatchSessionResponse,
        contentReferer: String,
        cookies: Map<String, String>,
    ): Pair<WatchSessionResponse, Map<String, String>>? {
        var session = initial
        val sessionCookies = cookies.toMutableMap()

        repeat(12) {
            if (session.claim.hasText() && session.redeemUrl.hasText()) {
                return session to sessionCookies.toMap()
            }

            val gateToken = session.gateToken?.takeIf { it.isNotBlank() } ?: return null
            waitForGate(session)

            val claimResponse = runCatching {
                app.post(
                    "$mainUrl/api/watch/session/claim",
                    headers = jsonHeaders,
                    cookies = sessionCookies,
                    referer = "$mainUrl/",
                    requestBody = mapOf("gateToken" to gateToken)
                        .toJson()
                        .toRequestBody("application/json".toMediaType()),
                    timeout = 10000L,
                )
            }.getOrNull() ?: return null

            sessionCookies.putAll(claimResponse.cookies)
            session = claimResponse.parsedSafe<WatchSessionResponse>() ?: return null

            if (session.claim.hasText() && session.redeemUrl.hasText()) {
                return session to sessionCookies.toMap()
            }
        }

        return null
    }

    private suspend fun waitForGate(playInfo: WatchSessionResponse) {
        val remaining = playInfo.remainingMs?.coerceAtLeast(0L) ?: run {
            val serverNow = playInfo.serverNow ?: return
            val unlockAt = playInfo.unlockAt ?: return
            (unlockAt - serverNow).coerceAtLeast(0L)
        }

        if (remaining > 25L) {
            Log.d(name, "Waiting IDLIX gate: ${remaining}ms")
            delay((remaining + 125L).coerceAtMost(16000L))
        }
    }

    private fun resolveIframeCandidates(
        iframeResponse: Iframe,
        redeemUrl: String,
    ): List<String> {
        val urls = mutableListOf<String>()

        iframeResponse.url
            ?.takeIf { it.isNotBlank() }
            ?.unescapeEmbedPayload()
            ?.fixAgainst(redeemUrl)
            ?.let(urls::add)

        val code = iframeResponse.code.orEmpty().unescapeEmbedPayload()
        val patterns = listOf(
            Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<embed[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<(?:source|video)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:iframe|embed|file|src|url|link|source|hls)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE),
        )

        patterns.forEach { regex ->
            regex.findAll(code).forEach { match ->
                val candidate = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
                candidate
                    .unescapeEmbedPayload()
                    .fixAgainst(redeemUrl)
                    ?.let(urls::add)
            }
        }

        return urls.distinct().filterNot { it.isStaticAssetUrl() }
    }

    private suspend fun emitStream(
        streamUrl: String,
        contentReferer: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val normalized = streamUrl.unescapeEmbedPayload()
        val streamReferer = if (normalized.isDirectMediaUrl()) contentReferer else normalized
        var delivered = false

        if (normalized.contains(".m3u8", true)) {
            runCatching {
                generateM3u8(
                    source = name,
                    streamUrl = normalized,
                    referer = "$mainUrl/",
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl,
                        "Accept" to "*/*",
                        "User-Agent" to IDLIX_USER_AGENT,
                    ),
                ).forEach { link ->
                    callback(link)
                    delivered = true
                }
            }.onFailure { error ->
                Log.d(name, "M3U8 helper failed for $normalized: ${error.message}")
            }

            if (!delivered) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = normalized,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "Origin" to mainUrl,
                            "User-Agent" to IDLIX_USER_AGENT,
                        )
                    },
                )
                delivered = true
            }
        } else if (normalized.contains(".mp4", true)) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = normalized,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl,
                        "User-Agent" to IDLIX_USER_AGENT,
                    )
                },
            )
            delivered = true
        }

        if (!delivered) {
            delivered = loadExtractor(normalized, contentReferer, subtitleCallback, callback) ||
                loadExtractor(normalized, streamReferer, subtitleCallback, callback)
        }

        return delivered
    }

    private fun ApiItem.toSearchResponse(defaultType: String?): SearchResponse? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val slug = slug?.takeIf { it.isNotBlank() } ?: return null
        val type = contentType ?: defaultType.orEmpty()
        val poster = posterPath?.tmdbPoster("w342")
        val year = (releaseDate ?: firstAirDate)?.substringBefore("-")?.toIntOrNull()
        val rating = voteAverage?.toString()
        val qualityText = quality

        return if (type.isSeriesType()) {
            newTvSeriesSearchResponse(title, "$mainUrl/api/series/$slug", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                this.quality = getSearchQuality(qualityText)
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/api/movies/$slug", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                this.quality = getSearchQuality(qualityText)
            }
        }
    }

    private fun SearchApiResult.toSearchResponse(): SearchResponse? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val slug = slug?.takeIf { it.isNotBlank() } ?: return null
        val poster = posterPath?.tmdbPoster("w342")
        val year = (releaseDate ?: firstAirDate)?.substringBefore("-")?.toIntOrNull()
        val rating = voteAverage?.toString()
        val qualityText = quality

        return if (contentType.isSeriesType()) {
            newTvSeriesSearchResponse(title, "$mainUrl/api/series/$slug", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                this.quality = getSearchQuality(qualityText)
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/api/movies/$slug", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                this.quality = getSearchQuality(qualityText)
            }
        }
    }

    private fun String?.isSeriesType(): Boolean {
        val value = this.orEmpty().lowercase()
        return value == "tv_series" || value == "series" || value == "tv" || value.contains("series")
    }

    private fun String.tmdbPoster(size: String): String {
        return if (startsWith("http", true)) this else "https://image.tmdb.org/t/p/$size$this"
    }

    private fun String.toProviderUrl(): String {
        val value = trim()
        if (value.isBlank()) return value
        return when {
            value.startsWith("https://178.62.105.101", true) -> mainUrl + value.substring("https://178.62.105.101".length)
            value.startsWith("https://www.idlixku.com", true) -> mainUrl + value.substring("https://www.idlixku.com".length)
            value.startsWith("https://idlixku.com", true) -> mainUrl + value.substring("https://idlixku.com".length)
            value.startsWith("https://idlix.com", true) -> mainUrl + value.substring("https://idlix.com".length)
            else -> value
        }
    }

    private fun String.fixAgainst(baseUrl: String): String? {
        val value = trim()
        if (value.isBlank()) return null
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return runCatching { URI(baseUrl).resolve(value).toString() }.getOrNull()
    }

    private fun String.unescapeEmbedPayload(): String {
        return replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003D", "=", ignoreCase = true)
            .replace("\\u003F", "?", ignoreCase = true)
            .replace("\\u0025", "%", ignoreCase = true)
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\\"", "\"")
            .trim()
    }

    private fun String.toIframeFallback(): Iframe {
        val normalized = unescapeEmbedPayload()
        val url = normalized.firstJsonStringValue("url")
            ?: normalized.firstJsonStringValue("iframe")
            ?: normalized.firstJsonStringValue("embed")
            ?: normalized.firstJsonStringValue("embedUrl")
            ?: normalized.firstJsonStringValue("player")
            ?: normalized.firstJsonStringValue("src")
            ?: Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(normalized)
                ?.groupValues
                ?.getOrNull(1)

        val code = normalized.firstJsonStringValue("code")
            ?: normalized.firstJsonStringValue("html")
            ?: normalized.firstJsonStringValue("embedCode")
            ?: normalized.takeIf { it.contains("<iframe", true) || it.contains("<source", true) || it.contains("<video", true) }

        return Iframe(
            code = code,
            url = url,
            subtitles = extractSubtitleObjects(normalized),
        )
    }

    private fun String.firstJsonStringValue(key: String): String? {
        val pattern = Regex("""["']$key["']\s*:\s*["']((?:\\.|[^"'])*)["']""", RegexOption.IGNORE_CASE)
        return pattern.find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.unescapeEmbedPayload()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractSubtitleObjects(text: String): List<Subtitle> {
        val subtitles = mutableListOf<Subtitle>()
        val objectRegex = Regex("""\{[^{}]*(?:"lang"|"label"|"path"|"file")[^{}]*\}""", RegexOption.IGNORE_CASE)
        for (match in objectRegex.findAll(text)) {
            val block = match.value
            val path = block.firstJsonStringValue("path")
                ?: block.firstJsonStringValue("file")
                ?: continue
            subtitles.add(
                Subtitle(
                    lang = block.firstJsonStringValue("lang"),
                    label = block.firstJsonStringValue("label"),
                    path = path,
                ),
            )
        }
        return subtitles.distinctBy { it.path }
    }

    private fun String.isDirectMediaUrl(): Boolean {
        val value = lowercase()
        return value.contains(".m3u8") || value.contains(".mp4")
    }

    private fun String.isStaticAssetUrl(): Boolean {
        val value = lowercase()
        return listOf(".js", ".css", ".png", ".jpg", ".jpeg", ".webp", ".svg", ".ico", ".woff", ".ttf")
            .any { value.contains(it) }
    }

    private fun Long.toQuality(): Int {
        return when {
            this >= 2160L -> Qualities.P2160.value
            this >= 1080L -> Qualities.P1080.value
            this >= 720L -> Qualities.P720.value
            this >= 480L -> Qualities.P480.value
            this >= 360L -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String?.hasText(): Boolean = !isNullOrBlank()

    private val deviceId = UUID.randomUUID().toString().replace("-", "")

    private val playbackCookies: Map<String, String>
        get() = mapOf(
            "NEXT_LOCALE" to "id",
            "did" to deviceId,
        )
}

fun getSearchQuality(check: String?): SearchQuality? {
    val value = check ?: return null
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()
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
        Regex("\\b(rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
    )

    for ((regex, quality) in patterns) {
        if (regex.containsMatchIn(normalized)) return quality
    }

    return null
}
