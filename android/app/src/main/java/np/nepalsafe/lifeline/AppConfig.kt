package np.nepalsafe.lifeline

import android.content.Context

object AppConfig {
    private const val PREFS = "nepalsafe_integrated_settings"
    private const val WARNING_KEY = "warning_backend"
    private const val VISION_KEY = "vision_backend"

    // Android Emulator -> host machine. Physical phones can change both in Settings.
    const val DEFAULT_WARNING_BACKEND = "http://10.0.2.2:8000"
    const val DEFAULT_VISION_BACKEND = "http://10.0.2.2:8001"

    fun warningBackend(context: Context): String = normalizeBaseUrl(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(WARNING_KEY, DEFAULT_WARNING_BACKEND).orEmpty()
    )

    fun visionBackend(context: Context): String = normalizeBaseUrl(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(VISION_KEY, DEFAULT_VISION_BACKEND).orEmpty()
    )

    fun validationError(value: String): String? {
        val normalized = normalizeBaseUrl(value)
        val uri = runCatching { java.net.URI(normalized) }.getOrNull()
            ?: return "Enter a valid server address"
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank())
            return "Use an http:// or https:// server address"
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null)
            return "Use a base address without credentials, a query, or a fragment"
        if (uri.port != -1 && uri.port !in 1..65535) return "Port must be between 1 and 65535"
        return null
    }

    fun save(context: Context, warningBackend: String, visionBackend: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(WARNING_KEY, normalizeBaseUrl(warningBackend))
            .putString(VISION_KEY, normalizeBaseUrl(visionBackend))
            .apply()
    }

    fun endpoint(base: String, path: String): String =
        "${normalizeBaseUrl(base)}/${path.trimStart('/')}"

    fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (
            trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("ws://") || trimmed.startsWith("wss://")
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }
}
