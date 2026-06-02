package com.kingbokep

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.kingbokep.KingBokepUtils.absoluteUrl
import com.kingbokep.KingBokepUtils.cleanText
import com.kingbokep.KingBokepUtils.durationMinutes
import com.kingbokep.KingBokepUtils.encodeLoadData
import com.kingbokep.KingBokepUtils.isVideoUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object KingBokepParser {
    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val strict = parseListingInternal(api, document, requirePoster = true)
        if (strict.isNotEmpty()) return strict
        return parseListingInternal(api, document, requirePoster = false)
    }

    private fun parseListingInternal(api: MainAPI, document: Document, requirePoster: Boolean): List<SearchResponse> {
        val candidates = linkedSetOf<Element>()
        document.select("li.video-card, article.video-card, div.video-card, .video-card").forEach { candidates.add(it) }
        document.select("a[href*='/view/']").forEach { anchor ->
            candidates.add(anchor.closest("li, article, .video-card, .card, .item, div") ?: anchor)
        }

        return candidates.mapNotNull { parseCard(api, it, requirePoster) }
            .distinctBy { it.url.substringBefore("#").trimEnd('/') }
            .take(48)
    }

    private fun parseCard(api: MainAPI, element: Element, requirePoster: Boolean): SearchResponse? {
        val link = element.selectFirst("a.group[href*='/view/'], a[href*='/view/']") ?: return null
        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isVideoUrl(href)) return null

        val title = cleanText(
            link.attr("title").ifBlank {
                element.selectFirst("span.video-card-title, .video-card-title, .title, h2, h3")?.text()
                    ?: link.text()
            }
        ).removeDurationSuffix().ifBlank { return null }

        val poster = extractPoster(api.mainUrl, element, document = null)
            ?: extractPoster(api.mainUrl, link, document = null)
        if (requirePoster && poster.isNullOrBlank()) return null

        return api.newMovieSearchResponse(title, href, TvType.NSFW, false) {
            this.posterUrl = poster
        }
    }

    suspend fun parseLoadResponse(api: MainAPI, url: String, document: Document): LoadResponse? {
        val title = cleanText(
            document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ).removePrefix("Video ").ifBlank { return null }

        val poster = extractPoster(api.mainUrl, document.body(), document)
        val plot = cleanText(
            document.selectFirst("meta[name=description]")?.attr("content")
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        )

        val durationText = document.selectFirst("[data-pagefind-meta=duration]")?.text()
            ?: Regex("(?i)Durasi[:\\s]+([0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)").find(document.text())?.groupValues?.getOrNull(1)

        val duration = durationMinutes(durationText)
            ?: document.selectFirst("meta[property=video:duration]")?.attr("content")?.toIntOrNull()?.let { (it + 59) / 60 }

        val tags = document.select("a[href*='/category/'], a[href*='/tag/']")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..50 }
            .distinct()
            .take(15)

        val year = document.selectFirst("meta[property=article:published_time]")?.attr("content")
            ?.take(4)
            ?.toIntOrNull()

        val recommendations = parseListing(api, document)
            .filterNot { it.url.trimEnd('/') == url.trimEnd('/') }
            .take(12)

        val data = encodeLoadData(
            KingBokepLoadData(
                url = url,
                id = url.trimEnd('/').substringAfterLast('/'),
                title = title
            )
        )

        return api.newMovieLoadResponse(title, url, TvType.NSFW, data) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.duration = duration
            this.year = year
            this.recommendations = recommendations
        }
    }

    fun extractPoster(baseUrl: String, element: Element?, document: Document? = null): String? {
        if (element == null) return documentPoster(baseUrl, document)

        val imageCandidates = mutableListOf<String>()
        val imgs = element.select("img")
        imgs.forEach { img ->
            listOf("data-src", "data-lazy-src", "data-original", "data-thumb", "data-poster", "src").forEach { attr ->
                img.attr(attr).takeIf { it.isNotBlank() }?.let { imageCandidates.add(it) }
            }
            listOf("srcset", "data-srcset").forEach { srcsetAttr ->
                img.attr(srcsetAttr).takeIf { it.isNotBlank() }?.let { srcset ->
                    imageCandidates.add(srcset.split(",").firstOrNull()?.trim()?.substringBefore(" ").orEmpty())
                }
            }
        }

        var cursor: Element? = element
        repeat(4) {
            val current = cursor ?: return@repeat
            current.attr("style").takeIf { it.contains("url", true) }?.let { style ->
                Regex("url\\((['\"]?)(.*?)\\1\\)").find(style)?.groupValues?.getOrNull(2)?.let { imageCandidates.add(it) }
            }
            listOf("data-bg", "data-background", "data-image", "data-thumb", "data-poster").forEach { attr ->
                current.attr(attr).takeIf { it.isNotBlank() }?.let { imageCandidates.add(it) }
            }
            cursor = current.parent()
        }

        return imageCandidates.asSequence()
            .mapNotNull { absoluteUrl(baseUrl, it) }
            .firstOrNull { isValidPoster(it) }
            ?: documentPoster(baseUrl, document)
    }

    private fun documentPoster(baseUrl: String, document: Document?): String? {
        if (document == null) return null
        val candidates = listOfNotNull(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")?.attr("content"),
            document.selectFirst("video#bokep-player")?.attr("poster")
        )
        return candidates.asSequence()
            .mapNotNull { absoluteUrl(baseUrl, it) }
            .firstOrNull { isValidPoster(it) }
    }

    private fun isValidPoster(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http") &&
            !lower.contains("placeholder") &&
            !lower.contains("no-image") &&
            !lower.endsWith(".svg") &&
            !lower.startsWith("data:")
    }

    private fun String.removeDurationSuffix(): String {
        return replace(Regex("\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\s*$"), "").trim()
    }
}
