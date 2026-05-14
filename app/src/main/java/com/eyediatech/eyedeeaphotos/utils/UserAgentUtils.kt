package com.eyediatech.eyedeeaphotos.utils

import android.content.Context
import android.os.Build
import com.eyediatech.eyedeeaphotos.BuildConfig

object UserAgentUtils {
    fun getUserAgent(context: Context): String {
        val versionName = BuildConfig.VERSION_NAME
        val androidVersion = Build.VERSION.RELEASE
        val model = Build.MODEL
        
        val buildId = Build.ID
        
        if (BuildConfig.FLAVOR == "firetv") {
            return "EyedeeaPhotos/$versionName (FireTV; Android $androidVersion; $model Build/$buildId)"
        } else {
            val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
            val deviceType = if (isTablet) "tablet" else "mobile"
            return "EyedeeaPhotos/$versionName (Android; $deviceType; Android $androidVersion; $model Build/$buildId)"
        }
    }
}
