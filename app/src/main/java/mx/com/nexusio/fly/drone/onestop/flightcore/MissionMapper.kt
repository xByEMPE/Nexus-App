package mx.com.nexusio.fly.drone.onestop.flightcore

/** Traduce la salida georreferenciada del planificador ([WaypointGeo]) al plan que consume el [MissionPort]. */
object MissionMapper {

    fun toMissionPlan(id: String, speedMps: Double, waypoints: List<WaypointGeo>): MissionPlan {
        var lastPitch = 0.0
        val planned = waypoints.map { w ->
            val acts = w.waypointActions.map {
                PlannedAction(
                    yawDeg = Angle.normalize(it.yaw, -180.0, 180.0),
                    gimbalPitchDeg = it.pitch,
                    takePhoto = it.takePhoto,
                    rotateGimbal = it.rotateGimbal,
                    rotateDroneYaw = it.rotateDroneYaw,
                )
            }
            val pitch = acts.firstOrNull { it.rotateGimbal }?.gimbalPitchDeg ?: lastPitch
            lastPitch = acts.lastOrNull { it.rotateGimbal }?.gimbalPitchDeg ?: lastPitch
            PlannedWaypoint(
                position = w.point,
                yawDeg = Angle.normalize(w.droneYaw, -180.0, 180.0),
                gimbalPitchDeg = pitch,
                takePhoto = acts.any { it.takePhoto },
                hoverSeconds = w.pauseSeconds,
                actions = acts,
                speedMps = w.velocity,
                // Como el original: solo se para al empezar y terminar cada recorrido, o si el plan lo pide;
                // en el resto el dron pasa sin detenerse y la foto se dispara al llegar al punto.
                stop = w.stop || w.isFirstInspectionPathPoint || w.isLastInspectionPathPoint,
                hoverBeforePhoto = w.pauseTiming == PauseTiming.BEFORE,
            )
        }
        return MissionPlan(id, speedMps, planned)
    }
}
