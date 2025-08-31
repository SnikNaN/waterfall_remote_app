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
import kotlin.math.sqrt

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
                // В pointerInput есть собственный Size (Float!), используем его,
                // чтобы не тащить никаких Int и не конвертить
                detectDragGestures(
                    onDragStart = { pos ->
                        onColorChanged(pickColor(pos.x, pos.y, size.width, size.height))
                    },
                    onDrag = { change, _ ->
                        onColorChanged(pickColor(change.position.x, change.position.y, size.width, size.height))
                    }
                )
            }
    ) {
        // Здесь тоже везде только Float
        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)
        val radius = if (size.width < size.height) size.width / 2f else size.height / 2f

        // Кольцо оттенков (Hue)
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(
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

/**
 * Преобразование координат касания -> HSV цвет.
 * Всё на float, без min()/hypot() из Double и без Int.
 */
private fun pickColor(x: Float, y: Float, w: Int, h: Int): Color {
    val cx = w / 2f
    val cy = h / 2f
    val dx = x - cx
    val dy = y - cy
    val radius = if (w < h) w / 2f else h / 2f

    // расстояние до центра (явно на float)
    val dist = sqrt(dx * dx + dy * dy)
    val sat = (dist / radius).coerceIn(0f, 1f)

    // угол -> градусы [0..360)
    val angleDeg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    val hue = ((angleDeg + 360f) % 360f)

    return Color.hsv(hue, sat, 1f)
}
