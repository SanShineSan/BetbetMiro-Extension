package com.byayzen

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KissKH : MainAPI() {
    override var mainUrl = "https://kisskh.buzz"
    override var name = "KissKH"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/category/latest-update/" to "Latest Update",
        "/category/south-korean/" to "Top K-Drama",
        "/category/china/" to "Top C-Drama",
        "/category/lgbtq/" to "Top LGBTQ+",
        "/category/ongoing/" to "Airing Now",
        "/category/hollywood/" to "Hollywood",
        "/category/thailand/" to "Thailand",
        "/category/japan/" to "Japan",
        "/category/movie/" to "Movies",
        "/category/completed/" to "Completed",
        "/category/upcoming/" to "Upcoming"
    )

    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val bloggerBlogId = "1422331367239821646"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = categoryPageUrl(request.data, page)
        val document = app.get(url, referer = mainUrl).document
        val results = parsePostCards(document)

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = results,
                isHorizontalImages = true
            ),
            hasNext = document.selectFirst("a.next.page-numbers, a.page-numbers.next") != null || results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf(
                "action" to "fetch_live_movies",
                "keyword" to query,
                "filter" to "all",
                "page" to "1",
                "is_popular" to "0"
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to userAgent
            ),
            referer = mainUrl
        ).text

        return parseSearchCards(Jsoup.parse(html, mainUrl)).ifEmpty {
            val document = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}", referer = mainUrl).document
            parsePostCards(document)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url.substringBefore("?"), referer = mainUrl).document
        val title = document.selectFirst("h1.wp-block-post-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - Kisskh")?.trim()
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: document.selectFirst("figure.post-backdrop img, img.wp-post-image")?.absUrl("src")?.trim()
        val plot = document.selectFirst("#accordion-item-2-panel p, .wp-block-post-content p")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val tags = document.select(".taxonomy-category a").map { it.text().trim() }.filter { it.isNotBlank() }
        val status = document.selectFirst(".release-status")?.text()?.trim()
        val year = document.selectFirst(".release-year")?.text()?.trim()?.toIntOrNull()
        val totalEpisodes = document.selectFirst(".total-episode")?.text()?.episodeNumber() ?: 1
        val bloggerPostId = document.selectFirst("#Sdachkun1[data-post-id], [data-post-id]")?.attr("data-post-id")?.trim()
            ?: throw ErrorLoadingException("Missing Blogger post id")

        val episodes = (1..totalEpisodes.coerceAtLeast(1)).map { episodeNumber ->
            newEpisode(
                encodeLoadData(
                    KissLoadData(
                        pageUrl = url.substringBefore("?"),
                        title = title,
                        episode = episodeNumber,
                        bloggerPostId = bloggerPostId,
                        totalEpisodes = totalEpisodes.coerceAtLeast(1)
                    )
                )
            ) {
                this.name = if (totalEpisodes > 1) "Episode $episodeNumber" else "Movie"
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url.substringBefore("?"),
            if (totalEpisodes > 1) TvType.TvSeries else TvType.Movie,
            episodes
        ) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)
            this.year = year
            this.plot = plot
            this.tags = tags
            this.showStatus = when {
                status.equals("completed", ignoreCase = true) -> ShowStatus.Completed
                status.equals("ongoing", ignoreCase = true) -> ShowStatus.Ongoing
                else -> null
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = decodeLoadData(data) ?: KissLoadData(pageUrl = data.substringBefore("?"))
        val pageUrl = loadData.pageUrl.substringBefore("?")
        val episodeUrl = "$pageUrl?episode=${loadData.episode}"
        val bloggerPostId = loadData.bloggerPostId ?: run {
            app.get(pageUrl, referer = mainUrl).document
                .selectFirst("#Sdachkun1[data-post-id], [data-post-id]")
                ?.attr("data-post-id")
                ?.trim()
        } ?: return false

        val content = fetchBloggerContent(bloggerPostId, pageUrl) ?: return false
        val episodeSources = parseBloggerContent(content)
        val selectedSources = collectEpisodeSources(episodeSources, loadData.episode, loadData.totalEpisodes)
        if (selectedSources.isEmpty()) return false

        selectedSources.flatMap { it.subtitles }
            .distinctBy { it.url }
            .forEach { subtitle ->
                subtitleCallback.invoke(newSubtitleFile(subtitle.label, subtitle.url))
            }

        var loaded = false
        selectedSources.flatMap { it.videos }.distinct().amap { link ->
            val cleanLink = link.trim()
            if (cleanLink.isBlank()) return@amap
            val fixedLink = fixUrl(cleanLink)
            when {
                fixedLink.contains(".m3u8", ignoreCase = true) -> {
                    M3u8Helper.generateM3u8(
                        name,
                        fixedLink,
                        referer = episodeUrl,
                        headers = mapOf("Origin" to mainUrl)
                    ).forEach { extractorLink ->
                        loaded = true
                        callback.invoke(extractorLink)
                    }
                }
                fixedLink.contains(".mp4", ignoreCase = true) || fixedLink.contains(".m4v", ignoreCase = true) -> {
                    loaded = true
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            url = fixedLink,
                            INFER_TYPE
                        ) {
                            this.referer = episodeUrl
                            this.quality = Qualities.P720.value
                            this.headers = mapOf("Referer" to episodeUrl)
                        }
                    )
                }
                else -> {
                    loaded = loadExtractor(fixedLink, episodeUrl, subtitleCallback, callback) || loaded
                }
            }
        }

        return loaded
    }

    private fun categoryPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim().ifBlank { "/category/latest-update/" }
        val base = if (cleanPath.startsWith("http")) cleanPath else "$mainUrl$cleanPath"
        return if (page <= 1) base else base.trimEnd('/') + "/page/$page/"
    }

    private fun parsePostCards(document: Document): List<SearchResponse> {
        return document.select("li.wp-block-post").mapNotNull { card ->
            val titleElement = card.selectFirst("h2.wp-block-post-title a[href], .wp-block-post-title a[href]")
                ?: return@mapNotNull null
            val title = titleElement.text().trim().ifBlank { return@mapNotNull null }
            val href = titleElement.absUrl("href").ifBlank { return@mapNotNull null }
            val poster = card.selectFirst("img.wp-post-image, img")?.let { img ->
                img.attr("data-src").ifBlank { img.absUrl("src") }.ifBlank { img.absUrl("data-src") }
            }
            val episodeCount = card.selectFirst(".episode")?.text()?.episodeNumber()

            newAnimeSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)
                addSub(episodeCount)
            }
        }.distinctBy { it.url }
    }

    private fun parseSearchCards(document: Document): List<SearchResponse> {
        return document.select("a.movie-card[href]").mapNotNull { card ->
            val title = card.selectFirst(".movie-title")?.text()?.trim()
                ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: return@mapNotNull null
            val href = card.absUrl("href").ifBlank { return@mapNotNull null }
            val poster = card.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.absUrl("src") }.ifBlank { img.absUrl("data-src") }
            }
            val episodeCount = card.selectFirst(".episode")?.text()?.episodeNumber()

            newAnimeSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)
                addSub(episodeCount)
            }
        }.distinctBy { it.url }
    }

    private suspend fun fetchBloggerContent(postId: String, refererUrl: String): String? {
        val jsonp = app.get(
            "https://www.blogger.com/feeds/$bloggerBlogId/posts/default/$postId?alt=json-in-script&callback=fetchBloggerPostContent",
            headers = mapOf("User-Agent" to userAgent, "Accept" to "*/*"),
            referer = refererUrl
        ).text
        val json = jsonp.substringAfter("fetchBloggerPostContent(", "")
            .substringBeforeLast(");")
            .ifBlank { jsonp }
        return runCatching {
            mapper.readTree(json).at("/entry/content/\$t").asText(null)
        }.getOrNull()
    }

    private fun parseBloggerContent(content: String): List<EpisodeSource> {
        return content.split(';')
            .mapNotNull { raw ->
                val row = raw.trim()
                if (row.isBlank()) return@mapNotNull null
                val parts = row.split("|", limit = 3)
                val videos = parts.getOrNull(0)
                    ?.split(',', '\n')
                    ?.map { it.trim() }
                    ?.filter { it.startsWith("http", ignoreCase = true) }
                    .orEmpty()
                if (videos.isEmpty()) return@mapNotNull null

                val labels = parts.getOrNull(1)
                    ?.split(',')
                    ?.map { normalizeSubtitleLabel(it.trim()) }
                    .orEmpty()
                val subtitleUrls = parts.getOrNull(2)
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.startsWith("http", ignoreCase = true) }
                    .orEmpty()
                val subtitles = subtitleUrls.mapIndexed { index, subtitleUrl ->
                    KissSubtitle(labels.getOrNull(index) ?: subtitleUrl.subtitleLabelFromUrl(), subtitleUrl)
                }

                EpisodeSource(videos, subtitles)
            }
    }

    private fun collectEpisodeSources(
        sources: List<EpisodeSource>,
        episode: Int,
        totalEpisodes: Int?
    ): List<EpisodeSource> {
        val safeEpisodeIndex = (episode - 1).coerceAtLeast(0)
        val step = totalEpisodes?.takeIf { it > 0 } ?: sources.size
        val matched = mutableListOf<EpisodeSource>()
        var index = safeEpisodeIndex
        while (index in sources.indices) {
            matched.add(sources[index])
            index += step
        }
        return matched.ifEmpty { sources.getOrNull(safeEpisodeIndex)?.let { listOf(it) } ?: sources.take(1) }
    }

    private fun encodeLoadData(data: KissLoadData): String {
        return mapper.writeValueAsString(data)
    }

    private fun decodeLoadData(data: String): KissLoadData? {
        return runCatching { mapper.readValue<KissLoadData>(data) }.getOrNull()
    }

    private fun String.episodeNumber(): Int? {
        return Regex("(\\d+)").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.subtitleLabelFromUrl(): String {
        val lower = substringBeforeLast('?').substringAfterLast('/').substringBeforeLast('.').lowercase()
        val code = lower.substringAfterLast('-').substringAfterLast('_')
        return normalizeSubtitleLabel(code)
    }

    private fun normalizeSubtitleLabel(label: String): String {
        return when (label.trim().lowercase()) {
            "en", "eng", "english" -> "English"
            "id", "ind", "indo", "indonesian", "indonesia" -> "Indonesian"
            "kh", "km", "khmer" -> "Khmer"
            "ms", "malay" -> "Malay"
            "th", "thai" -> "Thai"
            "vi", "vie", "vietnamese" -> "Vietnamese"
            "ko", "kor", "korean" -> "Korean"
            "fr", "fre", "french" -> "French"
            "ar", "ara", "arabic" -> "Arabic"
            "tl", "fil", "filipino" -> "Filipino"
            "hi", "hin", "hindi" -> "Hindi"
            "it", "ita", "italian" -> "Italian"
            else -> label.ifBlank { "Subtitle" }
        }
    }

    private val CHUNK_REGEX1 by lazy { Regex("^\\d+$", RegexOption.MULTILINE) }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request().newBuilder().build()
                val response = chain.proceed(request)
                val url = response.request.url.toString()

                if (url.contains(".txt")) {
                    Log.d("KISSKH_SUB", "Intercepting encrypted subtitle: $url")
                    val responseBody = response.body.string()
                    val chunks = responseBody.split(CHUNK_REGEX1)
                        .filter(String::isNotBlank)
                        .map(String::trim)

                    val decrypted = chunks.mapIndexed { index, chunk ->
                        if (chunk.isBlank()) return@mapIndexed ""
                        val parts = chunk.split("\n")
                        if (parts.isEmpty()) return@mapIndexed ""

                        val header = parts.first()
                        val text = parts.drop(1)
                        val d = text.joinToString("\n") { line ->
                            try {
                                decrypt(line)
                            } catch (e: Exception) {
                                Log.e("KISSKH_SUB", "Decryption failed for line: $line | Error: ${e.message}")
                                "DECRYPT_ERROR:${e.message}"
                            }
                        }
                        listOf(index + 1, header, d).joinToString("\n")
                    }.filter { it.isNotEmpty() }
                        .joinToString("\n\n")

                    val newBody = decrypted.toResponseBody(response.body.contentType())
                    return response.newBuilder()
                        .body(newBody)
                        .build()
                }
                return response
            }
        }
    }
}

data class KissLoadData(
    val pageUrl: String,
    val title: String? = null,
    val episode: Int = 1,
    val bloggerPostId: String? = null,
    val totalEpisodes: Int? = null
)

data class EpisodeSource(
    val videos: List<String>,
    val subtitles: List<KissSubtitle>
)

data class KissSubtitle(
    val label: String,
    val url: String
)
