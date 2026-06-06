package com.sad25kag.duniafilm21

import com.lagradost.cloudstream3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object DuniaFilm21Parser {
    private val homeCardSelectors = listOf(
        "#gmr-main-load article.item",
        "#gmr-main-load article.item-infinite",
        ".gmr-module-posts .gmr-item-modulepost"
    ).joinToString(",")

    fun parseHomeItems(api: MainAPI, document: Document, baseUrl: String, defaultType: TvType = TvType.Movie): List<SearchResponse> {
        return document.select(homeCardSelectors)
            .mapNotNull { it.toSearchResponse(api, baseUrl, defaultType) }
            .distinctBy { it.url }
            .filter { it.name.length >= 2 }
    }

    private fun Element.toSearchResponse(api: MainAPI, baseUrl: String, defaultType: TvType = TvType.Movie): SearchResponse? {
        val titleElement = selectFirst("h2.entry-title a[href], .entry-title a[href]")
        val imageAnchor = selectFirst(".content-thumbnail a[href], a[itemprop=url]")
        val anchor = titleElement ?: imageAnchor ?: selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").absUrlDf21(baseUrl) ?: return null
        if (!href.sameHostDf21(DuniaFilm21Provider.DEFAULT_MAIN_URL)) return null
        if (href.isNoiseUrlDf21() || href.contains("/Genre/", true) || href.contains("/year/", true)) return null

        val title = listOf(
            titleElement?.text(),
            titleElement?.attr("title"),
            anchor.attr("title").removePrefix("Permalink to:"),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() }?.cleanTitleDf21() ?: return null

        if (title.length < 2) return null
        if (title.equals("Watch Movie", true) || title.equals("Download", true)) return null

        val poster = posterUrl(this, baseUrl)
        val type = inferType(title, href, defaultType)

        return when (type) {
            TvType.TvSeries, TvType.AsianDrama -> api.newTvSeriesSearchResponse(title, href, type) { posterUrl = poster }
            else -> api.newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    fun posterUrl(element: Element, baseUrl: String): String? {
        val candidates = mutableListOf<String>()
        element.select(".content-thumbnail img, img.wp-post-image, img, source").forEach { img ->
            listOf(
                img.attr("data-src"),
                img.attr("data-original"),
                img.attr("data-lazy-src"),
                img.attr("data-image"),
                img.attr("data-poster"),
                img.attr("poster"),
                img.attr("src")
            ).filter { it.isNotBlank() && !it.startsWith("data:") }.forEach(candidates::add)

            listOf("srcset", "data-srcset").forEach { attr ->
                img.attr(attr)
                    .split(",")
                    .map { it.trim().substringBefore(" ").trim() }
                    .filter { it.isNotBlank() && !it.startsWith("data:") }
                    .forEach(candidates::add)
            }
        }

        return candidates.distinct()
            .filterNot { value ->
                val lower = value.lowercase()
                lower.contains("logo") || lower.contains("favicon") || lower.contains("placeholder") || lower.endsWith(".gif")
            }
            .sortedByDescending { it.posterCandidateScoreDf21() }
            .firstOrNull()
            ?.replace(Regex("-\\d+x\\d+(?=\\.(?:jpg|jpeg|png|webp))", RegexOption.IGNORE_CASE), "")
            ?.absUrlDf21(baseUrl)
    }

    fun parseTitle(document: Document): String {
        return listOf(
            document.selectFirst("h1.entry-title")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[name=title]")?.attr("content"),
            document.title()
        ).firstOrNull { !it.isNullOrBlank() }?.cleanTitleDf21().orEmpty()
    }

    fun parsePoster(document: Document, pageUrl: String): String? {
        return listOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")?.attr("content"),
            document.selectFirst("article.single-thumb img.wp-post-image")?.attr("src"),
            document.selectFirst(".content-thumbnail img.wp-post-image")?.attr("src")
        ).firstOrNull { !it.isNullOrBlank() }?.absUrlDf21(pageUrl) ?: posterUrl(document.body(), pageUrl)
    }

    fun parsePlot(document: Document): String? {
        val firstParagraph = document.selectFirst(".entry-content.entry-content-single > p")?.text()
            ?: document.selectFirst(".entry-content > p")?.text()
        return listOf(
            firstParagraph,
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("meta[name=description]")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() }?.cleanDf21()?.takeIf { it.length > 10 }
    }

    fun parseTags(document: Document): List<String> {
        val genreBlock = document.select(".gmr-moviedata")
            .firstOrNull { it.selectFirst("strong")?.text()?.contains("Genre", ignoreCase = true) == true }
        return (genreBlock?.select("a") ?: document.select("a[href*='/Genre/']"))
            .map { it.text().cleanDf21() }
            .filter { it.isNotBlank() && it.length <= 40 }
            .distinct()
    }

    fun parseYear(document: Document, title: String): Int? {
        val yearBlock = document.select(".gmr-moviedata")
            .firstOrNull { it.selectFirst("strong")?.text()?.contains("Year", ignoreCase = true) == true }
            ?.text()
        return Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: yearBlock?.let { Regex("""\b(19\d{2}|20\d{2})\b""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    fun parseEpisodes(api: MainAPI, document: Document, pageUrl: String): List<Episode> {
        val candidates = document.select(
            ".gmr-listseries a[href], .gmr-eps-list a[href], ul.gmr-episodes li a[href], .episodios a[href], .eplister a[href], .list-episode a[href], a[href*='/episode-'], a[href*='?episode=']"
        )
        return candidates.mapNotNull { element ->
            val href = element.attr("href").absUrlDf21(pageUrl) ?: return@mapNotNull null
            if (href == pageUrl || href.isNoiseUrlDf21()) return@mapNotNull null
            val text = element.text().cleanDf21()
            val number = Regex("""(?i)(?:episode|eps|ep|e)[-_\s]*(\d+)""").find("$text $href")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""^\D*(\d+)\D*$""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val season = Regex("""(?i)(?:season|s)[-_\s]*(\d+)""").find("$text $href")?.groupValues?.getOrNull(1)?.toIntOrNull()
            api.newEpisode(href) {
                name = text.takeIf { it.isNotBlank() && it.length > 1 } ?: number?.let { "Episode $it" } ?: "Episode"
                episode = number
                this.season = season
            }
        }.distinctBy { it.data }
    }

    fun hasNextPage(document: Document, page: Int): Boolean {
        return document.select("a.next.page-numbers, .pagination a.next, .nav-links a.next").isNotEmpty() ||
            document.select("a.page-numbers").any { it.text().trim().toIntOrNull()?.let { number -> number > page } == true }
    }

    fun inferType(title: String, url: String, defaultType: TvType = TvType.Movie): TvType {
        val text = "$title $url".lowercase()
        return when {
            text.contains("/tv/") || text.contains("series") || text.contains("season") || text.contains("episode") -> TvType.TvSeries
            text.contains("drama china") || text.contains("drama korea") || text.contains("drakor") -> TvType.AsianDrama
            else -> defaultType
        }
    }

    fun parseDocumentFromHtml(html: String, baseUrl: String): Document = Jsoup.parse(html.cleanDf21(), baseUrl)
}
