package mx.com.nexusio.fly.drone.onestop.job

import android.content.Context
import mx.com.nexusio.fly.drone.onestop.flightcore.CameraFov
import mx.com.nexusio.fly.drone.onestop.flightcore.KmzAircraftInfo
import org.json.JSONObject
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/** Un dron del catálogo `assets/drone_catalog.json` con sus cámaras admitidas (nombre -> payloadEnumValue). */
data class DroneModel(
    val key: String,
    val droneEnum: Int,
    val droneSubEnum: Int,
    val payloads: Map<String, Int>,
) {
    val displayName get() = key.replace('_', ' ')
    /** Cuando el modelo admite una sola cámara no hay que preguntar cuál es. */
    val hasSinglePayload get() = payloads.size == 1
}

/** Una lente de una cámara con su campo de visión diagonal (grados) tal como está en el catálogo. */
data class CameraLens(val name: String, val diagonalFovDeg: Double) {
    val displayName get() = name.replace('_', ' ')
    val isThermal get() = name.contains("thermal")
    val isZoom get() = name.contains("zoom") || name.contains("tele")
    /** Por defecto la inspección usa la cámara gran angular: se descartan zoom/tele y térmica (salvo lente forzada por dron). */
    val isInspectionLens get() = !isThermal && !isZoom

    /** Valor de `wpml:payloadLensIndex` de la acción takePhoto (obligatorio en el WPML de DJI). */
    val wpmlLensIndex: String get() = when {
        isThermal -> "ir"
        name.contains("wide") -> "wide"
        name.contains("zoom") || name.contains("tele") -> "zoom"
        else -> "visable" // ortofoto Zenmuse P1: una sola lente de luz visible (ortografía del WPML de DJI)
    }
}

/** Dron + cámara + lente elegidos: todo lo que depende del equipo para armar el KMZ y el plan. */
data class AircraftSetup(
    val aircraft: KmzAircraftInfo,
    val cameraFov: CameraFov,
    val lens: CameraLens,
    /** Claves del catálogo del dron y de la cámara (deciden el sensor de distancia, ver `SensorProfile`). */
    val droneKey: String = "",
    val payload: String? = null,
)

/** Resultado de identificar el dron enlazado. */
sealed interface DroneDetection {
    data class Recognized(val drone: DroneModel, val payload: String) : DroneDetection
    /** Plataforma conocida (M400/M350/M300) pero cámara no reconocida: el piloto elige la cámara. */
    data class NeedsPayload(val drone: DroneModel) : DroneDetection
    /** Ni plataforma ni cámara reconocidas: el piloto elige dron y cámara. */
    data object Unknown : DroneDetection
}

/**
 * Catálogo de drones/cámaras (`values.json`): valores WPML, campo de visión por lente y detección
 * por `ProductType` + `CameraType` del SDK de DJI.
 */
