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
        "all?status=returning%20series" to "Ongoing",
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

        val rawTitle = listOf(
            document.extractMovieTitleFromScripts(),
            document.selectFirst(".breadcrumb_last")?.text(),
            document.selectFirst("h1[itemprop=headline], h1")?.text(),
            document.selectFirst("meta[property=og:title], meta[name=title]")?.attr("content"),
        ).firstNotNullOfOrNull { value ->
            value?.cleanTitle()?.takeIf { it.isNotBlank() }
        } ?: throw ErrorLoadingException("Judul DrakorIndo tidak ditemukan")

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

        suspend fun emitVideoPayload(videoPayload: JSONObject, referer: String) {
            emitSubtitles(videoPayload.optString("subtitle"), referer, subtitleCallback)

            val candidates = linkedMapOf<String, String>()
            val fileText = videoPayload.optString("file").replace("\\/", "/")
            fileText.extractQualityLinks().forEach { (quality, link) ->
                candidates[link.cleanPlayableUrl()] = quality.stripHtml().ifBlank { qualityLabel(inferQuality(link)) }
            }

            if (candidates.isEmpty()) {
                val rawText = videoPayload.toString().replace("\\/", "/")
                extractUrls(rawText, referer)
                    .filter { it.isPlayableCandidate() }
                    .forEach { link -> candidates[link.cleanPlayableUrl()] = qualityLabel(inferQuality(link)) }
            }

            for ((link, label) in candidates) {
                if (link.isBlank() || !link.isPlayableCandidate()) continue
                if (link.isHlsLike()) {
                    if (!validateHlsPlaylist(link, referer)) continue
                    emitted.incrementAndGet()
                    emitDirect(link, label, referer, callback)
                } else {
                    val before = emitted.get()
                    loadExtractor(link, referer, subtitleCallback) { linkOut ->
                        emitted.incrementAndGet()
                        callback.invoke(linkOut)
                    }
                    if (emitted.get() == before && link.isDirectMedia()) {
                        emitted.incrementAndGet()
                        emitDirect(link, label, referer, callback)
                    }
                }
            }
        }

        val episodePayload = runCatching { fetchEpisodePayload(playerData) }.getOrNull()
        val episodeId = playerData.episodeId.ifBlank { episodePayload?.optString("first_ep_id").orEmpty() }
        if (episodeId.isBlank()) return false

        val baseData = playerData.copy(
            episodeId = episodeId,
            serverXid = playerData.serverXid.ifBlank { episodePayload?.optString("server_xid").orEmpty() },
        )

        val serverXids = linkedSetOf<String>()
        if (baseData.serverXid.isNotBlank()) serverXids += baseData.serverXid
        parseEpisodeServerXids(episodePayload?.optString("episode_lists").orEmpty()).forEach { serverXids += it }
        serverXids += listOf("f1", "f2")

        for (serverXid in serverXids.filter { it.isNotBlank() }) {
            val serverPayload = runCatching { fetchServerPayload(baseData.copy(serverXid = serverXid)) }.getOrNull() ?: continue
            val choices = parseServerChoices(serverPayload, baseData.copy(serverXid = serverXid))
            for (choice in choices) {
                val videoData = baseData.copy(
                    serverXid = choice.serverXid,
                    serverId = choice.serverId,
                    quality = choice.quality,
                    tag = choice.tag.ifBlank { baseData.tag },
                )
                val videoPayload = runCatching { fetchVideoPayload(videoData) }.getOrNull() ?: continue
                emitVideoPayload(videoPayload, baseData.detailUrl)
                if (emitted.get() > 0) return true
            }
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
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
        )
    }

    private fun mediaHeaders(referer: String): Map<String, String> {
        return baseHeaders + mapOf(
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to referer,
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
        )
    }

    private suspend fun validateHlsPlaylist(url: String, referer: String): Boolean {
        if (!url.isHlsLike()) return true
        return runCatching {
            val response = app.get(url, headers = mediaHeaders(referer), referer = referer)
            response.text.trimStart().startsWith("#EXTM3U", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun parseEpisodeServerXids(episodeHtml: String): List<String> {
        if (episodeHtml.isBlank()) return emptyList()
        val doc = Jsoup.parse(episodeHtml.replace("\\/", "/"), mainUrl)
        return doc.select("a[data-server_xid]")
            .map { it.attr("data-server_xid") }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun parseServerChoices(payload: JSONObject, baseData: PlayerData): List<ServerChoice> {
        val choices = linkedMapOf<String, ServerChoice>()
        val data = payload.optJSONObject("data")
        if (data != null) {
            val serverId = data.optString("server_id").ifBlank { data.optString("server_xid") }.ifBlank { baseData.serverXid }
            val quality = data.optString("qua").ifBlank { baseData.quality.ifBlank { "web" } }
            val label = data.optString("svname").ifBlank { "Server $serverId" }
            if (serverId.isNotBlank()) {
                val choice = ServerChoice(
                    serverXid = data.optString("server_xid").ifBlank { baseData.serverXid.ifBlank { serverId } },
                    serverId = serverId,
                    quality = quality,
                    tag = data.optString("tag").ifBlank { baseData.tag },
                    label = label,
                )
                choices["${choice.serverId}|${choice.quality}"] = choice
            }
        }

        val serverList = payload.optString("server_lists").replace("\\/", "/")
        SERVER_CHOICE_REGEX.findAll(serverList).forEach { match ->
            val fn = match.groupValues.getOrNull(1).orEmpty().removePrefix("loadVideo").ifBlank { "Server" }
            val quality = match.groupValues.getOrNull(3).orEmpty().ifBlank { "web" }
            val resolution = match.groupValues.getOrNull(4).orEmpty()
            val serverId = match.groupValues.getOrNull(5).orEmpty()
            val tag = match.groupValues.getOrNull(6).orEmpty().ifBlank { baseData.tag }
            if (serverId.isBlank()) return@forEach
            val choice = ServerChoice(
                serverXid = serverId,
                serverId = serverId,
                quality = quality,
                tag = tag,
                label = listOf(fn, resolution).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Server $serverId" },
            )
            choices["${choice.serverId}|${choice.quality}"] = choice
        }

        if (choices.isEmpty() && baseData.serverXid.isNotBlank()) {
            val choice = ServerChoice(baseData.serverXid, baseData.serverXid, baseData.quality.ifBlank { "web" }, baseData.tag, "Server ${baseData.serverXid}")
            choices["${choice.serverId}|${choice.quality}"] = choice
        }
        return choices.values.toList()
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
                val serverXid = element.attr("data-server_xid").ifBlank { baseData.serverXid.ifBlank { "f1" } }
                val rawName = element.text().stripHtml().cleanSpaces()
                val episodeNumber = extractEpisodeNumber(rawName)
                    ?: Regex("epz-([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(element.attr("class"))?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)
                val episodeName = when {
                    rawName.isBlank() || rawName.equals("unnamed", true) || rawName == episodeNumber.toString() -> "Episode $episodeNumber"
                    rawName.matches(Regex("(?i)^(?:E|EP|Episode)?\\s*${episodeNumber}$")) -> "Episode $episodeNumber"
                    else -> rawName.cleanEpisodeTitle(episodeNumber)
                }

                newEpisode(baseData.copy(episodeId = episodeId, tag = tag, serverXid = serverXid).pack()) {
                    name = episodeName
                    episode = episodeNumber
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: 0 }
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
                val raw = listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP)
                    .firstNotNullOfOrNull { flag ->
                        runCatching { String(Base64.decode(chunk, flag), Charsets.UTF_8) }.getOrNull()
                    }
                    .orEmpty()
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

    private fun Document.extractMovieTitleFromScripts(): String? {
        val decoded = decodeInlineConfigScripts(html()).joinToString("\n")
        return Regex("""var\s+movie_title\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
    }

    private fun Document.extractSearchResults(): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = linkedSetOf<String>()

        select(".col-lg-8 .row .card a[href*='/detail/'], .card.mx-auto a[href*='/detail/'], a.poster[href*='/detail/']")
            .forEach { element ->
                val response = element.toSearchResult() ?: return@forEach
                if (seen.add(response.url)) results.add(response)
            }

        return results
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(attr("abs:href").ifBlank { attr("href") })
        if (!href.startsWith(mainUrl, true) || !href.contains("/detail/", true)) return null

        val card = parents().firstOrNull { parent -> parent.hasClass("card") } ?: this
        val hasListingAncestor = card.parents().any { parent ->
            parent.hasClass("col-lg-8") || parent.hasClass("row")
        }
        if (!hasListingAncestor) return null

        val title = extractCardTitle(card).takeIf { it.length >= 2 && !it.isMenuNoise() } ?: return null
        val poster = card.selectFirst("img[src], img[data-src], img[data-lazy-src]")
            ?.let { img -> img.attr("abs:data-src").ifBlank { img.attr("abs:data-lazy-src") }.ifBlank { img.attr("abs:src") } }
            ?.takeIf { it.isNotBlank() && !it.contains("favicon", true) }

        val cardText = card.text()
        val isMovie = cardText.contains(" WEB ", ignoreCase = true) ||
            cardText.contains("Movie", ignoreCase = true) ||
            (!cardText.contains(Regex("E\\d+", RegexOption.IGNORE_CASE)) && title.hasYearSuffix())

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = poster }
        }
    }

    private fun extractCardTitle(card: Element): String {
        val fromTitle = card.selectFirst("a[href*='/detail/'][title]")?.attr("title").orEmpty()
        val fromAlt = card.selectFirst("img[alt]")?.attr("alt").orEmpty()
        val fromNode = card.selectFirst(".card-title, .jdl, .title, h2, h3, h4")?.text().orEmpty()
        val fromText = card.selectFirst("a[href*='/detail/']")?.text().orEmpty()
        return listOf(fromTitle, fromAlt, fromNode, fromText)
            .firstNotNullOfOrNull { value -> value.cleanTitle().takeIf { it.isNotBlank() } }
            .orEmpty()
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
        return listOf(
            selectFirst(".sinopsis.mv-description .desc-wrap p")?.text(),
            selectFirst(".sinopsis .desc-wrap p")?.text(),
            selectFirst(".sinopsis.mv-description .desc-wrap")?.text(),
            selectFirst(".sinopsis .desc-wrap")?.text(),
        ).firstNotNullOfOrNull { value ->
            value?.replace("Selengkapnya", "")
                ?.cleanSpaces()
                ?.takeIf { it.length >= 20 && !it.isSeoDescription() }
        }
    }

    private fun Document.findTags(): List<String> {
        val tags = linkedSetOf<String>()
        select("ol.breadcrumb a[href*='genre='], .breadcrumb a[href*='genre=']")
            .map { it.text().cleanSpaces().trim(',') }
            .filter { it.isNotBlank() && !it.isMenuNoise() }
            .forEach { tag -> tags.add(tag) }

        select("ul.anf li").firstOrNull { item ->
            item.selectFirst("b")?.text()?.contains("Genre", ignoreCase = true) == true
        }?.select("a[href*='genre=']")
            ?.map { it.text().cleanSpaces().trim(',') }
            ?.filter { it.isNotBlank() && !it.isMenuNoise() }
            ?.forEach { tag -> tags.add(tag) }
        return tags.take(8)
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
                this.headers = mediaHeaders(referer)
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

        Regex("""https?://[^"'\s<>\\,\]\)]+""", RegexOption.IGNORE_CASE)
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
            (contains("/v/", true) && (host.contains("handal.bid") || host.contains("drakor.bid") || host.contains("uyeshare.cc")))
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
        return stripHtml()
            .replace(Regex("""(?i)^\d{1,2}:\d{2}(?::\d{2})?\s+"""), "")
            .replace(Regex("""(?i)^(?:Streaming|Nonton|Download|WATCH|Free download)\s+"""), "")
            .replace(Regex("""(?i)^Drama\s+Korea\s+"""), "")
            .replace(Regex("""(?i)^Film\s+"""), "")
            .replace(Regex("""(?i)\s+Subtitle\s+Indonesia.*$"""), "")
            .replace(Regex("""(?i)\s+Sub\s+Indo.*$"""), "")
            .replace(Regex("""(?i)\s+Episode\s+\d+(?:\s*[-–]\s*\d+)?.*$"""), "")
            .replace(Regex("""(?i)\s+E\d+(?:/\d+|\s*END)?.*$"""), "")
            .replace(Regex("""(?i)\s+(?:2160p|1080p|720p|480p|360p|4K)(?:,?\s*(?:2160p|1080p|720p|480p|360p|4K|&))*.*$"""), "")
            .replace(Regex("""(?i)\s+\[(?:SOFTSUB|HARDSUB).*?]"""), "")
            .replace(Regex("""(?i)\s+(WEB[- ]?DL|HDTV|BluRay|HDRip|DVDRip|WEB|HD)\b.*$"""), "")
            .replace(Regex("""(?i)\s+Full\s+Movie.*$"""), "")
            .replace(Regex("""(?i)\s+[-–|]\s+(?:DrakorKita|DrakorIndo|DramaQu|BioskopKeren).*$"""), "")
            .cleanSpaces()
            .trim('-', '–', '|', ',', ' ')
    }

    private fun String.cleanSpaces(): String = replace(Regex("\\s+"), " ").trim()

    private fun String.stripHtml(): String = Jsoup.parse(this).text().cleanSpaces()

    private fun String.cleanEpisodeTitle(episodeNumber: Int): String {
        val cleaned = cleanTitle()
        return when {
            cleaned.isBlank() || cleaned.equals("unnamed", true) -> "Episode $episodeNumber"
            cleaned == episodeNumber.toString() -> "Episode $episodeNumber"
            else -> cleaned
        }
    }

    private fun String.isSeoDescription(): Boolean {
        val value = lowercase()
        return value.contains("watch ") ||
            value.contains("free download") ||
            value.contains("subtitle indonesia gratis") ||
            value.contains("drakorindo,drakorkita") ||
            value.contains("bioskopkeren") ||
            value.startsWith("streaming ")
    }

    private fun String.cleanPlayableUrl(): String {
        return replace("\\/", "/")
            .trim()
            .trim('"', '\'', ',', ';', ')', ']', '}', '[')
    }

    private fun String.isDirectMedia(): Boolean {
        return contains(".m3u8", true) || contains(".mp4", true) || isHlsLike()
    }

    private fun String.isMenuNoise(): Boolean {
        val value = cleanSpaces().lowercase()
        return value in setOf("home", "movie", "series", "ongoing", "complete", "all", "social media", "facebook", "instagram")
    }

    private fun String.hasYearSuffix(): Boolean = Regex("\\(\\d{4}\\)").containsMatchIn(this)

    private data class ServerChoice(
        val serverXid: String,
        val serverId: String,
        val quality: String,
        val tag: String,
        val label: String,
    )

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
        private val QUALITY_LINK_REGEX = Regex("""\[([^\]]+)]\s*(https?://[^,\s"'<>\]\)]+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val SERVER_CHOICE_REGEX = Regex("""loadVideo([A-Za-z0-9_]+)\('([^']+)'\s*,\s*'([^']+)'\s*,\s*'([^']+)'\s*,\s*'([^']+)'\s*,\s*'([^']+)'(?:\s*,\s*'([^']*)')?\)""", RegexOption.IGNORE_CASE)
        private fun VAR_REGEX(name: String) = Regex("""var\s+$name\s*=\s*['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)
    }
}
