package com.chameleon.blend.core.blend

import com.chameleon.blend.core.color.ColorMath
import com.chameleon.blend.core.img.ImageOps
import com.chameleon.blend.core.img.ParallelRows
import com.chameleon.blend.core.img.RasterImage
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * The blending engine.
 *
 * Pipeline order matters and mirrors how a compositor would work:
 *  1. place the character (affine transform, supersampled for clean edges)
 *  2. match sharpness so it does not look pasted at a different resolution
 *  3. transfer colour statistics from the photograph onto the character (CIELAB mean/σ + tones)
 *  4. light the character with the photograph's own illumination map, rim light and bounce light
 *  5. blur the backdrop for depth of field when asked
 *  6. project a contact shadow / ambient occlusion onto the backdrop
 *  7. composite with a feathered, decontaminated edge
 *  8. unify grain, chromatic aberration, vignette and the final grade
 */
object BlendPipeline {

    fun render(
        background: RasterImage,
        foreground: RasterImage,
        foregroundAlpha: FloatArray,
        params: BlendParams,
        analysis: SceneAnalysis,
        fast: Boolean = false,
        onStage: ((String) -> Unit)? = null,
    ): RasterImage {
        val w = background.width
        val h = background.height
        val n = w * h

        onStage?.invoke("布置角色位置")

        // ---------------------------------------------------------------- background prep
        // Linear copies are only materialised when the background is blurred for depth of field;
        // otherwise the conversion happens inline while compositing, which keeps peak memory low.
        val dofSigma = min(w, h) * 0.02f * params.backgroundBlur
        var bgIsLinear = false
        var bgR = background.r
        var bgG = background.g
        var bgB = background.b
        if (dofSigma > 0.5f) {
            onStage?.invoke("虚化背景景深")
            val linR = FloatArray(n)
            val linG = FloatArray(n)
            val linB = FloatArray(n)
            ParallelRows.forEach(h) { y ->
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    linR[i] = ColorMath.srgbToLinearFast(background.r[i])
                    linG[i] = ColorMath.srgbToLinearFast(background.g[i])
                    linB[i] = ColorMath.srgbToLinearFast(background.b[i])
                }
            }
            bgR = ImageOps.blur(linR, w, h, dofSigma)
            bgG = ImageOps.blur(linG, w, h, dofSigma)
            bgB = ImageOps.blur(linB, w, h, dofSigma)
            bgIsLinear = true
        }

        // ---------------------------------------------------------------- place the subject
        val placement = PlacementSolver.solve(w, h, foreground.width, foreground.height, params)
        val supersample = if (fast) 1 else 2
        // Radii expressed in pixels are scaled so that a small preview and a full size export look
        // the same; every other distance in the engine is already relative to the image.
        val resolutionScale = max(w, h) / 1000f
        val layer = rasterize(foreground, foregroundAlpha, w, h, placement, supersample)
        if (layer.coverage <= 1e-4f) {
            return background.copy()
        }

        // ---------------------------------------------------------------- edge cleanup
        if (params.edgeDecontamination > 0.01f) {
            onStage?.invoke("净化边缘颜色")
            decontaminateEdges(layer, params, w, h)
        }

        // ---------------------------------------------------------------- sharpness matching
        if (params.sharpnessMatch > 0.02f) {
            onStage?.invoke("统一清晰度")
            matchSharpness(layer, background, analysis, params, resolutionScale)
        }

        // ---------------------------------------------------------------- colour harmony
        onStage?.invoke("匹配背景色彩")
        colorTransfer(layer, background, analysis, params, w, h)

        // ---------------------------------------------------------------- scene lighting
        onStage?.invoke("融合场景光影")
        val lighting = applySceneLighting(
            layer,
            analysis,
            params,
            background,
            w,
            h,
            fast,
            resolutionScale,
        )

        // ---------------------------------------------------------------- cast shadow
        onStage?.invoke("生成投影与环境光遮蔽")
        val shadow = buildShadow(layer, analysis, params, w, h, fast)

