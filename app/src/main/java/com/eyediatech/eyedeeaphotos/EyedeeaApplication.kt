package com.eyediatech.eyedeeaphotos

import android.app.Application
import com.eyediatech.eyedeeaphotos.api.RetrofitClient

class EyedeeaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RetrofitClient.init(this)
    }
}
