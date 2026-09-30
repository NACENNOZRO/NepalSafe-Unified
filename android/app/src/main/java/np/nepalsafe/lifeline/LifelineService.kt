package np.nepalsafe.lifeline

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.UUID

class LifelineService : Service(), NearbyMeshManager.Listener {
    interface UiListener {
        fun onMeshStateChanged(active: Boolean, peerCount: Int)
        fun onConnectionModeChanged(mode: NearbyMeshManager.ConnectionMode)
        fun onDiscoveredNodesChanged(nodes: List<NearbyMeshManager.DiscoveredNode>)
        fun onConnectionApprovalRequired(approval: NearbyMeshManager.ConnectionApproval)
        fun onConnectionApprovalResolved(endpointId: String)
        fun onHopLogChanged()
        fun onPacketReceived(packet: MeshPacket)
        fun onEvent(message: String)
    }

    inner class LocalBinder : Binder() {
        fun service(): LifelineService = this@LifelineService
    }

    private val binder = LocalBinder()
    private val backgroundHandler = Handler(Looper.getMainLooper())
    private lateinit var mesh: NearbyMeshManager
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var uiListener: UiListener? = null
    private var active = false
    private var peerCount = 0
    private var selectedMode = NearbyMeshManager.ConnectionMode.AUTOMATIC
    private var discoveredNodes = emptyList<NearbyMeshManager.DiscoveredNode>()
    private val pendingApprovals = linkedMapOf<String, NearbyMeshManager.ConnectionApproval>()
    private val renewWakeLock = object : Runnable {
        override fun run() {
            if (!active) return
            acquireCpuWakeLock()
            backgroundHandler.postDelayed(this, WAKE_LOCK_RENEWAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val preferences = getSharedPreferences(IDENTITY_PREFERENCES, MODE_PRIVATE)
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        val endpointName = "NepalSafe-${Build.MODEL.take(14)}-${deviceId.takeLast(8)}"
        mesh = NearbyMeshManager(this, endpointName, PacketStore(this), this)
        selectedMode = runCatching {
            NearbyMeshManager.ConnectionMode.valueOf(
                getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
                    .getString(KEY_MODE, NearbyMeshManager.ConnectionMode.AUTOMATIC.name)!!
            )
        }.getOrDefault(NearbyMeshManager.ConnectionMode.AUTOMATIC)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopLifeline()
            else -> if (intent?.action == ACTION_START || shouldRestore()) {
                val requestedMode = intent?.getStringExtra(EXTRA_CONNECTION_MODE)?.let { value ->
                    runCatching { NearbyMeshManager.ConnectionMode.valueOf(value) }.getOrNull()
                } ?: selectedMode
                startLifeline(requestedMode)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopBackgroundProtection()
        mesh.stop()
        super.onDestroy()
    }

    fun setUiListener(listener: UiListener?) {
        uiListener = listener
        listener?.onMeshStateChanged(active, peerCount)
        listener?.onConnectionModeChanged(selectedMode)
        listener?.onDiscoveredNodesChanged(discoveredNodes)
        pendingApprovals.values.forEach { listener?.onConnectionApprovalRequired(it) }
    }

    fun startLifeline(mode: NearbyMeshManager.ConnectionMode = selectedMode) {
        selectedMode = mode
        persistMode(mode)
        if (active) {
            mesh.setConnectionMode(mode)
            return
        }
        active = true
        persistActive(true)
        startForeground(NOTIFICATION_ID, buildNotification())
        startBackgroundProtection()
        mesh.start(mode)
    }

    fun stopLifeline() {
        if (active) mesh.stop()
        active = false
        peerCount = 0
        stopBackgroundProtection()
        persistActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        uiListener?.onMeshStateChanged(false, 0)
        stopSelf()
    }

    fun send(packet: MeshPacket): NearbyMeshManager.SendResult = mesh.send(packet)
    fun isActive(): Boolean = active
    fun connectionMode(): NearbyMeshManager.ConnectionMode = selectedMode

    fun setConnectionMode(mode: NearbyMeshManager.ConnectionMode) {
        selectedMode = mode
        persistMode(mode)
        if (active) mesh.setConnectionMode(mode) else uiListener?.onConnectionModeChanged(mode)
    }

    fun connectToEndpoint(endpointId: String) = mesh.connectToEndpoint(endpointId)
    fun approveConnection(endpointId: String) = mesh.approveConnection(endpointId)
    fun rejectConnection(endpointId: String) = mesh.rejectConnection(endpointId)

    override fun onMeshStateChanged(active: Boolean, peerCount: Int) {
        this.active = active
        this.peerCount = peerCount
        if (active && (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                )
        ) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
        uiListener?.onMeshStateChanged(active, peerCount)
    }

    override fun onConnectionModeChanged(mode: NearbyMeshManager.ConnectionMode) {
        selectedMode = mode
        persistMode(mode)
        uiListener?.onConnectionModeChanged(mode)
    }

    override fun onDiscoveredNodesChanged(nodes: List<NearbyMeshManager.DiscoveredNode>) {
        discoveredNodes = nodes
        uiListener?.onDiscoveredNodesChanged(nodes)
    }

    override fun onConnectionApprovalRequired(approval: NearbyMeshManager.ConnectionApproval) {
        pendingApprovals[approval.endpointId] = approval
        uiListener?.onConnectionApprovalRequired(approval)
    }

    override fun onConnectionApprovalResolved(endpointId: String) {
        pendingApprovals.remove(endpointId)
        uiListener?.onConnectionApprovalResolved(endpointId)
    }

    override fun onHopLogChanged() {
        uiListener?.onHopLogChanged()
    }

    override fun onPacketReceived(packet: MeshPacket) {
        uiListener?.onPacketReceived(packet)
    }

    override fun onEvent(message: String) {
        uiListener?.onEvent(message)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LifelineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val detail = if (peerCount == 0) {
            when (selectedMode) {
                NearbyMeshManager.ConnectionMode.AUTOMATIC -> "Screen-off auto mesh • SOS queue retrying"
                NearbyMeshManager.ConnectionMode.HOST -> "Manual host active • waiting for another phone"
                NearbyMeshManager.ConnectionMode.SEARCH -> "Manual search active • looking for hosts"
            }
        } else {
            "$peerCount nearby node(s) connected • passing paused messages"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("NepalSafe Lifeline active")
            .setContentText(detail)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lifeline mesh",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps NepalSafe scanning for nearby relay nodes"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startBackgroundProtection() {
        backgroundHandler.removeCallbacks(renewWakeLock)
        acquireCpuWakeLock()
        backgroundHandler.postDelayed(renewWakeLock, WAKE_LOCK_RENEWAL_MS)
    }

    private fun acquireCpuWakeLock() {
        val lock = cpuWakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:LifelineMesh")
            .apply { setReferenceCounted(false) }
            .also { cpuWakeLock = it }
        if (lock.isHeld) lock.release()
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun stopBackgroundProtection() {
        backgroundHandler.removeCallbacks(renewWakeLock)
        cpuWakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        cpuWakeLock = null
    }

    private fun persistActive(value: Boolean) {
        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, value).apply()
    }

    private fun persistMode(mode: NearbyMeshManager.ConnectionMode) {
        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }

    private fun shouldRestore(): Boolean = wasActive(this)

    companion object {
        const val ACTION_START = "np.nepalsafe.lifeline.START"
        const val ACTION_STOP = "np.nepalsafe.lifeline.STOP"
        const val EXTRA_CONNECTION_MODE = "connection_mode"
        const val IDENTITY_PREFERENCES = "lifeline_identity"
        const val KEY_DEVICE_ID = "device_id"
        private const val SERVICE_PREFERENCES = "lifeline_service"
        private const val KEY_ACTIVE = "active"
        private const val KEY_MODE = "connection_mode"
        private const val CHANNEL_ID = "lifeline_mesh"
        private const val NOTIFICATION_ID = 1101
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_RENEWAL_MS = 8 * 60 * 1000L

        fun wasActive(context: android.content.Context): Boolean =
            context.getSharedPreferences(SERVICE_PREFERENCES, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false)
    }
}
