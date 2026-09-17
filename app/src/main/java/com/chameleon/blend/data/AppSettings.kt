package com.chameleon.blend.data

import com.chameleon.blend.core.blend.BlendStyle

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

enum class PreviewQuality(val label: String, val maxDimension: Int) {
    FAST("流畅", 720),
    BALANCED("均衡", 1024),
    FINE("精细", 1280),
}

/**
 * User preferences. Everything the editor uses as a starting point lives here so the app behaves
 * the way the user configured it, on every new project.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val autoStyleByScene: Boolean = true,
    val defaultStyle: BlendStyle = BlendStyle.NATURAL,
    val previewQuality: PreviewQuality = PreviewQuality.BALANCED,
    /** Master multiplier applied on top of every automatic strength (0.4 .. 1.4). */
    val blendIntensity: Float = 1f,
    val exportMaxDimension: Int = 1920,
    val exportFormat: ExportFormat = ExportFormat.JPEG,
    val exportQuality: Int = 95,
    val autoMatting: Boolean = true,
    val mattingTolerance: Float = 0.45f,
    val mattingFeather: Float = 0.5f,
    val keepProjectHistory: Boolean = true,
    val showCompositionGuides: Boolean = true,
)
