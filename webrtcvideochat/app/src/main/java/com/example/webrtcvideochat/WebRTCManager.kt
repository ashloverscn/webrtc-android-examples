package com.example.webrtcvideochat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ==================== DATA CLASSES ====================

data class PeerStatus(
    val id: String,
    val lastSeen: Long = System.currentTimeMillis(),
    var isOnline: Boolean = true
)

data class IceCandidateData(val sdpMid: String, val sdpMLineIndex: Int, val candidate: String)
data class MqttConfig(
    val brokerUrl: String,
    val username: String,
    val password: String,
    val topic: String = "webrtc/signaling"
)

// ==================== PEER REGISTRY ====================

class PeerRegistry {
    private val peers = mutableMapOf<String, PeerStatus>()
    private val listeners = mutableListOf<(Map<String, PeerStatus>) -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val OFFLINE_THRESHOLD = 3500L   // Mark offline after 3.5 seconds (3.5x presence interval)
        const val EXPIRE_THRESHOLD = 12000L   // Remove after 12 seconds
    }

    fun addListener(listener: (Map<String, PeerStatus>) -> Unit) {
        listeners.add(listener)
    }

    fun update(peerId: String) {
        val existing = peers[peerId]
        val wasOffline = existing?.isOnline == false

        peers[peerId] = PeerStatus(
            id = peerId,
            lastSeen = System.currentTimeMillis(),
            isOnline = true
        )

        // Only notify if new peer or was explicitly offline (prevents blinking)
        if (existing == null || wasOffline) {
            notifyListeners()
        }
    }

    fun cleanup() {
        val now = System.currentTimeMillis()
        var changed = false

        val iterator = peers.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val elapsed = now - entry.value.lastSeen

            when {
                elapsed > EXPIRE_THRESHOLD -> {
                    iterator.remove()
                    changed = true
                    Log.d("PeerRegistry", "Expired and removed: ${entry.key}")
                }
                elapsed > OFFLINE_THRESHOLD && entry.value.isOnline -> {
                    entry.value.isOnline = false
                    changed = true
                    Log.d("PeerRegistry", "Marked offline: ${entry.key}")
                }
            }
        }

        if (changed) notifyListeners()
    }

    private fun notifyListeners() {
        val currentPeers = peers.toMap()
        handler.post { listeners.forEach { it(currentPeers) } }
    }

    fun startCleanup() {
        // Run every 1000ms for stable presence detection
        handler.postDelayed(object : Runnable {
            override fun run() {
                cleanup()
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }
}

// ==================== WEBRTC MANAGER ====================

class WebRTCManager(
    private val context: Context,
    private val listener: WebRTCListener
) {
    interface WebRTCListener {
        fun onPeerListUpdated(peers: Map<String, PeerStatus>, myPeerId: String)
        fun onOfferReceived(from: String, sdp: String)
        fun onAnswerReceived(from: String, sdp: String)
        fun onIceCandidateReceived(from: String, candidate: IceCandidateData)
        fun onConnected()
        fun onConnectionLost(error: Throwable?)
        fun onDataChannelOpen()
        fun onDataChannelClosed()
        fun onMessageReceived(message: String)
        fun onIceStateChanged(state: PeerConnection.IceConnectionState)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onRemoteAudioTrack(track: AudioTrack)
    }

    // MQTT
    private lateinit var mqttConfig: MqttConfig
    private var mqttClient: MqttClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectHandler = Handler(Looper.getMainLooper())
    private var presenceHandler = Handler(Looper.getMainLooper())
    private var isMqttConnected = false
    private val reconnectDelay = 5000L

    // Peer Registry
    private val registry = PeerRegistry()
    private var targetPeer: String? = null
    private val peerId = "peer_" + generateRandomAlphanumeric(6)

    // WebRTC
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tag = "WebRTCManager"

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val iceQueue = LinkedList<IceCandidate>()
    private val messageQueue = LinkedList<String>()
    private var isDataChannelReady = false
    private var hasRemoteDescription = false
    private var isCaller = false

    // ==================== INITIALIZATION ====================

    fun getPeerId(): String = peerId

    private fun generateRandomAlphanumeric(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }

    fun initialize(remoteView: SurfaceViewRenderer, egl: EglBase, config: MqttConfig) {
        this.remoteRenderer = remoteView
        this.eglBase = egl
        this.mqttConfig = config

        registry.addListener { peers ->
            mainHandler.post { listener.onPeerListUpdated(peers, peerId) }
        }
        registry.startCleanup()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        startLocalMedia()
    }

    private fun startLocalMedia() {
        executor.execute {
            try {
                videoCapturer = createCameraCapturer()
                videoSource = factory!!.createVideoSource(false)
                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
                videoCapturer!!.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
                videoCapturer!!.startCapture(1280, 720, 30)

                localVideoTrack = factory!!.createVideoTrack("VIDEO_LOCAL", videoSource)
                localVideoTrack!!.setEnabled(true)

                audioSource = factory!!.createAudioSource(MediaConstraints())
                localAudioTrack = factory!!.createAudioTrack("AUDIO_LOCAL", audioSource)
                localAudioTrack!!.setEnabled(true)

                Log.d(tag, "Local media started")
            } catch (e: Exception) {
                Log.e(tag, "Error starting local media", e)
            }
        }
    }

    // ==================== MQTT METHODS ====================

    fun connect() {
        Thread {
            try {
                val clientId = "android_${peerId}_${System.currentTimeMillis()}"
                mqttClient = MqttClient(mqttConfig.brokerUrl, clientId, MemoryPersistence())

                val options = MqttConnectOptions().apply {
                    userName = mqttConfig.username
                    password = mqttConfig.password.toCharArray()
                    connectionTimeout = 30
                    keepAliveInterval = 60
                    isAutomaticReconnect = false
                    isCleanSession = true
                    socketFactory = createSocketFactory()
                }

                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.e(tag, "MQTT connection lost", cause)
                        isMqttConnected = false
                        mainHandler.post { listener.onConnectionLost(cause) }
                        stopPresenceLoop()
                        scheduleReconnect()
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        message?.let {
                            try {
                                val json = JSONObject(String(it.payload))
                                mainHandler.post { handleMqttMessage(json) }
                            } catch (e: Exception) {
                                Log.e(tag, "Failed to parse message", e)
                            }
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                mqttClient?.connect(options)
                mqttClient?.subscribe(mqttConfig.topic)
                isMqttConnected = true

                startPresenceLoop()

                mainHandler.post { listener.onConnected() }
                Log.d(tag, "Connected to MQTT broker")
            } catch (e: Exception) {
                Log.e(tag, "MQTT connection failed", e)
                mainHandler.post { listener.onConnectionLost(e) }
                scheduleReconnect()
            }
        }.start()
    }

    private fun startPresenceLoop() {
        presenceHandler.removeCallbacksAndMessages(null)
        presenceHandler.post(object : Runnable {
            override fun run() {
                if (isMqttConnected) {
                    val presence = JSONObject().apply {
                        put("type", "presence")
                        put("from", peerId)
                    }
                    publish(presence)
                    Log.d(tag, "Sent presence")
                }
                presenceHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun stopPresenceLoop() {
        presenceHandler.removeCallbacksAndMessages(null)
    }

    private fun handleMqttMessage(json: JSONObject) {
        val type = json.optString("type")
        val from = json.optString("from")
        val to = json.optString("to")

        when (type) {
            "presence" -> {
                registry.update(from)
                return
            }
        }

        if (to != peerId) return

        when (type) {
            "offer" -> {
                targetPeer = from
                val data = json.getJSONObject("data")
                listener.onOfferReceived(from, data.getString("sdp"))
            }
            "answer" -> {
                val data = json.getJSONObject("data")
                listener.onAnswerReceived(from, data.getString("sdp"))
            }
            "ice" -> {
                val data = json.getJSONObject("data")
                listener.onIceCandidateReceived(from, IceCandidateData(
                    data.getString("sdpMid"),
                    data.getInt("sdpMLineIndex"),
                    data.getString("candidate")
                ))
            }
        }
    }

    private fun publish(message: JSONObject) {
        Thread {
            try {
                if (isMqttConnected) {
                    mqttClient?.publish(mqttConfig.topic, MqttMessage(message.toString().toByteArray()))
                }
            } catch (e: Exception) {
                Log.e(tag, "Publish failed", e)
            }
        }.start()
    }

    private fun scheduleReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            Log.d(tag, "Attempting to reconnect...")
            connect()
        }, reconnectDelay)
    }

    private fun createSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate>? = null
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            }
        )
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext.socketFactory
    }

    // ==================== SIGNALING METHODS ====================

    fun selectPeer(peerId: String) {
        targetPeer = peerId
    }

    fun sendOffer(sdp: String) {
        targetPeer?.let {
            val data = JSONObject().apply {
                put("type", "offer")
                put("sdp", sdp)
            }
            val msg = JSONObject().apply {
                put("type", "offer")
                put("from", peerId)
                put("to", it)
                put("data", data)
            }
            publish(msg)
        }
    }

    fun sendAnswer(sdp: String) {
        targetPeer?.let {
            val data = JSONObject().apply {
                put("type", "answer")
                put("sdp", sdp)
            }
            val msg = JSONObject().apply {
                put("type", "answer")
                put("from", peerId)
                put("to", it)
                put("data", data)
            }
            publish(msg)
        }
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        targetPeer?.let {
            val data = JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
            val msg = JSONObject().apply {
                put("type", "ice")
                put("from", peerId)
                put("to", it)
                put("data", data)
            }
            publish(msg)
        }
    }

    // ==================== WEBRTC METHODS ====================

    fun createConnection(caller: Boolean, onCreated: () -> Unit = {}) {
        executor.execute {
            isCaller = caller
            if (pc != null) closeConnection()

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer()
            )

            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = PeerConnection.IceTransportsType.ALL
            }

            pc = factory?.createPeerConnection(config, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { mainHandler.post { sendIceCandidate(it) } }
                }
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    state?.let { mainHandler.post { listener.onIceStateChanged(it) } }
                }
                override fun onDataChannel(dc: DataChannel?) {
                    dc?.let {
                        mainHandler.post {
                            dataChannel = it
                            setupDataChannel()
                        }
                    }
                }
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    receiver?.track()?.let { track ->
                        when (track) {
                            is VideoTrack -> {
                                track.setEnabled(true)
                                mainHandler.post {
                                    remoteRenderer?.let { track.addSink(it) }
                                    listener.onRemoteVideoTrack(track)
                                }
                            }
                            is AudioTrack -> {
                                track.setEnabled(true)
                                mainHandler.post { listener.onRemoteAudioTrack(track) }
                            }
                        }
                    }
                }
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onRenegotiationNeeded() {}
            })

            localVideoTrack?.let { pc?.addTrack(it, listOf("stream")) }
            localAudioTrack?.let { pc?.addTrack(it, listOf("stream")) }

            if (isCaller) {
                val init = DataChannel.Init().apply { ordered = true }
                dataChannel = pc?.createDataChannel("terminal", init)
                mainHandler.post { setupDataChannel() }
            }

            mainHandler.post { onCreated() }
        }
    }

    private fun setupDataChannel() {
        dataChannel?.let { dc ->
            dc.registerObserver(object : DataChannel.Observer {
                override fun onStateChange() {
                    when (dc.state()) {
                        DataChannel.State.OPEN -> {
                            if (!isDataChannelReady) {
                                isDataChannelReady = true
                                mainHandler.post {
                                    listener.onDataChannelOpen()
                                    flushMessageQueue()
                                }
                            }
                        }
                        DataChannel.State.CLOSED, DataChannel.State.CLOSING -> {
                            isDataChannelReady = false
                            mainHandler.post { listener.onDataChannelClosed() }
                        }
                        else -> {}
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer?) {
                    buffer?.let {
                        val bytes = ByteArray(it.data.remaining())
                        it.data.get(bytes)
                        mainHandler.post { listener.onMessageReceived(String(bytes)) }
                    }
                }
                override fun onBufferedAmountChange(p0: Long) {}
            })

            if (dc.state() == DataChannel.State.OPEN && !isDataChannelReady) {
                isDataChannelReady = true
                mainHandler.post {
                    listener.onDataChannelOpen()
                    flushMessageQueue()
                }
            }
        }
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit) {
        executor.execute {
            pc?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    desc?.let {
                        pc?.setLocalDescription(SimpleSdpObserver(), it)
                        mainHandler.post { onSuccess(it) }
                    }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit) {
        executor.execute {
            pc?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    desc?.let {
                        pc?.setLocalDescription(SimpleSdpObserver(), it)
                        mainHandler.post { onSuccess(it) }
                    }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }
    }

    fun setRemoteDescription(sdp: SessionDescription, onComplete: () -> Unit = {}) {
        executor.execute {
            pc?.setRemoteDescription(SimpleSdpObserver(), sdp)
            hasRemoteDescription = true
            flushIceQueue()
            mainHandler.post { onComplete() }
        }
    }

    fun addIceCandidate(candidate: IceCandidate) {
        executor.execute {
            if (hasRemoteDescription) {
                pc?.addIceCandidate(candidate)
            } else {
                iceQueue.add(candidate)
            }
        }
    }

    private fun flushIceQueue() {
        while (iceQueue.isNotEmpty()) {
            pc?.addIceCandidate(iceQueue.poll())
        }
    }

    fun sendMessage(message: String): Boolean {
        return if (isDataChannelReady && dataChannel?.state() == DataChannel.State.OPEN) {
            dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray()), false))
            true
        } else {
            messageQueue.add(message)
            false
        }
    }

    private fun flushMessageQueue() {
        while (messageQueue.isNotEmpty()) {
            sendMessage(messageQueue.poll())
        }
    }

    fun toggleVideo(enable: Boolean) {
        localVideoTrack?.setEnabled(enable)
    }

    fun toggleAudio(enable: Boolean) {
        localAudioTrack?.setEnabled(enable)
    }

    fun switchCamera() {
        try {
            videoCapturer?.switchCamera(null)
        } catch (e: Exception) {
            Log.e(tag, "Failed to switch camera", e)
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer {
        val enumerator = Camera2Enumerator(context)
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        throw RuntimeException("No front camera found")
    }

    private fun closeConnection() {
        executor.execute {
            isDataChannelReady = false
            dataChannel?.close()
            dataChannel = null
            pc?.close()
            pc = null
            hasRemoteDescription = false
            iceQueue.clear()
            messageQueue.clear()
        }
    }

    fun close() = closeConnection()

    fun cleanup() {
        closeConnection()
        reconnectHandler.removeCallbacksAndMessages(null)
        presenceHandler.removeCallbacksAndMessages(null)
        executor.execute {
            localVideoTrack?.dispose()
            localVideoTrack = null
            localAudioTrack?.dispose()
            localAudioTrack = null
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            videoSource?.dispose()
            videoSource = null
            audioSource?.dispose()
            audioSource = null
            factory?.dispose()
            factory = null
            eglBase = null
        }
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) {
            Log.e(tag, "MQTT disconnect error", e)
        }
    }
}

class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
