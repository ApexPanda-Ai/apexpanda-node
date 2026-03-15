package com.apexpanda.node

import android.app.Application

class ApexPandaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        var instance: ApexPandaApp? = null
    }
}
