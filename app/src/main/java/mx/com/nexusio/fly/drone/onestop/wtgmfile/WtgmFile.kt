package mx.com.nexusio.fly.drone.onestop.wtgmfile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import mx.com.nexusio.fly.drone.onestop.job.TurbineJob
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Lector del archivo .wtgm (Wind Turbine Geometry Model) generado por WtgCalibrator.
 *
 * Formato: JSON UTF-8. El checksum SHA-256 se calcula sobre la cadena canónica
 *   "hubLength=2.5000\nbladeFlangeRadius=1.2550\n..."   (orden de FIELDS, 4 decimales, Locale.US)
 * para detectar archivos corruptos o editados a mano. No es una firma de seguridad.
 *
 * Uso con Storage Access Framework (recomendado, no requiere permisos):
 *
 *   private val pickWtgm = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
 *       uri ?: return@registerForActivityResult
 *       when (val r = WtgmReader.read(this, uri)) {
 *           is WtgmResult.Ok    -> viewModel.loadTurbine(r.file)
 *           is WtgmResult.Error -> showError(r.message)
 *       }
 *   }
 *   pickWtgm.launch(arrayOf("application/octet-stream", "application/json", WtgmReader.MIME_TYPE))
 *
 * Para "Abrir con…" desde el gestor de archivos / correo, en AndroidManifest.xml:
 *
 *   <intent-filter>
 *       <action android:name="android.intent.action.VIEW" />
 *       <category android:name="android.intent.category.DEFAULT" />
 *       <category android:name="android.intent.category.BROWSABLE" />
 *       <data android:scheme="content" />
 *       <data android:scheme="file" />
 *       <data android:host="*" />
 *       <data android:mimeType="application/vnd.wtgsurvey.geometry+json" />
 *       <data android:mimeType="application/octet-stream" />
 *       <data android:pathPattern=".*\\.wtgm" />
 *       <data android:pathPattern=".*\\..*\\.wtgm" />
 *   </intent-filter>
 *
 * (Android suele entregar archivos de extensión desconocida como application/octet-stream,
 *  por eso la validación real ocurre aquí al parsear, no en el filtro.)
 */

data class WtgGeometry(
    val hubLength: Double,                 // m
    val bladeFlangeRadius: Double,         // m
    val bladeMaxChordWidth: Double,        // m
    val hubCenterToBladeFlange: Double,    // m
    val hubCenterToMaxChordWidth: Double,  // m
    val bladeTiltAngle: Double,            // °
    val rotorAxisAngle: Double,            // °
    val towerCenterToHubCenter: Double,    // m
    val towerBottomRadius: Double,         // m
    val towerTopRadius: Double,            // m
    val nacelleThickness: Double,          // m
) {
    fun asMap(): Map<String, Double> = linkedMapOf(
        "hubLength" to hubLength,
        "bladeFlangeRadius" to bladeFlangeRadius,
        "bladeMaxChordWidth" to bladeMaxChordWidth,
        "hubCenterToBladeFlange" to hubCenterToBladeFlange,
        "hubCenterToMaxChordWidth" to hubCenterToMaxChordWidth,
        "bladeTiltAngle" to bladeTiltAngle,
        "rotorAxisAngle" to rotorAxisAngle,
        "towerCenterToHubCenter" to towerCenterToHubCenter,
        "towerBottomRadius" to towerBottomRadius,
        "towerTopRadius" to towerTopRadius,
        "nacelleThickness" to nacelleThickness,
    )

    companion object {
        /** Valores genéricos (los de la tabla original) por si no hay medición. */
        val GENERIC = WtgGeometry(2.5, 1.255, 3.8, 2.0, 12.4, 4.5, 9.0, 3.0, 3.0, 2.0, 2.5)
    }
}

data class WtgTurbineInfo(
    val id: String?,
    val type: String?,
    val windpark: String?,
    val powerRating: String?,
    val rotorDiameter: Double?,
    val towerHeight: Double?,
    val bladeLength: Double?,
    val bladeLengthRef: String,
)

data class WtgmFile(
    val formatVersion: Int,
    val createdAt: String,
    val turbine: WtgTurbineInfo,
    val geometry: WtgGeometry,
    val uncertainty: Map<String, Double?>,
    val method: String,
    val warnings: List<String>,
)

sealed interface WtgmResult {
    data class Ok(val file: WtgmFile) : WtgmResult
    data class Error(val message: String) : WtgmResult
}

class WtgmException(message: String) : Exception(message)

/**
 * Convierte el `.wtgm` en los datos de turbina que ya consume el planificador (mismas claves que el `.job`).
 * El `.wtgm` ya viene validado (checksum y rangos seguros), por eso no se vuelve a filtrar con los rangos del `.job`.
 */
fun WtgmFile.toTurbineJob(): TurbineJob = TurbineJob(
    name = turbine.id ?: turbine.type ?: "WTGM",
    model = turbine.type,
    manufacturer = null,
    dimensions = geometry.asMap(),
)

object WtgmReader {
    const val EXTENSION = "wtgm"
    const val MIME_TYPE = "application/vnd.wtgsurvey.geometry+json"
    const val SUPPORTED_VERSION = 1
    const val MAX_BYTES = 512 * 1024

    val FIELDS = listOf(
        "hubLength", "bladeFlangeRadius", "bladeMaxChordWidth", "hubCenterToBladeFlange",
        "hubCenterToMaxChordWidth", "bladeTiltAngle", "rotorAxisAngle", "towerCenterToHubCenter",
        "towerBottomRadius", "towerTopRadius", "nacelleThickness",
    )

