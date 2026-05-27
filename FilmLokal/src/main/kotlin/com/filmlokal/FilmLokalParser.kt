package com.filmlokal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object FilmLokalParser {

    private const val TAG = "FilmLokalParser"

    fun parseListing(html: String?): List<SearchResponse> {
        val doc = org.jsoup.parse(html.orEmpty())
        val container = doc.selectFirst("div.row, div#primary, div.site-content")
            ?: return emptyList()

        return container.select("article.post, div.post").mapNotNull { el ->
            el.toSearchResponse()
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleEl = selectFirst("h2.entry-title a, h2 a, h3 a, a:has(h2)")
            ?: return null
        val title = titleEl.text().orEmpty()
        val href = titleEl.attr("href")

        // Taktik Aggressive buat nyari poster di listing
        val poster = selectFirst("img.attachment-post-thumbnail, img.attachment-medium, img.attachment-large, img")
            ?.attr("data-src")
            ?: selectFirst("img")?.attr("lazy-src")
            ?: selectFirst("img")?.attr("src")
            ?: return null

        val isTv = selectFirst("span.series, .type-series") != null
        val type = if (isTv) TvType.TvSeries else TvType.Movie

        return if (isTv) {
            newTvSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    // --- BAGIAN DETAIL YANG DI-FIX TOTAL ---
    fun parseDetail(doc: Document, url: String): LoadResponse {
        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.orEmpty()
            ?: "Unknown"
        val plot = doc.selectFirst("div.entry-content, div.synopsis, div.content")?.text()
            ?.trim()

        // TAKTIK "AGGRESSIVE FALLBACK SEARCH" BUAT POSTER DETAIL
        // Kita cari poster dengan selector yang paling spesifik dulu, baru mundur ke yang generik.
        val posterUrl = doc.selectFirst("div.heroImg img")?.attr("src")
            // Selector WordPress/Generic Fallback
            ?: doc.selectFirst("img.attachment-post-thumbnail, img.attachment-single-post-thumbnail")?.attr("src")
            ?: doc.selectFirst("img.size-full, img.size-large")?.attr("src")
            // Selector WordPress/Schema.org Fallback
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            // Selector Brute-Force terakhir: ambil tag img yang pertama kali ketemu di konten utama
            ?: doc.selectFirst("div.site-content img")?.attr("src")
            ?: doc.selectFirst("div.content img")?.attr("src")
            // Paling mentok: ambil img tag yang pertama kali di dokumen
            ?: doc.selectFirst("img")?.attr("src")

        val tagsEl = doc.select("span.tags-links a, span.cat-links a")
        val tags = tagsEl.map { it.text() }

        val type = if (doc.selectFirst("span.series, div.post.type-series") != null) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            // Taktik Aggressive buat nyari episode series
            val episodes = doc.select("div.episodes a, div#primary article.post a").mapNotNull { el ->
                val epHref = el.attr("href")
                val epName = el.text()
                Episode(
                    data = epHref,
                    name = epName,
                )
            }.reversed() // Biasanya dibalik biar episode 1 di atas

            newTvLoadResponse(title, url, type, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
            }
        }
    }
}
