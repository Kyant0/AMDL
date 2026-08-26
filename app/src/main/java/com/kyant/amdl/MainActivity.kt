package com.kyant.amdl

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import com.kyant.amdl.scene.LanguageScene
import com.kyant.amdl.scene.LoginScene
import com.kyant.amdl.scene.MainScene
import com.kyant.amdl.scene.Scene
import com.kyant.amdl.scene.SettingsScene
import com.kyant.amdl.scene.SetupWizardScene
import com.kyant.amdl.ui.Indication
import com.kyant.amdl.ui.Palette

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        enableEdgeToEdge()
        val appState = (application as MainApp).appState
        setContent {
            BackHandler(appState.navBackStack.size > 1) {
                appState.navBackStack.removeLastOrNull()
            }

            CompositionLocalProvider(
                LocalIndication provides Indication(Palette.content)
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Palette.background)
                ) {
                    when (appState.navBackStack.last()) {
                        Scene.SetupWizard -> SetupWizardScene(appState)
                        Scene.Login -> LoginScene(appState)
                        Scene.Main -> MainScene(appState)
                        Scene.Settings -> SettingsScene(appState)
                        Scene.Language -> LanguageScene(appState)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val url = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val appState = (application as MainApp).appState
        if (!appState.isLoggedIn) return
        Snapshot.withMutableSnapshot {
            appState.navBackStack.clear()
            appState.navBackStack += Scene.Main
        }
        appState.parseFromUrl(url)
    }
}
