package com.sad25kag.donghuafilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class DonghuaFilm : MainAPI() {
    override var mainUrl = "https://donghuafilm.com"
    override var name = "DonghuaFilm"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.6",
        "Referer" to "$mainUrl/",
    )

    private val minimumHomePageCards = 5

    override val mainPage = mainPageOf(
        "" to "Latest Release",
        "anime/?order=update&status=&type=" to "New Donghua",
        "anime/?order=update&status=ongoing&type=" to "Donghua Ongoing",
        "anime/?order=update&status=completed&type=" to "Completed",
        "anime/?order=popular&status=&type=" to "Popular",
        "anime/?order=update&status=&type=movie" to "Movie",
        "genres/action/" to "Action",
        "genres/adventure/" to "Adventure",
        "genres/comedian/" to "Comedian",
        "genres/comedy/" to "Comedy",
        "genres/demon/" to "Demon",
        "genres/donghua/" to "Donghua",
        "genres/drama/" to "Drama",
        "genres/fanstasy/" to "Fanstasy",
        "genres/fantasy/" to "Fantasy",
        "genres/historical/" to "Historical",
        "genres/isekai/" to "Isekai",
        "genres/martial-arts/" to "Martial Arts",
        "genres/movie/" to "Movie Genre",
        "genres/mystery/" to "Mystery",
        "genres/reincarnation/" to "Reincarnation",
        "genres/romance/" to "Romance",
        "genres/school/" to "School",
        "genres/sci-fi/" to "Sci-Fi",
        "genres/super-power/" to "Super Power",
        "genres/supranatural/" to "Supranatural",
        "genres/xuanhuan/" to "Xuanhuan",
        "az-list/" to "AZ List",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document
        val parsed = parseCards(document, request.data)
            .distinctBy { it.url.normalizedKey() }
            .filter { !it.posterUrl.isNullOrBlank() }
        val filteredResults = if (request.name.equals("Movie", true) || request.name.equals("Movie Genre", true)) {
            filterMovieCards(parsed)
        } else {
            parsed
        }
        val results = if (page <= 1 && filteredResults.size < minimumHomePageCards) {
            emptyList()
        } else {
            filteredResults
        }
        val hasNext = results.isNotEmpty() && document.selectFirst("a.next, .pagination a.next, a.next.page-numbers, link[rel=next], a[href*='/page/${page + 1}/'], a[href*='page=${page + 1}']") != null
        return newHomePageResponse(HomePageList(request.name, results, isHorizontalImages = false), hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val routes = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/anime/?s=$encoded",
            "$mainUrl/anime/?order=update&status=&type=&keyword=$encoded",
        )

        return routes.flatMap { route ->
            runCatching {
                app.get(route, headers = browserHeaders, referer = "$mainUrl/").document.let(::parseCards)
            }.getOrDefault(emptyList())
        }
            .filter { response -> response.name.contains(query, true) || response.url.contains(query.replace(" ", "-"), true) }
            .distinctBy { it.url.normalizedKey() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1[itemprop=name], .infox h1, .entry-title")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ) ?: throw ErrorLoadingException("Judul DonghuaFilm tidak ditemukan")

        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")?.toAbsoluteUrl(url)
            ?: document.selectFirst(".thumb img, .bigcontent img, .ime img, .infox img, img.wp-post-image")?.imageUrl(url)

        val plot = document.selectFirst(".entry-content, .synopsis, .desc, .mindesc, .bixbox.synp")
            ?.text()
            ?.cleanText()
            ?.takeIf { it.length > 20 }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.cleanText()

        val detailRoot = document.detailRoot()
        val tags = detailRoot.select("a[href*='/genres/'], a[rel=tag]")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() }
            .distinct()

        val infoText = listOf(
            detailRoot.text().cleanText(),
            document.selectFirst(".spe, .info-content, .infotable, .bigcontent")?.text()?.cleanText().orEmpty(),
        ).joinToString(" ").cleanText()
        val episodes = parseEpisodes(document, url).distinctBy { it.data.normalizedKey() }
        val year = Regex("""(?i)(?:Released|Rilis|Aired)\s*:?\s*([12][0-9]{3})""").find(infoText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val status = detectStatus(infoText)
        val type = detectDetailType(title, infoText, tags, episodes)

        val recommendations = parseCards(document)
            .filterNot { it.url.normalizedKey() == url.normalizedKey() }
            .take(16)

        return if (type == TvType.Anime && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.showStatus = status
                this.recommendations = recommendations
            }
        } else {
            val data = episodes.firstOrNull()?.data ?: url
            newMovieLoadResponse(title, url, type, data) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val response = app.get(data, headers = browserHeaders, referer = "$mainUrl/")
        val document = response.document
        val emitted = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, label: String? = null, referer: String = data): Boolean {
            val videoUrl = rawUrl
                ?.decodeEmbedText()
                ?.toAbsoluteUrl(referer)
                ?.takeIf { it.isDirectMediaLike() }
                ?: return false
            if (!emitted.add(videoUrl.substringBefore("#"))) return true
            val linkName = listOfNotNull(name, label?.cleanText()?.takeIf { it.isNotBlank() }).joinToString(" - ")
            callback.invoke(
                newExtractorLink(linkName, linkName, videoUrl, inferType(videoUrl)) {
                    this.referer = referer
                    this.quality = getQualityFromName(label ?: videoUrl)
                    this.headers = browserHeaders + mapOf(
                        "Referer" to referer,
                        "Origin" to originOf(referer),
                        "Range" to "bytes=0-",
                    )
                },
            )
            return true
        }

        val candidates = collectPlayerCandidates(document, response.text, data)
        for (candidate in candidates.take(50)) {
            val playerUrl = candidate.decodeEmbedText().toAbsoluteUrl(data) ?: continue
            if (emitDirect(playerUrl, hostLabel(playerUrl), data)) continue

            val before = emitted.size
            val countedCallback: (ExtractorLink) -> Unit = { link ->
                emitted.add(link.url.substringBefore("#"))
                callback.invoke(link)
            }
            runCatching { loadExtractor(playerUrl, data, subtitleCallback, countedCallback) }
            if (emitted.size > before) continue

            val playerHtml = runCatching {
                app.get(playerUrl, headers = browserHeaders + mapOf("Referer" to data), referer = data).text
            }.getOrNull() ?: continue
            val unpacked = runCatching { getAndUnpack(playerHtml) }.getOrNull().orEmpty()
            val nested = collectUrlsFromText(playerHtml + "\n" + unpacked, playerUrl)
            for (nestedUrl in nested.take(20)) {
                if (emitDirect(nestedUrl, hostLabel(playerUrl), playerUrl)) continue
                nestedUrl.toAbsoluteUrl(playerUrl)?.let { fixed ->
                    runCatching { loadExtractor(fixed, playerUrl, subtitleCallback, countedCallback) }
                }
            }
        }

        return emitted.isNotEmpty()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim().trimStart('/')
        if (cleanPath.isBlank()) return if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val base = if (cleanPath.startsWith("http", true)) cleanPath.trimEnd('/') else "$mainUrl/$cleanPath".trimEnd('/')
        if (page <= 1) return if (base.contains("?")) base else "$base/"
        return when {
            base.contains("page=") -> base.replace(Regex("""page=\d+"""), "page=$page")
            base.contains("?") -> "$base&page=$page"
            else -> "$base/page/$page/"
        }
    }

    private fun parseCards(document: Document, pagePath: String = ""): List<SearchResponse> {
        val working = document.clone()
        if (pagePath.isNotBlank()) {
            working.select("aside, #sidebar, .sidebar, .side, .widget, .wpop, .serieslist.pop, .ongoingseries, .history, .comment, .comments").remove()
        }
        val selectors = listOf(
            ".listupd article, .listupd .bsx, .listupd .bs, .listupd .utao",
            "article.bs, .bs .bsx, .bsx, .utao",
            ".result .bsx, .search-page article",
        )
        return selectors.asSequence()
            .flatMap { selector -> working.select(selector).asSequence() }
            .filterNot { it.isSidebarOrWidgetCard() }
            .mapNotNull { it.toDonghuaFilmCard() }
            .distinctBy { it.url.normalizedKey() }
            .toList()
    }

    private suspend fun filterMovieCards(cards: List<SearchResponse>): List<SearchResponse> {
        val movies = mutableListOf<SearchResponse>()
        for (card in cards.take(32)) {
            val isMovie = runCatching {
                val document = app.get(card.url, headers = browserHeaders, referer = "$mainUrl/").document
                val title = cleanTitle(
                    document.selectFirst("h1.entry-title, h1[itemprop=name], .infox h1, .entry-title")?.text()
                        ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                        ?: document.title()
                ) ?: card.name
                val detailRoot = document.detailRoot()
                val tags = detailRoot.select("a[href*='/genres/'], a[rel=tag]")
                    .map { it.text().cleanText() }
                    .filter { it.isNotBlank() }
                    .distinct()
                val infoText = listOf(
                    detailRoot.text().cleanText(),
                    document.selectFirst(".spe, .info-content, .infotable, .bigcontent")?.text()?.cleanText().orEmpty(),
                ).joinToString(" ").cleanText()
                val episodes = parseEpisodes(document, card.url).distinctBy { it.data.normalizedKey() }
                detectDetailType(title, infoText, tags, episodes) == TvType.AnimeMovie
            }.getOrDefault(false)
            if (isMovie) movies.add(card)
        }
        return movies
    }

    private fun Element.isSidebarOrWidgetCard(): Boolean {
        return parents().any { parent ->
            val id = parent.id().lowercase(Locale.ROOT)
            val classes = parent.classNames().joinToString(" ").lowercase(Locale.ROOT)
            id.contains("sidebar") || id == "comments" || classes.contains("sidebar") || classes.contains("widget") ||
                classes.contains("serieslist") || classes.contains("ongoingseries") || classes.contains("wpop") || classes.contains("comment")
        }
    }

    private fun Element.toDonghuaFilmCard(): SearchResponse? {
        val anchor = selectFirst(".bsx a[href], a.series[href], h2 a[href], h3 a[href], h4 a[href], a[href*='/anime/'], a[href*='episode'], a[href]") ?: return null
        val href = anchor.attr("href").toAbsoluteUrl(mainUrl) ?: return null
        if (!href.contains(mainUrl, true)) return null
        if (!href.contains("/anime/", true) && !href.contains("episode", true)) return null

        val rawTitle = anchor.attr("title").cleanText().takeIf { it.length > 2 }
            ?: selectFirst(".tt, .eggtitle, h2, h3, h4, .limit, .epl-title")?.text()?.cleanText()?.takeIf { it.length > 2 }
            ?: selectFirst("img")?.attr("alt")?.cleanText()?.takeIf { it.length > 2 }
            ?: anchor.text().cleanText().takeIf { it.length > 2 }
            ?: return null

        val title = cleanTitle(rawTitle) ?: rawTitle
        val poster = selectFirst("img, source[srcset], noscript")?.imageUrl(href)
            ?: anchor.selectFirst("img, source[srcset], noscript")?.imageUrl(href)
            ?: backgroundImageUrl(href)
        val tvType = when {
            href.contains("episode", true) -> TvType.Anime
            title.contains("movie", true) || href.contains("movie", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }
        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            this.posterHeaders = browserHeaders + mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodes(document: Document, detailUrl: String): List<Episode> {
        val scopedAnchors = document.select(
            ".eplister li a[href], .episodelist li a[href], .bixbox.bxcl li a[href], .epcheck li a[href], .episode-list li a[href], .les-content li a[href]"
        )
        val anchors = scopedAnchors.takeIf { it.isNotEmpty() }
            ?: document.select("a[href*='episode']").filter { anchor ->
                val href = anchor.attr("href").toAbsoluteUrl(mainUrl).orEmpty()
                href.belongsToDetailSlug(detailUrl)
            }

        return anchors.mapNotNull { anchor ->
            val href = anchor.attr("href").toAbsoluteUrl(mainUrl) ?: return@mapNotNull null
            if (!href.contains("episode", true)) return@mapNotNull null
            val title = anchor.selectFirst(".epl-title, .playinfo h3, h3, .title, span")?.text()?.cleanText()
                ?: anchor.text().cleanText().takeIf { it.isNotBlank() }
                ?: href.substringAfter(mainUrl).trim('/').replace('-', ' ')
            val episode = anchor.selectFirst(".epl-num, .epx, .num")?.text()?.toEpisodeNumber()
                ?: title.toEpisodeNumber()
                ?: href.toEpisodeNumber()
            newEpisode(href) {
                this.name = cleanTitle(title) ?: title
                this.episode = episode
                this.posterUrl = anchor.selectFirst("img")?.imageUrl(href)
            }
        }.distinctBy { it.data.normalizedKey() }
    }

    private suspend fun collectPlayerCandidates(document: Document, html: String, referer: String): LinkedHashSet<String> {
        val candidates = linkedSetOf<String>()

        document.select("iframe[src], embed[src], video[src], source[src]").forEach { node ->
            node.attr("src").takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        }

        document.select("select option[value], .mirror option[value], .mobius option[value]").forEach { option ->
            val value = option.attr("value").trim()
            if (value.isBlank()) return@forEach
            candidates.add(value)
            decodeBase64Value(value)?.let { decoded -> collectUrlsFromText(decoded, referer).forEach { candidates.add(it) } }
        }

        val dataAttrs = listOf("data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file", "data-stream", "data-content")
        dataAttrs.forEach { attr ->
            document.select("[$attr]").forEach { node ->
                val value = node.attr(attr).trim()
                if (value.isNotBlank()) {
                    candidates.add(value)
                    decodeBase64Value(value)?.let { decoded -> collectUrlsFromText(decoded, referer).forEach { candidates.add(it) } }
                }
            }
        }

        collectAjaxPlayerCandidates(document, referer).forEach { candidates.add(it) }
        collectUrlsFromText(html, referer).forEach { candidates.add(it) }
        return candidates
    }

    private suspend fun collectAjaxPlayerCandidates(document: Document, referer: String): List<String> {
        val results = linkedSetOf<String>()
        val actionUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val nodes = document.select("[data-post][data-nume], [data-id][data-nume], [data-post][data-server], [data-episode][data-server]")
        val actions = listOf("doo_player_ajax", "player_ajax", "ts_player_ajax", "donghuafilm_player_ajax")

        for (node in nodes.take(12)) {
            val post = node.attr("data-post").ifBlank { node.attr("data-id") }.ifBlank { node.attr("data-episode") }
            val nume = node.attr("data-nume").ifBlank { node.attr("data-server") }.ifBlank { "1" }
            val type = node.attr("data-type").ifBlank { "tv" }
            if (post.isBlank()) continue

            for (action in actions) {
                val text = runCatching {
                    app.post(
                        actionUrl,
                        data = mapOf("action" to action, "post" to post, "nume" to nume, "type" to type),
                        headers = browserHeaders + mapOf(
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to mainUrl,
                            "Referer" to referer,
                        ),
                        referer = referer,
                    ).text
                }.getOrNull().orEmpty()
                if (text.isNotBlank()) collectUrlsFromText(text, referer).forEach { results.add(it) }
            }
        }
        return results.toList()
    }

    private fun collectUrlsFromText(text: String, base: String): List<String> {
        val normalized = text.decodeEmbedText()
        val urls = linkedSetOf<String>()
        Regex("""<(?:iframe|embed|source|video)[^>]+(?:src|data-src)=['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { urls.add(it.groupValues[1]) }
        Regex("""(?:src|file|url|source|embed|iframe|data-url|data-src|data-file|data-player|link)\s*[:=]\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { urls.add(it.groupValues[1]) }
        Regex("""https?://[^'\"<>()\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map { it.value.trimEnd(',', ';', ')') }
            .forEach { urls.add(it) }
        return urls.filter { it.isPotentialPlayer() }
    }

    private fun decodeBase64Value(value: String): String? {
        val clean = value.trim().trim('"', '\'')
        if (clean.length < 12) return null
        return runCatching { base64Decode(clean) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun Element.backgroundImageUrl(base: String = mainUrl): String? {
        val ownStyle = attr("style").extractCssImageUrl()
        if (!ownStyle.isNullOrBlank()) return ownStyle.toAbsoluteUrl(base)
        return select("[style*='url']").asSequence()
            .mapNotNull { it.attr("style").extractCssImageUrl() }
            .firstOrNull()
            ?.toAbsoluteUrl(base)
    }

    private fun String.takeValidImageValue(): String? {
        val value = trim().trim('"', '\'')
        if (value.isBlank()) return null
        if (value.startsWith("data:", true) || value.startsWith("#") || value.startsWith("javascript", true)) return null
        return value
    }

    private fun String.bestSrcFromSet(): String? {
        return split(',')
            .map { it.trim().substringBefore(" ").takeValidImageValue() }
            .lastOrNull { !it.isNullOrBlank() }
    }

    private fun String.extractCssImageUrl(): String? {
        return Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(2)
            ?.takeValidImageValue()
    }

    private fun Element.imageUrl(base: String = mainUrl): String? {
        val raw = listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-thumb", "data-poster", "src", "poster")
            .firstNotNullOfOrNull { attr -> attr(attr).takeValidImageValue() }
            ?: listOf("data-srcset", "data-lazy-srcset", "srcset")
                .firstNotNullOfOrNull { attr -> attr(attr).bestSrcFromSet() }
            ?: selectFirst("source[srcset]")?.attr("srcset")?.bestSrcFromSet()
            ?: selectFirst("noscript")?.let { node -> Jsoup.parse(node.html()).selectFirst("img")?.imageUrl(base) }
            ?: backgroundImageUrl(base)
        return raw?.toAbsoluteUrl(base)
    }

    private fun Document.detailRoot(): Element {
        return selectFirst(".bigcontent, .infox, .info-content, .infotable, .spe, article") ?: body()
    }

    private fun detectDetailType(title: String, infoText: String, tags: List<String>, episodes: List<Episode>): TvType {
        if (episodes.size > 1) return TvType.Anime
        val isOva = Regex("""(?i)\b(Type|Tipe|Jenis)\s*:?\s*OVA\b""").containsMatchIn(infoText) || tags.any { it.equals("OVA", true) }
        if (isOva) return TvType.OVA
        val explicitMovie = Regex("""(?i)\b(Type|Tipe|Jenis)\s*:?\s*Movie\b""").containsMatchIn(infoText)
            || title.contains("Movie", true)
            || (episodes.isEmpty() && tags.any { it.equals("Movie", true) })
        return if (explicitMovie) TvType.AnimeMovie else TvType.Anime
    }

    private fun String.belongsToDetailSlug(detailUrl: String): Boolean {
        val hrefSlug = seriesSlugFromUrl(this)
        val detailSlug = seriesSlugFromUrl(detailUrl)
        if (hrefSlug.isBlank() || detailSlug.isBlank()) return true
        return hrefSlug.contains(detailSlug) || detailSlug.contains(hrefSlug.substringBeforeLast("-episode"))
    }

    private fun seriesSlugFromUrl(url: String): String {
        val path = runCatching { URI(url).path }.getOrDefault(url)
            .trim('/')
            .lowercase(Locale.ROOT)
        val slug = path
            .substringAfter("anime/", path)
            .substringBefore("/")
        return slug
            .substringBeforeLast("-episode", slug)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun cleanTitle(raw: String?): String? {
        return raw?.htmlUnescape()?.cleanText()
            ?.replace(Regex("""(?i)\s*[-–|]\s*DonghuaFilm.*$"""), "")
            ?.replace(Regex("""(?i)^Donghua\s+"""), "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun detectStatus(infoText: String): ShowStatus? {
        val value = infoText.lowercase(Locale.ROOT)
        return when {
            value.contains("completed") || value.contains("selesai") -> ShowStatus.Completed
            value.contains("ongoing") || value.contains("airing") || value.contains("tayang") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun String.cleanText(): String = htmlUnescape()
        .replace("\u00a0", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.htmlUnescape(): String = Jsoup.parse(this).text()

    private fun String.decodeEmbedText(): String = htmlUnescape()
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("&quot;", "\"")
        .replace("&#038;", "&")
        .replace("&amp;", "&")
        .trim()

    private fun String.toAbsoluteUrl(base: String = mainUrl): String? {
        val clean = trim().trim('"', '\'')
        if (clean.isBlank() || clean.startsWith("javascript", true) || clean.startsWith("#")) return null
        return runCatching { URI(base).resolve(clean).toString() }.getOrNull()
    }

    private fun String.normalizedKey(): String = substringBefore("#").trimEnd('/').lowercase(Locale.ROOT)

    private fun String.toEpisodeNumber(): Int? = Regex("""(?i)(?:episode|eps?|ep)\s*\.?\s*(\d{1,4})""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("""\b(\d{1,4})\b""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String.isDirectMediaLike(): Boolean {
        val value = lowercase(Locale.ROOT)
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".webm") || value.contains(".mkv") || value.contains("/stream/") || value.contains("videoplayback")
    }

    private fun String.isPotentialPlayer(): Boolean {
        val value = lowercase(Locale.ROOT)
        if (isDirectMediaLike()) return true
        return listOf(
            "iframe", "embed", "player", "stream", "maodrive", "desustream", "ondesuhd", "vidhide", "filedon", "filemoon", "streamtape", "dood", "mp4upload", "blogger", "googlevideo", "sendvid", "rumble", "dailymotion", "youtube"
        ).any { value.contains(it) }
    }

    private fun inferType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun hostLabel(url: String): String = runCatching { URI(url).host ?: url }.getOrDefault(url)
}
