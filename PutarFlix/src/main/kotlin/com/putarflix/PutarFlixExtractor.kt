package com.putarflix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object PutarFlixExtractor {
    private const val EXTRACT_TIMEOUT_MS = 30_000L
    private const val REQUEST_TIMEOUT_MS = 8_000L
    private const val LOAD_EXTRACTOR_TIMEOUT_MS = 8_000L
    private const val MAX_RESOLVE_DEPTH = 4

    private val directVideoRegex = Regex(
        """https?:\\?/\\?/[^\"'<>)\]\[\s]+?\.(?:m3u8|mp4|mkv|mpd)(?:\?[^\"'<>)\]\[\s]+)?""",
        RegexOption.IGNORE_CASE
    )
    private val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val jsonEmbedRegex = Regex(
        """["'](?:embed_url|file|url|source|src|link|download|download_url|direct_link|downloadLink)["']\s*:\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

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
            if (PutarFlixUtils.looksDirectVideo(startUrl) || PutarFlixUtils.isFinalStreamUrl(startUrl)) {
                return emitDirect(startUrl, PutarFlixSeeds.MAIN_URL, "PutarFlix Direct", callback)
            }
            return resolveServer(
                url = startUrl,
                referer = PutarFlixSeeds.MAIN_URL,
                label = "PutarFlix External",
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }

        val clean = startUrl.substringBefore("#")
        val base = clean.substringBefore("?")
        val playerPages = buildList {
            add(base)
            PutarFlixSeeds.playerNumbers.forEach { number ->
                if (number != "1") add("$base?player=$number")
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

        for (server in candidates
            .sortedWith(compareBy<PutarFlixServer> { rankServer(it.url) }.thenBy { it.label })
            .distinctBy { PutarFlixUtils.decodeKnownRedirect(it.url) }) {

            val finalUrl = PutarFlixUtils.decodeKnownRedirect(server.url)
            if (shouldSkipCandidate(finalUrl, allowPlayerPage = true, allowShortener = true)) continue

            val resolved = resolveServer(
                url = finalUrl,
                referer = server.referer,
                label = server.label,
                subtitleCallback = subtitleCallback,
                callback = callback,
                visited = visited,
                depth = 0
            )
            if (resolved) found = true
        }

        return found
    }

    private fun collectServersFromDocument(pageUrl: String, doc: Document): List<PutarFlixServer> {
        val servers = linkedSetOf<PutarFlixServer>()

        doc.select("iframe[src], embed[src], video[src], source[src]").forEach { element ->
            addServerFromElement(servers, pageUrl, element, allowInternalPlayerPage = true, forceAllowShortener = true)
        }

        doc.select(playerContainers).forEach { container ->
            container.select("iframe[src], embed[src], video[src], source[src], a[href], button, div, li, span")
                .forEach { element ->
                    addServerFromElement(servers, pageUrl, element, allowInternalPlayerPage = true, forceAllowShortener = true)
                }
        }

        // PutarFlix exposes download mirrors as shortlinks outside the visible player block.
        // Keep shortlinks as candidates even when the redirect target is hidden behind the shortener slug.
        doc.select("a[href]").forEach { anchor ->
            val raw = anchor.attr("href")
            val absolute = PutarFlixUtils.absoluteUrl(pageUrl, raw) ?: return@forEach
            val decoded = PutarFlixUtils.decodeKnownRedirect(absolute)
            val candidate = decoded.takeIf { it != absolute } ?: absolute

            when {
                PutarFlixUtils.isShortenerUrl(absolute) -> {
                    if (!shouldSkipCandidate(candidate, allowPlayerPage = true, allowShortener = true)) {
                        servers += PutarFlixServer(
                            PutarFlixUtils.extractLabelNear(anchor),
                            candidate,
                            pageUrl,
                            "shortlink-anchor"
                        )
                    }
                }
                PutarFlixUtils.isKnownPlayableHost(absolute) || PutarFlixUtils.looksDirectVideo(absolute) || PutarFlixUtils.isDirectDownloadUrl(absolute) -> {
                    if (!shouldSkipCandidate(absolute, allowPlayerPage = true, allowShortener = true)) {
                        servers += PutarFlixServer(
                            PutarFlixUtils.extractLabelNear(anchor),
                            absolute,
                            pageUrl,
                            "playable-anchor"
                        )
                    }
                }
            }
        }

        val scriptText = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        val normalized = normalizeExtractText(scriptText)

        directVideoRegex.findAll(normalized).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.value) ?: return@forEach
            if (!shouldSkipCandidate(url, allowPlayerPage = true, allowShortener = true)) {
                servers += PutarFlixServer("PutarFlix Direct", url, pageUrl, "script-direct")
            }
        }

        jsonEmbedRegex.findAll(normalized).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            val decoded = PutarFlixUtils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(decoded, allowPlayerPage = true, allowShortener = true)) {
                servers += PutarFlixServer("PutarFlix Embed", decoded, pageUrl, "script-json")
            }
        }

        PutarFlixUtils.extractUrlsFromText(pageUrl, normalized).forEach { url ->
            val decoded = PutarFlixUtils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(decoded, allowPlayerPage = true, allowShortener = true)) {
                servers += PutarFlixServer("PutarFlix Script", decoded, pageUrl, "script-url")
            }
        }

        return servers.distinctBy { PutarFlixUtils.decodeKnownRedirect(it.url) }
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

        if (shouldSkipCandidate(
                decoded,
                allowPlayerPage = allowInternalPlayerPage,
                allowShortener = forceAllowShortener
            )
        ) return

        servers += PutarFlixServer(
            PutarFlixUtils.extractLabelNear(element),
            decoded,
            pageUrl,
            element.tagName()
        )
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
        return output.distinctBy { PutarFlixUtils.decodeKnownRedirect(it.url) }
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

        // Some Dooplay skins expose only ?player=2/3 tabs in visible HTML and keep the post id
        // in rel=shortlink, body classes, or inline scripts. Generate the standard 1..3 AJAX
        // candidates from that post id so server tabs still resolve.
        if (players.isEmpty()) {
            val postId = extractPostId(doc)
            if (!postId.isNullOrBlank()) {
                PutarFlixSeeds.playerNumbers.forEach { nume ->
                    players += PutarFlixAjaxPlayer(postId, fallbackType, nume, "Server $nume")
                }
            }
        }

        return players.distinctBy { "${it.postId}:${it.type}:${it.nume}" }.take(6)
    }

    private fun extractPostId(doc: Document): String? {
        val shortLink = doc.selectFirst("link[rel=shortlink]")?.attr("href").orEmpty()
        Regex("""[?&]p=(\d+)""").find(shortLink)?.groupValues?.getOrNull(1)?.let { return it }

        val bodyClasses = doc.body()?.className().orEmpty()
        Regex("""postid-(\d+)""", RegexOption.IGNORE_CASE).find(bodyClasses)?.groupValues?.getOrNull(1)?.let { return it }

        val scripts = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        return listOf(
            Regex("""postid-(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?postId["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?post_id["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?post["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { regex -> regex.find(scripts)?.groupValues?.getOrNull(1) }
    }

    private fun collectServersFromAjaxText(pageUrl: String, response: String, label: String): List<PutarFlixServer> {
        val decodedText = normalizeExtractText(PutarFlixUtils.decodeUrlRepeated(response))
        val output = linkedSetOf<PutarFlixServer>()

        jsonEmbedRegex.findAll(decodedText).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            val fixed = PutarFlixUtils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(fixed, allowPlayerPage = true, allowShortener = true)) {
                output += PutarFlixServer(label, fixed, pageUrl, "ajax-json")
            }
        }

        iframeRegex.findAll(decodedText).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            val fixed = PutarFlixUtils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(fixed, allowPlayerPage = true, allowShortener = true)) {
                output += PutarFlixServer(label, fixed, pageUrl, "ajax-iframe")
            }
        }

        PutarFlixUtils.extractUrlsFromText(pageUrl, decodedText).forEach { url ->
            val fixed = PutarFlixUtils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(fixed, allowPlayerPage = true, allowShortener = true)) {
                output += PutarFlixServer(label, fixed, pageUrl, "ajax-url")
            }
        }

        val htmlDoc = Jsoup.parse(decodedText, pageUrl)
        htmlDoc.select("iframe[src], embed[src], source[src], video[src], a[href]").forEach { element ->
            addServerFromElement(output, pageUrl, element, allowInternalPlayerPage = true, forceAllowShortener = true)
        }

        return output.distinctBy { PutarFlixUtils.decodeKnownRedirect(it.url) }
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

        if (PutarFlixUtils.looksDirectVideo(fixedUrl) || PutarFlixUtils.isFinalStreamUrl(fixedUrl)) {
            return emitDirect(fixedUrl, referer, label, callback)
        }

        if (PutarFlixUtils.isRejectedVideoCandidate(fixedUrl)) return false

        // Prefer the custom FilePress path; FilePress extractors can emit landing/download pages
        // that Cloudstream treats as "success" but ExoPlayer cannot play.
        if (PutarFlixUtils.isFilePressUrl(fixedUrl)) {
            val fp = resolveFilePress(
                url = fixedUrl,
                referer = referer,
                label = label,
                subtitleCallback = subtitleCallback,
                callback = callback,
                visited = visited,
                depth = depth + 1
            )
            if (fp == true) return true
        }

        val loadedWithLinks = safeLoadExtractor(fixedUrl, referer, subtitleCallback, callback)
        if (loadedWithLinks) return true

        // Google Drive uc/file/open links are not directly playable. If Cloudstream's
        // extractor did not emit a real stream above, do not pass the HTML landing
        // page further into ExoPlayer.
        if (PutarFlixUtils.isGoogleDriveLandingUrl(fixedUrl)) return false

        if (shouldSkipCandidate(fixedUrl, allowPlayerPage = true, allowShortener = true)) return false

        val doc = safeGetDocument(fixedUrl, referer) ?: return false
        val nested = collectServersFromDocument(fixedUrl, doc) + collectAjaxServers(fixedUrl, doc)

        var found = false
        for (server in nested
            .sortedBy { rankServer(it.url) }
            .distinctBy { PutarFlixUtils.decodeKnownRedirect(it.url) }) {

            val nestedUrl = PutarFlixUtils.decodeKnownRedirect(server.url)
            if (nestedUrl == fixedUrl) continue
            if (shouldSkipCandidate(nestedUrl, allowPlayerPage = true, allowShortener = true)) continue

            val resolved = resolveServer(
                url = nestedUrl,
                referer = fixedUrl,
                label = server.label.ifBlank { label },
                subtitleCallback = subtitleCallback,
                callback = callback,
                visited = visited,
                depth = depth + 1
            )
            if (resolved) found = true
        }

        return found
    }

    private suspend fun resolveFilePress(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String>,
        depth: Int
    ): Boolean? {
        if (depth > MAX_RESOLVE_DEPTH) return false

        val origin = PutarFlixUtils.originOf(url) ?: return false
        val fileId = Regex("""/file/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1) ?: return false

        val servers = linkedSetOf<PutarFlixServer>()

        val fileDoc = safeGetDocument(url, referer)
        if (fileDoc != null) {
            servers += collectServersFromDocument(url, fileDoc)
            val text = normalizeExtractText(fileDoc.select("script").joinToString("\n") { it.data() + "\n" + it.html() })
            PutarFlixUtils.extractUrlsFromText(url, text).forEach { found ->
                val fixed = PutarFlixUtils.decodeKnownRedirect(found)
                if (!PutarFlixUtils.isHtmlLandingUrl(fixed)) {
                    servers += PutarFlixServer(label.ifBlank { "FilePress" }, fixed, url, "filepress-page")
                }
            }
        }

        val firstStepEndpoints = listOf(
            "$origin/api/file/downlaod/",
            "$origin/api/file/download/"
        )
        val firstStepMethods = listOf("publicDownlaod", "publicDownload", "download", "telegramDownload")
        val secondStepEndpoints = listOf(
            "$origin/api/file/downlaod2/",
            "$origin/api/file/download2/"
        )

        val secondStepIds = linkedSetOf<String>()
        for (endpoint in firstStepEndpoints) {
            for (method in firstStepMethods) {
                val response = safePostFilePressJsonText(
                    url = endpoint,
                    referer = url,
                    id = fileId,
                    method = method
                ) ?: safePostAjaxText(
                    url = endpoint,
                    referer = url,
                    data = mapOf("id" to fileId, "method" to method)
                ) ?: continue

                collectFilePressResponseCandidates(url, response, label.ifBlank { "FilePress" }, servers, secondStepIds)
            }
        }

        for (nextId in secondStepIds) {
            for (endpoint in secondStepEndpoints) {
                val response = safePostFilePressJsonText(
                    url = endpoint,
                    referer = url,
                    id = nextId,
                    method = "publicDownlaod"
                ) ?: safePostAjaxText(
                    url = endpoint,
                    referer = url,
                    data = mapOf("id" to nextId, "method" to "publicDownlaod")
                ) ?: continue

                collectFilePressResponseCandidates(url, response, label.ifBlank { "FilePress" }, servers, linkedSetOf())
            }
        }

        for (server in servers
            .sortedBy { rankServer(it.url) }
            .distinctBy { PutarFlixUtils.decodeKnownRedirect(it.url) }) {

            val fixed = PutarFlixUtils.decodeKnownRedirect(server.url)
            if (fixed == url || fixed in visited) continue

            val resolved = resolveServer(
                url = fixed,
                referer = url,
                label = server.label.ifBlank { label.ifBlank { "FilePress" } },
                subtitleCallback = subtitleCallback,
                callback = callback,
                visited = visited,
                depth = depth
            )
            if (resolved) return true
        }
        return false
    }

    private fun collectFilePressResponseCandidates(
        baseUrl: String,
        response: String,
        label: String,
        servers: MutableSet<PutarFlixServer>,
        secondStepIds: MutableSet<String>
    ) {
        val decoded = normalizeExtractText(response)

        servers += collectServersFromAjaxText(baseUrl, decoded, label)

        PutarFlixUtils.extractUrlsFromText(baseUrl, decoded).forEach { found ->
            val fixed = PutarFlixUtils.decodeKnownRedirect(found)
            if (!PutarFlixUtils.isHtmlLandingUrl(fixed) || PutarFlixUtils.isGoogleDriveLandingUrl(fixed)) {
                servers += PutarFlixServer(label, fixed, baseUrl, "filepress-api-url")
            }
        }

        extractJsonStringValues(decoded, "data").forEach { dataValue ->
            val value = dataValue.trim()
            when {
                value.startsWith("http", true) -> {
                    val fixed = PutarFlixUtils.decodeKnownRedirect(value)
                    servers += PutarFlixServer(label, fixed, baseUrl, "filepress-api-data-url")
                }
                value.length > 8 && Regex("^[A-Za-z0-9_-]+$").matches(value) -> {
                    secondStepIds += value
                    servers += PutarFlixServer(
                        label,
                        "https://drive.google.com/file/d/$value/view",
                        baseUrl,
                        "filepress-drive-view"
                    )
                    servers += PutarFlixServer(
                        label,
                        "https://drive.google.com/uc?id=$value&export=download",
                        baseUrl,
                        "filepress-drive-uc"
                    )
                }
            }
        }
    }

    private fun extractJsonStringValues(text: String, key: String): List<String> {
        val quoted = Regex("""["']${Regex.escape(key)}["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
        val bare = Regex("""["']${Regex.escape(key)}["']\s*:\s*([A-Za-z0-9_-]{8,})""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
        return (quoted + bare).map { PutarFlixUtils.decodeUrlRepeated(it) }.distinct().toList()
    }


    private fun shouldSkipCandidate(url: String, allowPlayerPage: Boolean, allowShortener: Boolean): Boolean {
        if (url.isBlank()) return true
        if (PutarFlixUtils.isRejectedVideoCandidate(url)) return true
        if (!allowShortener && PutarFlixUtils.isShortenerUrl(url)) return true

        if (PutarFlixUtils.isPutarFlixUrl(url) && !PutarFlixUtils.looksDirectVideo(url)) {
            return !(allowPlayerPage && PutarFlixUtils.isPutarFlixPlayerPage(url))
        }

        return !PutarFlixUtils.looksDirectVideo(url) &&
            !PutarFlixUtils.isDirectDownloadUrl(url) &&
            !PutarFlixUtils.isKnownPlayableHost(url) &&
            !PutarFlixUtils.isShortenerUrl(url)
    }

    private fun rankServer(url: String): Int {
        val fixed = PutarFlixUtils.decodeKnownRedirect(url)
        val host = PutarFlixUtils.hostOf(fixed).orEmpty()
        return when {
            PutarFlixUtils.looksDirectVideo(fixed) -> 0
            PutarFlixUtils.isDirectDownloadUrl(fixed) -> 1
            "filepress" in host -> 2
            "drive.google.com" in host || "googleusercontent" in host || "drive.usercontent.google.com" in host -> 3
            PutarFlixUtils.isKnownPlayableHost(fixed) -> 4
            PutarFlixUtils.isPutarFlixPlayerPage(fixed) -> 5
            PutarFlixUtils.isShortenerUrl(fixed) -> 6
            else -> 10
        }
    }

    private suspend fun safeLoadExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            if (!PutarFlixUtils.isHtmlLandingUrl(link.url)) {
                emitted = true
                callback(link)
            }
        }

        withTimeoutOrNull(LOAD_EXTRACTOR_TIMEOUT_MS) {
            runCatching {
                loadExtractor(url, referer, subtitleCallback, wrappedCallback)
            }.getOrDefault(false)
        } ?: false

        return emitted
    }

    private suspend fun safeGetDocument(url: String, referer: String): Document? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                app.get(
                    url = url,
                    referer = referer,
                    timeout = REQUEST_TIMEOUT_MS
                ).document
            }.getOrNull()
        }
    }

    private suspend fun safePostAjaxText(url: String, referer: String, data: Map<String, String>): String? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                app.post(
                    url = url,
                    referer = referer,
                    timeout = REQUEST_TIMEOUT_MS,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Origin" to PutarFlixSeeds.MAIN_URL
                    ),
                    data = data
                ).text
            }.getOrNull()
        }
    }

    private suspend fun safePostFilePressJsonText(url: String, referer: String, id: String, method: String): String? {
        val body = """{"id":"${escapeJson(id)}","method":"${escapeJson(method)}"}"""
            .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                app.post(
                    url = url,
                    referer = referer,
                    timeout = REQUEST_TIMEOUT_MS,
                    headers = mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Origin" to (PutarFlixUtils.originOf(referer) ?: PutarFlixSeeds.MAIN_URL),
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    requestBody = body
                ).text
            }.getOrNull()
        }
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
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

        callback(
            newExtractorLink(
                source = "PutarFlix",
                name = label.ifBlank { "PutarFlix" },
                url = url,
                type = type
            ) {
                this.referer = referer
                this.quality = getQualityFromName(label).takeIf { it > 0 } ?: getQualityFromName(url)
                this.headers = mapOf(
                    "Referer" to referer,
                    "Origin" to PutarFlixSeeds.MAIN_URL,
                    "User-Agent" to "Mozilla/5.0"
                )
            }
        )
        return true
    }

    private fun normalizeExtractText(text: String): String {
        return PutarFlixUtils.decodeUrlRepeated(text)
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
            .replace("\\u002f", "/")
            .replace("\\\\/", "/")
    }

    private fun firstAttr(element: Element, vararg attrs: String): String? {
        return attrs.firstNotNullOfOrNull { attr ->
            element.attr(attr).takeIf { it.isNotBlank() }
        }
    }
}
