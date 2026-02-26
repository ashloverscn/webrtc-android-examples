package com.example.gpstracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationProvider
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class GpsManager(private val context: Context) {
    private var locationManager: LocationManager? = null

    private var locationListener: LocationListener? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null

    private var satellitesTotal = 0
    private var satellitesUsed = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    var onLocationUpdate: ((Location) -> Unit)? = null
    var onNmeaReceived: ((String) -> Unit)? = null
    var onSatelliteStatusChanged: ((Int, Int, List<GnssSatelliteInfo>) -> Unit)? = null
    var onVerboseLog: ((String, String) -> Unit)? = null

    data class GnssSatelliteInfo(
        val svid: Int,
        val constellation: String,
        val cn0DbHz: Float,
        val usedInFix: Boolean,
        val hasEphemeris: Boolean,
        val hasAlmanac: Boolean
    )

    fun initialize() {
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun startGps() {
        locationManager ?: run {
            onVerboseLog?.invoke("GPS", "LocationManager not available")
            return
        }

        if (locationManager?.allProviders?.contains(LocationManager.GPS_PROVIDER) != true) {
            onVerboseLog?.invoke("GPS", "Provider not available")
            return
        }

        onVerboseLog?.invoke("GPS", "Starting GPS acquisition...")

        setupLocationListener()
        setupNmeaListener()
        setupGnssStatusCallback()
    }

    private fun setupLocationListener() {
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocationUpdate?.invoke(location)
            }

            override fun onProviderEnabled(provider: String) {
                onVerboseLog?.invoke("GPS", "Provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                onVerboseLog?.invoke("GPS", "Provider disabled: $provider")
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                val statusStr = when (status) {
                    LocationProvider.AVAILABLE -> "AVAILABLE"
                    LocationProvider.OUT_OF_SERVICE -> "OUT_OF_SERVICE"
                    LocationProvider.TEMPORARILY_UNAVAILABLE -> "TEMPORARILY_UNAVAILABLE"
                    else -> "UNKNOWN($status)"
                }
                onVerboseLog?.invoke("GPS", "Provider status changed: $provider = $statusStr")
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
            onVerboseLog?.invoke("GPS", "Location updates requested (1s, 0m)")
        } catch (e: SecurityException) {
            onVerboseLog?.invoke("GPS", "Security Exception: ${e.message}")
        }
    }

    private fun setupNmeaListener() {
        nmeaListener = OnNmeaMessageListener { message, timestamp ->
            onNmeaReceived?.invoke(message)
        }

        locationManager?.addNmeaListener(nmeaListener!!, mainHandler)
        onVerboseLog?.invoke("GPS", "NMEA listener registered")
    }

    private fun setupGnssStatusCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssStatusCallback = object : GnssStatus.Callback() {
                override fun onStarted() {
                    onVerboseLog?.invoke("GNSS", "Status tracking started")
                }

                override fun onStopped() {
                    onVerboseLog?.invoke("GNSS", "Status tracking stopped")
                }

                override fun onFirstFix(ttffMillis: Int) {
                    onVerboseLog?.invoke("GNSS", "First fix acquired in ${ttffMillis}ms")
                }

                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    satellitesTotal = status.satelliteCount
                    var used = 0
                    val satelliteList = mutableListOf<GnssSatelliteInfo>()

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
                    onSatelliteStatusChanged?.invoke(satellitesUsed, satellitesTotal, satelliteList)
                }
            }

            locationManager?.registerGnssStatusCallback(gnssStatusCallback!!, mainHandler)
            onVerboseLog?.invoke("GPS", "GNSS status callback registered")
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

    fun stopGps() {
        locationListener?.let {
            try {
                locationManager?.removeUpdates(it)
            } catch (_: Exception) {}
            locationListener = null
        }

        nmeaListener?.let {
            try {
                locationManager?.removeNmeaListener(it)
            } catch (_: Exception) {}
            nmeaListener = null
        }

        gnssStatusCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    locationManager?.unregisterGnssStatusCallback(it)
                } catch (_: Exception) {}
            }
            gnssStatusCallback = null
        }

        onVerboseLog?.invoke("GPS", "GPS updates stopped")
    }

    fun getSatellitesUsed(): Int = satellitesUsed
    fun getSatellitesTotal(): Int = satellitesTotal
}