    /** Rangos de seguridad: un valor fuera de aquí no se carga en la planeación de vuelo. */
    private val HARD_LIMITS = mapOf(
        "hubLength" to (0.5..12.0),
        "bladeFlangeRadius" to (0.3..4.0),
        "bladeMaxChordWidth" to (0.5..10.0),
        "hubCenterToBladeFlange" to (0.3..6.0),
        "hubCenterToMaxChordWidth" to (1.0..40.0),
        "bladeTiltAngle" to (-2.0..15.0),
        "rotorAxisAngle" to (0.0..20.0),
        "towerCenterToHubCenter" to (0.5..15.0),
        "towerBottomRadius" to (0.5..8.0),
        "towerTopRadius" to (0.5..6.0),
        "nacelleThickness" to (1.0..10.0),
    )

    fun read(context: Context, uri: Uri): WtgmResult = try {
        val name = displayName(context, uri)
        if (name != null && !name.endsWith(".$EXTENSION", ignoreCase = true)) {
            throw WtgmException("El archivo \"$name\" no es .$EXTENSION")
        }
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw WtgmException("No se pudo abrir el archivo")
        WtgmResult.Ok(stream.use { parse(it) })
    } catch (e: WtgmException) {
        WtgmResult.Error(e.message ?: "Archivo inválido")
    } catch (e: Exception) {
        WtgmResult.Error("Error leyendo .$EXTENSION: ${e.message}")
    }

    fun parse(input: InputStream): WtgmFile {
        val bytes = input.readBytes()
        if (bytes.size > MAX_BYTES) throw WtgmException("Archivo demasiado grande")
        return parse(String(bytes, Charsets.UTF_8))
    }

    fun parse(json: String): WtgmFile {
        val root = try { JSONObject(json) } catch (e: Exception) {
            throw WtgmException("No es un JSON válido")
        }
        if (root.optString("format") != "wtgm") throw WtgmException("Formato desconocido")
        val version = root.optInt("formatVersion", -1)
        if (version < 1 || version > SUPPORTED_VERSION) {
            throw WtgmException("Versión $version no soportada (máx. $SUPPORTED_VERSION). Actualiza la app.")
        }

        val g = root.optJSONObject("geometry") ?: throw WtgmException("Falta 'geometry'")
        val values = FIELDS.associateWith { f ->
            if (!g.has(f) || g.isNull(f)) throw WtgmException("Falta el campo $f")
            val v = g.getDouble(f)
            if (v.isNaN() || v.isInfinite()) throw WtgmException("Valor inválido en $f")
            v
        }

        val checksum = root.optJSONObject("checksum")
        val expected = checksum?.optString("value").orEmpty()
        if (checksum?.optString("algorithm") != "SHA-256" || expected.isEmpty()) {
            throw WtgmException("Falta checksum SHA-256")
        }
        if (!expected.equals(sha256Hex(canonicalString(values)), ignoreCase = true)) {
            throw WtgmException("Checksum no coincide: el archivo fue modificado o está dañado")
        }

        HARD_LIMITS.forEach { (f, range) ->
            val v = values.getValue(f)
            if (v !in range) throw WtgmException("$f = $v fuera de rango seguro $range")
        }

        val t = root.optJSONObject("turbine") ?: JSONObject()
        val u = root.optJSONObject("uncertainty") ?: JSONObject()
        val q = root.optJSONObject("quality") ?: JSONObject()
        val warnings = q.optJSONArray("warnings")?.let { arr -> List(arr.length()) { arr.optString(it) } }
            ?: emptyList()

        return WtgmFile(
            formatVersion = version,
            createdAt = root.optString("createdAt"),
            turbine = WtgTurbineInfo(
                id = t.optStringOrNull("id"),
                type = t.optStringOrNull("type"),
                windpark = t.optStringOrNull("windpark"),
                powerRating = t.optStringOrNull("powerRating"),
                rotorDiameter = t.optDoubleOrNull("rotorDiameter"),
                towerHeight = t.optDoubleOrNull("towerHeight"),
                bladeLength = t.optDoubleOrNull("bladeLength"),
                bladeLengthRef = t.optString("bladeLengthRef", "flange"),
            ),
            geometry = WtgGeometry(
                hubLength = values.getValue("hubLength"),
                bladeFlangeRadius = values.getValue("bladeFlangeRadius"),
                bladeMaxChordWidth = values.getValue("bladeMaxChordWidth"),
                hubCenterToBladeFlange = values.getValue("hubCenterToBladeFlange"),
                hubCenterToMaxChordWidth = values.getValue("hubCenterToMaxChordWidth"),
                bladeTiltAngle = values.getValue("bladeTiltAngle"),
                rotorAxisAngle = values.getValue("rotorAxisAngle"),
                towerCenterToHubCenter = values.getValue("towerCenterToHubCenter"),
                towerBottomRadius = values.getValue("towerBottomRadius"),
                towerTopRadius = values.getValue("towerTopRadius"),
                nacelleThickness = values.getValue("nacelleThickness"),
            ),
            uncertainty = FIELDS.associateWith { u.optDoubleOrNull(it) },
            method = q.optString("method", "manual"),
            warnings = warnings,
        )
    }

    /** Debe coincidir exactamente con canonicalString() de wtgmFormat.js. */
    fun canonicalString(values: Map<String, Double>): String =
        FIELDS.joinToString("\n") { f -> "$f=${String.format(Locale.US, "%.4f", values.getValue(f))}" }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
}
