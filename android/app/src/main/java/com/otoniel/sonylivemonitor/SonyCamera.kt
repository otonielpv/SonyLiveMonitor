package com.otoniel.sonylivemonitor

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CameraException(message: String) : Exception(message)

/** Cliente minimo de la Sony Camera Remote API (JSON-RPC sobre HTTP). */
object SonyCamera {

    const val DEFAULT_ENDPOINT = "http://192.168.122.1:8080/sony/camera"

    private var nextId = 0

    fun call(endpoint: String, method: String, params: JSONArray = JSONArray()): JSONArray {
        val payload = JSONObject()
            .put("method", method)
            .put("params", params)
            .put("id", ++nextId)
            .put("version", "1.0")

        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            if (json.has("error")) {
                val err = json.getJSONArray("error")
                throw CameraException("$method failed: [${err.getInt(0)}] ${err.getString(1)}")
            }
            return json.optJSONArray("result") ?: json.getJSONArray("results")
        } finally {
            conn.disconnect()
        }
    }

    /** Prepara la camara y devuelve la URL del stream de liveview. */
    fun startLiveview(endpoint: String): String {
        // La a6000 exige startRecMode antes del liveview; en otros modelos
        // puede no existir o estar ya activo — ambos casos son ignorables.
        try {
            call(endpoint, "startRecMode")
        } catch (_: CameraException) {
        }
        return call(endpoint, "startLiveview").getString(0)
    }
}
