package com.kyant.amdl.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.amdl.ui.Indication
import com.kyant.amdl.ui.Palette
import com.kyant.amdl.ui.TopBar
import com.kyant.shapes.RoundedRectangle

@Composable
fun SetupWizardScene(appState: AppState) {
    val downloadPathPicker = appState.downloadPathPicker()

    appState.ObserveStoragePermission()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(16f.dp),
        verticalArrangement = Arrangement.spacedBy(8f.dp)
    ) {
        TopBar("AMDL 设置向导")

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedRectangle(24f.dp))
                .clickable { appState.navBackStack += Scene.Login }
                .drawBehind {
                    drawRect(
                        if (appState.isLoggedIn) Color.Green.copy(0.2f)
                        else Color.Red.copy(0.2f)
                    )
                }
                .padding(16f.dp, 32f.dp)
        ) {
            BasicText(
                "登录 Apple Music 账户",
                style = TextStyle(Palette.content, 18f.sp)
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedRectangle(24f.dp))
                .clickable { appState.grantStoragePermission() }
                .drawBehind {
                    drawRect(
                        if (appState.isStoragePermissionGranted) Color.Green.copy(0.2f)
                        else Color.Red.copy(0.2f)
                    )
                }
                .padding(16f.dp, 32f.dp)
        ) {
            BasicText(
                "授予存储权限",
                style = TextStyle(Palette.content, 18f.sp)
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedRectangle(24f.dp))
                .clickable { downloadPathPicker.launch(null) }
                .drawBehind {
                    drawRect(
                        if (appState.isDownloadPathSet) Color.Green.copy(0.2f)
                        else Color.Red.copy(0.2f)
                    )
                }
                .padding(16f.dp, 32f.dp)
        ) {
            BasicText(
                "选择下载位置",
                style = TextStyle(Palette.content, 18f.sp)
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedRectangle(24f.dp))
                .clickable { appState.navBackStack += Scene.Settings }
                .background(Palette.card)
                .padding(16f.dp, 32f.dp)
        ) {
            BasicText(
                "查看其他设置项",
                style = TextStyle(Palette.content, 18f.sp)
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedRectangle(24f.dp))
                .clickable(
                    interactionSource = null,
                    indication = Indication(Color.White),
                    enabled = appState.isSetupComplete
                ) {
                    Snapshot.withMutableSnapshot {
                        appState.navBackStack.clear()
                        appState.navBackStack += Scene.Main
                    }
                }
                .background(
                    if (appState.isSetupComplete) Palette.accent
                    else Palette.card
                )
                .padding(16f.dp, 32f.dp)
        ) {
            BasicText(
                "完成",
                style = TextStyle(
                    if (appState.isSetupComplete) Color.White
                    else Palette.content.copy(0.5f),
                    18f.sp
                )
            )
        }
    }
}
