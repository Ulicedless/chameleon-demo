package com.chameleon.blend

import com.chameleon.blend.core.blend.BlendEngine
import com.chameleon.blend.core.blend.BlendParams
import com.chameleon.blend.core.blend.BlendPipeline
import com.chameleon.blend.core.blend.BlendStyle
import com.chameleon.blend.core.blend.Matting
import com.chameleon.blend.core.blend.SceneAnalysis
import com.chameleon.blend.core.blend.SceneTime
import com.chameleon.blend.core.color.ColorMath
import com.chameleon.blend.core.img.RasterImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class BlendPipelineTest {

    private val outputDir = File("build/engine-preview").apply { mkdirs() }

    @Test
    fun `scene analysis finds a key light, a ground plane and noise`() {
        val bg = TestImages.streetPhoto()
        val analysis = SceneAnalysis.analyze(bg)

        writePng("00-background.png", bg)

        // The synthetic sun sits at 80% width, 16% height: light must come from the upper right.
        val azimuth = BlendEngine.lightAzimuthOf(analysis)
        assertTrue("azimuth was $azimuth", azimuth > 5f && azimuth < 90f)
        assertTrue("groundY was ${analysis.groundY}", analysis.groundY in 0.55f..0.94f)
        assertTrue("noise was ${analysis.noiseSigma}", analysis.noiseSigma > 0.001f)
        assertTrue("meanLuma was ${analysis.meanLuma}", analysis.meanLuma > 0.2f)
        assertTrue("time was ${analysis.time}", analysis.time == SceneTime.DAY || analysis.time == SceneTime.GOLDEN)
    }

    @Test
    fun `auto tune reacts to the photograph`() {
        val bg = TestImages.streetPhoto()
        val fg = TestImages.animeCharacter()
        val analysis = SceneAnalysis.analyze(bg)
        val alpha = Matting.extractAlpha(fg)
        val params = BlendEngine.autoTune(bg, analysis, fg, alpha)

        assertTrue("heightFraction ${params.heightFraction}", params.heightFraction in 0.2f..0.9f)
        assertTrue("shadow strength ${params.shadowStrength}", params.shadowStrength > 0.2f)
        assertTrue("color match ${params.colorMatch}", params.colorMatch > 0.3f)
        // The subject should be placed so that its feet land near the estimated ground line.
        val feet = 0.5f + params.offsetY + params.heightFraction / 2f
        assertTrue("feet at $feet vs ground ${analysis.groundY}", abs(feet - analysis.groundY) < 0.4f)
        assertTrue(abs(params.offsetX) <= 0.36f)
    }

    @Test
    fun `blend produces a plausible composite and writes previews`() {
        val bg = TestImages.streetPhoto()
        val fg = TestImages.animeCharacter()
        val analysis = SceneAnalysis.analyze(bg)
        val alpha = Matting.extractAlpha(fg)
        var params = BlendEngine.autoTune(bg, analysis, fg, alpha)
        params = params.copy(style = BlendStyle.DAYLIGHT)

        val stages = ArrayList<String>()
        val blended = BlendPipeline.render(bg, fg, alpha, params, analysis, onStage = { stages.add(it) })
        val raw = BlendPipeline.render(
            bg,
            fg,
            alpha,
            BlendEngine.rawPasteParams(params),
            analysis,
            fast = true,
        )

        assertTrue("stages were $stages", stages.size >= 4)
        assertEquals(bg.width, blended.width)
        assertEquals(bg.height, blended.height)

        // No NaN / out of range values anywhere.
        for (i in 0 until blended.size) {
            assertTrue(blended.r[i].isFinite() && blended.g[i].isFinite() && blended.b[i].isFinite())
            assertTrue(blended.r[i] in 0f..1f && blended.g[i] in 0f..1f && blended.b[i] in 0f..1f)
        }

        writePng("01-naive-paste.png", raw)
        writePng("02-blended.png", blended)
        writePng("03-character.png", fg, background = floatArrayOf(0.32f, 0.34f, 0.38f))

        // The blended result must differ from the naive paste: colour transfer and shadow both move pixels.
        var diffSum = 0.0
        var diffCount = 0
        for (i in 0 until blended.size) {
            val d = abs(blended.r[i] - raw.r[i]) + abs(blended.g[i] - raw.g[i]) + abs(blended.b[i] - raw.b[i])
            if (d > 0.02f) diffCount++
            diffSum += d
        }
        val diffRatio = diffCount.toFloat() / blended.size
        assertTrue("only $diffRatio of pixels changed", diffRatio > 0.02f)
        assertTrue("total difference too small: $diffSum", diffSum > 1000)
    }

    @Test
    fun `colour transfer pulls the character towards the photograph`() {
        val bg = TestImages.streetPhoto()
        val fg = TestImages.animeCharacter()
        val analysis = SceneAnalysis.analyze(bg)
        val alpha = Matting.extractAlpha(fg)
        var params = BlendEngine.autoTune(bg, analysis, fg, alpha)
        params = params.copy(
            style = BlendStyle.NATURAL,
            colorMatch = 0.9f,
            contrastMatch = 0.8f,
            chromaMatch = 0.8f,
            whiteBalanceMatch = 0.8f,
            shadowStrength = 0.5f,
        )

        val blended = BlendPipeline.render(bg, fg, alpha, params, analysis)

        // Measure the chroma statistics of the character region instead of the whole frame.
        val placement = com.chameleon.blend.core.blend.PlacementSolver.solve(
            bg.width,
            bg.height,
            fg.width,
            fg.height,
            params,
        )
        val mask = regionMask(bg.width, bg.height, placement.centerX, placement.centerY, placement.scale * fg.width, placement.scale * fg.height)
        val bgStats = ColorMath.labStats(bg, mask)
        val blendedStats = ColorMath.labStats(blended, mask)
        val sourceStats = ColorMath.labStats(fg, alpha)

        val bgToSource = abs(bgStats.meanA - sourceStats.meanA) + abs(bgStats.meanB - sourceStats.meanB)
        val bgToBlended = abs(bgStats.meanA - blendedStats.meanA) + abs(bgStats.meanB - blendedStats.meanB)
        assertTrue(
            "chroma moved the wrong way: before=$bgToSource after=$bgToBlended",
            bgToBlended <= bgToSource + 0.5f,
        )
        assertNotNull(blendedStats)
    }

    @Test
    fun `rendering is deterministic so slider previews do not flicker`() {
        val bg = TestImages.streetPhoto(600, 420)
        val fg = TestImages.animeCharacter()
        val analysis = SceneAnalysis.analyze(bg)
        val alpha = Matting.extractAlpha(fg)
        val params = BlendEngine.autoTune(bg, analysis, fg, alpha)
            .copy(style = BlendStyle.NIGHT, grain = 0.4f, noiseMatch = 0.8f)

        val first = BlendPipeline.render(bg, fg, alpha, params, analysis)
        val second = BlendPipeline.render(bg, fg, alpha, params, analysis)
        for (i in 0 until first.size) {
            assertEquals(first.r[i], second.r[i], 0f)
            assertEquals(first.b[i], second.b[i], 0f)
        }
        writePng("04-night-style.png", first)
    }

    @Test
    fun `empty foreground returns the untouched background`() {
        val bg = TestImages.streetPhoto(320, 240)
        val fg = TestImages.animeCharacter(120, 200)
        val emptyAlpha = FloatArray(fg.size)
        val analysis = SceneAnalysis.analyze(bg)
        val out = BlendPipeline.render(bg, fg, emptyAlpha, BlendParams(), analysis)
        for (i in 0 until out.size) {
            assertEquals(bg.r[i], out.r[i], 1e-5f)
        }
    }

    @Test
    fun `auto cutout recovers a character from a flat backdrop`() {
        val photo = TestImages.streetPhoto(400, 300, seed = 3)
        val fg = TestImages.animeCharacter(200, 360)
        // composite the character over a flat studio backdrop to simulate a JPEG source
        val jpegLike = RasterImage(200, 360)
        val backdrop = floatArrayOf(0.90f, 0.91f, 0.95f)
        for (i in 0 until jpegLike.size) {
            jpegLike.r[i] = backdrop[0]
            jpegLike.g[i] = backdrop[1]
            jpegLike.b[i] = backdrop[2]
        }
        for (i in 0 until jpegLike.size) {
            val a = fg.a[i]
            if (a <= 0f) continue
            jpegLike.r[i] = fg.r[i] * a + backdrop[0] * (1f - a)
            jpegLike.g[i] = fg.g[i] * a + backdrop[1] * (1f - a)
            jpegLike.b[i] = fg.b[i] * a + backdrop[2] * (1f - a)
        }
        val alpha = Matting.extractAlpha(jpegLike)
        var opaque = 0
        var transparent = 0
        for (i in 0 until alpha.size) {
            if (alpha[i] > 0.6f) opaque++
            if (alpha[i] < 0.2f) transparent++
        }
        assertTrue("nothing kept opaque", opaque > 0)
        assertTrue("nothing became transparent", transparent > 0)
        assertTrue("backdrop corner should be removed", alpha[0] < 0.2f)
        val kept = opaque.toFloat() / alpha.size
        assertTrue("kept ratio $kept looks wrong", kept > 0.15f && kept < 0.72f)
        writePng("05-cutout.png", jpegLike.withAlpha(alpha), background = floatArrayOf(0.1f, 0.12f, 0.16f))
        writePng("06-photo.png", photo)
    }

    private fun regionMask(
        width: Int,
        height: Int,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
    ): FloatArray {
        val mask = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val inside = abs(x - cx) <= w * 0.35f && abs(y - cy) <= h * 0.35f
                if (inside) mask[y * width + x] = 1f
            }
        }
        return mask
    }

    private fun writePng(
        name: String,
        raster: RasterImage,
        background: FloatArray = floatArrayOf(1f, 1f, 1f),
    ) {
        val px = TestImages.toArgb(raster, background)
        val image = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, raster.width, raster.height, px, 0, raster.width)
        val file = File(outputDir, name)
        ImageIO.write(image, "png", file)
    }
}
