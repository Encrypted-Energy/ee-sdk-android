package com.encryptedenergy.sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Runtime permissions for the FCA6 scan loop. BLUETOOTH_SCAN deliberately
 * does NOT assert neverForLocation (plan §14): we attach location to relayed
 * packets, so we declare location use honestly. That also means
 * ACCESS_FINE_LOCATION is required on every API level, and on 31+ it must be
 * granted alongside BLUETOOTH_SCAN for scan results to flow.
 *
 * The SDK NEVER requests these (plan §3: no prompts, ever) — it only checks.
 * The host app owns the permission UX; [EEGateway.start] without the grants
 * parks in [EEState.AWAITING_PERMISSIONS] until they appear.
 *
 * Delta from gateway-android: `Context.checkSelfPermission` instead of
 * androidx ContextCompat — same framework call on minSdk 26+, and it keeps
 * the AAR's runtime dependency count at zero (C1 contract).
 */
internal object ScanPermissions {

    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) else arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

    fun granted(context: Context): Boolean = required().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}
