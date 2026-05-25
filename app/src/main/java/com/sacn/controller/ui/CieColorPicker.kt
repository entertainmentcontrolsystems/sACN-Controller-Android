@file:Suppress("SpellCheckingInspection")

package com.sacn.controller.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sacn.controller.model.ChannelDef
import com.sacn.controller.model.resolveEmitterXy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

// ── CIE 1931 2° observer spectral locus (10 nm steps, 380–700 nm) ─────────────
// Source: CIE 1931 standard colorimetric observer (Wyszecki & Stiles 2nd ed.)

private val LOCUS_X = floatArrayOf(
    0.1741f, 0.1741f, 0.1733f, 0.1726f, 0.1714f, 0.1689f, 0.1644f, 0.1566f,
    0.1440f, 0.1241f, 0.0913f, 0.0454f, 0.0082f, 0.0139f, 0.0743f, 0.1547f,
    0.2296f, 0.3016f, 0.3731f, 0.4441f, 0.5125f, 0.5752f, 0.6270f, 0.6658f,
    0.6914f, 0.7079f, 0.7190f, 0.7260f, 0.7300f, 0.7316f, 0.7334f, 0.7344f, 0.7347f
)
private val LOCUS_Y = floatArrayOf(
    0.0050f, 0.0049f, 0.0048f, 0.0048f, 0.0051f, 0.0069f, 0.0109f, 0.0177f,
    0.0297f, 0.0578f, 0.1327f, 0.2950f, 0.5384f, 0.7502f, 0.8338f, 0.8059f,
    0.7544f, 0.6923f, 0.6244f, 0.5547f, 0.4866f, 0.4242f, 0.3725f, 0.3340f,
    0.3084f, 0.2920f, 0.2810f, 0.2740f, 0.2700f, 0.2681f, 0.2665f, 0.2656f, 0.2653f
)

// sRGB primaries (for gamut triangle overlay)
private val PRIMARIES = listOf(
    0.6400f to 0.3300f,
    0.3000f to 0.6000f,
    0.1500f to 0.0600f
)
private val D65 = 0.3127f to 0.3290f

private const val X_MAX = 0.80f
private const val Y_MAX = 0.90f

// ── Color math ────────────────────────────────────────────────────────────────

private fun lin(v: Float): Float =
    if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

private fun gam(v: Float): Float =
    if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f

/** Ray-casting point-in-polygon (spectral locus + purple line). */
internal fun insideLocus(cx: Float, cy: Float): Boolean {
    var inside = false
    val n = LOCUS_X.size
    var j = n - 1
    for (i in 0 until n) {
        val xi = LOCUS_X[i]; val yi = LOCUS_Y[i]
        val xj = LOCUS_X[j]; val yj = LOCUS_Y[j]
        if ((yi > cy) != (yj > cy) &&
            cx < (xj - xi) * (cy - yi) / (yj - yi) + xi
        ) inside = !inside
        j = i
    }
    return inside
}

/** xy chromaticity → display sRGB [0,1], normalised to max channel = 1. */
internal fun xyToDisplayRgb(x: Float, y: Float): Triple<Float, Float, Float> {
    if (y < 1e-6f) return Triple(0f, 0f, 0f)
    val X = x / y; val Z = (1f - x - y) / y
    // CIE XYZ D65 → linear sRGB (IEC 61966-2-1), Y assumed = 1
    var rl =  3.2406f * X - 1.5372f - 0.4986f * Z
    var gl = -0.9689f * X + 1.8758f + 0.0415f * Z
    var bl =  0.0557f * X - 0.2040f + 1.0570f * Z
    rl = rl.coerceAtLeast(0f); gl = gl.coerceAtLeast(0f); bl = bl.coerceAtLeast(0f)
    val mx = maxOf(rl, gl, bl, 1e-6f)
    return Triple(gam(rl / mx), gam(gl / mx), gam(bl / mx))
}

// ── Multi-emitter forward / inverse model ─────────────────────────────────────

