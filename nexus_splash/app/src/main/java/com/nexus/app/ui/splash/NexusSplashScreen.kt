package com.nexus.app.ui.splash

import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Límite duro de la pantalla de arranque (requisito: no más de 8 s). */
const val SPLASH_MAX_MS = 8_000

/** Duración real de la animación. Cámbiala aquí (debe ser <= 8 s). */
private const val TOTAL_S = 5.8f

private val NexusBlue = Color(0xFF2979FF)
private val DividerBlue = Color(0xFF3D5AFE)
private const val R2 = 0.70710678f
private const val DEG = 0.017453292f

/**
 * Splash horizontal de Nexus para una app de inspecciones estructurales / energía.
 *
 * Línea de tiempo (s):
 *  0.0–0.9  retícula de plano técnico
 *  0.2–1.7  el dron (logo) se dibuja; los rotores giran
 *  0.3–1.9  se levanta la escena: suelo, 2 aerogeneradores, campo solar, 2 torres de transmisión
 *  1.9–2.6  se tienden los conductores (y el enlace del campo solar a la red)
 *  2.0–3.6  el dron escanea la escena de izquierda a derecha; los nodos se iluminan
 *  3.0–5.8  pulsos de energía recorren las líneas
 *  3.3–5.3  NEXUS, divisor, "INSPECCIÓN ESTRUCTURAL" y pie de marca
 *  5.5–5.8  desvanecido y llamada a [onFinished]
 *
 * Tocar la pantalla la omite.
 */
@Composable
fun NexusSplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "NEXUS",
    subtitle: String = "INSPECCIÓN ESTRUCTURAL",
    footer: String = "SOFTWARE · AI · DRONES",
) {
    require(TOTAL_S * 1000f <= SPLASH_MAX_MS) { "El splash no puede durar más de 8 s" }

    val clock = remember { Animatable(0f) }
    val currentOnFinished by rememberUpdatedState(onFinished)
    var finished by remember { mutableStateOf(false) }
    val finish: () -> Unit = {
        if (!finished) {
            finished = true
            currentOnFinished()
        }
    }

    LaunchedEffect(Unit) {
        clock.animateTo(TOTAL_S, tween((TOTAL_S * 1000).toInt(), easing = LinearEasing))
        finish()
    }

    val painter = remember(title, subtitle, footer) { SplashPainter(title, subtitle, footer) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = finish,
            )
    ) {
        painter.draw(this, clock.value)
    }
}

// ───────────────────────────── utilidades de animación ─────────────────────────────

private fun seg(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)

private fun easeOut(x: Float): Float {
    val k = 1f - x
    return 1f - k * k * k
}

private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val k = x - 1f
    return 1f + c3 * k * k * k + c1 * k * k
}

// ───────────────────────────────────── dibujo ─────────────────────────────────────

