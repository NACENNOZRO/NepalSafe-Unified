package np.nepalsafe.lifeline

import android.app.Activity
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only readiness checks; never fabricates hazard predictions or clears queued SOS. */
class DashboardStatus(private val activity: Activity) {
    private val executor = Executors.newSingleThreadExecutor()
    private val checking = AtomicBoolean(false)

    fun refresh() {
        if (executor.isShutdown || !checking.compareAndSet(false, true)) return
        val status = activity.findViewById<TextView>(R.id.dashboardServiceStatus)
        val warningBase = AppConfig.warningBackend(activity)
        val visionBase = AppConfig.visionBackend(activity)
        status.text = "Checking services…\nYour phone-to-phone Lifeline is available below."
        executor.execute {
            try {
                val warning = runCatching {
                    val data = NetworkClient.getJson(AppConfig.endpoint(warningBase, "/api/now"), 4000)
                    require(data.optJSONObject("place") != null) { "Unexpected warning response" }
                }.isSuccess
                val vision = runCatching {
                    val data = NetworkClient.getJson(AppConfig.endpoint(visionBase, "/alerts"), 4000)
                    require(data.optJSONArray("alerts") != null) { "Unexpected analysis response" }
                }.isSuccess
                val queued = OfflineQueue.count(activity)
                activity.runOnUiThread {
                    if (!activity.isDestroyed && !activity.isFinishing) {
                        status.text = buildString {
                            append(if (warning) "● Hazard service connected" else "○ Hazard service unavailable")
                            append('\n')
                            append(if (vision) "● Photo service connected" else "○ Photo service unavailable")
                            if (queued > 0) append("\n$queued report(s) waiting to send")
                            if (!warning || !vision) append("\nOpen connection settings to check your servers.")
                        }
                    }
                }
            } finally { checking.set(false) }
        }
    }

    fun close() { executor.shutdownNow() }
}
