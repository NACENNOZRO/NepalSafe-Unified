package np.nepalsafe.lifeline

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : Activity(), LifelineService.UiListener, LocationListener {
    private lateinit var statusTitle: TextView
    private lateinit var peerCount: TextView
    private lateinit var connectionDetail: TextView
    private lateinit var toggleButton: Button
    private lateinit var setupWarningLayout: LinearLayout
    private lateinit var networkChecks: TextView
    private lateinit var setupButton: Button
    private lateinit var hopEmptyText: TextView
    private lateinit var hopLogContainer: LinearLayout
    private lateinit var hopLog: TextView
    private lateinit var advancedToggleButton: Button
    private lateinit var advancedConnectionContent: LinearLayout
    private lateinit var automaticModeButton: Button
    private lateinit var hostModeButton: Button
    private lateinit var searchModeButton: Button
    private lateinit var manualModeStatus: TextView
    private lateinit var nodesEmpty: TextView
    private lateinit var discoveredNodesContainer: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var locationStatus: TextView
    private lateinit var recordVoiceButton: Button
    private lateinit var voicePreviewLayout: LinearLayout
    private lateinit var voicePreviewLabel: TextView
    private lateinit var playVoicePreviewButton: Button
    private lateinit var deleteVoicePreviewButton: Button

    private lateinit var packetStore: PacketStore
    private lateinit var locationManager: LocationManager
    private var lifelineService: LifelineService? = null
    private var serviceBound = false

    private var latestLocation: Location? = null
    private var setupFlowActive = false
    private var startAfterSetup = false
    private var advancedVisible = false
    private var pendingStartMode = NearbyMeshManager.ConnectionMode.AUTOMATIC
    private var currentMode = NearbyMeshManager.ConnectionMode.AUTOMATIC
    private val approvalDialogs = linkedMapOf<String, AlertDialog>()

    // Audio recording & playback state
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isRecording = false
    private var recordedSeconds = 0
    private var tempAudioFile: File? = null
    private var currentAudioBase64: String? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            recordedSeconds++
            recordVoiceButton.text = getString(R.string.stop_recording, recordedSeconds)
            if (recordedSeconds >= MAX_AUDIO_DURATION_SECONDS) {
                stopVoiceRecording()
            } else {
                timerHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            lifelineService = (binder as LifelineService.LocalBinder).service()
            serviceBound = true
            lifelineService?.setUiListener(this@MainActivity)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            lifelineService = null
            onMeshStateChanged(false, 0)
        }
    }

    private val dashboardStatus by lazy { DashboardStatus(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = getColor(R.color.navy)
        window.navigationBarColor = getColor(R.color.navy)

        bindViews()
        setupQuickChips()
        setupVoiceControls()
        findViewById<Button>(R.id.openWarningButton).setOnClickListener {
            startActivity(Intent(this, EarlyWarningActivity::class.java))
        }
        findViewById<Button>(R.id.openAnalysisButton).setOnClickListener {
            startActivity(Intent(this, DisasterReportActivity::class.java))
        }
        findViewById<Button>(R.id.openSettingsButton).setOnClickListener {
            startActivity(Intent(this, EndpointSettingsActivity::class.java))
        }

        findViewById<Button>(R.id.openCommunityReportButton).setOnClickListener {
            startActivity(Intent(this, CommunityAlertActivity::class.java))
        }

        val preferences = getSharedPreferences(LifelineService.IDENTITY_PREFERENCES, MODE_PRIVATE)
        val deviceId = preferences.getString(LifelineService.KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(LifelineService.KEY_DEVICE_ID, it).apply()
            }
        nameInput.setText(preferences.getString("sender_name", ""))

        packetStore = PacketStore(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        toggleButton.setOnClickListener {
            if (lifelineService?.isActive() == true) {
                lifelineService?.stopLifeline()
            } else {
                pendingStartMode = currentMode
                beginGuidedSetup(startWhenReady = true)
            }
        }
        setupButton.setOnClickListener { beginGuidedSetup(startWhenReady = false) }
        advancedToggleButton.setOnClickListener { toggleAdvancedControls() }
        automaticModeButton.setOnClickListener { chooseConnectionMode(NearbyMeshManager.ConnectionMode.AUTOMATIC) }
        hostModeButton.setOnClickListener { chooseConnectionMode(NearbyMeshManager.ConnectionMode.HOST) }
        searchModeButton.setOnClickListener { chooseConnectionMode(NearbyMeshManager.ConnectionMode.SEARCH) }
        findViewById<Button>(R.id.sendButton).setOnClickListener { sendSos(deviceId) }

        renderConnectionMode(currentMode)
        renderHopLog()
        renderNetworkReadiness()
        if (hasAllPermissions()) startLocationUpdates() else locationStatus.setText(R.string.location_permission_needed)

        // Directly launch permission sequence on first run without unnecessary intermediate blocker dialogs
        window.decorView.postDelayed({ promptRequirementsDirectlyIfNeeded() }, SETUP_PROMPT_DELAY_MS)
    }

    override fun onResume() {
        super.onResume()
        if (::networkChecks.isInitialized) renderNetworkReadiness()
        dashboardStatus.refresh()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, LifelineService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        stopVoiceRecording()
        stopAudioPlayback()
        approvalDialogs.values.toList().forEach(AlertDialog::dismiss)
        approvalDialogs.clear()
        if (serviceBound) {
            lifelineService?.setUiListener(null)
            unbindService(serviceConnection)
            serviceBound = false
            lifelineService = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        dashboardStatus.close()
        stopVoiceRecording()
        stopAudioPlayback()
        runCatching { locationManager.removeUpdates(this) }
        super.onDestroy()
    }

    private fun bindViews() {
        statusTitle = findViewById(R.id.statusTitle)
        peerCount = findViewById(R.id.peerCount)
        connectionDetail = findViewById(R.id.connectionDetail)
        toggleButton = findViewById(R.id.toggleMeshButton)
        setupWarningLayout = findViewById(R.id.setupWarningLayout)
        networkChecks = findViewById(R.id.networkChecks)
        setupButton = findViewById(R.id.setupButton)
        hopEmptyText = findViewById(R.id.hopEmptyText)
        hopLogContainer = findViewById(R.id.hopLogContainer)
        hopLog = findViewById(R.id.hopLog)
        advancedToggleButton = findViewById(R.id.advancedToggleButton)
        advancedConnectionContent = findViewById(R.id.advancedConnectionContent)
        automaticModeButton = findViewById(R.id.automaticModeButton)
        hostModeButton = findViewById(R.id.hostModeButton)
        searchModeButton = findViewById(R.id.searchModeButton)
        manualModeStatus = findViewById(R.id.manualModeStatus)
        nodesEmpty = findViewById(R.id.nodesEmpty)
        discoveredNodesContainer = findViewById(R.id.discoveredNodesContainer)
        nameInput = findViewById(R.id.nameInput)
        messageInput = findViewById(R.id.messageInput)
        locationStatus = findViewById(R.id.locationStatus)
        recordVoiceButton = findViewById(R.id.recordVoiceButton)
        voicePreviewLayout = findViewById(R.id.voicePreviewLayout)
        voicePreviewLabel = findViewById(R.id.voicePreviewLabel)
        playVoicePreviewButton = findViewById(R.id.playVoicePreviewButton)
        deleteVoicePreviewButton = findViewById(R.id.deleteVoicePreviewButton)
    }

    private fun setupQuickChips() {
        val chips = listOf(
            findViewById<Button>(R.id.chipFlood) to "Flood water rising, immediate rescue needed.",
            findViewById<Button>(R.id.chipLandslide) to "Landslide occurred, road blocked and trapped.",
            findViewById<Button>(R.id.chipMedical) to "Medical emergency, critical care needed urgently.",
            findViewById<Button>(R.id.chipTrapped) to "People trapped inside damaged building/debris.",
            findViewById<Button>(R.id.chipFood) to "Urgent need for food and clean drinking water."
        )
        chips.forEach { (button, text) ->
            button?.setOnClickListener {
                val current = messageInput.text.toString().trim()
                if (current.isEmpty()) {
                    messageInput.setText(text)
                } else if (!current.contains(text)) {
                    messageInput.setText("$current • $text")
                }
                messageInput.setSelection(messageInput.text.length)
            }
        }
    }

    private fun setupVoiceControls() {
        recordVoiceButton.setOnClickListener {
            if (isRecording) stopVoiceRecording() else startVoiceRecording()
        }
        playVoicePreviewButton.setOnClickListener {
            val base64 = currentAudioBase64
            if (!base64.isNullOrBlank()) {
                toggleAudioPlayback(base64, playVoicePreviewButton)
            }
        }
        deleteVoicePreviewButton.setOnClickListener {
            discardRecordedVoice()
        }
    }

    private fun startVoiceRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)
            return
        }
        stopAudioPlayback()
        val file = File(cacheDir, "voice_sos_${System.currentTimeMillis()}.amr")
        tempAudioFile = file
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(file.absolutePath)
                setMaxDuration(MAX_AUDIO_DURATION_SECONDS * 1000)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopVoiceRecording()
                    }
                }
                prepare()
                start()
            }
            mediaRecorder = recorder
            isRecording = true
            recordedSeconds = 0
            recordVoiceButton.text = getString(R.string.stop_recording, 0)
            recordVoiceButton.setBackgroundColor(getColor(R.color.danger))
            recordVoiceButton.setTextColor(getColor(R.color.white))
            voicePreviewLayout.visibility = View.GONE
            timerHandler.postDelayed(timerRunnable, 1000L)
        } catch (e: Exception) {
            recorder.release()
            mediaRecorder = null
            Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVoiceRecording() {
        if (!isRecording) return
        isRecording = false
        timerHandler.removeCallbacks(timerRunnable)
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (ignored: Exception) { }
        mediaRecorder = null

        recordVoiceButton.text = getString(R.string.record_voice)
        recordVoiceButton.setBackgroundResource(R.drawable.bg_chip)
        recordVoiceButton.setTextColor(getColor(R.color.navy))

        val file = tempAudioFile
        if (file != null && file.exists() && file.length() > 0) {
            val bytes = file.readBytes()
            currentAudioBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            voicePreviewLayout.visibility = View.VISIBLE
            voicePreviewLabel.text = getString(R.string.voice_attached, recordedSeconds.coerceAtLeast(1))
            if (messageInput.text.toString().trim().isEmpty()) {
                messageInput.setText(getString(R.string.journey_voice_badge))
            }
        }
    }

    private fun discardRecordedVoice() {
        stopAudioPlayback()
        currentAudioBase64 = null
        tempAudioFile?.delete()
        tempAudioFile = null
        voicePreviewLayout.visibility = View.GONE
        if (messageInput.text.toString().trim() == getString(R.string.journey_voice_badge)) {
            messageInput.text.clear()
        }
    }

    private fun toggleAudioPlayback(base64Data: String, playButton: Button) {
        if (mediaPlayer?.isPlaying == true) {
            stopAudioPlayback()
            playButton.text = getString(R.string.play_voice)
            return
        }
        stopAudioPlayback()
        try {
            val audioBytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)
            val tempPlayFile = File.createTempFile("playback_", ".amr", cacheDir)
            tempPlayFile.writeBytes(audioBytes)

            val player = MediaPlayer().apply {
                setDataSource(tempPlayFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    playButton.text = getString(R.string.play_voice)
                    stopAudioPlayback()
                }
            }
            mediaPlayer = player
            playButton.text = getString(R.string.stop_voice)
        } catch (e: Exception) {
            Toast.makeText(this, "Audio playback error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAudioPlayback() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (ignored: Exception) { }
        mediaPlayer = null
    }

    private fun promptRequirementsDirectlyIfNeeded() {
        if (setupFlowActive || allRequirementsReady() || isFinishing || isDestroyed) return
        beginGuidedSetup(startWhenReady = false)
    }

    private fun beginGuidedSetup(startWhenReady: Boolean) {
        startAfterSetup = startAfterSetup || startWhenReady
        setupFlowActive = true
        continueGuidedSetup()
    }

    private fun continueGuidedSetup() {
        if (!setupFlowActive) return

        val missingPermissions = permissionsToRequest().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), PERMISSION_REQUEST)
            return
        }

        if (!batteryOptimizationsDisabled()) {
            requestScreenOffPermission()
            return
        }

        if (!bluetoothEnabled()) {
            launchSetupIntent(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                BLUETOOTH_REQUEST,
                "Bluetooth could not be opened"
            )
            return
        }

        if (!wifiEnabled()) {
            val wifiIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_WIFI)
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            launchSetupIntent(wifiIntent, WIFI_REQUEST, "Wi-Fi settings could not be opened")
            return
        }

        if (!locationEnabled()) {
            launchSetupIntent(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                LOCATION_REQUEST,
                "Location settings could not be opened"
            )
            return
        }

        val playServicesStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        if (playServicesStatus != ConnectionResult.SUCCESS) {
            val dialog = GoogleApiAvailability.getInstance()
                .getErrorDialog(this, playServicesStatus, PLAY_SERVICES_REQUEST)
            if (dialog != null) {
                dialog.setOnCancelListener { stopGuidedSetup("Google Play services still needs attention") }
                dialog.show()
            } else {
                stopGuidedSetup("Google Play services is not available on this phone")
            }
            return
        }

        setupFlowActive = false
        renderNetworkReadiness()
        startLocationUpdates()
        Toast.makeText(this, R.string.phone_ready_toast, Toast.LENGTH_SHORT).show()
        if (startAfterSetup) {
            startAfterSetup = false
            startLifeline(pendingStartMode)
        }
    }

    private fun launchSetupIntent(intent: Intent, requestCode: Int, failureMessage: String) {
        if (runCatching { startActivityForResult(intent, requestCode) }.isFailure) {
            stopGuidedSetup(failureMessage)
        }
    }

    private fun stopGuidedSetup(message: String) {
        setupFlowActive = false
        startAfterSetup = false
        renderNetworkReadiness()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun chooseConnectionMode(mode: NearbyMeshManager.ConnectionMode) {
        currentMode = mode
        pendingStartMode = mode
        renderConnectionMode(mode)
        if (lifelineService?.isActive() == true) {
            lifelineService?.setConnectionMode(mode)
        } else {
            beginGuidedSetup(startWhenReady = true)
        }
    }

    private fun startLifeline(mode: NearbyMeshManager.ConnectionMode) {
        if (!connectionReady()) {
            pendingStartMode = mode
            beginGuidedSetup(startWhenReady = true)
            return
        }
        currentMode = mode
        startForegroundService(
            Intent(this, LifelineService::class.java)
                .setAction(LifelineService.ACTION_START)
                .putExtra(LifelineService.EXTRA_CONNECTION_MODE, mode.name)
        )
    }

    private fun sendSos(deviceId: String) {
        val message = messageInput.text.toString().trim()
        if (message.isEmpty() && currentAudioBase64 == null) {
            messageInput.error = getString(R.string.describe_emergency)
            messageInput.requestFocus()
            return
        }
        val effectiveMessage = message.ifEmpty { getString(R.string.journey_voice_badge) }
        val sender = nameInput.text.toString().trim().ifEmpty { getString(R.string.unknown_survivor) }
        getSharedPreferences(LifelineService.IDENTITY_PREFERENCES, MODE_PRIVATE)
            .edit().putString("sender_name", sender).apply()

        val position = latestLocation ?: Location("sindhupalchok_fallback").apply {
            latitude = DEFAULT_SINDHUPALCHOK_LAT
            longitude = DEFAULT_SINDHUPALCHOK_LNG
        }
        val packet = MeshPacket.create(
            senderName = sender,
            originDeviceId = deviceId,
            message = effectiveMessage,
            latitude = position.latitude,
            longitude = position.longitude,
            audioBase64 = currentAudioBase64
        )
        val sendResult = lifelineService?.send(packet) ?: run {
            if (packetStore.saveOutgoing(packet)) {
                NearbyMeshManager.SendResult.SAVED_AND_PAUSED
            } else {
                NearbyMeshManager.SendResult.STORAGE_FAILED
            }
        }
        if (sendResult == NearbyMeshManager.SendResult.STORAGE_FAILED) {
            Toast.makeText(this, R.string.sos_storage_failed, Toast.LENGTH_LONG).show()
            return
        }
        messageInput.text.clear()
        discardRecordedVoice()

        connectionDetail.text = if (sendResult == NearbyMeshManager.SendResult.HANDED_TO_PEER) {
            getString(R.string.sos_handed_to_mesh)
        } else {
            getString(R.string.sos_saved_waiting)
        }
        renderHopLog()
        Toast.makeText(this, R.string.sos_saved, Toast.LENGTH_SHORT).show()
    }

    override fun onMeshStateChanged(active: Boolean, peerCount: Int) {
        runOnUiThread {
            statusTitle.text = if (active) getString(R.string.mesh_on) else getString(R.string.mesh_off)
            statusTitle.setTextColor(getColor(if (active) R.color.teal else R.color.navy))
            this.peerCount.text = when (peerCount) {
                0 -> getString(R.string.no_peers)
                else -> resources.getQuantityString(R.plurals.peer_count, peerCount, peerCount)
            }
            toggleButton.text = if (active) getString(R.string.stop_lifeline) else getString(R.string.start_lifeline)
            toggleButton.setBackgroundResource(if (active) R.drawable.bg_stop_button else R.drawable.bg_primary_button)
            if (!active) connectionDetail.setText(R.string.connection_waiting)
        }
    }

    override fun onConnectionModeChanged(mode: NearbyMeshManager.ConnectionMode) {
        runOnUiThread {
            currentMode = mode
            renderConnectionMode(mode)
        }
    }

    override fun onDiscoveredNodesChanged(nodes: List<NearbyMeshManager.DiscoveredNode>) {
        runOnUiThread { renderDiscoveredNodes(nodes) }
    }

    override fun onConnectionApprovalRequired(approval: NearbyMeshManager.ConnectionApproval) {
        runOnUiThread {
            if (isFinishing || isDestroyed || approvalDialogs.containsKey(approval.endpointId)) return@runOnUiThread
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.verify_phone)
                .setMessage(
                    getString(
                        R.string.verify_phone_message,
                        approval.endpointName,
                        approval.authenticationDigits
                    )
                )
                .setPositiveButton(R.string.accept) { _, _ ->
                    lifelineService?.approveConnection(approval.endpointId)
                }
                .setNegativeButton(R.string.reject) { _, _ ->
                    lifelineService?.rejectConnection(approval.endpointId)
                }
                .setCancelable(false)
                .create()
            dialog.setOnDismissListener { approvalDialogs.remove(approval.endpointId) }
            approvalDialogs[approval.endpointId] = dialog
            dialog.show()
        }
    }

    override fun onConnectionApprovalResolved(endpointId: String) {
        runOnUiThread { approvalDialogs.remove(endpointId)?.dismiss() }
    }

    override fun onHopLogChanged() {
        runOnUiThread { renderHopLog() }
    }

    override fun onPacketReceived(packet: MeshPacket) {
        runOnUiThread {
            val latStr = String.format("%.4f", packet.latitude)
            val lngStr = String.format("%.4f", packet.longitude)
            connectionDetail.text = "Emergency SOS from ${packet.senderName} • 📍 $latStr, $lngStr"
            renderHopLog()
            Toast.makeText(this, "Emergency SOS from ${packet.senderName} (📍 $latStr, $lngStr)", Toast.LENGTH_LONG).show()
        }
    }

    override fun onEvent(message: String) {
        runOnUiThread { connectionDetail.text = friendlyEvent(message) }
    }

    private fun friendlyEvent(message: String): String {
        val event = message.lowercase()
        return when {
            "disconnected" in event -> getString(R.string.searching_again)
            "connected" in event -> getString(R.string.connected_ready)
            "offered" in event || "forwarding" in event -> getString(R.string.sending_saved_messages)
            "paused" in event || "waiting for the next node" in event -> getString(R.string.sos_saved_waiting)
            "failed" in event || "blocked" in event || "retry" in event -> getString(R.string.still_searching)
            "scanning" in event || "advertising" in event || "visible" in event || "search" in event ->
                getString(R.string.looking_for_phones)
            else -> message
        }
    }

    private fun toggleAdvancedControls() {
        advancedVisible = !advancedVisible
        advancedConnectionContent.visibility = if (advancedVisible) View.VISIBLE else View.GONE
        advancedToggleButton.text = getString(
            if (advancedVisible) R.string.hide_manual_connection else R.string.show_manual_connection
        )
    }

    private fun renderConnectionMode(mode: NearbyMeshManager.ConnectionMode) {
        automaticModeButton.isEnabled = mode != NearbyMeshManager.ConnectionMode.AUTOMATIC
        hostModeButton.isEnabled = mode != NearbyMeshManager.ConnectionMode.HOST
        searchModeButton.isEnabled = mode != NearbyMeshManager.ConnectionMode.SEARCH
        automaticModeButton.alpha = if (automaticModeButton.isEnabled) 1f else 0.55f
        hostModeButton.alpha = if (hostModeButton.isEnabled) 1f else 0.55f
        searchModeButton.alpha = if (searchModeButton.isEnabled) 1f else 0.55f

        manualModeStatus.text = when (mode) {
            NearbyMeshManager.ConnectionMode.AUTOMATIC -> getString(R.string.manual_auto_status)
            NearbyMeshManager.ConnectionMode.HOST -> getString(R.string.manual_host_status)
            NearbyMeshManager.ConnectionMode.SEARCH -> getString(R.string.manual_search_status)
        }
        val showNodes = mode == NearbyMeshManager.ConnectionMode.SEARCH
        nodesEmpty.visibility = if (showNodes) View.VISIBLE else View.GONE
        discoveredNodesContainer.visibility = if (showNodes) View.VISIBLE else View.GONE
        if (!showNodes) discoveredNodesContainer.removeAllViews()
    }

    private fun renderDiscoveredNodes(nodes: List<NearbyMeshManager.DiscoveredNode>) {
        discoveredNodesContainer.removeAllViews()
        val showNodes = currentMode == NearbyMeshManager.ConnectionMode.SEARCH
        nodesEmpty.visibility = if (showNodes && nodes.isEmpty()) View.VISIBLE else View.GONE
        discoveredNodesContainer.visibility = if (showNodes) View.VISIBLE else View.GONE
        if (!showNodes) return

        nodes.forEach { node ->
            val button = Button(this).apply {
                text = getString(R.string.connect_to_phone, node.name)
                isAllCaps = false
                setOnClickListener {
                    isEnabled = false
                    text = getString(R.string.connecting_to_phone, node.name)
                    lifelineService?.connectToEndpoint(node.endpointId)
                }
            }
            discoveredNodesContainer.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun renderHopLog() {
        val entries = packetStore.loadHopLog(20)
        if (entries.isEmpty()) {
            hopEmptyText.visibility = View.VISIBLE
            hopLogContainer.visibility = View.GONE
            hopLogContainer.removeAllViews()
            return
        }
        hopEmptyText.visibility = View.GONE
        hopLogContainer.visibility = View.VISIBLE
        hopLogContainer.removeAllViews()

        entries.forEach { entry ->
            val itemView = layoutInflater.inflate(R.layout.item_hop_message, hopLogContainer, false)
            val badge = itemView.findViewById<TextView>(R.id.hopStatusBadge)
            val timestamp = itemView.findViewById<TextView>(R.id.hopTimestamp)
            val content = itemView.findViewById<TextView>(R.id.hopMessageContent)
            val footer = itemView.findViewById<TextView>(R.id.hopDetailFooter)
            val playButton = itemView.findViewById<Button>(R.id.hopPlayVoiceButton)

            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.createdAt))
            timestamp.text = time
            content.text = entry.message

            val packet = packetStore.getPacket(entry.packetId)
            val audioData = packet?.audioBase64
            val coordinatesView = itemView.findViewById<TextView>(R.id.hopCoordinates)
            if (packet != null) {
                coordinatesView.visibility = View.VISIBLE
                val latStr = String.format("%.5f", packet.latitude)
                val lngStr = String.format("%.5f", packet.longitude)
                coordinatesView.text = "📍 GPS Location: $latStr° N, $lngStr° E"
                coordinatesView.setOnClickListener {
                    runCatching {
                        val geoUri = Uri.parse("geo:${packet.latitude},${packet.longitude}?q=${packet.latitude},${packet.longitude}(Survivor+SOS)")
                        startActivity(Intent(Intent.ACTION_VIEW, geoUri))
                    }
                }
                footer.text = "Hop ${entry.hopCount} • ${entry.peer} • 📍 $latStr, $lngStr"
            } else {
                coordinatesView.visibility = View.GONE
                footer.text = "Hop ${entry.hopCount} • ${entry.peer}"
            }

            if (!audioData.isNullOrBlank()) {
                playButton.visibility = View.VISIBLE
                playButton.setOnClickListener {
                    toggleAudioPlayback(audioData, playButton)
                }
            } else {
                playButton.visibility = View.GONE
            }

            when (entry.action) {
                "CREATED" -> {
                    badge.text = getString(R.string.journey_status_saved)
                    badge.setTextColor(getColor(R.color.badge_yellow_text))
                    badge.setBackgroundResource(R.drawable.bg_badge_yellow)
                }
                "PAUSED" -> {
                    badge.text = getString(R.string.journey_status_waiting)
                    badge.setTextColor(getColor(R.color.badge_yellow_text))
                    badge.setBackgroundResource(R.drawable.bg_badge_yellow)
                }
                "RECEIVED" -> {
                    badge.text = getString(R.string.journey_status_received)
                    badge.setTextColor(getColor(R.color.badge_blue_text))
                    badge.setBackgroundResource(R.drawable.bg_badge_blue)
                }
                "FORWARDED" -> {
                    badge.text = getString(R.string.journey_status_relayed)
                    badge.setTextColor(getColor(R.color.badge_green_text))
                    badge.setBackgroundResource(R.drawable.bg_badge_green)
                }
                else -> {
                    badge.text = entry.action
                    badge.setTextColor(getColor(R.color.muted))
                    badge.setBackgroundResource(R.drawable.bg_pill)
                }
            }
            if (!audioData.isNullOrBlank()) {
                badge.text = "${badge.text} • 🎙️"
            }
            hopLogContainer.addView(itemView)
        }
    }

    private fun connectionReady(): Boolean =
        hasAllPermissions() && bluetoothEnabled() && wifiEnabled() && playServicesReady()

    private fun allRequirementsReady(): Boolean =
        runtimePermissionsReady() &&
            batteryOptimizationsDisabled() &&
            bluetoothEnabled() &&
            wifiEnabled() &&
            locationEnabled() &&
            playServicesReady()

    private fun renderNetworkReadiness() {
        val missing = buildList {
            if (!runtimePermissionsReady()) add(getString(R.string.access_permissions))
            if (!batteryOptimizationsDisabled()) add(getString(R.string.background_access))
            if (!bluetoothEnabled()) add(getString(R.string.bluetooth))
            if (!wifiEnabled()) add(getString(R.string.wifi))
            if (!locationEnabled()) add(getString(R.string.gps_location))
            if (!playServicesReady()) add(getString(R.string.google_play_services))
        }
        val ready = missing.isEmpty()
        if (ready) {
            setupWarningLayout.visibility = View.GONE
        } else {
            setupWarningLayout.visibility = View.VISIBLE
            networkChecks.text = getString(R.string.setup_warning_summary) + "\nMissing: " + missing.joinToString(", ")
        }
    }

    private fun bluetoothEnabled(): Boolean = runCatching {
        getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(false)

    private fun wifiEnabled(): Boolean = runCatching {
        (getSystemService(WIFI_SERVICE) as WifiManager).isWifiEnabled
    }.getOrDefault(false)

    private fun locationEnabled(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }.getOrDefault(false)

    private fun playServicesReady(): Boolean = GoogleApiAvailability.getInstance()
        .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS

    private fun runtimePermissionsReady(): Boolean = permissionsToRequest().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    } && getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun batteryOptimizationsDisabled(): Boolean = runCatching {
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)

    @SuppressLint("BatteryLife")
    private fun requestScreenOffPermission() {
        val directRequest = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        if (runCatching { startActivityForResult(directRequest, BATTERY_REQUEST) }.isFailure) {
            launchSetupIntent(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                BATTERY_REQUEST,
                "Battery settings could not be opened"
            )
        }
    }

    private fun startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        val bestKnown = providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull(Location::getTime)

        if (bestKnown != null) {
            latestLocation = bestKnown
            cacheLocation(bestKnown.latitude, bestKnown.longitude)
            showLocation(bestKnown)
        } else {
            val prefs = getSharedPreferences(LifelineService.IDENTITY_PREFERENCES, MODE_PRIVATE)
            if (prefs.contains(KEY_CACHED_LAT) && prefs.contains(KEY_CACHED_LNG)) {
                val cachedLat = prefs.getFloat(KEY_CACHED_LAT, 0f).toDouble()
                val cachedLng = prefs.getFloat(KEY_CACHED_LNG, 0f).toDouble()
                val cachedLoc = Location("cached_gps").apply {
                    latitude = cachedLat
                    longitude = cachedLng
                    time = System.currentTimeMillis()
                }
                latestLocation = cachedLoc
                showLocation(cachedLoc, isCached = true)
            } else {
                val fallbackLoc = Location("sindhupalchok_default").apply {
                    latitude = DEFAULT_SINDHUPALCHOK_LAT
                    longitude = DEFAULT_SINDHUPALCHOK_LNG
                    time = System.currentTimeMillis()
                }
                latestLocation = fallbackLoc
                showLocation(fallbackLoc, isRegional = true)
            }
        }

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            runCatching { locationManager.requestLocationUpdates(provider, 3_000L, 2f, this) }
        }
    }

    override fun onLocationChanged(location: Location) {
        latestLocation = location
        cacheLocation(location.latitude, location.longitude)
        showLocation(location)
    }

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        renderNetworkReadiness()
    }

    private fun showLocation(location: Location, isCached: Boolean = false, isRegional: Boolean = false) {
        val latStr = String.format("%.5f", location.latitude)
        val lngStr = String.format("%.5f", location.longitude)
        locationStatus.text = when {
            isRegional -> "📍 GPS: $latStr, $lngStr (Sindhupalchok)"
            isCached -> "📍 GPS: $latStr, $lngStr (Cached)"
            else -> "📍 GPS: $latStr, $lngStr"
        }
        locationStatus.setTextColor(getColor(R.color.badge_green_text))
    }

    private fun cacheLocation(lat: Double, lng: Double) {
        getSharedPreferences(LifelineService.IDENTITY_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putFloat(KEY_CACHED_LAT, lat.toFloat())
            .putFloat(KEY_CACHED_LNG, lng.toFloat())
            .apply()
    }

    private fun hasAllPermissions(): Boolean = meshPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun meshPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private fun permissionsToRequest(): Array<String> = buildList {
        addAll(meshPermissions())
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        renderNetworkReadiness()
        if (permissionsToRequest().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            startLocationUpdates()
            continueGuidedSetup()
        } else {
            setupFlowActive = false
            startAfterSetup = false
            AlertDialog.Builder(this)
                .setTitle(R.string.access_not_allowed)
                .setMessage(R.string.access_not_allowed_message)
                .setPositiveButton(R.string.open_app_settings) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(R.string.not_now, null)
                .show()
        }
    }

    @Deprecated("Uses the platform setup activities supported by the app's minimum Android version")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!setupFlowActive) return
        val stepReady = when (requestCode) {
            BATTERY_REQUEST -> batteryOptimizationsDisabled()
            BLUETOOTH_REQUEST -> bluetoothEnabled()
            WIFI_REQUEST -> wifiEnabled()
            LOCATION_REQUEST -> locationEnabled()
            PLAY_SERVICES_REQUEST -> playServicesReady()
            else -> return
        }
        renderNetworkReadiness()
        if (stepReady) {
            continueGuidedSetup()
        } else {
            stopGuidedSetup(getString(R.string.setup_incomplete))
        }
    }

    companion object {
        private const val PERMISSION_REQUEST = 1001
        private const val BLUETOOTH_REQUEST = 1002
        private const val WIFI_REQUEST = 1003
        private const val LOCATION_REQUEST = 1004
        private const val BATTERY_REQUEST = 1005
        private const val PLAY_SERVICES_REQUEST = 1006
        private const val SETUP_PROMPT_DELAY_MS = 300L
        private const val MAX_AUDIO_DURATION_SECONDS = 10
        private const val DEFAULT_SINDHUPALCHOK_LAT = 27.9441
        private const val DEFAULT_SINDHUPALCHOK_LNG = 85.9483
        private const val KEY_CACHED_LAT = "cached_latitude"
        private const val KEY_CACHED_LNG = "cached_longitude"
    }
}
