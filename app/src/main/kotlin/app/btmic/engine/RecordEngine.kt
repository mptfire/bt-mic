package app.btmic.engine

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import app.btmic.audio.CuePlayer
import app.btmic.audio.BtRoute
import app.btmic.audio.InputPicker
import app.btmic.audio.Primer
import app.btmic.audio.Recorder
import app.btmic.audio.RouteVerifier
import app.btmic.policy.RecorderPolicy
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * SKILL.md recipe 7 (orchestration + one-shot fallback) driven by the pure
 * RecorderPolicy state machine. Captured PCM is kept across the fallback.
 */
class RecordEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sink: PcmSink,
) {
    interface PcmSink {
        fun open()
        fun write(pcm: ShortArray)
        fun close()
    }

    sealed interface Ui {
        data object Routing : Ui
        data object WaitingForBt : Ui
        data object RecordingBt : Ui
        data object RecordingPhone : Ui
        data class Saved(val bytes: Int) : Ui
        data class Failed(val why: String) : Ui
    }

    private val _ui = MutableStateFlow<Ui>(Ui.Routing)
    val ui: StateFlow<Ui> = _ui

    /** Invoked from the engine scope after the sink is closed and saved. */
    var onSaved: (() -> Unit)? = null

    private var job: Job? = null
    @Volatile private var stopping = false

    fun start() {
        if (job?.isActive == true) return
        stopping = false
        var policy = RecorderPolicy.State()
        var route: BtRoute? = null
        var primer: Primer? = null
        var rec: Recorder? = null
        var verifier: RouteVerifier? = null
        val cues = CuePlayer(context)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        fun apply(e: RecorderPolicy.Event) {
            val (s, actions) = RecorderPolicy.reduce(policy, e)
            policy = s
            actions.forEach { act ->
                when (act) {
                    RecorderPolicy.Action.START_PRIMER ->
                        primer?.start() // also satisfies some HALs (pitfall 6)
                    RecorderPolicy.Action.PLAY_FALLBACK_CUE ->
                        cues.play(CuePlayer.Cue.FALLBACK)
                    else -> {}
                }
            }
        }

        fun openPhone() {
            rec?.stopAndRelease()
            verifier?.release()
            val ar = Recorder.open(null)
            val r = Recorder(ar)
            r.onFirstSound = { apply(RecorderPolicy.Event.PHONE_FIRST_SOUND); _ui.value = Ui.RecordingPhone }
            r.onFailure = { msg -> _ui.value = Ui.Failed("phone mic: $msg") }
            verifier = RouteVerifier(ar, am)
            r.start()
            rec = r
        }

        fun openBt(input: AudioDeviceInfo?) {
            val ar = Recorder.open(input)
            val r = Recorder(ar)
            r.onFirstSound = { apply(RecorderPolicy.Event.FIRST_SOUND); _ui.value = Ui.RecordingBt }
            r.onFailure = { msg ->
                if (!stopping) {
                    apply(RecorderPolicy.Event.BT_READ_ERROR)
                    if (policy.fallbackUsed) openPhone()
                    else _ui.value = Ui.Failed(msg)
                }
            }
            verifier = RouteVerifier(ar, am)
            verifier?.onRouteChanged = { type ->
                // recipe 9: if routedDevice is not Bluetooth, say so
                if (!app.btmic.policy.DevicePolicy.isBt(type) &&
                    policy.phase == RecorderPolicy.Phase.RECORDING) {
                    // the HAL can reroute under us; surface it, do not lie
                    Log.w("BtRec", "routed off Bluetooth (type=$type)")
                }
            }
            r.start()
            rec = r
        }

        job = scope.launch(Dispatchers.IO) {
            try {
                sink.open()
                apply(RecorderPolicy.Event.START)
                _ui.value = Ui.Routing
                val bt = BtRoute(am, Dispatchers.Main.asExecutor())
                val ok = bt.acquire()
                if (!ok) {
                    apply(RecorderPolicy.Event.ROUTE_FAILED)
                    openPhone()
                } else {
                    apply(RecorderPolicy.Event.ROUTE_OK)
                    _ui.value = Ui.WaitingForBt
                    route = bt
                    primer = Primer().also { it.start() }
                    openBt(InputPicker.findBtInput(am, bt.device))
                }
                // PCM pump: the recorder accumulates; we stream it to the sink.
                while (!stopping && policy.phase != RecorderPolicy.Phase.SAVED) {
                    val chunk = rec?.drain() ?: ShortArray(0)
                    if (chunk.isNotEmpty()) sink.write(chunk)
                    Thread.sleep(100)
                }
                if (!stopping) apply(RecorderPolicy.Event.USER_STOP)
            } catch (t: Throwable) {
                _ui.value = Ui.Failed(t.message ?: t.javaClass.simpleName)
            } finally {
                rec?.stopAndRelease()
                verifier?.release()
                primer?.release()
                route?.release()
                cues.stop()
                sink.close()
                onSaved?.invoke()
            }
        }
    }

    fun stop() {
        stopping = true
        job?.cancel()
        job = null
    }
}
