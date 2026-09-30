package np.nepalsafe.lifeline

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

class MapActivity : Activity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)
        window.statusBarColor = getColor(R.color.navy)
        webView = findViewById(R.id.safetyMapWebView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                val snapshot = runCatching { JSONObject(intent.getStringExtra("snapshot").orEmpty()) }.getOrElse { JSONObject() }
                val people = runCatching { JSONArray(intent.getStringExtra("people").orEmpty()) }.getOrElse { JSONArray() }
                val sos = runCatching { JSONArray(intent.getStringExtra("sos").orEmpty()) }.getOrElse { JSONArray() }
                val payload = JSONObject()
                    .put("snapshot", snapshot)
                    .put("people", people)
                    .put("sos", sos)
                    .put("me", if (intent.getBooleanExtra("hasLocation", false)) JSONObject().put("lat", intent.getDoubleExtra("lat", 0.0)).put("lon", intent.getDoubleExtra("lng", 0.0)) else JSONObject.NULL)
                view.evaluateJavascript("window.renderNepalSafe($payload);", null)
            }
        }
        webView.loadUrl("file:///android_asset/nepalsafe_map.html")
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
