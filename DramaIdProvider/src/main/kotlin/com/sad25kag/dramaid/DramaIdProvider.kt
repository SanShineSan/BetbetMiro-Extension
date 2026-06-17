package com.sad25kag.dramaid

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

class DramaIdProvider : MainAPI() {
    override var mainUrl = "https://drama-id.com"
    override var name = "DramaID"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Update Terbaru",

        "$mainUrl/negara/korea-selatan/page/%d/" to "Drama Korea",
        "$mainUrl/negara/china/page/%d/" to "Drama China",
        "$mainUrl/negara/japan/page/%d/" to "Drama Jepang",
        "$mainUrl/negara/thailand/page/%d/" to "Drama Thailand",
        "$mainUrl/negara/taiwan/page/%d/" to "Drama Taiwan",
        "$mainUrl/negara/hongkong/page/%d/" to "Drama Hongkong",
        "$mainUrl/negara/philippines/page/%d/" to "Drama Philippines",

        "$mainUrl/status-drama/ongoing/page/%d/" to "Ongoing",
        "$mainUrl/status-drama/complete/page/%d/" to "Tamat",

        "$mainUrl/genre/action/page/%d/" to "Action",
        "$mainUrl/genre/adventure/page/%d/" to "Adventure",
        "$mainUrl/genre/business/page/%d/" to "Business",
        "$mainUrl/genre/comedy/page/%d/" to "Comedy",
        "$mainUrl/genre/crime/page/%d/" to "Crime",
        "$mainUrl/genre/drama/page/%d/" to "Drama",
        "$mainUrl/genre/family/page/%d/" to "Family",
        "$mainUrl/genre/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/genre/food/page/%d/" to "Food",
        "$mainUrl/genre/friendship/page/%d/" to "Friendship",
        "$mainUrl/genre/historical/page/%d/" to "Historical",
        "$mainUrl/genre/horror/page/%d/" to "Horror",
        "$mainUrl/genre/law/page/%d/" to "Law",
        "$mainUrl/genre/life/page/%d/" to "Life",
        "$mainUrl/genre/melodrama/page/%d/" to "Melodrama",
        "$mainUrl/genre/military/page/%d/" to "Military",
        "$mainUrl/genre/music/page/%d/" to "Music",
        "$mainUrl/genre/mystery/page/%d/" to "Mystery",
        "$mainUrl/genre/office/page/%d/" to "Office",
        "$mainUrl/genre/political/page/%d/" to "Political",
        "$mainUrl/genre/psychological/page/%d/" to "Psychological",
        "$mainUrl/genre/romance/page/%d/" to "Romance",
        "$mainUrl/genre/school/page/%d/" to "School",
        "$mainUrl/genre/sci-fi/page/%d/" to "Sci-Fi",
        "$mainUrl/genre/sports/page/%d/" to "Sports",
        "$mainUrl/genre/supernatural/page/%d/" to "Supernatural",
        "$mainUrl/genre/thriller/page/%d/" to "Thriller",
        "$mainUrl/genre/variety-show/page/%d/" to "Variety Show",
        "$mainUrl/genre/war/page/%d/" to "War",
        "$mainUrl/genre/youth/page/%d/" to "Youth",

        "$mainUrl/rating/semua-umur/page/%d/" to "Semua Umur",
        "$mainUrl/rating/13/page/%d/" to "Rating 13",
        "$mainUrl/rating/15/page/%d/" to "Rating 15",
        "$mainUrl/rating/17/page/%d/" to "Rating 17",
        "$mainUrl/rating/18/page/%d/" to "Rating 18"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = app.get(url, referer = "$mainUrl/").document
        val items = document.toSearchResults()
        val hasNext = document.select(
            ".pagination a[href]:matchesOwn((?i)Next), " +
                ".pagination a[href*='/page/${page + 1}/'], " +
                "a.next[href], a[href*='/page/${page + 1}/']"
        ).isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document
        return document.toSearchResults()
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeUrl(url, mainUrl) ?: throw ErrorLoadingException("Invalid URL")
        if (!isDramaDetailUrl(fixedUrl)) throw ErrorLoadingException("Invalid DramaID detail URL")

