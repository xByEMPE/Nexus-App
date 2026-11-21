package mx.com.nexusio.fly.drone.onestop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObstacleAvoidanceSetupTest {
    /** Puerto falso que registra las llamadas y puede fallar en las que se le indiquen. */
    private class FakePort(private val failOn: Set<String> = emptySet()) : ObstacleAvoidancePort {
        val calls = ArrayList<String>()
        private fun call(c: String) { calls += c; if (c in failOn) throw IllegalStateException("rechazado: $c") }
        override suspend fun setBrakeMode() = call("BRAKE")
        override suspend fun setEnabled(direction: AvoidanceDirection, enabled: Boolean) = call("$direction enabled=$enabled")
        override suspend fun setBrakingDistance(direction: AvoidanceDirection, meters: Double) = call("$direction braking=$meters")
        override suspend fun setWarningDistance(direction: AvoidanceDirection, meters: Double) = call("$direction warning=$meters")
    }

    @Test
    fun appliesBrakeModeFirstThenTheOriginalDistancesPerDirection() {
        val port = FakePort()
        val failures = runBlocking { ObstacleAvoidanceSetup.apply(port) }
        assertTrue(failures.isEmpty())
        assertEquals(
            listOf(
                "BRAKE",
                "UPWARD enabled=true", "UPWARD braking=3.0", "UPWARD warning=10.0",
                "DOWNWARD enabled=true", "DOWNWARD braking=3.0", "DOWNWARD warning=10.0",
                "HORIZONTAL enabled=true", "HORIZONTAL braking=3.0", "HORIZONTAL warning=16.0",
            ),
            port.calls,
        )
    }

    @Test
    fun aRejectedStepIsReportedAndTheRestIsStillApplied() {
        val port = FakePort(failOn = setOf("DOWNWARD braking=3.0"))
        val failures = runBlocking { ObstacleAvoidanceSetup.apply(port) }
        assertEquals(1, failures.size)
        assertTrue(failures[0].message.contains("DOWNWARD braking"))
        assertEquals(10, port.calls.size)                       // los demás pasos se aplicaron igual
        assertTrue("HORIZONTAL braking=3.0" in port.calls)
    }

    /** Dron simulado con valores previos que no son los del One Stop (como los dejaría el método Three Stop). */
    private class Drone {
        val values = linkedMapOf<String, Any>("type" to "BYPASS", "HORIZONTAL braking" to 5.0, "HORIZONTAL warning" to 20.0)
        val writes = ArrayList<String>()
        val journal = RestoreJournal()
        suspend fun change(name: String, value: Any) = journal.change(name, read = { values[name] }, write = { v -> writes += "$name=$v"; values[name] = v }, value = value)
    }

    @Test
    fun leavingOneStopRestoresThePreviousAvoidanceSettings() = runBlocking {
        val d = Drone()
        d.change("type", "BRAKE")
        d.change("HORIZONTAL braking", 3.0)
        d.change("HORIZONTAL warning", 16.0)
        d.change("HORIZONTAL braking", 3.0)                     // se reaplica: no pisa el valor original (5 m)
        assertEquals("BRAKE", d.values["type"])
        assertTrue(d.journal.restoreAll().isEmpty())
        assertEquals(mapOf<String, Any>("type" to "BYPASS", "HORIZONTAL braking" to 5.0, "HORIZONTAL warning" to 20.0), d.values)
        // Deshace en orden inverso: aviso, frenado y por último el modo.
        assertEquals(listOf("HORIZONTAL warning=20.0", "HORIZONTAL braking=5.0", "type=BYPASS"), d.writes.takeLast(3))
        assertTrue(d.journal.restoreAll().isEmpty())             // ya restaurado: no repite nada
    }

    @Test
    fun aValueThatCannotBeReadIsChangedButNotRestored() = runBlocking {
        val d = Drone()
        d.change("UPWARD braking", 3.0)                          // el dron no devolvió el valor previo
        assertEquals(3.0, d.values["UPWARD braking"])
        d.journal.restoreAll()
        assertEquals(3.0, d.values["UPWARD braking"])
    }

    @Test
    fun aFailedRestoreIsReportedAndTheRestIsStillRestored() = runBlocking {
        val j = RestoreJournal()
        val values = mutableMapOf<String, Any>("a" to 1, "b" to 2)
        j.change("a", read = { values["a"] }, write = { v -> if (v == 1) error("rechazado"); values["a"] = v }, value = 10)
        j.change("b", read = { values["b"] }, write = { v -> values["b"] = v }, value = 20)
        val failures = j.restoreAll()
        assertEquals(1, failures.size)
        assertTrue(failures[0].startsWith("a:"))
        assertEquals(2, values["b"])
    }

    @Test
    fun brakingHappensBeforeThePlanMinimumAndTheAppBrake() {
        // El plan mantiene ≥ 4.5 m; el freno de la app actúa a 4 m; el del dron, a 3 m. El orden importa.
        val horizontal = ObstacleAvoidanceSetup.DEFAULTS.single { it.direction == AvoidanceDirection.HORIZONTAL }
        assertTrue(horizontal.brakingM < 4.0)
        assertTrue(horizontal.warningM > horizontal.brakingM)
    }
}
