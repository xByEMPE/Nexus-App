package mx.com.nexusio.fly.drone.onestop

import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

enum class AvoidanceDirection { UPWARD, DOWNWARD, HORIZONTAL }

/** Lo que hace falta del SDK para configurar la evasión de obstáculos (interfaz para poder probarlo sin dron). */
interface ObstacleAvoidancePort {
    suspend fun setBrakeMode()
    suspend fun setEnabled(direction: AvoidanceDirection, enabled: Boolean)
    suspend fun setBrakingDistance(direction: AvoidanceDirection, meters: Double)
    suspend fun setWarningDistance(direction: AvoidanceDirection, meters: Double)

    /** Devuelve la evasión de obstáculos a como estaba antes de cambiarla; devuelve lo que no pudo restaurar. */
    suspend fun restorePrevious(): List<String> = emptyList()
}

/**
 * Activa la evasión de obstáculos del propio dron en modo FRENO, como `enableObstacleAvoidanceBrakingWithDefaultValues`
 * del original: es el dron, no la app, quien frena si algo queda a menos de la distancia de frenado, también durante
 * la misión de waypoints. Sin esto el dron vuela con lo que el piloto haya dejado en DJI Pilot (incluso desactivado).
 */
object ObstacleAvoidanceSetup {
    data class DirectionSetting(val direction: AvoidanceDirection, val brakingM: Double, val warningM: Double)

    /** Valores del original: frena a 3 m en todas las direcciones; avisa a 16 m en horizontal y a 10 m arriba/abajo. */
    val DEFAULTS = listOf(
        DirectionSetting(AvoidanceDirection.UPWARD, brakingM = 3.0, warningM = 10.0),
        DirectionSetting(AvoidanceDirection.DOWNWARD, brakingM = 3.0, warningM = 10.0),
        DirectionSetting(AvoidanceDirection.HORIZONTAL, brakingM = 3.0, warningM = 16.0),
    )

    /** Un paso que el dron rechazó, con el motivo. */
    data class Failure(val step: String, val message: String)

    /**
     * Aplica primero el modo FRENO y luego, por dirección, activar + distancia de frenado + distancia de aviso.
     * Si un paso falla se sigue con los demás (mejor proteger lo que se pueda) y se devuelven los fallos.
     */
    suspend fun apply(port: ObstacleAvoidancePort, settings: List<DirectionSetting> = DEFAULTS): List<Failure> {
        val failures = ArrayList<Failure>()
        suspend fun step(name: String, action: suspend () -> Unit) {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += Failure(name, e.message ?: e.javaClass.simpleName)
            }
        }
        step("BRAKE") { port.setBrakeMode() }
        for (s in settings) {
            step("${s.direction} enabled") { port.setEnabled(s.direction, true) }
            step("${s.direction} braking ${s.brakingM} m") { port.setBrakingDistance(s.direction, s.brakingM) }
            step("${s.direction} warning ${s.warningM} m") { port.setWarningDistance(s.direction, s.warningM) }
        }
        return failures
    }
}

/**
 * [ObstacleAvoidancePort] sobre `PerceptionManager` de DJI MSDK v5. Guarda el valor previo de cada ajuste antes de
 * cambiarlo y [restorePrevious] lo repone al salir del One Stop, para que el método Three Stop quede como estaba.
 */
class DjiObstacleAvoidancePort : ObstacleAvoidancePort {
    private val pm get() = PerceptionManager.getInstance()
    private val journal = RestoreJournal()

    class DjiAvoidanceException(error: IDJIError) : Exception(error.description())

    private fun AvoidanceDirection.toDji() = when (this) {
        AvoidanceDirection.UPWARD -> PerceptionDirection.UPWARD
        AvoidanceDirection.DOWNWARD -> PerceptionDirection.DOWNWARD
        AvoidanceDirection.HORIZONTAL -> PerceptionDirection.HORIZONTAL
    }

    override suspend fun setBrakeMode() = journal.change(
        "type",
        read = { param<ObstacleAvoidanceType> { pm.getObstacleAvoidanceType(it) } },
        write = { v -> completion { pm.setObstacleAvoidanceType(v, it) } },
        value = ObstacleAvoidanceType.BRAKE,
    )

    override suspend fun setEnabled(direction: AvoidanceDirection, enabled: Boolean) = journal.change(
        "$direction enabled",
        read = { param<Boolean> { pm.getObstacleAvoidanceEnabled(direction.toDji(), it) } },
        write = { v -> completion { pm.setObstacleAvoidanceEnabled(v, direction.toDji(), it) } },
        value = enabled,
    )

    override suspend fun setBrakingDistance(direction: AvoidanceDirection, meters: Double) = journal.change(
        "$direction braking",
        read = { param<Double> { pm.getObstacleAvoidanceBrakingDistance(direction.toDji(), it) } },
        write = { v -> completion { pm.setObstacleAvoidanceBrakingDistance(v, direction.toDji(), it) } },
        value = meters,
    )

    override suspend fun setWarningDistance(direction: AvoidanceDirection, meters: Double) = journal.change(
        "$direction warning",
        read = { param<Double> { pm.getObstacleAvoidanceWarningDistance(direction.toDji(), it) } },
        write = { v -> completion { pm.setObstacleAvoidanceWarningDistance(v, direction.toDji(), it) } },
        value = meters,
    )

    override suspend fun restorePrevious(): List<String> = journal.restoreAll()

    /** Lee un valor del dron; null si no responde (entonces ese ajuste no se podrá restaurar). */
    private suspend fun <T> param(call: (CommonCallbacks.CompletionCallbackWithParam<T>) -> Unit): T? =
        suspendCancellableCoroutine { cont ->
            call(object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(t: T) { if (cont.isActive) cont.resume(t) }
                override fun onFailure(error: IDJIError) { if (cont.isActive) cont.resume(null) }
            })
        }

    private suspend fun completion(call: (CommonCallbacks.CompletionCallback) -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->
            call(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onFailure(error: IDJIError) { if (cont.isActive) cont.resumeWithException(DjiAvoidanceException(error)) }
            })
        }
}