        // ---------------------------------------------------------------- shadow + composite
        val shadowResponse = shadowResponse(analysis)
        val ambientFraction = shadowResponse[0]
        val tintR = shadowResponse[1]
        val tintG = shadowResponse[2]
        val tintB = shadowResponse[3]
        onStage?.invoke("合成边缘")
        val softAlpha = softEdge(layer, params, w, h, fast, resolutionScale)
        run {
            val lr = bgR
            val lg = bgG
            val lb = bgB
            val linearAlready = bgIsLinear
            val shadowStrength = params.shadowStrength
            // The subject colour planes are no longer needed in sRGB, so the composite overwrites
            // them in place: at 2560 px this saves three full float planes of peak memory.
            val outR = lighting.linR
            val outG = lighting.linG
            val outB = lighting.linB
            ParallelRows.forEach(h) { y ->
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    // A shadow removes the direct light and keeps the ambient part (tinted by the
                    // scene's own ambient colour), which is what makes it read as light.
                    val groundR = if (linearAlready) lr[i] else ColorMath.srgbToLinearFast(lr[i])
                    val groundG = if (linearAlready) lg[i] else ColorMath.srgbToLinearFast(lg[i])
                    val groundB = if (linearAlready) lb[i] else ColorMath.srgbToLinearFast(lb[i])
                    val s = shadow[i] * shadowStrength
                    val keepR: Float
                    val keepG: Float
                    val keepB: Float
                    if (s <= 0.001f) {
                        keepR = 1f
                        keepG = 1f
                        keepB = 1f
                    } else {
                        keepR = (1f - s * (1f - ambientFraction * tintR)).coerceAtLeast(0.12f)
                        keepG = (1f - s * (1f - ambientFraction * tintG)).coerceAtLeast(0.12f)
                        keepB = (1f - s * (1f - ambientFraction * tintB)).coerceAtLeast(0.12f)
                    }
                    val bgr = groundR * keepR
                    val bgg = groundG * keepG
                    val bgb = groundB * keepB
                    val a = softAlpha[i]
                    if (a <= 0.0005f) {
                        outR[i] = ColorMath.linearToSrgbFast(bgr)
                        outG[i] = ColorMath.linearToSrgbFast(bgg)
                        outB[i] = ColorMath.linearToSrgbFast(bgb)
                    } else if (a >= 0.9995f) {
                        outR[i] = ColorMath.linearToSrgbFast(outR[i])
                        outG[i] = ColorMath.linearToSrgbFast(outG[i])
                        outB[i] = ColorMath.linearToSrgbFast(outB[i])
                    } else {
                        val inv = 1f - a
                        outR[i] = ColorMath.linearToSrgbFast(outR[i] * a + bgr * inv)
                        outG[i] = ColorMath.linearToSrgbFast(outG[i] * a + bgg * inv)
                        outB[i] = ColorMath.linearToSrgbFast(outB[i] * a + bgb * inv)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- integration pass
        onStage?.invoke("统一颗粒与色调")
        val result = RasterImage(w, h, lighting.linR, lighting.linG, lighting.linB)
        applyIntegrationPass(result, softAlpha, analysis, params, fast)
        return result
    }

    // ------------------------------------------------------------------ rasterisation

    private class Layer(
        val width: Int,
        val height: Int,
        val r: FloatArray,
        val g: FloatArray,
        val b: FloatArray,
        val a: FloatArray,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val coverage: Float
            get() {
                var sum = 0f
                for (v in a) sum += v
                return sum / a.size
            }

        val bboxWidth: Float get() = (right - left).toFloat()
        val bboxHeight: Float get() = (bottom - top).toFloat()
    }

    private class Lighting(val linR: FloatArray, val linG: FloatArray, val linB: FloatArray)

    private fun rasterize(
        fg: RasterImage,
        fgAlpha: FloatArray,
        w: Int,
        h: Int,
        placement: Placement,
        supersample: Int,
    ): Layer {
        val rOut = FloatArray(w * h)
        val gOut = FloatArray(w * h)
        val bOut = FloatArray(w * h)
        val aOut = FloatArray(w * h)

        val halfW = fg.width * placement.scale * 0.5f
        val halfH = fg.height * placement.scale * 0.5f
        val cosR = placement.cosR
        val sinR = placement.sinR
        val extentX = abs(halfW * cosR) + abs(halfH * sinR)
        val extentY = abs(halfW * sinR) + abs(halfH * cosR)

        val left = floor(placement.centerX - extentX - 1f).toInt().coerceIn(0, w - 1)
        val right = (placement.centerX + extentX + 1f).toInt().coerceIn(0, w - 1)
        val top = floor(placement.centerY - extentY - 1f).toInt().coerceIn(0, h - 1)
        val bottom = (placement.centerY + extentY + 1f).toInt().coerceIn(0, h - 1)

        val invScale = 1f / placement.scale
        val fgHalfW = fg.width * 0.5f - 0.5f
        val fgHalfH = fg.height * 0.5f - 0.5f
        val step = 1f / supersample
        val offsetBase = -0.5f + step * 0.5f
        val weight = 1f / (supersample * supersample)

        ParallelRows.forEach(h) { y ->
            if (y < top || y > bottom) return@forEach
            // Per-row scratch space: rows run on different threads.
            val samples = FloatArray(4)
            for (x in left..right) {
                var accR = 0f
                var accG = 0f
                var accB = 0f
                var accA = 0f
                for (sy in 0 until supersample) {
                    for (sx in 0 until supersample) {
                        val dx = x + 0.5f + offsetBase + sx * step - placement.centerX
                        val dy = y + 0.5f + offsetBase + sy * step - placement.centerY
                        val rx = dx * cosR + dy * sinR
                        val ry = -dx * sinR + dy * cosR
                        val srcX = (if (placement.flip) -rx else rx) * invScale + fgHalfW
                        val srcY = ry * invScale + fgHalfH
                        sampleForeground(fg, fgAlpha, srcX, srcY, samples)
                        val av = samples[3]
                        if (av <= 0.001f) continue
                        accR += samples[0] * av
                        accG += samples[1] * av
                        accB += samples[2] * av
                        accA += av
                    }
                }
                if (accA <= 0.001f) continue
                val alpha = (accA / (supersample * supersample)).coerceIn(0f, 1f)
                val i = y * w + x
                rOut[i] = accR / accA
                gOut[i] = accG / accA
                bOut[i] = accB / accA
                aOut[i] = alpha
            }
        }

        return Layer(w, h, rOut, gOut, bOut, aOut, left, top, right, bottom)
    }

    private fun sampleForeground(
        fg: RasterImage,
        fgAlpha: FloatArray,
        x: Float,
        y: Float,
        out: FloatArray,
    ) {
        if (x < -1f || y < -1f || x > fg.width.toFloat() || y > fg.height.toFloat()) {
            out[0] = 0f
            out[1] = 0f
            out[2] = 0f
            out[3] = 0f
            return
        }
        val fx = x.coerceIn(0f, (fg.width - 1).toFloat())
        val fy = y.coerceIn(0f, (fg.height - 1).toFloat())
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val x1 = min(x0 + 1, fg.width - 1)
        val y1 = min(y0 + 1, fg.height - 1)
        val wx = fx - x0
        val wy = fy - y0
        val i00 = y0 * fg.width + x0
        val i10 = y0 * fg.width + x1
        val i01 = y1 * fg.width + x0
        val i11 = y1 * fg.width + x1

        out[0] = bilerp(fg.r[i00], fg.r[i10], fg.r[i01], fg.r[i11], wx, wy)
        out[1] = bilerp(fg.g[i00], fg.g[i10], fg.g[i01], fg.g[i11], wx, wy)
        out[2] = bilerp(fg.b[i00], fg.b[i10], fg.b[i01], fg.b[i11], wx, wy)
        out[3] = bilerp(fgAlpha[i00], fgAlpha[i10], fgAlpha[i01], fgAlpha[i11], wx, wy)
    }

    private fun bilerp(v00: Float, v10: Float, v01: Float, v11: Float, wx: Float, wy: Float): Float {
        val top = v00 + (v10 - v00) * wx
        val bottom = v01 + (v11 - v01) * wx
        return top + (bottom - top) * wy
    }

    /**
     * Removes the backdrop colour that a cut-out still carries on its semi-transparent border (the
     * classic white or green fringe). Colours are extrapolated inwards from the fully opaque pixels
     * with a few relaxation passes, then blended into the band according to how transparent it is.
     */
    private fun decontaminateEdges(layer: Layer, params: BlendParams, w: Int, h: Int) {
        val strength = params.edgeDecontamination.coerceIn(0f, 1f)
        if (strength <= 0.01f) return
        val n = w * h
        val bandIndex = IntArray(n) { -1 }
        var count = 0
        for (i in 0 until n) {
            val a = layer.a[i]
            if (a > 0.02f && a < 0.995f) {
                bandIndex[i] = count
                count++
            }
        }
        if (count == 0) return
        val pixels = IntArray(count)
        var cursor = 0
        for (i in 0 until n) {
            val idx = bandIndex[i]
            if (idx >= 0) {
                pixels[idx] = i
                cursor++
            }
        }

        val fillR = FloatArray(count)
        val fillG = FloatArray(count)
        val fillB = FloatArray(count)
        val estimated = BooleanArray(count)
        var iteration = 0
        var progressed = true
        while (iteration < 20 && progressed) {
            progressed = false
            for (k in 0 until count) {
                val i = pixels[k]
                val x = i % w
                val y = i / w
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumW = 0f
                var dir = 0
                while (dir < 8) {
                    val nx = x + NEIGHBOR_X[dir]
                    val ny = y + NEIGHBOR_Y[dir]
                    if (nx in 0 until w && ny in 0 until h) {
                        val j = ny * w + nx
                        val nb = bandIndex[j]
                        if (nb < 0) {
                            if (layer.a[j] >= 0.995f) {
                                sumR += layer.r[j]
                                sumG += layer.g[j]
                                sumB += layer.b[j]
                                sumW += 1f
                            }
                        } else if (estimated[nb]) {
                            sumR += fillR[nb]
                            sumG += fillG[nb]
                            sumB += fillB[nb]
                            sumW += 1f
                        }
                    }
                    dir++
                }
                if (sumW > 0.5f) {
                    fillR[k] = sumR / sumW
                    fillG[k] = sumG / sumW
                    fillB[k] = sumB / sumW
                    if (!estimated[k]) {
                        estimated[k] = true
                        progressed = true
                    }
                }
            }
            iteration++
        }

        for (k in 0 until count) {
            if (!estimated[k]) continue
            val i = pixels[k]
            val a = layer.a[i]
            // Half-transparent pixels carry the most backdrop colour.
            val weight = (strength * (1f - a) * 1.55f).coerceIn(0f, 1f)
            if (weight <= 0.01f) continue
            layer.r[i] = ImageOps.lerp(layer.r[i], fillR[k], weight)
            layer.g[i] = ImageOps.lerp(layer.g[i], fillG[k], weight)
            layer.b[i] = ImageOps.lerp(layer.b[i], fillB[k], weight)
        }
    }

    // ------------------------------------------------------------------ sharpness

    private fun matchSharpness(
        layer: Layer,
        background: RasterImage,
        analysis: SceneAnalysis,
        params: BlendParams,
        resolutionScale: Float,
    ) {
        val w = layer.width
        val h = layer.height
        val luma = FloatArray(layer.a.size)
        for (i in layer.a.indices) {
            if (layer.a[i] <= 0.02f) continue
            luma[i] = ColorMath.perceptiveLuma(layer.r[i], layer.g[i], layer.b[i])
        }
        val layerDetail = ImageOps.detailEnergy(luma, w, h, layer.a)
        // Compare against the photograph at the resolution we are actually rendering.
        val bgDetail = if (background.width == w && background.height == h) {
            max(1e-4f, analysis.detailEnergy)
        } else {
            val bgLuma = FloatArray(w * h)
            for (i in 0 until w * h) {
                bgLuma[i] = ColorMath.perceptiveLuma(background.r[i], background.g[i], background.b[i])
            }
            max(1e-4f, ImageOps.detailEnergy(bgLuma, w, h))
        }
        if (layerDetail <= 1e-5f) return
        val ratio = layerDetail / bgDetail
        val strength = params.sharpnessMatch.coerceIn(0f, 1f)
        if (ratio > 1.25f) {
            // Illustration is crisper than the photograph: soften it slightly.
            val sigma = ((ratio - 1f).coerceAtMost(1.4f) * 1.2f) * strength
            if (sigma > 0.35f) {
                val br = ImageOps.blur(layer.r, w, h, sigma)
                val bg = ImageOps.blur(layer.g, w, h, sigma)
                val bb = ImageOps.blur(layer.b, w, h, sigma)
                for (i in layer.a.indices) {
                    if (layer.a[i] <= 0.02f) continue
                    layer.r[i] = br[i]
                    layer.g[i] = bg[i]
                    layer.b[i] = bb[i]
                }
            }
        } else if (ratio < 0.8f) {
            val amount = (((0.8f / ratio) - 1f).coerceAtMost(1.5f)) * strength
            val ur = ImageOps.unsharp(layer.r, w, h, amount, 1.2f)
            val ug = ImageOps.unsharp(layer.g, w, h, amount, 1.2f)
            val ub = ImageOps.unsharp(layer.b, w, h, amount, 1.2f)
            for (i in layer.a.indices) {
                if (layer.a[i] <= 0.02f) continue
                layer.r[i] = ur[i]
                layer.g[i] = ug[i]
                layer.b[i] = ub[i]
            }
        }
    }

    // ------------------------------------------------------------------ colour transfer

    private fun colorTransfer(
        layer: Layer,
        background: RasterImage,
        analysis: SceneAnalysis,
        params: BlendParams,
        w: Int,
        h: Int,
    ) {
        val strength = params.colorMatch.coerceIn(0f, 1f)
        if (strength <= 0.001f) return

        val layerImage = RasterImage(w, h, layer.r, layer.g, layer.b, layer.a)
        val fgStats = ColorMath.labStats(layerImage, weights = layer.a)
        if (fgStats.count < 32) return

        // Local statistics around the placement, blending towards global stats for small subjects.
        val regionSigma = max(3f, min(w, h) * 0.025f)
        val region = ImageOps.blur(layer.a, w, h, regionSigma)
        val regionCoverage = ImageOps.mean(region)
        val local = ColorMath.labStats(background, weights = region)
        val localWeight = ImageOps.smoothstep(0.03f, 0.18f, regionCoverage)
        val targetMeanL = ImageOps.lerp(analysis.stats.meanL, local.meanL, localWeight)
        val targetStdL = ImageOps.lerp(analysis.stats.stdL, local.stdL, localWeight)
        val targetMeanA = ImageOps.lerp(analysis.stats.meanA, local.meanA, localWeight)
        val targetMeanB = ImageOps.lerp(analysis.stats.meanB, local.meanB, localWeight)
        val targetStdA = ImageOps.lerp(analysis.stats.stdA, local.stdA, localWeight)
        val targetStdB = ImageOps.lerp(analysis.stats.stdB, local.stdB, localWeight)
        val targetChroma = ImageOps.lerp(analysis.stats.meanChroma, local.meanChroma, localWeight)
        val targetLow = ImageOps.lerp(analysis.stats.lowL, local.lowL, localWeight)
        val targetHigh = ImageOps.lerp(analysis.stats.highL, local.highL, localWeight)

        val spanF = max(6f, fgStats.highL - fgStats.lowL)
        val spanB = max(6f, targetHigh - targetLow)
        val toneScale = (spanB / spanF).coerceIn(0.6f, 1.7f)
        val sigmaScale = (targetStdL / max(2f, fgStats.stdL)).coerceIn(0.55f, 1.7f)
        val chromaScale = (targetChroma / max(3f, fgStats.meanChroma)).coerceIn(0.6f, 1.45f)
        val aSigma = (targetStdA / max(2f, fgStats.stdA)).coerceIn(0.6f, 1.5f)
        val bSigma = (targetStdB / max(2f, fgStats.stdB)).coerceIn(0.6f, 1.5f)

        // Band statistics: a photograph's shadows and highlights do not share one colour, and
        // matching them separately is what makes the character belong to the same light.
        val fgBands = ColorMath.bandChromaStats(layerImage, weights = layer.a)
        val bgBands = ColorMath.bandChromaStats(background, weights = region)
        val keyChromaA = analysis.keyChromaA
        val keyChromaB = analysis.keyChromaB
        val ambientChromaA = analysis.ambientChromaA
        val ambientChromaB = analysis.ambientChromaB

        val toneStrength = params.toneMatch.coerceIn(0f, 1f)
        val contrastStrength = params.contrastMatch.coerceIn(0f, 1f)
        val chromaStrength = params.chromaMatch.coerceIn(0f, 1f)
        val highlightStrength = params.highlightMatch.coerceIn(0f, 1f)

        ParallelRows.forEach(layer.height) { y ->
            val row = y * w
            val localLab = FloatArray(3)
            for (x in 0 until w) {
                val i = row + x
                if (layer.a[i] <= 0.02f) continue
                ColorMath.rgbToLab(layer.r[i], layer.g[i], layer.b[i], localLab)

                val lStats = targetMeanL + (localLab[0] - fgStats.meanL) *
                    ImageOps.lerp(1f, sigmaScale, contrastStrength)
                val lTone = targetLow + (localLab[0] - fgStats.lowL) * toneScale
                val targetL = ImageOps.lerp(lStats, lTone, toneStrength)
                val newL = ImageOps.lerp(localLab[0], targetL, strength).coerceIn(0f, 100f)

                val bands = ColorMath.bandWeights(newL)
                val bandTarget = bgBands.blended(bands, targetMeanA, targetMeanB)
                val chromaTargetA = ImageOps.lerp(targetMeanA, bandTarget.first, chromaStrength)
                val chromaTargetB = ImageOps.lerp(targetMeanB, bandTarget.second, chromaStrength)

                val scaledA = (localLab[1] - fgStats.meanA) * ImageOps.lerp(1f, aSigma, chromaStrength)
                val scaledB = (localLab[2] - fgStats.meanB) * ImageOps.lerp(1f, bSigma, chromaStrength)
                val targetA = chromaTargetA + scaledA
                val targetB = chromaTargetB + scaledB

                val chroma = ImageOps.lerp(1f, chromaScale, chromaStrength * strength)
                var newA = ImageOps.lerp(localLab[1], targetA, strength) * chroma
                var newB = ImageOps.lerp(localLab[2], targetB, strength) * chroma

                if (highlightStrength > 0.01f) {
                    val highlight = bands[2] * highlightStrength * strength
                    val shadow = bands[0] * highlightStrength * strength * 0.85f
                    if (highlight > 0.002f) {
                        // Give the character's own specular highlights the key light's colour.
                        val k = (highlight * 0.6f).coerceIn(0f, 1f)
                        newA = ImageOps.lerp(newA, keyChromaA * 0.92f, k)
                        newB = ImageOps.lerp(newB, keyChromaB * 0.92f, k)
                    }
                    if (shadow > 0.002f) {
                        val k = (shadow * 0.5f).coerceIn(0f, 1f)
                        newA = ImageOps.lerp(newA, ambientChromaA * 0.9f, k)
                        newB = ImageOps.lerp(newB, ambientChromaB * 0.9f, k)
                    }
                }

                val compressed = ColorMath.compressChroma(newA, newB, 58f)
                ColorMath.labToRgb(newL, compressed.first, compressed.second, localLab)
                layer.r[i] = localLab[0]
                layer.g[i] = localLab[1]
                layer.b[i] = localLab[2]
            }
        }
    }

    // ------------------------------------------------------------------ lighting

    private fun applySceneLighting(
        layer: Layer,
        analysis: SceneAnalysis,
        params: BlendParams,
        background: RasterImage,
        w: Int,
        h: Int,
        fast: Boolean,
        resolutionScale: Float,
    ): Lighting {
        val n = w * h
        // Lighting is applied in place: the sRGB planes of the layer become linear lit planes.
        val linR = layer.r
        val linG = layer.g
        val linB = layer.b

        val fgWorld = ColorMath.grayWorld(RasterImage(w, h, layer.r, layer.g, layer.b, layer.a), layer.a)
        val bgWorld = analysis.whiteBalance
        val wbStrength = (params.whiteBalanceMatch * params.colorMatch).coerceIn(0f, 1f)
        val wbR = ImageOps.lerp(1f, (bgWorld[0] / max(0.2f, fgWorld[0])).coerceIn(0.7f, 1.4f), wbStrength)
        val wbG = ImageOps.lerp(1f, (bgWorld[1] / max(0.2f, fgWorld[1])).coerceIn(0.7f, 1.4f), wbStrength)
        val wbB = ImageOps.lerp(1f, (bgWorld[2] / max(0.2f, fgWorld[2])).coerceIn(0.7f, 1.4f), wbStrength)

        val lightRadiusU = 0.06f
        val lightStrength = params.sceneLightStrength.coerceIn(0f, 1.2f)
        val consistency = params.shadingConsistency.coerceIn(0f, 1f)

        // Rim light masks
        val rimRadius = max(1, ((if (fast) 2.4f else 3f) * resolutionScale).toInt())
        val eroded = ImageOps.erode(layer.a, w, h, rimRadius)
        val blurredAlpha = ImageOps.blur(layer.a, w, h, max(1.5f, rimRadius.toFloat()))

        val keyColor = analysis.keyColor
        val keyLuma = max(0.05f, ColorMath.perceptiveLuma(keyColor[0], keyColor[1], keyColor[2]))
        val rimStrength = params.rimLight.coerceIn(0f, 1.2f) * (0.45f + 0.85f * keyLuma)
        val rimR = ColorMath.srgbToLinear(keyColor[0].coerceIn(0f, 1f))
        val rimG = ColorMath.srgbToLinear(keyColor[1].coerceIn(0f, 1f))
        val rimB = ColorMath.srgbToLinear(keyColor[2].coerceIn(0f, 1f))

        val ground = analysis.groundColor
        val bounceStrength = params.bounceLight.coerceIn(0f, 1f) * 0.4f
        val bounceR = ColorMath.srgbToLinear(ground[0])
        val bounceG = ColorMath.srgbToLinear(ground[1])
        val bounceB = ColorMath.srgbToLinear(ground[2])

        val aoStrength = params.ambientOcclusion.coerceIn(0f, 1f) * 0.3f
        val bboxTop = layer.top.toFloat()
        val bboxHeight = max(1f, layer.bboxHeight)

        ParallelRows.forEach(h) { y ->
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val alpha = layer.a[i]
                if (alpha <= 0.02f) continue

                val u = (x + 0.5f) / w
                val v = (y + 0.5f) / h
                val illum = analysis.illuminationAt(u, v)
                var factor = ImageOps.lerp(1f, illum, lightStrength)
                if (consistency > 0.01f) {
                    val back = max(
                        0.25f,
                        analysis.illuminationAt(
                            u - params.lightX * lightRadiusU,
                            v - params.lightY * lightRadiusU,
                        ),
                    )
                    val directional = (illum / back).coerceIn(0.6f, 1.6f)
                    factor *= ImageOps.lerp(1f, directional, consistency)
                }

                var r = ColorMath.srgbToLinearFast(layer.r[i]) * factor * wbR
                var g = ColorMath.srgbToLinearFast(layer.g[i]) * factor * wbG
                var b = ColorMath.srgbToLinearFast(layer.b[i]) * factor * wbB

                // Contact occlusion and bounce light at the feet.
                val relY = ((y - bboxTop) / bboxHeight).coerceIn(0f, 1f)
                val foot = ImageOps.smoothstep(0.72f, 1f, relY) * alpha
                if (aoStrength > 0.001f) {
                    val dim = 1f - aoStrength * foot
                    r *= dim
                    g *= dim
                    b *= dim
                }
                if (bounceStrength > 0.001f) {
                    val k = (bounceStrength * foot).coerceIn(0f, 0.5f)
                    r = ImageOps.lerp(r, r * 0.6f + bounceR * 0.4f, k)
                    g = ImageOps.lerp(g, g * 0.6f + bounceG * 0.4f, k)
                    b = ImageOps.lerp(b, b * 0.6f + bounceB * 0.4f, k)
                }

                // Rim light on the side of the silhouette that faces the key light.
                if (rimStrength > 0.001f) {
                    val edge = (layer.a[i] - eroded[i]).coerceIn(0f, 1f)
                    if (edge > 0.02f) {
                        val left = if (x > 0) blurredAlpha[i - 1] else blurredAlpha[i]
                        val right = if (x < w - 1) blurredAlpha[i + 1] else blurredAlpha[i]
                        val up = if (y > 0) blurredAlpha[i - w] else blurredAlpha[i]
                        val down = if (y < h - 1) blurredAlpha[i + w] else blurredAlpha[i]
                        val gx = right - left
                        val gy = down - up
                        val len = max(1e-4f, kotlin.math.sqrt(gx * gx + gy * gy))
                        val nx = -gx / len
                        val ny = -gy / len
                        val facing = (nx * params.lightX + ny * params.lightY).coerceIn(-1f, 1f)
                        val lit = ImageOps.smoothstep(0.1f, 0.85f, facing)
                        val rim = edge * lit * rimStrength
                        if (rim > 0.002f) {
                            val screen = rim.coerceAtMost(1f)
                            r = ImageOps.lerp(r, r + rimR * (1f - r) * 1.3f, screen)
                            g = ImageOps.lerp(g, g + rimG * (1f - g) * 1.3f, screen)
                            b = ImageOps.lerp(b, b + rimB * (1f - b) * 1.3f, screen)
                        }
                    }
                }

                linR[i] = r
                linG[i] = g
                linB[i] = b
            }
        }

        // Unused but kept for readability of the call site: background is only needed when the
        // caller wants to inspect it; the analysis already holds every measured statistic.
        require(background.width == w)
        return Lighting(linR, linG, linB)
    }

    // ------------------------------------------------------------------ shadow

    private fun buildShadow(
        layer: Layer,
        analysis: SceneAnalysis,
        params: BlendParams,
        w: Int,
        h: Int,
        fast: Boolean,
    ): FloatArray {
        val n = w * h
        val shadow = FloatArray(n)
        val height = max(1f, layer.bboxHeight)
        val elevation = params.lightElevationDeg.coerceIn(4f, 88f)
        val tanElev = max(0.12f, tan(Math.toRadians(elevation.toDouble())).toFloat())
        /*
         * Ground projection. A point standing `h` above the contact line throws its shadow
         * h / tan(elevation) away from the light, measured *on the ground*. In the picture the
         * ground direction is compressed vertically by the camera pitch, which is what the squash
         * factor models: without it the shadow would run down the screen instead of lying on the
         * floor. Every shadow point therefore stays anchored around the feet line.
         */
        val groundSquash = 0.38f
        val projection = params.shadowLength.coerceIn(0f, 1.4f) / tanElev
        val dirX = -params.lightX
        val dirY = -params.lightY
        val top = layer.top
        val bottom = layer.bottom
        val bboxH = max(1f, (bottom - top).toFloat())

        // Where the character touches the ground: the bottom-most covered row and its centre.
        var feetY = layer.top
        var feetSumX = 0f
        var feetCount = 0
        var scanY = layer.bottom
        while (scanY >= layer.top && feetCount == 0) {
            for (x in layer.left..layer.right) {
                if (layer.a[scanY * w + x] > 0.5f) {
                    feetSumX += x
                    feetCount++
                }
            }
            if (feetCount > 0) feetY = scanY else scanY--
        }
        val feetX = if (feetCount > 0) {
            feetSumX / feetCount
        } else {
            (layer.left + layer.right) * 0.5f
        }

        // Project every silhouette pixel onto the ground plane, anchored at the feet.
        for (y in top..bottom) {
            val heightAboveGround = (feetY - y).toFloat().coerceAtLeast(0f)
            val offset = heightAboveGround * projection
            if (offset <= 0.01f) continue
            val ox = dirX * offset
            val oy = dirY * offset * groundSquash
            for (x in layer.left..layer.right) {
                val a = layer.a[y * w + x]
                if (a <= 0.02f) continue
                splat(shadow, w, h, x + ox, feetY + oy, a)
            }
        }

        val softness = params.shadowSoftness.coerceIn(0f, 1f)
        // Keep the umbra reasonably tight: a shadow that is blurred into mush reads as a smudge.
        val baseSigma = max(1f, height * (0.005f + 0.03f * softness))
        val sigmaX = baseSigma * (1.35f + 0.95f * (1f - params.lightHeight))
        val sigmaY = baseSigma * (0.7f + 0.35f * params.lightHeight)
        var blurred = if (fast) {
            ImageOps.boxBlur(shadow, w, h, max(1, (baseSigma * 1.4f).toInt()))
        } else {
            ImageOps.blurAnisotropic(shadow, w, h, sigmaX, sigmaY)
        }
        // A short smear softens the far edge without dissolving the shadow into a smudge.
        blurred = smear(
            blurred,
            w,
            h,
            dirX,
            dirY * groundSquash,
            min(height * projection * 0.12f, height * 0.18f),
            if (fast) 3 else 5,
        )
        // Blurring inevitably lowers the peak density; compensate so the shadow reads as a shadow.
        val densityGain = if (softness > 0.6f) 1.5f else 1.75f
        // A real shadow gets softer the further it travels (penumbra growth).
        val farField = if (fast) {
            blurred
        } else {
            ImageOps.blurAnisotropic(shadow, w, h, sigmaX * 2.7f, sigmaY * 2.4f)
        }

        // Contact shadow: an ellipse hugging the feet, plus the blurred silhouette for the body.
        val contactRadiusX = max(3f, layer.bboxWidth * 0.42f)
        val contactRadiusY = max(3f, height * 0.038f)
        val contact = FloatArray(n)
        // Sit the contact patch slightly below the feet so the body does not hide it.
        val contactCenterY = feetY + contactRadiusY * 0.9f
        val cx0 = max(0, (feetX - contactRadiusX * 1.6f).toInt())
        val cx1 = min(w - 1, (feetX + contactRadiusX * 1.6f).toInt())
        val cy0 = max(0, (contactCenterY - contactRadiusY * 2.5f).toInt())
        val cy1 = min(h - 1, (contactCenterY + contactRadiusY * 2.5f).toInt())
        for (y in cy0..cy1) {
            for (x in cx0..cx1) {
                val nx = (x + 0.5f - feetX) / contactRadiusX
                val ny = (y + 0.5f - contactCenterY) / contactRadiusY
                val d = kotlin.math.sqrt(nx * nx + ny * ny)
                contact[y * w + x] = (1f - d).coerceIn(0f, 1f)
            }
        }
        val contactBlurred = ImageOps.blur(contact, w, h, max(1f, height * 0.012f))

        val contactStrength = params.contactShadow.coerceIn(0f, 1f)
        // Longest possible projection: the top of the head.
        val maxShadow = max(1f, height * projection)
        val out = FloatArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                // Distance travelled along the ground, undoing the camera squash.
                val proj = (x - feetX) * dirX + ((y - feetY) / groundSquash) * dirY
                val decay = if (proj <= 0f) 1f else {
                    (1f - (proj / maxShadow).coerceIn(0f, 1f)).pow(1.15f)
                }
                val reach = if (maxShadow <= 1f) 0f else (proj / maxShadow).coerceIn(0f, 1f)
                val penumbra = ImageOps.smoothstep(0.10f, 0.85f, reach)
                // The far field only widens the soft edge; the umbra keeps its density.
                val widened = max(blurred[i] * 0.92f, farField[i] * 1.18f)
                val density = ImageOps.lerp(blurred[i], widened, penumbra)
                val core = density * densityGain * (0.62f + 0.38f * decay)
                val tight = contactBlurred[i] * contactStrength
                out[i] = max(core, tight).coerceIn(0f, 1.2f)
            }
        }
        return out
    }

    private fun splat(field: FloatArray, w: Int, h: Int, x: Float, y: Float, value: Float) {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val wx = x - x0
        val wy = y - y0
        for (dy in 0..1) {
            val yy = y0 + dy
            if (yy < 0 || yy >= h) continue
            for (dx in 0..1) {
                val xx = x0 + dx
                if (xx < 0 || xx >= w) continue
                val weight = (if (dx == 0) 1f - wx else wx) * (if (dy == 0) 1f - wy else wy)
                val i = yy * w + xx
                field[i] += value * weight
            }
        }
    }

    /** Smears a field along a direction, used to stretch a cast shadow away from the light. */
    private fun smear(
        field: FloatArray,
        w: Int,
        h: Int,
        dirX: Float,
        dirY: Float,
        distance: Float,
        taps: Int,
    ): FloatArray {
        if (distance < 0.6f || taps <= 1) return field
        val out = FloatArray(field.size)
        val half = (taps - 1) / 2
        var divisor = 0f
        for (t in -half..half) divisor += 1f - 0.5f * (abs(t).toFloat() / max(1, half))
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (t in -half..half) {
                    val offset = distance * t / max(1, half)
                    val sx = (x + dirX * offset).toInt().coerceIn(0, w - 1)
                    val sy = (y + dirY * offset).toInt().coerceIn(0, h - 1)
                    val weight = 1f - 0.5f * (abs(t).toFloat() / max(1, half))
                    acc += field[sy * w + sx] * weight
                }
                out[y * w + x] = acc / divisor
            }
        }
        return out
    }

    /**
     * [ambientFraction, tintR, tintG, tintB]: how much of the ground's light survives in the umbra,
     * and the colour of that surviving light.
     */
    private fun shadowResponse(analysis: SceneAnalysis): FloatArray {
        val amb = analysis.ambientColor
        val mx = max(0.05f, max(amb[0], max(amb[1], amb[2])))
        val tintR = amb[0] / mx
        val tintG = amb[1] / mx
        val tintB = amb[2] / mx
        // High contrast scenes mean a strong key light and therefore a darker shadow.
        val contrast = (analysis.stdLuma / max(0.05f, analysis.meanLuma))
        var ambient = 0.62f - 0.75f * contrast
        if (analysis.time == SceneTime.NIGHT) ambient *= 0.72f
        if (analysis.time == SceneTime.INDOOR) ambient *= 1.18f
        ambient = ambient.coerceIn(0.16f, 0.62f)
        return floatArrayOf(ambient, tintR, tintG, tintB)
    }

    // ------------------------------------------------------------------ edges

    private fun softEdge(
        layer: Layer,
        params: BlendParams,
        w: Int,
        h: Int,
        fast: Boolean,
        resolutionScale: Float,
    ): FloatArray {
        val erodePx = (params.edgeErode.coerceIn(0f, 1f) * 1.5f * resolutionScale).toInt().coerceIn(0, 8)
        var alpha = if (erodePx > 0) ImageOps.erode(layer.a, w, h, erodePx) else layer.a
        val featherSigma = (0.45f + params.edgeFeather.coerceIn(0f, 1f) * 2.2f) * resolutionScale
        alpha = ImageOps.blur(alpha, w, h, featherSigma)
        val out = FloatArray(alpha.size)
        val lo = 0.32f
        val hi = 0.72f
        for (i in alpha.indices) {
            out[i] = ImageOps.smoothstep(lo, hi, alpha[i])
        }
        if (!fast) {
            // Suppress any residual halo: alpha must never exceed the original coverage by much.
            for (i in out.indices) {
                val limit = (layer.a[i] * 1.25f + 0.02f).coerceAtMost(1f)
                if (out[i] > limit) out[i] = limit
            }
        }
        return out
    }

    // ------------------------------------------------------------------ integration

    private fun applyIntegrationPass(
        image: RasterImage,
        softAlpha: FloatArray,
        analysis: SceneAnalysis,
        params: BlendParams,
        fast: Boolean,
    ) {
        val w = image.width
        val h = image.height
        val n = image.size

        // ---- film grain and matched sensor noise (deterministic so previews do not flicker)
        val noiseAmp = (params.noiseMatch.coerceIn(0f, 1f) * analysis.noiseSigma * 1.1f)
            .coerceIn(0f, 0.09f)
        val grainAmp = (params.grain.coerceIn(0f, 1f) * 0.055f)
        if (noiseAmp > 0.0005f || grainAmp > 0.0005f) {
            ParallelRows.forEach(h) { y ->
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    val subject = softAlpha[i]
                    val amount = noiseAmp * subject + grainAmp
                    if (amount <= 0.0004f) continue
                    val nz = (hashNoise(x, y, 1337) - 0.5f) * 2f * amount
                    val shadowWeight = 1.1f - 0.5f * ColorMath.perceptiveLuma(image.r[i], image.g[i], image.b[i])
                    val delta = nz * shadowWeight
                    image.r[i] = (image.r[i] + delta).coerceIn(0f, 1f)
                    image.g[i] = (image.g[i] + delta).coerceIn(0f, 1f)
                    image.b[i] = (image.b[i] + delta).coerceIn(0f, 1f)
                }
            }
        }

        // ---- exposure / temperature / tint (linear), then contrast / saturation (sRGB)
        val exposureGain = 2f.pow(params.exposure.coerceIn(-1f, 1f))
        val tempR = 1f + 0.22f * params.temperature
        val tempB = 1f - 0.2f * params.temperature
        val tintG = 1f - 0.16f * params.tint
        val tintRB = 1f + 0.07f * params.tint
        val contrastGain = 1f + params.contrast.coerceIn(-1f, 1f) * 0.8f
        val saturationGain = 1f + params.saturation.coerceIn(-1f, 1f)
        val needsGrade = exposureGain != 1f || tempR != 1f || tempB != 1f ||
            tintG != 1f || contrastGain != 1f || saturationGain != 1f
        if (needsGrade) {
            ParallelRows.forEach(h) { y ->
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    var r = ColorMath.srgbToLinearFast(image.r[i]) * exposureGain * tempR * tintRB
                    var g = ColorMath.srgbToLinearFast(image.g[i]) * exposureGain * tintG
                    var b = ColorMath.srgbToLinearFast(image.b[i]) * exposureGain * tempB * tintRB
                    r = ColorMath.linearToSrgbFast(r)
                    g = ColorMath.linearToSrgbFast(g)
                    b = ColorMath.linearToSrgbFast(b)
                    r = (r - 0.5f) * contrastGain + 0.5f
                    g = (g - 0.5f) * contrastGain + 0.5f
                    b = (b - 0.5f) * contrastGain + 0.5f
                    val luma = ColorMath.perceptiveLuma(r, g, b)
                    image.r[i] = (luma + (r - luma) * saturationGain).coerceIn(0f, 1f)
                    image.g[i] = (luma + (g - luma) * saturationGain).coerceIn(0f, 1f)
                    image.b[i] = (luma + (b - luma) * saturationGain).coerceIn(0f, 1f)
                }
            }
        }

        // ---- chromatic aberration (very subtle, sells a real lens)
        val ca = params.chromaticAberration.coerceIn(0f, 1f)
        if (ca > 0.01f && !fast) {
            val srcR = image.r.copyOf()
            val srcB = image.b.copyOf()
            val cx = w * 0.5f
            val cy = h * 0.5f
            val maxShift = ca * 0.0025f * max(w, h)
            ParallelRows.forEach(h) { y ->
                val row = y * w
                for (x in 0 until w) {
                    val dx = (x - cx) / max(1f, cx)
                    val dy = (y - cy) / max(1f, cy)
                    val sx = x + dx * maxShift
                    val sy = y + dy * maxShift
                    val bx = x - dx * maxShift
                    val by = y - dy * maxShift
                    val i = row + x
                    image.r[i] = samplePlane(srcR, w, h, sx, sy)
                    image.b[i] = samplePlane(srcB, w, h, bx, by)
                }
            }
        }

        // ---- vignette
        val vig = params.vignette.coerceIn(0f, 1f)
        if (vig > 0.01f) {
            val cx = w * 0.5f
            val cy = h * 0.5f
            val maxR = kotlin.math.sqrt((cx * cx + cy * cy).toDouble()).toFloat()
            ParallelRows.forEach(h) { y ->
                val row = y * w
                for (x in 0 until w) {
                    val dx = x - cx
                    val dy = y - cy
                    val r = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() / maxR
                    val factor = (1f - vig * 0.55f * r * r).coerceIn(0.2f, 1f)
                    val i = row + x
                    image.r[i] = (image.r[i] * factor).coerceIn(0f, 1f)
                    image.g[i] = (image.g[i] * factor).coerceIn(0f, 1f)
                    image.b[i] = (image.b[i] * factor).coerceIn(0f, 1f)
                }
            }
        }
    }

    private fun samplePlane(plane: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (w - 1).toFloat())
        val cy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        return bilerp(
            plane[y0 * w + x0],
            plane[y0 * w + x1],
            plane[y1 * w + x0],
            plane[y1 * w + x1],
            cx - x0,
            cy - y0,
        )
    }

    /** Cheap deterministic value noise. */
    private fun hashNoise(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695040
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0xFFFF).toFloat() / 65535f
    }

    /** 8-connected neighbourhood used by the edge colour relaxation. */
    private val NEIGHBOR_X = intArrayOf(-1, 1, 0, 0, -1, 1, -1, 1)
    private val NEIGHBOR_Y = intArrayOf(0, 0, -1, 1, -1, -1, 1, 1)
}
