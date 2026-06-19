@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.sad25kag.ayononton

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * AyoNonton Provider — ayononton.live
 *
 * Platform : WordPress + MuviPro/idmuvi theme (Terbit21 clone)
 * x-powered-by: TERBIT21.COM
 *
 * URL pattern  : https://ayononton.live/{slug}/
 * Listing      : /, /latest/, /populer/, /trending-minggu-ini/, /rating/, /release/
 * Genre        : /genre/{slug}/
 * Search       : /?s={query}  (pages: /page/N/?s={query})
 * Pagination   : /page/N/  or  /page/N/?s=...
 *
 * Card HTML (confirmed):
 *   <article class="item has-post-thumbnail" itemtype="https://schema.org/Movie">
 *     <a href="{url}" itemprop="url" title="Nonton Film: Title (Year)">
 *       <picture><source srcset="https://i2.wp.com/terbit21.com/wp-content/uploads/...">
 *       <img src="..." itemprop="image">
 *     </a>
 *   </article>
 *
 * Detail metadata (confirmed — <div class="gmr-moviedata">):
 *   Genre, Kualitas, Size, Tahun, Durasi, Negara, Rating
 *
 * Player (confirmed):
 *   POST https://t21.press/data.php  body: movie={slug}
 *   Response: HTML <a href="https://t21.press/play-ads.php?movie={slug}&iframe={server}">
 *   Servers observed: DRIVE (p2p), HYDRAX (g-hydrax), P2P (p2p), GDFRAME (gdframe)
 */
class AyoNonton : MainAPI() {

