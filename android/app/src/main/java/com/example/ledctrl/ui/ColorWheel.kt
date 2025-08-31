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
                    onDragStart = { o ->
                        onColorChanged(pickColor(o, this.size.width, this.size.height))
                    },
                    onDrag = { change, _ ->
                        onColorChanged(pickColor(change.position, this.size.width, this.size.height))
                    }
                )
            }
    ) {
        val cx: Float = size.width / 2f
        val cy: Float = size.height / 2f
        val center = Offset(cx, cy)
        val radius: Float = min(size.width, size.height) / 2f

        // Кольцо оттенков (H)
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color.Red,
                    Color.Magenta,
                    Color.Blue,
                    Color.Cyan,
                    Color.Green,
                    Color.Yellow,
                    Color.Red
                )
            ),
            center = center,
            radius = radius
        )

        // К центру — белый (уменьшаем насыщенность)
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

/** Вычисление цвета по позиции касания */
private fun pickColor(pos: Offset, w: Float, h: Float): Color {
    val cx: Float = w / 2f
    val cy: Float = h / 2f
    val dx: Float = pos.x - cx
    val dy: Float = pos.y - cy
    val radius: Float = min(w, h) / 2f

    val distance: Float = hypot(dx, dy)
    val sat: Float = (distance / radius).coerceIn(0f, 1f)

    val angleDeg: Float = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    val hue: Float = ((angleDeg + 360f) % 360f)

    return Color.hsv(hue, sat, 1f)
}

