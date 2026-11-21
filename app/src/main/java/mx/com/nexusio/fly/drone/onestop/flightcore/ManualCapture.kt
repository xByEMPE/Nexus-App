package mx.com.nexusio.fly.drone.onestop.flightcore

import kotlin.math.*

data class Attitude(val yaw: Double? = null, val pitch: Double? = null, val roll: Double? = null)

object Euler {
    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> a[i][k] * b[k][j] } } }

    /** Rz(yaw)·Ry(pitch)·Rx(roll) con los signos exactos del original. */
    fun matrix(yaw: Angle, pitch: Angle, roll: Angle): Array<DoubleArray> {
        val y = yaw.radians; val p = pitch.radians; val r = roll.radians
        val rz = arrayOf(doubleArrayOf(cos(y), sin(y), 0.0), doubleArrayOf(-sin(y), cos(y), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val ry = arrayOf(doubleArrayOf(cos(p), 0.0, sin(p)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(p), 0.0, cos(p)))
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(r), -sin(r)), doubleArrayOf(0.0, sin(r), cos(r)))
        return mul(mul(rz, ry), rx)
    }

    /**
     * Dirección de visión = M·Y.
     * ⚠ HALLAZGO: Ry gira alrededor del eje Y (el eje "adelante"), así que con roll = 0 el resultado es (sin yaw, cos yaw, 0):
     * el PITCH del gimbal NO inclina la dirección de visión. Es un error del original (confunde pitch con roll).
     * Se conserva en [eulerToVector] por fidelidad y se ofrece [eulerToVectorFixed] con el pitch aplicado como elevación.
     */
    fun eulerToVector(yaw: Angle, pitch: Angle, roll: Angle): Vec3 {
        val m = matrix(yaw, pitch, roll)
        return Vec3(m[0][1], m[1][1], m[2][1])
    }

    /** Dirección de cámara con el pitch como elevación real (mismo convenio que TurbineMeasurementPoint y viewDirection). */
    fun eulerToVectorFixed(yaw: Angle, pitch: Angle): Vec3 = InspectionOptics.viewDirection(pitch.radians, yaw.radians)
}

/** ¿Qué componente de la turbina está mirando la cámara y a qué distancia? (captura manual de imagen) */
object ManualPhotoLocation {
    fun distanceToComponentInLineOfSight(
        t: WindTurbine, cam: CameraFov, drone: Vec3, att: Attitude, c: ComponentIndex,
        pos: BladePosition = BladePosition.UNDEFINED, fixPitchBug: Boolean = false,
    ): Double? {
        val (a, b) = when (pos) {
            BladePosition.FOUR, BladePosition.EIGHT, BladePosition.TWELVE -> t.bladeRoot to t.bladeTip(pos)
            BladePosition.UNDEFINED -> when (c) {
                ComponentIndex.T -> t.towerBottom to t.towerTop
                ComponentIndex.S -> t.spinnerStart to t.spinner
                ComponentIndex.N -> t.rotorAxisBack to t.rotorAxisFront
                else -> throw IllegalArgumentException("No valid blade position/component index combination")
            }
        }
        val axis = Line3(a, (b - a).normalize())
        val pitch = Angle.fromDegrees(att.pitch ?: 0.0); val yaw = Angle.fromDegrees(att.yaw ?: 0.0); val roll = Angle.fromDegrees(att.roll ?: 0.0)
        val view = if (fixPitchBug) Euler.eulerToVectorFixed(yaw, pitch) else Euler.eulerToVector(yaw, pitch, roll)
        val (onAxis, onView) = axis.closestPointsToLineBetween(Line3(drone, view), a, b)
        if ((onView - drone) dot view < 0.0) return null            // el objetivo queda detrás de la cámara
        val dist = (onAxis - drone).norm
        val off = Geo3D.angleBetween(view, (onAxis - drone).normalize()).degrees
        // ⚠ Umbral del original: fovDiag/2 + tan(fovDiag[rad de un valor en grados])·dist. Mezcla unidades y crece con la
        // distancia (puede aceptar objetivos muy fuera del encuadre); se conserva tal cual.
        if (off > cam.diagonal / 2.0 + tan(Math.toRadians(cam.diagonal)) * dist) return null
        return dist
    }

