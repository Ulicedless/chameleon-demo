package com.chameleon.blend

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import com.chameleon.blend.core.blend.BlendEngine
import com.chameleon.blend.core.blend.BlendPipeline
import com.chameleon.blend.core.blend.Matting
import com.chameleon.blend.core.blend.SceneAnalysis
import com.chameleon.blend.core.img.RasterImage
import com.chameleon.blend.data.AppSettings
import com.chameleon.blend.data.BitmapIo
import com.chameleon.blend.ui.editor.AppScreen
import com.chameleon.blend.ui.editor.ControlTab
import com.chameleon.blend.ui.editor.EditorScreen
import com.chameleon.blend.ui.editor.EditorState
import com.chameleon.blend.ui.home.HomeScreen
import com.chameleon.blend.ui.settings.SettingsScreen
import com.chameleon.blend.ui.theme.ChameleonTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Renders the real screens on the JVM and writes them to `docs/screenshots` for the README.
 *
 * Compose's `captureToImage()` needs PixelCopy, which Robolectric does not implement, so the view is
 * laid out at phone size and drawn straight into a Bitmap. Each capture is validated (size, colour
 * variety, brand colour, text contrast) because a silently blank render would otherwise end up
 * published in the README.
 *
 * Opt-in: run with `CHAMELEON_SCREENSHOTS=1` so a normal test run never rewrites repository files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotGeneratorTest {

    private val outputDir = File("../docs/screenshots").apply { mkdirs() }

    private companion object {
        /** SurfaceLight from ui/theme/Color.kt. */
        const val SURFACE_LIGHT = 0xFFFAFCFA.toInt()
    }

    @Test
    fun `capture home editor and settings`() {
        assumeTrue(
            "set CHAMELEON_SCREENSHOTS=1 to regenerate the README screenshots",
            System.getenv("CHAMELEON_SCREENSHOTS") == "1",
        )
        val preview = renderBlendPreview()
        val params = BlendEngine.autoTune(background, analysis, character, alpha)
        val summary = BlendEngine.summarize(analysis)

        capture("home") {
            HomeScreen(
                foregroundThumb = null,
                backgroundThumb = null,
                recents = emptyList(),
                hasBothImages = false,
                onPickForeground = {},
                onPickBackground = {},
                onClearForeground = {},
                onClearBackground = {},
                onStart = {},
                onOpenRecent = {},
                onDeleteRecent = {},
                onOpenSettings = {},
            )
        }

        capture("editor") {
            EditorScreen(
                state = EditorState(
                    screen = AppScreen.EDITOR,
                    preview = preview,
                    previewAspect = preview.width.toFloat() / preview.height,
                    params = params,
                    summary = summary,
                    tab = ControlTab.LIGHT,
                    foregroundHasAlpha = true,
                ),
                onBack = {},
                onParamsChange = {},
                onStyle = {},
                onAuto = {},
                onToggleCompare = {},
                onTab = {},
                onMattingChange = {},
                onResetLayout = {},
                onShowExport = {},
                onExport = {},
                settings = AppSettings(),
            )
        }

        capture("settings") {
            SettingsScreen(
                settings = AppSettings(),
                cacheSizeLabel = "3.4 MB",
                onUpdate = {},
                onClearCache = {},
                onClearProjects = {},
                onBack = {},
            )
        }
    }

    // ---------------------------------------------------------------- rendering

    private fun capture(name: String, content: @Composable () -> Unit) {
        val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java)
            .setup()
            .get()
        val view = ComposeView(activity).apply {
            setContent {
                ChameleonTheme(dynamicColor = false) {
                    Box(modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
        activity.setContentView(view)
        idle()

        val width = 1080
        val height = 2340
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        idle()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // The window behind a Compose screen is transparent; fill the theme surface first so the
        // capture is a real screenshot instead of an image with transparent holes.
        canvas.drawColor(SURFACE_LIGHT)
        view.draw(canvas)
        idle()

        validate(name, bitmap)
        // JPEG keeps the repository light; UI text stays readable at quality 92.
        val file = File(outputDir, "$name.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        println("[screenshot] $name -> ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * A real screen has thousands of colours, text with strong contrast and some pixels of the brand
     * colour; anything less means the capture failed and must not be published.
     */
    private fun validate(name: String, bitmap: Bitmap) {
        val colors = HashSet<Int>()
        var dark = 0
        var brand = 0
        var total = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val c = bitmap.getPixel(x, y)
                total++
                colors.add(c)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                if (luma < 0.45) dark++
                if (g > r + 12 && g > 80 && abs(g - b) < 70 && r < 150 && luma < 0.65) brand++
            }
        }
        val darkRatio = dark.toDouble() / total
        val brandRatio = brand.toDouble() / total
        println(
            "[screenshot] $name check: colours=${colors.size} " +
                "darkRatio=${"%.3f".format(darkRatio)} brandRatio=${"%.3f".format(brandRatio)}",
        )
        assertTrue("$name looks blank (${colors.size} colours)", colors.size > 300)
        assertTrue("$name has no readable content ($darkRatio)", darkRatio > 0.005)
        assertTrue("$name is missing the brand colour ($brandRatio)", brandRatio > 0.002)
    }

    // ---------------------------------------------------------------- engine input

    private val background: RasterImage by lazy { TestImages.streetPhoto(900, 640) }
    private val character: RasterImage by lazy { TestImages.animeCharacter(360, 620) }
    private val analysis: SceneAnalysis by lazy { SceneAnalysis.analyze(background) }
    private val alpha: FloatArray by lazy { Matting.extractAlpha(character) }

    /**
     * Renders a real engine result so the editor screenshot shows an actual composite, and publishes
     * the before/after pair used by the README.
     */
    private fun renderBlendPreview() = run {
        val params = BlendEngine.autoTune(background, analysis, character, alpha)
        val output = BlendPipeline.render(background, character, alpha, params, analysis)
        val blended = Bitmap.createScaledBitmap(BitmapIo.toBitmap(output), 900, 640, true)
        val naive = BlendPipeline.render(
            background,
            character,
            alpha,
            BlendEngine.rawPasteParams(params),
            analysis,
            fast = true,
        )
        val pasted = Bitmap.createScaledBitmap(BitmapIo.toBitmap(naive), 900, 640, true)

        val imagesDir = File("../docs/images").apply { mkdirs() }
        FileOutputStream(File(imagesDir, "engine-paste.jpg")).use {
            pasted.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
        FileOutputStream(File(imagesDir, "engine-blended.jpg")).use {
            blended.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
        blended.asImageBitmap()
    }
}
