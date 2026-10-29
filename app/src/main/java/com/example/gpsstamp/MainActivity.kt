package com.example.gpsstamp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.View
import android.view.MotionEvent
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.Recording
import androidx.camera.video.MediaStoreOutputOptions
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var stampText: TextView
    private lateinit var shutter: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var activeCamera: Camera? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var stampEnabled = true
    private var videoMode = false
    private var wideAspect = false
    private var timerSeconds = 0
    private var templateIndex = 0
    private var includeAltitude = true
    private var includeSpeed = true
    private var includeAccuracy = true
    private var includePressure = true
    private var personName = ""
    private var pressureHpa: Float? = null
    private var weatherEnabled = false
    private var weather: WeatherSnapshot? = null
    private var weatherFetchedAt = 0L
    private var weatherLocation: Location? = null
    private var weatherLoading = false
    private val weatherExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var latestLocation: Location? = null
    private var lastPhoto: Uri? = null
    private var focusView: TextView? = null
    private lateinit var gpsButton: TextView
    private lateinit var flashButton: TextView
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) { pressureHpa = event.values.firstOrNull(); updateStamp() }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(pressureListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(pressureListener)
        super.onPause()
    }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private data class WeatherSnapshot(val temperature: Double, val humidity: Int, val windKmh: Double, val pressure: Double, val summary: String)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasCameraPermission()) startCamera()
        else showMessage("Camera permission is needed to take photos")
        refreshLocationLabel()
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        beginVideoRecording(withAudio = granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 24, 32)
        window.navigationBarColor = Color.rgb(16, 24, 32)
        cameraExecutor = Executors.newSingleThreadExecutor()
        lastPhoto = getPreferences(MODE_PRIVATE).getString("last_photo", null)?.let(Uri::parse)
        stampEnabled = getPreferences(MODE_PRIVATE).getBoolean("stamp_enabled", true)
        timerSeconds = getPreferences(MODE_PRIVATE).getInt("timer_seconds", 0)
        templateIndex = getPreferences(MODE_PRIVATE).getInt("template_index", 0)
        includeAltitude = getPreferences(MODE_PRIVATE).getBoolean("include_altitude", true)
        includeSpeed = getPreferences(MODE_PRIVATE).getBoolean("include_speed", true)
        includeAccuracy = getPreferences(MODE_PRIVATE).getBoolean("include_accuracy", true)
        includePressure = getPreferences(MODE_PRIVATE).getBoolean("include_pressure", true)
        personName = getPreferences(MODE_PRIVATE).getString("person_name", "") ?: ""
        weatherEnabled = getPreferences(MODE_PRIVATE).getBoolean("weather_enabled", false)
        buildCameraUi()
        requestNeededPermissions()
    }

    private fun buildCameraUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(8), dp(5), dp(8))
            setBackgroundColor(0xF0000000.toInt())
        }
        val aspectButton = button("▣") { cycleAspect(it) }
        flashButton = button("ϟ") { cycleFlash() }
        val timerButton = button("◷") { cycleTimer(it) }
        val mapButton = button("⌖") { openMap() }
        val flipButton = button("⟳") { flipCamera() }
        gpsButton = button("●") { requestLocation() }
        val settingsButton = button("⚙") { openStampSettings() }
        listOf(aspectButton, flashButton, timerButton, mapButton, flipButton, gpsButton, settingsButton).forEach { control ->
            top.addView(control, weightParams())
        }
        aspectButton.contentDescription = "Change photo aspect ratio"
        flashButton.contentDescription = "Change flash setting"
        timerButton.contentDescription = "Set capture timer"
        mapButton.contentDescription = "Open this location in Maps"
        flipButton.contentDescription = "Switch front or rear camera"
        gpsButton.contentDescription = "Refresh GPS location"
        settingsButton.contentDescription = "Stamp settings"
        root.addView(top, FrameLayout.LayoutParams(-1, dp(52), Gravity.TOP))

        statusText = TextView(this).apply {
            text = "GPS: checking permission"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(0xAA101820.toInt(), 20)
            contentDescription = "GPS and privacy information"
            setOnClickListener { showPrivacyNotice() }
        }
        root.addView(statusText, FrameLayout.LayoutParams(-2, dp(30), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(58) })

        val focus = TextView(this).apply {
            text = "+"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFD600.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x22111111)
                setStroke(dp(1), 0x77FFFFFF)
            }
            contentDescription = "Tap viewfinder to focus"
            isClickable = false
        }
        focusView = focus
        root.addView(focus, FrameLayout.LayoutParams(dp(74), dp(74), Gravity.CENTER).apply { bottomMargin = dp(90) })

        val metadataCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = roundedBackground(0xDD09090D.toInt(), 12)
            elevation = dp(5).toFloat()
            setOnClickListener { openMap() }
        }
        val mapPreview = MapPreviewView(this).apply {
            contentDescription = "Stylized location preview; tap to open map"
        }
        metadataCard.addView(mapPreview, LinearLayout.LayoutParams(dp(112), dp(132)))
        stampText = TextView(this).apply {
            text = "Waiting for GPS…"
            setTextColor(Color.WHITE)
            textSize = 11f
            setLineSpacing(dp(1).toFloat(), 1f)
            setPadding(dp(9), 0, dp(3), 0)
            maxLines = 10
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = if (stampEnabled) View.VISIBLE else View.GONE
        }
        metadataCard.addView(stampText, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(metadataCard, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(12), 0, dp(12), dp(222))
        })

        val exposure = SeekBar(this).apply {
            max = 12
            progress = 6
            rotation = -90f
            alpha = 0.92f
            contentDescription = "Exposure compensation"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) activeCamera?.cameraControl?.setExposureCompensationIndex(progress - 6)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        root.addView(exposure, FrameLayout.LayoutParams(dp(150), dp(38), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = -dp(54) })

        val zoomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = roundedBackground(0xCC09090D.toInt(), 22)
        }
        listOf(1f, 2f).forEach { zoom ->
            zoomControls.addView(button(if (zoom == 1f) "1×" else "2×") {
                activeCamera?.cameraControl?.setZoomRatio(zoom)
            }, LinearLayout.LayoutParams(dp(52), dp(34)))
        }
        root.addView(zoomControls, FrameLayout.LayoutParams(-2, dp(42), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(174) })

        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBackground(0xCC09090D.toInt(), 24)
        }
        modePhoto = button("PHOTO") { setVideoMode(false) }
        modeVideo = button("VIDEO") { setVideoMode(true) }
        modes.addView(modePhoto, LinearLayout.LayoutParams(dp(90), dp(34)))
        modes.addView(modeVideo, LinearLayout.LayoutParams(dp(90), dp(34)))
        root.addView(modes, FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(112) })
        updateModeButtons()

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(5))
            setBackgroundColor(0xF3000000.toInt())
        }
        val gallery = button("▧\nPreview") { openLastPhoto() }
        val location = button("♧\nLocation") { requestLocation() }
        shutter = TextView(this).apply {
            text = "●"
            textSize = 58f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Take photo or start video"
            setOnClickListener { if (videoMode) toggleVideoRecording() else captureWithLocation() }
        }
        val templates = button("▤\nDefault") { setDefaultTemplate() }
        val more = button("▦\nTemplates") { chooseTemplate() }
        bottom.addView(gallery, LinearLayout.LayoutParams(0, dp(78), 1f))
        bottom.addView(location, LinearLayout.LayoutParams(0, dp(78), 1f))
        bottom.addView(shutter, LinearLayout.LayoutParams(0, dp(78), 1.3f))
        bottom.addView(templates, LinearLayout.LayoutParams(0, dp(78), 1f))
        bottom.addView(more, LinearLayout.LayoutParams(0, dp(78), 1f))
        root.addView(bottom, FrameLayout.LayoutParams(-1, dp(90), Gravity.BOTTOM))

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val state = activeCamera?.cameraInfo?.zoomState?.value ?: return false
                val requested = (state.zoomRatio * detector.scaleFactor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                activeCamera?.cameraControl?.setZoomRatio(requested)
                return true
            }
        })
        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress && event.pointerCount == 1) {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                activeCamera?.cameraControl?.startFocusAndMetering(androidx.camera.core.FocusMeteringAction.Builder(point).build())
                focus?.animate()?.alpha(0.25f)?.setDuration(100)?.withEndAction { focus.alpha = 1f }?.start()
            }
            true
        }
        setContentView(root)
    }
    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (!hasCameraPermission()) needed += Manifest.permission.CAMERA
        if (!hasLocationPermission()) {
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray()) else {
            startCamera()
            requestLocation()
        }
    }

    private fun cycleAspect(button: TextView) {
        wideAspect = !wideAspect
        button.alpha = if (wideAspect) 1f else 0.72f
        showMessage(if (wideAspect) "16:9 photo framing" else "4:3 photo framing")
        startCamera()
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        if (flashMode != ImageCapture.FLASH_MODE_OFF && activeCamera?.cameraInfo?.hasFlashUnit() == false) {
            flashMode = ImageCapture.FLASH_MODE_OFF
            showMessage("Flash is not available on this camera")
        }
        flashButton.text = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "⚡A"
            ImageCapture.FLASH_MODE_ON -> "⚡"
            else -> "ϟ"
        }
        flashButton.contentDescription = "Flash ${when (flashMode) { ImageCapture.FLASH_MODE_AUTO -> "auto"; ImageCapture.FLASH_MODE_ON -> "on"; else -> "off" }}"
    }

    private fun cycleTimer(button: TextView) {
        timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
        button.text = if (timerSeconds == 0) "◷" else timerSeconds.toString()
        getPreferences(MODE_PRIVATE).edit().putInt("timer_seconds", timerSeconds).apply()
    }

    private fun openMap() {
        val location = latestLocation ?: run { showMessage("Get a GPS fix first"); return }
        val uri = Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}")
        try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
        catch (_: Exception) { showMessage("No map app is installed") }
    }

    private fun flipCamera() {
        if (recording != null) { showMessage("Stop video recording before switching camera"); return }
        lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        startCamera()
    }

    private fun setVideoMode(enabled: Boolean) {
        if (recording != null && !enabled) { recording?.stop(); return }
        videoMode = enabled
        updateModeButtons()
        statusText.text = if (enabled) "VIDEO MODE • tap shutter to record" else "PHOTO MODE • GPS stamp ready"
    }

    private fun updateModeButtons() {
        if (!::modePhoto.isInitialized) return
        modePhoto.setTextColor(if (videoMode) Color.WHITE else Color.BLACK)
        modeVideo.setTextColor(if (videoMode) Color.BLACK else Color.WHITE)
        modePhoto.background = roundedBackground(if (videoMode) Color.TRANSPARENT else 0xFFFFD11A.toInt(), 16)
        modeVideo.background = roundedBackground(if (videoMode) 0xFFFFD11A.toInt() else Color.TRANSPARENT, 16)
    }

    private fun toggleVideoRecording() {
        if (recording != null) { recording?.stop(); return }
        if (videoCapture == null) { showMessage("Video camera is starting; try again"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginVideoRecording(withAudio = true)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Record video with sound?")
                .setMessage("Allow microphone access to include audio. If you decline, video will be recorded silently.")
                .setPositiveButton("Allow audio") { _, _ -> audioPermission.launch(Manifest.permission.RECORD_AUDIO) }
                .setNegativeButton("Record silently") { _, _ -> beginVideoRecording(withAudio = false) }
                .show()
        }
    }

    private fun beginVideoRecording(withAudio: Boolean) {
        val capture = videoCapture ?: return
        val now = Date()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "GPSVID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, now.time)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GPS Stamp Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = capture.output.prepareRecording(this, output)
        if (withAudio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    shutter.text = "■"
                    shutter.setTextColor(0xFFFF5353.toInt())
                    statusText.text = "RECORDING • tap shutter to stop"
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    shutter.text = "●"
                    shutter.setTextColor(Color.WHITE)
                    if (!event.hasError()) {
                        val uri = event.outputResults.outputUri
                        if (Build.VERSION.SDK_INT >= 29 && uri != Uri.EMPTY) {
                            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                        }
                        rememberMedia(uri)
                        statusText.text = "Video saved in Movies/GPS Stamp Camera"
                        showMessage("Video saved")
                    } else {
                        statusText.text = "Video could not be saved"
                        showMessage("Video failed: ${event.cause?.message ?: event.error}")
                    }
                }
            }
        }
    }

    private fun chooseTemplate() {
        val names = arrayOf("Default GPS", "Compact", "Purple Night", "Black Minimal")
        AlertDialog.Builder(this)
            .setTitle("Photo stamp template")
            .setSingleChoiceItems(names, templateIndex) { dialog, which ->
                templateIndex = which
                getPreferences(MODE_PRIVATE).edit().putInt("template_index", which).apply()
                stampText.textSize = if (which == 1) 10f else 11f
                updateStamp()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setDefaultTemplate() {
        templateIndex = 0
        getPreferences(MODE_PRIVATE).edit().putInt("template_index", 0).apply()
        stampText.textSize = 11f
        updateStamp()
    }

    private fun openStampSettings() {
        val labels = arrayOf("Show GPS accuracy", "Show altitude", "Show speed and heading", "Show device pressure", "Show photo stamp", "Fetch weather from Open-Meteo (sends GPS coordinates)")
        val values = booleanArrayOf(includeAccuracy, includeAltitude, includeSpeed, includePressure, stampEnabled, weatherEnabled)
        AlertDialog.Builder(this)
            .setTitle("Camera and stamp settings")
            .setMultiChoiceItems(labels, values) { _, which, checked ->
                when (which) {
                    0 -> { includeAccuracy = checked; getPreferences(MODE_PRIVATE).edit().putBoolean("include_accuracy", checked).apply() }
                    1 -> { includeAltitude = checked; getPreferences(MODE_PRIVATE).edit().putBoolean("include_altitude", checked).apply() }
                    2 -> { includeSpeed = checked; getPreferences(MODE_PRIVATE).edit().putBoolean("include_speed", checked).apply() }
                    3 -> {
                        includePressure = checked
                        getPreferences(MODE_PRIVATE).edit().putBoolean("include_pressure", checked).apply()
                        if (pressureHpa == null) showMessage("Pressure is available only when this phone has a barometer")
                    }
                    4 -> { stampEnabled = checked; getPreferences(MODE_PRIVATE).edit().putBoolean("stamp_enabled", checked).apply(); stampText.visibility = if (checked) View.VISIBLE else View.GONE }
                    5 -> {
                        weatherEnabled = checked
                        getPreferences(MODE_PRIVATE).edit().putBoolean("weather_enabled", checked).apply()
                        if (checked) latestLocation?.takeIf { isFresh(it) }?.let { fetchWeather(it) }
                        else { weather = null; weatherFetchedAt = 0L; weatherLocation = null }
                    }
                }
                updateStamp()
            }
            .setNeutralButton("Person name") { _, _ -> editPersonName() }
            .setPositiveButton("Done", null)
            .setNegativeButton("Privacy") { _, _ -> showPrivacyNotice() }
            .show()
    }

    private fun editPersonName() {
        val input = EditText(this).apply {
            setText(personName)
            hint = "Optional name on photo"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Person name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                personName = input.text.toString().trim().take(40)
                getPreferences(MODE_PRIVATE).edit().putString("person_name", personName).apply()
                updateStamp()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPrivacyNotice() {
        AlertDialog.Builder(this)
            .setTitle("Privacy")
            .setMessage("Camera and foreground location are used for captures. Photos and videos are saved on this device. The app does not upload your media or coordinates. Weather is off by default; if enabled in settings, your GPS coordinates are sent to Open-Meteo for current conditions. Video audio is optional and recorded only after microphone permission.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun plusCode(latitude: Double, longitude: Double): String {
        val alphabet = "23456789CFGHJMPQRVWX"
        var lat = (latitude + 90.0).coerceIn(0.0, 180.0 - 1e-10)
        var lon = (longitude + 180.0).coerceIn(0.0, 360.0 - 1e-10)
        val code = StringBuilder()
        val resolutions = doubleArrayOf(20.0, 1.0, 0.05, 0.0025, 0.000125)
        resolutions.forEachIndexed { index, resolution ->
            val latDigit = (lat / resolution).toInt().coerceIn(0, 19)
            val lonDigit = (lon / resolution).toInt().coerceIn(0, 19)
            code.append(alphabet[latDigit]).append(alphabet[lonDigit])
            lat -= latDigit * resolution
            lon -= lonDigit * resolution
            if (index == 3) code.append('+')
        }
        return code.toString()
    }

    private fun fetchWeather(location: Location) {
        if (!weatherEnabled || weatherLoading) return
        val now = System.currentTimeMillis()
        if (weather != null && now - weatherFetchedAt < 10 * 60_000L && (weatherLocation?.distanceTo(location) ?: Float.MAX_VALUE) < 1_000f) return
        weatherLoading = true
        weatherExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val query = "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,surface_pressure&timezone=auto"
                connection = (URL(query).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    setRequestProperty("Accept", "application/json")
                }
                val root = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val current = root.getJSONObject("current")
                val snapshot = WeatherSnapshot(
                    current.getDouble("temperature_2m"),
                    current.getInt("relative_humidity_2m"),
                    current.getDouble("wind_speed_10m"),
                    current.getDouble("surface_pressure"),
                    weatherDescription(current.getInt("weather_code"))
                )
                mainHandler.post {
                    weather = snapshot
                    weatherFetchedAt = System.currentTimeMillis()
                    weatherLocation = location
                    weatherLoading = false
                    updateStamp()
                }
            } catch (_: Exception) {
                mainHandler.post {
                    weatherLoading = false
                    statusText.text = "GPS ready • weather unavailable"
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Thunderstorm"
        else -> "Current weather"
    }

    private fun headingText(degrees: Float): String {
        val labels = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return labels[((degrees / 45f).roundToInt() % 8 + 8) % 8]
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!hasCameraPermission()) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val selector = CameraSelector.Builder().requireLensFacing(lens).build()
                val aspect = if (wideAspect) androidx.camera.core.AspectRatio.RATIO_16_9 else androidx.camera.core.AspectRatio.RATIO_4_3
                val preview = Preview.Builder().setTargetAspectRatio(aspect).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                imageCapture = ImageCapture.Builder().setTargetAspectRatio(aspect).setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
                videoCapture = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                activeCamera = provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
            } catch (_: Exception) {
                showMessage("Camera could not start on this device")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestLocation() {
        if (!hasLocationPermission()) {
            permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }
        statusText.text = "GPS: finding a fresh location…"
        val token = CancellationTokenSource()
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { location ->
                if (location != null && isFresh(location)) {
                    latestLocation = location
                    updateStamp()
                    fetchWeather(location)
                } else {
                    statusText.text = "GPS: no fix yet; photo will show coordinates only if available"
                    fused.lastLocation.addOnSuccessListener { previous ->
                        if (previous != null && isFresh(previous)) { latestLocation = previous; updateStamp(); fetchWeather(previous) }
                    }
                }
            }
            .addOnFailureListener { statusText.text = "GPS unavailable; photo can still be saved" }
    }

    private fun refreshLocationLabel() {
        if (hasLocationPermission()) requestLocation() else {
            statusText.text = "GPS permission off — photos can still be saved without location"
            stampText.text = "Location permission is off"
        }
    }

    private fun updateStamp() {
        val loc = latestLocation ?: return
        val date = SimpleDateFormat("dd MMM yyyy • h:mm a", Locale.getDefault()).format(Date())
        val coords = String.format(Locale.US, "%.5f° %s, %.5f° %s", kotlin.math.abs(loc.latitude), if (loc.latitude >= 0) "N" else "S", kotlin.math.abs(loc.longitude), if (loc.longitude >= 0) "E" else "W")
        val details = mutableListOf(date, coords, "Plus Code ${plusCode(loc.latitude, loc.longitude)}")
        if (includeAccuracy) details += if (loc.hasAccuracy()) "GPS accuracy ±${loc.accuracy.toInt()} m" else "GPS accuracy unavailable"
        if (includeAltitude && loc.hasAltitude()) details += "Altitude ${loc.altitude.toInt()} m"
        if (includeSpeed && loc.hasSpeed()) details += String.format(Locale.getDefault(), "Speed %.1f km/h", loc.speed * 3.6f)
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1f) details += "Heading ${headingText(loc.bearing)}"
        if (includePressure) pressureHpa?.let { details += String.format(Locale.getDefault(), "Pressure %.0f hPa", it) }
        weather?.let { details += String.format(Locale.getDefault(), "Open-Meteo • %s %.0f°C • RH %d%%", it.summary, it.temperature, it.humidity) }
        weather?.let { details += String.format(Locale.getDefault(), "Wind %.1f km/h • %.0f hPa", it.windKmh, it.pressure) }
        if (personName.isNotBlank()) details += "Person: $personName"
        stampText.text = details.joinToString("\n")
        gpsButton.text = "●"
        statusText.text = "GPS READY • ${if (loc.hasAccuracy()) "±${loc.accuracy.toInt()} m" else "location found"}"
    }

    private fun captureWithLocation() {
        if (imageCapture == null) { showMessage("Camera is starting; try again in a moment"); return }
        shutter.isEnabled = false
        if (!hasLocationPermission()) {
            schedulePhoto(latestLocation)
            return
        }
        statusText.text = "GPS: getting location for this photo…"
        val token = CancellationTokenSource()
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { current ->
                val selected = current?.takeIf { isFresh(it) } ?: latestLocation?.takeIf { isFresh(it) }
                if (selected != null) latestLocation = selected
                selected?.let { fetchWeather(it) }
                schedulePhoto(selected)
            }
            .addOnFailureListener { schedulePhoto(latestLocation) }
    }

    private fun schedulePhoto(location: Location?) {
        val usable = location?.takeIf { isFresh(it) }
        if (timerSeconds <= 0) { takePhoto(usable); return }
        statusText.text = "Photo in $timerSeconds seconds…"
        shutter.text = timerSeconds.toString()
        mainHandler.postDelayed({ shutter.text = "●"; takePhoto(usable) }, timerSeconds * 1_000L)
    }

    private fun isFresh(location: Location): Boolean = System.currentTimeMillis() - location.time in 0..120_000

    private fun takePhoto(location: Location?) {
        val usableLocation = location?.takeIf { isFresh(it) }
        val capture = imageCapture ?: run { shutter.isEnabled = true; return }
        capture.flashMode = if (lens == CameraSelector.LENS_FACING_BACK) flashMode else ImageCapture.FLASH_MODE_OFF
        val temp = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(temp).build()
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(exception: ImageCaptureException) {
                mainHandler.post { shutter.isEnabled = true; showMessage("Photo failed: ${exception.message ?: "camera error"}") }
                temp.delete()
            }
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                try {
                    val uri = stampAndSave(temp, usableLocation)
                    temp.delete()
                    mainHandler.post {
                        rememberMedia(uri)
                        shutter.isEnabled = true
                        statusText.text = "Photo saved to Gallery${if (usableLocation == null) " • no fresh GPS fix" else " • GPS stamped"}"
                        showMessage("Photo saved to Gallery")
                    }
                } catch (error: Exception) {
                    temp.delete()
                    mainHandler.post { shutter.isEnabled = true; showMessage("Could not save photo: ${error.message ?: "storage error"}") }
                }
            }
        })
    }

    private fun stampAndSave(input: File, location: Location?): Uri {
        val source = BitmapFactory.decodeFile(input.absolutePath) ?: error("Could not decode captured image")
        val exifIn = ExifInterface(input.absolutePath)
        val rotation = when (exifIn.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val oriented = if (rotation != 0f) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also { if (it !== source) source.recycle() }
        } else source
        val outputBitmap = if (stampEnabled) drawStamp(oriented, location) else oriented
        val now = Date()
        val filename = "GPS_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, now.time)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GPS Stamp Camera")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Gallery could not create a photo")
        try {
            resolver.openOutputStream(uri, "w")?.use { stream ->
                if (!outputBitmap.compress(Bitmap.CompressFormat.JPEG, 94, stream)) error("JPEG encoding failed")
            } ?: error("Gallery file could not be opened")
            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                exif.setAttribute(ExifInterface.TAG_DATETIME, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now))
                if (location != null) {
                    exif.setLatLong(location.latitude, location.longitude)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now))
                }
                exif.saveAttributes()
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        } finally {
            if (outputBitmap !== oriented) outputBitmap.recycle()
            oriented.recycle()
        }
        return uri
    }

    private fun drawStamp(photo: Bitmap, location: Location?): Bitmap {
        val bitmap = photo.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val scale = (bitmap.width / 900f).coerceAtLeast(0.75f)
        val pad = 24f * scale
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 31f * scale; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
        val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1F5F7.toInt(); textSize = 25f * scale; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL) }
        val lines = mutableListOf(SimpleDateFormat("dd MMM yyyy • h:mm a", Locale.getDefault()).format(Date()))
        if (location != null) {
            lines += String.format(Locale.US, "%.5f° %s, %.5f° %s", kotlin.math.abs(location.latitude), if (location.latitude >= 0) "N" else "S", kotlin.math.abs(location.longitude), if (location.longitude >= 0) "E" else "W")
            lines += "Plus Code ${plusCode(location.latitude, location.longitude)}"
            if (templateIndex != 3 && includeAccuracy) lines += if (location.hasAccuracy()) "GPS accuracy ±${location.accuracy.toInt()} m" else "GPS accuracy unavailable"
            if (templateIndex != 3 && includeAltitude && location.hasAltitude()) lines += "Altitude ${location.altitude.toInt()} m"
            if (templateIndex != 3 && includeSpeed && location.hasSpeed()) lines += String.format(Locale.getDefault(), "Speed %.1f km/h", location.speed * 3.6f)
            if (personName.isNotBlank()) lines += "Person: $personName"
        } else lines += "Location unavailable"
        if (templateIndex != 3 && includePressure) pressureHpa?.let { lines += String.format(Locale.getDefault(), "Pressure %.0f hPa", it) }
        if (templateIndex != 3) weather?.let { lines += String.format(Locale.getDefault(), "Open-Meteo • %s %.0f°C • RH %d%%", it.summary, it.temperature, it.humidity) }
        if (templateIndex != 3) weather?.let { lines += String.format(Locale.getDefault(), "Wind %.1f km/h • %.0f hPa", it.windKmh, it.pressure) }
        val lineHeight = 39f * scale
        val maxTextWidth = lines.maxOf { detail.measureText(it) }.coerceAtMost(bitmap.width - pad * 4)
        val boxWidth = (maxTextWidth + pad * 2).coerceAtMost(bitmap.width - pad * 2)
        val boxHeight = pad * 2 + lineHeight * lines.size
        val left = pad
        val top = bitmap.height - boxHeight - pad
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (templateIndex == 2) 0xDD251348.toInt() else if (templateIndex == 3) 0xCC000000.toInt() else 0xCC101820.toInt() }
        canvas.drawRoundRect(RectF(left, top, left + boxWidth, top + boxHeight), 20f * scale, 20f * scale, boxPaint)
        var baseline = top + pad + 27f * scale
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, left + pad, baseline, if (index == 0) title else detail)
            baseline += lineHeight
        }
        return bitmap
    }

    private fun openLastPhoto() {
        val media = savedMedia()
        if (media.isEmpty()) { showMessage("Take a photo or video first; media is saved on this device"); return }
        val labels = media.map { mediaDisplayName(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        AlertDialog.Builder(this)
            .setTitle("Recent captures")
            .setAdapter(adapter) { _, index -> openMedia(media[index]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openMedia(uri: Uri) {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "image/jpeg").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { showMessage("No photo viewer is available") }
    }

    private fun shareLastPhoto() {
        val uri = lastPhoto ?: savedMedia().firstOrNull()
        if (uri == null) { showMessage("Take a photo before sharing"); return }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "image/jpeg"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "Share GPS-stamped photo"))
    }

    private fun savedMedia(): List<Uri> = getPreferences(MODE_PRIVATE).getString("photos", "")
        ?.lineSequence()?.mapNotNull { value -> runCatching { Uri.parse(value) }.getOrNull() }?.toList().orEmpty()

    private fun rememberMedia(uri: Uri) {
        lastPhoto = uri
        val updated = (listOf(uri) + savedMedia().filterNot { it == uri }).take(100)
        getPreferences(MODE_PRIVATE).edit()
            .putString("last_photo", uri.toString())
            .putString("photos", updated.joinToString("\n"))
            .apply()
    }

    private fun mediaDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "Saved media"
        }
        return "Saved media"
    }

    private fun button(label: String, click: (TextView) -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = if (label.contains('\n')) 11f else 19f
        setTextColor(Color.WHITE)
        isFocusable = true
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = roundedBackground(0x331C1C22, 16)
        setOnClickListener { click(this) }
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, -1, 1f)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun roundedBackground(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private inner class MapPreviewView(context: android.content.Context) : View(context) {
        private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF827990.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3D52.toInt(); style = Paint.Style.FILL }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(9).toFloat(); typeface = android.graphics.Typeface.DEFAULT_BOLD }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(0xFF211B2A.toInt())
            val w = width.toFloat()
            val h = height.toFloat()
            road.strokeWidth = dp(5).toFloat()
            val mainRoad = android.graphics.Path().apply {
                moveTo(-w * .1f, h * .72f)
                cubicTo(w * .22f, h * .55f, w * .35f, h * .8f, w * .52f, h * .5f)
                cubicTo(w * .7f, h * .18f, w * .78f, h * .47f, w * 1.12f, h * .18f)
            }
            canvas.drawPath(mainRoad, road)
            road.strokeWidth = dp(2).toFloat()
            listOf(.23f, .43f, .65f, .84f).forEach { fraction ->
                canvas.drawLine(w * fraction, 0f, w * (fraction - .12f), h, road)
            }
            listOf(.3f, .58f, .91f).forEach { fraction ->
                canvas.drawLine(0f, h * fraction, w, h * (fraction + .04f), road)
            }
            val cx = w * .52f
            val cy = h * .51f
            canvas.drawCircle(cx, cy, dp(7).toFloat(), marker)
            val pin = android.graphics.Path().apply {
                moveTo(cx - dp(5), cy + dp(4)); lineTo(cx, cy + dp(15)); lineTo(cx + dp(5), cy + dp(4)); close()
            }
            canvas.drawPath(pin, marker)
            canvas.drawRect(0f, h - dp(19), w, h, Paint().apply { color = 0xAA000000.toInt() })
            canvas.drawText("GPS MAP  •  TAP", dp(5).toFloat(), h - dp(6).toFloat(), label)
        }
    }

    override fun onDestroy() {
        recording?.stop()
        weatherExecutor.shutdownNow()
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}