/**
 * Forward: compute current CIE xy from all colour channels and their DMX levels.
 *
 * Each emitter i at relative level li contributes:
 *   Xi = li · xi/yi,   Yi = li,   Zi = li · (1−xi−yi)/yi
 * Chromaticity = (ΣXi / ΣXYZi, ΣYi / ΣXYZi)
 *
 * Subtractive channels (ColorSub_*, ColorCMY_*): inverted — lvl=max = no filter (white),
 * lvl=0 = fully saturated color. effective_li = max - lvl.
 */
fun channelsToXy(channels: List<ChannelDef>, values: Map<Int, Int>): Pair<Float, Float> {
    var sumX = 0f; var sumY = 0f; var sumZ = 0f
    for (ch in channels) {
        val (xi, yi) = resolveEmitterXy(ch.name) ?: continue
        if (yi < 1e-6f) continue
        val lvl = (values[ch.offset] ?: ch.defaultValue).toFloat() / ch.maxValue
        val isSub = ch.name.startsWith("ColorSub_") || ch.name.startsWith("ColorCMY_")
        val li = if (isSub) (ch.maxValue - (values[ch.offset] ?: ch.defaultValue)).toFloat() / ch.maxValue else lvl
        sumX += li * xi / yi
        sumY += li
        sumZ += li * (1f - xi - yi) / yi
    }
    val s = sumX + sumY + sumZ
    return if (s < 1e-8f) D65 else sumX / s to sumY / s
}

/**
 * Inverse: projected-gradient descent to find channel levels that
 * reproduce target CIE xy as closely as possible.
 *
 * Gradient derivation (x = ΣXi / S, S = ΣXi+ΣYi+ΣZi):
 *   ∂x/∂li = (xi − x) / (yi · S)
 *   ∂y/∂li = (yi − y) / (yi · S)
 *
 * We descend the squared chromaticity error, project to [0, maxValue].
 *
 * Subtractive channels (ColorSub_*, ColorCMY_*): inverted — lvl=0 means
 * fully saturated color filter, lvl=max means no filter (white).
 * For these, effective_li = max[i] - lvl[i] for the emitter contribution.
 */
fun solveEmitterMix(
    targetX : Float, targetY : Float,
    channels: List<ChannelDef>,
    values  : Map<Int, Int>
): Map<Int, Int> {
    if (channels.isEmpty()) return emptyMap()
    val n   = channels.size
    val max = FloatArray(n) { channels[it].maxValue.toFloat() }
    // true for channels where zero = saturated color, max = white
    val isSubtractive = BooleanArray(n) {
        val name = channels[it].name
        name.startsWith("ColorSub_") || name.startsWith("ColorCMY_")
    }

    // Start from current values; seed at reasonable starting points
    val lvl = FloatArray(n) { i ->
        val cur = (values[channels[i].offset] ?: channels[i].defaultValue).toFloat()
        if (cur < 1f) {
            // For additive: seed at 20% of max
            // For subtractive: seed at 80% of max (mostly open filter = white)
            if (isSubtractive[i]) max[i] * 0.8f else max[i] * 0.2f
        } else cur
    }

    val alpha = 0.8f   // step size — large but stable with clamp
    repeat(40) {
        var sumX = 0f; var sumY = 0f; var sumZ = 0f
        for (i in 0 until n) {
            val (xi, yi) = resolveEmitterXy(channels[i].name) ?: return@repeat
            if (yi < 1e-6f) return@repeat
            // Effective level: for subtractive, invert (max=no color, 0=saturated)
            val effLvl = if (isSubtractive[i]) max[i] - lvl[i] else lvl[i]
            sumX += effLvl * xi / yi
            sumY += effLvl
            sumZ += effLvl * (1f - xi - yi) / yi
        }
        val s = sumX + sumY + sumZ
        if (s < 1e-8f) return@repeat
        val cx = sumX / s; val cy = sumY / s
        val ex = targetX - cx; val ey = targetY - cy
        if (ex * ex + ey * ey < 1e-10f) return@repeat   // converged

        for (i in 0 until n) {
            val (xi, yi) = resolveEmitterXy(channels[i].name) ?: continue
            if (yi < 1e-6f) continue
            val dxdl = (xi - cx) / (yi * s)
            val dydl = (yi - cy) / (yi * s)
            // For subtractive: gradient is inverted (lvl↑ = less color)
            val grad = if (isSubtractive[i]) -(ex * dxdl + ey * dydl) else (ex * dxdl + ey * dydl)
            lvl[i] = (lvl[i] + alpha * grad * max[i]).coerceIn(0f, max[i])
        }
    }
    return buildMap { for (i in 0 until n) put(channels[i].offset, lvl[i].toInt()) }
}

