package np.nepalsafe.lifeline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable store-and-forward queue. Packets are never removed automatically: a phone keeps
 * carrying them across process restarts and offers them to every newly connected peer.
 */
class PacketStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    data class HopEntry(
        val packetId: String,
        val action: String,
        val hopCount: Int,
        val peer: String,
        val message: String,
        val createdAt: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE packets (
                id TEXT PRIMARY KEY NOT NULL,
                display_payload TEXT NOT NULL,
                relay_payload TEXT NOT NULL,
                direction TEXT NOT NULL,
                stored_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX packets_stored_at ON packets(stored_at DESC)")
        createHopTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createHopTable(db)
    }

    @Synchronized
    fun saveOutgoing(packet: MeshPacket): Boolean = insert(packet, packet, "SENT")

    @Synchronized
    fun saveIncoming(packet: MeshPacket): Boolean = insert(packet, packet.forwarded(), "RECEIVED")

    @Synchronized
    fun contains(packetId: String): Boolean {
        readableDatabase.query(
            "packets",
            arrayOf("id"),
            "id = ?",
            arrayOf(packetId),
            null,
            null,
            null,
            "1"
        ).use { return it.moveToFirst() }
    }

    @Synchronized
    fun getPacket(packetId: String): MeshPacket? {
        readableDatabase.query(
            "packets",
            arrayOf("display_payload"),
            "id = ?",
            arrayOf(packetId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return runCatching { MeshPacket.fromJson(cursor.getString(0)) }.getOrNull()
            }
        }
        return null
    }

    @Synchronized
    fun loadCarried(): List<MeshPacket> {
        val packets = mutableListOf<MeshPacket>()
        readableDatabase.query(
            "packets",
            arrayOf("relay_payload"),
            null,
            null,
            null,
            null,
            "stored_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { MeshPacket.fromJson(cursor.getString(0)) }.getOrNull()?.let(packets::add)
            }
        }
        return packets
    }

    @Synchronized
    fun loadHistory(limit: Int = 100): List<Pair<String, MeshPacket>> {
        val packets = mutableListOf<Pair<String, MeshPacket>>()
        readableDatabase.query(
            "packets",
            arrayOf("direction", "display_payload"),
            null,
            null,
            null,
            null,
            "stored_at DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val direction = cursor.getString(0)
                runCatching { MeshPacket.fromJson(cursor.getString(1)) }.getOrNull()?.let {
                    packets += direction to it
                }
            }
        }
        return packets
    }

    @Synchronized
    fun recordHop(packet: MeshPacket, action: String, peer: String): Boolean {
        val values = ContentValues().apply {
            put("packet_id", packet.id)
            put("action", action)
            put("hop_count", packet.hopCount)
            put("peer", peer)
            put("message", packet.message)
            put("created_at", System.currentTimeMillis())
        }
        return runCatching { writableDatabase.insert("hop_events", null, values) != -1L }.getOrDefault(false)
    }

    @Synchronized
    fun loadHopLog(limit: Int = 50): List<HopEntry> {
        val entries = mutableListOf<HopEntry>()
        readableDatabase.query(
            "hop_events",
            arrayOf("packet_id", "action", "hop_count", "peer", "message", "created_at"),
            null,
            null,
            null,
            null,
            "created_at DESC, id DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries += HopEntry(
                    packetId = cursor.getString(0),
                    action = cursor.getString(1),
                    hopCount = cursor.getInt(2),
                    peer = cursor.getString(3),
                    message = cursor.getString(4),
                    createdAt = cursor.getLong(5)
                )
            }
        }
        return entries
    }

    private fun createHopTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hop_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                packet_id TEXT NOT NULL,
                action TEXT NOT NULL,
                hop_count INTEGER NOT NULL,
                peer TEXT NOT NULL,
                message TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS hop_events_created_at ON hop_events(created_at DESC)")
    }

    private fun insert(displayPacket: MeshPacket, relayPacket: MeshPacket, direction: String): Boolean {
        val values = ContentValues().apply {
            put("id", displayPacket.id)
            put("display_payload", displayPacket.toJson())
            put("relay_payload", relayPacket.toJson())
            put("direction", direction)
            put("stored_at", System.currentTimeMillis())
        }
        return runCatching {
            writableDatabase.insertWithOnConflict(
                "packets",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            ) != -1L
        }.getOrDefault(false)
    }

    companion object {
        private const val DATABASE_NAME = "lifeline_packets.db"
        private const val DATABASE_VERSION = 2
    }
}
