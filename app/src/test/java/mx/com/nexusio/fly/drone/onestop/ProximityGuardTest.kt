package mx.com.nexusio.fly.drone.onestop

import mx.com.nexusio.fly.drone.onestop.flightcore.BladeTwist
import mx.com.nexusio.fly.drone.onestop.flightcore.CameraFov
import mx.com.nexusio.fly.drone.onestop.flightcore.ComponentIndex
import mx.com.nexusio.fly.drone.onestop.flightcore.GimbalLimits
import mx.com.nexusio.fly.drone.onestop.flightcore.InspectionDefinition
import mx.com.nexusio.fly.drone.onestop.flightcore.InspectionPlanner
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineDimensions
import mx.com.nexusio.fly.drone.onestop.flightcore.Vec3
import mx.com.nexusio.fly.drone.onestop.flightcore.WindTurbine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityGuardTest {

    @Test
    fun staysQuietAtSafeDistancesAndWithoutReadings() {
        val g = ProximityGuard()
        repeat(100) { assertFalse(g.update(10.0, 6.0, 999.0)) }
        repeat(100) { assertFalse(g.update(-1.0, -1.0)) }        // sin lectura: no frena (de eso se ocupa el filtro de Kalman)
        assertNull(g.closestM)
    }

    @Test
    fun brakesAfterConfirmTicksBelowThresholdFromAnySensor() {
        val g = ProximityGuard(minDistanceM = 4.0, confirmTicks = 3)
        assertFalse(g.update(12.0, 3.5))
        assertFalse(g.update(12.0, 3.4))
        assertTrue(g.update(12.0, 3.3))                         // tercer tick seguido: la visión ve algo a < 4 m
        assertEquals(3.3, g.closestM!!, 1e-9)
        assertTrue(g.update(3.0, -1.0))                         // sigue cerca, ahora por el láser
    }

    @Test
    fun aSingleSpuriousReadingDoesNotBrake() {
        val g = ProximityGuard(confirmTicks = 3)
        repeat(50) { i -> assertFalse(g.update(if (i % 2 == 0) 2.0 else 9.0)) }
    }

    @Test
    fun resetClearsTheCount() {
        val g = ProximityGuard(confirmTicks = 3)
        g.update(2.0); g.update(2.0)
        g.reset()
        assertFalse(g.update(2.0))
    }

    @Test
    fun thresholdIsStrict() {
        val g = ProximityGuard(minDistanceM = 4.0, confirmTicks = 1)
        assertFalse(g.update(4.0))
        assertTrue(g.update(3.999))
    }

    /** Ningún waypoint del plan nominal está a menos del umbral: el freno no corta una misión que va según lo previsto. */
    @Test
    fun nominalPlanNeverTriggersTheGuard() {
        val turbine = WindTurbine.fromDimensions(TurbineDimensions.SAMPLE)
        val planner = InspectionPlanner(BladeTwist.default(), turbine, InspectionDefinition(), CameraFov.default(), GimbalLimits())
        val wps = planner.plan(ComponentIndex.B, ComponentIndex.A, ComponentIndex.C)
        val g = ProximityGuard(confirmTicks = 1)
        for (w in wps) assertFalse("waypoint ${w.index}", g.update(turbine.distance(w.point).first))
    }

    /** Un dron que deriva hacia el buje (como con viento fuerte) se frena antes de tocarlo. */
    @Test
    fun driftTowardsTheHubIsStoppedBeforeContact() {
        val turbine = WindTurbine.fromDimensions(TurbineDimensions.SAMPLE)
        val g = ProximityGuard()
        // 15 m por delante del buje y 6 m a un lado, a su altura.
        val start = turbine.spinner + turbine.turbineDirection * 15.0 + Vec3(6.0, 0.0, 0.0)
        val towardsHub = (turbine.spinner - start).normalize()
        assertTrue(turbine.distance(start).first > 10.0)
        var braked: Double? = null
        for (step in 0 until 1000) {                             // 20 Hz, 0.5 m/s de deriva (hasta 25 m)
            val p = start + towardsHub * (step * 0.025)
            val d = turbine.distance(p).first
            if (g.update(d)) { braked = d; break }
        }
        assertTrue("no frenó", braked != null)
        assertTrue("frenó a $braked m", braked!! in 3.9..4.0)
    }
}
