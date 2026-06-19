package com.sad25kag.animeplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class AnimePlay : MainAPI() {

    override var mainUrl   = "https://anime-play.id"
    override var name      = "AnimePlay"
    override var lang      = "id"
    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // ─── Main page sections ───────────────────────────────────────────────────
    // Evidence from active menu: /tag/on-going, /tag/complete, /type/movie, /type/ova, /type/ona

    override val mainPage = mainPageOf(
        "$mainUrl/tag/on-going" to "On-Going",
        "$mainUrl/tag/complete" to "Complete",
        "$mainUrl/type/movie"   to "Movie",
        "$mainUrl/type/ova"     to "OVA",
        "$mainUrl/type/ona"     to "ONA",
    )

    // ─── Poster helper ────────────────────────────────────────────────────────

    private fun toPosterUrl(src: String?): String? {
        if (src.isNullOrBlank()) return null
        return when {
            src.startsWith("http") -> src
            src.startsWith("//")   -> "https:$src"
            src.startsWith("/")    -> "$mainUrl$src"
            else                   -> null
        }
    }

    // ─── Card parser (listing + search pages) ────────────────────────────────
    // Evidence:
    //   - DOM: <a href="/anime/{slug}"> contains <img src="/_wp_images/{hash}.webp" alt="Title">
    //   - Fallback: JSON-LD @type=ItemList with name+url per entry

    private fun parseCards(
        rawHtml: String,
        doc: org.jsoup.nodes.Document,
    ): List<AnimeSearchResponse> {
        val results = mutableListOf<AnimeSearchResponse>()
        val seen    = mutableSetOf<String>()

        // DOM pass — every <a href="/anime/{slug}"> that is NOT an episode link
        for (link in doc.select("a[href]")) {
            val href = link.attr("href").trim()
            val isAnimeDetail = (href.startsWith("/anime/") || href.startsWith("$mainUrl/anime/"))
                && !href.contains("/episode/")
                && href != "/anime" && href != "/anime/"
            if (!isAnimeDetail) continue

            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            if (!seen.add(fullUrl)) continue

            val img    = link.selectFirst("img[src]")
            val title  = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: link.text().trim().takeIf { it.isNotBlank() }
                ?: continue
            val poster = toPosterUrl(img?.attr("src").orEmpty().takeIf { it.isNotBlank() })

            val tvType = when {
                fullUrl.contains("-movie", ignoreCase = true) ||
                title.contains("Movie",   ignoreCase = true)  -> TvType.AnimeMovie
                fullUrl.contains("/ova",  ignoreCase = true)  ||
                title.contains(" OVA",   ignoreCase = true)   -> TvType.OVA
                else                                           -> TvType.Anime
            }
            results.add(newAnimeSearchResponse(title, fullUrl, tvType) {
                this.posterUrl = poster
            })
        }

        // Fallback — JSON-LD ItemList (no posters but reliable titles+URLs)
        if (results.isEmpty()) {
            val m = Regex(
                """"@type"\s*:\s*"ItemList"[\s\S]*?"itemListElement"\s*:\s*(\[[\s\S]*?])\s*"""
            ).find(rawHtml)
            val listBlock = m?.groupValues?.getOrNull(1) ?: return results
            val entryRe   = Regex(""""name"\s*:\s*"([^"]+)"[\s\S]*?"url"\s*:\s*"([^"]+)"""")
            for (em in entryRe.findAll(listBlock)) {
                val title = em.groupValues[1].trim()
                val url   = em.groupValues[2].trim()
                if (!url.contains("/anime/")) continue
                if (!seen.add(url)) continue
                val tvType = if (title.contains("Movie", ignoreCase = true))
                    TvType.AnimeMovie else TvType.Anime
                results.add(newAnimeSearchResponse(title, url, tvType) {
                    posterUrl = null
                })
            }
        }

        return results
    }

    // ─── getMainPage ──────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val res = app.get(url, headers = mapOf("Accept-Language" to "id-ID,id;q=0.9"))
        val items = parseCards(res.text, res.document).distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    // ─── Search (/search?q=...) ───────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q   = URLEncoder.encode(query, "UTF-8")
        val res = app.get("$mainUrl/search?q=$q",
            headers = mapOf("Accept-Language" to "id-ID,id;q=0.9"))
        return parseCards(res.text, res.document).distinctBy { it.url }
    }

    // ─── load (anime detail page) ─────────────────────────────────────────────
    // Evidence from /anime/hourou-musuko :
    //   Title   : <h1 class="text-xl ...">Hourou Musuko</h1>
    //   Poster  : <meta property="og:image" content="https://.../_wp_images/{hash}.webp">
    //   Genres  : <a href="/genre/drama">Drama</a> etc.
    //   Episodes: <a href="/anime/{slug}/episode/{N}">
    //   Desc    : JSON-LD @type=TVSeries "description"
    //   Status  : RSC metaJson "id":"status","value":"Tamat"/"Ongoing"
    //   Type    : RSC metaJson "id":"type","value":"TV"/"Movie"/"OVA"

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.substringBefore("?").trimEnd('/')
        val res  = app.get(cleanUrl, headers = mapOf("Accept-Language" to "id-ID,id;q=0.9"))
        val doc  = res.document
        val html = res.text

        // Title
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Unknown"

        // Poster
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.trim()
            ?: toPosterUrl(doc.selectFirst("img[src*='_wp_images']")?.attr("src"))

        // Description — JSON-LD @type=TVSeries
        val description = Regex(
            """"@type"\s*:\s*"TVSeries"[\s\S]*?"description"\s*:\s*"((?:[^"\\]|\\.)*)""""
        ).find(html)?.groupValues?.getOrNull(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        // Genres
        val genres = doc.select("a[href*='/genre/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        // Year
        val year = Regex(""""datePublished"\s*:\s*"(\d{4})""")
            .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()

        // Status — RSC metaJson: {"label":"Status","id":"status","value":"Tamat"}
        val metaStatus = Regex(""""id"\s*:\s*"status"[^}]*"value"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.getOrNull(1)?.trim()

        // Type — RSC metaJson: {"label":"Type","id":"type","value":"TV"}
        val metaType = Regex(""""id"\s*:\s*"type"[^}]*"value"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.getOrNull(1)?.trim()

        val showStatus: ShowStatus? = when (metaStatus?.lowercase()) {
            "tamat", "completed", "selesai", "finished" -> ShowStatus.Completed
            "ongoing", "berlangsung", "airing"           -> ShowStatus.Ongoing
            else                                          -> null
        }

        val tvType: TvType = when (metaType?.lowercase()) {
            "movie"                  -> TvType.AnimeMovie
            "ova", "ona", "special"  -> TvType.OVA
            else                     -> {
                if (title.contains("Movie", ignoreCase = true) ||
                    cleanUrl.contains("-movie", ignoreCase = true))
                    TvType.AnimeMovie
                else TvType.Anime
            }
        }

        // Episodes — <a href="/anime/{slug}/episode/{N}">
        val slug = cleanUrl.trimEnd('/').substringAfterLast("/")
        val episodeLinks = doc.select("a[href*='/episode/']")
            .filter { it.attr("href").let { h -> h.contains("/anime/$slug/episode/") || (h.contains("/anime/") && h.contains("/episode/")) } }
            .mapNotNull { a ->
                val href      = a.attr("href").trim()
                val fullEpUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                val epNumStr  = href.trimEnd('/').substringAfterLast("/episode/")
                val epNum     = epNumStr.toIntOrNull()
                    ?: Regex("""(\d+)$""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epLabel   = a.text().trim().ifBlank { "Episode $epNum" }
                newEpisode(fullEpUrl) {
                    name    = epLabel
                    episode = epNum
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        // Tracker
        val tracker = APIHolder.getTracker(
            listOf(title), TrackerType.getTypes(tvType), null, true
        )

        // Build response
        return if (tvType == TvType.AnimeMovie) {
            val movieData = episodeLinks.firstOrNull()?.data ?: cleanUrl
            newMovieLoadResponse(title, cleanUrl, TvType.AnimeMovie, movieData) {
                this.posterUrl = tracker?.image ?: poster
                this.plot      = description
                this.tags      = genres
                this.year      = year
            }
        } else {
            newAnimeLoadResponse(title, cleanUrl, tvType) {
                engName             = title
                posterUrl           = tracker?.image ?: poster
                backgroundPosterUrl = tracker?.cover
                addEpisodes(DubStatus.Subbed, episodeLinks)
                plot            = description
                tags            = genres
                this.year       = year
                this.showStatus = showStatus
            }
        }
    }

    // ─── loadLinks (episode/movie watch page) ─────────────────────────────────
    // Evidence:
    //   Hourou Musuko ep1 : <iframe src="https://video.nimegami.id/?url={base64}">
    //   Naruto ep1        : <iframe src="https://dl.berkasdrive.com/streaming/?id={base64}">
    //
    // Strategy:
    //   1. Try CloudStream's built-in loadExtractor (handles nimegami, hxfile, etc.)
    //   2. Unwrap base64 param and try loadExtractor on decoded URL
    //   3. Scrape the wrapper page for JWPlayer/HLS/MP4 URLs

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val res = app.get(data, headers = mapOf("Accept-Language" to "id-ID,id;q=0.9"))
        val doc = res.document

        val iframeSrcs = doc.select("iframe[src]")
            .map { it.attr("src").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        var found = false

        for (rawSrc in iframeSrcs) {
            val src = when {
                rawSrc.startsWith("http") -> rawSrc
                rawSrc.startsWith("//")   -> "https:$rawSrc"
                rawSrc.startsWith("/")    -> "$mainUrl$rawSrc"
                else                       -> continue
            }

            // Step 1: built-in extractor
            if (loadExtractor(src, data, subtitleCallback, callback)) {
                found = true
                continue
            }

            // Step 2: decode base64 inner URL and try extractor on it
            val inner = unwrapBase64(src)
            if (inner != null) {
                if (loadExtractor(inner, src, subtitleCallback, callback)) {
                    found = true
                    continue
                }
                // If inner is a direct stream URL, add it directly
                if (inner.contains(".m3u8") || inner.contains(".mp4")) {
                    val isHls = inner.contains(".m3u8")
                    val host  = inner.substringAfter("://").substringBefore("/").substringAfterLast(".")
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = "AnimePlay-$host",
                            url    = inner,
                            type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = src
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                    continue
                }
            }

            // Step 3: scrape the wrapper player page
            if (scrapePlayer(src, data, callback)) found = true
        }

        return found
    }

    // ─── loadLinks helpers ────────────────────────────────────────────────────

    /** Decode base64-wrapped URL from ?url=, ?id=, or ?v= query param */
    private fun unwrapBase64(src: String): String? {
        val param = Regex("""[?&](?:url|id|v)=([A-Za-z0-9+/=]{10,})""")
            .find(src)?.groupValues?.getOrNull(1) ?: return null
        return try {
            val decoded = String(android.util.Base64.decode(param, android.util.Base64.DEFAULT))
            if (decoded.startsWith("http")) decoded else null
        } catch (_: Exception) {
            null
        }
    }

    /** Fetch player wrapper HTML and search for JWPlayer / HLS / HTML5 source / MP4 */
    private suspend fun scrapePlayer(
        src: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageHtml = try {
            app.get(src, referer = referer,
                headers = mapOf("Referer" to referer, "Origin" to mainUrl)).text
        } catch (_: Exception) {
            return false
        }

        // HLS m3u8
        val hlsUrl = Regex(""""(https?://[^"]+\.m3u8[^"]*)"""").find(pageHtml)
            ?.groupValues?.getOrNull(1)
        if (!hlsUrl.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = name, name = "HLS",
                    url    = hlsUrl,
                    type   = ExtractorLinkType.M3U8,
                ) {
                    this.referer = src
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // JWPlayer "file":"url"
        val jwFile = Regex(""""file"\s*:\s*"(https?://[^"]+)"""").find(pageHtml)
            ?.groupValues?.getOrNull(1)
        if (!jwFile.isNullOrBlank()) {
            val isHls = jwFile.contains(".m3u8")
            callback(
                newExtractorLink(
                    source = name, name = "JW",
                    url    = jwFile,
                    type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = src
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // HTML5 <source src="...">
        val sourceEl = org.jsoup.Jsoup.parse(pageHtml).selectFirst("source[src]")?.attr("src")
        if (!sourceEl.isNullOrBlank()) {
            val videoUrl = if (sourceEl.startsWith("//")) "https:$sourceEl" else sourceEl
            callback(
                newExtractorLink(
                    source = name, name = "Direct",
                    url    = videoUrl,
                    type   = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                             else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = src
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // MP4 anywhere
        val mp4Url = Regex(""""(https?://[^"]+\.mp4[^"]*)"""").find(pageHtml)
            ?.groupValues?.getOrNull(1)
        if (!mp4Url.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = name, name = "MP4",
                    url    = mp4Url,
                    type   = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = src
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        return false
    }
}
