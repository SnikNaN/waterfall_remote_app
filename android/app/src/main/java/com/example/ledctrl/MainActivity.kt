package com.example.ledctrl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        val key = stringPreferencesKey("device_ip")
        var ipText by remember { mutableStateOf(TextFieldValue("")) }
        var status by remember { mutableStateOf("Idle") }
        var brightness by remember { mutableStateOf(128f) }

        LaunchedEffect(Unit) {
            dataStore.data.collect { prefs ->
                val saved = prefs[key] ?: ""
                if (saved.isNotEmpty() && ipText.text.isEmpty()) ipText = TextFieldValue(saved)
            }
        }

        fun api(): LedApi? {
            val host = ipText.text.trim()
            return if (host.isNotEmpty()) LedApi(host) else null
        }

        MaterialTheme {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("ESP IP (e.g. 192.168.1.50)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            dataStore.edit { it[key] = ipText.text.trim() }
                            status = "Saved"
                        }
                    }) { Text("Save") }

                    Button(onClick = {
                        scope.launch {
                            val ok = api()?.ping() ?: false
                            status = if (ok) "Online" else "No response"
                        }
                    }) { Text("Test") }
                }

                Spacer(Modifier.height(8.dp))
                Text("Status: $status")

                Spacer(Modifier.height(16.dp))

                ColorWheel(onColorChanged = { c: Color ->
                    scope.launch {
                        val r = (c.red * 255).toInt().coerceIn(0,255)
                        val g = (c.green * 255).toInt().coerceIn(0,255)
                        val b = (c.blue * 255).toInt().coerceIn(0,255)
                        val ok = api()?.setColor(r,g,b) ?: false
                        status = if (ok) "Color: $r,$g,$b" else "Send color failed"
                    }
                })

                Spacer(Modifier.height(16.dp))

                Text("Brightness: ${brightness.toInt()}")
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
                    onValueChangeFinished = {
                        scope.launch {
                            val ok = api()?.setBrightness(brightness.toInt()) ?: false
                            status = if (ok) "Brightness set" else "Send brightness failed"
                        }
                    },
                    valueRange = 1f..255f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch { status = if (api()?.on() == true) "ON" else "ON failed" }
                    }) { Text("ON") }
                    Button(onClick = {
                        scope.launch { status = if (api()?.off() == true) "OFF" else "OFF failed" }
                    }) { Text("OFF") }
                    Button(onClick = {
                        scope.launch { status = if (api()?.rainbow() == true) "Rainbow" else "Rainbow failed" }
                    }) { Text("Rainbow") }
                }
            }
        }
    }
}
