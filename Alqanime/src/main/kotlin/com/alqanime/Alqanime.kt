package com.alqanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Alqanime : MainAPI() {
    override var mainUrl = "https://alqanime.net"
    override var name = "Alqanime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType = when {
            t.contains("Movie", true) -> TvType.AnimeMovie
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        fun getStatus(t: String): ShowStatus = when {
            t.contains("Completed", true) || t.contains("Tamat", true) -> ShowStatus.Completed
            t.contains("Ongoing", true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Rilisan Terbaru",
        "$mainUrl/advanced-search/page/%d/?status=ongoing&order=update" to "Sedang Tayang",
        "$mainUrl/advanced-search/page/%d/?status=completed&order=update" to "Selesai Tayang",
        "$mainUrl/advanced-search/page/%d/?type[]=movie&order=update" to "Film",
        "$mainUrl/popular/page/%d/" to "Popular",
        "$mainUrl/tag/action/page/%d/" to "Action",
        "$mainUrl/tag/romance/page/%d/" to "Romance"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data.format(page), headers = commonHeaders).document
        val home = document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val title = (this.selectFirst(".tt") ?: this.selectFirst(".ntitle"))?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))
        val epNum = this.selectFirst(".epx, .adds")?.text()?.let { 
            Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() 
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus("Sub Indo", epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = commonHeaders).document
        return document.select("article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = commonHeaders).document

        val title = (document.selectFirst("h1.entry-title") ?: document.selectFirst(".infox h1"))
            ?.text()?.trim()
            ?.replace(Regex("\\s*\\(Episode.*?\\)", RegexOption.IGNORE_CASE), "")
            ?: return null

        val poster = document.selectFirst("div.thumb img, meta[property='og:image']")
            ?.let { it.attr("src").ifBlank { it.attr("content") } }

        val description = (document.selectFirst("div.entry-content") ?: document.selectFirst(".sinopsis"))
            ?.text()
            ?.trim()

        val typeText = document.selectFirst(".spe, .info-content")?.text().orEmpty()
        val type = getType(typeText)
        val status = getStatus(typeText)

        val episodes = mutableListOf<Episode>()

        // Mencari daftar episode di list standar
        val epLinks = document.select(".eplister ul li a")
        if (epLinks.isNotEmpty()) {
            epLinks.reversed().forEachIndexed { i, a ->
                val href = fixUrlNull(a.attr("href")) ?: return@forEachIndexed
                val name = a.selectFirst(".epl-num")?.text() ?: "Episode ${i + 1}"
                episodes.add(
                    newEpisode(href) {
                        this.name = name
                        this.episode = i + 1
                    }
                )
            }
        } 
        
        // Fallback jika tidak ada daftar episode (misal: Halaman Film/Movie langsung)
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = title
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = description
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data, headers = commonHeaders).document

        // Mengambil semua link video dari iframe atau tombol download
        document.select("iframe[src], .mirror option, a[href*='embed']").forEach { el ->
            var url = el.attr("src").ifBlank { el.attr("value") }.ifBlank { el.attr("href") }
            
            if (url.contains("base64")) {
                try {
                    url = java.net.URLDecoder.decode(url.substringAfter("r="), "UTF-8")
                    if (url.contains("http").not()) {
                         url = String(android.util.Base64.decode(url, android.util.Base64.DEFAULT))
                    }
                } catch (e: Exception) { }
            }

            if (url.startsWith("http") || url.startsWith("//")) {
                loadExtractor(fixUrl(url), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