    override var mainUrl = "https://ayononton.live"
    override var name = "AyoNonton"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    // ──────────────────────────────────────────────────────────────
    // Main page sections (evidence: homepage nav links)
    // ──────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/latest/"                 to "Terbaru",
        "$mainUrl/populer/"                to "Populer",
        "$mainUrl/trending-minggu-ini/"    to "Trending Minggu Ini",
        "$mainUrl/rating/"                 to "Rating Tertinggi",
        "$mainUrl/genre/action/"           to "Aksi",
        "$mainUrl/genre/drama/"            to "Drama",
        "$mainUrl/genre/comedy/"           to "Komedi",
        "$mainUrl/genre/horror/"           to "Horor",
        "$mainUrl/genre/thriller/"         to "Thriller",
        "$mainUrl/genre/romance/"          to "Romansa",
        "$mainUrl/genre/animation/"        to "Animasi",
        "$mainUrl/genre/sci-fi/"           to "Sci-Fi",
    )

    // ──────────────────────────────────────────────────────────────
    // Helper: parse a listing page (returns SearchResponse list)
    // ──────────────────────────────────────────────────────────────
    private fun parseListingPage(doc: Document): List<SearchResponse> {
        // Cards confirmed: <article class="item has-post-thumbnail">
        return doc.select("article.item.has-post-thumbnail").mapNotNull { art ->
            // Link + title from <a itemprop="url">
            val anchor = art.selectFirst("a[itemprop=url]") ?: return@mapNotNull null
            val href   = anchor.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null

            // Title: strip "Nonton Film: " prefix if present (confirmed in crawl)
            val rawTitle = anchor.attr("title")
                .ifBlank { anchor.ownText() }
            val title = rawTitle
                .removePrefix("Nonton Film: ")
                .removePrefix("Nonton ")
                .trim()
                .ifBlank { href.trimEnd('/').substringAfterLast('/') }

            // Poster: prefer <source srcset> (WebP) → fallback <img src>
            val poster = art.selectFirst("source[srcset]")
                ?.attr("srcset")
                ?.split(",")
                ?.lastOrNull()
                ?.trim()
                ?.split(" ")
                ?.firstOrNull()
                ?: art.selectFirst("img[itemprop=image], img")
                    ?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }

            // Type detection: check title for series hints; default Movie
            val tvType = when {
                title.contains(Regex("(?i)\\bseason\\b|\\bs\\d+|\\bseries\\b|\\bepisode\\b")) -> TvType.TvSeries
                art.attr("itemtype").contains("TVSeries", ignoreCase = true) -> TvType.TvSeries
                else -> TvType.Movie
            }

            if (tvType == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Main page loader — paginated listing
    // ──────────────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Pagination: /page/N/ appended before trailing slash
        val url = if (page == 1) {
            request.data
        } else {
            // Insert page before trailing ?s= or at end
            request.data.trimEnd('/') + "/page/$page/"
        }

        val doc = app.get(url, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )).document

        val items = parseListingPage(doc)
        val hasMore = doc.selectFirst("a.next.page-numbers, .next a, .pagination .next") != null

        return newHomePageResponse(
            list     = HomePageList(request.name, items, isHorizontalImages = true),
            hasNext  = hasMore
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Search — GET /?s={query}  pages: /page/N/?s={query}
    // ──────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val url  = "$mainUrl/?s=${query.encodeUri()}"
        val doc  = app.get(url, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )).document
        return parseListingPage(doc)
    }

    // ──────────────────────────────────────────────────────────────
    // Load detail page
    // ──────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )).document

        // Slug (used for player API)
        val slug = url.trimEnd('/').substringAfterLast('/')

        // ── Title ──────────────────────────────────────────────
        // Confirmed: <h1 itemprop="name">Title (Year)</h1>
        val rawTitle = doc.selectFirst("h1[itemprop=name], h1.entry-title, h1")
            ?.text()?.trim() ?: slug

        // ── Poster ─────────────────────────────────────────────
        // Confirmed: og:image → ayononton.live/wp-content/uploads/...
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img[itemprop=image]")?.absUrl("src")

        // ── Description ────────────────────────────────────────
        val description = doc.selectFirst("div.entry-content.clearfix, div.entry-content, div#synopsis")
            ?.text()?.trim()

        // ── Metadata from gmr-moviedata divs ──────────────────
        // Evidence:  <div class="gmr-moviedata"><strong>Genre: </strong><a>...</a>...</div>
        //            <div class="gmr-moviedata"><strong>Tahun: </strong><a href=".../year/2026/">2026</a>...
        //            <div class="gmr-moviedata"><strong>Durasi: </strong><span property="duration">105 Min</span>
        val genres   = mutableListOf<String>()
        var year     : Int?    = null
        var duration : String? = null
        var rating   : String? = null

        doc.select("div.gmr-moviedata, div.gmr-movie-data").forEach { div ->
            val label = div.selectFirst("strong")?.text()?.lowercase()?.trim() ?: ""
            val value = div.text().removePrefix(div.selectFirst("strong")?.text() ?: "").trim()
            when {
                label.contains("genre")    -> div.select("a[href*=/genre/]").forEach { genres.add(it.text()) }
                label.contains("tahun")    -> year = value.take(4).toIntOrNull()
                label.contains("durasi")   -> duration = value
                label.contains("rating")   ->
                    rating = div.selectFirst("span[itemprop=ratingValue], .imdb-rating, span")
                        ?.text()?.trim()?.ifBlank { null }
            }
        }

        // Fallback: year from title "(YYYY)"
        if (year == null) {
            year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        }

        // Clean title (remove year suffix "(2026)")
        val title = rawTitle.replace(Regex("""\s*\(\d{4}\)\s*$"""), "").trim()
            .ifBlank { rawTitle }

        // ── Type detection ─────────────────────────────────────
        val isSeries = doc.select("div.gmr-moviedata a[href*=/genre/]")
            .any { it.text().contains(Regex("(?i)series|episode|season")) }
            || title.contains(Regex("(?i)\\bseason\\b|\\bseries\\b|\\bS\\d+\\b"))

        // ── Build data URL (used in loadLinks) ─────────────────
        // We pass the slug so loadLinks can call the t21.press API
        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                Episode(
                    data        = "$mainUrl/||$slug",
                    name        = title,
                    season      = 1,
                    episode     = 1,
                    posterUrl   = poster,
                    description = description,
                )
            )) {
                this.posterUrl    = poster
                this.plot         = description
                this.year         = year
                this.tags         = genres.distinct()
                this.rating       = rating?.toRatingInt()
                this.duration     = duration?.filter { it.isDigit() }?.toIntOrNull()
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/||$slug") {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = genres.distinct()
                this.rating    = rating?.toRatingInt()
                this.duration  = duration?.filter { it.isDigit() }?.toIntOrNull()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Load streaming links
    //
    // Strategy (confirmed from crawl):
    //   1. POST https://t21.press/data.php  body: movie={slug}
    //   2. Response HTML: <a href="https://t21.press/play-ads.php?movie=...&iframe={server}">
    //   3. For each server: pass URL to loadExtractor
    //   4. Fallback: fetch play-ads.php page + regex scrape for video URLs
    // ──────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data     : String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback : (ExtractorLink) -> Unit
    ): Boolean {

        // data format: "baseUrl||slug"  OR plain URL (episode data)
        val slug = if (data.contains("||")) {
            data.substringAfterLast("||")
        } else {
            data.trimEnd('/').substringAfterLast('/')
        }

        // ── Step 1: POST to t21.press/data.php ─────────────────
        val apiResponse = try {
            app.post(
                "https://t21.press/data.php",
                headers = mapOf(
                    "User-Agent"   to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer"      to "$mainUrl/$slug/",
                    "Origin"       to mainUrl,
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
                data = mapOf("movie" to slug)
            ).text
        } catch (e: Exception) {
            null
        }

        if (apiResponse.isNullOrBlank() || apiResponse.trim() == "none") {
            return false
        }

        // ── Step 2: Parse server links from response HTML ───────
        // Evidence: <a href="https://t21.press/play-ads.php?movie=...&iframe=p2p" class="DRIVE">UTAMA</a>
        val serverDoc = Jsoup.parse(apiResponse)
        val serverLinks = serverDoc.select("a[href]").filter { a ->
            val href = a.attr("href")
            href.contains("t21.press") || href.startsWith("http")
        }

        if (serverLinks.isEmpty()) return false

        var loaded = false

        for (serverLink in serverLinks) {
            val serverHref  = serverLink.absUrl("href").ifBlank { serverLink.attr("href") }
            val serverName  = serverLink.text().trim().ifBlank {
                serverLink.attr("class").uppercase().trim()
            }

            // Skip download-only links
            if (serverLink.attr("rel").contains("download", ignoreCase = true)) continue
            if (serverHref.contains("#download", ignoreCase = true)) continue

            // ── Step 3: Try loadExtractor first ──────────────
            try {
                val foundByExtractor = loadExtractor(serverHref, "$mainUrl/", subtitleCallback, callback)
                if (foundByExtractor) {
                    loaded = true
                    continue
                }
            } catch (_: Exception) {}

            // ── Step 4: Fetch play-ads.php and scrape video URL ─
            try {
                val playerPage = app.get(
                    serverHref,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Referer"    to "$mainUrl/$slug/",
                    )
                ).text

                // Look for M3U8 / MP4 direct URLs
                val videoUrl = extractVideoUrl(playerPage) ?: continue

                callback.invoke(
                    ExtractorLink(
                        source  = name,
                        name    = "$name [$serverName]",
                        url     = videoUrl,
                        referer = serverHref,
                        quality = Qualities.Unknown.value,
                        isM3u8  = videoUrl.contains(".m3u8"),
                    )
                )
                loaded = true

            } catch (_: Exception) {}
        }

        return loaded
    }

    // ──────────────────────────────────────────────────────────────
    // Regex scraper: extract video URL from player page HTML/JS
    // Handles JWPlayer setup (confirmed: JWPlayer 8.17.7 + P2P HLS)
    // ──────────────────────────────────────────────────────────────
    private fun extractVideoUrl(html: String): String? {

        // JWPlayer file:  {"file":"https://...m3u8"} or "file":"https://..."
        val jwFile = Regex("""["\']file["\']\s*:\s*["\'](https?://[^"\']+\.(?:m3u8|mp4)[^"\']*)["\']""")
            .find(html)?.groupValues?.get(1)
        if (jwFile != null) return jwFile

        // Generic M3U8 in script
        val m3u8 = Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""")
            .find(html)?.groupValues?.get(1)
        if (m3u8 != null) return m3u8

        // Generic MP4 in script
        val mp4 = Regex("""["'](https?://[^"']*\.mp4[^"']*)["']""")
            .find(html)?.groupValues?.get(1)
        if (mp4 != null) return mp4

        // iframe src pointing to known video hosts
        val iframeSrc = Regex("""<iframe[^>]+src=["'](https?://(?!ayononton)[^"']+)["']""")
            .find(html)?.groupValues?.get(1)
        if (iframeSrc != null && !iframeSrc.contains("terbit21")) return iframeSrc

        // Hydrax: source URL pattern from play hydra
        val hydrax = Regex("""["'](https?://playhydrax\.com/[^"']+)["']""")
            .find(html)?.groupValues?.get(1)
        if (hydrax != null) return hydrax

        return null
    }

    // ──────────────────────────────────────────────────────────────
    // URL encode helper
    // ──────────────────────────────────────────────────────────────
    private fun String.encodeUri() =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
