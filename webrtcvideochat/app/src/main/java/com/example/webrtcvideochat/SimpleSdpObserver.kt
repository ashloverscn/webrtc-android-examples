package com.example.webrtcvideochat

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}