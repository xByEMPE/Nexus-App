package mx.com.nexusio.fly.drone.onestop.flightcore

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Archivo de resultado de una inspección One Stop: qué pala, cara y tramo de la pala muestra cada foto.
 *
 * (`ImageData`: pala, posición, cara, distancia a la raíz) pero solo lo planificado, y
 * empareja fotos y entradas por orden: si el dron no guarda una foto, todas las siguientes quedan desplazadas. Aquí cada
 * foto real se asigna al waypoint que el dron estaba ejecutando al guardarla ([PhotoRecord.assign]), y se registra lo
 * que de verdad pasó: archivo del dron, hora, posición, gimbal, distancia medida y exposición.
 *
 * Punta y raíz: no hay marca en la imagen; lo dice [PlannedPhoto.distanceToRootM] (0 = raíz, largo de pala = punta) y
 * el sentido de la cara (LE y TE de raíz a punta, SS y PS de punta a raíz).
 */
data class PlannedPhoto(
    /** Orden de disparo en la misión, desde 1 (como `imageIndex` del original). */
    val seq: Int,
    /** Índice del waypoint en el KMZ. */
    val waypointIndex: Int,
    val componentIndex: ComponentIndex,
    val bladePosition: BladePosition,
    val pathIndex: PathIndex,
    /** Metros desde la raíz de la pala hasta el punto fotografiado. */
    val distanceToRootM: Double,
    /** Distancia planificada a la pala (m). */
    val focusDistanceM: Double,
    val planned: GeoPoint,
    val gimbalPitchDeg: Double,
    val yawDeg: Double,
) {
    companion object {
        /** Fotos en el orden en que las dispara el KMZ: waypoint a waypoint y, dentro de cada uno, acción a acción. */
        fun fromPlan(waypoints: List<WaypointGeo>): List<PlannedPhoto> {
            val out = ArrayList<PlannedPhoto>()
            waypoints.forEachIndexed { i, w ->
                for (a in w.waypointActions) if (a.takePhoto) out += PlannedPhoto(
                    out.size + 1, i, w.componentIndex, w.bladePosition, w.pathIndex,
                    a.distanceToRoot, a.focusDistance, w.point, a.pitch, a.yaw,
                )
            }
            return out
        }
    }
}

/** Lo que entrega el dron al guardar una foto, más el estado del dron en ese momento. */
data class CaptureInfo(
    val timeIso: String,
    /** Número de archivo del dron (`GeneratedMediaFileInfo.index`), carpeta y número dentro de ella. */
    val fileIndex: Int?,
    val dirNo: Int?,
    val fileNo: Int?,
    /** Nombre que pone DJI sin el sufijo de lente: `DJI_<fecha y hora>_<número>`. */
    val expectedFileName: String?,
    /** Waypoint que ejecutaba el dron al guardarla (null si el dron no lo informó). */
    val waypointAtCapture: Int?,
    val position: GeoPoint?,
    val yawDeg: Double?,
    val gimbalPitchDeg: Double?,
    /** Distancia del sensor al disparar (láser o visión) y cuál; null sin lectura. */
    val sensorDistanceM: Double?,
    val sensor: String?,
    /** Distancia del dron a la turbina según el modelo (la única para el Mavic 3E cuando la visión no ve la pala). */
    val modelClearanceM: Double?,
    val iso: Int?,
    val shutterS: Double?,
    val aperture: Int?,
)

