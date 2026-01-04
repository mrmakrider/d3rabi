package com.cimalight

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CimaLightPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CimaLight())
    }
}
