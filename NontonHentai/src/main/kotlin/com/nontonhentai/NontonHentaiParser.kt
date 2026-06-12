package com.nontonhentai

import com.nontonhentai.NontonHentaiUtils.absoluteUrl
import com.nontonhentai.NontonHentaiUtils.cleanText
import com.nontonhentai.NontonHentaiUtils.cleanTitle
import com.nontonhentai.NontonHentaiUtils.episodeNumber
import com.nontonhentai.NontonHentaiUtils.isEpisodeUrl
import com.nontonhentai.NontonHentaiUtils.isLikelyPlayableCardText
import com.nontonhentai.NontonHentaiUtils.isPlayablePageUrl
import com.nontonhentai.NontonHentaiUtils.isPseudoUrl
import com.nontonhentai.NontonHentaiUtils.isSeriesUrl
import com.nontonhentai.NontonHentaiUtils.isUsablePosterUrl
import com.nontonhentai.NontonHentaiUtils.statusFromText
import com.nontonhentai.NontonHentaiUtils.titleFromSlug
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

object NontonHentaiParser {
    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val results = linkedSetOf<AnimeSearchResponse>()

        document.select(
            "article.bs.styletwo, article.bs, .listupd article, .listupd .bs, .bsx, .swiper-slide, .postbody article, .post, .item, .venz li, .serieslist li, .ml-item, .animepost, .excstf article"
        ).forEach { element ->
            parseCard(api, element)?.let { results.add(it) }
        }

        if (results.size < 6) {
            document.select("a[href]:has(img), a[href]:has(noscript)").forEach { anchor ->
                parseAnchorCard(api, anchor)?.let { results.add(it) }
            }
        }

        if (results.size < 6) {
            document.select("a[href]").forEach { anchor ->
                parseTextCard(api, anchor)?.let { results.add(it) }
            }
        }

        return results
            .distinctBy { it.url }
            .filter { it.name.length > 2 && it.url.isNotBlank() }
            .filterNot { it.name.equals("lihat semua", true) || it.name.equals("selanjutnya", true) }
            .take(40)
    }

    private fun parseCard(api: MainAPI, element: Element): AnimeSearchResponse? {
        val link = element.selectFirst("a[href*='/anime/']")
            ?: element.selectFirst("a[href*='-episode-']")
            ?: element.selectFirst("h2 a[href], h3 a[href], h4 a[href], .tt a[href], .entry-title a[href], .epl-title a[href], .ep-title a[href], .thumb a[href], a[href]")
            ?: return null

        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isPlayablePageUrl(href)) return null

        val poster = posterFromElement(api, element, link)
        val rawTitle = link.attr("oldtitle")
            .ifBlank { link.attr("title") }
            .ifBlank { element.selectFirst("img")?.attr("alt").orEmpty() }
            .ifBlank { element.selectFirst("img")?.attr("title").orEmpty() }
            .ifBlank { link.text() }
            .ifBlank { element.selectFirst(".tt, h2, h3, h4, .entry-title, .epl-title, .ep-title")?.text().orEmpty() }
            .ifBlank { titleFromSlug(href) }

        val title = cleanTitle(rawTitle).ifBlank { titleFromSlug(href) }
        if (title.length < 3 || !isLikelyPlayableCardText("$title ${element.text()}")) return null

        return api.newAnimeSearchResponse(title, href, TvType.NSFW) {
            posterUrl = poster
        }
    }

    private fun parseAnchorCard(api: MainAPI, anchor: Element): AnimeSearchResponse? {
        val href = absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return null
        if (!isPlayablePageUrl(href)) return null
        val poster = posterFromElement(api, anchor, anchor)
        val text = cleanTitle(
            anchor.attr("title")
                .ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty() }
                .ifBlank { anchor.text() }
        ).ifBlank { titleFromSlug(href) }
        if (text.length < 3 || !isLikelyPlayableCardText("$text ${anchor.text()}")) return null
        return api.newAnimeSearchResponse(text, href, TvType.NSFW) {
            posterUrl = poster
        }
    }

    private fun parseTextCard(api: MainAPI, anchor: Element): AnimeSearchResponse? {
        val href = absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return null
        if (!isPlayablePageUrl(href)) return null
        val text = cleanTitle(anchor.text()).ifBlank { titleFromSlug(href) }
        if (text.length < 3 || !isLikelyPlayableCardText(text)) return null
        return api.newAnimeSearchResponse(text, href, TvType.NSFW) {
            posterUrl = posterFromElement(api, anchor, anchor)
        }
    }

    private fun posterFromElement(api: MainAPI, vararg elements: Element?): String? {
        val searchRoots = linkedSetOf<Element>()
        elements.filterNotNull().forEach { element ->
            searchRoots.add(element)
            var parent = element.parent()
            repeat(5) {
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

        val tags = document.select(".genxed a, .genres a, .genre a, a[href*='/genres/'], a[href*='/genre/']")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..40 }
            .distinct()
            .take(20)

        val infoText = document.select(".spe, .info-content, .entry-content, .postbody, .infox, .infodetail").text()
        val plot = cleanText(
            document.selectFirst(".entry-content p, .synopsis p, .desc p, .entry-content")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
        )
        val year = Regex("(?i)(?:released|rilis|dirilis|tahun)[:\\s]+(\\d{4})").find(infoText)?.groupValues?.getOrNull(1)?.toIntOrNull()
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
            ".eplister li a[href], .episodelist li a[href], .episode-list a[href], .epcheck a[href], #episode_related a[href], .serieslist a[href], .episodelain a[href], a[href*='-episode-']"
        ).forEach { a ->
            val href = absoluteUrl(api.mainUrl, a.attr("href")) ?: return@forEach
            if (!isEpisodeUrl(href) && !isPlayablePageUrl(href)) return@forEach
            if (isSeriesUrl(href) && href != fallbackUrl) return@forEach
            val key = href.substringBefore('#')
            if (!seen.add(key)) return@forEach
            val ep = episodeNumber(a.selectFirst(".epl-num, .eps, .epnum")?.text())
                ?: episodeNumber(a.text())
                ?: episodeNumber(href)
                ?: (episodes.size + 1)
            val name = cleanText(
                a.selectFirst(".epl-title, .ep-title, .title")?.text()
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

        if (episodes.isNotEmpty()) return episodes.distinctBy { it.data }

        return listOf(
            api.newEpisode(fallbackUrl) {
                name = if (isEpisodeUrl(fallbackUrl)) cleanTitle(titleFromSlug(fallbackUrl)).ifBlank { "Episode" } else "Movie"
                episode = episodeNumber(fallbackUrl)
            }
        )
    }
}
