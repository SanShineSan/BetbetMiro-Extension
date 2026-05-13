package com.Yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class YunshanidProvider : MainAPI() {
    override var mainUrl = "https://yunshanid.site"
    override var name = "Yunshanid"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // 1. Membuat Tampilan Home yang Proporsional (Multiple Rows)
    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePageLists = mutableListOf<HomePageList>()

        // Mengambil kategori berdasarkan section yang ada di gambar
        // Kita cari heading-nya (misal: "Update Terbaru", "Movie Terbaru", dll)
        document.select(".block").forEach { section ->
            val title = section.selectFirst(".title-resizer h2, .title-block h3")?.text()?.trim() ?: "Terbaru"
            val items = section.select("article, .bs").mapNotNull {
                it.toSearchResult()
            }
            if (items.isNotEmpty()) {
                homePageLists.add(HomePageList(title, items))
            }
        }

        // Jika section di atas gagal, fallback ke list standar
        if (homePageLists.isEmpty()) {
            val items = document.select("article, .bs").mapNotNull { it.toSearchResult() }
            homePageLists.add(HomePageList("Beranda", items))
        }

        return HomePageResponse(homePageLists)
    }

    // 2. Fungsi Pencarian
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article, .bs").mapNotNull {
            it.toSearchResult()
        }
    }

    // 3. Mapping elemen ke SearchResult (Menangkap Label Ongoing/Quality)
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".tt, h2")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        // Mengambil label kualitas (HD) atau status (Ongoing) dari UI
        val quality = this.select(".quality").text().trim()
        val typeLabel = this.select(".type").text().toLowerCase()
        
        val type = if (typeLabel.contains("tv") || typeLabel.contains("series")) {
            TvType.TvSeries
        } else if (typeLabel.contains("anime")) {
            TvType.Anime
        } else {
            TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            // Menambahkan metadata kualitas jika ada
            addQuality(quality)
        }
    }

    // 4. Detail Page (Sinopsis & Episode)
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".poster img, .thumb img")?.attr("src")
        val plot = document.selectFirst(".entry-content p, .synopsis p")?.text()
        
        // Cek apakah ada daftar episode
        val episodes = document.select(".eplister li, .list-episode li").mapNotNull {
            val name = it.select(".epl-num, .ep-num").text() ?: "Episode"
            val epHref = it.select("a").attr("href") ?: return@mapNotNull null
            val date = it.select(".epl-date").text()
            Episode(epHref, name, date = date)
        }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // 5. Ekstraksi Link (Mengincar Iframe & Player)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Cari di player utama
        document.select("iframe").forEach {
            val src = it.attr("src")
            if (src.isNotEmpty()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // Cari di server alternatif (tab/dropdown)
        document.select(".mirror-option option, .nav-tabs li a").forEach {
            val embedUrl = it.attr("value").ifEmpty { it.attr("data-embed") }
            if (embedUrl.isNotEmpty() && embedUrl.startsWith("http")) {
                loadExtractor(embedUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}