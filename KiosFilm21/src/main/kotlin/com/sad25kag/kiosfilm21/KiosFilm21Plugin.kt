package com.sad25kag.kiosfilm21

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KiosFilm21Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KiosFilm21())
    }
}
