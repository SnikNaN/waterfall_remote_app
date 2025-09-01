package com.example.ledctrl.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/** Шина для UI: последний запрос и его HTTP-код ответа */
object RequestBus {
    /** Pair(url, code). code == null — сеть/исключение; 200/400/… — реальный код ответа */
    val last = MutableStateFlow<Pair<String, Int?>?>(null)
}

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

/* ---------------- low-level GET helpers (логируем URL и код ответа) ---------------- */

    private suspend fun get(path: String): Boolean = withContext(Dispatchers.IO) {
        val full = url(path)
        RequestBus.last.value = full to null
        Log.d("LedApi", "GET → $full")
        try {
            client.newCall(Request.Builder().url(full).build()).execute().use { resp ->
                RequestBus.last.value = full to resp.code
                resp.isSuccessful
            }
        } catch (_: Exception) {
            RequestBus.last.value = full to null
            false
        }
    }

    private suspend fun getStatus(path: String): Pair<Int, String?>? = withContext(Dispatchers.IO) {
        val full = url(path)
        RequestBus.last.value = full to null
        Log.d("LedApi", "GET → $full")
        try {
            client.newCall(Request.Builder().url(full).build()).execute().use { resp ->
                RequestBus.last.value = full to resp.code
                resp.code to (resp.body?.string())
            }
        } catch (_: Exception) {
            RequestBus.last.value = full to null
            null
        }
    }

/* ----------------------------------- API ----------------------------------- */

    suspend fun stateRaw(): String? = withContext(Dispatchers.IO) {
        val full = url("state")
        RequestBus.last.value = full to null
        Log.d("LedApi", "GET → $full")
        try {
            client.newCall(Request.Builder().url(full).build()).execute().use { resp ->
                RequestBus.last.value = full to resp.code
                resp.body?.string()
            }
        } catch (_: Exception) {
            RequestBus.last.value = full to null
            null
        }
    }

    suspend fun ping(): Boolean = stateRaw() != null

    suspend fun select(start: Int, end: Int, blink: Boolean = false): Boolean =
        get("select?start=$start&end=$end${if (blink) "&blink=1" else ""}")

    /** hex может быть '#RRGGBB' или 'RRGGBB' */
    suspend fun setPreviewHexStatus(hex: String, lbright: Int? = null): Pair<Int, String?>? {
        val clean = hex.trim().removePrefix("#")
        val lb = lbright?.let { "&lbright=${it.coerceIn(0,255)}" } ?: ""
        return getStatus("set?hex=$clean$lb")
    }

    suspend fun setPreviewHex(hex: String, lbright: Int? = null): Boolean =
        setPreviewHexStatus(hex, lbright)?.first == 200

    suspend fun setLocalBrightness(value: Int): Boolean =
        get("lbright?value=${value.coerceIn(0,255)}")

    suspend fun setGlobalBrightness(value: Int): Boolean =
        get("brightness?value=${value.coerceIn(0,255)}")

    suspend fun save(): Boolean = get("save")
    suspend fun cancel(): Boolean = get("cancel")

    suspend fun blink(start: Int, end: Int, times: Int = 2, ms: Int = 180): Boolean =
        get("blink?start=$start&end=$end&times=${times.coerceAtLeast(1)}&ms=${ms.coerceAtLeast(20)}")
}
