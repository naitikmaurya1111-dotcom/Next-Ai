package com.agychat.app

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class AGYChatApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Global Uncaught Exception Shield & Logger
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("AGYChatApp", "FATAL EXCEPTION on thread: ${thread.name}", throwable)
                val crashFile = File(filesDir, "last_crash.txt")
                crashFile.writeText(
                    "Timestamp: ${System.currentTimeMillis()}\n" +
                    "Thread: ${thread.name}\n" +
                    "Exception: ${throwable.javaClass.name}: ${throwable.message}\n" +
                    "Stacktrace:\n${throwable.stackTraceToString()}\n"
                )
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
