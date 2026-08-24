package com.kyant.amdl.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

@Composable
fun Switch(
    checked: () -> Boolean,
    onCheckedChange: (checked: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val background = Palette.background
    val accent = Palette.accent

    val toggleAnimation = remember { Animatable(if (checked()) 1f else 0f) }
    LaunchedEffect(checked) {
        val animationSpec = spring<Float>(1f, 600f)
        snapshotFlow(checked)
            .drop(1)
            .collectLatest { isChecked ->
                toggleAnimation.animateTo(if (isChecked) 1f else 0f, animationSpec)
            }
    }

    Box(modifier) {
        Box(
            Modifier
                .size(56f.dp, 32f.dp)
                .clip(Capsule())
                .clickable { onCheckedChange(!checked()) }
                .drawBehind {
                    drawRect(background)
                    drawRect(accent.copy(toggleAnimation.value))
                }
        )

        Box(
            Modifier
                .size(24f.dp)
                .graphicsLayer {
                    translationX = lerp(4f.dp.toPx(), 28f.dp.toPx(), toggleAnimation.value)
                    translationY = 4f.dp.toPx()
                }
                .background(Palette.card, CircleShape)
        )
    }
}
