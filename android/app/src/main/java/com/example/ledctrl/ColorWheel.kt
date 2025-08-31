
package com.example.ledctrl

import android.graphics.Color.HSVToColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlin.math.*

@Composable
fun ColorWheel(
    modifier: Modifier,
    hue: Float,
    sat: Float,
    onChange: (h: Float, s: Float) -> Unit
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val radius get() = min(size.width, size.height) / 2f
    val center get() = Offset(size.width / 2f, size.height / 2f)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { p -> onPointer(p, center, radius, onChange) },
                    onDrag = { change, _ -> onPointer(change.position, center, radius, onChange) }
                )
            }
    ) {
        size = IntSize(size.width, size.height)
        drawIntoCanvas { canvas ->
            val steps = 360
            val sweep = 360f / steps
            for (i in 0 until steps) {
                val h = i.toFloat()
                val path = Path().apply {
                    arcTo(Rect(center = center, radius = radius), h, sweep, false)
                    lineTo(center); close()
                }
                val cEdge = hsvToComposeColor(h, 1f)
                val brush = Brush.radialGradient(
                    colors = listOf(Color.White, cEdge),
                    center = center,
                    radius = radius
                )
                canvas.drawPath(path, Paint().apply { this.asFrameworkPaint().isAntiAlias = true; this.shader = brush })
            }
        }

        val ang = Math.toRadians(hue.toDouble())
        val r = sat * radius
        val px = center.x + (cos(ang) * r).toFloat()
        val py = center.y + (sin(ang) * r).toFloat()
        drawCircle(color = Color.Black, radius = 8f, center = Offset(px, py))
        drawCircle(color = Color.White, radius = 6f, center = Offset(px, py))
    }
}

private fun onPointer(
    pos: Offset,
    center: Offset,
    radius: Float,
    onChange: (Float, Float) -> Unit
) {
    val dx = pos.x - center.x
    val dy = pos.y - center.y
    val ang = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0
    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
    val s = (dist / radius).coerceIn(0f, 1f)
    onChange(ang.toFloat(), s)
}

private fun hsvToComposeColor(h: Float, s: Float): Color {
    val arr = floatArrayOf(h, s, 1f)
    val c = HSVToColor(arr)
    return Color(c)
}
