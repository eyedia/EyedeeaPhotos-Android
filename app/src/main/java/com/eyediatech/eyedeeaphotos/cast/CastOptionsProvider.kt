package com.eyediatech.eyedeeaphotos.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.eyediatech.eyedeeaphotos.R

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            // Using a default test receiver ID for now. You need to replace this with your actual Custom Receiver App ID from the Google Cast Developer Console.
            // Replace with context.getString(R.string.cast_app_id) after creating it in strings.xml
            .setReceiverApplicationId(context.getString(R.string.cast_app_id))
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return null
    }
}
