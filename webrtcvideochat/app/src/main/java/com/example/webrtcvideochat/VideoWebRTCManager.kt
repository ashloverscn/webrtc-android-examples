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
import java.util.concurrent.atomic.AtomicBoolean

class VideoWebRTCManager(
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

    private var videoCapturer: VideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val iceQueue = Collections.synchronizedList(LinkedList<IceCandidate>())
    private val messageQueue = LinkedList<String>()
    private val isDataChannelReady = AtomicBoolean(false)
    @Volatile
    private var hasRemoteDescription = false
    private var isCaller = false
    private var isClosing = false

    private var localVideoView: SurfaceViewRenderer? = null
    private var remoteVideoView: SurfaceViewRenderer? = null
    private var eglBase: EglBase? = null
    private var context: Context? = null

    init {
        this.context = context
        initializeFactory(context)
    }

    private fun initializeFactory(context: Context) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()
    }

    fun initializeVideo(
        context: Context,
        localView: SurfaceViewRenderer,
        remoteView: SurfaceViewRenderer,
        eglBase: EglBase,
        isFrontCamera: Boolean = true
    ) {
        executor.execute {
            this.eglBase = eglBase
            this.context = context
            localVideoView = localView
            remoteVideoView = remoteView

            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

            videoCapturer = createVideoCapturer(context, isFrontCamera)

            localVideoSource = factory?.createVideoSource(videoCapturer!!.isScreencast)
            videoCapturer?.initialize(surfaceTextureHelper, context, localVideoSource!!.capturerObserver)
            videoCapturer?.startCapture(1280, 720, 30)

            localVideoTrack = factory?.createVideoTrack("video_local", localVideoSource)
            localVideoTrack?.setEnabled(true)
            localVideoTrack?.addSink(localView)

            localAudioSource = factory?.createAudioSource(MediaConstraints())
            localAudioTrack = factory?.createAudioTrack("audio_local", localAudioSource)
            localAudioTrack?.setEnabled(true)

            mainHandler.post {
                localVideoTrack?.let { listener.onLocalVideoTrack(it) }
            }
        }
    }

    private fun createVideoCapturer(context: Context, isFrontCamera: Boolean): VideoCapturer {
        val cameraEnumerator = Camera2Enumerator(context)
        val deviceNames = cameraEnumerator.deviceNames

        for (deviceName in deviceNames) {
            if (isFrontCamera && cameraEnumerator.isFrontFacing(deviceName)) {
                return cameraEnumerator.createCapturer(deviceName, null)!!
            } else if (!isFrontCamera && cameraEnumerator.isBackFacing(deviceName)) {
                return cameraEnumerator.createCapturer(deviceName, null)!!
            }
        }
        throw IllegalStateException("No camera found")
    }

    fun createConnection(caller: Boolean, onCreated: () -> Unit = {}) {
        executor.execute {
            isCaller = caller
            isClosing = false
            hasRemoteDescription = false

            if (pc != null) {
                closeInternal()
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
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer()
            )

            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
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
                        if (state == PeerConnection.IceConnectionState.FAILED && !isClosing) {
                            Log.w(tag, "ICE failed, attempting restart...")
                            executor.execute { pc?.restartIce() }
                        }
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    transceiver?.receiver?.track()?.let { track ->
                        when (track) {
                            is VideoTrack -> {
                                Log.d(tag, "Remote video track received")
                                track.setEnabled(true)
                                mainHandler.post {
                                    remoteVideoView?.let { view ->
                                        track.addSink(view)
                                    }
                                    listener.onRemoteVideoTrack(track)
                                }
                            }
                            is AudioTrack -> {
                                Log.d(tag, "Remote audio track received")
                                track.setEnabled(true)
                                mainHandler.post { listener.onRemoteAudioTrack(track) }
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
                override fun onAddStream(stream: MediaStream?) {
                    Log.d(tag, "onAddStream: ${stream?.id}")
                }
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onRenegotiationNeeded() {
                    Log.d(tag, "Renegotiation needed")
                }
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            })

            // CRITICAL FIX: Add local tracks to PeerConnection AFTER it's created
            addLocalTracksToPeerConnection()

            if (isCaller) {
                val init = DataChannel.Init().apply {
                    ordered = true
                    maxRetransmits = 30
                }
                dataChannel = pc?.createDataChannel("terminal", init)
                Log.d(tag, "DataChannel created: ${dataChannel?.label()}")
                setupDataChannel()
            }

            Log.d(tag, "PeerConnection ready, caller=$isCaller")
            mainHandler.post { onCreated() }
        }
    }

    // CRITICAL FIX: Separate method to add tracks with proper transceiver configuration
    private fun addLocalTracksToPeerConnection() {
        Log.d(tag, "Adding local tracks to PeerConnection")

        localAudioTrack?.let { track ->
            Log.d(tag, "Adding local audio track")
            val audioSender = pc?.addTrack(track, listOf("stream_id"))
            if (audioSender == null) {
                Log.e(tag, "Failed to add audio track")
            } else {
                Log.d(tag, "Audio track added successfully")
            }
        } ?: Log.w(tag, "Local audio track is null")

        localVideoTrack?.let { track ->
            Log.d(tag, "Adding local video track")
            val videoSender = pc?.addTrack(track, listOf("stream_id"))
            if (videoSender == null) {
                Log.e(tag, "Failed to add video track")
            } else {
                Log.d(tag, "Video track added successfully")
            }
        } ?: Log.w(tag, "Local video track is null")
    }

    private fun setupDataChannel() {
        dataChannel?.let { dc ->
            dc.registerObserver(object : DataChannel.Observer {
                override fun onStateChange() {
                    val state = dc.state()
                    Log.d(tag, "DataChannel state: $state")

                    when (state) {
                        DataChannel.State.OPEN -> {
                            isDataChannelReady.set(true)
                            mainHandler.post {
                                listener.onDataChannelOpen()
                                flushMessageQueue()
                            }
                        }
                        DataChannel.State.CLOSING -> isDataChannelReady.set(false)
                        DataChannel.State.CLOSED -> {
                            isDataChannelReady.set(false)
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

                override fun onBufferedAmountChange(previousAmount: Long) {
                    if (previousAmount > 1048576) {
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
            Log.d(tag, "Setting remote ${sdp.type}...")
            pc?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(tag, "Remote ${sdp.type} set successfully")

                    synchronized(this@VideoWebRTCManager) {
                        hasRemoteDescription = true
                    }
                    flushIceQueue()

                    mainHandler.post { onComplete() }
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(error: String?) {
                    Log.e(tag, "Set remote ${sdp.type} failed: $error")
                    mainHandler.post { listener.onError("Set remote ${sdp.type} failed: $error") }
                }
            }, sdp)
        }
    }

    fun addIceCandidate(candidate: IceCandidate) {
        executor.execute {
            val shouldQueue = synchronized(this) {
                if (hasRemoteDescription) {
                    false
                } else {
                    Log.d(tag, "Queuing ICE candidate (remote desc not ready)")
                    true
                }
            }

            if (shouldQueue) {
                iceQueue.add(candidate)
            } else {
                val success = pc?.addIceCandidate(candidate) ?: false
                if (!success) {
                    Log.w(tag, "Failed to add ICE candidate immediately, re-queuing")
                    iceQueue.add(candidate)
                } else {
                    Log.d(tag, "ICE candidate added successfully")
                }
            }
        }
    }

    private fun flushIceQueue() {
        Log.d(tag, "Flushing ${iceQueue.size} queued ICE candidates")
        while (iceQueue.isNotEmpty()) {
            val candidate = iceQueue.removeFirstOrNull() ?: break
            val success = pc?.addIceCandidate(candidate) ?: false
            if (!success) {
                Log.w(tag, "Failed to flush ICE candidate: ${candidate.sdpMid}")
                iceQueue.add(candidate)
                break
            }
        }
        Log.d(tag, "ICE queue flushed, remaining: ${iceQueue.size}")
    }

    fun sendMessage(message: String): Boolean {
        if (!isDataChannelReady.get()) {
            synchronized(messageQueue) { messageQueue.add(message) }
            return false
        }

        executor.execute {
            val dc = dataChannel
            if (dc == null || dc.state() != DataChannel.State.OPEN) {
                synchronized(messageQueue) { messageQueue.add(message) }
                return@execute
            }

            if (dc.bufferedAmount() > 1048576) {
                synchronized(messageQueue) { messageQueue.add(message) }
                return@execute
            }

            try {
                val buffer = ByteBuffer.wrap(message.toByteArray())
                dc.send(DataChannel.Buffer(buffer, false))
            } catch (e: Exception) {
                Log.e(tag, "Send failed: ${e.message}")
                synchronized(messageQueue) { messageQueue.add(message) }
            }
        }
        return true
    }

    private fun flushMessageQueue() {
        synchronized(messageQueue) {
            while (messageQueue.isNotEmpty()) {
                sendMessage(messageQueue.poll() ?: break)
            }
        }
    }

    fun toggleVideo(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun toggleAudio(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun switchCamera(context: Context) {
        videoCapturer?.let { capturer ->
            if (capturer is CameraVideoCapturer) {
                capturer.switchCamera(null)
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
        synchronized(this) {
            hasRemoteDescription = false
        }

        dataChannel?.close()
        dataChannel = null

        localVideoTrack?.removeSink(localVideoView)
        localVideoTrack?.dispose()
        localVideoTrack = null

        localAudioTrack?.dispose()
        localAudioTrack = null

        localVideoSource?.dispose()
        localVideoSource = null

        localAudioSource?.dispose()
        localAudioSource = null

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        pc?.close()
        pc = null

        iceQueue.clear()
        synchronized(messageQueue) { messageQueue.clear() }
        isClosing = false
    }
}