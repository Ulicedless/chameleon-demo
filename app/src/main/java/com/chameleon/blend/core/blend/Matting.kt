package com.chameleon.blend.core.blend

import com.chameleon.blend.core.color.ColorMath
import com.chameleon.blend.core.img.ImageOps
import com.chameleon.blend.core.img.RasterImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class MattingOptions(
    val enabled: Boolean = true,
    /** 0 = only colours very close to the backdrop are removed, 1 = very permissive. */
    val tolerance: Float = 0.45f,
    val feather: Float = 0.5f,
    /** Keep pixels that match the backdrop but are not connected to the border. */
    val protectEnclosedRegions: Boolean = true,
)

/**
 * Foreground extraction for illustrations.
 *
 * Characters are usually exported with an alpha channel, in which case the work is trivial. When
 * the user hands us a JPEG, we estimate the backdrop colour from the border (k-means over the border
 * pixels), build a soft alpha from the CIELAB distance, keep only the region connected to the border
 * transparent, and finally snap the matte onto the artwork's own edges with a guided filter.
 */
object Matting {

    fun transparencyRatio(image: RasterImage): Float {
        var count = 0
        for (i in 0 until image.size) {
            if (image.a[i] < 0.85f) count++
        }
        return count.toFloat() / image.size
    }

    fun hasUsableAlpha(image: RasterImage): Boolean = transparencyRatio(image) > 0.02f

    fun extractAlpha(image: RasterImage, options: MattingOptions = MattingOptions()): FloatArray {
        val existing = transparencyRatio(image)
        if (existing > 0.02f) {
            // Already a cut-out: just tighten the edge a little.
            return tighten(image.a, image, image.width, image.height)
        }
        if (!options.enabled) {
            return FloatArray(image.size) { 1f }
        }
        return autoCutout(image, options)
    }

    private fun tighten(alpha: FloatArray, guide: RasterImage, w: Int, h: Int): FloatArray {
        val refined = ImageOps.guidedFilter(guide.r, alpha, w, h, 2, 1e-4f)
        val out = FloatArray(alpha.size)
        for (i in alpha.indices) {
            val v = (0.55f * alpha[i] + 0.45f * refined[i]).coerceIn(0f, 1f)
            out[i] = ImageOps.smoothstep(0.08f, 0.55f, v)
        }
        return out
    }

