package mx.com.nexusio.fly.drone.onestop.job

import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.KeyManager

/** Lee del dron enlazado su plataforma y cámara, y los traduce con el [DroneCatalog]. */
object DroneDetector {

    /** ¿Hay un dron enlazado? (el SDK reporta su ProductType). */
    fun isLinked(): Boolean = KeyManager.getInstance().getValue(KeyTools.createKey(ProductKey.KeyProductType)) != null

    /** Devuelve [DroneDetection.Unknown] si no hay dron enlazado o no se reconoce. */
    fun detect(catalog: DroneCatalog): DroneDetection {
        val product = KeyManager.getInstance().getValue(KeyTools.createKey(ProductKey.KeyProductType))?.name
        val camera = KeyManager.getInstance()
            .getValue(KeyTools.createKey(CameraKey.KeyCameraType, ComponentIndexType.LEFT_OR_MAIN))?.name
        return catalog.detect(product, camera)
    }

    /**
     * Dron, cámara y lente definitivos (valores WPML + campo de visión): lo detectado, o la elección manual guardada.
     * Null si falta información (el piloto debe elegir dron/cámara en la sección Cargar JOB).
     */
    fun resolveSetup(catalog: DroneCatalog, store: ProjectTurbineStore): AircraftSetup? {
        val (droneKey, payload) = when (val d = detect(catalog)) {
            is DroneDetection.Recognized -> d.drone.key to d.payload
            is DroneDetection.NeedsPayload -> d.drone.key to store.loadPayload(d.drone.key)
            DroneDetection.Unknown -> store.loadManualDrone().let { it to it?.let(store::loadPayload) }
        }
        val drone = catalog.find(droneKey) ?: return null
        val cameraKey = catalog.cameraKey(drone, payload)
        return catalog.setup(droneKey, payload, store.loadLens(cameraKey))
    }
}
