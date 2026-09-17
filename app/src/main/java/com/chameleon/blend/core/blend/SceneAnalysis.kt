package com.chameleon.blend.core.blend

import com.chameleon.blend.core.color.ColorMath
import com.chameleon.blend.core.img.ImageOps
import com.chameleon.blend.core.img.RasterImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Everything the engine needs to know about the photograph: where the light comes from, how bright
 * and how noisy it is, which colour the key light carries and where the ground plane sits.
 *
 * Analysis is done once per background image and cached by the caller, so slider drags stay cheap.
 */
class SceneAnalysis(
    val width: Int,
    val height: Int,
    val illumination: FloatArray,
    val lightDirX: Float,
    val lightDirY: Float,
    val keyColor: FloatArray,
    val ambientColor: FloatArray,
    /** Chroma of the key light and of the ambient light, in Lab. */
    val keyChromaA: Float,
    val keyChromaB: Float,
    val ambientChromaA: Float,
    val ambientChromaB: Float,
    val meanLuma: Float,
    val stdLuma: Float,
    val noiseSigma: Float,
    val detailEnergy: Float,
    /** Detail measured per 1000 px of the long edge, so the value is comparable across sizes. */
    val detailEnergyNormalized: Float,
    val whiteBalance: FloatArray,
    val meanHue: Float,
    val saturationMean: Float,
    val saturationStd: Float,
    val groundY: Float,
    val groundColor: FloatArray,
    val busyLeft: Float,
    val busyRight: Float,
    val stats: ColorMath.LabStats,
    val time: SceneTime,
) {
    /**
     * Illumination sampled with bilinear filtering using normalised coordinates, so the same
     * analysis can be reused for a small interactive preview and a full resolution export.
     */
    fun illuminationAt(u: Float, v: Float): Float {
        if (width <= 0 || height <= 0) return 1f
        val fx = (u.coerceIn(0f, 1f) * (width - 1)).coerceIn(0f, (width - 1).toFloat())
        val fy = (v.coerceIn(0f, 1f) * (height - 1)).coerceIn(0f, (height - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val wx = fx - x0
        val wy = fy - y0
        val top = ImageOps.lerp(illumination[y0 * width + x0], illumination[y0 * width + x1], wx)
        val bottom = ImageOps.lerp(illumination[y1 * width + x0], illumination[y1 * width + x1], wx)
        return ImageOps.lerp(top, bottom, wy)
    }

    companion object {
        fun analyze(image: RasterImage): SceneAnalysis {
            val w = image.width
            val h = image.height
            val n = image.size

            val luma = FloatArray(n)
            for (i in 0 until n) {
                luma[i] = ColorMath.perceptiveLuma(image.r[i], image.g[i], image.b[i])
            }

            val meanLuma = ImageOps.mean(luma)
            var varSum = 0f
            for (v in luma) {
                val d = v - meanLuma
                varSum += d * d
            }
            val stdLuma = sqrt(varSum / n)

            // Low frequency luminance = incoming light map. Normalised so the average is 1.
            val illumSigma = max(4f, min(w, h) * 0.075f)
            val rawIllum = ImageOps.blur(luma, w, h, illumSigma)
            val illumMean = max(0.02f, ImageOps.mean(rawIllum))
            val illumination = FloatArray(n)
            for (i in 0 until n) {
                illumination[i] = (rawIllum[i] / illumMean).coerceIn(0.35f, 2.2f)
            }

            // Direction of the key light: uphill on the illumination map.
            var gx = 0f
            var gy = 0f
            val step = max(1, min(w, h) / 64)
            for (y in step until h - step step step) {
                for (x in step until w - step step step) {
                    val left = illumination[y * w + x - step]
                    val right = illumination[y * w + x + step]
                    val up = illumination[(y - step) * w + x]
                    val down = illumination[(y + step) * w + x]
                    gx += right - left
                    gy += down - up
                }
            }
            val gLen = sqrt(gx * gx + gy * gy)
            val lightDirX = if (gLen > 1e-5f) gx / gLen else 0.6f
            val lightDirY = if (gLen > 1e-5f) gy / gLen else -0.8f

            val keyAmbient = ColorMath.lightAndAmbientColors(image)
            val keyLab = FloatArray(3)
            ColorMath.rgbToLab(
                keyAmbient[0][0].coerceIn(0f, 1f),
                keyAmbient[0][1].coerceIn(0f, 1f),
                keyAmbient[0][2].coerceIn(0f, 1f),
                keyLab,
            )
            val ambientLab = FloatArray(3)
            ColorMath.rgbToLab(
                keyAmbient[1][0].coerceIn(0f, 1f),
                keyAmbient[1][1].coerceIn(0f, 1f),
                keyAmbient[1][2].coerceIn(0f, 1f),
                ambientLab,
            )
            val whiteBalance = ColorMath.grayWorld(image)
            val meanHue = ColorMath.meanHue(image)
            val sat = ColorMath.saturationStats(image)
            val noise = ImageOps.noiseSigma(luma, w, h)
            val detail = ImageOps.detailEnergy(luma, w, h)
            val stats = ColorMath.labStats(image)

            // Ground plane estimate: strongest vertical illumination change in the lower half.
            var groundY = 0.78f
            var bestScore = -1f
            val yStart = (h * 0.45f).toInt()
            val yEnd = (h * 0.95f).toInt()
            for (y in yStart + 2 until yEnd) {
                val above = rowMean(illumination, w, y - 2)
                val below = rowMean(illumination, w, y + 2)
                val score = abs(below - above) * (1f + (y - yStart).toFloat() / max(1, yEnd - yStart))
                if (score > bestScore) {
                    bestScore = score
                    groundY = y.toFloat() / h
                }
            }
            groundY = groundY.coerceIn(0.55f, 0.94f)

            val groundBandY = (groundY * h).toInt().coerceIn(0, h - 1)
            val groundColor = bandAverage(image, groundBandY, min(h - 1, groundBandY + (h * 0.06f).toInt()))

            val thirdW = max(1, w / 3)
            val busyLeft = ImageOps.detailEnergy(luma, w, h, thirdMask(w, h, 0, thirdW))
            val busyRight = ImageOps.detailEnergy(luma, w, h, thirdMask(w, h, w - thirdW, w))

            val keyLuma = ColorMath.perceptiveLuma(keyAmbient[0][0], keyAmbient[0][1], keyAmbient[0][2])
            val warm = keyAmbient[0][0] - keyAmbient[0][2]
            val time = when {
                meanLuma < 0.24f -> SceneTime.NIGHT
                warm > 0.08f && keyLuma > 0.45f -> SceneTime.GOLDEN
                meanLuma > 0.45f -> SceneTime.DAY
                else -> SceneTime.INDOOR
            }

            return SceneAnalysis(
                width = w,
                height = h,
                illumination = illumination,
                lightDirX = lightDirX,
                lightDirY = lightDirY,
                keyColor = keyAmbient[0],
                ambientColor = keyAmbient[1],
                keyChromaA = keyLab[1],
                keyChromaB = keyLab[2],
                ambientChromaA = ambientLab[1],
                ambientChromaB = ambientLab[2],
                meanLuma = meanLuma,
                stdLuma = stdLuma,
                noiseSigma = noise,
                detailEnergy = detail,
                detailEnergyNormalized = detail * (max(w, h) / 1000f),
                whiteBalance = whiteBalance,
                meanHue = meanHue,
                saturationMean = sat[0],
                saturationStd = sat[1],
                groundY = groundY,
                groundColor = groundColor,
                busyLeft = busyLeft,
                busyRight = busyRight,
                stats = stats,
                time = time,
            )
        }

        private fun rowMean(plane: FloatArray, width: Int, y: Int): Float {
            val row = y * width
            var sum = 0f
            var count = 0
            var x = 0
            while (x < width) {
                sum += plane[row + x]
                count++
                x += 2
            }
            return if (count == 0) 0f else sum / count
        }

        private fun bandAverage(image: RasterImage, y0: Int, y1: Int): FloatArray {
            var sr = 0f
            var sg = 0f
            var sb = 0f
            var count = 0
            val start = y0.coerceIn(0, image.height - 1)
            val end = y1.coerceIn(start, image.height - 1)
            for (y in start..end) {
                var x = image.width / 4
                while (x < image.width * 3 / 4) {
                    val i = y * image.width + x
                    sr += image.r[i]
                    sg += image.g[i]
                    sb += image.b[i]
                    count++
                    x += 3
                }
            }
            if (count == 0) return floatArrayOf(0.5f, 0.5f, 0.5f)
            return floatArrayOf(sr / count, sg / count, sb / count)
        }

        private fun thirdMask(width: Int, height: Int, x0: Int, x1: Int): FloatArray {
            val mask = FloatArray(width * height)
            for (y in 0 until height) {
                val row = y * width
                for (x in x0.coerceAtLeast(0) until min(x1, width)) {
                    mask[row + x] = 1f
                }
            }
            return mask
        }
    }
}
