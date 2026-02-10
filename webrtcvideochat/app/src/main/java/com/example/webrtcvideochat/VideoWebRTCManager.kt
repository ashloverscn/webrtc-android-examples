package com.example.webrtcvideochat

import android.content.Context
import android.util.Log
import org.webrtc.*

class VideoWebRTCManager(
    private val context: Context,
    private val listener: WebRTCListener
) {

    interface WebRTCListener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onIceStateChanged(state: PeerConnection.IceConnectionState)
        fun onDataChannelOpen()
        fun onDataChannelClosed()
        fun onMessageReceived(message: String)
        fun onError(error: String)
        fun onLocalVideoTrack(track: VideoTrack)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onRemoteAudioTrack(track: AudioTrack)
    }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    private var dataChannel: DataChannel? = null
    private var eglBase: EglBase? = null

    private val TAG = "VideoWebRTCManager"

    /* -------------------------------------------------- */
    /* INITIALIZATION                                     */
    /* -------------------------------------------------- */

    fun initializeVideo(
        context: Context,
        localView: SurfaceViewRenderer,
        remoteView: SurfaceViewRenderer,
        egl: EglBase,
        enableVideo: Boolean
    ) {
        eglBase = egl
        localRenderer = localView
        remoteRenderer = remoteView

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        val encoderFactory =
            DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        val decoderFactory =
            DefaultVideoDecoderFactory(egl.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        if (enableVideo) {
            startLocalMedia()
        }
    }

    private fun startLocalMedia() {
        videoCapturer = createCameraCapturer()
        videoSource = factory!!.createVideoSource(false)

        val surfaceHelper = SurfaceTextureHelper.create(
            "CameraThread", eglBase!!.eglBaseContext
        )

        videoCapturer!!.initialize(
            surfaceHelper,
            context,
            videoSource!!.capturerObserver
        )

        videoCapturer!!.startCapture(1280, 720, 30)

        localVideoTrack = factory!!.createVideoTrack("VIDEO", videoSource)
        localVideoTrack!!.addSink(localRenderer)
        listener.onLocalVideoTrack(localVideoTrack!!)

        audioSource = factory!!.createAudioSource(MediaConstraints())
        localAudioTrack = factory!!.createAudioTrack("AUDIO", audioSource)
    }

    /* -------------------------------------------------- */
    /* PEER CONNECTION                                    */
    /* -------------------------------------------------- */

    fun createConnection(isCaller: Boolean, onReady: () -> Unit) {
        close()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        peerConnection = factory!!.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers),
            object : PeerConnection.Observer {

                override fun onIceCandidate(candidate: IceCandidate) {
                    listener.onIceCandidate(candidate)
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    listener.onIceStateChanged(state)
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    // REQUIRED for your WebRTC version
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    val track = transceiver.receiver.track()
                    when (track) {
                        is VideoTrack -> {
                            track.addSink(remoteRenderer)
                            listener.onRemoteVideoTrack(track)
                        }
                        is AudioTrack -> {
                            listener.onRemoteAudioTrack(track)
                        }
                    }
                }

                override fun onDataChannel(dc: DataChannel) {
                    dataChannel = dc
                    setupDataChannel()
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState) {}
                override fun onIceCandidatesRemoved(p0: Array<IceCandidate>) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
                override fun onAddStream(p0: MediaStream) {}
                override fun onRemoveStream(p0: MediaStream) {}
                override fun onRenegotiationNeeded() {}
            }
        )

        /* ---------- RTP TRANSCEIVERS (CRITICAL FIX) ---------- */

        peerConnection!!.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        )

        peerConnection!!.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        )

        /* ---------- Attach local tracks ---------- */

        peerConnection!!.senders.forEach {
            when (it.track()?.kind()) {
                MediaStreamTrack.VIDEO_TRACK_KIND -> it.setTrack(localVideoTrack, false)
                MediaStreamTrack.AUDIO_TRACK_KIND -> it.setTrack(localAudioTrack, false)
            }
        }

        if (isCaller) {
            val dcInit = DataChannel.Init()
            dataChannel = peerConnection!!.createDataChannel("chat", dcInit)
            setupDataChannel()
        }

        onReady()
    }

    /* -------------------------------------------------- */
    /* SDP                                                */
    /* -------------------------------------------------- */

    fun createOffer(onSdp: (SessionDescription) -> Unit) {
        peerConnection!!.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection!!.setLocalDescription(SimpleSdpObserver(), desc)
                onSdp(desc)
            }
        }, MediaConstraints())
    }

    fun createAnswer(onSdp: (SessionDescription) -> Unit) {
        peerConnection!!.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection!!.setLocalDescription(SimpleSdpObserver(), desc)
                onSdp(desc)
            }
        }, MediaConstraints())
    }

    fun setRemoteDescription(desc: SessionDescription, onDone: (() -> Unit)? = null) {
        peerConnection!!.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                onDone?.invoke()
            }
        }, desc)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    /* -------------------------------------------------- */
    /* DATA CHANNEL                                       */
    /* -------------------------------------------------- */

    private fun setupDataChannel() {
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}

            override fun onStateChange() {
                when (dataChannel?.state()) {
                    DataChannel.State.OPEN -> listener.onDataChannelOpen()
                    DataChannel.State.CLOSED -> listener.onDataChannelClosed()
                    else -> {}
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                listener.onMessageReceived(String(data))
            }
        })
    }

    fun sendMessage(msg: String): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        dc.send(DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(msg.toByteArray()),
            false
        ))
        return true
    }

    /* -------------------------------------------------- */
    /* CONTROLS                                           */
    /* -------------------------------------------------- */

    fun toggleVideo(enable: Boolean) {
        localVideoTrack?.setEnabled(enable)
    }

    fun toggleAudio(enable: Boolean) {
        localAudioTrack?.setEnabled(enable)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    /* -------------------------------------------------- */
    /* CLEANUP                                            */
    /* -------------------------------------------------- */

    fun close() {
        dataChannel?.close()
        dataChannel = null

        peerConnection?.close()
        peerConnection = null
    }

    /* -------------------------------------------------- */
    /* CAMERA                                             */
    /* -------------------------------------------------- */

    private fun createCameraCapturer(): CameraVideoCapturer {
        val enumerator = Camera2Enumerator(context)
        for (device in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(device)) {
                return enumerator.createCapturer(device, null)
            }
        }
        throw RuntimeException("No camera found")
    }
}