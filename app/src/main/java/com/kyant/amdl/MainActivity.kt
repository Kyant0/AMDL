package com.kyant.amdl

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
}
