package com.chameleon.blend.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.chameleon.blend.R
import com.chameleon.blend.core.blend.BlendStyle
import com.chameleon.blend.data.AppSettings
import com.chameleon.blend.data.ExportFormat
import com.chameleon.blend.data.PreviewQuality
import com.chameleon.blend.data.ThemeMode
import com.chameleon.blend.ui.components.ChoiceChip
import com.chameleon.blend.ui.components.SectionCard
import com.chameleon.blend.ui.components.SliderRow
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    cacheSizeLabel: String,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onClearCache: () -> Unit,
    onClearProjects: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = "返回",
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
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            SectionCard(title = "外观", icon = painterResource(R.drawable.ic_palette)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        ChoiceChip(
                            label = mode.label,
                            selected = settings.themeMode == mode,
                            onClick = { onUpdate { it.copy(themeMode = mode) } },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    title = "动态取色",
                    subtitle = "使用系统壁纸的配色（Android 12+）",
                    checked = settings.dynamicColor,
                    onCheckedChange = { value -> onUpdate { it.copy(dynamicColor = value) } },
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionCard(
                title = "融合偏好",
                subtitle = "影响每次自动融合的默认力度",
                icon = painterResource(R.drawable.ic_sparkle),
            ) {
                SwitchRow(
                    title = "自动识别场景风格",
                    subtitle = "根据照片的时段与对比度自动挑选风格",
                    checked = settings.autoStyleByScene,
                    onCheckedChange = { value -> onUpdate { it.copy(autoStyleByScene = value) } },
                )
                if (!settings.autoStyleByScene) {
                    Spacer(Modifier.height(8.dp))
                    Text("默认风格", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    BlendStyle.entries.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { style ->
                                ChoiceChip(
                                    label = style.label,
                                    selected = settings.defaultStyle == style,
                                    onClick = { onUpdate { it.copy(defaultStyle = style) } },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                SliderRow(
                    label = "融合强度",
                    value = settings.blendIntensity,
                    range = 0.4f..1.4f,
                    defaultValue = 1f,
                    hint = "整体放大或收敛色彩、光影与投影的自动强度",
                    format = { "${(it * 100).roundToInt()}%" },
                    onValueChange = { value -> onUpdate { it.copy(blendIntensity = value) } },
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionCard(
                title = "预览质量",
                subtitle = "Tips:分辨率越高越接近成片，也越慢",
                icon = painterResource(R.drawable.ic_image),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewQuality.entries.forEach { quality ->
                        ChoiceChip(
                            label = "${quality.label} ${quality.maxDimension}",
                            selected = settings.previewQuality == quality,
                            onClick = { onUpdate { it.copy(previewQuality = quality) } },
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionCard(
                title = "导出默认值",
                subtitle = "导出面板会从这里取初始值",
                icon = painterResource(R.drawable.ic_save),
            ) {
                Text("分辨率", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1280, 1920, 2560).forEach { size ->
                        ChoiceChip(
                            label = when (size) {
                                1280 -> "快速 1280"
                                1920 -> "推荐 1920"
                                else -> "高清 2560"
                            },
                            selected = settings.exportMaxDimension == size,
                            onClick = { onUpdate { it.copy(exportMaxDimension = size) } },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("格式", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(
                        label = "JPG",
                        selected = settings.exportFormat == ExportFormat.JPEG,
                        onClick = { onUpdate { it.copy(exportFormat = ExportFormat.JPEG) } },
                    )
                    ChoiceChip(
                        label = "PNG",
                        selected = settings.exportFormat == ExportFormat.PNG,
                        onClick = { onUpdate { it.copy(exportFormat = ExportFormat.PNG) } },
                    )
                }
                SliderRow(
                    label = "JPG 质量",
                    value = settings.exportQuality / 100f,
                    range = 0.6f..1f,
                    defaultValue = 0.95f,
                    format = { "${(it * 100).roundToInt()}" },
                    onValueChange = { value ->
                        onUpdate { it.copy(exportQuality = (value * 100).roundToInt()) }
                    },
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionCard(
                title = "抠图",
                subtitle = "非透明底图片会先做一次自动抠图",
                icon = painterResource(R.drawable.ic_character),
            ) {
                SwitchRow(
                    title = "自动抠图",
                    subtitle = "关闭后整张图都会被当成角色",
                    checked = settings.autoMatting,
                    onCheckedChange = { value -> onUpdate { it.copy(autoMatting = value) } },
                )
                SliderRow(
                    label = "默认容差",
                    value = settings.mattingTolerance,
                    defaultValue = 0.45f,
                    onValueChange = { value -> onUpdate { it.copy(mattingTolerance = value) } },
                )
                SliderRow(
                    label = "默认羽化",
                    value = settings.mattingFeather,
                    defaultValue = 0.5f,
                    onValueChange = { value -> onUpdate { it.copy(mattingFeather = value) } },
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionCard(
                title = "作品与缓存",
                icon = painterResource(R.drawable.ic_layers),
            ) {
                SwitchRow(
                    title = "保留最近作品",
                    subtitle = "导出后保存原图与参数，可再次编辑",
                    checked = settings.keepProjectHistory,
                    onCheckedChange = { value -> onUpdate { it.copy(keepProjectHistory = value) } },
                )
                SwitchRow(
                    title = "显示构图参考线",
                    subtitle = "融合过程中显示三分线与地面参考",
                    checked = settings.showCompositionGuides,
                    onCheckedChange = { value ->
                        onUpdate { it.copy(showCompositionGuides = value) }
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "图片缓存：$cacheSizeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onClearCache) { Text("清理图片缓存") }
                    OutlinedButton(onClick = onClearProjects) { Text("清空最近作品") }
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionCard(
                title = "关于 Chameleon",
                subtitle = "版本 1.1",
                icon = painterResource(R.drawable.ic_light),
            ) {
                Text(
                    text = "所有处理都在本机完成，不上传任何图片，也不需要存储权限：" +
                        "Android 10 及以上通过系统媒体库写入相册。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
