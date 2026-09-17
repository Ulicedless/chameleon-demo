package com.chameleon.blend.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.chameleon.blend.R
import com.chameleon.blend.core.blend.BlendParams
import com.chameleon.blend.core.blend.BlendStyle
import com.chameleon.blend.core.blend.MattingOptions
import com.chameleon.blend.data.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorState,
    onBack: () -> Unit,
    onParamsChange: ((BlendParams) -> BlendParams) -> Unit,
    onStyle: (BlendStyle) -> Unit,
    onAuto: () -> Unit,
    onToggleCompare: () -> Unit,
    onTab: (ControlTab) -> Unit,
    onMattingChange: ((MattingOptions) -> MattingOptions) -> Unit,
    onResetLayout: () -> Unit,
    onShowExport: (Boolean) -> Unit,
    onExport: (ExportRequest) -> Unit,
    settings: AppSettings = AppSettings(),
) {
    var immersive by remember { mutableStateOf(false) }
    val view = LocalView.current

    // A large export can take a few seconds; keep the screen awake so the process is not suspended.
    LaunchedEffect(state.exporting) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        if (state.exporting) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chameleon", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (state.rendering) {
                                state.stage.ifEmpty { "融合中…" }
                            } else {
                                "${state.params.style.displayName()} · 已匹配背景光影"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { immersive = true }, enabled = state.preview != null) {
                        Icon(
                            painter = painterResource(R.drawable.ic_expand),
                            contentDescription = "沉浸式预览",
                        )
                    }
                    IconButton(onClick = onToggleCompare) {
                        Icon(
                            painter = painterResource(R.drawable.ic_compare),
                            contentDescription = "对比原始贴图",
                            tint = if (state.showRaw) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = { onShowExport(true) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_save),
                            contentDescription = "导出",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            PreviewPane(
                state = state,
                onParamsChange = onParamsChange,
                showGuides = settings.showCompositionGuides,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            ControlPanel(
                state = state,
                onParamsChange = onParamsChange,
                onStyle = onStyle,
                onAuto = onAuto,
                onTab = onTab,
                onMattingChange = onMattingChange,
                onResetLayout = onResetLayout,
                onExportClick = { onShowExport(true) },
            )
        }
    }

    val immersiveBitmap = state.preview
    if (immersive && immersiveBitmap != null) {
        ImmersivePreview(
            blended = immersiveBitmap,
            raw = state.rawPreview,
            title = state.params.style.displayName(),
            onDismiss = { immersive = false },
        )
    }

    if (state.exportSheetVisible) {
        ExportSheet(
            state = state,
            settings = settings,
            onDismiss = { onShowExport(false) },
            onExport = onExport,
        )
    }
}

@Composable
private fun PreviewPane(
    state: EditorState,
    onParamsChange: ((BlendParams) -> BlendParams) -> Unit,
    showGuides: Boolean,
    modifier: Modifier = Modifier,
) {
    val bitmap = if (state.showRaw && state.rawPreview != null) state.rawPreview else state.preview
    val aspect = state.previewAspect

    BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        val density = LocalDensity.current
        // Gestures arrive in pixels, so the fitted rectangle is computed in pixels and only
        // converted to dp where it is used as a size.
        val fitted = fitInside(
            constraints.maxWidth.toFloat(),
            constraints.maxHeight.toFloat(),
            aspect,
        )
        val fittedWidthDp = with(density) { fitted.first.toDp() }
        val fittedHeightDp = with(density) { fitted.second.toDp() }
        val transformState = rememberTransformableState { zoomChange, offsetChange, rotationChange ->
            onParamsChange { params ->
                params.copy(
                    heightFraction = (params.heightFraction * zoomChange).coerceIn(0.08f, 1.4f),
                    offsetX = (params.offsetX + offsetChange.x / fitted.first).coerceIn(-0.6f, 0.6f),
                    offsetY = (params.offsetY + offsetChange.y / fitted.second).coerceIn(-0.6f, 0.6f),
                    rotationDeg = (params.rotationDeg + rotationChange).coerceIn(-45f, 45f),
                )
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = fittedWidthDp, height = fittedHeightDp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black)
                    .transformable(transformState),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "融合预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }

                if (state.rendering) {
                    if (showGuides) {
                        CompositionGuides()
                    }
                }

                if (state.rendering || state.showRaw) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color(0x99000000))
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (state.showRaw) {
                                "直接贴图 · 未做色彩与光影融合"
                            } else {
                                state.stage.ifEmpty { "融合中…" }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/** Thirds grid plus a ground line hint, shown while the engine is still catching up. */
@Composable
private fun CompositionGuides() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = 1f
        val color = Color(0x44FFFFFF)
        for (i in 1..2) {
            val x = size.width * i / 3f
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            val y = size.height * i / 3f
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

private fun fitInside(containerWidth: Float, containerHeight: Float, aspect: Float): Pair<Float, Float> {
    if (aspect <= 0f) return containerWidth to containerHeight
    var width = containerWidth
    var height = width / aspect
    if (height > containerHeight) {
        height = containerHeight
        width = height * aspect
    }
    return width to height
}
