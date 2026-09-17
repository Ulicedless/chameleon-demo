package com.chameleon.blend.ui.editor

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.chameleon.blend.core.blend.BlendEngine
import com.chameleon.blend.core.blend.BlendParams
import com.chameleon.blend.core.blend.BlendStyle
import com.chameleon.blend.core.blend.MattingOptions
import com.chameleon.blend.data.RecentProject
import java.io.File

enum class AppScreen { HOME, EDITOR, SETTINGS }

/** Where an image came from: a picked document uri or a file we saved ourselves. */
sealed interface ImageSource {
    data class Picked(val uri: Uri) : ImageSource
    data class Local(val file: File) : ImageSource
}

enum class ControlTab(val label: String) {
    PLACE("位置"),
    LIGHT("光影"),
    COLOR("色彩"),
    DETAIL("细节"),
}

@Immutable
data class EditorState(
    val screen: AppScreen = AppScreen.HOME,
    val foreground: ImageSource? = null,
    val background: ImageSource? = null,
    val foregroundThumb: ImageBitmap? = null,
    val backgroundThumb: ImageBitmap? = null,
    val preview: ImageBitmap? = null,
    val rawPreview: ImageBitmap? = null,
    val showRaw: Boolean = false,
    val previewAspect: Float = 1f,
    val params: BlendParams = BlendParams(),
    val matting: MattingOptions = MattingOptions(),
    val summary: BlendEngine.SceneSummary? = null,
    val tab: ControlTab = ControlTab.LIGHT,
    val rendering: Boolean = false,
    val stage: String = "",
    val loading: Boolean = false,
    val exporting: Boolean = false,
    val foregroundHasAlpha: Boolean = false,
    val recents: List<RecentProject> = emptyList(),
    val message: String? = null,
    val pendingShareUri: Uri? = null,
    val exportSheetVisible: Boolean = false,
    val cacheSizeBytes: Long = 0L,
) {
    val hasBothImages: Boolean get() = foreground != null && background != null
}

/** Result of a user export, used to show the right confirmation. */
enum class ExportTarget { GALLERY, SHARE }

data class ExportRequest(
    val maxDimension: Int = 1920,
    val format: com.chameleon.blend.data.ExportFormat = com.chameleon.blend.data.ExportFormat.JPEG,
    val quality: Int = 95,
    val target: ExportTarget = ExportTarget.GALLERY,
)

fun BlendStyle.displayName(): String = label
