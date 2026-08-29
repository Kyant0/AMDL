package com.kyant.amdl.scene

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.kyant.amdl.ui.SettingsListDivider
import com.kyant.amdl.ui.SettingsListItem
import com.kyant.amdl.ui.SettingsListSection
import com.kyant.amdl.ui.Switch
import com.kyant.amdl.ui.TopBar
import java.util.Locale

@Composable
fun SettingsScene(appState: AppState) {
    val context = LocalContext.current

    val downloadPathPicker = appState.downloadPathPicker()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(16f.dp),
        verticalArrangement = Arrangement.spacedBy(8f.dp)
    ) {
        TopBar(
            "设置",
            actionButtonTitle = "返回",
            onActionButtonClick = { appState.navBackStack -= Scene.Settings }
        )

        SettingsListSection("账户") {
            SettingsListItem(
                title = "Apple Music 账户",
                subtitle = if (appState.isLoggedIn) "已登录" else "未登录",
                onClick = { appState.navBackStack += Scene.Login }
            )
            SettingsListDivider()
            SettingsListItem(
                title = "资料库语言",
                subtitle = Locale.forLanguageTag(appState.config.language).let { it.getDisplayName(it) },
                onClick = { appState.navBackStack += Scene.Language }
            )
        }

        SettingsListSection("音频") {
            SettingsListItem(
                title = "空间音频",
                subtitle = "下载 E-AC-3 JOC 格式的音频文件（若可用）",
                action = {
                    Switch(
                        checked = { appState.config.preferAtmos },
                        onCheckedChange = { appState.setPreferAtmos(it) }
                    )
                }
            )
        }

        SettingsListSection("下载") {
            SettingsListItem(
                title = "下载位置",
                subtitle = appState.config.downloadPath.removePrefix("/storage/emulated/0").ifEmpty { "未设置" },
                onClick = { downloadPathPicker.launch(null) }
            )
            SettingsListDivider()
            SettingsListItem(
                title = "按专辑目录保存歌曲文件",
                subtitle = "开启后，歌曲将保存至艺人的专辑目录；关闭后，所有歌曲将保存至同一目录",
                action = {
                    Switch(
                        checked = { appState.config.saveByAlbum },
                        onCheckedChange = { appState.setSaveByAlbum(it) }
                    )
                }
            )
            SettingsListDivider()
            SettingsListItem(
                title = "归并单曲",
                subtitle = "单曲将保存至艺人的 \"Singles\" 目录下",
                action = {
                    Switch(
                        checked = { appState.config.mergeSingles },
                        onCheckedChange = { appState.setMergeSingles(it) }
                    )
                }
            )
            SettingsListDivider()
            SettingsListItem(
                title = "保存 TTML 逐字歌词文件",
                action = {
                    Switch(
                        checked = { appState.config.saveTtml },
                        onCheckedChange = { appState.setSaveTtml(it) }
                    )
                }
            )
        }

        SettingsListSection("关于") {
            val repoUrl = "https://github.com/Kyant0/AMDL"
            SettingsListItem(
                title = "GitHub",
                subtitle = repoUrl,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, repoUrl.toUri())
                    context.startActivity(intent)
                }
            )
        }
    }
}
