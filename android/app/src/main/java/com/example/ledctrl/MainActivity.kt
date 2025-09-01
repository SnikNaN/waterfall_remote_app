package com.example.ledctrl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.example.ledctrl.net.LedApi
import com.example.ledctrl.net.RequestBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var ipText by remember { mutableStateOf(TextFieldValue("")) }
            var status by remember { mutableStateOf("Idle") }
            var startIdx by remember { mutableStateOf(0f) }
            var endIdx by remember { mutableStateOf(0f) }
            var numLeds by remember { mutableStateOf(99) }
            var hasSelection by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            // Подписка на RequestBus — показываем последний отправленный URL
            LaunchedEffect(Unit) {
                RequestBus.last.collectLatest { url ->
                    if (url != null) {
                        status = "GET → $url"
                    }
                }
            }

            fun api(): LedApi? =
                ipText.text.trim().takeIf { it.isNotEmpty() }?.let { LedApi(it) }

            fun clampRange() {
                val max = (numLeds - 1).coerceAtLeast(0)
                if (startIdx < 0f) startIdx = 0f
                if (endIdx < 0f) endIdx = 0f
                if (startIdx > max) startIdx = max.toFloat()
                if (endIdx > max) endIdx = max.toFloat()
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("Controller IP (например 192.168.1.42)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Text("Диапазон светодиодов:")
                Row {
                    OutlinedTextField(
                        value = startIdx.toInt().toString(),
                        onValueChange = { startIdx = it.toFloatOrNull() ?: 0f },
                        label = { Text("Start") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = endIdx.toInt().toString(),
                        onValueChange = { endIdx = it.toFloatOrNull() ?: 0f },
                        label = { Text("End") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Цветовое колесо (ColorWheel у тебя уже есть в проекте)
                ColorWheel(onColorChanged = { c: Color ->
                    scope.launch {
                        clampRange()
                        if (!hasSelection) {
                            val okSel = api()?.select(
                                startIdx.toInt(),
                                endIdx.toInt(),
                                blink = false
                            ) ?: false
                            hasSelection = okSel
                            if (!okSel) {
                                status = "Select failed"
                                return@launch
                            }
                        }
                        // собираем hex без "#"
                        val hex = listOf(c.red, c.green, c.blue).joinToString("") {
                            "%02X".format((it * 255f).toInt().coerceIn(0, 255))
                        }
                        val resp = api()?.setPreviewHexStatus(hex)
                        status = when (resp?.first) {
                            200 -> "Preview $hex on ${startIdx.toInt()}–${endIdx.toInt()}"
                            400 -> "Set failed (400 bad hex?)"
                            409 -> "Set failed (409 no selection)"
                            null -> "Network error"
                            else -> "Set failed (${resp.first})"
                        }
                    }
                })

                Spacer(Modifier.height(8.dp))

                Button(onClick = {
                    scope.launch {
                        val ok = api()?.save() ?: false
                        status = if (ok) "Saved" else "Save failed"
                    }
                }) {
                    Text("Save")
                }

                Spacer(Modifier.height(8.dp))
                Text("Status: $status")
            }
        }
    }
}
