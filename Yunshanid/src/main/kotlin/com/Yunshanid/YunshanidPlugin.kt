package com.betbet.yunshanid

import android.content.Context
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YunshanIDPlugin : Plugin() {

    override fun load(context: Context) {

        registerMainAPI(YunshanidProvider())

        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Krakenfiles())
        registerExtractorAPI(Mediafire())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(StreamWish())
        registerExtractorAPI(VidHidePro())
    }
}