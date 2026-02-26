package com.example.gpstracker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView
    private lateinit var txtAccuracy: TextView
    private lateinit var txtNmea: TextView
    private lateinit var txtVerbose: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnStartStop: Button

    private var gpsService: GpsService? = null
    private var isServiceBound = false
    private var isTracking = false

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GpsService.LocalBinder
            gpsService = binder.getService()
            isServiceBound = true
            setupServiceCallbacks()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gpsService = null
            isServiceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startGpsService()
        } else {
            txtStatus.text = "Permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        txtStatus = findViewById(R.id.txtStatus)
        txtAccuracy = findViewById(R.id.txtAccuracy)
        txtNmea = findViewById(R.id.txtNmea)
        txtVerbose = findViewById(R.id.txtVerbose)
        scrollView = findViewById(R.id.scrollView)
        btnStartStop = findViewById(R.id.btnStartStop)

        btnStartStop.setOnClickListener {
            if (isTracking) {
                stopTracking()
            } else {
                startTracking()
            }
        }

        checkPermissions()
    }

    private fun setupServiceCallbacks() {
        gpsService?.onStatusUpdate = { status ->
            runOnUiThread { txtStatus.text = status }
        }

        gpsService?.onAccuracyUpdate = { accuracy ->
            runOnUiThread { txtAccuracy.text = accuracy }
        }

        gpsService?.onNmeaUpdate = { nmea ->
            runOnUiThread { txtNmea.text = nmea }
        }

        gpsService?.onVerboseLog = { category, message ->
            runOnUiThread {
                val timestamp = dateFormat.format(Date())
                val logLine = "[$timestamp] [$category] $message\n"
                txtVerbose.append(logLine)
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.plus(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needsPermission = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsPermission) {
            permissionLauncher.launch(permissions)
        } else {
            startGpsService()
        }
    }

    private fun startTracking() {
        if (!isServiceBound) {
            bindService()
        }
        isTracking = true
        btnStartStop.text = "Stop Tracking"
        btnStartStop.setBackgroundColor(getColor(android.R.color.holo_red_dark))
    }

    private fun stopTracking() {
        gpsService?.stopTracking()
        unbindService(serviceConnection)
        isServiceBound = false
        isTracking = false
        btnStartStop.text = "Start Tracking"
        btnStartStop.setBackgroundColor(getColor(android.R.color.holo_green_dark))
    }

    private fun startGpsService() {
        val intent = Intent(this, GpsService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun bindService() {
        val intent = Intent(this, GpsService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
        }
    }
}