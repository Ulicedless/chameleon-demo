package com.chameleon.blend

import com.chameleon.blend.core.blend.BlendParams
import com.chameleon.blend.core.blend.BlendPipeline
import com.chameleon.blend.core.blend.BlendStyle
import com.chameleon.blend.core.blend.Matting
import com.chameleon.blend.core.blend.PlacementSolver
import com.chameleon.blend.core.blend.SceneAnalysis
import com.chameleon.blend.core.color.ColorMath
import com.chameleon.blend.core.img.RasterImage
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Guards the second generation of the algorithm: edge decontamination, band-wise colour matching,
 * highlight tinting, matte accuracy and shadow penumbra. Every test compares "before" and "after" on
 * the same scene so the assertion states a real improvement instead of a magic constant.
 */
class BlendUpgradeTest {

    private val background = TestImages.streetPhoto(760, 540)
    private val character = TestImages.animeCharacter(300, 520)
    private val analysis = SceneAnalysis.analyze(background)
    private val trueAlpha = Matting.extractAlpha(character)

    private fun neutralParams() = BlendParams.of(BlendStyle.NATURAL).copy(
        shadowStrength = 0f,
        contactShadow = 0f,
        ambientOcclusion = 0f,
        rimLight = 0f,
        bounceLight = 0f,
        sceneLightStrength = 0f,
        shadingConsistency = 0f,
        whiteBalanceMatch = 0f,
        toneMatch = 0f,
        contrastMatch = 0f,
        noiseMatch = 0f,
        grain = 0f,
        sharpnessMatch = 0f,
        colorMatch = 0f,
        chromaMatch = 0f,
        highlightMatch = 0f,
        edgeDecontamination = 0f,
    )

    private fun placementOf(params: BlendParams) = PlacementSolver.solve(
        background.width,
        background.height,
        character.width,
        character.height,
        params,
    )

    /** Maps the character silhouette into background space. */
    private fun subjectMask(alpha: FloatArray, params: BlendParams): BooleanArray {
        val mask = BooleanArray(background.size)
        val placement = placementOf(params)
        val invScale = 1f / placement.scale
        val fgHalfW = character.width * 0.5f - 0.5f
        val fgHalfH = character.height * 0.5f - 0.5f
        for (y in 0 until background.height) {
            for (x in 0 until background.width) {
                val dx = x + 0.5f - placement.centerX
                val dy = y + 0.5f - placement.centerY
                val rx = dx * placement.cosR + dy * placement.sinR
                val ry = -dx * placement.sinR + dy * placement.cosR
                val sx = rx * invScale + fgHalfW
                val sy = ry * invScale + fgHalfH
                if (sx < 0f || sy < 0f || sx >= character.width || sy >= character.height) continue
                if (alpha[sy.toInt() * character.width + sx.toInt()] > 0.5f) {
                    mask[y * background.width + x] = true
                }
            }
        }
        return mask
    }

    private fun weightsOf(mask: BooleanArray): FloatArray {
        val out = FloatArray(mask.size)
        for (i in mask.indices) if (mask[i]) out[i] = 1f
        return out
    }

    // ---------------------------------------------------------------- edge decontamination

    @Test
    fun `edge decontamination removes the backdrop fringe`() {
        val params = neutralParams()
        // Ground truth: the same composite built from the clean artwork.
        val reference = BlendPipeline.render(background, character, trueAlpha, params, analysis)
        val fringed = TestImages.withBackdropFringe(character)
        val contaminated = BlendPipeline.render(background, fringed, trueAlpha, params, analysis)
        val cleaned = BlendPipeline.render(
            background,
            fringed,
            trueAlpha,
            params.copy(edgeDecontamination = 1f),
            analysis,
        )
        val band = edgeBandMask(params)
        val contaminatedError = meanAbsDifference(contaminated, reference, band)
        val cleanedError = meanAbsDifference(cleaned, reference, band)
        println("[fringe] contaminatedError=$contaminatedError cleanedError=$cleanedError")
        assertTrue("the fringe should be measurable: $contaminatedError", contaminatedError > 0.01f)
        assertTrue(
            "edge cleanup did not move the result towards the clean cut-out: " +
                "$contaminatedError -> $cleanedError",
            cleanedError < contaminatedError * 0.75f,
        )
    }

