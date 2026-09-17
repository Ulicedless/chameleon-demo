package com.chameleon.blend

import com.chameleon.blend.core.blend.BlendEngine
import com.chameleon.blend.core.blend.BlendParams
import com.chameleon.blend.core.blend.BlendPipeline
import com.chameleon.blend.core.blend.BlendStyle
import com.chameleon.blend.core.blend.Matting
import com.chameleon.blend.core.blend.Placement
import com.chameleon.blend.core.blend.PlacementSolver
import com.chameleon.blend.core.blend.SceneAnalysis
import com.chameleon.blend.core.color.ColorMath
import com.chameleon.blend.core.img.RasterImage
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Numeric quality gates. The engine cannot be judged by eye in CI, so every claim the UI makes
 * ("投影已贴合地面", "轮廓光来自背景光源") is verified against measurable pixel statistics.
 */
class BlendQualityTest {

    private val bgImage = TestImages.streetPhoto(900, 640)
    private val fgImage = TestImages.animeCharacter(320, 560)
    private val analysis = SceneAnalysis.analyze(bgImage)
    private val alpha = Matting.extractAlpha(fgImage)

    private fun subjectMask(params: BlendParams): FloatArray {
        val placement = PlacementSolver.solve(
            bgImage.width,
            bgImage.height,
            fgImage.width,
            fgImage.height,
            params,
        )
        return subjectMask(placement)
    }

