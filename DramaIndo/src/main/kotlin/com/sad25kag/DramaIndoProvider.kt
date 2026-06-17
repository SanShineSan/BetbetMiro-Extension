package com.sad25kag

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DramaIndoProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(DramaIndo())
        registerExtractorAPI(BysetayicoFileMoon())
        registerExtractorAPI(DrakorkitaStream())
        registerExtractorAPI(NunaUpnsStream())
    }
}
