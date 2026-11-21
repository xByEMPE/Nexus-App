package mx.com.nexusio.fly.drone.onestop.flightcore.sim

import mx.com.nexusio.fly.drone.onestop.flightcore.Angle
import mx.com.nexusio.fly.drone.onestop.flightcore.BladePosition
import mx.com.nexusio.fly.drone.onestop.flightcore.BladeTwist
import mx.com.nexusio.fly.drone.onestop.flightcore.CameraFov
import mx.com.nexusio.fly.drone.onestop.flightcore.ComponentIndex
import mx.com.nexusio.fly.drone.onestop.flightcore.DroneLocation
import mx.com.nexusio.fly.drone.onestop.flightcore.GeoMath
import mx.com.nexusio.fly.drone.onestop.flightcore.GeoPoint
import mx.com.nexusio.fly.drone.onestop.flightcore.Geo3D
import mx.com.nexusio.fly.drone.onestop.flightcore.GimbalLimits
import mx.com.nexusio.fly.drone.onestop.flightcore.InspectionDefinition
import mx.com.nexusio.fly.drone.onestop.flightcore.MissionPipeline
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineDimensions
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineMeasurementLocation
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineMeasurementPoint
import mx.com.nexusio.fly.drone.onestop.flightcore.Vec3
import mx.com.nexusio.fly.drone.onestop.flightcore.VirtualTurbineScene
import mx.com.nexusio.fly.drone.onestop.flightcore.Waypoint3D
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualLaserTest {
    private val dims = TurbineDimensions.SAMPLE
    private val towerBase = GeoPoint(40.0, -3.0, 0.0)

    private fun scene(facing: Double = 137.0, rotorIndex: Double = 0.0, noise: Double = 0.0) =
        VirtualTurbineScene(towerBase, dims, facing, rotorIndex, noise)

    private fun geoOf(scene: VirtualTurbineScene, local: Vec3) = GeoMath.toGeo(scene.towerBase, local)

    // ── el modelo ──
    @Test
    fun rotorFacesRequestedBearing() {
        for (facing in listOf(0.0, 90.0, 180.0, 270.0, 137.0)) {
            val dir = scene(facing).turbine.turbineDirection
            assertEquals("facing $facing", facing, Angle.normalize(Geo3D.yawOf(dir), 0.0, 360.0), 0.5)
        }
    }

    // ── el rayo ──
    @Test
    fun laserHitsTowerAtKnownDistance() {
        val s = scene(facing = 180.0)
        // Dron al sur de la torre, a 30 m de su eje y a media altura, mirando al norte (rumbo 0), horizontal.
        val drone = geoOf(s, Vec3(0.0, -30.0, 60.0))
        val d = s.laserDistance(drone, yawDeg = 0.0, pitchDeg = 0.0)
        // Radio de la torre a 60 m: interpolación entre 3.0 (base) y 2.0 (cima) → 2.5 m.
        assertEquals(30.0 - 2.5, d.toDouble(), 0.15)   // el eje reconstruido de la torre queda a ~5 cm del origen
    }

    @Test
    fun laserReturnsMinusOneWhenNothingIsHit() {
        val s = scene()
        val drone = geoOf(s, Vec3(0.0, -30.0, 60.0))
        assertEquals(-1f, s.laserDistance(drone, 180.0, 0.0), 0f)      // apuntando en sentido contrario
        assertEquals(-1f, s.laserDistance(drone, 0.0, 80.0), 0f)       // al cielo
        val far = geoOf(s, Vec3(0.0, -500.0, 60.0))
        assertEquals(-1f, s.laserDistance(far, 0.0, 0.0), 0f)          // fuera de alcance (200 m)
    }

    @Test
    fun laserNoiseIsBoundedAndSeeded() {
        val noisy = scene(noise = 0.05)
        val drone = geoOf(noisy, Vec3(0.0, -30.0, 60.0))
        val reads = (1..200).map { noisy.laserDistance(drone, 0.0, 0.0).toDouble() }
        val clean = scene().laserDistance(drone, 0.0, 0.0).toDouble()
        assertTrue(reads.all { abs(it - clean) < 0.4 })
        assertTrue(reads.toSet().size > 50)
    }

    // ── el flujo de medición real con piloto virtual ──

    @Test
    fun planFromVirtualLaserMatchesTheTrueTurbineAndFliesSafely() {
        for ((name, facing, index, noise) in listOf(
            Case("sur", 180.0, 0.0, 0.0), Case("rumbo-137", 137.0, 0.0, 0.05), Case("rotor+3", 300.0, 3.0, 0.05), Case("rotor-3", 20.0, -3.0, 0.05),
        )) {
            val s = scene(facing, index, noise)
            val plan = VirtualPilot.planFromLaser(s)
            // El rotor reconstruido a partir de las lecturas debe coincidir con el verdadero.
            val rootError = (plan.turbine.bladeRoot + GeoMath.toLocal(s.towerBase, plan.worldOrigin) - s.turbine.bladeRoot).norm
            assertTrue("$name: raíz del buje a ${"%.2f".format(rootError)} m de la real", rootError < 1.5)
            assertEquals("$name: índice del rotor", index, plan.turbine.rotorIndexAngle.degrees, 2.0)

            val wps = VirtualPilot.inSceneFrame(s, plan)
            val report = FlightSimulator(s.turbine).run(wps)
            println("== virtual-laser-$name  raíz ±${"%.2f".format(rootError)} m\n${report.summary()}")
            report.writeCsv(File("build/sim-output/virtual-laser-$name.csv"))
            ViewerExporter.export(File("build/sim-output/viewer-virtual-laser-$name.html"), "Láser virtual — $name", s.turbine, wps, report, CameraFov.default())
            assertTrue("$name: ${report.violations}", report.violations.isEmpty())
            for ((blade, c) in report.coverage) assertTrue("$name: cobertura $blade = $c", c >= 0.95)
        }
    }

    private data class Case(val name: String, val facing: Double, val index: Double, val noise: Double)

    @Test
    fun spinnerMeasurementProvidesFirstDroneLocationAboveGround() {
        val m = VirtualPilot.pilotMeasurements(scene())
        assertNotNull(m[TurbineMeasurementLocation.SPINNER])
        assertTrue(m.getValue(TurbineMeasurementLocation.SPINNER)!!.droneLocation.altitude!! > 100.0)
    }
}
