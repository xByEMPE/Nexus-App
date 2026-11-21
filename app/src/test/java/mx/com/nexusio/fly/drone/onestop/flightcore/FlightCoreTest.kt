package mx.com.nexusio.fly.drone.onestop.flightcore

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightCoreTest {
    private val dims = TurbineDimensions.SAMPLE
    private val turbine = WindTurbine.fromDimensions(dims)
    private val planner = InspectionPlanner(BladeTwist.default(), turbine, InspectionDefinition(), CameraFov.default(), GimbalLimits())

    private fun waypoints() = planner.plan(ComponentIndex.B, ComponentIndex.A, ComponentIndex.C)

    @Test
    fun plannerProducesWaypointsKeepingSafeDistance() {
        val wps = waypoints()
        assertTrue(wps.isNotEmpty())
        assertTrue(planner.minDistanceToTurbine(wps) >= 4.5)
    }

    @Test
    fun missionMapperKeepsCountAndNormalizesYaw() {
        val origin = GeoPoint(40.0, -3.0, 0.0)
        val geo = waypoints().map { WaypointGeo.from(origin, it) }
        val plan = MissionMapper.toMissionPlan("t", 1.0, geo)
        assertEquals(geo.size, plan.waypoints.size)
        assertTrue(plan.waypoints.all { it.yawDeg >= -180.0 && it.yawDeg <= 180.0 })
    }

    @Test
    fun signedGatewayAcceptsMappedPlan() {
        val origin = GeoPoint(40.0, -3.0, 0.0)
        val plan = MissionMapper.toMissionPlan("t", 1.0, waypoints().map { WaypointGeo.from(origin, it) })
        val gw = SignedMissionGateway(object : MissionPort {
            override suspend fun upload(plan: MissionPlan, onProgress: (Double) -> Unit) {}
            override suspend fun start(planId: String) {}
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun stop(planId: String) {}
        }, SafetyLimits(5.0, 3000, 90.0, 90.0), ByteArray(32) { it.toByte() })
        gw.seal(plan)
    }

    @Test
    fun kmzTakePhotoCarriesRequiredLensIndex() {
        val origin = GeoPoint(40.0, -3.0, 0.0)
        val plan = MissionMapper.toMissionPlan("lens", 1.0, waypoints().map { WaypointGeo.from(origin, it) })
        val dir = Files.createTempDirectory("kmzlens").toFile()
        val kmz = KmzWaylineWriter.write(plan, dir, KmzAircraftInfo(99, 0, 88, payloadLensIndex = "zoom"))
        ZipFile(kmz).use { z ->
            val text = z.getInputStream(z.getEntry("wpmz/waylines.wpml")).bufferedReader().readText()
            val photos = Regex("<wpml:actionActuatorFunc>takePhoto").findAll(text).count()
            assertTrue(photos > 0)
            assertEquals(photos, Regex("<wpml:payloadLensIndex>zoom</wpml:payloadLensIndex>").findAll(text).count())
            assertTrue(text.contains("<wpml:droneEnumValue>99</wpml:droneEnumValue>"))
            assertTrue(text.contains("<wpml:payloadEnumValue>88</wpml:payloadEnumValue>"))
        }
        dir.deleteRecursively()
    }

    @Test
    fun kmzContainsBothFilesWithValidXml() {
        val origin = GeoPoint(40.0, -3.0, 0.0)
        val plan = MissionMapper.toMissionPlan("kmztest", 1.0, waypoints().map { WaypointGeo.from(origin, it) })
        val dir = Files.createTempDirectory("kmz").toFile()
        val kmz = KmzWaylineWriter.write(plan, dir)
        ZipFile(kmz).use { z ->
            for (name in listOf("wpmz/template.kml", "wpmz/waylines.wpml")) {
                val entry = requireNotNull(z.getEntry(name)) { "missing $name" }
                val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder().parse(z.getInputStream(entry))
                assertEquals(plan.waypoints.size, doc.getElementsByTagName("Placemark").length)
            }
        }
        dir.deleteRecursively()
    }
}
