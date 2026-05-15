package com.hereliesaz.doxray

import android.app.Application
import com.hereliesaz.doxray.net.HttpClients

class DoxrayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HttpClients.init(this)
    }
}
