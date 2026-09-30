package np.nepalsafe.lifeline

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointValidationTest {
    @Test fun allowsLanAndHostedEndpoints() {
        listOf("192.168.1.10:8000", "https://example.org/nepalsafe", "http://[::1]:8001")
            .forEach { assertNull(AppConfig.validationError(it)) }
    }
    @Test fun rejectsInvalidHttpConfiguration() {
        listOf("", "http://", "file:///tmp/app", "ws://example.org", "http://user:pass@example.org",
            "https://example.org?token=secret", "http://example.org:99999", "not a host")
            .forEach { assertNotNull(AppConfig.validationError(it)) }
    }
}
