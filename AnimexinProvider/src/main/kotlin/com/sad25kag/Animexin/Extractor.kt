package com.sad25kag.Animexin

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class AnimexinVtbe : ExtractorApi() {
    override var name = "Vtbe"
    override var mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = referer ?: mainUrl).document
        val packed = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().orEmpty()
        JsUnpacker(packed).unpack()?.let { unpacked ->
            Regex("""sources:\[\{file:["'](.*?)["']""").find(unpacked)?.groupValues?.getOrNull(1)?.let { link ->
                return listOf(
                    newExtractorLink(name, name, link, ExtractorLinkType.M3U8) {
                        this.referer = referer ?: mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return null
    }
}

class AnimexinWishFast : StreamWishExtractor() {
    override var mainUrl = "https://wishfast.top"
    override var name = "StreamWish"
}

class AnimexinSeekPlayer : StreamWishExtractor() {
    override var mainUrl = "https://animexinfansub.seekplayer.vip"
    override var name = "Animexin StreamWish"
}

class AnimexinWaaw : StreamSB() {
    override var mainUrl = "https://waaw.to"
    override var name = "Waaw"
}

class AnimexinFileMoon : Filesim() {
    override val mainUrl = "https://filemoon.sx"
    override val name = "FileMoon"
}

class AnimexinDailymotion : Dailymotion()

fun Http(url: String): String {
    return if (url.startsWith("//")) "https:$url" else url
}
