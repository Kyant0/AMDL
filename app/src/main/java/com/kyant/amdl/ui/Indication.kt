package com.kyant.amdl.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.util.fastCoerceAtLeast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class Indication(val color: Color) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        IndicationInstance(color, interactionSource)
}

private class IndicationInstance(
    private val color: Color,
    private val interactionSource: InteractionSource
) : DrawModifierNode, CompositionLocalConsumerModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private val alphaAnimation = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var pressCount = 0
            var hoverCount = 0
            var focusCount = 0

            var animationJob: Job? = null
            var releaseJob: Job? = null
            var targetAlpha = 0f
            var lastPressTime = 0L

            fun currentAlpha(): Float {
                return when {
                    pressCount > 0 -> PressedAlpha
                    hoverCount > 0 -> HoveredAlpha
                    focusCount > 0 -> FocusedAlpha
                    else -> 0f
                }
            }

            fun animateToAlpha(alpha: Float, snap: Boolean = false) {
                if (targetAlpha == alpha && (!snap || alphaAnimation.value == alpha)) return
                targetAlpha = alpha
                animationJob?.cancel()
                animationJob = launch {
                    if (snap) {
                        alphaAnimation.snapTo(alpha)
                    } else {
                        alphaAnimation.animateTo(alpha, AlphaAnimationSpec)
                    }
                }
            }

            fun scheduleRelease() {
                releaseJob?.cancel()
                releaseJob = launch {
                    val elapsed = (System.nanoTime() - lastPressTime) / 1_000_000L
                    delay((MinimumPressedMillis - elapsed).fastCoerceAtLeast(0L).milliseconds)
                    releaseJob = null
                    if (pressCount <= 0) {
                        pressCount = 0
                        animateToAlpha(currentAlpha())
                    }
                }
            }

            fun animateIfNotPressing() {
                if (pressCount <= 0 && releaseJob == null) {
                    animateToAlpha(currentAlpha())
                }
            }

            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        pressCount++
                        lastPressTime = System.nanoTime()
                        releaseJob?.cancel()
                        releaseJob = null
                        animateToAlpha(currentAlpha(), snap = true)
                    }

                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        pressCount--
                        if (pressCount <= 0) {
                            pressCount = 0
                            scheduleRelease()
                        }
                    }

                    is HoverInteraction.Enter -> {
                        hoverCount++
                        animateIfNotPressing()
                    }

                    is HoverInteraction.Exit -> {
                        hoverCount--
                        if (hoverCount < 0) hoverCount = 0
                        animateIfNotPressing()
                    }

                    is FocusInteraction.Focus -> {
                        focusCount++
                        animateIfNotPressing()
                    }

                    is FocusInteraction.Unfocus -> {
                        focusCount--
                        if (focusCount < 0) focusCount = 0
                        animateIfNotPressing()
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()

        val alpha = alphaAnimation.value
        if (alpha > 0f) {
            drawRect(
                color,
                alpha = alpha
            )
        }
    }
}

private val AlphaAnimationSpec = spring(1f, 500f, 0.01f)

private const val PressedAlpha = 0.2f
private const val HoveredAlpha = 0.15f
private const val FocusedAlpha = 0.2f
private const val MinimumPressedMillis = 80L
