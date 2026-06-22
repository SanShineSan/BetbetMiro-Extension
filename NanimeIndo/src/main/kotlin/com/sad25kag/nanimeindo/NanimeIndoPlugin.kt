package com.sad25kag.nanimeindo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NanimeIndoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NanimeIndoProvider())
    }
}
