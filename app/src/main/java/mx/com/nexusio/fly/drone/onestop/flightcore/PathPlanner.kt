package mx.com.nexusio.fly.drone.onestop.flightcore

import kotlin.math.*

/**
 * Port de PathInspectionWaypointPlanner3D. `computeInspectionWaypoints` no pudo decompilarse con JADX
 * en modo normal; se reconstruyó desde el pseudo-código de registros del modo --fallback.
 */
object PathPlanner {
    private const val NO_RTK_TIP = 5.5
    private const val NO_RTK_ROOT = 3.0
    private const val PITCH_UP = 60.0
    private const val PITCH_DOWN = -60.0
    private const val SMALL_ROTOR_BLADE_LENGTH_LIMIT = 34.0
    private const val TOWER_CLEARANCE_MIN = 5.0
    private const val TOWER_CLEARANCE_STEP = 2.5
    private const val TOWER_CLEARANCE_MAX_EXTRA = 90.0
    private const val UNDERSIDE_DISTANCE_CORRECTION = 1.5
    private const val UNDERSIDE_EXPOSURE_RETREAT = 1.5
    private const val UNDERSIDE_ROOT_FRAME_TARGET = -2.0
    private const val UNDERSIDE_ROOT_FRAME_MAX_CLIMB = 8.0

    private val PS_SS = setOf(PathIndex.PS, PathIndex.SS)

    private fun isUnderSide(b: BladePosition, p: PathIndex) =
        (b == BladePosition.FOUR && p == PathIndex.SS) || (b == BladePosition.EIGHT && p == PathIndex.PS)

