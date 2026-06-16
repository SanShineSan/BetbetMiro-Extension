package com.sad25kag.Animexin

import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimexinProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(Animexin())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(AnimexinVtbe())
        registerExtractorAPI(AnimexinWishFast())
        registerExtractorAPI(AnimexinSeekPlayer())
        registerExtractorAPI(AnimexinWaaw())
        registerExtractorAPI(AnimexinFileMoon())
    }
}
