package app.btmic.audio

import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * SKILL.md recipe 9: verify where you actually record. The UI must show the
 * truth; logs carry the device TYPE only — productName is user-facing data
 * and never goes into shared bug reports (SKILL.md:280).
 */
class RouteVerifier(
    private val rec: AudioRecord,
    private val am: AudioManager,
) {
    var onRouteChanged: ((Int) -> Unit)? = null

    private val routingListener = object : AudioRecord.OnRoutingChangedListener {
        override fun onRoutingChanged(r: AudioRecord?) {
            val type = r?.routedDevice?.type ?: -1
            Log.i(TAG, "routing changed -> type=$type")
            onRouteChanged?.invoke(type)
        }
    }
    private val recCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(cfgs: List<AudioRecordingConfiguration>) {
            cfgs.forEach {
                Log.i(TAG, "src=${it.clientAudioSource} silenced=${it.isClientSilenced}")
            }
        }
    }

    init {
        val type = rec.routedDevice?.type ?: -1
        Log.i(TAG, "routed type=$type")
        rec.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))
        am.registerAudioRecordingCallback(recCallback, Handler(Looper.getMainLooper()))
        onRouteChanged?.invoke(type)
    }

    fun release() {
        rec.removeOnRoutingChangedListener(routingListener)
        am.unregisterAudioRecordingCallback(recCallback)
    }

    companion object { private const val TAG = "BtRec" }
}
