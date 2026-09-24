package org.handshake.resolver.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.handshake.resolver.HnsApplication
import org.handshake.resolver.R
import org.handshake.resolver.dns.DnsRouter
import org.handshake.resolver.engine.HnsEngine
import org.handshake.resolver.engine.HnsdNativeEngine
import org.handshake.resolver.ui.MainActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

data class ResolverState(
    val isRunning: Boolean = false,
    val isSynced: Boolean = false,
    val progress: Float = 0.0f,
    val blockHeight: Int = 0,
    val engineName: String = ""
)

class HnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Decoupled engine instance (easily swappable for Node.js hsd if needed)
    private val engine: HnsEngine = HnsdNativeEngine()
    private var dnsRouter: DnsRouter? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        dnsRouter = DnsRouter(this, engine = engine, hnsPort = engine.defaultPort)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForegroundServiceWithNotification()
                startVpn()
                return START_STICKY
            }
            else -> {
                startForegroundServiceWithNotification()
                startVpn()
                return START_STICKY
            }
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notification = buildNotification("Initializing Handshake SPV resolver...", 0f, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                HnsApplication.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(HnsApplication.NOTIFICATION_ID, notification)
        }
    }

    private fun startVpn() {
        if (vpnInterface != null) {
            Log.d(TAG, "VPN already active")
            return
        }

        // Acquire wake lock during initial sync so sync finishes quickly
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hns:sync_lock").apply {
            acquire(30 * 60 * 1000L) // 30 mins max
        }

        // 1. Start Handshake native engine in internal app directory
        val hnsDataDir = File(filesDir, "hnsd")
        engine.start(hnsDataDir, engine.defaultPort)

        // 2. Establish DNS-only loopback VPN
        val builder = Builder()
            .setSession("Handshake Resolver")
            .setMtu(1500)
            .addAddress(VIRTUAL_CLIENT_IP, 30)
            .addDnsServer(VIRTUAL_DNS_IP)
            .addRoute(VIRTUAL_DNS_IP, 32) // ONLY route DNS queries into VPN tunnel!
            .setBlocking(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }

        _stateFlow.value = ResolverState(
            isRunning = true,
            isSynced = engine.isSynced(),
            progress = engine.getProgress(),
            blockHeight = engine.getHeight(),
            engineName = engine.engineName
        )

        serviceJob = serviceScope.launch {
            launch { runPacketLoop() }
            launch { runStatusMonitoringLoop() }
        }
    }

    private fun runPacketLoop() {
        val pfd = vpnInterface ?: return
        val inputStream = FileInputStream(pfd.fileDescriptor)
        val outputStream = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(32767)

        Log.i(TAG, "Packet forwarding loop started")

        while (serviceScope.isActive && vpnInterface != null) {
            try {
                val length = inputStream.read(buffer)
                if (length <= 0) continue

                // Check for IPv4 packet
                val version = (buffer[0].toInt() and 0xF0) shr 4
                if (version != 4) continue

                val protocol = buffer[9].toInt() and 0xFF
                if (protocol != 17) continue // UDP only

                val ihl = (buffer[0].toInt() and 0x0F) * 4
                if (length < ihl + 8) continue

                val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
                if (dstPort != 53) continue

                val srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
                val udpLen = ((buffer[ihl + 4].toInt() and 0xFF) shl 8) or (buffer[ihl + 5].toInt() and 0xFF)
                val dnsPayloadLen = udpLen - 8
                if (dnsPayloadLen <= 0 || ihl + 8 + dnsPayloadLen > length) continue

                val dnsQuery = ByteArray(dnsPayloadLen)
                System.arraycopy(buffer, ihl + 8, dnsQuery, 0, dnsPayloadLen)

                // Dispatch query in background thread
                serviceScope.launch {
                    val dnsResponse = dnsRouter?.routeQuery(dnsQuery)
                    if (dnsResponse != null && vpnInterface != null) {
                        sendDnsReply(outputStream, buffer, ihl, srcPort, dstPort, dnsResponse)
                    }
                }
            } catch (e: IOException) {
                if (!serviceScope.isActive) break
                Log.e(TAG, "Error in packet loop", e)
            }
        }
        Log.i(TAG, "Packet loop terminated")
    }

    private fun sendDnsReply(
        output: FileOutputStream,
        origPacket: ByteArray,
        ihl: Int,
        clientPort: Int,
        dnsPort: Int,
        dnsResponse: ByteArray
    ) {
        val totalLength = ihl + 8 + dnsResponse.size
        val reply = ByteArray(totalLength)

        // Copy original IP header
        System.arraycopy(origPacket, 0, reply, 0, ihl)

        // Swap IP addresses (source becomes virtual DNS, dest becomes client)
        // Original src: 12..15, dst: 16..19
        System.arraycopy(origPacket, 16, reply, 12, 4)
        System.arraycopy(origPacket, 12, reply, 16, 4)

        // Update Total Length in IP header
        reply[2] = ((totalLength shr 8) and 0xFF).toByte()
        reply[3] = (totalLength and 0xFF).toByte()

        // Clear and recalculate IP checksum
        reply[10] = 0
        reply[11] = 0
        val ipChecksum = computeIpChecksum(reply, 0, ihl)
        reply[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        reply[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header
        // Source Port = 53 (dnsPort)
        reply[ihl] = ((dnsPort shr 8) and 0xFF).toByte()
        reply[ihl + 1] = (dnsPort and 0xFF).toByte()
        // Dest Port = clientPort
        reply[ihl + 2] = ((clientPort shr 8) and 0xFF).toByte()
        reply[ihl + 3] = (clientPort and 0xFF).toByte()
        // UDP Length
        val udpLen = 8 + dnsResponse.size
        reply[ihl + 4] = ((udpLen shr 8) and 0xFF).toByte()
        reply[ihl + 5] = (udpLen and 0xFF).toByte()
        // UDP Checksum (0 is valid for IPv4 UDP)
        reply[ihl + 6] = 0
        reply[ihl + 7] = 0

        // Copy DNS response payload
        System.arraycopy(dnsResponse, 0, reply, ihl + 8, dnsResponse.size)

        synchronized(output) {
            try {
                output.write(reply)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write DNS reply to TUN: ${e.message}")
            }
        }
    }

    private fun computeIpChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            if (sum > 0xFFFF) {
                sum = (sum and 0xFFFF) + 1
            }
            i += 2
        }
        return sum.inv() and 0xFFFF
    }

    private suspend fun runStatusMonitoringLoop() {
        var wasSynced = false
        while (serviceScope.isActive) {
            val progress = engine.getProgress()
            val height = engine.getHeight()
            val synced = engine.isSynced()

            if (synced && !wasSynced) {
                wasSynced = true
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
            }

            _stateFlow.value = ResolverState(
                isRunning = true,
                isSynced = synced,
                progress = progress,
                blockHeight = height,
                engineName = engine.engineName
            )

            val statusText = if (synced) {
                "Handshake Active • Block #$height • Synced"
            } else {
                val pct = (progress * 100).toInt().coerceIn(0, 100)
                "Syncing chain headers: $pct% (Block #$height)"
            }

            val notification = buildNotification(statusText, progress, height)
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(HnsApplication.NOTIFICATION_ID, notification)

            delay(1000)
        }
    }

    private fun buildNotification(statusText: String, progress: Float, height: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, HnsVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pct = (progress * 100).toInt().coerceIn(0, 100)

        val builder = NotificationCompat.Builder(this, HnsApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dns)
            .setContentTitle("Handshake HNS Resolver")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpen)
            .addAction(R.drawable.ic_stop, "Stop", pendingStop)

        if (!engine.isSynced() && progress > 0f) {
            builder.setProgress(100, pct, false)
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping HNS VPN Service")
        serviceJob?.cancel()
        serviceScope.cancel()

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        engine.stop()

        wakeLock?.let {
            if (it.isHeld) it.release()
        }

        _stateFlow.value = ResolverState(isRunning = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.handshake.resolver.START"
        const val ACTION_STOP = "org.handshake.resolver.STOP"

        const val VIRTUAL_DNS_IP = "10.254.1.1"
        const val VIRTUAL_CLIENT_IP = "10.254.1.2"
        private const val TAG = "HnsVpnService"

        private val _stateFlow = MutableStateFlow(ResolverState())
        val stateFlow = _stateFlow.asStateFlow()
    }
}
