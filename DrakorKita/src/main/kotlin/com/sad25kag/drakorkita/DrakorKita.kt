package com.sad25kag.drakorkita

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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64

class DrakorKita : MainAPI() {
    override var mainUrl = "https://drakor.kita.mobi"
    override var name = "DrakorKita"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    private val sourceHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private val ajaxHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/plain, */*; q=0.01",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "/" to "Eps Terbaru",
        "all?media_type=tv" to "Series",
        "all?media_type=movie" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getDocument(buildPagedUrl(request.data, page))
        val list = document.toSearchResults(request.name)

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = list,
                isHorizontalImages = false
            ),
            hasNext = list.isNotEmpty() && hasNextPage(document, page)
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrls = listOf(
            buildPagedUrl("?s=$encoded", page),
            buildPagedUrl("all?keyword=$encoded", page)
        )

        val results = linkedMapOf<String, SearchResponse>()
        searchUrls.forEach { url ->
            runCatching {
                getDocument(url).toSearchResults("Search")
            }.getOrDefault(emptyList()).forEach { item ->
                results[item.url] = item
            }
        }

        return results.values.toList().toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)
        val title = document.pickTitle()
            .ifBlank { url.substringBeforeLast('/').substringAfterLast('/').replace('-', ' ') }
        val cleanTitle = cleanDetailTitle(title)
        val poster = document.pickPoster()
        val plot = document.pickDescription()
        val tags = document.pickTags()
        val year = Regex("""\((\d{4})\)""")
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val configs = parseApiConfigs(document, url, cleanTitle, poster)
        val apiEpisodes = configs
            .take(1)
            .flatMap { config -> fetchEpisodes(config) }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        val infoText = document.select("ul.anf").text()
        val isSeries = apiEpisodes.size > 1 ||
            infoText.contains("Type : TV Series", ignoreCase = true) ||
            title.contains("Season", ignoreCase = true) ||
            title.contains(Regex("""Episode\s+\d+\s*-\s*\d+""", RegexOption.IGNORE_CASE))

        return if (isSeries) {
            val episodes = if (apiEpisodes.isNotEmpty()) {
                apiEpisodes
            } else {
                configs.firstOrNull()?.let { config ->
                    listOf(
                        newEpisode(config.toPayloadJson()) {
                            name = "Episode"
                            posterUrl = poster
                        }
                    )
                } ?: emptyList()
            }

            newTvSeriesLoadResponse(cleanTitle, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            val movieData = apiEpisodes.firstOrNull()?.data
                ?: configs.firstOrNull()?.toPayloadJson()
                ?: url

            newMovieLoadResponse(cleanTitle, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = EpisodePayload.fromJson(data)
        if (payload != null) {
            return DrakorKitaResolver.resolveApiPlayback(
                providerName = name,
                mainUrl = mainUrl,
                payload = payload.toResolverPayload(),
                ajaxHeaders = ajaxHeaders,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }

        val document = getDocument(data)
        DrakorKitaResolver.extractSubtitles(document, mainUrl).forEach(subtitleCallback)

        val apiConfigs = parseApiConfigs(document, data, document.pickTitle(), document.pickPoster())
        val apiHandled = apiConfigs.firstOrNull()?.let { config ->
            DrakorKitaResolver.resolveApiPlayback(
                providerName = name,
                mainUrl = mainUrl,
                payload = config.toPayload().toResolverPayload(),
                ajaxHeaders = ajaxHeaders,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        } ?: false

        if (apiHandled) return true

        val candidates = DrakorKitaResolver.extractEmbedCandidates(document, mainUrl)
        return DrakorKitaResolver.resolveCandidates(
            providerName = name,
            mainUrl = mainUrl,
            pageUrl = data,
            candidates = candidates,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun getDocument(url: String): Document {
        return app.get(
            url = url,
            headers = sourceHeaders,
            referer = mainUrl
        ).document
    }

    private suspend fun getText(url: String, referer: String): String {
        return app.get(
            url = url,
            headers = ajaxHeaders,
            referer = referer
        ).text
    }

    private fun buildPagedUrl(path: String, page: Int): String {
        val base = when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path == "/" || path.isBlank() -> "$mainUrl/"
            path.startsWith("?") -> "$mainUrl/$path"
            else -> "$mainUrl/${path.trimStart('/')}"
        }
        if (page <= 1) return base
        val separator = if (base.contains("?")) "&" else "?"
        return "$base${separator}page=$page"
    }

    private fun Document.toSearchResults(sectionName: String): List<SearchResponse> {
        val type = when {
            sectionName.contains("movie", ignoreCase = true) -> TvType.Movie
            else -> TvType.AsianDrama
        }

        return selectCardsForSection(sectionName)
            .mapNotNull { it.toSearchResult(type) }
            .distinctBy { it.url }
    }

    private fun Document.selectCardsForSection(sectionName: String): List<Element> {
        val keyword = when {
            sectionName.contains("eps", ignoreCase = true) -> "Eps Terbaru"
            else -> null
        }

        val sectionRow = keyword?.let { key ->
            select("h4.heading1").firstOrNull { heading ->
                heading.text().contains(key, ignoreCase = true)
            }?.let { heading ->
                var sibling = heading.nextElementSibling()
                while (sibling != null && !sibling.classNames().contains("row")) {
                    sibling = sibling.nextElementSibling()
                }
                sibling
            }
        }

        val selector = "a.poster[href*=/detail/], .card a[href*=/detail/], a[href*=/detail/]"
        return (sectionRow ?: this).select(selector).toList()
    }

    private fun Element.toSearchResult(defaultType: TvType): SearchResponse? {
        val href = attr("abs:href").ifBlank { DrakorKitaResolver.normalizeUrl(attr("href"), mainUrl) }
        if (href.isBlank() || !href.contains("/detail/")) return null

        val image = selectFirst("img")
        val rawTitle = selectFirst(".titit")?.let { titleNode ->
            titleNode.ownText().trim().ifBlank {
                Jsoup.parse(titleNode.html().substringBefore("<br")).text().trim()
            }
        }.orEmpty()
            .ifBlank { image?.attr("alt").orEmpty() }
            .ifBlank { attr("title") }
            .ifBlank { text() }
        val title = cleanCardTitle(rawTitle)
        if (title.isBlank()) return null

        val rawPoster = image?.let {
            it.attr("data-src")
                .ifBlank { it.attr("data-lazy-src") }
                .ifBlank { it.attr("data-original") }
                .ifBlank { it.attr("src") }
                .ifBlank { it.attr("srcset").substringBefore(" ") }
        }.orEmpty()
        val poster = rawPoster
            .takeIf { it.isNotBlank() && !it.contains("/svg/", ignoreCase = true) }
            ?.let(::fixUrlNull)

        if (defaultType == TvType.Movie && poster == null) return null

        val detectionText = listOf(rawTitle, attr("title"), text()).joinToString(" ")
        val detectedType = when {
            detectionText.contains(Regex("""\bE\d+(/\d+|\s*END)?\b""", RegexOption.IGNORE_CASE)) -> TvType.AsianDrama
            detectionText.contains(Regex("""Episode\s+\d+""", RegexOption.IGNORE_CASE)) -> TvType.AsianDrama
            detectionText.contains("Season", ignoreCase = true) -> TvType.AsianDrama
            defaultType == TvType.Movie -> TvType.Movie
            else -> TvType.AsianDrama
        }

        return if (detectedType == TvType.Movie) {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, fixUrl(href), detectedType) {
                posterUrl = poster
            }
        }
    }

    private fun Document.pickTitle(): String {
        return selectFirst("h1[itemprop=headline]")?.text()?.trim()
            ?: selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: selectFirst("title")?.text()?.trim()
            ?: title().substringBefore(" - ").trim()
    }

    private fun cleanDetailTitle(raw: String): String {
        return raw
            .substringBefore(" - DrakorKita")
            .replace(Regex("""^\s*Nonton\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\s*Streaming\s+Drama\s+Korea\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\s*Drama\s+Korea\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\[Episode\s+\d+\s*-\s*\d+\].*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(WEB-DL|WEBRip|HDRip|BluRay|HDTV).*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(1080p|720p|480p|2160p).*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub(title)?\s+Indo(nesia)?.*""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun Document.pickPoster(): String? {
        val raw = selectFirst("meta[property=og:image]")?.attr("content")
            ?: selectFirst("img[src*='image.tmdb.org'], img[src*=/wp-content/], img[src*=/uploads/], .poster img, .thumb img")?.attr("src")
            ?: selectFirst("img[data-src]")?.attr("data-src")
        return raw?.let(::fixUrlNull)
    }

    private fun Document.pickDescription(): String? {
        return select(".sinopsis .desc-wrap p, .sinopsis p, .mv-description .desc-wrap p")
            .joinToString("\n") { it.text().trim() }
            .trim()
            .ifBlank {
                selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()
            }
            .ifBlank { null }
    }

    private fun Document.pickTags(): List<String> {
        return select("ol.breadcrumb a[href*=genre=], .animefull a[href*=genre=]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Home", ignoreCase = true) }
            .distinct()
    }

    private suspend fun fetchEpisodes(config: ApiConfig): List<com.lagradost.cloudstream3.Episode> {
        val url = "${config.cApiHost.trimEnd('/')}/episode_mob.php" +
            "?is_mob=${config.isMob}" +
            "&is_uc=${config.isUc}" +
            "&movie_id=${urlEncode(config.movieId)}" +
            "&tag=${urlEncode(config.tag)}" +
            "&c=${urlEncode(config.c)}" +
            "&t=${urlEncode(config.t)}" +
            "&ver=${urlEncode(config.ver)}"

        val json = runCatching { JSONObject(getText(url, config.detailUrl)) }.getOrNull() ?: return emptyList()
        val serverXid = json.optString("server_xid").ifBlank { "" }
        val firstEpisodeId = json.optString("first_ep_id").ifBlank { "" }
        val episodeHtml = json.optString("episode_lists").ifBlank { "" }
        val items = linkedMapOf<String, EpisodePayload>()

        if (episodeHtml.isNotBlank()) {
            Jsoup.parse(episodeHtml).select("a[data-epid], a[data-server_xid], a[data-movieid]").forEach { element ->
                val epId = element.attr("data-epid").ifBlank { firstEpisodeId }
                if (epId.isBlank()) return@forEach
                val text = element.text().trim()
                val epNumber = parseEpisodeNumber(text)
                val server = element.attr("data-server_xid").ifBlank { serverXid }
                val payload = config.toPayload(
                    episodeId = epId,
                    serverXid = server,
                    episodeName = cleanEpisodeTitle(text, epNumber),
                    episodeNumber = epNumber
                )
                items["${payload.episodeId}-${payload.serverXid}-${payload.tag}"] = payload
            }
        }

        if (items.isEmpty() && firstEpisodeId.isNotBlank()) {
            val payload = config.toPayload(
                episodeId = firstEpisodeId,
                serverXid = serverXid,
                episodeName = if (config.mediaType == "movie") "Movie" else "Episode",
                episodeNumber = null
            )
            items["${payload.episodeId}-${payload.serverXid}-${payload.tag}"] = payload
        }

        return items.values.map { payload ->
            newEpisode(payload.toJson()) {
                name = payload.episodeName
                episode = payload.episodeNumber
                posterUrl = payload.posterUrl
            }
        }
    }

    private fun parseApiConfigs(
        document: Document,
        detailUrl: String,
        title: String,
        posterUrl: String?
    ): List<ApiConfig> {
        val decodedBlocks = linkedSetOf<String>()
        decodedBlocks.add(document.html())

        document.select("script").forEach { script ->
            val scriptText = script.data().ifBlank { script.html() }
            if (scriptText.contains("split('.')") || scriptText.contains("document.write")) {
                decodeDocumentWriteScript(scriptText)?.let(decodedBlocks::add)
            }
        }

        val results = linkedMapOf<String, ApiConfig>()
        decodedBlocks.forEach { block ->
            val c = block.extractJsVar("c") ?: "bfb1"
            val tRaw = block.extractJsVar("t") ?: return@forEach
            val t = tRaw.substringBefore("&").ifBlank { tRaw }
            val ver = Regex("""(?:^|&)ver=([^&'"]+)""")
                .find(tRaw)
                ?.groupValues
                ?.getOrNull(1)
                ?: block.extractJsVar("ver")
                ?: "37asy3iq"
            val cApiHost = block.extractJsVar("c_api_host") ?: "https://drakorkita.cc/c_api"
            val isMob = block.extractJsVar("is_mob") ?: "1"
            val isUc = block.extractJsVar("is_uc") ?: "0"

            val matches = Regex("""(?:initEpisodeList|loadEpisode)\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]""")
                .findAll(block)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()

            matches.forEach { (movieId, tag) ->
                val mediaType = if (
                    title.contains("Season", ignoreCase = true) ||
                    title.contains(Regex("""Episode\s+\d+\s*-\s*\d+""", RegexOption.IGNORE_CASE))
                ) "tv" else "movie"
                val config = ApiConfig(
                    detailUrl = detailUrl,
                    title = title,
                    posterUrl = posterUrl,
                    movieId = movieId,
                    tag = tag,
                    c = c,
                    t = t,
                    ver = ver,
                    cApiHost = cApiHost,
                    isMob = isMob,
                    isUc = isUc,
                    mediaType = mediaType
                )
                results["$movieId-$tag"] = config
            }
        }

        return results.values.toList()
    }

    private fun decodeDocumentWriteScript(script: String): String? {
        val encoded = Regex("""(?:var\s+)?[A-Za-z_$][A-Za-z0-9_$]*\s*=\s*['"]([A-Za-z0-9+/=._-]+(?:\.[A-Za-z0-9+/=._-]+)+)['"]""")
            .findAll(script)
            .map { it.groupValues[1] }
            .firstOrNull { it.count { char -> char == '.' } > 10 }
            ?: return null

        val decoded = encoded.split(".").mapNotNull { segment ->
            val raw = decodeBase64Segment(segment) ?: return@mapNotNull null
            val digits = raw.replace(Regex("""\D"""), "")
            digits.toIntOrNull()?.toChar()
        }.joinToString("")

        return decoded
            .replace("\\/", "/")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
    }

    private fun decodeBase64Segment(value: String): String? {
        return runCatching {
            val padded = value.padEnd(value.length + ((4 - value.length % 4) % 4), '=')
            String(Base64.getDecoder().decode(padded))
        }.getOrNull()
    }

    private fun cleanCardTitle(raw: String): String {
        return raw.trim()
            .replace(Regex("""^\d{1,2}:\d{2}(:\d{2})?\s*"""), "")
            .replace(Regex("""^\s*Nonton\s+Drama\s+Korea\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\s*Streaming\s+Drama\s+Korea\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\s*Nonton\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s+Indo.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(1080p|720p|480p|2160p).*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+diperbarui\s*:.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+E\d+(/\d+|\s*END)?.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+WEB\s*.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\d+(\.\d+)?$"""), "")
            .trim()
    }

    private fun cleanEpisodeTitle(text: String, episode: Int?): String {
        val cleaned = text.trim()
            .replace("unnamed", "", ignoreCase = true)
            .trim()
        return cleaned.ifBlank { episode?.let { "Episode $it" } ?: "Movie" }
    }

    private fun parseEpisodeNumber(text: String): Int? {
        return Regex("""\b(?:Episode|Eps|EP|E)\s*(\d+)\b""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.select("a[href*='page=${page + 1}'], a:matchesOwn((?i)next|berikut|selanjutnya)").isNotEmpty()
    }

    private fun String.extractJsVar(name: String): String? {
        return Regex("""var\s+${Regex.escape(name)}\s*=\s*['"]([^'"]*)['"]""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private data class ApiConfig(
        val detailUrl: String,
        val title: String,
        val posterUrl: String?,
        val movieId: String,
        val tag: String,
        val c: String,
        val t: String,
        val ver: String,
        val cApiHost: String,
        val isMob: String,
        val isUc: String,
        val mediaType: String
    ) {
        fun toPayload(
            episodeId: String = "",
            serverXid: String = "",
            episodeName: String = if (mediaType == "movie") "Movie" else "Episode",
            episodeNumber: Int? = null
        ): EpisodePayload {
            return EpisodePayload(
                detailUrl = detailUrl,
                title = title,
                posterUrl = posterUrl,
                movieId = movieId,
                episodeId = episodeId,
                serverXid = serverXid,
                tag = tag,
                c = c,
                t = t,
                ver = ver,
                cApiHost = cApiHost,
                isMob = isMob,
                isUc = isUc,
                mediaType = mediaType,
                episodeName = episodeName,
                episodeNumber = episodeNumber
            )
        }

        fun toPayloadJson(): String = toPayload().toJson()
    }

    private data class EpisodePayload(
        val detailUrl: String,
        val title: String,
        val posterUrl: String?,
        val movieId: String,
        val episodeId: String,
        val serverXid: String,
        val tag: String,
        val c: String,
        val t: String,
        val ver: String,
        val cApiHost: String,
        val isMob: String,
        val isUc: String,
        val mediaType: String,
        val episodeName: String,
        val episodeNumber: Int?
    ) {
        fun toJson(): String {
            return JSONObject()
                .put("detailUrl", detailUrl)
                .put("title", title)
                .put("posterUrl", posterUrl ?: "")
                .put("movieId", movieId)
                .put("episodeId", episodeId)
                .put("serverXid", serverXid)
                .put("tag", tag)
                .put("c", c)
                .put("t", t)
                .put("ver", ver)
                .put("cApiHost", cApiHost)
                .put("isMob", isMob)
                .put("isUc", isUc)
                .put("mediaType", mediaType)
                .put("episodeName", episodeName)
                .put("episodeNumber", episodeNumber ?: JSONObject.NULL)
                .toString()
        }

        fun toResolverPayload(): DrakorKitaResolver.ApiPayload {
            return DrakorKitaResolver.ApiPayload(
                detailUrl = detailUrl,
                title = title,
                movieId = movieId,
                episodeId = episodeId,
                serverXid = serverXid,
                tag = tag,
                c = c,
                t = t,
                ver = ver,
                cApiHost = cApiHost,
                isMob = isMob,
                isUc = isUc,
                mediaType = mediaType
            )
        }

        companion object {
            fun fromJson(data: String): EpisodePayload? {
                return runCatching {
                    val json = JSONObject(data)
                    EpisodePayload(
                        detailUrl = json.optString("detailUrl"),
                        title = json.optString("title"),
                        posterUrl = json.optString("posterUrl").takeIf { it.isNotBlank() },
                        movieId = json.optString("movieId"),
                        episodeId = json.optString("episodeId"),
                        serverXid = json.optString("serverXid"),
                        tag = json.optString("tag"),
                        c = json.optString("c"),
                        t = json.optString("t"),
                        ver = json.optString("ver"),
                        cApiHost = json.optString("cApiHost"),
                        isMob = json.optString("isMob", "1"),
                        isUc = json.optString("isUc", "0"),
                        mediaType = json.optString("mediaType"),
                        episodeName = json.optString("episodeName"),
                        episodeNumber = if (json.isNull("episodeNumber")) null else json.optInt("episodeNumber")
                    )
                }.getOrNull()?.takeIf {
                    it.cApiHost.isNotBlank() && it.movieId.isNotBlank() && it.tag.isNotBlank()
                }
            }
        }
    }
}
