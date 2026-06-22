package com.sad25kag.nanimeindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class NanimeIndoProvider : MainAPI() {
    override var mainUrl = "https://nanimeindo.com"
    override var name = "NanimeIndo"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Rilisan Terbaru",
        "$mainUrl/anime/" to "Anime List",
        "$mainUrl/ongoing-anime/" to "Ongoing Anime",
        "$mainUrl/complete-anime/" to "Completed Anime",
        "$mainUrl/movie/" to "Anime Movie",
        "$mainUrl/genres/action/" to "Action",
        "$mainUrl/genres/adventure/" to "Adventure",
        "$mainUrl/genres/comedy/" to "Comedy",
        "$mainUrl/genres/fantasy/" to "Fantasy",
        "$mainUrl/genres/romance/" to "Romance"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data.toPagedUrl(page), headers = browserHeaders, referer = "$mainUrl/").document
        val items = document.parseCards().distinctBy { it.url.normalizedKey() }
        val hasNext = document.hasNextPage(page)
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val routes = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?s=$encoded&post_type=anime",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/search/$encoded/"
        )

        val results = linkedMapOf<String, SearchResponse>()
        for (route in routes) {
            val document = runCatching {
                app.get(route, headers = browserHeaders, referer = "$mainUrl/").document
            }.getOrNull() ?: continue
            document.parseCards().forEach { item -> results.putIfAbsent(item.url.normalizedKey(), item) }
            if (results.isNotEmpty()) break
        }
        return results.values.filter { it.name.contains(keyword, ignoreCase = true) || results.size <= 8 }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = url.toAbsoluteUrl(mainUrl) ?: return null
        val document = app.get(fixedUrl, headers = browserHeaders, referer = "$mainUrl/").document
        val html = document.html().decodeHtmlSource()

        val title = document.bestTitle()?.cleanTitle()?.takeIf { it.isNotBlank() }
            ?: fixedUrl.slugTitle()
        val poster = document.bestPoster(fixedUrl)
        val plot = document.bestPlot()
        val tags = document.genreTags()
        val year = document.parseYear()
        val status = document.detectStatus()
        val type = title.detectTvType(fixedUrl)
        val recommendations = document.parseCards()
            .filterNot { it.url.normalizedKey() == fixedUrl.normalizedKey() }
            .distinctBy { it.url.normalizedKey() }
            .take(16)

        val episodes = document.parseEpisodes(poster)
            .ifEmpty { html.parseEpisodeUrlsFromText(poster) }
            .distinctBy { it.data.normalizedKey() }
            .sortedWith(compareBy({ it.episode ?: Int.MAX_VALUE }, { it.name.orEmpty() }))
            .ifEmpty { listOf(newEpisode(fixedUrl) { name = if (type == TvType.AnimeMovie) "Movie" else title }) }

        return if (type != TvType.Anime && episodes.size <= 1) {
            newMovieLoadResponse(title, fixedUrl, type, episodes.firstOrNull()?.data ?: fixedUrl) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                showStatus = status
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.toAbsoluteUrl(mainUrl) ?: return false
        val response = runCatching {
            app.get(pageUrl, headers = browserHeaders, referer = "$mainUrl/")
        }.getOrNull() ?: return false

        val document = response.document
        val emitted = linkedSetOf<String>()
        val candidates = linkedSetOf<PlayerCandidate>()

        fun addCandidate(raw: String?, label: String? = null, referer: String = pageUrl) {
            raw?.toCandidateValues(referer)?.forEach { candidateUrl ->
                if (!candidateUrl.isNoiseUrl() && candidateUrl.isPlayableCandidate()) {
                    candidates.add(PlayerCandidate(candidateUrl, label?.cleanLabel(), referer))
                }
            }
        }

        document.collectSubtitles(pageUrl, subtitleCallback)
        document.select(
            "#pembed iframe[src], .player-embed iframe[src], #embed_holder iframe[src], " +
                ".player iframe[src], .embed iframe[src], iframe[src], iframe[data-src], embed[src], video[src], source[src]"
        ).forEach { element ->
            addCandidate(
                element.attr("src").ifBlank { element.attr("data-src") },
                element.attr("title").ifBlank { element.attr("label") },
                pageUrl
            )
        }
        document.select("select.mirror option[value], .mirror option[value], select option[value]").forEach { option ->
            addCandidate(option.attr("value"), option.text(), pageUrl)
        }
        document.select("[data-src], [data-url], [data-file], [data-player], [data-video], [data-iframe], [data-embed], [data-href], [value]").forEach { element ->
            val label = element.text().cleanLabel().takeIf { it.isNotBlank() }
            listOf("data-src", "data-url", "data-file", "data-player", "data-video", "data-iframe", "data-embed", "data-href", "value")
                .forEach { attr -> addCandidate(element.attr(attr), label, pageUrl) }
        }
        collectUrlsFromText(response.text, pageUrl).forEach { addCandidate(it, null, pageUrl) }

        suspend fun emitDirect(rawUrl: String, label: String, referer: String): Boolean {
            val fixed = rawUrl.toAbsoluteUrl(referer) ?: return false
            if (!fixed.isDirectMedia()) return false
            val key = fixed.normalizedKey()
            if (!emitted.add(key)) return true
            val safeLabel = label.ifBlank { fixed.qualityLabelFromUrl().ifBlank { fixed.hostLabel() } }
            val headers = mediaHeaders(fixed, referer)
            val mediaReferer = mediaReferer(fixed, referer)

            if (fixed.contains(".m3u8", true)) {
                val links = runCatching {
                    M3u8Helper.generateM3u8(safeLabel, fixed, mediaReferer, headers = headers)
                }.getOrDefault(emptyList())
                links.forEach { callback.invoke(it) }
                if (links.isNotEmpty()) return true
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - $safeLabel",
                    url = fixed,
                    type = if (fixed.contains(".m3u8", true)) INFER_TYPE else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mediaReferer
                    this.quality = fixed.parseQuality() ?: Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        }

        suspend fun inspectNested(candidate: PlayerCandidate) {
            val html = runCatching {
                app.get(
                    candidate.url,
                    headers = browserHeaders + mapOf("Referer" to candidate.referer),
                    referer = candidate.referer,
                    timeout = 15L
                ).text
            }.getOrNull() ?: return
            val unpacked = runCatching { getAndUnpack(html) }.getOrNull().orEmpty()
            collectUrlsFromText("$html\n$unpacked", candidate.url).forEach { nested ->
                val label = candidate.label.orEmpty().ifBlank { nested.hostLabel() }
                if (nested.isDirectMedia()) {
                    emitDirect(nested, label, candidate.url)
                } else if (nested.isPlayableCandidate()) {
                    runCatching {
                        loadExtractor(nested, candidate.url, subtitleCallback) { link ->
                            if (emitted.add(link.url.normalizedKey())) callback.invoke(link)
                        }
                    }
                }
            }
        }

        candidates.distinctBy { it.url.normalizedKey() }.take(48).forEach { candidate ->
            val label = candidate.label.orEmpty().ifBlank { candidate.url.hostLabel() }
            if (candidate.url.isDirectMedia()) {
                emitDirect(candidate.url, label, candidate.referer)
            } else {
                val before = emitted.size
                runCatching {
                    loadExtractor(candidate.url, candidate.referer, subtitleCallback) { link ->
                        if (emitted.add(link.url.normalizedKey())) callback.invoke(link)
                    }
                }
                if (emitted.size == before && candidate.url.shouldInspectInline()) {
                    inspectNested(candidate)
                }
            }
        }

        return emitted.isNotEmpty()
    }

    private fun String.toPagedUrl(page: Int): String {
        val base = trimEnd('/')
        if (page <= 1) return if (base.isBlank()) "$mainUrl/" else "$base/"
        return if (contains("?")) "$base&page=$page" else "$base/page/$page/"
    }

    private fun Document.parseCards(): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val selectors = listOf(
            ".listupd article", ".listupd .bsx", ".bs .bsx", "article.bs", ".bsx",
            ".postbody article", ".post article", ".animepost", ".post-item", ".item", ".result", "article"
        )
        selectors.flatMap { select(it) }.forEach { card ->
            card.toSearchResponseStrict()?.let { results.putIfAbsent(it.url.normalizedKey(), it) }
        }
        if (results.isEmpty()) {
            select("a[href]").forEach { anchor ->
                anchor.toSearchResponseFromAnchor()?.let { results.putIfAbsent(it.url.normalizedKey(), it) }
            }
        }
        collectUrlsFromText(html(), baseUri().ifBlank { mainUrl }).forEach { href ->
            if (href.isContentUrl()) {
                val title = href.slugTitle()
                results.putIfAbsent(
                    href.normalizedKey(),
                    newAnimeSearchResponse(title, href, title.detectTvType(href))
                )
            }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResponseStrict(): SearchResponse? {
        val anchor = selectFirst("a[href*='/anime/'], a[href*='episode'], h2 a[href], h3 a[href], h4 a[href], .tt a[href], .limit a[href], a[href]") ?: return null
        return anchor.toSearchResponseFromAnchor(this)
    }

    private fun Element.toSearchResponseFromAnchor(container: Element = this): SearchResponse? {
        val href = attr("href").toAbsoluteUrl(mainUrl) ?: return null
        if (!href.isContentUrl()) return null
        val rawTitle = listOf(
            attr("title"),
            container.selectFirst(".tt, .title, .entry-title, .post-title, h2, h3, h4")?.text(),
            container.selectFirst("img[alt]")?.attr("alt"),
            text()
        ).firstOrNull { !it.isNullOrBlank() } ?: return null
        val title = rawTitle.cleanTitle().takeIf { it.isValidTitle() } ?: href.slugTitle()
        val poster = container.bestImage(href)
        val episode = listOf(container.selectFirst(".epx, .eggepisode, .episode, .num")?.text(), container.text(), href)
            .firstNotNullOfOrNull { it?.parseEpisodeNumber() }
        return newAnimeSearchResponse(title, href, title.detectTvType(href)) {
            posterUrl = poster
            posterHeaders = mapOf("Referer" to "$mainUrl/")
            episode?.let { addSub(it) }
        }
    }

    private fun Document.parseEpisodes(fallbackPoster: String?): List<Episode> {
        val selectors = listOf(
            ".eplister li a[href]", ".episodelist li a[href]", ".episode-list a[href]", ".episodes a[href]",
            ".epslist a[href]", ".epcheck a[href]", ".naveps a[href*='episode']", ".bxcl ul li a[href]",
            ".entry-content a[href*='episode']", "article a[href*='episode']", "a[href*='episode']"
        ).joinToString(",")
        return select(selectors).mapNotNull { anchor ->
            val href = anchor.attr("href").toAbsoluteUrl(mainUrl) ?: return@mapNotNull null
            if (!href.isEpisodeUrl()) return@mapNotNull null
            val label = listOf(
                anchor.selectFirst(".epl-title, .playinfo h3, h3, .title, .ep-title, .eptitle")?.text(),
                anchor.attr("title"),
                anchor.text(),
                href.slugTitle()
            ).firstOrNull { !it.isNullOrBlank() }!!.cleanTitle()
            val episodeNumber = listOf(anchor.selectFirst(".epl-num, .epx, .num, .ep-num")?.text(), label, href)
                .firstNotNullOfOrNull { it?.parseEpisodeNumber() }
            newEpisode(href) {
                name = label
                episode = episodeNumber
                posterUrl = anchor.selectFirst("img")?.imageUrl(href) ?: fallbackPoster
            }
        }
    }

    private fun String.parseEpisodeUrlsFromText(fallbackPoster: String?): List<Episode> {
        return collectUrlsFromText(this, mainUrl)
            .filter { it.isEpisodeUrl() }
            .distinctBy { it.normalizedKey() }
            .map { href ->
                val title = href.slugTitle()
                newEpisode(href) {
                    name = title
                    episode = href.parseEpisodeNumber()
                    posterUrl = fallbackPoster
                }
            }
    }

    private fun Document.collectSubtitles(pageUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val subtitles = linkedSetOf<String>()
        select("track[kind=subtitles][src], track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val url = element.attr("src").ifBlank { element.attr("href") }.toAbsoluteUrl(pageUrl) ?: return@forEach
            if (!subtitles.add(url.normalizedKey())) return@forEach
            val label = element.attr("label").ifBlank { element.text() }.cleanLabel().ifBlank { "Subtitle" }
            subtitleCallback.invoke(SubtitleFile(label, url))
        }
    }

    private fun Document.hasNextPage(currentPage: Int): Boolean {
        if (selectFirst("link[rel=next], .pagination a.next[href], a.next.page-numbers[href], .nav-links a.next[href], .hpage a.r[href]") != null) return true
        return select(".pagination a[href], .page-numbers a[href]").any { it.text().trim().toIntOrNull()?.let { page -> page > currentPage } == true }
    }

    private fun Document.bestTitle(): String? {
        return selectFirst("h1.entry-title, .entry-title h1, .post-title h1, h1[itemprop=name], h1")?.text()
            ?: selectFirst("meta[property=og:title]")?.attr("content")
            ?: title()
    }

    private fun Document.bestPoster(base: String): String? {
        return selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")?.toAbsoluteUrl(base)
            ?: selectFirst(".bigcontent .thumb img, .thumb img, .poster img, img.wp-post-image, .post-thumb img, .entry-content img")?.imageUrl(base)
    }

    private fun Document.bestPlot(): String? {
        return select(".entry-content p, .postbody .entry-content p, .synopsis p, .sinopsis p, .desc p, .description p")
            .map { it.text().cleanText() }
            .filter { text ->
                text.length > 30 &&
                    !text.contains("Download ", true) &&
                    !text.contains("Streaming ", true) &&
                    !text.contains("Nonton ", true)
            }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
            ?: selectFirst("meta[name=description]")?.attr("content")?.cleanText()
    }

    private fun Document.genreTags(): List<String> {
        return select(".genxed a[href*='/genres/'], .info-content a[href*='/genres/'], a[rel=tag], a[href*='/genres/'], a[href*='/genre/']")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun Document.parseYear(): Int? {
        val text = select(".spe, .info-content, .infox, .entry-content, body").text()
        return Regex("""\b(19\d{2}|20\d{2})\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun Document.detectStatus(): ShowStatus? {
        val statusText = select(".spe, .info-content, .infox, .entry-content").text().lowercase(Locale.ROOT)
        return when {
            statusText.contains("completed") || statusText.contains("selesai") || statusText.contains("tamat") -> ShowStatus.Completed
            statusText.contains("ongoing") || statusText.contains("airing") || statusText.contains("berjalan") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun Element.bestImage(base: String): String? = selectFirst("img")?.imageUrl(base)

    private fun Element.imageUrl(base: String): String? {
        val raw = attr("data-src").ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("src") }
        return raw.toAbsoluteUrl(base)
    }

    private fun collectUrlsFromText(text: String, base: String): List<String> {
        val normalized = text.decodeHtmlSource()
        val urls = linkedSetOf<String>()
        Regex("""<(?:iframe|embed|source|video|track)[^>]+(?:src|data-src)=['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized).forEach { urls.add(it.groupValues[1]) }
        Regex("""(?:src|file|url|source|embed|iframe|data-url|data-src|data-file|data-player|data-video|data-iframe|data-embed)\s*[:=]\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized).forEach { urls.add(it.groupValues[1]) }
        Regex("""atob\(['\"]([^'\"]+)['\"]\)""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { runCatching { base64Decode(it.groupValues[1]) }.getOrNull() }
            .forEach { decoded -> collectUrlsFromText(decoded, base).forEach { urls.add(it) } }
        Regex("""https?://[^'\"<>()\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized).map { it.value.trimEnd(',', ';', ')') }.forEach { urls.add(it) }
        Regex("""/(?:embed|iframe|player|stream|videos|episode|anime)/[^'\"<>()\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized).mapNotNull { it.value.toAbsoluteUrl(base) }.forEach { urls.add(it) }
        return urls.mapNotNull { it.decodeUrlText().toAbsoluteUrl(base) }
            .filter { it.isPlayableCandidate() || it.isContentUrl() }
            .distinctBy { it.normalizedKey() }
    }

    private fun String.toCandidateValues(referer: String): List<String> {
        val output = linkedSetOf<String>()
        fun addValue(value: String?) {
            val clean = value?.decodeUrlText()?.trim()?.takeIf { it.isNotBlank() } ?: return
            clean.toAbsoluteUrl(referer)?.let { output.add(it) }
            clean.decodeLoose()?.toAbsoluteUrl(referer)?.let { output.add(it) }
            runCatching { base64Decode(clean) }.getOrNull()?.let { decoded ->
                decoded.toAbsoluteUrl(referer)?.let { output.add(it) }
                collectUrlsFromText(decoded, referer).forEach { output.add(it) }
            }
            collectUrlsFromText(clean, referer).forEach { output.add(it) }
        }
        addValue(this)
        return output.toList()
    }

    private fun String.cleanTitle(): String = cleanText()
        .replace(Regex("""(?i)\s*[-–|]\s*Nanime.*$"""), "")
        .replace(Regex("""(?i)\s*Subtitle\s+Indonesia.*$"""), "")
        .replace(Regex("""(?i)\s*Sub\s+Indo.*$"""), "")
        .replace(Regex("""(?i)^Nonton\s+"""), "")
        .replace(Regex("""(?i)^Download\s+"""), "")
        .trim()

    private fun String.cleanLabel(): String = cleanText()
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.cleanText(): String = Jsoup.parse(this.decodeHtmlSource()).text()
        .replace("\u00a0", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.decodeUrlText(): String = decodeHtmlSource().trim().trim('"', '\'')

    private fun String.decodeHtmlSource(): String = replace("\\/", "/")
        .replace("\\u002F", "/", true)
        .replace("\\u003A", ":", true)
        .replace("\\u003D", "=", true)
        .replace("\\u003F", "?", true)
        .replace("\\u0026", "&", true)
        .replace("\\u0025", "%", true)
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#039;", "'")
        .replace("&#038;", "&")
        .replace("&amp;", "&")

    private fun String.decodeLoose(): String? = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrNull()

    private fun String.toAbsoluteUrl(base: String): String? {
        val clean = trim().trim('"', '\'')
        if (clean.isBlank() || clean.startsWith("javascript", true) || clean.startsWith("#")) return null
        return runCatching { URI(base).resolve(clean).toString() }.getOrNull()
    }

    private fun String.normalizedKey(): String = substringBefore("#").trimEnd('/').lowercase(Locale.ROOT)

    private fun String.pathLower(): String = runCatching { URI(this).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault(this.lowercase(Locale.ROOT))

    private fun String.sameHostAsMain(): Boolean = runCatching {
        val host = URI(this).host?.removePrefix("www.") ?: return@runCatching false
        val mainHost = URI(mainUrl).host?.removePrefix("www.") ?: return@runCatching false
        host == mainHost
    }.getOrDefault(false)

    private fun String.isContentUrl(): Boolean {
        val absolute = toAbsoluteUrl(mainUrl) ?: return false
        if (!absolute.sameHostAsMain()) return false
        val path = absolute.pathLower()
        if (path.isBlank() || path == "/") return false
        if (listOf("/wp-", "/category/", "/genres/", "/genre/", "/tag/", "/page/", "/search/", "/author/").any { path.contains(it) }) return false
        if (absolute.contains("?s=", true)) return false
        return path.any { it.isLetterOrDigit() }
    }

    private fun String.isEpisodeUrl(): Boolean {
        val absolute = toAbsoluteUrl(mainUrl) ?: return false
        if (!absolute.sameHostAsMain()) return false
        val path = absolute.pathLower()
        return path.contains("episode") || Regex("""[-/](?:ep|eps)[-/]?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(path)
    }

    private fun String.isDirectMedia(): Boolean {
        val value = lowercase(Locale.ROOT).substringBefore("?")
        return value.endsWith(".m3u8") || value.endsWith(".mp4") || value.endsWith(".webm") || value.endsWith(".mkv") || value.contains("/stream/")
    }

    private fun String.isPlayableCandidate(): Boolean {
        val value = lowercase(Locale.ROOT)
        if (isDirectMedia()) return true
        if (listOf("/embed", "/iframe", "/player", "/e/", "/v/", "?id=").any { value.contains(it) }) return true
        return listOf(
            "dailymotion", "ok.ru", "rumble", "youtube", "blogger", "googlevideo",
            "streamsb", "streamtape", "streamwish", "streamhide", "streamruby", "streamlare", "streamhub", "streamvid",
            "vidhide", "vidguard", "vidmoly", "vidcloud", "vidoza", "filemoon", "filelions", "dood", "mp4upload",
            "uqload", "mixdrop", "voe.sx", "short.ink", "sendvid", "luluvdo", "yourupload", "embed", "iframe", "player"
        ).any { value.contains(it) }
    }

    private fun String.isNoiseUrl(): Boolean {
        val value = lowercase(Locale.ROOT)
        return value.contains("/wp-content/") || value.contains("/wp-json/") || value.contains("/xmlrpc.php") ||
            value.endsWith(".jpg") || value.endsWith(".jpeg") || value.endsWith(".png") || value.endsWith(".webp") || value.endsWith(".gif") ||
            value.endsWith(".css") || value.endsWith(".js")
    }

    private fun String.shouldInspectInline(): Boolean {
        val value = lowercase(Locale.ROOT)
        return !isDirectMedia() && (value.contains("embed") || value.contains("iframe") || value.contains("player") || value.contains("/e/") || value.contains("/v/"))
    }

    private fun String.isValidTitle(): Boolean {
        val value = trim()
        if (value.length < 2) return false
        return !listOf("home", "login", "search", "genre", "genres", "anime", "download", "streaming").any { value.equals(it, true) }
    }

    private fun String.detectTvType(url: String): TvType {
        val value = "$this $url".lowercase(Locale.ROOT)
        return when {
            value.contains("movie") -> TvType.AnimeMovie
            value.contains("ova") || value.contains("special") -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun String.parseEpisodeNumber(): Int? = Regex("""(?i)(?:episode|eps?|ep)[\s\-_]*(\d{1,4})""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("""\b(\d{1,4})\b""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String.slugTitle(): String {
        val path = runCatching { URI(this).path.orEmpty().trim('/') }.getOrDefault(this)
        return path.substringAfterLast('/').ifBlank { path }
            .replace(Regex("""(?i)(episode|eps?|ep)-?\d+"""), "")
            .replace('-', ' ')
            .replace('_', ' ')
            .cleanText()
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }
            .ifBlank { name }
    }

    private fun String.hostLabel(): String = runCatching { URI(this).host?.removePrefix("www.") ?: this }.getOrDefault(this)

    private fun String.qualityLabelFromUrl(): String = Regex("""(?:^|[^0-9])(\d{3,4})p?(?:[^0-9]|$)""").find(this)?.groupValues?.getOrNull(1)?.let { "${it}p" }.orEmpty()

    private fun String.parseQuality(): Int? = Regex("""(?:^|[^0-9])(\d{3,4})p?(?:[^0-9]|$)""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun mediaReferer(url: String, fallback: String): String {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return if (host.contains("googlevideo", true)) fallback else fallback
    }

    private fun mediaHeaders(url: String, referer: String): Map<String, String> {
        val origin = runCatching {
            val uri = URI(referer)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(mainUrl)
        return browserHeaders + mapOf(
            "Referer" to referer,
            "Origin" to origin,
            "Accept" to "*/*"
        )
    }

    private data class PlayerCandidate(
        val url: String,
        val label: String?,
        val referer: String
    )
}
