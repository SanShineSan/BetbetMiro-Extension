package com.sad25kag.sarangfilm21

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SarangFilm21Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SarangFilm21())
    }
}
