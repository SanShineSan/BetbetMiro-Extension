package com.sad25kag.layarwarna21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class LayarWarna21 : MainAPI() {
    override var mainUrl = "https://hisgloryco.com"
    override var name = "LayarWarna21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "/" to "Update Terbaru",
        "/film-terbaik/page/%d/" to "Film Terbaik",
        "/genre/bioskopkeren/page/%d/" to "Box Office",
        "/genre/action/page/%d/" to "Action",
        "/genre/animation/page/%d/" to "Animation",
        "/genre/comedy/page/%d/" to "Comedy",
        "/genre/drama/page/%d/" to "Drama",
        "/genre/horror/page/%d/" to "Horror",
        "/genre/romance/page/%d/" to "Romance",
        "/genre/thriller/page/%d/" to "Thriller",
        "/country/indonesia/page/%d/" to "Indonesia",
        "/country/usa/page/%d/" to "USA",
        "/country/china/page/%d/" to "China",
        "/country/japan/page/%d/" to "Japan",
        "/country/korea/page/%d/" to "Korea",
        "/year/2026/page/%d/" to "Tahun 2026",
        "/year/2025/page/%d/" to "Tahun 2025",
        "/year/2024/page/%d/" to "Tahun 2024",
        "/year/2023/page/%d/" to "Tahun 2023"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = headers, referer = "$mainUrl/").document
        val items = document.select("article.item, article.item.col-md-20, div.gmr-box-content article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = hasNextPage(document, page, items.isNotEmpty()))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv",
            "$mainUrl/?s=$encoded"
        )
        val results = linkedMapOf<String, SearchResponse>()
        urls.forEach { url ->
            val document = runCatching { app.get(url, headers = headers, referer = "$mainUrl/").document }.getOrNull() ?: return@forEach
            document.select("article.item, article.item.col-md-20, div.gmr-box-content article")
                .mapNotNull { it.toSearchResult() }
                .forEach { results[it.url] = it }
            if (results.isNotEmpty()) return results.values.toList()
        }
        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(fixUrl(url), headers = headers, referer = "$mainUrl/")
        val document = response.document
        val finalUrl = response.url.ifBlank { fixUrl(url) }
        val title = document.selectFirst("h1.entry-title, h1[itemprop=name], h1[itemprop=headline], h1")
            ?.text()?.cleanTitle()?.takeIf { it.isNotBlank() }
            ?: finalUrl.slugTitle()
        val poster = fixUrlNull(
            document.selectFirst("div.gmr-movie-data figure img, .gmr-movie-data img, .content-thumbnail img, img.wp-post-image")?.getImageAttr()
        )?.fixImageQuality()
        val tags = document.select("div.gmr-moviedata a[href*='/genre/'], div.gmr-movie-on a[rel='category tag'], a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.isUiText() }
            .distinct()
        val year = document.selectFirst("div.gmr-moviedata strong:contains(Year:) > a, a[href*='/year/']")
            ?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        val description = document.selectFirst("div[itemprop=description] p, div[itemprop=description], .entry-content p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav a.gmr-trailer-popup[href], a.gmr-trailer-popup[href], a[href*='youtube.com'], a[href*='youtu.be']")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating span[itemprop=ratingValue], span[itemprop=ratingValue], div.gmr-rating-item")?.text()?.trim()
        val actors = document.select("span[itemprop=actors] a, a[href*='/cast/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val duration = document.selectFirst("span[property=duration], div.gmr-duration-item, .runtime")?.text()?.replace(Regex("\\D"), "")?.toIntOrNull()
        val recommendations = document.select("article.item.col-md-20, div.gmr-box-content article")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != finalUrl }
            .distinctBy { it.url }
            .take(24)

        return newMovieLoadResponse(title, finalUrl, TvType.Movie, finalUrl) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            addScore(rating)
            addActors(actors)
            this.recommendations = recommendations
            this.duration = duration ?: 0
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(fixUrl(data), headers = headers, referer = "$mainUrl/", timeout = 30L)
        val document = response.document
        val finalUrl = response.url.ifBlank { fixUrl(data) }
        val directUrl = getBaseUrl(finalUrl)
        val emitted = linkedSetOf<String>()
        var found = false

        suspend fun submit(raw: String?, referer: String = finalUrl) {
            val playerUrl = raw?.trim()?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }?.httpsifySafe() ?: return
            if (playerUrl.isBadPlaybackUrl()) return
            if (!emitted.add(playerUrl.substringBefore("#"))) return
            loadExtractor(playerUrl, referer, subtitleCallback) { link ->
                if (!link.url.isBadPlaybackUrl()) {
                    found = true
                    callback(link)
                }
            }
        }

        document.select("div.gmr-embed-responsive iframe, .gmr-player iframe, iframe[src], iframe[data-src], iframe[data-litespeed-src]")
            .forEach { submit(it.getIframeAttr(), finalUrl) }

        val postId = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if (!postId.isNullOrBlank()) {
            document.select("div.tab-content-ajax[id]").forEach { tab ->
                val tabId = tab.attr("id").trim()
                if (tabId.isBlank()) return@forEach
                val ajax = runCatching {
                    app.post(
                        "$directUrl/wp-admin/admin-ajax.php",
                        data = mapOf("action" to "muvipro_player_content", "tab" to tabId, "post_id" to postId),
                        headers = headers + mapOf(
                            "Accept" to "*/*",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to directUrl,
                            "Referer" to finalUrl
                        ),
                        referer = finalUrl
                    ).document
                }.getOrNull() ?: return@forEach

                ajax.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]").forEach { iframe ->
                    submit(iframe.getIframeAttr(), "$directUrl/")
                }
            }
        }

        document.select("option[value], select option[value], .mobius option[value], .mirror option[value]").forEach { option ->
            decodeServerValue(option.attr("value")).forEach { decoded ->
                Regex("""(?i)<iframe[^>]+src=['\"]([^'\"]+)['\"]""").findAll(decoded).forEach { match ->
                    submit(match.groupValues[1], finalUrl)
                }
            }
        }

        document.select("ul.gmr-download-list li a[href], .gmr-download-list a[href]").forEach { link ->
            submit(link.attr("href"), finalUrl)
        }
        return found
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2.entry-title > a[href], h2 a[href], .entry-title a[href], a[href][title]") ?: return null
        val title = listOf(anchor.text(), anchor.attr("title"), selectFirst("img[alt]")?.attr("alt"))
            .firstOrNull { !it.isNullOrBlank() && !it.isUiText() }
            ?.cleanTitle() ?: return null
        val href = fixUrl(anchor.attr("href"))
        val poster = fixUrlNull(selectFirst("div.content-thumbnail img, a > img, img")?.getImageAttr())?.fixImageQuality()
        val quality = selectFirst("div.gmr-quality-item a, .gmr-quality-item, a[href*='/quality/']")?.text()?.trim()
        val rating = selectFirst("div.gmr-rating-item, .gmr-meta-rating span[itemprop=ratingValue], span[itemprop=ratingValue]")
            ?.text()?.replace(",", ".")?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.getOrNull(1) }?.toDoubleOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.quality = getQualityFromString(quality)
            rating?.let { this.score = Score.from10(it) }
        }
    }

    private fun buildPageUrl(path: String, page: Int): String {
        if (path == "/") return if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        return fixUrl(if (path.contains("%d")) path.format(page) else path)
    }

    private fun hasNextPage(document: org.jsoup.nodes.Document, page: Int, hasItems: Boolean): Boolean {
        return document.selectFirst("a.next, a.nextpostslink, .pagination a:contains(Next), .pagination a:contains(»)") != null || (page == 1 && hasItems)
    }

    private fun decodeServerValue(value: String): List<String> {
        val clean = value.trim()
        if (clean.isBlank()) return emptyList()
        val decoded = runCatching { java.net.URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val candidates = linkedSetOf(clean, decoded)
        listOf(clean, decoded).forEach { item ->
            runCatching { String(java.util.Base64.getDecoder().decode(item), Charsets.UTF_8) }.getOrNull()?.let { candidates.add(it) }
        }
        return candidates.toList()
    }

    private fun Element.getImageAttr(): String = when {
        hasAttr("data-src") -> attr("data-src")
        hasAttr("data-lazy-src") -> attr("data-lazy-src")
        hasAttr("data-litespeed-src") -> attr("data-litespeed-src")
        hasAttr("srcset") -> attr("srcset").substringBefore(" ")
        else -> attr("src")
    }

    private fun Element.getIframeAttr(): String? = listOf(attr("data-litespeed-src"), attr("data-src"), attr("src"))
        .firstOrNull { it.isNotBlank() }

    private fun String.httpsifySafe(): String = if (startsWith("//")) "https:$this" else httpsify(this)

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    private fun String.fixImageQuality(): String {
        val marker = Regex("(-\\d*x\\d*)").find(this)?.value ?: return this
        return replace(marker, "")
    }

    private fun String.cleanTitle(): String = replace(Regex("(?i)\\s*-\\s*LayarWarna21.*$"), "")
        .replace(Regex("(?i)^nonton\\s+film\\s+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.isUiText(): Boolean {
        val lower = lowercase().trim()
        return lower in setOf("watch", "watch movie", "trailer", "download", "server 1", "server 2", "click to play", "turn off light")
    }

    private fun String.slugTitle(): String = substringBefore('?').trimEnd('/').substringAfterLast('/').replace('-', ' ').cleanTitle()

    private fun String.isBadPlaybackUrl(): Boolean {
        val lower = lowercase()
        return lower.isBlank() ||
            lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.contains("googleads") ||
            lower.contains("/ads") ||
            lower.contains("banner") ||
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".css") || lower.endsWith(".js")
    }
}
