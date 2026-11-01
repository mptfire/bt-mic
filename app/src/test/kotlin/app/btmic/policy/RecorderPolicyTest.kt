package app.btmic.policy

import app.btmic.policy.RecorderPolicy.Action
import app.btmic.policy.RecorderPolicy.Event
import app.btmic.policy.RecorderPolicy.Phase
import app.btmic.policy.RecorderPolicy.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pitfall 5 / recipe 7: the fallback happens at most once per recording and
 * is never re-armed; the user's own switch always works.
 */
class RecorderPolicyTest {

    private fun step(s: State, e: Event) = RecorderPolicy.reduce(s, e)

    @Test
    fun happyBtPath() {
        var (s, a) = step(State(), Event.START)
        assertEquals(listOf(Action.OPEN_BT), a)
        val r2 = step(s, Event.ROUTE_OK); s = r2.first
        assertEquals(listOf(Action.START_PRIMER), a.let { r2.second })
        assertEquals(Phase.WAITING, s.phase)
        val r3 = step(s, Event.FIRST_SOUND); s = r3.first
        assertEquals(Phase.RECORDING, s.phase)
        val r4 = step(s, Event.USER_STOP)
        assertEquals(Phase.SAVED, r4.first.phase)
        assertEquals(listOf(Action.SAVE), r4.second)
    }

    @Test
    fun fallbackFiresOnceThenStaysOnPhone() {
        var s = State()
        s = step(s, Event.START).first
        s = step(s, Event.ROUTE_OK).first
        // BT dies after first sound -> fallback with cue + open phone
        val (sf, af) = step(s, Event.BT_READ_ERROR)
        assertTrue(sf.fallbackUsed)
        assertEquals(Phase.FALLBACK_PENDING, sf.phase)
        assertEquals(
            listOf(Action.PLAY_FALLBACK_CUE, Action.OPEN_PHONE), af)
        // phone delivers sound
        val (sp, _) = step(sf, Event.PHONE_FIRST_SOUND)
        assertEquals(Phase.PHONE, sp.phase)
        // phone mic read error is NOT a Bluetooth fallback: hold, no crash
        val (s2, a2) = step(sp, Event.BT_READ_ERROR)
        assertTrue(a2.isEmpty())
        assertEquals(Phase.PHONE, s2.phase)
    }

    @Test
    fun fallbackWhenBtNeverProducesSound() {
        var s = State()
        s = step(s, Event.START).first
        s = step(s, Event.ROUTE_OK).first
        val (sf, af) = step(s, Event.ROUTE_FAILED)
        assertEquals(Phase.FALLBACK_PENDING, sf.phase)
        assertEquals(listOf(Action.PLAY_FALLBACK_CUE, Action.OPEN_PHONE), af)
    }

    @Test
    fun idleIgnoresStrayEvents() {
        val (s, a) = step(State(), Event.FIRST_SOUND)
        assertEquals(Phase.IDLE, s.phase)
        assertTrue(a.isEmpty())
    }

    @Test
    fun stopIsIdempotent() {
        val (s2, a2) = step(State(Phase.SAVED), Event.USER_STOP)
        assertEquals(Phase.SAVED, s2.phase)
        assertTrue(a2.isEmpty())
        assertFalse(s2.fallbackUsed)
    }
}
