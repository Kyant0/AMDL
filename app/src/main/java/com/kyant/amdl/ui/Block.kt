package com.kyant.amdl.ui

import androidx.compose.runtime.Composable

@Composable
fun Block(content: @Composable () -> Unit) {
    content()
}
