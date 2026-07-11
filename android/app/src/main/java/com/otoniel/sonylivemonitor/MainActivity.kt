package com.otoniel.sonylivemonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private enum class Grid { OFF, TERCIOS, TERCIOS_DIAG, CRUZ }

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
        Setting("Flash", "getAvailableFlashMode", "setFlashMode") { "Flash $it" },
        Setting("Timer", "getAvailableSelfTimer", "setSelfTimer", numeric = true) { "T:${it}s" },
    )

    private lateinit var surface: SurfaceView
    private lateinit var connectivity: ConnectivityManager
    private val apiExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var active = false
    private var worker: Thread? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var directNetwork: Network? = null
    private var directCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var grid = Grid.OFF
    @Volatile private var meterOn = false
    @Volatile private var rotation = 0  // 0/90/180/270 grados de rotacion del render
    @Volatile private var capturing = false
    @Volatile private var lastFitRect = RectF()
    @Volatile private var focusMarker: Pair<Float, Float>? = null
    @Volatile private var focusMarkerUntil = 0L

    // Zonas ocupadas por las barras de botones (medidas tras el layout),
    // para que HUD y medidor no se solapen con ellas en ninguna orientacion
    @Volatile private var topBarBottom = 0
    @Volatile private var topBarLeft = Int.MAX_VALUE

    // Hueco que el panel de control resta al area de video
    @Volatile private var videoBottomInset = 0
    @Volatile private var videoRightInset = 0

    private lateinit var panel: LinearLayout
    private lateinit var panelContainer: ScrollView
    private lateinit var panelFrame: FrameLayout
    private lateinit var controlGrid: GridLayout
    private val chipButtons = mutableListOf<Button>()

    // Feedback de zoom (la API da porcentaje; los mm son estimados 16-50)
    @Volatile private var zoomText: String? = null
    @Volatile private var zoomTextUntil = 0L

    private lateinit var btnConnect: Button
    private lateinit var btnGrid: Button
    private lateinit var btnMeter: Button
    private lateinit var btnHud: Button
    private lateinit var btnShutter: Button
    private val chips = mutableMapOf<Setting, Button>()
    private lateinit var chipEv: Button
    private lateinit var chipWb: Button
    private lateinit var chipShootMode: Button

    @Volatile private var hudOn = true
    @Volatile private var shootMode = "still"
    @Volatile private var movieRecording = false
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

    // -- ciclo de vida -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        connectivity = getSystemService(ConnectivityManager::class.java)

        grid = Grid.entries[getPreferences(MODE_PRIVATE).getInt("grid", 0)]
        meterOn = getPreferences(MODE_PRIVATE).getBoolean("meter", false)
        rotation = getPreferences(MODE_PRIVATE).getInt("rot", 0)
        hudOn = getPreferences(MODE_PRIVATE).getBoolean("hud", true)

        val root = FrameLayout(this)
        surface = SurfaceView(this)
        surface.setOnTouchListener { v, ev -> onSurfaceTouch(v, ev) }
        root.addView(surface)
        root.addView(buildBottomBar())
        root.addView(buildShutterButton())
        setContentView(root)
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        active = true
        worker = Thread(::monitorLoop, "monitor").also { it.start() }
    }

    override fun onStop() {
        super.onStop()
        active = false
        worker?.interrupt()
        worker = null
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        // Nota: NO liberamos directCallback aqui — soltarlo desconectaria la
        // WiFi de la camara cada vez que la app pasa a segundo plano.
        connectivity.bindProcessToNetwork(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        directCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        directCallback = null
        apiExecutor.shutdownNow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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
     * Disparador flotante y arrastrable: toque corto dispara, arrastrar lo
     * recoloca. La posicion se recuerda por orientacion (vertical/horizontal).
     */
    private fun buildShutterButton(): View {
        val frame = FrameLayout(this)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        )
        val b = Button(this).apply {
            text = "●"
            textSize = 30f
            alpha = 0.85f
            setTextColor(Color.rgb(255, 80, 80))
            setOnClickListener { takePicture() }
        }
        frame.addView(b, lp)
        btnShutter = b

        fun orientKey() = if (frame.height > frame.width) "port" else "land"

        fun place() {
            if (frame.width == 0) return
            val prefs = getPreferences(MODE_PRIVATE)
            val defX = frame.width - b.width - 16
            val defY = (frame.height - b.height) / 2
            lp.leftMargin = prefs.getInt("shutter_${orientKey()}_x", defX)
                .coerceIn(0, maxOf(0, frame.width - b.width))
            lp.topMargin = prefs.getInt("shutter_${orientKey()}_y", defY)
                .coerceIn(0, maxOf(0, frame.height - b.height))
            b.layoutParams = lp
        }

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
                        lp.leftMargin = (startLeft + dx).toInt().coerceIn(0, maxOf(0, frame.width - v.width))
                        lp.topMargin = (startTop + dy).toInt().coerceIn(0, maxOf(0, frame.height - v.height))
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
     * Panel de control fijo con TODOS los ajustes visibles a la vez (como la
     * app oficial): rejilla a lo ancho bajo el monitor en vertical, columna
     * lateral en horizontal. El area de video se encoge para no solaparse.
     */
    private fun buildBottomBar(): View {
        valueRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        valueStrip = HorizontalScrollView(this).apply {
            addView(valueRow)
            visibility = View.GONE
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }

        fun chip(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            textSize = 13f
            alpha = 0.9f
            isAllCaps = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(4, 4, 4, 4)
            setOnClickListener { onClick() }
            chipButtons.add(this)
        }

        settings.forEach { s -> chips[s] = chip(s.title) { toggleValueStrip(s) } }
        chipEv = chip("EV") { toggleEvStrip() }
        chipWb = chip("WB") { toggleWbStrip() }
        chipShootMode = chip("Mode: Photo") { toggleModeStrip() }

        // Zoom motorizado: mantener pulsado
        fun zoomChip(label: String, direction: String) = chip(label) {}.apply {
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
        zoomChip("Z− (W)", "out")
        zoomChip("Z+ (T)", "in")

        // Botones de la app, en el mismo panel para que nada flote sobre
        // el monitor. Conectar: un toque conecta con lo guardado; mantener
        // pulsado edita las credenciales.
        btnConnect = chip("Connect") {
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
        btnRot = chip("Rot: ${rotation}°") {
            rotation = (rotation + 90) % 360
            getPreferences(MODE_PRIVATE).edit().putInt("rot", rotation).apply()
            btnRot?.text = "Rot: ${rotation}°"
        }
        btnGrid = chip("Grid: ${gridLabel()}") { cycleGrid() }
        btnMeter = chip("Meter: ${if (meterOn) "on" else "off"}") {
            meterOn = !meterOn
            getPreferences(MODE_PRIVATE).edit().putBoolean("meter", meterOn).apply()
            btnMeter.text = "Meter: ${if (meterOn) "on" else "off"}"
        }
        btnHud = chip("HUD: ${if (hudOn) "on" else "off"}") {
            hudOn = !hudOn
            getPreferences(MODE_PRIVATE).edit().putBoolean("hud", hudOn).apply()
            btnHud.text = "HUD: ${if (hudOn) "on" else "off"}"
        }

        controlGrid = GridLayout(this)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            addView(valueStrip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL })
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
        controlGrid.removeAllViews()
        controlGrid.columnCount = if (portrait) 4 else 2
        chipButtons.forEach { b ->
            (b.parent as? android.view.ViewGroup)?.removeView(b)
            controlGrid.addView(b, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                // Altura fija: un texto largo no debe descuadrar su fila
                height = (44 * resources.displayMetrics.density).toInt()
                setMargins(2, 2, 2, 2)
            })
        }
        val density = resources.displayMetrics.density
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

    /** El video se encaja en el hueco libre que deja el panel. */
    private fun updateVideoInsets() {
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        if (portrait) {
            videoBottomInset = if (panelContainer.height > 0) panelFrame.height - panelContainer.top else 0
            videoRightInset = 0
        } else {
            videoBottomInset = 0
            videoRightInset = if (panelContainer.width > 0) panelFrame.width - panelContainer.left else 0
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
        if (openSetting === setting) {
            closeValueStrip()
            return
        }
        setStripOwner(setting, chips[setting])
        apiExecutor.execute {
            try {
                val r = SonyCamera.call(SonyCamera.DEFAULT_ENDPOINT, setting.getMethod)
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
                // Estilo pildora: distingue el submenu de los botones padre
                background = GradientDrawable().apply {
                    cornerRadius = 32f
                    setColor(Color.argb(235, 38, 42, 50))
                    setStroke(2, Color.argb(130, 0, 255, 128))
                }
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
        button.setTextColor(if (selected) Color.rgb(0, 255, 128) else Color.WHITE)
        button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        button.alpha = if (selected) 1f else 0.85f
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
                val r = SonyCamera.call(SonyCamera.DEFAULT_ENDPOINT, "getAvailableExposureCompensation")
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
                val r = SonyCamera.call(SonyCamera.DEFAULT_ENDPOINT, "getAvailableWhiteBalance")
                val current = r.getJSONObject(0).getString("whiteBalanceMode")
                val cand = r.getJSONArray(1)
                val modes = List(cand.length()) { cand.getJSONObject(it).getString("whiteBalanceMode") }
                runOnUiThread {
                    showValueStrip("WB", modes, current) { mode, view ->
                        apiExecutor.execute {
                            try {
                                // Para "Color Temperature" hace falta un kelvin: 5500 por defecto
                                val temp = mode == "Color Temperature"
                                SonyCamera.call(
                                    SonyCamera.DEFAULT_ENDPOINT, "setWhiteBalance",
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
                val r = SonyCamera.call(SonyCamera.DEFAULT_ENDPOINT, "getAvailableShootMode")
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
                    SonyCamera.DEFAULT_ENDPOINT, "actZoom",
                    JSONArray().put(direction).put(movement),
                )
            }.onFailure {
                if (movement == "start") toast("Zoom: requires a power zoom lens")
            }
        }
        pollZoomPosition()
        if (movement == "stop") {
            // El motor sigue frenando un instante: leer tambien la posicion final
            surface.postDelayed({ pollZoomPosition() }, 600)
        }
    }

    /**
     * Lee la posicion de zoom via getEvent. La API solo da porcentaje 0-100;
     * los mm se estiman mapeando linealmente al rango 16-50 del PZ del kit.
     */
    private fun pollZoomPosition() {
        apiExecutor.execute {
            runCatching {
                val r = SonyCamera.call(SonyCamera.DEFAULT_ENDPOINT, "getEvent", JSONArray().put(false))
                for (i in 0 until r.length()) {
                    val o = r.optJSONObject(i) ?: continue
                    if (o.has("zoomPosition")) {
                        val pct = o.getInt("zoomPosition")
                        // Progresion geometrica: los zoom motorizados avanzan
                        // a ratio constante, no a mm constantes (16-50 PZ)
                        val mm = 16.0 * Math.pow(50.0 / 16.0, pct / 100.0)
                        zoomText = "Zoom %d%%  ~%.0f mm".format(pct, mm)
                        zoomTextUntil = SystemClock.elapsedRealtime() + 2500
                        break
                    }
                }
            }
        }
    }

    private fun applySetting(setMethod: String, value: Any, onOk: () -> Unit) {
        apiExecutor.execute {
            try {
                SonyCamera.call(SonyCamera.DEFAULT_ENDPOINT, setMethod, JSONArray().put(value))
                runOnUiThread(onOk)
            } catch (e: Exception) {
                toast("Error: ${e.message}")
            }
        }
    }

    /** Actualiza las etiquetas de los chips con los valores actuales de la camara. */
    private fun refreshChips() {
        apiExecutor.execute {
            // getEvent es la instantanea real del estado. Algunos modelos no
            // rellenan de forma fiable el primer elemento de getAvailable*
            // hasta que el ajuste se cambia desde el remoto.
            val eventKeys = mapOf(
                "ISO" to "currentIsoSpeedRate",
                "Shutter" to "currentShutterSpeed",
                "Aperture" to "currentFNumber",
                "Focus" to "currentFocusMode",
                "Flash" to "currentFlashMode",
                "Timer" to "currentSelfTimer",
            )
            val currentByTitle = mutableMapOf<String, String>()
            settings.forEach { setting ->
                runCatching {
                    currentByTitle[setting.title] = SonyCamera.call(
                        SonyCamera.DEFAULT_ENDPOINT, setting.currentMethod,
                    ).get(0).toString()
                }
            }
            runCatching {
                val events = SonyCamera.call(
                    SonyCamera.DEFAULT_ENDPOINT, "getEvent", JSONArray().put(false), version = "1.1",
                )
                for (i in 0 until events.length()) {
                    val event = events.optJSONObject(i) ?: continue
                    eventKeys.forEach { (title, key) ->
                        if (!currentByTitle.containsKey(title) && event.has(key) && !event.isNull(key)) {
                            currentByTitle[title] = event.get(key).toString()
                        }
                    }
                }
            }
            settings.forEach { s ->
                if (!currentByTitle.containsKey(s.title)) {
                    runCatching {
                        currentByTitle[s.title] = SonyCamera.call(
                            SonyCamera.DEFAULT_ENDPOINT, s.getMethod,
                        ).get(0).toString()
                    }
                }
            }
            runOnUiThread {
                settings.forEach { s ->
                    currentByTitle[s.title]?.let { chips[s]?.text = s.chipLabel(it) }
                }
            }
            runCatching {
                val r = SonyCamera.call(SonyCamera.DEFAULT_ENDPOINT, "getAvailableExposureCompensation")
                val stepEv = if (r.getInt(3) == 2) 0.5 else 1.0 / 3.0
                val label = String.format("%+.1f EV", r.getInt(0) * stepEv)
                runOnUiThread { chipEv.text = label }
            }
            runCatching {
                val mode = SonyCamera.call(SonyCamera.DEFAULT_ENDPOINT, "getAvailableWhiteBalance")
                    .getJSONObject(0).getString("whiteBalanceMode")
                runOnUiThread { chipWb.text = "WB ${mode.removeSuffix(" WB")}" }
            }
            runCatching {
                val mode = SonyCamera.call(SonyCamera.DEFAULT_ENDPOINT, "getAvailableShootMode").getString(0)
                shootMode = mode
                runOnUiThread { chipShootMode.text = if (mode == "movie") "Mode: Video" else "Mode: Photo" }
            }
        }
    }

    private fun takePicture() {
        if (shootMode == "movie") {
            // En modo video el disparador inicia/detiene la grabacion
            apiExecutor.execute {
                try {
                    if (movieRecording) {
                        SonyCamera.call(SonyCamera.DEFAULT_ENDPOINT, "stopMovieRec")
                        movieRecording = false
                        toast("Recording stopped")
                    } else {
                        SonyCamera.call(SonyCamera.DEFAULT_ENDPOINT, "startMovieRec")
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
        apiExecutor.execute {
            try {
                SonyCamera.call(SonyCamera.DEFAULT_ENDPOINT, "actTakePicture")
                toast("Photo taken")
                refreshChips()  // los valores pueden cambiar tras el disparo (p.ej. ISO auto)
            } catch (e: Exception) {
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

    /**
     * Conexion directa y silenciosa con la API legacy de WifiManager
     * (posible porque la app tiene targetSdk 28): anade/activa la red de la
     * camara sin pasar por ningun dialogo ni menu del sistema.
     */
    @Suppress("DEPRECATION")
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

        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        if (!wifi.isWifiEnabled) {
            wifi.isWifiEnabled = true  // legacy: permitido con targetSdk 28
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
                    val ok = runCatching {
                        SonyCamera.call(SonyCamera.DEFAULT_ENDPOINT, "getVersions")
                    }.isSuccess
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
        // Deshacer la rotacion del render para que el punto caiga donde se toco
        val u = (ev.x - r.left) / r.width()
        val w = (ev.y - r.top) / r.height()
        val (fx, fy) = when (rotation) {
            90 -> w to 1 - u
            180 -> 1 - u to 1 - w
            270 -> 1 - w to u
            else -> u to w
        }
        val xPct = fx * 100.0
        val yPct = fy * 100.0
        focusMarker = ev.x to ev.y
        focusMarkerUntil = SystemClock.elapsedRealtime() + 1500
        apiExecutor.execute {
            try {
                SonyCamera.call(
                    SonyCamera.DEFAULT_ENDPOINT, "setTouchAFPosition",
                    JSONArray().put(xPct).put(yPct),
                )
            } catch (e: Exception) {
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

    private fun monitorLoop() {
        while (active) {
            try {
                drawMessage("Waiting for camera WiFi...\n(Connect button in the panel)")
                bindToWifi()
                drawMessage("Connecting to the camera...")
                val url = SonyCamera.startLiveview(SonyCamera.DEFAULT_ENDPOINT)
                refreshChips()
                streamAndRender(url)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (!active) return
                drawMessage("Error: ${e.message ?: e.javaClass.simpleName}\nRetrying...")
                SystemClock.sleep(2000)
            }
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

    private fun streamAndRender(url: String) {
        val stream = LiveviewStream(url)
        stream.start()

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

        try {
            while (active) {
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
                        drawFrame(bitmap, "CAPTURING...", fps, -1, stream.framesDropped, exposure)
                        continue
                    }
                    if (stalled > 20_000) throw IllegalStateException("no frames for 20s")
                    if (stalled > 1200) {
                        drawFrame(bitmap, "NO SIGNAL ${stalled / 1000}s (${stream.status})", fps, -1, stream.framesDropped, exposure)
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
                // El analisis de exposicion es caro: muestrear ~7 veces/s basta
                exposure = when {
                    !meterOn -> null
                    now - lastMeterAt >= 150 -> {
                        lastMeterAt = now
                        computeExposure(bitmap)
                    }
                    else -> exposure
                }
                drawFrame(bitmap, null, fps, ageMs, stream.framesDropped, exposure)
            }
        } finally {
            stream.stop()
        }
    }

    // -- dibujo -------------------------------------------------------------------

    private fun drawFrame(
        bitmap: Bitmap?, alert: String?, fps: Float, ageMs: Long, dropped: Int,
        exposure: Exposure? = null,
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
                // Area libre: la pantalla menos el hueco del panel de control
                val freeW = (canvas.width - videoRightInset).toFloat()
                val freeH = (canvas.height - videoBottomInset).toFloat()
                val cx = freeW / 2f
                val cy = freeH / 2f
                // Con 90/270 grados el encaje se calcula con los ejes intercambiados
                val swap = rotation % 180 != 0
                val availW = if (swap) freeH else freeW
                val availH = if (swap) freeW else freeH
                val scale = minOf(availW / bitmap.width, availH / bitmap.height)
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                val dst = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                if (rotation != 0) {
                    canvas.save()
                    canvas.rotate(rotation.toFloat(), cx, cy)
                    canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
                    canvas.restore()
                } else {
                    canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
                }
                // Caja ocupada en pantalla (para cuadricula y enfoque tactil)
                lastFitRect = if (swap) RectF(cx - h / 2, cy - w / 2, cx + h / 2, cy + w / 2) else dst
                drawGrid(canvas, lastFitRect)
                drawFocusMarker(canvas)
                if (exposure != null) drawMeter(canvas, exposure)
            }
            var hudY = 48f
            if (hudOn) {
                val hud = if (ageMs >= 0) {
                    "%.1f fps | age %d ms | drops %d".format(fps, ageMs, dropped)
                } else {
                    "%.1f fps | drops %d".format(fps, dropped)
                }
                // Si la barra superior invade la esquina del HUD (movil en
                // vertical), el HUD baja hasta quedar por debajo de ella
                val hudWidth = hudPaint.measureText(hud) + 48f
                hudY = if (topBarLeft < hudWidth) topBarBottom + 44f else 48f
                canvas.drawText(hud, 24f, hudY, hudShadow)
                canvas.drawText(hud, 24f, hudY, hudPaint)
            } else if (alert != null) {
                hudY = if (topBarLeft < alertPaint.measureText(alert) + 48f) topBarBottom + 44f else 48f
            }
            if (alert != null) canvas.drawText(alert, 24f, hudY + 56f, alertPaint)
            zoomText?.let {
                if (SystemClock.elapsedRealtime() < zoomTextUntil) {
                    canvas.drawText(it, 24f, hudY + 100f, hudShadow)
                    canvas.drawText(it, 24f, hudY + 100f, hudPaint)
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
        val left = 24f
        // Siempre dentro del area de video, por encima del panel
        val bottom = canvas.height - videoBottomInset - 16f
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
            val baseY = maxOf(64f, topBarBottom + 52f)
            message.split("\n").forEachIndexed { i, line ->
                canvas.drawText(line, 24f, baseY + i * 52f, alertPaint)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

}
