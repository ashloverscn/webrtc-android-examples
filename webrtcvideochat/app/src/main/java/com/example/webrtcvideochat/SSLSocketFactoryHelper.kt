package com.example.webrtcvideochat

import javax.net.ssl.SSLSocketFactory

object SSLSocketFactoryHelper {
    fun get(): SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
}