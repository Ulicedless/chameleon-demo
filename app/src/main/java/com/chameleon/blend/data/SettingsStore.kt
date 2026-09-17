package com.chameleon.blend.data

import android.content.Context
import androidx.core.content.edit
import com.chameleon.blend.core.blend.BlendStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists [AppSettings] and exposes them as a flow so the theme can react immediately. */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("chameleon_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun update(block: (AppSettings) -> AppSettings) {
        val updated = block(_state.value)
        if (updated == _state.value) return
        write(updated)
        _state.value = updated
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = prefs.enumValue("themeMode", ThemeMode.entries, defaults.themeMode),
            dynamicColor = prefs.getBoolean("dynamicColor", defaults.dynamicColor),
            autoStyleByScene = prefs.getBoolean("autoStyleByScene", defaults.autoStyleByScene),
            defaultStyle = prefs.enumValue("defaultStyle", BlendStyle.entries, defaults.defaultStyle),
            previewQuality = prefs.enumValue(
                "previewQuality",
                PreviewQuality.entries,
                defaults.previewQuality,
            ),
            blendIntensity = prefs.getFloat("blendIntensity", defaults.blendIntensity),
            exportMaxDimension = prefs.getInt("exportMaxDimension", defaults.exportMaxDimension),
            exportFormat = prefs.enumValue("exportFormat", ExportFormat.entries, defaults.exportFormat),
            exportQuality = prefs.getInt("exportQuality", defaults.exportQuality),
            autoMatting = prefs.getBoolean("autoMatting", defaults.autoMatting),
            mattingTolerance = prefs.getFloat("mattingTolerance", defaults.mattingTolerance),
            mattingFeather = prefs.getFloat("mattingFeather", defaults.mattingFeather),
            keepProjectHistory = prefs.getBoolean("keepProjectHistory", defaults.keepProjectHistory),
            showCompositionGuides = prefs.getBoolean(
                "showCompositionGuides",
                defaults.showCompositionGuides,
            ),
        )
    }

    private fun write(settings: AppSettings) {
        prefs.edit {
            putString("themeMode", settings.themeMode.name)
            putBoolean("dynamicColor", settings.dynamicColor)
            putBoolean("autoStyleByScene", settings.autoStyleByScene)
            putString("defaultStyle", settings.defaultStyle.name)
            putString("previewQuality", settings.previewQuality.name)
            putFloat("blendIntensity", settings.blendIntensity)
            putInt("exportMaxDimension", settings.exportMaxDimension)
            putString("exportFormat", settings.exportFormat.name)
            putInt("exportQuality", settings.exportQuality)
            putBoolean("autoMatting", settings.autoMatting)
            putFloat("mattingTolerance", settings.mattingTolerance)
            putFloat("mattingFeather", settings.mattingFeather)
            putBoolean("keepProjectHistory", settings.keepProjectHistory)
            putBoolean("showCompositionGuides", settings.showCompositionGuides)
        }
    }

    private fun <T : Enum<T>> android.content.SharedPreferences.enumValue(
        key: String,
        values: List<T>,
        fallback: T,
    ): T {
        val raw = getString(key, null) ?: return fallback
        return values.firstOrNull { it.name == raw } ?: fallback
    }
}
