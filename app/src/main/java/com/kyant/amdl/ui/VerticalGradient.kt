package com.kyant.amdl.ui

import androidx.compose.animation.core.Easing
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import androidx.core.graphics.createBitmap

fun Modifier.verticalGradient(color: Color, startOpacity: Float, stopOpacity: Float, easing: Easing) =
    this.drawWithCache {
        val painter = VerticalGradientPainter(color) { t ->
            lerp(startOpacity, stopOpacity, easing.transform(t))
        }
        onDrawWithContent {
            with(painter) { draw(size) }
        }
    }

private class VerticalGradientPainter(
    private val color: Color,
    private val alphaAt: (t: Float) -> Float
) : Painter() {

    override val intrinsicSize: Size = Size.Unspecified

    private val argb = color.toArgb()
    private var _gradientSize: Int = 0
    private var imageBitmap: ImageBitmap? = null

    override fun DrawScope.onDraw() {
        updateBitmap(size.height.toInt())
        imageBitmap?.let {
            drawImage(
                it,
                dstSize = size.toIntSize()
            )
        }
    }

    private fun updateBitmap(size: Int) {
        if (_gradientSize == size) return
        _gradientSize = size

        if (size <= 0) {
            imageBitmap = null
            return
        }

        val pixels = IntArray(size)
        val colorAlpha = color.alpha
        for (y in 0 until size) {
            val t = y / (size - 1f)
            val alpha = colorAlpha * alphaAt(t)
            val a = (alpha.fastCoerceIn(0f, 1f) * 255f + 0.5f).toInt()
            pixels[y] = argb and 0x00FFFFFF or (a shl 24)
        }
        val bitmap = createBitmap(1, size)
        bitmap.setPixels(pixels, 0, 1, 0, 0, 1, size)
        imageBitmap = bitmap.asImageBitmap()
    }
}
