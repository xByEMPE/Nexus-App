package mx.com.nexusio.fly.drone.onestop

import kotlinx.coroutines.CancellationException

/** Lente de la cámara a la que van los ajustes. */
enum class CaptureLens { DEFAULT, WIDE, ZOOM }

/** Un escalón de exposición manual. [aperture] null = no se toca (lente de diafragma fijo). */
data class ExposureStep(val iso: Int, val shutterDenominator: Int, val aperture: Double?) {
    val label get() = "ISO $iso · 1/$shutterDenominator s" + (aperture?.let { " · f/" + if (it % 1.0 == 0.0) it.toInt() else it } ?: "")
}

/**
 * Cómo fotografía cada equipo durante la inspección, copiado de la app auditada
 * (`DroneAndCameraConfiguration`, `DroneSettingsFactory`, `CameraSettingsController`, `ExposureSettingsList*`,
 * `WaypointMissionConverter`).
 *
 * El dron vuela sin parar entre waypoints y dispara al pasar (ver `KmzWaylineWriter`); la calidad se protege con:
 * - Mavic 3E: gran angular con obturador MECÁNICO (sin rolling shutter) y corrección de distorsión de la lente.
 * - Matrice 4E: tele media (zoom 3×, ~70 mm, 48 MP) con obturador electrónico; para no emborronar usa velocidades
 *   muy rápidas (hasta 1/8000 s) y una pausa de 1 s tras mover el gimbal antes de disparar.
 * - En los dos: exposición MANUAL por escalones que nunca bajan de 1/640 s; con poca luz sube el ISO, no el tiempo.
 */
data class CaptureProfile(
    val lens: CaptureLens = CaptureLens.DEFAULT,
    /** Zoom óptico fijo de la lente ZOOM (null = no se toca). */
    val zoomRatio: Double? = null,
    /**
     * Mostrar la cámara zoom en el vídeo (el original lo hace en el M4E). Solo desde que termina la medición
     * ([CameraSetup.inspectionLens]): para medir y posicionar el dron el piloto ve el gran angular.
     */
    val useZoomStream: Boolean = false,
    /** Foto a tamaño grande (48 MP en el M4E). */
    val largePhoto: Boolean = false,
    val mechanicalShutter: Boolean = false,
    val dewarping: Boolean = false,
    /** Pausa entre girar el gimbal y disparar (s). */
    val gimbalSettleHoverS: Double = 0.0,
    /** Parar en todos los waypoints (el original solo lo hace con el Matrice 400). */
    val stopAtEachWaypoint: Boolean = false,
    /** Escalones de exposición, de más oscuro a más claro; vacío = exposición automática (+/- ajusta la compensación EV). */
    val exposureSteps: List<ExposureStep> = emptyList(),
) {
    val manualExposure get() = exposureSteps.isNotEmpty()

    companion object {
        /** `ExposureSettingsListM3E` del original (gran angular del Mavic 3E/3M). */
        val M3E_STEPS = listOf(
            ExposureStep(100, 1000, 8.0), ExposureStep(100, 800, 8.0), ExposureStep(100, 640, 8.0),
            ExposureStep(100, 1000, 5.6), ExposureStep(100, 800, 5.6), ExposureStep(100, 640, 5.6),
            ExposureStep(200, 640, 5.6), ExposureStep(400, 640, 5.6), ExposureStep(800, 640, 5.6),
            // Más claros para poca luz (nublado, atardecer): más ISO y después diafragma abierto.
            ExposureStep(1600, 640, 5.6), ExposureStep(1600, 640, 4.0), ExposureStep(1600, 640, 2.8),
            ExposureStep(3200, 640, 2.8), ExposureStep(6400, 640, 2.8),
        )

        /**
         * `ExposureSettingsListM4EZoom` del original. El original pide f/2.8, pero la tele media del M4E tiene diafragma
         * fijo f/2.8: no se envía (evita un rechazo del dron sin cambiar el resultado).
         */
        val M4E_ZOOM_STEPS = listOf(
            ExposureStep(100, 8000, null), ExposureStep(100, 6400, null), ExposureStep(100, 5000, null),
            ExposureStep(100, 2500, null), ExposureStep(100, 1250, null), ExposureStep(100, 640, null),
            ExposureStep(200, 640, null), ExposureStep(400, 640, null), ExposureStep(800, 640, null),
            // Más claros para poca luz.
            ExposureStep(1600, 640, null), ExposureStep(3200, 640, null), ExposureStep(6400, 640, null),
        )

        val MAVIC_3E = CaptureProfile(lens = CaptureLens.WIDE, mechanicalShutter = true, dewarping = true, exposureSteps = M3E_STEPS)

        val MATRICE_4E = CaptureProfile(
            lens = CaptureLens.ZOOM, zoomRatio = 3.0, useZoomStream = true, largePhoto = true,
            gimbalSettleHoverS = 1.0, exposureSteps = M4E_ZOOM_STEPS,
        )

        /** [droneKey] y [payload] son las claves del catálogo (`values.json`). Equipos no cubiertos: comportamiento previo. */
        fun forEquipment(droneKey: String, payload: String?): CaptureProfile = when (droneKey) {
            "Mavic_3E" -> MAVIC_3E
            "Matrice_4E" -> MATRICE_4E
            "Matrice_400" -> CaptureProfile(stopAtEachWaypoint = true)
            else -> CaptureProfile()
        }
    }
}

