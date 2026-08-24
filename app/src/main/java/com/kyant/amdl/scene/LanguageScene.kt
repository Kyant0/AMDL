package com.kyant.amdl.scene

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.amdl.ui.Palette
import com.kyant.amdl.ui.SettingsListDivider
import com.kyant.amdl.ui.SettingsListItem
import com.kyant.amdl.ui.SettingsListSection
import com.kyant.amdl.ui.TopBar
import java.util.Locale

@Composable
fun LanguageScene(appState: AppState) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(16f.dp),
        verticalArrangement = Arrangement.spacedBy(8f.dp)
    ) {
        TopBar(
            "选择资料库语言",
            actionButtonTitle = "返回",
            onActionButtonClick = { appState.navBackStack -= Scene.Language }
        )

        SettingsListSection("语言") {
            Locales.forEachIndexed { index, locale ->
                SettingsListItem(
                    title = locale.getDisplayName(locale),
                    subtitle = locale.displayName,
                    onClick = {
                        appState.setLanguage(locale.toLanguageTag())
                        appState.navBackStack -= Scene.Language
                    },
                    action = {
                        if (locale.toLanguageTag() == appState.config.language) {
                            BasicText(
                                "已选择",
                                style = TextStyle(Palette.accent, 14f.sp)
                            )
                        }
                    }
                )
                if (index < Locales.lastIndex) {
                    SettingsListDivider()
                }
            }
        }
    }
}

private val Locales =
    listOf(
        "zh-Hans-CN", // 简体中文
        "zh-Hant-TW", // 繁体中文（台湾）
        "zh-Hant-HK", // 繁体中文（香港）
        "en-US",      // 英语（美国）
        "en-GB",      // 英语（英国）
        "ja-JP",      // 日语
        "ko-KR",      // 韩语
        "fr-FR",      // 法语
        "de-DE",      // 德语
        "es-ES",      // 西班牙语
        "pt-BR",      // 葡萄牙语（巴西）
        "ru-RU",      // 俄语
        "it-IT",      // 意大利语
        "ar-SA",      // 阿拉伯语
        "hi-IN",      // 印地语
        "th-TH",      // 泰语
        "vi-VN",      // 越南语
        "id-ID",      // 印尼语
        "tr-TR"       // 土耳其语
    ).map { Locale.forLanguageTag(it) }
