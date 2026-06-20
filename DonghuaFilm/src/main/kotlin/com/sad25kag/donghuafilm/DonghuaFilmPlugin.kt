package com.sad25kag.donghuafilm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaFilmPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DonghuaFilmCosmetic())
        registerExtractorAPI(DonghuaFilmDailyMotion())
        registerExtractorAPI(DonghuaFilmGeoDailyMotion())
        registerExtractorAPI(DonghuaFilmOdnoklassniki())
        registerExtractorAPI(DonghuaFilmOkRuSSL())
        registerExtractorAPI(DonghuaFilmOkRuHTTP())
    }
}