class PhotoRecord(
    val missionId: String,
    /** Carpeta de la misión en la tarjeta (sufijo que se le pone a la carpeta de DJI). */
    val folder: String,
    val planned: List<PlannedPhoto>,
    private val header: Map<String, Any?>,
) {
    enum class Status { CAPTURED, MISSING, EXTRA }

    data class Entry(val planned: PlannedPhoto?, val capture: CaptureInfo?, val status: Status)

    private val captures = HashMap<Int, CaptureInfo>()        // seq → captura
    private val extras = ArrayList<CaptureInfo>()
    private var lastSeq = 0

    /**
     * Asigna una foto guardada a su foto planificada. La foto se dispara al llegar al waypoint, pero el aviso de "foto
     * guardada" llega un poco después y el dron puede haber avanzado ya: se busca la primera foto pendiente en los
     * waypoints [current − [lag], current]. Sin waypoint informado, se toma la siguiente pendiente en orden.
     * Devuelve la foto planificada asignada, o null si la foto no corresponde a ninguna (foto manual o fuera de plan).
     */
    fun assign(capture: CaptureInfo, lag: Int = 2): PlannedPhoto? {
        val wp = capture.waypointAtCapture
        val pending = planned.filter { it.seq > lastSeq && it.seq !in captures }
        val match = if (wp == null) pending.firstOrNull()
            else pending.firstOrNull { it.waypointIndex in (wp - lag)..wp }
        if (match == null) { extras += capture; return null }
        captures[match.seq] = capture
        lastSeq = match.seq
        return match
    }

    /** Planificadas en orden (capturadas o no) y, al final, las fotos que no correspondían a ninguna. */
    fun entries(): List<Entry> =
        planned.map { p -> captures[p.seq]?.let { Entry(p, it, Status.CAPTURED) } ?: Entry(p, null, Status.MISSING) } +
            extras.map { Entry(null, it, Status.EXTRA) }

    val capturedCount get() = captures.size

    fun toJson(finished: Boolean): JSONObject {
        val o = JSONObject()
        header.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        o.put("missionId", missionId).put("folder", folder).put("finished", finished)
            .put("plannedPhotos", planned.size).put("capturedPhotos", captures.size).put("extraPhotos", extras.size)
        o.put("images", JSONArray().apply {
            for (e in entries()) put(JSONObject().apply {
                put("status", e.status.name.lowercase(Locale.ROOT))
                e.planned?.let { p ->
                    put("seq", p.seq)
                    put("imageName", "$folder-${p.seq}")
                    put("waypointIndex", p.waypointIndex)
                    put("componentIndex", p.componentIndex.name)
                    put("bladePosition", p.bladePosition.name)
                    put("componentView", p.pathIndex.name)
                    put("distanceToRoot", r(p.distanceToRootM))
                    put("distanceToComponent", r(p.focusDistanceM))
                    put("planned", JSONObject().put("lat", p.planned.lat).put("lon", p.planned.lon).put("alt", r(p.planned.alt))
                        .put("gimbalPitch", r(p.gimbalPitchDeg)).put("yaw", r(p.yawDeg)))
                }
                e.capture?.let { c ->
                    put("capture", JSONObject().apply {
                        put("time", c.timeIso)
                        put("fileIndex", c.fileIndex ?: JSONObject.NULL); put("dirNo", c.dirNo ?: JSONObject.NULL); put("fileNo", c.fileNo ?: JSONObject.NULL)
                        put("expectedFileName", c.expectedFileName ?: JSONObject.NULL)
                        put("waypointAtCapture", c.waypointAtCapture ?: JSONObject.NULL)
                        c.position?.let { put("lat", it.lat).put("lon", it.lon).put("alt", r(it.alt)) }
                        put("yaw", c.yawDeg?.let(::r) ?: JSONObject.NULL); put("gimbalPitch", c.gimbalPitchDeg?.let(::r) ?: JSONObject.NULL)
                        put("sensorDistance", c.sensorDistanceM?.let(::r) ?: JSONObject.NULL); put("sensor", c.sensor ?: JSONObject.NULL)
                        put("modelClearance", c.modelClearanceM?.let(::r) ?: JSONObject.NULL)
                        put("iso", c.iso ?: JSONObject.NULL); put("shutter", c.shutterS ?: JSONObject.NULL); put("aperture", c.aperture ?: JSONObject.NULL)
                    })
                }
            })
        })
        return o
    }

    private fun r(v: Double) = Math.round(v * 100) / 100.0

    companion object {
        /** `DJI_<yyyyMMddHHmmss>_<0001>` (el sufijo de lente, `_W`, `_V`…, depende del dron). */
        fun djiFileName(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, index: Int) =
            String.format(Locale.ROOT, "DJI_%04d%02d%02d%02d%02d%02d_%04d", year, month, day, hour, minute, second, index)
    }
}
