package mx.com.nexusio.fly.drone.onestop.flightcore.sim

import mx.com.nexusio.fly.drone.onestop.LaserMeasurementSampler
import mx.com.nexusio.fly.drone.onestop.MeasurementDistanceSource
import mx.com.nexusio.fly.drone.onestop.MeasurementSensor
import mx.com.nexusio.fly.drone.onestop.SensorProfile
import mx.com.nexusio.fly.drone.onestop.VisionSector
import mx.com.nexusio.fly.drone.onestop.VisionSectors
import mx.com.nexusio.fly.drone.onestop.flightcore.Angle
import mx.com.nexusio.fly.drone.onestop.flightcore.BladePosition
import mx.com.nexusio.fly.drone.onestop.flightcore.BladeTwist
import mx.com.nexusio.fly.drone.onestop.flightcore.CameraFov
import mx.com.nexusio.fly.drone.onestop.flightcore.ComponentIndex
import mx.com.nexusio.fly.drone.onestop.flightcore.DroneLocation
import mx.com.nexusio.fly.drone.onestop.flightcore.GeoMath
import mx.com.nexusio.fly.drone.onestop.flightcore.GeoPoint
import mx.com.nexusio.fly.drone.onestop.flightcore.GimbalLimits
import mx.com.nexusio.fly.drone.onestop.flightcore.InspectionDefinition
import mx.com.nexusio.fly.drone.onestop.flightcore.MissionPipeline
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineDimensions
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineMeasurementLocation
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineMeasurementPoint
import mx.com.nexusio.fly.drone.onestop.flightcore.Vec3
import mx.com.nexusio.fly.drone.onestop.flightcore.VirtualTurbineScene
import kotlin.math.atan2
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Medición de cerca (4–6 m), como el original, con láser (Matrice 4T) y sin láser (Mavic 3E): el plan de las 3 palas
 * tiene que coincidir con la turbina verdadera y volarse sin violaciones (distancia mínima a torre, góndola y palas).
 */
class CloseRangeMeasurementFlightTest {
    private val dims = TurbineDimensions.SAMPLE
    private val towerBase = GeoPoint(16.5, -95.0, 0.0)
    private val laserProfile = SensorProfile.forEquipment("Matrice_4T", null)
    private val visionProfile = SensorProfile.forEquipment("Mavic_3E", null)

    /** Sensores de visión simulados: [n] sectores horizontales alrededor del dron (rayos al centro de cada sector). */
    private fun visionSectors(s: VirtualTurbineScene, drone: GeoPoint, yawDeg: Double, n: Int = 180): List<VisionSector> {
        val mm = (0 until n).map { i ->
            val d = s.forwardObstacle(drone, yawDeg + (i + 0.5) * 360.0 / n, maxRange = 30.0)
            if (d > 0f) (d * 1000).roundToInt() else 60_000
        }
        return VisionSectors.fromMillimeters(mm)
    }

    /** Mide [target] desde [from] (locales, misma altura) con el sensor del perfil y el muestreador, como en la app. */
    private fun measure(s: VirtualTurbineScene, profile: SensorProfile, from: Vec3, target: Vec3): LaserMeasurementSampler.Status {
        val v = target - from
        val yaw = Angle.normalize(Math.toDegrees(atan2(v.x, v.y)), -180.0, 180.0)
        val g = GeoMath.toGeo(s.towerBase, from)
        val loc = DroneLocation(g.lat, g.lon, g.alt)
        val source = MeasurementDistanceSource(profile)
        val sampler = LaserMeasurementSampler(LaserMeasurementSampler.Config(maxDistance = profile.maxMeasurementDistanceM))
        for (i in 0 until 1000) {
            val laser = if (profile.sensor == MeasurementSensor.LASER) s.laserDistance(g, yaw, 0.0).toDouble() else -1.0
            val reading = when (val r = source.evaluate(laser, visionSectors(s, g, yaw), 0.0)) {
                is MeasurementDistanceSource.Result.Ok -> LaserMeasurementSampler.Reading(true, r.distanceM, loc, yaw + r.bearingOffsetDeg, 0.0, true)
                is MeasurementDistanceSource.Result.Rejected -> LaserMeasurementSampler.Reading(true, r.distanceM, loc, yaw, 0.0, true, rejected = true)
                MeasurementDistanceSource.Result.NoReading -> LaserMeasurementSampler.Reading(false, -1.0, loc, yaw, 0.0, true)
            }
            val st = sampler.tick(reading, i * 100L)
            if (st !is LaserMeasurementSampler.Status.Running) return st
        }
        error("la medición no terminó")
    }

