package com.pasarbokep

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object PasarBokepParser {
    private val listingStopHeading = Regex("(?i)^(bokep terbaru|related videos|latest videos|random videos|login to|reset password).*")

    fun parseCards(document: Document, api: MainAPI, strictListing: Boolean = false): List<SearchResponse> {
        val cards = linkedMapOf<String, PasarBokepCard>()

        val parsed = if (strictListing) parseStrictListing(document, api.mainUrl) else emptyList()
        parsed.forEach { card -> cards.putIfAbsent(card.url, card) }

        if (cards.isEmpty()) {
            parseSafeBlocks(document, api.mainUrl).forEach { card -> cards.putIfAbsent(card.url, card) }
        }

        return cards.values.map { card ->
            api.newMovieSearchResponse(
                card.title,
                PasarBokepUtils.packLoadData(card.url, card.posterUrl),
                TvType.NSFW,
            ) {
                this.posterUrl = card.posterUrl
            }
        }
    }

    fun parseTags(document: Document): List<String> {
        return document.select("a[href*=/category/], a[rel=category tag], .cat-links a")
            .map { PasarBokepUtils.cleanText(it.text()) }
            .filter { it.length > 2 && !it.contains("uncensored", ignoreCase = true) }
            .distinct()
            .take(8)
    }

    fun parsePlot(document: Document): String? {
        val content = document.selectFirst(
            ".entry-content, .post-content, .single-content, article .content, article, main"
        ) ?: return null

        val text = content.text()
            .substringBefore("Related videos")
            .substringBefore("Bokep Terbaru")
            .substringBefore("Latest videos")
            .substringBefore("Registration is disabled")
            .substringBefore("Login to")

        return PasarBokepUtils.cleanText(text).takeIf { it.length > 12 }
    }

    private fun parseStrictListing(document: Document, mainUrl: String): List<PasarBokepCard> {
        val scope = document.selectFirst("main, #content, .site-content, .content-area, body") ?: document
        val out = linkedMapOf<String, PasarBokepCard>()
        var afterMainHeading = scope.selectFirst("h1") == null
        var stopped = false

        scope.getAllElements().forEach { element ->
            if (stopped) return@forEach
            val own = PasarBokepUtils.cleanText(element.ownText())
            val tag = element.tagName().lowercase()
            if (tag in listOf("h1", "h2", "h3")) {
                if (tag == "h1") afterMainHeading = true
                if (listingStopHeading.matches(own)) {
                    stopped = true
                    return@forEach
                }
            }
            if (!afterMainHeading || tag != "a") return@forEach
            parseAnchor(element, mainUrl)?.let { card -> out.putIfAbsent(card.url, card) }
        }

        return out.values.toList()
    }

    private fun parseSafeBlocks(document: Document, mainUrl: String): List<PasarBokepCard> {
        val clean = document.clone()
        clean.select(
            "nav, header, footer, aside, form, script, style, .sidebar, .widget, .menu, .pagination, .page-numbers, " +
                ".related, .related-posts, .login, .modal, .popup, .ad, .ads, [class*=ad-], [id*=ad-], [class*=banner], [id*=banner]"
        ).remove()

        val cards = linkedMapOf<String, PasarBokepCard>()
        clean.select("article, .post, .video, .item, .thumb, .loop, .entry, .post-item, .video-item").forEach { block ->
            parseBlock(block, mainUrl)?.let { card -> cards.putIfAbsent(card.url, card) }
        }

        if (cards.isEmpty()) {
            clean.select("main a[href], #content a[href], .site-content a[href]").forEach { anchor ->
                parseAnchor(anchor, mainUrl)?.let { card -> cards.putIfAbsent(card.url, card) }
            }
        }

        return cards.values.toList()
    }

    private fun parseBlock(block: Element, mainUrl: String): PasarBokepCard? {
        if (isJunkBlock(block)) return null
        val anchor = block.selectFirst("a[href]") ?: return null
        val href = PasarBokepUtils.absoluteUrl(anchor.attr("href"), mainUrl) ?: return null
        val title = PasarBokepUtils.cleanText(
            block.selectFirst("h1, h2, h3, .entry-title, .post-title, .title")?.text()
                ?.ifBlank { anchor.attr("title") }
                ?.ifBlank { anchor.text() }
                ?.ifBlank { block.selectFirst("img")?.attr("alt").orEmpty() }
                ?: anchor.attr("title")
                    .ifBlank { anchor.text() }
                    .ifBlank { block.selectFirst("img")?.attr("alt").orEmpty() }
        ).ifBlank { PasarBokepUtils.titleFromUrl(href) }

        if (!PasarBokepUtils.isLikelyVideoPage(href, title, mainUrl)) return null
        return PasarBokepCard(title = title, url = href, posterUrl = block.bestPosterFromBlock(mainUrl))
    }

    private fun parseAnchor(anchor: Element, mainUrl: String): PasarBokepCard? {
        if (isJunkBlock(anchor)) return null
        val href = PasarBokepUtils.absoluteUrl(anchor.attr("href"), mainUrl) ?: return null
        val title = PasarBokepUtils.cleanText(
            anchor.attr("title")
                .ifBlank { anchor.text() }
                .ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty() }
        ).ifBlank { PasarBokepUtils.titleFromUrl(href) }

        if (!PasarBokepUtils.isLikelyVideoPage(href, title, mainUrl)) return null
        val block = anchor.closest("article, .post, .video, .item, .thumb, .loop, .entry, .post-item, .video-item") ?: anchor.parent() ?: anchor
        return PasarBokepCard(title = title, url = href, posterUrl = block.bestPosterFromBlock(mainUrl))
    }

    private fun isJunkBlock(element: Element): Boolean {
        val text = PasarBokepUtils.cleanText(element.text()).lowercase()
        val cls = element.className().lowercase()
        val id = element.id().lowercase()
        if (text.isBlank() && element.selectFirst("img") == null) return true
        if (PasarBokepSeeds.blockedTitleHints.any { text == it || text.startsWith(it) }) return true
        return listOf("sidebar", "widget", "related", "latest", "random", "menu", "pagination", "login", "ad", "banner").any {
            cls.contains(it) || id.contains(it)
        }
    }

    private fun Element.bestPosterFromBlock(mainUrl: String): String? {
        return this.bestImage(mainUrl)
            ?: parent()?.bestImage(mainUrl)
            ?: selectFirst("img")?.let { PasarBokepUtils.absoluteUrl(it.attr("data-src").ifBlank { imgSrcFallback(it) }, mainUrl) }
                ?.takeIf { PasarBokepUtils.isValidPoster(it) }
    }

    private fun imgSrcFallback(img: Element): String {
        return img.attr("data-lazy-src")
            .ifBlank { img.attr("data-original") }
            .ifBlank { img.attr("src") }
            .ifBlank { img.attr("srcset").substringBefore(" ") }
    }
}
