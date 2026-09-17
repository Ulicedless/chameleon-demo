package com.chameleon.blend

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.graphics.asImageBitmap
import com.chameleon.blend.core.blend.BlendEngine
import com.chameleon.blend.core.blend.BlendParams
import com.chameleon.blend.core.blend.BlendStyle
import com.chameleon.blend.core.blend.MattingOptions
import com.chameleon.blend.ui.editor.AppScreen
import com.chameleon.blend.ui.editor.ControlTab
import com.chameleon.blend.ui.editor.EditorScreen
import com.chameleon.blend.ui.editor.EditorState
import com.chameleon.blend.ui.components.ChoiceChip
import com.chameleon.blend.ui.home.HomeScreen
import com.chameleon.blend.ui.settings.SettingsScreen
import com.chameleon.blend.data.AppSettings
import com.chameleon.blend.data.PreviewQuality
import com.chameleon.blend.ui.theme.ChameleonTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real Compose screens on the JVM so that resource, theme and composition mistakes are
 * caught without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `home screen renders both upload slots and the primary action`() {
        var pickedForeground = false
        var started = false
        composeRule.setContent {
            ChameleonTheme(dynamicColor = false) {
                HomeScreen(
                    foregroundThumb = null,
                    backgroundThumb = null,
                    recents = emptyList(),
                    hasBothImages = false,
                    onPickForeground = { pickedForeground = true },
                    onPickBackground = {},
                    onClearForeground = {},
                    onClearBackground = {},
                    onStart = { started = true },
                    onOpenRecent = {},
                    onDeleteRecent = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("Chameleon").assertIsDisplayed()
        // The label below each slot and the placeholder headline both mention the slot name.
        composeRule.onAllNodesWithText("角色图片").assertCountEquals(1)
        composeRule.onAllNodesWithText("现实照片").assertCountEquals(1)
        composeRule.onNodeWithText("开始融合").assertIsDisplayed()
        // One button per slot.
        composeRule.onAllNodesWithText("选择图片", useUnmergedTree = true).assertCountEquals(2)

        composeRule.onNodeWithText("开始融合").performClick()
        assertTrue("the start button should be inert while an image is missing", !started)
    }

    @Test
    fun `chips report their selection state`() {
        var clicked = false
        composeRule.setContent {
            ChameleonTheme(dynamicColor = false) {
                ChoiceChip(label = "日光", selected = false, onClick = { clicked = true })
            }
        }
        composeRule.onNodeWithText("日光").assertIsDisplayed().performClick()
        assertTrue(clicked)
    }

    @Test
    fun `editor screen renders preview, control tabs and export entry points`() {
        val preview = android.graphics.Bitmap
            .createBitmap(240, 180, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
        var selectedTab: ControlTab? = null
        var exportOpened = false
        var autoPressed = false
        val state = EditorState(
            screen = AppScreen.EDITOR,
            preview = preview,
            previewAspect = 4f / 3f,
            params = BlendParams.of(BlendStyle.DAYLIGHT),
            matting = MattingOptions(),
            summary = BlendEngine.summarize(TestImages.analysisForTest()),
            tab = ControlTab.LIGHT,
            foregroundHasAlpha = true,
        )

        composeRule.setContent {
            ChameleonTheme(dynamicColor = false) {
                EditorScreen(
                    state = state,
                    onBack = {},
                    onParamsChange = {},
                    onStyle = {},
                    onAuto = { autoPressed = true },
                    onToggleCompare = {},
                    onTab = { selectedTab = it },
                    onMattingChange = {},
                    onResetLayout = {},
                    onShowExport = { exportOpened = it },
                    onExport = {},
                )
            }
        }

        composeRule.onNodeWithText("Chameleon").assertIsDisplayed()
        composeRule.onAllNodesWithText("位置").assertCountEquals(1)
        composeRule.onNodeWithText("光影").assertIsDisplayed().performClick()
        assertTrue("tab clicks should reach the view model", selectedTab == ControlTab.LIGHT)
        composeRule.onNodeWithText("自动融合").assertIsDisplayed().performClick()
        assertTrue(autoPressed)
        composeRule.onNodeWithText("导出").assertIsDisplayed().performClick()
        assertTrue("the export sheet should open", exportOpened)
    }

    @Test
    fun `settings screen renders every section and reports changes`() {
        var updated: AppSettings? = null
        var backPressed = false
        composeRule.setContent {
            ChameleonTheme(dynamicColor = false) {
                SettingsScreen(
                    settings = AppSettings(),
                    cacheSizeLabel = "1.2 MB",
                    onUpdate = { block -> updated = block(AppSettings()) },
                    onClearCache = {},
                    onClearProjects = {},
                    onBack = { backPressed = true },
                )
            }
        }

        composeRule.onNodeWithText("设置").assertIsDisplayed()
        composeRule.onNodeWithText("外观").assertExists()
        composeRule.onNodeWithText("融合偏好").assertExists()
        composeRule.onNodeWithText("预览质量").assertExists()
        composeRule.onNodeWithText("导出默认值").assertExists()
        composeRule.onNodeWithText("抠图").assertExists()
        composeRule.onNodeWithText("作品与缓存").assertExists()
        composeRule.onNodeWithText("关于 Chameleon").assertExists()

        composeRule.onNodeWithText("精细 1280").performScrollTo().performClick()
        assertEquals(PreviewQuality.FINE, updated?.previewQuality)
    }
}
