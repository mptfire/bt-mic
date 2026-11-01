package app.btmic.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

/** SKILL.md recipe 3: preferred input for the chosen route. */
object InputPicker {

    fun findBtInput(am: AudioManager, route: AudioDeviceInfo?): AudioDeviceInfo? {
        if (route == null) return null
        val rc = app.btmic.policy.DevicePolicy.Cand(route.type, route.address ?: "")
        val ranked = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .map { app.btmic.policy.DevicePolicy.Cand(it.type, it.address ?: "") to it }
            .filter { app.btmic.policy.DevicePolicy.inputMatches(rc.type, it.first.type) }
            .sortedBy { app.btmic.policy.DevicePolicy.inputRank(rc, it.first) }
        return ranked.firstOrNull()?.second
    }

    fun isBluetoothInput(d: AudioDeviceInfo?): Boolean =
        d != null && app.btmic.policy.DevicePolicy.isBt(d.type)
}