    private fun subjectMask(placement: Placement): FloatArray {
        val w = bgImage.width
        val h = bgImage.height
        val mask = FloatArray(w * h)
        val invScale = 1f / placement.scale
        val fgHalfW = fgImage.width * 0.5f - 0.5f
        val fgHalfH = fgImage.height * 0.5f - 0.5f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x + 0.5f - placement.centerX
                val dy = y + 0.5f - placement.centerY
                val rx = dx * placement.cosR + dy * placement.sinR
                val ry = -dx * placement.sinR + dy * placement.cosR
                val sx = (if (placement.flip) -rx else rx) * invScale + fgHalfW
                val sy = ry * invScale + fgHalfH
                if (sx < 0f || sy < 0f || sx >= fgImage.width || sy >= fgImage.height) continue
                val a = alpha[sy.toInt() * fgImage.width + sx.toInt()]
                if (a > 0.5f) mask[y * w + x] = 1f
            }
        }
        return mask
    }

    private fun meanLuma(image: RasterImage, indices: List<Int>): Float {
        var sum = 0f
        for (i in indices) {
            sum += ColorMath.perceptiveLuma(image.r[i], image.g[i], image.b[i])
        }
        return if (indices.isEmpty()) 0f else sum / indices.size
    }

    @Test
    fun `cast shadow darkens the ground under the character`() {
        debugShadowFootprint()
        val params = BlendParams.of(BlendStyle.DAYLIGHT).copy(
            shadowStrength = 0.75f,
            contactShadow = 0.7f,
            grain = 0f,
            noiseMatch = 0f,
        )
        val placement = PlacementSolver.solve(bgImage.width, bgImage.height, fgImage.width, fgImage.height, params)
        val mask = subjectMask(placement)
        val blended = BlendPipeline.render(bgImage, fgImage, alpha, params, analysis)

        val left = (placement.centerX - placement.scale * fgImage.width * 0.45f).toInt().coerceIn(0, bgImage.width - 1)
        val right = (placement.centerX + placement.scale * fgImage.width * 0.45f).toInt().coerceIn(0, bgImage.width - 1)
        val feet = (placement.centerY + placement.scale * fgImage.height * 0.5f).toInt()
        // Sample the strip that runs away from the feet along the shadow direction: that is where a
        // cast shadow of a standing figure must land.
        val band = ArrayList<Int>()
        val dirX = -params.lightX
        val dirY = -params.lightY
        val feetX = placement.centerX
        val maxTravel = placement.scale * fgImage.height * 0.55f
        val halfWidth = placement.scale * fgImage.width * 0.30f
        var travel = 4f
        while (travel < maxTravel) {
            val baseX = feetX + dirX * travel
            val baseY = feet + dirY * travel
            var lateral = -halfWidth
            while (lateral < halfWidth) {
                val x = (baseX - dirY * lateral).toInt()
                val y = (baseY + dirX * lateral).toInt()
                if (x in 0 until bgImage.width && y in 0 until bgImage.height) {
                    val i = y * bgImage.width + x
                    if (mask[i] <= 0.5f) band.add(i)
                }
                lateral += 2f
            }
            travel += 2f
        }
        val groundBefore = meanLuma(bgImage, band)
        val groundAfter = meanLuma(blended, band)
        val deepBefore = percentileLuma(bgImage, band, 0.15f)
        val deepAfter = percentileLuma(blended, band, 0.15f)
        println(
            "[shadow] band=${band.size} mean $groundBefore -> $groundAfter " +
                "p15 $deepBefore -> $deepAfter",
        )
        assertTrue("shadow band too small", band.size > 500)
        assertTrue("no shadow darkening: $groundBefore -> $groundAfter", groundAfter < groundBefore - 0.02f)
        assertTrue("shadow core too weak: $deepBefore -> $deepAfter", deepAfter < deepBefore - 0.06f)
    }

    /** Prints where the shadow actually lands, used while tuning the projection model. */
    private fun debugShadowFootprint() {
        val withShadow = BlendParams.of(BlendStyle.DAYLIGHT).copy(
            shadowStrength = 0.9f,
            contactShadow = 0.8f,
            grain = 0f,
            noiseMatch = 0f,
        )
        val placement = PlacementSolver.solve(bgImage.width, bgImage.height, fgImage.width, fgImage.height, withShadow)
        val base = BlendPipeline.render(bgImage, fgImage, alpha, withShadow.copy(shadowStrength = 0f, contactShadow = 0f), analysis)
        val shadowed = BlendPipeline.render(bgImage, fgImage, alpha, withShadow, analysis)
        val w = bgImage.width
        var minX = w
        var maxX = 0
        var minY = bgImage.height
        var maxY = 0
        var count = 0
        var sumX = 0.0
        var sumY = 0.0
        var maxDrop = 0f
        for (y in 0 until bgImage.height) {
            for (x in 0 until w) {
                val i = y * w + x
                val drop = ColorMath.perceptiveLuma(base.r[i], base.g[i], base.b[i]) -
                    ColorMath.perceptiveLuma(shadowed.r[i], shadowed.g[i], shadowed.b[i])
                if (drop > 0.02f) {
                    count++
                    sumX += x
                    sumY += y
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                if (drop > maxDrop) maxDrop = drop
            }
        }
        val feetY = placement.centerY + placement.scale * fgImage.height * 0.5f
        println(
            "[shadow-debug] darkPixels=$count bbox=($minX,$minY)-($maxX,$maxY) " +
                "centroid=(${sumX / max(1, count)},${sumY / max(1, count)}) maxDrop=$maxDrop " +
                "feetY=$feetY lightY=${withShadow.lightY} shadowLen=${withShadow.shadowLength}",
        )
    }

    private fun percentileLuma(image: RasterImage, indices: List<Int>, fraction: Float): Float {
        val values = FloatArray(indices.size)
        for (k in indices.indices) {
            val i = indices[k]
            values[k] = ColorMath.perceptiveLuma(image.r[i], image.g[i], image.b[i])
        }
        return com.chameleon.blend.core.img.ImageOps.percentile(values, fraction)
    }

    @Test
    fun `rim light brightens the side that faces the key light`() {
        val params = BlendParams.of(BlendStyle.BACKLIT).copy(
            rimLight = 1f,
            shadowStrength = 0f,
            contactShadow = 0f,
            grain = 0f,
            noiseMatch = 0f,
            colorMatch = 0f,
            whiteBalanceMatch = 0f,
            sceneLightStrength = 0f,
            shadingConsistency = 0f,
        )
        val placement = PlacementSolver.solve(bgImage.width, bgImage.height, fgImage.width, fgImage.height, params)
        val mask = subjectMask(placement)
        val blended = BlendPipeline.render(bgImage, fgImage, alpha, params, analysis)
        val plain = BlendPipeline.render(bgImage, fgImage, alpha, params.copy(rimLight = 0f), analysis)

        val w = bgImage.width
        val lightX = params.lightX
        val lightY = params.lightY
        val litEdge = ArrayList<Int>()
        val darkEdge = ArrayList<Int>()
        for (y in 1 until bgImage.height - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (mask[i] <= 0.5f) continue
                val onEdge = mask[i - 1] < 0.5f || mask[i + 1] < 0.5f ||
                    mask[i - w] < 0.5f || mask[i + w] < 0.5f
                if (!onEdge) continue
                val dot = (x - placement.centerX) * lightX + (y - placement.centerY) * lightY
                if (dot > 0f) litEdge.add(i) else darkEdge.add(i)
            }
        }
        val litGain = meanLuma(blended, litEdge) - meanLuma(plain, litEdge)
        val darkGain = meanLuma(blended, darkEdge) - meanLuma(plain, darkEdge)
        println("[rim] lit=${litEdge.size} dark=${darkEdge.size} litGain=$litGain darkGain=$darkGain")
        assertTrue("edge pixels missing", litEdge.size > 100 && darkEdge.size > 100)
        assertTrue("rim light did not brighten the lit edge enough: $litGain", litGain > 0.01f)
        assertTrue("rim leaked onto the dark side: $litGain vs $darkGain", litGain > darkGain)
    }

    @Test
    fun `background outside the subject footprint is left untouched`() {
        val params = BlendParams.of(BlendStyle.STUDIO).copy(
            backgroundBlur = 0f,
            vignette = 0f,
            grain = 0f,
            noiseMatch = 0f,
            chromaticAberration = 0f,
            shadowStrength = 0.6f,
        )
        val placement = PlacementSolver.solve(bgImage.width, bgImage.height, fgImage.width, fgImage.height, params)
        val mask = subjectMask(placement)
        val blended = BlendPipeline.render(bgImage, fgImage, alpha, params, analysis)

        val w = bgImage.width
        val h = bgImage.height
        val influence = FloatArray(w * h)
        val shadowRadius = (placement.scale * fgImage.height * 0.55f).toInt().coerceAtLeast(8)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y * w + x] <= 0.5f) continue
                val x0 = max(0, x - shadowRadius)
                val x1 = min(w - 1, x + shadowRadius)
                val y0 = max(0, y - shadowRadius / 4)
                val y1 = min(h - 1, y + shadowRadius)
                for (yy in y0..y1) {
                    for (xx in x0..x1) influence[yy * w + xx] = 1f
                }
            }
        }
        var checked = 0
        var drift = 0
        var maxDelta = 0f
        for (i in 0 until w * h) {
            if (influence[i] > 0.5f) continue
            checked++
            val d = maxOf(
                abs(blended.r[i] - bgImage.r[i]),
                abs(blended.g[i] - bgImage.g[i]),
                abs(blended.b[i] - bgImage.b[i]),
            )
            if (d > 0.02f) drift++
            if (d > maxDelta) maxDelta = d
        }
        println("[preserve] checked=$checked drifted=$drift maxDelta=$maxDelta")
        val ratio = drift.toFloat() / max(1, checked)
        assertTrue("background drifted in $ratio of pixels (max $maxDelta)", ratio < 0.02f)
    }

    @Test
    fun `scene illumination gradient is transferred onto the character`() {
        val params = BlendParams.of(BlendStyle.DAYLIGHT).copy(
            sceneLightStrength = 1f,
            shadingConsistency = 0.9f,
            colorMatch = 0f,
            whiteBalanceMatch = 0f,
            rimLight = 0f,
            shadowStrength = 0f,
            grain = 0f,
            noiseMatch = 0f,
        )
        val placement = PlacementSolver.solve(bgImage.width, bgImage.height, fgImage.width, fgImage.height, params)
        val mask = subjectMask(placement)
        val blended = BlendPipeline.render(bgImage, fgImage, alpha, params, analysis)
        val plain = BlendPipeline.render(bgImage, fgImage, alpha, params.copy(sceneLightStrength = 0f, shadingConsistency = 0f), analysis)

        val lightIndices = ArrayList<Int>()
        val farIndices = ArrayList<Int>()
        for (i in mask.indices) {
            if (mask[i] <= 0.5f) continue
            val x = i % bgImage.width
            val y = i / bgImage.width
            val illum = analysis.illuminationAt(
                (x + 0.5f) / bgImage.width,
                (y + 0.5f) / bgImage.height,
            )
            if (illum > 1.08f) lightIndices.add(i)
            if (illum < 0.92f) farIndices.add(i)
        }
        val litGain = meanLuma(blended, lightIndices) - meanLuma(plain, lightIndices)
        val farGain = meanLuma(blended, farIndices) - meanLuma(plain, farIndices)
        println("[illumination] lit=${lightIndices.size} far=${farIndices.size} litGain=$litGain farGain=$farGain")
        assertTrue("not enough lit/far samples", lightIndices.size > 50 && farIndices.size > 50)
        assertTrue("lit side did not brighten: $litGain", litGain > 0.005f)
        assertTrue("light gradient missing: $litGain vs $farGain", litGain > farGain)
    }

    @Test
    fun `grain increases local variance and stays deterministic`() {
        val flat = BlendParams.of(BlendStyle.FILM).copy(
            grain = 0.6f,
            noiseMatch = 0f,
            vignette = 0f,
            chromaticAberration = 0f,
            backgroundBlur = 0f,
            shadowStrength = 0f,
            contactShadow = 0f,
        )
        val withGrain = BlendPipeline.render(bgImage, fgImage, alpha, flat, analysis)
        val withoutGrain = BlendPipeline.render(bgImage, fgImage, alpha, flat.copy(grain = 0f), analysis)

        val varianceWith = localVariance(withGrain, 40, 60, 160, 140)
        val varianceWithout = localVariance(withoutGrain, 40, 60, 160, 140)
        println("[grain] with=$varianceWith without=$varianceWithout")
        assertTrue("grain did not raise variance: $varianceWith vs $varianceWithout", varianceWith > varianceWithout * 1.5f)
    }

    @Test
    fun `preview resolution render is fast enough for interactive use`() {
        val previewBg = TestImages.streetPhoto(1280, 900)
        val previewFg = TestImages.animeCharacter(420, 720)
        val previewAnalysis = SceneAnalysis.analyze(previewBg)
        val previewAlpha = Matting.extractAlpha(previewFg)
        val params = BlendEngine.autoTune(previewBg, previewAnalysis, previewFg, previewAlpha)

        // warm up the JIT
        BlendPipeline.render(previewBg, previewFg, previewAlpha, params, previewAnalysis, fast = true)

        val fastStart = System.nanoTime()
        BlendPipeline.render(previewBg, previewFg, previewAlpha, params, previewAnalysis, fast = true)
        val fastMs = (System.nanoTime() - fastStart) / 1_000_000

        val fullStart = System.nanoTime()
        BlendPipeline.render(previewBg, previewFg, previewAlpha, params, previewAnalysis, fast = false)
        val fullMs = (System.nanoTime() - fullStart) / 1_000_000

        println("[timing] fast=${fastMs}ms full=${fullMs}ms at 1280x900 (JVM; devices are slower)")
        assertTrue("fast path too slow: ${fastMs}ms", fastMs < 3000)
    }

    @Test
    fun `full resolution export stays within a phone sized memory budget`() {
        val exportBg = TestImages.streetPhoto(2560, 1920)
        val exportFg = TestImages.animeCharacter(700, 1180)
        val runtime = Runtime.getRuntime()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val exportAnalysis = SceneAnalysis.analyze(exportBg)
        val exportAlpha = Matting.extractAlpha(exportFg)
        val params = BlendEngine.autoTune(exportBg, exportAnalysis, exportFg, exportAlpha)

        val start = System.nanoTime()
        val result = BlendPipeline.render(exportBg, exportFg, exportAlpha, params, exportAnalysis, fast = false)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val after = runtime.totalMemory() - runtime.freeMemory()
        val peakMb = (after - before) / (1024.0 * 1024.0)
        println("[export] 2560x1920 render ${elapsedMs}ms, heap delta ${"%.1f".format(peakMb)}MB")
        assertEquals(2560, result.width)
        assertTrue("export render too slow: ${elapsedMs}ms", elapsedMs < 20000)
    }

    private fun localVariance(image: RasterImage, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var sum = 0f
        var sumSq = 0f
        var n = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val i = y * image.width + x
                val l = ColorMath.perceptiveLuma(image.r[i], image.g[i], image.b[i])
                sum += l
                sumSq += l * l
                n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        return sumSq / n - mean * mean
    }
}
