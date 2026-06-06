package com.sad25kag.yurinime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YuriNimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YuriNime())
    }
}
