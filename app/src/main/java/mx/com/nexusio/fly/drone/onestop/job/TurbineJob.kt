package mx.com.nexusio.fly.drone.onestop.job

import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineDimensions
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs

/** Datos de una turbina leídos de un archivo `.job` (ver docs/onestop_job_format.md). */
data class TurbineJob(
    val name: String,
    val model: String?,
    val manufacturer: String?,
    /** Solo los campos válidos del bloque `dimensions`, con la misma clave que el archivo. */
    val dimensions: Map<String, Double>,
)

sealed interface JobParseResult {
    /** [rejected]: campos de `dimensions` descartados (tipo, rango o coherencia); quedan genéricos. */
    data class Ok(val job: TurbineJob, val rejected: List<String>) : JobParseResult
    /** Archivo completo rechazado; [reason] es un mensaje para el piloto. */
    data class Invalid(val reason: String) : JobParseResult
}

object JobFormat {
    const val VERSION = 1
    const val MAX_BYTES = 256 * 1024
    const val ROTOR_DIAMETER = "rotorDiameter"
    const val TOWER_BOTTOM_R = "towerBottomRadius"
    const val TOWER_TOP_R = "towerTopRadius"

    /** Campos de `dimensions` y su rango aceptado (m o °). */
    val RANGES: Map<String, ClosedFloatingPointRange<Double>> = linkedMapOf(
        ROTOR_DIAMETER to 50.0..300.0,
        "hubLength" to 0.1..15.0,
        "bladeFlangeRadius" to 0.3..4.0,
        "bladeMaxChordWidth" to 1.0..10.0,
        "hubCenterToBladeFlange" to 0.0..10.0,
        "hubCenterToMaxChordWidth" to 1.0..40.0,
        "bladeTiltAngle" to 0.0..15.0,
        "rotorAxisAngle" to 0.0..15.0,
        "towerCenterToHubCenter" to 0.0..15.0,
        TOWER_BOTTOM_R to 0.5..10.0,
        TOWER_TOP_R to 0.5..10.0,
        "nacelleThickness" to 0.5..10.0,
    )
}

object JobParser {

    fun parse(text: String): JobParseResult {
        val root = try {
            JSONObject(text.removePrefix("\uFEFF").trim())
        } catch (e: JSONException) {
            return JobParseResult.Invalid("El archivo no es un JSON válido")
        }
        if (root.optInt("jobVersion", -1) != JobFormat.VERSION) {
            return JobParseResult.Invalid("Versión de job no compatible (se espera jobVersion = ${JobFormat.VERSION})")
        }
        val turbine = root.optJSONObject("turbine") ?: return JobParseResult.Invalid("Falta el bloque \"turbine\"")
        val name = turbine.optString("name", "").trim()
        if (name.isEmpty()) return JobParseResult.Invalid("Falta turbine.name")
        val dims = root.optJSONObject("dimensions") ?: return JobParseResult.Invalid("Falta el bloque \"dimensions\"")

        val accepted = LinkedHashMap<String, Double>()
        val rejected = ArrayList<String>()
        for ((field, range) in JobFormat.RANGES) {
            if (!dims.has(field)) continue
            val value = (dims.opt(field) as? Number)?.toDouble()
            if (value == null || !value.isFinite() || value !in range) rejected += field else accepted[field] = value
        }

        // Coherencia: la cima de la torre no puede ser más ancha que la base
        val bottom = accepted[JobFormat.TOWER_BOTTOM_R]
        val top = accepted[JobFormat.TOWER_TOP_R]
        if (bottom != null && top != null && top > bottom) {
            accepted.remove(JobFormat.TOWER_BOTTOM_R); accepted.remove(JobFormat.TOWER_TOP_R)
            rejected += JobFormat.TOWER_BOTTOM_R; rejected += JobFormat.TOWER_TOP_R
        }

        return JobParseResult.Ok(
            TurbineJob(name, turbine.optString("model", "").trim().ifEmpty { null },
                turbine.optString("manufacturer", "").trim().ifEmpty { null }, accepted),
            rejected,
        )
    }

    fun toJson(job: TurbineJob): String = JSONObject().apply {
        put("name", job.name); put("model", job.model); put("manufacturer", job.manufacturer)
        put("dimensions", JSONObject().apply { job.dimensions.forEach { (k, v) -> put(k, v) } })
    }.toString()

    fun fromStoredJson(json: String): TurbineJob? = try {
        val o = JSONObject(json)
        val d = o.getJSONObject("dimensions")
        TurbineJob(o.getString("name"), o.optString("model", "").ifEmpty { null }, o.optString("manufacturer", "").ifEmpty { null },
            d.keys().asSequence().associateWith { d.getDouble(it) })
    } catch (e: JSONException) { null }
}

/** Une los datos reales (longitud de pala, altura de buje, .job) con los genéricos de `TurbineDimensions.SAMPLE`. */
object TurbineDimensionsBuilder {

    /** La altura de buje equivale a `towerHeight`. El diámetro del rotor sale de la longitud de pala, no del job. */
    fun build(bladeLengthM: Double, hubHeightM: Double, job: TurbineJob?): TurbineDimensions {
        val g = TurbineDimensions.SAMPLE
        val j = job?.dimensions.orEmpty()
        return TurbineDimensions(
            rotorDiameter = bladeLengthM * 2.0,
            hubLength = j["hubLength"] ?: g.hubLength,
            bladeFlangeRadius = j["bladeFlangeRadius"] ?: g.bladeFlangeRadius,
            bladeMaxChordWidth = j["bladeMaxChordWidth"] ?: g.bladeMaxChordWidth,
            bladeTiltAngle = j["bladeTiltAngle"] ?: g.bladeTiltAngle,
            rotorAxisAngle = j["rotorAxisAngle"] ?: g.rotorAxisAngle,
            hubCenterToBladeFlange = j["hubCenterToBladeFlange"] ?: g.hubCenterToBladeFlange,
            hubCenterToMaxChordWidth = j["hubCenterToMaxChordWidth"] ?: g.hubCenterToMaxChordWidth,
            towerCenterToHubCenter = j["towerCenterToHubCenter"] ?: g.towerCenterToHubCenter,
            towerHeight = hubHeightM,
            towerBottomRadius = j[JobFormat.TOWER_BOTTOM_R] ?: g.towerBottomRadius,
            towerTopRadius = j[JobFormat.TOWER_TOP_R] ?: g.towerTopRadius,
            nacelleThickness = j["nacelleThickness"] ?: g.nacelleThickness,
        )
    }

    /** ¿El diámetro declarado en el job difiere del de la app más de [toleranceM]? */
    fun rotorDiameterMismatch(job: TurbineJob?, bladeLengthM: Double, toleranceM: Double = 1.0): Boolean {
        val declared = job?.dimensions?.get(JobFormat.ROTOR_DIAMETER) ?: return false
        return abs(declared - bladeLengthM * 2.0) > toleranceM
    }
}
