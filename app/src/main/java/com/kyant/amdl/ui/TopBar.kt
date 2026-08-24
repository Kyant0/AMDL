package com.kyant.amdl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle

@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    actionButtonTitle: String? = null,
    onActionButtonClick: (() -> Unit)? = null
) {
    Row(
        modifier
            .padding(16f.dp, 8f.dp, 0f.dp, 8f.dp)
            .fillMaxWidth()
            .height(56f.dp),
        horizontalArrangement = Arrangement.spacedBy(16f.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            title,
            Modifier.weight(1f),
            style = TextStyle(Palette.content, 28f.sp, FontWeight.SemiBold),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1
        )

        if (actionButtonTitle != null && onActionButtonClick != null) {
            Box(
                Modifier
                    .height(40f.dp)
                    .clip(RoundedRectangle(12f.dp))
                    .clickable(
                        interactionSource = null,
                        indication = Indication(Palette.accent),
                        onClick = onActionButtonClick
                    )
                    .padding(horizontal = 16f.dp),
                Alignment.Center
            ) {
                BasicText(
                    actionButtonTitle,
                    style = TextStyle(Palette.accent, 16f.sp)
                )
            }
        }
    }
}
