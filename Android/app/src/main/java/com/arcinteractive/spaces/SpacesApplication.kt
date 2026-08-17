package com.arcinteractive.spaces

import android.app.Application
import android.util.Log
import com.giphy.sdk.ui.Giphy

class SpacesApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.GIF_SDK_API_KEY.isBlank()) {
            Log.w("GifSend", "GIF SDK key is missing. GIF sending will stay unavailable.")
            return
        }

        Giphy.configure(this, BuildConfig.GIF_SDK_API_KEY)
    }
}
