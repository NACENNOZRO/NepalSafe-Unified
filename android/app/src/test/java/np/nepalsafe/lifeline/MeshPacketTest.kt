package np.nepalsafe.lifeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshPacketTest {
    @Test
    fun packetRoundTripsThroughJson() {
        val packet = MeshPacket.create("Maya", "device-a", "Bridge is blocked", 27.9441, 85.9483, now = 1_000L)
        val deserialized = MeshPacket.fromJson(packet.toJson())
        assertEquals(packet, deserialized)
        assertNull(deserialized.audioBase64)
    }

    @Test
    fun packetWithVoiceRoundTripsThroughJson() {
        val audioPayload = "T2ZmbGluZVZvaWNlU09TU2FtcGxl"
        val packet = MeshPacket.create(
            senderName = "Maya",
            originDeviceId = "device-a",
            message = "Voice distress call",
            latitude = 27.9441,
            longitude = 85.9483,
            audioBase64 = audioPayload,
            now = 1_000L
        )
        val deserialized = MeshPacket.fromJson(packet.toJson())
        assertEquals(packet, deserialized)
        assertEquals(audioPayload, deserialized.audioBase64)
        assertEquals(27.9441, deserialized.latitude, 0.0)
        assertEquals(85.9483, deserialized.longitude, 0.0)
    }

    @Test
    fun forwardingIncrementsHopWithoutChangingIdentityOrCoordinates() {
        val audioPayload = "T2ZmbGluZVZvaWNlU09TU2FtcGxl"
        val packet = MeshPacket.create("Maya", "device-a", "Need help", 27.7, 85.3, audioBase64 = audioPayload, now = 1_000L)
        val forwarded = packet.forwarded()
        assertEquals(packet.id, forwarded.id)
        assertEquals(1, forwarded.hopCount)
        assertEquals(packet.latitude, forwarded.latitude, 0.0)
        assertEquals(packet.longitude, forwarded.longitude, 0.0)
        assertEquals(audioPayload, forwarded.audioBase64)
    }

    @Test
    fun parserRejectsPacketWithoutGpsCoordinates() {
        val raw = """{"version":2,"id":"abc","kind":"SOS","senderName":"Maya","originDeviceId":"a","message":"Help","createdAt":1000,"hopCount":0}"""
        assertTrue(runCatching { MeshPacket.fromJson(raw) }.isFailure)
    }
}
