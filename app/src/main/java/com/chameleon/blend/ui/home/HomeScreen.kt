package com.chameleon.blend.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chameleon.blend.R
import com.chameleon.blend.data.BitmapIo
import com.chameleon.blend.data.RecentProject
import com.chameleon.blend.ui.components.ChoiceChip
import com.chameleon.blend.ui.components.EmptySlotHint
import com.chameleon.blend.ui.components.SectionCard
import com.chameleon.blend.ui.components.SlotSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    foregroundThumb: androidx.compose.ui.graphics.ImageBitmap?,
    backgroundThumb: androidx.compose.ui.graphics.ImageBitmap?,
    recents: List<RecentProject>,
    hasBothImages: Boolean,
    onPickForeground: () -> Unit,
    onPickBackground: () -> Unit,
    onClearForeground: () -> Unit,
    onClearBackground: () -> Unit,
    onStart: () -> Unit,
    onOpenRecent: (RecentProject) -> Unit,
    onDeleteRecent: (RecentProject) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Chameleon",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_tune),
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "溶图 · 把二次元角色自然放进现实照片：先匹配光线方向与色温，再生成投影、环境光与噪点。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UploadSlot(
                title = "角色图片",
                hint = "PNG 透明底最佳",
                thumb = foregroundThumb,
                iconRes = R.drawable.ic_character,
                modifier = Modifier.weight(1f),
                onPick = onPickForeground,
                onClear = onClearForeground,
            )
            UploadSlot(
                title = "现实照片",
                hint = "作为场景背景",
                thumb = backgroundThumb,
                iconRes = R.drawable.ic_image,
                modifier = Modifier.weight(1f),
                onPick = onPickBackground,
                onClear = onClearBackground,
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onStart,
            enabled = hasBothImages,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_sparkle),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("开始融合", style = MaterialTheme.typography.titleMedium)
        }
        if (!hasBothImages) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "选好两张图片后即可进入编辑器",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(22.dp))
        SectionCard(
            title = "自动完成的事",
            subtitle = "进入编辑器后会先做一次分析，你也可以随时手动微调",
            icon = painterResource(R.drawable.ic_tune),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(label = "光源方向", selected = true, onClick = {}, enabled = false)
                ChoiceChip(label = "色彩迁移", selected = true, onClick = {}, enabled = false)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(label = "接触投影", selected = true, onClick = {}, enabled = false)
                ChoiceChip(label = "噪点统一", selected = true, onClick = {}, enabled = false)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "透明底 PNG 效果最好；如果是白底 JPG，App 会自动尝试抠图，并在「细节」里提供容差调整。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text(
                text = "最近作品",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recents, key = { it.id }) { project ->
                    RecentCard(
                        project = project,
                        onOpen = { onOpenRecent(project) },
                        onDelete = { onDeleteRecent(project) },
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionCard(
            title = "上手建议",
            subtitle = "三个细节决定真实感",
            icon = painterResource(R.drawable.ic_light),
        ) {
            TipLine("1", "角色的脚要压在照片的地面线上，投影才会自然。")
            TipLine("2", "光向要和照片里的高光一致：夜景霓虹用侧逆光更带感。")
            TipLine("3", "先调色彩融合，再调噪点与颗粒，最后统一色调。")
        }
        Spacer(Modifier.height(36.dp))
    }
}

@Composable
private fun UploadSlot(
    title: String,
    hint: String,
    thumb: androidx.compose.ui.graphics.ImageBitmap?,
    iconRes: Int,
    modifier: Modifier = Modifier,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = modifier) {
        Box {
            SlotSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
                    .clickable { onPick() },
                selected = thumb != null,
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    EmptySlotHint(
                        icon = painterResource(iconRes),
                        title = "点击选择",
                        subtitle = hint,
                    )
                }
            }
            if (thumb != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "移除",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (thumb == null) hint else "更换",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .padding(2.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            Button(
                onClick = onPick,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.filledTonalButtonColors(),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    text = if (thumb == null) "选择图片" else "重新选择",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun RecentCard(
    project: RecentProject,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, project.result) {
        value = withContext(Dispatchers.IO) {
            BitmapIo.decodeFile(project.result, 320)?.asImageBitmap()
        }
    }
    Column(
        modifier = Modifier.width(132.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
                .clip(RoundedCornerShape(16.dp))
                .clickable { onOpen() },
        ) {
            SlotSurface(modifier = Modifier.fillMaxSize()) {
                val image = bitmap
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = project.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = project.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TipLine(index: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = index,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
