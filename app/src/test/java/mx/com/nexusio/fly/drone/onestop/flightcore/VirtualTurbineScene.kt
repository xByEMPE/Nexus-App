package mx.com.nexusio.fly.drone.onestop.flightcore

import java.util.Random

/**
 * Turbina virtual fija en el mundo (para el simulador de DJI, que no tiene turbina ni láser reales).
 *
 * [towerBase] es el pie de la torre (altitud 0 = punto de despegue, igual que la altitud relativa del dron) y
 * [facingDeg] el rumbo hacia el que mira el rotor (0 = Norte, 90 = Este). El modelo es el mismo que usa el planificador
 * ([WindTurbine]), de modo que el láser virtual "ve" exactamente los sólidos contra los que se calculan las distancias de seguridad.
 */
class VirtualTurbineScene(
    val towerBase: GeoPoint,
    val dims: TurbineDimensions,
    val facingDeg: Double,
    rotorIndexDeg: Double = 0.0,
    private val laserNoiseSigmaM: Double = 0.0,
    seed: Long = 1L,
) {
    /** Turbina en coordenadas locales ENU con origen en [towerBase]. */
    val turbine: WindTurbine
    private val rng = Random(seed)

    init {
        // La turbina sintética mira al Norte (+Y); girar −facing (horario) la orienta al rumbo pedido.
        val m = WindTurbine.syntheticMeasurements(dims, Angle.fromDegrees(rotorIndexDeg), bottomOnLeadingEdge = true).rotatedAboutZ(-facingDeg)
        turbine = WindTurbine(dims, m, WindTurbine.defaultComponents())
    }

    fun toLocal(p: GeoPoint) = GeoMath.toLocal(towerBase, p)

    /**
     * Lectura del telémetro láser: el dron en [drone] (altitud relativa) con rumbo [yawDeg] y gimbal [pitchDeg]
     * (negativo = hacia abajo). Devuelve la distancia al primer sólido, o −1 si no hay impacto en el rango útil del láser.
     */
    fun laserDistance(drone: GeoPoint, yawDeg: Double, pitchDeg: Double, minRange: Double = 0.1, maxRange: Double = 200.0): Float {
        val dir = InspectionOptics.viewDirection(Math.toRadians(pitchDeg), Math.toRadians(yawDeg))
        val d = turbine.raycast(toLocal(drone), dir, maxRange) ?: return -1f
        val noisy = if (laserNoiseSigmaM > 0.0) d + rng.nextGaussian() * laserNoiseSigmaM else d
        return if (noisy in minRange..maxRange) noisy.toFloat() else -1f
    }

    /** Distancia horizontal hacia delante hasta el primer sólido (sensor de visión frontal), o −1. */
    fun forwardObstacle(drone: GeoPoint, yawDeg: Double, maxRange: Double = 30.0): Float =
        laserDistance(drone, yawDeg, 0.0, 0.1, maxRange)

    /** Distancia mínima del dron a cualquier parte de la turbina (sensores de proximidad en todas direcciones). */
    fun nearestDistance(drone: GeoPoint): Double = turbine.distance(toLocal(drone)).first

    companion object {
        /**
         * Coloca la turbina [standoffM] metros al Norte de [start] con el rotor mirando al Sur, es decir, hacia el dron
         * si éste arranca mirando al Norte. Hay que subir hasta la altura del buje (≈ `dims.towerHeight`) para medirla.
         */
        fun inFrontOf(start: GeoPoint, dims: TurbineDimensions, standoffM: Double = 40.0, laserNoiseSigmaM: Double = 0.05): VirtualTurbineScene {
            val base = GeoMath.toGeo(GeoPoint(start.lat, start.lon, 0.0), Vec3(0.0, standoffM, 0.0))
            return VirtualTurbineScene(base, dims, facingDeg = 180.0, laserNoiseSigmaM = laserNoiseSigmaM)
        }
    }
}
