package com.putarflix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

internal object PutarFlixExtractor {
    private const val EXTRACT_TIMEOUT_MS = 45_000L
    private const val REQUEST_TIMEOUT_MS = 12_000L
    private const val LOAD_EXTRACTOR_TIMEOUT_MS = 12_000L
    private const val MAX_RESOLVE_DEPTH = 5

    private val directVideoRegex = Regex(
        """https?:\\?/\\?/[^\"'<>)\]\[\s]+?(?:(?:\.(?:m3u8|mp4|mkv|mpd|webm)(?:\?[^\"'<>)\]\[\s]+)?)|(?:\?[^\"'<>)\]\[\s]*(?:m3u8|mp4|mkv|mpd|webm)[^\"'<>)\]\[\s]*))""",
        RegexOption.IGNORE_CASE
    )
    private val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val jsonEmbedRegex = Regex(
        """["'](?:embed_url|file|url|source|src|link|download|download_url|direct_link|downloadLink)["']\s*:\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    private val rawHtmlRegex = Regex("""<iframe|<source|<video|&lt;iframe|&lt;source|&lt;video""", RegexOption.IGNORE_CASE)
    private val payloadAttributes = listOf(
        "content", "src", "data-src", "data-lazy-src", "data-litespeed-src",
        "data-iframe", "data-embed", "data-link", "data-url", "data-video",
        "data-video-url", "data-stream", "data-stream-url", "data-file", "data-href",
        "data-content", "data-html", "data-frame", "data-player", "data-play",
        "data-server", "data-hls", "data-m3u8", "value", "href", "srcdoc"
    )

    private val playerContainers = listOf(
        "#player", "#player2", "#video", ".player", ".player-area", ".playex",
        ".movieplay", ".video-content", ".responsive-embed", ".embed-responsive",
        ".pembed", ".dooplay_player", ".dooplay_player_content", ".dooplay_player_option", "#playeroptionsul", ".server",
        ".servers", ".server-item", ".player-option", ".player-option-item", ".muvipro-player-tabs", ".gmr-embed-responsive",
        ".tab-content", ".tab-pane", ".download", ".dllinks", "#download", ".entry-content", "article"
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
            candidates += collectMuviproServers(page, doc)
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

        doc.select("iframe[src], iframe[data-src], iframe[srcdoc], embed[src], video[src], video[data-src], source[src], source[data-src], meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player]").forEach { element ->
            addServerFromElement(servers, pageUrl, element, allowInternalPlayerPage = true, forceAllowShortener = true)
        }

        doc.select(playerContainers).forEach { container ->
            container.select("iframe[src], iframe[data-src], iframe[srcdoc], embed[src], video[src], video[data-src], source[src], source[data-src], a[href], button, div, li, span, [data-content], [data-html], [data-url], [data-link], [data-file], [data-video]")
                .forEach { element ->
                    addServerFromElement(servers, pageUrl, element, allowInternalPlayerPage = true, forceAllowShortener = true)
                }
        }

        doc.select("[srcdoc], [data-content], [data-html], [data-iframe], [data-embed], [data-player], [data-url], [data-video], [data-file]").forEach { element ->
            payloadAttributes.mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                .forEach { payload -> servers += collectServersFromAjaxText(pageUrl, payload, PutarFlixUtils.extractLabelNear(element)) }
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

        val unpacked = runCatching {
            if (!getPacked(normalized).isNullOrEmpty()) getAndUnpack(normalized) else null
        }.getOrNull()
        if (!unpacked.isNullOrBlank()) {
            servers += collectServersFromAjaxText(pageUrl, unpacked, "PutarFlix Unpacked")
        }

        // Last-pass scan against the full raw HTML catches sources hidden in lazy
        // attributes or inline JSON that are outside the usual player containers.
        val fullHtml = normalizeExtractText(doc.outerHtml())
        if (fullHtml.length > normalized.length) {
            PutarFlixUtils.extractUrlsFromText(pageUrl, fullHtml).forEach { raw ->
                val fixed = PutarFlixUtils.decodeKnownRedirect(raw)
                if (!shouldSkipCandidate(fixed, allowPlayerPage = true, allowShortener = true)) {
                    servers += PutarFlixServer("PutarFlix HTML", fixed, pageUrl, "full-html-url")
                }
            }
        }

        PutarFlixUtils.decodeBase64Payloads(fullHtml).forEach { decodedPayload ->
            servers += collectServersFromAjaxText(pageUrl, decodedPayload, "PutarFlix Encoded")
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
        val label = PutarFlixUtils.extractLabelNear(element)
        val payloads = payloadAttributes.mapNotNull { attr ->
            element.attr(attr).takeIf { it.isNotBlank() }
        }.distinct()

        for (rawPayload in payloads) {
            val raw = PutarFlixUtils.cleanUrlText(rawPayload)
            if (raw.isBlank()) continue

            if (rawHtmlRegex.containsMatchIn(raw) || (raw.contains("http", true) && raw.contains("src=", true))) {
                servers += collectServersFromAjaxText(pageUrl, raw, label)
                continue
            }

            PutarFlixUtils.decodeBase64Payloads(raw).forEach { decodedPayload ->
                servers += collectServersFromAjaxText(pageUrl, decodedPayload, label)
            }

            val url = PutarFlixUtils.absoluteUrl(pageUrl, raw) ?: continue
            val decoded = PutarFlixUtils.decodeKnownRedirect(url)

            if (shouldSkipCandidate(
                    decoded,
                    allowPlayerPage = allowInternalPlayerPage,
                    allowShortener = forceAllowShortener
                )
            ) continue

            servers += PutarFlixServer(
                label,
                decoded,
                pageUrl,
                element.tagName()
            )
        }
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

    private suspend fun collectMuviproServers(pageUrl: String, doc: Document): List<PutarFlixServer> {
        val output = linkedSetOf<PutarFlixServer>()

        // Same pattern as the working Dutamovie provider: some Muvipro themes expose
        // direct tab URLs, while others keep the iframe behind admin-ajax.
        doc.select("ul.muvipro-player-tabs li a[href], .muvipro-player-tabs a[href], a[href*='?player='], a[href*='&player=']")
            .forEach { tab ->
                val raw = tab.attr("href").trim()
                val tabUrl = PutarFlixUtils.absoluteUrl(pageUrl, raw) ?: return@forEach
                if (!shouldSkipCandidate(tabUrl, allowPlayerPage = true, allowShortener = true)) {
                    output += PutarFlixServer(PutarFlixUtils.extractLabelNear(tab), tabUrl, pageUrl, "muvipro-tab")
                }
            }

        doc.select("div.gmr-embed-responsive iframe[src], div.gmr-embed-responsive iframe[data-src], .tab-pane iframe[src], .tab-pane iframe[data-src], .tab-content iframe[src], .tab-content iframe[data-src]")
            .forEach { iframe ->
                addServerFromElement(output, pageUrl, iframe, allowInternalPlayerPage = true, forceAllowShortener = true)
            }

        val postId = doc.selectFirst("div#muvipro_player_content_id[data-id]")
            ?.attr("data-id")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: extractPostId(doc)

        if (postId.isNullOrBlank()) return output.distinctBy { PutarFlixUtils.decodeKnownRedirect(it.url) }

        val tabIds = linkedSetOf<String>()
        doc.select("div.tab-content-ajax[id], .tab-content-ajax[id], div[id^=muvipro_player_content], div[id*=muvipro][id]")
            .map { it.id().trim() }
            .filter { it.isNotBlank() }
            .forEach { tabIds += it }

        doc.select("ul.muvipro-player-tabs li a[href^=#], .muvipro-player-tabs a[href^=#]")
            .map { it.attr("href").removePrefix("#").trim() }
            .filter { it.isNotBlank() }
            .forEach { tabIds += it }

        // Conservative fallback: if the tabs are generated client-side, try common ids
        // used by Muvipro skins. Bad ids just return empty ajax responses.
        if (tabIds.isEmpty()) {
            PutarFlixSeeds.playerNumbers.forEach { number ->
                tabIds += "muvipro_player_content_$number"
                tabIds += "player-option-$number"
                tabIds += "server-$number"
            }
        }

        for (tabId in tabIds.take(9)) {
            val response = safePostAjaxText(
                url = "${PutarFlixSeeds.MAIN_URL}/wp-admin/admin-ajax.php",
                referer = pageUrl,
                data = mapOf(
                    "action" to "muvipro_player_content",
                    "tab" to tabId,
                    "post_id" to postId
                )
            ) ?: continue

            output += collectServersFromAjaxText(pageUrl, response, "Muvipro ${tabId.substringAfterLast('-').substringAfterLast('_')}")
        }

        return output.distinctBy { PutarFlixUtils.decodeKnownRedirect(it.url) }
    }

    private fun collectAjaxPlayers(pageUrl: String, doc: Document): List<PutarFlixAjaxPlayer> {
        val players = linkedSetOf<PutarFlixAjaxPlayer>()
        val fallbackType = if (pageUrl.contains("/tv/") || pageUrl.contains("/eps/")) "tv" else "movie"

        doc.select("#playeroptionsul li[data-post][data-nume], [data-post][data-nume], [data-type][data-post], [data-postid][data-nume], [data-post-id][data-nume], .dooplay_player_option, .dooplay_player_option[data-post], li[id*=player-option], .player-option[data-post], .player-option-item[data-post], .server-item[data-id], .server[data-post]")
            .forEach { element ->
                val post = firstAttr(element, "data-post", "data-id", "data-postid", "data-post-id", "data-movie", "data-movieid") ?: return@forEach
                val nume = firstAttr(element, "data-nume", "data-server", "data-player", "data-number", "data-no", "data-episode") ?: return@forEach
                val type = firstAttr(element, "data-type", "data-kind") ?: fallbackType
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
        val fullHtml = doc.outerHtml()
        return listOf(
            Regex("""postid-(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""data-(?:post|id|postid|movie|movieid)\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?postId["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?post_id["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?movie_id["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?post["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { regex ->
            regex.find(fullHtml)?.groupValues?.getOrNull(1)
                ?: regex.find(scripts)?.groupValues?.getOrNull(1)
        }
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

        PutarFlixUtils.decodeBase64Payloads(decodedText)
            .filter { it != decodedText }
            .forEach { decodedPayload ->
                output += collectServersFromAjaxText(pageUrl, decodedPayload, label)
            }

        val htmlDoc = Jsoup.parse(decodedText, pageUrl)
        htmlDoc.select("iframe[src], iframe[data-src], iframe[srcdoc], embed[src], source[src], source[data-src], video[src], video[data-src], a[href], [data-src], [data-file], [data-video], [data-url], [data-content], [data-html], [srcdoc]").forEach { element ->
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

        extractWrappedTargetUrl(fixedUrl)?.let { target ->
            if (target != fixedUrl && target !in visited) {
                val resolved = resolveServer(
                    url = target,
                    referer = fixedUrl,
                    label = label.ifBlank { "PutarFlix Wrapped" },
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                    visited = visited,
                    depth = depth + 1
                )
                if (resolved) return true
            }
        }

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

        // Google Drive uc/file/open links are not directly playable as HTML pages.
        // If the built-in extractor did not emit a stream, convert the id to the
        // drive.usercontent download endpoint instead of handing ExoPlayer a landing page.
        if (PutarFlixUtils.isGoogleDriveLandingUrl(fixedUrl)) {
            return resolveGoogleDriveLanding(
                url = fixedUrl,
                referer = referer,
                label = label.ifBlank { "Google Drive" },
                callback = callback
            )
        }

        if (shouldSkipCandidate(fixedUrl, allowPlayerPage = true, allowShortener = true)) return false

        val doc = safeGetDocument(fixedUrl, referer) ?: return false
        val nested = buildList {
            addAll(collectServersFromDocument(fixedUrl, doc))
            addAll(collectAjaxServers(fixedUrl, doc))
            if (PutarFlixUtils.isPutarFlixUrl(fixedUrl)) {
                addAll(collectMuviproServers(fixedUrl, doc))
            }
        }

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

    private suspend fun resolveGoogleDriveLanding(
        url: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = PutarFlixUtils.extractGoogleDriveId(url) ?: return false
        val directUrl = PutarFlixUtils.googleDriveDownloadUrl(id)
        return emitDirect(directUrl, referer, label.ifBlank { "Google Drive" }, callback)
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

        val keys = listOf(
            "data", "id", "file_id", "fileId", "driveId", "googleDriveId", "gdrive",
            "source", "src", "link", "url", "file", "download", "download_url", "downloadLink"
        )

        for (key in keys) {
            extractJsonStringValues(decoded, key).forEach { rawValue ->
                collectFilePressValueCandidate(baseUrl, rawValue, label, servers, secondStepIds)
            }
        }
    }

    private fun collectFilePressValueCandidate(
        baseUrl: String,
        rawValue: String,
        label: String,
        servers: MutableSet<PutarFlixServer>,
        secondStepIds: MutableSet<String>
    ) {
        val value = PutarFlixUtils.decodeUrlRepeated(rawValue.trim())
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
            .replace("\\u002f", "/")
            .trim(' ', '"', '\'', '`')

        if (value.isBlank()) return

        if (value.startsWith("http", true) || value.startsWith("//")) {
            val fixed = PutarFlixUtils.decodeKnownRedirect(PutarFlixUtils.absoluteUrl(baseUrl, value) ?: value)
            servers += PutarFlixServer(label, fixed, baseUrl, "filepress-api-value-url")
            return
        }

        if (!Regex("^[A-Za-z0-9_-]{8,160}$").matches(value)) return

        // FilePress commonly returns its own 24-hex file id first, then a second-step
        // payload may return a Google Drive id. Keep the second step, but do not turn
        // Mongo-like ids into fake Drive URLs because they only create endless loading.
        secondStepIds += value

        if (looksLikeGoogleDriveId(value)) {
            servers += PutarFlixServer(
                label,
                PutarFlixUtils.googleDriveDownloadUrl(value),
                baseUrl,
                "filepress-drive-direct"
            )
            servers += PutarFlixServer(
                label,
                "https://drive.google.com/file/d/$value/view",
                baseUrl,
                "filepress-drive-view"
            )
        }
    }

    private fun looksLikeGoogleDriveId(value: String): Boolean {
        val clean = value.trim()
        if (!Regex("^[A-Za-z0-9_-]{20,120}$").matches(clean)) return false
        if (Regex("^[a-f0-9]{24}$", RegexOption.IGNORE_CASE).matches(clean)) return false
        return true
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


    private fun extractWrappedTargetUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val rawQuery = uri.rawQuery.orEmpty()
        val params = rawQuery.split("&")
            .mapNotNull { part ->
                val key = part.substringBefore("=", "").lowercase()
                val value = part.substringAfter("=", "")
                if (key.isBlank() || value.isBlank()) null else key to value
            }
            .toMap()

        val encoded = listOf("url", "u", "link", "file", "src", "source", "target", "video", "v")
            .firstNotNullOfOrNull { key -> params[key]?.takeIf { it.isNotBlank() } }
            ?: return null

        val decoded = PutarFlixUtils.decodeUrlRepeated(encoded)
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
            .replace("\\u002f", "/")
            .trim()

        if (decoded.startsWith("http", true) || decoded.startsWith("//")) {
            return PutarFlixUtils.absoluteUrl(url, decoded)
        }

        val padded = decoded + "=".repeat((4 - decoded.length % 4) % 4)
        return runCatching { String(java.util.Base64.getDecoder().decode(padded)) }
            .recoverCatching { String(java.util.Base64.getUrlDecoder().decode(padded)) }
            .mapCatching { PutarFlixUtils.decodeUrlRepeated(it) }
            .mapCatching { PutarFlixUtils.absoluteUrl(url, it) }
            .getOrNull()
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
                    timeout = REQUEST_TIMEOUT_MS,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
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
                        "Origin" to PutarFlixSeeds.MAIN_URL,
                        "User-Agent" to USER_AGENT
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
                        "Content-Type" to "application/json; charset=UTF-8",
                        "Origin" to (PutarFlixUtils.originOf(referer) ?: PutarFlixSeeds.MAIN_URL),
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to USER_AGENT
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

        val headers = mapOf(
            "Referer" to referer,
            "Origin" to (PutarFlixUtils.originOf(referer) ?: PutarFlixSeeds.MAIN_URL),
            "User-Agent" to USER_AGENT
        )

        if (type == ExtractorLinkType.M3U8) {
            val generated = runCatching {
                generateM3u8(
                    source = label.ifBlank { "PutarFlix" },
                    streamUrl = url,
                    referer = referer,
                    headers = headers
                )
            }.getOrNull()
            if (!generated.isNullOrEmpty()) {
                generated.forEach(callback)
                return true
            }
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
                this.headers = headers
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


open class PutarFlixHostExtractor : ExtractorApi() {
    override var name = "PutarFlix Host"
    override var mainUrl = ""
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = url.replace(" ", "%20")
        val domain = runCatching { "https://${URI(pageUrl).host}" }.getOrDefault(mainUrl.ifBlank { pageUrl })
        val response = runCatching {
            app.get(
                pageUrl,
                referer = referer ?: domain,
                headers = putarFlixExtractorHeaders(referer ?: domain),
                timeout = 15L
            )
        }.getOrNull() ?: return

        val html = response.text.putarFlixCleanEscaped()
        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        if (html.trimStart().startsWith("#EXTM3U")) {
            putarFlixEmitExtractorLink(name, pageUrl, referer ?: domain, callback)
            return
        }

        response.document.select(
            "meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], " +
                "meta[name=twitter:player], iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                "video[src], video[data-src], video source[src], source[src], embed[src], object[data], " +
                "a[href], [data-src], [data-file], [data-video], [data-url], [data-embed]"
        ).forEach { element ->
            val raw = element.attr("content")
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            putarFlixAddExtractorCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        putarFlixExtractExtractorUrls(html).forEach { raw ->
            putarFlixAddExtractorCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            putarFlixExtractExtractorUrls(unpacked.putarFlixCleanEscaped()).forEach { raw ->
                putarFlixAddExtractorCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        directLinks.distinct().forEach { link ->
            putarFlixEmitExtractorLink(name, link, pageUrl, callback)
        }

        if (directLinks.isNotEmpty()) return

        embedLinks
            .filterNot { it == pageUrl }
            .filterNot { putarFlixIsJunkExtractorUrl(it) }
            .distinct()
            .take(6)
            .forEach { embed ->
                val nested = runCatching {
                    app.get(
                        embed,
                        referer = pageUrl,
                        headers = putarFlixExtractorHeaders(pageUrl),
                        timeout = 15L
                    ).text.putarFlixCleanEscaped()
                }.getOrNull().orEmpty()

                putarFlixExtractExtractorUrls(nested).forEach { raw ->
                    val fixed = putarFlixNormalizeExtractorUrl(raw, embed).replace(".txt", ".m3u8")
                    if (fixed.putarFlixIsDirectVideoUrl()) {
                        putarFlixEmitExtractorLink(name, fixed, embed, callback)
                    }
                }

                val nestedUnpacked = runCatching {
                    if (!getPacked(nested).isNullOrEmpty()) getAndUnpack(nested) else null
                }.getOrNull()

                if (!nestedUnpacked.isNullOrBlank()) {
                    putarFlixExtractExtractorUrls(nestedUnpacked.putarFlixCleanEscaped()).forEach { raw ->
                        val fixed = putarFlixNormalizeExtractorUrl(raw, embed).replace(".txt", ".m3u8")
                        if (fixed.putarFlixIsDirectVideoUrl()) {
                            putarFlixEmitExtractorLink(name, fixed, embed, callback)
                        }
                    }
                }
            }
    }
}

class PutarFlixEmturbovid : PutarFlixHostExtractor() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
}

class PutarFlixF16 : PutarFlixHostExtractor() {
    override var name = "F16"
    override var mainUrl = "https://f16px.com"
}

class PutarFlixMajorplay : PutarFlixHostExtractor() {
    override var name = "Majorplay"
    override var mainUrl = "https://majorplay.net"
}

class PutarFlixE2eMajorplay : PutarFlixHostExtractor() {
    override var name = "Majorplay E2E"
    override var mainUrl = "https://e2e.majorplay.net"
}

class PutarFlixM3u8Majorplay : PutarFlixHostExtractor() {
    override var name = "Majorplay M3U8"
    override var mainUrl = "https://m3u8.majorplay.net"
}

class PutarFlixP2P : PutarFlixHostExtractor() {
    override var name = "P2P"
    override var mainUrl = "https://cloud.hownetwork.xyz"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        super.getUrl(url, referer, subtitleCallback, callback)

        val id = url.substringAfter("id=", "")
            .substringBefore("&")
            .substringBefore("?")
            .trim()
        if (id.isBlank()) return

        val text = runCatching {
            app.post(
                "$mainUrl/api2.php?id=$id",
                data = mapOf(
                    "r" to (referer ?: "https://playeriframe.sbs/"),
                    "d" to "cloud.hownetwork.xyz"
                ),
                referer = url,
                headers = putarFlixExtractorHeaders(url) + mapOf("X-Requested-With" to "XMLHttpRequest"),
                timeout = 15L
            ).text.putarFlixCleanEscaped()
        }.getOrNull().orEmpty()

        putarFlixParseJsonStream(text)?.let { stream ->
            putarFlixEmitExtractorLink(name, putarFlixNormalizeExtractorUrl(stream, url), mainUrl, callback)
        }

        putarFlixExtractExtractorUrls(text).forEach { raw ->
            putarFlixEmitExtractorLink(name, putarFlixNormalizeExtractorUrl(raw, url), mainUrl, callback)
        }
    }
}

class PutarFlixJeniusplay : PutarFlixHostExtractor() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        super.getUrl(url, referer, subtitleCallback, callback)

        val pageUrl = url.replace(" ", "%20")
        val hash = pageUrl.substringAfter("data=", pageUrl.substringAfterLast("/"))
            .substringBefore("&")
            .substringBefore("?")
            .trim()
        if (hash.isBlank()) return

        listOf(
            "$mainUrl/player/ajax.php?data=$hash&do=getVideo",
            "$mainUrl/player/index.php?data=$hash&do=getVideo"
        ).forEach { endpoint ->
            val text = runCatching {
                app.post(
                    url = endpoint,
                    data = mapOf("hash" to hash, "r" to (referer ?: "")),
                    referer = pageUrl,
                    headers = putarFlixExtractorHeaders(pageUrl) + mapOf("X-Requested-With" to "XMLHttpRequest"),
                    timeout = 15L
                ).text.putarFlixCleanEscaped()
            }.getOrNull().orEmpty()

            putarFlixParseJsonStream(text)?.let { stream ->
                putarFlixEmitExtractorLink(name, putarFlixNormalizeExtractorUrl(stream, pageUrl), pageUrl, callback)
            }
            putarFlixExtractExtractorUrls(text).forEach { raw ->
                putarFlixEmitExtractorLink(name, putarFlixNormalizeExtractorUrl(raw, pageUrl), pageUrl, callback)
            }
        }
    }
}

class PutarFlixHglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

class PutarFlixGhbrisk : StreamWishExtractor() {
    override val name = "Ghbrisk"
    override val mainUrl = "https://ghbrisk.com"
}

class PutarFlixDhcplay : StreamWishExtractor() {
    override var name = "DHC Play"
    override var mainUrl = "https://dhcplay.com"
}

class PutarFlixStreamcasthub : VidStack() {
    override var name = "Streamcasthub"
    override var mainUrl = "https://live.streamcasthub.store"
    override var requiresReferer = true
}

class PutarFlixDm21embed : VidStack() {
    override var name = "Dm21embed"
    override var mainUrl = "https://dm21.embed4me.vip"
    override var requiresReferer = true
}

class PutarFlixDm21upns : VidStack() {
    override var name = "Dm21upns"
    override var mainUrl = "https://dm21.upns.live"
    override var requiresReferer = true
}

class PutarFlixDm21 : VidStack() {
    override var name = "Dm21"
    override var mainUrl = "https://dm21.embed4me.vip"
    override var requiresReferer = true
}

class PutarFlixMeplayer : VidStack() {
    override var name = "Meplayer"
    override var mainUrl = "https://video.4meplayer.com"
    override var requiresReferer = true
}

private fun putarFlixAddExtractorCandidate(
    raw: String,
    baseUrl: String,
    directLinks: MutableSet<String>,
    embedLinks: MutableSet<String>
) {
    if (raw.isBlank()) return
    val fixed = putarFlixNormalizeExtractorUrl(raw.putarFlixCleanEscaped(), baseUrl)
        .replace(".txt", ".m3u8")
        .trim()
    if (fixed.isBlank() || putarFlixIsJunkExtractorUrl(fixed)) return

    when {
        fixed.putarFlixIsDirectVideoUrl() -> directLinks.add(fixed)
        fixed.startsWith("http", true) && putarFlixIsKnownExtractorHost(fixed) -> embedLinks.add(fixed)
        fixed.startsWith("http", true) && fixed.contains("embed", true) -> embedLinks.add(fixed)
        fixed.startsWith("http", true) && fixed.contains("player", true) -> embedLinks.add(fixed)
        fixed.startsWith("http", true) && fixed.contains("stream", true) -> embedLinks.add(fixed)
    }
}

private suspend fun putarFlixEmitExtractorLink(
    source: String,
    streamUrl: String,
    referer: String,
    callback: (ExtractorLink) -> Unit
) {
    val fixed = streamUrl.putarFlixCleanEscaped().replace(".txt", ".m3u8")
    if (putarFlixIsJunkExtractorUrl(fixed)) return

    if (fixed.contains(".m3u8", true)) {
        generateM3u8(
            source = source,
            streamUrl = fixed,
            referer = referer,
            headers = putarFlixExtractorHeaders(referer)
        ).forEach(callback)
    } else {
        callback(
            newExtractorLink(
                source = source,
                name = source,
                url = fixed,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(fixed).takeIf { it != Qualities.Unknown.value }
                    ?: Qualities.Unknown.value
                this.headers = putarFlixExtractorHeaders(referer)
            }
        )
    }
}

private fun putarFlixParseJsonStream(text: String): String? {
    return runCatching {
        val json = JSONObject(text)
        listOf(
            json.optString("file"),
            json.optString("link"),
            json.optString("videoSource"),
            json.optString("securedLink"),
            json.optString("url"),
            json.optString("src")
        ).firstOrNull { it.isNotBlank() }
    }.getOrNull()
}

private fun putarFlixExtractExtractorUrls(text: String): List<String> {
    val clean = text.putarFlixCleanEscaped()
    val urls = linkedSetOf<String>()

    Regex(
        """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { it.value.putarFlixCleanEscaped().replace(".txt", ".m3u8") }
        .filterNot { putarFlixIsJunkExtractorUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { "https:${it.value.putarFlixCleanEscaped().replace(".txt", ".m3u8")}" }
        .filterNot { putarFlixIsJunkExtractorUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt|emturbovid|hownetwork|f16|jeniusplay|majorplay|streamwish|filemoon|dood|streamtape|vidhide|voe|mixdrop|play\.putar\.in|gdplayer|awstream|megaplay|luluvdo|filedon|blogger|blogspot|streamplay|movearnpre)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
        .map { it.putarFlixCleanEscaped().replace(".txt", ".m3u8") }
        .filterNot { putarFlixIsJunkExtractorUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """https?://[^"'\\\s<>]+?(?:emturbovid|hownetwork|playeriframe|f16|jeniusplay|majorplay|streamwish|filemoon|dood|streamtape|vidhide|vidguard|voe|mixdrop|hglink|ghbrisk|dhcplay|streamcasthub|embed4me|upns\.live|4meplayer|play\.putar\.in|gdplayer|awstream|megaplay|luluvdo|filedon|blogger|blogspot|streamplay|movearnpre|vidsrc|embed|player|stream)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { it.value.putarFlixCleanEscaped().replace(".txt", ".m3u8") }
        .filterNot { putarFlixIsJunkExtractorUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """//[^"'\\\s<>]+?(?:emturbovid|hownetwork|playeriframe|f16|jeniusplay|majorplay|streamwish|filemoon|dood|streamtape|vidhide|vidguard|voe|mixdrop|hglink|ghbrisk|dhcplay|streamcasthub|embed4me|upns\.live|4meplayer|play\.putar\.in|gdplayer|awstream|megaplay|luluvdo|filedon|blogger|blogspot|streamplay|movearnpre|vidsrc|embed|player|stream)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { "https:${it.value.putarFlixCleanEscaped().replace(".txt", ".m3u8")}" }
        .filterNot { putarFlixIsJunkExtractorUrl(it) }
        .forEach { urls.add(it) }

    PutarFlixUtils.decodeBase64Payloads(clean)
        .filter { it != clean }
        .forEach { decodedPayload ->
            putarFlixExtractExtractorUrls(decodedPayload).forEach { urls.add(it) }
        }

    Regex(
        """(?:file|src|source|url|videoSource|videoUrl|video_url|playUrl|play_url|hls|hlsUrl|hls_url|stream|streamUrl|stream_url|embedUrl|embed_url|iframe|content|playlist)\s*[:=]\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.putarFlixCleanEscaped().replace(".txt", ".m3u8") }
        .filter {
            it.putarFlixIsDirectVideoUrl() ||
                putarFlixIsKnownExtractorHost(it) ||
                it.contains("embed", true) ||
                it.contains("player", true)
        }
        .filterNot { putarFlixIsJunkExtractorUrl(it) }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun putarFlixIsKnownExtractorHost(url: String): Boolean {
    val value = url.lowercase()
    return listOf(
        "emturbovid",
        "hownetwork",
        "playeriframe",
        "cloud.",
        "p2p",
        "f16",
        "jeniusplay",
        "majorplay",
        "e2e.majorplay",
        "m3u8.majorplay",
        "streamwish",
        "filemoon",
        "dood",
        "streamtape",
        "vidhide",
        "voe",
        "mixdrop",
        "hglink",
        "ghbrisk",
        "dhcplay",
        "streamcasthub",
        "embed4me",
        "upns.live",
        "4meplayer",
        "play.putar.in",
        "gdplayer",
        "z.awstream.net",
        "awstream",
        "megaplay",
        "luluvdo",
        "filedon",
        "blogger.com",
        "blogspot",
        "play.streamplay.co.in",
        "movearnpre",
        "vidsrc",
        "googlevideo",
        "ok.ru",
        "rumble",
        "sbfull",
        "listeamed",
        "streamhide",
        "vidlink"
    ).any { value.contains(it) }
}

private fun putarFlixIsJunkExtractorUrl(url: String): Boolean {
    val value = url.lowercase()
    return value.isBlank() ||
        value.contains("facebook.com") ||
        value.contains("twitter.com") ||
        value.contains("telegram") ||
        value.contains("whatsapp") ||
        value.contains("mailto:") ||
        value.contains("trailer") ||
        value.contains("youtube.com") ||
        value.contains("youtu.be") ||
        value.contains("googletagmanager") ||
        value.contains("cloudflareinsights") ||
        value.contains("recaptcha") ||
        value.contains("doubleclick") ||
        value.contains("googlesyndication") ||
        value.contains("/ads/") ||
        value.contains("banner") ||
        value.contains("tracking") ||
        value.contains("analytics")
}

private fun String.putarFlixIsDirectVideoUrl(): Boolean {
    return contains(".m3u8", true) || contains(".mp4", true) || contains(".webm", true)
}

private fun putarFlixNormalizeExtractorUrl(url: String, baseUrl: String): String {
    val clean = url.putarFlixCleanEscaped().trim()
    return when {
        clean.isBlank() -> ""
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> "${putarFlixOrigin(baseUrl)}$clean"
        else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
    }
}

private fun putarFlixOrigin(url: String): String {
    return runCatching {
        val uri = URI(url)
        "${uri.scheme ?: "https"}://${uri.host}"
    }.getOrDefault(url.substringBeforeLast("/"))
}

private fun putarFlixExtractorHeaders(referer: String): Map<String, String> {
    return mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Origin" to putarFlixOrigin(referer)
    )
}

private fun String.putarFlixCleanEscaped(): String {
    return replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\\"", "\"")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003a", ":")
        .replace("\\u002f", "/")
        .replace("\\\\/", "/")
}


class PutarFlixPlayPutarIn : ExtractorApi() {
    override var name = "PlayPutarIn"
    override var mainUrl = "https://play.putar.in"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val encoded = url.substringAfter("?url=", "").substringBefore("&").trim()
        val target = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
            .putarFlixCleanEscaped()
            .trim()

        if (target.startsWith("http", true)) {
            val loaded = runCatching { loadExtractor(target, url, subtitleCallback, callback) }.getOrDefault(false)
            if (!loaded) PutarFlixHostExtractor().getUrl(target, url, subtitleCallback, callback)
        }

        PutarFlixHostExtractor().getUrl(url, referer ?: mainUrl, subtitleCallback, callback)
    }
}

class PutarFlixLk21PlayerPage : ExtractorApi() {
    override var name = "Lk21Player"
    override var mainUrl = "https://playeriframe.sbs"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = runCatching {
            app.get(url, referer = referer, headers = putarFlixExtractorHeaders(referer ?: mainUrl), timeout = 15L)
        }.getOrNull() ?: return

        response.document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val raw = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            val src = putarFlixNormalizeExtractorUrl(raw, url)
            if (src.isBlank() || putarFlixIsJunkExtractorUrl(src)) return@forEach
            val loaded = runCatching { loadExtractor(src, url, subtitleCallback, callback) }.getOrDefault(false)
            if (!loaded) PutarFlixHostExtractor().getUrl(src, url, subtitleCallback, callback)
        }

        putarFlixExtractExtractorUrls(response.text).forEach { raw ->
            val fixed = putarFlixNormalizeExtractorUrl(raw, url).replace(".txt", ".m3u8")
            if (fixed.putarFlixIsDirectVideoUrl()) {
                putarFlixEmitExtractorLink(name, fixed, url, callback)
            }
        }
    }
}

class PutarFlixGdplayer : ExtractorApi() {
    override var name = "Gdplayer"
    override var mainUrl = "https://gdplayer.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = runCatching { app.get(url, referer = referer, timeout = 15L).document }.getOrNull() ?: return
        val script = doc.selectFirst("script:containsData(kaken), script:containsData(player =)")?.data().orEmpty()
        val kaken = Regex("""kaken\s*=\s*["']([^"']+)""").find(script)?.groupValues?.getOrNull(1).orEmpty()
        if (kaken.isNotBlank()) {
            val jsonText = runCatching {
                app.get(
                    "$mainUrl/api/?$kaken=&_=${System.currentTimeMillis()}",
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    referer = url,
                    timeout = 15L
                ).text
            }.getOrNull().orEmpty()
            val files = linkedSetOf<String>()
            putarFlixExtractExtractorUrls(jsonText).forEach { files += it }
            Regex("""[\"']file[\"']\s*:\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
                .findAll(jsonText.putarFlixCleanEscaped())
                .mapNotNull { it.groupValues.getOrNull(1) }
                .forEach { files += it }
            files.forEach { file ->
                putarFlixEmitExtractorLink(name, putarFlixNormalizeExtractorUrl(file, url), mainUrl, callback)
            }
        }

        PutarFlixHostExtractor().getUrl(url, referer ?: mainUrl, subtitleCallback, callback)
    }
}

class PutarFlixAWSStream : ExtractorApi() {
    override var name = "AWSStream"
    override var mainUrl = "https://z.awstream.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hash = url.substringAfterLast("/").substringBefore("?").trim()
        if (hash.isNotBlank()) {
            val response = runCatching {
                app.post(
                    "$mainUrl/player/index.php?data=$hash&do=getVideo",
                    referer = url,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    data = mapOf("hash" to hash, "r" to mainUrl),
                    timeout = 15L
                ).text
            }.getOrNull().orEmpty()
            putarFlixParseJsonStream(response)?.let { stream ->
                putarFlixEmitExtractorLink(name, putarFlixNormalizeExtractorUrl(stream, url), mainUrl, callback)
            }
        }
        PutarFlixHostExtractor().getUrl(url, referer ?: mainUrl, subtitleCallback, callback)
    }
}

class PutarFlixMegaPlay : ExtractorApi() {
    override var name = "MegaPlay"
    override var mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = runCatching { app.get(url, referer = referer, timeout = 15L).document }.getOrNull() ?: return
        val id = doc.selectFirst("#megaplay-player[data-id], [data-id]")?.attr("data-id").orEmpty()
        if (id.isNotBlank()) {
            val jsonText = runCatching { app.get("$mainUrl/stream/getSources?id=$id", referer = url, timeout = 15L).text }.getOrNull().orEmpty()
            val streams = linkedSetOf<String>()
            putarFlixExtractExtractorUrls(jsonText).forEach { streams += it }
            Regex("""[\"']file[\"']\s*:\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
                .findAll(jsonText.putarFlixCleanEscaped())
                .mapNotNull { it.groupValues.getOrNull(1) }
                .forEach { streams += it }
            streams.forEach { stream ->
                putarFlixEmitExtractorLink(name, putarFlixNormalizeExtractorUrl(stream, url), mainUrl, callback)
            }
        }
        PutarFlixHostExtractor().getUrl(url, referer ?: mainUrl, subtitleCallback, callback)
    }
}

class PutarFlixLuluStream : ExtractorApi() {
    override var name = "LuluStream"
    override var mainUrl = "https://luluvdo.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileCode = url.substringAfterLast("/").substringBefore("?").trim()
        if (fileCode.isNotBlank()) {
            val doc = runCatching {
                app.post(
                    "$mainUrl/dl",
                    referer = url,
                    data = mapOf("op" to "embed", "file_code" to fileCode, "auto" to "1", "referer" to (referer ?: "")),
                    timeout = 15L
                ).document
            }.getOrNull()
            val script = doc?.selectFirst("script:containsData(vplayer), script:containsData(file:)")?.data().orEmpty()
            Regex("""file\s*:\s*["']([^"']+)""").find(script)?.groupValues?.getOrNull(1)?.let { stream ->
                putarFlixEmitExtractorLink(name, putarFlixNormalizeExtractorUrl(stream, url), mainUrl, callback)
            }
        }
        PutarFlixHostExtractor().getUrl(url, referer ?: mainUrl, subtitleCallback, callback)
    }
}

class PutarFlixFiledon : PutarFlixHostExtractor() {
    override var name = "Filedon"
    override var mainUrl = "https://filedon.co"
}

class PutarFlixBloggerVideo : PutarFlixHostExtractor() {
    override var name = "BloggerVideo"
    override var mainUrl = "https://www.blogger.com"
}

class PutarFlixPlayStreamplay : PutarFlixHostExtractor() {
    override var name = "PlayStreamplay"
    override var mainUrl = "https://play.streamplay.co.in"
}

class PutarFlixMovearnpre : PutarFlixHostExtractor() {
    override var name = "Movearnpre"
    override var mainUrl = "https://movearnpre.com"
}
