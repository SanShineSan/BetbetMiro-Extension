package Yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class YunshanidProvider : MainAPI() {

    override var mainUrl = "https://yunshanid.site"
    override var name = "Yunshanid"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10)",
        "Referer" to "$mainUrl/"
    )

    // ----------------------------
    // SMART SELECTOR SYSTEM
    // ----------------------------

    private fun Element.sText(vararg sel: String): String? {
        for (s in sel) {
            val t = selectFirst(s)?.text()
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    private fun Element.sAttr(attr: String, vararg sel: String): String? {
        for (s in sel) {
            val v = selectFirst(s)?.attr(attr)
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    // ----------------------------
    // MAIN PAGE
    // ----------------------------

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "category/movie/page/%d/" to "Movie",
        "category/tv-series/page/%d/" to "TV Series",
        "category/anime/page/%d/" to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (page == 1)
            request.data.replace("/page/%d/", "/")
        else
            request.data.format(page)

        val doc = app.get("$mainUrl/$path", headers = commonHeaders).document

        val list = doc.select("article, .bs, .post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name, list)),
            hasNext = list.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val title = sText(".tt", "h2", ".title", ".post-title") ?: return null

        val href = fixUrl(
            selectFirst("a")?.attr("href") ?: return null
        )

        val poster = sAttr("src", "img", ".thumb img", ".poster img")
            ?: selectFirst("img")?.attr("data-src")

        val typeText = select(".type, .label, .cat").text().lowercase()

        val type = when {
            typeText.contains("tv") || typeText.contains("series") -> TvType.TvSeries
            typeText.contains("anime") || typeText.contains("donghua") -> TvType.Anime
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    // ----------------------------
    // SEARCH
    // ----------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query", headers = commonHeaders)
            .document
            .select("article, .bs, .post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // ----------------------------
    // LOAD
    // ----------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = commonHeaders).document

        val title =
            doc.selectFirst("h1, .entry-title")?.text()
                ?: "No Title"

        val poster =
            doc.selectFirst(".poster img, .thumb img, img")?.attr("src")

        val plot =
            doc.selectFirst(".entry-content p, .synopsis p, .desc")?.text()

        val episodes = doc.select(".eplister li a, .list-episode li a, .num-ep a")
            .mapIndexedNotNull { i, it ->
                val epUrl = fixUrl(it.attr("href"))
                val epName = it.text().ifBlank { "Episode ${i + 1}" }

                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = i + 1
                }
            }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // ----------------------------
    // LOAD LINKS (ULTIMATE ENGINE)
    // ----------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data, headers = commonHeaders).document

        val seen = hashSetOf<String>()
        var found = false

        val priority = listOf(
            "filemoon",
            "streamwish",
            "gofile",
            "mp4upload",
            "voe"
        )

        fun extract(url: String) {
            if (!url.startsWith("http")) return
            if (!seen.add(url)) return

            runCatching {
                loadExtractor(url, data, subtitleCallback) {
                    found = true
                    callback(it)
                }
            }
        }

        val sources = doc.select(
            "iframe[src], iframe[data-src], option[value], a[href*='http']"
        )

        // PASS 1: priority sources
        for (s in sources) {
            val url = s.attr("src")
                .ifBlank { s.attr("data-src") }
                .ifBlank { s.attr("value") }

            if (priority.any { url.contains(it) }) {
                extract(url)
            }
        }

        // PASS 2: fallback all
        for (s in sources) {
            val url = s.attr("src")
                .ifBlank { s.attr("data-src") }
                .ifBlank { s.attr("value") }

            extract(url)
        }

        return found
    }
}