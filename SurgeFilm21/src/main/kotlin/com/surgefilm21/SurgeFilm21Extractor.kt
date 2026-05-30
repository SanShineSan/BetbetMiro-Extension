package com.surgefilm21

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

object SurgeFilm21Extractor {
    private val candidatePatterns = listOf(
        Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""(?:file|src|source|video|videoUrl|video_url|hls|url|embed|embed_url)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""["']((?:https?:)?//[^"']+(?:\.m3u8|\.mp4|\.webm|\.mpd)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE),
        Regex("""["'](https?://[^"']*(?:dood|streamtape|filemoon|vidhide|vidguard|voe|mixdrop|streamwish|wishfast|mp4upload|uqload|krakenfiles|streamlare|filelions|gdrive|drive.google)[^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""["']((?:/[^"']*)/(?:embed|player|stream|get|watch|video)[^"']*)["']""", RegexOption.IGNORE_CASE)
    )

    suspend fun load(pageUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var found = false

        fun emit(link: ExtractorLink) {
            val key = link.url.substringBefore("?token=").substringBefore("&token=")
            if (emitted.add(key)) {
                found = true
                callback(link)
            }
        }

        suspend fun resolve(raw: String?, referer: String = pageUrl, depth: Int = 0) {
            val url = raw.absUrlSf21(referer) ?: return
            if (url.isNoiseUrlSf21()) return
            if (!visited.add(url)) return

            if (url.isVideoUrlSf21()) {
                emitDirect(url, referer, ::emit)
                return
            }

            runCatching {
                val success = loadExtractor(url, referer, subtitleCallback, callback)
                if (success) found = true
            }

            if (depth >= 2 || isKnownExternal(url)) return

            val response = runCatching {
                app.get(url, headers = SurgeFilm21Sepeda.baseHeaders + mapOf("Referer" to referer), referer = referer, timeout = 15000L)
            }.getOrNull() ?: return

            val contentType = response.headers["Content-Type"].orEmpty().lowercase()
            val contentLength = response.headers["Content-Length"]?.toLongOrNull()
            if (contentType.startsWith("video/") || contentType.contains("mpegurl") || contentType.contains("dash") || contentType.contains("octet-stream") || (contentLength != null && contentLength > 5_000_000L)) return

            val body = runCatching { response.text.cleanSf21() }.getOrNull() ?: return
            collectCandidates(body, url).forEach { resolve(it, url, depth + 1) }
        }

        val html = SurgeFilm21Sepeda.getText(pageUrl, SurgeFilm21Provider.DEFAULT_MAIN_URL).cleanSf21()
        val document = SurgeFilm21Parser.parseDocumentFromHtml(html, pageUrl)

        document.select("iframe[src], iframe[data-src], embed[src], video source[src], video[src], source[src], a[href*='.mp4'], a[href*='.m3u8'], a[href*='.webm'], a[href*='embed'], a[href*='player'], [data-src], [data-embed], [data-video], [data-url], [data-link]")
            .forEach { element ->
                listOf("src", "data-src", "href", "data-embed", "data-video", "data-url", "data-link")
                    .map { element.attr(it) }
                    .firstOrNull { it.isNotBlank() }
                    ?.let { candidate -> runCatching { resolve(candidate, pageUrl, 0) } }
            }

        collectCandidates(html, pageUrl).forEach { resolve(it, pageUrl, 0) }
        return found
    }

    fun collectCandidates(html: String, baseUrl: String): Set<String> {
        val clean = html.cleanSf21()
        val out = linkedSetOf<String>()

        Regex("""atob\(["']([^"']+)["']\)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { decodeBase64Sf21(it.groupValues[1]) }
            .forEach { decoded ->
                candidatePatterns.forEach { pattern ->
                    pattern.findAll(decoded.cleanSf21()).forEach { match ->
                        match.groupValues.getOrNull(1)?.absUrlSf21(baseUrl)?.let(out::add)
                    }
                }
            }

        candidatePatterns.forEach { pattern ->
            pattern.findAll(clean).forEach { match ->
                match.groupValues.getOrNull(1)?.absUrlSf21(baseUrl)?.let(out::add)
            }
        }

        return out.filterNot { it.isNoiseUrlSf21() }.toCollection(linkedSetOf())
    }

    private fun emitDirect(url: String, referer: String, emit: (ExtractorLink) -> Unit) {
        emit(
            newExtractorLink("SurgeFilm21", "SurgeFilm21 ${url.qualitySf21().takeIf { it > 0 } ?: "Auto"}", url, INFER_TYPE) {
                this.referer = referer
                this.quality = url.qualitySf21()
                this.headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
            }
        )
    }

    private fun isKnownExternal(url: String): Boolean {
        val lower = url.lowercase()
        return listOf("dood", "streamtape", "filemoon", "vidhide", "vidguard", "voe", "mixdrop", "streamwish", "wishfast", "mp4upload", "uqload", "krakenfiles", "streamlare", "filelions", "drive.google", "gdrive").any { lower.contains(it) }
    }
}