    // ───────── API principal ─────────
    fun computeInspectionWaypoints(
        pathIndex: PathIndex, pathDirection: PathDirection, maxWidth: Double,
        axisStart: Vec3, axisEnd: Vec3, waypointDirection: Vec3,
        bladePosition: BladePosition, componentIndex: ComponentIndex, bladeTwist: BladeTwist?,
        bladeRoot: Vec3, cameraFov: CameraFov, gimbalLimits: GimbalLimits,
        def: InspectionDefinition, thickness: BladeThickness,
        hubCenterToMaxChordWidth: Double, hubLength: Double,
        usingRtk: Boolean, stopAtEachWaypoint: Boolean, turbine: WindTurbine,
    ): List<Waypoint3D> {
        val out = ArrayList<Waypoint3D>()
        val gimbalPitch = gimbalPitch(bladePosition, pathIndex, gimbalLimits)

        // Sin RTK se ensanchan las holguras (la posición es peor): +5.5 m en punta, +3 m en raíz
        val obsDistTip = if (usingRtk) def.observationDistanceTip else def.observationDistanceTip + NO_RTK_TIP
        var pathSafety = if (usingRtk) def.pathSafetyDistance else def.pathSafetyDistance + NO_RTK_TIP
        val isLE = pathIndex == PathIndex.LE
        val isPS = pathIndex == PathIndex.PS
        val flange = thickness.bladeFlangeRadius
        if (isLE) pathSafety = hubLength + pathSafety + flange
        val minObsDistRoot = max(pathSafety, observationDistanceRoot(bladePosition, cameraFov, def, maxWidth, pathIndex))
        val obsDistRootIdl = if (usingRtk) def.observationDistanceRoot else def.observationDistanceRoot + NO_RTK_ROOT

        val under = isUnderSide(bladePosition, pathIndex)
        val obsDistStart = if (pathDirection == PathDirection.ROOT_TO_TIP) max(obsDistRootIdl, minObsDistRoot)
                           else if (under) obsDistTip + UNDERSIDE_DISTANCE_CORRECTION else obsDistTip
        val obsDistEnd = if (pathDirection == PathDirection.TIP_TO_ROOT) max(obsDistRootIdl, minObsDistRoot) else obsDistTip

        val angleOffset = observationAngleOffset(bladePosition, pathDirection, pathIndex, def)
        val rootTwist = if (pathIndex != PathIndex.TE || bladeTwist == null) 0.0 else bladeTwist.angleRoot
        val angleStart = if (pathDirection == PathDirection.ROOT_TO_TIP) def.observationAngleRoot + rootTwist
                         else def.observationAngleTip + (bladeTwist?.angleTip ?: 0.0)
        val angleMiddle = if (pathIndex in PS_SS || bladeTwist == null) 0.0 else bladeTwist.angleMiddle
        val angleEnd = if (pathDirection == PathDirection.TIP_TO_ROOT) def.observationAngleRoot + angleOffset + rootTwist
                       else def.observationAngleTip + (bladeTwist?.angleTip ?: 0.0)
        val yawMode = if (bladePosition == BladePosition.TWELVE || pathIndex in PS_SS) YawMode.TOWARD_POI else YawMode.FIXED

        // El original suma otros 1.5 m aquí (UNDER_SIDE_BLADE_EXPOSURE_RETREAT): en TIP_TO_ROOT de cara inferior el total es +3 m.
        val obsDistStartAdj = if (under) obsDistStart + UNDERSIDE_EXPOSURE_RETREAT else obsDistStart
        val startOffset = startOffset(bladePosition, pathIndex, pathDirection, obsDistStartAdj, def.tipMargin, axisStart, axisEnd, gimbalPitch)
        val endOffset = endOffset(bladePosition, pathIndex, pathDirection, obsDistEnd, axisStart, axisEnd, gimbalPitch, def)
        val axisEndAdj = axisEnd + endOffset
        val axisDirection = (axisEnd - axisStart).normalize()

        val fovStart = effectiveFov(bladePosition, pathIndex, cameraFov, obsDistStart, def.imageOverlap)
        val fovEnd = effectiveFov(bladePosition, pathIndex, cameraFov, obsDistEnd, def.imageOverlap)

        // Waypoint previo de ajuste de exposición (solo si hay desplazamiento inicial)
        var exposureAdded = 0
        var pathStart = axisStart
        if (startOffset.norm > 1e-6) {
            val radius = (axisStart + startOffset - bladeRoot).norm
            val pitch = gimbalPitch(bladePosition, pathIndex, gimbalLimits)
            val th = thickness.thicknessAt(radius, pathIndex)
            val (wp, poi) = exposureSettingWaypoint(axisStart, startOffset, axisDirection, waypointDirection, obsDistStartAdj,
                pathIndex, bladePosition, componentIndex, yawMode, def, pitch, radius, gimbalPitch, th)
            out += wp
            pathStart = poi + (-axisDirection) * def.tipMargin
            exposureAdded = 1
        }

        val pathVec = axisEndAdj - pathStart
        var pathLength = pathVec.norm
        val intervals = InspectionOptics.waypointIntervals(pathLength, fovStart, fovEnd)
        val numWaypoints = exposureAdded + intervals.size
        val angleInterp = observationAngleInterpolator(pathIndex, pathLength, angleStart, angleMiddle, angleEnd)
        if (pathIndex in PS_SS) pathLength -= hubCenterToMaxChordWidth
        // Rotores pequeños: se permite girar el ángulo de observación para librar la torre
        val angleSign = if (turbine.dims.bladeLength < SMALL_ROTOR_BLADE_LENGTH_LIMIT) when {
            bladePosition == BladePosition.FOUR && pathIndex == PathIndex.SS -> 1.0
            bladePosition == BladePosition.EIGHT && pathIndex == PathIndex.PS -> -1.0
            else -> 0.0
        } else 0.0
        val isUnderTipToRoot = under && pathDirection == PathDirection.TIP_TO_ROOT
        val distInterp = LinearInterpolator(0.0, obsDistStart, pathLength, obsDistEnd)

        var underSideRoot: Waypoint3D? = null
        var pos = 0.0
        for (interval in intervals) {
            val axisPoint = pathStart + axisDirection * pos
            var focus = distInterp.interpolate(pos)
            val radius = (axisPoint - bladeRoot).norm
            val obsDist = focus + thickness.thicknessAt(radius, pathIndex)
            var angle = angleInterp.interpolate(pos)
            if (angleSign != 0.0)
                angle = towerClearanceAngle(angle, angleSign, axisPoint, axisDirection, waypointDirection, obsDist, turbine)

            val dir = Geo3D.rotate(waypointDirection, axisDirection, Angle.fromDegrees(angle))
            val wpPoint = axisPoint + dir * obsDist
            val droneYaw = Geo3D.yawOf(-dir)

            // Cara inferior de la pala: la distancia de enfoque se corrige por la inclinación del gimbal
            val inclined = (bladePosition == BladePosition.FOUR && pathIndex == PathIndex.SS) ||
                           (bladePosition == BladePosition.EIGHT && pathIndex == PathIndex.PS)
            if (inclined) focus /= cos(Angle.fromDegrees(60.0).radians - abs(gimbalPitch.radians))

            val poi = poi(axisPoint, axisDirection, wpPoint, pathIndex, droneYaw, gimbalPitch)
            val idx = out.size
            val isFirstPoint = idx == 0
            val isLastPoint = idx == numWaypoints - 1
            underSideRoot = null
            if (isLastPoint && isUnderTipToRoot)
                underSideRoot = underSideRootFrame(wpPoint, bladeRoot, axisDirection, gimbalPitch, pathIndex, bladePosition,
                    componentIndex, yawMode, def, isPS, turbine)
            val isLastInspection = isLastPoint && underSideRoot == null
            val isFirstBlade = isFirstPoint && isLE
            val isLastBlade = isLastInspection && isPS
            val stop = stopAtEachWaypoint || (pathIndex in PS_SS && idx == 1)

            val actions = waypointActions(bladePosition, pathIndex, isFirstPoint, isLastInspection, isFirstBlade, isLastBlade,
                wpPoint, axisPoint, droneYaw, focus, bladeRoot, poi, def, bladeTwist, cameraFov, gimbalPitch)
            val pauseTiming = if (!isFirstPoint) PauseTiming.UNDEFINED else if (pathIndex in PS_SS) PauseTiming.AFTER else PauseTiming.BEFORE
            val pause = if (isFirstPoint) def.exposureSettingPause else 0.0

            out += Waypoint3D(
                idx, pathIndex, PathType.INSPECTION, bladePosition, componentIndex, wpPoint,
                offsetPoiForSmoothYaw(poi, wpPoint, pathIndex), yawMode, droneYaw, actions, pauseTiming, pause,
                def.inspectionVelocity, stop, isFirstPoint, isLastInspection, isFirstBlade, isLastBlade,
                obsDist, actions.last().distanceToRoot,
            )
            pos += interval
        }
        if (under) postProcessPoiForUnderSide(out)
        underSideRoot?.let { out += it.reIndex(out.size) }
        return out
    }

