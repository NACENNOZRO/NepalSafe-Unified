package np.nepalsafe.lifeline

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.nio.charset.StandardCharsets
import java.util.Random

class NearbyMeshManager(
    context: Context,
    private val endpointName: String,
    private val packetStore: PacketStore,
    private val listener: Listener
) {
    enum class SendResult { HANDED_TO_PEER, SAVED_AND_PAUSED, STORAGE_FAILED }
    enum class ConnectionMode { AUTOMATIC, HOST, SEARCH }
    data class DiscoveredNode(val endpointId: String, val name: String)
    data class ConnectionApproval(
        val endpointId: String,
        val endpointName: String,
        val authenticationDigits: String
    )
    private data class OutgoingTransfer(val packet: MeshPacket, val startedAt: Long)

    private enum class RadioMode { ADVERTISING, DISCOVERING }

    interface Listener {
        fun onMeshStateChanged(active: Boolean, peerCount: Int)
        fun onConnectionModeChanged(mode: ConnectionMode)
        fun onDiscoveredNodesChanged(nodes: List<DiscoveredNode>)
        fun onConnectionApprovalRequired(approval: ConnectionApproval)
        fun onConnectionApprovalResolved(endpointId: String)
        fun onHopLogChanged()
        fun onPacketReceived(packet: MeshPacket)
        fun onEvent(message: String)
    }

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val retryHandler = Handler(Looper.getMainLooper())
    private val connectedEndpoints = linkedSetOf<String>()
    private val pendingEndpoints = linkedSetOf<String>()
    private val discoveredEndpoints = linkedMapOf<String, String>()
    private val pendingApprovals = linkedMapOf<String, ConnectionApproval>()
    private val endpointNames = linkedMapOf<String, String>()
    private val outgoingTransfers = linkedMapOf<Pair<Long, String>, OutgoingTransfer>()
    private val deliveredPacketIdsByEndpoint = linkedMapOf<String, MutableSet<String>>()
    private val scheduledRetries = linkedSetOf<String>()
    private val automaticRandom = Random(System.nanoTime() xor endpointName.hashCode().toLong())
    private var active = false
    private var connectionMode = ConnectionMode.AUTOMATIC
    private var radioMode: RadioMode? = null
    private var radioGeneration = 0L
    private var automaticAdvertisingActive = false
    private var automaticDiscoveryActive = false
    private var automaticFallbackActive = false
    private val queueSweep = object : Runnable {
        override fun run() {
            if (!active) return
            expireStalledTransfers()
            retrySavedPackets()
            retryHandler.postDelayed(this, QUEUE_SWEEP_MS)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            if (bytes.size > MAX_PACKET_BYTES) {
                listener.onEvent("Ignored an oversized packet")
                return
            }
            val packet = runCatching {
                MeshPacket.fromJson(String(bytes, StandardCharsets.UTF_8))
            }.getOrElse {
                listener.onEvent("Ignored a malformed packet")
                return
            }
            if (packetStore.contains(packet.id)) return
            if (!packetStore.saveIncoming(packet)) {
                listener.onEvent("Could not store incoming SOS; free device storage and retry")
                return
            }
            recordHop(packet, "RECEIVED", "From ${peerName(endpointId)}")
            listener.onPacketReceived(packet)
            val forward = packet.forwarded()
            if (broadcast(forward, exceptEndpoint = endpointId)) {
                listener.onEvent("Stored safely; forwarding at hop ${forward.hopCount}")
            } else {
                recordHop(forward, "PAUSED", "No next node available")
                listener.onEvent("Stored safely at hop ${forward.hopCount}; waiting for the next node")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val key = update.payloadId to endpointId
            val packet = outgoingTransfers[key]?.packet ?: return
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    outgoingTransfers.remove(key)
                    deliveredPacketIdsByEndpoint.getOrPut(endpointId) { linkedSetOf() }.add(packet.id)
                    recordHop(packet, "FORWARDED", "To ${peerName(endpointId)}")
                }

                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    outgoingTransfers.remove(key)
                    recordHop(packet, "PAUSED", "Transfer to ${peerName(endpointId)} failed")
                    scheduleFlush(endpointId)
                }
            }
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            pendingEndpoints.add(endpointId)
            if (connectionMode == ConnectionMode.AUTOMATIC) {
                listener.onEvent("Authenticating ${info.endpointName} • code ${info.authenticationDigits}")
                client.acceptConnection(endpointId, payloadCallback)
                    .addOnFailureListener { listener.onEvent("Could not accept ${info.endpointName}") }
                return
            }

            val approval = ConnectionApproval(
                endpointId = endpointId,
                endpointName = info.endpointName,
                authenticationDigits = info.authenticationDigits
            )
            pendingApprovals[endpointId] = approval
            listener.onConnectionApprovalRequired(approval)
            listener.onEvent("Verify code ${info.authenticationDigits} on both phones")
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            pendingEndpoints.remove(endpointId)
            resolveApproval(endpointId)
            if (resolution.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                listener.onEvent("Nearby phone connected")
                flushCarriedPacketsTo(endpointId)
            } else {
                listener.onEvent("Connection failed (${resolution.status.statusCode})")
                if (connectionMode == ConnectionMode.AUTOMATIC) {
                    scheduleConnectionRetry(endpointId)
                } else {
                    publishDiscoveredNodes()
                }
            }
            publishState()
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            pendingEndpoints.remove(endpointId)
            outgoingTransfers.entries.removeAll { it.key.second == endpointId }
            resolveApproval(endpointId)
            listener.onEvent("Nearby phone disconnected")
            if (connectionMode == ConnectionMode.AUTOMATIC) {
                scheduleConnectionRetry(endpointId)
            } else {
                publishDiscoveredNodes()
            }
            publishState()
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            discoveredEndpoints[endpointId] = info.endpointName
            endpointNames[endpointId] = info.endpointName
            publishDiscoveredNodes()
            if (connectionMode == ConnectionMode.AUTOMATIC) {
                if (endpointName < info.endpointName) {
                    requestConnection(endpointId, automatic = true)
                } else {
                    listener.onEvent("Found ${info.endpointName}; allowing it to initiate first")
                    val generation = radioGeneration
                    retryHandler.postDelayed({
                        if (
                            active &&
                            connectionMode == ConnectionMode.AUTOMATIC &&
                            generation == radioGeneration &&
                            endpointId in discoveredEndpoints &&
                            endpointId !in connectedEndpoints &&
                            endpointId !in pendingEndpoints
                        ) {
                            requestConnection(endpointId, automatic = true)
                        }
                    }, AUTO_TIE_BREAK_DELAY_MS)
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            discoveredEndpoints.remove(endpointId)
            pendingEndpoints.remove(endpointId)
            if (endpointId !in connectedEndpoints) endpointNames.remove(endpointId)
            publishDiscoveredNodes()
        }
    }

    fun start(mode: ConnectionMode = connectionMode) {
        if (active) {
            setConnectionMode(mode)
            return
        }
        active = true
        connectionMode = mode
        listener.onConnectionModeChanged(connectionMode)
        publishState()
        configureRadio()
        retryHandler.removeCallbacks(queueSweep)
        retryHandler.postDelayed(queueSweep, QUEUE_SWEEP_MS)
    }

    fun stop() {
        if (!active) return
        active = false
        radioGeneration += 1
        client.stopAdvertising()
        client.stopDiscovery()
        pendingApprovals.keys.toList().forEach { client.rejectConnection(it) }
        client.stopAllEndpoints()
        connectedEndpoints.clear()
        pendingEndpoints.clear()
        pendingApprovals.clear()
        clearDiscoveredNodes()
        endpointNames.clear()
        outgoingTransfers.clear()
        deliveredPacketIdsByEndpoint.clear()
        scheduledRetries.clear()
        radioMode = null
        automaticAdvertisingActive = false
        automaticDiscoveryActive = false
        automaticFallbackActive = false
        retryHandler.removeCallbacksAndMessages(null)
        listener.onEvent("Lifeline stopped")
        publishState()
    }

    fun setConnectionMode(mode: ConnectionMode) {
        if (connectionMode == mode) {
            listener.onConnectionModeChanged(mode)
            return
        }
        connectionMode = mode
        listener.onConnectionModeChanged(mode)
        if (active) configureRadio()
    }

    fun connectToEndpoint(endpointId: String) {
        if (!active || connectionMode != ConnectionMode.SEARCH) {
            listener.onEvent("Choose Find Nodes before selecting a phone")
            return
        }
        if (endpointId !in discoveredEndpoints) {
            listener.onEvent("That phone is no longer visible; keep searching")
            return
        }
        requestConnection(endpointId, automatic = false)
    }

    fun approveConnection(endpointId: String) {
        val approval = pendingApprovals[endpointId] ?: return
        listener.onEvent("Code approved for ${approval.endpointName}; waiting for the other phone")
        client.acceptConnection(endpointId, payloadCallback)
            .addOnSuccessListener { resolveApproval(endpointId) }
            .addOnFailureListener {
                resolveApproval(endpointId)
                pendingEndpoints.remove(endpointId)
                listener.onEvent("Could not accept ${approval.endpointName}")
                publishDiscoveredNodes()
            }
    }

    fun rejectConnection(endpointId: String) {
        val approval = pendingApprovals[endpointId] ?: return
        client.rejectConnection(endpointId)
        pendingEndpoints.remove(endpointId)
        resolveApproval(endpointId)
        listener.onEvent("Connection to ${approval.endpointName} rejected")
    }

    fun send(packet: MeshPacket): SendResult {
        if (!packetStore.saveOutgoing(packet)) {
            listener.onEvent("Could not save SOS; message was not cleared")
            return SendResult.STORAGE_FAILED
        }
        recordHop(packet, "CREATED", "Originated on this phone")
        return if (broadcast(packet, exceptEndpoint = null)) {
            SendResult.HANDED_TO_PEER
        } else {
            recordHop(packet, "PAUSED", "Waiting for a nearby node")
            SendResult.SAVED_AND_PAUSED
        }
    }

    fun isActive(): Boolean = active
    fun connectionMode(): ConnectionMode = connectionMode

    fun retrySavedPackets() {
        if (!active) return
        connectedEndpoints.toList().forEach(::flushCarriedPacketsTo)
    }

    private fun broadcast(packet: MeshPacket, exceptEndpoint: String?): Boolean {
        val targets = connectedEndpoints.filter { endpointId ->
            endpointId != exceptEndpoint &&
                packet.id !in deliveredPacketIdsByEndpoint[endpointId].orEmpty() &&
                !isPacketInFlight(packet.id, endpointId)
        }
        if (targets.isEmpty()) return false
        val bytes = packet.toJson().toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_PACKET_BYTES) {
            listener.onEvent("SOS packet is too large")
            return false
        }
        val payload = Payload.fromBytes(bytes)
        val startedAt = System.currentTimeMillis()
        targets.forEach { endpointId ->
            outgoingTransfers[payload.id to endpointId] = OutgoingTransfer(packet, startedAt)
        }
        client.sendPayload(targets, payload)
            .addOnFailureListener {
                targets.forEach { endpointId -> outgoingTransfers.remove(payload.id to endpointId) }
                recordHop(packet, "PAUSED", "Nearby transfer could not start")
                listener.onEvent("Packet send paused; retry is automatic and the SOS remains saved")
                targets.forEach(::scheduleFlush)
            }
        return true
    }

    private fun flushCarriedPacketsTo(endpointId: String) {
        if (!active || endpointId !in connectedEndpoints) return
        val delivered = deliveredPacketIdsByEndpoint[endpointId].orEmpty()
        val packets = packetStore.loadCarried().filter { packet ->
            packet.id !in delivered && !isPacketInFlight(packet.id, endpointId)
        }
        if (packets.isEmpty()) return
        packets.forEach { packet ->
            val payload = Payload.fromBytes(packet.toJson().toByteArray(StandardCharsets.UTF_8))
            outgoingTransfers[payload.id to endpointId] =
                OutgoingTransfer(packet, System.currentTimeMillis())
            client.sendPayload(endpointId, payload)
                .addOnFailureListener {
                    outgoingTransfers.remove(payload.id to endpointId)
                    recordHop(packet, "PAUSED", "Replay to ${peerName(endpointId)} failed")
                    scheduleFlush(endpointId)
                }
        }
        listener.onEvent("Automatically offered ${packets.size} paused SOS packet(s) to the new node")
    }

    private fun isPacketInFlight(packetId: String, endpointId: String): Boolean =
        outgoingTransfers.any { (key, transfer) ->
            key.second == endpointId && transfer.packet.id == packetId
        }

    private fun expireStalledTransfers() {
        val cutoff = System.currentTimeMillis() - TRANSFER_TIMEOUT_MS
        val expired = outgoingTransfers.filterValues { it.startedAt < cutoff }
        if (expired.isEmpty()) return
        expired.forEach { (key, transfer) ->
            outgoingTransfers.remove(key)
            recordHop(transfer.packet, "PAUSED", "Transfer timed out; automatic retry queued")
        }
    }

    private fun scheduleFlush(endpointId: String) {
        if (!active || endpointId !in connectedEndpoints || !scheduledRetries.add(endpointId)) return
        retryHandler.postDelayed({
            scheduledRetries.remove(endpointId)
            if (active && endpointId in connectedEndpoints) flushCarriedPacketsTo(endpointId)
        }, RETRY_DELAY_MS)
    }

    private fun configureRadio() {
        radioGeneration += 1
        val generation = radioGeneration
        client.stopAdvertising()
        client.stopDiscovery()
        pendingApprovals.keys.toList().forEach { client.rejectConnection(it) }
        pendingApprovals.keys.toList().forEach(listener::onConnectionApprovalResolved)
        pendingApprovals.clear()
        pendingEndpoints.clear()
        clearDiscoveredNodes()
        automaticAdvertisingActive = false
        automaticDiscoveryActive = false
        automaticFallbackActive = false

        when (connectionMode) {
            ConnectionMode.AUTOMATIC -> {
                radioMode = null
                listener.onEvent("Automatic mesh selected; preparing continuous visibility and scanning…")
                retryHandler.postDelayed({ startAutomaticMesh(generation) }, RADIO_COOLDOWN_MS)
            }

            ConnectionMode.HOST -> {
                radioMode = RadioMode.ADVERTISING
                listener.onEvent("Host mode selected; preparing to be visible…")
                scheduleManualRadioStart(generation)
            }

            ConnectionMode.SEARCH -> {
                radioMode = RadioMode.DISCOVERING
                listener.onEvent("Find Nodes selected; preparing to scan…")
                scheduleManualRadioStart(generation)
            }
        }
    }

    private fun startAutomaticMesh(generation: Long) {
        if (!automaticModeIsCurrent(generation) || automaticFallbackActive) return
        if ((endpointName.hashCode() and 1) == 0) {
            startAutomaticAdvertising(generation)
            startAutomaticDiscovery(generation)
        } else {
            startAutomaticDiscovery(generation)
            startAutomaticAdvertising(generation)
        }
    }

    private fun startAutomaticAdvertising(generation: Long) {
        if (!automaticModeIsCurrent(generation) || automaticFallbackActive) return
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(endpointName, SERVICE_ID, lifecycleCallback, options)
            .addOnSuccessListener {
                if (!automaticModeIsCurrent(generation) || automaticFallbackActive) return@addOnSuccessListener
                automaticAdvertisingActive = true
                publishAutomaticRadioState()
            }
            .addOnFailureListener { throwable ->
                val status = (throwable as? ApiException)?.statusCode
                if (status == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
                    automaticAdvertisingActive = true
                    publishAutomaticRadioState()
                } else if (status == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                    activateAutomaticFallback(generation)
                } else {
                    automaticAdvertisingActive = false
                    listener.onEvent(failureMessage("Automatic visibility", throwable))
                    retryHandler.postDelayed(
                        { startAutomaticAdvertising(generation) },
                        AUTOMATIC_RETRY_MS
                    )
                }
            }
    }

    private fun startAutomaticDiscovery(generation: Long) {
        if (!automaticModeIsCurrent(generation) || automaticFallbackActive) return
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnSuccessListener {
                if (!automaticModeIsCurrent(generation) || automaticFallbackActive) return@addOnSuccessListener
                automaticDiscoveryActive = true
                publishAutomaticRadioState()
            }
            .addOnFailureListener { throwable ->
                val status = (throwable as? ApiException)?.statusCode
                if (status == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                    automaticDiscoveryActive = true
                    publishAutomaticRadioState()
                } else if (status == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
                    activateAutomaticFallback(generation)
                } else {
                    automaticDiscoveryActive = false
                    listener.onEvent(failureMessage("Automatic scanning", throwable))
                    retryHandler.postDelayed(
                        { startAutomaticDiscovery(generation) },
                        AUTOMATIC_RETRY_MS
                    )
                }
            }
    }

    private fun activateAutomaticFallback(generation: Long) {
        if (!automaticModeIsCurrent(generation) || automaticFallbackActive) return
        automaticFallbackActive = true
        automaticAdvertisingActive = false
        automaticDiscoveryActive = false
        client.stopAdvertising()
        client.stopDiscovery()
        listener.onEvent("This phone requires compatibility Auto mode; randomized role switching enabled")
        retryHandler.postDelayed(
            { runAutomaticFallbackPhase(generation) },
            RADIO_COOLDOWN_MS + automaticRandom.nextInt(1_500)
        )
    }

    private fun runAutomaticFallbackPhase(generation: Long) {
        if (!automaticModeIsCurrent(generation) || !automaticFallbackActive) return
        client.stopAdvertising()
        client.stopDiscovery()
        clearDiscoveredNodes()
        radioMode = if (automaticRandom.nextBoolean()) RadioMode.ADVERTISING else RadioMode.DISCOVERING
        retryHandler.postDelayed({
            if (!automaticModeIsCurrent(generation) || !automaticFallbackActive) return@postDelayed
            when (radioMode) {
                RadioMode.ADVERTISING -> startAdvertising(generation, manual = false)
                else -> startDiscovery(generation, manual = false)
            }
        }, RADIO_COOLDOWN_MS)
        retryHandler.postDelayed(
            { runAutomaticFallbackPhase(generation) },
            AUTOMATIC_FALLBACK_MIN_PHASE_MS + automaticRandom.nextInt(AUTOMATIC_FALLBACK_JITTER_MS)
        )
    }

    private fun automaticModeIsCurrent(generation: Long): Boolean =
        active && generation == radioGeneration && connectionMode == ConnectionMode.AUTOMATIC

    private fun publishAutomaticRadioState() {
        val message = when {
            automaticAdvertisingActive && automaticDiscoveryActive ->
                "Auto mesh ready: this phone is visible and scanning continuously"
            automaticAdvertisingActive ->
                "Auto mesh is visible; scanning is retrying"
            automaticDiscoveryActive ->
                "Auto mesh is scanning; visibility is retrying"
            else -> "Auto mesh radio is retrying"
        }
        listener.onEvent(message)
    }

    private fun scheduleManualRadioStart(generation: Long, delayMs: Long = RADIO_COOLDOWN_MS) {
        retryHandler.postDelayed({
            if (!active || generation != radioGeneration) return@postDelayed
            when (connectionMode) {
                ConnectionMode.HOST -> startAdvertising(generation, manual = true)
                ConnectionMode.SEARCH -> startDiscovery(generation, manual = true)
                ConnectionMode.AUTOMATIC -> Unit
            }
        }, delayMs)
    }

    private fun startAdvertising(generation: Long, manual: Boolean) {
        listener.onEvent(
            if (manual) "Hosting now; use Find Nodes on the second phone"
            else "Advertising automatically; waiting for a nearby node"
        )
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(endpointName, SERVICE_ID, lifecycleCallback, options)
            .addOnSuccessListener {
                listener.onEvent(
                    if (manual) "Host is visible; waiting for a connection request"
                    else "Advertising active; nearby nodes can connect"
                )
            }
            .addOnFailureListener {
                listener.onEvent(failureMessage("Advertising", it))
                if (manual) scheduleManualRadioStart(generation, MANUAL_RETRY_MS)
            }
    }

    private fun startDiscovery(generation: Long, manual: Boolean) {
        listener.onEvent(
            if (manual) "Searching for NepalSafe phones; tap one below to connect"
            else "Scanning automatically for NepalSafe nodes"
        )
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnSuccessListener {
                listener.onEvent(
                    if (manual) "Search active; nearby hosts will appear below"
                    else "Scanning active; looking for a nearby node"
                )
            }
            .addOnFailureListener {
                listener.onEvent(failureMessage("Scanning", it))
                if (manual) scheduleManualRadioStart(generation, MANUAL_RETRY_MS)
            }
    }

    private fun requestConnection(endpointId: String, automatic: Boolean) {
        if (!active || endpointId in connectedEndpoints || !pendingEndpoints.add(endpointId)) return
        val remoteName = discoveredEndpoints[endpointId] ?: "nearby node"
        listener.onEvent(
            if (automatic) "Found $remoteName; connecting automatically…"
            else "Connecting to $remoteName…"
        )
        client.requestConnection(endpointName, endpointId, lifecycleCallback)
            .addOnSuccessListener { listener.onEvent("Connection request sent to $remoteName") }
            .addOnFailureListener {
                pendingEndpoints.remove(endpointId)
                if (automatic) {
                    listener.onEvent("Connection retry scheduled for $remoteName")
                    scheduleConnectionRetry(endpointId)
                } else {
                    listener.onEvent("Could not connect to $remoteName; tap the phone to try again")
                    publishDiscoveredNodes()
                }
            }
    }

    private fun scheduleConnectionRetry(endpointId: String) {
        if (!active || endpointId !in discoveredEndpoints) return
        val automatic = connectionMode == ConnectionMode.AUTOMATIC
        retryHandler.postDelayed({ requestConnection(endpointId, automatic) }, CONNECTION_RETRY_MS)
    }

    private fun resolveApproval(endpointId: String) {
        if (pendingApprovals.remove(endpointId) != null) {
            listener.onConnectionApprovalResolved(endpointId)
        }
    }

    private fun clearDiscoveredNodes() {
        if (discoveredEndpoints.isEmpty()) return
        discoveredEndpoints.clear()
        publishDiscoveredNodes()
    }

    private fun publishDiscoveredNodes() {
        listener.onDiscoveredNodesChanged(
            discoveredEndpoints.map { (endpointId, name) -> DiscoveredNode(endpointId, name) }
        )
    }

    private fun failureMessage(operation: String, throwable: Throwable): String {
        val statusCode = (throwable as? ApiException)?.statusCode
        if (statusCode == 8032) {
            return "$operation blocked: Wi-Fi state permission is missing. Install NepalSafe v0.6 on every phone."
        }
        val status = statusCode?.let { " (code $it)" }.orEmpty()
        val detail = throwable.localizedMessage?.takeIf(String::isNotBlank) ?: throwable.javaClass.simpleName
        return "$operation failed$status: $detail • retry scheduled"
    }

    private fun peerName(endpointId: String): String = endpointNames[endpointId] ?: "nearby node"

    private fun recordHop(packet: MeshPacket, action: String, peer: String) {
        packetStore.recordHop(packet, action, peer)
        listener.onHopLogChanged()
    }

    private fun publishState() {
        listener.onMeshStateChanged(active, connectedEndpoints.size)
    }

    companion object {
        private const val SERVICE_ID = "np.nepalsafe.lifeline.mesh.v2"
        private const val MAX_PACKET_BYTES = 32 * 1024
        private const val RETRY_DELAY_MS = 5_000L
        private const val QUEUE_SWEEP_MS = 15_000L
        private const val TRANSFER_TIMEOUT_MS = 60_000L
        private const val CONNECTION_RETRY_MS = 3_000L
        private const val MANUAL_RETRY_MS = 5_000L
        private const val AUTOMATIC_RETRY_MS = 4_000L
        private const val AUTO_TIE_BREAK_DELAY_MS = 4_000L
        private const val RADIO_COOLDOWN_MS = 750L
        private const val AUTOMATIC_FALLBACK_MIN_PHASE_MS = 8_000L
        private const val AUTOMATIC_FALLBACK_JITTER_MS = 5_000
        private val STRATEGY = Strategy.P2P_CLUSTER
    }
}
