package com.example.disasterreport

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MainActivity
 * Orchestrates the full citizen flow:
 *   1. Request camera + location permissions
 *   2. Launch camera, get the photo file
 *   3. Get live GPS (Module 4, client side) -- falls back gracefully to
 *      null if permission denied or no fix; backend then falls back to
 *      the photo's EXIF GPS, and finally to "unavailable"
 *   4. Upload photo + lat/lng to the backend (Modules 1-3 + 4 + 5 run there)
 *   5. Show the returned verdict/severity to the user
 *
 * This is a minimal, single-screen reference implementation -- wire it
 * into your app's actual navigation/UI as needed.
 */
class MainActivity : ComponentActivity() {

    private lateinit var locationHelper: LocationHelper
    private var photoFile: File? = null
    private var photoUri: Uri? = null

    private lateinit var resultText: TextView
    private lateinit var previewImage: ImageView

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Camera and location permissions are required to submit a report.", Toast.LENGTH_LONG).show()
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoFile != null) {
            previewImage.setImageURI(photoUri)
            submitReport()
        } else {
            Toast.makeText(this, "Photo capture failed or cancelled.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        locationHelper = LocationHelper(this)
        resultText = findViewById(R.id.resultText)
        previewImage = findViewById(R.id.previewImage)

        val captureButton: Button = findViewById(R.id.captureButton)
        captureButton.setOnClickListener {
            ensurePermissionsThenCapture()
        }
    }

    private fun ensurePermissionsThenCapture() {
        val missing = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            launchCamera()
        }
    }

    private fun launchCamera() {
        val file = File.createTempFile("crack_photo_", ".jpg", cacheDir)
        photoFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        photoUri = uri
        // Fixed during troubleshooting: TakePicture's launch() requires a
        // non-null Uri, but photoUri is declared nullable (var photoUri:
        // Uri? = null) so it could carry state across the callback. Using
        // the local non-null `uri` here instead of the nullable field
        // avoids a compile-time type mismatch.
        takePictureLauncher.launch(uri)
    }

    private fun submitReport() {
        val file = photoFile ?: return
        resultText.text = "Getting location..."

        lifecycleScope.launch {
            // Module 4 (client side): try live GPS first. Null is fine --
            // the backend falls back to EXIF, then to "unavailable".
            val location = locationHelper.getCurrentLocation()

            resultText.text = "Uploading and analyzing..."

            val userId = getCurrentUserId() // wire up to your auth/session layer
            val result = withContext(Dispatchers.IO) {
                try {
                    ApiClient.submitPhoto(
                        imageFile = file,
                        userId = userId,
                        lat = location?.first,
                        lng = location?.second
                    )
                } catch (e: Exception) {
                    null
                }
            }

            if (result == null) {
                resultText.text = "Upload failed. Check your connection and try again."
                return@launch
            }

            renderResult(result)
        }
    }

    private fun renderResult(result: AnalysisResult) {
        val a = result.analysis
        val loc = result.location
        val locStr = if (loc.lat != null && loc.lng != null) {
            "${loc.lat}, ${loc.lng} (${loc.source})"
        } else {
            "Location unavailable"
        }
        val adminStr = if (result.admin_transmission.transmitted) {
            "Sent to admin index"
        } else {
            "Not yet sent to admin index (${result.admin_transmission.detail ?: "unknown reason"})"
        }

        resultText.text = buildString {
            appendLine(a.verdict)
            appendLine("Severity: ${a.severity} (${a.confidence_pct}% confidence)")
            appendLine("Location: $locStr")
            appendLine(adminStr)
        }
    }

    /** Replace with your real user/session id lookup. */
    private fun getCurrentUserId(): String? = null
}
