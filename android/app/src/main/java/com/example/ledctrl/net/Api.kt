package com.example.ledctrl.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

class LedApi(baseInput: String) {
    private val base: String

    init {
        val trimmed = baseInput.trim()
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://"))
            trimmed else "http://$trimmed"
        val uri = try { URI(withScheme) } catch (_: Exception) {
            throw IllegalArgumentException("Bad address: $baseInput")
        }
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: uri.authority ?: throw IllegalArgumentException("No host in address")
        val portPart = if (uri.port != -1) ":${uri.port}" else ""
        base = "$scheme://$host$portPart"
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private fun url(path: String): String = "$base/${path.removePrefix("/")}"

    private suspend fun get(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url(path)).build())
                .execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    suspend fun stateRaw(): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url("state")).build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (_: Exception) { null }
    }

    suspend fun ping(): Boolean = stateRaw() != null

    suspend fun select(start: Int, end: Int, blink: Boolean = false): Boolean =
        get("select?start=$start&end=$end${if (blink) "&blink=1" else ""}")

    // ВАЖНО: убираем '#' (иначе браузерный якорь ломает запрос)
    suspend fun setPreviewHex(hex: String, lbright: Int? = null): Boolean {
        val clean = hex.trim().removePrefix("#")
        val lb = lbright?.let { "&lbright=${it.coerceIn(0,255)}" } ?: ""
        return get("set?hex=$clean$lb")
    }

    suspend fun setLocalBrightness(value: Int): Boolean =
        get("lbright?value=${value.coerceIn(0,255)}")

    suspend fun setGlobalBrightness(value: Int): Boolean =
        get("brightness?value=${value.coerceIn(0,255)}")

    suspend fun save(): Boolean = get("save")
    suspend fun cancel(): Boolean = get("cancel")

    suspend fun blink(start: Int, end: Int, times: Int = 2, ms: Int = 180): Boolean =
        get("blink?start=$start&end=$end&times=${times.coerceAtLeast(1)}&ms=${ms.coerceAtLeast(20)}")
}
