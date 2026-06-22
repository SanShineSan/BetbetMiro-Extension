package com.sad25kag.layarwarna21

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

class LayarWarna21 : MainAPI() {
    override var mainUrl = "https://hisgloryco.com"
    override var name = "LayarWarna21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(TvType.Movie)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache"
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
        val document = app.get(
            buildPageUrl(request.data, page),
            headers = siteHeaders,
            referer = "$mainUrl/"
        ).document

        val items = parseCards(document).distinctBy { it.url }
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
            val document = runCatching {
                app.get(url, headers = siteHeaders, referer = "$mainUrl/").document
            }.getOrNull() ?: return@forEach

            parseCards(document).forEach { item -> results[item.url] = item }
            if (results.isNotEmpty()) return results.values.toList()
        }
        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = resolveUrl(url, mainUrl) ?: return null
        val response = app.get(fixedUrl, headers = siteHeaders, referer = "$mainUrl/")
        val document = response.document
        val finalUrl = response.url.ifBlank { fixedUrl }

        val title = document.selectFirst(
            "h1.entry-title, h1[itemprop=name], h1[itemprop=headline], h1, meta[property=og:title]"
        )?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() && !it.isUiText() }
            ?: finalUrl.slugTitle()

        val poster = document.selectFirst(
            "div.gmr-movie-data figure img, .gmr-movie-data img, .content-thumbnail img, img.wp-post-image, meta[property=og:image], meta[name=twitter:image]"
        )?.let { if (it.hasAttr("content")) it.attr("content") else it.getImageAttr() }
            ?.let { resolveUrl(it, finalUrl) }
            ?.fixImageQuality()

        val tags = document.select(
            "div.gmr-moviedata a[href*='/genre/'], div.gmr-movie-on a[rel='category tag'], a[href*='/genre/']"
        ).map { it.text().trim().cleanTitle() }
            .filter { it.isNotBlank() && !it.isUiText() }
            .distinct()
            .take(24)

        val year = document.selectFirst("div.gmr-moviedata strong:contains(Year:) > a, a[href*='/year/']")
            ?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(document.text())?.value?.toIntOrNull()

        val description = document.selectFirst(
            "div[itemprop=description] > p, div[itemprop=description], .entry-content p, .gmr-movie-data p, meta[property=og:description], meta[name=description]"
        )?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val trailer = document.selectFirst(
            "ul.gmr-player-nav a.gmr-trailer-popup[href], a.gmr-trailer-popup[href], a[href*='youtube.com'], a[href*='youtu.be']"
        )?.attr("href")?.takeIf { it.isNotBlank() }

        val rating = document.selectFirst(
            "div.gmr-meta-rating > span[itemprop=ratingValue], span[itemprop=ratingValue], div.gmr-rating-item"
        )?.text()?.trim()?.replace(",", ".")

        val actors = document.select("div.gmr-moviedata span[itemprop=actors] a, span[itemprop=actors] a, a[href*='/cast/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.isUiText() }
            .distinct()
            .take(30)

        val duration = document.selectFirst("div.gmr-moviedata span[property=duration], span[property=duration], .runtime, div.gmr-duration-item")
            ?.text()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()

        val recommendations = parseCards(document)
            .filter { it.url != fixedUrl }
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
        val fixedData = resolveUrl(data, mainUrl) ?: return false
        val response = app.get(
            fixedData,
            headers = siteHeaders,
            referer = "$mainUrl/",
            timeout = 30L
        )
        val document = response.document
        val finalUrl = response.url.ifBlank { fixedData }
        val directBase = getBaseUrl(finalUrl)
        val emitted = linkedSetOf<String>()
        var found = false

        suspend fun submit(raw: String?, referer: String = finalUrl) {
            val playerUrl = resolveUrl(raw, referer)?.cleanPlaybackUrl() ?: return
            if (playerUrl.isBadPlaybackUrl()) return
            val key = playerUrl.substringBefore("#")
            if (!emitted.add(key)) return

            if (playerUrl.isDirectMedia()) {
                callback(
                    newExtractorLink(
                        name,
                        name,
                        playerUrl,
                        if (playerUrl.isM3u8Like()) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = playerUrl.qualityFromUrl()
                        this.headers = siteHeaders + mapOf("Referer" to referer)
                    }
                )
                found = true
                return
            }

            runCatching {
                loadExtractor(playerUrl, referer, subtitleCallback) { link ->
                    if (!link.url.isBadPlaybackUrl()) {
                        found = true
                        callback(link)
                    }
                }
            }
        }

        collectPlayerUrls(document.html(), finalUrl).forEach { submit(it, finalUrl) }

        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if (!id.isNullOrBlank()) {
            document.select("div.tab-content-ajax[id]").forEach { tab ->
                val tabId = tab.attr("id").trim()
                if (tabId.isBlank()) return@forEach

                val ajax = runCatching {
                    app.post(
                        "$directBase/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to tabId,
                            "post_id" to id
                        ),
                        headers = ajaxHeaders(directBase, finalUrl),
                        referer = finalUrl
                    )
                }.getOrNull() ?: return@forEach

                collectPlayerUrls(ajax.text.ifBlank { ajax.document.html() }, directBase).forEach { submit(it, directBase) }
            }
        }

        document.select("ul.gmr-download-list li a[href], .gmr-download-list a[href]").forEach { link ->
            submit(link.attr("href"), finalUrl)
        }

        return found
    }

    private fun parseCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select("article.item, article.item.col-md-20, div.gmr-box-content article, div.row article")
            .forEach { card -> card.toSearchResult()?.let { results[it.url] = it } }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2.entry-title > a[href], h2 a[href], .entry-title a[href], a[href][title]") ?: return null
        val href = resolveUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!href.startsWith(mainUrl, ignoreCase = true)) return null

        val title = listOf(
            anchor.text().trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim()
        ).firstOrNull { !it.isNullOrBlank() && !it.isUiText() }
            ?.cleanTitle()
            ?: return null

        val poster = selectFirst("div.content-thumbnail img, a > img, img")
            ?.getImageAttr()
            ?.let { resolveUrl(it, href) }
            ?.fixImageQuality()

        val rating = selectFirst("div.gmr-rating-item, .gmr-meta-rating span[itemprop=ratingValue], span[itemprop=ratingValue]")
            ?.text()
            ?.replace(",", ".")
            ?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.getOrNull(1) }
            ?.toDoubleOrNull()

        val quality = selectFirst("div.gmr-quality-item a, .gmr-quality-item, a[href*='/quality/']")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.quality = qualityFromName(quality)
            rating?.let { this.score = Score.from10(it) }
        }
    }

    private fun collectPlayerUrls(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val normalized = html.unescapeCommon()
        val parsed = runCatching { Jsoup.parse(normalized, baseUrl) }.getOrNull()

        parsed?.select(
            "div.gmr-embed-responsive iframe[src], div.gmr-embed-responsive iframe[data-src], " +
                "div.gmr-embed-responsive iframe[data-litespeed-src], .gmr-player iframe[src], " +
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], source[src], video[src], video source[src]"
        )?.forEach { element ->
            listOf(
                element.attr("data-litespeed-src"),
                element.attr("data-src"),
                element.attr("src")
            ).firstOrNull { it.isNotBlank() }?.let { links.add(it) }
        }

        parsed?.select("option[value], select option[value], .mobius option[value], .mirror option[value]")?.forEach { option ->
            decodeServerValue(option.attr("value")).forEach { decoded ->
                collectPlayerUrls(decoded, baseUrl).forEach(links::add)
            }
        }

        Regex("""(?i)<(?:iframe|embed)[^>]+(?:src|data-src|data-litespeed-src)=['"]([^'"]+)['"]""")
            .findAll(normalized)
            .forEach { links.add(it.groupValues[1]) }

        Regex("""(?i)['"]((?:https?:)?//[^'"]+(?:embed|player|stream|drive|gofile|dood|streamtape|filemoon|vidhide|vidguard|voe|mp4upload|uqload|krakenfiles|filelions|gdplayer|hglink|ghbrisk|dhcplay|dingtezuni|movearnpre|mivalyo|bingezove|ryderjet|dm21|streamcasthub|\.m3u8|\.mp4)[^'"]*)['"]""")
            .findAll(normalized)
            .forEach { links.add(it.groupValues[1]) }

        Regex("""(?i)(?:file|source|src|url|link)\s*[:=]\s*['"]([^'"]+(?:\.m3u8|\.mp4|embed|player|stream)[^'"]*)['"]""")
            .findAll(normalized)
            .forEach { links.add(it.groupValues[1]) }

        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""")
            .findAll(normalized)
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .forEach { decoded -> collectPlayerUrls(decoded, baseUrl).forEach(links::add) }

        return links.mapNotNull { resolveUrl(it, baseUrl) }
            .map { it.cleanPlaybackUrl() }
            .filterNot { it.isBadPlaybackUrl() }
            .distinctBy { it.substringBefore("#") }
    }

    private fun decodeServerValue(value: String): List<String> {
        val clean = value.trim()
        if (clean.isBlank()) return emptyList()
        val decodedUrl = urlDecode(clean)
        val candidates = linkedSetOf(clean, decodedUrl)
        listOf(clean, decodedUrl).forEach { item ->
            decodeBase64(item)?.let { candidates.add(it) }
        }
        return candidates.map { it.unescapeCommon() }
    }

    private fun buildPageUrl(path: String, page: Int): String {
        if (path == "/") return if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val fixedPath = if (path.contains("%d")) path.format(page) else path
        return resolveUrl(fixedPath, mainUrl) ?: "$mainUrl/"
    }

    private fun hasNextPage(document: org.jsoup.nodes.Document, page: Int, hasItems: Boolean): Boolean {
        return document.selectFirst("a.next, a.nextpostslink, .pagination a:contains(Next), .pagination a:contains(»), ul.pagination li a:contains(»)") != null || (page == 1 && hasItems)
    }

    private fun ajaxHeaders(baseUrl: String, referer: String): Map<String, String> = siteHeaders + mapOf(
        "Accept" to "*/*",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to baseUrl,
        "Referer" to referer
    )

    private fun Element.getImageAttr(): String = when {
        hasAttr("data-src") -> attr("data-src")
        hasAttr("data-lazy-src") -> attr("data-lazy-src")
        hasAttr("data-litespeed-src") -> attr("data-litespeed-src")
        hasAttr("srcset") -> attr("srcset").substringBefore(" ")
        else -> attr("src")
    }

    private fun resolveUrl(raw: String?, baseUrl: String): String? {
        val value = raw?.trim()?.unescapeCommon()?.takeIf { it.isNotBlank() } ?: return null
        if (value.startsWith("javascript:", true) || value.startsWith("#")) return null
        return runCatching {
            when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("http://", true) || value.startsWith("https://", true) -> value
                value.startsWith("/") -> "${getBaseUrl(baseUrl)}$value"
                else -> {
                    val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                    URI(base).resolve(value).toString()
                }
            }
        }.getOrNull()
    }

    private fun getBaseUrl(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.host}" }
    }.getOrDefault(mainUrl)

    private fun decodeBase64(value: String): String? = runCatching {
        String(Base64.getDecoder().decode(value.trim()), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun urlDecode(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)

    private fun String.unescapeCommon(): String = this
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#039;", "'")

    private fun String.cleanPlaybackUrl(): String = this
        .replace("&amp;", "&")
        .trim()
        .trimEnd(',', ';')

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase(Locale.ROOT).substringBefore("?")
        return lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".mkv")
    }

    private fun String.isM3u8Like(): Boolean = lowercase(Locale.ROOT).contains(".m3u8")

    private fun String.isBadPlaybackUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.isBlank() ||
            lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.contains("googleads") ||
            lower.contains("/ads") ||
            lower.contains("banner") ||
            lower.contains("ambilbonus") ||
            lower.contains("vipmerahtoto") ||
            lower.contains("nagagas") ||
            lower.contains("gampangbanget") ||
            lower.contains("emasputih") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".css") ||
            lower.endsWith(".js")
    }

    private fun String.fixImageQuality(): String {
        val marker = Regex("(-\\d*x\\d*)").find(this)?.value ?: return this
        return replace(marker, "")
    }

    private fun String.cleanTitle(): String = replace(Regex("(?i)\\s*-\\s*LayarWarna21.*$"), "")
        .replace(Regex("(?i)^nonton\\s+film\\s+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.isUiText(): Boolean {
        val lower = lowercase(Locale.ROOT).trim()
        return lower in setOf("watch", "watch movie", "trailer", "download", "server 1", "server 2", "click to play", "turn off light")
    }

    private fun String.slugTitle(): String = substringBefore('?')
        .trimEnd('/')
        .substringAfterLast('/')
        .replace('-', ' ')
        .cleanTitle()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

    private fun qualityFromName(value: String?): Int {
        val text = value.orEmpty()
        return Regex("""(\d{3,4})\s*[pP]""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: when {
                text.contains("CAM", true) -> Qualities.P360.value
                text.contains("HD", true) -> Qualities.P720.value
                else -> Qualities.Unknown.value
            }
    }

    private fun String.qualityFromUrl(): Int = Regex("""(\d{3,4})[pP]""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: Qualities.Unknown.value
}
