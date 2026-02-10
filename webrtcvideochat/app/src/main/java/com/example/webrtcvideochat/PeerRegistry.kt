package com.example.webrtcvideochat

import android.os.Handler
import android.os.Looper

class PeerRegistry(private val timeoutMs: Long = 5000) {
    data class PeerInfo(val lastSeen: Long, val isOnline: Boolean)

    private val peers = mutableMapOf<String, Long>()
    private val listeners = mutableListOf<(Map<String, PeerInfo>) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cleanupRunnable: Runnable? = null

    fun update(peerId: String) {
        peers[peerId] = System.currentTimeMillis()
        notifyListeners()
        scheduleCleanup()
    }

    fun remove(peerId: String) {
        peers.remove(peerId)
        notifyListeners()
    }

    fun getPeers(excludeId: String? = null): Map<String, PeerInfo> {
        val now = System.currentTimeMillis()
        return peers.mapValues { (_, lastSeen) ->
            PeerInfo(lastSeen, now - lastSeen <= timeoutMs)
        }.filter { (id, _) -> id != excludeId }
    }

    fun addListener(listener: (Map<String, PeerInfo>) -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        mainHandler.post {
            listeners.forEach { it.invoke(getPeers()) }
        }
    }

    private fun scheduleCleanup() {
        cleanupRunnable?.let { mainHandler.removeCallbacks(it) }
        cleanupRunnable = Runnable {
            val now = System.currentTimeMillis()
            val expired = peers.filter { now - it.value > timeoutMs }.keys
            if (expired.isNotEmpty()) {
                expired.forEach { peers.remove(it) }
                notifyListeners()
            }
        }.also { mainHandler.postDelayed(it, timeoutMs) }
    }
}