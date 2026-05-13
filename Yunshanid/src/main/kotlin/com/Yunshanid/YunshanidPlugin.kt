package com.Yunshanid

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class YunshanidPlugin: Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan API utama
        registerMainAPI(YunshanidProvider())
    }
}
