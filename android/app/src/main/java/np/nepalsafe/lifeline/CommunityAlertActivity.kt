package np.nepalsafe.lifeline

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class CommunityAlertActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var preview: ImageView
    private lateinit var result: TextView
    private lateinit var sendButton: Button
    private lateinit var spinner: Spinner
    private lateinit var severityGroup: RadioGroup
    private lateinit var caption: EditText
    private var photoFile: File? = null
    private var photoUri: Uri? = null
    private var location = AppLocation(LocationSupport.FALLBACK_LAT, LocationSupport.FALLBACK_LNG, false, "fallback")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_community_alert)
        window.statusBarColor = getColor(R.color.navy)
        preview = findViewById(R.id.communityPhotoPreview)
        result = findViewById(R.id.communityAlertResult)
        sendButton = findViewById(R.id.sendCommunityAlertButton)
        spinner = findViewById(R.id.communityHazardSpinner)
        severityGroup = findViewById(R.id.communitySeverityGroup)
        caption = findViewById(R.id.communityCaptionInput)

        location = LocationSupport.bestAvailable(this)
        findViewById<TextView>(R.id.communityAlertLocation).text =
            "📍 ${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)} • ${location.source}"
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            HAZARD_LABELS
        )
        findViewById<Button>(R.id.takeCommunityPhotoButton).setOnClickListener { ensureCameraThenCapture() }
        findViewById<Button>(R.id.pickCommunityPhotoButton).setOnClickListener { choosePhoto() }
        sendButton.setOnClickListener { sendAlert() }
    }

    private fun ensureCameraThenCapture() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        } else capturePhoto()
    }

    private fun capturePhoto() {
        val file = File.createTempFile("community_alert_", ".jpg", cacheDir)
        photoFile = file
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivityForResult(
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            CAPTURE_PHOTO
        )
    }

    private fun choosePhoto() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            },
            PICK_PHOTO
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            capturePhoto()
        } else if (requestCode == CAMERA_PERMISSION) {
            Toast.makeText(this, "Camera permission is required to take a photo", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses platform activity results supported by minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            CAPTURE_PHOTO -> showPhoto(photoUri)
            PICK_PHOTO -> {
                val uri = data?.data ?: return
                runCatching { PhotoSupport.copyToCache(this, uri, "community_picked_") }
                    .onSuccess { file ->
                        photoFile = file
                        photoUri = Uri.fromFile(file)
                        showPhoto(uri)
                    }
                    .onFailure { Toast.makeText(this, it.message ?: "Could not open photo", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showPhoto(uri: Uri?) {
        preview.setImageURI(uri)
        preview.visibility = View.VISIBLE
        result.text = "Photo ready. It will be compressed below 100 KB when sent."
    }

    private fun sendAlert() {
        location = LocationSupport.bestAvailable(this)
        if (!location.exact) {
            result.text = "Waiting for your location. Enable location and allow permission on the dashboard."
            return
        }
        sendButton.isEnabled = false
        result.text = "Preparing and sending alert…"
        val selectedHazard = HAZARD_IDS[spinner.selectedItemPosition.coerceIn(0, HAZARD_IDS.lastIndex)]
        val selectedSeverity = when (severityGroup.checkedRadioButtonId) {
            R.id.severitySmall -> 3
            R.id.severityVeryBad -> 9
            else -> 6
        }
        val captionText = caption.text.toString().trim()
        val selectedPhoto = photoFile
        executor.execute {
            val identity = getSharedPreferences(LifelineService.IDENTITY_PREFERENCES, MODE_PRIVATE)
            val deviceId = identity.getString(LifelineService.KEY_DEVICE_ID, null)
                ?: UUID.randomUUID().toString().also { identity.edit().putString(LifelineService.KEY_DEVICE_ID, it).apply() }
            val photoData = runCatching { selectedPhoto?.let(PhotoSupport::compressedDataUri) }.getOrNull()
            val body = JSONObject()
                .put("lat", location.latitude)
                .put("lon", location.longitude)
                .put("severity", selectedSeverity)
                .put("hazard", selectedHazard)
                .put("caption", captionText)
                .put("photo_url", photoData ?: JSONObject.NULL)
                .put("device_id", deviceId)
                .put("name", identity.getString("sender_name", "Someone nearby"))
            try {
                val response = NetworkClient.postJson(
                    AppConfig.endpoint(AppConfig.warningBackend(this), "/api/alert"),
                    body,
                    30_000
                )
                val current = response.optJSONObject("place_now")
                runOnUiThread {
                    sendButton.isEnabled = true
                    result.text = "✓ Alert sent to ${response.optInt("notified_count")} nearby people.\nCondition is now: ${current?.optString("verdict", "updated")}."
                    result.setTextColor(getColor(R.color.badge_green_text))
                }
            } catch (_: Exception) {
                val queued = OfflineQueue.enqueue(this, "alert", body)
                runOnUiThread {
                    sendButton.isEnabled = true
                    result.text = "No network. Alert saved on this phone ($queued queued) and will send automatically when the warning service reconnects."
                    result.setTextColor(getColor(R.color.warning_text))
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CAMERA_PERMISSION = 401
        private const val CAPTURE_PHOTO = 402
        private const val PICK_PHOTO = 403
        private val HAZARD_IDS = listOf("landslide", "flood", "ground_crack", "road_blocked")
        private val HAZARD_LABELS = listOf(
            "⛰️ Land or snow slipping",
            "🌊 Water rising",
            "🪨 Cracks in the ground",
            "🚧 Trail blocked"
        )
    }
}
