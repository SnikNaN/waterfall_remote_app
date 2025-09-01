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
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface

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

        var numLeds by remember { mutableStateOf(99) }
        var startIdx by remember { mutableStateOf(0f) }
        var endIdx by remember { mutableStateOf(98f) }
        var localBright by remember { mutableStateOf(255f) }
        var hasSelection by remember { mutableStateOf(false) }

        // Scan dialog state
        var scanning by remember { mutableStateOf(false) }
        var foundHosts by remember { mutableStateOf(listOf<String>()) }
        var showPicker by remember { mutableStateOf(false) }

        // Load saved IP
        LaunchedEffect(Unit) {
            dataStore.data.collect { prefs ->
                val saved = prefs[keyIp] ?: ""
                if (saved.isNotEmpty() && ipText.text.isEmpty())
                    ipText = TextFieldValue(saved)
            }
        }

        fun api(): LedApi? = ipText.text.trim().takeIf { it.isNotEmpty() }?.let { LedApi(it) }

        fun clampRange() {
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
                // Connection row
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("ESP IP/host (e.g. 192.168.1.50)") },
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

                                // Выбираем всю ленту без мигания (чтобы колесо сразу работало)
                                val okSel = api()?.select(startIdx.toInt(), endIdx.toInt(), blink = false) ?: false
                                hasSelection = okSel
                                status = if (okSel) "Online · selection 0–${endIdx.toInt()}" else "Online · selection failed"
                            } else {
                                status = "No response"
                            }
                        }
                    }) { Text("Test /state") }
                    Button(onClick = {
                        val myIp = getLocalIpv4()
                        val prefix = myIp?.let { subnetPrefix(it) }
                        if (prefix == null) {
                            status = "Can't detect subnet"
                            return@Button
                        }
                        scanning = true
                        status = "Scanning $prefix.*"

                        scope.launch(Dispatchers.IO) {
                            val candidates = (1..254).map { "$prefix.$it" }
                            val okList = mutableListOf<String>()
                            val chunkSize = 32

                            for (part in candidates.chunked(chunkSize)) {
                                val found = part
                                    .map { host ->
                                        async {
                                            val client = LedApi(host)
                                            if (client.ping()) host else null
                                        }
                                    }
                                    .awaitAll()
                                    .filterNotNull()
                                okList.addAll(found)
                            }

                            withContext(Dispatchers.Main) {
                                foundHosts = okList.sorted()
                                scanning = false
                                showPicker = true
                                status = if (okList.isEmpty()) "No devices" else "Found: ${okList.size}"
                            }
                        }
                    }) { Text(if (scanning) "Scanning..." else "Scan") }
                }

                Spacer(Modifier.height(8.dp))
                Text("Status: $status")

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
                        confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Close") } }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Color wheel preview on selection
                ColorWheel(onColorChanged = { c: Color ->
                    scope.launch {
                        clampRange()
                        if (!hasSelection) {
                            val okSel = api()?.select(startIdx.toInt(), endIdx.toInt(), blink = false) ?: false
                            hasSelection = okSel
                            if (!okSel) { status = "Select failed"; return@launch }
                        }
                        // ШЛЁМ RRGGBB (без #) — иначе сервер видит пустой hex
                        val hex = listOf(c.red, c.green, c.blue).joinToString("") {
                            "%02X".format((it * 255f).toInt().coerceIn(0, 255))
                        }
                        val resp = api()?.setPreviewHexStatus(hex /*, lbright = localBright.toInt() */)
                        status = when (resp?.first) {
                            200 -> "Preview $hex on ${startIdx.toInt()}–${endIdx.toInt()}"
                            400 -> "Set failed (400 bad hex?)"
                            409 -> "Set failed (409 no selection)"
                            null -> "Network error"
                            else -> "Set failed (HTTP ${resp.first})"
                        }
                    }
                })

                Spacer(Modifier.height(16.dp))

                // Range sliders
                Text("Диапазон: ${startIdx.toInt()} – ${endIdx.toInt()} (0..${numLeds-1})")
                Slider(
                    value = startIdx,
                    onValueChange = { startIdx = it; if (startIdx > endIdx) endIdx = startIdx },
                    onValueChangeFinished = {
                        scope.launch {
                            clampRange()
                            val ok = api()?.select(startIdx.toInt(), endIdx.toInt(), blink = true) ?: false
                            hasSelection = ok
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
                            hasSelection = ok
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
                            hasSelection = false
                            status = if (ok) "Saved" else "Save failed"
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Save") }

                    Button(onClick = {
                        scope.launch {
                            val ok = api()?.cancel() ?: false
                            hasSelection = false
                            status = if (ok) "Canceled" else "Cancel failed"
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }

                Spacer(Modifier.height(12.dp))

                Text("Local brightness: ${localBright.toInt()} (range)")
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

                Text("Global brightness: ${globalBright.toInt()} (whole strip)")
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

                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(40.dp).background(Color(1f,1f,1f,0.05f)))
            }
        }
    }
}

// ===== Network helpers for scanning =====
private fun getLocalIpv4(): String? {
    val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
    for (ni in ifaces) {
        if (!ni.isUp || ni.isLoopback) continue
        val addrs = ni.inetAddresses ?: continue
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
