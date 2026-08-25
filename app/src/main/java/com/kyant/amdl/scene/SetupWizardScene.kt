package com.kyant.amdl.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

        Column(
            Modifier
                .fillMaxWidth()
                .height(80f.dp)
                .clip(RoundedRectangle(24f.dp))
                .clickable { appState.navBackStack += Scene.Login }
                .background(Palette.card)
                .padding(horizontal = 16f.dp),
            verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically)
        ) {
            BasicText(
                "登录 Apple Music 账户",
                style = TextStyle(Palette.content, 18f.sp)
            )
            BasicText(
                if (appState.isLoggedIn) "已登录" else "未登录",
                style = TextStyle(
                    if (appState.isLoggedIn) Palette.content.copy(0.6f) else Color.Red,
                    14f.sp
                )
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .height(80f.dp)
                .clip(RoundedRectangle(24f.dp))
                .clickable { appState.grantStoragePermission() }
                .background(Palette.card)
                .padding(horizontal = 16f.dp),
            verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically)
        ) {
            BasicText(
                "授予存储权限",
                style = TextStyle(Palette.content, 18f.sp)
            )
            BasicText(
                if (appState.isStoragePermissionGranted) "已授予" else "未授予",
                style = TextStyle(
                    if (appState.isStoragePermissionGranted) Palette.content.copy(0.6f) else Color.Red,
                    14f.sp
                )
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .height(80f.dp)
                .clip(RoundedRectangle(24f.dp))
                .clickable { downloadPathPicker.launch(null) }
                .background(Palette.card)
                .padding(horizontal = 16f.dp),
            verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically)
        ) {
            BasicText(
                "选择下载位置",
                style = TextStyle(Palette.content, 18f.sp)
            )
            BasicText(
                if (appState.isDownloadPathSet) "已设置" else "未设置",
                style = TextStyle(
                    if (appState.isDownloadPathSet) Palette.content.copy(0.6f) else Color.Red,
                    14f.sp
                )
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .height(80f.dp)
                .clip(RoundedRectangle(24f.dp))
                .clickable { appState.navBackStack += Scene.Settings }
                .background(Palette.card)
                .padding(horizontal = 16f.dp),
            verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically)
        ) {
            BasicText(
                "查看其他设置项",
                style = TextStyle(Palette.content, 18f.sp)
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .height(80f.dp)
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
                .background(if (appState.isSetupComplete) Palette.accent else Palette.card)
                .padding(horizontal = 16f.dp),
            verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically)
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
