package com.andrerinas.openheadunit.connection.carplaybox

import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxLinkSession.Action
import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxLinkSession.Event
import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxLinkSession.Phase
import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxLinkSession.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayBoxLinkSessionTest {

    private class Run(var state: State = State()) {
        val actions = ArrayList<Action>()
        fun step(event: Event, at: Long): List<Action> {
            val r = CarPlayBoxLinkSession.reduce(state, event, at)
            state = r.state
            actions.addAll(r.actions)
            return r.actions
        }
    }

    private fun started(): Run = Run().also { it.step(Event.Started, 0) }

    private fun List<Action>.ends() = filterIsInstance<Action.End>()

    @Test
    fun `started sends the heartbeat before init and open`() {
        val acts = Run().step(Event.Started, 0)
        assertEquals(listOf(Action.SendHeartBeat, Action.SendInit, Action.SendOpen), acts)
    }

    @Test
    fun `open repeats every second until the echo and never after`() {
        val run = started()
        assertTrue(run.step(Event.Tick, 900).isEmpty())
        assertTrue(run.step(Event.Tick, 1000).contains(Action.SendOpen))
        assertTrue(run.step(Event.Tick, 1500).none { it == Action.SendOpen })
        assertTrue(run.step(Event.Tick, 2000).contains(Action.SendOpen))
        run.step(Event.OpenEchoed, 2100)
        assertTrue(run.step(Event.Tick, 3500).none { it == Action.SendOpen })
        assertTrue(run.step(Event.Tick, 9000).none { it == Action.SendOpen })
    }

    @Test
    fun `heartbeat repeats every two seconds`() {
        val run = started()
        assertTrue(run.step(Event.Tick, 1999).none { it == Action.SendHeartBeat })
        assertTrue(run.step(Event.Tick, 2000).contains(Action.SendHeartBeat))
        assertTrue(run.step(Event.Tick, 3000).none { it == Action.SendHeartBeat })
        assertTrue(run.step(Event.Tick, 4000).contains(Action.SendHeartBeat))
    }

    @Test
    fun `silence ends nothing before or after the phone connects`() {
        val run = started()
        run.step(Event.OpenEchoed, 100)
        run.step(Event.SurfaceReady, 200)
        var t = 200L
        while (t < 60000) { t += 250; run.step(Event.Tick, t) }
        run.step(Event.PhoneConnected, 60000)
        while (t < 120000) { t += 250; run.step(Event.Tick, t) }
        assertTrue(run.actions.ends().isEmpty())
        assertEquals(Phase.Linked(true), run.state.phase)
    }

    @Test
    fun `stream ended ends once and a second one ends nothing`() {
        val run = started()
        assertEquals(1, run.step(Event.StreamEnded("read error"), 10).ends().size)
        assertTrue(run.step(Event.StreamEnded("again"), 11).isEmpty())
        assertTrue(run.step(Event.UserExit, 12).isEmpty())
        assertEquals(1, run.actions.ends().size)
    }

    @Test
    fun `user exit after a stream end gives one end`() {
        val run = started()
        run.step(Event.StreamEnded("unplugged"), 10)
        run.step(Event.UserExit, 11)
        assertEquals(listOf(Action.End("unplugged")), run.actions.ends())
    }

    @Test
    fun `box commands 7 and 8 switch the microphone`() {
        val run = started()
        assertEquals(listOf(Action.MicOn), run.step(Event.BoxCommand(7), 10))
        assertEquals(listOf(Action.MicOff), run.step(Event.BoxCommand(8), 20))
        assertTrue(run.step(Event.BoxCommand(99), 30).isEmpty())
    }

    @Test
    fun `two keyframe asks 400 ms apart give one request`() {
        val run = started()
        assertEquals(listOf(Action.SendKeyframeRequest), run.step(Event.DecoderAskedKeyframe, 5000))
        assertTrue(run.step(Event.DecoderAskedKeyframe, 5400).isEmpty())
        assertEquals(listOf(Action.SendKeyframeRequest), run.step(Event.DecoderAskedKeyframe, 6000))
    }

    @Test
    fun `surface ready asks for a keyframe`() {
        val run = started()
        assertEquals(listOf(Action.SendKeyframeRequest), run.step(Event.SurfaceReady, 300))
    }

    @Test
    fun `no surface 16 seconds after the echo ends with no screen`() {
        val run = started()
        run.step(Event.OpenEchoed, 1000)
        assertTrue(run.step(Event.Tick, 16999).ends().isEmpty())
        assertEquals(listOf(Action.End("no screen")), run.step(Event.Tick, 17000).ends())
    }

    @Test
    fun `a surface keeps the session past 16 seconds`() {
        val run = started()
        run.step(Event.OpenEchoed, 1000)
        run.step(Event.SurfaceReady, 2000)
        assertTrue(run.step(Event.Tick, 40000).ends().isEmpty())
    }

    @Test
    fun `phone gone returns to linked without a phone and never ends`() {
        val run = started()
        run.step(Event.OpenEchoed, 10)
        run.step(Event.PhoneConnected, 20)
        run.step(Event.PhoneGone, 30)
        assertEquals(Phase.Linked(false), run.state.phase)
        assertTrue(run.actions.ends().isEmpty())
    }

    @Test
    fun `nothing happens before started`() {
        val run = Run()
        assertTrue(run.step(Event.Tick, 5000).isEmpty())
        assertTrue(run.step(Event.UserExit, 5001).isEmpty())
        assertEquals(Phase.Idle, run.state.phase)
    }

    @Test
    fun `no path sends command 22`() {
        assertEquals(23, CarPlayBoxMessages.CMD_AUDIO_OVER_USB)
        assertTrue(CarPlayBoxMessages.CMD_AUDIO_OVER_USB != 22)
    }
}
