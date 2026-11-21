package mx.com.nexusio.fly.drone.onestop.job

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** Una fila de la tabla `wtgmFile` de Supabase: los datos para elegirla, sin el `.wtgm` completo. */
data class RemoteWtgm(
    val id: String,
    val windFarm: String,
    val manufacturer: String,
    val model: String,
    val towerHeightM: Double?,
    val rotorDiameterM: Double?,
    val bladeLengthM: Double?,
    val createdAt: String?,
) {
    /** "2026-10-25T17:18:33+00:00" → "25/10/2026" (vacío si no hay fecha legible). */
    val createdDay: String
        get() = createdAt?.take(10)?.split("-")?.takeIf { it.size == 3 }?.let { "${it[2]}/${it[1]}/${it[0]}" }.orEmpty()
}

class RemoteWtgmException(message: String) : Exception(message)

object RemoteWtgmParser {
    fun parseList(json: String): List<RemoteWtgm> {
        val array = try { JSONArray(json) } catch (e: Exception) { throw RemoteWtgmException("Respuesta inválida del servidor") }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            RemoteWtgm(
                id = id,
                windFarm = o.optString("wind_farm").takeUnless { o.isNull("wind_farm") }.orEmpty(),
                manufacturer = o.optString("manufacturer").takeUnless { o.isNull("manufacturer") }.orEmpty(),
                model = o.optString("wtg_model").takeUnless { o.isNull("wtg_model") }.orEmpty(),
                towerHeightM = o.number("tower_height_m"),
                rotorDiameterM = o.number("rotor_diameter_m"),
                bladeLengthM = o.number("blade_length_m"),
                createdAt = o.optString("created_at").takeUnless { o.isNull("created_at") || it.isEmpty() },
            )
        }
    }

    /** Saca el objeto `wtgm` de `[{"wtgm": {...}}]`, listo para `WtgmReader.parse`. */
    fun parseWtgm(json: String): String {
        val array = try { JSONArray(json) } catch (e: Exception) { throw RemoteWtgmException("Respuesta inválida del servidor") }
        val wtgm = array.optJSONObject(0)?.optJSONObject("wtgm") ?: throw RemoteWtgmException("El job ya no existe en la base de datos")
        return wtgm.toString()
    }

    /** Primero los del parque del proyecto, luego el resto; dentro de cada grupo los más recientes arriba. */
    fun sorted(list: List<RemoteWtgm>, windFarm: String?): List<RemoteWtgm> {
        val wanted = windFarm?.let(::normalize).orEmpty()
        return list.sortedWith(
            compareByDescending<RemoteWtgm> { wanted.isNotEmpty() && sameFarm(it.windFarm, windFarm) }
                .thenByDescending { it.createdAt.orEmpty() }
                .thenBy { normalize(it.windFarm) }
        )
    }

    fun sameFarm(a: String?, b: String?): Boolean {
        val x = a?.let(::normalize).orEmpty()
        return x.isNotEmpty() && x == b?.let(::normalize).orEmpty()
    }

    private fun normalize(s: String) =
        Normalizer.normalize(s.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("\\s+"), " ")

    private fun JSONObject.number(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key).takeUnless { it.isNaN() }
}

/** Lee los jobs de Supabase con la sesión del piloto (la misma que usa el resto de la app). */
class WtgmRemoteSource(
    private val supabaseUrl: String,
    private val anonKey: String,
    private val token: () -> String,
) {
    suspend fun list(): List<RemoteWtgm> = withContext(Dispatchers.IO) {
        RemoteWtgmParser.parseList(get("wtgmFile?select=id,wind_farm,manufacturer,wtg_model,tower_height_m,rotor_diameter_m,blade_length_m,created_at&order=created_at.desc"))
    }

    /** El `.wtgm` completo de un job, como texto JSON. */
    suspend fun download(id: String): String = withContext(Dispatchers.IO) {
        RemoteWtgmParser.parseWtgm(get("wtgmFile?select=wtgm&id=eq.${URLEncoder.encode(id, "UTF-8")}"))
    }

    private fun get(path: String): String {
        val connection = URL("$supabaseUrl/rest/v1/$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("apikey", anonKey)
            connection.setRequestProperty("Authorization", "Bearer ${token().ifEmpty { anonKey }}")
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) return connection.inputStream.bufferedReader().use { it.readText() }
            throw RemoteWtgmException(
                when (code) {
                    401, 403 -> "Sesión vencida o sin permiso. Vuelve a iniciar sesión"
                    else -> "Error del servidor ($code)"
                }
            )
        } catch (e: RemoteWtgmException) {
            throw e
        } catch (e: java.io.IOException) {
            throw RemoteWtgmException("Sin conexión con el servidor")
        } finally {
            connection.disconnect()
        }
    }
}
