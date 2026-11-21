package mx.com.nexusio.fly.drone.onestop.flightcore.sim

import mx.com.nexusio.fly.drone.onestop.flightcore.CameraFov
import mx.com.nexusio.fly.drone.onestop.flightcore.PathType
import mx.com.nexusio.fly.drone.onestop.flightcore.Vec3
import mx.com.nexusio.fly.drone.onestop.flightcore.Waypoint3D
import mx.com.nexusio.fly.drone.onestop.flightcore.WindTurbine
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Genera un HTML autónomo (sin dependencias ni red) con la turbina, el plan y el vuelo simulado para revisarlos en 3D.
 * El visor es `src/test/resources/sim/viewer_template.html`; aquí solo se inyecta el JSON de la escena.
 */
object ViewerExporter {
    private const val TEMPLATE = "/sim/viewer_template.html"

    /** [sampleStride]: se conserva 1 de cada N muestras de trayectoria para que el HTML pese poco. */
    fun export(file: File, title: String, turbine: WindTurbine, waypoints: List<Waypoint3D>, report: SimReport, cam: CameraFov, sampleStride: Int = 2) {
        val template = ViewerExporter::class.java.getResourceAsStream(TEMPLATE)?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: error("falta el recurso $TEMPLATE")
        // `</` dentro de un <script> cerraría la etiqueta; el JSON no debería contenerlo, pero se escapa por seguridad.
        val json = toJson(title, turbine, waypoints, report, cam, sampleStride).toString().replace("</", "<\\/")
        file.parentFile?.mkdirs()
        file.writeText(template.replace("/*DATA*/null/*END*/", json), Charsets.UTF_8)
    }

    private fun r(v: Double) = Math.round(v * 100) / 100.0
    private fun vec(v: Vec3) = JSONArray().put(r(v.x)).put(r(v.y)).put(r(v.z))

    fun toJson(title: String, turbine: WindTurbine, waypoints: List<Waypoint3D>, report: SimReport, cam: CameraFov, sampleStride: Int = 2): JSONObject {
        val samples = JSONArray()
        report.samples.forEachIndexed { i, s ->
            if (i % sampleStride == 0 || i == report.samples.lastIndex)
                samples.put(JSONArray().put(r(s.t)).put(r(s.pos.x)).put(r(s.pos.y)).put(r(s.pos.z)).put(r(s.yawDeg)).put(r(s.gimbalPitchDeg)).put(s.nextWaypoint).put(r(s.distance)))
        }
        val solids = JSONArray()
        for (s in turbine.collisionSolids)
            solids.put(JSONObject().put("n", s.name).put("a", vec(s.base)).put("b", vec(s.top)).put("ra", r(s.rBase)).put("rb", r(s.rTop)))
        val wps = JSONArray()
        for (w in waypoints)
            wps.put(JSONObject().put("p", vec(w.point)).put("i", w.pathType == PathType.INSPECTION).put("b", w.bladePosition.name))
        val photos = JSONArray()
        for (p in report.photos) photos.put(JSONObject().put("p", vec(p.pos)).put("yaw", r(p.yawDeg)).put("pitch", r(p.pitchDeg)))
        val coverage = JSONArray()
        for (c in report.coveragePoints) coverage.put(JSONObject().put("p", vec(c.pos)).put("c", c.covered).put("b", c.blade.name))
        val cover = JSONObject()
        for ((b, v) in report.coverage) cover.put(b.name, v)

        return JSONObject()
            .put("title", title)
            .put("cam", JSONObject().put("h", cam.horizontal).put("v", cam.vertical))
            .put("bladeLength", turbine.dims.bladeLength)
            .put("hubHeight", turbine.dims.towerHeight)
            .put("solids", solids).put("waypoints", wps).put("photos", photos).put("coverage", coverage).put("samples", samples)
            .put("summary", JSONObject()
                .put("minDistance", r(report.minDistance)).put("minComponent", report.minDistanceComponent)
                .put("flightTime", r(report.flightTimeSec)).put("pathLength", r(report.pathLengthM))
                .put("maxTrackingError", r(report.maxTrackingErrorM)).put("rmsTrackingError", r(report.rmsTrackingErrorM))
                .put("waypoints", waypoints.size).put("photos", report.photos.size)
                .put("coverage", cover).put("violations", JSONArray(report.violations)))
    }
}
