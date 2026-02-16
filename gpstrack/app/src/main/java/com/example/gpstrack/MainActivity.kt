package com.example.gpstrack

import android.Manifest
import android.content.pm.PackageManager
import android.location.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import com.hivemq.client.mqtt.mqtt3.message.connect.Mqtt3Connect
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var locationManager: LocationManager? = null

    private lateinit var txtStatus: TextView
    private lateinit var txtAccuracy: TextView
    private lateinit var txtNmea: TextView
    private lateinit var txtVerbose: TextView
    private lateinit var scrollView: ScrollView

    // ===== MQTT CONFIGURATION - UPDATED =====
    private var mqttClient: Mqtt3AsyncClient? = null
    private val brokerHost = "324fe28c17b24e76a2e94df596af2aef.s1.eu.hivemq.cloud"
    private val brokerPort = 8883
    private val mqttUsername = "admin"
    private val mqttPassword = "admin1234S"
    private val topic = "gps/demo/nmea"

    private val LOCATION_REQ = 1001

    // Store listeners for cleanup
    private var locationListener: LocationListener? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null

    // Coroutine scope for MQTT operations
    private val mqttScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nmeaExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // GPS Status tracking
    private var satellitesTotal = 0
    private var satellitesUsed = 0
    private var firstFix = true
    private var fixStartTime = 0L
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    data class GnssSatelliteInfo(
        val svid: Int,
        val constellation: String,
        val cn0DbHz: Float,
        val usedInFix: Boolean,
        val hasEphemeris: Boolean,
        val hasAlmanac: Boolean
    )

    private val satelliteList = mutableListOf<GnssSatelliteInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtAccuracy = findViewById(R.id.txtAccuracy)
        txtNmea = findViewById(R.id.txtNmea)
        txtVerbose = findViewById(R.id.txtVerbose)
        scrollView = findViewById(R.id.scrollView)

        locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager

        checkPermissionAndStart()
    }

    // ---------- MQTT - UPDATED WITH AUTHENTICATION & TLS ----------
    private fun connectMqtt() {
        mqttScope.launch {
            try {
                logVerbose("MQTT", "Connecting to $brokerHost:$brokerPort...")

                val client = Mqtt3Client.builder()
                    .identifier("android-gps-${UUID.randomUUID()}")
                    .serverHost(brokerHost)
                    .serverPort(brokerPort)
                    .sslWithDefaultConfig() // Enable TLS/SSL for port 8883
                    .automaticReconnectWithDefaultConfig()
                    .buildAsync()

                mqttClient = client

                // Connect with username and password
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
                    logVerbose("MQTT", "Connected successfully to HiveMQ Cloud")
                    updateStatus()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    logVerbose("MQTT", "Connection failed: ${e.message}")
                    txtStatus.text = "MQTT: ERROR - ${e.message}"
                }
                e.printStackTrace()
            }
        }
    }

    private fun publishNmea(nmea: String) {
        mqttScope.launch {
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

    private fun disconnectMqtt() {
        mqttScope.launch {
            try {
                mqttClient?.disconnect()?.get()
                withContext(Dispatchers.Main) {
                    logVerbose("MQTT", "Disconnected")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ---------- GPS Verbose Logging ----------
    private fun logVerbose(category: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [$category] $message\n"

        runOnUiThread {
            txtVerbose.append(logLine)
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun updateStatus() {
        val mqttStatus = if (mqttClient?.state?.isConnected == true) "CONNECTED" else "DISCONNECTED"
        val gpsStatus = if (locationListener != null) "ACTIVE" else "INACTIVE"

        txtStatus.text = buildString {
            appendLine("MQTT: $mqttStatus")
            appendLine("GPS: $gpsStatus")
            appendLine("Satellites: $satellitesUsed/$satellitesTotal used")
        }
    }

    private fun getConstellationName(constellationType: Int): String {
        return when (constellationType) {
            GnssStatus.CONSTELLATION_GPS -> "GPS"
            GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
            GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
            GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
            GnssStatus.CONSTELLATION_UNKNOWN -> "Unknown"
            else -> "Other($constellationType)"
        }
    }

    // ---------- GPS ----------
    private fun checkPermissionAndStart() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                LOCATION_REQ
            )
        } else {
            startGps()
        }
    }

    private fun startGps() {
        locationManager ?: run {
            txtStatus.text = "GPS: LocationManager not available"
            return
        }

        if (locationManager?.allProviders?.contains(LocationManager.GPS_PROVIDER) != true) {
            txtStatus.text = "GPS: Provider not available"
            return
        }

        fixStartTime = System.currentTimeMillis()
        logVerbose("GPS", "Starting GPS acquisition...")

        connectMqtt()

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val ttf = if (firstFix) {
                    val timeToFix = System.currentTimeMillis() - fixStartTime
                    firstFix = false
                    "TTF: ${timeToFix}ms"
                } else {
                    ""
                }

                txtAccuracy.text = buildString {
                    appendLine("Accuracy: ${location.accuracy} m")
                    appendLine("Speed: ${location.speed} m/s")
                    appendLine("Altitude: ${location.altitude} m")
                    appendLine("Bearing: ${location.bearing}°")
                    appendLine("Provider: ${location.provider}")
                    appendLine(ttf)
                }

                logVerbose("LOCATION", "Lat: ${location.latitude}, Lon: ${location.longitude}, Acc: ${location.accuracy}m")
            }

            override fun onProviderEnabled(provider: String) {
                logVerbose("GPS", "Provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                logVerbose("GPS", "Provider disabled: $provider")
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                val statusStr = when (status) {
                    LocationProvider.AVAILABLE -> "AVAILABLE"
                    LocationProvider.OUT_OF_SERVICE -> "OUT_OF_SERVICE"
                    LocationProvider.TEMPORARILY_UNAVAILABLE -> "TEMPORARILY_UNAVAILABLE"
                    else -> "UNKNOWN($status)"
                }
                logVerbose("GPS", "Provider status changed: $provider = $statusStr")
            }
        }

        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                locationListener!!,
                Looper.getMainLooper()
            )
            logVerbose("GPS", "Location updates requested (1s, 0m)")
        } catch (e: SecurityException) {
            logVerbose("GPS", "Security Exception: ${e.message}")
            return
        }

        nmeaListener = OnNmeaMessageListener { message, timestamp ->
            runOnUiThread {
                txtNmea.text = message
            }
            logVerbose("NMEA", message.take(60))
            publishNmea(message)
        }

        locationManager?.addNmeaListener(nmeaListener!!, mainHandler)
        logVerbose("GPS", "NMEA listener registered")

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
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
                    satelliteList.clear()

                    for (i in 0 until satellitesTotal) {
                        if (status.usedInFix(i)) used++

                        satelliteList.add(GnssSatelliteInfo(
                            svid = status.getSvid(i),
                            constellation = getConstellationName(status.getConstellationType(i)),
                            cn0DbHz = status.getCn0DbHz(i),
                            usedInFix = status.usedInFix(i),
                            hasEphemeris = status.hasEphemerisData(i),
                            hasAlmanac = status.hasAlmanacData(i)
                        ))
                    }

                    satellitesUsed = used
                    updateStatus()

                    if (System.currentTimeMillis() % 10000 < 1000) {
                        val sb = StringBuilder("Satellites ($satellitesUsed/$satellitesTotal):\n")
                        satelliteList.groupBy { it.constellation }.forEach { (const, sats) ->
                            val usedCount = sats.count { it.usedInFix }
                            sb.append("  $const: ${sats.size} visible, $usedCount used\n")
                        }
                        logVerbose("SATELLITES", sb.toString().trim())
                    }
                }
            }

            locationManager?.registerGnssStatusCallback(gnssStatusCallback!!, mainHandler)
            logVerbose("GPS", "GNSS status callback registered")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_REQ &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startGps()
        } else {
            logVerbose("PERMISSION", "Location permission denied")
            txtStatus.text = "GPS: PERMISSION DENIED"
        }
    }

    override fun onPause() {
        super.onPause()
        stopGpsUpdates()
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startGps()
        }
    }

    override fun onDestroy() {
        stopGpsUpdates()
        disconnectMqtt()
        mqttScope.cancel()
        nmeaExecutor.shutdown()
        super.onDestroy()
    }

    private fun stopGpsUpdates() {
        logVerbose("GPS", "Stopping updates...")

        locationListener?.let {
            try {
                locationManager?.removeUpdates(it)
                logVerbose("GPS", "Location updates removed")
            } catch (_: Exception) {}
            locationListener = null
        }

        nmeaListener?.let {
            try {
                locationManager?.removeNmeaListener(it)
                logVerbose("GPS", "NMEA listener removed")
            } catch (_: Exception) {}
            nmeaListener = null
        }

        gnssStatusCallback?.let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    locationManager?.unregisterGnssStatusCallback(it)
                    logVerbose("GPS", "GNSS callback removed")
                } catch (_: Exception) {}
            }
            gnssStatusCallback = null
        }
    }
}