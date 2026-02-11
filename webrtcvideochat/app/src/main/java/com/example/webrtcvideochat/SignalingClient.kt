package com.example.webrtcvideochat

import android.content.Context
import org.json.JSONObject

class SignalingClient(
    context: Context,
    private val peerId: String,
    private val listener: SignalingListener
) : MQTTManager.MQTTListener {

    interface SignalingListener {
        fun onPeerListUpdated(peers: Map<String, PeerRegistry.PeerInfo>)
        fun onOfferReceived(from: String, sdp: String)
        fun onAnswerReceived(from: String, sdp: String)
        fun onIceCandidateReceived(from: String, candidate: IceCandidateData)
        fun onConnected()
        fun onError(error: Throwable)
    }

    data class IceCandidateData(
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val candidate: String
    )

    private val mqtt = MQTTManager(context, peerId, this)
    private val registry = PeerRegistry()
    private var targetPeer: String? = null

    init {
        registry.addListener { listener.onPeerListUpdated(it) }
    }

    fun connect() = mqtt.connect()

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
                put("from", this@SignalingClient.peerId)
                put("to", it)
                put("data", data)
            }
            mqtt.publish(msg)
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
                put("from", this@SignalingClient.peerId)
                put("to", it)
                put("data", data)
            }
            mqtt.publish(msg)
        }
    }

    fun sendIceCandidate(candidate: org.webrtc.IceCandidate) {
        targetPeer?.let {
            val data = JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
            val msg = JSONObject().apply {
                put("type", "ice")
                put("from", this@SignalingClient.peerId)
                put("to", it)
                put("data", data)
            }
            mqtt.publish(msg)
        }
    }

    override fun onMessageReceived(json: JSONObject) {
        val type = json.optString("type")
        val from = json.optString("from")
        val to = json.optString("to")

        if (type == "presence") {
            registry.update(from)
            return
        }

        // Only process messages meant for us
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

    override fun onConnected() {
        listener.onConnected()
    }

    override fun onConnectionLost(error: Throwable?) {
        error?.let { listener.onError(it) }
    }

    fun destroy() {
        mqtt.disconnect()
    }
}