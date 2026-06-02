package com.istarvin

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getExtractorApiFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MissAV : MainAPI() {
    override var mainUrl = "https://missav.live"
    override var name = "MissAV"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/dm169/en/weekly-hot?sort=weekly_views" to "Weekly Hot",
        "$mainUrl/dm263/en/monthly-hot?sort=views" to "Monthly Hot",
        "$mainUrl/en/new?sort=published_at" to "Newly Added",
        "$mainUrl/en/english-subtitle" to "English Subtitles",
        "$mainUrl/dm628/en/uncensored-leak" to "Uncensored Leak",
        "$mainUrl/dm150/en/fc2" to "FC2",
        "$mainUrl/dm35/en/madou" to "Madou",
        "$mainUrl/en/klive" to "K-Live",
        "$mainUrl/en/clive" to "C-Live",
        "$mainUrl/dm29/en/tokyohot" to "Tokyo Hot",
        "$mainUrl/dm1198483/en/heyzo" to "HEYZO",
        "$mainUrl/dm2469695/en/1pondo" to "1pondo",
        "$mainUrl/dm3959622/en/caribbeancom" to "Caribbeancom",
        "$mainUrl/dm48032/en/caribbeancompr" to "Caribbeancom Premium",
        "$mainUrl/dm3710098/en/10musume" to "10musume",
        "$mainUrl/dm1342558/en/pacopacomama" to "Pacopacomama",
        "$mainUrl/dm136/en/gachinco" to "Gachinco",
        "$mainUrl/dm29/en/xxxav" to "XXX-AV",
        "$mainUrl/dm24/en/marriedslash" to "Married Slash",
        "$mainUrl/dm20/en/naughty4610" to "Naughty 4610",
        "$mainUrl/dm22/en/naughty0930" to "Naughty 0930"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${separator}page=$page"

        val document = app.get(url).document

        val home = document.select("div.grid.grid-cols-2 > div, div.thumbnail.group")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = selectFirst("a[href*='/en/'], a[href*='/dm']") ?: return null
        val url = fixUrlNull(link.attr("abs:href")) ?: return null

        val baseTitle = selectFirst("div.my-2 a, div.title a, a.text-secondary")?.text()?.trim()
            ?: link.text().trim()

        if (baseTitle.isBlank()) return null

        val blacklist = listOf("Recent update", "Contact", "Support", "DMCA", "Home")
        if (blacklist.any { baseTitle.equals(it, ignoreCase = true) }) return null

        val isUncensored = (link.attr("alt") + link.attr("href") + this.outerHtml())
            .contains(Regex("uncensored[-_ ]?leak", RegexOption.IGNORE_CASE))

        val title = if (isUncensored && !baseTitle.startsWith("Uncensored - ", ignoreCase = true))
            "Uncensored - $baseTitle" else baseTitle

        val posterUrl = fixUrlNull(
            selectFirst("img")?.let { img ->
                img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
            }
        )

        if (posterUrl == null) return null

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = query.urlEncoded()
        val url = if (page == 1) {
            "${mainUrl}/en/search/${encodedQuery}"
        } else {
            "${mainUrl}/en/search/${encodedQuery}?page=$page"
        }

        val document = app.get(url).document

        val aramaCevap = document.select("div.grid.grid-cols-2 > div, div.thumbnail.group")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        return newSearchResponseList(aramaCevap, hasNext = aramaCevap.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.text-base")?.text()?.trim() ?: return null
        val code = title.substringBefore(" ")
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val year = document.selectFirst("time")?.text()?.split("-")?.firstOrNull()?.toIntOrNull()

        val tags = document.select("div.text-secondary:contains(genre) a").map {
            it.text().trim()
        }
        val actresses = document.select("div.text-secondary:contains(actress) a").map {
            Actor(it.text().trim())
        }
        return newMovieLoadResponse(title, url, TvType.NSFW, "$code$LOAD_DATA_SEPARATOR$url") {
            this.posterUrl = poster
            this.year = year
            this.tags = tags
            addActors(actresses)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val code = data.substringBefore(LOAD_DATA_SEPARATOR)
            .substringBefore(LEGACY_LOAD_DATA_SEPARATOR)
            .trim()
        val url = data.substringAfter(LOAD_DATA_SEPARATOR, data.substringAfter(LEGACY_LOAD_DATA_SEPARATOR))
            .trim()

        if (!url.startsWith("http")) return false

        val response = app.get(url).text
        val playlistUrls = extractSurritPlaylistUrls(response)
        var hasLinks = false

        playlistUrls.forEach { streamUrl ->
            val links = generateM3u8(
                source = name,
                streamUrl = streamUrl,
                referer = "$mainUrl/",
                headers = mapOf("Referer" to "$mainUrl/")
            )
            links.forEach { link ->
                hasLinks = true
                callback(link)
            }
        }

        if (hasLinks && code.isNotBlank()) {
            runCatching {
                getExtractorApiFromName("SubtitleCat").getUrl(
                    url = code,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        }

        return hasLinks
    }

    private fun extractSurritPlaylistUrls(response: String): List<String> {
        val sources = listOf(response, runCatching { getAndUnpack(response) }.getOrDefault(""))
        val directUrls = sources.flatMap { source ->
            val normalized = source.replace("\\/", "/")
            SURRIT_M3U8_REGEX.findAll(normalized).map { it.value }.toList()
        }

        val playlistIds = sources.flatMap { source ->
            val normalized = source.replace("\\/", "/")
            val directIds = SURRIT_ID_REGEX.findAll(normalized).map { it.groupValues[1] } +
                NINEYU_ID_REGEX.findAll(normalized).map { it.groupValues[1] }
            val packedIds = UUID_REGEX.findAll(normalized).mapNotNull { match ->
                val start = (match.range.first - 320).coerceAtLeast(0)
                val end = (match.range.last + 320).coerceAtMost(normalized.length)
                val context = normalized.substring(start, end)
                if (context.contains("surrit", ignoreCase = true) || context.contains("m3u8", ignoreCase = true)) {
                    match.value
                } else {
                    null
                }
            }
            (directIds + packedIds).toList()
        }

        return (directUrls + playlistIds.map { "https://surrit.com/$it/playlist.m3u8" })
            .map { it.substringBefore("\\") }
            .distinct()
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        private const val LOAD_DATA_SEPARATOR = "|MISSAV|"
        private const val LEGACY_LOAD_DATA_SEPARATOR = ":"
        private val UUID_REGEX = Regex("""[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}""", RegexOption.IGNORE_CASE)
        private val SURRIT_ID_REGEX = Regex("""https?://surrit\.com/([a-f0-9\-]{36})(?:/|$)""", RegexOption.IGNORE_CASE)
        private val NINEYU_ID_REGEX = Regex("""https?://nineyu\.com/([a-f0-9\-]{36})/seek/""", RegexOption.IGNORE_CASE)
        private val SURRIT_M3U8_REGEX = Regex("""https?://surrit\.com/[^\s'"<>]+?\.m3u8""", RegexOption.IGNORE_CASE)
    }
}