    /** (componente, posición de pala, distancia) del componente más cercano en la línea de visión. */
    fun closestInLineOfSight(t: WindTurbine, cam: CameraFov, drone: Vec3, att: Attitude, fixPitchBug: Boolean = false): Triple<ComponentIndex, BladePosition, Double> {
        val hits = ArrayList<Triple<ComponentIndex, BladePosition, Double>>()
        for (pos in listOf(BladePosition.TWELVE, BladePosition.FOUR, BladePosition.EIGHT)) {
            val c = t.componentIndex(pos).takeIf { it != ComponentIndex.X } ?: continue
            distanceToComponentInLineOfSight(t, cam, drone, att, c, pos, fixPitchBug)?.takeIf { it.isFinite() && it >= 0.0 }?.let { hits += Triple(c, pos, it) }
        }
        for (c in listOf(ComponentIndex.T, ComponentIndex.S, ComponentIndex.N))
            distanceToComponentInLineOfSight(t, cam, drone, att, c, BladePosition.UNDEFINED, fixPitchBug)?.takeIf { it.isFinite() && it >= 0.0 }?.let { hits += Triple(c, BladePosition.UNDEFINED, it) }
        val best = hits.minByOrNull { it.third } ?: Triple(ComponentIndex.X, BladePosition.UNDEFINED, Double.NaN)
        val d = t.distanceToComponent(best.first, best.second, drone) ?: best.third
        return Triple(best.first, best.second, d)
    }
}

/** Lógica pura de SingleImagePlanner.computeWaypointInfoForManualSingleImageCapture (sin DI ni repositorios). */
object SingleImagePlanner {
    fun manualCaptureInfo(
        previous: WaypointInfo, currentIndex: Int, droneLocation: GeoPoint, gimbal: Attitude,
        turbine: WindTurbine, origin: GeoPoint, cam: CameraFov, fixPitchBug: Boolean = false,
    ): WaypointInfo {
        val drone = GeoMath.toLocal(origin, droneLocation)
        val (component, pos, dist) = ManualPhotoLocation.closestInLineOfSight(turbine, cam, drone, gimbal, fixPitchBug)
        return WaypointInfo(
            index = currentIndex, pathStartIndex = previous.pathStartIndex,
            // El camino solo se conserva si seguimos en el mismo componente que el waypoint previo
            pathIndex = if (previous.componentIndex == component) previous.pathIndex else PathIndex.UNDEFINED,
            bladePosition = pos, componentIndex = component, point = droneLocation, dronePausing = false,
            waypointActions = listOf(WaypointAction(yaw = gimbal.yaw!!, pitch = gimbal.pitch!!, focusDistance = dist, takePhoto = true)),
        )
    }
}

/** Equivalente funcional de InspectionVisualizer (que solo imprime scripts matplotlib de depuración). */
object PlanExport {
    fun csv(wps: List<Waypoint3D>): String = buildString {
        appendLine("index,pathIndex,pathType,bladePosition,component,x,y,z,poiX,poiY,poiZ,yaw,velocity,stop,pause,photos")
        for (w in wps) appendLine(listOf(w.index, w.pathIndex, w.pathType, w.bladePosition, w.componentIndex, w.point.x, w.point.y, w.point.z,
            w.pointOfInterest.x, w.pointOfInterest.y, w.pointOfInterest.z, w.droneYaw, w.velocity, w.stop, w.pauseDrone, w.actions.count { it.takePhoto }).joinToString(","))
    }

    /** Script Python/matplotlib que dibuja la ruta y la turbina (misma finalidad que el visualizador original). */
    fun matplotlib(t: WindTurbine, wps: List<Waypoint3D>): String = buildString {
        appendLine("import numpy as np, matplotlib.pyplot as plt")
        appendLine("fig = plt.figure(); ax = fig.add_subplot(111, projection='3d')")
        fun arr(v: Vec3) = "[${v.x}, ${v.y}, ${v.z}]"
        appendLine("root = np.array(${arr(t.bladeRoot)}); tower = np.array([${arr(t.towerBottom)}, ${arr(t.towerTop)}])")
        for (p in listOf(BladePosition.FOUR, BladePosition.TWELVE, BladePosition.EIGHT))
            appendLine("tip = np.array(${arr(t.bladeTip(p))}); ax.plot(*zip(root, tip), 'k-', lw=3)")
        appendLine("ax.plot(*zip(*tower), 'k-', lw=6)")
        appendLine("pts = np.array([${wps.joinToString(", ") { arr(it.point) }}])")
        appendLine("ax.plot(pts[:,0], pts[:,1], pts[:,2], 'b.-'); plt.show()")
    }
}
