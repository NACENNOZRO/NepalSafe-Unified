package np.nepalsafe.lifeline

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

class DisasterReportActivity : Activity() {
    private lateinit var backendStatus: TextView
    private lateinit var locationStatus: TextView
    private lateinit var preview: ImageView
    private lateinit var removeButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var resultText: TextView
    private lateinit var feedEmpty: TextView
    private lateinit var feed: LinearLayout

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val webSocketClient = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var running = false
    private var photoFile: File? = null
    private var photoUri: Uri? = null
    private var location = AppLocation(LocationSupport.FALLBACK_LAT, LocationSupport.FALLBACK_LNG, false, "fallback")
    private val seenAlertIds = linkedSetOf<String>()
    private val alerts = mutableListOf<JSONObject>()

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollBackend()
            if (running) handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disaster_report)
        window.statusBarColor = getColor(R.color.navy)
        bindViews()
        refreshLocation()
        findViewById<Button>(R.id.refreshAnalysisLocationButton).setOnClickListener { refreshLocation() }
        findViewById<Button>(R.id.takeAnalysisPhotoButton).setOnClickListener { ensureCameraThenCapture() }
        findViewById<Button>(R.id.pickAnalysisPhotoButton).setOnClickListener { choosePhoto() }
        removeButton.setOnClickListener { clearPhoto() }
        analyzeButton.setOnClickListener { analyzePhoto() }
    }

    override fun onStart() {
        super.onStart()
        running = true
        connectLiveAlerts()
        pollBackend()
    }

    override fun onStop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "screen stopped")
        webSocket = null
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        webSocketClient.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        backendStatus = findViewById(R.id.analysisBackendStatus)
        locationStatus = findViewById(R.id.analysisLocationStatus)
        preview = findViewById(R.id.analysisPhotoPreview)
        removeButton = findViewById(R.id.removeAnalysisPhotoButton)
        analyzeButton = findViewById(R.id.runAnalysisButton)
        resultText = findViewById(R.id.analysisResultText)
        feedEmpty = findViewById(R.id.analysisFeedEmpty)
        feed = findViewById(R.id.analysisAlertFeed)
    }

    private fun refreshLocation() {
        location = LocationSupport.bestAvailable(this)
        locationStatus.text = if (location.exact) "📍 ${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)}" else "Location unavailable — photo GPS metadata will be used if present"
    }

    private fun ensureCameraThenCapture() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        } else capturePhoto()
    }

    private fun capturePhoto() {
        val file = File.createTempFile("disaster_photo_", ".jpg", cacheDir)
        photoFile = file
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivityForResult(
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, CAPTURE_PHOTO
        )
    }

    private fun choosePhoto() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }, PICK_PHOTO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) capturePhoto()
        else if (requestCode == CAMERA_PERMISSION) Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
    }

    @Deprecated("Uses platform activity results supported by minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            CAPTURE_PHOTO -> showSelectedPhoto(photoUri)
            PICK_PHOTO -> {
                val uri = data?.data ?: return
                runCatching { PhotoSupport.copyToCache(this, uri, "analysis_picked_") }
                    .onSuccess {
                        photoFile = it
                        photoUri = Uri.fromFile(it)
                        showSelectedPhoto(uri)
                    }
                    .onFailure { Toast.makeText(this, it.message ?: "Could not open image", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showSelectedPhoto(uri: Uri?) {
        preview.setImageURI(uri)
        preview.visibility = View.VISIBLE
        removeButton.visibility = View.VISIBLE
        analyzeButton.isEnabled = true
        analyzeButton.alpha = 1f
        resultText.text = "Photo ready. Tap Run Analysis & Broadcast."
    }

    private fun clearPhoto() {
        photoFile = null
        photoUri = null
        preview.setImageDrawable(null)
        preview.visibility = View.GONE
        removeButton.visibility = View.GONE
        analyzeButton.isEnabled = false
        analyzeButton.alpha = 0.55f
        resultText.text = "Choose a photo to begin."
    }

    private fun analyzePhoto() {
        val file = photoFile ?: return
        analyzeButton.isEnabled = false
        analyzeButton.text = "ANALYZING DISASTER SIGNALS…"
        resultText.text = "Uploading photo, resolving location, and running flood/damage/fire analysis…"
        refreshLocation()
        val fields = mutableMapOf("user_id" to userId())
        if (location.exact) {
            fields["lat"] = location.latitude.toString()
            fields["lng"] = location.longitude.toString()
        }
        executor.execute {
            try {
                val report = NetworkClient.postMultipart(
                    AppConfig.endpoint(AppConfig.visionBackend(this), "/analyze"), file, fields
                )
                runOnUiThread {
                    analyzeButton.isEnabled = true
                    analyzeButton.text = "⚡ RUN ANALYSIS & BROADCAST"
                    renderAnalysis(report)
                    setBackendOnline(true)
                }
                val analysis = report.optJSONObject("analysis")
                if (analysis?.optBoolean("is_problem") == true) {
                    handleAlert(
                        JSONObject()
                            .put("id", "my-${System.currentTimeMillis()}")
                            .put("timestamp_utc", report.optString("report_generated_utc"))
                            .put("disaster_type", analysis.optString("disaster_type"))
                            .put("verdict", analysis.optString("verdict"))
                            .put("severity", analysis.optString("severity"))
                            .put("severity_score", analysis.optDouble("severity_score"))
                            .put("confidence_pct", analysis.optDouble("confidence_pct"))
                            .put("location", report.optJSONObject("location"))
                            .put("user_id", "You"),
                        showPopup = false
                    )
                }
            } catch (error: Exception) {
                runOnUiThread {
                    analyzeButton.isEnabled = true
                    analyzeButton.text = "⚡ RUN ANALYSIS & BROADCAST"
                    resultText.text = "Analysis failed: ${error.message}\n\nCheck the image-analysis backend address in Backend Connection Settings and confirm the server is running."
                    resultText.setTextColor(getColor(R.color.danger))
                    setBackendOnline(false)
                }
            }
        }
    }

    private fun renderAnalysis(report: JSONObject) {
        val analysis = report.optJSONObject("analysis") ?: JSONObject()
        val loc = report.optJSONObject("location") ?: JSONObject()
        val admin = report.optJSONObject("admin_transmission") ?: JSONObject()
        val locationLine = if (!loc.isNull("lat") && !loc.isNull("lng")) {
            "${loc.optDouble("lat")}, ${loc.optDouble("lng")} (${loc.optString("source")})"
        } else "Unavailable (no phone GPS or EXIF GPS)"
        resultText.text = buildString {
            append(analysis.optString("verdict", "Analysis complete"))
            append("\n\nType: ${analysis.optString("disaster_type")}")
            append("\nSeverity: ${analysis.optString("severity")} (score ${analysis.optDouble("severity_score")})")
            append("\nConfidence: ${analysis.optDouble("confidence_pct")}%")
            append("\n\n🌊 Flood water: ${if (analysis.optBoolean("flood_detected")) "DETECTED" else "None"} (${analysis.optDouble("water_coverage_pct")}% coverage)")
            append("\n🏚 Structural damage: ${if (analysis.optBoolean("damage_detected")) "DETECTED" else "None"} (texture ${analysis.optDouble("texture_score")})")
            append("\n🔥 Fire / thermal: ${if (analysis.optBoolean("fire_detected")) "DETECTED" else "None"} (${analysis.optDouble("fire_coverage_pct")}% fire-color; ${analysis.optDouble("dark_background_pct")}% dark background)")
            append("\n📍 Location: $locationLine")
            append("\n\nAdmin prediction index: ${if (admin.optBoolean("transmitted")) "report forwarded" else "not forwarded — ${admin.optString("detail", "endpoint not configured")}"}")
        }
        resultText.setTextColor(getColor(if (analysis.optBoolean("is_problem")) R.color.danger else R.color.badge_green_text))
    }

    private fun pollBackend() {
        executor.execute {
            val base = AppConfig.visionBackend(this)
            val online = runCatching { NetworkClient.getJson(AppConfig.endpoint(base, "/health"), 5_000) }.isSuccess
            runOnUiThread { setBackendOnline(online) }
            if (online) {
                val history = runCatching { NetworkClient.getJson(AppConfig.endpoint(base, "/alerts"), 5_000) }
                    .getOrNull()?.optJSONArray("alerts")
                if (history != null) for (i in 0 until history.length()) {
                    history.optJSONObject(i)?.let { handleAlert(it, showPopup = i == history.length() - 1) }
                }
            }
        }
        handler.removeCallbacks(pollRunnable)
        if (running) handler.postDelayed(pollRunnable, POLL_MS)
    }

    private fun connectLiveAlerts() {
        webSocket?.cancel()
        val httpBase = AppConfig.visionBackend(this)
        val wsBase = when {
            httpBase.startsWith("https://") -> "wss://${httpBase.removePrefix("https://")}" 
            httpBase.startsWith("http://") -> "ws://${httpBase.removePrefix("http://")}" 
            else -> "ws://$httpBase"
        }
        webSocket = webSocketClient.newWebSocket(
            Request.Builder().url(AppConfig.endpoint(wsBase, "/ws/alerts")).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("nepalsafe-listening")
                    runOnUiThread { setBackendOnline(true) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (message.optString("type")) {
                        "DISASTER_ALERT" -> message.optJSONObject("alert")?.let { handleAlert(it, showPopup = true) }
                        "INIT_ALERTS" -> {
                            val initial = message.optJSONArray("alerts") ?: JSONArray()
                            for (index in initial.length() - 1 downTo 0) {
                                initial.optJSONObject(index)?.let { handleAlert(it, showPopup = false) }
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (running) handler.postDelayed({ if (running) connectLiveAlerts() }, 5_000L)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (running) handler.postDelayed({ if (running) connectLiveAlerts() }, 5_000L)
                }
            }
        )
    }

    private fun handleAlert(alert: JSONObject, showPopup: Boolean) {
        val id = alert.optString("id").ifBlank { "${alert.optString("timestamp_utc")}-${alert.optString("disaster_type")}" }
        synchronized(seenAlertIds) {
            if (!seenAlertIds.add(id)) return
            alerts.add(0, alert)
            while (alerts.size > 20) alerts.removeAt(alerts.lastIndex)
        }
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                renderFeed()
                if (showPopup && running) showDisasterPopup(alert)
            }
        }
    }

    private fun renderFeed() {
        feed.removeAllViews()
        feedEmpty.visibility = if (alerts.isEmpty()) View.VISIBLE else View.GONE
        val snapshot = synchronized(seenAlertIds) { alerts.toList() }
        snapshot.forEach { alert ->
            val card = TextView(this).apply {
                val loc = alert.optJSONObject("location")
                text = buildString {
                    append("⚠ ${alert.optString("disaster_type")}\n")
                    append("${alert.optString("severity")} • ${alert.optDouble("confidence_pct")}% confidence")
                    if (loc != null && !loc.isNull("lat")) append("\n📍 ${loc.optDouble("lat")}, ${loc.optDouble("lng")}")
                    append("\n🕒 ${friendlyTime(alert.optString("timestamp_utc"))}")
                }
                textSize = 13f
                setTextColor(getColor(R.color.navy_soft))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                setBackgroundResource(R.drawable.bg_banner_alert)
            }
            feed.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    private fun showDisasterPopup(alert: JSONObject) {
        vibrateAlert()
        val type = alert.optString("disaster_type")
        val advice = when {
            type.contains("flood", true) -> "Stay clear of low waterways and flooded roads. Move to higher ground immediately."
            type.contains("fire", true) -> "Evacuate by marked safe routes and stay upwind from smoke."
            else -> "Avoid damaged buildings and watch for falling debris or unstable ground."
        }
        val loc = alert.optJSONObject("location")
        AlertDialog.Builder(this)
            .setTitle("🚨 EMERGENCY ALERT")
            .setMessage(
                "CRITICAL DISASTER DETECTED\n\n$type\n\n" +
                    "Severity: ${alert.optString("severity")} (score ${alert.optDouble("severity_score")})\n" +
                    (if (loc != null && !loc.isNull("lat")) "Location: ${loc.optDouble("lat")}, ${loc.optDouble("lng")}\n\n" else "\n") +
                    "⚠ Urgent safety advice:\n$advice"
            )
            .setPositiveButton("I UNDERSTAND", null)
            .show()
    }

    private fun vibrateAlert() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 700), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 500, 200, 500, 200, 700), -1)
        }
    }

    private fun setBackendOnline(online: Boolean) {
        backendStatus.text = if (online) "● Backend online • live community alerts active" else "● Backend unavailable • check settings/server"
        backendStatus.setTextColor(getColor(if (online) R.color.badge_green_bg else R.color.warning_border))
    }

    private fun userId(): String {
        val prefs = getSharedPreferences(LifelineService.IDENTITY_PREFERENCES, MODE_PRIVATE)
        return prefs.getString(LifelineService.KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(LifelineService.KEY_DEVICE_ID, it).apply() }
    }

    private fun friendlyTime(raw: String): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val instant = java.time.Instant.parse(raw)
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date.from(instant))
        } else raw
    }.getOrDefault(raw)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CAMERA_PERMISSION = 501
        private const val CAPTURE_PHOTO = 502
        private const val PICK_PHOTO = 503
        private const val POLL_MS = 4_000L
    }
}
