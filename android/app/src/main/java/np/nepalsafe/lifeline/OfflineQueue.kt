package np.nepalsafe.lifeline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object OfflineQueue {
    private const val PREFS = "nepalsafe_offline_queue"
    private const val KEY = "items"

    @Synchronized
    fun enqueue(context: Context, kind: String, payload: JSONObject): Int {
        val items = read(context)
        items.put(JSONObject().put("kind", kind).put("payload", payload).put("queued_at", System.currentTimeMillis()))
        save(context, items)
        return items.length()
    }

    fun count(context: Context): Int = read(context).length()

    @Synchronized
    fun flush(context: Context): Int {
        val source = read(context)
        val remaining = JSONArray()
        var sent = 0
        var failureReached = false
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            if (failureReached) {
                remaining.put(item)
                continue
            }
            try {
                val payload = item.getJSONObject("payload")
                val path = when (item.optString("kind")) {
                    "alert" -> "/api/alert"
                    "ask" -> "/api/ask-help"
                    "sos" -> {
                        payload.put("hops", payload.optInt("hops", 0) + 1)
                        "/api/relay"
                    }
                    else -> "/api/relay"
                }
                NetworkClient.postJson(AppConfig.endpoint(AppConfig.warningBackend(context), path), payload)
                sent++
            } catch (_: Exception) {
                failureReached = true
                remaining.put(item)
            }
        }
        save(context, remaining)
        return sent
    }

    private fun read(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]").orEmpty()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun save(context: Context, items: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, items.toString()).apply()
    }
}
