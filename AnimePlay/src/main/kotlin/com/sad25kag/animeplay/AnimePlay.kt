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
                val epLabel   = cleanEpisodeLabel(a.text(), epNum)
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

    private fun cleanEpisodeLabel(rawLabel: String?, epNum: Int?): String {
        val fallback = epNum?.let { "Episode $it" } ?: "Episode"
        val normalized = rawLabel
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()

        if (normalized.isBlank()) return fallback
        if (epNum == null) return normalized

        val withoutLeadingNumber = normalized
            .replace(Regex("""^\s*${epNum}(?=\D)\s*[-.:)]?\s*"""), "")
            .trim()

        if (withoutLeadingNumber.equals("Tonton Sekarang", ignoreCase = true)) return fallback

        return withoutLeadingNumber.ifBlank { fallback }
    }

    // ─── loadLinks (episode/movie watch page) ─────────────────────────────────
    // Evidence from HAR 2026-06-19:
    //   - Player wrappers: dl.berkasdrive.com, stordl.halahgan.com, dlgan.halahgan.com
    //   - Final media: stor.halahgan.com/*.mp4
    //
    // Strategy:
    //   1. Keep the old iframe/extractor/scrape flow that was playable.
    //   2. Add HAR-backed streamingSources parsing only as extra candidates.
    //   3. Drop non-HAR player hosts so playback does not wander into stale hosts.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val res = app.get(data, headers = mapOf("Accept-Language" to "id-ID,id;q=0.9"))
        val html = res.text
        val candidates = mutableListOf<SourceCandidate>()

        res.document.select("iframe[src]").forEach { iframe ->
            normalizeUrl(iframe.attr("src"))?.let { src ->
                candidates.add(SourceCandidate(iframe.attr("title").trim().ifBlank { "Iframe" }, src))
            }
        }

        candidates.addAll(extractStreamingSources(html))

        val seenCandidates = mutableSetOf<String>()
        val emittedUrls = mutableSetOf<String>()
        var found = false

        for (candidate in candidates.distinctBy { it.url }) {
            val src = normalizeUrl(candidate.url) ?: continue
            if (!seenCandidates.add(src)) continue

            val trackedCallback: (ExtractorLink) -> Unit = { link ->
                val linkUrl = cleanupEscaped(link.url).trim()
                if (isHarDirectVideo(linkUrl) && emittedUrls.add(linkUrl)) {
                    found = true
                    callback(link)
                }
            }

            if (isHarDirectVideo(src)) {
                found = emitDirectLink(src, candidate.label, data, emittedUrls, callback) || found
                continue
            }

            if (!isHarWrapperUrl(src)) {
                continue
            }

            try {
                loadExtractor(src, data, subtitleCallback, trackedCallback)
            } catch (_: Exception) {
                // Continue with source-backed resolver below.
            }

            val inner = unwrapBase64(src)
            if (inner != null) {
                val normalizedInner = normalizeUrl(inner) ?: inner
                if (isHarDirectVideo(normalizedInner)) {
                    found = emitDirectLink(normalizedInner, candidate.label, src, emittedUrls, callback) || found
                } else if (isHarWrapperUrl(normalizedInner)) {
                    try {
                        loadExtractor(normalizedInner, src, subtitleCallback, trackedCallback)
                    } catch (_: Exception) {
                        // Continue with wrapper scraping.
                    }
                    found = scrapePlayer(normalizedInner, src, candidate.label, emittedUrls, callback) || found
                }
            }

            found = resolveHalahganApi(src, candidate.label, emittedUrls, callback) || found
            found = scrapePlayer(src, data, candidate.label, emittedUrls, callback) || found
        }

        return found
    }

    // ─── loadLinks helpers ────────────────────────────────────────────────────

    private data class SourceCandidate(
        val label: String,
        val url: String,
    )

    private val harWrapperHosts = setOf(
        "dl.berkasdrive.com",
        "stordl.halahgan.com",
        "dlgan.halahgan.com",
    )

    private val harDirectHosts = setOf(
        "stor.halahgan.com",
    )

    private fun cleanupEscaped(raw: String): String {
        return raw
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
    }

    private fun normalizeUrl(src: String?): String? {
        val clean = src
            ?.trim()
            ?.let { cleanupEscaped(it) }
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return when {
            clean.startsWith("http", ignoreCase = true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$mainUrl$clean"
            else -> null
        }
    }

    private fun hostOf(url: String): String? {
        return Regex("""https?://([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
    }

    private fun isDirectVideo(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4")
    }

    private fun isHarWrapperUrl(url: String): Boolean {
        return hostOf(url) in harWrapperHosts
    }

    private fun isHarDirectVideo(url: String): Boolean {
        return hostOf(url) in harDirectHosts && isDirectVideo(url)
    }

    /**
     * Extract React/Next RSC streamingSources[] from AnimePlay episode pages.
     * HAR sample exposes objects containing:
     * {"label":"1080p - Berkasdrive","streaming":"https://stordl.halahgan.com/streaming//..."}
     */
    private fun extractStreamingSources(rawHtml: String): List<SourceCandidate> {
        val clean = cleanupEscaped(rawHtml)
        val results = mutableListOf<SourceCandidate>()
        val objectRe = Regex("""\{[^{}]*"streaming"\s*:\s*"([^"]+)"[^{}]*}""")
        val labelRe = Regex(""""label"\s*:\s*"([^"]+)"""")
        val resolutionRe = Regex(""""resolutionLabel"\s*:\s*"([^"]+)"""")

        for (match in objectRe.findAll(clean)) {
            val block = match.value
            val url = normalizeUrl(match.groupValues[1]) ?: continue
            if (!isHarWrapperUrl(url) && !isHarDirectVideo(url)) continue

            val label = labelRe.find(block)?.groupValues?.getOrNull(1)
                ?: resolutionRe.find(block)?.groupValues?.getOrNull(1)
                ?: "Streaming"
            results.add(SourceCandidate(label, url))
        }

        return results.distinctBy { it.url }
    }

    /** Decode base64-wrapped URL from ?url=, ?id=, or ?v= query param. */
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

    private suspend fun emitDirectLink(
        rawUrl: String,
        label: String,
        referer: String,
        emittedUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val url = cleanupEscaped(rawUrl).trim()
        if (!isHarDirectVideo(url)) return false
        if (!emittedUrls.add(url)) return false

        val isHls = url.contains(".m3u8", ignoreCase = true)
        callback(
            newExtractorLink(
                source = name,
                name = label.ifBlank { if (isHls) "HLS" else "Direct" },
                url = url,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private fun findUrlField(raw: String, field: String): String? {
        val pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]+)\""
        return Regex(pattern, RegexOption.IGNORE_CASE)
            .find(cleanupEscaped(raw))
            ?.groupValues
            ?.getOrNull(1)
    }

    /**
     * stordl.halahgan.com wrapper has a direct JSON resolver:
     * /streaming//{id}?action=stream-url&id={id} -> {"ok":true,"url":"https://stor.halahgan.com/stream/...mp4"}
     */
    private suspend fun resolveHalahganApi(
        src: String,
        label: String,
        emittedUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val host = hostOf(src) ?: return false
        if (host != "stordl.halahgan.com") return false

        val id = Regex("""/streaming/+([^/?&#]+)""").find(src)?.groupValues?.getOrNull(1)
            ?: Regex("""[?&]id=([^&#]+)""").find(src)?.groupValues?.getOrNull(1)
            ?: return false

        val apiUrl = "https://$host/streaming//$id?action=stream-url&id=$id"
        val json = try {
            app.get(
                apiUrl,
                referer = src,
                headers = mapOf("Referer" to src, "Origin" to "https://$host"),
            ).text
        } catch (_: Exception) {
            return false
        }

        val direct = findUrlField(json, "stream_url")
            ?: findUrlField(json, "url")
            ?: return false

        return emitDirectLink(direct, label, src, emittedUrls, callback)
    }

    /** Fetch player wrapper HTML and search only for HAR-backed final media URLs. */
    private suspend fun scrapePlayer(
        src: String,
        referer: String,
        label: String,
        emittedUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!isHarWrapperUrl(src)) return false

        val pageHtml = try {
            app.get(
                src,
                referer = referer,
                headers = mapOf("Referer" to referer, "Origin" to mainUrl),
            ).text
        } catch (_: Exception) {
            return false
        }

        val clean = cleanupEscaped(pageHtml)
        var found = false

        // Explicit JSON / JS fields first: PRELOAD.data.stream_url, direct_url, JW "file", generic "url".
        for (field in listOf("stream_url", "direct_url", "file", "url")) {
            val direct = findUrlField(clean, field)
            if (!direct.isNullOrBlank()) {
                found = emitDirectLink(direct, label, src, emittedUrls, callback) || found
            }
        }

        // HTML5 <source src="..."> used by dl.berkasdrive wrapper.
        org.jsoup.Jsoup.parse(clean).select("source[src], [data-url]").forEach { el ->
            val direct = el.attr("src").ifBlank { el.attr("data-url") }
            if (direct.isNotBlank()) {
                found = emitDirectLink(direct, label, src, emittedUrls, callback) || found
            }
        }

        // Last pass: HAR-backed direct media URL anywhere in wrapper JS/HTML.
        val directRe = Regex("""https?://stor\.halahgan\.com/[^"'\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\s<>]*)?""", RegexOption.IGNORE_CASE)
        for (match in directRe.findAll(clean)) {
            found = emitDirectLink(match.value, label, src, emittedUrls, callback) || found
        }

        return found
    }

}