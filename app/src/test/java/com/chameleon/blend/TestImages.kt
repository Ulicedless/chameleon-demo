package com.chameleon.blend

import com.chameleon.blend.core.color.ColorMath
import com.chameleon.blend.core.blend.SceneAnalysis
import com.chameleon.blend.core.img.RasterImage
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Synthetic fixtures for engine tests: a "photograph" of a city street at golden hour and a flat
 * shaded illustration cut-out. They are not art, but they carry the properties the blend engine
 * cares about: a directional key light, a ground plane, sensor noise, flat cel shading and an
 * outline-heavy silhouette.
 */
object TestImages {

    /** Shared, cached analysis of the synthetic street photo (keeping tests fast). */
    private val cachedAnalysis: SceneAnalysis by lazy { SceneAnalysis.analyze(streetPhoto(640, 460)) }

    fun analysisForTest(): SceneAnalysis = cachedAnalysis

    fun streetPhoto(width: Int = 900, height: Int = 640, seed: Int = 7): RasterImage {
        val img = RasterImage(width, height)
        val sunX = width * 0.80f
        val sunY = height * 0.16f
        val horizon = height * 0.70f
        var state = seed.toULong() * 2654435761uL

        for (y in 0 until height) {
            val v = y / height.toFloat()
            for (x in 0 until width) {
                val i = y * width + x
                var r: Float
                var g: Float
                var b: Float
                if (y < horizon) {
                    // sky gradient: deep blue up top, warm haze at the horizon
                    val t = (v / 0.70f).coerceIn(0f, 1f)
                    r = 0.16f + 0.62f * t.pow(2.1f)
                    g = 0.28f + 0.44f * t.pow(2.0f)
                    b = 0.58f + 0.06f * t.pow(1.4f)
                } else {
                    // asphalt, slightly brighter where the key light lands
                    val t = ((v - 0.70f) / 0.30f).coerceIn(0f, 1f)
                    val lightFalloff = 0.72f + 0.5f * ((x / width.toFloat() - 0.2f)).coerceIn(0f, 1f)
                    r = (0.30f + 0.10f * t) * lightFalloff
                    g = (0.30f + 0.09f * t) * lightFalloff
                    b = (0.31f + 0.08f * t) * lightFalloff
                }
                // sun glow
                val dx = x - sunX
                val dy = y - sunY
                val dist = sqrt(dx * dx + dy * dy)
                val glow = 0.85f * exp(-(dist * dist) / (2f * (width * 0.22f).pow(2)))
                r += glow
                g += glow * 0.82f
                b += glow * 0.55f

                state = state * 6364136223846793005uL + 1442695040888963407uL
                val noise = ((state shr 40).toInt() and 0xFF) / 255f - 0.5f
                val grain = noise * 0.035f

                img.r[i] = (r + grain).coerceIn(0f, 1f)
                img.g[i] = (g + grain).coerceIn(0f, 1f)
                img.b[i] = (b + grain * 1.1f).coerceIn(0f, 1f)
            }
        }

        // building silhouettes on the left, catching less light
        val blocks = listOf(
            floatArrayOf(0.02f, 0.36f, 0.16f, 0.70f),
            floatArrayOf(0.14f, 0.28f, 0.27f, 0.70f),
            floatArrayOf(0.25f, 0.44f, 0.36f, 0.70f),
            floatArrayOf(0.60f, 0.40f, 0.74f, 0.70f),
            floatArrayOf(0.70f, 0.50f, 0.86f, 0.70f),
        )
        for ((idx, block) in blocks.withIndex()) {
            val x0 = (block[0] * width).toInt()
            val y0 = (block[1] * height).toInt()
            val x1 = (block[2] * width).toInt()
            val y1 = (block[3] * height).toInt()
            val base = 0.16f + 0.05f * idx
            val lit = if (x0 > width * 0.5f) 1.25f else 1f
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val i = y * width + x
                    val window = ((x - x0) / 22 + (y - y0) / 26) % 2 == 0 &&
                        (x - x0) % 22 < 9 && (y - y0) % 26 < 11
                    val c = if (window) 0.72f * lit else base * lit
                    img.r[i] = (c * 1.05f).coerceIn(0f, 1f)
                    img.g[i] = (c * 0.98f).coerceIn(0f, 1f)
                    img.b[i] = (c * 0.92f).coerceIn(0f, 1f)
                }
            }
        }
        return img
    }

    /** Flat shaded "anime" character with alpha, standing pose. */
    fun animeCharacter(width: Int = 320, height: Int = 560): RasterImage {
        val img = RasterImage(width, height)
        for (i in 0 until img.size) img.a[i] = 0f
        val ink = floatArrayOf(0.16f, 0.13f, 0.20f)
        val skin = floatArrayOf(0.99f, 0.87f, 0.79f)
        val skinShade = floatArrayOf(0.90f, 0.74f, 0.68f)
        val hair = floatArrayOf(0.42f, 0.18f, 0.36f)
        val hairLight = floatArrayOf(0.56f, 0.26f, 0.46f)
        val shirt = floatArrayOf(0.94f, 0.95f, 0.98f)
        val shirtShade = floatArrayOf(0.78f, 0.82f, 0.92f)
        val accent = floatArrayOf(0.28f, 0.47f, 0.82f)
        val skirt = floatArrayOf(0.32f, 0.30f, 0.44f)
        val shoe = floatArrayOf(0.24f, 0.22f, 0.26f)

        val cx = width / 2f

        // ---- legs
        capsule(img, cx - 34f, 400f, cx - 26f, 500f, 16f, ink)
        capsule(img, cx + 34f, 400f, cx + 26f, 500f, 16f, ink)
        capsule(img, cx - 34f, 400f, cx - 26f, 498f, 13f, skin)
        capsule(img, cx + 34f, 400f, cx + 26f, 498f, 13f, skin)
        capsule(img, cx - 36f, 492f, cx - 26f, 505f, 13f, shoe)
        capsule(img, cx + 36f, 492f, cx + 26f, 505f, 13f, shoe)

        // ---- skirt
        trapezoid(img, cx - 62f, 330f, cx + 62f, 330f, cx + 86f, 416f, cx - 86f, 416f, ink)
        trapezoid(img, cx - 56f, 336f, cx + 56f, 336f, cx + 78f, 410f, cx - 78f, 410f, skirt)
        trapezoid(img, cx - 56f, 336f, cx - 10f, 336f, cx - 26f, 410f, cx - 78f, 410f, floatArrayOf(0.26f, 0.25f, 0.38f))

        // ---- torso
        trapezoid(img, cx - 58f, 176f, cx + 58f, 176f, cx + 52f, 342f, cx - 52f, 342f, ink)
        trapezoid(img, cx - 52f, 180f, cx + 52f, 180f, cx + 46f, 338f, cx - 46f, 338f, shirt)
        trapezoid(img, cx + 6f, 180f, cx + 52f, 180f, cx + 46f, 338f, cx + 4f, 338f, shirtShade)
        // collar + tie accent
        trapezoid(img, cx - 26f, 176f, cx + 26f, 176f, cx + 6f, 226f, cx - 6f, 226f, accent)

        // ---- arms
        capsule(img, cx - 58f, 196f, cx - 78f, 320f, 17f, ink)
        capsule(img, cx + 58f, 196f, cx + 78f, 320f, 17f, ink)
        capsule(img, cx - 58f, 196f, cx - 76f, 316f, 13f, shirtShade)
        capsule(img, cx + 58f, 196f, cx + 78f, 318f, 13f, shirt)
        fillCircle(img, cx + 79f, 326f, 13f, skin)
        fillCircle(img, cx - 77f, 326f, 13f, skin)

        // ---- head + hair
        fillEllipse(img, cx, 118f, 66f, 74f, hair)
        fillEllipse(img, cx - 30f, 96f, 34f, 30f, hairLight)
        fillEllipse(img, cx, 124f, 52f, 60f, ink)
        fillEllipse(img, cx, 126f, 49f, 57f, skin)
        // fringe
        fillEllipse(img, cx - 34f, 84f, 34f, 26f, hair)
        fillEllipse(img, cx + 34f, 84f, 34f, 26f, hair)
        // shadow side of the face (light from viewer's left in the artwork)
        fillEllipse(img, cx + 30f, 130f, 20f, 44f, skinShade)
        // eyes
        fillEllipse(img, cx - 20f, 128f, 12f, 15f, floatArrayOf(0.20f, 0.36f, 0.62f))
        fillEllipse(img, cx + 20f, 128f, 12f, 15f, floatArrayOf(0.20f, 0.36f, 0.62f))
        fillEllipse(img, cx - 20f, 122f, 5f, 5f, floatArrayOf(1f, 1f, 1f))
        fillEllipse(img, cx + 20f, 122f, 5f, 5f, floatArrayOf(1f, 1f, 1f))
        // mouth
        fillEllipse(img, cx, 158f, 8f, 4f, floatArrayOf(0.72f, 0.42f, 0.44f))

        return img
    }

    fun toArgb(raster: RasterImage, background: FloatArray = floatArrayOf(1f, 1f, 1f)): IntArray {
        val out = IntArray(raster.size)
        for (i in 0 until raster.size) {
            val a = raster.a[i]
            val r = raster.r[i] * a + background[0] * (1f - a)
            val g = raster.g[i] * a + background[1] * (1f - a)
            val b = raster.b[i] * a + background[2] * (1f - a)
            out[i] = (0xFF shl 24) or
                ((r.coerceIn(0f, 1f) * 255f).toInt() shl 16) or
                ((g.coerceIn(0f, 1f) * 255f).toInt() shl 8) or
                (b.coerceIn(0f, 1f) * 255f).toInt()
        }
        return out
    }

    /**
     * Simulates the colour fringe a JPEG matte leaves behind: the semi-transparent border keeps the
     * old backdrop colour. Real cut-outs taken from white studio backdrops look exactly like this.
     */
    fun withBackdropFringe(
        source: RasterImage,
        fringe: FloatArray = floatArrayOf(0.96f, 0.97f, 0.99f),
        minAlpha: Float = 0.03f,
        maxAlpha: Float = 0.97f,
    ): RasterImage {
        val out = source.copy()
        for (i in 0 until out.size) {
            val a = out.a[i]
            if (a > minAlpha && a < maxAlpha) {
                out.r[i] = fringe[0]
                out.g[i] = fringe[1]
                out.b[i] = fringe[2]
            }
        }
        return out
    }

    // ---------------------------------------------------------------- drawing helpers

    private fun blend(img: RasterImage, x: Int, y: Int, color: FloatArray, coverage: Float) {
        if (coverage <= 0.001f) return
        if (x < 0 || y < 0 || x >= img.width || y >= img.height) return
        val i = y * img.width + x
        val a = (coverage * color[3]).coerceIn(0f, 1f)
        val dstA = img.a[i]
        val outA = a + dstA * (1f - a)
        if (outA <= 1e-5f) {
            img.a[i] = 0f
            return
        }
        img.r[i] = (color[0] * a + img.r[i] * dstA * (1f - a)) / outA
        img.g[i] = (color[1] * a + img.g[i] * dstA * (1f - a)) / outA
        img.b[i] = (color[2] * a + img.b[i] * dstA * (1f - a)) / outA
        img.a[i] = outA
    }

    private fun rgba(r: Float, g: Float, b: Float, a: Float = 1f) = floatArrayOf(r, g, b, a)

    fun fillCircle(img: RasterImage, cx: Float, cy: Float, radius: Float, color: FloatArray) {
        val color4 = if (color.size == 4) color else floatArrayOf(color[0], color[1], color[2], 1f)
        val x0 = max(0, (cx - radius - 1).toInt())
        val x1 = min(img.width - 1, (cx + radius + 1).toInt())
        val y0 = max(0, (cy - radius - 1).toInt())
        val y1 = min(img.height - 1, (cy + radius + 1).toInt())
        for (y in y0..y1) {
            for (x in x0..x1) {
                val d = sqrt((x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy))
                val coverage = (radius - d + 0.5f).coerceIn(0f, 1f)
                blend(img, x, y, color4, coverage)
            }
        }
    }

    fun fillEllipse(
        img: RasterImage,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        color: FloatArray,
    ) {
        val color4 = if (color.size == 4) color else floatArrayOf(color[0], color[1], color[2], 1f)
        val x0 = max(0, (cx - rx - 1).toInt())
        val x1 = min(img.width - 1, (cx + rx + 1).toInt())
        val y0 = max(0, (cy - ry - 1).toInt())
        val y1 = min(img.height - 1, (cy + ry + 1).toInt())
        for (y in y0..y1) {
            for (x in x0..x1) {
                val nx = (x + 0.5f - cx) / rx
                val ny = (y + 0.5f - cy) / ry
                val d = sqrt(nx * nx + ny * ny)
                val coverage = ((1f - d) * min(rx, ry) + 0.5f).coerceIn(0f, 1f)
                blend(img, x, y, color4, coverage)
            }
        }
    }

    private fun capsule(
        img: RasterImage,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        radius: Float,
        color: FloatArray,
    ) {
        val steps = 64
        for (s in 0..steps) {
            val t = s / steps.toFloat()
            val x = x0 + (x1 - x0) * t
            val y = y0 + (y1 - y0) * t
            fillCircle(img, x, y, radius, color)
        }
    }

    private fun trapezoid(
        img: RasterImage,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        cx2: Float,
        cy2: Float,
        dx: Float,
        dy: Float,
        color: FloatArray,
    ) {
        val color4 = if (color.size == 4) color else floatArrayOf(color[0], color[1], color[2], 1f)
        val minX = max(0, minOf(ax, bx, cx2, dx).toInt())
        val maxX = min(img.width - 1, maxOf(ax, bx, cx2, dx).toInt() + 1)
        val minY = max(0, minOf(ay, by, cy2, dy).toInt())
        val maxY = min(img.height - 1, maxOf(ay, by, cy2, dy).toInt() + 1)
        for (y in minY..maxY) {
            val py = y + 0.5f
            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            val edges = arrayOf(
                floatArrayOf(ax, ay, bx, by),
                floatArrayOf(bx, by, cx2, cy2),
                floatArrayOf(cx2, cy2, dx, dy),
                floatArrayOf(dx, dy, ax, ay),
            )
            for (e in edges) {
                val (xa, ya, xb, yb) = e
                if ((ya <= py && yb > py) || (yb <= py && ya > py)) {
                    val t = (py - ya) / (yb - ya)
                    val xi = xa + (xb - xa) * t
                    if (xi < left) left = xi
                    if (xi > right) right = xi
                }
            }
            if (left > right) continue
            for (x in max(minX, left.toInt() - 1)..min(maxX, right.toInt() + 1)) {
                val px = x + 0.5f
                val coverage = (min(right - px, px - left) + 0.5f).coerceIn(0f, 1f)
                blend(img, x, y, color4, coverage)
            }
        }
    }
}
