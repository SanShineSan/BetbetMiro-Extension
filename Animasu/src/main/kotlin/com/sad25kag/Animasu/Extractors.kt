package com.sad25kag.Animasu

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import org.json.JSONObject
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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

        val res = app.get(url).document

        val json =
            res.selectFirst("div#app")
                ?.attr("data-page")

        if (json.isNullOrBlank()) return

        val video = runCatching {
            JSONObject(json)
                .optJSONObject("props")
                ?.optJSONObject("datas")
                ?.optJSONObject("data")
                ?.optJSONObject("link")
                ?.optString("media")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

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

    data class Link(
        @JsonProperty("media")
        val media: String? = null,
    )

    data class Data(
        @JsonProperty("link")
        val link: Link? = null,
    )

    data class Datas(
        @JsonProperty("data")
        val data: Data? = null,
    )

    data class Props(
        @JsonProperty("datas")
        val datas: Datas? = null,
    )

    data class Sources(
        @JsonProperty("props")
        val props: Props? = null,
    )
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
            "VIDEO_CONFIG\\s?=\\s?(\\{.*?\\})"
                .toRegex()
                .find(doc)
                ?.groupValues
                ?.get(1)
                ?: "VIDEO_CONFIG\\s?=\\s?(.*)"
                    .toRegex()
                    .find(doc)
                    ?.groupValues
                    ?.get(1)

        if (json.isNullOrBlank()) return

        val streams = runCatching {
            JSONObject(json).optJSONArray("streams")
        }.getOrNull() ?: return

        for (index in 0 until streams.length()) {
            val stream = streams.optJSONObject(index) ?: continue
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

    data class Streams(
        @JsonProperty("play_url")
        val playUrl: String? = null,

        @JsonProperty("format_id")
        val formatId: Int? = null,
    )

    data class Sources(
        @JsonProperty("streams")
        val streams: ArrayList<Streams>? = null,
    )
}

class Vidhidepro : Filesim() {

    override val mainUrl = "https://vidhidepro.com"
    override val name = "Vidhidepro"
}