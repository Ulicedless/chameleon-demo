package com.chameleon.blend.core.color

import com.chameleon.blend.core.img.ImageOps
import com.chameleon.blend.core.img.RasterImage
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * sRGB / CIELAB conversions plus the statistics used for colour transfer.
 *
 * Colour harmony between an illustration and a photograph is mostly a first and second order
 * statistics problem: matching the mean removes the cast, matching the standard deviation keeps the
 * contrast relationship believable.
 */
object ColorMath {

    private const val LUT_SIZE = 1024

    // pow() dominates the per-pixel cost of the engine, so the sRGB transfer functions are tabulated
    // with linear interpolation. Error stays below 1e-4 while running several times faster.
    private val toLinearLut = FloatArray(LUT_SIZE + 1) { i ->
        val v = i.toFloat() / LUT_SIZE
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private val toSrgbLut = FloatArray(LUT_SIZE + 1) { i ->
        val v = i.toFloat() / LUT_SIZE
        if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }

    fun srgbToLinearFast(c: Float): Float {
        if (c <= 0f) return 0f
        if (c >= 1f) return 1f
        val x = c * LUT_SIZE
        val i = x.toInt()
        val f = x - i
        return toLinearLut[i] + (toLinearLut[i + 1] - toLinearLut[i]) * f
    }

    fun linearToSrgbFast(c: Float): Float {
        if (c <= 0f) return 0f
        if (c >= 1f) return 1f
        val x = c * LUT_SIZE
        val i = x.toInt()
        val f = x - i
        return toSrgbLut[i] + (toSrgbLut[i + 1] - toSrgbLut[i]) * f
    }

    fun srgbToLinear(c: Float): Float {
        return srgbToLinearFast(c.coerceIn(0f, 1f))
    }

    fun linearToSrgb(c: Float): Float {
        return linearToSrgbFast(c.coerceIn(0f, 1f))
    }

    fun luminance(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    fun perceptiveLuma(r: Float, g: Float, b: Float): Float = 0.299f * r + 0.587f * g + 0.114f * b

    private const val EPS = 0.008856f
    private const val KAPPA = 903.3f

    private fun pivot(t: Float): Float =
        if (t > EPS) cbrt(t) else (KAPPA * t + 16f) / 116f

    /**
     * sRGB (D65) -> CIELAB. [out] receives L* (0..100), a* and b* (roughly -128..127).
     */
    fun rgbToLab(r: Float, g: Float, b: Float, out: FloatArray) {
        val rl = srgbToLinear(r)
        val gl = srgbToLinear(g)
        val bl = srgbToLinear(b)

        val x = (rl * 0.4124564f + gl * 0.3575761f + bl * 0.1804375f) / 0.95047f
        val y = (rl * 0.2126729f + gl * 0.7151522f + bl * 0.0721750f)
        val z = (rl * 0.0193339f + gl * 0.1191920f + bl * 0.9503041f) / 1.08883f

        val fx = pivot(x)
        val fy = pivot(y)
        val fz = pivot(z)

        out[0] = 116f * fy - 16f
        out[1] = 500f * (fx - fy)
        out[2] = 200f * (fy - fz)
    }

    fun labToRgb(L: Float, a: Float, b: Float, out: FloatArray) {
        val fy = (L + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f

        val xr = unpivot(fx) * 0.95047f
        val yr = unpivot(fy)
        val zr = unpivot(fz) * 1.08883f

        val rl = xr * 3.2404542f + yr * -1.5371385f + zr * -0.4985314f
        val gl = xr * -0.9692660f + yr * 1.8760108f + zr * 0.0415560f
        val bl = xr * 0.0556434f + yr * -0.2040259f + zr * 1.0572252f

        out[0] = linearToSrgb(rl)
        out[1] = linearToSrgb(gl)
        out[2] = linearToSrgb(bl)
    }

    private fun unpivot(t: Float): Float {
        val t3 = t * t * t
        return if (t3 > EPS) t3 else (116f * t - 16f) / KAPPA
    }

    class LabStats(
        val meanL: Float,
        val stdL: Float,
        val meanA: Float,
        val stdA: Float,
        val meanB: Float,
        val stdB: Float,
        val lowL: Float,
        val highL: Float,
        val meanChroma: Float,
        val count: Int,
    )

    /**
     * Weighted Lab statistics. [weights] can be a soft mask (alpha or region weight) and is combined
     * with an optional threshold on the image alpha channel.
     */
    fun labStats(
        img: RasterImage,
        weights: FloatArray? = null,
        alphaThreshold: Float = 0.5f,
        useImageAlpha: Boolean = false,
    ): LabStats {
        val lab = FloatArray(3)
        var n = 0
        var sumW = 0f
        var sL = 0f
        var sA = 0f
        var sB = 0f
        var sLL = 0f
        var sAA = 0f
        var sBB = 0f
        var sChroma = 0f
        for (i in 0 until img.size) {
            if (useImageAlpha && img.a[i] < alphaThreshold) continue
            val w = weights?.get(i) ?: 1f
            if (w <= 0.001f) continue
            rgbToLab(img.r[i], img.g[i], img.b[i], lab)
            sumW += w
            sL += w * lab[0]
            sA += w * lab[1]
            sB += w * lab[2]
            sLL += w * lab[0] * lab[0]
            sAA += w * lab[1] * lab[1]
            sBB += w * lab[2] * lab[2]
            sChroma += w * sqrt(lab[1] * lab[1] + lab[2] * lab[2])
            n++
        }
        if (n == 0 || sumW <= 0f) {
            return LabStats(50f, 15f, 0f, 8f, 0f, 8f, 0f, 100f, 8f, 0)
        }
        val mL = sL / sumW
        val mA = sA / sumW
        val mB = sB / sumW
        val vL = max(0f, sLL / sumW - mL * mL)
        val vA = max(0f, sAA / sumW - mA * mA)
        val vB = max(0f, sBB / sumW - mB * mB)

        val ls = FloatArray(n)
        var idx = 0
        for (i in 0 until img.size) {
            if (useImageAlpha && img.a[i] < alphaThreshold) continue
            val w = weights?.get(i) ?: 1f
            if (w <= 0.001f) continue
            rgbToLab(img.r[i], img.g[i], img.b[i], lab)
            if (idx < ls.size) ls[idx++] = lab[0]
        }
        val low = if (idx > 8) ImageOps.percentile(ls, 0.05f) else mL - 1.65f * sqrt(vL)
        val high = if (idx > 8) ImageOps.percentile(ls, 0.95f) else mL + 1.65f * sqrt(vL)

        return LabStats(
            meanL = mL,
            stdL = sqrt(vL),
            meanA = mA,
            stdA = sqrt(vA),
            meanB = mB,
            stdB = sqrt(vB),
            lowL = low,
            highL = high,
            meanChroma = sChroma / sumW,
            count = n,
        )
    }

    /**
     * Gray world illuminant of a region, expressed as linear RGB gains relative to the average.
     * Used to make an illustration pick up the white balance of the photograph it is embedded in.
     */
    fun grayWorld(img: RasterImage, weights: FloatArray? = null): FloatArray {
        var sumW = 0f
        var sr = 0f
        var sg = 0f
        var sb = 0f
        for (i in 0 until img.size) {
            val w = weights?.get(i) ?: 1f
            if (w <= 0.001f) continue
            sumW += w
            sr += w * srgbToLinear(img.r[i])
            sg += w * srgbToLinear(img.g[i])
            sb += w * srgbToLinear(img.b[i])
        }
        if (sumW <= 0f) return floatArrayOf(1f, 1f, 1f)
        val mr = sr / sumW
        val mg = sg / sumW
        val mb = sb / sumW
        val gray = (mr + mg + mb) / 3f
        if (mr <= 1e-5f || mg <= 1e-5f || mb <= 1e-5f) return floatArrayOf(1f, 1f, 1f)
        return floatArrayOf(gray / mr, gray / mg, gray / mb)
    }

    /** Average linear RGB of the brightest / darkest percentile, used for key and ambient light. */
    fun lightAndAmbientColors(img: RasterImage, weights: FloatArray? = null): Array<FloatArray> {
        val samples = ArrayList<Float>(4096)
        val values = ArrayList<Float>(4096)
        val step = max(1, img.size / 60000)
        var i = 0
        while (i < img.size) {
            val w = weights?.get(i) ?: 1f
            if (w > 0.05f) {
                values.add(perceptiveLuma(img.r[i], img.g[i], img.b[i]))
                samples.add(i.toFloat())
            }
            i += step
        }
        if (values.size < 32) {
            return arrayOf(floatArrayOf(1f, 1f, 1f), floatArrayOf(0.1f, 0.1f, 0.12f))
        }
        val arr = FloatArray(values.size)
        for (k in values.indices) arr[k] = values[k]
        val highCut = ImageOps.percentile(arr, 0.92f)
        val lowCut = ImageOps.percentile(arr, 0.08f)
        var sr = 0f
        var sg = 0f
        var sb = 0f
        var nHigh = 0
        var dr = 0f
        var dg = 0f
        var db = 0f
        var nLow = 0
        for (k in values.indices) {
            val idx = samples[k].toInt()
            if (values[k] >= highCut) {
                sr += img.r[idx]
                sg += img.g[idx]
                sb += img.b[idx]
                nHigh++
            } else if (values[k] <= lowCut) {
                dr += img.r[idx]
                dg += img.g[idx]
                db += img.b[idx]
                nLow++
            }
        }
        val key = if (nHigh > 0) floatArrayOf(sr / nHigh, sg / nHigh, sb / nHigh) else floatArrayOf(1f, 1f, 1f)
        val ambient = if (nLow > 0) floatArrayOf(dr / nLow, dg / nLow, db / nLow) else floatArrayOf(0.1f, 0.1f, 0.12f)
        return arrayOf(key, ambient)
    }

    /** Circular mean of the hue histogram, a compact description of "what colour is this photo". */
    fun meanHue(img: RasterImage): Float {
        var xs = 0f
        var ys = 0f
        var weightSum = 0f
        val step = max(1, img.size / 40000)
        var i = 0
        while (i < img.size) {
            val r = img.r[i]
            val g = img.g[i]
            val b = img.b[i]
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val delta = mx - mn
            if (delta > 0.05f) {
                val hue = when (mx) {
                    r -> 60f * (((g - b) / delta) % 6f)
                    g -> 60f * (((b - r) / delta) + 2f)
                    else -> 60f * (((r - g) / delta) + 4f)
                }
                val radians = Math.toRadians(hue.toDouble()).toFloat()
                val w = delta * mx
                xs += w * cos(radians)
                ys += w * sin(radians)
                weightSum += w
            }
            i += step
        }
        if (weightSum < 1e-4f) return 0f
        val deg = Math.toDegrees(atan2(ys.toDouble(), xs.toDouble()).toDouble()).toFloat()
        return if (deg < 0f) deg + 360f else deg
    }

    /** Saturation statistics (HSV S channel), used for chroma harmonisation strength. */
    fun saturationStats(img: RasterImage, weights: FloatArray? = null): FloatArray {
        var sum = 0f
        var sumSq = 0f
        var n = 0
        for (i in 0 until img.size) {
            val w = weights?.get(i) ?: 1f
            if (w <= 0.05f) continue
            val r = img.r[i]
            val g = img.g[i]
            val b = img.b[i]
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val s = if (mx <= 1e-4f) 0f else (mx - mn) / mx
            sum += s
            sumSq += s * s
            n++
        }
        if (n == 0) return floatArrayOf(0f, 0f)
        val mean = sum / n
        return floatArrayOf(mean, sqrt(max(0f, sumSq / n - mean * mean)))
    }

    /** Slightly non-linear chroma compression, keeps highlights from clipping when matching. */
    fun compressChroma(a: Float, b: Float, maxChroma: Float): Pair<Float, Float> {
        val c = sqrt(a * a + b * b)
        if (c <= maxChroma || c < 1e-4f) return a to b
        val scale = maxChroma / c + (1f - maxChroma / c) * 0.35f
        val s = scale.coerceIn(0.05f, 1f)
        return a * s to b * s
    }

    /**
     * Chroma of the shadow / midtone / highlight bands, measured in one pass.
     *
     * A photograph is rarely neutral across its tonal range: its shadows lean blue, its highlights
     * carry the key light colour. Matching the character band by band (instead of with one global
     * mean) is what removes the "different light source" feeling.
     */
    class ChromaBands(
        val meanA: FloatArray,
        val meanB: FloatArray,
        val weight: FloatArray,
    ) {
        fun blended(weights: FloatArray, fallbackA: Float, fallbackB: Float): Pair<Float, Float> {
            var sum = 0f
            var a = 0f
            var b = 0f
            for (band in 0..2) {
                val w = weights[band] * this.weight[band]
                sum += w
                a += w * meanA[band]
                b += w * meanB[band]
            }
            if (sum <= 1e-4f) return fallbackA to fallbackB
            return a / sum to b / sum
        }
    }

    /** Luminance band edges in L*: shadows below 35, highlights above 70. */
    fun bandWeights(L: Float): FloatArray {
        val shadow = 1f - ImageOps.smoothstep(28f, 46f, L)
        val highlight = ImageOps.smoothstep(62f, 82f, L)
        val mid = (1f - shadow - highlight).coerceAtLeast(0f)
        return floatArrayOf(shadow, mid, highlight)
    }

    fun bandChromaStats(
        img: RasterImage,
        weights: FloatArray? = null,
        alphaThreshold: Float = 0.5f,
        useImageAlpha: Boolean = false,
    ): ChromaBands {
        val sumA = FloatArray(3)
        val sumB = FloatArray(3)
        val sumW = FloatArray(3)
        val lab = FloatArray(3)
        for (i in 0 until img.size) {
            if (useImageAlpha && img.a[i] < alphaThreshold) continue
            val w = weights?.get(i) ?: 1f
            if (w <= 0.02f) continue
            rgbToLab(img.r[i], img.g[i], img.b[i], lab)
            val bands = bandWeights(lab[0])
            for (band in 0..2) {
                val bw = bands[band] * w
                if (bw <= 0f) continue
                sumA[band] += bw * lab[1]
                sumB[band] += bw * lab[2]
                sumW[band] += bw
            }
        }
        val meanA = FloatArray(3)
        val meanB = FloatArray(3)
        for (band in 0..2) {
            if (sumW[band] > 0.5f) {
                meanA[band] = sumA[band] / sumW[band]
                meanB[band] = sumB[band] / sumW[band]
            }
        }
        return ChromaBands(meanA, meanB, sumW)
    }
}
