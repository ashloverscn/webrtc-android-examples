package com.example.webrtcvideochat

import android.Manifest
import android.content.pm.PackageManager
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
import org.webrtc.RendererCommon

class MainActivity : AppCompatActivity(),
    SignalingClient.SignalingListener,
    VideoWebRTCManager.WebRTCListener {

    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var localVideoView: SurfaceViewRenderer
    private lateinit var terminalOutput: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var peerListLayout: LinearLayout
    private lateinit var peerListScroll: ScrollView
    private lateinit var terminalScroll: ScrollView
    private lateinit var toggleVideoBtn: Button
    private lateinit var toggleAudioBtn: Button
    private lateinit var switchCameraBtn: Button
    private lateinit var connectionStatus: TextView

    private lateinit var signalingClient: SignalingClient
    private lateinit var webRTCManager: VideoWebRTCManager

    private val peerId = "peer_" + (100000..999999).random()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = "MainActivity"

    private var targetPeerId: String? = null
    private var isCaller = false
    private var connectionTimeoutHandler: Handler? = null
    private var iceRestartAttempts = 0
    private var isVideoEnabled = true
    private var isAudioEnabled = true

    private var eglBase: EglBase? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on during video call
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
        // 1. Bind all views first
        bindViews()

        // 2. Create EglBase - MUST be done before initializing SurfaceViewRenderers
        eglBase = EglBase.create()

        // 3. Initialize LOCAL video view FIRST (picture-in-picture)
        // CRITICAL: Must use same eglBaseContext for all views
        localVideoView.init(eglBase?.eglBaseContext, null)
        localVideoView.setEnableHardwareScaler(true)
        localVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        localVideoView.setMirror(true)  // Mirror so it looks like a selfie camera
        localVideoView.setZOrderMediaOverlay(true)  // CRITICAL: Allows it to render on top

        // 4. Initialize REMOTE video view (full screen background)
        remoteVideoView.init(eglBase?.eglBaseContext, null)
        remoteVideoView.setEnableHardwareScaler(true)
        remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)

        updateStatus("Initializing...")

        // 5. Create signaling client and WebRTC manager
        signalingClient = SignalingClient(this, peerId, this)
        webRTCManager = VideoWebRTCManager(this, this)

        // 6. Initialize video with BOTH views - localView first for PIP
        webRTCManager.initializeVideo(this, localVideoView, remoteVideoView, eglBase!!, true)

        // 7. Delayed re-attach to ensure everything is ready
        mainHandler.postDelayed({
            webRTCManager.attachLocalVideoSink()
        }, 500)

        signalingClient.connect()

        // Setup UI listeners
        sendButton.setOnClickListener { sendMessage() }
        toggleVideoBtn.setOnClickListener { toggleVideo() }
        toggleAudioBtn.setOnClickListener { toggleAudio() }
        switchCameraBtn.setOnClickListener { webRTCManager.switchCamera() }

        log("Ready - PeerId: $peerId")
        log("Click a peer to call")
        updateStatus("Online - $peerId")
    }

    private fun bindViews() {
        remoteVideoView = findViewById(R.id.remoteVideoView)
        localVideoView = findViewById(R.id.localVideoView)
        terminalOutput = findViewById(R.id.terminalOutput)
        terminalScroll = findViewById(R.id.terminalScroll)
        peerListScroll = findViewById(R.id.peerListScroll)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)
        peerListLayout = findViewById(R.id.peerList)
        toggleVideoBtn = findViewById(R.id.toggleVideoBtn)
        toggleAudioBtn = findViewById(R.id.toggleAudioBtn)
        switchCameraBtn = findViewById(R.id.switchCameraBtn)
        connectionStatus = findViewById(R.id.connectionStatus)
    }

    private fun updateStatus(status: String) {
        mainHandler.post {
            connectionStatus.text = status
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
                                signalingClient.sendOffer(sdp.description)
                            }
                        }
                    }
                } else {
                    log("Connection failed")
                    updateStatus("Connection failed")
                }
            }
        }, 15000)
    }

    private fun cancelConnectionTimeout() {
        connectionTimeoutHandler?.removeCallbacksAndMessages(null)
        iceRestartAttempts = 0
    }

    private fun toggleVideo() {
        isVideoEnabled = !isVideoEnabled
        webRTCManager.toggleVideo(isVideoEnabled)
        toggleVideoBtn.text = if (isVideoEnabled) "Video Off" else "Video On"
        localVideoView.visibility = if (isVideoEnabled) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    private fun toggleAudio() {
        isAudioEnabled = !isAudioEnabled
        webRTCManager.toggleAudio(isAudioEnabled)
        toggleAudioBtn.text = if (isAudioEnabled) "Mute" else "Unmute"
    }

    override fun onPeerListUpdated(peers: Map<String, PeerRegistry.PeerInfo>) {
        mainHandler.post {
            peerListLayout.removeAllViews()
            peers.forEach { (id, info) ->
                val tv = TextView(this).apply {
                    text = if (id == peerId) "[me]:$id" else "[online]:$id"
                    setTextColor(if (id == peerId) 0xFF888888.toInt() else 0xFF00FF00.toInt())
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

    private fun selectPeer(id: String) {
        if (id == peerId) {
            Toast.makeText(this, "Cannot call yourself", Toast.LENGTH_SHORT).show()
            return
        }

        log("Calling $id...")
        updateStatus("Calling $id...")
        webRTCManager.close()
        iceRestartAttempts = 0

        targetPeerId = id
        signalingClient.selectPeer(id)
        isCaller = true

        sendButton.isEnabled = false

        startConnectionTimeout()

        webRTCManager.createConnection(true) {
            webRTCManager.createOffer { sdp ->
                signalingClient.sendOffer(sdp.description)
            }
        }
    }

    override fun onOfferReceived(from: String, sdp: String) {
        log("Incoming call from $from")
        updateStatus("Incoming call from $from")

        targetPeerId = from
        signalingClient.selectPeer(from)
        isCaller = false
        iceRestartAttempts = 0

        sendButton.isEnabled = false

        startConnectionTimeout()

        webRTCManager.createConnection(false) {
            val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
            webRTCManager.setRemoteDescription(sessionDesc) {
                webRTCManager.createAnswer { answer ->
                    signalingClient.sendAnswer(answer.description)
                }
            }
        }
    }

    override fun onAnswerReceived(from: String, sdp: String) {
        log("Answer received")
        val sessionDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        webRTCManager.setRemoteDescription(sessionDesc)
    }

    override fun onIceCandidateReceived(from: String, candidate: SignalingClient.IceCandidateData) {
        webRTCManager.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        )
    }

    override fun onConnected() {
        log("Online")
    }

    override fun onError(error: Throwable) {
        log("Error: ${error.message}")
    }

    override fun onDataChannelOpen() {
        cancelConnectionTimeout()
        mainHandler.post {
            sendButton.isEnabled = true
            log("=== DataChannel Opened - Messaging Enabled ===")
            updateStatus("Connected - Ready to chat")
        }
    }

    override fun onDataChannelClosed() {
        mainHandler.post {
            sendButton.isEnabled = false
            log("=== DataChannel Closed - Messaging Disabled ===")
            updateStatus("Disconnected")
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
                    updateStatus("ICE Completed")
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    log("ICE Failed")
                    updateStatus("ICE Failed")
                    if (!isCaller && iceRestartAttempts < 3) {
                        iceRestartAttempts++
                    }
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    log("ICE Disconnected")
                    sendButton.isEnabled = false
                    updateStatus("ICE Disconnected")
                }
                else -> {}
            }
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        signalingClient.sendIceCandidate(candidate)
    }

    override fun onError(error: String) {
        log("Error: $error")
    }

    override fun onLocalVideoTrack(track: VideoTrack) {
        log("Local video track ready - PIP should be visible")
    }

    override fun onRemoteVideoTrack(track: VideoTrack) {
        log("Remote video started")
        updateStatus("Video connected")
    }

    override fun onRemoteAudioTrack(track: AudioTrack) {
        log("Remote audio started")
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

    override fun onDestroy() {
        super.onDestroy()
        cancelConnectionTimeout()
        // Release views in reverse order
        localVideoView.release()
        remoteVideoView.release()
        eglBase?.release()
        webRTCManager.cleanup()
        signalingClient.destroy()
    }
}