// WebRTCManager.kt - FIXED VERSION
package com.example.webrtcchat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WebRTCManager(
    context: Context,
    private val listener: WebRTCListener
) {
    interface WebRTCListener {
        fun onDataChannelOpen()
        fun onDataChannelClosed()
        fun onMessageReceived(message: String)
        fun onIceStateChanged(state: PeerConnection.IceConnectionState)
        fun onIceCandidate(candidate: IceCandidate)
        fun onError(error: String)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = "WebRTCManager"

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private val iceQueue = LinkedList<IceCandidate>()
    private val messageQueue = LinkedList<String>()
    private val isDataChannelReady = AtomicBoolean(false)
    private var hasRemoteDescription = false
    private var isCaller = false

    // FIX: Track actual connection state more accurately
    private var isClosing = false

    init {
        initializeFactory(context)
    }

    private fun initializeFactory(context: Context) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    fun createConnection(caller: Boolean, onCreated: () -> Unit = {}) {
        executor.execute {
            isCaller = caller
            isClosing = false

            if (pc != null) {
                closeInternal()
            }

            // STUN + TURN servers for NAT traversal
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
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:turn.anyfirewall.com:443?transport=tcp")
                    .setUsername("webrtc")
                    .setPassword("webrtc")
                    .createIceServer()
            )

            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                // FIX: Enable continual gathering for network changes
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                // FIX: Aggressive ICE restart on failure
                iceBackupCandidatePairPingInterval = 2000
            }

            pc = factory?.createPeerConnection(config, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(tag, "Signaling: $state")
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(tag, "Local ICE: ${it.sdpMid}")
                        mainHandler.post { listener.onIceCandidate(it) }
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(tag, "ICE connection: $state")
                    state?.let {
                        mainHandler.post { listener.onIceStateChanged(it) }

                        // FIX: Auto-restart ICE on failure
                        if (state == PeerConnection.IceConnectionState.FAILED && !isClosing) {
                            Log.w(tag, "ICE failed, attempting restart...")
                            executor.execute {
                                pc?.restartIce()
                            }
                        }
                    }
                }

                override fun onDataChannel(dc: DataChannel?) {
                    dc?.let {
                        Log.d(tag, "onDataChannel: ${it.label()}")
                        executor.execute {
                            dataChannel = it
                            setupDataChannel()
                        }
                    }
                }

                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(tag, "ICE gathering: $state")
                }
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onRenegotiationNeeded() {
                    Log.d(tag, "Renegotiation needed")
                }
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            })

            if (isCaller) {
                val init = DataChannel.Init().apply {
                    ordered = true
                    maxRetransmits = 30  // FIX: Prevent infinite retransmits
                }
                dataChannel = pc?.createDataChannel("terminal", init)
                Log.d(tag, "DataChannel created: ${dataChannel?.label()}")
                setupDataChannel()
            }

            Log.d(tag, "PeerConnection ready, caller=$isCaller")
            mainHandler.post { onCreated() }
        }
    }

    private fun setupDataChannel() {
        dataChannel?.let { dc ->
            dc.registerObserver(object : DataChannel.Observer {
                override fun onStateChange() {
                    val state = dc.state()
                    Log.d(tag, "DataChannel state changed to: $state")

                    when (state) {
                        DataChannel.State.OPEN -> {
                            isDataChannelReady.set(true)
                            Log.d(tag, "DataChannel OPEN")
                            mainHandler.post {
                                listener.onDataChannelOpen()
                                flushMessageQueue()
                            }
                        }
                        DataChannel.State.CLOSING -> {
                            isDataChannelReady.set(false)
                            Log.d(tag, "DataChannel CLOSING")
                        }
                        DataChannel.State.CLOSED -> {
                            isDataChannelReady.set(false)
                            Log.d(tag, "DataChannel CLOSED")
                            mainHandler.post { listener.onDataChannelClosed() }
                        }
                        DataChannel.State.CONNECTING -> {
                            Log.d(tag, "DataChannel CONNECTING")
                        }
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer?) {
                    buffer?.let {
                        val bytes = ByteArray(it.data.remaining())
                        it.data.get(bytes)
                        val msg = String(bytes)
                        mainHandler.post { listener.onMessageReceived(msg) }
                    }
                }

                override fun onBufferedAmountChange(previousAmount: Long) {
                    // FIX: Monitor buffer backpressure
                    if (previousAmount > 1048576) { // 1MB threshold
                        Log.w(tag, "High buffer backpressure: $previousAmount")
                    }
                }
            })
        }
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit) {
        executor.execute {
            pc?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    desc?.let {
                        pc?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(tag, "Local offer set")
                                mainHandler.post { onSuccess(it) }
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(error: String?) {
                                mainHandler.post { listener.onError("Set local offer failed: $error") }
                            }
                        }, it)
                    }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    mainHandler.post { listener.onError("Create offer failed: $error") }
                }
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit) {
        executor.execute {
            pc?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    desc?.let {
                        pc?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(tag, "Local answer set")
                                mainHandler.post { onSuccess(it) }
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(error: String?) {
                                mainHandler.post { listener.onError("Set local answer failed: $error") }
                            }
                        }, it)
                    }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    mainHandler.post { listener.onError("Create answer failed: $error") }
                }
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }
    }

    fun setRemoteDescription(sdp: SessionDescription, onComplete: () -> Unit = {}) {
        executor.execute {
            pc?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(tag, "Remote ${sdp.type} set")
                    hasRemoteDescription = true
                    flushIceQueue()
                    mainHandler.post { onComplete() }
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(error: String?) {
                    mainHandler.post { listener.onError("Set remote ${sdp.type} failed: $error") }
                }
            }, sdp)
        }
    }

    fun addIceCandidate(candidate: IceCandidate) {
        executor.execute {
            if (hasRemoteDescription) {
                val success = pc?.addIceCandidate(candidate) ?: false
                if (!success) {
                    Log.w(tag, "Failed to add ICE candidate immediately, queuing")
                    iceQueue.add(candidate)
                }
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

    // FIX: Completely rewritten sendMessage with proper state checking
    fun sendMessage(message: String): Boolean {
        // Immediate check on caller thread
        if (!isDataChannelReady.get()) {
            synchronized(messageQueue) {
                messageQueue.add(message)
            }
            Log.w(tag, "DataChannel not ready, queued message")
            return false
        }

        // Execute actual send on executor thread for thread safety
        executor.execute {
            val dc = dataChannel
            if (dc == null) {
                Log.e(tag, "sendMessage: dataChannel is null")
                synchronized(messageQueue) {
                    messageQueue.add(message)
                }
                return@execute
            }

            // Double-check state on executor thread
            if (dc.state() != DataChannel.State.OPEN) {
                Log.w(tag, "sendMessage: DataChannel state is ${dc.state()}, not OPEN")
                synchronized(messageQueue) {
                    messageQueue.add(message)
                }

                // If closed unexpectedly, notify UI
                if (dc.state() == DataChannel.State.CLOSED && isDataChannelReady.get()) {
                    isDataChannelReady.set(false)
                    mainHandler.post { listener.onDataChannelClosed() }
                }
                return@execute
            }

            // Check buffer backpressure
            if (dc.bufferedAmount() > 1048576) { // 1MB
                Log.w(tag, "Buffer full, queuing message")
                synchronized(messageQueue) {
                    messageQueue.add(message)
                }
                return@execute
            }

            try {
                val buffer = ByteBuffer.wrap(message.toByteArray())
                val success = dc.send(DataChannel.Buffer(buffer, false))

                if (!success) {
                    Log.e(tag, "DataChannel.send() returned false")
                    synchronized(messageQueue) {
                        messageQueue.add(message)
                    }
                } else {
                    Log.d(tag, "Message sent successfully")
                }
            } catch (e: Exception) {
                Log.e(tag, "Exception sending message: ${e.message}")
                synchronized(messageQueue) {
                    messageQueue.add(message)
                }
            }
        }

        return true
    }

    private fun flushMessageQueue() {
        synchronized(messageQueue) {
            while (messageQueue.isNotEmpty()) {
                val msg = messageQueue.poll() ?: break
                sendMessage(msg)
            }
        }
    }

    fun close() {
        executor.execute {
            isClosing = true
            closeInternal()
        }
    }

    private fun closeInternal() {
        isDataChannelReady.set(false)
        dataChannel?.close()
        dataChannel = null
        pc?.close()
        pc = null
        hasRemoteDescription = false
        iceQueue.clear()
        synchronized(messageQueue) {
            messageQueue.clear()
        }
        isClosing = false
    }
}