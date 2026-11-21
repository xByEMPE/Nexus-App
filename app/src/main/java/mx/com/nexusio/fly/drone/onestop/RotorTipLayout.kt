package mx.com.nexusio.fly.drone.onestop

import kotlin.math.min

data class TipPoint(val x: Float, val y: Float)

data class ImageBox(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Geometría de la foto del rotor (drawable-nodpi/turbine_rotor.png) dentro de
 * [RotorOrientationView]: la foto se escala con fit-center y las puntas de las palas
 * se ubican en coordenadas relativas a la foto, no a la vista.
 */
object RotorTipLayout {
    const val IMAGE_W = 1792f
    const val IMAGE_H = 1539f

    /** Puntas normalizadas en el orden de assignedLabels: [12h, 4h, 8h]. */
    val TIPS_NORM: List<TipPoint> = listOf(
        TipPoint(0.505f, 0.10f),
        TipPoint(0.895f, 0.88f),
        TipPoint(0.12f, 0.88f),
    )

    fun imageBox(viewW: Float, viewH: Float, padding: Float = 0f): ImageBox {
        val availW = (viewW - 2 * padding).coerceAtLeast(0f)
        val availH = (viewH - 2 * padding).coerceAtLeast(0f)
        val scale = min(availW / IMAGE_W, availH / IMAGE_H)
        val w = IMAGE_W * scale
        val h = IMAGE_H * scale
        return ImageBox(padding + (availW - w) / 2f, padding + (availH - h) / 2f, w, h)
    }

    fun tipCenters(viewW: Float, viewH: Float, padding: Float = 0f): List<TipPoint> {
        val b = imageBox(viewW, viewH, padding)
        return TIPS_NORM.map { TipPoint(b.left + it.x * b.width, b.top + it.y * b.height) }
    }

    fun hitTip(x: Float, y: Float, viewW: Float, viewH: Float, radius: Float, padding: Float = 0f): Int? =
        tipCenters(viewW, viewH, padding)
            .mapIndexed { i, p -> i to ((x - p.x) * (x - p.x) + (y - p.y) * (y - p.y)) }
            .filter { it.second <= radius * radius }
            .minByOrNull { it.second }
            ?.first
}
