package com.example.webrtcvideochat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.AudioTrack

class MainActivity : AppCompatActivity(), WebRTCManager.WebRTCListener {

    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var terminalOutput: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var peerListLayout: LinearLayout
    private lateinit var peerListScroll: ScrollView
    private lateinit var terminalScroll: ScrollView
    private lateinit var toggleVideoBtn: Button
    private lateinit var toggleAudioBtn: Button
    private lateinit var switchCameraBtn: Button

    private lateinit var webRTCManager: WebRTCManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = "MainActivity"

    private var targetPeerId: String? = null
    private var isCaller = false
    private var connectionTimeoutHandler: Handler? = null
    private var iceRestartAttempts = 0
    private var isVideoEnabled = true
    private var isAudioEnabled = true

    private var eglBase: EglBase? = null

    // Network monitoring
    private val networkCheckHandler = Handler(Looper.getMainLooper())
    private var wasNetworkAvailable = false
    private val networkCheckInterval = 3000L

    // MQTT Configuration - MODIFY THESE
    private val mqttConfig = MqttConfig(
        brokerUrl = "ssl://e5122a5328ea4986a0295fa6e037655a.s2.eu.hivemq.cloud:8883",
        username = "admin",
        password = "admin1234S",
        topic = "webrtc/signaling"
    )

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        if (checkPermissions()) {
            initialize()
        } else {
            requestPermissions()
        }
    }

    private fun checkPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initialize()
        } else {
            Toast.makeText(this, "Permissions required for video chat", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initialize() {
        bindViews()

        eglBase = EglBase.create()

        remoteVideoView.init(eglBase?.eglBaseContext, null)
        remoteVideoView.setEnableHardwareScaler(true)

        webRTCManager = WebRTCManager(this, this)
        webRTCManager.initialize(remoteVideoView, eglBase!!, mqttConfig)

        startNetworkMonitoring()

        if (isInternetAvailable()) {
            webRTCManager.connect()
        } else {
            log("Waiting for internet connection...")
        }

        sendButton.setOnClickListener { sendMessage() }
        toggleVideoBtn.setOnClickListener { toggleVideo() }
        toggleAudioBtn.setOnClickListener { toggleAudio() }
        switchCameraBtn.setOnClickListener { webRTCManager.switchCamera() }

        log("Ready - PeerId: ${webRTCManager.getPeerId()}")
        log("Click a peer to call")
    }

    private fun bindViews() {
        remoteVideoView = findViewById(R.id.remoteVideoView)
        terminalOutput = findViewById(R.id.terminalOutput)
        terminalScroll = findViewById(R.id.terminalScroll)
        peerListScroll = findViewById(R.id.peerListScroll)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)
        peerListLayout = findViewById(R.id.peerList)
        toggleVideoBtn = findViewById(R.id.toggleVideoBtn)
        toggleAudioBtn = findViewById(R.id.toggleAudioBtn)
        switchCameraBtn = findViewById(R.id.switchCameraBtn)
    }

    // ==================== NETWORK MONITORING ====================

    private fun isInternetAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun startNetworkMonitoring() {
        networkCheckHandler.post(object : Runnable {
            override fun run() {
                val isNetworkAvailable = isInternetAvailable()

                if (isNetworkAvailable && !wasNetworkAvailable) {
                    log("Internet connection restored")
                    webRTCManager.connect()
                } else if (!isNetworkAvailable && wasNetworkAvailable) {
                    log("Internet connection lost")
                }

                wasNetworkAvailable = isNetworkAvailable
                networkCheckHandler.postDelayed(this, networkCheckInterval)
            }
        })
    }

    // ==================== WEBRTC LISTENER CALLBACKS ====================

    override fun onPeerListUpdated(peers: Map<String, PeerInfo>) {
        mainHandler.post {
            peerListLayout.removeAllViews()
            peers.forEach { (id, info) ->
                val tv = TextView(this).apply {
                    text = if (id == webRTCManager.getPeerId()) "[me]:$id" else "[online]:$id"
                    setTextColor(if (id == webRTCManager.getPeerId()) 0xFF888888.toInt() else 0xFF00FF00.toInt())
                    textSize = 14f
                    setPadding(8, 4, 8, 4)
                    setOnClickListener { selectPeer(id) }
                }
                if (id == targetPeerId) {
                    tv.setTextColor(0xFFFFFF00.toInt())
                    tv.text = "[selected]:$id"
                }
                peerListLayout.addView(tv)
            }
            peerListScroll.post { peerListScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onOfferReceived(from: String, sdp: String) {
        log("Incoming call from $from")
        targetPeerId = from
        webRTCManager.selectPeer(from)
        isCaller = false
        iceRestartAttempts = 0
        sendButton.isEnabled = false
        startConnectionTimeout()

        webRTCManager.createConnection(false) {
            val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
            webRTCManager.setRemoteDescription(sessionDesc) {
                webRTCManager.createAnswer { answer ->
                    webRTCManager.sendAnswer(answer.description)
                }
            }
        }
    }

    override fun onAnswerReceived(from: String, sdp: String) {
        log("Answer received")
        val sessionDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        webRTCManager.setRemoteDescription(sessionDesc)
    }

    override fun onIceCandidateReceived(from: String, candidate: IceCandidateData) {
        webRTCManager.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        )
    }

    override fun onConnected() {
        log("Online")
    }

    override fun onConnectionLost(error: Throwable?) {
        error?.let { log("Connection lost: ${it.message}") }
    }

    override fun onDataChannelOpen() {
        cancelConnectionTimeout()
        mainHandler.post {
            sendButton.isEnabled = true
            log("=== DataChannel Opened - Messaging Enabled ===")
        }
    }

    override fun onDataChannelClosed() {
        mainHandler.post {
            sendButton.isEnabled = false
            log("=== DataChannel Closed - Messaging Disabled ===")
        }
    }

    override fun onMessageReceived(message: String) {
        log("> $message")
    }

    override fun onIceStateChanged(state: PeerConnection.IceConnectionState) {
        mainHandler.post {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    iceRestartAttempts = 0
                    log("ICE Connected")
                }
                PeerConnection.IceConnectionState.COMPLETED -> {
                    iceRestartAttempts = 0
                    log("ICE Completed")
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    log("ICE Failed")
                    if (!isCaller && iceRestartAttempts < 3) iceRestartAttempts++
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    log("ICE Disconnected")
                    sendButton.isEnabled = false
                }
                else -> {}
            }
        }
    }

    override fun onRemoteVideoTrack(track: VideoTrack) {
        log("Video started")
    }

    override fun onRemoteAudioTrack(track: AudioTrack) {
        log("Audio started")
    }

    // ==================== UI ACTIONS ====================

    private fun selectPeer(id: String) {
        if (id == webRTCManager.getPeerId()) {
            Toast.makeText(this, "Cannot call yourself", Toast.LENGTH_SHORT).show()
            return
        }

        log("Calling $id...")
        webRTCManager.close()
        targetPeerId = id
        webRTCManager.selectPeer(id)
        isCaller = true
        iceRestartAttempts = 0
        sendButton.isEnabled = false
        startConnectionTimeout()

        webRTCManager.createConnection(true) {
            webRTCManager.createOffer { sdp ->
                webRTCManager.sendOffer(sdp.description)
            }
        }
    }

    private fun toggleVideo() {
        isVideoEnabled = !isVideoEnabled
        webRTCManager.toggleVideo(isVideoEnabled)
        toggleVideoBtn.text = if (isVideoEnabled) "Video Off" else "Video On"
    }

    private fun toggleAudio() {
        isAudioEnabled = !isAudioEnabled
        webRTCManager.toggleAudio(isAudioEnabled)
        toggleAudioBtn.text = if (isAudioEnabled) "Mute" else "Unmute"
    }

    private fun sendMessage() {
        val msg = inputField.text.toString().trim()
        if (msg.isEmpty()) return

        if (webRTCManager.sendMessage(msg)) {
            log("< $msg")
            inputField.text.clear()
        } else {
            log("Send failed")
        }
    }

    private fun log(message: String) {
        Log.d(tag, message)
        mainHandler.post {
            terminalOutput.append(message + "\n")
            terminalScroll.post { terminalScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun startConnectionTimeout() {
        connectionTimeoutHandler?.removeCallbacksAndMessages(null)
        connectionTimeoutHandler = Handler(Looper.getMainLooper())
        connectionTimeoutHandler?.postDelayed({
            if (!sendButton.isEnabled) {
                log("Connection timeout")
                if (iceRestartAttempts < 3) {
                    iceRestartAttempts++
                    webRTCManager.createConnection(isCaller) {
                        if (isCaller) {
                            webRTCManager.createOffer { sdp ->
                                webRTCManager.sendOffer(sdp.description)
                            }
                        }
                    }
                } else {
                    log("Connection failed")
                }
            }
        }, 15000)
    }

    private fun cancelConnectionTimeout() {
        connectionTimeoutHandler?.removeCallbacksAndMessages(null)
        iceRestartAttempts = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelConnectionTimeout()
        networkCheckHandler.removeCallbacksAndMessages(null)
        remoteVideoView.release()
        eglBase?.release()
        webRTCManager.cleanup()
    }
}
