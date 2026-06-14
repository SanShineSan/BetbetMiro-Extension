package com.pasarbokep

import android.content.Context
import com.lagradost.cloudstream3.extractors.Mp4Upload
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PasarBokepPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PasarBokepProvider())

        registerExtractorAPI(PasarBokepStreamSB())
        registerExtractorAPI(PasarBokepSbrisk())
        registerExtractorAPI(PasarBokepSbfull())
        registerExtractorAPI(PasarBokepSblanh())
        registerExtractorAPI(PasarBokepSbplay())
        registerExtractorAPI(PasarBokepWaaw())
        registerExtractorAPI(PasarBokepDood())
        registerExtractorAPI(PasarBokepDoodWf())
        registerExtractorAPI(PasarBokepDoodTo())
        registerExtractorAPI(PasarBokepDoodRe())
        registerExtractorAPI(PasarBokepStreamWish())
        registerExtractorAPI(PasarBokepWishFast())
        registerExtractorAPI(PasarBokepFileMoon())
        registerExtractorAPI(PasarBokepFileLions())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Mp4Upload())
    }
}
