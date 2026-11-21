package mx.com.nexusio.fly.drone.onestop

import mx.com.nexusio.fly.drone.onestop.flightcore.PhotoRecord
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CustomExpandNameSettings
import dji.sdk.keyvalue.value.camera.GeneratedMediaFileInfo
import dji.sdk.keyvalue.value.camera.MediaFileType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EnCodingType
import dji.sdk.keyvalue.value.common.RelativePosition
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Lo que dice el dron de las fotos de la misión: la carpeta de la misión (sufijo con la fecha y
 * hora UTC), cada foto JPEG que guarda y el waypoint que ejecuta en cada momento.
 */
class DjiCaptureFeed {
    /** Foto guardada: número de archivo, carpeta, hora y exposición que informa el dron. */
    data class SavedPhoto(
        val fileIndex: Int?, val dirNo: Int?, val fileNo: Int?, val expectedFileName: String?,
        val iso: Int?, val shutterS: Double?, val aperture: Int?,
    )

    class DjiCaptureException(error: IDJIError) : Exception(error.description())

    /** Waypoint que ejecuta el dron (null antes de empezar o si no lo informa). */
    @Volatile var currentWaypoint: Int? = null
        private set

    private var onPhoto: ((SavedPhoto) -> Unit)? = null
    private val waylineListener = WaylineExecutingInfoListener { info -> currentWaypoint = info.currentWaypointIndex }

    /** Sufijo de carpeta de la misión: fecha y hora UTC, `yyyyMMddHHmmss`, como `setFolderNameExtension` del original. */
    fun newFolderName(now: Date = Date()): String =
        SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)

    /**
     * Carpeta nueva para la misión y sin sufijo en los archivos (la foto ID deja puesto "ID"): así cada misión tiene su
     * carpeta y las fotos conservan el nombre de DJI.
     */
    suspend fun prepareFolder(folder: String) {
        setName(CameraKey.KeyCustomExpandDirectoryNameSettings, folder, forceCreateFolder = true)
        setName(CameraKey.KeyCustomExpandFileNameSettings, "", forceCreateFolder = false)
    }

    /** Al terminar: quita los sufijos para que el resto de vuelos (Three Stop) no los herede. */
    suspend fun resetNames() {
        runCatching { setName(CameraKey.KeyCustomExpandDirectoryNameSettings, "", forceCreateFolder = false) }
        runCatching { setName(CameraKey.KeyCustomExpandFileNameSettings, "", forceCreateFolder = false) }
    }

    fun start(onPhoto: (SavedPhoto) -> Unit) {
        this.onPhoto = onPhoto
        currentWaypoint = null
        WaypointMissionManager.getInstance().addWaylineExecutingInfoListener(waylineListener)
        KeyManager.getInstance().listen(KeyTools.createKey(CameraKey.KeyNewlyGeneratedMediaFile, ComponentIndexType.LEFT_OR_MAIN), this) { _, info ->
            // Solo el JPEG: con RAW activado cada foto genera además un DNG, que contaría doble.
            if (info != null && info.type == MediaFileType.JPEG) this.onPhoto?.invoke(info.toSaved())
        }
    }

    fun stop() {
        onPhoto = null
        WaypointMissionManager.getInstance().removeWaylineExecutingInfoListener(waylineListener)
        KeyManager.getInstance().cancelListen(this)
    }

    private fun GeneratedMediaFileInfo.toSaved(): SavedPhoto {
        val t = createTime
        val name = if (t?.year != null && index != null)
            PhotoRecord.djiFileName(t.year, t.month ?: 0, t.day ?: 0, t.hour ?: 0, t.minute ?: 0, t.second ?: 0, index) else null
        return SavedPhoto(index, dir_no, file_no, name, iso, shutterTime, aperture)
    }

    private suspend fun setName(info: dji.sdk.keyvalue.key.DJIKeyInfo<CustomExpandNameSettings>, content: String, forceCreateFolder: Boolean) {
        val settings = CustomExpandNameSettings().apply {
            customContent = content; this.forceCreateFolder = forceCreateFolder; priority = 0
            encodingType = EnCodingType.UTF8; relativePosition = RelativePosition.POSITION_END
        }
        suspendCancellableCoroutine<Unit> { cont ->
            KeyManager.getInstance().setValue(KeyTools.createKey(info, ComponentIndexType.LEFT_OR_MAIN), settings, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onFailure(error: IDJIError) { if (cont.isActive) cont.resumeWithException(DjiCaptureException(error)) }
            })
        }
    }
}
