package np.nepalsafe.lifeline

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PacketStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun resetDatabase() {
        context.deleteDatabase("lifeline_packets.db")
    }

    @After
    fun cleanDatabase() {
        context.deleteDatabase("lifeline_packets.db")
    }

    @Test
    fun queuedPacketSurvivesStoreReopenAndKeepsGps() {
        val packet = MeshPacket.create(
            senderName = "Maya",
            originDeviceId = "device-a",
            message = "Need evacuation",
            latitude = 27.9441,
            longitude = 85.9483,
            now = 1_000L
        )

        PacketStore(context).use { store ->
            assertTrue(store.saveOutgoing(packet))
            assertTrue(store.recordHop(packet, "CREATED", "Originated on this phone"))
        }

        PacketStore(context).use { reopened ->
            assertTrue(reopened.contains(packet.id))
            val carried = reopened.loadCarried()
            assertEquals(1, carried.size)
            assertEquals(packet.latitude, carried.single().latitude, 0.0)
            assertEquals(packet.longitude, carried.single().longitude, 0.0)
            val hops = reopened.loadHopLog()
            assertEquals(1, hops.size)
            assertEquals("CREATED", hops.single().action)
            assertEquals(packet.id, hops.single().packetId)
        }
    }
}
