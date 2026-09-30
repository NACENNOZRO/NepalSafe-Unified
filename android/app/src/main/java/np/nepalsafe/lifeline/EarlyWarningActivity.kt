package np.nepalsafe.lifeline

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class EarlyWarningActivity : Activity() {
    private lateinit var connectionStatus: TextView
    private lateinit var updatedAt: TextView
    private lateinit var nearbySummary: TextView
    private lateinit var queueSummary: TextView
    private lateinit var placeName: TextView
    private lateinit var placeRegion: TextView
    private lateinit var placeVerdict: TextView
    private lateinit var placeReason: TextView
    private lateinit var weatherSummary: TextView
    private lateinit var hazardContainer: LinearLayout
    private lateinit var resolveButton: Button

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val loading = AtomicBoolean(false)
    private var snapshot: JSONObject? = null
    private var forecast: JSONObject? = null
    private var people = JSONArray()
    private var nearbySos = JSONArray()
    private var helpRequests = JSONArray()
    private var location = AppLocation(LocationSupport.FALLBACK_LAT, LocationSupport.FALLBACK_LNG, false, "fallback")
    private var mySosId: String? = null
    private lateinit var deviceId: String

    private val periodicRefresh = object : Runnable {
        override fun run() {
            loadAll(showSpinner = false)
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val periodicCheckIn = object : Runnable {
        override fun run() {
            checkIn()
            handler.postDelayed(this, CHECK_IN_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_early_warning)
        window.statusBarColor = getColor(R.color.navy)
        bindViews()

        val identity = getSharedPreferences(LifelineService.IDENTITY_PREFERENCES, MODE_PRIVATE)
        deviceId = identity.getString(LifelineService.KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also {
                identity.edit().putString(LifelineService.KEY_DEVICE_ID, it).apply()
            }
        mySosId = getPreferences(MODE_PRIVATE).getString("online_sos_id", null)
        resolveButton.visibility = if (mySosId == null) View.GONE else View.VISIBLE

        findViewById<Button>(R.id.refreshWarningButton).setOnClickListener { loadAll(showSpinner = true) }
        findViewById<Button>(R.id.onlineSosButton).setOnClickListener { showOnlineSosDialog() }
        findViewById<Button>(R.id.communityButton).setOnClickListener { showCommunity() }
        findViewById<Button>(R.id.photoAlertButton).setOnClickListener {
            startActivity(Intent(this, CommunityAlertActivity::class.java))
        }
        findViewById<Button>(R.id.routeButton).setOnClickListener { loadSafeRoute() }
        findViewById<Button>(R.id.mapButton).setOnClickListener { openMap() }
        findViewById<Button>(R.id.detailsButton).setOnClickListener { showFullDetails() }
        resolveButton.setOnClickListener { resolveMySos() }

        loadCachedSnapshot()
        location = LocationSupport.bestAvailable(this)
    }

    override fun onStart() {
        super.onStart()
        loadAll(showSpinner = snapshot == null)
        handler.postDelayed(periodicRefresh, REFRESH_MS)
        handler.postDelayed(periodicCheckIn, 1_000L)
    }

    override fun onStop() {
        handler.removeCallbacks(periodicRefresh)
        handler.removeCallbacks(periodicCheckIn)
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        connectionStatus = findViewById(R.id.warningConnectionStatus)
        updatedAt = findViewById(R.id.warningUpdatedAt)
        nearbySummary = findViewById(R.id.nearbySummary)
        queueSummary = findViewById(R.id.offlineQueueSummary)
        placeName = findViewById(R.id.placeName)
        placeRegion = findViewById(R.id.placeRegion)
        placeVerdict = findViewById(R.id.placeVerdict)
        placeReason = findViewById(R.id.placeReason)
        weatherSummary = findViewById(R.id.weatherSummary)
        hazardContainer = findViewById(R.id.hazardContainer)
        resolveButton = findViewById(R.id.resolveOnlineSosButton)
    }

    private fun loadAll(showSpinner: Boolean) {
        if (!loading.compareAndSet(false, true)) return
        if (showSpinner) connectionStatus.text = "Refreshing live conditions…"
        location = LocationSupport.bestAvailable(this)
        executor.execute {
            try {
                val base = AppConfig.warningBackend(this)
                val fresh = NetworkClient.getJson(AppConfig.endpoint(base, "/api/now"))
                val freshForecast = runCatching {
                    NetworkClient.getJson(AppConfig.endpoint(base, "/api/forecast"))
                }.getOrNull()
                getSharedPreferences(CACHE_PREFS, MODE_PRIVATE).edit()
                    .putString("snapshot", fresh.toString())
                    .putLong("saved_at", System.currentTimeMillis())
                    .apply()
                val flushed = runCatching { OfflineQueue.flush(this) }.getOrDefault(0)
                snapshot = fresh
                if (freshForecast != null) forecast = freshForecast
                runOnUiThread {
                    connectionStatus.text = "● Live warning service connected"
                    connectionStatus.setTextColor(getColor(R.color.badge_green_text))
                    if (flushed > 0) Toast.makeText(this, "$flushed saved message(s) delivered", Toast.LENGTH_SHORT).show()
                    renderSnapshot(fresh, cached = false)
                    renderQueue()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    connectionStatus.text = "● Service unavailable — ${if (snapshot != null) "showing saved conditions" else "no saved conditions"}"
                    connectionStatus.setTextColor(getColor(R.color.warning_text))
                    snapshot?.let { renderSnapshot(it, cached = true) }
                    renderQueue()
                }
            } finally {
                loading.set(false)
            }
        }
    }

    private fun checkIn() {
        location = LocationSupport.bestAvailable(this)
        if (!location.exact) return
        val name = getSharedPreferences(LifelineService.IDENTITY_PREFERENCES, MODE_PRIVATE)
            .getString("sender_name", "Someone").orEmpty().ifBlank { "Someone" }
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("name", name)
            .put("lat", location.latitude)
            .put("lon", location.longitude)
            .put("status", if (mySosId == null) "ok" else "needs_help")
            .put("online", true)
        executor.execute {
            runCatching {
                NetworkClient.postJson(
                    AppConfig.endpoint(AppConfig.warningBackend(this), "/api/check-in"),
                    body
                )
            }.onSuccess { response ->
                people = response.optJSONArray("people_nearby") ?: JSONArray()
                nearbySos = response.optJSONArray("sos_nearby") ?: JSONArray()
                helpRequests = response.optJSONArray("help_requests_for_you") ?: JSONArray()
                runOnUiThread { renderCommunitySummary() }
            }
        }
    }

    private fun loadCachedSnapshot() {
        val prefs = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        val raw = prefs.getString("snapshot", null) ?: return
        runCatching { JSONObject(raw) }.onSuccess {
            snapshot = it
            renderSnapshot(it, cached = true)
        }
    }

    private fun renderSnapshot(data: JSONObject, cached: Boolean) {
        val place = data.optJSONObject("place") ?: return
        placeName.text = place.optString("name", "Langtang Lirung")
        placeRegion.text = place.optString("region")
        val risk = place.optDouble("risk", 0.0)
        val band = place.optString("band", "safe")
        placeVerdict.text = "${place.optString("verdict", "Conditions available")} • ${risk.toInt()}%"
        placeVerdict.setTextColor(bandColor(band))
        val reasons = place.optJSONArray("reasons")
        placeReason.text = if (reasons != null && reasons.length() > 0) reasons.optString(0) else place.optString("advice")
        val weather = place.optJSONObject("weather")
        weatherSummary.text = if (weather == null) "Weather measurements unavailable" else {
            "Temperature ${weather.optDouble("temperature").toInt()}°C  •  Rain 24h ${weather.optDouble("rain_24h")} mm\n" +
                "Wet ground ${(weather.optDouble("soil_water") * 100).toInt()}%  •  Wind ${weather.optDouble("wind_kmh")} km/h"
        }
        val stamp = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE).getLong("saved_at", System.currentTimeMillis())
        updatedAt.text = if (cached) {
            "Saved ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(stamp))}"
        } else {
            "Updated ${data.optString("updated_at")}"
        }
        renderHazards(place.optJSONObject("multi_hazard_cards") ?: JSONObject())
    }

    private fun renderHazards(cards: JSONObject) {
        hazardContainer.removeAllViews()
        listOf("earthquake", "landslide", "rainfall", "snowfall").forEach { key ->
            val card = cards.optJSONObject(key) ?: return@forEach
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setBackgroundResource(R.drawable.bg_card)
                isClickable = true
                isFocusable = true
                setOnClickListener { showHazardDetails(key, card) }
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(10), dp(16), 0)
            }
            wrapper.layoutParams = params
            wrapper.addView(TextView(this).apply {
                text = "${card.optString("icon")}  ${card.optString("name")}     ${card.optString("band").uppercase()} ${card.optDouble("score").toInt()}%"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(bandColor(card.optString("band")))
            })
            wrapper.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = card.optDouble("score").toInt()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(9) }
            })
            wrapper.addView(TextView(this).apply {
                text = card.optString("detail")
                textSize = 13f
                setTextColor(getColor(R.color.navy_soft))
                setPadding(0, dp(8), 0, 0)
            })
            wrapper.addView(TextView(this).apply {
                text = "${card.optString("status")}  •  Analysis ›"
                textSize = 11f
                setTextColor(getColor(R.color.muted))
                setPadding(0, dp(7), 0, 0)
            })
            hazardContainer.addView(wrapper)
        }
    }

    private fun showHazardDetails(key: String, card: JSONObject) {
        val pipeline = pipelineFor(key)
        val forecastText = if ((key == "rainfall" || key == "snowfall") && forecast != null) {
            buildForecastText(forecast!!)
        } else ""
        AlertDialog.Builder(this)
            .setTitle("${card.optString("icon")} ${card.optString("name")} • ${card.optString("band").uppercase()} ${card.optDouble("score").toInt()}%")
            .setMessage(
                "${card.optString("detail")}\n\n" +
                    "1. DETECT\n${pipeline[0]}\n\n" +
                    "2. ANALYSE\n${pipeline[1]}\n\n" +
                    "3. ALERT\n${pipeline[2]}\n\n" +
                    "4. RESPOND\n${pipeline[3]}" + forecastText
            )
            .setPositiveButton("CLOSE", null)
            .show()
    }

    private fun pipelineFor(key: String): List<String> = when (key) {
        "earthquake" -> listOf(
            "USGS seismometer feed and Sentinel-1 InSAR surface-deformation proxy.",
            "XGBoost processes shaking intensity, depth, and crustal deformation.",
            "Alerts trigger when shaking or expected acceleration exceeds safety thresholds.",
            "• DROP, COVER, and HOLD ON.\n• Stay away from glass and outer walls.\n• Move away from steep slopes after shaking stops."
        )
        "landslide" -> listOf(
            "NASA SRTM 30m slope, satellite soil water, and rainfall accumulation.",
            "XGBoost evaluates saturation on slopes above 30 degrees.",
            "Orange/red alert when heavy rain falls on saturated slopes.",
            "• Listen for rumbling or snapping trees.\n• Leave drainage channels and steep downhill paths.\n• Move to Kyanjin Gompa or the Langtang safe plateau."
        )
        "rainfall" -> listOf(
            "Open-Meteo high-resolution forecast and IMD Himalayan precipitation grid.",
            "1h, 6h, 24h, and 72h accumulation models estimate flash-flood surges.",
            "Alert when forecast rain exceeds 30mm/12h or catchments are saturated.",
            "• Move documents to high ground.\n• Stay clear of Langtang Khola.\n• Never cross flooded trails or fast water."
        )
        else -> listOf(
            "Open-Meteo alpine snowfall, freezing level, wind, and snow depth.",
            "XGBoost combines fresh load, wind slab formation, and temperature gradients.",
            "Warnings dispatch for heavy fresh snow with high winds.",
            "• Avoid open slopes over 25 degrees.\n• Stay in heated community buildings.\n• Wear insulated windproof layers."
        )
    }

    private fun buildForecastText(data: JSONObject): String {
        val timeline = data.optJSONArray("timeline") ?: return ""
        val rows = buildList {
            for (index in 0 until minOf(timeline.length(), 10)) {
                val item = timeline.optJSONObject(index) ?: continue
                add("${item.optString("time", "+${item.optInt("hour_offset")}h")}: rain ${item.optDouble("rain_mm")}mm, snow ${item.optDouble("snow_cm")}cm, ${item.optDouble("temp_c").toInt()}°C")
            }
        }
        return if (rows.isEmpty()) "" else "\n\n72-HOUR FORECAST\n${rows.joinToString("\n")}"
    }

    private fun renderCommunitySummary() {
        val needsHelp = (0 until people.length()).count {
            people.optJSONObject(it)?.optString("status") == "needs_help"
        } + nearbySos.length()
        nearbySummary.text = buildString {
            val count = people.length()
            append("$count ${if (count == 1) "person" else "people"} nearby")
            if (needsHelp > 0) append(" • $needsHelp need help")
            if (helpRequests.length() > 0) append("\n${helpRequests.length()} direct request(s) waiting for you")
        }
    }

    private fun renderQueue() {
        val count = OfflineQueue.count(this)
        queueSummary.visibility = if (count > 0) View.VISIBLE else View.GONE
        queueSummary.text = "$count message(s) saved on this phone and waiting for network"
    }

    private fun requireCurrentLocation(): Boolean {
        location = LocationSupport.bestAvailable(this)
        if (location.exact) return true
        Toast.makeText(this, "Waiting for your location. Enable location and allow permission on the dashboard.", Toast.LENGTH_LONG).show()
        return false
    }

    private fun showOnlineSosDialog() {
        if (!requireCurrentLocation()) return
        val identity = getSharedPreferences(LifelineService.IDENTITY_PREFERENCES, MODE_PRIVATE)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val name = EditText(this).apply {
            hint = "Your name"
            setText(identity.getString("sender_name", ""))
        }
        val message = EditText(this).apply { hint = "What is happening? (optional)" }
        form.addView(name)
        form.addView(message)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Send an emergency call")
            .setMessage("Your location is attached. Without network, this is saved and relayed when a connection returns.")
            .setView(form)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SEND", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val sender = name.text.toString().trim()
                if (sender.isEmpty()) {
                    name.error = "Name is required"
                    return@setOnClickListener
                }
                if (!requireCurrentLocation()) return@setOnClickListener
                identity.edit().putString("sender_name", sender).apply()
                val body = JSONObject()
                    .put("device_id", deviceId)
                    .put("name", sender)
                    .put("lat", location.latitude)
                    .put("lon", location.longitude)
                    .put("message", message.text.toString().trim())
                dialog.dismiss()
                sendOnlineSos(body)
            }
        }
        dialog.show()
    }

    private fun sendOnlineSos(body: JSONObject) {
        connectionStatus.text = "Sending online emergency call…"
        executor.execute {
            try {
                val response = NetworkClient.postJson(
                    AppConfig.endpoint(AppConfig.warningBackend(this), "/api/sos"), body
                )
                mySosId = response.optJSONObject("sent")?.optString("id")
                getPreferences(MODE_PRIVATE).edit().putString("online_sos_id", mySosId).apply()
                runOnUiThread {
                    resolveButton.visibility = View.VISIBLE
                    connectionStatus.text = "Emergency call sent"
                    AlertDialog.Builder(this)
                        .setTitle("Your call was sent")
                        .setMessage("Notified ${response.optInt("notified_count")} nearby people. Also call Nepal Police 100, Ambulance 102, or Mountain Rescue 1144 when phone service is available.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (_: Exception) {
                val count = OfflineQueue.enqueue(this, "sos", body)
                runOnUiThread {
                    renderQueue()
                    AlertDialog.Builder(this)
                        .setTitle("Saved for relay")
                        .setMessage("No server connection. This call is stored on the phone ($count queued) and will send automatically when network returns. The offline Lifeline dashboard can also relay an SOS directly phone-to-phone.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun showCommunity() {
        val lines = mutableListOf<String>()
        if (people.length() == 0) lines += "Nobody else has checked in nearby yet."
        for (index in 0 until people.length()) {
            val person = people.optJSONObject(index) ?: continue
            lines += "• ${person.optString("name")} — ${person.optString("distance_text")} ${person.optString("direction")}${if (person.optString("status") == "needs_help") " — NEEDS HELP" else ""}"
        }
        if (nearbySos.length() > 0) {
            lines += "\nOPEN SOS CALLS"
            for (index in 0 until nearbySos.length()) {
                val sos = nearbySos.optJSONObject(index) ?: continue
                lines += "• ${sos.optString("name")}: ${sos.optString("message")} (${sos.optString("distance_text")})"
            }
        }
        if (helpRequests.length() > 0) {
            val help = helpRequests.optJSONObject(0)
            lines += "\nREQUEST FOR YOU\n${help?.optString("from_name")}: ${help?.optString("message")}"
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("People nearby")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("CLOSE", null)
        if (people.length() > 0) builder.setNeutralButton("ASK NEAREST FOR HELP") { _, _ -> askNearestForHelp() }
        if (helpRequests.length() > 0) builder.setNegativeButton("I WILL HELP") { _, _ -> answerFirstHelpRequest() }
        builder.show()
    }

    private fun askNearestForHelp() {
        if (!requireCurrentLocation()) return
        val person = people.optJSONObject(0) ?: return
        val input = EditText(this).apply { hint = "What help do you need?" }
        AlertDialog.Builder(this)
            .setTitle("Ask ${person.optString("name")} for help")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SEND") { _, _ ->
                val identity = getSharedPreferences(LifelineService.IDENTITY_PREFERENCES, MODE_PRIVATE)
                val body = JSONObject()
                    .put("device_id", deviceId)
                    .put("name", identity.getString("sender_name", "Someone"))
                    .put("to_device", person.optString("device_id"))
                    .put("lat", location.latitude)
                    .put("lon", location.longitude)
                    .put("message", input.text.toString().ifBlank { "I need help" })
                executor.execute {
                    val sent = runCatching {
                        NetworkClient.postJson(AppConfig.endpoint(AppConfig.warningBackend(this), "/api/ask-help"), body)
                    }.isSuccess
                    if (!sent) OfflineQueue.enqueue(this, "ask", body)
                    runOnUiThread { Toast.makeText(this, if (sent) "Help request sent" else "Help request saved for later", Toast.LENGTH_LONG).show() }
                }
            }.show()
    }

    private fun answerFirstHelpRequest() {
        val id = helpRequests.optJSONObject(0)?.optString("id") ?: return
        executor.execute {
            val url = AppConfig.endpoint(AppConfig.warningBackend(this), "/api/answer-help/$id") +
                "?device_id=${encode(deviceId)}"
            val success = runCatching { NetworkClient.postJson(url, JSONObject()) }.isSuccess
            runOnUiThread {
                Toast.makeText(this, if (success) "The caller was told you will help" else "Could not answer yet", Toast.LENGTH_LONG).show()
                if (success) checkIn()
            }
        }
    }

    private fun resolveMySos() {
        val id = mySosId ?: return
        executor.execute {
            val success = runCatching {
                NetworkClient.postJson(
                    AppConfig.endpoint(AppConfig.warningBackend(this), "/api/sos/$id/resolve"), JSONObject()
                )
            }.isSuccess
            runOnUiThread {
                if (success) {
                    mySosId = null
                    getPreferences(MODE_PRIVATE).edit().remove("online_sos_id").apply()
                    resolveButton.visibility = View.GONE
                    Toast.makeText(this, "SOS marked resolved", Toast.LENGTH_SHORT).show()
                    checkIn()
                } else Toast.makeText(this, "Could not resolve SOS", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSafeRoute() {
        if (!requireCurrentLocation()) return
        executor.execute {
            try {
                val url = AppConfig.endpoint(AppConfig.warningBackend(this), "/api/route") +
                    "?lat=${location.latitude}&lon=${location.longitude}"
                val route = NetworkClient.getJson(url)
                val point = route.optJSONObject("go_to") ?: JSONObject()
                val steps = route.optJSONArray("steps") ?: JSONArray()
                val body = buildString {
                    append("${point.optString("name")} • ${point.optDouble("distance_km")} km\n")
                    append("${point.optString("kind")} • capacity ${point.optInt("capacity")}\n\n")
                    append(route.optString("instruction"))
                    for (index in 0 until steps.length()) append("\n\n${index + 1}. ${steps.optString(index)}")
                }
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Where to go").setMessage(body)
                        .setNeutralButton("OPEN IN MAP") { _, _ -> openExternalMap(point.optDouble("lat"), point.optDouble("lon"), point.optString("name")) }
                        .setPositiveButton("CLOSE", null).show()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Safe route unavailable offline")
                        .setMessage("No verified route is available. Follow local emergency authority instructions; do not assume a saved route is safe.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun openMap() {
        val data = snapshot ?: return Toast.makeText(this, "Map data is not available yet", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MapActivity::class.java).apply {
            putExtra("snapshot", data.toString())
            putExtra("hasLocation", location.exact)
            putExtra("lat", location.latitude)
            putExtra("lng", location.longitude)
            putExtra("people", people.toString())
            putExtra("sos", nearbySos.toString())
        })
    }

    private fun openExternalMap(lat: Double, lng: Double, label: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${encode(label)})")))
        }
    }

    private fun showFullDetails() {
        val place = snapshot?.optJSONObject("place") ?: return
        val steps = place.optJSONArray("what_to_do") ?: JSONArray()
        val reasons = place.optJSONArray("reasons") ?: JSONArray()
        val hazards = place.optJSONObject("hazards") ?: JSONObject()
        val body = buildString {
            append("Risk ${place.optDouble("risk").toInt()}% • ${place.optString("main_hazard_name")}\n")
            append("Elevation ${place.optInt("elevation_m")}m • slope ${place.optDouble("slope_deg")}°\n\nWHY\n")
            for (index in 0 until reasons.length()) append("• ${reasons.optString(index)}\n")
            append("\nWHAT TO DO\n")
            for (index in 0 until steps.length()) append("${index + 1}. ${steps.optString(index)}\n")
            append("\nALL MODEL SCORES\n")
            hazards.keys().forEach { key -> append("${key.replaceFirstChar(Char::uppercase)}: ${hazards.optDouble(key).toInt()}%\n") }
        }
        AlertDialog.Builder(this).setTitle("Full conditions for ${place.optString("name")}")
            .setMessage(body).setPositiveButton("CLOSE", null).show()
    }

    private fun bandColor(band: String): Int = when (band.lowercase()) {
        "danger" -> Color.rgb(220, 38, 38)
        "warning" -> Color.rgb(234, 88, 12)
        "watch" -> Color.rgb(202, 138, 4)
        else -> Color.rgb(21, 128, 61)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        private const val CACHE_PREFS = "nepalsafe_warning_cache"
        private const val REFRESH_MS = 90_000L
        private const val CHECK_IN_MS = 30_000L
    }
}
