package com.nonton01

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.nonton01.Nonton01Utils.absoluteUrl
import com.nonton01.Nonton01Utils.decodeMaybe
import com.nonton01.Nonton01Utils.originOf
import com.nonton01.Nonton01Utils.videoHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object Nonton01Extractor {
    private const val TAG = "Nonton01"
    private const val MAX_HOPS = 3
    private const val MAX_DIRECT_CANDIDATES = 26
    private const val MAX_EMBED_CANDIDATES = 14
    private const val MAX_AJAX_CANDIDATES = 48

    private val keyValueRegex = Regex(
        """(?i)(?:file|src|url|source|hls|hlsUrl|video|videoUrl|stream|streamUrl|playlist|embed|iframe|link|player|content)
            .{0,16}[:=]\s*['\"]([^'\"]+)['\"]""".trimIndent().replace("\n", "")
    )
    private val quotedUrlRegex = Regex(
        """(?i)['\"]((?:https?:)?//[^'\"<>\s]+|/[^'\"<>\s]+)['\"]"""
    )
    private val iframeRegex = Regex("""(?i)<iframe[^>]+src\s*=\s*['\"]([^'\"]+)['\"]""")
    private val encodedUrlRegex = Regex("""https?%3A%2F%2F[^'\"<>\s]+""", RegexOption.IGNORE_CASE)
    private val atobRegex = Regex("""(?i)atob\s*\(\s*['\"]([A-Za-z0-9+/=_-]{16,})['\"]\s*\)""")
    private val base64StringRegex = Regex("""['\"]([A-Za-z0-9+/=]{28,})['\"]""")
    private val dataPostRegex = Regex("""(?i)data-(?:post|id|movie|movieid)\s*=\s*['\"]?(\d+)""")
    private val dataNumeRegex = Regex("""(?i)data-(?:nume|server|episode)\s*=\s*['\"]?(\d+)""")
    private val dataTypeRegex = Regex("""(?i)data-type\s*=\s*['\"]?([a-zA-Z0-9_-]+)""")
    private val jsPostRegex = Regex("""(?i)(?:post|post_id|movie_id|id)\s*[:=]\s*['\"]?(\d+)""")
    private val bodyPostRegex = Regex("""(?i)(?:postid-|post-|wp-post-)(\d{2,})""")

    private val serverAttributes = listOf(
        "src", "href", "value", "data-src", "data-url", "data-link", "data-href",
        "data-file", "data-video", "data-video-url", "data-stream", "data-stream-url",
        "data-embed", "data-iframe", "data-player", "data-play", "data-server",
        "data-content", "data-id", "data-post", "data-nume"
    )

    suspend fun loadLinks(
        providerName: String,
        mainUrl: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e(TAG, "loadLinks start: $data")
        val emitted = linkedSetOf<String>()
        for (candidate in Nonton01Utils.mirrorUrlsFor(data)) {
            val found = resolvePage(providerName, candidate, candidate, mainUrl, 0, emitted, subtitleCallback, callback)
            if (found) return true
        }
        Log.e(TAG, "loadLinks no playable links for: $data")
        return false
    }

    private suspend fun resolvePage(
        providerName: String,
        pageUrl: String,
        referer: String,
        mainUrl: String,
        depth: Int,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > MAX_HOPS) {
            Log.e(TAG, "max hop reached: $pageUrl")
            return false
        }

        val response = runCatching {
            app.get(pageUrl, headers = Nonton01Utils.siteHeadersFor(referer), referer = referer)
        }.onFailure { Log.e(TAG, "GET failed $pageUrl: ${it.message}") }.getOrNull() ?: return false
        val document = response.document

        collectSubtitles(pageUrl, document, subtitleCallback)
        var found = false

        val ajaxCandidates = prioritizeCandidates(fetchAjaxPlayers(pageUrl, mainUrl, document))
        val direct = prioritizeCandidates(extractDirectMedia(pageUrl, document) + ajaxCandidates)
        val embeds = prioritizeCandidates(extractEmbeds(pageUrl, document) + ajaxCandidates.filter { looksLikeEmbed(it) })
        Log.e(TAG, "captured page=$pageUrl depth=$depth ajax=${ajaxCandidates.size} direct=${direct.size} embeds=${embeds.size}")

        for (url in direct.take(MAX_DIRECT_CANDIDATES)) {
            Log.e(TAG, "direct candidate: $url")
            val emittedNow = emitDirect(providerName, url, pageUrl, emitted, callback)
            found = found || emittedNow
            if (!emittedNow && (isKnownExtractorHost(url.lowercase()) || looksLikeEmbed(url))) {
                val extractorFound = runExtractor(url, pageUrl, emitted, subtitleCallback, callback)
                found = found || extractorFound
            }
        }

        for (embed in embeds.filterNot { direct.contains(it) }.take(MAX_EMBED_CANDIDATES)) {
            Log.e(TAG, "embed candidate: $embed referer=$pageUrl")
            val extractorFound = runExtractor(embed, pageUrl, emitted, subtitleCallback, callback)
            found = found || extractorFound
            if (!extractorFound && depth < MAX_HOPS && canRecurseInto(embed, pageUrl)) {
                val nestedFound = resolvePage(providerName, embed, pageUrl, mainUrl, depth + 1, emitted, subtitleCallback, callback)
                found = found || nestedFound
            }
        }

        if (!found) {
            Log.e(TAG, "fallback extractor on page: $pageUrl")
            found = runExtractor(pageUrl, referer, emitted, subtitleCallback, callback)
        }
        return found
    }

    private suspend fun emitDirect(
        providerName: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            when {
                looksLikeHls(url) -> {
                    val links = generateM3u8(
                        source = providerName,
                        streamUrl = url,
                        referer = referer,
                        headers = videoHeaders(referer)
                    )
                    links.forEach { link ->
                        if (emitted.add(link.url)) callback(link)
                    }
                    if (links.isEmpty()) Log.e(TAG, "generateM3u8 returned empty: $url")
                    links.isNotEmpty()
                }
                looksLikeDirectMp4(url) -> {
                    if (emitted.add(url)) {
                        callback(
                            newExtractorLink(providerName, "$providerName MP4", url) {
                                this.referer = referer
                                this.quality = Qualities.Unknown.value
                                this.headers = videoHeaders(referer)
                            }
                        )
                        true
                    } else {
                        false
                    }
                }
                else -> {
                    if (!shouldTryHlsFallback(url)) return false
                    val links = runCatching {
                        generateM3u8(
                            source = providerName,
                            streamUrl = url,
                            referer = referer,
                            headers = videoHeaders(referer)
                        )
                    }.getOrDefault(emptyList())
                    links.forEach { link ->
                        if (emitted.add(link.url)) callback(link)
                    }
                    links.isNotEmpty()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "emitDirect failed $url: ${e.message}")
            false
        }
    }

    private suspend fun runExtractor(
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        return try {
            loadExtractor(url, referer, subtitleCallback) { link ->
                if (emitted.add(link.url)) {
                    found = true
                    Log.e(TAG, "loadExtractor emitted: ${link.url}")
                    callback(link)
                }
            }
            if (!found) Log.e(TAG, "loadExtractor emitted 0 links: $url")
            found
        } catch (e: Throwable) {
            Log.e(TAG, "loadExtractor failed $url: ${e.message}")
            false
        }
    }

    private suspend fun fetchAjaxPlayers(pageUrl: String, mainUrl: String, document: Document): List<String> {
        val html = normalizedHtml(document)
        val origin = originOf(pageUrl) ?: originOf(mainUrl) ?: Nonton01Seeds.MAIN_URL
        val posts = linkedSetOf<String>()
        val numes = linkedSetOf<String>()
        val types = linkedSetOf<String>()

        val optionSelectors = listOf(
            ".dooplay_player_option",
            "#playeroptionsul li",
            "ul#playeroptionsul li",
            ".player_sorces li",
            ".player option",
            ".player-option",
            ".server",
            ".server-item",
            "[data-post]",
            "[data-id]",
            "[data-nume]",
            "[data-type]"
        ).joinToString(", ")

        document.select(optionSelectors).forEach { element ->
            listOf("data-post", "data-id", "data-movie", "data-movieid", "post", "id")
                .map { element.attr(it) }
                .filter { it.isNotBlank() && it.all { ch -> ch.isDigit() } }
                .forEach { posts.add(it) }
            listOf("data-nume", "data-server", "data-episode", "server", "episode")
                .map { element.attr(it) }
                .filter { it.isNotBlank() && it.all { ch -> ch.isDigit() } }
                .forEach { numes.add(it) }
            listOf("data-type", "type")
                .map { element.attr(it) }
                .filter { it.isNotBlank() }
                .forEach { types.add(normalizeAjaxType(it)) }

            val identityText = listOf(element.id(), element.className(), element.attr("onclick"), element.attr("data-target"), element.text())
                .joinToString(" ")
            extractAjaxIdsFromText(identityText, posts, numes, types)
        }

        dataPostRegex.findAll(html).map { it.groupValues[1] }.forEach { posts.add(it) }
        dataNumeRegex.findAll(html).map { it.groupValues[1] }.forEach { numes.add(it) }
        dataTypeRegex.findAll(html).map { it.groupValues[1] }.forEach { types.add(normalizeAjaxType(it)) }
        jsPostRegex.findAll(html).map { it.groupValues[1] }.take(3).forEach { posts.add(it) }
        bodyPostRegex.findAll(html).map { it.groupValues[1] }.take(4).forEach { posts.add(it) }
        extractAjaxIdsFromText(html, posts, numes, types)

        if (posts.isEmpty()) {
            Log.e(TAG, "ajax attributes missing: no post/id found for $pageUrl")
            return emptyList()
        }
        if (numes.isEmpty()) (1..8).map { it.toString() }.forEach { numes.add(it) }

        val inferredTypes = inferAjaxTypes(pageUrl)
        val orderedTypes = linkedSetOf<String>()
        if (types.isNotEmpty()) {
            types.map { normalizeAjaxType(it) }.forEach { orderedTypes.add(it) }
        } else {
            inferredTypes.forEach { orderedTypes.add(it) }
        }
        listOf("movie", "tv", "episode").forEach { orderedTypes.add(it) }

        Log.e(TAG, "ajax attrs page=$pageUrl posts=${posts.take(6)} numes=${numes.take(10)} types=${orderedTypes.take(6)}")

        val endpoints = listOf(
            "$origin/wp-admin/admin-ajax.php",
            "$origin/admin-ajax.php",
            "$origin/wp-json/dooplay/v1/player"
        )
        val actions = listOf("doo_player_ajax", "dt_player_ajax", "player_ajax")
        val payloads = mutableListOf<Triple<String, String, String>>()
        posts.take(3).forEach { post ->
            numes.take(10).forEach { nume ->
                orderedTypes.take(4).forEach { type -> payloads.add(Triple(post, nume, type)) }
            }
        }

        val out = linkedSetOf<String>()
        var attempts = 0
        for ((post, nume, type) in payloads) {
            for (endpoint in endpoints) {
                for (action in actions) {
                    if (attempts >= MAX_AJAX_CANDIDATES) {
                        Log.e(TAG, "ajax probe limit reached attempts=$attempts candidates=${out.size}")
                        return prioritizeCandidates(out.toList())
                    }
                    attempts++
                    val form = "action=$action&post=$post&nume=$nume&type=$type"
                    val ajaxHeaders = Nonton01Utils.siteHeadersFor(pageUrl) + mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Referer" to pageUrl,
                        "Origin" to origin,
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                    Log.e(TAG, "ajax probe#$attempts endpoint=$endpoint action=$action post=$post nume=$nume type=$type")

                    val responseText = runCatching {
                        val body = form.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull())
                        val res = app.post(endpoint, requestBody = body, headers = ajaxHeaders)
                        val text = res.text
                        Log.e(TAG, "ajax POST status=${res.code} action=$action post=$post nume=$nume type=$type body=${snippet(text)}")
                        text
                    }.onFailure { Log.e(TAG, "ajax POST failed endpoint=$endpoint action=$action post=$post nume=$nume type=$type error=${it.message}") }
                        .getOrNull()
                        ?: runCatching {
                            val res = app.get("$endpoint?$form", headers = ajaxHeaders)
                            val text = res.text
                            Log.e(TAG, "ajax GET status=${res.code} action=$action post=$post nume=$nume type=$type body=${snippet(text)}")
                            text
                        }.onFailure { Log.e(TAG, "ajax GET failed endpoint=$endpoint action=$action post=$post nume=$nume type=$type error=${it.message}") }
                            .getOrNull()

                    if (responseText.isNullOrBlank()) {
                        Log.e(TAG, "ajax empty response endpoint=$endpoint action=$action post=$post nume=$nume type=$type")
                        continue
                    }

                    val payloadText = ajaxPayloadText(responseText)
                    val urls = extractUrlsFromText(pageUrl, payloadText)
                    if (urls.isEmpty()) {
                        Log.e(TAG, "ajax parsed 0 urls action=$action post=$post nume=$nume type=$type payload=${snippet(payloadText)}")
                    } else {
                        Log.e(TAG, "ajax captured ${urls.size} urls action=$action post=$post nume=$nume type=$type urls=${urls.take(5)}")
                        urls.forEach { out.add(it) }
                    }
                }
            }
        }
        Log.e(TAG, "ajax probes done attempts=$attempts candidates=${out.size}")
        return prioritizeCandidates(out.toList())
    }

    private fun ajaxPayloadText(raw: String): String {
        val normalized = normalizedText(raw)
        val collected = linkedSetOf<String>()

        fun collectJsonValue(value: Any?) {
            when (value) {
                null -> Unit
                JSONObject.NULL -> Unit
                is JSONObject -> {
                    value.keys().forEach { key -> collectJsonValue(value.opt(key)) }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) collectJsonValue(value.opt(i))
                }
                is String -> {
                    val v = normalizedText(value)
                    if (v.isNotBlank() && v != "0" && !v.equals("null", ignoreCase = true)) collected.add(v)
                    if ((v.startsWith("{") && v.endsWith("}")) || (v.startsWith("[") && v.endsWith("]"))) {
                        runCatching { collectJsonValue(JSONObject(v)) }
                        runCatching { collectJsonValue(JSONArray(v)) }
                    }
                }
                else -> collectJsonValue(value.toString())
            }
        }

        runCatching { collectJsonValue(JSONObject(normalized)) }
        runCatching { collectJsonValue(JSONArray(normalized)) }

        if (collected.isEmpty()) collected.add(normalized)
        return collected.joinToString("\n")
    }

    private fun extractAjaxIdsFromText(
        text: String,
        posts: MutableSet<String>,
        numes: MutableSet<String>,
        types: MutableSet<String>
    ) {
        Regex("""(?i)(?:player[-_]?option|player_option|option|server)[-_](\d{2,})[-_](\d{1,2})""")
            .findAll(text)
            .forEach {
                posts.add(it.groupValues[1])
                numes.add(it.groupValues[2])
            }
        Regex("""(?i)(?:data|post|movie)[-_](\d{2,})[-_](\d{1,2})[-_]([a-z]+)""")
            .findAll(text)
            .forEach {
                posts.add(it.groupValues[1])
                numes.add(it.groupValues[2])
                types.add(normalizeAjaxType(it.groupValues[3]))
            }
        Regex("""(?i)(?:nume|server|episode)[-_=: ]+(\d{1,2})""")
            .findAll(text)
            .map { it.groupValues[1] }
            .forEach { numes.add(it) }
        Regex("""(?i)(?:type)[-_=: ]+([a-zA-Z0-9_-]+)""")
            .findAll(text)
            .map { it.groupValues[1] }
            .forEach { types.add(normalizeAjaxType(it)) }
    }

    private fun normalizeAjaxType(value: String): String {
        val v = value.trim().lowercase()
        return when {
            v.contains("episode") || v == "ep" -> "episode"
            v.contains("tv") || v.contains("series") -> "tv"
            v.contains("movie") || v.contains("film") -> "movie"
            v.isBlank() -> "movie"
            else -> v
        }
    }

    private fun inferAjaxTypes(pageUrl: String): List<String> {
        val low = pageUrl.lowercase()
        return when {
            low.contains("/episodes/") || low.contains("/episode/") -> listOf("episode", "tv")
            low.contains("/tvshows/") || low.contains("/series/") -> listOf("tv", "episode")
            low.contains("/movies/") || low.contains("/movie/") -> listOf("movie")
            else -> listOf("movie")
        }
    }

    private fun snippet(text: String?, max: Int = 300): String {
        return text.orEmpty()
            .replace("\n", " ")
            .replace("\r", " ")
            .replace(Regex("\\s+"), " ")
            .take(max)
    }

    private suspend fun collectSubtitles(pageUrl: String, document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val url = absoluteUrl(pageUrl, element.attr("src").ifBlank { element.attr("href") }) ?: return@forEach
            val label = Nonton01Utils.cleanText(element.attr("label").ifBlank { element.text().ifBlank { "Subtitle" } })
            runCatching { subtitleCallback(newSubtitleFile(label, url)) }
        }
    }

    private fun extractDirectMedia(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        extractAttributeUrls(pageUrl, document).filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        extractUrlsFromText(pageUrl, normalizedHtml(document)).filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        return out.distinct()
    }

    private fun extractEmbeds(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        extractAttributeUrls(pageUrl, document).filter { looksLikeEmbed(it) }.forEach { out.add(it) }
        extractUrlsFromText(pageUrl, normalizedHtml(document)).filter { looksLikeEmbed(it) }.forEach { out.add(it) }
        return out.distinct()
    }

    private fun extractUrlsFromText(pageUrl: String, text: String): List<String> {
        val html = normalizedText(text)
        val out = linkedSetOf<String>()

        if (html.contains("<iframe", ignoreCase = true) || html.contains("<video", ignoreCase = true) || html.contains("<source", ignoreCase = true)) {
            runCatching {
                val miniDoc = Jsoup.parseBodyFragment(html, pageUrl)
                miniDoc.select("iframe[src], embed[src], video[src], source[src], a[href], [data-src], [data-url], [data-file], [data-embed], [data-iframe]").forEach { element ->
                    listOf("src", "href", "data-src", "data-url", "data-file", "data-embed", "data-iframe")
                        .map { element.attr(it) }
                        .mapNotNull { normalizeUrl(pageUrl, it) }
                        .forEach { out.add(it) }
                }
            }.onFailure { Log.e(TAG, "mini HTML parse failed: ${it.message}") }
        }

        keyValueRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
        quotedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
        iframeRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
        encodedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.value) }.forEach { out.add(it) }
        decodeBase64Candidates(html).mapNotNull { normalizeUrl(pageUrl, it) }.forEach { out.add(it) }
        return out.distinct()
    }

    private fun extractAttributeUrls(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        document.select("iframe, embed, video, source, option, a[href], button, div, span, li, script").forEach { element ->
            serverAttributes.forEach { attr ->
                val raw = element.attr(attr)
                normalizeUrl(pageUrl, raw)?.let { out.add(it) }
                if (raw.contains("http", ignoreCase = true) ||
                    raw.contains("iframe", ignoreCase = true) ||
                    raw.contains("&lt;", ignoreCase = true) ||
                    raw.contains("\\/")) {
                    extractUrlsFromText(pageUrl, raw).forEach { out.add(it) }
                }
            }
            if (element.tagName().equals("script", ignoreCase = true)) {
                extractUrlsFromText(pageUrl, element.data()).forEach { out.add(it) }
            }
        }
        return out.distinct()
    }

    private fun normalizedHtml(document: Document): String = normalizedText(document.html())

    private fun normalizedText(text: String): String = text
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003a", ":")
        .replace("\\u002f", "/")

    private fun normalizeUrl(pageUrl: String, value: String?): String? {
        val raw = decodeMaybe(value.orEmpty())
            .trim()
            .trim('"', '\'', ',', ';', ' ')
        if (raw.isBlank()) return null
        val low = raw.lowercase()
        if (low == "#" || low == "null" || low == "undefined" || low == "about:blank") return null
        if (low.startsWith("javascript:") || low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("intent:")) return null
        if (low.contains(".jpg") || low.contains(".png") || low.contains(".webp") || low.contains(".gif")) return null
        if (low.contains("youtube.com") || low.contains("youtu.be") || low.contains("trailer")) return null
        if (isDeniedUrl(low)) return null
        return absoluteUrl(pageUrl, raw)
    }

    private fun decodeBase64Candidates(html: String): List<String> {
        val out = linkedSetOf<String>()
        val candidates = mutableListOf<String>()
        atobRegex.findAll(html).map { it.groupValues[1] }.forEach { candidates.add(it) }
        base64StringRegex.findAll(html).map { it.groupValues[1] }.take(120).forEach { candidates.add(it) }
        candidates.forEach { encoded ->
            val decoded = decodeBase64(encoded) ?: return@forEach
            if (decoded.contains("http", ignoreCase = true) || decoded.contains("iframe", ignoreCase = true) || decoded.contains("m3u8", ignoreCase = true)) {
                keyValueRegex.findAll(decoded).map { it.groupValues[1] }.forEach { out.add(it) }
                quotedUrlRegex.findAll(decoded).map { it.groupValues[1] }.forEach { out.add(it) }
                iframeRegex.findAll(decoded).map { it.groupValues[1] }.forEach { out.add(it) }
                encodedUrlRegex.findAll(decoded).map { it.value }.forEach { out.add(it) }
                if (decoded.startsWith("http", ignoreCase = true)) out.add(decoded)
            }
        }
        return out.distinct()
    }

    private fun decodeBase64(value: String): String? {
        return runCatching {
            val normalized = value.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(Base64.decode(padded, Base64.DEFAULT))
        }.getOrNull()
    }

    private fun looksLikeHls(url: String): Boolean {
        val low = url.lowercase()
        return low.contains(".m3u8") || low.contains("m3u8") || low.contains("playlist") || low.contains("master.m3u")
    }

    private fun looksLikeDirectMp4(url: String): Boolean {
        val low = url.lowercase()
        return low.contains(".mp4") || low.contains("googlevideo") || low.contains("videoplayback")
    }

    private fun looksLikeMediaOrPlayer(url: String): Boolean {
        val low = url.lowercase()
        return !isDeniedUrl(low) &&
            (looksLikeHls(url) ||
                looksLikeDirectMp4(url) ||
                isKnownExtractorHost(low) ||
                looksLikeEmbed(url) ||
                shouldTryHlsFallback(url))
    }

    private fun looksLikeEmbed(url: String): Boolean {
        val low = url.lowercase()
        return !isDeniedUrl(low) &&
            !looksLikeDirectMp4(url) &&
            !looksLikeHls(url) &&
            (isKnownExtractorHost(low) ||
                low.contains("/embed/") ||
                low.contains("/player/") ||
                low.contains("?embed=") ||
                low.contains("?source=") ||
                low.contains("?url="))
    }

    private fun shouldTryHlsFallback(url: String): Boolean {
        val low = url.lowercase()
        if (isDeniedUrl(low) || looksLikeDirectMp4(url) || looksLikeEmbed(url)) return false
        return low.contains("m3u") ||
            low.contains("playlist") ||
            low.contains("hls") ||
            low.contains("master") ||
            low.contains("chunklist") ||
            low.contains("manifest") ||
            low.contains("index.php?source=") ||
            low.contains("index.php?url=")
    }

    private fun isKnownExtractorHost(low: String): Boolean {
        return listOf(
            "myvidplay", "minochinos", "hglink", "streamtape", "dood", "filemoon",
            "vidhide", "vidguard", "filelions", "streamwish", "streamsb", "sbembed",
            "voe.sx", "uqload", "mixdrop", "fembed", "doodstream", "streamlare",
            "luluvdo", "vidmoly", "wolfstream", "upstream", "streamruby", "mcloud"
        ).any { low.contains(it) }
    }

    private fun isDeniedUrl(low: String): Boolean {
        return low.contains("facebook.com") ||
            low.contains("twitter.com") ||
            low.contains("instagram.com") ||
            low.contains("whatsapp") ||
            low.contains("telegram") ||
            low.contains("disqus") ||
            low.contains("googletagmanager") ||
            low.contains("google-analytics") ||
            low.contains("doubleclick") ||
            low.contains("/wp-content/themes/") ||
            low.endsWith(".css") ||
            low.endsWith(".js")
    }

    private fun canRecurseInto(embed: String, pageUrl: String): Boolean {
        val embedOrigin = originOf(embed)
        val pageOrigin = originOf(pageUrl)
        val low = embed.lowercase()
        return embedOrigin != null &&
            embedOrigin != pageOrigin &&
            !Nonton01Utils.isSameHost(embed) &&
            !isDeniedUrl(low) &&
            (isKnownExtractorHost(low) || low.contains("/embed/") || low.contains("/player/"))
    }

    private fun prioritizeCandidates(urls: List<String>): List<String> {
        return urls.distinct().sortedBy { url ->
            when {
                looksLikeHls(url) -> 0
                looksLikeDirectMp4(url) -> 1
                isKnownExtractorHost(url.lowercase()) -> 2
                looksLikeEmbed(url) -> 3
                else -> 4
            }
        }
    }
}
