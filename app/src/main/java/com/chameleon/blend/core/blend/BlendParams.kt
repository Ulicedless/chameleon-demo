package com.chameleon.blend.core.blend

import kotlin.math.cos
import kotlin.math.sin

/** High level look presets shown as chips in the editor. */
enum class BlendStyle(val label: String, val hint: String) {
    NATURAL("自然", "自适应光影，最稳妥的默认值"),
    STUDIO("影棚", "柔和棚拍光，干净色彩"),
    DAYLIGHT("日光", "强烈方向光，清晰投影"),
    GOLDEN("黄昏", "暖色调、长投影"),
    NIGHT("夜景", "霓虹补光、高噪点匹配"),
    BACKLIT("逆光", "强轮廓光，弱投影"),
    ILLUSTRATION("插画", "保留二次元色感，弱融合"),
    FILM("胶片", "颗粒、暗角、低饱和"),
}

enum class SceneTime { DAY, GOLDEN, NIGHT, INDOOR }

/**
 * Every knob the engine understands. Values are normalised so the UI can bind them directly to
 * sliders, and so a preset is a plain copy of this object.
 */
data class BlendParams(
    // ---- placement ----
    val heightFraction: Float = 0.62f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDeg: Float = 0f,
    val flipHorizontal: Boolean = false,

    // ---- lighting ----
    val lightAzimuthDeg: Float = 130f,
    val lightElevationDeg: Float = 42f,
    val sceneLightStrength: Float = 0.75f,
    val shadingConsistency: Float = 0.45f,
    val shadowStrength: Float = 0.55f,
    val shadowSoftness: Float = 0.55f,
    val shadowLength: Float = 0.5f,
    val contactShadow: Float = 0.6f,
    val ambientOcclusion: Float = 0.45f,
    val rimLight: Float = 0.35f,
    val bounceLight: Float = 0.25f,

    // ---- colour ----
    val colorMatch: Float = 0.7f,
    val contrastMatch: Float = 0.6f,
    val chromaMatch: Float = 0.5f,
    val whiteBalanceMatch: Float = 0.6f,
    val toneMatch: Float = 0.5f,
    /** Tints the character's highlights with the scene key light and its shadows with ambient light. */
    val highlightMatch: Float = 0.55f,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,

    // ---- integration ----
    val edgeFeather: Float = 0.35f,
    val edgeErode: Float = 0.2f,
    val noiseMatch: Float = 0.5f,
    val sharpnessMatch: Float = 0.4f,
    /** Replaces contaminated edge colours with colours extrapolated from the artwork interior. */
    val edgeDecontamination: Float = 0.65f,
    val grain: Float = 0.15f,
    val backgroundBlur: Float = 0f,
    val vignette: Float = 0f,
    val chromaticAberration: Float = 0f,

    val style: BlendStyle = BlendStyle.NATURAL,
) {
    /** Screen space unit vector pointing towards the light source. Y grows downwards. */
    val lightX: Float get() = cos(Math.toRadians(lightAzimuthDeg.toDouble())).toFloat()
    val lightY: Float get() = -sin(Math.toRadians(lightAzimuthDeg.toDouble())).toFloat()

    /** 1 when the light is overhead, near 0 when it grazes the scene. */
    val lightHeight: Float
        get() = sin(Math.toRadians(lightElevationDeg.coerceIn(2f, 90f).toDouble())).toFloat()

    companion object {
        fun of(style: BlendStyle): BlendParams = when (style) {
            BlendStyle.NATURAL -> BlendParams(
                style = BlendStyle.NATURAL,
                sceneLightStrength = 0.72f,
                shadingConsistency = 0.42f,
                shadowStrength = 0.5f,
                shadowSoftness = 0.55f,
                shadowLength = 0.45f,
                contactShadow = 0.6f,
                ambientOcclusion = 0.45f,
                rimLight = 0.3f,
                bounceLight = 0.25f,
                colorMatch = 0.7f,
                contrastMatch = 0.55f,
                chromaMatch = 0.5f,
                whiteBalanceMatch = 0.6f,
                toneMatch = 0.5f,
                highlightMatch = 0.55f,
                noiseMatch = 0.5f,
                sharpnessMatch = 0.4f,
                edgeDecontamination = 0.65f,
                grain = 0.12f,
            )

            BlendStyle.STUDIO -> BlendParams(
                style = BlendStyle.STUDIO,
                lightAzimuthDeg = 145f,
                lightElevationDeg = 55f,
                sceneLightStrength = 0.5f,
                shadingConsistency = 0.3f,
                shadowStrength = 0.35f,
                shadowSoftness = 0.8f,
                shadowLength = 0.3f,
                contactShadow = 0.7f,
                ambientOcclusion = 0.35f,
                rimLight = 0.2f,
                bounceLight = 0.2f,
                colorMatch = 0.75f,
                contrastMatch = 0.6f,
                chromaMatch = 0.55f,
                whiteBalanceMatch = 0.7f,
                toneMatch = 0.6f,
                highlightMatch = 0.6f,
                noiseMatch = 0.25f,
                sharpnessMatch = 0.5f,
                edgeDecontamination = 0.7f,
                grain = 0.05f,
                backgroundBlur = 0.15f,
            )

            BlendStyle.DAYLIGHT -> BlendParams(
                style = BlendStyle.DAYLIGHT,
                lightAzimuthDeg = 115f,
                lightElevationDeg = 38f,
                sceneLightStrength = 0.85f,
                shadingConsistency = 0.6f,
                shadowStrength = 0.7f,
                shadowSoftness = 0.35f,
                shadowLength = 0.7f,
                contactShadow = 0.75f,
                ambientOcclusion = 0.5f,
                rimLight = 0.35f,
                bounceLight = 0.3f,
                colorMatch = 0.8f,
                contrastMatch = 0.7f,
                chromaMatch = 0.6f,
                whiteBalanceMatch = 0.7f,
                toneMatch = 0.6f,
                highlightMatch = 0.7f,
                noiseMatch = 0.45f,
                sharpnessMatch = 0.45f,
                edgeDecontamination = 0.7f,
                grain = 0.08f,
            )

            BlendStyle.GOLDEN -> BlendParams(
                style = BlendStyle.GOLDEN,
                lightAzimuthDeg = 165f,
                lightElevationDeg = 18f,
                sceneLightStrength = 0.9f,
                shadingConsistency = 0.6f,
                shadowStrength = 0.6f,
                shadowSoftness = 0.45f,
                shadowLength = 0.95f,
                contactShadow = 0.65f,
                ambientOcclusion = 0.45f,
                rimLight = 0.55f,
                bounceLight = 0.35f,
                colorMatch = 0.8f,
                contrastMatch = 0.65f,
                chromaMatch = 0.6f,
                whiteBalanceMatch = 0.65f,
                toneMatch = 0.6f,
                highlightMatch = 0.85f,
                temperature = 0.18f,
                noiseMatch = 0.4f,
                sharpnessMatch = 0.4f,
                edgeDecontamination = 0.7f,
                grain = 0.12f,
                vignette = 0.15f,
            )

            BlendStyle.NIGHT -> BlendParams(
                style = BlendStyle.NIGHT,
                lightAzimuthDeg = 200f,
                lightElevationDeg = 25f,
                sceneLightStrength = 1f,
                shadingConsistency = 0.7f,
                shadowStrength = 0.35f,
                shadowSoftness = 0.6f,
                shadowLength = 0.5f,
                contactShadow = 0.5f,
                ambientOcclusion = 0.55f,
                rimLight = 0.8f,
                bounceLight = 0.4f,
                colorMatch = 0.85f,
                contrastMatch = 0.75f,
                chromaMatch = 0.7f,
                whiteBalanceMatch = 0.75f,
                toneMatch = 0.65f,
                highlightMatch = 0.9f,
                exposure = -0.05f,
                temperature = -0.12f,
                noiseMatch = 0.75f,
                sharpnessMatch = 0.35f,
                edgeDecontamination = 0.7f,
                grain = 0.25f,
                vignette = 0.2f,
            )

            BlendStyle.BACKLIT -> BlendParams(
                style = BlendStyle.BACKLIT,
                lightAzimuthDeg = 75f,
                lightElevationDeg = 20f,
                sceneLightStrength = 0.9f,
                shadingConsistency = 0.55f,
                shadowStrength = 0.3f,
                shadowSoftness = 0.7f,
                shadowLength = 0.4f,
                contactShadow = 0.45f,
                ambientOcclusion = 0.35f,
                rimLight = 1f,
                bounceLight = 0.3f,
                colorMatch = 0.7f,
                contrastMatch = 0.5f,
                chromaMatch = 0.45f,
                whiteBalanceMatch = 0.6f,
                toneMatch = 0.5f,
                highlightMatch = 0.75f,
                exposure = -0.05f,
                noiseMatch = 0.45f,
                sharpnessMatch = 0.35f,
                edgeDecontamination = 0.7f,
                grain = 0.12f,
            )

            BlendStyle.ILLUSTRATION -> BlendParams(
                style = BlendStyle.ILLUSTRATION,
                sceneLightStrength = 0.35f,
                shadingConsistency = 0.2f,
                shadowStrength = 0.35f,
                shadowSoftness = 0.75f,
                shadowLength = 0.3f,
                contactShadow = 0.45f,
                ambientOcclusion = 0.25f,
                rimLight = 0.15f,
                bounceLight = 0.15f,
                colorMatch = 0.35f,
                contrastMatch = 0.3f,
                chromaMatch = 0.2f,
                whiteBalanceMatch = 0.35f,
                toneMatch = 0.25f,
                highlightMatch = 0.25f,
                noiseMatch = 0.1f,
                sharpnessMatch = 0.15f,
                edgeDecontamination = 0.6f,
                grain = 0f,
            )

            BlendStyle.FILM -> BlendParams(
                style = BlendStyle.FILM,
                lightAzimuthDeg = 150f,
                lightElevationDeg = 35f,
                sceneLightStrength = 0.7f,
                shadingConsistency = 0.45f,
                shadowStrength = 0.5f,
                shadowSoftness = 0.6f,
                shadowLength = 0.5f,
                contactShadow = 0.6f,
                ambientOcclusion = 0.45f,
                rimLight = 0.3f,
                bounceLight = 0.3f,
                colorMatch = 0.75f,
                contrastMatch = 0.6f,
                chromaMatch = 0.5f,
                whiteBalanceMatch = 0.6f,
                toneMatch = 0.55f,
                highlightMatch = 0.5f,
                saturation = -0.12f,
                temperature = 0.1f,
                noiseMatch = 0.6f,
                sharpnessMatch = 0.3f,
                edgeDecontamination = 0.7f,
                grain = 0.45f,
                vignette = 0.3f,
                chromaticAberration = 0.2f,
            )
        }
    }
}
