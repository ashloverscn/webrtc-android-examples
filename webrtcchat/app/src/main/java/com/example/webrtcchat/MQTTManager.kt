// MQTTManager.kt
package com.example.webrtcchat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject

class MQTTManager(
    context: Context,
    private val peerId: String,
    private val listener: MQTTListener,
    private val brokerUrl: String = "ssl://e5122a5328ea4986a0295fa6e037655a.s2.eu.hivemq.cloud:8883",
    private val username: String = "admin",
    private val password: String = "admin1234S",
    private val topic: String = "webrtc/signaling"
) {
    interface MQTTListener {
        fun onMessageReceived(json: JSONObject)
        fun onConnectionLost(error: Throwable?)
        fun onConnected()
    }

    private var client: MqttClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var presenceThread: Thread? = null

    fun connect() {
        try {
            val clientId = "android_$peerId"
            client = MqttClient(brokerUrl, clientId, null)

            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                userName = username
                password = this@MQTTManager.password.toCharArray()
            }

            client?.setCallback(object : MqttCallback {
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.let {
                        val json = JSONObject(String(it.payload))
                        mainHandler.post { listener.onMessageReceived(json) }
                    }
                }
                override fun connectionLost(cause: Throwable?) {
                    mainHandler.post { listener.onConnectionLost(cause) }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            client?.connect(options)
            client?.subscribe(topic)
            startPresenceHeartbeat()
            mainHandler.post { listener.onConnected() }

        } catch (e: Exception) {
            Log.e("MQTTManager", "Connection failed", e)
            mainHandler.post { listener.onConnectionLost(e) }
        }
    }

    private fun startPresenceHeartbeat() {
        presenceThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    publish(JSONObject().apply {
                        put("type", "presence")
                        put("from", peerId)
                    })
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e("MQTTManager", "Presence error", e)
                }
            }
        }.apply { start() }
    }

    fun send(to: String, type: String, data: JSONObject) {
        try {
            val msg = JSONObject().apply {
                put("type", type)
                put("from", peerId)
                put("to", to)
                put("data", data)
            }
            client?.publish(topic, MqttMessage(msg.toString().toByteArray()))
        } catch (e: Exception) {
            Log.e("MQTTManager", "Send failed", e)
        }
    }

    fun publish(msg: JSONObject) {
        try {
            client?.publish(topic, MqttMessage(msg.toString().toByteArray()))
        } catch (e: Exception) {
            Log.e("MQTTManager", "Publish failed", e)
        }
    }

    fun disconnect() {
        presenceThread?.interrupt()
        try {
            client?.disconnect()
            client?.close()
        } catch (e: Exception) {
            Log.e("MQTTManager", "Disconnect error", e)
        }
    }
}