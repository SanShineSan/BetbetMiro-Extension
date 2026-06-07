package com.sad25kag.Animekhor

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup


class Donghuaword  : Animekhor() {
    override var mainUrl              = "https://donghuaworld.com"
    override var name                 = "Donghuaword"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.Anime)

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        var found = false

        suspend fun resolveDecodedMirror(raw: String) {
            val decodedUrl = runCatching { base64Decode(raw) }.getOrNull() ?: return
            val decodedDocument = Jsoup.parse(decodedUrl)

            val candidates = linkedSetOf<String>()

            decodedDocument.select("iframe[src]").forEach { iframe ->
                candidates.add(iframe.attr("src"))
            }

            Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(decodedUrl)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .forEach { candidates.add(it) }

            candidates.forEach { url ->
                runCatching {
                    loadExtractor(url, referer = mainUrl, subtitleCallback) { link ->
                        found = true
                        callback(link)
                    }
                }
            }
        }

        document.select("div.server-item a[data-hash], .server-item a[data-hash], a[data-hash]").forEach {
            resolveDecodedMirror(it.attr("data-hash"))
        }

        document.select(".mobius select.mirror option[value], select.mirror option[value], option[value]").forEach {
            resolveDecodedMirror(it.attr("value"))
        }

        document.select("iframe[src]").forEach {
            runCatching {
                loadExtractor(it.attr("src"), referer = mainUrl, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            }
        }

        return found
    }
}