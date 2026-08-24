package com.kyant.amdl

import android.app.Application
import com.kyant.amdl.scene.AppState

class MainApp : Application() {

    lateinit var appState: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("amdl")
        appState = AppState(applicationContext)
    }
}
