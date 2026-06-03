package com.nodrakorid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private data class NoDrakorIDAbyssPayload(
    val slug: String,
    val md5Id: String,
    val userId: String,
    val media: String
)

internal object NoDrakorIDExtractor {
    private const val EXTRACT_TIMEOUT_MS = 45_000L
    private const val REQUEST_TIMEOUT_MS = 12_000L
    private const val LOAD_EXTRACTOR_TIMEOUT_MS = 14_000L
    private const val MAX_DEPTH = 5

    private val directVideoRegex = Regex(
        """https?:\\?/\\?/[^\"'<>)\]\[\s]+?(?:(?:\.(?:m3u8|mp4|mkv|mpd|webm)(?:\?[^\"'<>)\]\[\s]+)?)|(?:\?[^\"'<>)\]\[\s]*(?:m3u8|mp4|mkv|mpd|webm)[^\"'<>)\]\[\s]*))""",
        RegexOption.IGNORE_CASE
    )
    private val jsonEmbedRegex = Regex(
        """["'](?:embed_url|file|url|source|src|link|download|download_url|direct_link|downloadLink)["']\s*:\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val rawHtmlRegex = Regex("""<iframe|<source|<video|&lt;iframe|&lt;source|&lt;video""", RegexOption.IGNORE_CASE)

    private val payloadAttributes = listOf(
        "content", "src", "data-src", "data-lazy-src", "data-litespeed-src", "data-iframe", "data-embed",
        "data-link", "data-url", "data-video", "data-video-url", "data-stream", "data-stream-url", "data-file",
        "data-href", "data-content", "data-html", "data-frame", "data-player", "data-play", "data-server",
        "data-hls", "data-m3u8", "value", "href", "srcdoc"
    )

    private val playerContainers = listOf(
        "#player", "#player2", "#video", ".player", ".player-area", ".playex", ".movieplay", ".video-content",
        ".responsive-embed", ".embed-responsive", ".pembed", ".dooplay_player", ".dooplay_player_content",
        ".dooplay_player_option", "#playeroptionsul", ".server", ".servers", ".server-item", ".player-option",
        ".player-option-item", ".muvipro-player-tabs", ".gmr-embed-responsive", ".tab-content", ".tab-pane",
        ".download", ".dllinks", "#download", ".entry-content", "article"
    ).joinToString(",")

    suspend fun extract(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
            extractInternal(data.trim(), subtitleCallback, callback)
        } ?: false
    }

    private suspend fun extractInternal(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startUrl = NoDrakorIDUtils.decodeKnownRedirect(data)
        if (startUrl.isBlank()) return false

        if (!NoDrakorIDUtils.isNoDrakorUrl(startUrl)) {
            if (NoDrakorIDUtils.looksDirectVideo(startUrl)) {
                return emitDirect(startUrl, NoDrakorIDSepeda.MAIN_URL, "NoDrakorID Direct", callback)
            }
            return resolveServer(startUrl, NoDrakorIDSepeda.MAIN_URL, "NoDrakorID External", subtitleCallback, callback)
        }

        val base = startUrl.substringBefore("#").substringBefore("?")
        val playerPages = buildList {
            add(base)
            NoDrakorIDSepeda.playerNumbers.forEach { num -> if (num != "1") add("$base?player=$num") }
        }.distinct()

        val candidates = linkedSetOf<NoDrakorIDServer>()
        for (page in playerPages) {
            val doc = safeGetDocument(page, NoDrakorIDSepeda.MAIN_URL) ?: continue
            candidates += collectServersFromDocument(page, doc)
            candidates += collectAjaxServers(page, doc)
            candidates += collectMuviproServers(page, doc)
        }

        var found = false
        val visited = linkedSetOf<String>()
        for (server in candidates.sortedBy { rankServer(it.url) }.distinctBy { NoDrakorIDUtils.decodeKnownRedirect(it.url) }) {
            val fixed = NoDrakorIDUtils.decodeKnownRedirect(server.url)
            if (shouldSkipCandidate(fixed, allowInternalPage = true, allowShortener = true)) continue
            if (resolveServer(fixed, server.referer, server.label, subtitleCallback, callback, visited, 0)) found = true
        }
        return found
    }

    private fun collectServersFromDocument(pageUrl: String, doc: Document): List<NoDrakorIDServer> {
        val servers = linkedSetOf<NoDrakorIDServer>()

        doc.select("iframe[src], iframe[data-src], iframe[srcdoc], embed[src], video[src], video[data-src], source[src], source[data-src], meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player]")
            .forEach { addServerFromElement(servers, pageUrl, it, allowInternalPage = true, allowShortener = true) }

        doc.select(playerContainers).forEach { container ->
            container.select("iframe[src], iframe[data-src], iframe[srcdoc], embed[src], video[src], video[data-src], source[src], source[data-src], a[href], button, div, li, span, [data-content], [data-html], [data-url], [data-link], [data-file], [data-video]")
                .forEach { addServerFromElement(servers, pageUrl, it, allowInternalPage = true, allowShortener = true) }
        }

        doc.select("[srcdoc], [data-content], [data-html], [data-iframe], [data-embed], [data-player], [data-url], [data-video], [data-file]").forEach { element ->
            payloadAttributes.mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                .forEach { payload -> servers += collectServersFromText(pageUrl, payload, NoDrakorIDUtils.extractLabelNear(element)) }
        }

        doc.select("a[href]").forEach { anchor ->
            val absolute = NoDrakorIDUtils.absoluteUrl(pageUrl, anchor.attr("href")) ?: return@forEach
            val decoded = NoDrakorIDUtils.decodeKnownRedirect(absolute)
            if ((NoDrakorIDUtils.isKnownPlayableHost(decoded) || NoDrakorIDUtils.looksDirectVideo(decoded) || NoDrakorIDUtils.isShortenerUrl(decoded)) &&
                !shouldSkipCandidate(decoded, allowInternalPage = true, allowShortener = true)
            ) {
                servers += NoDrakorIDServer(NoDrakorIDUtils.extractLabelNear(anchor), decoded, pageUrl, "anchor")
            }
        }

        val scriptText = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        val normalizedScript = normalizeText(scriptText)
        collectFromRawText(pageUrl, normalizedScript, "NoDrakorID Script", servers)

        val unpacked = runCatching {
            if (!getPacked(normalizedScript).isNullOrEmpty()) getAndUnpack(normalizedScript) else null
        }.getOrNull()
        if (!unpacked.isNullOrBlank()) collectFromRawText(pageUrl, unpacked, "NoDrakorID Unpacked", servers)

        val fullHtml = normalizeText(doc.outerHtml())
        collectFromRawText(pageUrl, fullHtml, "NoDrakorID HTML", servers)
        NoDrakorIDUtils.decodeBase64Payloads(fullHtml).forEach { decoded ->
            collectFromRawText(pageUrl, decoded, "NoDrakorID Encoded", servers)
        }

        return servers.distinctBy { NoDrakorIDUtils.decodeKnownRedirect(it.url) }
    }

    private fun collectFromRawText(pageUrl: String, text: String, label: String, servers: MutableSet<NoDrakorIDServer>) {
        directVideoRegex.findAll(text).forEach { match ->
            val url = NoDrakorIDUtils.absoluteUrl(pageUrl, match.value) ?: return@forEach
            if (!shouldSkipCandidate(url, allowInternalPage = true, allowShortener = true)) servers += NoDrakorIDServer(label, url, pageUrl, "direct")
        }
        jsonEmbedRegex.findAll(text).forEach { match ->
            val url = NoDrakorIDUtils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            val fixed = NoDrakorIDUtils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(fixed, allowInternalPage = true, allowShortener = true)) servers += NoDrakorIDServer(label, fixed, pageUrl, "json")
        }
        NoDrakorIDUtils.extractUrlsFromText(pageUrl, text).forEach { raw ->
            val fixed = NoDrakorIDUtils.decodeKnownRedirect(raw)
            if (!shouldSkipCandidate(fixed, allowInternalPage = true, allowShortener = true)) servers += NoDrakorIDServer(label, fixed, pageUrl, "url")
        }
        if (rawHtmlRegex.containsMatchIn(text)) {
            val doc = Jsoup.parse(NoDrakorIDUtils.decodeHtml(text))
            doc.select("iframe[src], iframe[data-src], iframe[srcdoc], video[src], source[src], a[href]").forEach { element ->
                addServerFromElement(servers, pageUrl, element, allowInternalPage = true, allowShortener = true)
            }
        }
    }

    private fun addServerFromElement(
        servers: MutableSet<NoDrakorIDServer>,
        pageUrl: String,
        element: Element,
        allowInternalPage: Boolean,
        allowShortener: Boolean
    ) {
        val label = NoDrakorIDUtils.extractLabelNear(element)
        payloadAttributes.mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }.distinct().forEach { payload ->
            val raw = NoDrakorIDUtils.cleanUrlText(payload)
            if (raw.isBlank()) return@forEach
            if (rawHtmlRegex.containsMatchIn(raw) || (raw.contains("http", true) && raw.contains("src=", true))) {
                servers += collectServersFromText(pageUrl, raw, label)
                return@forEach
            }
            NoDrakorIDUtils.decodeBase64Payloads(raw).forEach { decoded -> servers += collectServersFromText(pageUrl, decoded, label) }
            val url = NoDrakorIDUtils.absoluteUrl(pageUrl, raw) ?: return@forEach
            val fixed = NoDrakorIDUtils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(fixed, allowInternalPage, allowShortener)) {
                servers += NoDrakorIDServer(label, fixed, pageUrl, element.tagName())
            }
        }
    }

    private fun collectServersFromText(pageUrl: String, text: String, label: String): List<NoDrakorIDServer> {
        val servers = linkedSetOf<NoDrakorIDServer>()
        collectFromRawText(pageUrl, normalizeText(text), label, servers)
        return servers.toList()
    }

    private suspend fun collectAjaxServers(pageUrl: String, doc: Document): List<NoDrakorIDServer> {
        val players = collectAjaxPlayers(pageUrl, doc)
        if (players.isEmpty()) return emptyList()
        val output = linkedSetOf<NoDrakorIDServer>()
        for (player in players) {
            for (action in NoDrakorIDSepeda.ajaxActions) {
                val forms = listOf(
                    mapOf("action" to action, "post" to player.postId, "nume" to player.nume, "type" to player.type),
                    mapOf("action" to action, "post_id" to player.postId, "server" to player.nume, "type" to player.type),
                    mapOf("action" to action, "id" to player.postId, "nume" to player.nume, "type" to player.type),
                    mapOf("action" to action, "movie" to player.postId, "player" to player.nume, "type" to player.type),
                    mapOf("action" to action, "tab" to "player-option-${player.nume}", "post_id" to player.postId)
                )
                for (form in forms) {
                    val response = safePostAjaxText("${NoDrakorIDSepeda.MAIN_URL}/wp-admin/admin-ajax.php", pageUrl, form) ?: continue
                    val servers = collectServersFromText(pageUrl, response, player.label)
                    if (servers.isNotEmpty()) {
                        output += servers
                        break
                    }
                }
                if (output.isNotEmpty()) break
            }
        }
        return output.distinctBy { NoDrakorIDUtils.decodeKnownRedirect(it.url) }
    }

    private fun collectAjaxPlayers(pageUrl: String, doc: Document): List<NoDrakorIDAjaxPlayer> {
        val players = linkedSetOf<NoDrakorIDAjaxPlayer>()
        val fallbackType = if (pageUrl.contains("/tv/", true) || pageUrl.contains("/episode/", true)) "tv" else "movie"
        doc.select("#playeroptionsul li[data-post][data-nume], [data-post][data-nume], [data-type][data-post], [data-postid][data-nume], [data-post-id][data-nume], .dooplay_player_option, .dooplay_player_option[data-post], li[id*=player-option], .player-option[data-post], .player-option-item[data-post], .server-item[data-id], .server[data-post]")
            .forEach { element ->
                val post = firstAttr(element, "data-post", "data-id", "data-postid", "data-post-id", "data-movie", "data-movieid") ?: return@forEach
                val nume = firstAttr(element, "data-nume", "data-server", "data-player", "data-number", "data-no", "data-episode") ?: return@forEach
                val type = firstAttr(element, "data-type", "data-kind") ?: fallbackType
                players += NoDrakorIDAjaxPlayer(post, type, nume, NoDrakorIDUtils.extractLabelNear(element))
            }

        val postId = extractPostId(doc)
        if (!postId.isNullOrBlank()) {
            NoDrakorIDSepeda.playerNumbers.forEach { nume ->
                players += NoDrakorIDAjaxPlayer(postId, fallbackType, nume, "Server $nume")
            }
        }
        return players.distinctBy { "${it.postId}:${it.type}:${it.nume}" }.take(12)
    }

    private suspend fun collectMuviproServers(pageUrl: String, doc: Document): List<NoDrakorIDServer> {
        val output = linkedSetOf<NoDrakorIDServer>()
        doc.select("ul.muvipro-player-tabs li a[href], .muvipro-player-tabs a[href], a[href*='?player='], a[href*='&player=']").forEach { tab ->
            val tabUrl = NoDrakorIDUtils.absoluteUrl(pageUrl, tab.attr("href")) ?: return@forEach
            if (!shouldSkipCandidate(tabUrl, allowInternalPage = true, allowShortener = true)) output += NoDrakorIDServer(NoDrakorIDUtils.extractLabelNear(tab), tabUrl, pageUrl, "muvipro-tab")
        }

        val postId = extractPostId(doc) ?: return output.toList()
        val tabIds = linkedSetOf<String>()
        doc.select("div.tab-content-ajax[id], .tab-content-ajax[id], div[id^=muvipro_player_content], div[id*=muvipro][id]").map { it.id() }.filter { it.isNotBlank() }.forEach { tabIds += it }
        doc.select("ul.muvipro-player-tabs li a[href^=#], .muvipro-player-tabs a[href^=#]").map { it.attr("href").removePrefix("#") }.filter { it.isNotBlank() }.forEach { tabIds += it }
        if (tabIds.isEmpty()) NoDrakorIDSepeda.playerNumbers.forEach { number -> tabIds += "muvipro_player_content_$number" }

        for (tabId in tabIds.take(10)) {
            val response = safePostAjaxText(
                "${NoDrakorIDSepeda.MAIN_URL}/wp-admin/admin-ajax.php",
                pageUrl,
                mapOf("action" to "muvipro_player_content", "tab" to tabId, "post_id" to postId)
            ) ?: continue
            output += collectServersFromText(pageUrl, response, "Muvipro ${tabId.substringAfterLast('_')}")
        }
        return output.distinctBy { NoDrakorIDUtils.decodeKnownRedirect(it.url) }
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
        if (depth > MAX_DEPTH) return false
        val fixedUrl = NoDrakorIDUtils.decodeKnownRedirect(url)
        if (!fixedUrl.startsWith("http", true) || !visited.add(fixedUrl)) return false
        if (NoDrakorIDUtils.isBadAssetUrl(fixedUrl)) return false

        if (NoDrakorIDUtils.looksDirectVideo(fixedUrl)) {
            return emitDirect(fixedUrl, referer, label, callback)
        }

        if (fixedUrl.contains("abyssplayer.com", true) || fixedUrl.contains("abyss.to", true)) {
            if (resolveAbyssPlayer(fixedUrl, referer, callback)) return true
        }

        if (fixedUrl.lowercase().contains("filepress") && fixedUrl.contains("/file/")) {
            resolveFilePress(fixedUrl, referer, label, subtitleCallback, callback, visited, depth + 1)?.let { if (it) return true }
        }

        if (safeLoadExtractor(fixedUrl, referer, subtitleCallback, callback)) return true

        if (shouldSkipCandidate(fixedUrl, allowInternalPage = true, allowShortener = true)) return false
        val doc = safeGetDocument(fixedUrl, referer) ?: return false
        val nested = linkedSetOf<NoDrakorIDServer>()
        nested += collectServersFromDocument(fixedUrl, doc)
        nested += collectAjaxServers(fixedUrl, doc)
        if (NoDrakorIDUtils.isNoDrakorUrl(fixedUrl)) nested += collectMuviproServers(fixedUrl, doc)

        var found = false
        for (server in nested.sortedBy { rankServer(it.url) }.distinctBy { NoDrakorIDUtils.decodeKnownRedirect(it.url) }) {
            val nestedUrl = NoDrakorIDUtils.decodeKnownRedirect(server.url)
            if (nestedUrl == fixedUrl || shouldSkipCandidate(nestedUrl, allowInternalPage = true, allowShortener = true)) continue
            if (resolveServer(nestedUrl, fixedUrl, server.label.ifBlank { label }, subtitleCallback, callback, visited, depth + 1)) found = true
        }
        return found
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = NoDrakorIDUtils.decodeKnownRedirect(url)
        if (!clean.startsWith("http", true)) return false
        return if (NoDrakorIDUtils.isHls(clean)) {
            M3u8Helper.generateM3u8(
                label.ifBlank { "NoDrakorID HLS" },
                clean,
                referer = referer,
                headers = NoDrakorIDUtils.videoHeaders(referer)
            ).forEach(callback)
            true
        } else {
            callback(
                newExtractorLink(
                    source = label.ifBlank { "NoDrakorID" },
                    name = label.ifBlank { "NoDrakorID" },
                    url = clean,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(clean).takeIf { it != Qualities.Unknown.value } ?: Qualities.Unknown.value
                    this.headers = NoDrakorIDUtils.videoHeaders(referer)
                }
            )
            true
        }
    }

    private suspend fun resolveAbyssPlayer(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = NoDrakorIDUtils.decodeKnownRedirect(url)
        val html = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching { app.get(pageUrl, referer = referer, headers = NoDrakorIDUtils.browserHeaders, timeout = REQUEST_TIMEOUT_MS).text }.getOrNull()
        }.orEmpty()
        if (html.isBlank()) return false
        return emitAbyssFromHtml(pageUrl, html, callback)
    }

    suspend fun extractAbyssForHost(
        pageUrl: String,
        html: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean = emitAbyssFromHtml(pageUrl, html, callback)

    private suspend fun emitAbyssFromHtml(
        pageUrl: String,
        html: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = parseAbyssPayload(html) ?: return false
        val mediaJson = decryptAbyssMedia(payload) ?: return false
        val root = runCatching { mapper.readTree(mediaJson) }.getOrNull() ?: return false
        val sources = root.path("mp4").path("sources")
        var emitted = false
        if (!sources.isArray) return false
        sources.forEach { source ->
            val status = source.path("status").asBoolean(true)
            val host = source.path("url").asText("").trimEnd('/')
            val path = source.path("path").asText("").trimStart('/')
            if (!status || host.isBlank() || path.isBlank()) return@forEach
            val label = source.path("label").asText("AbyssPlayer")
            val videoUrl = "$host/$path"
            callback(
                newExtractorLink(
                    source = "AbyssPlayer",
                    name = "AbyssPlayer $label",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = pageUrl
                    this.quality = getQualityFromName(label).takeIf { it != Qualities.Unknown.value } ?: getQualityFromName(videoUrl)
                    this.headers = mapOf(
                        "Referer" to "https://abyssplayer.com/",
                        "Origin" to "https://abyssplayer.com",
                        "User-Agent" to USER_AGENT
                    )
                }
            )
            emitted = true
        }
        return emitted
    }

    private fun parseAbyssPayload(html: String): NoDrakorIDAbyssPayload? {
        val encoded = Regex("""(?:const|let|var)?\s*datas\s*=\s*["']([A-Za-z0-9+/=]+)["']""")
            .find(html)?.groupValues?.getOrNull(1) ?: return null
        val json = runCatching { String(Base64.getDecoder().decode(encoded), Charsets.ISO_8859_1) }.getOrNull() ?: return null
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        val slug = node.path("slug").asText("")
        val md5Id = node.path("md5_id").asText("")
        val userId = node.path("user_id").asText("")
        val media = node.path("media").asText("")
        if (slug.isBlank() || md5Id.isBlank() || userId.isBlank() || media.isBlank()) return null
        return NoDrakorIDAbyssPayload(slug, md5Id, userId, media)
    }

    private fun decryptAbyssMedia(payload: NoDrakorIDAbyssPayload): String? {
        return runCatching {
            val key = md5Hex("${payload.userId}:${payload.slug}:${payload.md5Id}").toByteArray(Charsets.UTF_8)
            val counter = key.copyOfRange(0, 16)
            val encrypted = ByteArray(payload.media.length) { index -> payload.media[index].code.toByte() }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private suspend fun safeLoadExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        withTimeoutOrNull(LOAD_EXTRACTOR_TIMEOUT_MS) {
            runCatching {
                loadExtractor(url, referer, subtitleCallback) { link ->
                    emitted = true
                    callback(link)
                }
            }.getOrDefault(false)
        }
        return emitted
    }

    private suspend fun safeGetDocument(url: String, referer: String): Document? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                app.get(url, referer = referer, timeout = REQUEST_TIMEOUT_MS, headers = NoDrakorIDUtils.browserHeaders).document
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
                        "Origin" to NoDrakorIDSepeda.MAIN_URL,
                        "User-Agent" to USER_AGENT
                    ),
                    data = data
                ).text
            }.getOrNull()
        }
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
        val origin = NoDrakorIDUtils.originOf(url) ?: return false
        val fileId = Regex("""/file/([^/?#]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1) ?: return false
        val servers = linkedSetOf<NoDrakorIDServer>()
        val endpoints = listOf("$origin/api/file/downlaod/", "$origin/api/file/download/", "$origin/api/file/downlaod2/", "$origin/api/file/download2/")
        val methods = listOf("publicDownlaod", "publicDownload", "download", "telegramDownload")
        for (endpoint in endpoints) {
            for (method in methods) {
                val response = safePostAjaxText(endpoint, url, mapOf("id" to fileId, "method" to method)) ?: continue
                servers += collectServersFromText(url, response, label.ifBlank { "FilePress" })
                NoDrakorIDUtils.extractUrlsFromText(url, response).forEach { found -> servers += NoDrakorIDServer(label.ifBlank { "FilePress" }, found, url, "filepress") }
            }
        }
        for (server in servers.distinctBy { NoDrakorIDUtils.decodeKnownRedirect(it.url) }) {
            if (resolveServer(server.url, url, server.label, subtitleCallback, callback, visited, depth + 1)) return true
        }
        return false
    }

    private fun firstAttr(element: Element, vararg names: String): String? = names.firstNotNullOfOrNull { name -> element.attr(name).trim().takeIf { it.isNotBlank() } }

    private fun extractPostId(doc: Document): String? {
        val shortLink = doc.selectFirst("link[rel=shortlink]")?.attr("href").orEmpty()
        Regex("""[?&]p=(\d+)""").find(shortLink)?.groupValues?.getOrNull(1)?.let { return it }
        val bodyClasses = doc.body()?.className().orEmpty()
        Regex("""postid-(\d+)""", RegexOption.IGNORE_CASE).find(bodyClasses)?.groupValues?.getOrNull(1)?.let { return it }
        val full = doc.outerHtml()
        return listOf(
            Regex("""postid-(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""data-(?:post|id|postid|movie|movieid)\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?postId["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?post_id["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?movie_id["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?post["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { regex -> regex.find(full)?.groupValues?.getOrNull(1) }
    }

    private fun normalizeText(text: String): String = NoDrakorIDUtils.decodeHtml(NoDrakorIDUtils.decodeUrlRepeated(text))
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\'", "'")

    private fun shouldSkipCandidate(url: String, allowInternalPage: Boolean, allowShortener: Boolean): Boolean {
        if (!url.startsWith("http", true)) return true
        if (NoDrakorIDUtils.isBadAssetUrl(url)) return true
        if (!allowShortener && NoDrakorIDUtils.isShortenerUrl(url)) return true
        if (!allowInternalPage && NoDrakorIDUtils.isNoDrakorUrl(url)) return true
        val lower = url.lowercase()
        return listOf("/wp-content/", "/wp-json/", "/xmlrpc.php", "/feed/", "/comments/", "#respond", "?replytocom=").any { lower.contains(it) }
    }

    private fun rankServer(url: String): Int {
        val lower = url.lowercase()
        return when {
            NoDrakorIDUtils.looksDirectVideo(lower) -> 0
            lower.contains("googlevideo") -> 1
            lower.contains("filepress") -> 2
            lower.contains("jeniusplay") || lower.contains("majorplay") || lower.contains("streamwish") || lower.contains("filemoon") -> 3
            NoDrakorIDUtils.isKnownPlayableHost(lower) -> 4
            NoDrakorIDUtils.isShortenerUrl(lower) -> 8
            NoDrakorIDUtils.isNoDrakorUrl(lower) -> 9
            else -> 6
        }
    }
}

open class NoDrakorIDHostExtractor : ExtractorApi() {
    override var name = "NoDrakorID Host"
    override var mainUrl = "https://example.com"
    override var requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = NoDrakorIDUtils.decodeKnownRedirect(url)
        val ref = referer ?: NoDrakorIDSepeda.MAIN_URL
        val html = runCatching { app.get(pageUrl, referer = ref, headers = NoDrakorIDUtils.browserHeaders, timeout = 15L).text }.getOrNull().orEmpty()
        if (pageUrl.contains("abyssplayer.com", true) || pageUrl.contains("abyss.to", true)) {
            if (NoDrakorIDExtractor.extractAbyssForHost(pageUrl, html, callback)) return
        }
        val normalized = NoDrakorIDUtils.decodeHtml(NoDrakorIDUtils.decodeUrlRepeated(html)).replace("\\/", "/")
        val urls = NoDrakorIDUtils.extractUrlsFromText(pageUrl, normalized)
            .filter { NoDrakorIDUtils.looksDirectVideo(it) || NoDrakorIDUtils.isKnownPlayableHost(it) }
            .distinct()
        for (candidate in urls) {
            if (NoDrakorIDUtils.looksDirectVideo(candidate)) {
                if (NoDrakorIDUtils.isHls(candidate)) {
                    M3u8Helper.generateM3u8(name, candidate, referer = pageUrl, headers = NoDrakorIDUtils.videoHeaders(pageUrl)).forEach(callback)
                } else {
                    callback(newExtractorLink(name, name, candidate, ExtractorLinkType.VIDEO) {
                        this.referer = pageUrl
                        this.quality = getQualityFromName(candidate)
                        this.headers = NoDrakorIDUtils.videoHeaders(pageUrl)
                    })
                }
            } else {
                loadExtractor(candidate, pageUrl, subtitleCallback, callback)
            }
        }
    }
}

class NoDrakorIDJeniusplay : NoDrakorIDHostExtractor() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"
}

