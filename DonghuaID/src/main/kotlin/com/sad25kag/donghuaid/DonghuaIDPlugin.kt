package com.sad25kag.donghuaid

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaIDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DonghuaID())
    }
}
