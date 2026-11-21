package mx.com.nexusio.fly.drone.onestop

import mx.com.nexusio.fly.drone.onestop.flightcore.ReturnHomeSafety
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.FailsafeAction
import dji.sdk.keyvalue.value.flightcontroller.GoHomePathMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Lo que hace falta del SDK para la vuelta a casa (interfaz para poder probarlo sin dron). */
interface ReturnHomePort {
    /** Punto de despegue (latitud, longitud), o null si el dron no lo tiene. */
    suspend fun homeLocation(): Pair<Double, Double>?

    /** Alturas de RTH que acepta el dron (rango de la clave y límite de altura), o null si no responde. */
    suspend fun rthHeightRange(): IntRange?

    /** Ante pérdida de enlace en vuelo manual: volver a casa. */
    suspend fun setFailsafeGoHome()

    /** RTH a altura fija (sube, recta y baja), la que modela [ReturnHomeSafety]; no todos los drones la tienen. */
    suspend fun setFixedHeightPath()

    suspend fun setRthHeight(meters: Int)

    /**
     * Pone el punto de casa en la posición actual del dron (botón H). No se restaura al salir: es un lugar, no un ajuste.
     * La altitud de referencia no cambia (sigue siendo la del despegue), así que las medidas siguen valiendo.
     */
    suspend fun setHomeToAircraftLocation() {
        throw UnsupportedOperationException("set home not supported")
    }

    /** Devuelve la vuelta a casa a como estaba antes de cambiarla; devuelve lo que no pudo restaurar. */
    suspend fun restorePrevious(): List<String> = emptyList()
}

/**
 * Vuelta a casa del One Stop. La app auditada deja la RTH a 20 m y, ante pérdida de enlace, el dron se queda junto a
 * la pala hasta la batería baja; desde detrás del rotor eso acaba en contacto (simulación). Aquí, al abrir el One Stop:
 * ante pérdida de enlace se vuelve a casa (en la misión lo fija el KMZ con `goBack`), por la ruta de altura fija, y la
 * altura de RTH la va cambiando [ReturnHomeHeightController] según el tramo de la misión.
 */
object ReturnHomeSetup {
    /** Un paso que el dron rechazó, con el motivo; los opcionales no los tienen todos los drones. */
    data class Failure(val step: String, val message: String, val optional: Boolean = false)

    /** Antes de medir el dron va delante del rotor: vuelve a la altura a la que esté ([ReturnHomeSafety.Profile.LEVEL]). */
    suspend fun prepare(port: ReturnHomePort, levelHeightM: Int = ReturnHomeSafety.DJI_MIN_RTH_HEIGHT_M): List<Failure> {
        val failures = ArrayList<Failure>()
        suspend fun step(name: String, optional: Boolean = false, action: suspend () -> Unit) {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += Failure(name, e.message ?: e.javaClass.simpleName, optional)
            }
        }
        step("RC lost → RTH") { port.setFailsafeGoHome() }
        step("RTH fixed height", optional = true) { port.setFixedHeightPath() }
        step("RTH height $levelHeightM m") { port.setRthHeight(levelHeightM) }
        return failures
    }

    /** Alturas de los dos perfiles dentro de lo que acepta el dron; [climbClamped] si no llega a pasar por encima del rotor. */
    data class Heights(val heights: ReturnHomeSafety.Heights, val climbClamped: Boolean)

    fun heightsWithin(range: IntRange?, climbNeededM: Int): Heights {
        val level = range?.let { ReturnHomeSafety.DJI_MIN_RTH_HEIGHT_M.coerceIn(it.first, it.last) } ?: ReturnHomeSafety.DJI_MIN_RTH_HEIGHT_M
        val climb = range?.let { climbNeededM.coerceAtMost(it.last) } ?: climbNeededM
        return Heights(ReturnHomeSafety.Heights(level, climb), climb < climbNeededM)
    }
}

