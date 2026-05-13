package Yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.util.Collections

class YunshanidProvider : MainAPI() {
    override var mainUrl = "https://yunshanid.site"
    override var name = "Yunshanid"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // HEADERS LENGKAP: Agar dikira browser asli oleh server
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Connection" to "keep-alive"
    )

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "category/movie/page/%d/" to "Movie",
        "category/tv-series/page/%d/" to "TV Series",
        "category/anime/page/%d/" to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (page == 1) {
            request.data.replace("/page/%d/", "/").replace("page/%d/", "")
        } else {
            request.data.format(page)
        }
        val response = app.get("$mainUrl/$path", headers = commonHeaders)
        val document = response.document
        val homeList = document.select("article, .bs").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(listOf(HomePageList(request.name, homeList)), hasNext = homeList.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".tt, h2")?.text()?.trim() ?: return null
        val href = this@YunshanidProvider.fixUrl(selectFirst("a")?.attr("href") ?: return null)
        val poster = selectFirst("img")?.attr("src") ?: selectFirst("img")?.attr("data-src")
        val typeLabel = select(".type").text().lowercase()
        val type = when {
            typeLabel.contains("tv") || typeLabel.contains("series") -> TvType.TvSeries
            typeLabel.contains("anime") || typeLabel.contains("donghua") -> TvType.Anime
            else -> TvType.Movie
        }
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newAnimeSearchResponse(title, href, type) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query", headers = commonHeaders).document
            .select("article, .bs").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    private fun cleanupTitle(rawTitle: String): String = rawTitle
        .replace(Regex("^(Nonton\\s+|Download\\s+)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+Sub\\s+Indo.*$", RegexOption.IGNORE_CASE), "").trim()

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        val title = cleanupTitle(document.selectFirst("h1.entry-title, .entry-title")?.text() ?: "No Title")
        val poster = document.selectFirst(".poster img, .thumb img")?.attr("src")
        val plot = document.selectFirst(".entry-content p, .synopsis p")?.text()
        val tags = document.select(".genredesc a, .genre a").map { it.text().trim() }

        // PERBAIKAN SELECTOR EPISODE: Agar tidak dianggap Movie padahal Series
        val episodes = document.select(".eplister li, .list-episode li, .num-ep a").mapIndexedNotNull { index, it ->
            val epHref = this@YunshanidProvider.fixUrl(it.selectFirst("a")?.attr("href") ?: it.attr("href") ?: return@mapIndexedNotNull null)
            val epName = it.select(".ep-num, .epl-num").text().ifBlank { "Episode ${index + 1}" }
            newEpisode(epHref) { this.name = epName; this.episode = index + 1 }
        }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = poster; this.plot = plot; this.tags = tags }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) { this.posterUrl = poster; this.plot = plot; this.tags = tags }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // Pancing dengan headers yang sama agar iframe keluar
        val document = app.get(data, headers = commonHeaders).document
        var found = false
        val seen = Collections.synchronizedSet(hashSetOf<String>())
        
        coroutineScope {
            // Cek iframe dan tombol player
            document.select("iframe, .mirror-option option, .nav-tabs li a, #embed_holder iframe").map { element ->
                async {
                    val src = when {
                        element.tagName() == "iframe" -> element.attr("src") ?: element.attr("data-src") ?: element.attr("data-litespeed-src")
                        element.tagName() == "option" -> element.attr("value")
                        else -> element.attr("data-embed") ?: element.attr("data-src")
                    }
                    
                    if (!src.isNullOrBlank() && src.startsWith("http") && seen.add(src)) {
                        runCatching { 
                            // Masukkan referer halaman episode agar tidak 403
                            loadExtractor(src, data, subtitleCallback) { link -> 
                                found = true; callback.invoke(link) 
                            } 
                        }
                    }
                }
            }.awaitAll()
        }
        return found
    }
}