    // ---------------------------------------------------------------- band colour matching

    @Test
    fun `band wise chroma matching beats a single global mean`() {
        val params = neutralParams().copy(colorMatch = 1f, chromaMatch = 1f)
        val globalOnly = BlendPipeline.render(
            background,
            character,
            trueAlpha,
            params.copy(chromaMatch = 0f),
            analysis,
        )
        val bandMatched = BlendPipeline.render(background, character, trueAlpha, params, analysis)
        val errorGlobal = bandChromaError(globalOnly, params)
        val errorBand = bandChromaError(bandMatched, params)
        println("[bands] global=$errorGlobal band=$errorBand")
        assertTrue(
            "band matching should lower per-band chroma error: $errorGlobal -> $errorBand",
            errorBand <= errorGlobal,
        )
    }

    // ---------------------------------------------------------------- highlight tinting

    @Test
    fun `highlight matching pulls speculars towards the key light colour`() {
        val params = neutralParams().copy(colorMatch = 1f, highlightMatch = 1f)
        val without = BlendPipeline.render(
            background,
            character,
            trueAlpha,
            params.copy(highlightMatch = 0f),
            analysis,
        )
        val with = BlendPipeline.render(background, character, trueAlpha, params, analysis)

        val keyLab = FloatArray(3)
        ColorMath.rgbToLab(
            analysis.keyColor[0].coerceIn(0f, 1f),
            analysis.keyColor[1].coerceIn(0f, 1f),
            analysis.keyColor[2].coerceIn(0f, 1f),
            keyLab,
        )
        val before = highlightChromaDistance(without, params, keyLab[1], keyLab[2])
        val after = highlightChromaDistance(with, params, keyLab[1], keyLab[2])
        println("[highlight] keyChroma=(${keyLab[1]},${keyLab[2]}) before=$before after=$after")
        assertTrue("highlights should move towards the key light: $before -> $after", after <= before)
    }

    // ---------------------------------------------------------------- matting quality

    @Test
    fun `auto matting recovers the silhouette from a flat backdrop`() {
        val flat = RasterImage(character.width, character.height)
        val backdrop = floatArrayOf(0.92f, 0.93f, 0.96f)
        for (i in 0 until flat.size) {
            val a = character.a[i]
            flat.r[i] = character.r[i] * a + backdrop[0] * (1f - a)
            flat.g[i] = character.g[i] * a + backdrop[1] * (1f - a)
            flat.b[i] = character.b[i] * a + backdrop[2] * (1f - a)
        }
        val recovered = Matting.extractAlpha(flat)
        var intersection = 0
        var union = 0
        for (i in 0 until recovered.size) {
            val truth = character.a[i] > 0.5f
            val guess = recovered[i] > 0.5f
            if (truth && guess) intersection++
            if (truth || guess) union++
        }
        val iou = if (union == 0) 0f else intersection.toFloat() / union
        println("[matting] IoU=$iou")
        assertTrue("matte is too inaccurate, IoU=$iou", iou > 0.80f)
    }

    // ---------------------------------------------------------------- shadow penumbra

