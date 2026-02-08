// WebRTCManager.kt
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
    private var isDataChannelReady = false
    private var hasRemoteDescription = false
    private var isCaller = false

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

            if (pc != null) {
                close()
            }

            // STUN + TURN servers for NAT traversal
            val iceServers = listOf(
                // Google STUN
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),

                // Public TURN servers (free)
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

                // Alternative TURN
                PeerConnection.IceServer.builder("turn:turn.anyfirewall.com:443?transport=tcp")
                    .setUsername("webrtc")
                    .setPassword("webrtc")
                    .createIceServer()
            )

            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                // Use relay if direct connection fails
                iceTransportsType = PeerConnection.IceTransportsType.ALL
            }

            pc = factory?.createPeerConnection(config, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(tag, "Signaling: $state")
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(tag, "Local ICE: ${it.sdpMid} - ${it.sdp.take(50)}...")
                        mainHandler.post { listener.onIceCandidate(it) }
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(tag, "ICE connection: $state")
                    state?.let { mainHandler.post { listener.onIceStateChanged(it) } }
                }

                override fun onDataChannel(dc: DataChannel?) {
                    dc?.let {
                        Log.d(tag, "onDataChannel: ${it.label()}")
                        mainHandler.post {
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
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            })

            if (isCaller) {
                val init = DataChannel.Init().apply { ordered = true }
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
                    Log.d(tag, "DataChannel: $state")

                    when (state) {
                        DataChannel.State.OPEN -> {
                            isDataChannelReady = true
                            mainHandler.post {
                                listener.onDataChannelOpen()
                                flushMessageQueue()
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
                        val msg = String(bytes)
                        mainHandler.post { listener.onMessageReceived(msg) }
                    }
                }

                override fun onBufferedAmountChange(p0: Long) {}
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

    fun close() {
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
}