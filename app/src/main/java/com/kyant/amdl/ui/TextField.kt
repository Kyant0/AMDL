package com.kyant.amdl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    hint: String? = null,
    height: Dp = 48f.dp,
    contentPadding: PaddingValues = PaddingValues.Zero,
    alignToEnd: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null
) {
    BasicTextField(
        state,
        modifier
            .fillMaxWidth()
            .height(height),
        textStyle = TextStyle(
            Palette.content,
            16f.sp,
            textAlign = if (alignToEnd) TextAlign.End else TextAlign.Start
        ),
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits = TextFieldLineLimits.SingleLine,
        cursorBrush = SolidColor(Palette.content),
        decorator = { innerTextField ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                if (alignToEnd) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                if (hint != null && state.text.isBlank()) {
                    BasicText(
                        hint,
                        style = TextStyle(
                            Palette.content.copy(0.5f),
                            16f.sp,
                            textAlign = if (alignToEnd) TextAlign.End else TextAlign.Start
                        )
                    )
                }

                innerTextField()
            }
        }
    )
}
