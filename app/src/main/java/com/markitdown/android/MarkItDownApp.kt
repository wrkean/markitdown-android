package com.markitdown.android

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Starts the embedded Python interpreter once per process.
 * The interpreter is required by Chaquopy for every [Python] call.
 */
class MarkItDownApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}