    fun offsetPoiForSmoothYaw(poi: Vec3, waypoint: Vec3, p: PathIndex): Vec3 =
        if (p in PS_SS) poi + (poi - waypoint).normalize() * 100.0 else poi

    // ───────── Óptica ─────────
    private fun effectiveFov(b: BladePosition, p: PathIndex, cam: CameraFov, focus: Double, overlap: Double) =
        if (b == BladePosition.TWELVE || p == PathIndex.SS || p == PathIndex.PS)
            InspectionOptics.effectiveFov(cam.vertical, focus, overlap)
        else InspectionOptics.effectiveFovRotated(cam.horizontal, cam.vertical, focus, overlap, 0.8)

    private fun observationDistanceRoot(b: BladePosition, cam: CameraFov, def: InspectionDefinition, maxWidth: Double, p: PathIndex): Double {
        val fov = when {
            b == BladePosition.TWELVE -> cam.horizontal
            (p == PathIndex.SS && b == BladePosition.FOUR) || (p == PathIndex.PS && b == BladePosition.EIGHT) -> cam.vertical
            (p == PathIndex.SS && b == BladePosition.EIGHT) || (p == PathIndex.PS && b == BladePosition.FOUR) -> cam.horizontal
            else -> cam.vertical
        }
        return InspectionOptics.minObservationDistance(fov, def.imageMargin, maxWidth)
    }

    // ───────── Ángulos de observación ─────────
    private fun gimbalPitch(b: BladePosition, p: PathIndex, g: GimbalLimits): Angle {
        val up = PITCH_UP.coerceAtLeast(0.0).coerceAtMost(g.maxPitchUp)
        val down = PITCH_DOWN.coerceAtMost(0.0).coerceAtLeast(-g.maxPitchDown)
        return Angle.fromDegrees(when {
            b == BladePosition.FOUR && p == PathIndex.SS -> up
            b == BladePosition.FOUR && p == PathIndex.PS -> down
            b == BladePosition.EIGHT && p == PathIndex.SS -> down
            b == BladePosition.EIGHT && p == PathIndex.PS -> up
            else -> 0.0
        })
    }

