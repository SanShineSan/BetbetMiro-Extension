package com.sad25kag.drakorasia

import android.util.Base64
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DrakorAsia : MainAPI() {
    override var mainUrl = "https://www.drakorasia.eu.org"
    override var name = "DrakorAsia"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private val feedHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json,text/javascript,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private val genreLabels = setOf(
        "Action", "Adventure", "Comedy", "Crime", "Drama", "Family", "Fantasy",
        "Historical", "Horror", "Law", "Life", "Melodrama", "Mystery", "Political",
        "Psychological", "Romance", "School", "Sci-Fi", "Sports", "Supernatural",
        "Thriller", "War", "Youth"
    )

    private val nonSeriesLabels = genreLabels + setOf(
        "Eps. TV", "TV", "Movie", "Series", "Completed", "CompletedTV", "Ongoing",
        "South Korea", "Thailand", "China", "Japan", "Netflix", "Disney+", "GMM 25",
        "Channel 3", "Viu", "TVING", "ENA", "jTBC", "KBS2", "MBC", "SBS", "tvN",
        "0-9", "0–9", "0 - 9", name, "drakorasia"
    )

    override val mainPage = mainPageOf(
        "" to "Episode Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest) = getMainPageData(request.data, page).let { data ->
        newHomePageResponse(
            request.name,
            data.items,
            hasNext = data.hasNext
        )
    }

    private suspend fun getMainPageData(label: String, page: Int): MainPageData {
        val posts = fetchFeed(label.ifBlank { null }, page, MAIN_PAGE_LIMIT)
        val filtered = filterMainPagePosts(label, posts)
        val fromFeed = filtered.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (fromFeed.isNotEmpty()) return MainPageData(fromFeed, posts.size >= MAIN_PAGE_LIMIT)

        val fallback = runCatching {
            parseCards(app.get(buildPageUrl(label, page), headers = headers, referer = mainUrl).document)
        }.getOrDefault(emptyList())

        return MainPageData(fallback, fallback.size >= MAIN_PAGE_LIMIT)
    }

    private fun filterMainPagePosts(label: String, posts: List<BloggerPost>): List<BloggerPost> {
        val filtered = when {
            label.isBlank() -> posts.filter { it.isEpisodePost() || it.isMoviePost() }
            label.equals("Series", true) -> posts.filter { it.isSeriesPost() && !it.isEpisodePost() }
            label.equals("Movie", true) -> posts.filter { it.isMoviePost() }
            else -> posts.filter { (it.isSeriesPost() || it.isMoviePost()) && !it.isEpisodePost() }
                .ifEmpty { posts.filter { it.isEpisodePost() || it.isSeriesPost() || it.isMoviePost() } }
        }
        return filtered.filter { it.isValidContentPost() }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val results = linkedMapOf<String, SearchResponse>()
        fetchSearchFeed(keyword).forEach { post ->
            if (post.matchesKeyword(keyword)) post.toSearchResult()?.let { results[it.url] = it }
        }

        fetchFeed("Series", 1, SEARCH_SERIES_LIMIT)
            .filter { it.matchesKeyword(keyword) }
            .forEach { post -> post.toSearchResult()?.let { results[it.url] = it } }

        val encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8.toString())
        val documentResults = runCatching {
            parseCards(app.get("$mainUrl/search?q=$encoded", headers = headers, referer = mainUrl).document)
        }.getOrDefault(emptyList())

        documentResults.forEach { response ->
            if (response.name.matchesKeyword(keyword)) results[response.url] = response
        }

        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = normalizeUrl(url)
        val document = app.get(withMobileParam(fixedUrl), headers = headers, referer = mainUrl).document
        val title = readTitle(document, fixedUrl)
        val seriesLabel = readSeriesLabel(document, title, fixedUrl)
        val seriesTitle = seriesLabel?.cleanTitle().orEmpty().ifBlank { cleanEpisodeTitle(title) }
        val metadataDocument = readSeriesDetailUrl(document, seriesTitle, fixedUrl)?.let { seriesUrl ->
            runCatching { app.get(withMobileParam(seriesUrl), headers = headers, referer = fixedUrl).document }.getOrNull()
        } ?: document

        val rawTags = readTags(metadataDocument).ifEmpty { readTags(document) }
        val tags = cleanTags(rawTags, seriesTitle)
        val poster = readPoster(metadataDocument, seriesTitle) ?: readPoster(document, seriesTitle)
        val plot = readPlot(metadataDocument, seriesTitle) ?: readPlot(document, seriesTitle)

        val episodes = readEpisodes(document, title, seriesTitle, fixedUrl)

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(seriesTitle, fixedUrl, TvType.AsianDrama, episodes) {
                posterUrl = poster ?: episodes.firstOrNull()?.posterUrl
                this.plot = plot
                this.tags = tags
            }
        } else {
            val movieTitle = title.cleanTitle()
            val playableData = pickPlayableData(document, fixedUrl, movieTitle)
            val tvType = if (tags.any { it.equals("Movie", true) } || movieTitle.contains("Movie", true)) TvType.Movie else TvType.AsianDrama
            newMovieLoadResponse(movieTitle, fixedUrl, tvType, playableData) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    private suspend fun readEpisodes(
        document: Document,
        pageTitle: String,
        seriesTitle: String,
        currentUrl: String
    ): List<Episode> {
        val posts = linkedMapOf<String, BloggerPost>()
        val labels = linkedSetOf<String>()
        readSeriesLabel(document, pageTitle, currentUrl)?.let(labels::add)
        seriesTitle.takeIf { it.isNotBlank() }?.let(labels::add)
        cleanEpisodeTitle(pageTitle).takeIf { it.isNotBlank() }?.let(labels::add)
        currentUrl.slugTitle().takeIf { it.isNotBlank() }?.let(labels::add)

        labels.forEach { label ->
            fetchFeed(label, 1, EPISODE_LIMIT)
                .filter { it.isEpisodePost() || it.title.contains("Episode", true) }
                .forEach { posts[it.url] = it }
        }

        if (posts.isEmpty()) {
            labels.forEach { label ->
                fetchSearchFeed(label)
                    .filter { it.isEpisodePost() || it.title.contains("Episode", true) || it.url.isEpisodeUrlFor(seriesTitle) }
                    .forEach { posts[it.url] = it }
            }
        }

        val fromFeed = posts.values
            .mapIndexedNotNull { index, post -> post.toEpisode(seriesTitle, index + 1) }

        val fromAnchors = document.select("a[href*='.html']")
            .mapNotNull { anchor ->
                val href = normalizeUrl(anchor.attr("abs:href").ifBlank { anchor.attr("href") }, currentUrl)
                if (!isValidContentUrl(href) || href.equals(currentUrl.substringBefore('?'), true)) return@mapNotNull null
                val anchorText = anchor.text().cleanTitle()
                if (!href.isEpisodeUrlFor(seriesTitle) && !anchorText.contains("Episode", true)) return@mapNotNull null
                val ep = extractEpisodeNumber(anchorText, href) ?: return@mapNotNull null
                newEpisode(href) {
                    name = "Episode ${ep.toString().padStart(2, '0')}"
                    episode = ep
                }
            }

        return (fromFeed + fromAnchors)
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private suspend fun pickPlayableData(document: Document, currentUrl: String, title: String): String {
        if (extractPlayerCandidates(document, currentUrl).any { it.isPlayableCandidate() }) return currentUrl
        return findWatchUrls(document, currentUrl, title).firstOrNull() ?: currentUrl
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = normalizeUrl(data)
        val visitedCandidates = linkedSetOf<String>()
        val visitedPages = linkedSetOf<String>()
        val emittedLinks = linkedSetOf<String>()
        var emittedCount = 0

        val countedCallback: (ExtractorLink) -> Unit = { link ->
            val key = link.url.substringBefore("#")
            if (emittedLinks.add(key)) {
                emittedCount++
                callback.invoke(link)
            }
        }

        suspend fun processPage(url: String, referer: String): Document? {
            val fixedPage = normalizeUrl(url, referer)
            if (!fixedPage.startsWith("http", true) || !visitedPages.add(fixedPage.substringBefore("#"))) return null
            val document = runCatching {
                app.get(withMobileParam(fixedPage), headers = headers, referer = referer).document
            }.getOrNull() ?: return null

            extractPlayerCandidates(document, fixedPage)
                .filter { it.isPlayableCandidate() }
                .forEach { candidate ->
                    resolveCandidate(candidate, fixedPage, visitedCandidates, subtitleCallback, countedCallback)
                }
            return document
        }

        val firstDocument = processPage(pageUrl, mainUrl)

        if (emittedCount == 0 && firstDocument != null && pageUrl.startsWith(mainUrl, true)) {
            val title = readTitle(firstDocument, pageUrl)
            findWatchUrls(firstDocument, pageUrl, cleanEpisodeTitle(title))
                .take(6)
                .forEach { watchUrl ->
                    processPage(watchUrl, pageUrl)
                }
        }

        return emittedCount > 0
    }

    private fun extractPlayerCandidates(document: Document, pageUrl: String): Set<String> {
        val candidates = linkedSetOf<String>()
        collectCandidates(document.html(), pageUrl, candidates)
        document.select("iframe[src], embed[src], video[src], video source[src], option[value], .mobius option[value], select option[value], .mirror option[value], [data-url], [data-src], [data-link], [data-href], [data-iframe], [data-embed], [data-player], [data-video], [data-file], [data-stream]")
            .forEach { element ->
                listOf("src", "value", "data-url", "data-src", "data-link", "data-href", "data-iframe", "data-embed", "data-player", "data-video", "data-file", "data-stream").forEach attrs@{ attr ->
                    val raw = element.attr(attr).trim()
                    if (raw.isBlank() || raw == "#" || raw.startsWith("javascript", true)) return@attrs
                    val decoded = decodePayload(raw)
                    collectCandidates(decoded, pageUrl, candidates)
                    Jsoup.parse(decoded).select("iframe[src], embed[src], video[src], video source[src]").forEach { nested ->
                        listOf("src").forEach { nestedAttr ->
                            normalizeUrl(nested.attr(nestedAttr), pageUrl)
                                .takeIf { it.startsWith("http", true) }
                                ?.let(candidates::add)
                        }
                    }
                    if (decoded.startsWith("http", true)) candidates.add(decoded)
                    else normalizeUrl(decoded, pageUrl).takeIf { it.startsWith("http", true) }?.let(candidates::add)
                }
            }
        return candidates
    }

    private suspend fun findWatchUrls(document: Document, currentUrl: String, title: String): List<String> {
        val urls = linkedSetOf<String>()
        val baseSlug = currentUrl.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        document.select("a[href*='.html']").forEach { anchor ->
            val href = normalizeUrl(anchor.attr("abs:href").ifBlank { anchor.attr("href") }, currentUrl)
            if (!isValidContentUrl(href)) return@forEach
            if (href.equals(currentUrl.substringBefore('?'), true)) return@forEach
            val text = anchor.text().cleanTitle()
            val slug = href.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
            val looksLikeWatch = slug.contains("episode", true) ||
                slug.startsWith("$baseSlug-episode", true) ||
                text.contains("Episode", true) ||
                text.contains("Nonton", true) ||
                text.contains("Watch", true) ||
                text.contains("Play", true)
            if (looksLikeWatch) urls.add(href)
        }

        val labels = linkedSetOf<String>()
        readSeriesLabel(document, title, currentUrl)?.let(labels::add)
        title.cleanTitle().takeIf { it.isNotBlank() }?.let(labels::add)
        currentUrl.slugTitle().takeIf { it.isNotBlank() }?.let(labels::add)

        labels.forEach { label ->
            fetchFeed(label, 1, EPISODE_LIMIT)
                .filter { it.isEpisodePost() || it.title.contains("Episode", true) }
                .forEach { urls.add(it.url) }
        }

        if (urls.isEmpty()) {
            labels.forEach { label ->
                fetchSearchFeed(label)
                    .filter { it.isEpisodePost() || it.title.contains("Episode", true) || it.url.isEpisodeUrlFor(label) }
                    .forEach { urls.add(it.url) }
            }
        }

        return urls.toList().sortedWith(compareBy<String> { extractEpisodeNumber(it, it) ?: Int.MAX_VALUE }.thenBy { it })
    }

    private suspend fun resolveCandidate(
        candidate: String,
        referer: String,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = candidate.replace("\\/", "/").trim().trim('"', '\'', ',', ';')
        if (!url.startsWith("http", true)) return false
        if (!visited.add(url.substringBefore("#"))) return false

        if (url.isDownloadCandidate() || url.isDirectMedia()) {
            emitDirect(url, referer, callback, if (url.isDownloadCandidate()) "Download" else null)
            return true
        }

        return loadExtractor(url, referer, subtitleCallback, callback)
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select("article.post, article.hentry, .blog-posts .hentry, .post-outer, .grid-post, .related-post, .item-post")
            .forEach { element -> element.toSearchResult()?.let { results[it.url] = it } }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href*='.html']") ?: return null
        val href = normalizeUrl(anchor.attr("abs:href").ifBlank { anchor.attr("href") })
        if (!isValidContentUrl(href)) return null

        val title = listOf(
            selectFirst("h1, h2, h3, h4, .entry-title, .post-title, .title")?.text(),
            anchor.attr("title"),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?.takeIf { isValidTitle(it) }
            ?: return null

        val poster = fixUrlNull(selectFirst("img")?.imageAttr()?.fixBloggerImage())
        val isMovie = href.contains("/movie/", true) || title.contains("Movie", true)
        return if (isMovie) newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        else newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
    }

    private fun BloggerPost.toSearchResult(): SearchResponse? {
        if (!isValidContentUrl(url) || !isValidTitle(title)) return null
        val fixedPoster = fixUrlNull(poster?.fixBloggerImage())
        return if (isMoviePost()) newMovieSearchResponse(title.cleanTitle(), url, TvType.Movie) { posterUrl = fixedPoster }
        else newTvSeriesSearchResponse(title.cleanTitle(), url, TvType.AsianDrama) { posterUrl = fixedPoster }
    }

    private fun BloggerPost.toEpisode(seriesTitle: String, fallbackEpisode: Int): Episode? {
        if (!isValidContentUrl(url)) return null
        val ep = extractEpisodeNumber(title, url) ?: fallbackEpisode
        return newEpisode(url) {
            name = "Episode ${ep.toString().padStart(2, '0')}"
            episode = ep
            posterUrl = fixUrlNull(poster?.takeUnless { it.isBadImageUrl(seriesTitle) }?.fixBloggerImage())
        }
    }

    private suspend fun fetchSearchFeed(query: String): List<BloggerPost> {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())
        return fetchFeedUrl("$mainUrl/feeds/posts/default?alt=json&q=$encoded&max-results=$SEARCH_LIMIT")
    }

    private suspend fun fetchFeed(label: String?, page: Int, max: Int): List<BloggerPost> {
        val start = ((page - 1).coerceAtLeast(0) * max) + 1
        val encodedLabel = label?.trim()?.takeIf { it.isNotBlank() }?.let { encodeLabelPath(it) }
        val url = if (encodedLabel.isNullOrBlank()) {
            "$mainUrl/feeds/posts/default?alt=json&start-index=$start&max-results=$max&orderby=published"
        } else {
            "$mainUrl/feeds/posts/default/-/$encodedLabel?alt=json&start-index=$start&max-results=$max"
        }
        return fetchFeedUrl(url)
    }

    private suspend fun fetchFeedUrl(url: String): List<BloggerPost> {
        return runCatching { parseBloggerFeed(app.get(url, headers = feedHeaders, referer = mainUrl).text) }
            .getOrDefault(emptyList())
    }

    private fun parseBloggerFeed(raw: String): List<BloggerPost> {
        val json = unwrapJson(raw)
        val root = runCatching { JsonParser().parse(json).asJsonObject }.getOrNull() ?: return emptyList()
        val entries = root.obj("feed")?.array("entry") ?: return emptyList()
        return entries.mapNotNull { element ->
            val entry = element.asObjectOrNull() ?: return@mapNotNull null
            val title = entry.obj("title")?.string("\$t")?.cleanTitle().orEmpty()
            val url = entry.array("link")
                ?.mapNotNull { it.asObjectOrNull() }
                ?.firstOrNull { it.string("rel").equals("alternate", true) }
                ?.string("href")
                ?.let { normalizeUrl(it) }
                .orEmpty()
            val labels = entry.array("category")
                ?.mapNotNull { it.asObjectOrNull()?.string("term")?.cleanTitle() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty()
            val content = entry.obj("content")?.string("\$t") ?: entry.obj("summary")?.string("\$t") ?: ""
            val poster = listOf(entry.obj("media\$thumbnail")?.string("url"), extractImageFromContent(content))
                .firstOrNull { !it.isNullOrBlank() }
                ?.fixBloggerImage()
            BloggerPost(title, url, poster, labels, content)
        }
    }

    private fun unwrapJson(raw: String): String {
        val stripped = raw.trim().replace(Regex("^\\s*//.*?\\n"), "")
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        return if (start >= 0 && end > start) stripped.substring(start, end + 1) else stripped
    }

    private fun extractImageFromContent(content: String): String? {
        return Jsoup.parse(content).selectFirst("img[src], img[data-src], img[data-original]")
            ?.imageAttr()
            ?.takeIf { it.isNotBlank() }
    }

    private fun readSeriesLabel(document: Document, title: String, fallbackUrl: String): String? {
        Regex("""var\s+label_episode\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(document.html())
            ?.groupValues
            ?.getOrNull(1)
            ?.urlDecodeSafe()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        title.toSeriesLabelFromEpisode()?.let { return it }

        val slug = fallbackUrl.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        Regex("""(?i)^(.+?)-episode[-\s]*\d+""").find(slug)?.groupValues?.getOrNull(1)
            ?.replace('-', ' ')
            ?.urlDecodeSafe()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return document.select("a[rel=tag][href*='/search/label/'], a[href*='/search/label/']")
            .map { it.text().trim(',', ' ', '\n', '\t').cleanTitle() }
            .firstOrNull { label ->
                label.isNotBlank() &&
                    label.length > 1 &&
                    !label.matches(Regex("^[A-Z0-9]$")) &&
                    !nonSeriesLabels.any { it.equals(label, true) }
            }
    }

    private fun readSeriesDetailUrl(document: Document, seriesTitle: String, fallbackUrl: String): String? {
        val normalizedTitle = seriesTitle.cleanTitle()
        return document.select("a[href*='.html']")
            .asSequence()
            .mapNotNull { anchor ->
                val href = normalizeUrl(anchor.attr("abs:href").ifBlank { anchor.attr("href") })
                val text = anchor.text().cleanTitle()
                href.takeIf {
                    isValidContentUrl(it) &&
                        !it.contains("episode", true) &&
                        !it.equals(fallbackUrl.substringBefore('?'), true) &&
                        (text.equals(normalizedTitle, true) || it.slugTitle().equals(normalizedTitle, true))
                }
            }
            .firstOrNull()
    }

    private fun cleanTags(rawTags: List<String>, seriesTitle: String): List<String> {
        val blocked = nonSeriesLabels + setOf("Series", "Eps. TV", "TV", name, seriesTitle)
        return rawTags
            .map { it.cleanTitle() }
            .filter { tag ->
                tag.length > 1 &&
                    !tag.matches(Regex("^[A-Z0-9]$")) &&
                    !blocked.any { it.equals(tag, true) } &&
                    !tag.equals(seriesTitle, true)
            }
            .distinct()
            .take(12)
    }

    private fun String.isBadImageUrl(seriesTitle: String? = null): Boolean {
        val value = lowercase()
        if (isBlank()) return true
        if (startsWith("data:image", true)) return true
        if (value.contains("favicon") || value.contains("logo") || value.contains("ads")) return true
        if (value.contains("drakorasia") && !seriesTitle.isNullOrBlank()) return true
        if (value.contains("1.bp.blogspot.com") && value.contains("241790225")) return true
        return false
    }

    private fun String.isValidPlot(title: String): Boolean {
        val clean = cleanTitle()
        if (clean.length < 35) return false
        if (clean.equals(title.cleanTitle(), true)) return false
        if (clean.contains("Video Server", true)) return false
        if (clean.contains("Download Turn Off Light", true)) return false
        if (clean.matches(Regex("""(?i).*(episode|season)\s*\d+.*"""))) return false
        return true
    }

    private fun String.slugTitle(): String {
        return substringBefore('?')
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .replace(Regex("""(?i)-episode[-\s]*\d+.*$"""), "")
            .replace('-', ' ')
            .urlDecodeSafe()
            .cleanTitle()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val base = when {
            path.isBlank() -> mainUrl
            path.equals("Series", true) -> "$mainUrl/search/label/Series"
            path.startsWith("http", true) -> path
            else -> "$mainUrl/search/label/${encodeLabelPath(path)}"
        }
        if (page <= 1) return base
        return if (base.contains("/search/label/", true)) "$base?max-results=$MAIN_PAGE_LIMIT" else base
    }

    private fun readTitle(document: Document, fallbackUrl: String): String {
        return document.selectFirst("h1.entry-title, h1.post-title, h1, meta[property=og:title], title")
            ?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackUrl.substringAfterLast('/').substringBefore('.').replace('-', ' ').cleanTitle()
    }

    private fun readPoster(document: Document, seriesTitle: String? = null): String? {
        return document.select(".post-body img[src], .entry-content img[src], article img[src], meta[property=og:image], meta[name=twitter:image]")
            .asSequence()
            .mapNotNull { element -> if (element.hasAttr("content")) element.attr("content") else element.imageAttr() }
            .map { it.fixBloggerImage() }
            .firstOrNull { !it.isBadImageUrl(seriesTitle) }
            ?.let { fixUrlNull(it) }
    }

    private fun readPlot(document: Document, title: String): String? {
        return document.select(".sinoposis, .sinopsis, .post-body p, .entry-content p, article p, meta[property=og:description], meta[name=description]")
            .asSequence()
            .map { if (it.hasAttr("content")) it.attr("content") else it.text() }
            .map { it.cleanTitle() }
            .firstOrNull { it.isValidPlot(title) }
    }

    private fun readTags(document: Document): List<String> {
        return document.select("a[rel=tag][href*='/search/label/'], a[href*='/search/label/']")
            .map { it.text().trim(',', ' ', '\n', '\t').cleanTitle() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun collectCandidates(text: String, referer: String, output: MutableSet<String>) {
        val normalized = decodePayload(text).replace("\\/", "/")
        Regex("""https?://[^\"'\s<>]+""")
            .findAll(normalized)
            .map { it.value.trim().trim('"', '\'', ',', ';', ')', ']', '}') }
            .forEach(output::add)

        Regex("""(?:src|href|file|url|value)\s*[:=]\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { normalizeUrl(it, referer) }
            .filter { it.startsWith("http", true) }
            .forEach(output::add)
    }

    private fun decodePayload(raw: String): String {
        val unescaped = raw.htmlUnescape().replace("\\/", "/")
        val urlDecoded = runCatching { URLDecoder.decode(unescaped, StandardCharsets.UTF_8.toString()) }.getOrDefault(unescaped)
        val base64Candidate = urlDecoded.substringAfter("base64,", urlDecoded).trim().trim('"', '\'', ')', ';')
        if (base64Candidate.length >= 8 && base64Candidate.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) {
            listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP).forEach { flag ->
                val decoded = runCatching { String(Base64.decode(base64Candidate, flag), Charsets.UTF_8) }.getOrNull()
                if (!decoded.isNullOrBlank() && (decoded.contains("http", true) || decoded.contains("iframe", true))) return decoded
            }
        }
        return urlDecoded
    }

    private suspend fun emitDirect(mediaUrl: String, referer: String, callback: (ExtractorLink) -> Unit, label: String? = null) {
        val fixed = mediaUrl.replace("\\/", "/").htmlUnescape()
        val type = when {
            fixed.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            fixed.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        val quality = getQualityFromName(fixed).let { if (it == Qualities.Unknown.value) inferQuality(fixed) else it }
        callback.invoke(
            newExtractorLink(
                source = name,
                name = label ?: "$name ${if (quality == Qualities.Unknown.value) "Auto" else "${quality}p"}",
                url = fixed,
                type = type
            ) {
                this.quality = quality
                this.referer = referer
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to USER_AGENT
                )
            }
        )
    }

    private fun String.isPlayableCandidate(): Boolean {
        val value = lowercase()
        return isDirectMedia() || isDownloadCandidate() || listOf(
            "short.ink", "abyssplayer.com", "abyss.to", "blogger.com/video.g", "googlevideo.com", "blogspot.com",
            "dailymotion", "ok.ru", "odnoklassniki", "streamtape", "filemoon", "dood", "vidhide", "vidguard",
            "voe.sx", "mixdrop", "mp4upload", "streamwish", "streamruby", "sendvid", "uqload",
            "desustream", "ondesu"
        ).any { value.contains(it) }
    }

    private fun String.isDownloadCandidate(): Boolean {
        val value = lowercase().htmlUnescape()
        return value.contains("/video/down.php") || value.contains("katong.usbx.me/video/down")
    }

    private fun String.isDirectMedia(): Boolean {
        val value = lowercase().substringBefore("#")
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".webm") || value.contains(".mkv") || value.contains(".mpd") || value.contains("videoplayback")
    }

    private fun String.isEpisodeUrlFor(seriesTitle: String): Boolean {
        val value = lowercase()
        val slug = seriesTitle.cleanTitle().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return value.contains("episode", true) && (slug.isBlank() || value.contains(slug))
    }

    private fun isValidContentUrl(url: String): Boolean {
        return url.startsWith(mainUrl, true) &&
            url.contains(".html", true) &&
            !url.contains("/p/", true) &&
            !url.contains("/search/", true)
    }

    private fun isValidTitle(title: String): Boolean {
        val clean = title.cleanTitle()
        if (clean.length < 2) return false
        return !listOf("Home", "Genre", "Season", "Drama List", "DRAMA LIST", "Video Server", "Server 1", "Tonton", "Nonton", name, "drakorasia")
            .any { clean.equals(it, true) }
    }

    private fun BloggerPost.isValidContentPost(): Boolean = isValidContentUrl(url) && isValidTitle(title)

    private fun BloggerPost.isEpisodePost(): Boolean {
        return title.contains("Episode", true) || labels.any { it.equals("Eps. TV", true) || it.startsWith("Eps.", true) }
    }

    private fun BloggerPost.isSeriesPost(): Boolean = labels.any { it.equals("Series", true) }
    private fun BloggerPost.isMoviePost(): Boolean = labels.any { it.equals("Movie", true) }

    private fun BloggerPost.matchesKeyword(keyword: String): Boolean {
        val words = keyword.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        val haystack = (title + " " + labels.joinToString(" ")).lowercase()
        return words.all { haystack.contains(it) } || title.lowercase().contains(keyword.lowercase())
    }

    private fun String.matchesKeyword(keyword: String): Boolean {
        val words = keyword.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        val haystack = lowercase()
        return words.all { haystack.contains(it) } || haystack.contains(keyword.lowercase())
    }

    private fun String.toSeriesLabelFromEpisode(): String? {
        return replace(Regex("(?i)\\s+Episode\\s+\\d+.*$"), "")
            .replace(Regex("(?i)\\s+Eps?\\.?\\s*\\d+.*$"), "")
            .cleanTitle()
            .takeIf { it.isNotBlank() && it != cleanTitle() }
    }

    private fun cleanEpisodeTitle(title: String): String {
        return title.toSeriesLabelFromEpisode() ?: title
            .replace(Regex("(?i)\\s+Sub\\s+Indo.*$"), "")
            .cleanTitle()
    }

    private fun extractEpisodeNumber(text: String, href: String): Int? {
        return Regex("(?i)episode[-\\s]*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)episode[-\\s]*(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)eps?\\.?[-\\s]*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)eps?\\.?[-\\s]*(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.urlDecodeSafe(): String {
        return runCatching { URLDecoder.decode(this, StandardCharsets.UTF_8.toString()) }.getOrDefault(this)
    }

    private fun normalizeUrl(url: String, referer: String = mainUrl): String {
        val clean = url.trim().htmlUnescape()
        if (clean.isBlank()) return clean
        if (clean.startsWith("//")) return "https:$clean"
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) return clean

        return runCatching {
            val base = URI(normalizeBaseUrl(referer))
            base.resolve(clean).toString()
        }.getOrElse {
            when {
                clean.startsWith("/") -> mainUrl.trimEnd('/') + clean
                else -> referer.substringBefore('?').substringBefore('#').substringBeforeLast('/').trimEnd('/') + "/" + clean
            }
        }
    }

    private fun normalizeBaseUrl(url: String): String {
        val clean = url.trim().htmlUnescape()
        if (clean.isBlank()) return mainUrl.trimEnd('/') + "/"
        if (clean.startsWith("//")) return "https:$clean"
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
            val noFragment = clean.substringBefore('#')
            return if (noFragment.substringAfter("//", "").contains('/')) noFragment else noFragment.trimEnd('/') + "/"
        }
        return mainUrl.trimEnd('/') + "/"
    }

    private fun withMobileParam(url: String): String {
        return if (url.contains("?", true)) url else "$url?m=1"
    }

    private fun encodeLabelPath(label: String): String {
        return URLEncoder.encode(label.trim(), StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }

    private fun Element.imageAttr(): String {
        return attr("abs:src").ifBlank { attr("abs:data-src") }
            .ifBlank { attr("abs:data-original") }
            .ifBlank { attr("src") }
            .ifBlank { attr("data-src") }
            .ifBlank { attr("data-original") }
    }

    private fun String.fixBloggerImage(): String {
        return replace(Regex("/s\\d+(-c|-rw)?/"), "/s500/")
            .replace(Regex("=s\\d+(-c|-rw)?"), "=s500")
    }

    private fun inferQuality(url: String): Int {
        return when {
            url.contains("2160", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.cleanTitle(): String {
        return htmlUnescape()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)\\s+[-–]\\s+drakorasia.*$"), "")
            .replace(Regex("(?i)^Nonton\\s+"), "")
            .trim()
    }

    private fun String.htmlUnescape(): String {
        return replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
    }

    private fun JsonElement?.asObjectOrNull(): JsonObject? {
        return this?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun JsonObject.obj(key: String): JsonObject? {
        return get(key).asObjectOrNull()
    }

    private fun JsonObject.array(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.string(key: String): String? {
        return get(key)?.takeIf { !it.isJsonNull }?.asString
    }

    private data class MainPageData(
        val items: List<SearchResponse>,
        val hasNext: Boolean
    )

    private data class BloggerPost(
        val title: String,
        val url: String,
        val poster: String?,
        val labels: List<String>,
        val content: String
    )

    companion object {
        private const val MAIN_PAGE_LIMIT = 20
        private const val SEARCH_LIMIT = 30
        private const val SEARCH_SERIES_LIMIT = 150
        private const val EPISODE_LIMIT = 150
    }
}
