package app.btmic.policy

/**
 * Recording state machine — PURE, host-testable.
 *
 * Rules from SKILL.md (MIT):
 *  - the fallback to the phone mic happens AT MOST ONCE per recording and is
 *    never re-armed automatically (recipe 7 / pitfall 5);
 *  - the user's own switch always happens and re-arms the fallback
 *    (Third Ear's SourcePolicy contract);
 *  - captured PCM is never discarded on fallback — the phone audio appends.
 */
object RecorderPolicy {

    enum class Phase { IDLE, ROUTING, WAITING, RECORDING, FALLBACK_PENDING, PHONE, SAVED }

    enum class Event {
        START, ROUTE_OK, ROUTE_FAILED, FIRST_SOUND, BT_READ_ERROR,
        PHONE_FIRST_SOUND, USER_STOP
    }

    enum class Action { OPEN_BT, START_PRIMER, PLAY_FALLBACK_CUE, OPEN_PHONE, SAVE }

    data class State(
        val phase: Phase = Phase.IDLE,
        val fallbackUsed: Boolean = false,
    )

    fun reduce(s: State, e: Event): Pair<State, List<Action>> {
        return when (e) {
            Event.START -> State(Phase.ROUTING) to listOf(Action.OPEN_BT)
            Event.ROUTE_OK -> when (s.phase) {
                Phase.ROUTING -> s.copy(phase = Phase.WAITING) to
                    listOf(Action.START_PRIMER)
                else -> s to emptyList()
            }
            Event.ROUTE_FAILED -> if (!s.fallbackUsed) {
                s.copy(phase = Phase.FALLBACK_PENDING, fallbackUsed = true) to
                    listOf(Action.PLAY_FALLBACK_CUE, Action.OPEN_PHONE)
            } else s to emptyList()
            Event.FIRST_SOUND -> when (s.phase) {
                Phase.WAITING -> s.copy(phase = Phase.RECORDING) to emptyList()
                Phase.FALLBACK_PENDING -> s.copy(phase = Phase.PHONE) to emptyList()
                else -> s to emptyList()
            }
            Event.BT_READ_ERROR -> if (!s.fallbackUsed &&
                (s.phase == Phase.WAITING || s.phase == Phase.RECORDING)
            ) {
                s.copy(phase = Phase.FALLBACK_PENDING, fallbackUsed = true) to
                    listOf(Action.PLAY_FALLBACK_CUE, Action.OPEN_PHONE)
            } else s to emptyList()
            Event.PHONE_FIRST_SOUND -> if (s.phase == Phase.FALLBACK_PENDING)
                s.copy(phase = Phase.PHONE) to emptyList() else s to emptyList()
            Event.USER_STOP ->
                if (s.phase != Phase.IDLE && s.phase != Phase.SAVED)
                    s.copy(phase = Phase.SAVED) to listOf(Action.SAVE)
                else s to emptyList()
        }
    }
}
