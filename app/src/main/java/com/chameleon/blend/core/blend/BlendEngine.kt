package com.chameleon.blend.core.blend

import com.chameleon.blend.core.color.ColorMath
import com.chameleon.blend.core.img.ImageOps
import com.chameleon.blend.core.img.RasterImage
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * Glue between analysis, automatic parameter tuning and the render pipeline.
 */
object BlendEngine {

    /** A short, human readable description of the scene, shown above the sliders. */
    data class SceneSummary(
        val timeLabel: String,
        val lightLabel: String,
        val noiseLabel: String,
        val detailLabel: String,
        val suggestion: String,
    )

    fun summarize(analysis: SceneAnalysis): SceneSummary {
        val time = when (analysis.time) {
            SceneTime.DAY -> "白天 / 硬光"
            SceneTime.GOLDEN -> "黄昏 / 暖光"
            SceneTime.NIGHT -> "夜晚 / 低照度"
            SceneTime.INDOOR -> "室内 / 柔光"
        }
        val azimuth = lightAzimuthOf(analysis)
        val light = when {
            azimuth >= 45f && azimuth < 135f -> "左上侧光"
            azimuth >= 135f && azimuth < 225f -> "左侧光"
            azimuth >= 225f && azimuth < 315f -> "右下侧光"
            else -> "右侧光"
        }
        val noise = when {
            analysis.noiseSigma > 0.02f -> "噪点明显"
            analysis.noiseSigma > 0.009f -> "轻微噪点"
            else -> "干净画面"
        }
        val detail = when {
            analysis.detailEnergyNormalized > 0.06f -> "细节丰富"
            analysis.detailEnergyNormalized > 0.03f -> "细节适中"
            else -> "画面柔和"
        }
        val suggestion = when (analysis.time) {
            SceneTime.DAY -> "建议使用「日光」，投影会更硬更清晰"
            SceneTime.GOLDEN -> "建议使用「黄昏」，加强轮廓光与暖色调"
            SceneTime.NIGHT -> "建议使用「夜景」，提高噪点匹配与补光"
            SceneTime.INDOOR -> "建议使用「自然」或「影棚」，柔化投影"
        }
        return SceneSummary(time, light, noise, detail, suggestion)
    }

    fun lightAzimuthOf(analysis: SceneAnalysis): Float {
        var deg = Math.toDegrees(
            atan2(-analysis.lightDirY.toDouble(), analysis.lightDirX.toDouble()),
        ).toFloat()
        if (deg < 0f) deg += 360f
        return deg
    }

