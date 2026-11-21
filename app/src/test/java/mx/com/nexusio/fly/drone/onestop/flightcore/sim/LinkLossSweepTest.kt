package mx.com.nexusio.fly.drone.onestop.flightcore.sim

import mx.com.nexusio.fly.drone.onestop.flightcore.BladeTwist
import mx.com.nexusio.fly.drone.onestop.flightcore.ComponentIndex
import mx.com.nexusio.fly.drone.onestop.flightcore.GimbalLimits
import mx.com.nexusio.fly.drone.onestop.flightcore.InspectionDefinition
import mx.com.nexusio.fly.drone.onestop.flightcore.InspectionPlanner
import mx.com.nexusio.fly.drone.onestop.flightcore.ReturnHomeSafety
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineDimensions
import mx.com.nexusio.fly.drone.onestop.flightcore.Vec3
import mx.com.nexusio.fly.drone.onestop.flightcore.WindTurbine
import mx.com.nexusio.fly.drone.onestop.job.DroneCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pérdida de enlace sin recuperación en cualquier punto de la misión (uno de cada 3 waypoints), con viento de 9 m/s
 * del norte, sur, este y oeste: 3 turbinas × 2 drones. Con la vuelta a casa inmediata y la altura de RTH por tramo
 * no debe haber contacto ni frenazo de la evasión de obstáculos; con la configuración anterior (mantenerse en el sitio
 * y RTH a una sola altura) sí lo hay.
 */
class LinkLossSweepTest {
    private val home = Vec3(0.0, 60.0, 0.0)
    private val turbines = listOf(80.0 to 38.0, 120.0 to 64.0, 145.0 to 68.0)
    private val cameras = listOf(
        "M4E" to DroneCatalog.fovFromDiagonal(35.0, 4.0 to 3.0),
        "M3E" to DroneCatalog.fovFromDiagonal(84.0, 4.0 to 3.0),
    )
    private val windsFrom = listOf(0.0, 180.0, 90.0, 270.0)
    private val drain = { w: Double -> 100.0 / (45.0 * 60.0) * (1.0 + 0.5 * (w / 12.0) * (w / 12.0)) }

    private data class Outcome(val runs: Int, val contacts: Int, val blocked: Int, val minD: Double, val where: String)

    private fun sweep(scheduled: Boolean): List<Pair<String, Outcome>> = turbines.flatMap { (hub, blade) ->
        val t = WindTurbine.fromDimensions(TurbineDimensions.SAMPLE.copy(rotorDiameter = blade * 2, towerHeight = hub))
        cameras.map { (name, cam) ->
            val wps = InspectionPlanner(BladeTwist.default(), t, InspectionDefinition(), cam, GimbalLimits())
                .plan(ComponentIndex.B, ComponentIndex.A, ComponentIndex.C).map { it.point }
            val schedule = ReturnHomeSafety.schedule(t, wps, home,
                ReturnHomeSafety.Heights(ReturnHomeSafety.DJI_MIN_RTH_HEIGHT_M, ReturnHomeSafety.climbHeightAboveHomeM(t, home)))
            var runs = 0; var contacts = 0; var blocked = 0; var minD = Double.MAX_VALUE; var where = ""
            for (i in wps.indices step 3) for (from in windsFrom) {
                val cfg = if (scheduled) LinkLossConfig(immediateRth = true, rthAltitudeM = schedule.heightAt(i).toDouble())
                    else LinkLossConfig(rthAltitudeM = 100.0)
                // Batería al 40 %: con la configuración anterior llega a la batería baja (25 %) sin agotar el escenario.
                val r = LinkLossSimulator(t, WindModel(9.0, from, seed = i.toLong(), lateralTurbulenceRatio = 0.7), home, cfg, drain).run(wps[i], 0.0, 40.0)
                runs++
                if (r.contact) contacts++
                if (r.events.any { it.second.startsWith("La evasión") }) blocked++
                if (r.minDistance < minD) { minD = r.minDistance; where = "wp $i, viento del ${from.toInt()}°, ${r.minComponent}" }
            }
            "%3.0f/%2.0f %s".format(hub, blade, name) to Outcome(runs, contacts, blocked, minD, where)
        }
    }

    @Test
    fun immediateRthWithScheduledHeightNeverTouchesNorBrakes() {
        for ((case, o) in sweep(scheduled = true)) {
            println("$case RTH por tramo: ${o.runs} pérdidas, contactos ${o.contacts}, frenazos ${o.blocked}, holgura mínima %.2f m (${o.where})".format(o.minD))
            assertEquals(case, 0, o.contacts)
            assertEquals(case, 0, o.blocked)
            assertTrue(case, o.minD >= 3.0)
        }
    }

    @Test
    fun thePreviousConfigurationTouchesTheTurbine() {
        val all = sweep(scheduled = false)
        for ((case, o) in all) println("$case anterior: ${o.runs} pérdidas, contactos ${o.contacts}, frenazos ${o.blocked}, holgura mínima %.2f m (${o.where})".format(o.minD))
        assertTrue(all.sumOf { it.second.contacts } > 0)
    }
}
