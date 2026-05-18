// ! Bu araç @ByAyzen tarafından | @Cs-GizliKeyif için yazılmıştır.
package com.byayzen

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HeavyPlugin: Plugin() {
    override fun load(context: Context) { // FIX: Tambah parameter context agar valid dengan superclass Plugin
        registerMainAPI(Heavy())
    }
}