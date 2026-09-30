package np.nepalsafe.lifeline

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigTest {
    @Test
    fun normalizesBackendUrlsWithoutBreakingWebSockets() {
        assertEquals("http://10.0.2.2:8000", AppConfig.normalizeBaseUrl("10.0.2.2:8000/"))
        assertEquals("https://safe.example/api", AppConfig.normalizeBaseUrl(" https://safe.example/api/// "))
        assertEquals("wss://safe.example", AppConfig.normalizeBaseUrl("wss://safe.example/"))
    }

    @Test
    fun joinsPathsWithOneSlash() {
        assertEquals("http://host:8000/api/now", AppConfig.endpoint("http://host:8000/", "/api/now"))
        assertEquals("ws://host:8001/ws/alerts", AppConfig.endpoint("ws://host:8001", "ws/alerts"))
    }
}
