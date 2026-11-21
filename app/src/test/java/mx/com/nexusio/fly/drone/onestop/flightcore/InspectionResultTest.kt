package mx.com.nexusio.fly.drone.onestop.flightcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionResultTest {
    private val origin = GeoPoint(16.5, -95.0, 80.0)
    private val turbine = WindTurbine.fromDimensions(TurbineDimensions.SAMPLE)
    private val local = InspectionPlanner(BladeTwist.default(), turbine, InspectionDefinition(), CameraFov.default(), GimbalLimits())
        .plan(ComponentIndex.B, ComponentIndex.A, ComponentIndex.C)
    private val geo = local.map { WaypointGeo.from(origin, it) }
    private val photos = PlannedPhoto.fromPlan(geo)

    private fun capture(wp: Int?, n: Int) = CaptureInfo("2026-10-27T10:00:00Z", n, 100, n, "DJI_20261027100000_%04d".format(n), wp,
        null, null, null, null, null, null, null, null, null)

    @Test
    fun plannedPhotosFollowTheKmzOrderWithBladeFaceAndDistanceToRoot() {
        assertEquals(geo.sumOf { w -> w.waypointActions.count { it.takePhoto } }, photos.size)
        assertEquals((1..photos.size).toList(), photos.map { it.seq })
        assertEquals(photos.map { it.waypointIndex }.sorted(), photos.map { it.waypointIndex })
        // Orden de palas del plan: 4h (B), 12h (A), 8h (C); cada una con sus cuatro caras.
        assertEquals(listOf(ComponentIndex.B, ComponentIndex.A, ComponentIndex.C), photos.map { it.componentIndex }.distinct())
        assertEquals(setOf(PathIndex.LE, PathIndex.SS, PathIndex.TE, PathIndex.PS),
            photos.filter { it.componentIndex == ComponentIndex.A }.map { it.pathIndex }.filter { it != PathIndex.UNDEFINED }.toSet())
        // LE de la raíz a la punta: la distancia a la raíz crece.
        val le = photos.filter { it.componentIndex == ComponentIndex.B && it.pathIndex == PathIndex.LE }.map { it.distanceToRootM }
        assertTrue("$le", le.first() < le.last())
    }

    @Test
    fun eachPhotoGoesToTheWaypointTheDroneWasFlyingEvenIfOneIsLost() {
        fun p(seq: Int, wp: Int) = photos[0].copy(seq = seq, waypointIndex = wp)
        val plan = listOf(p(1, 0), p(2, 3), p(3, 6))
        val rec = PhotoRecord("m1", "20261027100000", plan, emptyMap())
        assertEquals(plan[0], rec.assign(capture(0, 1)))
        // La foto 2 no llega nunca; la 3 avisa de que se guardó cuando el dron va ya por el waypoint 7.
        assertEquals(plan[2], rec.assign(capture(7, 3)))
        assertEquals(listOf(PhotoRecord.Status.CAPTURED, PhotoRecord.Status.MISSING, PhotoRecord.Status.CAPTURED), rec.entries().map { it.status })
        assertEquals(2, rec.capturedCount)
    }

    @Test
    fun aPhotoFarFromAnyPendingWaypointIsKeptAsExtra() {
        val rec = PhotoRecord("m1", "f", photos, emptyMap())
        rec.assign(capture(photos[0].waypointIndex, 1))
        assertNull(rec.assign(capture(photos.last().waypointIndex + 50, 2)))
        assertEquals(1, rec.entries().count { it.status == PhotoRecord.Status.EXTRA })
    }

    @Test
    fun withoutWaypointInfoPhotosAreTakenInOrder() {
        val rec = PhotoRecord("m1", "f", photos, emptyMap())
        assertEquals(photos[0], rec.assign(capture(null, 1)))
        assertEquals(photos[1], rec.assign(capture(null, 2)))
    }

    @Test
    fun jsonCarriesTheAuditedFieldsPlusWhatReallyHappened() {
        val rec = PhotoRecord("m1", "20261027100000", photos, mapOf("turbine" to "WTG 01", "bladeMapping" to "12h=A 4h=B 8h=C"))
        rec.assign(capture(photos[0].waypointIndex, 7))
        val o = rec.toJson(finished = true)
        assertEquals("WTG 01", o.getString("turbine"))
        assertEquals(photos.size, o.getInt("plannedPhotos"))
        assertEquals(1, o.getInt("capturedPhotos"))
        val first = o.getJSONArray("images").getJSONObject(0)
        assertEquals("20261027100000-1", first.getString("imageName"))
        assertEquals("B", first.getString("componentIndex"))
        assertEquals("FOUR", first.getString("bladePosition"))
        assertTrue(first.has("distanceToRoot"))
        assertEquals("DJI_20261027100000_0007", first.getJSONObject("capture").getString("expectedFileName"))
        assertEquals("missing", o.getJSONArray("images").getJSONObject(1).getString("status"))
        assertEquals("DJI_20261027093005_0042", PhotoRecord.djiFileName(2026, 10, 27, 9, 30, 5, 42))
    }
}
