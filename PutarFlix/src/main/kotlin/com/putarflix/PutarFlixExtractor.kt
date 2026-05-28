package com.putarflix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object PutarFlixExtractor {
    private const val EXTRACT_TIMEOUT_MS = 22_000L
    private const val REQUEST_TIMEOUT_MS = 7_000L
    private const val LOAD_EXTRACTOR_TIMEOUT_MS = 8_000L
    private const val MAX_RESOLVE_DEPTH = 2

    private val directVideoRegex = Regex("""https?:\\?/\\?/[^\"'<>)\]\[\s]+?\.(?:m3u8|mp4|mkv|mpd)(?:\?[^\"'<>)\]\[\s]+)?""", RegexOption.IGNORE_CASE)
    private val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val jsonEmbedRegex = Regex("""["'](?:embed_url|file|url|source|src|link|download|download_url)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private val playerContainers = listOf(
        "#player", "#player2", "#video", ".player", ".player-area", ".playex",
        ".movieplay", ".video-content", ".responsive-embed", ".embed-responsive",
        ".pembed", ".dooplay_player", ".dooplay_player_content", ".server",
        ".servers", ".server-item", ".player-option", ".download", ".dllinks",
        "#download", ".entry-content", "article"
    ).joinToString(",")

    suspend fun extract(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
            extractInternal(data, subtitleCallback, callback)
        } ?: false
    }

    private suspend fun extractInternal(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startUrl = PutarFlixUtils.decodeKnownRedirect(data.trim())
        if (startUrl.isBlank()) return false

        if (!PutarFlixUtils.isPutarFlixUrl(startUrl)) {
            if (PutarFlixUtils.looksDirectVideo(startUrl)) {
                return emitDirect(startUrl, PutarFlixSeeds.MAIN_URL, "PutarFlix Direct", callback)
            }
            return resolveServer(startUrl, PutarFlixSeeds.MAIN_URL, "PutarFlix External", subtitleCallback, callback)
        }

        val clean = startUrl.substringBefore("?")
        val playerPages = buildList {
            add(clean)
            PutarFlixSeeds.playerNumbers.forEach { number ->
                if (number != "1") add("$clean?player=$number")
            }
        }.distinct()

        val candidates = linkedSetOf<PutarFlixServer>()
        for (page in playerPages) {
            val doc = safeGetDocument(page, PutarFlixSeeds.MAIN_URL) ?: continue
            candidates += collectServersFromDocument(page, doc)
            candidates += collectAjaxServers(page, doc)
        }

        var found = false
        val visited = linkedSetOf<String>()
        for (server in candidates.sortedWith(compareBy<PutarFlixServer> { rankServer(it.url) }.thenBy { it.label }).distinctBy { it.url }) {
            val finalUrl = PutarFlixUtils.decodeKnownRedirect(server.url)
            if (shouldSkipCandidate(finalUrl, allowPlayerPage = false, allowShortener = false)) continue
            found = resolveServer(finalUrl, server.referer, server.label, subtitleCallback, callback, visited) || found
            if (found) break
        }
        return found
    }

    private fun collectServersFromDocument(pageUrl: String, doc: Document): List<PutarFlixServer> {
        val servers = linkedSetOf<PutarFlixServer>()

        doc.select("iframe[src], embed[src], video[src], source[src]").forEach { element ->
            addServerFromElement(servers, pageUrl, element, allowInternalPlayerPage = false, forceAllowShortener = false)
        }

        doc.select(playerContainers).forEach { container ->
            container.select("iframe[src], embed[src], video[src], source[src], a[href], button, div, li, span").forEach { element ->
                addServerFromElement(servers, pageUrl, element, allowInternalPlayerPage = true, forceAllowShortener = false)
            }
        }

        // PutarFlix exposes usable download mirrors as shortlinks outside the visible player block.
        // Grab only known shortener/playable hosts globally; do not crawl menus or related posts.
        doc.select("a[href]").forEach { anchor ->
            val raw = anchor.attr("href")
            val absolute = PutarFlixUtils.absoluteUrl(pageUrl, raw) ?: return@forEach
            val decoded = PutarFlixUtils.decodeKnownRedirect(absolute)
            if (PutarFlixUtils.isShortenerUrl(absolute) && decoded != absolute) {
                if (!shouldSkipCandidate(decoded, allowPlayerPage = false, allowShortener = false)) {
                    servers += PutarFlixServer(PutarFlixUtils.extractLabelNear(anchor), decoded, pageUrl, "decoded-shortlink")
                }
            } else if (PutarFlixUtils.isKnownPlayableHost(absolute)) {
                if (!shouldSkipCandidate(absolute, allowPlayerPage = false, allowShortener = false)) {
                    servers += PutarFlixServer(PutarFlixUtils.extractLabelNear(anchor), absolute, pageUrl, "playable-anchor")
                }
            }
        }

        val scriptText = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        directVideoRegex.findAll(scriptText).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.value) ?: return@forEach
            if (!shouldSkipCandidate(url, allowPlayerPage = false, allowShortener = false)) {
                servers += PutarFlixServer("PutarFlix Direct", url, pageUrl, "script-direct")
            }
        }
        jsonEmbedRegex.findAll(scriptText).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            val decoded = PutarFlixUtils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(decoded, allowPlayerPage = false, allowShortener = false)) {
                servers += PutarFlixServer("PutarFlix Embed", decoded, pageUrl, "script-json")
            }
        }
        PutarFlixUtils.extractUrlsFromText(pageUrl, scriptText).forEach { url ->
            val decoded = PutarFlixUtils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(decoded, allowPlayerPage = false, allowShortener = false)) {
                servers += PutarFlixServer("PutarFlix Script", decoded, pageUrl, "script-url")
            }
        }

        return servers.distinctBy { it.url }
    }

    private fun addServerFromElement(
        servers: MutableSet<PutarFlixServer>,
        pageUrl: String,
        element: Element,
        allowInternalPlayerPage: Boolean,
        forceAllowShortener: Boolean
    ) {
        val raw = firstAttr(
            element,
            "src", "data-src", "data-lazy-src", "data-iframe", "data-embed", "data-link",
            "data-url", "data-video", "data-file", "data-href", "href"
        ) ?: return
        val url = PutarFlixUtils.absoluteUrl(pageUrl, raw) ?: return
        val decoded = PutarFlixUtils.decodeKnownRedirect(url)
        if (shouldSkipCandidate(decoded, allowPlayerPage = allowInternalPlayerPage, allowShortener = forceAllowShortener)) return
        servers += PutarFlixServer(PutarFlixUtils.extractLabelNear(element), decoded, pageUrl, element.tagName())
    }

    private suspend fun collectAjaxServers(pageUrl: String, doc: Document): List<PutarFlixServer> {
        val players = collectAjaxPlayers(pageUrl, doc)
        if (players.isEmpty()) return emptyList()

        val output = linkedSetOf<PutarFlixServer>()
        for (player in players) {
            for (action in PutarFlixSeeds.ajaxActions) {
                val response = safePostAjaxText(
                    url = "${PutarFlixSeeds.MAIN_URL}/wp-admin/admin-ajax.php",
                    referer = pageUrl,
                    data = mapOf(
                        "action" to action,
                        "post" to player.postId,
                        "nume" to player.nume,
                        "type" to player.type
                    )
                ) ?: continue

                val found = collectServersFromAjaxText(pageUrl, response, player.label)
                if (found.isNotEmpty()) {
                    output += found
                    break
                }
            }
        }
        return output.distinctBy { it.url }
    }

    private fun collectAjaxPlayers(pageUrl: String, doc: Document): List<PutarFlixAjaxPlayer> {
        val players = linkedSetOf<PutarFlixAjaxPlayer>()
        val fallbackType = if (pageUrl.contains("/tv/") || pageUrl.contains("/eps/")) "tv" else "movie"

        doc.select("[data-post][data-nume], [data-type][data-post], .dooplay_player_option, li[id*=player-option], .server-item[data-id]")
            .forEach { element ->
                val post = firstAttr(element, "data-post", "data-id", "data-postid", "data-post-id") ?: return@forEach
                val nume = firstAttr(element, "data-nume", "data-server", "data-player", "data-number", "data-no") ?: return@forEach
                val type = firstAttr(element, "data-type") ?: fallbackType
                players += PutarFlixAjaxPlayer(post, type, nume, PutarFlixUtils.extractLabelNear(element))
            }

        return players.distinctBy { "${it.postId}:${it.type}:${it.nume}" }.take(3)
    }

    private fun collectServersFromAjaxText(pageUrl: String, response: String, label: String): List<PutarFlixServer> {
        val decoded = PutarFlixUtils.decodeUrlRepeated(response)
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
            .replace("\\u002f", "/")

        val output = linkedSetOf<PutarFlixServer>()
        jsonEmbedRegex.findAll(decoded).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            val fixed = PutarFlixUtils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(fixed, allowPlayerPage = false, allowShortener = false)) {
                output += PutarFlixServer(label, fixed, pageUrl, "ajax-json")
            }
        }
        iframeRegex.findAll(decoded).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            val fixed = PutarFlixUtils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(fixed, allowPlayerPage = false, allowShortener = false)) {
                output += PutarFlixServer(label, fixed, pageUrl, "ajax-iframe")
            }
        }
        PutarFlixUtils.extractUrlsFromText(pageUrl, decoded).forEach { url ->
            val fixed = PutarFlixUtils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(fixed, allowPlayerPage = false, allowShortener = false)) {
                output += PutarFlixServer(label, fixed, pageUrl, "ajax-url")
            }
        }

        val htmlDoc = Jsoup.parse(decoded, pageUrl)
        htmlDoc.select("iframe[src], embed[src], source[src], video[src], a[href]").forEach { element ->
            addServerFromElement(output, pageUrl, element, allowInternalPlayerPage = false, forceAllowShortener = false)
        }
        return output.distinctBy { it.url }
    }

    private suspend fun resolveServer(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String> = linkedSetOf(),
        depth: Int = 0
    ): Boolean {
        val fixedUrl = PutarFlixUtils.decodeKnownRedirect(url)
        if (depth > MAX_RESOLVE_DEPTH || fixedUrl in visited) return false
        visited += fixedUrl

        if (PutarFlixUtils.looksDirectVideo(fixedUrl)) {
            return emitDirect(fixedUrl, referer, label, callback)
        }
        if (shouldSkipCandidate(fixedUrl, allowPlayerPage = false, allowShortener = false)) return false

        if (PutarFlixUtils.isFilePressUrl(fixedUrl)) {
            resolveFilePress(fixedUrl, referer, label, subtitleCallback, callback, visited)?.let { return it }
        }

        val loaded = safeLoadExtractor(fixedUrl, referer, subtitleCallback, callback)
        if (loaded) return true

        if (!PutarFlixUtils.isKnownPlayableHost(fixedUrl)) return false

        val doc = safeGetDocument(fixedUrl, referer) ?: return false
        val nested = collectServersFromDocument(fixedUrl, doc)
        var found = false
        for (server in nested.sortedBy { rankServer(it.url) }.distinctBy { it.url }) {
            val nestedUrl = PutarFlixUtils.decodeKnownRedirect(server.url)
            if (shouldSkipCandidate(nestedUrl, allowPlayerPage = false, allowShortener = false)) continue
            found = resolveServer(nestedUrl, fixedUrl, server.label, subtitleCallback, callback, visited, depth + 1) || found
            if (found) break
        }
        return found
    }

    private suspend fun resolveFilePress(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String>
    ): Boolean? {
        val loaded = safeLoadExtractor(url, referer, subtitleCallback, callback)
        if (loaded) return true

        val origin = PutarFlixUtils.originOf(url) ?: return false
        val fileId = Regex("""/file/([^/?#]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1) ?: return false
        val endpoints = listOf(
            "$origin/api/file/downlaod/",
            "$origin/api/file/download/"
        )

        for (endpoint in endpoints) {
            val response = safePostAjaxText(
                url = endpoint,
                referer = url,
                data = mapOf(
                    "id" to fileId,
                    "method" to "publicDownlaod"
                )
            ) ?: continue

            val servers = collectServersFromAjaxText(url, response, label.ifBlank { "FilePress" })
            for (server in servers.sortedBy { rankServer(it.url) }.distinctBy { it.url }) {
                val fixed = PutarFlixUtils.decodeKnownRedirect(server.url)
                if (fixed == url || fixed in visited) continue
                val resolved = resolveServer(fixed, url, server.label, subtitleCallback, callback, visited, depth = 1)
                if (resolved) return true
            }
        }
        return false
    }

    private fun shouldSkipCandidate(url: String, allowPlayerPage: Boolean, allowShortener: Boolean): Boolean {
        if (PutarFlixUtils.isRejectedVideoCandidate(url)) return true
        if (!allowShortener && PutarFlixUtils.isShortenerUrl(url)) return true
        if (!allowPlayerPage && PutarFlixUtils.isInternalNavigation(url)) return true
        if (PutarFlixUtils.isPutarFlixUrl(url) && !PutarFlixUtils.looksDirectVideo(url) && !url.contains("?player=")) return true
        if (!PutarFlixUtils.isPutarFlixUrl(url) && !PutarFlixUtils.isKnownPlayableHost(url) && !PutarFlixUtils.looksDirectVideo(url)) return true
        return false
    }

    private fun rankServer(url: String): Int {
        val fixed = PutarFlixUtils.decodeKnownRedirect(url)
        val host = PutarFlixUtils.hostOf(fixed).orEmpty()
        return when {
            PutarFlixUtils.looksDirectVideo(fixed) -> 0
            "filepress" in host -> 1
            "drive.google.com" in host || "googleusercontent" in host -> 2
            PutarFlixUtils.isKnownPlayableHost(fixed) -> 3
            PutarFlixUtils.isShortenerUrl(fixed) -> 9
            else -> 10
        }
    }

    private suspend fun safeLoadExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return withTimeoutOrNull(LOAD_EXTRACTOR_TIMEOUT_MS) {
            runCatching { loadExtractor(url, referer, subtitleCallback, callback) }.getOrDefault(false)
        } ?: false
    }

    private suspend fun safeGetDocument(url: String, referer: String): Document? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching { app.get(url, referer = referer).document }.getOrNull()
        }
    }

    private suspend fun safePostAjaxText(url: String, referer: String, data: Map<String, String>): String? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                app.post(
                    url,
                    referer = referer,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    ),
                    data = data
                ).text
            }.getOrNull()
        }
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val type = when {
            url.substringBefore("?").endsWith(".m3u8", true) -> ExtractorLinkType.M3U8
            url.substringBefore("?").endsWith(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        callback.invoke(
            newExtractorLink(
                source = "PutarFlix",
                name = label.ifBlank { "PutarFlix" },
                url = url,
                type = type
            ) {
                this.referer = referer
                this.quality = getQualityFromName(url)
            }
        )
        return true
    }

    private fun firstAttr(element: Element, vararg attrs: String): String? {
        return attrs.firstNotNullOfOrNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
    }
}
