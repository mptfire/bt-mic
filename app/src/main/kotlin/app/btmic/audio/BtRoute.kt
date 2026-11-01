package app.btmic.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor

/**
 * SKILL.md recipe 2: make the headset the communication device, wait for the
 * callback (it fires before the LE link is reconfigured), then settle.
 */
class BtRoute(private val am: AudioManager, private val exec: Executor) {

    var device: AudioDeviceInfo? = null
        private set

    private fun pick(): AudioDeviceInfo? {
        val cands = am.availableCommunicationDevices.map {
            DevicePolicyBridge.cand(it)
        }
        val media = if (Build.VERSION.SDK_INT >= 33) {
            am.getAudioDevicesForAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
            ).mapNotNull { it.address?.uppercase() }.toSet()
        } else emptySet()
        val chosen = app.btmic.policy.DevicePolicy.pick(cands, media) ?: return null
        return am.availableCommunicationDevices.firstOrNull {
            it.type == chosen.type && it.address == chosen.address
        }
    }

    /** True when the headset is the communication device and has settled. */
    suspend fun acquire(timeoutMs: Long = 3000, settleMs: Long = 500): Boolean {
        val target = pick() ?: return false
        val reported = CompletableDeferred<Unit>()
        val l = AudioManager.OnCommunicationDeviceChangedListener { d ->
            if (d?.id == target.id) reported.complete(Unit)
        }
        am.addOnCommunicationDeviceChangedListener(exec, l)
        try {
            if (am.communicationDevice?.id != target.id) {
                if (!am.setCommunicationDevice(target)) return false
                withTimeoutOrNull(timeoutMs) { reported.await() } ?: return false
            }
            delay(settleMs) // the device is reported before the LE link is reconfigured
            device = target
            return true
        } finally {
            am.removeOnCommunicationDeviceChangedListener(l)
        }
    }

    fun release() {
        am.clearCommunicationDevice()
        device = null
    }
}

/** Keeps android.media types out of the pure policy module. */
internal object DevicePolicyBridge {
    fun cand(d: AudioDeviceInfo) =
        app.btmic.policy.DevicePolicy.Cand(d.type, d.address ?: "")
}
