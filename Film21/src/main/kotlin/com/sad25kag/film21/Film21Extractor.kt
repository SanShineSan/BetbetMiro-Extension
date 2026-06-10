package com.sad25kag.film21

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Film21Extractor {
    private const val MAIN_URL = "https://palacepalace.com"

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Referer" to "$MAIN_URL/"
    )

    private val candidatePatterns = listOf(
        Regex("""(?i)<(?:iframe|embed)[^>]+(?:src|data-src|data-litespeed-src)=['"]([^'"]+)['"]"""),
        Regex("""(?i)(?:data-src|data-embed|data-video|data-url|data-link|data-file)=['"]([^'"]+)['"]"""),
        Regex("""(?i)(?:embed_url|iframe_url|player_url|url|src|file|source|link|m3u8|hls|hlsVideoTiktok|video|videoUrl|video_url)\s*[:=]\s*['"]([^'"]+)['"]"""),
        Regex("""(?i)['"]((?:https?:)?//[^'"]+(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^'"]+|videoplayback[^'"]*|/hls/[^'"]+|/stream/[^'"]+|/play/token_hash\?[^'"]+)(?:\?[^'"]*)?)['"]"""),
        Regex("""(?i)['"]((?:https?:)?//[^'"]*(?:embed|player|stream|drive|gofile|dood|streamtape|filemoon|vidhide|vidguard|voe|mp4upload|uqload|krakenfiles|filelions|gdplayer|gdriveplayer|hubcloud|short|sht|p2pplay|playerp2p|vidplayer|editdulu|playdulu|havanabrown|/e/|/v/|/d/)[^'"]*)['"]"""),
        Regex("""(?i)['"]((?:/[^'"]*)/(?:embed|player|stream|get|watch|video|dl)[^'"]*)['"]""")
    )

    suspend fun load(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startUrl = fixUrl(data, MAIN_URL) ?: return false
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var found = false

        fun emit(link: ExtractorLink) {
            val key = link.url.substringBefore("#").substringBefore("?token=").substringBefore("&token=")
            if (emitted.add(key)) {
                found = true
                callback(link)
            }
        }

        suspend fun emitDirect(rawUrl: String, referer: String, sourceName: String = "Film21"): Boolean {
            val fixed = fixUrl(rawUrl, referer) ?: return false
            if (!fixed.isPlayableMedia()) return false
            val key = fixed.substringBefore("#")
            if (!emitted.add(key)) return false
            val mediaReferer = mediaReferer(fixed, referer)
            val mediaHeaders = mediaHeaders(fixed, referer)

            if (fixed.isM3u8Like()) {
                val variants = runCatching {
                    generateM3u8(sourceName, fixed, mediaReferer, headers = mediaHeaders)
                }.getOrDefault(emptyList())
                variants.forEach { link -> emit(link) }
                if (variants.isNotEmpty()) return true
            }

            emit(
                newExtractorLink(
                    "Film21",
                    sourceName,
                    fixed,
                    if (fixed.isM3u8Like()) ExtractorLinkType.M3U8 else INFER_TYPE
                ) {
                    this.referer = mediaReferer
                    this.quality = fixed.qualityFromUrl()
                    this.headers = mediaHeaders
                }
            )
            return true
        }

        suspend fun emitExtractor(rawUrl: String, referer: String): Boolean {
            val fixed = fixUrl(rawUrl, referer) ?: return false
            if (fixed.isNoiseUrl()) return false
            if (fixed.isPlayableMedia()) return emitDirect(fixed, referer)
            var localFound = false
            runCatching {
                loadExtractor(fixed, referer, subtitleCallback) { link ->
                    val key = link.url.substringBefore("#")
                    if (emitted.add(key)) {
                        localFound = true
                        found = true
                        callback(link)
                    }
                }
            }
            return localFound
        }

        suspend fun resolveKnownPlayer(rawUrl: String, referer: String): Boolean {
            val fixed = fixUrl(rawUrl, referer) ?: return false
            var localFound = false
            resolvePlayerLinks(fixed, referer).forEach { resolved ->
                if (emitDirect(resolved.url, resolved.referer, resolved.source)) localFound = true
            }
            return localFound
        }

        suspend fun resolve(rawUrl: String?, referer: String = "$MAIN_URL/", depth: Int = 0) {
            val url = fixUrl(rawUrl, referer) ?: return
            if (url.isNoiseUrl()) return
            if (!visited.add(url)) return

            when {
                url.isSubtitleUrl() -> {
                    subtitleCallback(SubtitleFile("Indonesian", url))
                    return
                }
                url.isPlayableMedia() -> {
                    if (emitDirect(url, referer)) found = true
                    return
                }
                resolveKnownPlayer(url, referer) -> {
                    found = true
                    return
                }
            }

            if (emitExtractor(url, referer)) found = true
            if (depth >= 5 || isKnownExternal(url)) return

            val response = runCatching {
                app.get(url, headers = baseHeaders + mapOf("Referer" to referer), referer = referer, timeout = 15000L)
            }.getOrNull() ?: return

            val contentType = response.headers["Content-Type"].orEmpty().lowercase(Locale.ROOT)
            val contentLength = response.headers["Content-Length"]?.toLongOrNull()
            if (contentType.startsWith("video/") || contentType.contains("mpegurl") || contentType.contains("dash") || contentType.contains("octet-stream") || (contentLength != null && contentLength > 5_000_000L)) return

            val body = normalize(response.text)
            val document = runCatching { response.document }.getOrNull() ?: Jsoup.parse(body, url)
            collectSubtitles(document, url, subtitleCallback)
            collectMuviproPlayers(document, body, url).forEach { candidate -> runCatching { resolve(candidate, url, depth + 1) } }
            collectElementLinks(document, url).forEach { candidate -> runCatching { resolve(candidate, url, depth + 1) } }
            collectCandidates(body, url).forEach { candidate -> runCatching { resolve(candidate, url, depth + 1) } }
        }

        runCatching { resolve(startUrl, "$MAIN_URL/", 0) }
        return found
    }

    private suspend fun collectMuviproPlayers(document: Document, html: String, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val pageOrigin = origin(pageUrl)
        val ajaxUrl = "$pageOrigin/wp-admin/admin-ajax.php"

        document.select("ul.muvipro-player-tabs li a[href], .muvipro-player-tabs a[href], a[href*='?player='], a[href*='&player=']").forEach { element ->
            fixUrl(element.attr("href"), pageUrl)?.let(links::add)
        }

        val muviproId = document.selectFirst("div#muvipro_player_content_id, #muvipro_player_content_id")
            ?.attr("data-id")
            ?.takeIf { it.isNotBlank() }

        if (!muviproId.isNullOrBlank()) {
            document.select("div.tab-content-ajax[id], .tab-content-ajax[id]").forEach { tab ->
                val tabId = tab.attr("id").trim()
                if (tabId.isBlank()) return@forEach
                val body = runCatching {
                    app.post(
                        ajaxUrl,
                        data = mapOf("action" to "muvipro_player_content", "tab" to tabId, "post_id" to muviproId),
                        headers = ajaxHeaders(pageOrigin, pageUrl),
                        referer = pageUrl
                    ).text
                }.getOrDefault("")
                collectCandidates(body, pageUrl).forEach(links::add)
            }
        }

        val playerOptions = document.select("li.dooplay_player_option, .dooplay_player_option, .dooplay_player, [data-post][data-nume][data-type], [data-post][data-type], [data-id][data-nume]")
        playerOptions.forEach { option ->
            val post = option.attr("data-post").ifBlank { option.attr("data-id") }
            val nume = option.attr("data-nume").ifBlank { option.attr("data-index").ifBlank { "1" } }
            val type = option.attr("data-type").ifBlank { sourceType(document, html) ?: "movie" }
            if (post.isBlank()) return@forEach
            listOf("doo_player_ajax", "doo_ajax_player", "player_ajax", "muvipro_player_content").forEach { action ->
                val data = if (action == "muvipro_player_content") {
                    mapOf("action" to action, "tab" to nume, "post_id" to post)
                } else {
                    mapOf("action" to action, "post" to post, "nume" to nume, "type" to type)
                }
                val body = runCatching {
                    app.post(ajaxUrl, data = data, headers = ajaxHeaders(pageOrigin, pageUrl), referer = pageUrl).text
                }.getOrDefault("")
                collectCandidates(body, pageUrl).forEach(links::add)
            }
        }

        return links.filterNot { it.isNoiseUrl() }.toList()
    }

    private fun collectCandidates(html: String, baseUrl: String): Set<String> {
        val normalized = normalize(html)
        val links = linkedSetOf<String>()

        fun collectFrom(text: String, currentBase: String = baseUrl) {
            val clean = normalize(text)
            val parsed = runCatching { Jsoup.parse(clean, currentBase) }.getOrNull()
            parsed?.let { collectElementLinks(it, currentBase).forEach(links::add) }
            directMedia(clean, currentBase).forEach(links::add)
            candidatePatterns.forEach { pattern ->
                pattern.findAll(clean).mapNotNull { fixUrl(it.groupValues.getOrNull(1), currentBase) }.forEach(links::add)
            }
        }

        collectFrom(normalized, baseUrl)
        base64Blocks(normalized).forEach { decoded -> collectFrom(decoded, baseUrl) }
        juicyCodesBlocks(normalized).forEach { decoded -> collectFrom(decoded, baseUrl) }
        xFileShareStream(normalized, baseUrl)?.let(links::add)

        return links.filterNot { it.isNoiseUrl() }.toCollection(linkedSetOf())
    }

    private fun collectElementLinks(document: Document, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select(
            "#player iframe[src], #player iframe[data-src], .player iframe[src], .player iframe[data-src], [id*=player] iframe[src], [class*=player] iframe[src], " +
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], video[src], video source[src], source[src], " +
                "a[href*='embed'], a[href*='player'], a[href*='stream'], a[href*='drive'], a[href*='gofile'], a[href*='dood'], a[href*='streamtape'], " +
                "a[href*='filemoon'], a[href*='vidhide'], a[href*='vidguard'], a[href*='voe'], a[href*='mp4upload'], a[href*='uqload'], a[href*='krakenfiles'], " +
                "a[href*='filelions'], a[href*='hubcloud'], a[href*='gdplayer'], a[href*='gdriveplayer'], a[href*='sht'], a[href*='short'], a[href*='p2pplay'], " +
                "a[href*='playerp2p'], a[href*='editdulu'], a[href*='playdulu'], a[href*='havanabrown'], a[href*='.mp4'], a[href*='.m3u8']"
        ).forEach { element ->
            val value = listOf("src", "data-src", "data-litespeed-src", "href", "data-embed", "data-video", "data-url", "data-link", "data-file")
                .map { element.attr(it) }
                .firstOrNull { it.isNotBlank() }
            fixUrl(value, baseUrl)?.let { if (!it.isNoiseUrl()) links.add(it) }
        }
        return links.toList()
    }

    private fun collectSubtitles(document: Document, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val url = fixUrl(element.attr("src").ifBlank { element.attr("href") }, baseUrl) ?: return@forEach
            val label = element.attr("label").ifBlank { element.attr("srclang").ifBlank { element.text().ifBlank { "Subtitle" } } }.trim()
            subtitleCallback(SubtitleFile(label, url))
        }
    }

    private fun directMedia(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        Regex("""(?i)['"]((?:https?:)?//[^'"]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^'"]+|videoplayback[^'"]*|/hls/[^'"]+|/stream/[^'"]+|/play/token_hash\?[^'"]+)(?:\?[^'"]*)?)['"]""")
            .findAll(html).mapNotNull { fixUrl(it.groupValues[1], baseUrl) }.filter { it.isPlayableMedia() }.forEach(links::add)
        Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^\s'"<>\\]+|videoplayback[^\s'"<>\\]*|/hls/[^\s'"<>\\]+|/stream/[^\s'"<>\\]+|/play/token_hash\?[^\s'"<>\\]+)(?:\?[^\s'"<>\\]*)?""")
            .findAll(html).mapNotNull { fixUrl(it.value, baseUrl) }.filter { it.isPlayableMedia() }.forEach(links::add)
        Regex("""https?%3A%2F%2F[^\s'"<>]+""", RegexOption.IGNORE_CASE)
            .findAll(html).mapNotNull { fixUrl(urlDecode(it.value), baseUrl) }.filter { it.isPlayableMedia() }.forEach(links::add)
        return links.toList()
    }

    private fun base64Blocks(html: String): List<String> {
        val decoded = mutableListOf<String>()
        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""").findAll(html).mapNotNull { decodeBase64(it.groupValues[1]) }.forEach(decoded::add)
        Regex("""(?i)Base64\.decode\(['"]([^'"]+)['"]\)""").findAll(html).mapNotNull { decodeBase64(it.groupValues[1]) }.forEach(decoded::add)
        return decoded
    }

    private fun juicyCodesBlocks(html: String): List<String> {
        val decoded = mutableListOf<String>()
        Regex("""(?is)_juicycodes\((.*?)\)\s*;?""").findAll(html).forEach { match ->
            val encoded = Regex("""['"]([^'"]*)['"]""").findAll(match.groupValues[1]).joinToString("") { it.groupValues[1] }
            decodeJuicyCodes(encoded)?.let(decoded::add)
        }
        return decoded
    }

    private fun decodeJuicyCodes(encoded: String): String? {
        if (encoded.length <= 7) return null
        return runCatching {
            val salt = encoded.takeLast(3).map { (it.code - 100).toString() }.joinToString("").toIntOrNull() ?: return null
            val raw = encoded.dropLast(3)
                .replace('-', '/')
                .replace('_', '+')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }
            val mapped = String(Base64.getDecoder().decode(raw))
            val symbols = listOf('`', '%', '-', '+', '*', '$', '!', '_', '^', '=')
            val digits = buildString {
                mapped.forEach { char ->
                    val index = symbols.indexOf(char)
                    if (index >= 0) append(index)
                }
            }
            buildString {
                Regex("""\d{4}""").findAll(digits).forEach { chunk ->
                    val code = (chunk.value.toIntOrNull() ?: return@forEach) % 1000 - salt
                    if (code in 0..Char.MAX_VALUE.code) append(code.toChar())
                }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private data class ResolvedPlayerLink(val url: String, val referer: String, val source: String)

    private suspend fun resolvePlayerLinks(url: String, referer: String): List<ResolvedPlayerLink> {
        val fixed = fixUrl(url, referer) ?: return emptyList()
        val host = runCatching { URI(fixed).host.orEmpty().lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        return when {
            isEncryptedPlayerHost(host) -> resolveEncryptedPlayer(fixed, referer)
            else -> emptyList()
        }
    }

    private suspend fun resolveEncryptedPlayer(url: String, referer: String): List<ResolvedPlayerLink> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val id = extractPlayerId(uri, url)
        if (id.isBlank()) return emptyList()
        val playerOrigin = origin(url)
        val sourceHost = runCatching { URI(referer).host.orEmpty().removePrefix("www.") }.getOrNull().orEmpty().ifBlank { "palacepalace.com" }
        val apiUrls = listOf(
            "$playerOrigin/api/v1/video?id=$id&w=1280&h=720&r=$sourceHost",
            "$playerOrigin/api/v1/video?id=$id&w=421&h=935&r=$sourceHost"
        ).distinct()
        val playerHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to "$playerOrigin/"
        )
        val links = linkedSetOf<String>()
        apiUrls.forEach { apiUrl ->
            val encrypted = runCatching { app.get(apiUrl, headers = playerHeaders, referer = "$playerOrigin/").text }.getOrNull() ?: return@forEach
            val json = decryptPlayerPayload(encrypted) ?: return@forEach
            parsePlayerJson(json, playerOrigin).forEach(links::add)
        }
        val source = when {
            uri.host.orEmpty().contains("p2pplay", true) -> "Film21 P2PPlay"
            uri.host.orEmpty().contains("playerp2p", true) -> "Film21 PlayerP2P"
            uri.host.orEmpty().contains("sf21", true) -> "Film21 Sf21"
            else -> "Film21 Player"
        }
        return links.filter { it.isPlayableMedia() }.map { ResolvedPlayerLink(it, "$playerOrigin/", source) }
    }

    private fun parsePlayerJson(json: String, playerOrigin: String): List<String> {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val links = linkedSetOf<String>()
        listOf("source", "hlsVideoTiktok", "cf").forEach { key ->
            obj.optString(key).takeIf { it.isNotBlank() }?.let { raw ->
                val fixed = fixUrl(raw, playerOrigin) ?: return@let
                if (key == "hlsVideoTiktok") {
                    val version = tiktokVersion(obj)
                    links.add(if (version.isNotBlank() && !fixed.contains("?")) "$fixed?v=$version" else fixed)
                } else {
                    links.add(fixed)
                }
            }
        }
        return links.toList()
    }

    private fun tiktokVersion(obj: JSONObject): String {
        val config = obj.optString("streamingConfig")
        if (config.isBlank()) return ""
        return runCatching {
            JSONObject(config)
                .optJSONObject("adjust")
                ?.optJSONObject("Tiktok")
                ?.optJSONObject("params")
                ?.optString("v")
                .orEmpty()
        }.getOrDefault("")
    }

    private fun extractPlayerId(uri: URI, url: String): String {
        uri.rawFragment?.substringBefore("&")?.substringBefore("?")?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{4,32}")) }?.let { return it }
        Regex("""(?i)(?:[?&]id=|/)([a-z0-9_-]{4,32})(?:[&#/?]|$)""").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return ""
    }

    private fun decryptPlayerPayload(value: String): String? {
        val clean = value.trim()
        if (clean.startsWith("{")) return clean
        return runCatching {
            val cipherBytes = clean.replace(Regex("[^0-9a-fA-F]"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(playerKey, "AES"), IvParameterSpec(playerIv))
            String(cipher.doFinal(cipherBytes))
        }.getOrNull()
    }

    private fun xFileShareStream(html: String, baseUrl: String): String? {
        val host = runCatching { URI(baseUrl).host.orEmpty() }.getOrNull().orEmpty()
        if (!host.contains("minochinos.com", true) && !host.contains("earnvidjav.online", true)) return null
        val fileId = Regex("""\$\.cookie\(['"]file_id['"]\s*,\s*['"](\d+)['"]""").find(html)?.groupValues?.getOrNull(1) ?: return null
        val stream = Regex("""\|(\d{10})\|([a-z0-9]+)\|([A-Za-z0-9_-]{16,})\|""").findAll(html)
            .map { it.groupValues }
            .firstOrNull { it[3].length >= 20 } ?: return null
        return "${origin(baseUrl)}/stream/${stream[3]}/${stream[2]}/${stream[1]}/$fileId/master.m3u8"
    }

    private fun sourceType(document: Document, html: String): String? {
        val dataType = document.selectFirst("[data-type]")?.attr("data-type")?.lowercase(Locale.ROOT)
        if (!dataType.isNullOrBlank()) return dataType
        return Regex("""(?i)['"]type['"]\s*:\s*['"](movie|tv|episode)['"]""").find(html)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
    }

    private fun ajaxHeaders(origin: String, referer: String): Map<String, String> = baseHeaders + mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to origin,
        "Referer" to referer
    )

    private fun mediaReferer(url: String, referer: String): String {
        val mediaHost = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val refOrigin = origin(referer)
        return when {
            mediaHost.contains("playdulu.xyz") || mediaHost.contains("editdulu.xyz") || mediaHost.contains("havanabrown.im") -> "$refOrigin/"
            else -> referer
        }
    }

    private fun mediaHeaders(url: String, referer: String): Map<String, String> {
        val mediaHost = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val mediaReferer = mediaReferer(url, referer)
        val mediaOrigin = origin(mediaReferer)
        val base = baseHeaders + mapOf(
            "Accept" to "*/*",
            "Referer" to mediaReferer
        )
        return if (mediaHost.contains("playdulu.xyz") || mediaHost.contains("editdulu.xyz") || mediaHost.contains("havanabrown.im")) {
            base + mapOf("Origin" to mediaOrigin)
        } else {
            base
        }
    }

    private fun isEncryptedPlayerHost(host: String): Boolean =
        host.contains("sf21.vidplayer.live") || host.contains("p2pplay.pro") || host.contains("playerp2p.live")

    private fun isKnownExternal(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return listOf(
            "dood", "streamtape", "filemoon", "vidhide", "vidguard", "voe", "mixdrop", "streamwish", "wishfast", "mp4upload", "uqload", "krakenfiles", "streamlare", "filelions", "drive.google", "gdrive", "ok.ru", "streamsb", "sbembed", "upstream", "vidoza", "fembed", "feurl", "gofile", "pixeldrain"
        ).any { lower.contains(it) }
    }

    private fun fixUrl(value: String?, baseUrl: String): String? {
        val raw = urlDecode(value.orEmpty().replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").trim().trim('"', '\'', ',', ';'))
        if (raw.isBlank() || raw == "#" || raw.equals("null", true) || raw.startsWith("javascript:", true) || raw.startsWith("mailto:", true) || raw.startsWith("tel:", true) || raw.startsWith("data:", true) || raw.startsWith("blob:", true) || raw.startsWith("about:", true)) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> origin(baseUrl) + raw
            else -> runCatching { URI(baseUrl).resolve(raw).toString() }.getOrElse { origin(baseUrl) + "/" + raw.trimStart('/') }
        }
    }

    private fun origin(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(MAIN_URL)

    private fun normalize(value: String): String = urlDecode(value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&"))
    private fun urlDecode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun decodeBase64(value: String): String? {
        val raw = value.trim()
        if (raw.length < 8) return null
        val normalized = raw.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded)) }
            .getOrElse { runCatching { String(Base64.getUrlDecoder().decode(padded)) }.getOrNull() }
    }

    private fun String.isSubtitleUrl(): Boolean = lowercase(Locale.ROOT).let { it.endsWith(".srt") || it.endsWith(".vtt") }

    private fun String.isPlayableMedia(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".php") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.contains("mime=image") || lower.contains("=image/")) return false
        return lower.isM3u8Like() || lower.contains(".mp4") || lower.contains(".webm") || lower.contains("videoplayback") || lower.contains("mime=video") || (lower.contains("googlevideo.com") && lower.contains("videoplayback"))
    }

    private fun String.isM3u8Like(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".m3u8") || lower.contains("m3u8") || lower.contains("/hls/") || lower.contains("/stream/") || lower.contains("/play/token_hash")
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("facebook.com") || lower.contains("telegram") || lower.contains("twitter.com") || lower.contains("x.com") || lower.contains("whatsapp") || lower.contains("mailto:") || lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("doubleclick") || lower.contains("googlesyndication") || lower.contains("google-analytics") || lower.contains("/wp-content/") || lower.contains("/wp-json/") || lower.contains(".css") || lower.contains(".js") || lower.contains("favicon") || lower.contains("logo") || lower.contains("arwana") || lower.contains("slot") || lower.contains("togel") || lower.contains("bet")
    }

    private fun String.qualityFromUrl(): Int {
        val lower = lowercase(Locale.ROOT)
        return when {
            lower.contains("2160") || lower.contains("4k") -> 2160
            lower.contains("1440") || lower.contains("2k") -> 1440
            lower.contains("1080") -> 1080
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            else -> 0
        }
    }

    private val playerKey = "kiemtienmua911ca".toByteArray()
    private val playerIv = "1234567890oiuytr".toByteArray()
}
