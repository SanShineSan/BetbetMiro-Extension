package com.istarvin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getExtractorApiFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class Sulasok : MainAPI() {
    override var mainUrl = "https://sulasok.uno"
    override var name = "Sulasok"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override var supportedTypes = setOf(TvType.NSFW)
    override var lang = "id"

    private val videoCount = 20
    private val bgUrlRegex = Regex("""url\(['\"]?([^'\")]+)['\"]?\)""")
    private val sourceParamRegex = Regex("([?&])s=[^&]*")

    override val mainPage = mainPageOf(
        "load_more.php?limit=$videoCount&filter=best" to "Trending",
        "load_more.php?limit=$videoCount" to "Latest",
        "load_more.php?limit=$videoCount&filter=longest" to "Longest",
        "load_more_random.php?limit=$videoCount" to "Random",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}&start=${(page - 1) * videoCount}"
        val document = app.get(url).document
        val home = document.select("div.col").mapNotNull { it.toSearchResult() }
        val newRequest = request.copy(horizontalImages = true)
        return newHomePageResponse(newRequest, home, home.size == videoCount)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".video_title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = selectFirst("a[href]")?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?.replace("watch.php", "video.php")
            ?.toAbsoluteUrl()
            ?: return null

        val posterUrl = selectFirst("div.itemsContainer")?.attr("style")
            ?.let { bgUrlRegex.find(it)?.groupValues?.getOrNull(1) }
            ?.toAbsoluteUrl()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url =
            "$mainUrl/load_more_search.php?start=${(page - 1) * videoCount}&limit=$videoCount&search=$encodedQuery"
        val document = app.get(url).document
        val list = document.select("div.col").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(list, list.size == videoCount)
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        val document = request.document
        val title = document.getElementsByAttributeValue("property", "og:title").attr("content")
            .ifBlank { document.title() }
            .trim()
        val posterUrl = document.getElementsByAttributeValue("property", "og:image")
            .attr("content")
            .takeIf { it.isNotBlank() }
            ?.toAbsoluteUrl()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
        }
    }

    private val sources = listOf("vidara", "streamruby")
    private val iframeSrcRegexes = listOf(
        Regex("""iframe\.src\s*=\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE),
        Regex("""<iframe[^>]+src\s*=\s*['\"]([^'\"]+)['\"]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val emittedLinks = AtomicInteger(0)
        val countedCallback: (ExtractorLink) -> Unit = { link ->
            emittedLinks.incrementAndGet()
            callback(link)
        }

        val executionList: List<suspend () -> Unit> = sources.map { source ->
            suspend sourceTask@ {
                val playerUrl = data.withSource(source)
                val text = app.get(playerUrl).text
                val src = text.extractIframeSrc()?.toAbsoluteUrl() ?: return@sourceTask

                // Force use my own streamruby extractor.
                // Remove if StreamPlay rubyvidhub extractor is updated.
                if (source == "streamruby") {
                    getExtractorApiFromName("RubyVidHub").getUrl(
                        src,
                        subtitleCallback = subtitleCallback,
                        callback = countedCallback
                    )
                    return@sourceTask
                }

                loadExtractor(src, subtitleCallback, countedCallback)
            }
        }

        runLimitedAsync(tasks = executionList.toTypedArray())
        return emittedLinks.get() > 0
    }

    private fun String.extractIframeSrc(): String? {
        return iframeSrcRegexes.firstNotNullOfOrNull { regex ->
            regex.find(this)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }
    }

    private fun String.withSource(source: String): String {
        val cleanUrl = trim()
        if (sourceParamRegex.containsMatchIn(cleanUrl)) {
            return sourceParamRegex.replace(cleanUrl) { match -> "${match.groupValues[1]}s=$source" }
        }
        val separator = if (cleanUrl.contains("?")) "&" else "?"
        return "$cleanUrl${separator}s=$source"
    }

    private fun String.toAbsoluteUrl(): String {
        val cleanUrl = trim()
        return when {
            cleanUrl.startsWith("//") -> "https:$cleanUrl"
            cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://") -> cleanUrl
            cleanUrl.startsWith("/") -> "$mainUrl$cleanUrl"
            else -> "$mainUrl/$cleanUrl"
        }
    }

    private suspend fun runLimitedAsync(
        concurrency: Int = 5,
        vararg tasks: suspend () -> Unit
    ) = coroutineScope {
        if (tasks.isEmpty()) return@coroutineScope

        val semaphore = Semaphore(concurrency)

        tasks.map { task ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        task()
                    } catch (e: Exception) {
                        Log.e("SulasokConcurrency", "Task failed: ${e.message}")
                    }
                }
            }
        }.awaitAll()
    }
}
