package com.otoniel.sonylivemonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Galeria de tarjeta mediante avContent habilitado en Smart Remote parcheado. */
class CameraGalleryActivity : Activity() {
    private data class Original(val fileName: String, val kind: String, val url: String)
    private data class CameraImage(
        val created: String,
        val thumbnail: String,
        val preview: String,
        val originals: List<Original>,
    )
    private data class Page(val images: List<CameraImage>, val received: Int)
    private data class DownloadJob(val image: CameraImage, val original: Original)

    companion object {
        private const val PAGE_SIZE = 15
        private const val FOLDER_REQUEST = 6102
    }

    private val apiExecutor = Executors.newSingleThreadExecutor()
    // Una sola miniatura cada vez: el servidor de la a6000 es muy limitado.
    private val thumbnailExecutor = Executors.newSingleThreadExecutor()
    // Preview separado: tocar una foto nunca espera a la cola de miniaturas.
    private val previewExecutor = Executors.newSingleThreadExecutor()
    private val downloadExecutor = Executors.newSingleThreadExecutor()

    private lateinit var grid: GridLayout
    private lateinit var status: TextView
    private lateinit var busy: ProgressBar
    private lateinit var more: Button
    private lateinit var selectButton: Button
    private lateinit var downloadButton: Button
    private lateinit var folderButton: Button
    private var downloadDialog: Dialog? = null
    private lateinit var downloadImage: ImageView
    private lateinit var downloadTitle: TextView
    private lateinit var downloadDetail: TextView
    private lateinit var downloadProgress: ProgressBar

