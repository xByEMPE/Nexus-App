package mx.com.nexusio.fly.drone.onestop

import mx.com.nexusio.fly.drone.onestop.EmergencyStopController.State
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyStopTest {
    private class FakePort(val failMission: Boolean = false, val failHold: Boolean = false) : EmergencyStopPort {
        val calls = ArrayList<String>()
        override suspend fun stopMission() { calls += "stopMission"; if (failMission) error("sin enlace") }
        override suspend fun stopGoHome() { calls += "stopGoHome" }
        override suspend fun startHold() { if (failHold) error("virtual stick rechazado"); calls += "hold" }
        override suspend fun releaseHold() { calls += "release" }
    }

    @Test
    fun aQuickPullBackTriggersOnce() {
        val d = PullBackDetector()
        assertFalse(d.update(0, 0))
        assertFalse(d.update(-300, 100))
        assertTrue(d.update(-600, 250))
        assertFalse("no se repite mientras sigue atrás", d.update(-660, 400))
        assertFalse(d.update(0, 800))
        assertTrue("vuelve a armarse al pasar por el centro", d.update(-660, 1000))
    }

    @Test
    fun aSlowPullOrAForwardPushDoesNotTrigger() {
        val d = PullBackDetector()
        assertFalse(d.update(0, 0))
        assertFalse(d.update(-200, 400))
        assertFalse(d.update(-400, 900))
        assertFalse("tarda más de 600 ms desde el centro", d.update(-600, 1400))
        assertFalse(d.update(0, 2000))
        assertFalse("hacia delante no es un paro", d.update(660, 2100))
        // Sin haber visto el centro (al empezar a escuchar con el stick ya atrás) no se dispara.
        assertFalse(PullBackDetector().update(-660, 0))
    }

    @Test
    fun pitchStickFollowsTheRcMode() {
        val s = Sticks(leftV = -10, rightV = -600)
        assertEquals(-600, s.pitch(2))
        assertEquals(-10, s.pitch(1))
        assertEquals(-10, s.pitch(3))
    }

    @Test
    fun buttonWithSticksCenteredStopsAndGivesControlAtOnce() = runBlocking {
        val port = FakePort()
        val c = EmergencyStopController(port)
        c.trigger(Sticks(), 0)
        assertEquals(State.MANUAL, c.state)
        assertEquals(listOf("stopMission", "stopGoHome"), port.calls)
    }

    @Test
    fun afterAPullTheDroneIsHeldUntilTheSticksReturnToCenter() = runBlocking {
        val port = FakePort()
        val c = EmergencyStopController(port)
        c.trigger(Sticks(rightV = -660), 0)
        assertEquals(State.HOLDING, c.state)
        c.onSticks(Sticks(rightV = -400), 100)
        c.onSticks(Sticks(rightV = 20), 200)        // en el centro, pero aún no 300 ms
        assertEquals(State.HOLDING, c.state)
        c.onSticks(Sticks(rightV = 20, leftH = 300), 350)   // otro stick fuera: reinicia la espera
        c.onSticks(Sticks(rightV = 10), 400)
        c.onSticks(Sticks(rightV = 0), 650)
        assertEquals(State.HOLDING, c.state)
        c.onSticks(Sticks(), 720)
        assertEquals(State.MANUAL, c.state)
        assertEquals(listOf("stopMission", "stopGoHome", "hold", "release"), port.calls)
        // Un segundo paro no hace nada hasta reiniciar.
        c.trigger(Sticks(rightV = -660), 800)
        assertEquals(4, port.calls.size)
        c.reset()
        assertEquals(State.IDLE, c.state)
    }

    @Test
    fun aStickThatRestsSlightlyOffCenterStillHandsOverControl() = runBlocking {
        val port = FakePort()
        val c = EmergencyStopController(port)
        c.trigger(Sticks(leftV = -660, rightV = -660), 0)
        assertEquals(State.HOLDING, c.state)
        // Al soltarlos, este mando deja los sticks en −90 y 75: dentro del margen.
        c.onSticks(Sticks(leftV = -90, rightV = 75), 100)
        c.onSticks(Sticks(leftV = -90, rightV = 75), 450)
        assertEquals(State.MANUAL, c.state)
        assertEquals(listOf("izq. vertical" to -660), Sticks(leftV = -660, rightV = 40).offCenter(c.deadband))
        // Lecturas que llegan mientras se suelta no repiten la liberación.
        c.onSticks(Sticks(), 600)
        assertEquals(1, port.calls.count { it == "release" })
    }

    @Test
    fun failuresAreReportedAndControlIsStillHandedOver() = runBlocking {
        val port = FakePort(failMission = true, failHold = true)
        val c = EmergencyStopController(port)
        c.trigger(Sticks(rightV = -660), 0)
        assertEquals(State.MANUAL, c.state)
        assertEquals(2, c.failures.size)
        assertTrue(c.failures[0].startsWith("misión"))
        assertTrue(c.failures[1].startsWith("retención"))
        assertEquals("se intenta cancelar la vuelta a casa aunque falle la misión", listOf("stopMission", "stopGoHome"), port.calls)
    }
}
