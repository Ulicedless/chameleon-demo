package com.chameleon.blend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chameleon.blend.core.blend.BlendStyle
import com.chameleon.blend.core.blend.Matting
import com.chameleon.blend.core.blend.MattingOptions
import com.chameleon.blend.core.blend.BlendParams
import com.chameleon.blend.data.BitmapIo
import com.chameleon.blend.data.BlendParamsJson
import com.chameleon.blend.data.ExportFormat
import com.chameleon.blend.data.ProjectStore
import com.chameleon.blend.data.SaveResult
import com.chameleon.blend.data.SourceCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Exercises the platform half of the app on the JVM: PNG/JPEG bridge, alpha preservation, parameter
 * serialisation and the on-disk project format.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidBridgeTest {

    @Test
    fun `bitmap round trip keeps size, colour and alpha`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val background = BitmapIo.toBitmap(TestImages.streetPhoto(320, 240))
        val character = BitmapIo.toBitmap(TestImages.animeCharacter(140, 240))

        val bgFile = File(context.cacheDir, "bridge-bg.png")
        val fgFile = File(context.cacheDir, "bridge-fg.png")
        assertTrue(BitmapIo.writeToFile(bgFile, background, ExportFormat.PNG))
        assertTrue(BitmapIo.writeToFile(fgFile, character, ExportFormat.PNG))

        val decodedBg = BitmapIo.decodeFile(bgFile, 2048)
        val decodedFg = BitmapIo.decodeFile(fgFile, 2048)
        assertNotNull(decodedBg)
        assertNotNull(decodedFg)
        assertEquals(320, decodedBg!!.width)
        assertEquals(240, decodedBg.height)

        val raster = BitmapIo.toRaster(decodedBg)
        assertEquals(320, raster.width)
        val sourceRaster = TestImages.streetPhoto(320, 240)
        var maxDelta = 0f
        for (i in 0 until raster.size) {
            maxDelta = maxOf(
                maxDelta,
                kotlin.math.abs(raster.r[i] - sourceRaster.r[i]),
                kotlin.math.abs(raster.g[i] - sourceRaster.g[i]),
                kotlin.math.abs(raster.b[i] - sourceRaster.b[i]),
            )
        }
        assertTrue("PNG round trip lost colour precision: $maxDelta", maxDelta < 0.01f)

        val fgRaster = BitmapIo.toRaster(decodedFg!!)
        assertTrue(
            "alpha should survive the PNG round trip",
            Matting.transparencyRatio(fgRaster) > 0.2f,
        )
        assertEquals(1f, TestImages.toArgb(sourceRaster).size.toFloat() / raster.size.toFloat(), 1e-6f)
    }

    @Test
    fun `project store keeps parameters and can reopen a work`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ProjectStore(context)
        val background = BitmapIo.toBitmap(TestImages.streetPhoto(240, 160))
        val character = BitmapIo.toBitmap(TestImages.animeCharacter(100, 170))
        val result = background

        val params = BlendParams.of(BlendStyle.NIGHT).copy(
            shadowStrength = 0.42f,
            rotationDeg = 12.5f,
            offsetX = -0.18f,
            flipHorizontal = true,
            grain = 0.31f,
        )
        val matting = MattingOptions(tolerance = 0.7f, feather = 0.22f, enabled = true)

        val saved = store.save(background, character, result, params, matting)
        assertNotNull(saved)
        val reopened = store.list().firstOrNull { it.id == saved!!.id }
        assertNotNull(reopened)
        assertEquals(BlendStyle.NIGHT, reopened!!.style)
        assertEquals(0.42f, reopened.params.shadowStrength, 1e-4f)
        assertEquals(12.5f, reopened.params.rotationDeg, 1e-4f)
        assertEquals(-0.18f, reopened.params.offsetX, 1e-4f)
        assertEquals(0.31f, reopened.params.grain, 1e-4f)
        assertTrue(reopened.params.flipHorizontal)
        assertEquals(0.7f, reopened.matting.tolerance, 1e-4f)
        assertTrue(reopened.background.length() > 0)
        assertTrue(reopened.foreground.length() > 0)

        store.delete(reopened)
        assertTrue(store.list().none { it.id == reopened.id })
    }

    @Test
    fun `parameter json survives every field`() {
        val params = BlendParams.of(BlendStyle.FILM).copy(
            lightAzimuthDeg = 205.5f,
            lightElevationDeg = 12f,
            shadowLength = 0.83f,
            chromaticAberration = 0.44f,
            saturation = -0.25f,
            temperature = 0.13f,
        )
        val json = BlendParamsJson.encode(params)
        val decoded = BlendParamsJson.decode(json)
        assertEquals(params, decoded)
    }

    @Test
    fun `source cache keeps a private copy that survives a lost uri grant`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = SourceCache(context)
        cache.clear()
        val bitmap = BitmapIo.toBitmap(TestImages.animeCharacter(140, 240))
        val file = cache.capture(SourceCache.Role.FOREGROUND, bitmap)
        assertNotNull(file)
        assertNotNull(cache.existing(SourceCache.Role.FOREGROUND))
        assertTrue(cache.sizeBytes() > 0L)

        // Export only ever reads from here, so a stale document uri can no longer break it.
        val decoded = BitmapIo.decodeFile(cache.fileFor(SourceCache.Role.FOREGROUND), 2048)
        assertNotNull(decoded)
        assertEquals(140, decoded!!.width)
        assertTrue(
            "alpha must survive the private copy",
            Matting.transparencyRatio(BitmapIo.toRaster(decoded)) > 0.2f,
        )
    }

    @Test
    fun `saving always produces a usable result`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = BitmapIo.toBitmap(TestImages.streetPhoto(200, 140))
        val result = BitmapIo.saveImage(context, bitmap, "chameleon-test", ExportFormat.JPEG, 90)
        assertTrue(
            "expected a gallery entry or the app-private fallback, got $result",
            result is SaveResult.Gallery || result is SaveResult.AppStorage,
        )
        if (result is SaveResult.AppStorage) {
            assertTrue(result.file.exists() && result.file.length() > 0)
        }
    }
}
