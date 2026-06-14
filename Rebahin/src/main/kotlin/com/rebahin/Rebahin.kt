package com.rebahin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

open class Rebahin : MainAPI() {
    companion object {
        // Single point of domain rotation. Update here when the site moves.
        const val DOMAIN = "https://rebahinxxi3.boats"

        val baseHeaders =
            mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            )
    }

    override var mainUrl = DOMAIN
    private var directUrl: String? = null
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    open var mainServer = DOMAIN
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    /**
     * Wraps app.get with up to [maxRetries] attempts, uniform headers, and a 30s timeout.
     * Returns null on exhaustion so callsites stay null-safe.
     */
    private suspend fun safeGet(
        url: String,
        referer: String? = "$mainUrl/",
        maxRetries: Int = 3,
    ): com.lagradost.nicehttp.NiceResponse? {
        var lastError: Throwable? = null
        repeat(maxRetries) { attempt ->
            try {
                return app.get(
                    url,
                    referer = referer,
                    headers = baseHeaders,
                    timeout = 30L,
                )
            } catch (t: Throwable) {
                lastError = t
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(700L * (attempt + 1))
                }
            }
        }
        logError(lastError ?: Exception("Rebahin safeGet failed: $url"))
        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val urls =
            listOf(
                Pair("Nonton Anime", "$mainUrl/genre/animation/"),
                Pair("Nonton Drama Barat", "$mainUrl/genre/westseries/"),
                Pair("Nonton Drama Jepang", "$mainUrl/genre/drama-jepang/"),
                Pair("Nonton Drama Korea", "$mainUrl/genre/drama-korea/"),
                Pair("Nonton Drama Cina", "$mainUrl/genre/drama-china/"),
                Pair("Nonton Drama Thailand", "$mainUrl/genre/thailand-series/"),
                Pair("Nonton Drama Indonesia", "$mainUrl/genre/series-indonesia/"),
                Pair("Nonton Drama Malaysia", "$mainUrl/genre/drama/series-malaysia/"),
                Pair("Nonton Film", "$mainUrl/movies/"),
                Pair("Nonton Bollywood", "$mainUrl/country/india/"),
            )

        val items =
            urls.amap { (header, url) ->
                val home =
                    safeGet(url)
                        ?.document
                        ?.select("div.ml-item")
                        ?.mapNotNull { it.toSearchResult() }
                        .orEmpty()
                if (home.isNotEmpty()) HomePageList(header, home) else null
            }.filterNotNull()

        if (items.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("span.mli-info > h2")?.text() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val type =
            if (this.select("span.mli-quality").isNotEmpty()) TvType.Movie else TvType.TvSeries
        return if (type == TvType.Movie) {
            val posterUrl = fixUrlNull(this.select("img").attr("src"))
            val quality = getQualityFromString(this.select("span.mli-quality").text().trim())
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            val posterUrl =
                fixUrlNull(
                    this.select("img").attr("src").ifEmpty {
                        this.select("img").attr("data-original")
                    },
                )
            val episode =
                this
                    .select("div.mli-eps > span")
                    .text()
                    .replace(Regex("[^0-9]"), "")
                    .toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document =
            safeGet("$mainUrl/?s=$encoded")?.document ?: return emptyList()
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val req = safeGet(url)
            ?: throw ErrorLoadingException("Rebahin page unreachable: $url")
        directUrl = getBaseUrl(req.url)
        val document = req.document
        val title = document.selectFirst("h3[itemprop=name]")?.ownText()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ").trim().ifBlank { "Untitled" }
        val poster =
            document
                .select(".mvic-desc > div.thumb.mvic-thumb")
                .attr("style")
                .substringAfter("url(")
                .substringBeforeLast(")")
                .ifBlank { null }
        val tags = document.select("span[itemprop=genre]").map { it.text() }

        val year =
            document
                .selectFirst(".mvici-right > p:nth-child(3)")
                ?.ownText()
                ?.trim()
                ?.let { Regex("([0-9]{4}?)-").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        val tvType = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie
        val description = document.select("span[itemprop=reviewBody] > p").text().trim()
        val trailer = fixUrlNull(document.selectFirst("div.modal-body-trailer iframe")?.attr("src"))
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()
        val duration =
            document
                .selectFirst(".mvici-right > p:nth-child(1)")
                ?.ownText()
                ?.replace(Regex("[^0-9]"), "")
                ?.toIntOrNull()
        val actors = document.select("span[itemprop=actor] > a").map { it.select("span").text() }

        val baseLink = fixUrlNull(document.select("div#mv-info > a").attr("href"))
            ?: throw ErrorLoadingException("Rebahin: no source link found on $url")

        return if (tvType == TvType.TvSeries) {
            val episodes =
                safeGet(baseLink)
                    ?.document
                    ?.select("div#list-eps > a")
                    ?.mapNotNull { eps ->
                        val episodeName = eps.text().trim().ifBlank { return@mapNotNull null }
                        val sourceUrl = decodeDataIframe(eps.attr("data-iframe")) ?: return@mapNotNull null
                        Pair(episodeName, sourceUrl)
                    }
                    ?.groupBy { it.first }
                    ?.map { eps ->
                        newEpisode(
                            encodeLinkData(eps.value.map { it.second }),
                        ) {
                            this.name = eps.key
                            this.episode = eps.key.filter { it.isDigit() }.toIntOrNull()
                        }
                    }.orEmpty()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val links =
                safeGet(baseLink)
                    ?.document
                    ?.select("div#server-list div.server-wrapper div[id*=episode]")
                    ?.mapNotNull { decodeDataIframe(it.attr("data-iframe")) }
                    .orEmpty()
            newMovieLoadResponse(title, url, TvType.Movie, encodeLinkData(links)) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val emitted = AtomicInteger(0)
        decodeLinkData(data).amap { link ->
            safeApiCall {
                resolveRebahinSource(link, subtitleCallback) { source ->
                    emitted.incrementAndGet()
                    callback.invoke(source)
                }
            }
        }
        return emitted.get() > 0
    }

    private suspend fun resolveRebahinSource(
        rawUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit,
    ): Boolean {
        val url = rawUrl.trim().trim('[', ']', '"').takeIf { it.isNotBlank() } ?: return false
        val host = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull().orEmpty()
        val ref = directUrl?.let { "$it/" } ?: "$mainServer/"
        val emitted = AtomicInteger(0)

        fun markEmit(source: ExtractorLink) {
            emitted.incrementAndGet()
            sourceCallback.invoke(source)
        }

        when {
            host == URI(mainServer).host || url.startsWith("$mainServer/iembed/", true) -> {
                resolveIembed(url)?.let { resolved ->
                    resolveRebahinSource(resolved, subtitleCallback) { markEmit(it) }
                }
            }

            host == "199.87.210.226" && url.contains("/player/", true) -> {
                invokeJuicyPlayer(url, subtitleCallback) { markEmit(it) }
            }

            host == "datura.groovy.monster" && url.contains(".m3u8", true) -> {
                emitM3u8(url, "https://199.87.210.226") { markEmit(it) }
            }

            else -> {
                loadExtractor(url, ref, subtitleCallback) { ext -> markEmit(ext) }
                if (emitted.get() == 0 && url.isDirectMedia()) {
                    val type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    markEmit(
                        newExtractorLink(name, name, url, type) {
                            this.referer = ref
                            this.quality = Qualities.Unknown.value
                            this.headers = baseHeaders + mapOf("Referer" to ref)
                        },
                    )
                }
            }
        }

        return emitted.get() > 0
    }

    private suspend fun resolveIembed(url: String): String? {
        val source = runCatching {
            URI(url).rawQuery
                ?.split("&")
                ?.firstOrNull { it.substringBefore("=") == "source" }
                ?.substringAfter("=", "")
                ?.let { URLDecoder.decode(it, "UTF-8") }
                ?.let { base64Decode(it) }
        }.getOrNull()

        if (!source.isNullOrBlank()) return fixUrl(source)

        val document = safeGet(url, referer = "$mainServer/")?.document ?: return null
        return document.selectFirst("iframe[src]")?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
    }

    private suspend fun invokeJuicyPlayer(
        url: String,
        subCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playerBase = getBaseUrl(url)
        val html = safeGet(url, referer = "$mainServer/")?.text ?: return false
        val juicyArg = Regex("_juicycodes\\(([\\s\\S]*?)\\);").find(html)?.groupValues?.getOrNull(1)
            ?: return false
        val payload = Regex("\"([^\"]*)\"").findAll(juicyArg).joinToString("") { it.groupValues[1] }
        val decoded = decodeJuicyCodes(payload) ?: return false
        val m3u8 = Regex("\"sources\"\\s*:\\s*\\{[\\s\\S]*?\"file\"\\s*:\\s*\"([^\"]+?\\.m3u8)\"")
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.cleanJsonUrl()
            ?: Regex("\"file\"\\s*:\\s*\"([^\"]+?\\.m3u8)\"")
                .findAll(decoded)
                .lastOrNull()
                ?.groupValues
                ?.getOrNull(1)
                ?.cleanJsonUrl()
            ?: return false

        emitM3u8(m3u8, playerBase, sourceCallback)
        emitSubtitles(decoded, subCallback)
        return true
    }

    private suspend fun emitM3u8(
        url: String,
        playerBase: String,
        sourceCallback: (ExtractorLink) -> Unit,
    ) {
        val fixedUrl = url.cleanJsonUrl()
        sourceCallback.invoke(
            newExtractorLink("$name Juicy", "$name Juicy", fixedUrl, ExtractorLinkType.M3U8) {
                this.referer = "$playerBase/"
                this.quality = Qualities.Unknown.value
                this.headers = baseHeaders + mapOf(
                    "Accept" to "*/*",
                    "Origin" to playerBase,
                    "Referer" to "$playerBase/",
                )
            },
        )
    }

    private fun decodeJuicyCodes(payload: String): String? = runCatching {
        if (payload.length <= 3) return@runCatching ""
        val salt = payload.takeLast(3).map { it.code - 100 }.joinToString("").toInt()
        var encoded = payload.dropLast(3).replace('_', '+').replace('-', '/')
        val padding = (4 - encoded.length % 4) % 4
        if (padding > 0) encoded += "=".repeat(padding)
        val decodedSymbols = base64Decode(encoded)
        val symbolMap = listOf('`', '%', '-', '+', '*', '$', '!', '_', '^', '=')
        val digits = buildString {
            decodedSymbols.forEach { symbol ->
                val index = symbolMap.indexOf(symbol)
                if (index >= 0) append(index)
            }
        }
        buildString {
            Regex(".{4}").findAll(digits).forEach { chunk ->
                append(((chunk.value.toInt() % 1000) - salt).toChar())
            }
        }
    }.getOrNull()

    private fun emitSubtitles(
        decodedConfig: String,
        subCallback: (SubtitleFile) -> Unit,
    ) {
        val subData = Regex("\"tracks\"\\s*:\\s*\\[([\\s\\S]*?)]").find(decodedConfig)
            ?.groupValues
            ?.getOrNull(1)
            ?: return
        tryParseJson<List<Tracks>>("[$subData]")?.map {
            subCallback.invoke(
                SubtitleFile(
                    getLanguage(it.label ?: return@map null),
                    if (it.file?.contains(".srt") == true) it.file.cleanJsonUrl() else return@map null,
                ),
            )
        }
    }

    private fun encodeLinkData(links: List<String>): String =
        links.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("|#|")

    private fun decodeLinkData(data: String): List<String> {
        val clean = data.trim().removeSurrounding("[", "]")
        val parts = if (clean.contains("|#|")) clean.split("|#|") else clean.split(",")
        return parts.map { it.trim().trim('"') }.filter { it.isNotBlank() }
    }

    private fun decodeDataIframe(data: String?): String? =
        data?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { fixUrl(base64Decode(raw.trim())) }.getOrNull()
        }

    private fun String.cleanJsonUrl(): String =
        this.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()

    private fun String.isDirectMedia(): Boolean =
        this.contains(".m3u8", true) || this.contains(".mp4", true)

    private fun getLanguage(str: String): String = when {
        str.contains("indonesia", true) || str.contains("bahasa", true) -> "Indonesian"
        else -> str
    }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    private data class Tracks(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
    )
}
