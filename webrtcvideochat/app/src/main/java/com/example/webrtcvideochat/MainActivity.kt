package com.example.webrtcvideochat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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

class MainActivity : AppCompatActivity(),
    SignalingClient.SignalingListener,
    VideoWebRTCManager.WebRTCListener {

    private lateinit var localVideoView: SurfaceViewRenderer
    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var terminalOutput: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var peerListLayout: LinearLayout
    private lateinit var connectionStatus: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var toggleVideoBtn: Button
    private lateinit var toggleAudioBtn: Button
    private lateinit var switchCameraBtn: Button

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

        localVideoView.init(eglBase?.eglBaseContext, null)
        remoteVideoView.init(eglBase?.eglBaseContext, null)
        localVideoView.setZOrderMediaOverlay(true)
        localVideoView.setEnableHardwareScaler(true)
        remoteVideoView.setEnableHardwareScaler(true)

        signalingClient = SignalingClient(this, peerId, this)
        webRTCManager = VideoWebRTCManager(this, this)

        webRTCManager.initializeVideo(this, localVideoView, remoteVideoView, eglBase!!, true)
        signalingClient.connect()

        sendButton.setOnClickListener { sendMessage() }
        toggleVideoBtn.setOnClickListener { toggleVideo() }
        toggleAudioBtn.setOnClickListener { toggleAudio() }
        switchCameraBtn.setOnClickListener { webRTCManager.switchCamera(this) }

        log("App started. PeerId: $peerId")
    }

    private fun bindViews() {
        localVideoView = findViewById(R.id.localVideoView)
        remoteVideoView = findViewById(R.id.remoteVideoView)
        terminalOutput = findViewById(R.id.terminalOutput)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)
        peerListLayout = findViewById(R.id.peerList)
        connectionStatus = findViewById(R.id.connectionStatus)
        scrollView = findViewById(R.id.scrollView)
        toggleVideoBtn = findViewById(R.id.toggleVideoBtn)
        toggleAudioBtn = findViewById(R.id.toggleAudioBtn)
        switchCameraBtn = findViewById(R.id.switchCameraBtn)
    }

    private fun log(message: String) {
        Log.d(tag, message)
        mainHandler.post {
            terminalOutput.append(message + "\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
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
                    text = if (id == peerId) "[me]: $id" else "[online]: $id"
                    setTextColor(if (id == peerId) 0xFF888888.toInt() else 0xFF00FF00.toInt())
                    textSize = 14f
                    setPadding(8, 4, 8, 4)  // Compact vertical spacing
                    setOnClickListener { selectPeer(id) }
                }
                if (id == targetPeerId) {
                    tv.setTextColor(0xFFFFFF00.toInt())
                    tv.text = "[selected]: $id"
                }
                peerListLayout.addView(tv)
            }
        }
    }

    private fun selectPeer(id: String) {
        if (id == peerId) return
        webRTCManager.close()
        iceRestartAttempts = 0

        targetPeerId = id
        targetPeerId?.let { signalingClient.selectPeer(it) }
        isCaller = true

        connectionStatus.text = "Calling $id..."
        sendButton.isEnabled = false
        log("=======================")
        log("Calling $id...")

        startConnectionTimeout()
        webRTCManager.createConnection(true) {
            webRTCManager.createOffer { sdp ->
                signalingClient.sendOffer(sdp.description)
            }
        }
    }

    override fun onOfferReceived(from: String, sdp: String) {
        log("Incoming call from $from")
        targetPeerId = from
        targetPeerId?.let { signalingClient.selectPeer(it) }
        isCaller = false
        iceRestartAttempts = 0

        connectionStatus.text = "Incoming call..."
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
        log("Signaling connected")
    }

    override fun onError(error: Throwable) {
        log("Error: ${error.message}")
    }

    override fun onDataChannelOpen() {
        cancelConnectionTimeout()
        mainHandler.post {
            sendButton.isEnabled = true
            connectionStatus.text = "Connected"
            log("DataChannel open")
        }
    }

    override fun onDataChannelClosed() {
        mainHandler.post {
            sendButton.isEnabled = false
            connectionStatus.text = "Disconnected"
        }
    }

    override fun onMessageReceived(message: String) {
        log("< $message")
    }

    override fun onIceStateChanged(state: PeerConnection.IceConnectionState) {
        mainHandler.post {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    iceRestartAttempts = 0
                    log("ICE connected")
                }
                PeerConnection.IceConnectionState.FAILED -> log("ICE failed")
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    sendButton.isEnabled = false
                    connectionStatus.text = "Disconnected"
                }
                else -> {}
            }
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        signalingClient.sendIceCandidate(candidate)
    }

    override fun onError(error: String) {
        log("WebRTC Error: $error")
    }

    override fun onLocalVideoTrack(track: VideoTrack) {
        log("Local video ready")
    }

    override fun onRemoteVideoTrack(track: VideoTrack) {
        log("Remote video received")
    }

    override fun onRemoteAudioTrack(track: AudioTrack) {
        log("Remote audio received")
    }

    private fun sendMessage() {
        val msg = inputField.text.toString()
        if (msg.isEmpty()) return
        if (webRTCManager.sendMessage(msg)) {
            log("> $msg")
            inputField.text.clear()
        } else {
            log("Queued")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelConnectionTimeout()
        localVideoView.release()
        remoteVideoView.release()
        eglBase?.release()
        webRTCManager.close()
        signalingClient.destroy()
    }
}