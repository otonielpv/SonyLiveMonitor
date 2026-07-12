package com.otoniel.sonylivemonitor

import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

class CameraException(message: String) : Exception(message)

/** Cliente minimo de la Sony Camera Remote API (JSON-RPC sobre HTTP). */
object SonyCamera {

    // Fallback: la IP tipica cuando la camara actua como punto de acceso
    // (a6000 y la mayoria de modelos Sony). El descubrimiento SSDP la
    // sustituye si la camara anuncia otra direccion.
    const val DEFAULT_ENDPOINT = "http://192.168.122.1:8080/sony/camera"
    const val CONTENT_ENDPOINT = "http://192.168.122.1:8080/sony/avContent"

    @Volatile var cameraEndpoint: String = DEFAULT_ENDPOINT
        private set
    @Volatile var avContentEndpoint: String = CONTENT_ENDPOINT
        private set

    /** Nombre del modelo segun el XML de descripcion SSDP (null sin SSDP). */
    @Volatile var modelName: String? = null
        private set

    /** Metodos que la camara declara en getAvailableApiList. Ojo: la lista
     *  crece tras startRecMode, por eso se refresca tambien en startLiveview. */
    @Volatile private var apiList: Set<String> = emptySet()

    /** Metodos que la a6000 (y modelos similares) NO listan de forma fiable en
     *  getAvailableApiList aunque SI los soportan: la lista depende del estado
     *  de la camara y llega incompleta. Nunca los ocultamos por gating; cada
     *  uno ya falla de forma segura por su cuenta (toast/manejo de error). */
    private val alwaysAssumed = setOf(
        "actZoom", "setTouchAFPosition", "setCameraFunction", "getContentList",
    )

    /** Fail-open: si aun no hay lista (o la llamada fallo), se asume soportado
     *  para no esconder funciones por un fallo transitorio. Ademas, los metodos
     *  de [alwaysAssumed] se asumen siempre soportados. */
    fun supports(method: String): Boolean =
        apiList.isEmpty() || method in alwaysAssumed || method in apiList

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SSDP_ST = "urn:schemas-sony-com:service:ScalarWebAPI:1"

    private var nextId = 0

    fun call(
        endpoint: String, method: String, params: JSONArray = JSONArray(), version: String = "1.0",
    ): JSONArray {
        val payload = JSONObject()
            .put("method", method)
            .put("params", params)
            .put("id", ++nextId)
            .put("version", version)

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

    /**
     * Confirma que hay una camara accesible. Si el endpoint actual no
     * responde, la busca por SSDP (asi funcionan modelos que no usan la
     * IP tipica de la a6000). Bloqueante: llamar fuera del hilo de UI.
     */
    fun locate(): Boolean {
        val found = ping() || (discover() && ping())
        if (found) refreshCapabilities()
        return found
    }

    private fun ping(): Boolean =
        runCatching { call(cameraEndpoint, "getVersions") }.isSuccess

    private fun refreshCapabilities() {
        apiList = runCatching {
            val arr = call(cameraEndpoint, "getAvailableApiList").getJSONArray(0)
            buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
        }.getOrDefault(apiList)
    }

    /** M-SEARCH SSDP; si la camara responde, actualiza los endpoints. */
    private fun discover(timeoutMs: Int = 2500): Boolean {
        val msearch = ("M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: $SSDP_ST\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.send(
                    DatagramPacket(msearch, msearch.size, InetAddress.getByName(SSDP_ADDR), SSDP_PORT)
                )
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (_: SocketTimeoutException) {
                        return false
                    }
                    val resp = String(pkt.data, 0, pkt.length, Charsets.US_ASCII)
                    val location = Regex("(?i)LOCATION:\\s*(\\S+)")
                        .find(resp)?.groupValues?.get(1) ?: continue
                    if (parseDescription(location)) return true
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    /** Descarga el XML de descripcion UPnP; extrae endpoints y modelo. */
    private fun parseDescription(locationUrl: String): Boolean {
        val xml = try {
            val conn = URL(locationUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            return false
        }
        val base = Regex("<av:X_ScalarWebAPI_ActionList_URL>(.*?)</av:X_ScalarWebAPI_ActionList_URL>")
            .find(xml)?.groupValues?.get(1)?.trimEnd('/') ?: return false
        modelName = Regex("<friendlyName>(.*?)</friendlyName>").find(xml)?.groupValues?.get(1)
        cameraEndpoint = "$base/camera"
        avContentEndpoint = "$base/avContent"
        return true
    }

    /**
     * Informe legible para compartir desde la app: con esto un tester con un
     * modelo no probado nos dice exactamente que soporta su camara.
     */
    fun diagnostics(): String {
        fun probe(endpoint: String, method: String): String =
            runCatching { call(endpoint, method).toString() }
                .getOrElse { "ERROR: ${it.message}" }

        refreshCapabilities()
        val api = apiList.sorted()
        return buildString {
            appendLine("SonyLiveMonitor diagnostics")
            appendLine("Model: ${modelName ?: "unknown (SSDP not used)"}")
            appendLine("Camera endpoint: $cameraEndpoint")
            appendLine("getVersions: ${probe(cameraEndpoint, "getVersions")}")
            appendLine("getApplicationInfo: ${probe(cameraEndpoint, "getApplicationInfo")}")
            appendLine("avContent getSchemeList: ${probe(avContentEndpoint, "getSchemeList")}")
            appendLine("getAvailableApiList (${api.size}):")
            api.forEach { appendLine("- $it") }
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
        // La lista de metodos disponibles crece tras startRecMode: refrescar
        // aqui para que el gating de la UI vea la lista completa.
        refreshCapabilities()
        return call(endpoint, "startLiveview").getString(0)
    }
}
