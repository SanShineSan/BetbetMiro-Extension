package com.sad25kag.kikonime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KikoNime : MainAPI() {
    override var mainUrl = "https://kikonime.com"
    override var name = "KikoNime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/perpustakaan/page/%d/?type=donghua" to "Donghua",
        "$mainUrl/perpustakaan/page/%d/?type=movie" to "Movie",
        "$mainUrl/perpustakaan/page/%d/?genre%%5B%%5D=adventure" to "Adventure",
        "$mainUrl/perpustakaan/page/%d/?genre%%5B%%5D=fantasy" to "Fantasy",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = if (request.data.startsWith("home:")) {
            app.get(mainUrl, headers = siteHeaders).document
        } else {
            app.get(request.data.format(page), headers = siteHeaders).document
        }

        val results = when (request.data) {
            "home:popular" -> document.sectionCards("Popular Today")
            "home:donghua" -> document.sectionCards("Update Terbaru Donghua")
            "home:movie" -> document.sectionCards("Update Movie Terbaru")
            else -> document.select(".listupd article.bs, .bsx, .serieslist li").mapNotNull { it.toSearchResult() }
        }.distinctBy { it.url }

        val hasNext = !request.data.startsWith("home:") && document.select(
            "a.next, .pagination a[href*=/page/${page + 1}/], a[href*='paged=${page + 1}']"
        ).isNotEmpty()

        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val results = linkedMapOf<String, SearchResponse>()

        runCatching {
            val document = app.get("$mainUrl?s=$encoded", headers = siteHeaders).document
            document.select(".listupd article.bs, .bsx, .serieslist li")
                .mapNotNull { it.toSearchResult() }
                .forEach { results[it.url] = it }
        }

        runCatching {
            val ajax = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = siteHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                ),
                referer = "$mainUrl/",
                data = mapOf(
                    "action" to "ts_ac_do_search",
                    "ts_ac_query" to query.trim()
                )
            ).text
            ajax.parseSearchAjax().forEach { results[it.url] = it }
        }

        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(fixUrlSafe(url), headers = siteHeaders, referer = "$mainUrl/")
        val document = response.document
        val fixedUrl = response.url

        val rawTitle = document.selectFirst("h1.entry-title, .infox h1, h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("-")?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val title = if (document.isEpisodePage()) {
            document.selectFirst(".infox h1.entry-title, .infox h1, .headpost h2, .headpost a")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: rawTitle.cleanEpisodeTitle()
        } else rawTitle

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let(::fixUrlSafe)
            ?: document.selectFirst(".bigcontent .thumb img, .thumbook img, .headpost img, img")?.imageUrl()

        val plot = document.selectFirst(".entry-content p, .desc, .mindesc, meta[name=description]")?.let {
            if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
        }?.cleanPlot()

        val genres = document.select(".genxed a, .info-content a[href*=/genres/], a[href*=/genres/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val typeText = document.infoValue("Type")
            ?: document.selectFirst(".typez")?.text()
            ?: rawTitle
        val type = getType(typeText, fixedUrl)
        val status = getStatus(document.infoValue("Status") ?: document.text())
        val year = Regex("""\b(19|20)\d{2}\b""").find(
            document.infoValue("Released") ?: document.infoValue("Season") ?: document.text()
        )?.value?.toIntOrNull()
        val score = document.selectFirst(".numscore")?.text()?.toRating()
            ?: Regex("""Rating\s*([0-9.]+)""", RegexOption.IGNORE_CASE).find(document.text())?.groupValues?.getOrNull(1)
                ?.toRating()

        val episodes = document.select(".eplister li, .episodelist li, .eplister a[href], .episodelist a[href]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })

        val recommendations = document.select(".listupd article.bs, .serieslist li")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }
            .take(12)

        return if (episodes.isNotEmpty() && fixedUrl.contains("/perpustakaan/", true)) {
            newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
                this.showStatus = status
                this.score = score
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newMovieLoadResponse(rawTitle.cleanEpisodeTitle(), fixedUrl, type, fixedUrl) {
                posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
                this.score = score
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = fixUrlSafe(data)
        val response = app.get(pageUrl, headers = siteHeaders, referer = "$mainUrl/")
        val document = response.document
        val html = response.text
        var emitted = false
        val seen = linkedSetOf<String>()

        fun mark(value: Boolean) {
            if (value) emitted = true
        }

        suspend fun emitDirect(url: String, referer: String, label: String = name) {
            val clean = fixUrlSafe(url.cleanMediaUrl())
            if (!clean.isDirectMedia()) return
            if (!seen.add(clean.substringBefore("#"))) return
            val type = if (clean.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, label, clean, type) {
                this.referer = referer
                this.quality = getQualityFromName(clean)
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*"
                )
            })
            emitted = true
        }

        suspend fun inspect(candidate: String, label: String = name, referer: String = pageUrl, depth: Int = 0) {
            if (depth > 2) return
            val clean = fixUrlSafe(candidate.cleanMediaUrl())
            if (clean.isBlank()) return
            if (!seen.add("inspect:${clean.substringBefore("#")}")) return

            if (clean.isDirectMedia()) {
                emitDirect(clean, referer, label)
                return
            }

            mark(loadExtractor(clean, referer, subtitleCallback, callback))

            if (clean.contains("dailymotion.com", true) || clean.contains("geo.dailymotion.com", true)) {
                mark(KikoDailyMotion().getUrlResult(clean, referer, subtitleCallback, callback))
                mark(KikoGeoDailyMotion().getUrlResult(clean, referer, subtitleCallback, callback))
                return
            }

            if (clean.contains("ok.ru", true) || clean.contains("odnoklassniki", true)) {
                mark(KikoOdnoklassniki().getUrlResult(clean, referer, subtitleCallback, callback))
                return
            }

            runCatching {
                val hostPage = app.get(clean, headers = siteHeaders, referer = referer)
                val hostHtml = hostPage.text
                val unpacked = getAndUnpack(hostHtml)
                (hostHtml + "\n" + unpacked).extractUrls().forEach { nested ->
                    inspect(nested, label, clean, depth + 1)
                }
            }
        }

        document.select("#pembed iframe[src], .player-embed iframe[src], .video-content iframe[src], iframe[src]")
            .mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } }
            .forEach { inspect(it, "Iframe") }

        document.select("select.mirror option[value], select option[value]").forEach { option ->
            val label = option.text().trim().ifBlank { "Mirror" }
            val value = option.attr("value").trim()
            if (value.isNotBlank()) {
                val decoded = value.tryDecodeBase64()
                decoded.extractUrls().forEach { inspect(it, label) }
                Jsoup.parse(decoded).select("iframe[src], video[src], source[src], a[href]").forEach { el ->
                    val src = el.attr("src").ifBlank { el.attr("href") }
                    if (src.isNotBlank()) inspect(src, label)
                }
            }
        }

        html.extractUrls().forEach { inspect(it, "Raw") }

        return emitted
    }

    private fun Document.sectionCards(heading: String): List<SearchResponse> {
        val box = select(".bixbox").firstOrNull { item ->
            item.selectFirst(".releases h2, .releases h3")?.text()?.contains(heading, true) == true
        } ?: return emptyList()
        return box.select("article.bs").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.contains(mainUrl)) return null
        val rawTitle = selectFirst("h2, h4, .epl-title")?.text()?.trim()
            ?: selectFirst(".tt")?.ownText()?.trim()
            ?: anchor.attr("title").trim()
        val title = rawTitle.cleanCardTitle()
        if (title.isBlank()) return null

        val poster = selectFirst("img")?.imageUrl()
        val typeText = selectFirst(".typez")?.text()?.trim()
            ?: selectFirst(".status")?.text()?.trim()
            ?: title
        val episodeNumber = Regex("""(?:Ep|Episode|Eps)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val score = selectFirst(".numscore")?.text()?.toRating()

        return newAnimeSearchResponse(title, href, getType(typeText, href)) {
            this.posterUrl = poster
            this.score = score
            addDubStatus("Sub", episodeNumber)
        }
    }

    private fun Element.toEpisode(): Episode? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = selectFirst(".epl-title, h3")?.text()?.trim()
            ?: anchor.attr("title").trim().ifBlank { anchor.text().trim() }
        val epNum = selectFirst(".epl-num")?.text()?.episodeNumber()
            ?: title.episodeNumber()
            ?: href.episodeNumber()
        val date = selectFirst(".epl-date")?.text()?.trim()
        return newEpisode(href) {
            this.name = title.ifBlank { "Episode ${epNum ?: ""}" }.cleanCardTitle()
            this.episode = epNum
            this.description = date
        }
    }

    private fun String.parseSearchAjax(): List<SearchResponse> {
        fun String.field(name: String): String? =
            Regex(""""$name"\s*:\s*"((?:\\.|[^"\\])*)"""").find(this)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("\\\"", "\"")
                ?.htmlUnescape()

        return Regex("""\{\s*"ID"\s*:\s*\d+.*?"post_link"\s*:\s*".*?".*?\}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(this)
            .mapNotNull { match ->
                val item = match.value
                val link = item.field("post_link") ?: return@mapNotNull null
                val title = item.field("post_title")?.cleanCardTitle() ?: return@mapNotNull null
                val poster = item.field("post_image")
                val type = item.field("post_type") ?: title
                newAnimeSearchResponse(title, fixUrlSafe(link), getType(type, link)) {
                    posterUrl = poster?.let(::fixUrlSafe)
                }
            }
            .distinctBy { it.url }
            .toList()
    }

    private fun getType(text: String, url: String = ""): TvType = when {
        text.contains("Movie", true) || url.contains("movie", true) -> TvType.AnimeMovie
        text.contains("OVA", true) || text.contains("Special", true) -> TvType.OVA
        else -> TvType.Anime
    }

    private fun getStatus(text: String): ShowStatus = when {
        text.contains("Ongoing", true) -> ShowStatus.Ongoing
        text.contains("Completed", true) || text.contains("END", true) -> ShowStatus.Completed
        else -> ShowStatus.Completed
    }

    private fun Document.isEpisodePage(): Boolean =
        selectFirst("#pembed iframe, .video-content iframe, select.mirror") != null

    private fun Document.infoValue(label: String): String? {
        return select(".spe span, .info-content span").firstNotNullOfOrNull { span ->
            val key = span.selectFirst("b")?.text()?.replace(":", "")?.trim() ?: return@firstNotNullOfOrNull null
            if (!key.equals(label, true)) return@firstNotNullOfOrNull null
            span.text().replace(span.selectFirst("b")?.text() ?: "", "").replace(":", "").trim().takeIf { it.isNotBlank() }
        }
    }

    private fun Element.imageUrl(): String? {
        return listOf("data-src", "data-lazy-src", "data-original", "src")
            .firstNotNullOfOrNull { attr -> attr(attr).takeIf { it.isNotBlank() && !it.startsWith("data:") } }
            ?.let(::fixUrlSafe)
    }


    private fun fixUrlSafe(url: String): String = fixUrlNull(url) ?: url

    private fun String.tryDecodeBase64(): String {
        val clean = trim().htmlUnescape()
        return runCatching { base64Decode(clean) }.getOrElse { clean }
    }

    private fun String.extractUrls(): List<String> {
        val source = this.htmlUnescape().replace("\\/", "/")
        val results = linkedSetOf<String>()
        Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE).findAll(source).forEach { results.add(it.value) }
        Regex("""(?:src|file|url|href)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(source).forEach {
            results.add(it.groupValues[1])
        }
        return results.map { it.trim().trim('"', '\'', ',', ';', ')', '(', ']', '[', '{', '}', '<', '>') }
            .filter { it.startsWith("http", true) }
            .distinct()
    }

    private fun String.isDirectMedia(): Boolean = contains(".m3u8", true) ||
        contains(".mp4", true) ||
        contains(".webm", true) ||
        contains(".mkv", true) ||
        contains("videoplayback", true)

    private fun String.cleanMediaUrl(): String = htmlUnescape()
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .trim()

    private fun String.cleanCardTitle(): String = htmlUnescape()
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.cleanEpisodeTitle(): String = cleanCardTitle()
        .replace(Regex("\\s*-\\s*KIKONIME.*$", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun String.cleanPlot(): String = htmlUnescape()
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.length > 15 } ?: ""

    private fun String.episodeNumber(): Int? =
        Regex("""(?:Episode|Eps|Ep)?\s*(\d+)""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String.toRating(): Score? = toDoubleOrNull()?.let { Score.from10(it) }

    private fun String.htmlUnescape(): String = this
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