    private fun observationAngleOffset(b: BladePosition, d: PathDirection, p: PathIndex, def: InspectionDefinition) = when {
        b == BladePosition.FOUR && d == PathDirection.TIP_TO_ROOT && p == PathIndex.SS -> def.towerSafetyAngle
        b == BladePosition.EIGHT && d == PathDirection.TIP_TO_ROOT && p == PathIndex.PS -> -def.towerSafetyAngle
        else -> 0.0
    }

    private fun observationAngleInterpolator(p: PathIndex, len: Double, start: Double, mid: Double, end: Double): Interpolator {
        if (p == PathIndex.TE) return PiecewiseLinearInterpolator(listOf(
            0.0 to start, len / 4 to start + (mid - start) * 3 / 4, len / 2 to mid, len to end))
        if (p !in PS_SS) return LinearInterpolator(0.0, start, len, end)
        var xMid = len / 2; var xLate = 3 * len / 4
        if (len / 4 < 10.0) { xLate = 15.0; xMid = 10.0 }
        return PiecewiseLinearInterpolator(listOf(0.0 to start, xMid to mid, xLate to end - (end - mid) / 4, len to end))
    }

    /** Si la trayectoria queda a <5 m de torre/nacelle/spinner, abre el ángulo en pasos de 2.5° (máx. +90°). */
    private fun towerClearanceAngle(nominal: Double, sign: Double, axisPoint: Vec3, axisDir: Vec3, wpDir: Vec3, dist: Double, t: WindTurbine): Double {
        var best = -1.0; var bestAngle = nominal
        var extra = 0.0
        while (extra <= TOWER_CLEARANCE_MAX_EXTRA) {
            val a = nominal + sign * extra
            val d = t.distanceToFixedComponents(axisPoint + Geo3D.rotate(wpDir, axisDir, Angle.fromDegrees(a)) * dist)
            if (d >= TOWER_CLEARANCE_MIN) return a
            if (d > best) { best = d; bestAngle = a }
            extra += TOWER_CLEARANCE_STEP
        }
        return bestAngle
    }

    // ───────── Offsets de inicio/fin ─────────
    private fun startOffset(b: BladePosition, p: PathIndex, d: PathDirection, obsDist: Double, tipMargin: Double, s: Vec3, e: Vec3, gimbal: Angle): Vec3 {
        val dir = (e - s).normalize()
        val a = Angle.fromDegrees((60.0 - abs(gimbal.degrees)).coerceAtLeast(0.0))
        var margin = tipMargin
        if (isUnderSide(b, p) && d == PathDirection.TIP_TO_ROOT) margin += obsDist * tan(a.radians)
        else if (!(d == PathDirection.TIP_TO_ROOT && p in PS_SS)) margin = 0.0
        return dir * margin
    }

    private fun endOffset(b: BladePosition, p: PathIndex, d: PathDirection, obsDist: Double, s: Vec3, e: Vec3, gimbal: Angle, def: InspectionDefinition): Vec3 =
        if (isUnderSide(b, p) && d == PathDirection.TIP_TO_ROOT)
            (e - s).normalize() * (obsDist * tan(Angle.fromDegrees((60.0 - abs(gimbal.degrees)).coerceAtLeast(0.0)).radians)).coerceIn(0.0, def.pathSafetyDistance)
        else Vec3.ZERO

    // ───────── POI ─────────
    private fun poi(axisPoint: Vec3, axisDir: Vec3, waypoint: Vec3, p: PathIndex, yaw: Double, gimbal: Angle): Vec3 =
        if (p in PS_SS) axisPointFor(Line3(axisPoint, axisDir), waypoint, gimbal, yaw) else axisPoint

