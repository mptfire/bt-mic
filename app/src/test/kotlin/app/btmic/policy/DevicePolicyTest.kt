package app.btmic.policy

import app.btmic.policy.DevicePolicy.Cand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SKILL.md "Choosing between several devices": the car + headphones case. */
class DevicePolicyTest {
    private val p = DevicePolicy
    private val car = Cand(p.TYPE_BLUETOOTH_SCO, "CAR:MAC")
    private val phones = Cand(p.TYPE_BLE_HEADSET, "HP:MAC")
    private val aids = Cand(33, "AID:MAC")

    @Test
    fun prefersDeviceMediaPlaysTo() {
        assertEquals(phones, p.pick(listOf(car, phones), setOf("hp:mac")))
    }

    @Test
    fun mediaMatchIsCaseInsensitive() {
        assertEquals(aids, p.pick(listOf(car, aids), setOf("aid:mac")))
    }

    @Test
    fun leBeatsScoWhenNoMediaHint() {
        assertEquals(phones, p.pick(listOf(car, phones), emptySet()))
    }

    @Test
    fun scoAloneIsPicked() {
        assertEquals(car, p.pick(listOf(car), emptySet()))
    }

    @Test
    fun nothingBluetoothIsNull() {
        assertNull(p.pick(listOf(Cand(15, "builtin")), emptySet()))
        assertNull(p.pick(emptyList(), emptySet()))
    }

    @Test
    fun type33CountsAsLe() {
        assertTrue(p.isLe(33))
        assertTrue(p.isLe(26))
        assertFalse(p.isLe(p.TYPE_BLUETOOTH_SCO))
        assertTrue(p.inputMatches(p.TYPE_BLE_HEARING_AID, p.TYPE_BLE_HEADSET))
    }

    @Test
    fun inputRankPrefersAddressThenFamily() {
        val route = Cand(p.TYPE_BLE_HEADSET, "AA:1")
        val same = Cand(p.TYPE_BLE_HEADSET, "AA:1")
        val otherAddr = Cand(p.TYPE_BLE_HEADSET, "BB:2")
        val sco = Cand(p.TYPE_BLUETOOTH_SCO, "AA:1")
        assertTrue(p.inputRank(route, same) < p.inputRank(route, otherAddr))
        assertTrue(p.inputRank(route, otherAddr) < p.inputRank(route, sco))
    }
}
