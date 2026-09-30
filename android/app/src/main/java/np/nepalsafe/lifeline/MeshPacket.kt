package np.nepalsafe.lifeline

import org.json.JSONObject
import java.util.UUID

data class MeshPacket(
    val id: String,
    val senderName: String,
    val originDeviceId: String,
    val message: String,
    val latitude: Double,
    val longitude: Double,
    val createdAt: Long,
    val hopCount: Int,
    val kind: String = KIND_SOS,
    val audioBase64: String? = null
) {
    fun toJson(): String = JSONObject().apply {
        put("version", PROTOCOL_VERSION)
        put("id", id)
        put("kind", kind)
        put("senderName", senderName)
        put("originDeviceId", originDeviceId)
        put("message", message)
        put("latitude", latitude)
        put("longitude", longitude)
        put("createdAt", createdAt)
        put("hopCount", hopCount)
        if (!audioBase64.isNullOrBlank()) {
            put("audioBase64", audioBase64)
        }
    }.toString()

    fun forwarded(): MeshPacket = copy(hopCount = if (hopCount == Int.MAX_VALUE) hopCount else hopCount + 1)

    companion object {
        const val PROTOCOL_VERSION = 2
        const val KIND_SOS = "SOS"

        fun create(
            senderName: String,
            originDeviceId: String,
            message: String,
            latitude: Double,
            longitude: Double,
            audioBase64: String? = null,
            now: Long = System.currentTimeMillis()
        ) = MeshPacket(
            id = UUID.randomUUID().toString(),
            senderName = senderName.trim().take(40),
            originDeviceId = originDeviceId,
            message = message.trim().take(500),
            latitude = latitude,
            longitude = longitude,
            createdAt = now,
            hopCount = 0,
            audioBase64 = audioBase64
        )

        fun fromJson(raw: String): MeshPacket {
            val json = JSONObject(raw)
            require(json.optInt("version", -1) == PROTOCOL_VERSION) { "Unsupported packet version" }
            val audioBase64 = json.optString("audioBase64").takeIf { it.isNotBlank() }
            val packet = MeshPacket(
                id = json.getString("id"),
                kind = json.optString("kind", KIND_SOS),
                senderName = json.getString("senderName"),
                originDeviceId = json.getString("originDeviceId"),
                message = json.getString("message"),
                latitude = json.getDouble("latitude"),
                longitude = json.getDouble("longitude"),
                createdAt = json.getLong("createdAt"),
                hopCount = json.getInt("hopCount"),
                audioBase64 = audioBase64
            )
            require(packet.id.isNotBlank() && packet.id.length <= 80) { "Invalid packet id" }
            require(packet.senderName.length <= 40) { "Sender name too long" }
            require(packet.message.isNotBlank() && packet.message.length <= 500) { "Invalid message" }
            require(packet.hopCount >= 0) { "Invalid hop count" }
            require(packet.latitude in -90.0..90.0) { "Invalid latitude" }
            require(packet.longitude in -180.0..180.0) { "Invalid longitude" }
            return packet
        }
    }
}
