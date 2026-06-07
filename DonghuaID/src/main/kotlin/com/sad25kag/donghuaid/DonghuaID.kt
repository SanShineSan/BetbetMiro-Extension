package com.sad25kag.donghuaid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

class DonghuaID : MainAPI() {
    override var mainUrl = "https://donghuaid.live"
    override var name = "DonghuaID"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Release",
        "$mainUrl/anime/?status=&type=&sub=&order=&page={page}" to "All Series",
        "$mainUrl/anime/?status=ongoing&type=&sub=&order=&page={page}" to "Ongoing",
        "$mainUrl/anime/?status=completed&type=&sub=&order=&page={page}" to "Completed",
        "$mainUrl/anime/?status=upcoming&type=&sub=&order=&page={page}" to "Upcoming",
        "$mainUrl/anime/?status=hiatus&type=&sub=&order=&page={page}" to "Hiatus",
        "$mainUrl/anime/?status=&type=tv&sub=&order=&page={page}" to "TV Series",
        "$mainUrl/anime/?status=&type=ova&sub=&order=&page={page}" to "OVA",
        "$mainUrl/anime/?status=&type=movie&sub=&order=&page={page}" to "Movie",
        "$mainUrl/anime/?status=&type=live%20action&sub=&order=&page={page}" to "Live Action",
        "$mainUrl/anime/?status=&type=special&sub=&order=&page={page}" to "Special",
        "$mainUrl/anime/?status=&type=bd&sub=&order=&page={page}" to "BD",
        "$mainUrl/anime/?status=&type=ona&sub=&order=&page={page}" to "ONA",
        "$mainUrl/anime/?status=&type=music&sub=&order=&page={page}" to "Music",
        "$mainUrl/anime/?status=&type=&sub=sub&order=&page={page}" to "Sub",
        "$mainUrl/anime/?status=&type=&sub=dub&order=&page={page}" to "Dub",
        "$mainUrl/anime/?status=&type=&sub=raw&order=&page={page}" to "RAW",
        "$mainUrl/anime/?status=&type=&sub=&order=title&page={page}" to "A-Z",
        "$mainUrl/anime/?status=&type=&sub=&order=titlereverse&page={page}" to "Z-A",
        "$mainUrl/anime/?status=&type=&sub=&order=update&page={page}" to "Latest Update",
        "$mainUrl/anime/?status=&type=&sub=&order=latest&page={page}" to "Latest Added",
        "$mainUrl/anime/?status=&type=&sub=&order=popular&page={page}" to "Popular",
        "$mainUrl/anime/?status=&type=&sub=&order=rating&page={page}" to "Rating",
        "$mainUrl/anime/?genre%5B%5D=action&status=&type=&sub=&order=&page={page}" to "Action",
        "$mainUrl/anime/?genre%5B%5D=action-fantasy&status=&type=&sub=&order=&page={page}" to "Action.Fantasy",
        "$mainUrl/anime/?genre%5B%5D=adult-cast&status=&type=&sub=&order=&page={page}" to "Adult Cast",
        "$mainUrl/anime/?genre%5B%5D=adventure&status=&type=&sub=&order=&page={page}" to "Adventure",
        "$mainUrl/anime/?genre%5B%5D=animation&status=&type=&sub=&order=&page={page}" to "Animation",
        "$mainUrl/anime/?genre%5B%5D=childcare&status=&type=&sub=&order=&page={page}" to "Childcare",
        "$mainUrl/anime/?genre%5B%5D=comedy&status=&type=&sub=&order=&page={page}" to "Comedy",
        "$mainUrl/anime/?genre%5B%5D=delinquents&status=&type=&sub=&order=&page={page}" to "Delinquents",
        "$mainUrl/anime/?genre%5B%5D=demon&status=&type=&sub=&order=&page={page}" to "Demon",
        "$mainUrl/anime/?genre%5B%5D=demons&status=&type=&sub=&order=&page={page}" to "Demons",
        "$mainUrl/anime/?genre%5B%5D=domestic&status=&type=&sub=&order=&page={page}" to "Domestic",
        "$mainUrl/anime/?genre%5B%5D=drama&status=&type=&sub=&order=&page={page}" to "Drama",
        "$mainUrl/anime/?genre%5B%5D=ecchi&status=&type=&sub=&order=&page={page}" to "Ecchi",
        "$mainUrl/anime/?genre%5B%5D=exciting&status=&type=&sub=&order=&page={page}" to "exciting",
        "$mainUrl/anime/?genre%5B%5D=fantaasy&status=&type=&sub=&order=&page={page}" to "Fantaasy",
        "$mainUrl/anime/?genre%5B%5D=fantasy&status=&type=&sub=&order=&page={page}" to "Fantasy",
        "$mainUrl/anime/?genre%5B%5D=fighting&status=&type=&sub=&order=&page={page}" to "fighting",
        "$mainUrl/anime/?genre%5B%5D=girls-love&status=&type=&sub=&order=&page={page}" to "Girls Love",
        "$mainUrl/anime/?genre%5B%5D=gourmet&status=&type=&sub=&order=&page={page}" to "Gourmet",
        "$mainUrl/anime/?genre%5B%5D=harem&status=&type=&sub=&order=&page={page}" to "Harem",
        "$mainUrl/anime/?genre%5B%5D=historical&status=&type=&sub=&order=&page={page}" to "Historical",
        "$mainUrl/anime/?genre%5B%5D=historical-martial-arts&status=&type=&sub=&order=&page={page}" to "Historical Martial Arts",
        "$mainUrl/anime/?genre%5B%5D=horror&status=&type=&sub=&order=&page={page}" to "Horror",
        "$mainUrl/anime/?genre%5B%5D=isekai&status=&type=&sub=&order=&page={page}" to "Isekai",
        "$mainUrl/anime/?genre%5B%5D=kids&status=&type=&sub=&order=&page={page}" to "Kids",
        "$mainUrl/anime/?genre%5B%5D=martial&status=&type=&sub=&order=&page={page}" to "Martial",
        "$mainUrl/anime/?genre%5B%5D=martial-art&status=&type=&sub=&order=&page={page}" to "Martial Art",
        "$mainUrl/anime/?genre%5B%5D=martial-arts&status=&type=&sub=&order=&page={page}" to "Martial Arts",
        "$mainUrl/anime/?genre%5B%5D=mecha&status=&type=&sub=&order=&page={page}" to "Mecha",
        "$mainUrl/anime/?genre%5B%5D=medical&status=&type=&sub=&order=&page={page}" to "Medical",
        "$mainUrl/anime/?genre%5B%5D=military&status=&type=&sub=&order=&page={page}" to "Military",
        "$mainUrl/anime/?genre%5B%5D=monsters&status=&type=&sub=&order=&page={page}" to "Monsters",
        "$mainUrl/anime/?genre%5B%5D=mystery&status=&type=&sub=&order=&page={page}" to "Mystery",
        "$mainUrl/anime/?genre%5B%5D=mythology&status=&type=&sub=&order=&page={page}" to "Mythology",
        "$mainUrl/anime/?genre%5B%5D=organized-crime&status=&type=&sub=&order=&page={page}" to "Organized Crime",
        "$mainUrl/anime/?genre%5B%5D=parody&status=&type=&sub=&order=&page={page}" to "Parody",
        "$mainUrl/anime/?genre%5B%5D=popular&status=&type=&sub=&order=&page={page}" to "Popular Genre",
        "$mainUrl/anime/?genre%5B%5D=psychological&status=&type=&sub=&order=&page={page}" to "Psychological",
        "$mainUrl/anime/?genre%5B%5D=reincarnation&status=&type=&sub=&order=&page={page}" to "Reincarnation",
        "$mainUrl/anime/?genre%5B%5D=romance&status=&type=&sub=&order=&page={page}" to "Romance",
        "$mainUrl/anime/?genre%5B%5D=school&status=&type=&sub=&order=&page={page}" to "School",
        "$mainUrl/anime/?genre%5B%5D=sci-fi&status=&type=&sub=&order=&page={page}" to "Sci-Fi",
        "$mainUrl/anime/?genre%5B%5D=shounen&status=&type=&sub=&order=&page={page}" to "Shounen",
        "$mainUrl/anime/?genre%5B%5D=showbiz&status=&type=&sub=&order=&page={page}" to "Showbiz",
        "$mainUrl/anime/?genre%5B%5D=sports&status=&type=&sub=&order=&page={page}" to "Sports",
        "$mainUrl/anime/?genre%5B%5D=super-power&status=&type=&sub=&order=&page={page}" to "Super Power",
        "$mainUrl/anime/?genre%5B%5D=supernatural&status=&type=&sub=&order=&page={page}" to "Supernatural",
        "$mainUrl/anime/?genre%5B%5D=suspense&status=&type=&sub=&order=&page={page}" to "Suspense",
        "$mainUrl/anime/?genre%5B%5D=sword-fight&status=&type=&sub=&order=&page={page}" to "Sword fight",
        "$mainUrl/anime/?genre%5B%5D=team-sports&status=&type=&sub=&order=&page={page}" to "Team Sports",
        "$mainUrl/anime/?genre%5B%5D=time-travel&status=&type=&sub=&order=&page={page}" to "Time Travel",
        "$mainUrl/anime/?genre%5B%5D=urban-fantasy&status=&type=&sub=&order=&page={page}" to "Urban Fantasy",
        "$mainUrl/anime/?genre%5B%5D=vengeance&status=&type=&sub=&order=&page={page}" to "Vengeance",
        "$mainUrl/anime/?genre%5B%5D=video-game&status=&type=&sub=&order=&page={page}" to "Video Game",
        "$mainUrl/anime/?genre%5B%5D=wuxia&status=&type=&sub=&order=&page={page}" to "Wuxia",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val document = app.get(url, headers = siteHeaders, referer = "$mainUrl/").document
        val results = parseDonghuaCards(document, includeSidebar = request.data == "$mainUrl/")
            .distinctBy { it.url.normalizedKey() }
        val hasNext = document.selectFirst(
            "a.next[href], a.next.page-numbers[href], link[rel=next], .hpage a[href*='page=${page + 1}'], a[href*='/page/${page + 1}/'], a[href*='page=${page + 1}']"
        ) != null

