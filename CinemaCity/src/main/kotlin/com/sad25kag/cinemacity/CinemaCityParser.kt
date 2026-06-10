package com.sad25kag.cinemacity

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CinemaCityParser(private val provider: CinemaCityProvider) {

    fun parseCards(document: Document): List<SearchResponse> {
        val cards = document.select(CinemaCityConstans.CARD_SELECTOR).mapNotNull { it.toSearchResult() }
        if (cards.isNotEmpty()) return cards.distinctBy { it.url }

        return document.select(CinemaCityConstans.DETAIL_LINK_SELECTOR)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    fun Element.toSearchResult(): SearchResponse? {
        val link = findDetailAnchor() ?: return null
        val href = CinemaCityUtils.absoluteUrl(link.attr("href")) ?: return null
        if (!CinemaCityUtils.isDetailUrl(href)) return null

        val rawTitle = listOf(
            link.attr("title"),
            link.selectFirst("img")?.attr("alt"),
            selectFirst(".dar-short_title, .short-title, .title, h2, h3")?.text(),
            link.text()
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

        val title = CinemaCityUtils.cleanTitle(rawTitle)
            .substringBefore(" • ")
            .trim()
        if (title.length < 2 || title.equals("Play", true) || title.equals("Details", true)) return null

        val poster = CinemaCityUtils.absoluteUrl(
            selectFirst("img")?.attr("data-src")
                ?.ifBlank { selectFirst("img")?.attr("data-original").orEmpty() }
                ?.ifBlank { selectFirst("img")?.attr("src").orEmpty() }
        )
        val score = selectFirst("span.rating-color, .rating-color, .rating")?.text()?.toDoubleOrNull()
        val year = CinemaCityUtils.parseYear(text()) ?: CinemaCityUtils.parseYear(title)
        val isTv = CinemaCityUtils.isTvSeries(href, text())

        return if (isTv) {
            provider.newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                posterHeaders = mapOf("Referer" to "${provider.mainUrl}/")
                this.year = year
                this.score = com.lagradost.cloudstream3.Score.from(score, 10)
            }
        } else {
            provider.newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                posterHeaders = mapOf("Referer" to "${provider.mainUrl}/")
                this.year = year
                this.score = com.lagradost.cloudstream3.Score.from(score, 10)
            }
        }
    }

    private fun Element.findDetailAnchor(): Element? {
        if (tagName().equals("a", true) && CinemaCityUtils.isDetailUrl(attr("href"))) return this
        return select(CinemaCityConstans.DETAIL_LINK_SELECTOR)
            .firstOrNull { !it.text().equals("Play", true) && !it.text().equals("Details", true) }
            ?: select(CinemaCityConstans.DETAIL_LINK_SELECTOR).firstOrNull()
    }

    fun parseTitle(document: Document): String? {
        return document.selectFirst("h1, .dar-full_title, .full-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    fun parsePoster(document: Document): String? {
        val poster = document.selectFirst(CinemaCityConstans.POSTER_SELECTOR)?.let {
            it.attr("data-src").ifBlank { it.attr("data-original") }.ifBlank { it.attr("src") }
        }
        return CinemaCityUtils.absoluteUrl(poster)
    }

    fun parseDescription(document: Document): String? {
        return document.selectFirst(CinemaCityConstans.DESCRIPTION_SELECTOR)?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    fun parseTags(document: Document): List<String> {
        return document.select("div.dar-full_meta span a[href*=genre], .dar-full_meta a[href*=genre], a[href*=xfsearch/genre]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun parseScore(document: Document): Double? {
        return document.selectFirst("div.dar-full_meta span.rating-color, .rating-color, .rating")?.text()?.toDoubleOrNull()
    }

    fun parseRecommendations(document: Document): List<SearchResponse> {
        return document.select(CinemaCityConstans.RECOMMENDATION_SELECTOR).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    fun parseEpisodes(document: Document, poster: String?): List<Episode> {
        return parseEpisodeSources(document.html(), poster).map { source ->
            provider.newEpisode(source.data, initializer = {
                name = source.title
                season = source.season
                episode = source.episode
                posterUrl = source.poster ?: poster
            })
        }.distinctBy { it.data }
    }

    fun parseEpisodeSources(html: String, poster: String? = null): List<CinemaCityEpisodeSource> {
        val decodedScripts = CinemaCityUtils.decodeAtobScripts(html)
        val sourceBlocks = decodedScripts + html
        val episodes = mutableListOf<CinemaCityEpisodeSource>()

        sourceBlocks.forEach { block ->
            extractFileArrays(block).forEach { arrayText ->
                episodes += parseFileArray(arrayText, poster)
            }
        }

        return episodes.distinctBy { it.data }
    }

    fun parseVideosFromData(data: String): List<CinemaCityResolvedVideo> {
        val value = data.trim()
        if (value.isBlank()) return emptyList()

        return when {
            value.startsWith("[") -> parseVideoArray(value)
            value.startsWith("{") -> parseVideoObject(value)
            value.startsWith("http", true) -> listOf(CinemaCityResolvedVideo(null, value))
            else -> emptyList()
        }.filter { it.url.isNotBlank() }
    }

    private fun extractFileArrays(block: String): List<String> {
        val results = mutableListOf<String>()
        val starts = Regex("""(?:file|sources)\s*:\s*(['\"])\s*\[""", RegexOption.IGNORE_CASE)
            .findAll(block)
            .map { it.range.last }
            .toList()

        starts.forEach { markerEnd ->
            val arrayStart = block.indexOf('[', markerEnd)
            if (arrayStart < 0) return@forEach

            var depth = 0
            var inString = false
            var quote = '\u0000'
            var escaped = false

            for (i in arrayStart until block.length) {
                val char = block[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (char == '\\') {
                    escaped = true
                    continue
                }
                if (inString) {
                    if (char == quote) inString = false
                    continue
                }
                if (char == '\'' || char == '"') {
                    inString = true
                    quote = char
                    continue
                }
                if (char == '[') depth++
                if (char == ']') {
                    depth--
                    if (depth == 0) {
                        results += CinemaCityUtils.unescapeSource(block.substring(arrayStart, i + 1))
                        break
                    }
                }
            }
        }

        if (results.isNotEmpty()) return results.distinct()

        val fallbackPatterns = listOf(
            Regex("file\\s*:\\s*'([\\s\\S]*?\\])'", RegexOption.DOT_MATCHES_ALL),
            Regex("file\\s*:\\s*\"([\\s\\S]*?\\])\"", RegexOption.DOT_MATCHES_ALL),
            Regex("""sources\s*:\s*(['"])([\s\S]*?\])\1""", RegexOption.DOT_MATCHES_ALL)
        )
        return fallbackPatterns.flatMap { pattern ->
            pattern.findAll(block).mapNotNull { match ->
                val group = if (match.groupValues.size > 2) match.groupValues[2] else match.groupValues[1]
                CinemaCityUtils.unescapeSource(group).takeIf { it.startsWith("[") }
            }
        }.distinct()
    }

    private fun parseFileArray(arrayText: String, poster: String?): List<CinemaCityEpisodeSource> {
        val output = mutableListOf<CinemaCityEpisodeSource>()
        runCatching {
            val jsonArray = JSONArray(arrayText)
            for (seasonIndex in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(seasonIndex) ?: continue
                if (item.has("folder")) {
                    val seasonTitle = item.optString("title", "Season ${seasonIndex + 1}")
                    val seasonNumber = CinemaCityUtils.parseSeasonNumber(seasonTitle, seasonIndex + 1)
                    val folder = item.optJSONArray("folder") ?: continue
                    for (episodeIndex in 0 until folder.length()) {
                        val episode = folder.optJSONObject(episodeIndex) ?: continue
                        val title = episode.optString("title", "Episode ${episodeIndex + 1}")
                        val episodeNumber = CinemaCityUtils.parseEpisodeNumber(title, episodeIndex + 1)
                        val data = mapper.writeValueAsString(
                            CinemaCityVideo(
                                url = episode.optString("file"),
                                subtitle = episode.optString("subtitle")
                            )
                        )
                        output += CinemaCityEpisodeSource(title, seasonNumber, episodeNumber, data, poster)
                    }
                } else {
                    output += item.toMovieEpisodeSource(seasonIndex, poster)
                }
            }
        }
        return output
    }

    private fun JSONObject.toMovieEpisodeSource(index: Int, poster: String?): CinemaCityEpisodeSource {
        val title = optString("title", "Movie")
        val data = mapper.writeValueAsString(
            CinemaCityVideo(
                url = optString("file"),
                subtitle = optString("subtitle")
            )
        )
        return CinemaCityEpisodeSource(title, null, index + 1, data, poster)
    }

    private fun parseVideoArray(data: String): List<CinemaCityResolvedVideo> {
        return runCatching {
            val wrappers = mapper.readValue<List<CinemaCityVideoWrapper>>(data)
            wrappers.flatMap { wrapper ->
                val nested = wrapper.data?.trim().orEmpty()
                when {
                    nested.startsWith("{") || nested.startsWith("[") -> parseVideosFromData(nested)
                    !wrapper.file.isNullOrBlank() -> listOf(
                        CinemaCityResolvedVideo(wrapper.title, wrapper.file, wrapper.subtitle.orEmpty())
                    )
                    nested.startsWith("http", true) -> listOf(
                        CinemaCityResolvedVideo(wrapper.title, nested, wrapper.subtitle.orEmpty())
                    )
                    else -> emptyList()
                }
            }
        }.getOrElse {
            runCatching {
                val jsonArray = JSONArray(data)
                val output = mutableListOf<CinemaCityResolvedVideo>()
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(i) ?: continue
                    val nested = item.optString("data")
                    if (nested.startsWith("{") || nested.startsWith("[")) {
                        output += parseVideosFromData(nested)
                    } else {
                        val url = item.optString("file", item.optString("url", nested))
                        if (url.isNotBlank()) output += CinemaCityResolvedVideo(item.optString("title"), url, item.optString("subtitle"))
                    }
                }
                output
            }.getOrDefault(emptyList())
        }
    }

    private fun parseVideoObject(data: String): List<CinemaCityResolvedVideo> {
        return runCatching {
            val video = mapper.readValue<CinemaCityVideo>(data)
            listOf(CinemaCityResolvedVideo(video.title, video.playableUrl, video.subtitleText))
        }.getOrDefault(emptyList())
    }
}