/**
 * Cambia la altura de RTH del dron según el waypoint que tiene más cerca, siguiendo el programa de
 * [ReturnHomeSafety.schedule]. Solo escribe cuando cambia (dos veces por misión en las simulaciones); si el dron
 * rechaza el cambio, lo reintenta en la siguiente llamada.
 */
class ReturnHomeHeightController(private val port: ReturnHomePort) {
    var schedule: ReturnHomeSafety.Schedule? = null
        set(value) { field = value; applied = null }

    /** Última altura que el dron aceptó (null si aún ninguna con este programa). */
    var applied: Int? = null
        private set
    private var busy = false

    /** Llamar periódicamente con el waypoint más cercano; devuelve la altura escrita si hubo que cambiarla. */
    suspend fun update(index: Int): Int? {
        val wanted = schedule?.heightAt(index) ?: return null
        if (busy || wanted == applied) return null
        busy = true
        return try {
            port.setRthHeight(wanted)
            applied = wanted
            wanted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            busy = false
        }
    }
}

/** [ReturnHomePort] sobre las claves del controlador de vuelo de DJI MSDK v5, guardando los valores previos. */
class DjiReturnHomePort : ReturnHomePort {
    private val journal = RestoreJournal()

    class DjiReturnHomeException(error: IDJIError) : Exception(error.description())

    override suspend fun homeLocation(): Pair<Double, Double>? {
        if (read(KeyTools.createKey(FlightControllerKey.KeyIsHomeLocationSet)) != true) return null
        val h = read(KeyTools.createKey(FlightControllerKey.KeyHomeLocation)) ?: return null
        return h.latitude to h.longitude
    }

    override suspend fun rthHeightRange(): IntRange? {
        val r = read(KeyTools.createKey(FlightControllerKey.KeyGoHomeHeightRange)) ?: return null
        val min = r.min ?: return null
        var max = r.max ?: return null
        read(KeyTools.createKey(FlightControllerKey.KeyHeightLimit))?.let { max = minOf(max, it) }
        return min..max
    }

    override suspend fun setFailsafeGoHome() = change("failsafe", KeyTools.createKey(FlightControllerKey.KeyFailsafeAction), FailsafeAction.GOHOME)

    override suspend fun setFixedHeightPath() = change("rth path", KeyTools.createKey(FlightControllerKey.KeyGoHomePathMode), GoHomePathMode.HEIGHT_FIXED)

    override suspend fun setRthHeight(meters: Int) = change("rth height", KeyTools.createKey(FlightControllerKey.KeyGoHomeHeight), meters)

    override suspend fun setHomeToAircraftLocation() = suspendCancellableCoroutine<Unit> { cont ->
        KeyManager.getInstance().performAction(
            KeyTools.createKey(FlightControllerKey.KeyHomeLocationUsingCurrentAircraftLocation),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) { if (cont.isActive) cont.resume(Unit) }
                override fun onFailure(error: IDJIError) { if (cont.isActive) cont.resumeWithException(DjiReturnHomeException(error)) }
            },
        )
    }

    override suspend fun restorePrevious(): List<String> = journal.restoreAll()

    private suspend fun <T : Any> change(name: String, key: DJIKey<T>, value: T) =
        journal.change(name, read = { read(key) }, write = { v -> write(key, v) }, value = value)

    private suspend fun <T> read(key: DJIKey<T>): T? = suspendCancellableCoroutine { cont ->
        KeyManager.getInstance().getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(t: T) { if (cont.isActive) cont.resume(t) }
            override fun onFailure(error: IDJIError) { if (cont.isActive) cont.resume(null) }
        })
    }

    private suspend fun <T> write(key: DJIKey<T>, value: T) = suspendCancellableCoroutine<Unit> { cont ->
        KeyManager.getInstance().setValue(key, value, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onFailure(error: IDJIError) { if (cont.isActive) cont.resumeWithException(DjiReturnHomeException(error)) }
        })
    }
}
