package com.sad25kag.donghuafilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
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

    // Known direct-file / player domains accepted on episode pages
    private val knownPlayerHosts = listOf(
        "pixeldrain.com", "ok.ru", "dailymotion.com", "drive.google.com",
        "rumble.com", "filemoon.", "streamtape.", "dood.", "vidhide.",
        "vidguard.", "voe.", "mixdrop.", "mp4upload.", "sendvid.",
        "blogger.com", "googlevideo.com", "mega.nz", "sbembed.",
        "streamwish.", "wishfast.", "filelions.", "abysscdn.com",
        "racaty.", "rubystream.", "streamruby.", "lulustream.",
    )

    override val mainPage = mainPageOf(
        "anime/?order=update&status=&type=" to "New Donghua",
        "anime/?order=update&status=completed&type=" to "Completed",
        "anime/?order=popular&status=&type=" to "Popular",
        "genres/action/" to "Action",
        "genres/adventure/" to "Adventure",
        "genres/fanstasy/" to "Fantasy",
        "genres/historical/" to "Historical",
        "genres/martial-arts/" to "Martial Arts",
        "genres/xuanhuan/" to "Xuanhuan",
        "genres/romance/" to "Romance",
        "genres/sci-fi/" to "Sci-Fi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document
        val parsed = parseCards(document, request.data)
            .distinctBy { it.url.normalizedKey() }
        val hasNext = parsed.isNotEmpty() && document.selectFirst(
            "a.next, .pagination a.next, a.next.page-numbers, link[rel=next], "
                    + "a[href*='/page/${page + 1}/'], a[href*='page=${page + 1}']"
        ) != null
        return newHomePageResponse(HomePageList(request.name, parsed, isHorizontalImages = false), hasNext = hasNext)
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
                app.get(route, headers = browserHeaders, referer = "$mainUrl/").document.let { parseCards(it) }
            }.getOrDefault(emptyList())
        }
            .filter { it.name.contains(query, true) || it.url.contains(query.replace(" ", "-"), true) }
            .distinctBy { it.url.normalizedKey() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1[itemprop=name], .infox h1, .entry-title, h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ) ?: throw ErrorLoadingException("Judul DonghuaFilm tidak ditemukan")

        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")?.toAbsoluteUrl(url)
            ?: document.selectFirst(".thumb img, .bigcontent img, .ime img, .infox img, img.wp-post-image")
                ?.imageUrl(url)

        val plot = document.selectFirst(".entry-content p, .synopsis p, .desc p, .mindesc")
            ?.text()?.cleanText()?.takeIf { it.length > 20 }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.cleanText()

        val detailRoot = document.detailRoot()
        val tags = detailRoot.select("a[href*='/genres/'], a[rel=tag]")
            .map { it.text().cleanText() }.filter { it.isNotBlank() }.distinct()

        val infoText = listOf(
            detailRoot.text().cleanText(),
            document.selectFirst(".spe, .info-content, .infotable, .bigcontent")?.text()?.cleanText().orEmpty(),
        ).joinToString(" ").cleanText()

        val episodes = parseEpisodes(document, url).distinctBy { it.data.normalizedKey() }
        val year = Regex("""(?i)(?:Released|Rilis|Aired)\s*:?\s*([12][0-9]{3})""").find(infoText)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
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

        // ── STEP 1: Plain-link extraction ────────────────────────────────────────
        // donghuafilm episode posts store the player URL as a plain hyperlink
        // (or bare text) inside .entry-content.  Primary example: pixeldrain.
        val contentLinks = document.select(
            ".entry-content a[href], .the__content a[href], article .content a[href]"
        ).mapNotNull { it.attr("href").toAbsoluteUrl(data) }
            .filter { url -> knownPlayerHosts.any { host -> url.contains(host, ignoreCase = true) } }

        for (playerUrl in contentLinks) {
            val key = playerUrl.substringBefore("#")
            if (!emitted.add(key)) continue
            // Try direct emit first (for file CDN links)
            if (playerUrl.isDirectMediaLike()) {
                emitDirect(playerUrl, hostLabel(playerUrl), data, callback)
                continue
            }
            runCatching {
                loadExtractor(playerUrl, data, subtitleCallback) { link ->
                    emitted.add(link.url.substringBefore("#"))
                    callback.invoke(link)
                }
            }
        }

        // ── STEP 2: iframe / embed / data-attr / option[value] ───────────────────
        val candidates = collectPlayerCandidates(document, response.text, data)
        for (candidate in candidates.take(50)) {
            val playerUrl = candidate.decodeEmbedText().toAbsoluteUrl(data)
                ?.normalizeDailymotionUrl() ?: continue
            val key = playerUrl.substringBefore("#")
            if (!emitted.add(key)) continue

            if (playerUrl.isDirectMediaLike()) {
                emitDirect(playerUrl, hostLabel(playerUrl), data, callback)
                continue
            }

            val before = emitted.size
            runCatching {
                loadExtractor(playerUrl, data, subtitleCallback) { link ->
                    emitted.add(link.url.substringBefore("#"))
                    callback.invoke(link)
                }
            }
            if (emitted.size > before) continue

            // Try fetching the player page for nested URLs
            val playerHtml = runCatching {
                app.get(playerUrl, headers = browserHeaders + mapOf("Referer" to data), referer = data).text
            }.getOrNull() ?: continue
            val unpacked = runCatching { getAndUnpack(playerHtml) }.getOrNull().orEmpty()
            for (nestedUrl in collectUrlsFromText(playerHtml + "\n" + unpacked, playerUrl).take(20)) {
                val fixed = nestedUrl.toAbsoluteUrl(playerUrl)?.normalizeDailymotionUrl() ?: continue
                val nestedKey = fixed.substringBefore("#")
                if (!emitted.add(nestedKey)) continue
                if (fixed.isDirectMediaLike()) {
                    emitDirect(fixed, hostLabel(playerUrl), playerUrl, callback)
                    continue
                }
                runCatching {
                    loadExtractor(fixed, playerUrl, subtitleCallback) { link ->
                        emitted.add(link.url.substringBefore("#"))
                        callback.invoke(link)
                    }
                }
            }
        }

        return emitted.isNotEmpty()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun emitDirect(
        videoUrl: String,
        label: String?,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val linkName = listOfNotNull(name, label?.cleanText()?.takeIf { it.isNotBlank() }).joinToString(" - ")
        callback.invoke(
            newExtractorLink(linkName, linkName, videoUrl, inferType(videoUrl)) {
                this.referer = referer
                this.quality = getQualityFromName(label ?: videoUrl)
                this.headers = browserHeaders + mapOf(
                    "Referer" to referer,
                    "Origin" to originOf(referer),
                )
            }
        )
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim().trimStart('/')
        if (cleanPath.isBlank()) return if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val base = if (cleanPath.startsWith("http", true)) cleanPath.trimEnd('/')
        else "$mainUrl/$cleanPath".trimEnd('/')
        if (page <= 1) return if (base.contains("?")) base else "$base/"
        return when {
            base.contains("page=") -> base.replace(Regex("""page=\d+"""), "page=$page")
            base.contains("?") -> "$base&page=$page"
            else -> "$base/page/$page/"
        }
    }

    private fun parseCards(document: Document, pagePath: String = ""): List<SearchResponse> {
        val working = document.clone()
        // Remove sidebar/widget noise
        working.select(
            "aside, #sidebar, .sidebar, .side, .widget, .wpop, .serieslist.pop, "
                    + ".ongoingseries, .history, .comment, .comments, #comments"
        ).remove()

        val results = mutableListOf<SearchResponse>()

        // ── Priority 1: series cards with /anime/ URL (sidebar "New Donghua" etc.) ──
        working.select("a[href*='/anime/']").forEach { a ->
            val card = a.toSeriesCard() ?: return@forEach
            results.add(card)
        }

        // ── Priority 2: episode post links (homepage "Latest Release", "Popular Today") ──
        // These are links to individual episode posts; extract the series from them
        working.select(
            "a[href*='-episode-'][href*='subtitle'], a[href*='-episode-'][href*='subtitle-indonesia']"
        ).forEach { a ->
            val href = a.attr("href").toAbsoluteUrl(mainUrl) ?: return@forEach
            if (!href.startsWith(mainUrl)) return@forEach
            // Derive canonical series URL from episode slug
            val seriesUrl = deriveSeriesUrl(href) ?: return@forEach
            val rawTitle = a.attr("title").cleanText().takeIf { it.length > 2 }
                ?: a.selectFirst("img")?.attr("alt")?.cleanText()?.takeIf { it.length > 2 }
                ?: a.text().cleanText().takeIf { it.length > 2 }
                ?: return@forEach
            val title = cleanTitle(rawTitle.substringBeforeLast(" Episode ").trim()) ?: return@forEach
            if (isBlockedTitle(title)) return@forEach
            val poster = a.selectFirst("img")?.imageUrl(href)
            results.add(
                newAnimeSearchResponse(title, seriesUrl, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = browserHeaders + mapOf("Referer" to "$mainUrl/")
                }
            )
        }

        return results
            .filterNot { it.name.isBlank() || isBlockedTitle(it.name) }
            .distinctBy { it.url.normalizedKey() }
    }

    private fun Element.toSeriesCard(): SearchResponse? {
        val href = attr("href").toAbsoluteUrl(mainUrl) ?: return null
        if (!href.contains("/anime/", true) || !href.startsWith(mainUrl)) return null
        // Skip pagination / filter / genre links
        val path = href.removePrefix(mainUrl).trim('/')
        if (path.startsWith("anime/?order") || path.startsWith("genres/") || path.startsWith("season/")
            || path.startsWith("studio/") || path.startsWith("network/") || path.startsWith("country/")
        ) return null
        val rawTitle = attr("title").cleanText().takeIf { it.length > 2 }
            ?: selectFirst("h3, h4, .tt, .eggtitle, .title")?.text()?.cleanText()?.takeIf { it.length > 2 }
            ?: selectFirst("img")?.attr("alt")?.cleanText()?.takeIf { it.length > 2 }
            ?: text().cleanText().takeIf { it.length > 2 }
            ?: return null
        val title = cleanTitle(rawTitle) ?: return null
        if (isBlockedTitle(title)) return null
        val poster = selectFirst("img")?.imageUrl(href)
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.posterHeaders = browserHeaders + mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodes(document: Document, detailUrl: String): List<Episode> {
        // donghuafilm episode list on /anime/xxx/ page:
        // Episodes are <li><a href="https://donghuafilm.com/series-name-episode-N-subtitle-indonesia/">...
        // They appear in an ordered/unordered list inside a section, NOT inside .eplister
        val detailSlug = seriesSlugFromUrl(detailUrl)

        // Collect all anchors that look like episode posts for this series
        val anchors = document.select(
            "article li a[href], section li a[href], .eplister li a[href], "
                    + ".episodelist li a[href], .bixbox li a[href], .episode-list li a[href]"
        ).ifEmpty {
            document.select("li a[href]")
        }.filter { anchor ->
            val href = anchor.attr("href")
            href.contains("subtitle", true) || href.contains("episode", true)
        }.filter { anchor ->
            val href = anchor.attr("href").toAbsoluteUrl(mainUrl).orEmpty()
            href.startsWith(mainUrl) && href.belongsToDetailSlug(detailSlug)
        }

        return anchors.mapNotNull { anchor ->
            val href = anchor.attr("href").toAbsoluteUrl(mainUrl) ?: return@mapNotNull null
            val labelText = anchor.selectFirst(".epl-title, .playinfo h3, h3, span")?.text()?.cleanText()
                ?: anchor.text().cleanText().takeIf { it.isNotBlank() }
                ?: href.substringAfterLast("/").replace("-", " ").trim()
            val episode = anchor.selectFirst(".epl-num, .epx, .num")?.text()?.toEpisodeNumber()
                ?: labelText.toEpisodeNumber()
                ?: href.toEpisodeNumber()
            newEpisode(href) {
                this.name = cleanTitle(labelText) ?: labelText
                this.episode = episode
                this.posterUrl = anchor.selectFirst("img")?.imageUrl(href)
            }
        }.distinctBy { it.data.normalizedKey() }
    }

    private suspend fun collectPlayerCandidates(
        document: Document,
        html: String,
        referer: String,
    ): LinkedHashSet<String> {
        val candidates = linkedSetOf<String>()

        fun addCandidate(raw: String?, base: String = referer) {
            raw?.decodeEmbedText()
                ?.toAbsoluteUrl(base)
                ?.takeIf { !it.isNoisePlayerCandidate() }
                ?.let { candidates.add(it) }
        }

        document.select(
            "iframe[src], iframe[data-src], embed[src], embed[data-src], video[src], source[src]"
        ).forEach { node ->
            addCandidate(node.attr("data-src").ifBlank { node.attr("src") }, referer)
        }

        document.select(
            ".mobius option[value], select option[value], .mirror option[value], option[value]"
        ).forEach { option ->
            val value = option.attr("value").trim()
            if (value.isBlank()) return@forEach
            decodeServerValueVariants(value).forEach { decoded ->
                collectUrlsFromTrustedPlayerHtml(decoded, referer).forEach { candidates.add(it) }
                collectUrlsFromText(decoded, referer).forEach { candidates.add(it) }
            }
            addCandidate(value, referer)
        }

        listOf(
            "data-src", "data-url", "data-link", "data-iframe", "data-embed",
            "data-player", "data-video", "data-file", "data-stream",
            "data-content", "data-value", "data-hash",
        ).forEach { attr ->
            document.select("[$attr]").forEach { node ->
                val value = node.attr(attr).trim()
                if (value.isBlank()) return@forEach
                decodeServerValueVariants(value).forEach { decoded ->
                    collectUrlsFromTrustedPlayerHtml(decoded, referer).forEach { candidates.add(it) }
                    collectUrlsFromText(decoded, referer).forEach { candidates.add(it) }
                }
                addCandidate(value, referer)
            }
        }

        collectAjaxPlayerCandidates(document, referer).forEach { candidates.add(it) }
        collectUrlsFromText(html, referer).forEach { candidates.add(it) }
        return candidates
    }

    private suspend fun collectAjaxPlayerCandidates(
        document: Document,
        referer: String,
    ): List<String> {
        val results = linkedSetOf<String>()
        val actionUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val nodes = document.select(
            "[data-post][data-nume], [data-id][data-nume], "
                    + "[data-post][data-server], [data-episode][data-server]"
        )
        val actions = listOf(
            "doo_player_ajax", "player_ajax", "ts_player_ajax", "donghuafilm_player_ajax"
        )
        for (node in nodes.take(12)) {
            val post = node.attr("data-post").ifBlank { node.attr("data-id") }
                .ifBlank { node.attr("data-episode") }
            val nume = node.attr("data-nume").ifBlank { node.attr("data-server") }.ifBlank { "1" }
            val type = node.attr("data-type").ifBlank { "tv" }
            if (post.isBlank()) continue
            for (action in actions) {
                val text = runCatching {
                    app.post(
                        actionUrl,
                        data = mapOf(
                            "action" to action, "post" to post,
                            "nume" to nume, "type" to type,
                        ),
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
        Regex(
            """<(?:iframe|embed|source|video)[^>]+(?:src|data-src)=['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized).forEach { urls.add(it.groupValues[1]) }
        Regex(
            """(?:src|file|url|source|embed|iframe|data-url|data-src|data-file|data-player|link)\s*[:=]\s*['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized).forEach { urls.add(it.groupValues[1]) }
        Regex("""https?://[^'"<>()\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized).map { it.value.trimEnd(',', ';', ')') }
            .forEach { urls.add(it) }
        return urls.filter { it.isPotentialPlayer() }
    }

    private fun collectUrlsFromTrustedPlayerHtml(text: String, base: String): List<String> {
        val normalized = text.decodeEmbedText()
        val urls = linkedSetOf<String>()
        val doc = Jsoup.parse(normalized, base)
        doc.select(
            "iframe[src], iframe[data-src], embed[src], embed[data-src], source[src], video[src], a[href]"
        ).forEach { node ->
            val raw = node.attr("data-src")
                .ifBlank { node.attr("abs:src") }
                .ifBlank { node.attr("src") }
                .ifBlank { node.attr("abs:href") }
                .ifBlank { node.attr("href") }
                .trim()
            raw.toAbsoluteUrl(base)?.takeIf { !it.isNoisePlayerCandidate() }?.let { urls.add(it) }
        }
        Regex(
            """<(?:iframe|embed|source|video)[^>]+(?:src|data-src)=['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(1)?.toAbsoluteUrl(base) }
            .filterNot { it.isNoisePlayerCandidate() }
            .forEach { urls.add(it) }
        return urls.toList()
    }

    private fun decodeServerValueVariants(value: String): List<String> {
        val clean = value.decodeEmbedText().trim().trim('"', '\'')
        if (clean.isBlank()) return emptyList()
        val variants = linkedSetOf(clean)
        runCatching { URLDecoder.decode(clean, "UTF-8") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { variants.add(it.decodeEmbedText()) }
        val decoded = variants.toList().flatMap { candidate ->
            val compact = candidate.trim().trim('"', '\'').replace(Regex("""\s+"""), "")
            if (compact.length < 8) return@flatMap emptyList<String>()
            val normalized = compact.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            listOf(compact, padded).mapNotNull { raw ->
                runCatching { base64Decode(raw).decodeEmbedText() }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }
        variants.addAll(decoded)
        return variants.toList()
    }

    // ── URL / String helpers ──────────────────────────────────────────────────────

    private fun String.takeValidImageValue(): String? {
        val value = trim().trim('"', '\'')
        if (value.isBlank()) return null
        if (value.startsWith("data:", true) || value.startsWith("#") || value.startsWith("javascript", true)) return null
        return value
    }

    private fun String.bestSrcFromSet(): String? = split(',')
        .map { it.trim().substringBefore(" ").takeValidImageValue() }
        .lastOrNull { !it.isNullOrBlank() }

    private fun String.extractCssImageUrl(): String? =
        Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(this)?.groupValues?.getOrNull(2)?.takeValidImageValue()

    private fun Element.backgroundImageUrl(base: String = mainUrl): String? {
        val own = attr("style").extractCssImageUrl()
        if (!own.isNullOrBlank()) return own.toAbsoluteUrl(base)
        return select("[style*='url']").asSequence()
            .mapNotNull { it.attr("style").extractCssImageUrl() }
            .firstOrNull()?.toAbsoluteUrl(base)
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

    private fun Document.detailRoot(): Element =
        selectFirst(".bigcontent, .infox, .info-content, .infotable, .spe, article") ?: body()

    private fun detectDetailType(title: String, infoText: String, tags: List<String>, episodes: List<Episode>): TvType {
        if (episodes.size > 1) return TvType.Anime
        val isOva = Regex("""(?i)\b(Type|Tipe|Jenis)\s*:?\s*OVA\b""").containsMatchIn(infoText) ||
                tags.any { it.equals("OVA", true) }
        if (isOva) return TvType.OVA
        val explicitMovie = Regex("""(?i)\b(Type|Tipe|Jenis)\s*:?\s*Movie\b""").containsMatchIn(infoText) ||
                title.contains("Movie", true) ||
                (episodes.isEmpty() && tags.any { it.equals("Movie", true) })
        return if (explicitMovie) TvType.AnimeMovie else TvType.Anime
    }

    private fun String.belongsToDetailSlug(detailSlug: String): Boolean {
        val hrefSlug = seriesSlugFromUrl(this)
        if (hrefSlug.isBlank() || detailSlug.isBlank()) return true
        return hrefSlug.startsWith(detailSlug) || detailSlug.contains(hrefSlug.substringBeforeLast("-episode"))
    }

    /** Extract the canonical series slug from either an /anime/xxx/ or a post URL */
    private fun seriesSlugFromUrl(url: String): String {
        val path = runCatching { URI(url).path }.getOrDefault(url)
            .trim('/').lowercase(Locale.ROOT)
        // /anime/renegade-immortal/ → renegade-immortal
        if (path.startsWith("anime/")) return path.removePrefix("anime/").substringBefore("/")
        // renegade-immortal-episode-144-subtitle-indonesia → renegade-immortal
        return path.replace(Regex("-episode-\\d.*"), "").replace(Regex("-subtitle.*"), "")
            .replace(Regex("-sub-indo.*"), "").replace(Regex("-sub.*"), "")
            .trim('-')
    }

    /** Derive canonical /anime/ URL from an episode post URL */
    private fun deriveSeriesUrl(episodeUrl: String): String? {
        val slug = seriesSlugFromUrl(episodeUrl).takeIf { it.length > 3 } ?: return null
        return "$mainUrl/anime/$slug/"
    }

    private fun String.normalizeDailymotionUrl(): String {
        val id = extractDailymotionId() ?: return this
        return "https://www.dailymotion.com/video/$id"
    }

    private fun String.extractDailymotionId(): String? {
        val clean = decodeEmbedText()
        Regex("""(?:video=|/video/|/embed/video/)([A-Za-z0-9]+)""")
            .find(clean)?.groupValues?.getOrNull(1)?.let { return it }
        return Regex("""dai\.ly/([A-Za-z0-9]+)""")
            .find(clean)?.groupValues?.getOrNull(1)
    }

    private fun cleanTitle(raw: String?): String? = raw?.htmlUnescape()?.cleanText()
        ?.replace(Regex("""(?i)\s*[-–|]\s*DonghuaFilm.*$"""), "")
        ?.replace(Regex("""(?i)^Donghua\s+"""), "")
        ?.replace(Regex("""(?i)\s+subtitle\s+indonesia.*$"""), "")
        ?.replace(Regex("""(?i)\s+subtitle\s+english.*$"""), "")
        ?.replace(Regex("""(?i)\s+sub\s+indo.*$"""), "")
        ?.takeIf { it.isNotBlank() }

    private fun isBlockedTitle(title: String): Boolean {
        val lower = title.lowercase().trim()
        return lower in setOf(
            "donghuafilm", "layaranime", "home", "latest release", "new donghua",
            "popular today", "genre", "search", "privacy", "dmca", "schedule",
            "sedang populer", "sedang tayang", "movie terbaru"
        )
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

    private fun String.decodeEmbedText(): String = this
        .replace("\\/ ", "/").replace("\\/", "/")
        .replace("\\u002F", "/").replace("\\u003C", "<")
        .replace("\\u003E", ">").replace("\\u0026", "&")
        .replace("\\u003D", "=").replace("\\u0027", "'")
        .replace("\\\"", "\"").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#34;", "\"").replace("&#039;", "'")
        .replace("&#x27;", "'").replace("&#038;", "&")
        .replace("&amp;", "&").trim()

    private fun String.toAbsoluteUrl(base: String = mainUrl): String? {
        val clean = trim().trim('"', '\'')
        if (clean.isBlank() || clean.startsWith("javascript", true) || clean.startsWith("#")) return null
        return runCatching { URI(base).resolve(clean).toString() }.getOrNull()
    }

    private fun String.normalizedKey(): String =
        substringBefore("#").trimEnd('/').lowercase(Locale.ROOT)

    private fun String.toEpisodeNumber(): Int? =
        Regex("""(?i)(?:episode|eps?|ep)\s*\.?\s*(\d{1,4})""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String.isDirectMediaLike(): Boolean {
        val value = lowercase(Locale.ROOT)
        return value.contains(".m3u8") || value.contains(".mp4") ||
                value.contains(".webm") || value.contains(".mkv") ||
                value.contains("/stream/") || value.contains("videoplayback")
    }

    private fun String.isPotentialPlayer(): Boolean {
        if (isNoisePlayerCandidate()) return false
        if (isDirectMediaLike()) return true
        val value = lowercase(Locale.ROOT)
        return listOf(
            "iframe", "embed", "player", "stream", "maodrive", "desustream", "ondesuhd",
            "vidhide", "filedon", "filemoon", "streamtape", "dood", "mp4upload", "blogger",
            "googlevideo", "sendvid", "rumble", "dailymotion", "dai.ly", "geo.dailymotion",
            "ok.ru", "odnoklassniki", "okru", "videoembed", "youtube", "streamwish",
            "wishfast", "vidguard", "mixdrop", "voe", "streamruby", "streamsb", "sbembed",
            "sbrapid", "playersb", "fembed", "femax", "abyss", "lulustream", "pixeldrain",
        ).any { value.contains(it) }
    }

    private fun String.isNoisePlayerCandidate(): Boolean {
        val value = lowercase(Locale.ROOT)
        if (value.isBlank()) return true
        return listOf(
            "javascript:", "about:blank", "data:image", "googlesyndication", "doubleclick",
            "google-analytics", "googletagmanager", "facebook.com/plugins", "histats",
            "/ads/", "banner", ".css", ".js", ".jpg", ".jpeg", ".png", ".gif",
            ".webp", ".svg", ".ico", ".woff", ".ttf",
        ).any { value.contains(it) }
    }

    private fun inferType(url: String): ExtractorLinkType = when {
        url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun hostLabel(url: String): String =
        runCatching { URI(url).host ?: url }.getOrDefault(url)
}
