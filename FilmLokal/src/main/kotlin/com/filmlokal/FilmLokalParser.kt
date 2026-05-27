package com.filmlokal

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.filmlokal.FilmLokalUtils.absoluteUrl
import com.filmlokal.FilmLokalUtils.cleanText
import com.filmlokal.FilmLokalUtils.durationMinutes
import com.filmlokal.FilmLokalUtils.isValidPoster
import com.filmlokal.FilmLokalUtils.isVideoUrl
import com.filmlokal.FilmLokalUtils.typeFromUrlOrTitle
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object FilmLokalParser {
    private val cardSelectors = listOf(
        "article",
        ".ml-item",
        ".movie",
        ".movie-item",
        ".result-item",
        ".film",
        ".post",
        ".item:has(img)",
        ".owl-item:has(img)",
        "a[href]:has(img)"
    ).joinToString(",")

    private const val IMAGE_SELECTOR =
        "img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-wpfc-original-src], img[srcset]"

    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val primary = document.select(cardSelectors)
        val containers: List<Element> = if (primary.isNotEmpty()) {
            primary.toList()
        } else {
            document.select("a[href]:has(img)").toList()
        }

        return containers
            .mapNotNull { parseCard(api, it) }
            .distinctBy { it.url }
            .take(48)
    }

    private fun parseCard(api: MainAPI, element: Element): SearchResponse? {
        val link = when {
            element.tagName().equals("a", ignoreCase = true) && element.hasAttr("href") -> element
            else -> element.selectFirst("a[href]:has(img)")
                ?: element.selectFirst("h2 a[href], h3 a[href], .title a[href], a[title][href]")
                ?: return null
        }

        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isVideoUrl(href)) return null

        val image = link.selectFirst(IMAGE_SELECTOR)
            ?: element.selectFirst(IMAGE_SELECTOR)
            ?: element.parent()?.selectFirst(IMAGE_SELECTOR)
            ?: return null

        val title = cleanTitle(
            link.attr("title").ifBlank { link.attr("aria-label") }.ifBlank { link.text() }.ifBlank {
                image.attr("alt").ifBlank { image.attr("title") }
            }
        ).ifBlank { return null }

        val poster = extractPoster(api.mainUrl, image, link) ?: extractPoster(api.mainUrl, image, element)
        if (!isValidPoster(poster)) return null

        val type = typeFromUrlOrTitle(href, title)
        return api.newMovieSearchResponse(title, href, type) {
            posterUrl = poster
        }
    }

    suspend fun parseLoadResponse(api: MainAPI, url: String, document: Document): LoadResponse? {
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1, .title, .heading")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        ).ifBlank { return null }

        val contentRoot = document.selectFirst("article, .entry-content, .single, .post, main") ?: document
        val poster = extractPoster(
            api.mainUrl,
            contentRoot.selectFirst(".poster img, .thumb img, .mvic-thumb img, .wp-post-image, img[src*='uploads'], img[alt], img[title]"),
            contentRoot
        ) ?: absoluteUrl(api.mainUrl, document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?.takeIf { isValidPoster(it) }

        val plot = cleanText(
            document.selectFirst(".desc, .description, .entry-content p, .sinopsis, .synopsis")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
        )

        val tags = document.select(
            "a[href*='/genre/'], " +
                "a[href*='/country/'], " +
                "a[href*='/year/'], " +
                "a[href*='/quality/'], " +
                "a[href*='/action/'], " +
                "a[href*='/adventure/'], " +
                "a[href*='/animation/'], " +
                "a[href*='/comedy/'], " +
                "a[href*='/crime/'], " +
                "a[href*='/drama/'], " +
                "a[href*='/fantasy/'], " +
                "a[href*='/horror/'], " +
                "a[href*='/mystery/'], " +
                "a[href*='/romance/'], " +
                "a[href*='/sci-fi/'], " +
                "a[href*='/thriller/']"
        )
            .map { cleanTitle(it.text()) }
            .filter { it.length in 2..30 }
            .filterNot { tag ->
                tag.equals("Watch", true) ||
                    tag.equals("Trailer", true) ||
                    tag.equals("Download", true) ||
                    tag.equals("Home", true)
            }
            .distinct()
            .take(16)

        val episodes = parseEpisodes(api, document, url)
        val recommendations = parseListing(api, document).filterNot { it.url == url }.take(12)
        val type = if (episodes.size > 1) TvType.TvSeries else typeFromUrlOrTitle(url, title)

        return if (type == TvType.TvSeries) {
            api.newTvSeriesLoadResponse(title, url, type, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            api.newMovieLoadResponse(title, url, type, url) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                duration = durationMinutes(document.text())
            }
        }
    }

    private fun parseEpisodes(api: MainAPI, document: Document, fallbackUrl: String): List<Episode> {
        val seen = linkedSetOf<String>()
        val selectors = ".eps a[href], .episode a[href], .episodes a[href], .episodelist a[href], a[href*='episode'], a[href*='season']"
        val episodes = document.select(selectors).mapNotNull { anchor ->
            val href = absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return@mapNotNull null
            if (!isVideoUrl(href)) return@mapNotNull null
            val normalized = href.substringBefore("#")
            if (!seen.add(normalized)) return@mapNotNull null

            val text = cleanTitle(anchor.text())
                .ifBlank { cleanTitle(anchor.attr("title")) }
                .ifBlank { "Episode" }

            api.newEpisode(href) {
                name = text
            }
        }

        return episodes.ifEmpty {
            listOf(
                api.newEpisode(fallbackUrl) {
                    name = "Movie"
                }
            )
        }
    }

    fun extractPoster(baseUrl: String, image: Element?, container: Element? = null): String? {
        val candidates = mutableListOf<String?>()
        if (image != null) {
            candidates += image.attr("data-src")
            candidates += image.attr("data-lazy-src")
            candidates += image.attr("data-original")
            candidates += image.attr("data-wpfc-original-src")
            candidates += image.attr("srcset")
                .split(",")
                .firstOrNull()
                ?.trim()
                ?.substringBefore(" ")
            candidates += image.attr("src")
        }
        if (container != null) {
            candidates += container.selectFirst("noscript img[src]")?.attr("src")
            candidates += container.selectFirst("img[data-src], img[data-lazy-src], img[data-original], img[src]")?.let {
                it.attr("data-src").ifBlank { it.attr("data-lazy-src").ifBlank { it.attr("data-original").ifBlank { it.attr("src") } } }
            }
            val style = container.attr("style")
                .ifBlank { container.selectFirst("[style*=background]")?.attr("style").orEmpty() }
            candidates += Regex("""url\((['\"]?)(.*?)\1\)""").find(style)?.groupValues?.getOrNull(2)
            candidates += container.selectFirst("meta[property=og:image]")?.attr("content")
        }
        return candidates.asSequence()
            .mapNotNull { absoluteUrl(baseUrl, it) }
            .firstOrNull { isValidPoster(it) }
    }

    private fun cleanTitle(value: String?): String {
        return cleanText(value)
            .replace(Regex("(?i)^permalink\\s+to:\\s*"), "")
            .replace(Regex("(?i)^permalink:\\s*"), "")
            .replace(Regex("(?i)\\s+-\\s+filmlokal$"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia$"), " Sub")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
