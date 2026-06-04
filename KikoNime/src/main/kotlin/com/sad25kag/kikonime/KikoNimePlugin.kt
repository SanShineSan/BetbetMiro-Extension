package com.sad25kag.kikonime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KikoNimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KikoNime())
        registerExtractorAPI(KikoDailyMotion())
        registerExtractorAPI(KikoGeoDailyMotion())
        registerExtractorAPI(KikoOdnoklassniki())
        registerExtractorAPI(KikoOkRuSSL())
        registerExtractorAPI(KikoOkRuHTTP())
    }
}
