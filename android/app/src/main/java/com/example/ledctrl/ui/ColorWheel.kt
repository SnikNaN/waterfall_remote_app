package com.example.ledctrl.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

@Composable
fun ColorWheel(
    modifier: Modifier = Modifier.size(240.dp),
    wheelSize: Dp = 240.dp,
    onColorChanged: (Color) -> Unit = {}
) {
    Canvas(
        modifier = modifier
            .size(wheelSize)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> onColorChanged(pickColor(o, size.width, size.height)) },
                    onDrag = { change, _ -> onColorChanged(pickColor(change.position, size.width, size.height)) }
                )
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f

        drawCircle(
            brush = Brush.sweepGradient(
                listOf(
                    Color.Red, Color.Magenta, Color.Blue,
                    Color.Cyan, Color.Green, Color.Yellow, Color.Red
                )
            ),
            center = center,
            radius = radius
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.Transparent),
                center = center,
                radius = radius
            ),
            center = center,
            radius = radius
        )
    }
}

private fun pickColor(pos: Offset, w: Float, h: Float): Color {
    val cx = w / 2f
    val cy = h / 2f
    val dx = pos.x - cx
    val dy = pos.y - cy
    val radius = min(w, h) / 2f

    val distance = hypot(dx, dy)
    val sat = (distance / radius).coerceIn(0f, 1f)

    val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    val hue = ((angle + 360f) % 360f)

    val value = 1f
    return Color.hsv(hue, sat, value)
}
