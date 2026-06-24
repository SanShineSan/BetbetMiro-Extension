package com.kusonime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.kusonime.KusonimeUtils.buildPageUrl
import com.kusonime.KusonimeUtils.cleanEscaped
import com.kusonime.KusonimeUtils.cleanTitle
import com.kusonime.KusonimeUtils.encode
import com.kusonime.KusonimeUtils.extractLabel
import com.kusonime.KusonimeUtils.getStatus
import com.kusonime.KusonimeUtils.getType
import com.kusonime.KusonimeUtils.isKusonimeDetailUrl
import com.kusonime.KusonimeUtils.normalizeUrl
import com.kusonime.KusonimeUtils.parseEpisodeNumber
import com.kusonime.KusonimeUtils.parseYear
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KusonimeProvider : MainAPI() {
    override var mainUrl = "https://kusonime.com"
    override var name = "Kusonime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/%page%" to "Update Terbaru",
        "$mainUrl/anime-list-bd/" to "Anime BD",
        "$mainUrl/anime-movie-list/" to "Movie List",
        "$mainUrl/seasons/ova/" to "OVA",
        "$mainUrl/seasons/ona/" to "ONA",
        "$mainUrl/seasons/special/" to "Special",
        "$mainUrl/daftar-live-action/" to "Live Action"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = buildPageUrl(request.data, page)
        val document = safeGet(pageUrl)?.document ?: throw ErrorLoadingException("Kusonime gagal memuat ${request.name}")
        val results = parseListing(document, pageUrl, request.name).distinctBy { it.url }
        val hasNext = document.selectFirst("a.next, a[rel=next], .pagination a[href*='/page/${page + 1}/'], a[href*='/page/${page + 1}/'], a:contains(Next Page), a:contains(»)") != null
        return newHomePageResponse(
            listOf(HomePageList(request.name, results, isHorizontalImages = request.name.contains("Movie", true))),
            hasNext
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        for (page in 1..3) {
            val path = if (page <= 1) "$mainUrl/?s=${encode(query)}" else "$mainUrl/page/$page/?s=${encode(query)}"
            val document = safeGet(path)?.document ?: break
            val pageResults = parseListing(document, path, "Search")
            if (pageResults.isEmpty()) break
            pageResults.forEach { results[it.url] = it }
        }
        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.substringBefore("#")
        val document = safeGet(cleanUrl)?.document ?: throw ErrorLoadingException("Kusonime detail gagal dimuat")
        val bodyText = document.select("article, main, .entry-content, .post, .venutama, .content, body").text().cleanEscaped()
        val title = document.selectFirst("h1.entry-title, article h1, main h1, h1")?.text()?.let { cleanTitle(it) }
            ?: throw ErrorLoadingException("Kusonime title kosong")
        val poster = parsePoster(document, cleanUrl)
        val typeText = extractLabel(bodyText, "Type") ?: title
        val type = getType(typeText)
        val status = getStatus(extractLabel(bodyText, "Status"))
        val tags = parseTags(document, bodyText)
        val year = parseYear(extractLabel(bodyText, "Released on") ?: bodyText)
        val score = extractLabel(bodyText, "Score")?.substringBefore(" ")?.trim()
        val description = parseDescription(document)
        val totalEpisode = parseEpisodeNumber(extractLabel(bodyText, "Total Episode") ?: bodyText)
        val episodeTitle = when (type) {
            TvType.AnimeMovie -> "Movie"
            TvType.OVA -> "OVA / Special"
            else -> if ((totalEpisode ?: 0) > 1) "Batch ${totalEpisode} Episode" else "Batch"
        }
        val episodes = listOf(
            newEpisode(url = cleanUrl, initializer = {
                this.name = episodeTitle
                this.episode = 1
            }, fix = false)
        )
        val recommendations = parseRecommendations(document, cleanUrl)

        return newAnimeLoadResponse(title, cleanUrl, type) {
            engName = title
            posterUrl = poster
            backgroundPosterUrl = poster
            this.year = year
            showStatus = status
            plot = description
            this.tags = tags
            addScore(score)
            addEpisodes(DubStatus.Subbed, episodes)
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return KusonimeExtractor.loadLinks(
            data = data.substringBefore("#"),
            mainUrl = mainUrl.trimEnd('/'),
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun safeGet(url: String): com.lagradost.nicehttp.NiceResponse? {
        return runCatching {
            app.get(url, referer = "$mainUrl/", headers = KusonimeUtils.headers, timeout = 30L)
        }.getOrNull()
    }

    private fun parseListing(document: Document, pageUrl: String, sectionName: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val cardSelectors = listOf(
            "article",
            "div.post",
            ".post",
            ".archive article",
            ".post-list article",
            ".postbody article",
            "h2 a[href]",
            "li a[href]"
        )
        for (selector in cardSelectors) {
            for (element in document.select(selector)) {
                val item = element.toSearchResponse(pageUrl, sectionName) ?: continue
                results[item.url] = item
            }
            if (results.isNotEmpty() && selector != "li a[href]") break
        }
        if (results.isEmpty()) {
            document.select("a[href]").forEach { anchor ->
                val item = anchor.toSearchResponse(pageUrl, sectionName) ?: return@forEach
                results[item.url] = item
            }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResponse(pageUrl: String, sectionName: String): SearchResponse? {
        val link = when {
            tagName().equals("a", true) && hasAttr("href") -> this
            else -> selectFirst("h2 a[href], h3 a[href], .entry-title a[href], .title a[href], a[href]")
        } ?: return null
        val href = normalizeUrl(link.attr("href"), pageUrl, mainUrl).substringBefore("#")
        if (!isKusonimeDetailUrl(href, mainUrl)) return null
        val rawTitle = link.attr("title").ifBlank { link.text() }.ifBlank {
            selectFirst("h2, h3, .entry-title, .title")?.text().orEmpty()
        }.ifBlank {
            (selectFirst("img") ?: link.selectFirst("img"))?.attr("alt").orEmpty()
        }
        val title = cleanTitle(rawTitle)
        if (title.length < 2 || isBlockedTitle(title)) return null
        val poster = parseCardPoster(this, link, pageUrl)
        val type = when {
            sectionName.contains("Movie", true) || sectionName.contains("Live Action", true) -> TvType.AnimeMovie
            sectionName.contains("OVA", true) || sectionName.contains("ONA", true) || sectionName.contains("Special", true) -> TvType.OVA
            else -> getType(title)
        }
        val episode = parseEpisodeNumber(title)
        return newAnimeSearchResponse(title, href, type) {
            posterUrl = poster
            addSub(episode)
        }
    }

    private fun parseCardPoster(element: Element, link: Element, pageUrl: String): String? {
        val image = element.selectFirst("img") ?: link.selectFirst("img") ?: element.parents().firstOrNull { it.selectFirst("img") != null }?.selectFirst("img")
        val raw = image?.attr("data-src")
            ?.ifBlank { image.attr("data-lazy-src") }
            ?.ifBlank { image.attr("data-original") }
            ?.ifBlank { image.attr("src") }
        return normalizeUrl(raw, pageUrl, mainUrl).takeIf { it.isNotBlank() && !it.contains("loading", true) }
    }

    private fun parsePoster(document: Document, pageUrl: String): String? {
        val image = document.selectFirst("article img.wp-post-image, article .entry-content img, main img.wp-post-image, .post img.wp-post-image, .entry-content img, img[alt]")
        val raw = image?.attr("data-src")
            ?.ifBlank { image.attr("data-lazy-src") }
            ?.ifBlank { image.attr("data-original") }
            ?.ifBlank { image.attr("src") }
        return normalizeUrl(raw, pageUrl, mainUrl).takeIf { it.isNotBlank() && !it.contains("loading", true) }
    }

    private fun parseTags(document: Document, bodyText: String): List<String> {
        val linked = document.select("a[href*='/genre/'], a[href*='/genres/'], a[rel=tag]")
            .map { it.text().cleanEscaped() }
            .filter { it.isNotBlank() && it.length <= 40 }
            .distinct()
        if (linked.isNotEmpty()) return linked
        return extractLabel(bodyText, "Genre")
            ?.split(",")
            ?.map { it.cleanEscaped() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    private fun parseDescription(document: Document): String? {
        val paragraphs = document.select("article p, main p, .entry-content p, .post p")
            .map { it.text().cleanEscaped() }
            .filter { text ->
                text.length > 50 &&
                    !text.contains("Download", true) &&
                    !text.contains("Matikan ADBLOCK", true) &&
                    !text.contains("Tolong di Baca", true) &&
                    !text.contains("Credit", true) &&
                    !text.contains("Japanese:", true) &&
                    !text.contains("Genre :", true)
            }
        return paragraphs.take(3).joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun parseRecommendations(document: Document, pageUrl: String): List<SearchResponse> {
        return document.select(".new-add-series a[href], .related-post a[href], .random-post a[href], .sidebar a[href], h2 a[href]")
            .mapNotNull { it.toSearchResponse(pageUrl, "Recommendation") }
            .distinctBy { it.url }
            .take(12)
    }

    private fun isBlockedTitle(title: String): Boolean {
        val clean = title.trim().lowercase()
        return clean in setOf(
            "home", "anime list", "anime bd", "movie list", "live action", "anime ova", "anime special", "anime ona",
            "genres", "tahun rilis", "credits", "faq", "dmca", "privacy policy", "contact person", "request"
        ) || clean.startsWith("page ") || clean.startsWith("ads ")
    }
}
