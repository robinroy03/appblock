package com.robin.appblock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the blocker back after a reboot, and after the app is updated in
 * place (an install over the old version kills the running service). Both
 * broadcasts are on Android's short list of moments a foreground service may
 * be started from the background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                BlockerService.start(context)
        }
    }
}