class NoDrakorIDMajorplay : NoDrakorIDHostExtractor() {
    override var name = "Majorplay"
    override var mainUrl = "https://majorplay.xyz"
}

class NoDrakorIDE2eMajorplay : NoDrakorIDHostExtractor() {
    override var name = "E2eMajorplay"
    override var mainUrl = "https://e2e.majorplay.xyz"
}

class NoDrakorIDM3u8Majorplay : NoDrakorIDHostExtractor() {
    override var name = "M3u8Majorplay"
    override var mainUrl = "https://m3u8.majorplay.xyz"
}

class NoDrakorIDBloggerVideo : NoDrakorIDHostExtractor() {
    override var name = "BloggerVideo"
    override var mainUrl = "https://www.blogger.com"
}

class NoDrakorIDGdplayer : NoDrakorIDHostExtractor() {
    override var name = "Gdplayer"
    override var mainUrl = "https://gdplayer.to"
}

class NoDrakorIDAWSStream : NoDrakorIDHostExtractor() {
    override var name = "AWSStream"
    override var mainUrl = "https://awsstream.com"
}

class NoDrakorIDStreamWish : StreamWishExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.to"
}

class NoDrakorIDFileMoon : StreamWishExtractor() {
    override var name = "FileMoon"
    override var mainUrl = "https://filemoon.sx"
}

class NoDrakorIDHglink : StreamWishExtractor() {
    override var name = "Hglink"
    override var mainUrl = "https://hglink.to"
}

class NoDrakorIDGhbrisk : StreamWishExtractor() {
    override var name = "Ghbrisk"
    override var mainUrl = "https://ghbrisk.com"
}

class NoDrakorIDDhcplay : StreamWishExtractor() {
    override var name = "Dhcplay"
    override var mainUrl = "https://dhcplay.com"
}

class NoDrakorIDDood : DoodLaExtractor() {
    override var name = "Dood"
    override var mainUrl = "https://doodstream.com"
}

class NoDrakorIDStreamcasthub : VidStack() {
    override var name = "Streamcasthub"
    override var mainUrl = "https://streamcasthub.com"
}

class NoDrakorIDDm21embed : VidStack() {
    override var name = "Dm21embed"
    override var mainUrl = "https://dm21embed.com"
}

class NoDrakorIDMeplayer : VidStack() {
    override var name = "Meplayer"
    override var mainUrl = "https://meplayer.xyz"
}

class NoDrakorIDAbyssPlayer : NoDrakorIDHostExtractor() {
    override var name = "AbyssPlayer"
    override var mainUrl = "https://abyssplayer.com"
}
