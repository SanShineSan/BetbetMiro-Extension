package com.nodrakorid

import com.lagradost.cloudstream3.extractors.Mp4Upload
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NoDrakorIDPlugin : Plugin() {
    override fun load() {
        registerMainAPI(NoDrakorIDProvider())

        registerExtractorAPI(NoDrakorIDJeniusplay())
        registerExtractorAPI(NoDrakorIDMajorplay())
        registerExtractorAPI(NoDrakorIDE2eMajorplay())
        registerExtractorAPI(NoDrakorIDM3u8Majorplay())
        registerExtractorAPI(NoDrakorIDHglink())
        registerExtractorAPI(NoDrakorIDGhbrisk())
        registerExtractorAPI(NoDrakorIDDhcplay())
        registerExtractorAPI(NoDrakorIDStreamcasthub())
        registerExtractorAPI(NoDrakorIDDm21embed())
        registerExtractorAPI(NoDrakorIDMeplayer())
        registerExtractorAPI(NoDrakorIDStreamWish())
        registerExtractorAPI(NoDrakorIDFileMoon())
        registerExtractorAPI(NoDrakorIDDood())
        registerExtractorAPI(NoDrakorIDBloggerVideo())
        registerExtractorAPI(NoDrakorIDGdplayer())
        registerExtractorAPI(NoDrakorIDAWSStream())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Mp4Upload())
    }
}
