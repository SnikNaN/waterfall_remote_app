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

        var status by remember { mutableStateOf("Idle") }
        var lastUrl by remember { mutableStateOf<String?>(null) }   // ← отдельная строка для URL
        var ipText by remember { mutableStateOf(TextFieldValue("")) }
        var startIdx by remember { mutableStateOf(0f) }
        var endIdx by remember { mutableStateOf(0f) }
        var numLeds by remember { mutableStateOf(99) }
        var hasSelection by remember { mutableStateOf(false) }
        var localBright by remember { mutableStateOf(255f) }
        var globalBright by remember { mutableStateOf(128f) }

        // сетевое сканирование
        var foundHosts by remember { mutableStateOf(listOf<String>()) }
        var showPicker by remember { mutableStateOf(false) }

        // загрузка сохранённого IP
        LaunchedEffect(Unit) {
            val saved = dataStore.data.first()[keyIp]
            if (!saved.isNullOrBlank()) ipText = TextFieldValue(saved)
        }

        // подписка на шину запросов: не затираем status, держим отдельный lastUrl
        LaunchedEffect(Unit) {
            RequestBus.last.collectLatest { url ->
                lastUrl = url
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
                label = { Text("Controller IP (например 192.168.1.42)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        dataStore.edit { it[keyIp] = ipText.text.trim() }
                        status = "Saved IP"
                    }
                }) { Text("Save IP") }

                Button(onClick = {
                    scope.launch {
                        val raw = api()?.stateRaw()
                        if (raw != null) {
                            status = "Online"
                            // простая вытяжка num_leds и bright
                            val n = run {
                                val k = "\"num_leds\":"
                                val i = raw.indexOf(k)
                                if (i >= 0) raw.substring(i + k.length).takeWhile { it.isDigit() }.toIntOrNull() else null
                            }
                            val gb = run {
                                val k = "\"bright\":"
                                val i = raw.indexOf(k)
                                if (i >= 0) raw.substring(i + k.length).takeWhile { it.isDigit() }.toIntOrNull() else null
                            }
                            if (n != null && n > 0) { numLeds = n; startIdx = 0f; endIdx = (n - 1).toFloat() }
                            if (gb != null) globalBright = gb.toFloat()

                            // выбираем всю ленту без мигания
                            val okSel = api()?.select(startIdx.toInt(), endIdx.toInt(), blink = false) ?: false
                            hasSelection = okSel
                            status = if (okSel) "Online · selection 0–${endIdx.toInt()}" else "Online · selection failed"
                        } else {
                            status = "No response"
                        }
                    }
                }) { Text("Test /state") }

                Button(onClick = {
                    scope.launch {
                        // сканирование подсети
                        val me = withContext(Dispatchers.IO) { getLocalIpv4() }
                        val prefix = me?.let { subnetPrefix(it) }
                        if (prefix == null) {
                            status = "No local IPv4"
                            return@launch
                        }
                        status = "Scanning $prefix.x ..."
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
                            jobs.forEach { it.join() } // дождаться пачки
                        }
                        foundHosts = found.sorted()
                        showPicker = true
                        status = if (foundHosts.isEmpty()) "Scan done: none" else "Scan done: ${foundHosts.size}"
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
                                    status = "Selected $host"
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
                        if (!okSel) { status = "Select failed"; return@launch }
                    }
                    // Формируем RRGGBB (без '#')
                    val hex = listOf(c.red, c.green, c.blue).joinToString("") {
                        "%02X".format((it * 255f).toInt().coerceIn(0, 255))
                    }
                    val resp = api()?.setPreviewHexStatus(hex /*, lbright = localBright.toInt() */)
                    status = when (resp?.first) {
                        200 -> "Preview $hex on ${startIdx.toInt()}–${endIdx.toInt()}"
                        400 -> "Set failed (400 bad hex?)"
                        409 -> "Set failed (409 no selection)"
                        null -> "Network error"
                        else -> "Set failed (${resp.first})"
                    }
                }
            })

            Spacer(Modifier.height(12.dp))

            // Локальная яркость выбранного диапазона
            Text("Local brightness: ${localBright.toInt()}")
            Slider(
                value = localBright,
                onValueChange = { localBright = it },
                onValueChangeFinished = {
                    scope.launch {
                        if (!hasSelection) return@launch
                        val ok = api()?.setLocalBrightness(localBright.toInt()) ?: false
                        status = if (ok) "lbright=${localBright.toInt()}" else "lbright failed"
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
                    scope.launch {
                        val ok = api()?.setGlobalBrightness(globalBright.toInt()) ?: false
                        status = if (ok) "gbright=${globalBright.toInt()}" else "gbright failed"
                    }
                },
                valueRange = 0f..255f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // Управляющие кнопки
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        clampRange()
                        val ok = api()?.select(startIdx.toInt(), endIdx.toInt(), blink = true) ?: false
                        hasSelection = ok
                        status = if (ok) "Selected with blink" else "Select failed"
                    }
                }) { Text("Select + Blink") }

                Button(onClick = {
                    scope.launch {
                        val ok = api()?.cancel() ?: false
                        hasSelection = false
                        status = if (ok) "Cancel OK" else "Cancel failed"
                    }
                }) { Text("Cancel") }

                Button(onClick = {
                    scope.launch {
                        val ok = api()?.save() ?: false
                        status = if (ok) "Saved" else "Save failed"
                    }
                }) { Text("Save") }
            }

            Spacer(Modifier.height(8.dp))

            // Полоса состояния + последний URL
            if (lastUrl != null) {
                Text("Last request: $lastUrl", style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.fillMaxWidth().height(40.dp).background(Color(1f, 1f, 1f, 0.05f)))
            Text("Status: $status")
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
