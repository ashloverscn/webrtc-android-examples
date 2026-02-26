package com.example.gpstracker

import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Logger(
    private val txtVerbose: TextView,
    private val scrollView: ScrollView
) {
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(category: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [$category] $message\n"

        txtVerbose.append(logLine)
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}