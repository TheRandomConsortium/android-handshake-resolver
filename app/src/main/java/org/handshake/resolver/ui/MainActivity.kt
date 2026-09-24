package org.handshake.resolver.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.handshake.resolver.R
import org.handshake.resolver.databinding.ActivityMainBinding
import org.handshake.resolver.dns.Punycode
import org.handshake.resolver.vpn.HnsVpnService
import org.handshake.resolver.vpn.ResolverState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN permission required for DNS interception", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional permission granted callback */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            try {
                val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
                val bottomInset = if (ime.bottom > systemBars.bottom) ime.bottom else systemBars.bottom
                view.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomInset)

                if (ime.bottom > systemBars.bottom && binding.etTestDomain.hasFocus()) {
                    binding.rootScrollView.post {
                        binding.rootScrollView.fullScroll(View.FOCUS_DOWN)
                    }
                }
            } catch (_: Throwable) {
                // Fail-safe fallback on older Android versions or unsupported OEM frameworks
            }
            windowInsets
        }

        setupListeners()
        observeState()
        checkNotificationPermission()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupListeners() {
        binding.btnToggleVpn.setOnClickListener {
            val currentState = HnsVpnService.stateFlow.value
            if (currentState.isRunning) {
                stopVpnService()
            } else {
                prepareAndStartVpn()
            }
        }

        binding.btnTestResolve.setOnClickListener {
            val domain = binding.etTestDomain.text.toString().trim()
            if (domain.isNotEmpty()) {
                hideKeyboard()
                testResolveDomain(domain)
            }
        }

        binding.etTestDomain.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                val domain = binding.etTestDomain.text.toString().trim()
                if (domain.isNotEmpty()) {
                    hideKeyboard()
                    testResolveDomain(domain)
                }
                true
            } else {
                false
            }
        }

        binding.etTestDomain.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.rootScrollView.postDelayed({
                    binding.rootScrollView.fullScroll(View.FOCUS_DOWN)
                }, 200)
            }
        }

        binding.etTestDomain.setOnClickListener {
            binding.rootScrollView.postDelayed({
                binding.rootScrollView.fullScroll(View.FOCUS_DOWN)
            }, 200)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etTestDomain.windowToken, 0)
        binding.etTestDomain.clearFocus()
    }

    private fun observeState() {
        lifecycleScope.launch {
            HnsVpnService.stateFlow.collect { state ->
                updateUi(state)
            }
        }
    }

    private fun updateUi(state: ResolverState) {
        if (!state.isRunning) {
            // Disconnected
            binding.badgeStatus.text = getString(R.string.status_disconnected)
            binding.badgeStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvStatusTitle.text = getString(R.string.status_disconnected)
            binding.tvStatusSubtitle.text = "Start service to activate native Handshake resolution."
            binding.bannerSyncWarning.visibility = View.GONE
            binding.layoutProgress.visibility = View.GONE

            binding.btnToggleVpn.text = getString(R.string.btn_start)
            binding.btnToggleVpn.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent_cyan)
            )
            binding.btnToggleVpn.setTextColor(Color.parseColor("#0B0F19"))
        } else {
            // Running
            binding.btnToggleVpn.text = getString(R.string.btn_stop)
            binding.btnToggleVpn.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent_red)
            )
            binding.btnToggleVpn.setTextColor(Color.WHITE)

            if (!state.isSynced) {
                // Syncing
                val pct = (state.progress * 100).toInt().coerceIn(0, 100)
                binding.badgeStatus.text = "SYNCING ($pct%)"
                binding.badgeStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))

                binding.tvStatusTitle.text = getString(R.string.status_syncing)
                binding.tvStatusSubtitle.text = "Verifying SPV block headers from Handshake peers."

                binding.bannerSyncWarning.visibility = View.VISIBLE
                binding.layoutProgress.visibility = View.VISIBLE

                binding.tvSyncPercent.text = "Sync Progress: $pct%"
                binding.tvBlockHeight.text = if (state.blockHeight > 0) "Block #${state.blockHeight}" else "Connecting..."
                binding.progressBarSync.progress = (state.progress * 1000).toInt().coerceIn(0, 1000)
            } else {
                // Fully Synced
                binding.badgeStatus.text = "ACTIVE"
                binding.badgeStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))

                binding.tvStatusTitle.text = getString(R.string.status_active)
                binding.tvStatusSubtitle.text = "Handshake domains resolve natively in browsers and all apps."

                binding.bannerSyncWarning.visibility = View.GONE
                binding.layoutProgress.visibility = View.VISIBLE

                binding.tvSyncPercent.text = "Chain: 100% Synced"
                binding.tvBlockHeight.text = "Block #${state.blockHeight}"
                binding.progressBarSync.progress = 1000
                binding.progressBarSync.progressTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.accent_green)
                )
            }
        }
    }

    private fun prepareAndStartVpn() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPrepareLauncher.launch(vpnIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, HnsVpnService::class.java).apply {
            action = HnsVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, HnsVpnService::class.java).apply {
            action = HnsVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun testResolveDomain(domain: String) {
        val asciiDomain = Punycode.toPunycode(domain)
        binding.tvTestResult.visibility = View.VISIBLE
        val displayInfo = if (asciiDomain != domain.lowercase()) "$domain ($asciiDomain)" else domain
        binding.tvTestResult.text = "Querying Handshake resolver for '$displayInfo'..."

        lifecycleScope.launch(Dispatchers.IO) {
            val resultText = try {
                val queryPacket = buildSimpleDnsQuery(asciiDomain)
                val socket = DatagramSocket()
                socket.soTimeout = 2000

                val sendPacket = DatagramPacket(
                    queryPacket, queryPacket.size,
                    InetAddress.getByName("127.0.0.1"), 5349
                )
                socket.send(sendPacket)

                val buffer = ByteArray(1024)
                val receivePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(receivePacket)
                socket.close()

                val rcode = receivePacket.data[3].toInt() and 0x0F
                val anCount = ((receivePacket.data[6].toInt() and 0xFF) shl 8) or
                        (receivePacket.data[7].toInt() and 0xFF)

                if (rcode == 0 && anCount > 0) {
                    "✓ Resolved via Handshake: $anCount answer record(s) received"
                } else if (rcode == 0) {
                    "Handshake root referral returned (NS records present)"
                } else {
                    "Handshake response: RCODE $rcode (NXDOMAIN or not found)"
                }
            } catch (e: Exception) {
                // If native resolver is not yet bound or timed out, attempt standard system resolution
                try {
                    val addresses = InetAddress.getAllByName(domain)
                    "Resolved via DNS loopback: " + addresses.joinToString { it.hostAddress ?: "" }
                } catch (ex: Exception) {
                    "Resolution query timed out: ${e.message}"
                }
            }

            withContext(Dispatchers.Main) {
                binding.tvTestResult.text = resultText
            }
        }
    }

    private fun buildSimpleDnsQuery(domain: String): ByteArray {
        val labels = domain.trimEnd('.').split('.')
        val output = mutableListOf<Byte>()

        // ID
        output.add(0x12)
        output.add(0x34)
        // Flags (Standard query, Recursion Desired)
        output.add(0x01)
        output.add(0x00)
        // QDCOUNT = 1
        output.add(0x00)
        output.add(0x01)
        // ANCOUNT, NSCOUNT, ARCOUNT = 0
        repeat(6) { output.add(0x00) }

        // Question name
        for (label in labels) {
            output.add(label.length.toByte())
            for (c in label.toCharArray()) {
                output.add(c.code.toByte())
            }
        }
        output.add(0x00) // End of name

        // QTYPE = A (1)
        output.add(0x00)
        output.add(0x01)
        // QCLASS = IN (1)
        output.add(0x00)
        output.add(0x01)

        return output.toByteArray()
    }
}
