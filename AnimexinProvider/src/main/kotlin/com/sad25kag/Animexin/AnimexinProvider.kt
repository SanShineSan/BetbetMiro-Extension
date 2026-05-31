package com.sad25kag.Animexin

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.Dailymotion

@CloudstreamPlugin
class AnimexinProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(Animexin())
        registerExtractorAPI(Dailymotion())
    }
}
