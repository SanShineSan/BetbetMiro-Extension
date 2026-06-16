package com.dgs

import com.dgs.DGSUtils.absoluteUrl
import com.dgs.DGSUtils.cleanText
import com.dgs.DGSUtils.cleanTitle
import com.dgs.DGSUtils.isUsablePosterUrl
import com.dgs.DGSUtils.isVideoUrl
import com.dgs.DGSUtils.titleFromSlug
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newEpisode
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object DGSParser {
    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val results = linkedSetOf<SearchResponse>()

        document.select(
            ".post-item article.video, article.video, .video, .video-item, .thumb, .thumb-block, .item, .post, .card, .grid-item, article, li:has(a[href])"
        ).forEach { element ->
            parseCard(api, element)?.let { results.add(it) }
        }

        if (results.size < 4) {
            document.select("a[href*='/video/']:has(img), a[href]:has(img), a[href]:has(picture), a[href]:has(noscript)").forEach { anchor ->
                parseAnchorCard(api, anchor)?.let { results.add(it) }
            }
        }

        return results
            .distinctBy { it.url }
            .filter { it.name.length > 2 && it.url.isNotBlank() }
            .take(48)
    }

    private fun parseCard(api: MainAPI, element: Element): SearchResponse? {
        val link = element.selectFirst("a.post-permalink[href*='/video/']")
            ?: element.selectFirst("a[href*='/video/']")
            ?: element.selectFirst("a[href]:has(img)")
            ?: element.selectFirst("h2 a[href], h3 a[href], .post-title a[href], .title a[href], .name a[href], a[href]")
            ?: return null

        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isVideoUrl(href)) return null

        val poster = posterFromElement(api, element, link)
        val rawTitle = link.attr("title")
            .ifBlank { link.attr("aria-label") }
            .ifBlank { element.selectFirst("h2.post-title a, h1.post-title, h2, h3, .post-title, .title, .name")?.text().orEmpty() }
            .ifBlank { element.selectFirst("img")?.attr("alt").orEmpty() }
            .ifBlank { element.selectFirst("img")?.attr("title").orEmpty() }
            .ifBlank { link.text() }
            .ifBlank { titleFromSlug(href) }

        val title = cleanTitle(rawTitle).ifBlank { titleFromSlug(href) }
        if (title.length < 3 || title.equals("home", true)) return null

        return api.newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = poster
        }
    }

    private fun parseAnchorCard(api: MainAPI, anchor: Element): SearchResponse? {
        val href = absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return null
        if (!isVideoUrl(href)) return null
        val poster = posterFromElement(api, anchor, anchor)
        val title = cleanTitle(
            anchor.attr("title")
                .ifBlank { anchor.attr("aria-label") }
                .ifBlank { anchor.parents().select("h2.post-title a, h3.post-title a, .post-title a").firstOrNull()?.text().orEmpty() }
                .ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty() }
                .ifBlank { anchor.text() }
        ).ifBlank { titleFromSlug(href) }
        if (title.length < 3 || title.equals("home", true)) return null
        return api.newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = poster
        }
    }

    private fun posterFromElement(api: MainAPI, vararg elements: Element?): String? {
        val searchRoots = linkedSetOf<Element>()
        elements.filterNotNull().forEach { element ->
            searchRoots.add(element)
            var parent = element.parent()
            repeat(4) {
                if (parent != null) {
                    searchRoots.add(parent!!)
                    parent = parent!!.parent()
                }
            }
        }

        for (root in searchRoots) {
            root.select("img[data-src], img[data-original], img[data-lazy-src], img[data-srcset], img[srcset], img[src]").forEach { img ->
                val candidates = listOf(
                    img.attr("data-src"),
                    img.attr("data-original"),
                    img.attr("data-lazy-src"),
                    img.attr("data-srcset").substringBefore(" "),
                    img.attr("srcset").substringBefore(" "),
                    img.attr("src")
                )
                candidates.mapNotNull { absoluteUrl(api.mainUrl, it) }.firstOrNull { isUsablePosterUrl(it) }?.let { return it }
            }

            root.select("noscript").forEach { noscript ->
                val parsed = Jsoup.parse(noscript.html())
                parsed.select("img[src], img[data-src], img[srcset]").forEach { img ->
                    val candidate = img.attr("data-src").ifBlank { img.attr("srcset").substringBefore(" ").ifBlank { img.attr("src") } }
                    absoluteUrl(api.mainUrl, candidate)?.takeIf { isUsablePosterUrl(it) }?.let { return it }
                }
            }

            val styleText = root.attr("style") + " " + root.select("[style]").joinToString(" ") { it.attr("style") }
            Regex("url\\((['\"]?)(.*?)\\1\\)", RegexOption.IGNORE_CASE)
                .findAll(styleText)
                .mapNotNull { absoluteUrl(api.mainUrl, it.groupValues.getOrNull(2)) }
                .firstOrNull { isUsablePosterUrl(it) }
                ?.let { return it }

            listOf("data-bg", "data-background", "data-image", "data-poster", "data-thumb", "data-thumbnail").forEach { attr ->
                root.attr(attr).takeIf { it.isNotBlank() }
                    ?.let { absoluteUrl(api.mainUrl, it) }
                    ?.takeIf { isUsablePosterUrl(it) }
                    ?.let { return it }
            }
        }

        return null
    }

    private fun posterFromDocument(api: MainAPI, document: Document): String? {
        val metaCandidates = listOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")?.attr("content"),
            document.selectFirst("link[rel=image_src]")?.attr("href")
        )
        metaCandidates.mapNotNull { absoluteUrl(api.mainUrl, it) }.firstOrNull { isUsablePosterUrl(it) }?.let { return it }
        return posterFromElement(api, document.body())
    }

    suspend fun parseLoadResponse(api: MainAPI, url: String, document: Document): LoadResponse? {
        val title = cleanTitle(
            document.selectFirst("h1.post-title, h1, h1.title, .video-title, .entry-title, meta[property=og:title]")?.let {
                if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
            } ?: document.title()
        ).ifBlank { titleFromSlug(url) }

        val poster = posterFromDocument(api, document)
        val plot = cleanText(
            document.selectFirst(".post-content, .description, .desc, .video-description, .entry-content, .about, meta[name=description]")?.let {
                if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
            }
        )

        val tags = document.select("a[href*='/tag/'], a[href*='/tags/'], a[href*='/category/'], a[href*='/categories/'], .tags a, .categories a")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..40 }
            .distinct()
            .take(20)

        val recommendations = parseListing(api, document).filterNot { it.url == url }.take(12)
        val playable = api.newEpisode(url) {
            name = title
            posterUrl = poster
        }

        return api.newAnimeLoadResponse(title, url, TvType.NSFW) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            addEpisodes(com.lagradost.cloudstream3.DubStatus.Subbed, listOf<Episode>(playable))
        }
    }
}
