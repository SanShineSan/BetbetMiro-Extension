package com.sad25kag.moenime

import android.content.Context
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.extractors.Geodailymotion
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoenimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Moenime())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
    }
}
