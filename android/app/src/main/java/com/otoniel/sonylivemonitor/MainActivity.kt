package com.otoniel.sonylivemonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    companion object {
        private const val WIFI_PERMISSION_REQUEST = 1001
        private const val CAMERA_PERMISSION_REQUEST = 1002
    }

    private enum class Grid { OFF, TERCIOS, TERCIOS_DIAG, CRUZ }
    private enum class VideoSource { WIFI, HDMI }

    private enum class PeakingColor(val label: String, val argb: Int) {
        OFF("off", Color.TRANSPARENT),
        RED("red", Color.argb(230, 255, 32, 32)),
        YELLOW("yellow", Color.argb(230, 255, 220, 0)),
        WHITE("white", Color.argb(235, 255, 255, 255)),
    }

    // Umbrales sobre el gradiente Sobel a resolucion completa. Al calcularse por
    // pixel (no sobre pares de 2px como antes) las magnitudes son menores, y con
    // supresion de no-maximos solo sobrevive la cresta del borde, asi que se
    // pueden usar umbrales mas bajos sin ensuciar.
    private enum class PeakingSensitivity(val label: String, val shortLabel: String, val threshold: Int) {
        LOW("low", "L", 190), MEDIUM("medium", "M", 130), HIGH("high", "H", 80),
    }

    /** Ajuste editable en vivo desde la barra inferior. */
    private class Setting(
        val title: String,
        val getMethod: String,
        val setMethod: String,
        val numeric: Boolean = false,  // el setter espera un numero, no un string
        val chipLabel: (String) -> String,
    ) {
        // getAvailableX sirve para construir el selector; getX devuelve el
        // valor actual de forma fiable al iniciar la sesion.
        val currentMethod: String get() = getMethod.replace("getAvailable", "get")
    }

    private val settings = listOf(
        Setting("ISO", "getAvailableIsoSpeedRate", "setIsoSpeedRate") { "ISO $it" },
        Setting("Shutter", "getAvailableShutterSpeed", "setShutterSpeed") { it },
        Setting("Aperture", "getAvailableFNumber", "setFNumber") { "f/$it" },
        Setting("Focus", "getAvailableFocusMode", "setFocusMode") { it },
        Setting("Drive", "getAvailableContShootingMode", "setContShootingMode") { "Drive $it" },
        Setting("Flash", "getAvailableFlashMode", "setFlashMode") { "Flash $it" },
        Setting("Timer", "getAvailableSelfTimer", "setSelfTimer", numeric = true) { "T:${it}s" },
    )

    private lateinit var surface: SurfaceView
    private lateinit var uvcPreviewSink: SurfaceView
    private lateinit var connectivity: ConnectivityManager
    private val apiExecutor = Executors.newSingleThreadExecutor()
    private val eventExecutor = Executors.newSingleThreadExecutor()
    private val eventListenerRunning = AtomicBoolean(false)
    private val captureEventLock = Any()
    private var lastCameraStatus: String? = null
    private var appCapturePendingAt = 0L

    @Volatile private var active = false
    @Volatile private var openingGallery = false
    private val lifecycleLock = Any()
    private val sessionGeneration = AtomicInteger(0)
    private var currentStream: LiveviewStream? = null
    private var currentUvcSource: UvcVideoSource? = null
    private var worker: Thread? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var directNetwork: Network? = null
    private var directCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingWifiCredentials: Pair<String, String>? = null

    @Volatile private var grid = Grid.OFF
    @Volatile private var meterOn = false
    @Volatile private var peakingColor = PeakingColor.OFF
    @Volatile private var peakingSensitivity = PeakingSensitivity.MEDIUM
    @Volatile private var rotation = 0  // 0/90/180/270 grados de rotacion del render
    @Volatile private var mirror = false  // espejo horizontal del render (modo selfie)
    @Volatile private var capturing = false
    @Volatile private var lastFitRect = RectF()
    @Volatile private var focusMarker: Pair<Float, Float>? = null
    @Volatile private var focusMarkerUntil = 0L
    @Volatile private var touchFocusActive = false
    @Volatile private var videoSource = VideoSource.WIFI
    private val frameMatrix = Matrix()
    private val frameSrcCorners = FloatArray(8)
    private val frameDstCorners = FloatArray(8)

    // Margenes seguros frente al agujero de camara (cutout) y barras del sistema
    @Volatile private var safeInsetLeft = 0
    @Volatile private var safeInsetTop = 0
    @Volatile private var safeInsetRight = 0
    @Volatile private var safeInsetBottom = 0

    // Hueco que el panel de control resta al area de video
    @Volatile private var videoBottomInset = 0
    @Volatile private var videoRightInset = 0

    private lateinit var panel: LinearLayout
    private lateinit var panelContainer: ScrollView
    private lateinit var panelFrame: FrameLayout
    private var placeShutterButton: (() -> Unit)? = null
    private lateinit var controlGrid: GridLayout
    // Chips agrupados por pestana del panel: Camera / View / App
    private val cameraChips = mutableListOf<Button>()
    private val viewChips = mutableListOf<Button>()
    private val appChips = mutableListOf<Button>()
    private val tabGroups get() = listOf(cameraChips, viewChips, appChips)
    private val tabButtons = mutableListOf<Button>()
    private var panelTab = 0

    // Feedback de zoom (la API da porcentaje; los mm son estimados 16-50)
    @Volatile private var zoomText: String? = null
    @Volatile private var zoomTextUntil = 0L

    private lateinit var btnConnect: Button
    private lateinit var btnVideoSource: Button
    private lateinit var btnGallery: Button
    private lateinit var btnGeotag: Button
    private val zoomChips = mutableListOf<Button>()
    private lateinit var btnGrid: Button
    private lateinit var btnMeter: Button
    private lateinit var btnPeaking: Button
    private lateinit var btnPeakingSensitivity: Button
    private lateinit var btnHud: Button
    private lateinit var btnShutter: Button
    private val chips = mutableMapOf<Setting, Button>()
    private lateinit var chipEv: Button
    private lateinit var chipWb: Button
    private lateinit var chipShootMode: Button

    @Volatile private var hudOn = true
    @Volatile private var shootMode = "still"
    @Volatile private var movieRecording = false
    @Volatile private var evStepIndex = 1

    // Salud del WiFi de la camara: ayuda a saber si unos fps bajos vienen de la
    // senal (RSSI/velocidad de enlace) o de la propia camara. Se muestrea en el
    // bucle del monitor y se pinta como segunda linea del HUD.
    @Volatile private var wifiRssi = 0          // dBm; 0 = desconocido
    @Volatile private var wifiLinkMbps = 0      // Mbps; 0 = desconocido
    private var lastWifiSampleAt = 0L
    private lateinit var valueStrip: HorizontalScrollView
    private lateinit var valueRow: LinearLayout
    private var openSetting: Any? = null  // Setting o "EV"

    // -- pintura ---------------------------------------------------------------

    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 128); textSize = 34f
    }
    private val hudShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 34f; strokeWidth = 6f; style = Paint.Style.STROKE
    }
    // Segunda linea del HUD (WiFi): mismo tamano, color segun la calidad de senal
    private val wifiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f }
    private val alertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 64, 64); textSize = 44f
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 200, 0); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val peakingPaint = Paint().apply { isFilterBitmap = false }

    /** Sobel a resolucion completa con supresion de no-maximos, de modo que solo
     *  se marca la cresta del borde (linea de ~1px) y no su relleno. Esto imita
     *  el peaking fino de la Sony y evita colorear bordes anchos y desenfocados.
     *  Mantiene sus buffers para no generar basura en cada frame y alterna dos
     *  mascaras para el compositor. */
    private class FocusPeakingProcessor {
        private var sourcePixels = IntArray(0)
        private var luma = ByteArray(0)
        // El maximo |gx|+|gy| de Sobel es 2040: Short evita 2 bytes/pixel.
        private var grad = ShortArray(0)
        private var maskPixels = IntArray(0)
        private var maskWidth = 0
        private var maskHeight = 0
        private var masks = arrayOfNulls<Bitmap>(2)
        private var maskIndex = 0

        fun compute(source: Bitmap, color: PeakingColor, sensitivity: PeakingSensitivity): Bitmap? {
            if (color == PeakingColor.OFF || source.width < 6 || source.height < 6) return null
            val w = source.width
            val h = source.height
            if (sourcePixels.size != w * h) sourcePixels = IntArray(w * h)
            if (w != maskWidth || h != maskHeight) {
                maskWidth = w; maskHeight = h
                luma = ByteArray(w * h)
                grad = ShortArray(w * h)
                maskPixels = IntArray(w * h)
                masks = arrayOfNulls(2)
                maskIndex = 0
            }
            source.getPixels(sourcePixels, 0, w, 0, 0, w, h)
            var p = 0
            val n = w * h
            while (p < n) {
                val c = sourcePixels[p]
                luma[p] = (((c shr 16 and 0xFF) * 54 + (c shr 8 and 0xFF) * 183 +
                    (c and 0xFF) * 19) shr 8).toByte()
                p++
            }

            // Paso 1: magnitud del gradiente Sobel en cada pixel interior.
            val threshold = sensitivity.threshold
            var y = 1
            while (y < h - 1) {
                var x = 1
                while (x < w - 1) {
                    val i = y * w + x
                    val tl = luma[i - w - 1].toInt() and 0xFF
                    val tc = luma[i - w].toInt() and 0xFF
                    val tr = luma[i - w + 1].toInt() and 0xFF
                    val ml = luma[i - 1].toInt() and 0xFF
                    val mr = luma[i + 1].toInt() and 0xFF
                    val bl = luma[i + w - 1].toInt() and 0xFF
                    val bc = luma[i + w].toInt() and 0xFF
                    val br = luma[i + w + 1].toInt() and 0xFF
                    val gx = -tl + tr - 2 * ml + 2 * mr - bl + br
                    val gy = -tl - 2 * tc - tr + bl + 2 * bc + br
                    grad[i] = (kotlin.math.abs(gx) + kotlin.math.abs(gy)).toShort()
                    x++
                }
                y++
            }

            // Paso 2: solo marcamos donde el gradiente supera el umbral y es un
            // maximo local frente a los dos vecinos perpendiculares al borde.
            // Asi la banda ancha de un borde borroso se reduce a su cresta y
            // desaparece cuando la transicion es suave (desenfocada).
            maskPixels.fill(Color.TRANSPARENT)
            val argb = color.argb
            y = 1
            while (y < h - 1) {
                var x = 1
                while (x < w - 1) {
                    val i = y * w + x
                    val g = grad[i].toInt() and 0xFFFF
                    if (g >= threshold &&
                        g >= (grad[i - 1].toInt() and 0xFFFF) &&
                        g >= (grad[i + 1].toInt() and 0xFFFF) &&
                        g >= (grad[i - w].toInt() and 0xFFFF) &&
                        g >= (grad[i + w].toInt() and 0xFFFF)
                    ) {
                        maskPixels[i] = argb
                    }
                    x++
                }
                y++
            }
            val bitmap = masks[maskIndex] ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                .also { masks[maskIndex] = it }
            bitmap.setPixels(maskPixels, 0, w, 0, 0, w, h)
            maskIndex = (maskIndex + 1) % masks.size
            return bitmap
        }
    }

    // -- ciclo de vida -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        connectivity = getSystemService(ConnectivityManager::class.java)

        grid = Grid.entries[getPreferences(MODE_PRIVATE).getInt("grid", 0)]
        meterOn = getPreferences(MODE_PRIVATE).getBoolean("meter", false)
        peakingColor = PeakingColor.entries.getOrElse(
            getPreferences(MODE_PRIVATE).getInt("peaking_color", 0)
        ) { PeakingColor.OFF }
        peakingSensitivity = PeakingSensitivity.entries.getOrElse(
            getPreferences(MODE_PRIVATE).getInt("peaking_sensitivity", 1)
        ) { PeakingSensitivity.MEDIUM }
        rotation = getPreferences(MODE_PRIVATE).getInt("rot", 0)
        mirror = getPreferences(MODE_PRIVATE).getBoolean("mirror", false)
        hudOn = getPreferences(MODE_PRIVATE).getBoolean("hud", true)
        panelTab = getPreferences(MODE_PRIVATE).getInt("panel_tab", 0).coerceIn(0, 2)
        videoSource = VideoSource.entries.getOrElse(
            getPreferences(MODE_PRIVATE).getInt("video_source", VideoSource.WIFI.ordinal)
        ) { VideoSource.WIFI }

        val root = FrameLayout(this)
        // Superficie diminuta y oculta tras el monitor. SurfaceFlinger consume
        // aqui la salida UVC mientras el callback entrega los frames al renderer.
        uvcPreviewSink = SurfaceView(this)
        root.addView(uvcPreviewSink, FrameLayout.LayoutParams(2, 2))
        surface = SurfaceView(this)
        surface.setOnTouchListener { v, ev -> onSurfaceTouch(v, ev) }
        root.addView(surface)
        root.addView(buildBottomBar())
        root.addView(buildShutterButton())
        applyWindowInsets(root)
        setContentView(root)
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        GeotagManager.start(applicationContext)
        updateGeotagChip()
        if (videoSource == VideoSource.HDMI &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
        openingGallery = false
        val thread = synchronized(lifecycleLock) {
            if (active) return
            active = true
            val generation = sessionGeneration.incrementAndGet()
            Thread({ monitorLoop(generation) }, "monitor").also { worker = it }
        }
        acquireWifiLock()
        thread.start()
    }

    override fun onStop() {
        super.onStop()
        GeotagManager.stop()
        val (stream, uvc, thread) = synchronized(lifecycleLock) {
            active = false
            sessionGeneration.incrementAndGet()
            val result = Triple(currentStream, currentUvcSource, worker)
            currentStream = null
            currentUvcSource = null
            worker = null
            result
        }
        // close() despierta inmediatamente cualquier lectura/espera de red.
        stream?.stop()
        uvc?.stop()
        thread?.interrupt()
        SonyCamera.cancelEventWait()
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        // Nota: NO liberamos directCallback aqui — soltarlo desconectaria la
        // WiFi de la camara cada vez que la app pasa a segundo plano.
        // GalleryActivity comparte esta conexion: no soltar el NetworkRequest
        // al abrirla, pues las redes DIRECT sin Internet no son la ruta por defecto.
        if (!openingGallery) connectivity.bindProcessToNetwork(null)
    }

    private fun isSessionActive(generation: Int): Boolean =
        active && sessionGeneration.get() == generation

    private fun registerStream(stream: LiveviewStream, generation: Int): Boolean =
        synchronized(lifecycleLock) {
            if (!isSessionActive(generation)) false
            else {
                currentStream = stream
                true
            }
        }

    private fun registerUvcSource(source: UvcVideoSource, generation: Int): Boolean =
        synchronized(lifecycleLock) {
            if (!isSessionActive(generation)) false
            else {
                currentUvcSource = source
                true
            }
        }

    private fun clearUvcSource(source: UvcVideoSource) {
        synchronized(lifecycleLock) {
            if (currentUvcSource === source) currentUvcSource = null
        }
    }

    /** Reinicia solamente el transporte de video al alternar WiFi/HDMI. */
    private fun restartMonitor() {
        var oldStream: LiveviewStream? = null
        var oldUvc: UvcVideoSource? = null
        var oldWorker: Thread? = null
        lateinit var replacement: Thread
        synchronized(lifecycleLock) {
            if (!active) return
            val generation = sessionGeneration.incrementAndGet()
            oldStream = currentStream
            oldUvc = currentUvcSource
            oldWorker = worker
            currentStream = null
            currentUvcSource = null
            replacement = Thread({ monitorLoop(generation) }, "monitor")
            worker = replacement
        }
        oldStream?.stop()
        oldUvc?.stop()
        oldWorker?.interrupt()
        replacement.start()
    }

    private fun clearStream(stream: LiveviewStream) {
        synchronized(lifecycleLock) {
            if (currentStream === stream) currentStream = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        directCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        directCallback = null
        SonyCamera.cancelEventWait()
        apiExecutor.shutdownNow()
        eventExecutor.shutdownNow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * Mantiene el chip WiFi despierto mientras la app esta en primer plano.
     * Sin esto, el ahorro de energia WiFi (agresivo en Samsung, sobre todo
     * en redes sin internet como la de la camara) mete latencia a rafagas
     * en el liveview. LOW_LATENCY solo actua con pantalla encendida y app
     * en primer plano: exactamente el caso de uso de un monitor.
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "sonylivemonitor:liveview").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    /**
     * Lee los margenes del agujero de camara (display cutout) para evitar que el
     * panel, el disparador o el HUD queden ocultos tras el (edge-to-edge en
     * Android 15+). Las barras del sistema no se reservan: esta pantalla las
     * oculta en modo inmersivo y reservarlas dejaria franjas negras fijas.
     */
    private fun applyWindowInsets(root: View) {
        root.setOnApplyWindowInsetsListener { _, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cutout = insets.getInsets(android.view.WindowInsets.Type.displayCutout())
                left = cutout.left
                top = cutout.top
                right = cutout.right
                bottom = cutout.bottom
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cutout = insets.displayCutout
                left = cutout?.safeInsetLeft ?: 0
                top = cutout?.safeInsetTop ?: 0
                right = cutout?.safeInsetRight ?: 0
                bottom = cutout?.safeInsetBottom ?: 0
            } else {
                left = 0; top = 0; right = 0; bottom = 0
            }
            safeInsetLeft = left
            safeInsetTop = top
            safeInsetRight = right
            safeInsetBottom = bottom
            if (::panelFrame.isInitialized) {
                panelFrame.setPadding(left, top, right, bottom)
            }
            updateVideoInsets()
            placeShutterButton?.invoke()
            insets
        }
    }

    /**
     * Disparador flotante y arrastrable: toque corto dispara, arrastrar lo
     * recoloca. La posicion se recuerda por orientacion (vertical/horizontal).
     */
    private fun buildShutterButton(): View {
        val frame = FrameLayout(this)
        val shutterSize = (68 * resources.displayMetrics.density).toInt()
        val lp = FrameLayout.LayoutParams(
            shutterSize, shutterSize,
            Gravity.TOP or Gravity.START,
        )
        val b = Button(this).apply {
            text = "●"
            textSize = 27f
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setPadding(0, 0, 0, 2)
            elevation = 6 * resources.displayMetrics.density
            setTextColor(Color.rgb(255, 76, 86))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(90, 255, 255, 255)),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(225, 24, 28, 34))
                    setStroke((2 * resources.displayMetrics.density).toInt(), Color.argb(150, 255, 255, 255))
                },
                null,
            )
            setOnClickListener { takePicture() }
        }
        frame.addView(b, lp)
        btnShutter = b

        fun orientKey() = if (frame.height > frame.width) "port" else "land"

        fun place() {
            if (frame.width == 0) return
            val prefs = getPreferences(MODE_PRIVATE)
            val minX = safeInsetLeft
            val minY = safeInsetTop
            val maxX = maxOf(minX, frame.width - safeInsetRight - b.width)
            val maxY = maxOf(minY, frame.height - safeInsetBottom - b.height)
            val defX = maxOf(minX, frame.width - maxOf(videoRightInset, safeInsetRight) - b.width - 16)
            val defY = (minY + maxOf(minY, frame.height - maxOf(videoBottomInset, safeInsetBottom) - b.height)) / 2
            lp.leftMargin = prefs.getInt("shutter_${orientKey()}_x", defX)
                .coerceIn(minX, maxX)
            lp.topMargin = prefs.getInt("shutter_${orientKey()}_y", defY)
                .coerceIn(minY, maxY)
            b.layoutParams = lp
        }
        placeShutterButton = ::place

        frame.addOnLayoutChangeListener { _, l, t, r, bo, ol, ot, or_, ob ->
            if (r - l != or_ - ol || bo - t != ob - ot) frame.post { place() }
        }
        frame.post { place() }

        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startTop = 0
        var dragged = false
        b.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startLeft = lp.leftMargin; startTop = lp.topMargin
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (dragged || Math.abs(dx) > 24 || Math.abs(dy) > 24) {
                        dragged = true
                        val minX = safeInsetLeft
                        val minY = safeInsetTop
                        val maxX = maxOf(minX, frame.width - safeInsetRight - v.width)
                        val maxY = maxOf(minY, frame.height - safeInsetBottom - v.height)
                        lp.leftMargin = (startLeft + dx).toInt().coerceIn(minX, maxX)
                        lp.topMargin = (startTop + dy).toInt().coerceIn(minY, maxY)
                        v.layoutParams = lp
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        getPreferences(MODE_PRIVATE).edit()
                            .putInt("shutter_${orientKey()}_x", lp.leftMargin)
                            .putInt("shutter_${orientKey()}_y", lp.topMargin)
                            .apply()
                    } else {
                        v.performClick()
                    }
                    true
                }
                else -> true
            }
        }
        return frame
    }

    // -- UI: barra inferior (ajustes en vivo + disparo) ---------------------------

    /**
     * Panel de control por categorias: rejilla a lo ancho bajo el monitor en
     * vertical y columna lateral en horizontal. El area de video se encoge
     * para no solaparse y cada grupo puede crecer sin saturar la pantalla.
     */
    private fun buildBottomBar(): View {
        valueRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        valueStrip = HorizontalScrollView(this).apply {
            addView(valueRow)
            visibility = View.GONE
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }

        fun chip(group: MutableList<Button>, text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            textSize = 12.5f
            isAllCaps = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setPadding(12, 4, 12, 4)
            tag = "chip"
            elevation = 0f
            stateListAnimator = null
            highlightValue(this, false)
            setOnClickListener { onClick() }
            group.add(this)
        }

        settings.forEach { s -> chips[s] = chip(cameraChips, s.title) { toggleValueStrip(s) } }
        chipEv = chip(cameraChips, "EV") { toggleEvStrip() }
        chipWb = chip(cameraChips, "WB") { toggleWbStrip() }
        chipShootMode = chip(cameraChips, "Mode: Photo") { toggleModeStrip() }

        // Zoom motorizado: mantener pulsado
        fun zoomChip(label: String, direction: String) = chip(cameraChips, label) {}.apply {
            setOnClickListener(null)
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> zoom(direction, "start")
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        zoom(direction, "stop")
                        v.performClick()
                    }
                }
                true
            }
        }
        zoomChips += zoomChip("Z− (W)", "out")
        zoomChips += zoomChip("Z+ (T)", "in")

        // Botones de la app, en el mismo panel para que nada flote sobre
        // el monitor. Conectar: un toque conecta con lo guardado; mantener
        // pulsado edita las credenciales.
        btnVideoSource = chip(
            appChips, if (videoSource == VideoSource.HDMI) "Video: HDMI" else "Video: WiFi"
        ) {
            videoSource = if (videoSource == VideoSource.WIFI) VideoSource.HDMI else VideoSource.WIFI
            getPreferences(MODE_PRIVATE).edit().putInt("video_source", videoSource.ordinal).apply()
            btnVideoSource.text = if (videoSource == VideoSource.HDMI) "Video: HDMI" else "Video: WiFi"
            highlightValue(btnVideoSource, videoSource == VideoSource.HDMI)
            if (videoSource == VideoSource.HDMI &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            }
            restartMonitor()
        }
        highlightValue(btnVideoSource, videoSource == VideoSource.HDMI)
        btnConnect = chip(appChips, "Connect") {
            when {
                directNetwork != null -> disconnectCamera()
                getPreferences(MODE_PRIVATE).getString("pass", "").isNullOrEmpty() -> showConnectDialog()
                else -> connectDirect(
                    getPreferences(MODE_PRIVATE).getString("ssid", "") ?: "",
                    getPreferences(MODE_PRIVATE).getString("pass", "") ?: "",
                )
            }
        }
        btnConnect.setOnLongClickListener {
            showConnectDialog()
            true
        }
        var btnRot: Button? = null
        btnRot = chip(viewChips, "Rot: ${rotation}°") {
            rotation = (rotation + 90) % 360
            getPreferences(MODE_PRIVATE).edit().putInt("rot", rotation).apply()
            btnRot?.text = "Rot: ${rotation}°"
        }
        // Espejo horizontal (modo selfie): solo afecta a la vista, no a lo que graba la camara
        var btnMirror: Button? = null
        btnMirror = chip(viewChips, "Mirror: ${if (mirror) "on" else "off"}") {
            mirror = !mirror
            getPreferences(MODE_PRIVATE).edit().putBoolean("mirror", mirror).apply()
            btnMirror?.text = "Mirror: ${if (mirror) "on" else "off"}"
            btnMirror?.let { highlightValue(it, mirror) }
        }
        highlightValue(btnMirror, mirror)
        btnGrid = chip(viewChips, "Grid: ${gridLabel()}") { cycleGrid() }
        highlightValue(btnGrid, grid != Grid.OFF)
        btnMeter = chip(viewChips, "Meter: ${if (meterOn) "on" else "off"}") {
            meterOn = !meterOn
            getPreferences(MODE_PRIVATE).edit().putBoolean("meter", meterOn).apply()
            btnMeter.text = "Meter: ${if (meterOn) "on" else "off"}"
            highlightValue(btnMeter, meterOn)
        }
        highlightValue(btnMeter, meterOn)
        btnPeaking = chip(viewChips, "Peak: ${peakingColor.label}") {
            peakingColor = PeakingColor.entries[(peakingColor.ordinal + 1) % PeakingColor.entries.size]
            getPreferences(MODE_PRIVATE).edit().putInt("peaking_color", peakingColor.ordinal).apply()
            btnPeaking.text = "Peak: ${peakingColor.label}"
            highlightValue(btnPeaking, peakingColor != PeakingColor.OFF)
        }
        highlightValue(btnPeaking, peakingColor != PeakingColor.OFF)
        btnPeakingSensitivity = chip(viewChips, "Peak sens: ${peakingSensitivity.shortLabel}") {
            peakingSensitivity = PeakingSensitivity.entries[
                (peakingSensitivity.ordinal + 1) % PeakingSensitivity.entries.size
            ]
            getPreferences(MODE_PRIVATE).edit()
                .putInt("peaking_sensitivity", peakingSensitivity.ordinal).apply()
            btnPeakingSensitivity.text = "Peak sens: ${peakingSensitivity.shortLabel}"
        }
        btnHud = chip(viewChips, "HUD: ${if (hudOn) "on" else "off"}") {
            hudOn = !hudOn
            getPreferences(MODE_PRIVATE).edit().putBoolean("hud", hudOn).apply()
            btnHud.text = "HUD: ${if (hudOn) "on" else "off"}"
            highlightValue(btnHud, hudOn)
        }
        highlightValue(btnHud, hudOn)
        btnGallery = chip(appChips, "Camera card") {
            openingGallery = true
            startActivity(Intent(this, CameraGalleryActivity::class.java))
        }
        btnGeotag = chip(appChips, if (GeotagManager.isEnabled(this)) "Location: on" else "Location: off") {
            val enable = !GeotagManager.isEnabled(this)
            GeotagManager.setEnabled(this, enable)
            updateGeotagChip()
            toast(if (enable) "Geotagging enabled for future photos" else "Geotagging disabled")
        }
        highlightValue(btnGeotag, GeotagManager.isEnabled(this))
        chip(appChips, "Diag") { showDiagnostics() }

        controlGrid = GridLayout(this)

        // Pestanas del panel: cambian que grupo de chips se muestra en la rejilla
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Camera", "View", "App").forEachIndexed { i, name ->
            val b = Button(this).apply {
                text = name
                textSize = 13f
                isAllCaps = false
                minWidth = 0; minimumWidth = 0
                minHeight = 0; minimumHeight = 0
                setPadding(4, 4, 4, 4)
                tag = "tab"
                elevation = 0f
                stateListAnimator = null
                setOnClickListener {
                    if (panelTab != i) {
                        panelTab = i
                        getPreferences(MODE_PRIVATE).edit().putInt("panel_tab", i).apply()
                        closeValueStrip()
                        applyPanelLayout()
                    }
                }
            }
            tabButtons.add(b)
            tabRow.addView(b, LinearLayout.LayoutParams(
                0, (40 * resources.displayMetrics.density).toInt(), 1f,
            ).apply { setMargins(2, 2, 2, 2) })
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14 * resources.displayMetrics.density
                setColor(Color.argb(242, 16, 19, 24))
            }
            addView(valueStrip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(tabRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(controlGrid, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        // Deslizable en vertical por si en horizontal no cabe todo el panel
        panelContainer = ScrollView(this).apply {
            addView(panel)
            isVerticalScrollBarEnabled = false
        }
        panelFrame = FrameLayout(this)
        panelFrame.addView(panelContainer)
        applyPanelLayout()

        panelContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateVideoInsets() }
        return panelFrame
    }

    /** Recoloca el panel segun la orientacion actual. */
    private fun applyPanelLayout() {
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        tabButtons.forEachIndexed { i, b -> highlightValue(b, i == panelTab) }
        controlGrid.removeAllViews()
        controlGrid.columnCount = if (portrait) 4 else 2
        tabGroups[panelTab].forEach { b ->
            (b.parent as? android.view.ViewGroup)?.removeView(b)
            controlGrid.addView(b, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                // Altura fija: un texto largo no debe descuadrar su fila
                height = (36 * resources.displayMetrics.density).toInt()
                setMargins(2, 2, 2, 2)
            })
        }
        val density = resources.displayMetrics.density
        // Reservar siempre las filas que necesita la categoria mas grande evita
        // que el liveview cambie de tamano al pasar entre Camera, View y App.
        val maxRows = tabGroups.maxOf { (it.size + controlGrid.columnCount - 1) / controlGrid.columnCount }
        controlGrid.layoutParams = (controlGrid.layoutParams as LinearLayout.LayoutParams).apply {
            height = (maxRows * 38 * density).toInt()
        }
        panelContainer.layoutParams = if (portrait) {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
        } else {
            FrameLayout.LayoutParams(
                (300 * density).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            )
        }
        updateVideoInsets()
    }

    /** El video se encaja en el hueco libre que deja el panel y las barras/cutout. */
    private fun updateVideoInsets() {
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        if (portrait) {
            videoBottomInset = if (panelContainer.height > 0) {
                maxOf(panelFrame.height - panelContainer.top, panelContainer.height + safeInsetBottom)
            } else {
                safeInsetBottom
            }
            videoRightInset = safeInsetRight
        } else {
            videoBottomInset = safeInsetBottom
            videoRightInset = if (panelContainer.width > 0) {
                maxOf(panelFrame.width - panelContainer.left, panelContainer.width + safeInsetRight)
            } else {
                safeInsetRight
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPanelLayout()
        hideSystemBars()
    }

    /** Abre/cierra la tira horizontal de valores de un ajuste. Cada valor se
     *  aplica al instante y la tira permanece abierta para seguir ajustando. */
    private fun toggleValueStrip(setting: Setting) {
        if (setting.title == "Drive") {
            toggleDriveStrip(setting)
            return
        }
        if (openSetting === setting) {
            closeValueStrip()
            return
        }
        setStripOwner(setting, chips[setting])
        apiExecutor.execute {
            try {
                val r = SonyCamera.call(SonyCamera.cameraEndpoint, setting.getMethod)
                val current = r.getString(0)
                val candidates = r.getJSONArray(1)
                val values = List(candidates.length()) { candidates.getString(it) }
                if (values.isEmpty()) throw CameraException("not available in this mode")
                runOnUiThread { if (openSetting === setting) fillValueStrip(setting, values, current) }
            } catch (e: Exception) {
                toast("${setting.title}: ${e.message}")
                runOnUiThread { closeValueStrip() }
            }
        }
    }

    /** Drive usa objetos JSON, a diferencia de ISO, apertura, etc. La a6000
     *  publica que este setter no esta disponible, aunque conozca sus valores. */
    private fun toggleDriveStrip(setting: Setting) {
        if (openSetting === setting) {
            closeValueStrip()
            return
        }
        setStripOwner(setting, chips[setting])
        apiExecutor.execute {
            if (shootMode == "movie") {
                toast("Drive: available only in Photo mode")
                runOnUiThread { closeValueStrip() }
                return@execute
            }
            // No usar getAvailableApiList como bloqueo: una app de camara
            // parcheada puede aceptar metodos que no anuncia en esa lista.
            if (CameraCompatibility.isA6000(SonyCamera.modelName)) {
                toast(CameraCompatibility.driveUnavailableMessage(SonyCamera.modelName))
                runOnUiThread { closeValueStrip() }
                return@execute
            }
            try {
                val state = SonyCamera.call(SonyCamera.cameraEndpoint, setting.getMethod).getJSONObject(0)
                val current = state.getString("contShootingMode")
                val candidates = state.getJSONArray("candidate")
                val values = List(candidates.length()) { candidates.getString(it) }
                if (values.isEmpty()) throw CameraException("not available in this mode")
                runOnUiThread {
                    if (openSetting === setting) {
                        showValueStrip(setting, values, current) { value, view ->
                            applySetting(
                                setting.setMethod,
                                JSONObject().put("contShootingMode", value),
                            ) {
                                chips[setting]?.text = setting.chipLabel(value)
                                markSelected(view)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                val unavailable = e.message?.contains("Not Available Now", ignoreCase = true) == true
                toast(if (unavailable) {
                    CameraCompatibility.driveUnavailableMessage(SonyCamera.modelName)
                } else {
                    "Drive: ${e.message}"
                })
                runOnUiThread { closeValueStrip() }
            }
        }
    }

    private fun fillValueStrip(setting: Setting, values: List<String>, current: String) {
        showValueStrip(setting, values, current) { value, view ->
            val payload: Any = if (setting.numeric) value.toInt() else value
            applySetting(setting.setMethod, payload) {
                chips[setting]?.text = setting.chipLabel(value)
                markSelected(view)
            }
        }
    }

    /** Tira horizontal generica de valores: cada toque aplica al instante. */
    private fun showValueStrip(
        owner: Any,
        values: List<String>,
        current: String,
        onPick: (value: String, view: Button) -> Unit,
    ) {
        if (openSetting != owner) return
        valueRow.removeAllViews()
        var currentView: View? = null
        values.forEach { value ->
            val b = Button(this).apply {
                text = value
                textSize = 13f
                isAllCaps = false
                maxLines = 1
                minHeight = 0
                minimumHeight = 0
                minWidth = 0
                minimumWidth = 0
                setPadding(30, 16, 30, 16)
                tag = "value"
                elevation = 0f
                stateListAnimator = null
                highlightValue(this, value == current)
                setOnClickListener { onPick(value, this) }
            }
            valueRow.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(6, 8, 6, 8) })
            if (value == current) currentView = b
        }
        valueStrip.visibility = View.VISIBLE
        currentView?.let { v -> valueStrip.post { valueStrip.smoothScrollTo(maxOf(0, v.left - 200), 0) } }
    }

    private fun markSelected(view: Button) {
        for (i in 0 until valueRow.childCount) {
            val child = valueRow.getChildAt(i) as Button
            highlightValue(child, child === view)
        }
    }

    private fun highlightValue(button: Button, selected: Boolean) {
        val density = resources.displayMetrics.density
        val kind = button.tag as? String
        val fill = when {
            kind == "tab" && selected -> Color.rgb(25, 66, 51)
            kind == "tab" -> Color.TRANSPARENT
            selected -> Color.rgb(20, 59, 46)
            else -> Color.rgb(31, 35, 42)
        }
        val stroke = when {
            selected -> Color.argb(220, 0, 255, 128)
            kind == "tab" -> Color.TRANSPARENT
            else -> Color.rgb(56, 62, 72)
        }
        val radius = if (kind == "value") 20f else 10f
        val content = GradientDrawable().apply {
            cornerRadius = radius * density
            setColor(fill)
            setStroke(maxOf(1, density.toInt()), stroke)
        }
        button.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(75, 0, 255, 128)),
            content,
            null,
        )
        button.setTextColor(if (selected) Color.rgb(75, 255, 163) else Color.rgb(224, 228, 234))
        button.typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
        button.alpha = 1f
    }

    private var openChipButton: Button? = null

    /** Marca el chip que tiene su menu abierto y desmarca el anterior. */
    private fun setStripOwner(owner: Any?, chip: Button?) {
        openChipButton?.let { highlightValue(it, false) }
        openSetting = owner
        openChipButton = chip
        chip?.let { highlightValue(it, true) }
    }

    private fun closeValueStrip() {
        setStripOwner(null, null)
        valueStrip.visibility = View.GONE
        valueRow.removeAllViews()
    }

    /** La compensacion EV va por indices de paso (1/3 o 1/2 EV por paso). */
    private fun toggleEvStrip() {
        if (openSetting == "EV") {
            closeValueStrip()
            return
        }
        setStripOwner("EV", chipEv)
        apiExecutor.execute {
            try {
                val r = SonyCamera.call(SonyCamera.cameraEndpoint, "getAvailableExposureCompensation")
                val current = r.getInt(0)
                val max = r.getInt(1)
                val min = r.getInt(2)
                val stepEv = if (r.getInt(3) == 2) 0.5 else 1.0 / 3.0
                val steps = (min..max).toList()
                val labels = steps.map { String.format("%+.1f", it * stepEv) }
                runOnUiThread {
                    showValueStrip("EV", labels, String.format("%+.1f", current * stepEv)) { label, view ->
                        applySetting("setExposureCompensation", steps[labels.indexOf(label)]) {
                            chipEv.text = "$label EV"
                            markSelected(view)
                        }
                    }
                }
            } catch (e: Exception) {
                toast("EV: ${e.message}")
                runOnUiThread { closeValueStrip() }
            }
        }
    }

    /** El balance de blancos usa objetos {whiteBalanceMode: ...} en la API. */
    private fun toggleWbStrip() {
        if (openSetting == "WB") {
            closeValueStrip()
            return
        }
        setStripOwner("WB", chipWb)
        apiExecutor.execute {
            try {
                val r = SonyCamera.call(SonyCamera.cameraEndpoint, "getAvailableWhiteBalance")
                val current = r.getJSONObject(0).getString("whiteBalanceMode")
                val cand = r.getJSONArray(1)
                val modes = List(cand.length()) { cand.getJSONObject(it).getString("whiteBalanceMode") }
                runOnUiThread {
                    showValueStrip("WB", modes, current) { mode, view ->
                        apiExecutor.execute {
                            try {
                                cancelTouchFocusBeforeSetting()
                                // Para "Color Temperature" hace falta un kelvin: 5500 por defecto
                                val temp = mode == "Color Temperature"
                                callSettingWhenAvailable(
                                    "setWhiteBalance",
                                    JSONArray().put(mode).put(temp).put(if (temp) 5500 else 0),
                                )
                                runOnUiThread {
                                    chipWb.text = "WB ${mode.removeSuffix(" WB")}"
                                    markSelected(view)
                                }
                            } catch (e: Exception) {
                                toast("WB: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                toast("WB: ${e.message}")
                runOnUiThread { closeValueStrip() }
            }
        }
    }

    /** Cambio entre foto y video (el disparador pasa a iniciar/parar REC). */
    private fun toggleModeStrip() {
        if (openSetting == "MODE") {
            closeValueStrip()
            return
        }
        setStripOwner("MODE", chipShootMode)
        apiExecutor.execute {
            try {
                val r = SonyCamera.call(SonyCamera.cameraEndpoint, "getAvailableShootMode")
                val current = r.getString(0)
                val cand = r.getJSONArray(1)
                val modes = List(cand.length()) { cand.getString(it) }
                runOnUiThread {
                    showValueStrip("MODE", modes, current) { mode, view ->
                        applySetting("setShootMode", mode) {
                            shootMode = mode
                            movieRecording = false
                            chipShootMode.text = if (mode == "movie") "Mode: Video" else "Mode: Photo"
                            btnShutter.text = "●"
                            markSelected(view)
                        }
                    }
                }
            } catch (e: Exception) {
                toast("Mode: ${e.message}")
                runOnUiThread { closeValueStrip() }
            }
        }
    }

    /** Zoom motorizado: mantener pulsado para acercar/alejar. */
    private fun zoom(direction: String, movement: String) {
        apiExecutor.execute {
            runCatching {
                SonyCamera.call(
                    SonyCamera.cameraEndpoint, "actZoom",
                    JSONArray().put(direction).put(movement),
                )
            }.onFailure {
                if (movement == "start") toast("Zoom: requires a power zoom lens")
            }
        }
    }

    private fun applySetting(setMethod: String, value: Any, onOk: () -> Unit) {
        apiExecutor.execute {
            try {
                cancelTouchFocusBeforeSetting()
                callSettingWhenAvailable(setMethod, JSONArray().put(value))
                runOnUiThread(onOk)
            } catch (e: Exception) {
                toast("Error: ${e.message}")
            }
        }
    }

    /** Sony bloquea temporalmente ISO, WB, modo, etc. mientras sigue activo el
     * enfoque tactil. Se cancela antes de cambiar un ajuste y se tolera el breve
     * estado de transicion que algunos modelos notifican como error 1. */
    private fun cancelTouchFocusBeforeSetting() {
        if (!touchFocusActive) return
        runCatching { callSettingWhenAvailable("cancelTouchAFPosition") }
        touchFocusActive = false
    }

    private fun callSettingWhenAvailable(method: String, params: JSONArray = JSONArray()): JSONArray {
        val delaysMs = longArrayOf(150, 300, 600)
        for (attempt in 0..delaysMs.size) {
            try {
                return SonyCamera.call(SonyCamera.cameraEndpoint, method, params)
            } catch (e: CameraException) {
                val temporarilyBusy = e.message?.contains("[1] Not Available Now", ignoreCase = true) == true
                if (!temporarilyBusy || attempt == delaysMs.size) throw e
                SystemClock.sleep(delaysMs[attempt])
            }
        }
        throw CameraException("$method failed")
    }

    /** Actualiza las etiquetas de los chips con los valores actuales de la camara. */
    private fun refreshChips() {
        apiExecutor.execute {
            val currentByTitle = mutableMapOf<String, String>()
            settings.forEach { setting ->
                runCatching {
                    val result = SonyCamera.call(
                        SonyCamera.cameraEndpoint, setting.currentMethod,
                    )
                    currentByTitle[setting.title] = if (setting.title == "Drive") {
                        result.getJSONObject(0).getString("contShootingMode")
                    } else {
                        result.get(0).toString()
                    }
                }
            }
            settings.forEach { s ->
                if (!currentByTitle.containsKey(s.title)) {
                    runCatching {
                        val result = SonyCamera.call(
                            SonyCamera.cameraEndpoint, s.getMethod,
                        )
                        currentByTitle[s.title] = if (s.title == "Drive") {
                            result.getJSONObject(0).getString("contShootingMode")
                        } else {
                            result.get(0).toString()
                        }
                    }
                }
            }
            runOnUiThread {
                settings.forEach { s ->
                    currentByTitle[s.title]?.let { chips[s]?.text = s.chipLabel(it) }
                }
            }
            runCatching {
                val r = SonyCamera.call(SonyCamera.cameraEndpoint, "getAvailableExposureCompensation")
                evStepIndex = r.getInt(3)
                val stepEv = if (evStepIndex == 2) 0.5 else 1.0 / 3.0
                val label = String.format("%+.1f EV", r.getInt(0) * stepEv)
                runOnUiThread { chipEv.text = label }
            }
            runCatching {
                val mode = SonyCamera.call(SonyCamera.cameraEndpoint, "getAvailableWhiteBalance")
                    .getJSONObject(0).getString("whiteBalanceMode")
                runOnUiThread { chipWb.text = "WB ${mode.removeSuffix(" WB")}" }
            }
            runCatching {
                val mode = SonyCamera.call(SonyCamera.cameraEndpoint, "getAvailableShootMode").getString(0)
                shootMode = mode
                runOnUiThread { chipShootMode.text = if (mode == "movie") "Mode: Video" else "Mode: Photo" }
            }
            applyCapabilities()
        }
    }

    /**
     * Mantiene una sola espera notificativa. Sony documenta que getEvent(true)
     * despierta tanto por llamadas remotas como por controles fisicos.
     */
    private fun startCameraEventListener(generation: Int) {
        if (!eventListenerRunning.compareAndSet(false, true)) return
        synchronized(captureEventLock) { lastCameraStatus = null }
        eventExecutor.execute {
            var version = "1.1"
            try {
                while (isSessionActive(generation) && !Thread.currentThread().isInterrupted) {
                    try {
                        val events = SonyCamera.call(
                            SonyCamera.cameraEndpoint, "getEvent", JSONArray().put(true),
                            version = version, readTimeoutMs = 65_000, eventWait = true,
                        )
                        if (isSessionActive(generation)) handleCameraEvents(events)
                    } catch (_: Exception) {
                        if (!isSessionActive(generation)) break
                        // getEvent 1.0 conserva long polling y cubre modelos que
                        // no anuncian la extension 1.1.
                        if (version == "1.1") version = "1.0" else SystemClock.sleep(500)
                    }
                }
            } finally {
                eventListenerRunning.set(false)
                if (active) startCameraEventListener(sessionGeneration.get())
            }
        }
    }

    private fun handleCameraEvents(result: JSONArray) {
        val objects = mutableListOf<JSONObject>()
        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> objects += value
                is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i))
            }
        }
        collect(result)

        val labels = mutableMapOf<Setting, String>()
        val keys = mapOf(
            "currentIsoSpeedRate" to "ISO",
            "currentShutterSpeed" to "Shutter",
            "currentFNumber" to "Aperture",
            "currentFocusMode" to "Focus",
            "currentContShootingMode" to "Drive",
            "currentFlashMode" to "Flash",
            "currentSelfTimer" to "Timer",
        )
        var evLabel: String? = null
        var wbLabel: String? = null
        var modeLabel: String? = null
        var zoomPosition: Int? = null

        objects.forEach { event ->
            event.optString("cameraStatus").takeIf { it.isNotBlank() }?.let(::handleCameraStatus)
            keys.forEach { (key, title) ->
                if (event.has(key) && !event.isNull(key)) {
                    settings.firstOrNull { it.title == title }?.let { setting ->
                        labels[setting] = setting.chipLabel(event.get(key).toString())
                    }
                }
            }
            if (event.has("currentExposureCompensation")) {
                if (event.has("stepIndexOfExposureCompensation")) {
                    evStepIndex = event.optInt("stepIndexOfExposureCompensation", evStepIndex)
                }
                val step = if (evStepIndex == 2) 0.5 else 1.0 / 3.0
                evLabel = String.format("%+.1f EV", event.optInt("currentExposureCompensation") * step)
            }
            val wb = event.optString("currentWhiteBalanceMode")
            if (wb.isNotBlank()) {
                wbLabel = "WB " + wb.removeSuffix(" WB")
            }
            val mode = event.optString("currentShootMode")
            if (mode.isNotBlank()) {
                shootMode = mode
                modeLabel = if (mode == "movie") "Mode: Video" else "Mode: Photo"
            }
            if (event.has("zoomPosition")) zoomPosition = event.optInt("zoomPosition")
        }

        zoomPosition?.let { pct ->
            val mm = 16.0 * Math.pow(50.0 / 16.0, pct / 100.0)
            zoomText = "Zoom %d%%  ~%.0f mm".format(pct, mm)
            zoomTextUntil = SystemClock.elapsedRealtime() + 2500
        }
        if (labels.isNotEmpty() || evLabel != null || wbLabel != null || modeLabel != null) {
            runOnUiThread {
                labels.forEach { (setting, label) -> chips[setting]?.text = label }
                evLabel?.let { chipEv.text = it }
                wbLabel?.let { chipWb.text = it }
                modeLabel?.let { chipShootMode.text = it }
            }
        }
    }

    private fun handleCameraStatus(status: String) {
        var recordPhysicalCapture = false
        synchronized(captureEventLock) {
            val previous = lastCameraStatus
            if (status == previous) return
            val wasCapturing = previous == "StillCapturing" || previous == "StillSaving"
            when (status) {
                "StillCapturing", "StillSaving" -> {
                    capturing = true
                    if (!wasCapturing) {
                        val pendingAppShot = System.currentTimeMillis() - appCapturePendingAt in 0..30_000
                        if (pendingAppShot) appCapturePendingAt = 0L else recordPhysicalCapture = true
                    }
                }
                "IDLE" -> {
                    capturing = false
                    if (wasCapturing) appCapturePendingAt = 0L
                }
            }
            lastCameraStatus = status
        }
        if (recordPhysicalCapture) GeotagManager.recordCapture(applicationContext)
    }

    /** Esconde lo que esta camara no soporta (galeria, zoom motorizado...). */
    private fun applyCapabilities() {
        val showGallery = SonyCamera.supports("setCameraFunction")
        val showZoom = SonyCamera.supports("actZoom")
        runOnUiThread {
            btnGallery.visibility = if (showGallery) View.VISIBLE else View.GONE
            zoomChips.forEach { it.visibility = if (showZoom) View.VISIBLE else View.GONE }
        }
    }

    /** Informe de la camara conectada, pensado para que testers lo compartan. */
    private fun showDiagnostics() {
        toast("Reading camera info...")
        apiExecutor.execute {
            val report = SonyCamera.diagnostics()
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(SonyCamera.modelName ?: "Camera diagnostics")
                    .setMessage(report)
                    .setPositiveButton("Share") { _, _ ->
                        startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, report),
                            "Share diagnostics",
                        ))
                    }
                    .setNeutralButton("Copy") { _, _ ->
                        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("diagnostics", report)
                        )
                        toast("Copied")
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun takePicture() {
        if (shootMode == "movie") {
            // En modo video el disparador inicia/detiene la grabacion
            apiExecutor.execute {
                try {
                    if (movieRecording) {
                        SonyCamera.call(SonyCamera.cameraEndpoint, "stopMovieRec")
                        movieRecording = false
                        toast("Recording stopped")
                    } else {
                        SonyCamera.call(SonyCamera.cameraEndpoint, "startMovieRec")
                        movieRecording = true
                        toast("Recording...")
                    }
                    runOnUiThread { btnShutter.text = if (movieRecording) "■" else "●" }
                } catch (e: Exception) {
                    toast("Video: ${e.message}")
                }
            }
            return
        }
        capturing = true
        synchronized(captureEventLock) { appCapturePendingAt = System.currentTimeMillis() }
        GeotagManager.recordCapture(applicationContext)
        apiExecutor.execute {
            try {
                SonyCamera.call(SonyCamera.cameraEndpoint, "actTakePicture")
                toast("Photo taken")
            } catch (e: Exception) {
                synchronized(captureEventLock) { appCapturePendingAt = 0L }
                toast("Shutter: ${e.message}")
            } finally {
                capturing = false
            }
        }
    }

    // -- conexion WiFi integrada ---------------------------------------------------

    private fun showConnectDialog() {
        val prefs = getPreferences(MODE_PRIVATE)
        val ssidInput = EditText(this).apply {
            hint = "SSID (shown on the camera screen)"
            setText(prefs.getString("ssid", "DIRECT-kbE0:ILCE-6000"))
        }
        val passInput = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("pass", ""))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(ssidInput)
            addView(passInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Connect to camera")
            .setMessage(
                "The SSID and password are shown on the camera screen " +
                "when entering 'Ctrl w/ Smartphone'. You only need to type them once."
            )
            .setView(box)
            .setPositiveButton("Connect") { _, _ ->
                val ssid = ssidInput.text.toString().trim()
                val pass = passInput.text.toString()
                prefs.edit().putString("ssid", ssid).putString("pass", pass).apply()
                connectDirect(ssid, pass)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Conecta a la red local de la camara usando la API adecuada del sistema. */
    private fun connectDirect(ssid: String, pass: String) {
        if (ssid.isEmpty()) {
            toast("Missing SSID")
            showConnectDialog()
            return
        }
        if (pass.length !in 8..63) {
            // WPA2 exige 8-63 caracteres; la red de la camara siempre tiene clave
            toast("The password is shown on the camera screen (8+ characters)")
            showConnectDialog()
            return
        }

        if (!hasWifiPermission()) {
            pendingWifiCredentials = ssid to pass
            requestPermissions(arrayOf(requiredWifiPermission()), WIFI_PERMISSION_REQUEST)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectDirectModern(ssid, pass)
        } else {
            connectDirectLegacy(ssid, pass)
        }
    }

    private fun requiredWifiPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun hasWifiPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(requiredWifiPermission()) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == GeotagManager.PERMISSION_REQUEST) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                GeotagManager.start(applicationContext)
                toast("Geotagging enabled for future photos")
            } else {
                GeotagManager.setEnabled(this, false)
                toast("Location permission is required for geotagging")
            }
            updateGeotagChip()
            return
        }
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                toast("HDMI capture enabled")
                restartMonitor()
            } else {
                videoSource = VideoSource.WIFI
                getPreferences(MODE_PRIVATE).edit().putInt("video_source", videoSource.ordinal).apply()
                if (::btnVideoSource.isInitialized) {
                    btnVideoSource.text = "Video: WiFi"
                    highlightValue(btnVideoSource, false)
                }
                toast("Camera permission is required for the HDMI capture device")
                restartMonitor()
            }
            return
        }
        if (requestCode != WIFI_PERMISSION_REQUEST) return
        val credentials = pendingWifiCredentials
        pendingWifiCredentials = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && credentials != null) {
            connectDirect(credentials.first, credentials.second)
        } else {
            toast("WiFi permission is required to connect to the camera")
        }
    }

    private fun updateGeotagChip() {
        if (!::btnGeotag.isInitialized) return
        val enabled = GeotagManager.isEnabled(this)
        btnGeotag.text = if (enabled) "Location: on" else "Location: off"
        highlightValue(btnGeotag, enabled)
    }

    /** Android 10+: conexion local aprobada mediante el dialogo seguro del sistema. */
    private fun connectDirectModern(ssid: String, pass: String) {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        if (!wifi.isWifiEnabled) {
            toast("Enable WiFi and try again")
            return
        }

        directCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        directNetwork = null
        connectivity.bindProcessToNetwork(null)

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(pass)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        btnConnect.text = "Searching..."
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                directNetwork = network
                connectivity.bindProcessToNetwork(network)
                apiExecutor.execute {
                    val ok = SonyCamera.locate()
                    runOnUiThread {
                        btnConnect.text = if (ok) "Disconnect" else "Searching..."
                        if (!ok) toast("Connected WiFi, but the camera did not respond")
                    }
                }
            }

            override fun onUnavailable() {
                directNetwork = null
                runOnUiThread {
                    btnConnect.text = "Connect"
                    toast("Camera WiFi connection was cancelled or unavailable")
                }
            }

            override fun onLost(network: Network) {
                if (directNetwork == network) directNetwork = null
                connectivity.bindProcessToNetwork(null)
                runOnUiThread { btnConnect.text = "Connect" }
            }
        }
        directCallback = callback
        connectivity.requestNetwork(request, callback)
    }

    /** Android 7-9: conexion mediante WifiManager, requerido por esas versiones. */
    @Suppress("DEPRECATION")
    private fun connectDirectLegacy(ssid: String, pass: String) {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        if (!wifi.isWifiEnabled) {
            wifi.isWifiEnabled = true
        }

        val prefs = getPreferences(MODE_PRIVATE)
        val quotedSsid = "\"$ssid\""
        var netId = prefs.getInt("netId_$quotedSsid", -1)
        if (netId < 0) {
            val conf = WifiConfiguration().apply {
                SSID = quotedSsid
                preSharedKey = "\"$pass\""
            }
            netId = wifi.addNetwork(conf)
            if (netId < 0) {
                toast("Could not register the network (forget the camera WiFi in Settings > WiFi and retry)")
                return
            }
            prefs.edit().putInt("netId_$quotedSsid", netId).apply()
        }

        btnConnect.text = "Searching..."
        toast("Connecting to $ssid...")
        wifi.disconnect()
        if (!wifi.enableNetwork(netId, true)) {
            // el netId guardado puede haber caducado: reintentar limpio
            prefs.edit().remove("netId_$quotedSsid").apply()
            toast("Retrying network registration...")
            connectDirect(ssid, pass)
            return
        }
        wifi.reconnect()
        watchForCameraNetwork()
    }

    /** Suelta la WiFi de la camara y deja al movil volver a su red anterior. */
    @Suppress("DEPRECATION")
    private fun disconnectCamera() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            directCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            directCallback = null
            directNetwork = null
            connectivity.bindProcessToNetwork(null)
            btnConnect.text = "Connect"
            toast("Camera disconnected")
            return
        }

        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        val ssid = getPreferences(MODE_PRIVATE).getString("ssid", "") ?: ""
        val netId = getPreferences(MODE_PRIVATE).getInt("netId_\"$ssid\"", -1)
        if (netId >= 0) wifi.disableNetwork(netId)  // evitar que se reenganche sola
        wifi.disconnect()
        wifi.reconnect()
        directCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        directCallback = null
        directNetwork = null
        connectivity.bindProcessToNetwork(null)
        btnConnect.text = "Connect"
        toast("Camera disconnected")
    }

    /** Actualiza el boton cuando la WiFi de la camara este realmente arriba. */
    private fun watchForCameraNetwork() {
        directCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                directNetwork = network
                connectivity.bindProcessToNetwork(network)
                // La WiFi disponible puede ser aun la de casa: confirmar que
                // de verdad se llega a la camara antes de dar el OK.
                apiExecutor.execute {
                    val ok = SonyCamera.locate()
                    runOnUiThread { btnConnect.text = if (ok) "Disconnect" else "Searching..." }
                }
            }

            override fun onLost(network: Network) {
                if (directNetwork == network) directNetwork = null
                runOnUiThread { btnConnect.text = "Connect" }
            }
        }
        directCallback = callback
        connectivity.requestNetwork(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(),
            callback,
        )
    }

    // -- enfoque tactil --------------------------------------------------------------

    private fun onSurfaceTouch(v: View, ev: MotionEvent): Boolean {
        if (ev.action != MotionEvent.ACTION_DOWN) return true
        if (openSetting != null) {
            closeValueStrip()  // tocar la imagen cierra la tira de valores
            return true
        }
        val r = lastFitRect
        if (r.isEmpty || !r.contains(ev.x, ev.y)) return true
        // Deshacer espejo y rotacion del render para que el punto caiga donde se toco
        var u = (ev.x - r.left) / r.width()
        val w = (ev.y - r.top) / r.height()
        if (mirror) u = 1 - u
        val (fx, fy) = when (rotation) {
            90 -> w to 1 - u
            180 -> 1 - u to 1 - w
            270 -> 1 - w to u
            else -> u to w
        }
        if (!SonyCamera.supports("setTouchAFPosition")) return true
        val xPct = fx * 100.0
        val yPct = fy * 100.0
        focusMarker = ev.x to ev.y
        focusMarkerUntil = SystemClock.elapsedRealtime() + 1500
        apiExecutor.execute {
            try {
                SonyCamera.call(
                    SonyCamera.cameraEndpoint, "setTouchAFPosition",
                    JSONArray().put(xPct).put(yPct),
                )
                touchFocusActive = true
            } catch (e: Exception) {
                touchFocusActive = false
                toast("Focus: ${e.message}")
            }
        }
        v.performClick()
        return true
    }

    private fun toast(message: String?) {
        runOnUiThread { Toast.makeText(this, message ?: "error", Toast.LENGTH_SHORT).show() }
    }

    // -- bucle principal (hilo propio, nunca bloquea la UI) ---------------------------

    private fun monitorLoop(generation: Int) {
        while (isSessionActive(generation)) {
            try {
                if (videoSource == VideoSource.HDMI) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        drawMessage("HDMI capture needs camera permission...")
                        SystemClock.sleep(500)
                        continue
                    }
                    connectControlsForHdmi(generation)
                    streamUvcAndRender(generation)
                    continue
                }
                drawMessage("Waiting for camera WiFi...\n(Connect button in the panel)")
                bindToWifi()
                drawMessage("Connecting to the camera...")
                if (!SonyCamera.locate()) {
                    throw CameraException("Camera not found on this WiFi")
                }
                val url = SonyCamera.startLiveview(SonyCamera.cameraEndpoint)
                if (!isSessionActive(generation)) return
                refreshChips()
                startCameraEventListener(generation)
                streamAndRender(url, generation)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (!isSessionActive(generation)) return
                drawMessage("Error: ${e.message ?: e.javaClass.simpleName}\nRetrying...")
                SystemClock.sleep(2000)
            }
        }
    }

    /** La imagen llega por HDMI, pero los ajustes y eventos siguen usando la
     * API WiFi de Sony. Un fallo WiFi nunca detiene la previsualizacion UVC. */
    private fun connectControlsForHdmi(generation: Int) {
        apiExecutor.execute {
            while (isSessionActive(generation) && videoSource == VideoSource.HDMI) {
                val connected = runCatching {
                    bindToWifi()
                    SonyCamera.locate()
                }.getOrDefault(false)
                if (connected && isSessionActive(generation)) {
                    refreshChips()
                    startCameraEventListener(generation)
                    return@execute
                }
                SystemClock.sleep(3000)
            }
        }
    }

    private fun streamUvcAndRender(generation: Int) {
        val source = UvcVideoSource(applicationContext, uvcPreviewSink)
        source.start()
        if (!registerUvcSource(source, generation)) {
            source.destroy()
            return
        }

        var bitmap: Bitmap? = null
        var fps = 0f
        var fpsWindowStart = SystemClock.elapsedRealtime()
        var fpsWindowCount = 0
        var lastFrameAt = fpsWindowStart
        var exposure: Exposure? = null
        var lastMeterAt = 0L
        val peakingProcessor = FocusPeakingProcessor()
        var peakingOverlay: Bitmap? = null
        var lastPeakingAt = 0L

        try {
            while (isSessionActive(generation) && videoSource == VideoSource.HDMI) {
                val frame = source.awaitFrame(250)
                val now = SystemClock.elapsedRealtime()
                if (frame == null) {
                    val stalled = now - lastFrameAt
                    val alert = if (stalled > 1200) source.status else null
                    drawFrame(bitmap, alert, fps, -1, source.framesDropped, exposure, peakingOverlay)
                    continue
                }

                val previous = bitmap
                bitmap = frame.bitmap
                fpsWindowCount++
                lastFrameAt = now
                if (now - fpsWindowStart >= 1000) {
                    fps = fpsWindowCount * 1000f / (now - fpsWindowStart)
                    fpsWindowStart = now
                    fpsWindowCount = 0
                }

                exposure = when {
                    !meterOn -> null
                    now - lastMeterAt >= 150 -> {
                        lastMeterAt = now
                        computeExposure(bitmap)
                    }
                    else -> exposure
                }
                peakingOverlay = when {
                    peakingColor == PeakingColor.OFF -> null
                    now - lastPeakingAt >= 120 -> {
                        lastPeakingAt = now
                        peakingProcessor.compute(bitmap, peakingColor, peakingSensitivity)
                    }
                    else -> peakingOverlay
                }
                drawFrame(
                    bitmap, null, fps, now - frame.receivedAtMs, source.framesDropped,
                    exposure, peakingOverlay,
                )
                previous?.takeIf { it !== bitmap }?.recycle()
            }
        } finally {
            bitmap?.recycle()
            source.destroy()
            clearUvcSource(source)
        }
    }

    private fun bindToWifi() {
        // Con conexion integrada (boton Conectar) ya tenemos la red objetivo.
        directNetwork?.let {
            connectivity.bindProcessToNetwork(it)
            return
        }

        // Si no, engancharse a la WiFi activa (conexion manual desde Ajustes).
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        connectivity.bindProcessToNetwork(null)

        val latch = CountDownLatch(1)
        var network: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                latch.countDown()
            }
        }
        networkCallback = callback
        connectivity.requestNetwork(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(),
            callback,
        )
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw IllegalStateException("No WiFi: use the Connect button or Settings > WiFi")
        }
        connectivity.bindProcessToNetwork(network)
    }

    private fun streamAndRender(url: String, generation: Int) {
        val stream = LiveviewStream(url)
        stream.start()
        if (!registerStream(stream, generation)) {
            stream.stop()
            return
        }

        // Dos bitmaps reutilizados en alternancia: mientras la GPU consume
        // uno, el siguiente frame se decodifica en el otro.
        var bitmap: Bitmap? = null
        val reuse = arrayOfNulls<Bitmap>(2)
        var reuseIdx = 0
        val decodeOptions = BitmapFactory.Options().apply { inMutable = true }
        var fps = 0f
        var fpsWindowStart = SystemClock.elapsedRealtime()
        var fpsWindowCount = 0
        var lastFrameAt = SystemClock.elapsedRealtime()
        var exposure: Exposure? = null
        var lastMeterAt = 0L
        val peakingProcessor = FocusPeakingProcessor()
        var peakingOverlay: Bitmap? = null
        var lastPeakingAt = 0L

        try {
            while (isSessionActive(generation)) {
                val frame = stream.awaitFrame(250)
                val now = SystemClock.elapsedRealtime()

                if (frame == null) {
                    val stalled = now - lastFrameAt
                    if (capturing) {
                        // El liveview se pausa mientras la camara captura y
                        // procesa la foto: es normal, no es perdida de senal.
                        // Reset del contador para no forzar reconexion tras
                        // una exposicion larga.
                        lastFrameAt = now
                        drawFrame(bitmap, "CAPTURING...", fps, -1, stream.framesDropped, exposure, peakingOverlay)
                        continue
                    }
                    if (stalled > 20_000) throw IllegalStateException("no frames for 20s")
                    if (stalled > 1200) {
                        drawFrame(bitmap, "NO SIGNAL ${stalled / 1000}s (${stream.status})", fps, -1, stream.framesDropped, exposure, peakingOverlay)
                    }
                    continue
                }

                decodeOptions.inBitmap = reuse[reuseIdx]
                val decoded = try {
                    BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, decodeOptions)
                } catch (_: IllegalArgumentException) {
                    decodeOptions.inBitmap = null
                    BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, decodeOptions)
                } ?: continue  // JPEG corrupto ocasional
                reuse[reuseIdx] = decoded
                reuseIdx = (reuseIdx + 1) % 2
                bitmap = decoded

                fpsWindowCount++
                lastFrameAt = now
                if (now - fpsWindowStart >= 1000) {
                    fps = fpsWindowCount * 1000f / (now - fpsWindowStart)
                    fpsWindowStart = now
                    fpsWindowCount = 0
                }

                val ageMs = SystemClock.elapsedRealtime() - frame.receivedAtMs
                if (now - lastWifiSampleAt >= 1000) {
                    lastWifiSampleAt = now
                    sampleWifi()
                }
                // El analisis de exposicion es caro: muestrear ~7 veces/s basta
                exposure = when {
                    !meterOn -> null
                    now - lastMeterAt >= 150 -> {
                        lastMeterAt = now
                        computeExposure(bitmap)
                    }
                    else -> exposure
                }
                peakingOverlay = when {
                    peakingColor == PeakingColor.OFF -> null
                    now - lastPeakingAt >= 120 -> {
                        lastPeakingAt = now
                        peakingProcessor.compute(bitmap, peakingColor, peakingSensitivity)
                    }
                    else -> peakingOverlay
                }
                drawFrame(bitmap, null, fps, ageMs, stream.framesDropped, exposure, peakingOverlay)
            }
        } finally {
            stream.stop()
            clearStream(stream)
        }
    }

    /** Lee la salud del WiFi al que esta unida la app (la red de la camara).
     *  RSSI y velocidad de enlace explican caidas de fps por senal debil. */
    @Suppress("DEPRECATION")
    private fun sampleWifi() {
        runCatching {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            val info = wifi?.connectionInfo ?: return
            // rssi -127 es el valor "invalido" de Android; lo tratamos como desconocido
            wifiRssi = if (info.rssi > -127) info.rssi else 0
            wifiLinkMbps = if (info.linkSpeed > 0) info.linkSpeed else 0
        }
    }

    // -- dibujo -------------------------------------------------------------------

    private fun drawFrame(
        bitmap: Bitmap?, alert: String?, fps: Float, ageMs: Long, dropped: Int,
        exposure: Exposure? = null,
        peakingOverlay: Bitmap? = null,
    ) {
        val holder = surface.holder
        if (holder.surface?.isValid != true) return
        // Canvas por hardware: escalar y rotar el bitmap pasa a hacerlo la
        // GPU, que es lo que evita drops con rotacion o frames pesados
        val canvas: Canvas = (if (Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas() else holder.lockCanvas())
            ?: return
        try {
            canvas.drawColor(Color.BLACK)
            if (bitmap != null) {
                // Area libre: la pantalla menos el hueco del panel de control y cutouts/barras
                val freeLeft = safeInsetLeft.toFloat()
                val freeTop = safeInsetTop.toFloat()
                val freeRight = (canvas.width - maxOf(videoRightInset, safeInsetRight)).toFloat()
                val freeBottom = (canvas.height - maxOf(videoBottomInset, safeInsetBottom)).toFloat()
                val freeW = maxOf(1f, freeRight - freeLeft)
                val freeH = maxOf(1f, freeBottom - freeTop)
                val cx = freeLeft + freeW / 2f
                val cy = freeTop + freeH / 2f
                // Con 90/270 grados el encaje se calcula con los ejes intercambiados
                val swap = rotation % 180 != 0
                val availW = if (swap) freeH else freeW
                val availH = if (swap) freeW else freeH
                val scale = minOf(availW / bitmap.width, availH / bitmap.height)
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                val dst = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                // Caja ocupada en pantalla (para cuadricula y enfoque tactil)
                lastFitRect = if (swap) RectF(cx - h / 2, cy - w / 2, cx + h / 2, cy + w / 2) else dst
                // Mapear explicitamente las cuatro esquinas evita que el orden
                // interno de scale/rotate cambie el eje del espejo. El reflejo
                // se aplica al final sobre X de pantalla en los cuatro angulos.
                frameSrcCorners[0] = 0f;                  frameSrcCorners[1] = 0f
                frameSrcCorners[2] = bitmap.width.toFloat(); frameSrcCorners[3] = 0f
                frameSrcCorners[4] = bitmap.width.toFloat(); frameSrcCorners[5] = bitmap.height.toFloat()
                frameSrcCorners[6] = 0f;                  frameSrcCorners[7] = bitmap.height.toFloat()
                val l = lastFitRect.left
                val t = lastFitRect.top
                val r = lastFitRect.right
                val b = lastFitRect.bottom
                when (rotation) {
                    90 -> frameDstCorners.apply {
                        this[0] = r; this[1] = t; this[2] = r; this[3] = b
                        this[4] = l; this[5] = b; this[6] = l; this[7] = t
                    }
                    180 -> frameDstCorners.apply {
                        this[0] = r; this[1] = b; this[2] = l; this[3] = b
                        this[4] = l; this[5] = t; this[6] = r; this[7] = t
                    }
                    270 -> frameDstCorners.apply {
                        this[0] = l; this[1] = b; this[2] = l; this[3] = t
                        this[4] = r; this[5] = t; this[6] = r; this[7] = b
                    }
                    else -> frameDstCorners.apply {
                        this[0] = l; this[1] = t; this[2] = r; this[3] = t
                        this[4] = r; this[5] = b; this[6] = l; this[7] = b
                    }
                }
                if (mirror) {
                    for (i in frameDstCorners.indices step 2) {
                        frameDstCorners[i] = 2f * cx - frameDstCorners[i]
                    }
                }
                frameMatrix.reset()
                frameMatrix.setPolyToPoly(frameSrcCorners, 0, frameDstCorners, 0, 4)
                canvas.drawBitmap(bitmap, frameMatrix, bitmapPaint)
                peakingOverlay?.let { canvas.drawBitmap(it, frameMatrix, peakingPaint) }
                drawGrid(canvas, lastFitRect)
                drawFocusMarker(canvas)
                if (exposure != null) drawMeter(canvas, exposure)
            }
            val hudX = 24f + safeInsetLeft
            var hudY = 48f + safeInsetTop
            if (hudOn) {
                val hud = if (ageMs >= 0) {
                    "%.1f fps | age %d ms | drops %d".format(fps, ageMs, dropped)
                } else {
                    "%.1f fps | drops %d".format(fps, dropped)
                }
                canvas.drawText(hud, hudX, hudY, hudShadow)
                canvas.drawText(hud, hudX, hudY, hudPaint)
                // Segunda linea: salud del WiFi de la camara (si hay lectura)
                if (wifiRssi != 0) {
                    val quality = when {
                        wifiRssi >= -60 -> "good"
                        wifiRssi >= -70 -> "fair"
                        else -> "weak"
                    }
                    val link = if (wifiLinkMbps > 0) " | link %d Mbps".format(wifiLinkMbps) else ""
                    val wifiLine = "wifi %d dBm (%s)%s".format(wifiRssi, quality, link)
                    wifiPaint.color = when {
                        wifiRssi >= -60 -> Color.rgb(0, 255, 128)   // verde
                        wifiRssi >= -70 -> Color.rgb(255, 200, 0)   // ambar
                        else -> Color.rgb(255, 80, 80)              // rojo
                    }
                    canvas.drawText(wifiLine, hudX, hudY + 40f, hudShadow)
                    canvas.drawText(wifiLine, hudX, hudY + 40f, wifiPaint)
                    hudY += 40f  // el alert/zoom bajan para no solaparse
                }
            }
            if (alert != null) canvas.drawText(alert, hudX, hudY + 56f, alertPaint)
            zoomText?.let {
                if (SystemClock.elapsedRealtime() < zoomTextUntil) {
                    canvas.drawText(it, hudX, hudY + 100f, hudShadow)
                    canvas.drawText(it, hudX, hudY + 100f, hudPaint)
                } else {
                    zoomText = null
                }
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawGrid(canvas: Canvas, r: RectF) {
        when (grid) {
            Grid.OFF -> Unit
            Grid.TERCIOS, Grid.TERCIOS_DIAG -> {
                for (i in 1..2) {
                    val x = r.left + r.width() * i / 3f
                    val y = r.top + r.height() * i / 3f
                    canvas.drawLine(x, r.top, x, r.bottom, gridPaint)
                    canvas.drawLine(r.left, y, r.right, y, gridPaint)
                }
                if (grid == Grid.TERCIOS_DIAG) {
                    canvas.drawLine(r.left, r.top, r.right, r.bottom, gridPaint)
                    canvas.drawLine(r.left, r.bottom, r.right, r.top, gridPaint)
                }
            }
            Grid.CRUZ -> {
                val cx = r.centerX()
                val cy = r.centerY()
                canvas.drawLine(cx, r.top, cx, r.bottom, gridPaint)
                canvas.drawLine(r.left, cy, r.right, cy, gridPaint)
                canvas.drawCircle(cx, cy, minOf(r.width(), r.height()) * 0.04f, gridPaint)
            }
        }
    }

    private fun gridLabel() = when (grid) {
        Grid.OFF -> "off"
        Grid.TERCIOS -> "3x3"
        Grid.TERCIOS_DIAG -> "3x3+X"
        Grid.CRUZ -> "cross"
    }

    private fun cycleGrid() {
        grid = Grid.entries[(grid.ordinal + 1) % Grid.entries.size]
        getPreferences(MODE_PRIVATE).edit().putInt("grid", grid.ordinal).apply()
        btnGrid.text = "Grid: ${gridLabel()}"
        highlightValue(btnGrid, grid != Grid.OFF)
    }

    private fun drawFocusMarker(canvas: Canvas) {
        val marker = focusMarker ?: return
        if (SystemClock.elapsedRealtime() > focusMarkerUntil) {
            focusMarker = null
            return
        }
        canvas.drawRect(
            marker.first - 40, marker.second - 40,
            marker.first + 40, marker.second + 40,
            focusPaint,
        )
    }

    // -- medidor de exposicion (calculado del liveview; la API de Sony no
    //    expone el fotometro interno de la camara) --------------------------------

    private class Exposure(val hist: IntArray, val evOffset: Float, val clipShadows: Float, val clipHighlights: Float)

    private val linearLut = FloatArray(256) { Math.pow(it / 255.0, 2.2).toFloat() }
    private var sampleRow = IntArray(0)

    private fun computeExposure(bitmap: Bitmap): Exposure {
        val w = bitmap.width
        val h = bitmap.height
        if (sampleRow.size != w) sampleRow = IntArray(w)
        val hist = IntArray(64)
        var sumLinear = 0f
        var n = 0
        var shadows = 0
        var highlights = 0
        val stepY = maxOf(1, h / 60)
        val stepX = maxOf(1, w / 120)
        var y = 0
        while (y < h) {
            bitmap.getPixels(sampleRow, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = sampleRow[x]
                // Luma BT.709 en aritmetica entera
                val luma = ((c shr 16 and 0xFF) * 54 + (c shr 8 and 0xFF) * 183 + (c and 0xFF) * 19) shr 8
                hist[luma shr 2]++
                sumLinear += linearLut[luma]
                if (luma <= 4) shadows++ else if (luma >= 251) highlights++
                n++
                x += stepX
            }
            y += stepY
        }
        val meanLinear = sumLinear / n
        // Desviacion respecto al gris medio (18% reflectancia)
        val ev = (Math.log((meanLinear / 0.18f).toDouble()) / Math.log(2.0)).toFloat()
        return Exposure(hist, ev, shadows * 100f / n, highlights * 100f / n)
    }

    private val meterBgPaint = Paint().apply { color = Color.argb(130, 0, 0, 0) }
    private val meterBarPaint = Paint().apply { color = Color.argb(220, 255, 255, 255) }
    private val meterTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 28f
    }
    private val meterClipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 80, 80); textSize = 28f
    }

    private fun drawMeter(canvas: Canvas, exposure: Exposure) {
        val w = 340f
        val h = 130f
        val left = 24f + safeInsetLeft
        // Siempre dentro del area de video, por encima del panel y barra inferior
        val bottom = canvas.height - maxOf(videoBottomInset, safeInsetBottom) - 16f
        val top = bottom - h
        canvas.drawRect(left, top, left + w, bottom, meterBgPaint)

        val histTop = top + 10f
        val histBottom = bottom - 42f
        val histHeight = histBottom - histTop
        val maxCount = exposure.hist.max().coerceAtLeast(1)
        val barW = (w - 20f) / exposure.hist.size
        exposure.hist.forEachIndexed { i, count ->
            val x = left + 10f + i * barW
            val barH = histHeight * count / maxCount
            canvas.drawRect(x, histBottom - barH, x + barW - 1f, histBottom, meterBarPaint)
        }

        val evText = String.format("%+.1f EV", exposure.evOffset)
        canvas.drawText(evText, left + 10f, bottom - 12f, meterTextPaint)
        val clipText = String.format(
            "low %.0f%%  high %.0f%%",
            exposure.clipShadows, exposure.clipHighlights,
        )
        val clipPaint = if (exposure.clipShadows > 20 || exposure.clipHighlights > 5) meterClipPaint else meterTextPaint
        canvas.drawText(clipText, left + 130f, bottom - 12f, clipPaint)
    }

    private fun drawMessage(message: String) {
        val holder = surface.holder
        if (holder.surface?.isValid != true) return
        // Mismo tipo de canvas que drawFrame: mezclar software y hardware
        // sobre la misma surface da problemas en algunos dispositivos
        val canvas = (if (Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas() else holder.lockCanvas())
            ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val baseX = 24f + safeInsetLeft
            val baseY = 64f + safeInsetTop
            message.split("\n").forEachIndexed { i, line ->
                canvas.drawText(line, baseX, baseY + i * 52f, alertPaint)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

}