class DroneCatalog(
    val drones: List<DroneModel>,
    /** Lentes por cámara: la clave es el dron (cámaras integradas) o el payload (Zenmuse). */
    private val lensesByCamera: Map<String, List<CameraLens>> = emptyMap(),
) {

    fun find(key: String?) = drones.firstOrNull { it.key == key }

    /** Clave con la que el catálogo guarda las lentes: el payload si es intercambiable, si no el dron. */
    fun cameraKey(drone: DroneModel, payload: String?): String =
        if (drone.hasSinglePayload) drone.key else payload ?: drone.key

    /**
     * Lentes con las que se inspecciona: la que fija [INSPECTION_LENS_BY_DRONE] (Matrice 4E: tele media, como el
     * original) o, si no hay, solo la gran angular (nunca zoom/tele ni térmica), de la más ancha
     * a la más estrecha; la primera es la predeterminada. La única cámara con varias opciones es el Zenmuse P1,
     * cuyas lentes intercambiables (24/35/50 mm) son de gran campo fijo. No depende del orden del JSON.
     */
    fun lensesFor(drone: DroneModel, payload: String?): List<CameraLens> {
        val all = lensesByCamera[cameraKey(drone, payload)].orEmpty()
        // Equipos en los que la app auditada inspecciona con otra lente (Matrice 4E: tele media 70 mm a zoom 3×).
        INSPECTION_LENS_BY_DRONE[drone.key]?.let { forced -> all.filter { it.name == forced }.takeIf { it.isNotEmpty() }?.let { return it } }
        return all.filter { it.isInspectionLens }.sortedByDescending { it.diagonalFovDeg }
    }

    /**
     * @param productType nombre del enum `ProductType` del dron (o null si no hay dron enlazado)
     * @param cameraType nombre del enum `CameraType` de la cámara principal
     */
    fun detect(productType: String?, cameraType: String?): DroneDetection {
        CAMERA_TO_DRONE[cameraType]?.let { key ->
            val d = find(key)
            if (d != null && d.hasSinglePayload) return DroneDetection.Recognized(d, d.payloads.keys.first())
        }
        val platform = find(PLATFORM_BY_PRODUCT[productType]) ?: return DroneDetection.Unknown
        val payload = PAYLOAD_BY_CAMERA[cameraType]
        return if (payload != null && payload in platform.payloads) DroneDetection.Recognized(platform, payload)
        else DroneDetection.NeedsPayload(platform)
    }

    /** Valores WPML del par dron + cámara, o null si el par no existe en el catálogo. */
    fun resolve(droneKey: String?, payload: String?): KmzAircraftInfo? {
        val d = find(droneKey) ?: return null
        val enum = (if (d.hasSinglePayload && payload == null) d.payloads.values.first() else d.payloads[payload]) ?: return null
        return KmzAircraftInfo(d.droneEnum, d.droneSubEnum, enum)
    }

    /**
     * Configuración completa del equipo. [lensName] es la lente elegida (null = la primera de luz visible).
     * Null si el par dron/cámara no existe o el catálogo no trae el campo de visión de la cámara.
     */
    fun setup(droneKey: String?, payload: String?, lensName: String?): AircraftSetup? {
        val drone = find(droneKey) ?: return null
        val info = resolve(droneKey, payload) ?: return null
        val actualPayload = payload ?: drone.payloads.keys.first()
        val lenses = lensesFor(drone, actualPayload)
        val lens = lenses.firstOrNull { it.name == lensName } ?: lenses.firstOrNull() ?: return null
        val fov = fovFromDiagonal(lens.diagonalFovDeg, aspectOf(actualPayload))
        return AircraftSetup(info.copy(payloadLensIndex = lens.wpmlLensIndex), fov, lens, drone.key, actualPayload)
    }

    companion object {
        const val ASSET_NAME = "drone_catalog.json"

        /** Lente de inspección forzada por dron (ver `CaptureProfile`): el M4E fotografía con la tele media, no la gran angular. */
        private val INSPECTION_LENS_BY_DRONE = mapOf("Matrice_4E" to "medium_tele_camera")

        // Cámaras integradas: la cámara identifica el dron completo.
        private val CAMERA_TO_DRONE = mapOf(
            "M4E" to "Matrice_4E", "M4T" to "Matrice_4T",
            "M3E" to "Mavic_3E", "M3T" to "Mavic_3T",
            "M30" to "Matrice_M30", "M30T" to "Matrice_M30T",
        )
        // Plataformas con cámara intercambiable: la plataforma sale del ProductType.
        private val PLATFORM_BY_PRODUCT = mapOf(
            "DJI_MATRICE_400" to "Matrice_400",
            "M350_RTK" to "Matrice_350_RTK",
            "M300_RTK" to "Matrice_300_RTK",
        )
        private val PAYLOAD_BY_CAMERA = mapOf(
            "ZENMUSE_H30" to "Zenmuse_H30", "ZENMUSE_H30T" to "Zenmuse_H30T", "ZENMUSE_P1" to "Zenmuse_P1",
        )

        /** Proporción del sensor (ancho:alto) para repartir el FOV diagonal. El P1 es 3:2; el resto 4:3. */
        private fun aspectOf(payload: String) = if (payload == "Zenmuse_P1") 3.0 to 2.0 else 4.0 to 3.0

        /**
         * Reparte el FOV diagonal en horizontal y vertical con la proporción del sensor (lente rectilínea):
         * tan(H/2) = tan(D/2)·w/√(w²+h²), tan(V/2) = tan(D/2)·h/√(w²+h²).
         * Con el P1 35 mm (63.5°, 3:2) da 54.5° x 37.9°, igual que `CameraFov.default()`.
         */
        fun fovFromDiagonal(diagonalDeg: Double, aspect: Pair<Double, Double>): CameraFov {
            val (w, h) = aspect
            val k = tan(Math.toRadians(diagonalDeg / 2.0)) / sqrt(w * w + h * h)
            fun deg(x: Double) = Math.toDegrees(2.0 * atan(x))
            return CameraFov(horizontal = deg(k * w), vertical = deg(k * h), diagonal = diagonalDeg)
        }

        fun fromAssets(context: Context): DroneCatalog =
            parse(context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() })

        /**
         * Formato de `values.json`: cada dron trae `wpml:droneEnumValue`/`wpml:droneSubEnumValue` y, o bien
         * `wpml:payloadEnumValue` + `cameraFov` (cámara integrada), o bien `payloads` con un objeto
         * `{wpml:payloadEnumValue, cameraFov}` por cámara intercambiable. `cameraFov` es lente -> FOV diagonal (°).
         */
        fun parse(text: String): DroneCatalog {
            val dronesObj = JSONObject(text.removePrefix("\uFEFF")).getJSONObject("drones")
            val lenses = LinkedHashMap<String, List<CameraLens>>()

            fun lensesOf(o: JSONObject): List<CameraLens> =
                o.optJSONObject("cameraFov")?.let { f -> f.keys().asSequence().map { CameraLens(it, f.getDouble(it)) }.toList() }.orEmpty()

            val drones = dronesObj.keys().asSequence().map { key ->
                val o = dronesObj.getJSONObject(key)
                val payloads = LinkedHashMap<String, Int>()
                if (o.has("payloads")) {
                    val p = o.getJSONObject("payloads")
                    p.keys().forEach { name ->
                        val po = p.getJSONObject(name)
                        payloads[name] = po.getInt("wpml:payloadEnumValue")
                        lenses[name] = lensesOf(po) // una cámara intercambiable se describe igual en todas las plataformas
                    }
                } else {
                    payloads[key] = o.getInt("wpml:payloadEnumValue")
                    lenses[key] = lensesOf(o)
                }
                DroneModel(key, o.getInt("wpml:droneEnumValue"), o.getInt("wpml:droneSubEnumValue"), payloads)
            }.toList()
            return DroneCatalog(drones, lenses)
        }
    }
}
