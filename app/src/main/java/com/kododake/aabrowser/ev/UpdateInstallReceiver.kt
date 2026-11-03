/*
 * EV Insight Lab -Tube — modifications to AABrowser (GPL-3.0-or-later).
 * Added 2026-11-03: receives results from Android's package installer.
 */
package com.kododake.aabrowser.ev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android restarts the app process with the new version.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // User tapped Cancel.
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, "Update not installed. $detail".trim(), Toast.LENGTH_LONG).show()
            }
        }
    }
}
