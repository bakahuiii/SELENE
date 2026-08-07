package io.github.bakahuiii.selene.context

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Restores the collectors and the paired sync process after a device reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (PairingManager.isPaired(context)) SyncthingService.start(context)
        if (!AutoCollectionSettings.isEnabled(context) || !ContextOutput.hasOutputTarget(context)) return
        AutoCollectionScheduler.start(context)
        val backgroundAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (AutoCollectionSettings.backgroundLocationEnabled(context) &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            backgroundAllowed
        ) {
            MovementTrackingService.start(context)
        }
    }
}
