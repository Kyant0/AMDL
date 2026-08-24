package com.kyant.amdl.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

object Palette {

    val background: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)

    val content: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color.White else Color.Black

    val card: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF1D1D1F) else Color.White

    val accent: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF0A84FF) else Color(0xFF007AFF)
}
