package mx.com.nexusio.fly.drone.onestop.job

import android.content.SharedPreferences

/**
 * Datos de turbina guardados por proyecto: el `.job` y la altura de buje se piden una sola vez
 * y se reutilizan en todas las turbinas del parque (mismo modelo de máquina), igual que la longitud de pala.
 */
class ProjectTurbineStore(private val prefs: SharedPreferences) {

    fun saveJob(projectId: String, job: TurbineJob) =
        prefs.edit().putString(jobKey(projectId), JobParser.toJson(job)).apply()

    fun loadJob(projectId: String?): TurbineJob? =
        projectId?.let { prefs.getString(jobKey(it), null) }?.let(JobParser::fromStoredJson)

    fun saveHubHeight(projectId: String, meters: Double) =
        prefs.edit().putFloat(hubKey(projectId), meters.toFloat()).apply()

    /** Altura de buje guardada, o null si el piloto aún no la ha ingresado para este proyecto. */
    fun loadHubHeight(projectId: String?): Double? =
        projectId?.takeIf { prefs.contains(hubKey(it)) }?.let { prefs.getFloat(hubKey(it), 0f).toDouble() }?.takeIf { it > 0.0 }

    fun hasHubHeight(projectId: String?) = loadHubHeight(projectId) != null

    /** Cámara elegida a mano para una plataforma con cámara intercambiable (clave del catálogo). */
    fun savePayload(droneKey: String, payload: String) = prefs.edit().putString(payloadKey(droneKey), payload).apply()
    fun loadPayload(droneKey: String): String? = prefs.getString(payloadKey(droneKey), null)

    /** Lente elegida para una cámara (clave de [DroneCatalog.cameraKey]); null = la predeterminada. */
    fun saveLens(cameraKey: String, lens: String) = prefs.edit().putString(lensKey(cameraKey), lens).apply()
    fun loadLens(cameraKey: String): String? = prefs.getString(lensKey(cameraKey), null)

    /** Dron elegido a mano cuando no se pudo reconocer el enlazado. */
    fun saveManualDrone(droneKey: String) = prefs.edit().putString(KEY_MANUAL_DRONE, droneKey).apply()
    fun loadManualDrone(): String? = prefs.getString(KEY_MANUAL_DRONE, null)

    private fun jobKey(projectId: String) = "onestop_job_$projectId"
    private fun hubKey(projectId: String) = "onestop_hub_height_$projectId"
    private fun payloadKey(droneKey: String) = "onestop_payload_$droneKey"
    private fun lensKey(cameraKey: String) = "onestop_lens_$cameraKey"

    private companion object { const val KEY_MANUAL_DRONE = "onestop_manual_drone" }
}
