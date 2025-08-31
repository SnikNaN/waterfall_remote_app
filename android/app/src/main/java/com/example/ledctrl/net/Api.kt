package com.example.ledctrl.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class LedApi(baseUrl: String) {
    private val normalizedBase = baseUrl.trim().removeSuffix("/")
    private val client = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = "http://$normalizedBase$path"

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url("/ping")).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    suspend fun on(): Boolean = call("/on")
    suspend fun off(): Boolean = call("/off")
    suspend fun setBrightness(v: Int): Boolean = call("/brightness?value=$v")
    suspend fun setColor(r: Int, g: Int, b: Int): Boolean = call("/color?r=$r&g=$g&b=$b")
    suspend fun rainbow(): Boolean = call("/rainbow")

    private suspend fun call(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url(path)).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }
}
