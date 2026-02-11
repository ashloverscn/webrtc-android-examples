package com.example.webrtcvideochat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoWebRTCManager(
    private val context: Context,
    private val listener: WebRTCListener
) {
    interface WebRTCListener {
        fun onDataChannelOpen()
        fun onDataChannelClosed()
        fun onMessageReceived(message: String)
        fun onIceStateChanged(state: PeerConnection.IceConnectionState)
        fun onIceCandidate(candidate: IceCandidate)
        fun onError(error: String)
        fun onLocalVideoTrack(track: VideoTrack)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onRemoteAudioTrack(track: AudioTrack)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = "VideoWebRTCManager"

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

    fun initializeVideo(
        context: Context,
        localView: SurfaceViewRenderer?,
        remoteView: SurfaceViewRenderer,
        egl: EglBase,
        enableVideo: Boolean
    ) {
        eglBase = egl
        remoteRenderer = remoteView

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

        if (enableVideo) {
            startLocalMedia()
        }
    }

    private fun startLocalMedia() {
        try {
            videoCapturer = createCameraCapturer()
            videoSource = factory!!.createVideoSource(false)
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
            videoCapturer!!.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
            videoCapturer!!.startCapture(1280, 720, 30)

            localVideoTrack = factory!!.createVideoTrack("VIDEO_LOCAL", videoSource)
            localVideoTrack!!.setEnabled(true)
            listener.onLocalVideoTrack(localVideoTrack!!)

            audioSource = factory!!.createAudioSource(MediaConstraints())
            localAudioTrack = factory!!.createAudioTrack("AUDIO_LOCAL", audioSource)
            localAudioTrack!!.setEnabled(true)

            Log.d(tag, "Local media started")
        } catch (e: Exception) {
            Log.e(tag, "Error starting local media", e)
            listener.onError("Failed to start camera: ${e.message}")
        }
    }

    fun createConnection(caller: Boolean, onCreated: () -> Unit = {}) {
        executor.execute {
            isCaller = caller

            if (pc != null) {
                close()
            }

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

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    receiver?.track()?.let { track ->
                        when (track) {
                            is VideoTrack -> {
                                Log.d(tag, "Remote video track received")
                                track.setEnabled(true)
                                remoteRenderer?.let { track.addSink(it) }
                                mainHandler.post { listener.onRemoteVideoTrack(track) }
                            }
                            is AudioTrack -> {
                                Log.d(tag, "Remote audio track received")
                                track.setEnabled(true)
                                mainHandler.post { listener.onRemoteAudioTrack(track) }
                            }
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
            })

            localVideoTrack?.let { pc?.addTrack(it, listOf("stream")) }
            localAudioTrack?.let { pc?.addTrack(it, listOf("stream")) }

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

    fun cleanup() {
        close()
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
    }
}