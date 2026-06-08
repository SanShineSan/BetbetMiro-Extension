package com.sad25kag.drakorindo

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class DrakorIndoProvider : MainAPI() {
    override var mainUrl = "https://drakor.kita.mobi"
    override var name = "DrakorIndo"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    private val apiBase = "https://drakorkita.cc/c_api"

    private val baseHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
    )

    override val mainPage = mainPageOf(
        "" to "Latest Update",
        "all?media_type=movie" to "Movie",
        "all?media_type=tv" to "Series",
        "all?status=returning series" to "Ongoing",
        "all?status=ended" to "Complete",
        "all" to "All",
        "all?genre=Drama" to "Drama",
        "all?genre=Romance" to "Romance",
        "all?genre=Action" to "Action",
        "all?genre=Thriller" to "Thriller",
        "all?genre=Comedy" to "Comedy",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            buildPageUrl(request.data, page),
            headers = baseHeaders,
            referer = mainUrl,
        ).document

        val items = document.extractSearchResults()
            .distinctBy { it.url }
            .take(40)

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty() && document.hasNextPage(page),
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(clean, "UTF-8")
        val document = app.get(
            "$mainUrl/all?q=$encoded",
            headers = baseHeaders,
            referer = mainUrl,
        ).document

        val words = clean.split(Regex("\\s+"))
            .filter { it.length >= 2 }

        return document.extractSearchResults()
            .filter { response ->
                response.name.contains(clean, ignoreCase = true) ||
                    words.any { word -> response.name.contains(word, ignoreCase = true) }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = fixUrl(url)
        val document = app.get(
            pageUrl,
            headers = baseHeaders,
            referer = mainUrl,
        ).document

        val rawTitle = document.selectFirst("h1[itemprop=headline], .breadcrumb_last, h1, meta[property=og:title], meta[name=title]")
            ?.let { element -> element.attr("content").ifBlank { element.text() } }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Judul DrakorIndo tidak ditemukan")

        val poster = document.findPoster()
        val plot = document.findPlot()
        val tags = document.findTags()
        val year = document.findYear(rawTitle)
        val typeText = document.findInfoValue("Type")
        val isMovie = typeText.contains("Movie", ignoreCase = true) ||
            (!typeText.contains("TV", ignoreCase = true) && !typeText.contains("Series", ignoreCase = true) && rawTitle.hasYearSuffix())

        val sourceConfig = document.extractPlayerConfig(pageUrl)
            ?: throw ErrorLoadingException("Konfigurasi player DrakorKita tidak ditemukan")

        val episodePayload = runCatching { fetchEpisodePayload(sourceConfig) }.getOrNull()
        val firstEpisodeId = episodePayload?.optString("first_ep_id").orEmpty()
        val serverXid = episodePayload?.optString("server_xid").orEmpty().ifBlank { "f2" }
        val episodes = parseEpisodes(
            episodePayload?.optString("episode_lists").orEmpty(),
            sourceConfig.copy(episodeId = firstEpisodeId, serverXid = serverXid),
        )

        if (isMovie || episodes.isEmpty()) {
            val playData = sourceConfig.copy(episodeId = firstEpisodeId, serverXid = serverXid).pack()
            return newMovieLoadResponse(rawTitle, pageUrl, if (isMovie) TvType.Movie else TvType.AsianDrama, playData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }

        return newTvSeriesLoadResponse(rawTitle, pageUrl, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playerData = PlayerData.unpack(data)
            ?: runCatching {
                val pageUrl = fixUrl(data)
                app.get(pageUrl, headers = baseHeaders, referer = mainUrl).document.extractPlayerConfig(pageUrl)
            }.getOrNull()
            ?: return false

        val emitted = AtomicInteger(0)
        suspend fun emit(linkUrl: String, label: String, referer: String) {
            emitted.incrementAndGet()
            emitDirect(linkUrl, label, referer, callback)
        }

        val withEpisode = if (playerData.episodeId.isBlank()) {
            val episodePayload = runCatching { fetchEpisodePayload(playerData) }.getOrNull()
            playerData.copy(
                episodeId = episodePayload?.optString("first_ep_id").orEmpty(),
                serverXid = episodePayload?.optString("server_xid").orEmpty().ifBlank { playerData.serverXid.ifBlank { "f2" } },
            )
        } else {
            playerData
        }

        if (withEpisode.episodeId.isBlank()) return false

        val serverPayload = runCatching { fetchServerPayload(withEpisode) }.getOrNull()
        val serverData = serverPayload?.optJSONObject("data")
        val withServer = withEpisode.copy(
            serverXid = serverData?.optString("server_xid").orEmpty().ifBlank { withEpisode.serverXid.ifBlank { "f2" } },
            serverId = serverData?.optString("server_id").orEmpty().ifBlank { withEpisode.serverId.ifBlank { withEpisode.serverXid.ifBlank { "f2" } } },
            quality = serverData?.optString("qua").orEmpty().ifBlank { withEpisode.quality.ifBlank { "web" } },
        )

        val videoPayload = runCatching { fetchVideoPayload(withServer) }.getOrNull() ?: return false
        val fileText = videoPayload.optString("file").replace("\\/", "/")
        val subtitles = videoPayload.optString("subtitle")
        emitSubtitles(subtitles, withServer.detailUrl, subtitleCallback)

        fileText.extractQualityLinks().forEach { (quality, link) ->
            emit(link, quality, withServer.detailUrl)
        }

        if (emitted.get() == 0) {
            extractUrls(fileText, withServer.detailUrl)
                .filter { it.isPlayableCandidate() }
                .distinct()
                .forEach { link -> emit(link, qualityLabel(inferQuality(link)), withServer.detailUrl) }
        }

        if (emitted.get() == 0) {
            val rawText = videoPayload.toString().replace("\\/", "/")
            extractUrls(rawText, withServer.detailUrl)
                .filter { it.isPlayableCandidate() }
                .distinct()
                .forEach { link -> emit(link, qualityLabel(inferQuality(link)), withServer.detailUrl) }
        }

        return emitted.get() > 0
    }

    private suspend fun fetchEpisodePayload(data: PlayerData): JSONObject {
        val url = "${data.apiHost.ifBlank { apiBase }}/episode_mob.php" +
            "?is_mob=${data.isMob.ifBlank { "1" }}" +
            "&is_uc=${data.isUc.ifBlank { "0" }}" +
            "&movie_id=${encode(data.movieId)}" +
            "&tag=${encode(data.tag)}" +
            "&c=${encode(data.c)}" +
            "&t=${data.t}"

        return JSONObject(
            app.get(url, headers = apiHeaders(data.detailUrl), referer = data.detailUrl).text,
        )
    }

    private suspend fun fetchServerPayload(data: PlayerData): JSONObject {
        val url = "${data.apiHost.ifBlank { apiBase }}/server_mob.php" +
            "?is_mob=${data.isMob.ifBlank { "1" }}" +
            "&is_uc=${data.isUc.ifBlank { "0" }}" +
            "&episode_id=${encode(data.episodeId)}" +
            "&tag=${encode(data.tag)}" +
            "&server_xid=${encode(data.serverXid.ifBlank { "f2" })}" +
            "&c=${encode(data.c)}" +
            "&t=${data.t}"

        return JSONObject(
            app.get(url, headers = apiHeaders(data.detailUrl), referer = data.detailUrl).text,
        )
    }

    private suspend fun fetchVideoPayload(data: PlayerData): JSONObject {
        val url = "${data.apiHost.ifBlank { apiBase }}/video.php" +
            "?is_mob=${data.isMob.ifBlank { "1" }}" +
            "&is_uc=${data.isUc.ifBlank { "0" }}" +
            "&id=${encode(data.episodeId)}" +
            "&qua=${encode(data.quality.ifBlank { "web" })}" +
            "&server_id=${encode(data.serverId.ifBlank { data.serverXid.ifBlank { "f2" } })}" +
            "&tag=${encode(data.tag)}" +
            "&c=${encode(data.c)}" +
            "&t=${data.t}"

        return JSONObject(
            app.get(url, headers = apiHeaders(data.detailUrl), referer = data.detailUrl).text,
        )
    }

    private fun apiHeaders(referer: String): Map<String, String> {
        return baseHeaders + mapOf(
            "Accept" to "text/plain, */*; q=0.01",
            "Origin" to mainUrl,
            "Referer" to referer,
        )
    }

    private fun parseEpisodes(episodeHtml: String, baseData: PlayerData): List<com.lagradost.cloudstream3.Episode> {
        if (episodeHtml.isBlank()) return emptyList()

        val doc = Jsoup.parse(episodeHtml.replace("\\/", "/"), mainUrl)
        return doc.select("a[data-epid], a[onclick*='loadServer']")
            .mapIndexedNotNull { index, element ->
                val episodeId = element.attr("data-epid")
                    .ifBlank { Regex("loadServer\\('([^']+)'", RegexOption.IGNORE_CASE).find(element.attr("onclick"))?.groupValues?.getOrNull(1).orEmpty() }
                if (episodeId.isBlank()) return@mapIndexedNotNull null

                val tag = element.attr("data-tag").ifBlank { baseData.tag }
                val serverXid = element.attr("data-server_xid").ifBlank { baseData.serverXid.ifBlank { "f2" } }
                val nameText = element.text().cleanSpaces()
                val episodeNumber = extractEpisodeNumber(nameText) ?: (index + 1)
                val episodeName = when {
                    nameText.isBlank() || nameText.equals("unnamed", true) -> "HARDSUB INDO"
                    else -> nameText
                }

                newEpisode(baseData.copy(episodeId = episodeId, tag = tag, serverXid = serverXid).pack()) {
                    name = episodeName
                    episode = episodeNumber
                }
            }
            .distinctBy { it.data }
    }

    private fun Document.extractPlayerConfig(pageUrl: String): PlayerData? {
        val html = html()
        val decodedScripts = decodeInlineConfigScripts(html).joinToString("\n")
        val text = "$html\n$decodedScripts"
        val loadEpisode = LOAD_EPISODE_REGEX.find(text) ?: return null

        return PlayerData(
            detailUrl = pageUrl,
            movieId = loadEpisode.groupValues.getOrNull(1).orEmpty(),
            tag = loadEpisode.groupValues.getOrNull(2).orEmpty(),
            c = VAR_REGEX("c").find(text)?.groupValues?.getOrNull(1).orEmpty(),
            t = VAR_REGEX("t").find(text)?.groupValues?.getOrNull(1).orEmpty(),
            isMob = VAR_REGEX("is_mob").find(text)?.groupValues?.getOrNull(1).orEmpty().ifBlank { "1" },
            isUc = VAR_REGEX("is_uc").find(text)?.groupValues?.getOrNull(1).orEmpty().ifBlank { "0" },
            apiHost = VAR_REGEX("c_api_host").find(text)?.groupValues?.getOrNull(1).orEmpty().ifBlank { apiBase },
            quality = "web",
        )
    }

    private fun decodeInlineConfigScripts(html: String): List<String> {
        val decoded = mutableListOf<String>()
        INLINE_B64_REGEX.findAll(html).forEach { match ->
            val chunks = match.groupValues.getOrNull(1).orEmpty().split('.')
            if (chunks.size < 20) return@forEach

            val builder = StringBuilder()
            chunks.forEach { chunk ->
                val raw = runCatching {
                    String(Base64.decode(chunk, Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull().orEmpty()
                val code = raw.filter { it.isDigit() }.toIntOrNull() ?: return@forEach
                builder.append(code.toChar())
            }

            val script = builder.toString()
            if (script.contains("loadEpisode", ignoreCase = true) || script.contains("c_api_host", ignoreCase = true)) {
                decoded += script
            }
        }
        return decoded
    }

    private fun Document.extractSearchResults(): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = linkedSetOf<String>()

        select("a[href*='/detail/']").forEach { element ->
            val response = element.toSearchResult() ?: return@forEach
            if (seen.add(response.url)) results.add(response)
        }

        return results
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(attr("abs:href").ifBlank { attr("href") })
        if (!href.startsWith(mainUrl, true) || !href.contains("/detail/", true)) return null

        val container = parents().firstOrNull { parent ->
            parent.selectFirst("img[src], img[data-src], h2, h3, h4, .title, .jdl, .ml-title") != null
        } ?: this

        val rawTitle = listOf(
            attr("title"),
            selectFirst("img[alt]")?.attr("alt").orEmpty(),
            container.selectFirst("h1, h2, h3, h4, .title, .jdl, .ml-title, .card-title")?.text().orEmpty(),
            text(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        val title = rawTitle.cleanTitle().takeIf { it.length >= 2 } ?: return null
        val poster = container.selectFirst("img[src], img[data-src], img[data-lazy-src]")
            ?.let { img -> img.attr("abs:data-src").ifBlank { img.attr("abs:data-lazy-src") }.ifBlank { img.attr("abs:src") } }
            ?.takeIf { it.isNotBlank() }

        val cardText = container.text()
        val isMovie = cardText.contains("Movie", ignoreCase = true) || title.hasYearSuffix()
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = poster }
        }
    }

    private fun Document.findPoster(): String? {
        return selectFirst("meta[property=og:image], meta[name=twitter:image], .bigcontent .thumb img, .animefull img, img[src*='image.tmdb.org']")
            ?.let { element ->
                element.attr("content")
                    .ifBlank { element.attr("abs:data-src") }
                    .ifBlank { element.attr("abs:data-lazy-src") }
                    .ifBlank { element.attr("abs:src") }
            }
            ?.takeIf { it.isNotBlank() }
    }

    private fun Document.findPlot(): String? {
        return selectFirst(".sinopsis .desc-wrap, .sinopsis p, .mv-description .desc-wrap, meta[property=og:description]")
            ?.let { element -> element.attr("content").ifBlank { element.text() } }
            ?.replace("Selengkapnya", "")
            ?.cleanSpaces()
            ?.takeIf { it.length >= 20 }
    }

    private fun Document.findTags(): List<String> {
        return select(".gnr a, .breadcrumb a[href*='genre='], a[href*='genre=']")
            .map { it.text().cleanSpaces() }
            .filter { it.isNotBlank() && !it.isMenuNoise() }
            .distinct()
    }

    private fun Document.findInfoValue(label: String): String {
        return select("ul.anf li").firstOrNull { item ->
            item.selectFirst("b")?.text()?.equals(label, ignoreCase = true) == true
        }?.text()?.substringAfter(":", "")?.cleanSpaces().orEmpty()
    }

    private fun Document.findYear(title: String): Int? {
        return Regex("\\((\\d{4})\\)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: findInfoValue("Release Date").let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        return select("a[href*='page=${page + 1}'], .pagination a, a:contains(Next), a:contains(›), a:contains(»)").isNotEmpty()
    }

    private suspend fun emitDirect(
        mediaUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val fixedUrl = mediaUrl.replace("\\/", "/")
        val type = if (fixedUrl.isHlsLike()) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val quality = getQualityFromName(label).let { quality ->
            if (quality == Qualities.Unknown.value) inferQuality("$label $fixedUrl") else quality
        }

        callback.invoke(
            newExtractorLink(name, "$name ${qualityLabel(quality)}", fixedUrl, type) {
                this.referer = referer
                this.quality = quality
                this.headers = baseHeaders + mapOf(
                    "Accept" to "*/*",
                    "Origin" to mainUrl,
                    "Referer" to referer,
                )
            },
        )
    }

    private fun emitSubtitles(rawSubtitle: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit) {
        if (rawSubtitle.isBlank() || rawSubtitle.equals("off", true)) return
        extractUrls(rawSubtitle.replace("\\/", "/"), referer)
            .filter { it.endsWith(".vtt", true) || it.endsWith(".srt", true) || it.contains(".vtt?", true) || it.contains(".srt?", true) }
            .distinct()
            .forEach { subtitleCallback.invoke(SubtitleFile("Subtitle", it)) }
    }

    private fun String.extractQualityLinks(): List<Pair<String, String>> {
        val normalized = replace("\\/", "/")
        val results = mutableListOf<Pair<String, String>>()

        QUALITY_LINK_REGEX.findAll(normalized).forEach { match ->
            val label = match.groupValues.getOrNull(1).orEmpty().stripHtml().ifBlank { "Auto" }
            val url = match.groupValues.getOrNull(2).orEmpty().trim()
            if (url.startsWith("http", true)) results += label to url
        }

        if (results.isEmpty()) {
            extractUrls(normalized, mainUrl).forEach { url -> results += qualityLabel(inferQuality(url)) to url }
        }

        return results.distinctBy { it.second }
    }

    private fun extractUrls(text: String, referer: String): Set<String> {
        val normalized = text.replace("\\/", "/")
        val urls = linkedSetOf<String>()

        Regex("""https?://[^"'\s<>\\]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map { it.value.trim().trim('"', '\'', ',', ';', ')', ']', '}') }
            .map { fixUrl(it, referer) }
            .filter { it.startsWith("http", true) }
            .forEach { urls.add(it) }

        return urls
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val base = when {
            path.isBlank() -> mainUrl
            path.startsWith("http://", true) || path.startsWith("https://", true) -> path.trimEnd('/')
            else -> "$mainUrl/${path.trimStart('/')}"
        }

        if (page <= 1) return base
        if (path.isBlank()) return "$mainUrl/all?page=$page"

        val separator = if (base.contains("?")) "&" else "?"
        return "$base${separator}page=$page"
    }

    private fun fixUrl(url: String, referer: String = mainUrl): String {
        val clean = url.trim().replace("\\/", "/")
        return when {
            clean.isBlank() -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("/") -> getBaseUrl(referer).trimEnd('/') + clean
            else -> referer.substringBefore("?").substringBeforeLast("/").trimEnd('/') + "/" + clean
        }
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.authority}"
        }.getOrDefault(mainUrl)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun extractEpisodeNumber(text: String): Int? {
        return Regex("(?:Episode|EP|Eps?)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("\\b(\\d{1,4})\\b").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun inferQuality(value: String): Int {
        return when {
            value.contains("2160", true) || value.contains("4k", true) -> Qualities.P2160.value
            value.contains("1080", true) -> Qualities.P1080.value
            value.contains("720", true) -> Qualities.P720.value
            value.contains("480", true) -> Qualities.P480.value
            value.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityLabel(quality: Int): String {
        return if (quality == Qualities.Unknown.value) "Auto" else "${quality}p"
    }

    private fun String.isHlsLike(): Boolean {
        val host = runCatching { URI(this).host.orEmpty().lowercase() }.getOrDefault("")
        return contains(".m3u8", true) ||
            (contains("/v/", true) && (host.contains("handal.bid") || host.contains("uyeshare.cc")))
    }

    private fun String.isPlayableCandidate(): Boolean {
        val host = runCatching { URI(this).host.orEmpty().lowercase() }.getOrDefault("")
        return isHlsLike() ||
            contains(".mp4", true) ||
            host.contains("handal.bid") ||
            host.contains("drakor.bid") ||
            host.contains("uyeshare.cc") ||
            host.contains("filemoon", true) ||
            host.contains("streamtape", true) ||
            host.contains("dood", true) ||
            host.contains("vidhide", true) ||
            host.contains("mixdrop", true)
    }

    private fun String.cleanTitle(): String {
        return cleanSpaces()
            .replace(Regex("(?i)^Nonton\\s+"), "")
            .replace(Regex("(?i)\\s+Subtitle\\s+Indonesia.*$"), "")
            .replace(Regex("(?i)\\s+Sub\\s+Indo.*$"), "")
            .replace(Regex("(?i)\\s+(WEB[- ]?DL|HDTV|BluRay|HDRip|DVDRip).*$"), "")
            .replace(Regex("(?i)\\s+[-–|]\\s+DrakorKita.*$"), "")
            .replace(Regex("(?i)^Drama Korea\\s+"), "")
            .trim()
    }

    private fun String.cleanSpaces(): String = replace(Regex("\\s+"), " ").trim()

    private fun String.stripHtml(): String = Jsoup.parse(this).text().cleanSpaces()

    private fun String.isMenuNoise(): Boolean {
        val value = cleanSpaces().lowercase()
        return value in setOf("home", "movie", "series", "ongoing", "complete", "all", "social media", "facebook", "instagram")
    }

    private fun String.hasYearSuffix(): Boolean = Regex("\\(\\d{4}\\)").containsMatchIn(this)

    private data class PlayerData(
        val detailUrl: String,
        val movieId: String = "",
        val tag: String = "",
        val c: String = "",
        val t: String = "",
        val isMob: String = "1",
        val isUc: String = "0",
        val apiHost: String = "",
        val episodeId: String = "",
        val serverXid: String = "",
        val serverId: String = "",
        val quality: String = "web",
    ) {
        fun pack(): String {
            return listOf(detailUrl, movieId, tag, c, t, isMob, isUc, apiHost, episodeId, serverXid, serverId, quality)
                .joinToString("|") { it.replace("|", "%7C") }
        }

        companion object {
            fun unpack(data: String): PlayerData? {
                val parts = data.split("|").map { it.replace("%7C", "|") }
                if (parts.size < 8) return null
                return PlayerData(
                    detailUrl = parts.getOrNull(0).orEmpty(),
                    movieId = parts.getOrNull(1).orEmpty(),
                    tag = parts.getOrNull(2).orEmpty(),
                    c = parts.getOrNull(3).orEmpty(),
                    t = parts.getOrNull(4).orEmpty(),
                    isMob = parts.getOrNull(5).orEmpty().ifBlank { "1" },
                    isUc = parts.getOrNull(6).orEmpty().ifBlank { "0" },
                    apiHost = parts.getOrNull(7).orEmpty(),
                    episodeId = parts.getOrNull(8).orEmpty(),
                    serverXid = parts.getOrNull(9).orEmpty(),
                    serverId = parts.getOrNull(10).orEmpty(),
                    quality = parts.getOrNull(11).orEmpty().ifBlank { "web" },
                )
            }
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
        private val LOAD_EPISODE_REGEX = Regex("""loadEpisode\(['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        private val INLINE_B64_REGEX = Regex("""[A-Za-z_$][\w$]*\s*=\s*['"]([A-Za-z0-9+/=_-]+(?:\.[A-Za-z0-9+/=_-]+){20,})['"]""")
        private val QUALITY_LINK_REGEX = Regex("""\[([^\]]+)]\s*(https?://[^,\s"'<>]+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private fun VAR_REGEX(name: String) = Regex("""var\s+$name\s*=\s*['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)
    }
}
