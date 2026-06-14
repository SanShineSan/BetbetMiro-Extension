package com.samehadaku

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.samehadaku.SamehadakuUtils.cleanEscaped
import com.samehadaku.SamehadakuUtils.normalizeUrl
import com.samehadaku.SamehadakuUtils.parseEpisodeNumber
import com.samehadaku.SamehadakuUtils.parseYear
import com.samehadaku.SamehadakuUtils.titleBloatClean
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

object SamehadakuParser {
    fun parseByMode(
        api: MainAPI,
        document: Document,
        baseUrl: String,
        mainUrl: String,
        mode: SamehadakuCategoryMode
    ): List<SearchResponse> {
        return when (mode) {
            SamehadakuCategoryMode.HomeLatest -> parseLatest(api, document, baseUrl, mainUrl)
            SamehadakuCategoryMode.HomeTop -> parseHomeTop(api, document, baseUrl, mainUrl)
            SamehadakuCategoryMode.HomeMovie -> parseProjectMovie(api, document, baseUrl, mainUrl)
            SamehadakuCategoryMode.Schedule -> parseSchedule(api, document, baseUrl, mainUrl).ifEmpty { parseListing(api, document, baseUrl, mainUrl) }
            SamehadakuCategoryMode.Listing -> parseListing(api, document, baseUrl, mainUrl)
        }
    }

    fun parseLatest(api: MainAPI, document: Document, baseUrl: String, mainUrl: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select("div.post-show ul li, .post-show li, main#main div.animepost, div.animepost, article.post").forEach { element ->
            element.toSearchResult(api, baseUrl, mainUrl)?.let { results[it.url] = it }
        }
        return results.values.toList().ifEmpty { parseListing(api, document, baseUrl, mainUrl) }
    }

    fun parseHomeTop(api: MainAPI, document: Document, baseUrl: String, mainUrl: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select("section:contains(Top 10) li, div:contains(Top 10 minggu ini) li, aside li, .topten li, .top-list li").forEach { element ->
            element.toSearchResult(api, baseUrl, mainUrl)?.let { results[it.url] = it }
        }
        if (results.isEmpty()) {
            document.select("a[href*=/anime/]")
                .filter { it.text().contains("TOP", true) || it.parent()?.text()?.contains("TOP", true) == true }
                .forEach { element -> element.toSearchResult(api, baseUrl, mainUrl)?.let { results[it.url] = it } }
        }
        return results.values.take(20)
    }

    fun parseProjectMovie(api: MainAPI, document: Document, baseUrl: String, mainUrl: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val blocks = document.select("div.widget_senction:contains(Project Movie), section:contains(Project Movie), div:contains(Project Movie Samehadaku)")
        blocks.forEach { block ->
            block.select("div.animepost, article, li, .bsx, a[href*=/anime/]").forEach { element ->
                element.toSearchResult(api, baseUrl, mainUrl)?.let { results[it.url] = it }
            }
        }
        if (results.isEmpty()) {
            document.select("a[href*=/anime/]")
                .filter { it.parent()?.text()?.contains("Genres:", true) == true }
                .forEach { element -> element.toSearchResult(api, baseUrl, mainUrl)?.let { results[it.url] = it } }
        }
        return results.values.take(24)
    }

    fun parseSchedule(api: MainAPI, document: Document, baseUrl: String, mainUrl: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select("div.animepost, article, li, .schedule-list li, .listupd article, a[href*=/anime/], a[href*=-episode-]").forEach { element ->
            element.toSearchResult(api, baseUrl, mainUrl)?.let { results[it.url] = it }
        }
        return results.values.toList()
    }

    fun parseListing(api: MainAPI, document: Document, baseUrl: String, mainUrl: String): List<SearchResponse> {
        val selectors = listOf(
            "div.post-show ul li",
            "main#main div.animepost",
            "div.animepost",
            "div.widget_senction div.animepost",
            "article.post",
            "article",
            "div.bsx",
            "div.listupd article",
            "div.list-anime a[href]",
            "div.listupd a[href]",
            "li:has(a[href*=/anime/])",
            "li:has(a[href*=-episode-])"
        )
        val results = linkedMapOf<String, SearchResponse>()
        selectors.forEach { selector ->
            document.select(selector).mapNotNull { it.toSearchResult(api, baseUrl, mainUrl) }.forEach { item ->
                results[item.url] = item
            }
        }
        if (results.isEmpty()) {
            document.select("a[href*=/anime/], a[href*=-episode-]").mapNotNull { it.toSearchResult(api, baseUrl, mainUrl) }.forEach { item ->
                results[item.url] = item
            }
        }
        return results.values.toList()
    }

