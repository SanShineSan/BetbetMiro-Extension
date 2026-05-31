package com.sad25kag.Animasu

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

private fun String?.toJsonObjectOrNull(): JSONObject? {
    if (this.isNullOrBlank()) return null
    return runCatching { JSONObject(this) }.getOrNull()
}

class Archivd : ExtractorApi() {

    override val name: String = "Archivd"
    override val mainUrl: String = "https://archivd.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val json =
            app.get(url).document
                .selectFirst("div#app")
                ?.attr("data-page")

        val video = json.toJsonObjectOrNull()
            ?.optJSONObject("props")
            ?.optJSONObject("datas")
            ?.optJSONObject("data")
            ?.optJSONObject("link")
            ?.optString("media")
            ?.takeIf { it.isNotBlank() }

        if (video.isNullOrBlank()) return

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                video,
                INFER_TYPE
            ) {
                this.referer = "$mainUrl/"
            }
        )
    }
}

class Newuservideo : ExtractorApi() {

    override val name: String = "Uservideo"
    override val mainUrl: String = "https://new.uservideo.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val headersMap =
            referer?.let {
                mapOf("Referer" to it)
            } ?: emptyMap()

        val iframeSrc =
            app.get(url, headers = headersMap)
                .document
                .selectFirst("iframe#videoFrame")
                ?.attr("src")

        if (iframeSrc.isNullOrBlank()) return

        val iframeUrl =
            if (iframeSrc.startsWith("http")) {
                iframeSrc
            } else {
                "$mainUrl$iframeSrc"
            }

        val doc =
            app.get(
                iframeUrl,
                headers = mapOf(
                    "Referer" to "$mainUrl/"
                )
            ).text

        val json =
            Regex("""VIDEO_CONFIG\s?=\s?(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)
                .find(doc)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""VIDEO_CONFIG\s?=\s?(.*)""", RegexOption.DOT_MATCHES_ALL)
                    .find(doc)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.substringBefore("</script>")
                    ?.trim()
                    ?.trimEnd(';')

        val streams = json.toJsonObjectOrNull()
            ?.optJSONArray("streams")
            ?: return

        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val playUrl = stream.optString("play_url").takeIf { it.isNotBlank() } ?: continue
            val formatId = stream.optInt("format_id", -1)

            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    playUrl,
                    INFER_TYPE
                ) {
                    this.referer = "$mainUrl/"

                    this.quality =
                        when (formatId) {
                            18 -> Qualities.P360.value
                            22 -> Qualities.P720.value
                            else -> Qualities.Unknown.value
                        }
                }
            )
        }
    }
}

class Vidhidepro : Filesim() {

    override val mainUrl = "https://vidhidepro.com"
    override val name = "Vidhidepro"
}
