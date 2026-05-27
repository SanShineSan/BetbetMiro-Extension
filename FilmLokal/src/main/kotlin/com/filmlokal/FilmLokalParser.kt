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
        "article", ".ml-item", ".movie", ".movie-item", ".item", ".result-item", ".film", ".box", ".col-md-2", ".col-md-3", ".col-sm-3", ".owl-item"
    ).joinToString(",")

    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val containers = document.select(cardSelectors).ifEmpty { document.select("a[href]").map { it.parent() }.distinct() }
        return containers
            .mapNotNull { parseCard(api, it) }
            .distinctBy { it.url }
            .take(48)
    }

    private fun parseCard(api: MainAPI, element: Element): SearchResponse? {
        val image = element.selectFirst("img[src], img[data-src], img[data-lazy-src], img[data-original], img[srcset]")
            ?: element.parent()?.selectFirst("img[src], img[data-src], img[data-lazy-src], img[data-original], img[srcset]")
            ?: return null
        val link = element.selectFirst("a[href*='${api.mainUrl}']:has(img), a[href]:has(img), h2 a[href], h3 a[href], .title a[href], a[title][href]")
            ?: image.parents().select("a[href]").first()
            ?: element.selectFirst("a[href]")
            ?: return null
        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isVideoUrl(href)) return null

        val title = cleanText(
            link.attr("title").ifBlank { link.text() }.ifBlank {
                image.attr("alt").ifBlank { image.attr("title") }
            }
        ).ifBlank { return null }

        val poster = extractPoster(api.mainUrl, image, element)
        if (!isValidPoster(poster)) return null
        val type = typeFromUrlOrTitle(href, title)
        val quality = cleanText(element.selectFirst(".quality, .mli-quality, .jtip-quality, a[href*='/quality/']")?.text()).ifBlank { null }
        return api.newMovieSearchResponse(title, href, type) {
            posterUrl = poster
            this.quality = quality
        }
    }

    suspend fun parseLoadResponse(api: MainAPI, url: String, document: Document): LoadResponse? {
        val title = cleanText(
            document.selectFirst("h1.entry-title, h1, .title, .heading")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        ).removeSuffix(" - Filmlokal").ifBlank { return null }

        val poster = extractPoster(api.mainUrl, document.selectFirst(".poster img, .thumb img, .mvic-thumb img, article img, img[alt]"), document)
            ?: absoluteUrl(api.mainUrl, document.selectFirst("meta[property=og:image]")?.attr("content"))

        val plot = cleanText(
            document.selectFirst(".desc, .description, .entry-content p, .sinopsis, .synopsis")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
        )

        val tags = document.select("a[href*='/${'$'}{api.mainUrl.substringAfter("//")}/']")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..30 }
            .filterNot { it.equals("Watch", true) || it.equals("Trailer", true) || it.equals("Download", true) }
            .distinct()
            .take(16)
            .ifEmpty {
                document.select("a[href*='/action/'], a[href*='/drama/'], a[href*='/horror/'], a[href*='/romance/'], a[href*='/country/'], a[href*='/year/']")
                    .map { cleanText(it.text()) }
                    .filter { it.length in 2..30 }
                    .distinct()
                    .take(16)
            }

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
        val selectors = ".eps a[href], .episode a[href], .episodes a[href], a[href*='episode'], a[href*='season']"
        val list = document.select(selectors).mapNotNull { a ->
            val href = absoluteUrl(api.mainUrl, a.attr("href")) ?: return@mapNotNull null
            if (!isVideoUrl(href)) return@mapNotNull null
            if (!seen.add(href.substringBefore("#"))) return@mapNotNull null
            val text = cleanText(a.text()).ifBlank { cleanText(a.attr("title")) }.ifBlank { "Episode" }
            api.newEpisode(href) { name = text }
        }
        return list.ifEmpty { listOf(api.newEpisode(fallbackUrl) { name = "Movie" }) }
    }

    fun extractPoster(baseUrl: String, image: Element?, container: Element? = null): String? {
        val candidates = mutableListOf<String?>()
        if (image != null) {
            candidates += image.attr("data-src")
            candidates += image.attr("data-lazy-src")
            candidates += image.attr("data-original")
            candidates += image.attr("data-wpfc-original-src")
            candidates += image.attr("srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ")
            candidates += image.attr("src")
        }
        if (container != null) {
            candidates += container.selectFirst("meta[property=og:image]")?.attr("content")
            candidates += container.selectFirst("noscript img[src]")?.attr("src")
            val style = container.attr("style").ifBlank { container.selectFirst("[style*=background]")?.attr("style").orEmpty() }
            candidates += Regex("""url\((['\"]?)(.*?)\1\)""").find(style)?.groupValues?.getOrNull(2)
        }
        return candidates.asSequence()
            .mapNotNull { absoluteUrl(baseUrl, it) }
            .firstOrNull { isValidPoster(it) }
    }
}
