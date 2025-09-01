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
import kotlinx.coroutines.Dispatchers
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

        // Пишем сюда только строки запросов
        var lastUrl by remember { mutableStateOf<String?>(null) }

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

        fun baseUrl(): String {
            val raw = ipText.text.trim().removeSuffix("/")
            val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
            return withScheme
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
            // ВЕРХНИЙ БЛОК: последняя строка запроса (бросается в глаза)
            if (lastUrl != null) {
                Text(
                    text = "Last request:\n$lastUrl",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Red,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x10FF0000))
                        .padding(8.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

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
                        // /state
                        val url = "${baseUrl()}/state"
                        lastUrl = url
                        val raw = api()?.stateRaw()
                        if (raw != null) {
                            // простая вытяжка num_leds и bright
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

                            // /select без blink
                            val s = startIdx.toInt(); val e = endIdx.toInt()
                            lastUrl = "${baseUrl()}/select?start=$s&end=$e"
                            api()?.select(s, e, blink = false)
                            hasSelection = true
                        }
                    }
                }) { Text("Test /state + select") }

                Button(onClick = {
                    scope.launch {
                        // сканирование подсети
                        val me = withContext(Dispatchers.IO) { getLocalIpv4() }
                        val prefix = me?.let { subnetPrefix(it) } ?: return@launch
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
                        val s = startIdx.toInt(); val e = endIdx.toInt()
                        lastUrl = "${baseUrl()}/select?start=$s&end=$e"
                        val okSel = api()?.select(s, e, blink = false) ?: false
                        hasSelection = okSel
                        if (!okSel) return@launch
                    }
                    // Формируем RRGGBB (без '#')
                    val hex = listOf(c.red, c.green, c.blue).joinToString("") {
                        "%02X".format((it * 255f).toInt().coerceIn(0, 255))
                    }
                    // /set?hex=...
                    lastUrl = "${baseUrl()}/set?hex=$hex"
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
                        if (!hasSelection) return@launch
                        val v = localBright.toInt().coerceIn(0,255)
                        lastUrl = "${baseUrl()}/lbright?value=$v"
                        api()?.setLocalBrightness(v)
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
                        val v = globalBright.toInt().coerceIn(0,255)
                        lastUrl = "${baseUrl()}/brightness?value=$v"
                        api()?.setGlobalBrightness(v)
                    }
                },
                valueRange = 0f..255f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        clampRange()
                        val s = startIdx.toInt(); val e = endIdx.toInt()
                        lastUrl = "${baseUrl()}/select?start=$s&end=$e&blink=1"
                        api()?.select(s, e, blink = true)
                        hasSelection = true
                    }
                }) { Text("Select + Blink") }

                Button(onClick = {
                    scope.launch {
                        lastUrl = "${baseUrl()}/cancel"
                        api()?.cancel()
                        hasSelection = false
                    }
                }) { Text("Cancel") }

                Button(onClick = {
                    scope.launch {
                        lastUrl = "${baseUrl()}/save"
                        api()?.save()
                    }
                }) { Text("Save") }
            }

            Spacer(Modifier.height(12.dp))

            // Небольшая “подложка”, чтобы верхний блок с URL не сливался
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .background(Color(0x06000000))
            )
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
