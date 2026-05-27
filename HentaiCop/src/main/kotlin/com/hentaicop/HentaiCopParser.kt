package com.hentaicop

import com.hentaicop.HentaiCopUtils.absoluteUrl
import com.hentaicop.HentaiCopUtils.cleanText
import com.hentaicop.HentaiCopUtils.cleanTitle
import com.hentaicop.HentaiCopUtils.episodeNumber
import com.hentaicop.HentaiCopUtils.isEpisodeUrl
import com.hentaicop.HentaiCopUtils.isPlayablePageUrl
import com.hentaicop.HentaiCopUtils.isPseudoUrl
import com.hentaicop.HentaiCopUtils.isUsablePosterUrl
import com.hentaicop.HentaiCopUtils.statusFromText
import com.hentaicop.HentaiCopUtils.titleFromSlug
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object HentaiCopParser {
    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val results = linkedSetOf<AnimeSearchResponse>()

        document.select(
            "article.bs.styletwo, article.bs, .listupd article, .listupd .bs, .bsx, .swiper-slide, .postbody article, .post, .item"
        ).forEach { element ->
            parseCard(api, element)?.let { results.add(it) }
        }

        if (results.size < 4) {
            document.select("a[href]:has(img), a[href]:has(noscript)").forEach { anchor ->
                parseAnchorCard(api, anchor)?.let { results.add(it) }
            }
        }

        return results
            .distinctBy { it.url }
            .filter { it.name.length > 2 && it.url.isNotBlank() && isUsablePosterUrl(it.posterUrl) }
            .take(36)
    }

    private fun parseCard(api: MainAPI, element: Element): AnimeSearchResponse? {
        val link = element.selectFirst("a[href*='/series/']")
            ?: element.selectFirst("a[href*='-episode-']")
            ?: element.selectFirst("h2 a[href], h3 a[href], .tt a[href], .entry-title a[href], .epl-title a[href], a[href]")
            ?: return null

        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isPlayablePageUrl(href)) return null

        val targetUrl = href
        val poster = posterFromElement(api, element, link) ?: return null
        val rawTitle = link.attr("oldtitle")
            .ifBlank { link.attr("title") }
            .ifBlank { element.selectFirst("img")?.attr("alt").orEmpty() }
            .ifBlank { element.selectFirst("img")?.attr("title").orEmpty() }
            .ifBlank { link.text() }
            .ifBlank { element.selectFirst(".tt, h2, h3, .entry-title, .epl-title")?.text().orEmpty() }
            .ifBlank { titleFromSlug(targetUrl) }

        val title = cleanTitle(rawTitle).ifBlank { titleFromSlug(targetUrl) }
        if (title.length < 3 || title.equals("lihat semua", true)) return null

        return api.newAnimeSearchResponse(title, targetUrl, TvType.NSFW) {
            posterUrl = poster
        }
    }

    private fun parseAnchorCard(api: MainAPI, anchor: Element): AnimeSearchResponse? {
        val href = absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return null
        if (!isPlayablePageUrl(href)) return null
        val poster = posterFromElement(api, anchor, anchor) ?: return null
        val targetUrl = href
        val text = cleanTitle(
            anchor.attr("title")
                .ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty() }
                .ifBlank { anchor.text() }
        ).ifBlank { titleFromSlug(targetUrl) }
        if (text.length < 3 || text.equals("home", true) || text.equals("download", true) || text.equals("lihat semua", true)) return null
        return api.newAnimeSearchResponse(text, targetUrl, TvType.NSFW) {
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
            root.select("img[data-src], img[data-lazy-src], img[data-original], img[data-wpfc-original-src], img[data-srcset], img[data-lazy-srcset], img[srcset], img[src]").forEach { img ->
                val candidates = listOf(
                    img.attr("data-src"),
                    img.attr("data-lazy-src"),
                    img.attr("data-original"),
                    img.attr("data-wpfc-original-src"),
                    img.attr("data-srcset").substringBefore(" "),
                    img.attr("data-lazy-srcset").substringBefore(" "),
                    img.attr("srcset").substringBefore(" "),
                    img.attr("src")
                )
                candidates.mapNotNull { absoluteUrl(api.mainUrl, it) }.firstOrNull { isUsablePosterUrl(it) }?.let { return it }
            }

            root.select("noscript").forEach { noscript ->
                val html = noscript.html()
                val parsed = Jsoup.parse(html)
                parsed.select("img[src], img[data-src], img[srcset]").forEach { img ->
                    val candidate = img.attr("data-src").ifBlank { img.attr("srcset").substringBefore(" ").ifBlank { img.attr("src") } }
                    absoluteUrl(api.mainUrl, candidate)?.takeIf { isUsablePosterUrl(it) }?.let { return it }
                }
                Regex("(?i)src=['\"]([^'\"]+)['\"]").find(html)?.groupValues?.getOrNull(1)
                    ?.let { absoluteUrl(api.mainUrl, it) }
                    ?.takeIf { isUsablePosterUrl(it) }
                    ?.let { return it }
            }

            val styleText = root.attr("style") + " " + root.select("[style]").joinToString(" ") { it.attr("style") }
            Regex("url\\((['\"]?)(.*?)\\1\\)", RegexOption.IGNORE_CASE)
                .findAll(styleText)
                .mapNotNull { absoluteUrl(api.mainUrl, it.groupValues.getOrNull(2)) }
                .firstOrNull { isUsablePosterUrl(it) }
                ?.let { return it }

            listOf("data-bg", "data-background", "data-image", "data-poster", "data-thumb").forEach { attr ->
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
            document.selectFirst(".entry-title, h1.entry-title, h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ).ifBlank { titleFromSlug(url) }

        val poster = posterFromDocument(api, document)

        val tags = document.select(".genxed a, a[href*='/genre/']")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..40 }
            .distinct()
            .take(20)

        val infoText = document.select(".spe, .info-content, .entry-content, .postbody").text()
        val plot = cleanText(
            document.selectFirst(".entry-content p, .synopsis p, .desc p, .entry-content")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
        )
        val year = Regex("(?i)(?:released|rilis|tahun)[:\\s]+(\\d{4})").find(infoText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val status = statusFromText(infoText)

        val episodes = parseEpisodes(api, document, url)
        val recommendations = parseListing(api, document).filterNot { it.url == url }.take(12)

        return api.newAnimeLoadResponse(title, url, TvType.NSFW) {
            posterUrl = poster
            this.tags = tags
            this.plot = plot
            this.year = year
            this.showStatus = status
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun parseEpisodes(api: MainAPI, document: Document, fallbackUrl: String): List<Episode> {
        val seen = linkedSetOf<String>()
        val episodes = mutableListOf<Episode>()

        document.select(
            ".eplister li a[href], .episodelist li a[href], .episode-list a[href], .soraddlx a[href], .epcheck a[href], #episode_related a[href], a[href*='-episode-']"
        ).forEach { a ->
            val href = absoluteUrl(api.mainUrl, a.attr("href")) ?: return@forEach
            if (!isEpisodeUrl(href)) return@forEach
            val key = href.substringBefore('#')
            if (!seen.add(key)) return@forEach
            val ep = episodeNumber(a.selectFirst(".epl-num")?.text())
                ?: episodeNumber(a.text())
                ?: episodeNumber(href)
                ?: (episodes.size + 1)
            val name = cleanText(
                a.selectFirst(".epl-title")?.text()
                    ?: a.attr("title")
                    ?: a.text()
            ).ifBlank { "Episode $ep" }
            episodes.add(
                api.newEpisode(href) {
                    this.name = name
                    this.episode = ep
                    this.posterUrl = posterFromElement(api, a)
                }
            )
        }

        if (episodes.isNotEmpty()) return episodes

        return listOf(
            api.newEpisode(fallbackUrl) {
                name = if (isEpisodeUrl(fallbackUrl)) cleanTitle(titleFromSlug(fallbackUrl)).ifBlank { "Episode" } else "Movie"
                episode = episodeNumber(fallbackUrl)
            }
        )
    }
}
