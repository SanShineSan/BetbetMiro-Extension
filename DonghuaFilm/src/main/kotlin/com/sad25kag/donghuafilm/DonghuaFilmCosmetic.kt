package com.sad25kag.donghuafilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Homepage-only cosmetic wrapper.
 * Detail, episode, search, and playback stay delegated to DonghuaFilm.
 */
class DonghuaFilmCosmetic : MainAPI() {
    private val delegate = DonghuaFilm()

    override var mainUrl = "https://donghuafilm.com"
    override var name = "DonghuaFilm"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.6",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "anime/?order=update&status=&type=" to "New Donghua",
        "anime/?order=update&status=completed&type=" to "Completed",
        "anime/?order=popular&status=&type=" to "Popular",
        "genres/action/" to "Action",
        "genres/adventure/" to "Adventure",
        "genres/fanstasy/" to "Fantasy",
        "genres/historical/" to "Historical",
        "genres/martial-arts/" to "Martial Arts",
        "genres/romance/" to "Romance",
        "genres/sci-fi/" to "Sci-Fi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document
        val cards = document.select("a[href*='/anime/']")
            .mapNotNull { it.toCleanCard() }
            .filterNot { it.name.equals("Text Mode", ignoreCase = true) }
            .distinctBy { it.url.trimEnd('/').lowercase(Locale.ROOT) }
        val hasNext = cards.isNotEmpty() && document.selectFirst(
            "a.next, .pagination a.next, a.next.page-numbers, link[rel=next], " +
                    "a[href*='/page/${page + 1}/'], a[href*='page=${page + 1}']"
        ) != null
        return newHomePageResponse(HomePageList(request.name, cards, isHorizontalImages = false), hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = delegate.quickSearch(query)

    override suspend fun search(query: String): List<SearchResponse> = delegate.search(query)

    override suspend fun load(url: String): LoadResponse = delegate.load(url)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = delegate.loadLinks(data, isCasting, subtitleCallback, callback)

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim().trimStart('/')
        val base = if (cleanPath.startsWith("http", true)) cleanPath.trimEnd('/')
        else "$mainUrl/$cleanPath".trimEnd('/')
        if (page <= 1) return if (base.contains("?")) base else "$base/"
        return when {
            base.contains("page=") -> base.replace(Regex("""page=\d+"""), "page=$page")
            base.contains("?") -> "$base&page=$page"
            else -> "$base/page/$page/"
        }
    }

    private fun Element.toCleanCard(): SearchResponse? {
        val href = attr("href").toAbsoluteUrl(mainUrl) ?: return null
        if (!href.startsWith(mainUrl) || !href.contains("/anime/", true)) return null
        val path = runCatching { URI(href).path }.getOrDefault(href).trim('/').lowercase(Locale.ROOT)
        if (path == "anime" || path.startsWith("anime/page/") || path.startsWith("genres/") ||
            path.startsWith("season/") || path.startsWith("studio/") || path.startsWith("network/") ||
            path.startsWith("country/")
        ) return null

        val rawTitle = attr("title").cleanText().takeIf { it.length > 2 }
            ?: selectFirst("h3, h4, .tt, .eggtitle, .title")?.text()?.cleanText()?.takeIf { it.length > 2 }
            ?: selectFirst("img")?.attr("alt")?.cleanText()?.takeIf { it.length > 2 }
            ?: text().cleanText().takeIf { it.length > 2 }
            ?: return null
        val title = rawTitle.cleanTitle() ?: return null
        if (title.equals("Text Mode", ignoreCase = true) || title.isBlockedTitle()) return null
        val poster = selectFirst("img")?.imageUrl(href)
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.posterHeaders = browserHeaders + mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun Element.imageUrl(base: String): String? {
        val raw = listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-thumb", "data-poster", "src", "poster")
            .firstNotNullOfOrNull { attr -> attr(attr).takeValidUrl() }
            ?: listOf("data-srcset", "data-lazy-srcset", "srcset")
                .firstNotNullOfOrNull { attr -> attr(attr).bestSrcFromSet() }
            ?: selectFirst("source[srcset]")?.attr("srcset")?.bestSrcFromSet()
        return raw?.toAbsoluteUrl(base)
    }

    private fun String.bestSrcFromSet(): String? = split(',')
        .mapNotNull { it.trim().substringBefore(" ").takeValidUrl() }
        .lastOrNull()

    private fun String.takeValidUrl(): String? {
        val value = trim().trim('"', '\'')
        if (value.isBlank() || value.startsWith("data:", true) || value.startsWith("#") || value.startsWith("javascript", true)) return null
        return value
    }

    private fun String.toAbsoluteUrl(base: String): String? {
        val clean = trim().trim('"', '\'')
        if (clean.isBlank() || clean.startsWith("javascript", true) || clean.startsWith("#")) return null
        return runCatching { URI(base).resolve(clean).toString() }.getOrNull()
    }

    private fun String.cleanTitle(): String? = cleanText()
        .replace(Regex("""(?i)\s*[-–|]\s*DonghuaFilm.*$"""), "")
        .replace(Regex("""(?i)^Donghua\s+"""), "")
        .replace(Regex("""(?i)\s+subtitle\s+indonesia.*$"""), "")
        .replace(Regex("""(?i)\s+subtitle\s+english.*$"""), "")
        .replace(Regex("""(?i)\s+sub\s+indo.*$"""), "")
        .takeIf { it.isNotBlank() }

    private fun String.cleanText(): String = Jsoup.parse(this).text()
        .replace("\u00a0", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.isBlockedTitle(): Boolean {
        val lower = lowercase(Locale.ROOT).trim()
        return lower in setOf(
            "donghuafilm", "layaranime", "home", "latest release", "new donghua",
            "popular today", "genre", "search", "privacy", "dmca", "schedule",
            "sedang populer", "sedang tayang", "movie terbaru", "text mode"
        )
    }
}