private class SplashPainter(
    private val title: String,
    private val subtitle: String,
    private val footer: String,
) {
    private val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    private val serif: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    fun draw(scope: DrawScope, t: Float) {
        with(scope) {
            val w = size.width
            val h = size.height
            val u = min(h, w * 0.5625f)           // unidad de diseño (alto de una pantalla 16:9)
            val cx = w / 2f
            val g = 1f - seg(t, TOTAL_S - 0.3f, TOTAL_S)   // desvanecido final
            fun a(v: Float) = (v * g).coerceIn(0f, 1f)

            val chordW = max(2f, u * 0.0042f)
            val webW = max(1.5f, u * 0.0028f)
            val thin = max(1.2f, u * 0.002f)
            val nr = u * 0.0085f

            // ── 1. Retícula de plano técnico ───────────────────────────────────────
            val gridA = easeOut(seg(t, 0f, 0.9f))
            val step = u * 0.05f
            var k = 0
            var gx = 0f
            while (gx <= w) {
                drawLine(
                    Color.White.copy(alpha = a(gridA * if (k % 5 == 0) 0.07f else 0.035f)),
                    Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f,
                )
                gx += step; k++
            }
            k = 0
            var gy0 = 0f
            while (gy0 <= h) {
                drawLine(
                    Color.White.copy(alpha = a(gridA * if (k % 5 == 0) 0.07f else 0.035f)),
                    Offset(0f, gy0), Offset(w, gy0), strokeWidth = 1f,
                )
                gy0 += step; k++
            }

            // ── 2. Geometría del logo y parámetros de escaneo ──────────────────────
            val logoY = h * 0.26f + sin(t * 2.6f) * u * 0.005f * seg(t, 1.6f, 2.2f)
            val ls = 0.40f * u / 396f                     // 396 = diámetro original del logo
            fun lp(x: Float, y: Float) = Offset(cx + x * ls, logoY + y * ls)

            val glowA = seg(t, 0.3f, 1.6f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NexusBlue.copy(alpha = a(0.16f * glowA)), NexusBlue.copy(alpha = 0f)),
                    center = Offset(cx, logoY),
                    radius = u * 0.5f,
                ),
                radius = u * 0.5f,
                center = Offset(cx, logoY),
            )

            val gy = h * 0.90f                              // línea de suelo
            val sStart = 0.03f * w - 0.02f * u
            val sLen = 0.95f * w + 0.04f * u
            val scanP = seg(t, 2.0f, 3.6f)
            val scanX = sStart + sLen * scanP
            val beamA = min(seg(t, 2.0f, 2.2f), 1f - seg(t, 3.45f, 3.7f))
            fun lit(nx: Float) = ((scanX - nx) / (0.05f * u)).coerceIn(0f, 1f)
            fun litTime(nx: Float) = 2.0f + 1.6f * ((nx - sStart) / sLen)

            /** Nodo de medición: se enciende en azul cuando el haz lo alcanza. */
            fun node(c: Offset, appear: Float) {
                if (appear <= 0f) return
                val l = lit(c.x)
                if (l > 0f) drawCircle(NexusBlue.copy(alpha = a(0.22f * l)), nr * 2.4f, c)
                drawCircle(Color.Black.copy(alpha = a(appear)), nr, c)
                drawCircle(
                    Color.White.copy(alpha = a(appear * (0.85f - 0.25f * l))), nr, c,
                    style = Stroke(width = max(1.5f, u * 0.003f)),
                )
                if (l > 0f) drawCircle(NexusBlue.copy(alpha = a(l)), nr * 0.72f * l, c)
            }

            /** Conductor con catenaria; dibuja un pulso de energía que lo recorre. */
            fun wire(
                x1: Float, y1: Float, x2: Float, y2: Float,
                sag: Float, f: Float, alpha: Float, width: Float, pulseOff: Float,
            ) {
                if (f <= 0f) return
                val n = 20
                val path = Path().apply { moveTo(x1, y1) }
                for (i in 1..n) {
                    val s = min(i / n.toFloat(), f)
                    path.lineTo(x1 + (x2 - x1) * s, y1 + (y2 - y1) * s + sag * 4f * s * (1f - s))
                    if (i / n.toFloat() >= f) break
                }
                drawPath(path, Color.White.copy(alpha = a(alpha)), style = Stroke(width = width))
                if (pulseOff >= 0f && f >= 1f && t > 3.0f) {
                    val s = ((t - 3.0f) * 0.28f + pulseOff) % 1f
                    val p = Offset(x1 + (x2 - x1) * s, y1 + (y2 - y1) * s + sag * 4f * s * (1f - s))
                    val pa = seg(t, 3.0f, 3.4f)
                    drawCircle(NexusBlue.copy(alpha = a(0.3f * pa)), u * 0.012f, p)
                    drawCircle(NexusBlue.copy(alpha = a(pa)), u * 0.0045f, p)
                }
            }

            // ── 3. Suelo ───────────────────────────────────────────────────────────
            val groundP = easeOut(seg(t, 0.3f, 1.2f))
            drawLine(Color.White.copy(alpha = a(0.55f)), Offset(0f, gy), Offset(w * groundP, gy), chordW)
            var hx = 0f
            while (hx < w * groundP) {
                drawLine(
                    Color.White.copy(alpha = a(0.15f)),
                    Offset(hx, gy), Offset(hx - 0.012f * u, gy + 0.014f * u), thin,
                )
                hx += 0.03f * u
            }

            // ── 4. Aerogeneradores ─────────────────────────────────────────────────
            val turbX = floatArrayOf(0.09f * w, 0.22f * w)
            val turbH = floatArrayOf(0.40f * u, 0.29f * u)
            val turbL = floatArrayOf(0.18f * u, 0.13f * u)
            val turbPh = floatArrayOf(10f, 55f)
            val spT = max(0f, t - 1.4f)
            val spinW = 80f * (spT + (exp(-2f * spT) - 1f) / 2f)    // giro suave hasta 80°/s

            for (i in 0..1) {
                val grow = easeOut(seg(t, 0.7f + 0.15f * i, 1.5f + 0.15f * i))
                if (grow <= 0f) continue
                val bx = turbX[i]
                val hubY = gy - turbH[i] * grow
                val bw0 = 0.013f * u
                val bw1 = 0.004f * u
                drawLine(Color.White.copy(alpha = a(0.9f)), Offset(bx - bw0, gy), Offset(bx - bw1, hubY), webW * 1.2f)
                drawLine(Color.White.copy(alpha = a(0.9f)), Offset(bx + bw0, gy), Offset(bx + bw1, hubY), webW * 1.2f)
                for (j in 1..4) {
                    val f = j / 5f
                    val yy = gy - (gy - hubY) * f
                    val hw = bw0 + (bw1 - bw0) * f
                    drawLine(Color.White.copy(alpha = a(0.35f)), Offset(bx - hw, yy), Offset(bx + hw, yy), thin)
                }
                val bl = easeOut(seg(t, 1.2f + 0.15f * i, 1.8f + 0.15f * i))
                if (bl > 0f) {
                    for (j in 0..2) {
                        val ang = (turbPh[i] + spinW + 120f * j) * DEG
                        val ca = cos(ang)
                        val sa = sin(ang)
                        val len = turbL[i] * bl
                        val rw = 0.007f * u * bl
                        val blade = Path().apply {
                            moveTo(bx - sa * rw, hubY + ca * rw)
                            lineTo(bx + ca * len, hubY + sa * len)
                            lineTo(bx + sa * rw, hubY - ca * rw)
                            close()
                        }
                        drawPath(blade, Color.White.copy(alpha = a(0.9f * bl)), style = Stroke(width = webW))
                    }
                }
                node(Offset(bx, hubY), easeOut(seg(t, 1.2f + 0.15f * i, 1.8f + 0.15f * i)))
            }

            // ── 5. Campo solar ─────────────────────────────────────────────────────
            val arrX0 = 0.31f * w
            val arrX1 = 0.60f * w
            val nPan = 7
            val pitch = (arrX1 - arrX0) / nPan
            val panW = pitch * 0.8f
            val panH = 0.05f * u
            val skew = 0.012f * u
            val panBottom = gy - 0.028f * u
            for (i in 0 until nPan) {
                val ap = easeOut(seg(t, 0.9f + 0.1f * i, 1.4f + 0.1f * i))
                if (ap <= 0f) continue
                val x = arrX0 + pitch * i
                val yt = panBottom - panH * ap
                val l = lit(x + panW / 2f)
                val poly = Path().apply {
                    moveTo(x, panBottom)
                    lineTo(x + panW, panBottom)
                    lineTo(x + panW + skew, yt)
                    lineTo(x + skew, yt)
                    close()
                }
                if (l > 0f) drawPath(poly, NexusBlue.copy(alpha = a(0.22f * l)))
                drawPath(poly, Color.White.copy(alpha = a(0.85f * ap)), style = Stroke(width = webW))
                for (m in 1..2) {
                    val p = m / 3f
                    drawLine(
                        Color.White.copy(alpha = a(0.35f * ap)),
                        Offset(x + panW * p, panBottom), Offset(x + skew + panW * p, yt), thin,
                    )
                }
                val ym = (panBottom + yt) / 2f
                drawLine(
                    Color.White.copy(alpha = a(0.35f * ap)),
                    Offset(x + skew / 2f, ym), Offset(x + panW + skew / 2f, ym), thin,
                )
                drawLine(
                    Color.White.copy(alpha = a(0.6f * ap)),
                    Offset(x + panW / 2f, panBottom), Offset(x + panW / 2f, gy), webW,
                )
                if (l > 0f) {
                    drawLine(NexusBlue.copy(alpha = a(l)), Offset(x + skew, yt), Offset(x + panW + skew, yt), chordW)
                }
            }

            // Inversor / subestación del campo solar
            val invX = arrX1 + 0.035f * u
            val invTop = gy - 0.03f * u
            val invA = easeOut(seg(t, 1.5f, 2.0f))
            if (invA > 0f) {
                drawRectOutline(
                    Offset(invX - 0.0175f * u, invTop), Size(0.035f * u, 0.03f * u),
                    Color.White.copy(alpha = a(0.8f * invA)), webW,
                )
                node(Offset(invX, invTop), invA)
            }

            // ── 6. Torres de transmisión ───────────────────────────────────────────
            val pylX = floatArrayOf(0.77f * w, 0.93f * w)
            val hf = 0.30f * u                              // altura total
            val lowY = gy - 0.60f * hf
            val lowA = 0.24f * hf
            val upY = gy - 0.78f * hf
            val upA = 0.17f * hf
            val ins = 0.035f * hf

            for (i in 0..1) {
                val grow = easeOut(seg(t, 1.0f + 0.15f * i, 1.9f + 0.15f * i))
                if (grow <= 0f) continue
                val bx = pylX[i]
                val hh = hf * grow
                val b0 = 0.075f * hh
                val b1 = 0.022f * hh
                val bodyTop = 0.80f * hh
                fun hw(f: Float) = b0 + (b1 - b0) * f
                val body = Color.White.copy(alpha = a(0.9f))
                val brace = Color.White.copy(alpha = a(0.5f))
                drawLine(body, Offset(bx - b0, gy), Offset(bx - b1, gy - bodyTop), webW * 1.2f)
                drawLine(body, Offset(bx + b0, gy), Offset(bx + b1, gy - bodyTop), webW * 1.2f)
                drawLine(body, Offset(bx - b1, gy - bodyTop), Offset(bx, gy - hh), webW)
                drawLine(body, Offset(bx + b1, gy - bodyTop), Offset(bx, gy - hh), webW)
                for (j in 0 until 5) {
                    val f1 = j / 5f
                    val f2 = (j + 1) / 5f
                    val y1 = gy - bodyTop * f1
                    val y2 = gy - bodyTop * f2
                    drawLine(brace, Offset(bx - hw(f1), y1), Offset(bx + hw(f2), y2), thin)
                    drawLine(brace, Offset(bx + hw(f1), y1), Offset(bx - hw(f2), y2), thin)
                    drawLine(brace, Offset(bx - hw(f2), y2), Offset(bx + hw(f2), y2), thin)
                }
                // Travesaños y aisladores
                val lowYs = gy - 0.60f * hh
                val upYs = gy - 0.78f * hh
                val lowAs = 0.24f * hh
                val upAs = 0.17f * hh
                val insS = 0.035f * hh
                drawLine(body, Offset(bx - lowAs, lowYs), Offset(bx + lowAs, lowYs), webW * 1.2f)
                drawLine(body, Offset(bx - upAs, upYs), Offset(bx + upAs, upYs), webW * 1.2f)
                for (s in floatArrayOf(-1f, 1f)) {
                    drawLine(body, Offset(bx + s * lowAs, lowYs), Offset(bx + s * lowAs, lowYs + insS), webW)
                    drawLine(body, Offset(bx + s * upAs, upYs), Offset(bx + s * upAs, upYs + insS), webW)
                }
                node(Offset(bx, gy - hh), easeOut(seg(t, 1.5f + 0.15f * i, 2.0f + 0.15f * i)))
            }

            // ── 7. Conductores ─────────────────────────────────────────────────────
            val cf = seg(t, 1.9f, 2.6f)
            val sag = 0.018f * u
            val p0 = pylX[0]
            val p1 = pylX[1]
            val farX = w * 1.02f
            // Torre 1 → torre 2
            wire(p0 + lowA, lowY + ins, p1 - lowA, lowY + ins, sag, cf, 0.75f, thin * 1.2f, 0.00f)
            wire(p0 + upA, upY + ins, p1 - upA, upY + ins, sag * 0.9f, cf, 0.75f, thin * 1.2f, 0.33f)
            wire(p0, gy - hf, p1, gy - hf, sag * 0.8f, cf, 0.45f, thin, 0.66f)
            // Torre 2 → fuera de pantalla
            wire(p1 + lowA, lowY + ins, farX, lowY + ins + 0.01f * u, sag * 0.4f, cf, 0.75f, thin * 1.2f, 0.10f)
            wire(p1 + upA, upY + ins, farX, upY + ins + 0.01f * u, sag * 0.4f, cf, 0.75f, thin * 1.2f, 0.45f)
            wire(p1, gy - hf, farX, gy - hf + 0.01f * u, sag * 0.4f, cf, 0.45f, thin, 0.75f)
            // Campo solar → red
            wire(invX, invTop, p0 - lowA, lowY + ins, 0.03f * u, seg(t, 2.1f, 2.7f), 0.75f, thin * 1.2f, 0.20f)

            // ── 8. Haz de escaneo ──────────────────────────────────────────────────
            if (beamA > 0f) {
                val bw = 0.04f * u
                val cone = Path().apply {
                    moveTo(cx, logoY)
                    lineTo(scanX - bw, gy)
                    lineTo(scanX + bw, gy)
                    close()
                }
                drawPath(
                    cone,
                    brush = Brush.linearGradient(
                        colors = listOf(NexusBlue.copy(alpha = 0f), NexusBlue.copy(alpha = a(0.32f * beamA))),
                        start = Offset(cx, logoY),
                        end = Offset(cx, gy),
                    ),
                )
                drawLine(
                    NexusBlue.copy(alpha = a(0.25f * beamA)),
                    Offset(scanX, gy - 0.44f * u), Offset(scanX, gy + 0.012f * u), u * 0.022f,
                )
                drawLine(
                    NexusBlue.copy(alpha = a(0.95f * beamA)),
                    Offset(scanX, gy - 0.44f * u), Offset(scanX, gy + 0.012f * u), max(2f, u * 0.004f),
                )
            }

            // Punto de medición: pulsos al ser alcanzado (buje del aerogenerador 2)
            val mx = turbX[1]
            val my = gy - turbH[1]
            val litT = litTime(mx)
            for (j in 0..1) {
                val pt = (t - litT - j * 0.35f) / 1.2f
                if (pt in 0f..1f) {
                    drawCircle(
                        NexusBlue.copy(alpha = a(0.8f * (1f - pt))),
                        radius = nr * (1.5f + 4.5f * easeOut(pt)),
                        center = Offset(mx, my),
                        style = Stroke(width = max(1.5f, u * 0.003f)),
                    )
                }
            }

            // ── 9. Dron (logo) ─────────────────────────────────────────────────────
            drawArc(
                color = Color.White.copy(alpha = a(0.22f)),
                startAngle = -90f,
                sweepAngle = 360f * easeOut(seg(t, 0.15f, 1.1f)),
                useCenter = false,
                topLeft = Offset(cx - 198f * ls, logoY - 198f * ls),
                size = Size(396f * ls, 396f * ls),
                style = Stroke(width = max(1.5f, 2f * ls)),
            )

            val armP = easeOut(seg(t, 0.6f, 1.3f))
            val rotorP = easeOutBack(seg(t, 0.9f, 1.5f)).coerceAtLeast(0f)
            val rotorA = seg(t, 0.9f, 1.3f)
            val blueA = seg(t, 1.2f, 1.7f)
            val sp = max(0f, t - 1.2f)
            val spin = 600f * (sp + (exp(-3f * sp) - 1f) / 3f)   // aceleración suave hasta 600°/s

            for (sy in intArrayOf(-1, 1)) {
                for (sx in intArrayOf(-1, 1)) {
                    val dx = sx * R2
                    val dy = sy * R2
                    if (armP > 0f) {
                        val r1 = 22f
                        val r2 = 22f + 128f * armP
                        drawLine(Color.White.copy(alpha = a(1f)), lp(dx * r1, dy * r1), lp(dx * r2, dy * r2), max(1.5f, 3.2f * ls))
                    }
                    drawLine(NexusBlue.copy(alpha = a(blueA)), lp(dx * 38f, dy * 38f), lp(dx * 47f, dy * 47f), max(2f, 4f * ls))

                    if (rotorA > 0f) {
                        val rc = lp(sx * 140f, sy * 140f)
                        drawCircle(
                            Color.White.copy(alpha = a(rotorA)), radius = 48f * ls * rotorP, center = rc,
                            style = Stroke(width = max(2f, 3.8f * ls)),
                        )
                        val base = (sx * sy).toFloat() * spin       // rotores contrarrotantes
                        val ar = 32f * ls * rotorP
                        for (j in 0..1) {
                            drawArc(
                                color = NexusBlue.copy(alpha = a(blueA)),
                                startAngle = base + 200f + j * 180f,
                                sweepAngle = 85f,
                                useCenter = false,
                                topLeft = Offset(rc.x - ar, rc.y - ar),
                                size = Size(2f * ar, 2f * ar),
                                style = Stroke(width = max(2f, 3.4f * ls), cap = StrokeCap.Round),
                            )
                        }
                        drawCircle(Color.White.copy(alpha = a(rotorA)), radius = 4.2f * ls * rotorP, center = rc)
                    }
                }
            }

            val hubP = easeOutBack(seg(t, 0.5f, 0.95f)).coerceAtLeast(0f)
            val hubA = seg(t, 0.5f, 0.8f)
            drawCircle(Color.White.copy(alpha = a(hubA)), radius = 22f * ls * hubP, center = lp(0f, 0f), style = Stroke(width = max(2f, 3.2f * ls)))
            drawCircle(NexusBlue.copy(alpha = a(hubA)), radius = 10.5f * ls * hubP, center = lp(0f, 0f))
            if (t in 1.6f..4.6f) {
                val ph = ((t - 1.6f) / 1.3f) % 1f
                drawCircle(
                    NexusBlue.copy(alpha = a(0.5f * (1f - ph))),
                    radius = (24f + 50f * ph) * ls, center = lp(0f, 0f),
                    style = Stroke(width = max(1.5f, 2f * ls)),
                )
            }

            // ── 10. Marca ──────────────────────────────────────────────────────────
            val tSize = 0.10f * u
            val trackP = easeOut(seg(t, 3.3f, 4.6f))
            drawTracked(
                title, cx, h * 0.62f, tSize, tSize * 0.42f * (0.7f + 0.3f * trackP), serif, Color.White,
                alphaFor = { i -> a(easeOut(seg(t, 3.3f + 0.12f * i, 3.9f + 0.12f * i))) },
                dyFor = { i -> (1f - easeOut(seg(t, 3.3f + 0.12f * i, 3.9f + 0.12f * i))) * u * 0.02f },
            )

            val divHalf = 0.16f * u * easeOut(seg(t, 4.1f, 4.8f))
            if (divHalf > 0f) {
                drawLine(
                    DividerBlue.copy(alpha = a(0.9f)),
                    Offset(cx - divHalf, h * 0.66f), Offset(cx + divHalf, h * 0.66f), max(1.5f, u * 0.002f),
                )
            }

            val subSize = 0.026f * u
            drawTracked(
                subtitle, cx, h * 0.705f, subSize, subSize * 0.5f, light, Color.White,
                alphaFor = { a(0.85f * seg(t, 4.4f, 5.0f)) },
            )

            val footSize = 0.02f * u
            drawTracked(
                footer, cx, h * 0.965f, footSize, footSize * 0.45f, light, Color.White,
                alphaFor = { a(0.45f * seg(t, 4.7f, 5.2f)) },
            )
        }
    }

    private fun DrawScope.drawRectOutline(topLeft: Offset, size: Size, color: Color, width: Float) {
        drawRect(color = color, topLeft = topLeft, size = size, style = Stroke(width = width))
    }

    /** Dibuja texto letra por letra, centrado, con espaciado (tracking) en píxeles. */
    private fun DrawScope.drawTracked(
        text: String,
        cx: Float,
        baseline: Float,
        sizePx: Float,
        tracking: Float,
        typeface: Typeface,
        color: Color,
        alphaFor: (Int) -> Float = { 1f },
        dyFor: (Int) -> Float = { 0f },
    ) {
        paint.typeface = typeface
        paint.textSize = sizePx
        val widths = FloatArray(text.length) { paint.measureText(text[it].toString()) }
        val total = widths.sum() + tracking * (text.length - 1)
        var x = cx - total / 2f
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            for (i in text.indices) {
                val alpha = alphaFor(i)
                if (alpha > 0.01f) {
                    paint.color = color.copy(alpha = alpha.coerceIn(0f, 1f)).toArgb()
                    native.drawText(text[i].toString(), x, baseline + dyFor(i), paint)
                }
                x += widths[i] + tracking
            }
        }
    }
}
