package com.prestige

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PrestigeProvider : Plugin() {
    override fun load(context: Context) {
        // Register Prestige provider
        registerMainAPI(Prestige())
    }
}
