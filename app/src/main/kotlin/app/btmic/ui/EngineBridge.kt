package app.btmic.ui

import app.btmic.engine.RecordEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bridge between the service-owned engine and the activity's mirror.
 * null = idle (no recording running anywhere in the process).
 */
object EngineBridge {

    private val _ui = MutableStateFlow<RecordEngine.Ui?>(null)
    val ui: StateFlow<RecordEngine.Ui?> = _ui

    fun publish(u: RecordEngine.Ui) { _ui.value = u }
    fun idle() { _ui.value = null }

    fun recording(): Boolean = when (val u = _ui.value) {
        is RecordEngine.Ui.Routing, is RecordEngine.Ui.WaitingForBt,
        is RecordEngine.Ui.RecordingBt, is RecordEngine.Ui.RecordingPhone -> true
        else -> false
    }
}