        val document = app.get(fixedUrl, referer = "$mainUrl/").document
        if (!document.isValidDetailPage(fixedUrl)) {
            throw ErrorLoadingException("Invalid DramaID detail page")
        }

        val detailTitle = detailValue(document, "Judul")
        val title = listOf(
            detailTitle,
            document.selectFirst("h1.single-title, h1.single_h2, h1.entry-title, h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?.takeIf { it.isValidDramaTitle() }
            ?: throw ErrorLoadingException("DramaID title not found")

        val poster = document.selectFirst(".thumbnail_single img, meta[property=og:image], img.wp-post-image, .poster img, img[alt]")
            ?.imageUrl()

        val plot = document.select("#sinopsis p, .synopsis p, .entry-content p")
            .joinToString("\n") { it.text().trim() }
            .trim()
            .takeUnless { it.isBlank() || it.equals("Plot Tidak Ditemukan", true) }

        val year = detailValue(document, "Tahun")?.let(::extractYear)
        val rating = detailValue(document, "Skor")?.toScore()
        val tags = document.select("#informasi li:has(strong:matchesOwn((?i)Genres)) a, .info li:has(strong:matchesOwn((?i)Genres)) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val status = getStatus(detailValue(document, "Status"))
        val duration = detailValue(document, "Durasi")?.durationToMinutes()
        val typeText = detailValue(document, "Tipe").orEmpty()

        val episodes = document.select(
            ".daftar-episode li a[href*='episode='], " +
                ".episode-list li a[href*='episode='], " +
                "a[href*='episode=']"
        )
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        val recommendations = document.select("article")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        val isMovie = typeText.contains("movie", true) && episodes.size <= 1
        val hasPlayableData = episodes.isNotEmpty() || document.hasPlayerEvidence()

        if (!hasPlayableData) {
            throw ErrorLoadingException("DramaID playable data not found")
        }

        return if (isMovie) {
            val movieData = episodes.firstOrNull()?.data ?: fixedUrl
            newMovieLoadResponse(title, fixedUrl, TvType.Movie, movieData) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                rating?.let { score = Score.from10(it) }
                duration?.let { this.duration = it }
                this.recommendations = recommendations
            }
        } else {
            val safeEpisodes = episodes.ifEmpty {
                if (document.hasPlayerEvidence()) listOf(newEpisode(fixedUrl) { name = "Movie" }) else emptyList()
            }
            if (safeEpisodes.isEmpty()) throw ErrorLoadingException("DramaID episode not found")
            newTvSeriesLoadResponse(title, fixedUrl, TvType.AsianDrama, safeEpisodes) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                rating?.let { score = Score.from10(it) }
                showStatus = status
                duration?.let { this.duration = it }
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedUrl = normalizeUrl(data, mainUrl) ?: return false
        val page = runCatching {
            app.get(
                fixedUrl,
                referer = "$mainUrl/",
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
        }.getOrNull() ?: return false

        val document = Jsoup.parse(page, fixedUrl)
        val emitted = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        val visited = linkedSetOf<String>()
        var delivered = 0

        suspend fun emitDirectIfMedia(url: String, label: String, refererUrl: String, qualityLabel: String? = null): Boolean {
            if (!url.isMediaUrl() || url.isBlockedMediaOrAd() || !emitted.add(url)) return false
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name ${label.cleanLabel()}",
                    url = url,
                    type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = refererUrl
                    quality = qualityFromLabel(qualityLabel ?: label)
                    headers = mapOf(
                        "Referer" to refererUrl,
                        "Range" to "bytes=0-",
                        "User-Agent" to USER_AGENT,
                    )
                }
            )
            delivered++
            return true
        }

        fun addCandidate(raw: String?, refererUrl: String = fixedUrl) {
            val normalized = normalizeUrl(raw.orEmpty(), refererUrl) ?: return
            if (normalized.isBlockedMediaOrAd()) return
            if (normalized.isMediaUrl() || normalized.isKnownResolverUrl()) {
                queue.add(normalized to refererUrl)
            }
        }

        fun decodeAndCollect(value: String?, refererUrl: String = fixedUrl) {
            val raw = value.orEmpty().trim()
            if (raw.isBlank()) return
            addCandidate(raw, refererUrl)

            decodeBase64(raw)?.let { decoded ->
                collectCandidatesFromText(decoded, refererUrl).forEach { addCandidate(it, refererUrl) }
                val decodedDoc = Jsoup.parse(decoded, refererUrl)
                decodedDoc.select("iframe[src], iframe[data-src], source[src], video[src], a[href], [data-url], [data-src], [data-video], [data-link]")
                    .forEach { element ->
                        addCandidate(element.attr("src"), refererUrl)
                        addCandidate(element.attr("data-src"), refererUrl)
                        addCandidate(element.attr("data-url"), refererUrl)
                        addCandidate(element.attr("data-video"), refererUrl)
                        addCandidate(element.attr("data-link"), refererUrl)
                        addCandidate(element.attr("href"), refererUrl)
                    }
            }
        }

        suspend fun collectFromDocument(doc: Document, html: String, refererUrl: String) {
            doc.select(
                ".streaming_load[data], " +
                    ".resolusi-list li[data], " +
                    ".server-list li[data], " +
                    ".mobius option[value], " +
                    "[data], [data-url], [data-src], [data-video], [data-link], " +
                    "iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                    "source[src], video[src], a[href]"
            ).forEach { element ->
                decodeAndCollect(element.attr("data"), refererUrl)
                decodeAndCollect(element.attr("value"), refererUrl)
                addCandidate(element.attr("data-url"), refererUrl)
                addCandidate(element.attr("data-src"), refererUrl)
                addCandidate(element.attr("data-video"), refererUrl)
                addCandidate(element.attr("data-link"), refererUrl)
                addCandidate(element.attr("data-litespeed-src"), refererUrl)
                addCandidate(element.attr("src"), refererUrl)
                addCandidate(element.attr("href"), refererUrl)
            }

            collectCandidatesFromText(html, refererUrl).forEach { addCandidate(it, refererUrl) }

            for (element in doc.select(".resolusi-list li[data], .server-list li[data], .streaming_load[data]")) {
                val decodedJson = decodeBase64(element.attr("data")) ?: continue
                val resolution = parseResolutionData(decodedJson)
                val servers = resolution?.links.orEmpty().ifEmpty {
                    listOfNotNull(parseServerData(decodedJson))
                }
                val qualityLabel = resolution?.resolution ?: element.text().trim()

                resolution?.subtitle_url
                    ?.takeIf { it.isNotBlank() }
                    ?.let { normalizeUrl(it, refererUrl) }
                    ?.let { subtitleCallback(newSubtitleFile("Indonesian", it)) }

                for (server in servers) {
                    val serverUrl = normalizeUrl(server.url.orEmpty(), refererUrl) ?: continue
                    if (serverUrl.isMediaUrl()) {
                        emitDirectIfMedia(
                            serverUrl,
                            qualityLabel.ifBlank { server.urutan_text ?: "Server" },
                            refererUrl,
                            qualityLabel
                        )
                    } else {
                        addCandidate(serverUrl, refererUrl)
                    }
                }
            }
        }

        collectFromDocument(document, page, fixedUrl)

        var safety = 0
        while (queue.isNotEmpty() && safety++ < 90) {
            val (target, refererUrl) = queue.removeFirst()
            if (!visited.add(target)) continue
            if (target.isBlockedMediaOrAd()) continue

            if (target.isMediaUrl() && !target.isResolverUrl()) {
                if (
                    emitDirectIfMedia(
                        target,
                        target.substringAfterLast("/").substringBefore("?").ifBlank { "Server" },
                        refererUrl,
                        target
                    )
                ) continue
            }

            decodeBerkasDriveId(target)?.let { addCandidate(it, refererUrl) }

            val extractorSuccess = runCatching {
                loadExtractor(target, refererUrl, subtitleCallback) { link ->
                    val linkUrl = link.url
                    if (linkUrl.isNotBlank() && !linkUrl.isBlockedMediaOrAd() && emitted.add(linkUrl)) {
                        delivered++
                        callback(link)
                    }
                }
            }.getOrDefault(false)

            if (extractorSuccess && delivered > 0) continue

            if (target.shouldCrawlResolver()) {
                val nested = runCatching {
                    app.get(
                        target,
                        referer = refererUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                        )
                    ).text
                }.getOrNull() ?: continue

                collectFromDocument(Jsoup.parse(nested, target), nested, target)
            }
        }

        return delivered > 0
    }

