// WebRTCManager.kt - FULL AUDIO/VIDEO + DATA CHANNEL
package com.example.webrtcchat

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WebRTCManager(
    private val context: Context,
    private val listener: WebRTCListener
) {
    interface WebRTCListener {
        fun onLocalVideoTrack(track: VideoTrack)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onRemoteAudioTrack(track: AudioTrack)
        fun onDataChannelOpen()
        fun onDataChannelClosed()
        fun onMessageReceived(message: String)
        fun onIceCandidate(candidate: IceCandidate)
        fun onIceStateChanged(state: PeerConnection.IceConnectionState)
        fun onError(error: String)
    }

    private val tag = "WebRTCManager"
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private var videoCapturer: VideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private val iceQueue = LinkedList<IceCandidate>()
    private val messageQueue = LinkedList<String>()
    private val isDataChannelReady = AtomicBoolean(false)
    private var hasRemoteDescription = false
    private var isCaller = false
    private var isClosing = false

    fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            EglBase.create().eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun initLocalMedia(isFrontCamera: Boolean = true) {
        executor.execute {
            // Video
            videoCapturer = createVideoCapturer(isFrontCamera)
            localVideoSource = factory?.createVideoSource(videoCapturer!!.isScreencast)
            videoCapturer?.initialize(
                SurfaceTextureHelper.create("CaptureThread", EglBase.create().eglBaseContext),
                context,
                localVideoSource!!.capturerObserver
            )
            videoCapturer?.startCapture(1280, 720, 30)

            localVideoTrack = factory?.createVideoTrack("local_video", localVideoSource)
            localVideoTrack?.setEnabled(true)
            listener.onLocalVideoTrack(localVideoTrack!!)

            // Audio
            localAudioSource = factory?.createAudioSource(MediaConstraints())
            localAudioTrack = factory?.createAudioTrack("local_audio", localAudioSource)
            localAudioTrack?.setEnabled(true)
        }
    }

    private fun createVideoCapturer(isFront: Boolean): VideoCapturer {
        val enumerator = Camera2Enumerator(context)
        val devices = enumerator.deviceNames
        for (device in devices) {
            if (isFront && enumerator.isFrontFacing(device)) {
                return enumerator.createCapturer(device, null)!!
            } else if (!isFront && enumerator.isBackFacing(device)) {
                return enumerator.createCapturer(device, null)!!
            }
        }
        throw IllegalStateException("No camera found")
    }

    fun createConnection(caller: Boolean, onCreated: () -> Unit = {}) {
        executor.execute {
            isCaller = caller
            isClosing = false
            if (pc != null) closeInternal()

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
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
                    candidate?.let {
                        listener.onIceCandidate(it)
                    }
                }
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    state?.let { listener.onIceStateChanged(it) }
                    if (state == PeerConnection.IceConnectionState.FAILED && !isClosing) {
                        pc?.restartIce()
                    }
                }
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel?) {
                    dc?.let {
                        dataChannel = it
                        setupDataChannel()
                    }
                }
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    receiver?.track()?.let { track ->
                        when (track) {
                            is VideoTrack -> listener.onRemoteVideoTrack(track)
                            is AudioTrack -> listener.onRemoteAudioTrack(track)
                        }
                    }
                }
            })

            // Add local tracks
            localAudioTrack?.let { pc?.addTrack(it, listOf("stream")) }
            localVideoTrack?.let { pc?.addTrack(it, listOf("stream")) }

            // Create DataChannel if caller
            if (isCaller) {
                val init = DataChannel.Init().apply { ordered = true; maxRetransmits = 30 }
                dataChannel = pc?.createDataChannel("terminal", init)
                setupDataChannel()
            }

            onCreated()
        }
    }

    private fun setupDataChannel() {
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onStateChange() {
                when (dataChannel?.state()) {
                    DataChannel.State.OPEN -> {
                        isDataChannelReady.set(true)
                        listener.onDataChannelOpen()
                        flushMessageQueue()
                    }
                    DataChannel.State.CLOSED -> {
                        isDataChannelReady.set(false)
                        listener.onDataChannelClosed()
                    }
                    else -> {}
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer?.let {
                    val bytes = ByteArray(it.data.remaining())
                    it.data.get(bytes)
                    listener.onMessageReceived(String(bytes))
                }
            }

            override fun onBufferedAmountChange(previousAmount: Long) {}
        })
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit) {
        executor.execute {
            pc?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    desc?.let {
                        pc?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() { onSuccess(it) }
                            override fun onSetFailure(error: String?) { listener.onError(error ?: "") }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, it)
                    }
                }
                override fun onCreateFailure(error: String?) { listener.onError(error ?: "") }
                override fun onSetSuccess() {}
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
                            override fun onSetSuccess() { onSuccess(it) }
                            override fun onSetFailure(error: String?) { listener.onError(error ?: "") }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, it)
                    }
                }
                override fun onCreateFailure(error: String?) { listener.onError(error ?: "") }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        executor.execute {
            pc?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    hasRemoteDescription = true
                    flushIceQueue()
                }

                override fun onSetFailure(error: String?) { listener.onError(error ?: "") }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, sdp)
        }
    }

    fun addIceCandidate(candidate: IceCandidate) {
        executor.execute {
            if (hasRemoteDescription) pc?.addIceCandidate(candidate)
            else iceQueue.add(candidate)
        }
    }

    private fun flushIceQueue() {
        while (iceQueue.isNotEmpty()) pc?.addIceCandidate(iceQueue.poll())
    }

    fun sendMessage(message: String) {
        if (!isDataChannelReady.get()) {
            synchronized(messageQueue) { messageQueue.add(message) }
            return
        }
        executor.execute {
            dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray()), false))
        }
    }

    private fun flushMessageQueue() {
        synchronized(messageQueue) {
            while (messageQueue.isNotEmpty()) sendMessage(messageQueue.poll()!!)
        }
    }

    fun close() {
        executor.execute { closeInternal() }
    }

    private fun closeInternal() {
        isDataChannelReady.set(false)
        dataChannel?.close()
        dataChannel = null

        localVideoTrack?.dispose(); localVideoTrack = null
        localAudioTrack?.dispose(); localAudioTrack = null
        localVideoSource?.dispose(); localVideoSource = null
        localAudioSource?.dispose(); localAudioSource = null
        videoCapturer?.dispose(); videoCapturer = null

        pc?.close()
        pc = null

        iceQueue.clear()
        messageQueue.clear()
        hasRemoteDescription = false
    }
}