package np.nepalsafe.lifeline

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class EndpointSettingsActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_endpoint_settings)
        window.statusBarColor = getColor(R.color.navy)
        val warningInput = findViewById<EditText>(R.id.warningBackendInput)
        val visionInput = findViewById<EditText>(R.id.visionBackendInput)
        val result = findViewById<TextView>(R.id.endpointTestResult)
        val test = findViewById<Button>(R.id.testEndpointsButton)
        warningInput.setText(AppConfig.warningBackend(this))
        visionInput.setText(AppConfig.visionBackend(this))

        fun validated(): Pair<String, String>? {
            warningInput.error = AppConfig.validationError(warningInput.text.toString())
            visionInput.error = AppConfig.validationError(visionInput.text.toString())
            if (warningInput.error != null || visionInput.error != null) return null
            return AppConfig.normalizeBaseUrl(warningInput.text.toString()) to
                AppConfig.normalizeBaseUrl(visionInput.text.toString())
        }

        findViewById<Button>(R.id.saveEndpointsButton).setOnClickListener {
            val urls = validated() ?: return@setOnClickListener
            AppConfig.save(this, urls.first, urls.second)
            warningInput.setText(urls.first)
            visionInput.setText(urls.second)
            Toast.makeText(this, "Connections saved", Toast.LENGTH_SHORT).show()
        }
        test.setOnClickListener {
            val urls = validated() ?: return@setOnClickListener
            test.isEnabled = false
            result.text = "Checking available features…"
            executor.execute {
                val checks = listOf(
                    Triple("Hazard readings", AppConfig.endpoint(urls.first, "/api/now"), "place"),
                    Triple("Forecast", AppConfig.endpoint(urls.first, "/api/forecast"), "timeline"),
                    Triple("Photo service", AppConfig.endpoint(urls.second, "/health"), "status"),
                    Triple("Community alerts", AppConfig.endpoint(urls.second, "/alerts"), "alerts")
                ).map { (label, url, key) ->
                    val success = runCatching {
                        val data = NetworkClient.getJson(url, 4000)
                        require(data.has(key) && !data.isNull(key)) { "Unexpected response" }
                    }.isSuccess
                    success to "${if (success) "✓" else "✕"} $label ${if (success) "connected" else "unavailable"}"
                }
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        test.isEnabled = true
                        result.text = checks.joinToString("\n") { it.second } +
                            "\n\nThese checks do not send an emergency report. Tap Save to use these addresses."
                        result.setTextColor(getColor(if (checks.all { it.first }) R.color.badge_green_text else R.color.warning_text))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