    /** Punto del eje de la pala al que apunta la cámara desde [point] con ese pitch/yaw. */
    fun axisPointFor(axis: Line3, point: Vec3, pitch: Angle, yawDeg: Double): Vec3 =
        axis.closestPointsTo(Line3(point, InspectionOptics.viewDirection(pitch.radians, Angle.fromDegrees(yawDeg).radians))).first

    private fun postProcessPoiForUnderSide(w: MutableList<Waypoint3D>) {
        for (i in 0 until w.size - 1) w[i] = w[i].copy(pointOfInterest = w[i + 1].pointOfInterest)
    }

    // ───────── Waypoints especiales ─────────
    private fun exposureSettingWaypoint(
        axisStart: Vec3, startOffset: Vec3, axisDir: Vec3, wpDir: Vec3, axisDistStart: Double,
        p: PathIndex, b: BladePosition, c: ComponentIndex, yawMode: YawMode, def: InspectionDefinition,
        pitch: Angle, radius: Double, gimbalPitch: Angle, thickness: Double,
    ): Pair<Waypoint3D, Vec3> {
        val p0 = axisStart + startOffset + axisDir
        val wp = p0 + wpDir * (axisDistStart + thickness)
        val yaw = Geo3D.yawOf(-wpDir)
        val poi = poi(p0, axisDir, wp, p, yaw, gimbalPitch)
        val offPoi = offsetPoiForSmoothYaw(poi, wp, p)
        val actions = listOf(WaypointAction(yaw, pitch.degrees, 0.0, (poi - wp).norm, radius, takePhoto = false, rotateGimbal = true, rotateDroneYaw = true))
        return Waypoint3D(0, p, PathType.INSPECTION, b, c, wp, offPoi, yawMode, yaw, actions, PauseTiming.AFTER,
            def.exposureSettingPause, def.inspectionVelocity, true, true, false, false, false, axisDistStart, radius) to p0
    }

    private fun underSideRootFrame(
        last: Vec3, bladeRoot: Vec3, axisDir: Vec3, gimbalPitch: Angle, p: PathIndex, b: BladePosition,
        c: ComponentIndex, yawMode: YawMode, def: InspectionDefinition, isLastBladePoint: Boolean, t: WindTurbine,
    ): Waypoint3D? {
        if (gimbalPitch.degrees <= 0.0) return null
        val inward = -axisDir
        val target = bladeRoot + inward * UNDERSIDE_ROOT_FRAME_TARGET
        val horiz = hypot(target.x - last.x, target.y - last.y)
        val climbNeeded = (target.z - tan(gimbalPitch.radians) * horiz) - last.z
        if (climbNeeded <= 0.0) return null
        val climb = min(climbNeeded, UNDERSIDE_ROOT_FRAME_MAX_CLIMB)
        val pt = Vec3(last.x, last.y, last.z + climb)
        val yaw = Geo3D.yawOf(target - pt)
        val axisPoint = axisPointFor(Line3(bladeRoot, inward), pt, gimbalPitch, yaw)
        val focus = (axisPoint - pt).norm
        val v = pt - bladeRoot
        val distToComponent = (v - inward * (v dot inward)).norm
        val distToRoot = (axisPoint - bladeRoot) dot inward
        val actions = mutableListOf(WaypointAction(yaw, gimbalPitch.degrees, 0.0, focus, distToRoot, true, true, true))
        if (isLastBladePoint) actions += WaypointAction(0.0, 0.0, 0.0, 0.0, 0.0, false, true, false)
        val wp = Waypoint3D(0, p, PathType.INSPECTION, b, c, pt, offsetPoiForSmoothYaw(axisPoint, pt, p), yawMode, yaw, actions,
            PauseTiming.UNDEFINED, 0.0, def.inspectionVelocity, true, false, true, false, isLastBladePoint, distToComponent, distToRoot)
        // Se descarta si el nuevo punto queda a <5 m de algún componente
        return if (t.distance(wp.point).first < 5.0) null else wp
    }

