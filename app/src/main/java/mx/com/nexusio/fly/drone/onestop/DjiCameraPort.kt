package mx.com.nexusio.fly.drone.onestop

import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraAperture
import dji.sdk.keyvalue.value.camera.CameraExposureMode
import dji.sdk.keyvalue.value.camera.CameraISO
import dji.sdk.keyvalue.value.camera.CameraShutterSpeed
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.camera.PhotoSize
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [CameraPort] sobre las claves de cámara de DJI MSDK v5, en la lente del perfil. Antes de cambiar cada clave por
 * primera vez lee su valor actual del dron y lo guarda; [restorePrevious] lo repone al salir del One Stop.
 */
class DjiCameraPort(lens: CaptureLens) : CameraPort {
    private val journal = SettingsJournal<String, Pair<DJIKey<Any>, Any>>()

    private val djiLens = when (lens) {
        CaptureLens.DEFAULT -> CameraLensType.CAMERA_LENS_DEFAULT
        CaptureLens.WIDE -> CameraLensType.CAMERA_LENS_WIDE
        CaptureLens.ZOOM -> CameraLensType.CAMERA_LENS_ZOOM
    }

    class DjiCameraException(error: IDJIError) : Exception(error.description())

    /** Ajuste en curso: identifica la entrada del registro de valores previos (uno por tipo de ajuste). */
    private var current = ""

    override suspend fun apply(setting: CameraSetting) {
        current = setting.javaClass.simpleName
        applyToDrone(setting)
    }

    private suspend fun applyToDrone(setting: CameraSetting) = when (setting) {
        is CameraSetting.VideoSource -> set(
            KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, ComponentIndexType.LEFT_OR_MAIN),
            if (setting.zoom) CameraVideoStreamSourceType.ZOOM_CAMERA else CameraVideoStreamSourceType.WIDE_CAMERA,
        )
        is CameraSetting.ZoomRatio -> set(lensKey(CameraKey.KeyCameraZoomRatios, CameraLensType.CAMERA_LENS_ZOOM), setting.ratio)
        CameraSetting.LargePhoto -> set(lensKey(CameraKey.KeyPhotoSize), PhotoSize.SIZE_LARGE)
        is CameraSetting.Dewarping -> set(lensKey(CameraKey.KeyDewarpingEnabled), setting.enabled)
        is CameraSetting.ManualExposureMode ->
            set(lensKey(CameraKey.KeyExposureMode), if (setting.manual) CameraExposureMode.MANUAL else CameraExposureMode.PROGRAM)
        is CameraSetting.MechanicalShutter -> set(lensKey(CameraKey.KeyMechanicalShutterEnabled), setting.enabled)
        is CameraSetting.Iso -> set(lensKey(CameraKey.KeyISO), iso(setting.iso))
        is CameraSetting.Shutter -> set(lensKey(CameraKey.KeyShutterSpeed), shutter(setting.denominator))
        is CameraSetting.Aperture -> set(lensKey(CameraKey.KeyAperture), aperture(setting.fNumber))
    }

    private fun <T> lensKey(info: dji.sdk.keyvalue.key.DJIKeyInfo<T>, lens: CameraLensType = djiLens): DJIKey<T> =
        KeyTools.createCameraKey(info, ComponentIndexType.LEFT_OR_MAIN, lens)

    override suspend fun restorePrevious(): List<String> {
        val failures = ArrayList<String>()
        for ((name, entry) in journal.restoreOrder()) {
            try {
                write(entry.first, entry.second)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "$name: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        journal.clear()
        return failures
    }

    /** Guarda el valor previo (la primera vez) y escribe el nuevo. Si no se puede leer, se cambia igual pero no se restaurará. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> set(key: DJIKey<T>, value: T) {
        val name = current
        if (!journal.has(name)) read(key)?.let { journal.recordIfFirst(name, (key as DJIKey<Any>) to (it as Any)) }
        write(key, value)
    }

    private suspend fun <T> read(key: DJIKey<T>): T? = suspendCancellableCoroutine { cont ->
        KeyManager.getInstance().getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(t: T) { if (cont.isActive) cont.resume(t) }
            override fun onFailure(error: IDJIError) { if (cont.isActive) cont.resume(null) }
        })
    }

    private suspend fun <T> write(key: DJIKey<T>, value: T) = suspendCancellableCoroutine<Unit> { cont ->
        KeyManager.getInstance().setValue(key, value, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onFailure(error: IDJIError) { if (cont.isActive) cont.resumeWithException(DjiCameraException(error)) }
        })
    }

    // Solo los valores que usan los perfiles; el compilador comprueba que existen en el SDK.
    private fun iso(v: Int) = when (v) {
        100 -> CameraISO.ISO_100
        200 -> CameraISO.ISO_200
        400 -> CameraISO.ISO_400
        800 -> CameraISO.ISO_800
        1600 -> CameraISO.ISO_1600
        3200 -> CameraISO.ISO_3200
        6400 -> CameraISO.ISO_6400
        else -> throw IllegalArgumentException("ISO $v no soportado")
    }

    private fun shutter(d: Int) = when (d) {
        8000 -> CameraShutterSpeed.SHUTTER_SPEED1_8000
        6400 -> CameraShutterSpeed.SHUTTER_SPEED1_6400
        5000 -> CameraShutterSpeed.SHUTTER_SPEED1_5000
        2500 -> CameraShutterSpeed.SHUTTER_SPEED1_2500
        1250 -> CameraShutterSpeed.SHUTTER_SPEED1_1250
        1000 -> CameraShutterSpeed.SHUTTER_SPEED1_1000
        800 -> CameraShutterSpeed.SHUTTER_SPEED1_800
        640 -> CameraShutterSpeed.SHUTTER_SPEED1_640
        else -> throw IllegalArgumentException("1/$d s no soportado")
    }

    private fun aperture(f: Double) = when (f) {
        2.8 -> CameraAperture.F2_8
        4.0 -> CameraAperture.F4
        5.6 -> CameraAperture.F5_6
        8.0 -> CameraAperture.F8
        else -> throw IllegalArgumentException("f/$f no soportado")
    }
}
