package app.btmic.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * SKILL.md "Background recording": restart after reboot AND after app update
 * (MY_PACKAGE_REPLACED) — without it the service stays dead after an update.
 * On Android 14+ the service starts but cannot record until the user opens
 * the app once; MicService shows that state instead of failing silently.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (ok) MicService.channel(context) // service will start via autostart pref
        // Recording-from-boot is gated by Android 14+; we deliberately do not
        // start recording here. The service is started when the user re-opens.
    }
}