    private fun Document.toSearchResults(): List<SearchResponse> {
        return select("article, div.bs, div.listupd article, .post, .item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = select("a[href*='/nonton-']")
            .firstOrNull { isDramaDetailUrl(it.attr("abs:href").ifBlank { it.attr("href") }) }
            ?: return null

        val href = normalizeUrl(link.attr("href"), mainUrl) ?: return null
        if (!isDramaDetailUrl(href)) return null

        val title = listOf(
            link.attr("title"),
            selectFirst("h3.title_post a, h2 a, h3 a, .title a, .tt, .entry-title a")?.text(),
            selectFirst("img[alt]")?.attr("alt"),
            link.text(),
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?.takeIf { it.isValidDramaTitle() }
            ?: return null

        val poster = selectFirst(".thumbnail img, img, .poster img")?.imageUrl()
        val type = if (text().contains("Episode:", true) || text().contains("Episode", true)) TvType.AsianDrama else TvType.Movie

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
            }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = normalizeUrl(attr("href"), mainUrl) ?: return null
        if (!href.contains("episode=", true) && !isDramaDetailUrl(href)) return null

        val title = attr("title")
            .ifBlank { selectFirst(".title_episode, .title_episode_2")?.text().orEmpty() }
            .ifBlank { text() }
            .replace(Regex("""\s+"""), " ")
            .trim()

        val episodeNumber = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(title.ifBlank { href })
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""[?&]episode=(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        return newEpisode(href) {
            name = episodeNumber?.let { "Episode $it" } ?: title.ifBlank { "Episode" }
            episode = episodeNumber
        }
    }

    private fun Document.isValidDetailPage(url: String): Boolean {
        if (!isDramaDetailUrl(url)) return false
        if (location().isNotBlank() && !isDramaDetailUrl(location()) && location().trimEnd('/') == mainUrl.trimEnd('/')) return false
        val pageTitle = selectFirst("title")?.text().orEmpty()
        if (pageTitle.equals("DramaID - Nonton dan Download Drama Korea", true)) return false
        if (pageTitle.contains("Nonton dan Download Drama Korea", true) && detailValue(this, "Judul").isNullOrBlank()) return false

        val hasDetailInfo = select("#informasi li, .info li").isNotEmpty()
        val hasDetailTitle = !detailValue(this, "Judul").isNullOrBlank() ||
            select("h1.single-title, h1.single_h2, h1.entry-title, h1").isNotEmpty()
        val hasEpisodeOrPlayer = select(
            ".daftar-episode li a[href*='episode='], " +
                ".episode-list li a[href*='episode='], " +
                ".resolusi-list li[data], .server-list li[data], .streaming_load[data], " +
                "iframe[src], video[src], source[src]"
        ).isNotEmpty()

        return hasDetailTitle && (hasDetailInfo || hasEpisodeOrPlayer)
    }

    private fun Document.hasPlayerEvidence(): Boolean {
        return select(
            ".resolusi-list li[data], .server-list li[data], .streaming_load[data], " +
                ".mobius option[value], iframe[src], video[src], source[src], [data-url], [data-src], [data-video], [data-link]"
        ).isNotEmpty() || collectCandidatesFromText(html(), baseUri()).isNotEmpty()
    }

    private fun detailValue(document: Document, label: String): String? {
        return document.select("#informasi li, .info li")
            .firstOrNull { item ->
                item.selectFirst("strong")?.text()
                    ?.replace(":", "")
                    ?.trim()
                    ?.equals(label, true) == true
            }
            ?.let { item ->
                val clone = item.clone()
                clone.select("strong").remove()
                clone.text().trim().ifBlank { null }
            }
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("abs:content"),
            attr("abs:data-src"),
            attr("abs:data-lazy-src"),
            attr("abs:srcset").substringBefore(" "),
            attr("abs:src"),
            attr("content"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("srcset").substringBefore(" "),
            attr("src"),
        ).firstOrNull { it.isNotBlank() }?.let { normalizeUrl(it, mainUrl) ?: fixUrl(it) }
    }

    private fun pageUrl(pattern: String, page: Int): String {
        if (page > 1) return pattern.format(page)
        return pattern
            .replace("/page/%d/", "/")
            .replace("page/%d/", "")
            .replace("%d", "1")
    }

    private fun isDramaDetailUrl(url: String): Boolean {
        val normalized = normalizeUrl(url, mainUrl) ?: return false
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        return host.endsWith("drama-id.com") && path.contains("/nonton-") && path != "/nonton-"
    }

    private fun normalizeUrl(raw: String, baseUrl: String): String? {
        val clean = raw.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .takeIf { it.isNotBlank() && !it.startsWith("javascript:", true) && !it.startsWith("data:", true) }
            ?: return null

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    private fun collectCandidatesFromText(text: String, baseUrl: String): List<String> {
        val output = linkedSetOf<String>()
        val clean = text
            .jsonUrlDecode()
            .replace("&amp;", "&")

        Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value }
            .filter { it.isKnownResolverUrl() || it.isMediaUrl() }
            .forEach { output.add(it) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .filter { it.isKnownResolverUrl() || it.isMediaUrl() }
            .forEach { output.add(it) }

        Regex(
            """(?:file|src|url|source|video|videoUrl|streamUrl|stream_url|downloadUrl|download_url|data-url|data-src|hls|hlsUrl|hls_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
            .findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .mapNotNull { normalizeUrl(it, baseUrl) }
            .filter { it.isKnownResolverUrl() || it.isMediaUrl() }
            .forEach { output.add(it) }

        return output.toList()
    }

    private fun parseResolutionData(json: String): ResolutionData? {
        return runCatching {
            val obj = JSONObject(json)
            val links = mutableListOf<ServerData>()
            val array = obj.optJSONArray("links")
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    links.add(item.toServerData())
                }
            }

            ResolutionData(
                resolution = obj.optStringOrNull("resolution"),
                subtitle_url = obj.optStringOrNull("subtitle_url"),
                links = links.takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

    private fun parseServerData(json: String): ServerData? {
        return runCatching {
            JSONObject(json).toServerData().takeIf { !it.url.isNullOrBlank() }
        }.getOrNull()
    }

    private fun JSONObject.toServerData(): ServerData {
        return ServerData(
            url = optStringOrNull("url"),
            mode = optStringOrNull("mode"),
            urutan_text = optStringOrNull("urutan_text"),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        return optString(key).trim().ifBlank { null }
    }

    private fun decodeBerkasDriveId(url: String): String? {
        if (!url.contains("dl.berkasdrive.com/streaming", true)) return null
        return url.substringAfter("?", "")
            .split("&")
            .firstOrNull { it.substringBefore("=") == "id" }
            ?.substringAfter("=")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?.let(::decodeBase64)
            ?.let { normalizeUrl(it, mainUrl) }
    }

    private fun decodeBase64(value: String): String? {
        val clean = value.trim().replace("\\s".toRegex(), "")
        if (clean.isBlank()) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded)) }.getOrNull()
    }

    private fun getStatus(value: String?): ShowStatus? {
        return when {
            value == null -> null
            value.contains("ongoing", true) -> ShowStatus.Ongoing
            value.contains("complete", true) || value.contains("tamat", true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun extractYear(value: String): Int? {
        return Regex("""(19|20)\d{2}""").find(value)?.value?.toIntOrNull()
    }

    private fun String.toScore(): Double? {
        return Regex("""\d+(?:\.\d+)?""").find(this)?.value?.toDoubleOrNull()
    }

    private fun String.durationToMinutes(): Int? {
        val hours = Regex("""(\d+)\s*hr""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    private fun qualityFromLabel(value: String): Int {
        return Regex("""\b(2160|1440|1080|720|480|360|240)p?\b""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun String.cleanTitle(): String {
        return Jsoup.parse(this).text()
            .replace(Regex("""(?i)^Nonton\s+(?:Drakor|Drama)\s+"""), "")
            .replace(Regex("""(?i)\s+Subtitle\s+Indonesia.*$"""), "")
            .replace(Regex("""(?i)\s+Sub\s+Indo.*$"""), "")
            .replace(Regex("""(?i)\s+Episode\s+\d+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.isValidDramaTitle(): Boolean {
        return isNotBlank() &&
            !equals("DramaID", true) &&
            !equals("DramaID - Nonton dan Download Drama Korea", true) &&
            !contains("Nonton dan Download Drama Korea", true)
    }

    private fun String.cleanLabel(): String {
        return replace(Regex("""\s+"""), " ").trim().ifBlank { "Server" }
    }

    private fun String.jsonUrlDecode(): String {
        return replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("\\u003f", "?")
            .replace("\\u003F", "?")
            .replace("\\u002F", "/")
    }

    private fun String.isMediaUrl(): Boolean {
        return Regex("""(?i)\.(mp4|m3u8)(?:$|[?#&])""").containsMatchIn(this)
    }

    private fun String.isResolverUrl(): Boolean {
        return contains("stordl.halahgan.com", true) || contains("dl.berkasdrive.com/streaming", true)
    }

    private fun String.isKnownResolverUrl(): Boolean {
        val value = lowercase()
        return value.contains("stordl.halahgan.com") ||
            value.contains("dl.berkasdrive.com") ||
            value.contains("berkasdrive.com") ||
            value.contains("halahgan.com") ||
            value.contains("streaming") ||
            value.contains("/embed/") ||
            value.contains("/player/") ||
            value.contains("filemoon") ||
            value.contains("streamwish") ||
            value.contains("wishfast") ||
            value.contains("dood") ||
            value.contains("streamtape") ||
            value.contains("vidhide") ||
            value.contains("vidguard") ||
            value.contains("voe.") ||
            value.contains("mixdrop") ||
            value.contains("mp4upload") ||
            value.contains("lulustream") ||
            value.contains("lulu") ||
            value.contains("krakenfiles") ||
            value.contains("acefile") ||
            value.contains("drive.google") ||
            value.contains("ok.ru")
    }

    private fun String.shouldCrawlResolver(): Boolean {
        val value = lowercase()
        return value.contains("stordl.halahgan.com") ||
            value.contains("dl.berkasdrive.com") ||
            value.contains("berkasdrive.com") ||
            value.contains("halahgan.com") ||
            value.contains("/embed/") ||
            value.contains("/player/") ||
            value.contains("streaming")
    }

    private fun String.isBlockedMediaOrAd(): Boolean {
        val value = lowercase()
        return value.isBlank() ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("adsbygoogle") ||
            value.contains("googletagmanager") ||
            value.contains("google-analytics") ||
            value.contains("histats") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("whatsapp") ||
            value.contains("mailto:") ||
            value.endsWith(".js") ||
            value.endsWith(".css") ||
            value.endsWith(".jpg") ||
            value.endsWith(".jpeg") ||
            value.endsWith(".png") ||
            value.endsWith(".webp") ||
            value.endsWith(".gif") ||
            value.endsWith(".svg")
    }

    data class ResolutionData(
        val resolution: String? = null,
        val subtitle_url: String? = null,
        val links: List<ServerData>? = null,
    )

    data class ServerData(
        val url: String? = null,
        val mode: String? = null,
        val urutan_text: String? = null,
    )
}