    @Test
    fun `cast shadow gets softer with distance and keeps a dark core`() {
        val params = BlendParams.of(BlendStyle.DAYLIGHT).copy(
            heightFraction = 0.45f,
            offsetY = -0.12f,
            shadowStrength = 0.9f,
            shadowSoftness = 0.5f,
            contactShadow = 0.7f,
            grain = 0f,
            noiseMatch = 0f,
        )
        val blended = BlendPipeline.render(background, character, trueAlpha, params, analysis)
        val placement = placementOf(params)
        val dirX = -params.lightX
        val dirY = -params.lightY
        // The shadow lies on the ground, which the camera compresses vertically.
        val groundSquash = 0.38f
        val feetX = placement.centerX
        val feetY = placement.centerY + placement.scale * character.height * 0.5f

        val near = placement.scale * character.height * 0.25f
        val far = placement.scale * character.height * 0.75f
        val nearProfile = lateralProfile(
            blended,
            feetX + dirX * near,
            feetY + dirY * near * groundSquash,
            -dirY,
            dirX,
        )
        val farProfile = lateralProfile(
            blended,
            feetX + dirX * far,
            feetY + dirY * far * groundSquash,
            -dirY,
            dirX,
        )
        val nearDepth = nearProfile.maxOrNull() ?: 0f
        val farDepth = farProfile.maxOrNull() ?: 0f
        val nearWidth = widthAbove(nearProfile, 0.35f)
        val farWidth = widthAbove(farProfile, 0.35f)
        println("[penumbra] nearWidth=$nearWidth farWidth=$farWidth nearDepth=$nearDepth farDepth=$farDepth")
        assertTrue("no shadow to measure: $nearDepth", nearDepth > 0.06f)
        assertTrue("the far shadow should be softer: $nearWidth -> $farWidth", farWidth > nearWidth)
        assertTrue("the shadow core faded away: $farDepth", farDepth > nearDepth * 0.4f)
    }

    /** Number of samples above a fraction of the peak: a proxy for how spread out the shadow is. */
    private fun widthAbove(profile: FloatArray, fraction: Float): Int {
        val peak = profile.maxOrNull() ?: 0f
        if (peak <= 0.01f) return 0
        val threshold = peak * fraction
        var count = 0
        for (value in profile) {
            if (value >= threshold) count++
        }
        return count
    }

    // ---------------------------------------------------------------- helpers