/** Un ajuste de cámara, independiente del SDK (lo traduce [DjiCameraPort]). */
sealed interface CameraSetting {
    /** Fuente del vídeo: cámara zoom ([zoom]) o gran angular. */
    data class VideoSource(val zoom: Boolean) : CameraSetting
    data class ZoomRatio(val ratio: Double) : CameraSetting
    data object LargePhoto : CameraSetting
    data class Dewarping(val enabled: Boolean) : CameraSetting
    data class ManualExposureMode(val manual: Boolean) : CameraSetting
    data class MechanicalShutter(val enabled: Boolean) : CameraSetting
    data class Iso(val iso: Int) : CameraSetting
    data class Shutter(val denominator: Int) : CameraSetting
    data class Aperture(val fNumber: Double) : CameraSetting
}

interface CameraPort {
    suspend fun apply(setting: CameraSetting)

    /**
     * Devuelve la cámara a como estaba antes del primer [apply] de cada ajuste (en orden inverso) y devuelve los que no
     * pudo restaurar. Así los ajustes del One Stop no se quedan en el dron para el método Three Stop.
     */
    suspend fun restorePrevious(): List<String> = emptyList()
}

/**
 * Valor previo de cada ajuste la primera vez que se cambia, para restaurarlo al salir. Los cambios siguientes del
 * mismo ajuste (p. ej. otro escalón de exposición) no sobrescriben el valor original.
 */
class SettingsJournal<K, V> {
    private val entries = LinkedHashMap<K, V>()

    fun has(key: K) = key in entries

    fun recordIfFirst(key: K, previous: V) {
        if (key !in entries) entries[key] = previous
    }

    /** Del último cambiado al primero: deshace en el orden inverso al aplicado. */
    fun restoreOrder(): List<Pair<K, V>> = entries.entries.reversed().map { it.key to it.value }

    fun clear() = entries.clear()
}

/**
 * Cambia ajustes del dron recordando cómo estaban, para dejarlo igual al salir del One Stop.
 * La primera vez que se cambia un ajuste lee su valor actual; [restoreAll] los repone en orden inverso.
 * Si no se puede leer el valor previo, el ajuste se cambia igual pero no se podrá restaurar.
 */
class RestoreJournal {
    private val journal = SettingsJournal<String, suspend () -> Unit>()

    suspend fun <T : Any> change(name: String, read: suspend () -> T?, write: suspend (T) -> Unit, value: T) {
        if (!journal.has(name)) read()?.let { previous -> journal.recordIfFirst(name) { write(previous) } }
        write(value)
    }

    /** Repone todo en orden inverso; devuelve los ajustes que no pudo restaurar con el motivo. */
    suspend fun restoreAll(): List<String> {
        val failures = ArrayList<String>()
        for ((name, restore) in journal.restoreOrder()) {
            try {
                restore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "$name: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        journal.clear()
        return failures
    }
}

/** Qué ajustes y en qué orden (el del original). */
object CameraSetup {
    /**
     * Al abrir la inspección: tamaño de foto y corrección de distorsión. El vídeo va al gran angular (con la tele cuesta
     * posicionar el dron para medir sin desorientarse); la tele llega con [inspectionLens].
     */
    fun preparation(p: CaptureProfile): List<CameraSetting> = buildList {
        // Con cámara zoom aparte (M4E) estos ajustes tocan la lente zoom y el dron puede pasar el vídeo a ella:
        // se aplican en [inspectionLens], ya con la calibración terminada y el plan cargado.
        if (!p.useZoomStream) {
            if (p.largePhoto) add(CameraSetting.LargePhoto)
            if (p.dewarping) add(CameraSetting.Dewarping(true))
        }
        addAll(measurementLens(p)) // el vídeo en gran angular va al final: nada posterior lo mueve
    }

    /** Vídeo en gran angular: para la foto ID, las 5 medidas y el vuelo manual después de la misión. */
    fun measurementLens(p: CaptureProfile): List<CameraSetting> = buildList {
        if (p.useZoomStream) add(CameraSetting.VideoSource(zoom = false))
    }

    /**
     * Terminadas las 5 medidas: vídeo de la cámara zoom y su zoom fijo, para ajustar el brillo viendo la imagen de las
     * fotos y volar la misión (las fotos del KMZ ya van a la lente zoom por `payloadLensIndex`).
     */
    fun inspectionLens(p: CaptureProfile): List<CameraSetting> = buildList {
        if (p.useZoomStream) add(CameraSetting.VideoSource(zoom = true))
        p.zoomRatio?.let { add(CameraSetting.ZoomRatio(it)) }
        if (p.useZoomStream) {
            if (p.largePhoto) add(CameraSetting.LargePhoto)
            if (p.dewarping) add(CameraSetting.Dewarping(true))
        }
    }

    /** `setInitialManualExposureControlCameraSettings` + `setExposure`: modo manual, obturador mecánico, ISO, tiempo, diafragma. */
    fun manualExposure(p: CaptureProfile, step: ExposureStep, includeMode: Boolean = true): List<CameraSetting> = buildList {
        if (includeMode) {
            add(CameraSetting.ManualExposureMode(true))
            if (p.mechanicalShutter) add(CameraSetting.MechanicalShutter(true))
        }
        add(CameraSetting.Iso(step.iso))
        add(CameraSetting.Shutter(step.shutterDenominator))
        step.aperture?.let { add(CameraSetting.Aperture(it)) }
    }

    /** Aplica en orden; si uno falla sigue con el resto y devuelve los que fallaron con el motivo. */
    suspend fun apply(port: CameraPort, settings: List<CameraSetting>): List<Pair<CameraSetting, String>> {
        val failures = ArrayList<Pair<CameraSetting, String>>()
        for (s in settings) {
            try {
                port.apply(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += s to (e.message ?: e.javaClass.simpleName)
            }
        }
        return failures
    }
}
