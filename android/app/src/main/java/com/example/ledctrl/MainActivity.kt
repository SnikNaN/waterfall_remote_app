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
import com.example.ledctrl.net.RequestBus
import com.example.ledctrl.ui.ColorWheel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

private val ComponentActivity.dataStore by preferencesDataStore(name = "settings")
private val keyIp = stringPreferencesKey("ip")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    @Composable
    fun App() {
        val scope = rememberCoroutineScope()

        // показываем только запросы
        var lastUrl by remember { mutableStateOf<String?>(null) }
        var lastCode by remember { mutableStateOf<Int?>(null) }

        var ipText by remember { mutableStateOf(TextFieldValue("")) }
        var startIdx by remember { mutableStateOf(0f) }
        var endIdx by remember { mutableStateOf(0f) }
        var numLeds by remember { mutableStateOf(99) }
        var hasSelection by remember { mutableStateOf(false) }
        var localBright by remember { mutableStateOf(255f) }
        var globalBright by remember { mutableStateOf(128f) }

        // сканирование
        var foundHosts by remember { mutableStateOf(listOf<String>()) }
        var showPicker by remember { mutableStateOf(false) }

        // загрузка IP
        LaunchedEffect(Unit) {
            val saved = dataStore.data.first()[keyIp]
            if (!saved.isNullOrBlank()) ipText = TextFieldValue(saved)
        }

        // подписка на RequestBus: отражаем URL и код
        LaunchedEffect(Unit) {
            RequestBus.last.collectLatest { pair ->
                lastUrl = pair?.first
                lastCode = pair?.second
            }
        }

        fun api(): LedApi? = ipText.text.trim().takeIf { it.isNotEmpty() }?.let { LedApi(it) }

        fun clampRange() {
            val max = (numLeds - 1).coerceAtLeast(0)
            if (startIdx < 0f) startIdx = 0f
            if (endIdx < 0f) endIdx = 0f
            if (startIdx > max) startIdx = max.toFloat()
            if (endIdx > max) endIdx = max.toFloat()
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // IP
            OutlinedTextField(
                value = ipText,
                onValueChange = { ipText = it },
                label = { Text("Controller IP") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch { dataStore.edit { it[keyIp] = ipText.text.trim() } }
                }) { Text("Save IP") }

                Button(onClick = {
                    scope.launch {
                        val raw = api()?.stateRaw()
                        if (raw != null) {
                            val n = "\"num_leds\":".let { k ->
                                val i = raw.indexOf(k)
                                if (i >= 0) raw.substring(i + k.length).takeWhile { it.isDigit() }.toIntOrNull()
                                else null
                            }
                            val gb = "\"bright\":".let { k ->
                                val i = raw.indexOf(k)
                                if (i >= 0) raw.substring(i + k.length).takeWhile { it.isDigit() }.toIntOrNull()
                                else null
                            }
                            if (n != null && n > 0) { numLeds = n; startIdx = 0f; endIdx = (n - 1).toFloat() }
                            if (gb != null) globalBright = gb.toFloat()
                            api()?.select(startIdx.toInt(), endIdx.toInt(), blink = false)
                            hasSelection = true
                        }
                    }
                }) { Text("Test /state") }

                Button(onClick = {
                    scope.launch {
                        val me = withContext(Dispatchers.IO) { getLocalIpv4() }
                        val prefix = me?.let { subnetPrefix(it) }
                        if (prefix == null) return@launch
                        val part = (1..254).chunked(32)
                        val found = mutableListOf<String>()
                        for (chunk in part) {
                            val jobs = chunk.map { host ->
                                scope.launch(Dispatchers.IO) {
                                    val ip = "$prefix.$host"
                                    val ok = try { LedApi(ip).ping() } catch (_: Exception) { false }
                                    if (ok) synchronized(found) { found.add(ip) }
                                }
                            }
                            jobs.forEach { it.join() }
                        }
                        foundHosts = found.sorted()
                        showPicker = true
                    }
                }) { Text("Scan") }
            }

            if (showPicker) {
                AlertDialog(
                    onDismissRequest = { showPicker = false },
                    title = { Text("Выбери устройство") },
                    text = {
                        Column {
                            if (foundHosts.isEmpty()) Text("Ничего не найдено")
                            foundHosts.forEach { host ->
                                Button(onClick = {
                                    ipText = TextFieldValue(host)
                                    showPicker = false
                                }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(host)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showPicker = false }) { Text("Close") }
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Диапазон
            Text("Диапазон: ${startIdx.toInt()} – ${endIdx.toInt()} (из $numLeds)")
            Slider(
                value = startIdx,
                onValueChange = { startIdx = it; clampRange() },
                valueRange = 0f..(numLeds - 1).coerceAtLeast(0).toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
            Slider(
                value = endIdx,
                onValueChange = { endIdx = it; clampRange() },
                valueRange = 0f..(numLeds - 1).coerceAtLeast(0).toFloat(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            // Колесо цвета
            ColorWheel(onColorChanged = { c: Color ->
                scope.launch {
                    clampRange()
                    if (!hasSelection) {
                        val okSel = api()?.select(startIdx.toInt(), endIdx.toInt(), blink = false) ?: false
                        hasSelection = okSel
                        if (!okSel) return@launch
                    }
                    val hex = listOf(c.red, c.green, c.blue).joinToString("") {
                        "%02X".format((it * 255f).toInt().coerceIn(0, 255))
                    }
                    api()?.setPreviewHexStatus(hex)
                }
            })

            Spacer(Modifier.height(12.dp))

            // Локальная яркость
            Text("Local brightness: ${localBright.toInt()}")
            Slider(
                value = localBright,
                onValueChange = { localBright = it },
                onValueChangeFinished = {
                    scope.launch {
                        if (hasSelection) api()?.setLocalBrightness(localBright.toInt())
                    }
                },
                valueRange = 0f..255f,
                modifier = Modifier.fillMaxWidth()
            )

            // Глобальная яркость
            Text("Global brightness: ${globalBright.toInt()}")
            Slider(
                value = globalBright,
                onValueChange = { globalBright = it },
                onValueChangeFinished = {
                    scope.launch { api()?.setGlobalBrightness(globalBright.toInt()) }
                },
                valueRange = 0f..255f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        clampRange()
                        api()?.select(startIdx.toInt(), endIdx.toInt(), blink = true)
                        hasSelection = true
                    }
                }) { Text("Select + Blink") }

                Button(onClick = { scope.launch { api()?.cancel(); hasSelection = false } }) { Text("Cancel") }
                Button(onClick = { scope.launch { api()?.save() } }) { Text("Save") }
            }

            Spacer(Modifier.height(12.dp))

            // Видим только запросы: URL + код
            if (lastUrl != null) {
                val codeTxt = lastCode?.toString() ?: "—"
                val color = when (lastCode) {
                    in 200..299 -> Color(0xFF2E7D32) // зелёный для 2xx
                    in 400..499 -> Color(0xFFC62828) // красный для 4xx
                    in 500..599 -> Color(0xFFAD1457) // малиновый для 5xx
                    null -> Color(0xFF6D4C41)       // сеть/исключение
                    else -> Color(0xFF1565C0)       // прочее
                }
                Text(
                    text = "Last: [$codeTxt] $lastUrl",
                    style = MaterialTheme.typography.bodyMedium,
                    color = color
                )
            }
        }
    }
}

/* ===== Network helpers for scanning ===== */
private fun getLocalIpv4(): String? {
    val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
    for (iface in ifaces) {
        if (!iface.isUp || iface.isLoopback) continue
        val addrs = iface.inetAddresses ?: continue
        for (addr in addrs) {
            if (addr is Inet4Address) {
                val ip = addr.hostAddress ?: continue
                if (ip.startsWith("10.") || ip.startsWith("172.") || ip.startsWith("192.168.")) {
                    return ip
                }
            }
        }
    }
    return null
}

private fun subnetPrefix(ip: String): String? {
    val p = ip.split('.')
    return if (p.size == 4) "${p[0]}.${p[1]}.${p[2]}" else null
}