    /**
     * Derives a full parameter set from the photograph and the cut-out: style, light direction,
     * shadow length and the strengths that actually matter for this particular scene.
     */
    fun autoTune(
        background: RasterImage,
        analysis: SceneAnalysis,
        foreground: RasterImage,
        foregroundAlpha: FloatArray,
        style: BlendStyle? = null,
        intensity: Float = 1f,
        autoStyleByScene: Boolean = true,
    ): BlendParams {
        val resolvedStyle = style ?: if (autoStyleByScene) {
            when (analysis.time) {
                SceneTime.DAY -> BlendStyle.DAYLIGHT
                SceneTime.GOLDEN -> BlendStyle.GOLDEN
                SceneTime.NIGHT -> BlendStyle.NIGHT
                SceneTime.INDOOR -> BlendStyle.NATURAL
            }
        } else {
            BlendStyle.NATURAL
        }
        val base = BlendParams.of(resolvedStyle)

        var opaque = 0
        var minX = foreground.width
        var maxX = 0
        var minY = foreground.height
        var maxY = 0
        for (y in 0 until foreground.height) {
            for (x in 0 until foreground.width) {
                val i = y * foreground.width + x
                if (foregroundAlpha[i] <= 0.5f) continue
                opaque++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (opaque == 0) {
            minX = 0
            minY = 0
            maxX = foreground.width - 1
            maxY = foreground.height - 1
        }
        val bboxW = max(1, maxX - minX + 1)
        val bboxH = max(1, maxY - minY + 1)
        val coverage = (opaque.toFloat() / (bboxW * bboxH)).coerceIn(0f, 1f)
        val heightFraction = PlacementSolver.suggestedHeightFraction(bboxW, bboxH, coverage)

        val croppedFore = cropTo(foreground, foregroundAlpha, minX, minY, maxX, maxY)
        val croppedAlpha = cropAlpha(foregroundAlpha, foreground.width, minX, minY, bboxW, bboxH)
        val fgStats = ColorMath.labStats(croppedFore, croppedAlpha)
        val fgChroma = fgStats.meanChroma
        val bgChroma = analysis.stats.meanChroma

        val (offsetX, offsetY) = PlacementSolver.suggestedOffsets(
            groundY = analysis.groundY,
            heightFraction = heightFraction,
            busyLeft = analysis.busyLeft,
            busyRight = analysis.busyRight,
        )

        val azimuth = lightAzimuthOf(analysis)
        val elevation = when (analysis.time) {
            SceneTime.GOLDEN -> 19f
            SceneTime.NIGHT -> 26f
            SceneTime.DAY -> 42f
            SceneTime.INDOOR -> 52f
        }
        val contrastInScene = (analysis.stdLuma / 0.22f).coerceIn(0.6f, 1.3f)
        val noisy = (analysis.noiseSigma / 0.02f).coerceIn(0f, 1f)
        val chromaGap = (abs(fgChroma - bgChroma) / 26f).coerceIn(0f, 1f)
        val busy = (analysis.detailEnergyNormalized / 0.08f).coerceIn(0f, 1f)

        val tuned = base.copy(
            heightFraction = heightFraction,
            offsetX = offsetX,
            offsetY = offsetY,
            lightAzimuthDeg = azimuth,
            lightElevationDeg = elevation,
            sceneLightStrength = (base.sceneLightStrength * contrastInScene).coerceIn(0.3f, 1.05f),
            shadingConsistency = (base.shadingConsistency * (0.7f + 0.6f * contrastInScene)).coerceIn(0f, 0.9f),
            shadowStrength = (base.shadowStrength * (1.15f - 0.2f * noisy)).coerceIn(0.2f, 0.85f),
            colorMatch = (base.colorMatch * (0.9f + 0.25f * chromaGap)).coerceIn(0.35f, 0.9f),
            noiseMatch = (base.noiseMatch * (0.5f + 0.9f * noisy)).coerceIn(0f, 1f),
            grain = (base.grain + 0.1f * noisy).coerceIn(0f, 0.5f),
            backgroundBlur = if (busy < 0.35f) (0.1f + 0.1f * (1f - busy)) else 0f,
            sharpnessMatch = base.sharpnessMatch,
        )
        return tuned.scaledEffectStrength(intensity)
    }

    /**
     * Master intensity from the settings screen: scales only the "effect" strengths, never the
     * geometry, so a user who wants a subtler blend keeps the composition they tuned by hand.
     */
    fun BlendParams.scaledEffectStrength(intensity: Float): BlendParams {
        val k = intensity.coerceIn(0.2f, 1.6f)
        return copy(
            colorMatch = (colorMatch * k).coerceIn(0f, 1f),
            contrastMatch = (contrastMatch * k).coerceIn(0f, 1f),
            chromaMatch = (chromaMatch * k).coerceIn(0f, 1f),
            whiteBalanceMatch = (whiteBalanceMatch * k).coerceIn(0f, 1f),
            toneMatch = (toneMatch * k).coerceIn(0f, 1f),
            highlightMatch = (highlightMatch * k).coerceIn(0f, 1f),
            sceneLightStrength = (sceneLightStrength * k).coerceIn(0f, 1.2f),
            shadingConsistency = (shadingConsistency * k).coerceIn(0f, 1f),
            shadowStrength = (shadowStrength * k).coerceIn(0f, 1f),
            contactShadow = (contactShadow * k).coerceIn(0f, 1f),
            ambientOcclusion = (ambientOcclusion * k).coerceIn(0f, 1f),
            rimLight = (rimLight * k).coerceIn(0f, 1.2f),
            bounceLight = (bounceLight * k).coerceIn(0f, 1f),
            noiseMatch = (noiseMatch * k).coerceIn(0f, 1f),
            grain = (grain * k).coerceIn(0f, 0.6f),
            edgeDecontamination = (edgeDecontamination * k).coerceIn(0f, 1f),
        )
    }

    private fun cropTo(
        image: RasterImage,
        alpha: FloatArray,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): RasterImage {
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val a = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val si = (y0 + y) * image.width + (x0 + x)
                val di = y * w + x
                r[di] = image.r[si]
                g[di] = image.g[si]
                b[di] = image.b[si]
                a[di] = alpha[si]
            }
        }
        return RasterImage(w, h, r, g, b, a)
    }

    private fun cropAlpha(alpha: FloatArray, srcWidth: Int, x0: Int, y0: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[y * w + x] = alpha[(y0 + y) * srcWidth + (x0 + x)]
            }
        }
        return out
    }

    /** Parameters that paste the cut-out with a clean edge but no harmonisation, for A/B compare. */
    fun rawPasteParams(params: BlendParams): BlendParams = params.copy(
        sceneLightStrength = 0f,
        shadingConsistency = 0f,
        shadowStrength = 0f,
        contactShadow = 0f,
        ambientOcclusion = 0f,
        rimLight = 0f,
        bounceLight = 0f,
        colorMatch = 0f,
        contrastMatch = 0f,
        chromaMatch = 0f,
        whiteBalanceMatch = 0f,
        toneMatch = 0f,
        highlightMatch = 0f,
        exposure = 0f,
        contrast = 0f,
        saturation = 0f,
        temperature = 0f,
        tint = 0f,
        noiseMatch = 0f,
        sharpnessMatch = 0f,
        grain = 0f,
        backgroundBlur = 0f,
        vignette = 0f,
        chromaticAberration = 0f,
    )

    /** Naive centre placement used before automatic tuning has run. */
    fun defaultParams(fgWidth: Int, fgHeight: Int): BlendParams {
        val aspect = fgHeight.toFloat() / max(1, fgWidth)
        return BlendParams(
            heightFraction = PlacementSolver.suggestedHeightFraction(fgWidth, fgHeight, 0.6f),
            offsetY = if (aspect > 1.4f) 0.14f else 0.06f,
        )
    }

    /** Small helper for the UI: how much of the frame the subject occupies. */
    fun coverage(width: Int, height: Int, alpha: FloatArray): Float {
        var sum = 0f
        for (v in alpha) sum += v
        return sum / (width * height).toFloat()
    }

    fun detailOf(image: RasterImage): Float {
        val luma = FloatArray(image.size)
        for (i in 0 until image.size) {
            luma[i] = ColorMath.perceptiveLuma(image.r[i], image.g[i], image.b[i])
        }
        return ImageOps.detailEnergy(luma, image.width, image.height)
    }
}
