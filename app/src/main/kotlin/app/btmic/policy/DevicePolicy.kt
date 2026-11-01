package app.btmic.policy

/**
 * Device selection policy — PURE, no Android imports, host-testable.
 *
 * Ported from SKILL.md (jooray/android-bluetooth-microphone @ b4de6e1, MIT)
 * sections "Routing helper" and "Choosing between several devices":
 * prefer the communication device whose address matches where media plays
 * (the headset the user is listening on — the car case), then LE Audio
 * (TYPE_BLE_HEADSET = 26, and Android 17's LE hearing-aid type 33, which is
 * not a public constant in SDK 36), then classic SCO (18).
 */
object DevicePolicy {
    const val TYPE_BLE_HEADSET = 26
    const val TYPE_BLE_HEARING_AID = 33 // Android 17 (API 37), flagged API
    const val TYPE_BLUETOOTH_SCO = 18

    data class Cand(val type: Int, val address: String)

    fun isLe(t: Int) = t == TYPE_BLE_HEADSET || t == TYPE_BLE_HEARING_AID
    fun isBt(t: Int) = isLe(t) || t == TYPE_BLUETOOTH_SCO

    /** The route to make the communication device. Never null on an empty list. */
    fun pick(cands: List<Cand>, mediaAddresses: Set<String>): Cand? {
        val bt = cands.filter { isBt(it.type) }
        if (bt.isEmpty()) return null
        val up = mediaAddresses.map { it.uppercase() }.toSet()
        return bt.firstOrNull { it.address.uppercase() in up }
            ?: bt.firstOrNull { isLe(it.type) }
            ?: bt.first()
    }

    /**
     * Preferred *input* for a chosen route: same family (LE routes accept both
     * LE types — a binaural set can expose output and input under different LE
     * types), exact type for SCO. Address is a soft preference (the input
     * address may legitimately differ), so this returns a ranked pair match.
     */
    fun inputMatches(routeType: Int, inputType: Int): Boolean =
        if (isLe(routeType)) isLe(inputType) else inputType == routeType

    fun inputRank(route: Cand, input: Cand): Int = when {
        input.address.uppercase() == route.address.uppercase() &&
            inputMatches(route.type, input.type) -> 0
        inputMatches(route.type, input.type) -> 1
        else -> 2
    }
}
