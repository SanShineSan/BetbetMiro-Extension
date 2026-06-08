package com.jsi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JSIPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JSIProvider())
    }
}