// ── Diagram bitmap (computed off-thread) ─────────────────────────────────────

@Composable
internal fun rememberCieBitmap(): ImageBitmap? {
    var bmp by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        bmp = withContext(Dispatchers.Default) {
            val sz = 256
            val b  = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
            for (py in 0 until sz) {
                val cy = (1f - py.toFloat() / sz) * Y_MAX
                for (px in 0 until sz) {
                    val cx = px.toFloat() / sz * X_MAX
                    if (insideLocus(cx, cy)) {
                        val (r, g, blue) = xyToDisplayRgb(cx, cy)
                        b.setPixel(px, py, AndroidColor.argb(
                            255,
                            (r    * 255).toInt().coerceIn(0, 255),
                            (g    * 255).toInt().coerceIn(0, 255),
                            (blue * 255).toInt().coerceIn(0, 255)
                        ))
                    }
                }
            }
            b.asImageBitmap()
        }
    }
    return bmp
}

// ── Coordinate helpers ────────────────────────────────────────────────────────

private fun c2c(cx: Float, cy: Float, w: Float, h: Float) =
    Offset(cx / X_MAX * w, (1f - cy / Y_MAX) * h)

private fun canvas2Cie(ox: Float, oy: Float, w: Float, h: Float) =
    (ox / w * X_MAX).coerceIn(0f, X_MAX) to ((1f - oy / h) * Y_MAX).coerceIn(0f, Y_MAX)

// ── Composable ────────────────────────────────────────────────────────────────

/**
 * Interactive CIE 1931 xy chromaticity diagram for multi-emitter fixtures.
 *
 * Supports any set of COLOR-category channels (DR, R, RY, GY, G, C, B, I,
 * W, WW, CW, A, UV, L, …).  Touch/drag picks a target chromaticity and
 * solves the optimal emitter mix via gradient descent.
 *
 * @param overrideXy If provided, directly display this xy point instead of
 *   computing from channels (used for xy pass-through fixtures).
 */
