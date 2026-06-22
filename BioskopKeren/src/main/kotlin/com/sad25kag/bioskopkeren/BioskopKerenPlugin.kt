package com.sad25kag.bioskopkeren

import android.content.Context
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BioskopKerenPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BioskopKeren())
        registerExtractorAPI(BioskopKerenVidHide())
    }
}

class BioskopKerenVidHide : VidHidePro() {
    override var name = "VidHide"
    override var mainUrl = "https://vidhide.org"
}
