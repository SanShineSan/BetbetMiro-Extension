package com.nodrakorid

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object NoDrakorIDParser {
    private val cardSelectors = listOf(
        "article",
        ".result-item",
        ".items .item",
        ".item",
        ".movie",
        ".ml-item",
        ".post",
        ".poster",
        ".boxinfo",
        ".module .content .items .item",
        ".movies-list .movie",
        "a[href]:has(img)"
    ).joinToString(",")

    private val metadataSelectors = listOf(
        ".sheader",
        ".data",
        ".extra",
        ".sgeneros",
        ".mta",
        ".entry-meta",
        ".postmeta",
        ".mvic-desc",
        ".mvic-info",
        ".movie-info",
        ".entry-content",
        "article"
    ).joinToString(",")

    fun parseHomeCards(api: MainAPI, doc: Document): List<SearchResponse> {
        val cards = parseCards(api, doc).take(40)
        if (cards.isNotEmpty()) return cards

        return dedupeCards(doc.select("a[href]").mapNotNull { anchor -> parseCard(api, anchor) })
            .take(40)
    }

    fun parseCards(api: MainAPI, doc: Document, query: String? = null): List<SearchResponse> {
        val needle = query?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val cards = doc.select(cardSelectors)
            .mapNotNull { parseCard(api, it) }
            .filter { needle == null || it.name.lowercase().contains(needle) }
        return dedupeCards(cards).take(100)
    }

    private fun parseCard(api: MainAPI, element: Element): SearchResponse? {
        val anchor = bestContentAnchor(element) ?: return null
        val url = NoDrakorIDUtils.absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return null
        if (!NoDrakorIDUtils.isContentUrl(url)) return null

        val image = anchor.selectFirst("img") ?: element.selectFirst("img")
        val rawTitle = element.selectFirst("h1 a, h2 a, h3 a, .entry-title a, .data h3 a, .data h2 a, .title a, .tt, .name a")?.text()
            ?: anchor.attr("title")
                .ifBlank { image?.attr("alt").orEmpty() }
                .ifBlank { element.selectFirst("h1, h2, h3, .entry-title, .title, .tt, .name")?.text().orEmpty() }
                .ifBlank { anchor.text() }
                .ifBlank { url.trimEnd('/').substringAfterLast('/').replace('-', ' ') }
        val title = NoDrakorIDUtils.cleanTitle(rawTitle)
        if (title.isBlank() || title.equals("Watch", true) || title.equals("Watch Movie", true) || title.length < 2) return null

        val text = element.text()
        val poster = NoDrakorIDUtils.pickImage(api.mainUrl, image, element)
        val type = NoDrakorIDUtils.typeFrom(url, title, text)
        val quality = element.selectFirst(".quality, .Qlty, .mli-quality, .calidad, span.quality, span:contains(HD), span:contains(CAM)")?.text()
        val year = NoDrakorIDUtils.extractYear(text) ?: NoDrakorIDUtils.extractYear(title)

        return api.newMovieSearchResponse(title, url, type) {
            posterUrl = poster
            quality?.let(NoDrakorIDUtils::cleanText)?.takeIf { it.isNotBlank() }?.let { addQuality(it) }
            this.year = year
        }
    }

    suspend fun parseLoad(api: MainAPI, url: String, doc: Document): LoadResponse? {
        val title = NoDrakorIDUtils.cleanTitle(
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1.entry-title, h1")?.text()
                ?: doc.title()
        ).ifBlank { return null }

        val metadataText = doc.select(metadataSelectors).joinToString(" ") { it.text() }
        val poster = NoDrakorIDUtils.extractMetaImage(api.mainUrl, doc)
        val plot = NoDrakorIDUtils.cleanText(
            doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.select(".entry-content p, .wp-content p, .description, .desc, .storyline, .wp-content").firstOrNull { it.text().length > 35 }?.text()
        ).ifBlank { null }

        val tags = doc.select("a[rel=tag], .sgeneros a, .genxed a, .mta a, a[href*='/genre/'], a[href*='/country/']")
            .map { NoDrakorIDUtils.cleanText(it.text()) }
            .filter { it.length in 2..36 && !it.equals("Watch", true) && !it.equals("Trailer", true) }
            .distinct()
            .take(24)

        val recommendations = parseCards(api, doc).filterNot { it.url == url }.take(16)
        val year = NoDrakorIDUtils.extractYear(metadataText) ?: NoDrakorIDUtils.extractYear(title)
        val duration = NoDrakorIDUtils.extractDuration(metadataText)
        val rating = NoDrakorIDUtils.extractRating(doc.selectFirst(".rating, .imdb, .dt_rating_vgs, .starstruck-rating")?.text())
            ?: NoDrakorIDUtils.extractRating(metadataText)
        val episodes = parseEpisodes(api, doc)
        val type = if (NoDrakorIDUtils.hasUnsupportedOnlyPlayer(doc.outerHtml())) {
            TvType.CustomMedia
        } else {
            NoDrakorIDUtils.typeFrom(url, title, metadataText)
        }

        return if (episodes.isNotEmpty() && type != TvType.Movie && type != TvType.CustomMedia) {
            api.newTvSeriesLoadResponse(title, url, type, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.duration = duration
                this.score = Score.from10(rating)
                this.recommendations = recommendations
            }
        } else {
            api.newMovieLoadResponse(title, url, type, url) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.duration = duration
                this.score = Score.from10(rating)
                this.recommendations = recommendations
            }
        }
    }

    private fun parseEpisodes(api: MainAPI, doc: Document): List<Episode> {
        return doc.select("a[href*='/episode/'], a[href*='/eps/'], a[href*='?episode='], .episodios a[href], .episode a[href], .se-c a[href], .eplister a[href], .all-episodes a[href]")
            .mapNotNull { anchor ->
                val epUrl = NoDrakorIDUtils.absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return@mapNotNull null
                if (!NoDrakorIDUtils.isContentUrl(epUrl) && !epUrl.contains("episode", true) && !epUrl.contains("/eps/", true)) return@mapNotNull null
                val rawName = NoDrakorIDUtils.cleanText(anchor.text()).ifBlank {
                    NoDrakorIDUtils.cleanTitle(anchor.attr("title"))
                }.ifBlank { epUrl.trimEnd('/').substringAfterLast('/').replace('-', ' ') }
                api.newEpisode(epUrl) {
                    name = rawName
                    season = NoDrakorIDUtils.seasonNumber(rawName)
                    episode = NoDrakorIDUtils.episodeNumber(rawName)
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 }.thenBy { it.name })
    }

    private fun bestContentAnchor(element: Element): Element? {
        if (element.tagName().equals("a", true) && element.hasAttr("href")) return element
        return element.selectFirst("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], .data h3 a[href], .data h2 a[href], .title a[href], .name a[href]")
            ?: element.selectFirst("a[href*='/episode/'], a[href*='/eps/'], a[href*='/tv/'], a[href]:has(img)")
            ?: element.selectFirst("a[href]")
    }

    private fun dedupeCards(cards: List<SearchResponse>): List<SearchResponse> {
        val seenUrls = linkedSetOf<String>()
        val seenTitles = linkedSetOf<String>()
        return cards.filter { card ->
            val urlKey = normalizeCardUrl(card.url)
            val titleKey = normalizeCardTitle(card.name)
            val urlFresh = urlKey.isBlank() || seenUrls.add(urlKey)
            val titleFresh = titleKey.isBlank() || seenTitles.add(titleKey)
            urlFresh && titleFresh
        }
    }

    private fun normalizeCardUrl(url: String): String {
        return NoDrakorIDUtils.decodeKnownRedirect(url)
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
            .lowercase()
    }

    private fun normalizeCardTitle(title: String): String {
        return NoDrakorIDUtils.cleanTitle(title)
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[^a-z0-9 ]"), "")
            .trim()
    }
}
