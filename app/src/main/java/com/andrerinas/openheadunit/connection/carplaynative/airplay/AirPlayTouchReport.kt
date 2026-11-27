package com.andrerinas.openheadunit.connection.carplaynative.airplay

/**
 * The HID reports of the touch and knob devices that /info announces. A touch report has two
 * finger slots of 6 bytes: slot, down flag, x and y as little-endian 16-bit values.
 * Follows LIVI (https://github.com/f-io/LIVI), crate livi-cp, `hid.rs`, with DiPlay's rule that a contact fills the slot of its id.
 */
object AirPlayTouchReport {
    private const val BYTES_PER_FINGER = 6

    /** [x] and [y] are in display pixels, as the touch descriptor's maximum. */
    class Contact(val id: Int, val x: Double, val y: Double, val down: Boolean)

    class Knob(
        val select: Boolean = false,
        val home: Boolean = false,
        val back: Boolean = false,
        val x: Double = 0.0,
        val y: Double = 0.0,
        val wheel: Double = 0.0,
    )

    /** A slot with no contact reads as up at 0,0. A contact outside slots 0 and 1 is ignored. */
    fun touch(contacts: List<Contact>): ByteArray {
        val report = ByteArray(BYTES_PER_FINGER * HidDevices.TOUCH_CONTACTS)
        for (slot in 0 until HidDevices.TOUCH_CONTACTS) {
            val offset = slot * BYTES_PER_FINGER
            report[offset] = slot.toByte()
            val contact = contacts.firstOrNull { it.id == slot } ?: continue
            report[offset + 1] = if (contact.down) 1 else 0
            putU16(report, offset + 2, axis(contact.x))
            putU16(report, offset + 4, axis(contact.y))
        }
        return report
    }

    /**
     * Maps contacts from a picture of [fromWidth] by [fromHeight] to the display /info declared and clamps them to it,
     * because the touch descriptor ends at that size.
     */
    fun toDisplay(contacts: List<Contact>, fromWidth: Int, fromHeight: Int, toWidth: Int, toHeight: Int): List<Contact> {
        if (fromWidth <= 0 || fromHeight <= 0 || toWidth <= 0 || toHeight <= 0) return emptyList()
        fun scale(value: Double, from: Int, to: Int): Double =
            if (value.isNaN()) 0.0 else (value * to / from).coerceIn(0.0, to.toDouble())
        return contacts.map { Contact(it.id, scale(it.x, fromWidth, toWidth), scale(it.y, fromHeight, toHeight), it.down) }
    }

    /** Buttons in bits 0 to 2, then x, y and the wheel as signed bytes clamped to -127..127. */
    fun knob(state: Knob): ByteArray {
        val buttons = (if (state.select) 1 else 0) or (if (state.home) 2 else 0) or (if (state.back) 4 else 0)
        return byteArrayOf(buttons.toByte(), knobAxis(state.x), knobAxis(state.y), knobAxis(state.wheel))
    }

    private fun axis(value: Double): Int =
        if (value.isNaN()) 0 else roundHalfUp(value).coerceIn(0.0, 65535.0).toInt()

    private fun knobAxis(value: Double): Byte =
        if (value.isNaN()) 0 else roundHalfUp(value).coerceIn(-127.0, 127.0).toInt().toByte()

    private fun roundHalfUp(x: Double): Double {
        val floor = Math.floor(x)
        return if (x - floor >= 0.5) floor + 1.0 else floor
    }

    private fun putU16(target: ByteArray, at: Int, value: Int) {
        target[at] = value.toByte()
        target[at + 1] = (value ushr 8).toByte()
    }
}
