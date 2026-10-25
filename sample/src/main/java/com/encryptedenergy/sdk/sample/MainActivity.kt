package com.encryptedenergy.sdk.sample

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.encryptedenergy.sdk.EEConfig
import com.encryptedenergy.sdk.EEGateway
import com.encryptedenergy.sdk.EEStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-screen test harness (plan §5 C7, minimal): paste a gateway token
 * (provisioned via the Org API — the C8 curl), Start/Stop, live status.
 * Deliberately not a product: plain Activity, programmatic views, zero
 * resources, zero dependencies beyond the SDK. The permission flow is the
 * part a partner copies — the SDK never prompts (plan §3), so the HOST asks,
 * and this is what asking looks like.
 */
class MainActivity : Activity() {

    private lateinit var tokenField: EditText
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        tokenField = EditText(this).apply {
            hint = "ee_live_… gateway token"
            setText(prefs().getString(KEY_TOKEN, "") ?: "")
        }
        val startButton = Button(this).apply {
            text = "Start gateway"
            setOnClickListener { onStartClicked() }
        }
        val stopButton = Button(this).apply {
            text = "Stop gateway"
            setOnClickListener {
                stopService(Intent(this@MainActivity, GatewayService::class.java))
            }
        }
        statusView = TextView(this).apply {
            text = "not started"
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(tokenField)
                addView(startButton)
                addView(stopButton)
                addView(statusView)
            }
        )
    }

    override fun onStart() {
        super.onStart()
        // Callbacks land on the main thread (SDK contract), so touching the
        // view here is safe. Cleared in onStop so the listener never holds a
        // dead Activity.
        EEGateway.setListener { status -> render(status) }
        render(EEGateway.status())
    }

    override fun onStop() {
        EEGateway.setListener(null)
        super.onStop()
    }

    private fun onStartClicked() {
        val token = tokenField.text.toString().trim()
        if (token.isEmpty()) {
            statusView.text = "paste a gateway token first"
            return
        }
        prefs().edit().putString(KEY_TOKEN, token).apply()
        EEGateway.configure(applicationContext, EEConfig.GatewayToken(token))

        val missing = missingPermissions()
        if (missing.isEmpty()) {
            startGatewayService()
        } else {
            // The host asks; the SDK only waits. Denials don't break
            // anything — the SDK parks in AWAITING_PERMISSIONS, except the
            // FGS itself, which needs the location grant on API 34+.
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode != PERMISSION_REQUEST) return
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            // Coarse alone satisfies the FGS location type (the API 34
            // prerequisite), so "approximate location" still starts the
            // service. Scanning itself needs FINE + BLUETOOTH_SCAN — until
            // those arrive the SDK sits in AWAITING_PERMISSIONS, which is
            // exactly the dormancy behavior worth seeing in the harness.
            startGatewayService()
        } else {
            statusView.text =
                "location permission is required to run the gateway service"
        }
    }

    private fun startGatewayService() {
        startForegroundService(Intent(this, GatewayService::class.java))
    }

    private fun missingPermissions(): List<String> {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_SCAN
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        return wanted.filterNot(::granted)
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun render(status: EEStatus) {
        val heartbeat = status.lastHeartbeatAt?.let {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it))
        } ?: "—"
        statusView.text = buildString {
            append("state: ").append(status.state).append('\n')
            append("heard: ").append(status.pingsHeard)
            append("   forwarded: ").append(status.packetsForwarded)
            append("   queued: ").append(status.queueDepth).append('\n')
            append("gateway: ").append(status.gatewayPublicId ?: "—").append('\n')
            append("last heartbeat: ").append(heartbeat)
        }
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        const val PREFS = "sample"
        const val KEY_TOKEN = "gateway_token"
        private const val PERMISSION_REQUEST = 1
    }
}
