package mx.com.nexusio.fly.drone.onestop.flightcore

import mx.com.nexusio.fly.drone.onestop.flightcore.ReturnHomeSafety.Profile
import mx.com.nexusio.fly.drone.onestop.job.DroneCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnHomeSafetyTest {
    /** Rotor mirando al norte, base de la torre en el origen y despegue 60 m delante del rotor, como en las simulaciones. */
    private val home = Vec3(0.0, 60.0, 0.0)
    private val turbines = listOf(80.0 to 38.0, 120.0 to 64.0, 145.0 to 68.0)
    private val cameras = listOf(
        "M4E" to DroneCatalog.fovFromDiagonal(35.0, 4.0 to 3.0),
        "M3E" to DroneCatalog.fovFromDiagonal(84.0, 4.0 to 3.0),
    )

    private fun turbine(hub: Double, blade: Double) =
        WindTurbine.fromDimensions(TurbineDimensions.SAMPLE.copy(rotorDiameter = blade * 2, towerHeight = hub))

    private fun plan(t: WindTurbine, cam: CameraFov) =
        InspectionPlanner(BladeTwist.default(), t, InspectionDefinition(), cam, GimbalLimits())
            .plan(ComponentIndex.B, ComponentIndex.A, ComponentIndex.C).map { it.point }

    private fun heights(t: WindTurbine) =
        ReturnHomeSafety.Heights(ReturnHomeSafety.DJI_MIN_RTH_HEIGHT_M, ReturnHomeSafety.climbHeightAboveHomeM(t, home))

    @Test
    fun climbHeightClearsTheHighestBladeTipByTheMargin() {
        val t = turbine(120.0, 64.0)
        val h = ReturnHomeSafety.climbHeightAboveHomeM(t, home)
        assertTrue(h >= t.bladeRoot.z + 64.0 + ReturnHomeSafety.ROTOR_MARGIN_M)
        assertTrue(h < t.bladeRoot.z + 64.0 + ReturnHomeSafety.ROTOR_MARGIN_M + 1.0)
        // Despegue 10 m más bajo que la base de la torre: la altura sobre el despegue sube 10 m.
        assertEquals(h + 10, ReturnHomeSafety.climbHeightAboveHomeM(t, home + Vec3(0.0, 0.0, -10.0)))
        assertEquals(204, ReturnHomeSafety.climbHeightBeforeMeasuringM(120.0, 64.0))
    }

    @Test
    fun scheduledRthIsSafeFromEveryPointOfTheMission() {
        for ((hub, blade) in turbines) for ((name, cam) in cameras) {
            val t = turbine(hub, blade)
            val wps = plan(t, cam)
            val s = ReturnHomeSafety.schedule(t, wps, home, heights(t))
            val switches = s.profiles.zipWithNext().count { (a, b) -> a != b }
            println("%3.0f/%2.0f %s: holgura mínima %.2f m (%s, %s, wp %d de %d); subir en %d wp, %d cambios; RTH %d/%d m".format(
                hub, blade, name, s.worst.minClearanceM, s.worst.component, s.worst.leg, s.worst.waypointIndex, wps.size,
                s.profiles.count { it == Profile.CLIMB }, switches, s.heights.levelM, s.heights.climbM))
            assertTrue("$hub/$blade $name: ${s.worst}", s.isSafe)
            assertTrue("hacen falta los dos perfiles", Profile.LEVEL in s.profiles && Profile.CLIMB in s.profiles)
        }
    }

    @Test
    fun aSingleRthHeightIsNotSafeAnywhereInTheMission() {
        for ((hub, blade) in turbines) for ((name, cam) in cameras) {
            val t = turbine(hub, blade)
            val wps = plan(t, cam)
            // 20 m (app auditada), 100 m y por encima del rotor
            for (h in listOf(20.0, 100.0, ReturnHomeSafety.climbHeightAboveHomeM(t, home).toDouble())) {
                val c = ReturnHomeSafety.check(t, wps, home, h)
                println("%3.0f/%2.0f %s RTH fija a %3.0f m: holgura mínima %.2f m (%s, %s)".format(hub, blade, name, h, c.minClearanceM, c.component, c.leg))
                assertFalse("$hub/$blade $name a $h m: $c", c.isSafe)
            }
        }
    }

    @Test
    fun climbingIsWhatSavesTheReturnFromBehindTheRotor() {
        val t = turbine(120.0, 64.0)
        val behindTop = t.bladeRoot + t.bladeAxisDirection(BladePosition.TWELVE) * 40.0 - t.turbineDirection * 10.0
        val h = heights(t)
        assertFalse(ReturnHomeSafety.clearance(t, behindTop, home, home.z + h.levelM).isSafe)
        assertTrue(ReturnHomeSafety.clearance(t, behindTop, home, home.z + h.climbM).isSafe)
        // Debajo de la pala de las 4h, delante: subir choca con la pala; volver a la altura actual no.
        val under4 = t.bladeRoot + t.bladeAxisDirection(BladePosition.FOUR) * 30.0 - Vec3.Z * 8.0 + t.turbineDirection * 6.0
        assertFalse(ReturnHomeSafety.clearance(t, under4, home, home.z + h.climbM).isSafe)
        assertTrue(ReturnHomeSafety.clearance(t, under4, home, home.z + h.levelM).isSafe)
    }

    /**
     * Caso de la prueba en campo del 2026-11-06: despegue a ~20 m de la torre, frente al cono. Los waypoints de detrás
     * del rotor quedan a menos de 50 m de casa, el dron volvería en línea recta sin subir y cruzaría el rotor: no se
     * puede volar. El diagnóstico lo explica y propone desde dónde despegar.
     */
    @Test
    fun homeCloseToTheTowerIsDiagnosedAndAFartherTakeoffIsSuggested() {
        for ((hub, blade) in turbines) for ((name, cam) in cameras) {
            val t = turbine(hub, blade)
            val wps = plan(t, cam)
            val near = ReturnHomeSafety.homeAhead(t, 20.0, 0.0)
            fun heightsFor(h: Vec3) = ReturnHomeSafety.Heights(ReturnHomeSafety.DJI_MIN_RTH_HEIGHT_M, ReturnHomeSafety.climbHeightAboveHomeM(t, h))
            val s = ReturnHomeSafety.schedule(t, wps, near, heightsFor(near))
            assertFalse("$hub/$blade $name: ${s.worst}", s.isSafe)
            assertTrue("cruza la turbina, no es falta de margen: ${s.worst}", s.worst.minClearanceM < 1.0)

            val d = ReturnHomeSafety.diagnose(t, wps, near, s.worst, ::heightsFor)
            println("%3.0f/%2.0f %s casa a %.0f m: wp %d (%s, %s) a %.0f m de casa, %.0f m %s de la torre; despegar a %s m".format(
                hub, blade, name, d.homeToTowerM, s.worst.waypointIndex + 1, s.worst.component, s.worst.leg, d.waypointToHomeM,
                kotlin.math.abs(d.waypointAheadOfTowerM), if (d.waypointAheadOfTowerM < 0) "detrás" else "delante", d.safeHomeAheadM))
            assertEquals(20.0, d.homeToTowerM, 1e-6)
            assertTrue("vuelve sin subir", d.nearHome)
            assertTrue("detrás del rotor", d.waypointAheadOfTowerM < 0)
            assertEquals(ReturnHomeSafety.Leg.CRUISE, s.worst.leg)

            val ahead = d.safeHomeAheadM
            assertTrue("hay un despegue seguro cerca", ahead != null && ahead <= 60.0)
            val far = ReturnHomeSafety.homeAhead(t, ahead!!, 0.0)
            assertTrue(ReturnHomeSafety.schedule(t, wps, far, heightsFor(far)).isSafe)
        }
    }

    @Test
    fun noSafeTakeoffIsReportedWhenTheDroneCannotClimbOverTheRotor() {
        val t = turbine(80.0, 38.0)
        val wps = plan(t, cameras[0].second)
        val near = ReturnHomeSafety.homeAhead(t, 20.0, 0.0)
        val h = ReturnHomeSafety.Heights(ReturnHomeSafety.DJI_MIN_RTH_HEIGHT_M, ReturnHomeSafety.climbHeightAboveHomeM(t, near))
        val s = ReturnHomeSafety.schedule(t, wps, near, h)
        assertEquals(null, ReturnHomeSafety.diagnose(t, wps, near, s.worst) { null }.safeHomeAheadM)
    }

    @Test
    fun nearHomeBothProfilesAreChecked() {
        assertEquals(2, ReturnHomeSafety.paths(Vec3(0.0, 30.0, 50.0), home, 150.0).size)
        assertEquals(1, ReturnHomeSafety.paths(Vec3(0.0, -30.0, 50.0), home, 150.0).size)
        // Si ya va por encima de la altura de RTH, vuelve a la suya.
        val climbed = ReturnHomeSafety.paths(Vec3(0.0, -30.0, 200.0), home, 150.0).single()
        assertEquals(200.0, climbed[1].second.first.z, 1e-9)
    }
}