    fun parseMeta(document: Document, pageUrl: String, mainUrl: String): SamehadakuMeta? {
        val rawTitle = document.selectFirst("h1.entry-title, h1, .entry-title")?.text()
            ?: document.selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content")
            ?: return null
        val title = titleBloatClean(rawTitle).takeIf { it.isNotBlank() } ?: rawTitle.cleanEscaped()
        val poster = normalizeUrl(
            document.selectFirst("div.thumb img, .thumb img, .infox img, .animefull img, meta[property=og:image], meta[name=twitter:image]")?.let { element ->
                element.attr("content")
                    .ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("data-litespeed-src") }
                    .ifBlank { element.attr("src") }
            },
            pageUrl,
            mainUrl
        ).takeIf { it.isNotBlank() }
        val background = normalizeUrl(document.selectFirst("meta[property=og:image]")?.attr("content"), pageUrl, mainUrl).takeIf { it.isNotBlank() }
        val infoText = document.select("div.spe, div.infoanime, div.info-content, .animeinfo, .entry-content, .infox").text().cleanEscaped()
        val type = extractInfo(infoText, "Type")?.let { SamehadakuUtils.getType(it) } ?: TvType.Anime
        val status = SamehadakuUtils.getStatus(extractInfo(infoText, "Status") ?: infoText)
        val year = parseYear(extractInfo(infoText, "Released") ?: extractInfo(infoText, "Rilis") ?: extractInfo(infoText, "Season") ?: infoText)
        val score = document.selectFirst("span.ratingValue, .rtg, .rating, [itemprop=ratingValue]")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val description = document.select("div.desc p, div.desc, .entry-content p, .sinopsis p, .synopsis p")
            .joinToString("\n") { it.text().cleanEscaped() }
            .ifBlank { document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.cleanEscaped().orEmpty() }
            .takeIf { it.isNotBlank() }
        val trailer = normalizeUrl(document.selectFirst("div.trailer-anime iframe, iframe[src*=youtube], iframe[src*=youtu]")?.attr("src"), pageUrl, mainUrl).takeIf { it.isNotBlank() }
        val tags = document.select("div.genre-info a, .genre-info a, a[href*=/genre/]")
            .map { it.text().cleanEscaped() }
            .filter { it.isNotBlank() && it.length <= 40 }
            .distinct()
        return SamehadakuMeta(
            title = title,
            poster = poster,
            background = background,
            type = type,
            status = status,
            year = year,
            score = score,
            description = description,
            trailer = trailer,
            tags = tags
        )
    }

    fun parseEpisodes(api: MainAPI, document: Document, baseUrl: String, mainUrl: String): List<Episode> {
        val selectors = listOf(
            "div.lstepsiode.listeps ul li",
            "div.listeps ul li",
            "ul.listeps li",
            "div.episodelist ul li",
            ".episodelist li"
        )
        val episodes = linkedMapOf<String, Episode>()
        selectors.forEach { selector ->
            document.select(selector).forEach { element ->
                val anchor = element.selectFirst("span.lchx a[href], a[href*=-episode-], a[href]") ?: return@forEach
                val link = normalizeUrl(anchor.attr("href"), baseUrl, mainUrl)
                if (!isValidEpisodeUrl(link)) return@forEach
                val title = anchor.text()
                    .ifBlank { element.selectFirst(".lchx, h2, h3")?.text().orEmpty() }
                    .ifBlank { element.text() }
                    .cleanEscaped()
                    .takeIf { it.isNotBlank() }
                    ?: return@forEach
                val episode = parseEpisodeNumber(title) ?: parseEpisodeNumberFromEpisodeUrl(link)
                val poster = normalizeUrl(
                    element.selectFirst("img")?.let { img -> img.attr("data-src").ifBlank { img.attr("data-litespeed-src") }.ifBlank { img.attr("src") } },
                    baseUrl,
                    mainUrl
                ).takeIf { it.isNotBlank() }
                episodes[link] = api.newEpisode(link, initializer = {
                    this.name = title
                    this.episode = episode
                    this.posterUrl = poster
                })
            }
        }
        return episodes.values.sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name ?: "" })
    }

    private fun isValidEpisodeUrl(url: String): Boolean {
        if (url.isBlank() || !url.contains("samehadaku", true)) return false
        if (url.contains("/anime/", true)) return false
        return Regex("""(?i)(?:^|[-_/])episode[-_/]?\d{1,4}(?:[-_/]|$)""").containsMatchIn(url) ||
            Regex("""(?i)(?:^|[-_/])eps?[-_/]?\d{1,4}(?:[-_/]|$)""").containsMatchIn(url)
    }

    private fun parseEpisodeNumberFromEpisodeUrl(url: String): Int? {
        val path = runCatching { URI(url).path }.getOrDefault(url)
        return listOf(
            Regex("""(?i)(?:^|[-_/])episode[-_/]?(\d{1,4})(?:[-_/]|$)"""),
            Regex("""(?i)(?:^|[-_/])eps?[-_/]?(\d{1,4})(?:[-_/]|$)""")
        ).firstNotNullOfOrNull { regex ->
            regex.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

    fun parseRecommendations(api: MainAPI, document: Document, baseUrl: String, mainUrl: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select("aside#sidebar ul li, aside#sidebar li, .recommended li, .related li, .rekomendasi li, .animepost")
            .mapNotNull { it.toSearchResult(api, baseUrl, mainUrl) }
            .forEach { results[it.url] = it }
        return results.values.take(20)
    }

    fun parseAnimeUrlFromEpisode(document: Document, baseUrl: String, mainUrl: String): String? {
        val selectors = listOf(
            "div.nvs.nvsc a[href*=/anime/]",
            "nav a[href*=/anime/]",
            ".breadcrumb a[href*=/anime/]",
            "a[href*=/anime/]"
        )
        selectors.forEach { selector ->
            document.selectFirst(selector)?.attr("href")?.let { raw ->
                val url = normalizeUrl(raw, baseUrl, mainUrl)
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    fun Element.toSearchResult(api: MainAPI, baseUrl: String, mainUrl: String): AnimeSearchResponse? {
        val anchor = when {
            tagName() == "a" && attr("href").isNotBlank() -> this
            else -> selectFirst("a[href*=/anime/], a[href*=-episode-], h2 a[href], h3 a[href], a[href]") ?: return null
        }
        val href = normalizeUrl(anchor.attr("href"), baseUrl, mainUrl)
        if (href.isBlank() || SamehadakuUtils.shouldSkipUrl(href)) return null
        if (!href.contains("samehadaku", true) && !href.contains("/anime/", true) && !href.contains("-episode-", true)) return null

        val rawTitle = selectFirst("div.title, h2.entry-title a, h2 a, h3 a, .lftinfo h2, .tt, .entry-title, .entry-title a, span.lchx a")?.text()
            ?: anchor.attr("title")
            ?: anchor.text()
        val title = titleBloatClean(rawTitle).takeIf { it.isNotBlank() } ?: return null
        if (title.length < 2 || title.equals("Image", true) || title.equals("Home", true)) return null

        val poster = normalizeUrl(
            selectFirst("img")?.let { img ->
                img.attr("data-src")
                    .ifBlank { img.attr("data-litespeed-src") }
                    .ifBlank { img.attr("data-lazy-src") }
                    .ifBlank { img.attr("src") }
            },
            baseUrl,
            mainUrl
        ).takeIf { it.isNotBlank() }
        val type = SamehadakuUtils.getType(text())
        val episode = parseEpisodeNumber(selectFirst(".dtla, .eps, .epx, .episode, span, author")?.text() ?: text())
        return api.newAnimeSearchResponse(title, href, type, initializer = {
            posterUrl = poster
            addSub(episode)
        })
    }

    private fun extractInfo(text: String, key: String): String? {
        return Regex("""(?i)$key\s*:?\s*([^:]+?)(?:\s+(?:Japanese|Synonyms|English|Status|Type|Source|Duration|Total Episode|Season|Studio|Producers|Released|Rilis|Skor)\s*:|$)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}
