package com.midasxxi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.toNewSearchResponseList
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder


class Midasxxi : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://ssstik.tv"
    private var directUrl = mainUrl
    override var name = "MidasXXI"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )


    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Movies",
        "$mainUrl/tvshows" to "TV Series",

        "$mainUrl/genre/action" to "Action",
        "$mainUrl/genre/adventure" to "Adventure",
        "$mainUrl/genre/animation" to "Animation",
        "$mainUrl/genre/anime" to "Anime",
        "$mainUrl/genre/comedy" to "Comedy",
        "$mainUrl/genre/crime" to "Crime",
        "$mainUrl/genre/drama" to "Drama",
        "$mainUrl/genre/family" to "Family",
        "$mainUrl/genre/fantasy" to "Fantasy",
        "$mainUrl/genre/history" to "History",
        "$mainUrl/genre/horror" to "Horror",
        "$mainUrl/genre/j-movie" to "J-Movie",
        "$mainUrl/genre/k-drama" to "K-Drama",
        "$mainUrl/genre/k-movie" to "K-Movie",
        "$mainUrl/genre/music" to "Music",
        "$mainUrl/genre/mystery" to "Mystery",
        "$mainUrl/genre/romance" to "Romance",
        "$mainUrl/genre/documentary" to "Documentary",
        "$mainUrl/genre/live-action" to "Live Action",
        "$mainUrl/genre/sci-fi-fantasy" to "Sci-Fi & Fantasy",
        "$mainUrl/genre/science-fiction" to "Science Fiction",
        "$mainUrl/genre/thriller" to "Thriller",
        "$mainUrl/genre/tv-movie" to "TV Movie",
        "$mainUrl/genre/war" to "War",
        "$mainUrl/genre/western" to "Western",

        "$mainUrl/network/apple-tv" to "Apple TV+",
        "$mainUrl/network/disney" to "Disney+",
        "$mainUrl/network/hbo" to "HBO",
        "$mainUrl/network/netflix" to "Netflix",

        "$mainUrl/tag/dc-movies" to "DCEU Movies",
        "$mainUrl/tag/marvel" to "MCU Movies",
        "$mainUrl/tag/disney" to "Disney+ Movie and Series",
        "$mainUrl/tag/netflix" to "Netflix Movie and Series"
    )

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = request.data.trimEnd('/')
        val target = if (page <= 1) "$base/" else "$base/page/$page/"
        val req = app.get(target)
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        val home = document.select(
            "div#archive-content article.item, article.item.movies, article.item.tvshows, div.items.full article"
        ).mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("/episodes/") -> {
                val slug = uri.substringAfter("/episodes/").substringBefore("/")
                val base = Regex("(.+)-\\d+x\\d+$").find(slug)?.groupValues?.getOrNull(1)
                    ?: slug.substringBeforeLast("-")
                "$mainUrl/tvshows/${base.trimEnd('-')}"
            }
            uri.contains("/episode/") -> {
                var title = uri.substringAfter("$mainUrl/episode/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvshows/$title"
            }

            uri.contains("/season/") -> {
                var title = uri.substringAfter("$mainUrl/season/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvshows/$title"
            }

            else -> {
                uri
            }
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleAnchor = this.selectFirst("div.data > h3 > a, h3 > a") ?: return null
        val title = titleAnchor.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
        val href = getProperLink(titleAnchor.attr("href"))
        val posterUrl = this.selectFirst("div.poster img, img")?.getImageAttr()
        val tvType = if (
            this.classNames().any { it.equals("tvshows", true) } ||
            href.contains("/tvshows/", true) ||
            href.contains("/episodes/", true)
        ) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        val quality = getQualityFromString(this.select("span.quality").text())
        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }

    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = listOf(
            "$mainUrl/search/$encoded/page/$page/",
            "$mainUrl/page/$page/?s=$encoded",
            "$mainUrl/?s=$encoded",
        )

        val req = urls.firstNotNullOfOrNull { url ->
            runCatching { app.get(url) }.getOrNull()?.takeIf {
                it.document.select("div.result-item, div#archive-content article.item, article.item.movies, article.item.tvshows").isNotEmpty()
            }
        } ?: return null

        mainUrl = getBaseUrl(req.url)
        val document = req.document

        val listFromSearchPage = document.select("div.result-item").mapNotNull {
            val titleElement = it.selectFirst("div.title > a, h3 > a, a") ?: return@mapNotNull null
            val title = titleElement.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
            if (title.isBlank()) return@mapNotNull null

            val href = getProperLink(titleElement.attr("href"))
            var posterUrl = it.selectFirst("img")?.getImageAttr()
            if (posterUrl?.contains("image.tmdb.org/t/p") == true) {
                posterUrl = posterUrl.replace(Regex("/w\\d+/"), "/w200/")
            }
            val type = if (
                href.contains("/tvshows/", true) ||
                href.contains("/tvseries/", true) ||
                href.contains("/episodes/", true) ||
                it.classNames().any { c -> c.equals("tvshows", true) }
            ) TvType.TvSeries else TvType.Movie

            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, href, type) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        }

        val listFromArchive = document
            .select("div#archive-content article.item, article.item.movies, article.item.tvshows")
            .mapNotNull { it.toSearchResult() }

        return (listFromSearchPage + listFromArchive)
            .distinctBy { it.url }
            .toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.document
        val title = document.selectFirst("div.data > h1, h1, h1.epih1")?.text()
            ?.replace(Regex("\\(\\d{4}\\)"), "")
            ?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - ")
                ?.trim()
                .orEmpty()

        val poster = document.selectFirst("div.poster > img, .poster img")?.getImageAttr()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.g-item a[href]")?.attr("href")
            ?: ""

        val tags = document.select("div.sgeneros > a").map { it.text() }
        val year = Regex("""(19|20)\d{2}""")
            .find(
                document.selectFirst("div.extra span.date, span.date, .custom_fields:contains(Release date) .valor")
                    ?.text()
                    .orEmpty()
            )
            ?.value
            ?.toIntOrNull()

        val tvType = when {
            request.url.contains("/episodes/", true) -> TvType.TvSeries
            request.url.contains("/tvshows/", true) -> TvType.TvSeries
            document.selectFirst("#single[itemtype*=TVSeries]") != null -> TvType.TvSeries
            else -> TvType.Movie
        }

        val description = document.selectFirst("#info .wp-content > p, div[itemprop=description].wp-content > p, .wp-content > p")
            ?.text()
            ?.trim()
            .orEmpty()

        val trailer = document.selectFirst("#trailer iframe[src], .trailer iframe[src], iframe[src*='youtube'], iframe[src*='youtu.be']")
            ?.attr("src")

        val ratingValue = document.selectFirst("div.starstruck-main[data-rating], .starstruck-main[data-rating], .dt_rating_data[data-rating]")
            ?.attr("data-rating")
            ?.toDoubleOrNull()
            ?: document.selectFirst("span.dt_rating_vgs")?.text()?.toDoubleOrNull()

        val actors = document.select("#cast .person, div.persons > div[itemprop=actor]").mapNotNull {
            val actorName = it.selectFirst("meta[itemprop=name]")?.attr("content")
                ?.takeIf { n -> n.isNotBlank() }
                ?: it.selectFirst("div.data > div.name > a, .name a, a[href*='/cast/']")?.text()
                ?.trim()
                ?: return@mapNotNull null
            val actorImage = it.selectFirst("div.img > img, img")?.getImageAttr()
            Actor(actorName, actorImage)
        }

        val recommendations = document
            .select("div.srelacionados article.item, div#single_relacionados article, div#single_relacionados article.item, div.owl-item article.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("#episodes ul.episodios > li, #seasons ul.episodios > li, #serie_contenido ul.episodios > li, ul.episodios > li")
                .mapNotNull { li ->
                    val href = li.selectFirst("div.episodiotitle > a, a[href*='/episodes/']")
                        ?.attr("href")
                        ?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val name = fixTitle(
                        li.selectFirst("div.episodiotitle > a, .episodiotitle a")
                            ?.text()
                            ?.trim()
                            .orEmpty()
                    )
                    val image = li.selectFirst("div.imagen > img, .imagen img, img")?.getImageAttr()
                    val numText = li.selectFirst("div.numerando")?.text().orEmpty()
                    val (season, episode) = parseEpisodeNumbers(numText, href)

                    newEpisode(href) {
                        this.name = name.ifBlank { "Episode ${episode ?: "?"}" }
                        this.season = season
                        this.episode = episode
                        this.posterUrl = image
                    }
                }
                .distinctBy { it.data }
                .sortedWith(compareBy<Episode>({ it.season ?: 0 }, { it.episode ?: 0 }))

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (ratingValue != null) this.score = Score.from10(ratingValue)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (ratingValue != null) this.score = Score.from10(ratingValue)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageResp = app.get(data)
        val pageUrl = pageResp.url
        directUrl = getBaseUrl(pageUrl)
        val document = pageResp.document
        val emitted = mutableSetOf<String>()
        var found = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            found = true
            callback(link)
        }

        suspend fun emitExtractor(rawUrl: String?, baseUrl: String = pageUrl) {
            val cleaned = rawUrl?.cleanEscaped()?.trim()?.takeIf { it.isNotBlank() } ?: return
            if (cleaned.startsWith("javascript:", true) || cleaned.startsWith("#")) return
            val fixed = normalizePlayerUrl(cleaned, baseUrl)
            if (!fixed.startsWith("http", true)) return
            if (!emitted.add(fixed)) return

            if (fixed.contains("youtube", true) || fixed.contains("youtu.be", true)) return

            loadExtractor(fixed, pageUrl, subtitleCallback, wrappedCallback)
        }

        suspend fun emitPlayerPayload(rawPayload: String?, baseUrl: String = pageUrl) {
            decodePayloadCandidates(rawPayload).forEach { payload ->
                extractPlayerUrls(payload, baseUrl).forEach { playerUrl ->
                    emitExtractor(playerUrl, baseUrl)
                }
            }
        }

        // Initial iframe/source already rendered on some pages.
        document.select(
            "#dooplay_player_response iframe[src], #playcontainer iframe[src], .player iframe[src], .player embed[src], " +
                ".video-content iframe[src], .movieplay iframe[src], iframe.metaframe[src], " +
                "iframe[src*='playcinematic'], iframe[src*='midasfilm'], iframe[src*='dailymotion'], " +
                "iframe[src*='ok.ru'], iframe[src*='rumble'], video[src], source[src]"
        ).forEach { element ->
            emitPlayerPayload(
                element.attr("data-src")
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("data") },
                pageUrl
            )
        }

        // Some current player templates expose server choices as encoded option values.
        document.select(
            "#dooplay_player_response option[value], #playcontainer option[value], #playeroptions option[value], " +
                "#playeroptionsul option[value], .player option[value], .server option[value], .servers option[value], " +
                ".mobius option[value], .mirror option[value], select option[value]"
        ).forEach { option ->
            emitPlayerPayload(option.attr("value"), pageUrl)
        }

        document.select(
            "#dooplay_player_response [data-embed], #dooplay_player_response [data-iframe], " +
                "#dooplay_player_response [data-src], #dooplay_player_response [data-url], " +
                "#dooplay_player_response [data-video], #dooplay_player_response [data-file], #dooplay_player_response [data-link], " +
                "#playcontainer [data-embed], #playcontainer [data-iframe], #playcontainer [data-src], #playcontainer [data-url], " +
                "#playcontainer [data-video], #playcontainer [data-file], #playcontainer [data-link], " +
                ".player [data-embed], .player [data-iframe], .player [data-src], .player [data-url], " +
                ".player [data-video], .player [data-file], .player [data-link], " +
                ".server [data-embed], .server [data-iframe], .server [data-src], .server [data-url], " +
                ".server [data-video], .server [data-file], .server [data-link], " +
                ".servers [data-embed], .servers [data-iframe], .servers [data-src], .servers [data-url], " +
                ".servers [data-video], .servers [data-file], .servers [data-link], " +
                ".mobius [data-embed], .mobius [data-iframe], .mobius [data-src], .mobius [data-url], " +
                ".mobius [data-video], .mobius [data-file], .mobius [data-link], " +
                ".mirror [data-embed], .mirror [data-iframe], .mirror [data-src], .mirror [data-url], " +
                ".mirror [data-video], .mirror [data-file], .mirror [data-link]"
        ).forEach { element ->
            listOf("data-embed", "data-iframe", "data-src", "data-url", "data-video", "data-file", "data-link").forEach { attr ->
                emitPlayerPayload(element.attr(attr), pageUrl)
            }
        }

        val defaultType = if (pageUrl.contains("/episodes/", true) || pageUrl.contains("/tvshows/", true)) {
            "tv"
        } else {
            "movie"
        }

        document.select("ul#playeroptionsul > li.dooplay_player_option[data-post], #playeroptionsul li[data-post]")
            .map {
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type").ifBlank { defaultType }
                )
            }
            .filterNot { (_, nume, _) -> nume.equals("trailer", true) }
            .distinctBy { (id, nume, type) -> "$id|$nume|$type" }
            .forEach { (id, nume, type) ->
                if (id.isBlank() || nume.isBlank() || type.isBlank()) return@forEach

                val ajaxResp = runCatching {
                    app.post(
                        url = "$directUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type
                        ),
                        referer = pageUrl,
                        headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest")
                    )
                }.getOrNull() ?: return@forEach

                val json = ajaxResp.parsedSafe<ResponseHash>() ?: runCatching {
                    AppUtils.parseJson<ResponseHash>(ajaxResp.text.replace("\\/", "/"))
                }.getOrNull()

                if (json == null) {
                    emitPlayerPayload(ajaxResp.text, pageUrl)
                    return@forEach
                }

                val rawEmbed = json.embed_url.trim()
                emitPlayerPayload(rawEmbed, pageUrl)

                // Fallback for old encrypted flow (kept for compatibility with mirrors).
                if (json.key.isNullOrBlank()) return@forEach
                val metrix = runCatching { AppUtils.parseJson<AesData>(rawEmbed).m }.getOrNull() ?: return@forEach
                val password = generateKey(json.key, metrix)
                val decrypted = AesHelper.cryptoAESHandler(rawEmbed, password.toByteArray(), false)
                    ?.fixBloat()
                    ?: return@forEach

                emitPlayerPayload(decrypted, pageUrl)
            }

        return found
    }

    private fun parseEpisodeNumbers(numberText: String, episodeUrl: String): Pair<Int?, Int?> {
        Regex("""(\d+)\s*-\s*(\d+)""").find(numberText)?.destructured?.let { (s, e) ->
            return s.toIntOrNull() to e.toIntOrNull()
        }

        val slug = episodeUrl.substringAfter("/episodes/").substringBefore("/")
        Regex("""-(\d+)x(\d+)$""").find(slug)?.destructured?.let { (s, e) ->
            return s.toIntOrNull() to e.toIntOrNull()
        }

        return null to null
    }

    private fun extractPlayerUrls(payload: String, baseUrl: String): List<String> {
        val results = linkedSetOf<String>()
        val clean = payload.cleanEscaped().trim()
        if (clean.isBlank()) return emptyList()

        if (isDirectPlayerCandidate(clean)) {
            results.add(normalizePlayerUrl(clean, baseUrl))
        }

        val parsed = Jsoup.parse(clean, baseUrl)
        parsed.select(
            "iframe[src], iframe[data-src], embed[src], object[data], video[src], video source[src], source[src], " +
                "[data-embed], [data-iframe], [data-src], [data-url], [data-video], [data-file], [data-link]"
        ).forEach { element ->
            listOf("data-embed", "data-iframe", "data-src", "data-url", "data-video", "data-file", "data-link", "data", "src").forEach { attr ->
                val value = element.attr(attr).cleanEscaped().trim()
                if (isDirectPlayerCandidate(value)) {
                    results.add(normalizePlayerUrl(value, baseUrl))
                }
            }
        }

        extractUrlCandidates(clean).forEach {
            results.add(normalizePlayerUrl(it, baseUrl))
        }

        return results.toList()
    }

    private fun extractUrlCandidates(text: String): List<String> {
        val clean = text.cleanEscaped()
        val results = linkedSetOf<String>()

        Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value }
            .forEach { results.add(it) }

        Regex("""(?<!:)//[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { "https:${it.value}" }
            .forEach { results.add(it) }

        return results.toList()
    }

    private fun decodePayloadCandidates(rawPayload: String?): List<String> {
        val clean = rawPayload?.cleanEscaped()?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val seeds = linkedSetOf(clean)
        runCatching { URLDecoder.decode(clean, "UTF-8") }
            .getOrNull()
            ?.cleanEscaped()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { seeds.add(it) }

        Regex("""atob\(["']([^"']+)["']\)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .forEach { seeds.add(it) }

        val results = linkedSetOf<String>()
        seeds.forEach { seed ->
            results.add(seed)
            val base64Body = seed.substringAfter("base64,", seed).trim()
            if (looksLikeBase64(base64Body)) {
                runCatching { safeBase64Decode(base64Body) }
                    .getOrNull()
                    ?.cleanEscaped()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { results.add(it) }
            }
        }

        return results.toList()
    }

    private fun isDirectPlayerCandidate(value: String): Boolean {
        val clean = value.cleanEscaped().trim()
        if (clean.isBlank()) return false
        if (clean.startsWith("<", true)) return false
        if (clean.startsWith("javascript:", true) || clean.startsWith("#")) return false
        if (clean.startsWith("http", true) || clean.startsWith("//")) return true
        return clean.startsWith("/") && (
            clean.contains("/embed", true) ||
                clean.contains("/video", true) ||
                clean.contains("/stream", true) ||
                clean.contains(".m3u8", true) ||
                clean.contains(".mp4", true) ||
                clean.contains(".webm", true) ||
                clean.contains(".mkv", true)
            )
    }

    private fun looksLikeBase64(value: String): Boolean {
        val compact = value.trim()
        if (compact.length < 12 || compact.length % 4 == 1) return false
        if (compact.contains("<") || compact.contains(">") || compact.contains("http", true)) return false
        return compact.matches(Regex("""[A-Za-z0-9+/=_-]+"""))
    }

    private fun normalizePlayerUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped().trim()
        if (clean.isBlank()) return ""
        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> getBaseUrl(baseUrl).trimEnd('/') + clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
    }

    private fun generateKey(r: String, m: String): String {
        val rList = r.split("\\x").toTypedArray()
        var n = ""
        val decodedM = safeBase64Decode(m.reversed())
        for (s in decodedM.split("|")) {
            n += "\\x" + rList[Integer.parseInt(s) + 1]
        }
        return n
    }

    private fun safeBase64Decode(input: String): String {
        var paddedInput = input.trim().replace("-", "+").replace("_", "/")
        val remainder = paddedInput.length % 4
        if (remainder != 0) {
            paddedInput += "=".repeat(4 - remainder)
        }
        return base64Decode(paddedInput)
    }

    private fun String.fixBloat(): String {
        return this.replace("\"", "").replace("\\", "")
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }


    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class AesData(
        @JsonProperty("m") val m: String,
    )
}