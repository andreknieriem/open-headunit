package com.andrerinas.openheadunit.connection.carkey

import android.content.Intent
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class CarKeyBroadcastReceiverKeyTest {

    // An unset int extra reads as its default, as a real Intent does.
    private fun newIntent(): Intent = mock(Intent::class.java) { inv ->
        if (inv.method.name == "getIntExtra") inv.arguments[1] else null
    }

    private fun intent(vararg ints: Pair<String, Int>, strings: Map<String, String> = emptyMap()): Intent {
        val i = newIntent()
        ints.forEach { (k, v) -> `when`(i.getIntExtra(k, -1)).thenReturn(v) }
        strings.forEach { (k, v) -> `when`(i.getStringExtra(k)).thenReturn(v) }
        return i
    }

    private fun withEvent(action: Int, repeat: Int, code: Int): Intent {
        val i = newIntent()
        val e = mock(KeyEvent::class.java)
        `when`(e.action).thenReturn(action)
        `when`(e.repeatCount).thenReturn(repeat)
        `when`(e.keyCode).thenReturn(code)
        `when`(i.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)).thenReturn(e)
        return i
    }

    private val media = "android.intent.action.MEDIA_BUTTON"

    @Test
    fun `a key event gives its key on down, repeat and up`() {
        val up = KeyEvent.KEYCODE_VOLUME_UP
        assertEquals(up, CarKeyBroadcastReceiver.keyCodeIn(media, withEvent(KeyEvent.ACTION_DOWN, 0, up)))
        assertEquals(up, CarKeyBroadcastReceiver.keyCodeIn(media, withEvent(KeyEvent.ACTION_DOWN, 3, up)))
        assertEquals(up, CarKeyBroadcastReceiver.keyCodeIn(media, withEvent(KeyEvent.ACTION_UP, 0, up)))
    }

    @Test
    fun `the keycode extras are read without a command`() {
        assertEquals(24, CarKeyBroadcastReceiver.keyCodeIn(media, intent("keycode" to 24)))
        assertEquals(25, CarKeyBroadcastReceiver.keyCodeIn(media, intent("key_code" to 25)))
    }

    @Test
    fun `a command extra gives no key`() {
        val i = intent("keycode" to 24, strings = mapOf("command" to "next"))
        assertNull(CarKeyBroadcastReceiver.keyCodeIn(media, i))
    }

    @Test
    fun `microntek gives its key`() {
        assertEquals(24, CarKeyBroadcastReceiver.keyCodeIn("com.microntek.irkeyDown", intent("keyCode" to 24)))
        assertEquals(25, CarKeyBroadcastReceiver.keyCodeIn("com.microntek.irkeyUp", intent("keyCode" to 25)))
    }

    @Test
    fun `c3 winca and key click give their keys`() {
        val c3 = intent("android.intent.extra.c3_hardkey_keycode" to 24, "android.intent.extra.c3_hardkey_action" to 0)
        assertEquals(24, CarKeyBroadcastReceiver.keyCodeIn("android.intent.action.C3_HARDKEY", c3))
        val winca = intent("com.winca.service.Setting.KEY_ACTION_EXTRA" to 25)
        assertEquals(25, CarKeyBroadcastReceiver.keyCodeIn("com.winca.service.Setting.KEY_ACTION", winca))
        assertEquals(164, CarKeyBroadcastReceiver.keyCodeIn("IKeyClick.KEY_CLICK", intent("CLICK_KEY" to 164)))
    }

    @Test
    fun `a missing extra or a virtual id action gives no key`() {
        assertNull(CarKeyBroadcastReceiver.keyCodeIn(media, intent()))
        assertNull(CarKeyBroadcastReceiver.keyCodeIn("com.microntek.irkeyDown", intent()))
        assertNull(CarKeyBroadcastReceiver.keyCodeIn("android.intent.action.C3_HARDKEY", intent("android.intent.extra.c3_hardkey_keycode" to 24)))
        assertNull(CarKeyBroadcastReceiver.keyCodeIn("com.eryanet.music.next", intent("keycode" to 24)))
        assertNull(CarKeyBroadcastReceiver.keyCodeIn("com.nwd.action.ACTION_KEY_VALUE", intent()))
    }
}
