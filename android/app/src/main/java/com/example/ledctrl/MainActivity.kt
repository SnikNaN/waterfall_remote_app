package com.example.ledctrl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ledctrl.net.LedApi
import com.example.ledctrl.ui.ColorWheel
import kotlinx.coroutines.launch

private val ComponentActivity.dataStore by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    @Composable
    fun App() {
        val scope = rememberCoroutineScope()
        val keyIp = stringPreferencesKey("device_ip")

        var ipText by remember { mutableStateOf(TextFieldValue("")) }
        var status by remember { mutableStateOf("Idle") }
        var globalBright by remember { mutableStateOf(128f) }

        // Диапазон (по твоему коду NUM_LEDS=99 => индексы 0..98)
        var numLeds by remember { mutableStateOf(99) }
        var startIdx by remember { mutableStateOf(0f) }
        var endIdx by remember { mutableStateOf(98f) }

        // Локальная яркость выбранного диапазона
        var localBright by remember { mutableStateOf(255f) }

        // Подтягиваем сохранённый IP
        LaunchedEffect(Unit) {
            dataStore.data.collect { prefs ->
                val saved = prefs[keyIp] ?: ""
                if (saved.isNotEmpty() && ipText.text.isEmpty()) ipText = TextFieldValue(saved)
            }
        }

        fun api(): LedApi? = ipText.text.trim().takeIf { it.isNotEmpty() }?.let { LedApi(it) }

        fun clampRange() {
            // в твоём прошивочном коде clampRange() делает 0..NUM_LEDS-1 и перестановку
            val max = (numLeds - 1).coerceAtLeast(0)
            if (startIdx < 0f) startIdx = 0f
            if (endIdx < 0f) endIdx = 0f
            if (startIdx > max) startIdx = max.toFloat()
            if (endIdx > max) endIdx = max.toFloat()
            if (startIdx > endIdx) {
                val t = startIdx; startIdx = endIdx; endIdx = t
            }
        }

        MaterialTheme {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Подключение
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("ESP IP/host (напр. 192.168.1.50)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            dataStore.edit { it[keyIp] = ipText.text.trim() }
                            status = "Saved"
                        }
                    }) { Text("Save") }
                    Button(onClick = {
                        scope.launch {
                            val raw = api()?.stateRaw()
                            status = if (raw != null) "Online" else "No response"
                            // (опционально) простая вытяжка num_leds/bright
                            raw?.let {
                                // грубо вытаскиваем числа без сторонних зависимостей
                                val n = "\"num_leds\":".let { k -> it.indexOf(k).takeIf { i-> i>=0 }?.let { j ->
                                    it.substring(j).substringAfter(':').takeWhile { ch -> ch.isDigit() }.toIntOrNull()
                                } }
                                val gb = "\"bright\":".let { k -> it.indexOf(k).takeIf { i-> i>=0 }?.let { j ->
                                    it.substring(j).substringAfter(':').takeWhile { ch -> ch.isDigit() }.toIntOrNull()
                                } }
                                if (n != null && n > 0) {
                                    numLeds = n
                                    endIdx = (n - 1).toFloat()
                                }
                                if (gb != null) globalBright = gb.toFloat()
                            }
                        }
                    }) { Text("Test /state") }
                }

                Spacer(Modifier.height(8.dp))
                Text("Status: $status")

                Spacer(Modifier.height(12.dp))

                // Колесо цвета — превью на выбранном диапазоне
                ColorWheel(onColorChanged = { c: Color ->
                    scope.launch {
                        clampRange()
                        val hex = "#" + listOf(c.red, c.green, c.blue).joinToString("") {
                            val v = (it * 255f).toInt().coerceIn(0, 255)
                            "%02X".format(v)
                        }
                        val ok = api()?.setPreviewHex(hex) ?: false
                        status = if (ok) "Preview $hex on ${startIdx.toInt()}–${endIdx.toInt()}" else "Set preview failed"
                    }
                })

                Spacer(Modifier.height(16.dp))

                // Диапазон
                Text("Диапазон: ${startIdx.toInt()} – ${endIdx.toInt()} (из 0..${numLeds-1})")
                Slider(
                    value = startIdx,
                    onValueChange = { startIdx = it; if (startIdx > endIdx) endIdx = startIdx },
                    onValueChangeFinished = {
                        scope.launch {
                            clampRange()
                            val ok = api()?.select(startIdx.toInt(), endIdx.toInt(), blink = true) ?: false
                            status = if (ok) "Selected ${startIdx.toInt()}–${endIdx.toInt()}" else "Select failed"
                        }
                    },
                    valueRange = 0f..(numLeds - 1).toFloat(),
                )
                Slider(
                    value = endIdx,
                    onValueChange = { endIdx = it; if (endIdx < startIdx) startIdx = endIdx },
                    onValueChangeFinished = {
                        scope.launch {
                            clampRange()
                            val ok = api()?.select(startIdx.toInt(), endIdx.toInt(), blink = true) ?: false
                            status = if (ok) "Selected ${startIdx.toInt()}–${endIdx.toInt()}" else "Select failed"
                        }
                    },
                    valueRange = 0f..(numLeds - 1).toFloat(),
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val ok = api()?.blink(startIdx.toInt(), endIdx.toInt()) ?: false
                            status = if (ok) "Blinking ${startIdx.toInt()}–${endIdx.toInt()}" else "Blink failed"
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Blink") }

                    Button(onClick = {
                        scope.launch {
                            val ok = api()?.save() ?: false
                            status = if (ok) "Saved" else "Save failed"
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Save") }

                    Button(onClick = {
                        scope.launch {
                            val ok = api()?.cancel() ?: false
                            status = if (ok) "Canceled" else "Cancel failed"
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }

                Spacer(Modifier.height(12.dp))

                // Локальная яркость выбранного диапазона
                Text("Local brightness: ${localBright.toInt()} (диапазон)")
                Slider(
                    value = localBright,
                    onValueChange = { localBright = it },
                    onValueChangeFinished = {
                        scope.launch {
                            val ok = api()?.setLocalBrightness(localBright.toInt()) ?: false
                            status = if (ok) "Local brightness set" else "Local brightness failed"
                        }
                    },
                    valueRange = 0f..255f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                // Глобальная яркость
                Text("Global brightness: ${globalBright.toInt()} (вся лента)")
                Slider(
                    value = globalBright,
                    onValueChange = { globalBright = it },
                    onValueChangeFinished = {
                        scope.launch {
                            val ok = api()?.setGlobalBrightness(globalBright.toInt()) ?: false
                            status = if (ok) "Global brightness set" else "Global brightness failed"
                        }
                    },
                    valueRange = 0f..255f,
                    modifier = Modifier.fillMaxWidth()
                )

                // Превью выбранного цвета (просто фон прямоугольника, не обязательно)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(Color(1f, 1f, 1f, 0.05f))
                )
            }
        }
    }
}