        return newHomePageResponse(request.name, results, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val routes = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/anime/?status=&type=&sub=&order=&keyword=$encoded",
            "$mainUrl/anime/?s=$encoded",
        )

        return routes.flatMap { route ->
            runCatching {
                parseDonghuaCards(app.get(route, headers = siteHeaders, referer = "$mainUrl/").document, includeSidebar = false)
            }.getOrDefault(emptyList())
        }
            .filter { result -> result.name.contains(query, true) || result.url.contains(query.slugHint(), true) }
            .distinctBy { it.url.normalizedKey() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = siteHeaders, referer = "$mainUrl/").document
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1[itemprop=name], .infox h1, .entry-title, h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ) ?: throw ErrorLoadingException("Judul DonghuaID tidak ditemukan")

        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")?.absoluteUrl(url)
            ?: document.selectFirst(".thumb img, .ime img, .bigcontent img, .poster img, img.wp-post-image")?.imageUrl(url)

        val plot = document.selectFirst(".entry-content, .synopsis, .desc, .mindesc, .bixbox.synp, article .content")
            ?.text()
            ?.cleanText()
            ?.takeIf { it.length > 20 }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.cleanText()

        val tags = document.select(".info-content a[href*='genre'], .spe a[href*='genre'], a[rel=tag]")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() && !it.equals("Genres", true) }
            .distinct()

        val infoText = document.select(".spe, .info-content, .infotable, .bigcontent, .tsinfo, .postbody").joinToString(" ") { it.text() }.cleanText()
        val year = Regex("""(?i)(?:Released|Rilis|Aired|Year)\s*:?\s*([12][0-9]{3})""").find(infoText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val status = detectStatus(infoText)
        val episodes = parseEpisodes(document, url).distinctBy { it.data.normalizedKey() }
        val type = when {
            episodes.size > 1 -> TvType.Anime
            infoText.contains("Movie", true) || tags.any { it.equals("Movie", true) } || url.contains("movie", true) -> TvType.AnimeMovie
            infoText.contains("OVA", true) || tags.any { it.equals("OVA", true) } -> TvType.OVA
            else -> TvType.Anime
        }

        val recommendations = parseDonghuaCards(document, includeSidebar = false)
            .filterNot { it.url.normalizedKey() == url.normalizedKey() }
            .take(16)

        return if (type == TvType.Anime && episodes.size > 1) {
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
        val response = app.get(data, headers = siteHeaders, referer = "$mainUrl/")
        val document = response.document
        val emitted = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, label: String? = null, referer: String = data): Boolean {
            val finalUrl = rawUrl
                ?.decodeEmbedText()
                ?.absoluteUrl(referer)
                ?.takeIf { it.isDirectMediaLike() }
                ?: return false
            val key = finalUrl.substringBefore("#")
            if (!emitted.add(key)) return true

            val linkName = listOfNotNull(name, label?.cleanText()?.takeIf { it.isNotBlank() }).joinToString(" - ")
            callback.invoke(
                newExtractorLink(linkName, linkName, finalUrl, inferType(finalUrl)) {
                    this.referer = referer
                    this.quality = getQualityFromName(label ?: finalUrl)
                    this.headers = siteHeaders + mapOf(
                        "Referer" to referer,
                        "Origin" to originOf(referer),
                        "Range" to "bytes=0-",
                    )
                }
            )
            return true
        }

        val candidates = collectPlayerCandidates(document, response.text, data)
        for (candidate in candidates.take(80)) {
            val playerUrl = candidate.decodeEmbedText().absoluteUrl(data) ?: continue
            if (emitDirect(playerUrl, hostLabel(playerUrl), data)) continue

            val before = emitted.size
            val countedCallback: (ExtractorLink) -> Unit = { link ->
                emitted.add(link.url.substringBefore("#"))
                callback.invoke(link)
            }
            runCatching { loadExtractor(playerUrl, data, subtitleCallback, countedCallback) }
            if (emitted.size > before) continue

            val playerReferer = if (playerUrl.contains("dailymotion", true)) "https://geo.dailymotion.com/" else data
            val playerHtml = runCatching {
                app.get(
                    playerUrl,
                    headers = siteHeaders + mapOf("Referer" to playerReferer, "Origin" to originOf(playerReferer)),
                    referer = playerReferer,
                ).text
            }.getOrNull().orEmpty()
            if (playerHtml.isBlank()) continue

            val unpacked = runCatching { getAndUnpack(playerHtml) }.getOrNull().orEmpty()
            val nested = collectUrlsFromText(playerHtml + "\n" + unpacked, playerUrl)
            for (nestedUrl in nested.take(40)) {
                if (emitDirect(nestedUrl, hostLabel(playerUrl), playerUrl)) continue
                val fixedNested = nestedUrl.absoluteUrl(playerUrl) ?: continue
                runCatching { loadExtractor(fixedNested, playerUrl, subtitleCallback, countedCallback) }
            }
        }

        return emitted.isNotEmpty()
    }

    private fun parseDonghuaCards(document: Document, includeSidebar: Boolean = false): List<SearchResponse> {
        val primarySelectors = listOf(
            ".listupd article.bs, .listupd article, .listupd .bs",
            ".result .bsx, .search-page article",
            ".items .item, .post-show li, .latest li",
        )
        val sidebarSelectors = listOf(
            ".serieslist.pop ul li, .ongoingseries ul li, .bixbox ul li",
        )

        val primary = primarySelectors.asSequence()
            .flatMap { selector -> document.select(selector).asSequence() }
            .mapNotNull { it.toDonghuaCard() }
            .distinctBy { it.url.normalizedKey() }
            .toList()

        if (primary.isNotEmpty() || !includeSidebar) return primary

        return sidebarSelectors.asSequence()
            .flatMap { selector -> document.select(selector).asSequence() }
            .mapNotNull { it.toDonghuaCard() }
            .distinctBy { it.url.normalizedKey() }
            .toList()
    }

    private fun Element.toDonghuaCard(): SearchResponse? {
        val anchor = selectFirst(
            ".bsx a[href], a.series[href], .tt a[href], h2 a[href], h3 a[href], h4 a[href], a[href*='/anime/'], a[href*='episode'], a[href]"
        ) ?: return null
        val href = anchor.attr("href").absoluteUrl(mainUrl) ?: return null
        if (!href.startsWith(mainUrl, true)) return null
        if (!href.isDonghuaContentUrl()) return null

        val rawTitle = anchor.attr("title").cleanText().takeIf { it.length > 2 }
            ?: selectFirst(".tt h2, .tt, .eggtitle, .epl-title, h2, h3, h4")?.text()?.cleanText()?.takeIf { it.length > 2 }
            ?: selectFirst("img")?.attr("title")?.cleanText()?.takeIf { it.length > 2 }
            ?: selectFirst("img")?.attr("alt")?.cleanText()?.takeIf { it.length > 2 }
            ?: anchor.text().cleanText().takeIf { it.length > 2 }
            ?: return null

        val title = cleanCardTitle(rawTitle).takeIf { it.length > 2 } ?: return null
        val poster = selectFirst("img")?.imageUrl(href) ?: anchor.selectFirst("img")?.imageUrl(href)
        val typeText = listOf(
            selectFirst(".typez")?.text(),
            selectFirst(".epx")?.text(),
            text(),
            href,
        ).joinToString(" ") { it.orEmpty() }
        val tvType = when {
            typeText.contains("Movie", true) -> TvType.AnimeMovie
            typeText.contains("OVA", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodes(document: Document, pageUrl: String): List<Episode> {
        val scopedAnchors = document.select(
            ".episodelist li a[href], .eplister li a[href], .bixbox.bxcl li a[href], .episodelist a[href*='episode']"
        )
        val anchors = if (scopedAnchors.isNotEmpty()) scopedAnchors else {
            val currentSlug = pageUrl.slugSeriesKey()
            document.select("article.post a[href*='episode'], .postbody a[href*='episode'], .entry-content a[href*='episode']")
                .filter { anchor -> anchor.attr("href").absoluteUrl(pageUrl)?.slugSeriesKey() == currentSlug }
        }

        return anchors.mapNotNull { anchor ->
            val href = anchor.attr("href").absoluteUrl(pageUrl) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl, true) || !href.contains("episode", true)) return@mapNotNull null
            val rawTitle = anchor.selectFirst(".epl-title, .playinfo h3, h3, .title")?.text()?.cleanText()
                ?: anchor.ownText().cleanText().takeIf { it.length > 2 }
                ?: anchor.text().cleanText().takeIf { it.length > 2 && !it.equals("Prev", true) && !it.equals("Next", true) }
                ?: href.substringAfter(mainUrl).trim('/').replace('-', ' ')
            val episodeNumber = anchor.selectFirst(".epl-num, .epx, .num")?.text()?.episodeNumber()
                ?: rawTitle.episodeNumber()
                ?: href.episodeNumber()
            newEpisode(href) {
                this.name = cleanTitle(rawTitle) ?: rawTitle
                this.episode = episodeNumber
                this.posterUrl = anchor.selectFirst("img")?.imageUrl(href)
            }
        }
            .distinctBy { it.data.normalizedKey() }
            .sortedByDescending { it.episode ?: -1 }
    }

    private suspend fun collectPlayerCandidates(document: Document, html: String, referer: String): LinkedHashSet<String> {
        val candidates = linkedSetOf<String>()

        document.select("iframe[src], embed[src], video[src], source[src]").forEach { node ->
            node.attr("src").takeIf { it.isNotBlank() }?.let { addCandidateValue(it, referer, candidates) }
        }

        document.select("select.mirror option[value], .mirror option[value], .mobius option[value], option[data-index][value]").forEach { option ->
            val value = option.attr("value").trim()
            if (value.isBlank()) return@forEach
            val label = option.text().cleanText()
            addCandidateValue(value, referer, candidates)
            decodeBase64(value)?.let { decoded ->
                collectUrlsFromText(decoded, referer).forEach { candidates.add(it) }
                Jsoup.parse(decoded).select("iframe[src], embed[src], video[src], source[src]").forEach { node ->
                    node.attr("src").takeIf { it.isNotBlank() }?.absoluteUrl(referer)?.let { candidates.add(it) }
                }
                if (label.isNotBlank()) candidates.add("#label:$label")
            }
        }

        val dataAttrs = listOf(
            "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file", "data-stream", "data-content", "data-hash"
        )
        dataAttrs.forEach { attr ->
            document.select("[$attr]").forEach { node ->
                val value = node.attr(attr).trim()
                if (value.isNotBlank()) addCandidateValue(value, referer, candidates)
            }
        }

        collectAjaxPlayers(document, referer).forEach { candidates.add(it) }
        collectUrlsFromText(html, referer).forEach { candidates.add(it) }
        document.select("script").forEach { script ->
            val scriptText = script.html()
            val unpacked = runCatching { getAndUnpack(scriptText) }.getOrNull().orEmpty()
            collectUrlsFromText(scriptText + "\n" + unpacked, referer).forEach { candidates.add(it) }
        }
        return candidates.filterNot { it.startsWith("#label:") }.toCollection(LinkedHashSet())
    }

    private fun addCandidateValue(value: String, referer: String, candidates: LinkedHashSet<String>) {
        candidates.add(value)
        value.decodeEmbedText().takeIf { it != value }?.let { decoded ->
            candidates.add(decoded)
            collectUrlsFromText(decoded, referer).forEach { candidates.add(it) }
            Jsoup.parse(decoded).select("iframe[src], embed[src], video[src], source[src]").forEach { node ->
                node.attr("src").takeIf { it.isNotBlank() }?.absoluteUrl(referer)?.let { candidates.add(it) }
            }
        }
        decodeBase64(value)?.takeIf { it.isNotBlank() }?.let { decoded ->
            candidates.add(decoded)
            collectUrlsFromText(decoded, referer).forEach { candidates.add(it) }
            Jsoup.parse(decoded).select("iframe[src], embed[src], video[src], source[src]").forEach { node ->
                node.attr("src").takeIf { it.isNotBlank() }?.absoluteUrl(referer)?.let { candidates.add(it) }
            }
        }
    }

    private suspend fun collectAjaxPlayers(document: Document, referer: String): List<String> {
        val results = linkedSetOf<String>()
        val nodes = document.select("[data-post][data-nume], [data-id][data-nume], [data-post][data-server], [data-episode][data-server]")
        val actions = listOf("doo_player_ajax", "player_ajax", "ts_player_ajax", "donghuaid_player_ajax")

        for (node in nodes.take(12)) {
            val post = node.attr("data-post").ifBlank { node.attr("data-id") }.ifBlank { node.attr("data-episode") }
            val nume = node.attr("data-nume").ifBlank { node.attr("data-server") }.ifBlank { "1" }
            val type = node.attr("data-type").ifBlank { "tv" }
            if (post.isBlank()) continue

            for (action in actions) {
                val text = runCatching {
                    app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf("action" to action, "post" to post, "nume" to nume, "type" to type),
                        headers = siteHeaders + mapOf(
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
        Regex("""(?:src|file|url|source|video|embed|iframe|data-url|data-src|data-file|data-player|link)\s*[:=]\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { urls.add(it.groupValues[1]) }
        Regex("""https?:\\?/\\?/[^'\"<>()\\\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map { it.value.replace("\\/", "/") }
            .forEach { urls.add(it) }
        Regex("""https?://[^'\"<>()\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map { it.value.trimEnd(',', ';', ')') }
            .forEach { urls.add(it) }

        return urls.mapNotNull { it.absoluteUrl(base) }
            .filter { it.isPlayableCandidate() }
            .distinct()
    }

    private fun buildPagedUrl(rawUrl: String, page: Int): String {
        if (rawUrl.contains("{page}")) {
            return if (page <= 1) {
                rawUrl
                    .replace("/page/{page}/", "/")
                    .replace("/page/{page}", "/")
                    .replace("page={page}", "page=1")
            } else {
                rawUrl.replace("{page}", page.toString())
            }
        }
        if (page <= 1) return rawUrl
        val clean = rawUrl.trimEnd('/')
        return when {
            clean.contains("page=") -> clean.replace(Regex("""page=\d+"""), "page=$page")
            clean.contains("?") -> "$clean&page=$page"
            else -> "$clean/page/$page/"
        }
    }

    private fun detectStatus(infoText: String): ShowStatus? {
        val value = infoText.lowercase(Locale.ROOT)
        return when {
            value.contains("completed") || value.contains("selesai") || value.contains("end") -> ShowStatus.Completed
            value.contains("ongoing") || value.contains("airing") || value.contains("tayang") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun Element.imageUrl(base: String = mainUrl): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        val raw = listOf("data-src", "data-lazy-src", "data-original", "data-image", "src", "poster", "srcset")
            .firstNotNullOfOrNull { attr ->
                image?.attr(attr)?.split(",")?.firstOrNull()?.substringBefore(" ")?.trim()?.takeIf { it.isImageCandidate() }
            }
        return raw?.absoluteUrl(base)
    }

    private fun cleanCardTitle(raw: String): String = raw.cleanText()
        .replace(Regex("""(?i)^\s*(?:ONA|TV|Movie|OVA|Special)\s+"""), "")
        .replace(Regex("""(?i)^\s*(?:Ongoing|Completed|Upcoming|Hiatus)\s+"""), "")
        .replace(Regex("""(?i)^\s*(?:Ep|Episode|Eps?)\s*\d+\s*"""), "")
        .replace(Regex("""(?i)\s+Episode\s+\d+.*$"""), "")
        .replace(Regex("""(?i)\s+Sub(?:title)?\s*(?:Indo|Indonesia)?.*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun cleanTitle(raw: String?): String? = raw?.cleanText()
        ?.replace(Regex("""(?i)^\s*Nonton\s+"""), "")
        ?.replace(Regex("""(?i)^\s*Download\s+"""), "")
        ?.replace(Regex("""(?i)\s+-\s+Donghuaid.*$"""), "")
        ?.replace(Regex("""(?i)\s+Subtitle\s+Indonesia.*$"""), "")
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()
        ?.takeIf { it.length > 1 }

    private fun String.cleanText(): String = Jsoup.parse(this).text()
        .replace("\u00a0", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.decodeEmbedText(): String {
        var value = cleanText()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        repeat(2) {
            value = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }
        return value.trim()
    }

    private fun decodeBase64(value: String): String? {
        val clean = value.trim().replace("\n", "").replace("\r", "")
        if (clean.length < 12 || clean.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '+' && it != '/' && it != '=' }) return null
        return runCatching { String(Base64.getDecoder().decode(clean), Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.contains("<iframe", true) || it.contains("http", true) }
    }

    private fun String.absoluteUrl(baseUrl: String = mainUrl): String? {
        val value = trim().trim('"', '\'').replace("\\/", "/")
        if (value.isBlank() || value.startsWith("javascript:", true) || value == "#" || value.startsWith("data:", true)) return null
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return runCatching { URI(baseUrl).resolve(value).toString() }.getOrNull()
    }

    private fun String.normalizedKey(): String = substringBefore("#").trimEnd('/').lowercase(Locale.ROOT)

    private fun String.episodeNumber(): Int? = Regex("""(?i)(?:episode|eps?|ep)\s*\.?\s*(\d{1,4})""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("""\b(\d{1,4})\b""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String.slugHint(): String = lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), "-").trim('-')

    private fun String.slugSeriesKey(): String {
        val slug = substringBefore("?").trimEnd('/').substringAfterLast('/')
        return slug
            .replace(Regex("""(?i)-episode-?\d+.*$"""), "")
            .replace(Regex("""(?i)-eps?-?\d+.*$"""), "")
            .trim('-')
    }

    private fun String.isDirectMediaLike(): Boolean {
        val value = lowercase(Locale.ROOT).substringBefore("#")
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".m4s") || value.contains(".webm") || value.contains(".mkv") || value.contains("videoplayback") || value.contains("/stream/")
    }

    private fun String.isPlayableCandidate(): Boolean {
        val value = decodeEmbedText().lowercase(Locale.ROOT)
        if (isDirectMediaLike()) return true
        if (!value.startsWith("http")) return false
        return listOf(
            "iframe", "embed", "player", "stream", "desustream", "ondesuhd", "maodrive", "vidhide", "filedon", "filemoon", "streamtape", "streamsb", "sbembed", "dood", "mp4upload", "blogger", "googlevideo", "sendvid", "ok.ru", "rumble", "dailymotion", "youtube", "short.icu", "abyssplayer", "hydrax", "turbovid", "turbosplayer", "cdndirector", "dmcdn", "ms.ok.ru", "cdn", "manifest"
        ).any { value.contains(it) }
    }


    private fun String.isDonghuaContentUrl(): Boolean {
        val value = substringBefore("#").substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)
        val root = mainUrl.trimEnd('/').lowercase(Locale.ROOT)
        if (value == root || !value.startsWith(root)) return false
        val path = value.removePrefix(root).trim('/')
        if (path.isBlank()) return false
        if (path.startsWith("wp-") || path.startsWith("tag/") || path.startsWith("category/") || path.startsWith("genres/")) return false
        if (path.startsWith("anime/list-mode") || path.startsWith("anime/page") || path == "anime") return false
        if (path.contains("/page/")) return false
        return true
    }

    private fun String.isImageCandidate(): Boolean {
        if (isBlank() || startsWith("data:", true)) return false
        if (contains("blank", true) || contains("placeholder", true) || contains("spacer", true)) return false
        return contains(".jpg", true) || contains(".jpeg", true) || contains(".png", true) || contains(".webp", true) || contains("/wp-content/uploads/", true)
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

    private fun hostLabel(url: String): String? = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()
}
