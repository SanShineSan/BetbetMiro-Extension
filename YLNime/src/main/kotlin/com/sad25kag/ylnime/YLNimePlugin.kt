package com.sad25kag.ylnime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YLNimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YLNime())
    }
}
