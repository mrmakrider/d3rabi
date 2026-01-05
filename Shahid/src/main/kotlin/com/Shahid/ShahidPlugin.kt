package com.shahid

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ShahidPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Shahid())
    }
}
