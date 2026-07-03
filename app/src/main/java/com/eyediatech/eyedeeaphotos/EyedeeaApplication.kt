package com.eyediatech.eyedeeaphotos

import android.app.Application
import com.eyediatech.eyedeeaphotos.api.RetrofitClient

class EyedeeaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.eyediatech.eyedeeaphotos.utils.FileLogger.init(this)
        RetrofitClient.init(this)
    }
}
