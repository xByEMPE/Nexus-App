package mx.com.nexusio.fly.drone.onestop.flightcore

import android.content.Context
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Adaptador de [MissionPort] sobre DJI MSDK v5 (misiones KMZ / WPML). */
class DjiMissionPort(
    context: Context,
    private val aircraft: KmzAircraftInfo = KmzAircraftInfo(),
) : MissionPort {

    private val kmzDir = File(context.applicationContext.cacheDir, "onestop_kmz")
    private val manager get() = WaypointMissionManager.getInstance()

    class DjiMissionException(val error: IDJIError) : Exception(error.description())

    override suspend fun upload(plan: MissionPlan, onProgress: (Double) -> Unit) {
        val kmz = KmzWaylineWriter.write(plan, kmzDir, aircraft)
        suspendCancellableCoroutine<Unit> { cont ->
            manager.pushKMZFileToAircraft(kmz.absolutePath, object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
                override fun onProgressUpdate(progress: Double) = onProgress(progress)
                override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onFailure(error: IDJIError) { if (cont.isActive) cont.resumeWithException(DjiMissionException(error)) }
            })
        }
    }

    // El id de misión que espera el SDK es el nombre del KMZ sin extensión (= MissionPlan.id).
    override suspend fun start(planId: String) = completion { manager.startMission(planId, it) }
    override suspend fun pause() = completion { manager.pauseMission(it) }
    override suspend fun resume() = completion { manager.resumeMission(it) }
    override suspend fun stop(planId: String) = completion { manager.stopMission(planId, it) }

    private suspend fun completion(call: (CommonCallbacks.CompletionCallback) -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->
            call(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onFailure(error: IDJIError) { if (cont.isActive) cont.resumeWithException(DjiMissionException(error)) }
            })
        }
}
