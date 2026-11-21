package mx.com.nexusio.fly.drone.onestop.flightcore.sim

import mx.com.nexusio.fly.drone.onestop.flightcore.Angle
import mx.com.nexusio.fly.drone.onestop.flightcore.BladePosition
import mx.com.nexusio.fly.drone.onestop.flightcore.BladeTwist
import mx.com.nexusio.fly.drone.onestop.flightcore.CameraFov
import mx.com.nexusio.fly.drone.onestop.flightcore.ComponentIndex
import mx.com.nexusio.fly.drone.onestop.flightcore.DroneLocation
import mx.com.nexusio.fly.drone.onestop.flightcore.GeoMath
import mx.com.nexusio.fly.drone.onestop.flightcore.GimbalLimits
import mx.com.nexusio.fly.drone.onestop.flightcore.InspectionDefinition
import mx.com.nexusio.fly.drone.onestop.flightcore.MissionPipeline
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineMeasurementLocation
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineMeasurementPoint
import mx.com.nexusio.fly.drone.onestop.flightcore.Vec3
import mx.com.nexusio.fly.drone.onestop.flightcore.VirtualTurbineScene
import mx.com.nexusio.fly.drone.onestop.flightcore.Waypoint3D
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertTrue

/** Piloto virtual: mide las 5 posiciones con el láser de una [VirtualTurbineScene], planifica con ellas y devuelve el plan en el sistema de la escena. */
object VirtualPilot {
    private fun geoOf(scene: VirtualTurbineScene, local: Vec3) = GeoMath.toGeo(scene.towerBase, local)

    /** Piloto virtual: coloca el dron en [from] (local), lo gira hacia [target] y mide con el láser, como en campo. */
    fun measure(s: VirtualTurbineScene, from: Vec3, target: Vec3): TurbineMeasurementPoint {
        val v = target - from
        val yaw = Angle.normalize(Math.toDegrees(atan2(v.x, v.y)), -180.0, 180.0)
        val pitch = Math.toDegrees(atan2(v.z, hypot(v.x, v.y)))
        val g = geoOf(s, from)
        val dist = s.laserDistance(g, yaw, pitch)
        assertTrue("el láser no impactó al apuntar a $target desde $from", dist > 0f)
        return TurbineMeasurementPoint(DroneLocation(g.lat, g.lon, g.alt), yaw, pitch, dist.toDouble())
    }

    fun pilotMeasurements(s: VirtualTurbineScene): Map<TurbineMeasurementLocation, TurbineMeasurementPoint?> {
        val t = s.turbine
        val front = t.turbineDirection
        val axis = t.rotorAxisDirection
        fun along(b: BladePosition, r: Double) = t.bladeRoot + t.bladeAxisDirection(b) * r
        val len = s.dims.bladeLength
        val spinnerAim = t.spinner - axis * 1.0
        val tip12 = along(BladePosition.TWELVE, len * 0.9)
        val tip4 = along(BladePosition.FOUR, len * 0.9)
        val tip8 = along(BladePosition.EIGHT, len * 0.9)
        val bottom12 = along(BladePosition.TWELVE, 6.0)
        return mapOf(
            // Buje: de frente, a la altura del spinner.
            TurbineMeasurementLocation.SPINNER to measure(s, t.spinner + front * 15.0, spinnerAim),
            // Punta de las 12: casi bajo la punta, mirando hacia arriba (≈80°).
            TurbineMeasurementLocation.TIP_12 to measure(s, tip12 + front * 10.0 - Vec3.Z * (10.0 * Math.tan(Math.toRadians(80.0))), tip12),
            TurbineMeasurementLocation.BOTTOM_12 to measure(s, bottom12 + front * 12.0 - Vec3.Z * 3.0, bottom12),
            // Puntas laterales: dron por encima y por delante, mirando hacia abajo (≈−30°).
            TurbineMeasurementLocation.TIP_4 to measure(s, tip4 + front * 20.0 + Vec3.Z * (20.0 * Math.tan(Math.toRadians(30.0))), tip4),
            TurbineMeasurementLocation.TIP_8 to measure(s, tip8 + front * 20.0 + Vec3.Z * (20.0 * Math.tan(Math.toRadians(30.0))), tip8),
        )
    }

    fun planFromLaser(s: VirtualTurbineScene): MissionPipeline.Result {
        val m = pilotMeasurements(s)
        return MissionPipeline.plan(
            firstDroneLocation = m.getValue(TurbineMeasurementLocation.SPINNER)!!.droneLocation,
            measurements = m, dimensions = s.dims, definition = InspectionDefinition(), bladeTwistInput = BladeTwist.default(),
            cam = CameraFov.default(), gimbal = GimbalLimits(), rtkSetting = null, stopAtEach = false,
            blade4 = ComponentIndex.B, blade12 = ComponentIndex.A, blade8 = ComponentIndex.C,
        )
    }

    /** Vuela el plan hecho con lecturas del láser sobre la turbina verdadera del escenario. */
    fun inSceneFrame(s: VirtualTurbineScene, r: MissionPipeline.Result): List<Waypoint3D> {
        val shift = GeoMath.toLocal(s.towerBase, r.worldOrigin)   // sistema local del plan → sistema local de la escena
        return r.localWaypoints.map { it.copy(point = it.point + shift, pointOfInterest = it.pointOfInterest + shift) }
    }


}