    // ───────── Acciones por waypoint ─────────
    private fun waypointActions(
        b: BladePosition, p: PathIndex, isFirstPath: Boolean, isLastPath: Boolean, isFirstBlade: Boolean, isLastBlade: Boolean,
        wp: Vec3, axisPoint: Vec3, droneYaw: Double, focus: Double, bladeRoot: Vec3, poi: Vec3,
        def: InspectionDefinition, twist: BladeTwist?, cam: CameraFov, gimbal: Angle,
    ): List<WaypointAction> {
        val rootAxis = (bladeRoot - axisPoint).norm
        if (isFirstPath && p == PathIndex.TE) return actionsTE(b, def, wp, axisPoint, twist, cam, droneYaw, rootAxis)
        if (isFirstPath && p == PathIndex.LE) return listOf(WaypointAction(droneYaw, 0.0, 0.0, (wp - axisPoint).norm, rootAxis, true, true, b != BladePosition.TWELVE))
        val radius = (poi - bladeRoot).norm
        if (isLastPath) return actionsLast(b, p, isLastBlade, droneYaw, focus, radius, cam, gimbal)
        return listOf(WaypointAction(droneYaw, gimbal.degrees, 0.0, focus, radius, true, isFirstPath, isFirstPath))
    }

    private fun actionsLast(b: BladePosition, p: PathIndex, isLastBlade: Boolean, yaw: Double, focus: Double, radius: Double, cam: CameraFov, gimbal: Angle): List<WaypointAction> {
        val out = mutableListOf(WaypointAction(yaw, gimbal.degrees, 0.0, focus, radius, true, false, false))
        val a = Angle.fromDegrees(cam.vertical * 3.0 / 4.0)
        fun extra(sign: Double) {
            out += WaypointAction(yaw, gimbal.degrees + sign * a.degrees, 0.0, focus, radius - sin(a.radians) * focus, true, true, false)
            out += WaypointAction(yaw, gimbal.degrees + sign * 2 * a.degrees, 0.0, focus, radius - sin(2 * a.radians) * focus, true, true, false)
        }
        when {
            b == BladePosition.FOUR && p == PathIndex.PS -> extra(+1.0)
            b == BladePosition.EIGHT && p == PathIndex.SS -> extra(+1.0)
            b == BladePosition.TWELVE && p in PS_SS -> extra(-1.0)
        }
        if (isLastBlade) out += WaypointAction(0.0, 0.0, 0.0, 0.0, 0.0, false, true, false)
        return out
    }

    /** Primer punto del borde de salida: barrido en yaw+pitch hasta apuntar a la raíz con solape de imagen. */
    private fun actionsTE(b: BladePosition, def: InspectionDefinition, wp: Vec3, axisPoint: Vec3, twist: BladeTwist?, cam: CameraFov, droneYaw: Double, radius: Double): List<WaypointAction> {
        val d = (wp - axisPoint).norm
        val (yawEnd, pitchEnd) = InspectionOptics.yawPitchToRoot(d, radius, b == BladePosition.TWELVE, b == BladePosition.FOUR)
        val nPitch = InspectionOptics.stepsPitch(0.0, abs(pitchEnd), d, cam.vertical, def.imageOverlap)
        val nYaw = InspectionOptics.stepsYaw(0.0, abs(yawEnd), d, cam.horizontal, def.imageOverlap)
        val steps = max(nPitch, nYaw)
        val yawStep = if (nYaw > 1) yawEnd / steps else yawEnd
        val pitchStep = if (nPitch > 1) pitchEnd / steps else pitchEnd
        val yaws = (0..steps).map { it * yawStep }.reversed()
        val pitches = (0..steps).map { it * pitchStep }.reversed()
        val out = ArrayList<WaypointAction>()
        for (i in 0..steps) {
            val ya = Angle.fromDegrees(yaws[i]); val pa = Angle.fromDegrees(pitches[i])
            val s = sqrt(abs(sin(ya.radians)).pow(2) + abs(tan(pa.radians)).pow(2)) * d / cos(ya.radians)
            out += WaypointAction(Angle.normalize(droneYaw + ya.degrees, -180.0, 180.0), pa.degrees, 0.0,
                sqrt(s * s + d * d), if (radius > s) radius - s else 0.0, true, true, true)
        }
        if (b == BladePosition.FOUR || b == BladePosition.EIGHT)
            out += WaypointAction(droneYaw, (if (b == BladePosition.FOUR) -1.0 else 1.0) * ((twist?.angleTip ?: 0.0) / 2.0), 0.0, d, radius, false, true, false)
        return out
    }
}