    private val images = mutableListOf<CameraImage>()
    private val selected = linkedSetOf<CameraImage>()
    private val selectionMarks = mutableMapOf<CameraImage, TextView>()
    private val thumbnailCache = ConcurrentHashMap<String, Bitmap>()
    private val activeDownloads = AtomicInteger(0)
    @Volatile private var closing = false
    @Volatile private var loadingPage = false
    @Volatile private var nextIndex = 0
    private var selectMode = false
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        updateFolderLabel()
        loadCard()
    }

    // Mismo lock que MainActivity: sin el, el ahorro de energia WiFi
    // (Samsung sobre todo) hunde la velocidad de miniaturas y descargas.
    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        if (wifiLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "sonylivemonitor:gallery").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    override fun onStop() {
        super.onStop()
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // La actividad no se recrea al girar (configChanges en el manifest): la camara
    // sigue en Contents Transfer y solo hay que reajustar el tamano de las miniaturas.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val size = resources.displayMetrics.widthPixels / grid.columnCount - 16
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            child.layoutParams = (child.layoutParams as GridLayout.LayoutParams).apply {
                width = size; height = size
            }
        }
    }

    private fun buildUi(): View {
        status = TextView(this).apply {
            text = "Opening camera card..."; textSize = 16f
            setTextColor(Color.WHITE); setPadding(16, 12, 16, 12)
        }
        busy = ProgressBar(this)
        selectButton = Button(this).apply { text = "Select"; setOnClickListener { toggleSelectMode() } }
        downloadButton = Button(this).apply {
            text = "Download"; isEnabled = false; setOnClickListener { chooseSelectedFormat() }
        }
        folderButton = Button(this).apply { text = "Folder"; setOnClickListener { showFolderOptions() } }
        more = Button(this).apply {
            text = "Load $PAGE_SIZE more"; visibility = View.GONE
            setOnClickListener { loadNextPage() }
        }
        grid = GridLayout(this).apply {
            columnCount = if (resources.configuration.smallestScreenWidthDp >= 600) 4 else 3
            alignmentMode = GridLayout.ALIGN_BOUNDS; useDefaultMargins = true
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(this@CameraGalleryActivity).apply {
                text = "Back"; setOnClickListener { closeCard() }
            })
            addView(status, LinearLayout.LayoutParams(0, -2, 1f)); addView(busy)
        }
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(selectButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(downloadButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(folderButton, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
            addView(top); addView(actions); addView(more, LinearLayout.LayoutParams(-1, -2))
            addView(ScrollView(this@CameraGalleryActivity).apply { addView(grid) },
                LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    /** Dialogo modal centrado: bloquea la pantalla hasta terminar las descargas. */
    private fun showDownloadDialog() {
        if (downloadDialog?.isShowing == true) return
        downloadImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.DKGRAY)
        }
        downloadTitle = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; text = "Preparing download..."
        }
        downloadDetail = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 13f }
        downloadProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = true
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(18, 4, 4, 4)
            addView(downloadTitle); addView(downloadDetail); addView(downloadProgress,
                LinearLayout.LayoutParams(-1, 20))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 26, 28, 26)
            background = GradientDrawable().apply { setColor(Color.rgb(30, 30, 30)); cornerRadius = 28f }
            addView(downloadImage, LinearLayout.LayoutParams(100, 100))
            addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        }
        downloadDialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(card); setCancelable(false)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(resources.displayMetrics.widthPixels * 9 / 10, -2)
            show()
        }
    }

    private fun hideDownloadDialog() {
        downloadDialog?.let { if (it.isShowing && !isFinishing) it.dismiss() }
        downloadDialog = null
    }

    private fun loadCard() = apiExecutor.execute {
        try {
            enterContentsTransfer()
            runOnUiThread { loadNextPage() }
        } catch (e: Exception) {
            runOnUiThread {
                busy.visibility = View.GONE; status.text = "Camera card unavailable"
                toast(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun enterContentsTransfer() {
        runCatching { SonyCamera.call(SonyCamera.cameraEndpoint, "stopLiveview") }
        var error: Exception? = null
        repeat(15) {
            try {
                SonyCamera.call(SonyCamera.cameraEndpoint, "setCameraFunction",
                    JSONArray().put("Contents Transfer"))
                return
            } catch (e: Exception) { error = e; Thread.sleep(400) }
        }
        throw error ?: CameraException("Could not enable Contents Transfer")
    }

    private fun loadNextPage() {
        if (loadingPage) return
        loadingPage = true; more.isEnabled = false; busy.visibility = View.VISIBLE
        status.text = "Reading camera index..."
        apiExecutor.execute {
            try {
                val page = readPage(nextIndex); nextIndex += page.received
                runOnUiThread {
                    // Los placeholders son interactivos inmediatamente. Las
                    // miniaturas se completan después, una a una.
                    page.images.forEach(::addThumbnail)
                    busy.visibility = View.GONE
                    status.text = if (nextIndex == 0) "No photos found" else "$nextIndex entries ready"
                    more.visibility = if (page.received == PAGE_SIZE) View.VISIBLE else View.GONE
                    more.isEnabled = true; loadingPage = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy.visibility = View.GONE; more.isEnabled = true; loadingPage = false
                    toast("Page failed: ${e.message}")
                }
            }
        }
    }

    private fun readPage(start: Int): Page {
        val request = JSONObject().put("uri", "storage:memoryCard1")
            .put("stIdx", start).put("cnt", PAGE_SIZE)
            .put("view", "flat").put("sort", "descending")
        val result = SonyCamera.call(SonyCamera.avContentEndpoint, "getContentList",
            JSONArray().put(request), "1.3")
        val page = result.optJSONArray(0) ?: JSONArray()
        return Page(parsePage(page), page.length())
    }

    private fun parsePage(array: JSONArray): List<CameraImage> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("contentKind") != "still") continue
            val content = item.optJSONObject("content") ?: continue
            val json = content.optJSONArray("original") ?: JSONArray()
            val originals = buildList {
                for (j in 0 until json.length()) {
                    val o = json.optJSONObject(j) ?: continue
                    if (o.optString("url").isNotBlank()) add(Original(
                        o.optString("fileName", "camera-file"), o.optString("stillObject"), o.optString("url")))
                }
            }
            val preview = content.optString("largeUrl").ifBlank {
                originals.firstOrNull { it.kind == "jpeg" }?.url.orEmpty()
            }
            add(CameraImage(item.optString("createdTime"), content.optString("thumbnailUrl"), preview, originals))
        }
    }

    private fun addThumbnail(item: CameraImage) {
        images.add(item)
        val size = resources.displayMetrics.widthPixels / grid.columnCount - 16
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.DKGRAY)
            contentDescription = item.created
        }
        val mark = TextView(this).apply {
            text = "✓"; textSize = 24f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(0, 145, 255)); visibility = View.GONE
        }
        val frame = FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(mark, FrameLayout.LayoutParams(54, 54, Gravity.TOP or Gravity.END))
            setOnClickListener { if (selectMode) toggleSelection(item) else showImage(item) }
            setOnLongClickListener { if (!selectMode) toggleSelectMode(); toggleSelection(item); true }
        }
        selectionMarks[item] = mark
        grid.addView(frame, GridLayout.LayoutParams().apply { width = size; height = size })
        if (item.thumbnail.isNotBlank()) thumbnailExecutor.execute {
            runCatching { getBytes(item.thumbnail) }.getOrNull()?.let { bytes ->
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) thumbnailCache[item.thumbnail] = bitmap
                runOnUiThread { image.setImageBitmap(bitmap) }
            }
        }
    }

    private fun toggleSelectMode() {
        selectMode = !selectMode
        selectButton.text = if (selectMode) "Cancel" else "Select"
        if (!selectMode) {
            selected.clear(); selectionMarks.values.forEach { it.visibility = View.GONE }
        }
        updateSelectionUi()
    }

    private fun toggleSelection(item: CameraImage) {
        if (!selected.add(item)) selected.remove(item)
        selectionMarks[item]?.visibility = if (item in selected) View.VISIBLE else View.GONE
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        downloadButton.isEnabled = selected.isNotEmpty()
        downloadButton.text = if (selected.isEmpty()) "Download" else "Download (${selected.size})"
        if (selectMode) status.text = "${selected.size} selected"
    }

    private fun showImage(item: CameraImage) {
        var index = images.indexOf(item).coerceAtLeast(0)
        var generation = 0
        val image = ZoomImageView(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        val spinner = ProgressBar(this)
        val title = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); setPadding(20, 18, 20, 18)
            setBackgroundColor(Color.argb(180, 0, 0, 0))
        }
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.argb(200, 0, 0, 0))
        }
        fun render() {
            val current = images[index]
            val expected = ++generation
            val jpeg = current.originals.firstOrNull { it.kind == "jpeg" }
            val raw = current.originals.firstOrNull { it.kind == "raw" }
            title.text = jpeg?.fileName ?: raw?.fileName ?: "Camera photo"
            image.resetZoom()
            image.setImageBitmap(thumbnailCache[current.thumbnail])
            actions.removeAllViews()
            actions.addView(Button(this).apply {
                text = "Close"; setOnClickListener { dialog.dismiss() }
            })
            jpeg?.let { original -> actions.addView(Button(this).apply {
                text = "Save JPEG"
                setOnClickListener { enqueueDownloads(listOf(DownloadJob(current, original))) }
            }) }
            raw?.let { original -> actions.addView(Button(this).apply {
                text = "Save RAW"
                setOnClickListener { enqueueDownloads(listOf(DownloadJob(current, original))) }
            }) }
            if (current.preview.isBlank()) { spinner.visibility = View.GONE; return }
            spinner.visibility = View.VISIBLE
            previewExecutor.execute {
                runCatching { getBytes(current.preview) }.getOrNull()?.let { bytes ->
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    runOnUiThread {
                        if (dialog.isShowing && expected == generation && bitmap != null) {
                            image.setImageBitmap(bitmap); spinner.visibility = View.GONE
                        }
                    }
                }
            }
        }
        image.onSwipe = { direction ->
            val next = index + direction
            if (next in images.indices) { index = next; render() }
        }
        val frame = FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(actions, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        dialog.setContentView(frame); dialog.show()
        render()
    }

    /** Visor ligero con pellizco para zoom, arrastre y swipe lateral, sin dependencias externas. */
    private class ZoomImageView(context: android.content.Context) : ImageView(context) {
        /** -1 = foto anterior, +1 = foto siguiente. Solo se emite sin zoom. */
        var onSwipe: ((Int) -> Unit)? = null
        private var zoom = 1f
        private var panX = 0f
        private var panY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var multiTouch = false
        private val swipeThreshold = 80 * context.resources.displayMetrics.density
        private val zoomMatrix = Matrix()
        private val scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val previous = zoom
                    val next = (previous * detector.scaleFactor).coerceIn(1f, 6f)
                    val factor = next / previous
                    // Conserva bajo los dedos el mismo punto de la fotografia.
                    // La vista no se transforma: solo cambia la matriz del bitmap.
                    val focusFromCenterX = detector.focusX - width / 2f
                    val focusFromCenterY = detector.focusY - height / 2f
                    panX = focusFromCenterX - (focusFromCenterX - panX) * factor
                    panY = focusFromCenterY - (focusFromCenterY - panY) * factor
                    zoom = next
                    if (zoom == 1f) { panX = 0f; panY = 0f }
                    applyImageMatrix()
                    return true
                }
            })

        init { scaleType = ScaleType.MATRIX }

        fun resetZoom() {
            zoom = 1f; panX = 0f; panY = 0f
            applyImageMatrix()
        }

        override fun setImageBitmap(bm: Bitmap?) {
            super.setImageBitmap(bm)
            // El preview grande sustituye a la miniatura de forma asincrona.
            // Recalcular mantiene el zoom actual aunque cambie la resolucion.
            post { applyImageMatrix() }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            applyImageMatrix()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX; lastY = event.rawY
                    downX = event.rawX; downY = event.rawY; multiTouch = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
                MotionEvent.ACTION_MOVE -> {
                    // rawX/rawY permanecen en coordenadas de pantalla aunque la
                    // propia ImageView este escalada. Las coordenadas locales se
                    // encogen con el zoom y hacian que el paneo pareciese lento.
                    if (!scaleDetector.isInProgress && zoom > 1f) {
                        panX += event.rawX - lastX
                        panY += event.rawY - lastY
                        applyImageMatrix()
                    }
                    lastX = event.rawX; lastY = event.rawY
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (zoom == 1f && !multiTouch && abs(dx) > swipeThreshold && abs(dx) > abs(dy) * 2) {
                        onSwipe?.invoke(if (dx < 0) 1 else -1)
                    } else performClick()
                }
            }
            return true
        }

        /** Encaja, amplia y limita la foto sin transformar la propia vista tactil. */
        private fun applyImageMatrix() {
            val d = drawable ?: return
            if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
            val fit = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
            val shownW = d.intrinsicWidth * fit * zoom
            val shownH = d.intrinsicHeight * fit * zoom
            val maxX = maxOf(0f, (shownW - width) / 2f)
            val maxY = maxOf(0f, (shownH - height) / 2f)
            panX = panX.coerceIn(-maxX, maxX)
            panY = panY.coerceIn(-maxY, maxY)
            val scale = fit * zoom
            val left = (width - shownW) / 2f + panX
            val top = (height - shownH) / 2f + panY
            zoomMatrix.reset()
            zoomMatrix.setScale(scale, scale)
            zoomMatrix.postTranslate(left, top)
            imageMatrix = zoomMatrix
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }

    private fun chooseSelectedFormat() {
        AlertDialog.Builder(this).setTitle("Files to download")
            .setItems(arrayOf("JPEG", "RAW", "JPEG + RAW")) { _, which ->
                val jobs = selected.flatMap { image -> when (which) {
                    0 -> image.originals.filter { it.kind == "jpeg" }
                    1 -> image.originals.filter { it.kind == "raw" }
                    else -> image.originals.filter { it.kind == "jpeg" || it.kind == "raw" }
                }.map { DownloadJob(image, it) } }
                if (jobs.isEmpty()) toast("The selected photos do not contain that format")
                else enqueueDownloads(jobs)
            }.show()
    }

    private fun enqueueDownloads(jobs: List<DownloadJob>) {
        if (Build.VERSION.SDK_INT < 29 && destinationTree() == null &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 42)
            toast("Grant storage access, then retry"); return
        }
        activeDownloads.addAndGet(jobs.size)
        showDownloadDialog()
        jobs.forEachIndexed { index, job -> downloadExecutor.execute { downloadOne(job, index + 1, jobs.size) } }
        if (selectMode) toggleSelectMode()
    }

    private fun downloadOne(job: DownloadJob, position: Int, totalJobs: Int) {
        var conn: HttpURLConnection? = null
        try {
            conn = open(job.original.url)
            val length = conn.contentLengthLong
            runOnUiThread {
                downloadTitle.text = job.original.fileName
                downloadDetail.text = "$position of $totalJobs"
                downloadProgress.isIndeterminate = length <= 0
                downloadProgress.progress = 0
                downloadImage.setImageBitmap(thumbnailCache[job.image.thumbnail])
            }
            val mime = if (job.original.kind == "raw") "image/x-sony-arw" else "image/jpeg"
            openDestination(job.original.fileName, mime).use { output ->
                conn.inputStream.use { input -> copyWithProgress(input, output, length) { percent ->
                    runOnUiThread {
                        downloadProgress.isIndeterminate = false; downloadProgress.progress = percent
                        downloadDetail.text = "$position of $totalJobs · $percent%"
                    }
                } }
            }
        } catch (e: Exception) {
            toast("${job.original.fileName}: ${e.message}")
        } finally {
            conn?.disconnect()
            if (activeDownloads.decrementAndGet() == 0) runOnUiThread {
                hideDownloadDialog(); busy.visibility = View.GONE
                status.text = "Downloads finished"
                toast("Saved to ${destinationDescription()}")
            }
        }
    }

    private fun copyWithProgress(input: InputStream, output: OutputStream, total: Long, update: (Int) -> Unit) {
        val buffer = ByteArray(64 * 1024); var copied = 0L; var last = -1
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            output.write(buffer, 0, count); copied += count
            if (total > 0) {
                val percent = (copied * 100 / total).toInt().coerceIn(0, 100)
                if (percent != last) { last = percent; update(percent) }
            }
        }
    }

    private fun openDestination(name: String, mime: String): OutputStream {
        destinationTree()?.let { tree ->
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val file = DocumentsContract.createDocument(contentResolver, parent, mime, name)
                ?: throw IllegalStateException("Cannot create file in selected folder")
            return contentResolver.openOutputStream(file) ?: throw IllegalStateException("Cannot open destination")
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/SonyLiveMonitor")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Cannot create download")
            return contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Cannot open download")
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SonyLiveMonitor").apply { mkdirs() }
        return FileOutputStream(File(dir, name))
    }

    private fun showFolderOptions() {
        val options = if (destinationTree() == null) arrayOf("Choose folder")
            else arrayOf("Choose another folder", "Use Download/SonyLiveMonitor")
        AlertDialog.Builder(this).setTitle("Saving destination").setItems(options) { _, which ->
            if (options.size == 2 && which == 1) {
                getPreferences(MODE_PRIVATE).edit().remove("download_tree").apply(); updateFolderLabel()
            } else {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, FOLDER_REQUEST)
            }
        }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FOLDER_REQUEST && resultCode == RESULT_OK) data?.data?.let { uri ->
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            getPreferences(MODE_PRIVATE).edit().putString("download_tree", uri.toString()).apply()
            updateFolderLabel()
        }
    }

    private fun destinationTree(): Uri? = getPreferences(MODE_PRIVATE)
        .getString("download_tree", null)?.let(Uri::parse)
    private fun destinationDescription() = if (destinationTree() == null)
        "Download/SonyLiveMonitor" else "the selected folder"
    private fun updateFolderLabel() { if (::folderButton.isInitialized)
        folderButton.text = if (destinationTree() == null) "Folder: default" else "Folder: custom" }

    private fun getBytes(url: String): ByteArray {
        val conn = open(url)
        return try { conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000; readTimeout = 120_000; useCaches = false
        setRequestProperty("Connection", "close"); connect()
        if (responseCode !in 200..299) throw CameraException("HTTP $responseCode")
    }

    private fun closeCard() {
        if (closing) return
        if (activeDownloads.get() > 0) { toast("Wait for downloads to finish"); return }
        closing = true; busy.visibility = View.VISIBLE; status.text = "Returning to remote shooting..."
        window.decorView.postDelayed({ if (!isFinishing) finish() }, 2500)
        apiExecutor.execute {
            runCatching { SonyCamera.call(SonyCamera.cameraEndpoint, "setCameraFunction",
                JSONArray().put("Remote Shooting")) }
            runOnUiThread { if (!isFinishing) finish() }
        }
    }

    override fun onBackPressed() = closeCard()
    override fun onDestroy() {
        super.onDestroy(); downloadDialog?.dismiss(); downloadDialog = null
        apiExecutor.shutdownNow(); thumbnailExecutor.shutdownNow()
        previewExecutor.shutdownNow(); downloadExecutor.shutdownNow()
    }
    private fun toast(message: String) = runOnUiThread {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
