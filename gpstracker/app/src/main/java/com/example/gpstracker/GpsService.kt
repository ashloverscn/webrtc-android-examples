package com.example.gpstracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class GpsService : Service() {
    private val TAG = "GpsService"
    private val CHANNEL_ID = "gps_service_channel"
    private val NOTIFICATION_ID = 1

    // MQTT
    private var mqttClient: Mqtt3AsyncClient? = null
    private val brokerHost = "324fe28c17b24e76a2e94df596af2aef.s1.eu.hivemq.cloud"
    private val brokerPort = 8883
    private val mqttUsername = "admin"
    private val mqttPassword = "admin1234S"
    private val topic = "gps/demo/nmea"

    // GPS
    private lateinit var locationManager: LocationManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var locationListener: LocationListener? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Service
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Network monitoring - FIXED: Track active network
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null
    private var isNetworkAvailable = AtomicBoolean(false)
    private val networkLock = Object()

    // Reconnection - FIXED: More aggressive reconnection
    private var isReconnecting = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 20 // Increased from 10
    private val baseReconnectDelay = 3000L // 3 seconds (faster)
    private val maxReconnectDelay = 30000L // 30 seconds

    // Data callbacks
    var onStatusUpdate: ((String) -> Unit)? = null
    var onAccuracyUpdate: ((String) -> Unit)? = null
    var onNmeaUpdate: ((String) -> Unit)? = null
    var onVerboseLog: ((String, String) -> Unit)? = null
    var onConnectionStateChange: ((Boolean) -> Unit)? = null

    // State
    private var isRunning = false
    private var firstFix = true
    private var fixStartTime = 0L
    private var satellitesUsed = 0
    private var satellitesTotal = 0
    private val nmeaMessageQueue = mutableListOf<String>()

    inner class LocalBinder : Binder() {
        fun getService(): GpsService = this@GpsService
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GpsService::WakeLock")
        createNotificationChannel()
        setupNetworkMonitoring()
    }

    // FIXED: Better network monitoring that tracks specific network changes
    private fun setupNetworkMonitoring() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(networkLock) {
                    Log.d(TAG, "Network available: $network")
                    val previousNetwork = activeNetwork
                    activeNetwork = network
                    isNetworkAvailable.set(true)

                    // FIXED: Force reconnect if network changed or was unavailable
                    if (isRunning) {
                        if (previousNetwork != network || !isMqttConnected()) {
                            logVerbose("NETWORK", "New network available: $network")
                            forceReconnect()
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                synchronized(networkLock) {
                    Log.d(TAG, "Network lost: $network")
                    if (activeNetwork == network) {
                        activeNetwork = null
                        isNetworkAvailable.set(false)
                        logVerbose("NETWORK", "Active network lost: $network")
                        onConnectionStateChange?.invoke(false)

                        // FIXED: Immediately trigger reconnect when network is lost
                        if (isRunning) {
                            forceReconnect()
                        }
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // Check if internet is actually available
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                Log.d(TAG, "Network capabilities changed: $network, hasInternet=$hasInternet")

                if (!hasInternet && activeNetwork == network) {
                    logVerbose("NETWORK", "Internet validation failed on: $network")
                }
            }

            override fun onUnavailable() {
                Log.d(TAG, "Network unavailable")
                isNetworkAvailable.set(false)
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        if (!isRunning) {
            startGpsTracking()
        }
        return START_STICKY
    }

    private fun startForeground() {
        val notification = createNotification("GPS Tracker Running...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS NMEA Tracker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Publishing GPS NMEA data to MQTT"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS NMEA Tracker")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    // ========== MQTT WITH IMPROVED AUTO-RECONNECT ==========

    // FIXED: Force disconnect and reconnect
    private fun forceReconnect() {
        serviceScope.launch {
            logVerbose("MQTT", "Forcing reconnect due to network change...")

            // Always disconnect first to clear stale connection
            try {
                mqttClient?.disconnect()?.get()
            } catch (e: Exception) {
                // Ignore disconnect errors
            }
            mqttClient = null

            delay(500) // Brief pause

            // Reset reconnection state
            isReconnecting.set(false)
            reconnectAttempts = 0

            // Start fresh connection
            connectMqtt()
        }
    }

    private fun connectMqtt() {
        serviceScope.launch {
            try {
                if (isReconnecting.get()) {
                    logVerbose("MQTT", "Already reconnecting, skipping...")
                    return@launch
                }

                isReconnecting.set(true)
                logVerbose("MQTT", "Connecting to $brokerHost:$brokerPort... (attempt ${reconnectAttempts + 1})")

                // FIXED: Always create new client for clean state
                val client = Mqtt3Client.builder()
                    .identifier("android-gps-${UUID.randomUUID()}")
                    .serverHost(brokerHost)
                    .serverPort(brokerPort)
                    .sslWithDefaultConfig()
                    // FIXED: Disable automatic reconnect - we handle it manually for better control
                    // .automaticReconnectWithDefaultConfig()
                    .buildAsync()

                mqttClient = client

                // Use blocking get() with timeout handling
                try {
                    val connAck = client.connectWith()
                        .cleanSession(true)
                        .keepAlive(20)
                        .simpleAuth()
                        .username(mqttUsername)
                        .password(mqttPassword.toByteArray())
                        .applySimpleAuth()
                        .send()
                        .get()

                    // Success
                    handleConnectionSuccess()

                } catch (e: Exception) {
                    handleConnectionFailure(e)
                }

            } catch (e: Exception) {
                handleConnectionFailure(e)
            }
        }
    }

    private suspend fun handleConnectionSuccess() {
        reconnectAttempts = 0
        isReconnecting.set(false)

        withContext(Dispatchers.Main) {
            logVerbose("MQTT", "✓ Connected successfully!")
            onConnectionStateChange?.invoke(true)
            updateStatus()
            updateNotification("MQTT Connected - GPS Active")
        }

        // Publish queued messages
        publishQueuedMessages()

        // Start connection monitor
        startConnectionMonitor()
    }

    private suspend fun handleConnectionFailure(throwable: Throwable) {
        Log.e(TAG, "Connection failed: ${throwable.message}")

        withContext(Dispatchers.Main) {
            logVerbose("MQTT", "✗ Connection failed: ${throwable.message}")
            onConnectionStateChange?.invoke(false)
        }

        isReconnecting.set(false)
        scheduleReconnect()
    }

    // FIXED: Monitor connection and detect silent failures
    private fun startConnectionMonitor() {
        serviceScope.launch {
            while (isRunning && isMqttConnected()) {
                delay(30000) // Check every 30 seconds

                if (!isMqttConnected()) {
                    logVerbose("MQTT", "Connection monitor detected disconnect")
                    forceReconnect()
                    break
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            logVerbose("MQTT", "Max attempts reached, waiting for network...")
            isReconnecting.set(false)
            // Keep trying when network returns
            return
        }

        reconnectAttempts++
        val delay = calculateReconnectDelay()

        logVerbose("MQTT", "Reconnecting in ${delay/1000}s (attempt $reconnectAttempts/$maxReconnectAttempts)")

        serviceScope.launch {
            delay(delay)

            if (isNetworkAvailable.get()) {
                connectMqtt()
            } else {
                logVerbose("MQTT", "Network unavailable, deferring reconnect...")
                isReconnecting.set(false)
                // Will retry when network becomes available
            }
        }
    }

    private fun calculateReconnectDelay(): Long {
        // Exponential backoff: 3s, 6s, 12s, 24s, 30s max
        val delay = baseReconnectDelay * (1 shl (reconnectAttempts - 1))
        return minOf(delay, maxReconnectDelay)
    }

    private fun isMqttConnected(): Boolean {
        return try {
            mqttClient?.state?.isConnected == true
        } catch (e: Exception) {
            false
        }
    }

    private fun publishNmea(nmea: String) {
        if (!isMqttConnected()) {
            synchronized(nmeaMessageQueue) {
                if (nmeaMessageQueue.size < 100) {
                    nmeaMessageQueue.add(nmea)
                }
            }

            // Trigger reconnect if we have messages to send
            if (!isReconnecting.get() && isNetworkAvailable.get()) {
                serviceScope.launch {
                    delay(1000) // Brief delay to batch messages
                    forceReconnect()
                }
            }
            return
        }

        serviceScope.launch {
            try {
                mqttClient?.publishWith()
                    ?.topic(topic)
                    ?.payload(nmea.toByteArray())
                    ?.qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_MOST_ONCE)
                    ?.send()
                    ?.whenComplete { _, throwable ->
                        if (throwable != null) {
                            Log.e(TAG, "Publish failed: ${throwable.message}")
                            synchronized(nmeaMessageQueue) {
                                if (nmeaMessageQueue.size < 100) {
                                    nmeaMessageQueue.add(nmea)
                                }
                            }
                            // Connection may be dead, force reconnect
                            serviceScope.launch {
                                delay(500)
                                if (!isMqttConnected()) {
                                    forceReconnect()
                                }
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Publish error: ${e.message}")
                synchronized(nmeaMessageQueue) {
                    if (nmeaMessageQueue.size < 100) {
                        nmeaMessageQueue.add(nmea)
                    }
                }
            }
        }
    }

    private fun publishQueuedMessages() {
        synchronized(nmeaMessageQueue) {
            while (nmeaMessageQueue.isNotEmpty() && isMqttConnected()) {
                val message = nmeaMessageQueue.removeAt(0)
                publishNmea(message)
                // Small delay to prevent overwhelming the connection
                Thread.sleep(10)
            }
        }
    }

    private fun disconnectMqtt() {
        try {
            mqttClient?.disconnect()?.get()
        } catch (e: Exception) {
            // Ignore
        }
        mqttClient = null
    }

    // ========== GPS ==========
    private fun startGpsTracking() {
        if (isRunning) return

        try {
            wakeLock.acquire(10 * 60 * 1000L)

            fixStartTime = System.currentTimeMillis()
            firstFix = true

            // Connect MQTT first
            connectMqtt()

            // Location listener
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val ttf = if (firstFix) {
                        val time = System.currentTimeMillis() - fixStartTime
                        firstFix = false
                        "TTF: ${time}ms"
                    } else ""

                    val accuracyText = buildString {
                        appendLine("Accuracy: ${location.accuracy} m")
                        appendLine("Speed: ${location.speed} m/s")
                        appendLine("Altitude: ${location.altitude} m")
                        appendLine("Bearing: ${location.bearing}°")
                        appendLine("Provider: ${location.provider}")
                        appendLine(ttf)
                    }

                    onAccuracyUpdate?.invoke(accuracyText)
                    logVerbose("LOCATION", "Lat: ${location.latitude}, Lon: ${location.longitude}, Acc: ${location.accuracy}m")
                    updateNotification("GPS Active - ${location.accuracy}m accuracy")
                }

                override fun onProviderEnabled(provider: String) {
                    logVerbose("GPS", "Provider enabled: $provider")
                }

                override fun onProviderDisabled(provider: String) {
                    logVerbose("GPS", "Provider disabled: $provider")
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, 0f, locationListener!!, Looper.getMainLooper()
            )

            // NMEA listener
            nmeaListener = OnNmeaMessageListener { message, timestamp ->
                onNmeaUpdate?.invoke(message)
                logVerbose("NMEA", message.take(60))
                publishNmea(message)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locationManager.addNmeaListener(nmeaListener!!, mainHandler)
            }

            // GNSS Status
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssStatusCallback = object : GnssStatus.Callback() {
                    override fun onStarted() {
                        logVerbose("GNSS", "Status tracking started")
                    }

                    override fun onStopped() {
                        logVerbose("GNSS", "Status tracking stopped")
                    }

                    override fun onFirstFix(ttffMillis: Int) {
                        logVerbose("GNSS", "First fix acquired in ${ttffMillis}ms")
                    }

                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        satellitesTotal = status.satelliteCount
                        var used = 0
                        for (i in 0 until satellitesTotal) {
                            if (status.usedInFix(i)) used++
                        }
                        satellitesUsed = used
                        updateStatus()
                    }
                }
                locationManager.registerGnssStatusCallback(gnssStatusCallback!!, mainHandler)
            }

            isRunning = true
            logVerbose("GPS", "GPS tracking started")

        } catch (e: SecurityException) {
            logVerbose("GPS", "Security Exception: ${e.message}")
        }
    }

    private fun updateStatus() {
        val mqttStatus = if (isMqttConnected()) "CONNECTED" else "DISCONNECTED"
        val gpsStatus = if (isRunning) "ACTIVE" else "INACTIVE"

        val statusText = buildString {
            appendLine("MQTT: $mqttStatus")
            appendLine("GPS: $gpsStatus")
            appendLine("Satellites: $satellitesUsed/$satellitesTotal used")
        }

        onStatusUpdate?.invoke(statusText)
    }

    private fun logVerbose(category: String, message: String) {
        onVerboseLog?.invoke(category, message)
    }

    fun stopTracking() {
        if (wakeLock.isHeld) wakeLock.release()

        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
            networkCallback = null
        }

        locationListener?.let {
            locationManager.removeUpdates(it)
            locationListener = null
        }

        nmeaListener?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locationManager.removeNmeaListener(it)
            }
            nmeaListener = null
        }

        gnssStatusCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locationManager.unregisterGnssStatusCallback(it)
            }
            gnssStatusCallback = null
        }

        isRunning = false
        disconnectMqtt()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
    }
}