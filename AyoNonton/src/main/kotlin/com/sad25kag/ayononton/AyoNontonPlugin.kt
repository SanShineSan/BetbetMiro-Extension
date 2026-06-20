package com.sad25kag.ayononton

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AyoNontonPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AyoNonton())
    }
}
