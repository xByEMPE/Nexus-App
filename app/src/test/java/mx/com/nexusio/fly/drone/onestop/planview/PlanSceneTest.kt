package mx.com.nexusio.fly.drone.onestop.planview

import mx.com.nexusio.fly.drone.onestop.flightcore.BladeTwist
import mx.com.nexusio.fly.drone.onestop.flightcore.CameraFov
import mx.com.nexusio.fly.drone.onestop.flightcore.ComponentIndex
import mx.com.nexusio.fly.drone.onestop.flightcore.GimbalLimits
import mx.com.nexusio.fly.drone.onestop.flightcore.InspectionDefinition
import mx.com.nexusio.fly.drone.onestop.flightcore.InspectionPlanner
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineDimensions
import mx.com.nexusio.fly.drone.onestop.flightcore.WindTurbine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSceneTest {
    private val turbine = WindTurbine.fromDimensions(TurbineDimensions.SAMPLE)        // rotor mirando al norte
    private val wps = InspectionPlanner(BladeTwist.default(), turbine, InspectionDefinition(), CameraFov.default(), GimbalLimits())
        .plan(ComponentIndex.B, ComponentIndex.A, ComponentIndex.C)
    private fun scene() = PlanScene(turbine, wps, wps.map { it.stop || it.isFirstInspectionPathPoint || it.isLastInspectionPathPoint })

    @Test
    fun wireHasEveryPartWithOneColourPerBlade() {
        val s = scene()
        assertTrue(s.wire.keys.containsAll(listOf(WireKind.BLADE_4, WireKind.BLADE_12, WireKind.BLADE_8, WireKind.TOWER, WireKind.NACELLE)))
        assertTrue(s.wire.values.all { it.isNotEmpty() })
        assertEquals(wps.size, s.points.size)
        assertTrue(s.points.any { it.stop } && s.points.any { !it.stop })
    }

    @Test
    fun everythingFitsInsideTheViewFromAnyAngle() {
        val s = scene()
        val w = 1280; val h = 720; val pad = 24f; val top = 64f; val bottom = 28f
        for ((dx, dy) in listOf(0f to 0f, 200f to 0f, -350f to 40f, 90f to -120f, 0f to -500f, 0f to 500f)) {
            s.rotateBy(dx, dy)
            s.layout(w, h, pad, top, bottom)
            val all = s.wire.values.flatten().flatMap { listOf(it.a, it.b) } + s.points.map { it.pos }
            for (p in all) {
                val q = s.project(p)
                assertTrue("x=${q.x} con giro ($dx,$dy)", q.x >= pad - 1 && q.x <= w - pad + 1)
                assertTrue("y=${q.y} con giro ($dx,$dy)", q.y >= top - 1 && q.y <= h - bottom + 1)
            }
        }
    }

    @Test
    fun presetsAreRelativeToTheRotorAndTheLabelSaysWhereWeLookFrom() {
        val s = scene()
        assertEquals(0.0, s.rotorFacingDeg, 1.0)
        s.preset(PlanScene.Preset.FRONT)
        assertEquals(PlanScene.Side.FRONT, s.side)
        assertEquals("N", PlanScene.cardinal(s.viewerBearingDeg))
        s.preset(PlanScene.Preset.SIDE); assertEquals(PlanScene.Side.LATERAL, s.side)
        s.preset(PlanScene.Preset.TOP); assertEquals(PlanScene.Side.TOP, s.side)
        s.preset(PlanScene.Preset.FRONT); s.rotateBy((Math.PI / PlanScene.ROTATE_PER_PX).toFloat(), 0f)    // media vuelta
        assertEquals(PlanScene.Side.BEHIND, s.side)
    }

    @Test
    fun dragDirectionsAndLimits() {
        val s = scene()
        val az = s.azimuth; val el = s.elevation
        s.rotateBy(100f, 0f); assertTrue(s.azimuth > az)                // a la derecha → gira a la derecha
        s.rotateBy(0f, -50f); assertTrue(s.elevation > el)              // hacia arriba → se inclina hacia arriba
        s.rotateBy(0f, -10_000f); assertEquals(PlanScene.MAX_ELEVATION, s.elevation, 1e-9)
        s.rotateBy(0f, 10_000f); assertEquals(PlanScene.MIN_ELEVATION, s.elevation, 1e-9)
        s.zoomBy(100.0); assertEquals(8.0, s.zoom, 1e-9)
    }

    @Test
    fun twoFingerPanMovesTheWholeViewAndAPresetCentresItAgain() {
        val s = scene()
        s.layout(1280, 720, 24f, 64f, 28f)
        val before = s.project(turbine.bladeRoot)
        s.panBy(0f, 150f)                                              // dos dedos hacia abajo: se ve más arriba de la turbina
        val after = s.project(turbine.bladeRoot)
        assertEquals(before.x, after.x, 1e-3f)
        assertEquals(before.y + 150f, after.y, 1e-3f)
        s.preset(PlanScene.Preset.FRONT)
        assertEquals(0.0, s.panX, 0.0); assertEquals(0.0, s.panY, 0.0)
    }

    @Test
    fun progressFollowsTheDroneForwardAndNeverJumpsBack() {
        val s = scene()
        var idx = 0
        for (i in wps.indices step 3) {
            idx = s.progressIndex(wps[i].point, idx)
            assertEquals("en el waypoint $i", i, idx)
        }
        // Volver a pasar cerca de un waypoint anterior no hace retroceder el avance.
        assertEquals(idx, s.progressIndex(wps[0].point, idx))
    }
}
