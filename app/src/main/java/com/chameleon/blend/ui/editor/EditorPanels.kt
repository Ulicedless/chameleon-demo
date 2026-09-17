package com.chameleon.blend.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chameleon.blend.R
import com.chameleon.blend.core.blend.BlendParams
import com.chameleon.blend.core.blend.BlendStyle
import com.chameleon.blend.core.blend.MattingOptions
import com.chameleon.blend.data.ExportFormat
import com.chameleon.blend.data.AppSettings
import com.chameleon.blend.ui.components.ChoiceChip
import com.chameleon.blend.ui.components.SliderRow
import kotlin.math.roundToInt

@Composable
fun ControlPanel(
    state: EditorState,
    onParamsChange: ((BlendParams) -> BlendParams) -> Unit,
    onStyle: (BlendStyle) -> Unit,
    onAuto: () -> Unit,
    onTab: (ControlTab) -> Unit,
    onMattingChange: ((MattingOptions) -> MattingOptions) -> Unit,
    onResetLayout: () -> Unit,
    onExportClick: () -> Unit,
) {
    val params = state.params
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            StyleRow(current = params.style, onStyle = onStyle)
            Spacer(Modifier.height(8.dp))
            state.summary?.let { summary ->
                Text(
                    text = "${summary.timeLabel} · ${summary.lightLabel} · ${summary.noiseLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = summary.suggestion,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
            }
            ControlTabs(current = state.tab, onTab = onTab)
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 210.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (state.tab) {
                    ControlTab.PLACE -> PlaceControls(
                        state = state,
                        onParamsChange = onParamsChange,
                        onMattingChange = onMattingChange,
                        onResetLayout = onResetLayout,
                    )
                    ControlTab.LIGHT -> LightControls(params, onParamsChange)
                    ControlTab.COLOR -> ColorControls(params, onParamsChange)
                    ControlTab.DETAIL -> DetailControls(state, onParamsChange, onMattingChange)
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onAuto,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_sparkle),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("自动融合", style = MaterialTheme.typography.labelLarge)
                }
                FilledTonalButton(
                    onClick = onExportClick,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 16.dp),
                ) {
                    Text("导出", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun StyleRow(current: BlendStyle, onStyle: (BlendStyle) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(BlendStyle.entries.toList()) { style ->
            ChoiceChip(
                label = style.label,
                selected = style == current,
                onClick = { onStyle(style) },
            )
        }
    }
}

@Composable
private fun ControlTabs(current: ControlTab, onTab: (ControlTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ControlTab.entries.forEach { tab ->
            ChoiceChip(
                label = tab.label,
                selected = tab == current,
                onClick = { onTab(tab) },
                icon = painterResource(
                    when (tab) {
                        ControlTab.PLACE -> R.drawable.ic_tune
                        ControlTab.LIGHT -> R.drawable.ic_light
                        ControlTab.COLOR -> R.drawable.ic_palette
                        ControlTab.DETAIL -> R.drawable.ic_grain
                    },
                ),
            )
        }
    }
}

@Composable
private fun PlaceControls(
    state: EditorState,
    onParamsChange: ((BlendParams) -> BlendParams) -> Unit,
    onMattingChange: ((MattingOptions) -> MattingOptions) -> Unit,
    onResetLayout: () -> Unit,
) {
    val params = state.params
    SliderRow(
        label = "角色大小",
        value = params.heightFraction,
        range = 0.15f..1.2f,
        defaultValue = 0.62f,
        format = { "${(it * 100).roundToInt()}%" },
        onValueChange = { value -> onParamsChange { it.copy(heightFraction = value) } },
    )
    SliderRow(
        label = "左右位置",
        value = params.offsetX,
        range = -0.5f..0.5f,
        defaultValue = 0f,
        onValueChange = { value -> onParamsChange { it.copy(offsetX = value) } },
    )
    SliderRow(
        label = "上下位置",
        value = params.offsetY,
        range = -0.5f..0.5f,
        defaultValue = 0f,
        onValueChange = { value -> onParamsChange { it.copy(offsetY = value) } },
    )
    SliderRow(
        label = "旋转角度",
        value = params.rotationDeg,
        range = -45f..45f,
        defaultValue = 0f,
        format = { "${it.roundToInt()}°" },
        onValueChange = { value -> onParamsChange { it.copy(rotationDeg = value) } },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChoiceChip(
            label = "水平镜像",
            selected = params.flipHorizontal,
            onClick = { onParamsChange { p -> p.copy(flipHorizontal = !p.flipHorizontal) } },
        )
        ChoiceChip(
            label = "重新自动构图",
            selected = false,
            onClick = onResetLayout,
        )
    }
    if (!state.foregroundHasAlpha) {
        Spacer(Modifier.height(4.dp))
        SliderRow(
            label = "抠图容差",
            value = state.matting.tolerance,
            hint = "值越大，判定为背景的颜色范围越宽；适合纯色底的立绘",
            defaultValue = 0.45f,
            onValueChange = { value -> onMattingChange { it.copy(tolerance = value) } },
        )
    }
}

@Composable
private fun LightControls(
    params: BlendParams,
    onParamsChange: ((BlendParams) -> BlendParams) -> Unit,
) {
    SliderRow(
        label = "光源方向",
        value = params.lightAzimuthDeg,
        range = 0f..360f,
        defaultValue = 130f,
        format = { "${it.roundToInt()}°" },
        hint = "0° 表示光从右侧来，180° 表示从左侧来",
        onValueChange = { value -> onParamsChange { it.copy(lightAzimuthDeg = value) } },
    )
    SliderRow(
        label = "光源高度",
        value = params.lightElevationDeg,
        range = 5f..88f,
        defaultValue = 42f,
        format = { "${it.roundToInt()}°" },
        hint = "越低投影越长，正午光约 70°",
        onValueChange = { value -> onParamsChange { it.copy(lightElevationDeg = value) } },
    )
    SliderRow(
        label = "场景光强",
        value = params.sceneLightStrength,
        range = 0f..1.2f,
        defaultValue = 0.75f,
        onValueChange = { value -> onParamsChange { it.copy(sceneLightStrength = value) } },
    )
    SliderRow(
        label = "受光一致性",
        value = params.shadingConsistency,
        range = 0f..1f,
        defaultValue = 0.45f,
        onValueChange = { value -> onParamsChange { it.copy(shadingConsistency = value) } },
    )
    SliderRow(
        label = "投影强度",
        value = params.shadowStrength,
        range = 0f..1f,
        defaultValue = 0.55f,
        onValueChange = { value -> onParamsChange { it.copy(shadowStrength = value) } },
    )
    SliderRow(
        label = "投影柔和",
        value = params.shadowSoftness,
        range = 0f..1f,
        defaultValue = 0.55f,
        onValueChange = { value -> onParamsChange { it.copy(shadowSoftness = value) } },
    )
    SliderRow(
        label = "投影长度",
        value = params.shadowLength,
        range = 0f..1f,
        defaultValue = 0.5f,
        onValueChange = { value -> onParamsChange { it.copy(shadowLength = value) } },
    )
    SliderRow(
        label = "接触阴影",
        value = params.contactShadow,
        range = 0f..1f,
        defaultValue = 0.6f,
        hint = "脚底与地面贴合处的深色阴影，最能消除“飘在空中”的感觉",
        onValueChange = { value -> onParamsChange { it.copy(contactShadow = value) } },
    )
    SliderRow(
        label = "环境遮蔽",
        value = params.ambientOcclusion,
        range = 0f..1f,
        defaultValue = 0.45f,
        onValueChange = { value -> onParamsChange { it.copy(ambientOcclusion = value) } },
    )
    SliderRow(
        label = "轮廓光",
        value = params.rimLight,
        range = 0f..1f,
        defaultValue = 0.35f,
        hint = "沿着背景光源方向给角色加一层边缘亮光",
        onValueChange = { value -> onParamsChange { it.copy(rimLight = value) } },
    )
    SliderRow(
        label = "环境反射",
        value = params.bounceLight,
        range = 0f..1f,
        defaultValue = 0.25f,
        onValueChange = { value -> onParamsChange { it.copy(bounceLight = value) } },
    )
}

@Composable
private fun ColorControls(
    params: BlendParams,
    onParamsChange: ((BlendParams) -> BlendParams) -> Unit,
) {
    SliderRow(
        label = "色彩融合",
        value = params.colorMatch,
        range = 0f..1f,
        defaultValue = 0.7f,
        hint = "把角色的整体色相与明度拉向背景的统计分布",
        onValueChange = { value -> onParamsChange { it.copy(colorMatch = value) } },
    )
    SliderRow(
        label = "对比匹配",
        value = params.contrastMatch,
        range = 0f..1f,
        defaultValue = 0.6f,
        onValueChange = { value -> onParamsChange { it.copy(contrastMatch = value) } },
    )
    SliderRow(
        label = "饱和度匹配",
        value = params.chromaMatch,
        range = 0f..1f,
        defaultValue = 0.5f,
        onValueChange = { value -> onParamsChange { it.copy(chromaMatch = value) } },
    )
    SliderRow(
        label = "白平衡匹配",
        value = params.whiteBalanceMatch,
        range = 0f..1f,
        defaultValue = 0.6f,
        onValueChange = { value -> onParamsChange { it.copy(whiteBalanceMatch = value) } },
    )
    SliderRow(
        label = "黑白场匹配",
        value = params.toneMatch,
        range = 0f..1f,
        defaultValue = 0.5f,
        onValueChange = { value -> onParamsChange { it.copy(toneMatch = value) } },
    )
    SliderRow(
        label = "高光 / 阴影配色",
        value = params.highlightMatch,
        range = 0f..1f,
        defaultValue = 0.55f,
        hint = "让角色的高光带上场景主光的颜色、暗部带上环境光的颜色",
        onValueChange = { value -> onParamsChange { it.copy(highlightMatch = value) } },
    )
    SliderRow(
        label = "曝光",
        value = params.exposure,
        range = -1f..1f,
        defaultValue = 0f,
        format = { if (it >= 0f) "+${(it * 100).roundToInt()}" else "${(it * 100).roundToInt()}" },
        onValueChange = { value -> onParamsChange { it.copy(exposure = value) } },
    )
    SliderRow(
        label = "对比度",
        value = params.contrast,
        range = -1f..1f,
        defaultValue = 0f,
        onValueChange = { value -> onParamsChange { it.copy(contrast = value) } },
    )
    SliderRow(
        label = "饱和度",
        value = params.saturation,
        range = -1f..1f,
        defaultValue = 0f,
        onValueChange = { value -> onParamsChange { it.copy(saturation = value) } },
    )
    SliderRow(
        label = "色温",
        value = params.temperature,
        range = -1f..1f,
        defaultValue = 0f,
        format = { if (it >= 0f) "暖 +${(it * 100).roundToInt()}" else "冷 ${(it * 100).roundToInt()}" },
        onValueChange = { value -> onParamsChange { it.copy(temperature = value) } },
    )
    SliderRow(
        label = "色调",
        value = params.tint,
        range = -1f..1f,
        defaultValue = 0f,
        format = { if (it >= 0f) "洋红 +${(it * 100).roundToInt()}" else "青绿 ${(it * 100).roundToInt()}" },
        onValueChange = { value -> onParamsChange { it.copy(tint = value) } },
    )
}

@Composable
private fun DetailControls(
    state: EditorState,
    onParamsChange: ((BlendParams) -> BlendParams) -> Unit,
    onMattingChange: ((MattingOptions) -> MattingOptions) -> Unit,
) {
    val params = state.params
    SliderRow(
        label = "边缘羽化",
        value = params.edgeFeather,
        range = 0f..1f,
        defaultValue = 0.35f,
        hint = "让角色边缘与照片的虚实过渡更自然",
        onValueChange = { value -> onParamsChange { it.copy(edgeFeather = value) } },
    )
    SliderRow(
        label = "边缘净化",
        value = params.edgeDecontamination,
        range = 0f..1f,
        defaultValue = 0.65f,
        hint = "用角色内部的颜色替换边缘残留的背景色，消除白边或彩边",
        onValueChange = { value -> onParamsChange { it.copy(edgeDecontamination = value) } },
    )
    SliderRow(
        label = "描边收缩",
        value = params.edgeErode,
        range = 0f..1f,
        defaultValue = 0.2f,
        hint = "裁掉一圈原图的深色描边，避免出现白边或黑边",
        onValueChange = { value -> onParamsChange { it.copy(edgeErode = value) } },
    )
    SliderRow(
        label = "噪点匹配",
        value = params.noiseMatch,
        range = 0f..1f,
        defaultValue = 0.5f,
        hint = "把照片的噪点强度加到角色上，画面更统一",
        onValueChange = { value -> onParamsChange { it.copy(noiseMatch = value) } },
    )
    SliderRow(
        label = "清晰度匹配",
        value = params.sharpnessMatch,
        range = 0f..1f,
        defaultValue = 0.4f,
        onValueChange = { value -> onParamsChange { it.copy(sharpnessMatch = value) } },
    )
    SliderRow(
        label = "颗粒",
        value = params.grain,
        range = 0f..1f,
        defaultValue = 0.15f,
        onValueChange = { value -> onParamsChange { it.copy(grain = value) } },
    )
    SliderRow(
        label = "背景虚化",
        value = params.backgroundBlur,
        range = 0f..1f,
        defaultValue = 0f,
        hint = "模拟大光圈景深，让角色更突出",
        onValueChange = { value -> onParamsChange { it.copy(backgroundBlur = value) } },
    )
    SliderRow(
        label = "暗角",
        value = params.vignette,
        range = 0f..1f,
        defaultValue = 0f,
        onValueChange = { value -> onParamsChange { it.copy(vignette = value) } },
    )
    SliderRow(
        label = "色散",
        value = params.chromaticAberration,
        range = 0f..1f,
        defaultValue = 0f,
        onValueChange = { value -> onParamsChange { it.copy(chromaticAberration = value) } },
    )
    if (!state.foregroundHasAlpha) {
        SliderRow(
            label = "抠图容差",
            value = state.matting.tolerance,
            defaultValue = 0.45f,
            hint = "非透明底图片会自动抠图，容差决定保留多少与背景相近的颜色",
            onValueChange = { value -> onMattingChange { it.copy(tolerance = value) } },
        )
        SliderRow(
            label = "抠图羽化",
            value = state.matting.feather,
            defaultValue = 0.5f,
            onValueChange = { value -> onMattingChange { it.copy(feather = value) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    state: EditorState,
    settings: AppSettings = AppSettings(),
    onDismiss: () -> Unit,
    onExport: (ExportRequest) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sizes = remember { listOf(1280, 1920, 2560) }
    var sizeIndex by remember {
        mutableIntStateOf(sizes.indexOf(settings.exportMaxDimension).coerceAtLeast(0))
    }
    var format by remember { mutableStateOf(settings.exportFormat) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = "导出作品",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "长边 ${sizes[sizeIndex]} px · 融合会在该分辨率下重新计算一遍",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Text("分辨率", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sizes.forEachIndexed { index, size ->
                    ChoiceChip(
                        label = when (size) {
                            1280 -> "快速 1280"
                            1920 -> "推荐 1920"
                            else -> "高清 2560"
                        },
                        selected = sizeIndex == index,
                        onClick = { sizeIndex = index },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("格式", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(
                    label = "JPG 更小",
                    selected = format == ExportFormat.JPEG,
                    onClick = { format = ExportFormat.JPEG },
                )
                ChoiceChip(
                    label = "PNG 无损",
                    selected = format == ExportFormat.PNG,
                    onClick = { format = ExportFormat.PNG },
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onExport(
                            ExportRequest(
                                maxDimension = sizes[sizeIndex],
                                format = format,
                                target = ExportTarget.SHARE,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !state.exporting,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_share),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("分享")
                }
                Button(
                    onClick = {
                        onExport(
                            ExportRequest(
                                maxDimension = sizes[sizeIndex],
                                format = format,
                                target = ExportTarget.GALLERY,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1.4f),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !state.exporting,
                ) {
                    Text(
                        text = if (state.exporting) state.stage.ifEmpty { "导出中…" } else "保存到相册",
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
