package com.example.ledctrl.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class LedApi(baseHost: String) {
    private val host = baseHost.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
    private val client = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = "http://$host$path"

    private suspend fun get(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url(path)).build())
                .execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    suspend fun stateRaw(): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url("/state")).build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (_: Exception) { null }
    }

    suspend fun select(start: Int, end: Int, blink: Boolean = false): Boolean =
        get("/select?start=$start&end=$end${if (blink) "&blink=1" else ""}")

    suspend fun setPreviewHex(hex: String, lbright: Int? = null): Boolean {
        val lb = lbright?.let { "&lbright=${it.coerceIn(0,255)}" } ?: ""
        return get("/set?hex=$hex$lb")
    }

    suspend fun setLocalBrightness(value: Int): Boolean =
        get("/lbright?value=${value.coerceIn(0,255)}")

    suspend fun setGlobalBrightness(value: Int): Boolean =
        get("/brightness?value=${value.coerceIn(0,255)}")

    suspend fun save(): Boolean = get("/save")
    suspend fun cancel(): Boolean = get("/cancel")

    suspend fun blink(start: Int, end: Int, times: Int = 2, ms: Int = 180): Boolean =
        get("/blink?start=$start&end=$end&times=${times.coerceAtLeast(1)}&ms=${ms.coerceAtLeast(20)}")
}