@Composable
fun CieColorPicker(
    colorChannels: List<ChannelDef>,
    values       : Map<Int, Int>,
    onValueChange: (offset: Int, value: Int) -> Unit,
    overrideXy   : Pair<Float, Float>? = null
) {
    val bitmap = rememberCieBitmap()
    val (curX, curY) = overrideXy ?: channelsToXy(colorChannels, values)
    val (pr, pg, pb) = xyToDisplayRgb(curX, curY)
    val previewColor  = Color(red = pr, green = pg, blue = pb)

    fun applyTarget(x: Float, y: Float) {
        if (!insideLocus(x, y)) return
        solveEmitterMix(x, y, colorChannels, values)
            .forEach { (off, v) -> onValueChange(off, v) }
    }

    Column(Modifier.fillMaxWidth()) {

        // Header
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text("CIE 1931 xy", color = TextSecond, fontSize = 10.sp)
            Box(
                Modifier.size(28.dp, 14.dp).clip(RoundedCornerShape(3.dp)).background(previewColor)
            )
            Text("x ${"%.4f".format(curX)}  y ${"%.4f".format(curY)}", color = TextSecond, fontSize = 10.sp)
        }

        // Diagram
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(X_MAX / Y_MAX)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF080810))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(colorChannels) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val (cx, cy) = canvas2Cie(
                                down.position.x, down.position.y,
                                size.width.toFloat(), size.height.toFloat()
                            )
                            applyTarget(cx, cy)
                            down.consume()
                            while (true) {
                                val ev  = awaitPointerEvent()
                                val chg = ev.changes.firstOrNull() ?: break
                                if (chg.changedToUpIgnoreConsumed()) break
                                val (dx, dy) = canvas2Cie(
                                    chg.position.x, chg.position.y,
                                    size.width.toFloat(), size.height.toFloat()
                                )
                                applyTarget(dx, dy)
                                chg.consume()
                            }
                        }
                    }
            ) {
                val w = size.width; val h = size.height

                // 1 — Gamut fill
                if (bitmap != null) {
                    drawImage(image = bitmap, dstSize = IntSize(w.toInt(), h.toInt()))
                } else {
                    drawRect(Color(0xFF151520))
                }

                // 2 — Spectral locus
                val locusPath = Path()
                val p0 = c2c(LOCUS_X[0], LOCUS_Y[0], w, h)
                locusPath.moveTo(p0.x, p0.y)
                for (i in 1 until LOCUS_X.size) {
                    val pt = c2c(LOCUS_X[i], LOCUS_Y[i], w, h)
                    locusPath.lineTo(pt.x, pt.y)
                }
                locusPath.close()
                drawPath(locusPath, Color.White.copy(alpha = 0.25f), style = Stroke(1.2f))

                // 3 — sRGB gamut triangle (dashed)
                val rpt = c2c(PRIMARIES[0].first, PRIMARIES[0].second, w, h)
                val gpt = c2c(PRIMARIES[1].first, PRIMARIES[1].second, w, h)
                val bpt = c2c(PRIMARIES[2].first, PRIMARIES[2].second, w, h)
                val triPath = Path()
                triPath.moveTo(rpt.x, rpt.y); triPath.lineTo(gpt.x, gpt.y)
                triPath.lineTo(bpt.x, bpt.y); triPath.close()
                drawPath(
                    triPath, Color.White.copy(alpha = 0.20f),
                    style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)))
                )

                // 4 — D65 white point
                val wpt = c2c(D65.first, D65.second, w, h)
                drawCircle(Color.White.copy(alpha = 0.55f), 4f, wpt, style = Stroke(1.5f))
                drawCircle(Color.White.copy(alpha = 0.25f), 2f, wpt, style = Fill)

                // 5 — Emitter chromaticity dots (brightness proportional to level)
                for (ch in colorChannels) {
                    val (exi, eyi) = resolveEmitterXy(ch.name) ?: continue
                    val ePt   = c2c(exi, eyi, w, h)
                    val level = (values[ch.offset] ?: ch.defaultValue).toFloat() / ch.maxValue
                    val alpha = 0.35f + 0.65f * level
                    drawCircle(Color.White.copy(alpha = alpha), 5f, ePt)
                    drawCircle(Color.White.copy(alpha = alpha * 0.4f), 5f, ePt, style = Stroke(1f))
                }

                // 6 — Current chromaticity crosshair
                val pos = c2c(curX, curY, w, h)
                val cr  = 9f; val arm = cr * 1.8f
                drawCircle(previewColor,      cr - 2.5f, pos)
                drawCircle(Color.White,       cr,        pos, style = Stroke(2f))
                drawLine(Color.White, Offset(pos.x - arm, pos.y), Offset(pos.x - cr + 1f, pos.y), 1.5f)
                drawLine(Color.White, Offset(pos.x + cr - 1f, pos.y), Offset(pos.x + arm, pos.y), 1.5f)
                drawLine(Color.White, Offset(pos.x, pos.y - arm), Offset(pos.x, pos.y - cr + 1f), 1.5f)
                drawLine(Color.White, Offset(pos.x, pos.y + cr - 1f), Offset(pos.x, pos.y + arm), 1.5f)
            }

            // Loading overlay
            if (bitmap == null) {
                Text(
                    "Computing gamut…",
                    color    = TextSecond,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
