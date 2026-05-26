package com.reynime

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object ReynimeParser {

    fun parseSearchItems(api: MainAPI, doc: Document): List<SearchResponse> {
        val selectors = listOf(
            "article",
            ".bs .bsx",
            ".listupd .bs",
            ".animepost",
            ".item"
        )

        val results = mutableListOf<SearchResponse>()
        val added = mutableSetOf<String>()

        for (selector in selectors) {
            val els = doc.select(selector)
            for (el in els) {
                val item = toSearchResponse(api, el) ?: continue
                if (added.add(item.url)) results.add(item)
            }
            if (results.isNotEmpty()) break
        }
        return results
    }

    private fun toSearchResponse(api: MainAPI, el: Element): SearchResponse? {
        val link = el.selectFirst("a")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val title = el.selectFirst("h2, h3, h4, .tt, .title")?.text()?.trim()
            ?: el.selectFirst("a[title]")?.attr("title")
            ?: return null

        val poster = el.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() }?.let { ReynimeUtils.fixUrl(link, it) }

        return api.newAnimeSearchResponse(title, link, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    suspend fun parseLoad(api: MainAPI, doc: Document, url: String): LoadResponse {
        val title = doc.selectFirst("h1, .entry-title, .infox h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() }?.let { ReynimeUtils.fixUrl(url, it) }

        val synopsis = doc.selectFirst(".entry-content p, .desc, .synp, .info-content")
            ?.text()?.trim()

        val episodeLinks = doc.select(
            "a[href*='episode'], .eplister a, .episodelist a, .episode-list a"
        )

        val episodes = episodeLinks.mapIndexed { index, ep ->
            val epUrl = ep.attr("href")
            val epName = ep.text().ifBlank { "Episode ${index + 1}" }
            api.newEpisode(epUrl) {
                this.name = epName
            }
        }.reversed()

        return api.newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            plot = synopsis
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }
}
