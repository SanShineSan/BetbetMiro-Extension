package com.sad25kag.anoboy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Anoboy : MainAPI() {
    override var mainUrl = "https://anoboy.be"
    override var name = "AnoBoy"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "anime-list/" to "Anime List",
        "category/anime/ongoing/" to "Anime Ongoing",
        "category/donghua/" to "Donghua",
        "category/anime-movie/" to "Movie",
        "category/tokusatsu/" to "Tokusatsu",
        "category/live-action-movie/" to "Live Action",
        "category/studio-ghibli/" to "Studio Ghibli",
        "category/action/" to "Action",
        "category/adventure/" to "Adventure",
        "category/comedy/" to "Comedy",
        "category/drama/" to "Drama",
        "category/fantasy/" to "Fantasy",
        "category/horror/" to "Horror",
        "category/isekai/" to "Isekai",
        "category/martial-arts/" to "Martial Arts",
        "category/romance/" to "Romance",
        "category/school/" to "School",
        "category/shounen/" to "Shounen",
        "category/slice-of-life/" to "Slice of Life",
        "category/sports/" to "Sports",
        "category/supernatural/" to "Supernatural"
    )

    private data class CardData(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType,
        val episode: Int?
    )

    private fun buildPageUrl(data: String, page: Int): String {
        val raw = data.trim().trimStart('/')
        if (raw.isBlank()) return if (page <= 1) mainUrl else "$mainUrl/page/$page/"

        val path = raw.trimEnd('/')
        return if (page <= 1) {
            "$mainUrl/$path/"
        } else {
            "$mainUrl/$path/page/$page/"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = buildPageUrl(request.data, page)
        val document = app.get(pageUrl, headers = defaultHeaders()).document

        val items = collectCards(document)
            .distinctBy { it.url }
            .map { it.toSearchResponse() }

        val hasNext = document.selectFirst(
            ".wp-pagenavi a.nextpostslink, a.next, a[rel=next], a[href*='/page/${page + 1}/']"
        ) != null

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery", headers = defaultHeaders()).document

        return collectCards(document)
            .distinctBy { it.url }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeAnoboyUrl(url)
        val document = app.get(fixedUrl, headers = defaultHeaders()).document

        val pageTitle = cleanTitle(
            document.selectFirst("h1.entry-title, h1, h2.entry-title, .pagetitle h1")
                ?.text()
                ?.trim()
                .orEmpty()
                .ifBlank {
                    document.title()
                        .substringBefore("–")
                        .substringBefore("- anoBoy")
                        .substringBefore("AnoBoy")
                        .trim()
                }
        ).ifBlank {
            throw ErrorLoadingException("Judul tidak ditemukan")
        }

        val poster = document.selectFirst(
            ".sisi.entry-content img, .deskripsi img, div.column-three-fourth > img, " +
                "div.column-content > img, div.bigcontent img, div.entry-content img, " +
                ".thumb img, .poster img, .info-content img, article img"
        )?.imageAttr()?.let { fixUrlNull(it) }

        val description = document.selectFirst(".contentdeks")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.select("div.entry-content p, .sisi.entry-content p")
                .joinToString("\n") { it.text() }
                .trim()

        val episodeList = parseEpisodeList(document, fixedUrl)
        val serverEpisodes = buildServerEpisodes(document, fixedUrl)
        val episodes = when {
            episodeList.isNotEmpty() -> episodeList
            serverEpisodes.isNotEmpty() -> serverEpisodes
            else -> emptyList()
        }

        val tags = document.select(
            "a[href*='/category/'], .genres a, .genre-info a, .info-content a[href*='/category/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinct()

        val recommendations = collectRecommendations(document)
            .distinctBy { it.url }
            .map { it.toSearchResponse() }

        val type = when {
            fixedUrl.contains("/anime-movie/", true) || pageTitle.contains("movie", true) -> TvType.AnimeMovie
            pageTitle.contains("ova", true) || pageTitle.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(pageTitle, fixedUrl, type) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newMovieLoadResponse(pageTitle, fixedUrl, type, encodeEpisodeData(fixedUrl, fixedUrl)) {
                posterUrl = poster
                plot = description
                this.tags = tags
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
        val decoded = decodeEpisodeData(data)
        val startUrl = decoded.second
        val startReferer = decoded.first ?: mainUrl

        val discovered = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        val crawled = mutableSetOf<String>()
        val emittedKeys = linkedSetOf<String>()
        var emitted = false

        fun emitOnce(link: ExtractorLink) {
            val key = "${link.source.lowercase()}|${canonicalLink(link.url)}"
            if (emittedKeys.add(key)) {
                emitted = true
                callback(link)
            }
        }

        fun queueUrl(raw: String?, base: String) {
            val resolved = resolvePlayerUrl(raw, base) ?: return
            if (isBadUrl(resolved)) return
            if (!isPlayerCandidate(resolved)) return
            if (discovered.add(resolved)) queue.add(resolved to base)
        }

        if (startUrl.startsWith("multi::")) {
            startUrl.removePrefix("multi::")
                .split("||")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { queueUrl(it, startReferer) }
        } else {
            queueUrl(startUrl, startReferer)

            val startDoc = runCatching {
                app.get(
                    startUrl,
                    referer = startReferer,
                    headers = defaultHeaders(startReferer),
                    timeout = 20L
                ).document
            }.getOrNull()

            if (startDoc != null) {
                extractFromDocument(startDoc, startUrl, ::queueUrl)
            }
        }

        var safety = 0
        while (queue.isNotEmpty() && safety++ < 45) {
            val (next, referer) = queue.removeFirst()
            if (isBadUrl(next)) continue

            if (isDirectMedia(next)) {
                emitDirect(next, referer, ::emitOnce)
                continue
            }

            if (!crawled.add(next)) continue

            val extracted = runCatching {
                loadExtractor(next, referer, subtitleCallback, ::emitOnce)
            }.getOrDefault(false)

            if (extracted && emitted) continue

            val response = runCatching {
                app.get(
                    next,
                    referer = referer,
                    headers = defaultHeaders(referer),
                    timeout = 20L
                )
            }.getOrNull() ?: continue

            val contentLength = response.headers["Content-Length"]?.toLongOrNull()
            if (contentLength != null && contentLength > 5_000_000L) continue

            val body = runCatching { response.text }.getOrNull() ?: continue
            val nestedDoc = Jsoup.parse(body, next)

            extractFromDocument(nestedDoc, next, ::queueUrl)
            extractFromText(body, next, ::queueUrl)
        }

        discovered
            .filter { isDirectMedia(it) }
            .forEach { direct -> emitDirect(direct, startReferer, ::emitOnce) }

        return emitted
    }

    private fun collectCards(document: Document): List<CardData> {
        val selectors = listOf(
            "article.bs",
            "div.bs",
            "div.listupd article",
            "div.listupd div.bs",
            "a[href]:has(div.amv)",
            "a[href]:has(div#amv)",
            ".venz ul li",
            ".latest a[href]",
            ".listupd a[href]",
            ".bixbox a[href]"
        ).joinToString(", ")

        return document.select(selectors)
            .mapNotNull { it.toCardData() }
            .filterNot { isNavigationTitle(it.title) }
            .distinctBy { it.url }
    }

    private fun collectRecommendations(document: Document): List<CardData> {
        return document.select(
            "a[href]:has(div.amv), a[href]:has(div#amv), div.listupd article.bs, article.bs, div.bs, .bixbox a[href]"
        ).mapNotNull { it.toCardData() }
            .filterNot { isNavigationTitle(it.title) }
    }

    private fun Element.toCardData(): CardData? {
        val href = when {
            tagName().equals("a", true) -> attr("href")
            else -> selectFirst("a[href]")?.attr("href").orEmpty()
        }.trim()

        val fixedHref = normalizeAnoboyUrl(href)
        if (!isContentUrl(fixedHref)) return null

        val title = cleanTitle(
            attr("title").trim().ifBlank {
                selectFirst("h3.ibox1, h3.ibox, h2, h3, .tt, .entry-title")?.text()?.trim().orEmpty()
            }.ifBlank {
                selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }.ifBlank {
                text().trim()
            }
        )

        if (title.length < 2 || isNavigationTitle(title)) return null

        val episode = parseEpisodeNumber(title) ?: parseEpisodeNumber(fixedHref)
        val type = when {
            fixedHref.contains("/anime-movie/", true) || title.contains("movie", true) -> TvType.AnimeMovie
            title.contains("ova", true) || title.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        val poster = selectFirst("img")?.imageAttr()?.let { fixUrlNull(it) }

        return CardData(
            title = title,
            url = fixedHref,
            poster = poster,
            type = type,
            episode = episode
        )
    }

    private fun CardData.toSearchResponse(): SearchResponse {
        return newAnimeSearchResponse(title, url, type) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private fun parseEpisodeList(document: Document, referer: String): List<Episode> {
        val anchors = document.select(
            "div.singlelink ul.lcp_catlist li a, div.eplister ul li a, " +
                "div.bixbox.bxcl ul li a, .episodelist ul li a, ul li a[href*='episode']"
        ).filter {
            val href = normalizeAnoboyUrl(it.attr("href"))
            isContentUrl(href) && !href.equals(referer, true)
        }

        return anchors
            .mapNotNull { anchor ->
                val href = normalizeAnoboyUrl(anchor.attr("href"))
                val title = cleanTitle(anchor.text().trim().ifBlank {
                    href.trimEnd('/').substringAfterLast('/').replace("-", " ")
                })
                val episode = parseEpisodeNumber(title) ?: parseEpisodeNumber(href)
                if (episode == null && title.length < 2) return@mapNotNull null

                newEpisode(encodeEpisodeData(referer, href)) {
                    name = title.ifBlank { "Episode ${episode ?: 1}" }
                    this.episode = episode
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun buildServerEpisodes(document: Document, referer: String): List<Episode> {
        val urls = linkedSetOf<String>()
        fun add(raw: String?) {
            val resolved = resolvePlayerUrl(raw, referer) ?: return
            if (isBadUrl(resolved)) return
            if (!isPlayerCandidate(resolved)) return
            urls.add(resolved)
        }

        document.select(
            "iframe[src], iframe[data-src], iframe[data-litespeed-src], source[src], video[src], embed[src], object[data]"
        ).forEach { element ->
            add(element.iframeAttr())
            add(element.attr("src"))
            add(element.attr("data-src"))
            add(element.attr("data-litespeed-src"))
            add(element.attr("data"))
        }

        document.select(
            "a[data-video], a[data-src], a[data-url], a[data-iframe], [data-video], [data-src], [data-url], [data-iframe], option[value]"
        ).forEach { element ->
            add(element.attr("data-video"))
            add(element.attr("data-src"))
            add(element.attr("data-url"))
            add(element.attr("data-iframe"))
            add(element.attr("value"))
        }

        extractFromText(document.html(), referer) { raw, base -> add(resolvePlayerUrl(raw, base)) }

        if (urls.isEmpty()) return emptyList()

        val episodeNumber = parseEpisodeNumber(document.location()) ?: parseEpisodeNumber(document.title()) ?: 1
        val payload = if (urls.size == 1) urls.first() else "multi::" + urls.joinToString("||")

        return listOf(
            newEpisode(encodeEpisodeData(referer, payload)) {
                name = "Episode $episodeNumber"
                episode = episodeNumber
            }
        )
    }

    private fun extractFromDocument(
        document: Document,
        baseUrl: String,
        queueUrl: (String?, String) -> Unit
    ) {
        document.select(
            "iframe[src], iframe[data-src], iframe[data-litespeed-src], iframe[data-lazy-src], " +
                "source[src], video[src], video[data-src], embed[src], object[data]"
        ).forEach { element ->
            queueUrl(element.iframeAttr(), baseUrl)
            queueUrl(element.attr("src"), baseUrl)
            queueUrl(element.attr("data-src"), baseUrl)
            queueUrl(element.attr("data-litespeed-src"), baseUrl)
            queueUrl(element.attr("data-lazy-src"), baseUrl)
            queueUrl(element.attr("data"), baseUrl)
        }

        document.select(
            "a[href], button, [data-video], [data-src], [data-url], [data-iframe], [data-embed], [data-file], [data-link], option[value]"
        ).forEach { element ->
            queueUrl(element.attr("href"), baseUrl)
            queueUrl(element.attr("data-video"), baseUrl)
            queueUrl(element.attr("data-src"), baseUrl)
            queueUrl(element.attr("data-url"), baseUrl)
            queueUrl(element.attr("data-iframe"), baseUrl)
            queueUrl(element.attr("data-embed"), baseUrl)
            queueUrl(element.attr("data-file"), baseUrl)
            queueUrl(element.attr("data-link"), baseUrl)
            queueUrl(element.attr("value"), baseUrl)

            val value = element.attr("value")
            if (value.isNotBlank()) {
                runCatching {
                    val decoded = decodeBase64String(value.replace("\\s".toRegex(), ""))
                    if (decoded.isNotBlank()) {
                        extractFromText(decoded, baseUrl, queueUrl)
                        val decodedDoc = Jsoup.parse(decoded, baseUrl)
                        decodedDoc.select("iframe[src], source[src], video[src], a[href]").forEach { nested ->
                            queueUrl(nested.iframeAttr(), baseUrl)
                            queueUrl(nested.attr("src"), baseUrl)
                            queueUrl(nested.attr("href"), baseUrl)
                        }
                    }
                }
            }
        }

        extractFromText(document.html(), baseUrl, queueUrl)
    }

    private fun extractFromText(
        text: String,
        baseUrl: String,
        queueUrl: (String?, String) -> Unit
    ) {
        val cleaned = text
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")

        Regex("""https?://[^"'<>\\\s]+""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .forEach { queueUrl(it.value, baseUrl) }

        Regex("""//[^"'<>\\\s]+\.(?:m3u8|mp4|webm|mkv)(?:\?[^"'<>\\\s]*)?""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .forEach { queueUrl("https:${it.value}", baseUrl) }

        Regex(
            """(?:file|src|url|source|video|data-video|data-src|data-url|hls|hlsUrl|hls_url|stream|streamUrl|stream_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(cleaned).forEach { queueUrl(it.groupValues[1], baseUrl) }

        Regex("""["']((?:/uploads/|/embed/|/player/|/api/|/stream/)[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .forEach { queueUrl(it.groupValues[1], baseUrl) }

        Regex("""https?%3A%2F%2F[^"'<>\\\s]+""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .forEach { match ->
                val decoded = runCatching { URLDecoder.decode(match.value, "UTF-8") }.getOrDefault(match.value)
                queueUrl(decoded, baseUrl)
            }
    }

    private fun emitDirect(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isDirectMedia(link) || isBadUrl(link)) return false

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isM3u8Media(link)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(link).takeIf { it != Qualities.Unknown.value }
                    ?: when {
                        link.contains("1080", true) -> Qualities.P1080.value
                        link.contains("720", true) -> Qualities.P720.value
                        link.contains("480", true) -> Qualities.P480.value
                        link.contains("360", true) -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "*/*"
                )
            }
        )

        return true
    }

    private fun normalizeAnoboyUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return mainUrl
        val fixed = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$mainUrl$trimmed"
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            else -> "$mainUrl/${trimmed.trimStart('/')}"
        }

        return fixed
            .replace("https://ww1.anoboy.boo", mainUrl, ignoreCase = true)
            .replace("http://ww1.anoboy.boo", mainUrl, ignoreCase = true)
            .replace("https://www1.anoboy.boo", mainUrl, ignoreCase = true)
            .replace("http://www1.anoboy.boo", mainUrl, ignoreCase = true)
            .replace("https://anoboy.watch", mainUrl, ignoreCase = true)
            .replace("http://anoboy.watch", mainUrl, ignoreCase = true)
            .replace("https://www.anoboy.be", mainUrl, ignoreCase = true)
            .replace("http://www.anoboy.be", mainUrl, ignoreCase = true)
    }

    private fun resolvePlayerUrl(raw: String?, base: String): String? {
        val clean = cleanCandidate(raw)
        if (!isValidCandidate(clean)) return null

        return runCatching {
            when {
                clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("/") -> URI(base).resolve(clean).toString()
                else -> URI(base).resolve(clean).toString()
            }
        }.getOrNull()
    }

    private fun cleanCandidate(raw: String?): String {
        return raw.orEmpty()
            .trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#038;", "&")
            .replace(" ", "%20")
    }

    private fun isValidCandidate(clean: String): Boolean {
        return clean.isNotBlank() &&
            clean != "#" &&
            !clean.equals("none", true) &&
            !clean.equals("null", true) &&
            !clean.startsWith("javascript", true) &&
            !clean.startsWith("about:", true) &&
            !clean.startsWith("data:", true) &&
            !clean.startsWith("blob:", true) &&
            !clean.startsWith("mailto:", true)
    }

    private fun isContentUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith(mainUrl.lowercase()) &&
            !lower.contains("/category/") &&
            !lower.contains("/tag/") &&
            !lower.contains("/season/") &&
            !lower.contains("/studio/") &&
            !lower.contains("/anime-list") &&
            !lower.contains("/az-list") &&
            !lower.contains("/page/")
    }

    private fun isPlayerCandidate(url: String): Boolean {
        val lower = url.lowercase()

        if (isBadUrl(url)) return false
        if (isDirectMedia(url)) return true

        return lower.contains("anoboy.be") ||
            lower.contains("/uploads/") ||
            lower.contains("mirrored.to") ||
            lower.contains("adsbatch") ||
            lower.contains("acbatch") ||
            lower.contains("yupbatch") ||
            lower.contains("yup/data.php") ||
            lower.contains("adsbatch720.php") ||
            lower.contains("stream.php") ||
            lower.contains("embed.php") ||
            lower.contains("/api/") ||
            lower.contains("blogger.com/video.g") ||
            lower.contains("blogger.com/_/bloggervideoplayerui") ||
            lower.contains("blogger.googleusercontent.com") ||
            lower.contains("video-downloads.googleusercontent.com") ||
            lower.contains("googlevideo.com/videoplayback") ||
            lower.contains("youtube.googleapis.com/embed") ||
            lower.contains("youtube.com/embed") ||
            lower.contains("viiwbpyl.com/h/") ||
            lower.contains("viiwbpyl.com/embed") ||
            lower.contains("yourupload.com/") ||
            lower.contains("streamtape") ||
            lower.contains("dood") ||
            lower.contains("filemoon") ||
            lower.contains("vidhide") ||
            lower.contains("vidguard") ||
            lower.contains("voe.sx") ||
            lower.contains("mixdrop") ||
            lower.contains("mp4upload") ||
            lower.contains("short.icu") ||
            lower.contains("abyss.to") ||
            lower.contains("abysscdn") ||
            lower.contains("ok.ru") ||
            lower.contains("drive.google.com")
    }

    private fun isBadUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") ||
            lower.contains("twitter.com") ||
            lower.contains("x.com/") ||
            lower.contains("telegram") ||
            lower.contains("whatsapp") ||
            lower.contains("mailto:") ||
            lower.contains("adsbygoogle") ||
            lower.contains("googlesyndication") ||
            lower.contains("doubleclick") ||
            lower.contains("analytics") ||
            lower.contains("histats") ||
            lower.contains("safebrowsing") ||
            lower.contains("beacons.gcp.gvt2.com") ||
            lower.contains("dns.google") ||
            lower.contains("cloudflareinsights") ||
            lower.contains("google-analytics") ||
            lower.contains("googletagmanager") ||
            lower.contains("wp-json") ||
            lower.contains("/wp-content/themes/") ||
            lower.endsWith(".css") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".svg")
    }

    private fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase()
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault(url.substringBefore("?").lowercase())

        return path.endsWith(".m3u8") ||
            path.endsWith(".mp4") ||
            path.endsWith(".webm") ||
            path.endsWith(".mkv") ||
            path.endsWith(".mov") ||
            path.endsWith(".ts") ||
            lower.contains("googlevideo.com/videoplayback") ||
            lower.contains("redirector.googlevideo.com/videoplayback")
    }

    private fun isM3u8Media(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault(url.substringBefore("?").lowercase())
        return path.endsWith(".m3u8") || url.lowercase().contains(".m3u8?")
    }

    private fun canonicalLink(link: String): String {
        return runCatching {
            val uri = URI(link)
            val host = uri.host.orEmpty().removePrefix("www.").lowercase()
            val path = uri.path.orEmpty().trimEnd('/').lowercase()
            "$host$path"
        }.getOrDefault(link.substringBefore("?").trimEnd('/').lowercase())
    }

    private fun parseEpisodeNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""(?i)\b(?:episode|eps|ep)\s*[-:]?\s*(\d+)\b""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?i)(?:episode|eps|ep)-(\d+)""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""(?i)\[(?:streaming|download)\]"""), "")
            .replace(Regex("""(?i)\b(?:streaming|download|subtitle indonesia|sub indo|nonton)\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '|')
    }

    private fun isNavigationTitle(title: String): Boolean {
        val lower = title.lowercase().trim()
        return lower in setOf(
            "home",
            "az list",
            "anime list",
            "genre all",
            "season all",
            "studio all",
            "status all",
            "type all",
            "order by all",
            "search",
            "download",
            "prev",
            "next",
            "all episodes"
        )
    }

    private fun encodeEpisodeData(referer: String?, payload: String): String {
        if (referer.isNullOrBlank()) return payload
        return "anoboyref::$referer:::$payload"
    }

    private fun decodeEpisodeData(data: String): Pair<String?, String> {
        val prefix = "anoboyref::"
        if (!data.startsWith(prefix)) return null to normalizeAnoboyUrl(data)

        val parts = data.removePrefix(prefix).split(":::", limit = 2)
        return if (parts.size == 2) {
            normalizeAnoboyUrl(parts[0]) to parts[1]
        } else {
            null to data
        }
    }

    private fun decodeBase64String(value: String): String {
        val clean = value.trim()
        val candidates = listOf(
            clean,
            clean.replace('-', '+').replace('_', '/'),
            clean + "=".repeat((4 - clean.length % 4) % 4),
            clean.replace('-', '+').replace('_', '/') + "=".repeat((4 - clean.length % 4) % 4)
        ).distinct()

        for (candidate in candidates) {
            val decoded = runCatching {
                String(Base64.decode(candidate, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }

        return ""
    }

    private fun defaultHeaders(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
    }

    private fun Element.imageAttr(): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        return image?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("abs:data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("abs:srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
            ?: image?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
    }

    private fun Element?.iframeAttr(): String? {
        return this?.attr("data-litespeed-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("src")?.takeIf { it.isNotBlank() }
    }
}
