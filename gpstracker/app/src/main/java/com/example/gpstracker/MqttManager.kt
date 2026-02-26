package com.example.gpstracker

import android.util.Log
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MqttManager private constructor() {
    private val TAG = "MqttManager"

    private val brokerHost = "324fe28c17b24e76a2e94df596af2aef.s1.eu.hivemq.cloud"
    private val brokerPort = 8883
    private val mqttUsername = "admin"
    private val mqttPassword = "admin1234S"
    private val topic = "gps/demo/nmea"

    private var mqttClient: Mqtt3AsyncClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var connectionCallback: ((Boolean, String) -> Unit)? = null

    companion object {
        @Volatile
        private var instance: MqttManager? = null

        fun getInstance(): MqttManager {
            return instance ?: synchronized(this) {
                instance ?: MqttManager().also { instance = it }
            }
        }
    }

    fun setConnectionCallback(callback: (Boolean, String) -> Unit) {
        connectionCallback = callback
    }

    fun connect() {
        scope.launch {
            try {
                Log.d(TAG, "Connecting to $brokerHost:$brokerPort...")

                val client = Mqtt3Client.builder()
                    .identifier("android-gps-${UUID.randomUUID()}")
                    .serverHost(brokerHost)
                    .serverPort(brokerPort)
                    .sslWithDefaultConfig()
                    .automaticReconnectWithDefaultConfig()
                    .buildAsync()

                mqttClient = client

                val connAck = client.connectWith()
                    .cleanSession(true)
                    .keepAlive(20)
                    .simpleAuth()
                    .username(mqttUsername)
                    .password(mqttPassword.toByteArray())
                    .applySimpleAuth()
                    .send()
                    .get()

                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Connected successfully")
                    connectionCallback?.invoke(true, "Connected")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    connectionCallback?.invoke(false, "Failed: ${e.message}")
                }
                e.printStackTrace()
            }
        }
    }

    fun publishNmea(nmea: String) {
        scope.launch {
            try {
                mqttClient?.publishWith()
                    ?.topic(topic)
                    ?.payload(nmea.toByteArray())
                    ?.qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_MOST_ONCE)
                    ?.send()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                mqttClient?.disconnect()?.get()
                withContext(Dispatchers.Main) {
                    connectionCallback?.invoke(false, "Disconnected")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isConnected(): Boolean {
        return mqttClient?.state?.isConnected == true
    }
}