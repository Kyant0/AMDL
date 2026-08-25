package com.kyant.amdl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle

@Composable
inline fun SettingsListSection(
    title: String,
    content: @Composable () -> Unit
) {
    BasicText(
        title,
        Modifier.padding(16f.dp, 8f.dp, 0f.dp, 0f.dp),
        style = TextStyle(Palette.content.copy(0.6f), 16f.sp, FontWeight.SemiBold)
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedRectangle(24f.dp))
            .background(Palette.card)
    ) {
        content()
    }
}

@Composable
fun SettingsListItem(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(16f.dp, 12f.dp),
        horizontalArrangement = Arrangement.spacedBy(8f.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2f.dp)
        ) {
            BasicText(
                title,
                style = TextStyle(Palette.content, 16f.sp)
            )
            if (subtitle != null) {
                BasicText(
                    subtitle,
                    style = TextStyle(Palette.content.copy(0.6f), 14f.sp)
                )
            }
        }

        if (action != null) {
            action()
        }
    }
}

@Composable
fun SettingsListDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5f.dp)
            .padding(horizontal = 16f.dp)
            .background(Palette.content.copy(0.2f))
    )
}
