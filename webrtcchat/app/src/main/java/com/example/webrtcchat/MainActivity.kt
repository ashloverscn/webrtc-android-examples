package com.example.webrtcchat

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

class MainActivity : AppCompatActivity(),
    SignalingClient.SignalingListener,
    WebRTCManager.WebRTCListener {

    private lateinit var terminalOutput: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var peerListLayout: LinearLayout
    private lateinit var connectionStatus: TextView
    private lateinit var scrollView: ScrollView

    private lateinit var signalingClient: SignalingClient
    private lateinit var webRTCManager: WebRTCManager

    private val peerId = "peer_" + (100000..999999).random()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = "MainActivity"

    private var targetPeerId: String? = null
    private var isCaller = false
    private var connectionTimeoutHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        initialize()

        sendButton.setOnClickListener { sendMessage() }
        log("✅ App started. PeerId: $peerId")
    }

    private fun bindViews() {
        terminalOutput = findViewById(R.id.terminalOutput)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)
        peerListLayout = findViewById(R.id.peerList)
        connectionStatus = findViewById(R.id.connectionStatus)
        scrollView = findViewById(R.id.scrollView)
    }

    private fun initialize() {
        signalingClient = SignalingClient(this, peerId, this)
        webRTCManager = WebRTCManager(this, this)
        signalingClient.connect()
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
                log("⚠️ Connection timeout - ICE failed or peer unreachable")
                log("Try: 1) Check both peers online 2) Check firewall 3) Add TURN server")
                connectionStatus.text = "Timeout - click peer to retry"
            }
        }, 15000) // 15 second timeout
    }

    private fun cancelConnectionTimeout() {
        connectionTimeoutHandler?.removeCallbacksAndMessages(null)
    }

    // SignalingListener Implementation
    override fun onPeerListUpdated(peers: Map<String, PeerRegistry.PeerInfo>) {
        mainHandler.post {
            peerListLayout.removeAllViews()
            peers.forEach { (id, info) ->
                val tv = TextView(this).apply {
                    text = if (id == peerId) "[me]:$id" else "[online]:$id"
                    setTextColor(if (id == peerId) 0xFF888888.toInt() else 0xFF00FF00.toInt())
                    textSize = 14f
                    setPadding(8, 8, 8, 8)
                    setOnClickListener { selectPeer(id) }
                }
                if (id == targetPeerId) {
                    tv.setTextColor(0xFFFFFF00.toInt())
                    tv.text = "[selected]:$id"
                }
                peerListLayout.addView(tv)
            }
        }
    }

    private fun selectPeer(id: String) {
        if (id == peerId) return

        // Reset previous connection if any
        webRTCManager.close()

        targetPeerId = id
        targetPeerId?.let { signalingClient.selectPeer(it) }
        isCaller = true

        connectionStatus.text = "Connecting to $id..."
        sendButton.isEnabled = false
        log("═══════════════════════════════════════")
        log("Selected $id as target (caller)")
        startConnectionTimeout()

        webRTCManager.createConnection(true) {
            log("PeerConnection created as caller")
            webRTCManager.createOffer { sdp ->
                log("Offer created, sending to $targetPeerId")
                signalingClient.sendOffer(sdp.description)
            }
        }
    }

    override fun onOfferReceived(from: String, sdp: String) {
        log("═══════════════════════════════════════")
        log("📥 Offer received from $from")

        targetPeerId = from
        targetPeerId?.let { signalingClient.selectPeer(it) }
        isCaller = false

        connectionStatus.text = "Incoming call from $from..."
        startConnectionTimeout()

        webRTCManager.createConnection(false) {
            log("PeerConnection created as callee")
            val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
            webRTCManager.setRemoteDescription(sessionDesc) {
                log("✅ Remote offer set, creating answer...")
                webRTCManager.createAnswer { answer ->
                    log("Answer created, sending to $from")
                    signalingClient.sendAnswer(answer.description)
                }
            }
        }
    }

    override fun onAnswerReceived(from: String, sdp: String) {
        log("📥 Answer received from $from")

        val sessionDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        webRTCManager.setRemoteDescription(sessionDesc) {
            log("✅ Remote answer set, waiting for ICE...")
            // DataChannel should open after ICE connects
        }
    }

    override fun onIceCandidateReceived(from: String, candidate: SignalingClient.IceCandidateData) {
        log("📥 ICE from $from: ${candidate.sdpMid}")
        webRTCManager.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        )
    }

    override fun onConnected() {
        log("✅ Connected to MQTT signaling server")
    }

    override fun onError(error: Throwable) {
        log("❌ Signaling error: ${error.message}")
        connectionStatus.text = "Error: ${error.message}"
    }

    // WebRTCListener Implementation
    override fun onDataChannelOpen() {
        cancelConnectionTimeout()
        mainHandler.post {
            sendButton.isEnabled = true
            connectionStatus.text = "Connected to $targetPeerId ✅"
            log("✅✅✅ DataChannel OPEN - Ready to chat!")
        }
    }

    override fun onDataChannelClosed() {
        mainHandler.post {
            sendButton.isEnabled = false
            connectionStatus.text = "Disconnected"
            log("❌ DataChannel closed")
        }
    }

    override fun onMessageReceived(message: String) {
        log("< $message")
    }

    override fun onIceStateChanged(state: PeerConnection.IceConnectionState) {
        mainHandler.post {
            when (state) {
                PeerConnection.IceConnectionState.CHECKING -> {
                    connectionStatus.text = "ICE checking..."
                    log("ICE: Checking...")
                }
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    log("ICE: Connected!")
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    log("ICE: Failed - connection may fail")
                    connectionStatus.text = "ICE Failed"
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    log("ICE: Disconnected")
                    connectionStatus.text = "Disconnected"
                    sendButton.isEnabled = false
                }
                else -> log("ICE: $state")
            }
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        log("📤 Sending ICE: ${candidate.sdpMid}")
        signalingClient.sendIceCandidate(candidate)
    }

    override fun onError(error: String) {
        log("❌ WebRTC Error: $error")
        connectionStatus.text = "Error: $error"
    }

    private fun sendMessage() {
        val msg = inputField.text.toString()
        if (msg.isEmpty()) return

        if (webRTCManager.sendMessage(msg)) {
            log("> $msg")
            inputField.text.clear()
        } else {
            log("⏳ Message queued (DataChannel not ready)")
            log("State: Check ICE connection status above")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelConnectionTimeout()
        log("Destroying...")
        webRTCManager.close()
        signalingClient.destroy()
    }
}