    private fun point(st: LaserMeasurementSampler.Status, what: String): TurbineMeasurementPoint {
        assertTrue("$what: $st", st is LaserMeasurementSampler.Status.Success)
        return (st as LaserMeasurementSampler.Status.Success).point
    }

    @Test
    fun planFromCloseRangeMeasurementsMatchesTheTrueTurbineAndFliesSafely() {
        assertEquals(MeasurementSensor.LASER, laserProfile.sensor)
        assertEquals(MeasurementSensor.VISION, visionProfile.sensor)
        for (profile in listOf(laserProfile, visionProfile)) for ((facing, noise) in listOf(180.0 to 0.0, 137.0 to 0.05, 20.0 to 0.05)) {
            val s = VirtualTurbineScene(towerBase, dims, facing, laserNoiseSigmaM = noise)
            val t = s.turbine
            val front = Vec3(t.turbineDirection.x, t.turbineDirection.y, 0.0).normalize()
            fun along(b: BladePosition, r: Double) = t.bladeRoot + t.bladeAxisDirection(b) * r
            val len = dims.bladeLength
            // El piloto se pone 5 m delante de cada punto, a su altura, mirándolo (alcance máximo: 6 m).
            fun at(target: Vec3) = measure(s, profile, target + front * 5.0, target)
            val m = mapOf(
                // Al centro del buje, no al vértice del cono del modelo (radio 0): ahí el rayo roza y sigue hasta la góndola.
                TurbineMeasurementLocation.SPINNER to point(at(t.spinner - t.rotorAxisDirection * 1.0), "buje"),
                TurbineMeasurementLocation.TIP_12 to point(at(along(BladePosition.TWELVE, len * 0.9)), "punta 12h"),
                TurbineMeasurementLocation.BOTTOM_12 to point(at(along(BladePosition.TWELVE, 6.0)), "base 12h"),
                TurbineMeasurementLocation.TIP_4 to point(at(along(BladePosition.FOUR, len * 0.9)), "punta 4h"),
                TurbineMeasurementLocation.TIP_8 to point(at(along(BladePosition.EIGHT, len * 0.9)), "punta 8h"),
            )
            val plan = MissionPipeline.plan(
                firstDroneLocation = m.getValue(TurbineMeasurementLocation.SPINNER).droneLocation,
                measurements = m, dimensions = dims, definition = InspectionDefinition(), bladeTwistInput = BladeTwist.default(),
                cam = CameraFov.default(), gimbal = GimbalLimits(), rtkSetting = null, stopAtEach = false,
                blade4 = ComponentIndex.B, blade12 = ComponentIndex.A, blade8 = ComponentIndex.C,
            )
            val name = "${profile.sensor} rumbo $facing ruido $noise"
            val rootError = (plan.turbine.bladeRoot + GeoMath.toLocal(s.towerBase, plan.worldOrigin) - t.bladeRoot).norm
            val report = FlightSimulator(t).run(VirtualPilot.inSceneFrame(s, plan))
            println("== cerca $name: raíz ±${"%.2f".format(rootError)} m, ${plan.waypoints.size} waypoints, distancia mínima ${"%.2f".format(report.minDistance)} m")
            assertTrue("$name: raíz del buje a $rootError m de la real", rootError < 1.5)
            assertTrue("$name: ${report.violations}", report.violations.isEmpty())
            assertTrue("$name: distancia mínima ${report.minDistance}", report.minDistance >= 4.5)
            for ((blade, c) in report.coverage) assertTrue("$name: cobertura $blade = $c", c >= 0.95)
        }
    }

    @Test
    fun measuringFromTooFarFailsInsteadOfMeasuringSomethingElse() {
        val s = VirtualTurbineScene(towerBase, dims, 180.0)
        val t = s.turbine
        val front = Vec3(t.turbineDirection.x, t.turbineDirection.y, 0.0).normalize()
        // A 9 m del buje los dos sensores lo ven, pero fuera del alcance (6.2 m): todas las lecturas se descartan.
        for (profile in listOf(laserProfile, visionProfile)) {
            val st = measure(s, profile, t.spinner + front * 9.0, t.spinner)
            st as LaserMeasurementSampler.Status.Failure
            assertEquals(LaserMeasurementSampler.FailureReason.NOT_ENOUGH_SAMPLES, st.reason)
            assertTrue("${profile.sensor}: ${st.ignored} descartadas", st.ignored > 100)
        }
    }
}
