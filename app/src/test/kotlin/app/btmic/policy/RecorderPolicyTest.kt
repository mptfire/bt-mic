package app.btmic.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pitfall 5 / recipe 7: the fallback happens at most once per recording and
 * is never re-armed; the user's own switch always works.
 */
class RecorderPolicyTest {
    private val p = RecorderPolicy

    @Test
    fun happyBtPath() {
        var s = p.State()
        val (s1, a1) = p.reduce(s, p.Event.START)
        assertEquals(listOf(p.Action.OPEN_BT), a1)
        val (s2, a2) = p.reduce(s1, p.Event.ROUTE_OK)
        assertEquals(listOf(p.Action.START_PRIMER), a2)
        val (s3, _) = p.reduce(s2, p.Event.FIRST_SOUND)
        assertEquals(p.Phase.RECORDING, s3.phase)
        val (s4, a4) = p.reduce(s3, p.Event.USER_STOP)
        assertEquals(p.Phase.SAVED, s4.phase)
        assertEquals(listOf(p.Action.SAVE), a4)
    }

    @Test
    fun fallbackFiresOnceThenStaysOnPhone() {
        var s = p.State()
        s = p.reduce(s, p.Event.START).first
        s = p.reduce(s, p.Event.ROUTE_OK).first
        // BT dies after first sound -> fallback with cue + open phone
        val (sf, af) = p.reduce(s, p.Event.BT_READ_ERROR)
        assertTrue(sf.fallbackUsed)
        assertEquals(p.Phase.FALLBACK_PENDING, sf.phase)
        assertEquals(
            listOf(p.Action.PLAY_FALLBACK_CUE, p.Action.OPEN_PHONE), af)
        // phone delivers sound
        val (sp, _) = p.reduce(sf, p.Event.PHONE_FIRST_SOUND)
        assertEquals(p.Phase.PHONE, sp.phase)
        // phone mic also dies: NO second fallback, no crash, just state hold
        val (s2, a2) = p.reduce(sp, p.Event.BT_READ_ERROR)
        assertTrue(a2.isEmpty())
        assertEquals(p.Phase.PHONE, s2.phase)
    }

    @Test
    fun fallbackWhenBtNeverProducesSound() {
        var s = p.State()
        s = p.reduce(s, p.Event.START).first
        s = p.reduce(s, p.Event.ROUTE_OK).first
        val (sf, af) = p.reduce(s, p.Event.ROUTE_FAILED)
        assertEquals(p.Phase.FALLBACK_PENDING, sf.phase)
        assertEquals(listOf(p.Action.PLAY_FALLBACK_CUE, p.Action.OPEN_PHONE), af)
    }

    @Test
    fun idleIgnoresStrayEvents() {
        val (s, a) = p.reduce(p.State(), p.Event.FIRST_SOUND)
        assertEquals(p.Phase.IDLE, s.phase)
        assertTrue(a.isEmpty())
    }

    @Test
    fun stopIsIdempotent() {
        var s = p.State(RecorderPolicy.Phase.SAVED)
        val (s2, a2) = p.reduce(s, p.Event.USER_STOP)
        assertEquals(p.Phase.SAVED, s2.phase)
        assertTrue(a2.isEmpty())
        assertFalse(s2.fallbackUsed)
    }
}
