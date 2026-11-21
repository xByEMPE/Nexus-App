package mx.com.nexusio.fly.drone.onestop.flightcore.sim

import mx.com.nexusio.fly.drone.onestop.flightcore.Angle
import mx.com.nexusio.fly.drone.onestop.flightcore.BladePosition
import mx.com.nexusio.fly.drone.onestop.flightcore.BladeTwist
import mx.com.nexusio.fly.drone.onestop.flightcore.CameraFov
import mx.com.nexusio.fly.drone.onestop.flightcore.ComponentIndex
import mx.com.nexusio.fly.drone.onestop.flightcore.GimbalLimits
import mx.com.nexusio.fly.drone.onestop.flightcore.InspectionDefinition
import mx.com.nexusio.fly.drone.onestop.flightcore.InspectionPlanner
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineDimensions
import mx.com.nexusio.fly.drone.onestop.flightcore.Vec3
import mx.com.nexusio.fly.drone.onestop.flightcore.Waypoint3D
import mx.com.nexusio.fly.drone.onestop.flightcore.WindTurbine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escenarios sobre turbinas virtuales: planifica y "vuela" el plan midiendo holgura, límites de gimbal y cobertura. */
class VirtualTurbineFlightTest {
    private data class Scenario(val name: String, val dims: TurbineDimensions, val rotorIndexDeg: Double = 0.0)

    private val base = TurbineDimensions.SAMPLE
    private val scenarios = listOf(
        Scenario("sample-112m", base),
        Scenario("small-80m", base.copy(rotorDiameter = 80.0, towerHeight = 80.0)),
        Scenario("large-136m", base.copy(rotorDiameter = 136.0, towerHeight = 150.0)),
        Scenario("rotor-index+4", base, 4.0),
        Scenario("rotor-index-4", base, -4.0),
        Scenario("no-tilt", base.copy(bladeTiltAngle = 0.0, rotorAxisAngle = 0.0)),
        Scenario("max-tilt", base.copy(bladeTiltAngle = 6.0, rotorAxisAngle = 9.0)),
    )

    private fun fly(s: Scenario): SimReport {
        val turbine = WindTurbine.fromDimensions(s.dims, Angle.fromDegrees(s.rotorIndexDeg))
        val planner = InspectionPlanner(BladeTwist.default(), turbine, InspectionDefinition(), CameraFov.default(), GimbalLimits())
        val wps = planner.plan(ComponentIndex.B, ComponentIndex.A, ComponentIndex.C)
        val report = FlightSimulator(turbine).run(wps)
        ViewerExporter.export(File("build/sim-output/viewer-${s.name}.html"), "Turbina ideal — ${s.name}", turbine, wps, report, CameraFov.default())
        return report
    }

    @Test
    fun allScenariosFlySafelyWithFullCoverage() {
        for (s in scenarios) {
            val report = fly(s)
            println("== ${s.name}\n${report.summary()}")
            report.writeCsv(File("build/sim-output/${s.name}.csv"))
            assertTrue("${s.name}: ${report.violations}", report.violations.isEmpty())
            for ((blade, c) in report.coverage) assertTrue("${s.name}: cobertura $blade = $c", c >= 0.98)
        }
    }

    // Controles negativos: el simulador debe detectar un plan malo, si no el resultado anterior no vale nada.
    private fun planAndTurbine(): Pair<WindTurbine, List<Waypoint3D>> {
        val turbine = WindTurbine.fromDimensions(base)
        val wps = InspectionPlanner(BladeTwist.default(), turbine, InspectionDefinition(), CameraFov.default(), GimbalLimits())
            .plan(ComponentIndex.B, ComponentIndex.A, ComponentIndex.C)
        return turbine to wps
    }

    @Test
    fun detectsWaypointDraggedIntoBlade() {
        val (turbine, wps) = planAndTurbine()
        val mid = wps.size / 2
        val bad = wps.toMutableList().also { it[mid] = it[mid].copy(point = turbine.bladeRoot + (turbine.bladeTip(BladePosition.TWELVE) - turbine.bladeRoot) * 0.5) }
        val report = FlightSimulator(turbine).run(bad)
        assertTrue(report.minDistance < 4.5)
        assertTrue(report.violations.any { it.contains("distancia mínima") })
    }

    @Test
    fun detectsStraightSegmentCuttingThroughTurbineBetweenSafeWaypoints() {
        val (turbine, wps) = planAndTurbine()
        // Dos waypoints seguros a ambos lados de la torre: el tramo recto entre ellos la atraviesa.
        val a = wps.first().copy(point = Vec3(-15.0, 0.0, 60.0))
        val b = wps.first().copy(index = 1, point = Vec3(15.0, 0.0, 60.0))
        assertTrue(turbine.distance(a.point).first > 4.5 && turbine.distance(b.point).first > 4.5)
        val report = FlightSimulator(turbine).run(listOf(a, b))
        assertTrue(report.minDistance < 4.5)
    }

    @Test
    fun detectsMissingPhotosAsLostCoverage() {
        val (turbine, wps) = planAndTurbine()
        val noPhotos = wps.map { w -> w.copy(actions = w.actions.map { it.copy(takePhoto = false) }) }
        val report = FlightSimulator(turbine).run(noPhotos)
        assertTrue(report.coverage.values.all { it == 0.0 })
        val half = wps.mapIndexed { i, w -> if (i < wps.size / 2) w.copy(actions = w.actions.map { it.copy(takePhoto = false) }) else w }
        assertTrue(FlightSimulator(turbine).run(half).coverage.values.any { it < 0.9 })
    }

    @Test
    fun detectsGimbalBeyondLimits() {
        val (turbine, wps) = planAndTurbine()
        val i = wps.indexOfFirst { it.actions.any { a -> a.takePhoto } }
        val bad = wps.toMutableList().also { l -> l[i] = l[i].copy(actions = l[i].actions.map { it.copy(pitch = -80.0) }) }
        assertTrue(FlightSimulator(turbine).run(bad).violations.any { it.contains("pitch de foto") })
    }
}