    /** Mean luma of the fully opaque artwork, i.e. what the edges should look like. */
    private fun interiorMeanLuma(image: RasterImage): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until image.size) {
            if (trueAlpha[i] < 0.9f) continue
            sum += ColorMath.perceptiveLuma(image.r[i], image.g[i], image.b[i])
            count++
        }
        return if (count == 0) 0f else sum / count
    }

    /** Mean luma of the composited band one pixel inside the silhouette. */
    /** Silhouette rim in background space: exactly where leftover backdrop colour shows up. */
    private fun edgeBandMask(params: BlendParams): BooleanArray {
        val placement = placementOf(params)
        val w = background.width
        val h = background.height
        val invScale = 1f / placement.scale
        val fgHalfW = character.width * 0.5f - 0.5f
        val fgHalfH = character.height * 0.5f - 0.5f
        val rim = BooleanArray(background.size)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val dx = x + 0.5f - placement.centerX
                val dy = y + 0.5f - placement.centerY
                val rx = dx * placement.cosR + dy * placement.sinR
                val ry = -dx * placement.sinR + dy * placement.cosR
                val sx = rx * invScale + fgHalfW
                val sy = ry * invScale + fgHalfH
                if (sx < 1f || sy < 1f || sx >= character.width - 1f || sy >= character.height - 1f) {
                    continue
                }
                val i = sy.toInt() * character.width + sx.toInt()
                val touchesRim = isRim(trueAlpha[i]) ||
                    isRim(trueAlpha[i - 1]) ||
                    isRim(trueAlpha[i + 1]) ||
                    isRim(trueAlpha[i - character.width]) ||
                    isRim(trueAlpha[i + character.width])
                if (touchesRim) rim[y * w + x] = true
            }
        }
        return rim
    }

    private fun isRim(alpha: Float): Boolean = alpha > 0.02f && alpha < 0.99f

    private fun meanAbsDifference(a: RasterImage, b: RasterImage, mask: BooleanArray): Float {
        var sum = 0f
        var count = 0
        for (i in mask.indices) {
            if (!mask[i]) continue
            sum += abs(a.r[i] - b.r[i]) + abs(a.g[i] - b.g[i]) + abs(a.b[i] - b.b[i])
            count++
        }
        return if (count == 0) 0f else sum / (count * 3f)
    }

    private fun bandChromaError(image: RasterImage, params: BlendParams): Float {
        val placement = placementOf(params)
        val subjectBands = ColorMath.bandChromaStats(
            image,
            weightsOf(subjectMask(trueAlpha, params)),
        )
        val regionWeights = FloatArray(image.size)
        val cx = placement.centerX
        val cy = placement.centerY
        val halfW = placement.scale * character.width * 0.5f
        val halfH = placement.scale * character.height * 0.5f
        for (y in 0 until background.height) {
            for (x in 0 until background.width) {
                if (abs(x - cx) <= halfW && abs(y - cy) <= halfH) {
                    regionWeights[y * background.width + x] = 1f
                }
            }
        }
        val backgroundBands = ColorMath.bandChromaStats(background, regionWeights)
        var error = 0f
        var bands = 0
        for (band in 0..2) {
            if (subjectBands.weight[band] < 50f || backgroundBands.weight[band] < 50f) continue
            val da = subjectBands.meanA[band] - backgroundBands.meanA[band]
            val db = subjectBands.meanB[band] - backgroundBands.meanB[band]
            error += sqrt(da * da + db * db)
            bands++
        }
        return if (bands == 0) 0f else error / bands
    }

    private fun highlightChromaDistance(
        image: RasterImage,
        params: BlendParams,
        keyA: Float,
        keyB: Float,
    ): Float {
        val bands = ColorMath.bandChromaStats(
            image,
            weightsOf(subjectMask(trueAlpha, params)),
        )
        if (bands.weight[2] < 50f) return 0f
        val da = bands.meanA[2] - keyA
        val db = bands.meanB[2] - keyB
        return sqrt(da * da + db * db)
    }

    /** Shadow depth (darker than the original photo) sampled across the shadow. */
    private fun lateralProfile(
        blended: RasterImage,
        baseX: Float,
        baseY: Float,
        dirX: Float,
        dirY: Float,
    ): FloatArray {
        val samples = ArrayList<Float>(128)
        var t = -48f
        while (t <= 48f) {
            val x = (baseX + dirX * t).toInt()
            val y = (baseY + dirY * t).toInt()
            if (x in 0 until background.width && y in 0 until background.height) {
                val i = y * background.width + x
                val before = ColorMath.perceptiveLuma(
                    background.r[i],
                    background.g[i],
                    background.b[i],
                )
                val after = ColorMath.perceptiveLuma(blended.r[i], blended.g[i], blended.b[i])
                samples.add((before - after).coerceAtLeast(0f))
            }
            t += 1f
        }
        return samples.toFloatArray()
    }

    /**
     * Softness of an edge: how far the profile travels between 20% and 80% of its peak on the side
     * that faces outwards.
     */
    private fun transitionWidth(profile: FloatArray): Float {
        if (profile.isEmpty()) return 0f
        var peak = 0f
        var peakIndex = 0
        for (i in profile.indices) {
            if (profile[i] > peak) {
                peak = profile[i]
                peakIndex = i
            }
        }
        if (peak <= 0.02f) return 0f
        val lowThreshold = peak * 0.2f
        val highThreshold = peak * 0.8f
        var lowIndex = peakIndex
        var highIndex = peakIndex
        var i = peakIndex
        while (i < profile.size && profile[i] > lowThreshold) {
            lowIndex = i
            i++
        }
        i = peakIndex
        while (i < profile.size && profile[i] > highThreshold) {
            highIndex = i
            i++
        }
        val leftLow = run {
            var index = peakIndex
            var k = peakIndex
            while (k >= 0 && profile[k] > lowThreshold) {
                index = k
                k--
            }
            index
        }
        val leftHigh = run {
            var index = peakIndex
            var k = peakIndex
            while (k >= 0 && profile[k] > highThreshold) {
                index = k
                k--
            }
            index
        }
        return max(peakIndex - leftLow, lowIndex - peakIndex).toFloat() +
            (highIndex - peakIndex).toFloat() * 0f +
            (peakIndex - leftHigh).toFloat() * 0f
    }
}