    fun autoCutout(image: RasterImage, options: MattingOptions): FloatArray {
        val w = image.width
        val h = image.height
        val n = image.size

        val borderColors = ArrayList<FloatArray>(max(64, (w + h) * 2))
        val band = max(1, min(w, h) / 100)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val isBorder = x < band || y < band || x >= w - band || y >= h - band
                if (!isBorder) continue
                val i = y * w + x
                borderColors.add(floatArrayOf(image.r[i], image.g[i], image.b[i]))
            }
        }
        if (borderColors.isEmpty()) return FloatArray(n) { 1f }

        val clusters = kMeans(borderColors, k = 3, iterations = 10)
        val clusterLab = Array(clusters.size) { idx ->
            val c = clusters[idx]
            val lab = FloatArray(3)
            ColorMath.rgbToLab(c[0], c[1], c[2], lab)
            lab
        }

        val distance = FloatArray(n)
        val borderDistance = FloatArray(borderColors.size)
        val lab = FloatArray(3)
        var borderIdx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                ColorMath.rgbToLab(image.r[i], image.g[i], image.b[i], lab)
                var best = Float.MAX_VALUE
                for (c in clusterLab) {
                    val dl = lab[0] - c[0]
                    val da = lab[1] - c[1]
                    val db = lab[2] - c[2]
                    val d = dl * dl + da * da + db * db
                    if (d < best) best = d
                }
                val dist = sqrt(best)
                distance[i] = dist
                if (x < band || y < band || x >= w - band || y >= h - band) {
                    if (borderIdx < borderDistance.size) borderDistance[borderIdx++] = dist
                }
            }
        }

        val borderSpread = if (borderIdx > 16) {
            ImageOps.percentile(borderDistance.copyOf(borderIdx), 0.9f)
        } else {
            6f
        }
        val tolerance = options.tolerance.coerceIn(0f, 1f)
        // Otsu splits the histogram of "distance from the backdrop colour" into backdrop and subject,
        // which adapts to gradients and soft shadows far better than a fixed percentile.
        val otsu = otsuThreshold(distance)
        val base = if (otsu > 1f) otsu else borderSpread * 1.6f + 3f
        val t0 = (base * (0.42f + 0.28f * (1f - tolerance)) + borderSpread * 0.35f)
            .coerceIn(max(1.8f, borderSpread * 1.02f), 30f)
        val t1 = (base * (0.95f + 0.35f * (1f - tolerance)) + borderSpread * 0.6f)
            .coerceIn(t0 + 1f, 70f)

        var alpha = FloatArray(n)
        for (i in 0 until n) {
            // Far from every backdrop colour => foreground.
            alpha[i] = ImageOps.smoothstep(t0, t1, distance[i])
        }

        if (options.protectEnclosedRegions) {
            alpha = keepOnlyConnectedToBorder(alpha, w, h, 0.45f)
        }

        // Snap the matte to the artwork edges with two guided-filter passes: a wide one to escape the
        // colour spread left by JPEG, then a tight one to lock onto the drawn outline.
        val guide = FloatArray(n)
        for (i in 0 until n) {
            guide[i] = ColorMath.perceptiveLuma(image.r[i], image.g[i], image.b[i])
        }
        val wideRadius = max(2, min(w, h) / 90)
        val tightRadius = max(1, min(w, h) / 320)
        var refined = ImageOps.guidedFilter(guide, alpha, w, h, wideRadius, 4e-3f)
        refined = ImageOps.guidedFilter(guide, refined, w, h, tightRadius, 6e-4f)
        val featherSigma = 0.6f + options.feather * 1.8f
        if (featherSigma > 0.4f) {
            refined = ImageOps.blur(refined, w, h, featherSigma)
        }
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = ImageOps.smoothstep(0.25f, 0.75f, refined[i].coerceIn(0f, 1f))
        }
        return out
    }

    /**
     * Otsu's threshold over the distance histogram (0..maxDistance, 1 unit per bucket).
     * Returns 0 when the histogram is not informative.
     */
    private fun otsuThreshold(distance: FloatArray): Float {
        var maxDistance = 0f
        for (d in distance) {
            if (d > maxDistance) maxDistance = d
        }
        if (maxDistance < 4f) return 0f
        val buckets = 64
        val scale = (buckets - 1) / maxDistance
        val histogram = IntArray(buckets)
        for (d in distance) {
            val bucket = (d * scale).toInt().coerceIn(0, buckets - 1)
            histogram[bucket]++
        }
        val total = distance.size
        var sumAll = 0.0
        for (bucket in 0 until buckets) sumAll += bucket.toDouble() * histogram[bucket]

        var sumBackground = 0.0
        var weightBackground = 0
        var bestVariance = -1.0
        var bestBucket = 0
        for (bucket in 0 until buckets) {
            weightBackground += histogram[bucket]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += bucket.toDouble() * histogram[bucket]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sumAll - sumBackground) / weightForeground
            val between = weightBackground.toDouble() * weightForeground *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (between > bestVariance) {
                bestVariance = between
                bestBucket = bucket
            }
        }
        if (bestVariance <= 0.0) return 0f
        return (bestBucket + 0.5f) / scale
    }

    /**
     * Marks backdrop-coloured pixels that touch the image border as transparent; matching pixels in
     * the middle of the art (a white shirt on a white backdrop) stay opaque.
     */
    private fun keepOnlyConnectedToBorder(
        alpha: FloatArray,
        width: Int,
        height: Int,
        transparentThreshold: Float,
    ): FloatArray {
        val n = alpha.size
        val visited = BooleanArray(n)
        val stack = IntArray(n)
        var top = 0
        fun push(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= width || y >= height) return
            val i = y * width + x
            if (visited[i]) return
            if (alpha[i] > transparentThreshold) return
            visited[i] = true
            stack[top++] = i
        }
        for (x in 0 until width) {
            push(x, 0)
            push(x, height - 1)
        }
        for (y in 0 until height) {
            push(0, y)
            push(width - 1, y)
        }
        while (top > 0) {
            val i = stack[--top]
            val x = i % width
            val y = i / width
            push(x - 1, y)
            push(x + 1, y)
            push(x, y - 1)
            push(x, y + 1)
        }
        val out = alpha.copyOf()
        for (i in 0 until n) {
            if (!visited[i]) out[i] = 1f
        }
        return out
    }

    private fun kMeans(samples: List<FloatArray>, k: Int, iterations: Int): Array<FloatArray> {
        val clusters = Array(min(k, samples.size)) { FloatArray(3) }
        val count = clusters.size
        for (c in 0 until count) {
            val pick = samples[(samples.size * (c + 1)) / (count + 1)]
            clusters[c][0] = pick[0]
            clusters[c][1] = pick[1]
            clusters[c][2] = pick[2]
        }
        val sums = Array(count) { FloatArray(4) }
        repeat(iterations) {
            for (s in sums) {
                s[0] = 0f
                s[1] = 0f
                s[2] = 0f
                s[3] = 0f
            }
            for (sample in samples) {
                var bestIdx = 0
                var bestDist = Float.MAX_VALUE
                for (c in 0 until count) {
                    val dr = sample[0] - clusters[c][0]
                    val dg = sample[1] - clusters[c][1]
                    val db = sample[2] - clusters[c][2]
                    val d = dr * dr + dg * dg + db * db
                    if (d < bestDist) {
                        bestDist = d
                        bestIdx = c
                    }
                }
                sums[bestIdx][0] += sample[0]
                sums[bestIdx][1] += sample[1]
                sums[bestIdx][2] += sample[2]
                sums[bestIdx][3] += 1f
            }
            for (c in 0 until count) {
                if (sums[c][3] > 0.5f) {
                    clusters[c][0] = sums[c][0] / sums[c][3]
                    clusters[c][1] = sums[c][1] / sums[c][3]
                    clusters[c][2] = sums[c][2] / sums[c][3]
                }
            }
        }
        return clusters
    }
}
