package com.chameleon.blend.core.img

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Scalar plane operations: separable blurs, morphology, resampling, edge statistics.
 *
 * Everything operates on single-channel [FloatArray] planes laid out row-major, which keeps the
 * number of temporary allocations predictable while dragging sliders.
 */
object ImageOps {

    private const val EDGE_CLAMP = true

    fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x < edge0) 0f else 1f
        val t = clamp01((x - edge0) / (edge1 - edge0))
        return t * t * (3f - 2f * t)
    }

    /** Separable box blur. Radius <= 0 returns a copy. */
    fun boxBlur(plane: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return plane.copyOf()
        val tmp = boxBlurHorizontal(plane, width, height, radius)
        return boxBlurVertical(tmp, width, height, radius)
    }

    fun boxBlurHorizontal(plane: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val out = FloatArray(plane.size)
        val window = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (i in -radius..radius) {
                sum += plane[row + clampX(i, width)]
            }
            for (x in 0 until width) {
                out[row + x] = sum / window
                val outIdx = clampX(x - radius, width)
                val inIdx = clampX(x + radius + 1, width)
                sum += plane[row + inIdx] - plane[row + outIdx]
            }
        }
        return out
    }

    fun boxBlurVertical(plane: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val out = FloatArray(plane.size)
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var sum = 0f
            for (i in -radius..radius) {
                sum += plane[clampY(i, height) * width + x]
            }
            for (y in 0 until height) {
                out[y * width + x] = sum / window
                val outIdx = clampY(y - radius, height) * width + x
                val inIdx = clampY(y + radius + 1, height) * width + x
                sum += plane[inIdx] - plane[outIdx]
            }
        }
        return out
    }

    private fun clampX(x: Int, width: Int): Int =
        if (EDGE_CLAMP) x.coerceIn(0, width - 1) else ((x % width) + width) % width

    private fun clampY(y: Int, height: Int): Int =
        if (EDGE_CLAMP) y.coerceIn(0, height - 1) else ((y % height) + height) % height

    /** Approximates a gaussian with three box passes (standard "stack blur" trick). */
    fun blur(plane: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        if (sigma <= 0.35f) return plane.copyOf()
        val radius = max(1, (sigma * 1.15f).toInt())
        var result = plane.copyOf()
        repeat(3) {
            result = boxBlur(result, width, height, radius)
        }
        return result
    }

    /** Anisotropic gaussian, used to squash projected shadows along the ground plane. */
    fun blurAnisotropic(
        plane: FloatArray,
        width: Int,
        height: Int,
        sigmaX: Float,
        sigmaY: Float,
    ): FloatArray {
        var result = plane.copyOf()
        if (sigmaX > 0.35f) {
            val rx = max(1, (sigmaX * 1.15f).toInt())
            repeat(3) { result = boxBlurHorizontal(result, width, height, rx) }
        }
        if (sigmaY > 0.35f) {
            val ry = max(1, (sigmaY * 1.15f).toInt())
            repeat(3) { result = boxBlurVertical(result, width, height, ry) }
        }
        return result
    }

    fun erode(mask: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return mask.copyOf()
        val tmp = FloatArray(mask.size)
        val out = FloatArray(mask.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var m = 1f
                for (k in -radius..radius) {
                    val v = mask[row + clampX(x + k, width)]
                    if (v < m) m = v
                }
                tmp[row + x] = m
            }
        }
        for (x in 0 until width) {
            for (y in 0 until height) {
                var m = 1f
                for (k in -radius..radius) {
                    val v = tmp[clampY(y + k, height) * width + x]
                    if (v < m) m = v
                }
                out[y * width + x] = m
            }
        }
        return out
    }

    /** Edge aware refinement of a mask against a guide plane (single scale guided filter). */
    fun guidedFilter(
        guide: FloatArray,
        src: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        eps: Float,
    ): FloatArray {
        val meanGuide = boxBlur(guide, width, height, radius)
        val meanSrc = boxBlur(src, width, height, radius)
        val corrGuide = FloatArray(guide.size)
        val corrCross = FloatArray(guide.size)
        for (i in guide.indices) {
            corrGuide[i] = guide[i] * guide[i]
            corrCross[i] = guide[i] * src[i]
        }
        val meanGuideSq = boxBlur(corrGuide, width, height, radius)
        val meanCross = boxBlur(corrCross, width, height, radius)
        val aCoef = FloatArray(guide.size)
        val bCoef = FloatArray(guide.size)
        for (i in guide.indices) {
            val varGuide = meanGuideSq[i] - meanGuide[i] * meanGuide[i]
            val cov = meanCross[i] - meanGuide[i] * meanSrc[i]
            val a = cov / (varGuide + eps)
            aCoef[i] = a
            bCoef[i] = meanSrc[i] - a * meanGuide[i]
        }
        val meanA = boxBlur(aCoef, width, height, radius)
        val meanB = boxBlur(bCoef, width, height, radius)
        val out = FloatArray(guide.size)
        for (i in guide.indices) {
            out[i] = meanA[i] * guide[i] + meanB[i]
        }
        return out
    }

    fun resizeBilinear(
        src: FloatArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): FloatArray {
        val out = FloatArray(dstWidth * dstHeight)
        val sx = srcWidth.toFloat() / dstWidth
        val sy = srcHeight.toFloat() / dstHeight
        for (y in 0 until dstHeight) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (srcHeight - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, srcHeight - 1)
            val wy = fy - y0
            for (x in 0 until dstWidth) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (srcWidth - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, srcWidth - 1)
                val wx = fx - x0
                val top = lerp(src[y0 * srcWidth + x0], src[y0 * srcWidth + x1], wx)
                val bottom = lerp(src[y1 * srcWidth + x0], src[y1 * srcWidth + x1], wx)
                out[y * dstWidth + x] = lerp(top, bottom, wy)
            }
        }
        return out
    }

    fun resizeImage(src: RasterImage, dstWidth: Int, dstHeight: Int): RasterImage {
        if (dstWidth == src.width && dstHeight == src.height) return src.copy()
        return RasterImage(
            width = dstWidth,
            height = dstHeight,
            r = resizeBilinear(src.r, src.width, src.height, dstWidth, dstHeight),
            g = resizeBilinear(src.g, src.width, src.height, dstWidth, dstHeight),
            b = resizeBilinear(src.b, src.width, src.height, dstWidth, dstHeight),
            a = resizeBilinear(src.a, src.width, src.height, dstWidth, dstHeight),
        )
    }

    /** Fits the image inside a bounding box, preserving aspect ratio. */
    fun fitInside(src: RasterImage, maxWidth: Int, maxHeight: Int): RasterImage {
        val scale = min(
            maxWidth.toFloat() / src.width,
            maxHeight.toFloat() / src.height,
        )
        if (scale >= 1f) return src.copy()
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        return resizeImage(src, w, h)
    }

    /** 5-tap high-pass magnitude, a fast proxy for local detail energy. */
    fun highPass(gray: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(gray.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val c = gray[i]
                val l = gray[y * width + clampX(x - 1, width)]
                val r = gray[y * width + clampX(x + 1, width)]
                val u = gray[clampY(y - 1, height) * width + x]
                val d = gray[clampY(y + 1, height) * width + x]
                out[i] = c - 0.25f * (l + r + u + d)
            }
        }
        return out
    }

    fun gradientMagnitude(gray: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(gray.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val xm = gray[y * width + clampX(x - 1, width)]
                val xp = gray[y * width + clampX(x + 1, width)]
                val ym = gray[clampY(y - 1, height) * width + x]
                val yp = gray[clampY(y + 1, height) * width + x]
                val gx = xp - xm
                val gy = yp - ym
                out[y * width + x] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }

    /** Robust noise estimate: median absolute high-pass residual scaled to a sigma. */
    fun noiseSigma(gray: FloatArray, width: Int, height: Int, mask: FloatArray? = null): Float {
        val hp = highPass(gray, width, height)
        val values = ArrayList<Float>(gray.size / 4)
        var i = 0
        while (i < hp.size) {
            val keep = mask == null || mask[i] > 0.5f
            if (keep) values.add(abs(hp[i]))
            i += 3
        }
        if (values.size < 64) return 0.004f
        values.sort()
        val median = values[values.size / 2]
        // 1.4826 * MAD, then undo the high-pass gain (0.5 for the 5-tap kernel).
        return (median * 1.4826f / 0.5f).coerceIn(0f, 0.2f)
    }

    /** Mean gradient energy, used to compare the perceived sharpness of two images. */
    fun detailEnergy(gray: FloatArray, width: Int, height: Int, mask: FloatArray? = null): Float {
        val grad = gradientMagnitude(gray, width, height)
        var sum = 0f
        var count = 0
        for (i in grad.indices) {
            if (mask == null || mask[i] > 0.5f) {
                sum += grad[i]
                count++
            }
        }
        if (count == 0) return 0f
        return sum / count
    }

    fun percentile(values: FloatArray, fraction: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf()
        sorted.sort()
        val idx = (fraction.coerceIn(0f, 1f) * (sorted.size - 1)).toInt()
        return sorted[idx]
    }

    fun mean(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var sum = 0f
        for (v in values) sum += v
        return sum / values.size
    }

    /** Unsharp mask on a single plane. */
    fun unsharp(plane: FloatArray, width: Int, height: Int, amount: Float, sigma: Float): FloatArray {
        if (amount == 0f) return plane.copyOf()
        val blurred = blur(plane, width, height, sigma)
        val out = FloatArray(plane.size)
        for (i in plane.indices) {
            out[i] = plane[i] + amount * (plane[i] - blurred[i])
        }
        return out
    }
}
