package com.chameleon.blend.data

import com.chameleon.blend.core.blend.BlendParams
import com.chameleon.blend.core.blend.BlendStyle
import com.chameleon.blend.core.blend.MattingOptions
import org.json.JSONObject

/**
 * Serialises the whole parameter set so a saved work can be reopened exactly as it was left.
 * Missing keys fall back to the style preset, which keeps older saves loadable.
 */
object BlendParamsJson {

    fun encode(params: BlendParams): String = JSONObject().apply {
        put("heightFraction", params.heightFraction)
        put("offsetX", params.offsetX)
        put("offsetY", params.offsetY)
        put("rotationDeg", params.rotationDeg)
        put("flipHorizontal", params.flipHorizontal)
        put("lightAzimuthDeg", params.lightAzimuthDeg)
        put("lightElevationDeg", params.lightElevationDeg)
        put("sceneLightStrength", params.sceneLightStrength)
        put("shadingConsistency", params.shadingConsistency)
        put("shadowStrength", params.shadowStrength)
        put("shadowSoftness", params.shadowSoftness)
        put("shadowLength", params.shadowLength)
        put("contactShadow", params.contactShadow)
        put("ambientOcclusion", params.ambientOcclusion)
        put("rimLight", params.rimLight)
        put("bounceLight", params.bounceLight)
        put("colorMatch", params.colorMatch)
        put("contrastMatch", params.contrastMatch)
        put("chromaMatch", params.chromaMatch)
        put("whiteBalanceMatch", params.whiteBalanceMatch)
        put("toneMatch", params.toneMatch)
        put("highlightMatch", params.highlightMatch)
        put("exposure", params.exposure)
        put("contrast", params.contrast)
        put("saturation", params.saturation)
        put("temperature", params.temperature)
        put("tint", params.tint)
        put("edgeFeather", params.edgeFeather)
        put("edgeErode", params.edgeErode)
        put("noiseMatch", params.noiseMatch)
        put("sharpnessMatch", params.sharpnessMatch)
        put("edgeDecontamination", params.edgeDecontamination)
        put("grain", params.grain)
        put("backgroundBlur", params.backgroundBlur)
        put("vignette", params.vignette)
        put("chromaticAberration", params.chromaticAberration)
        put("style", params.style.name)
    }.toString()

    fun decode(text: String): BlendParams = runCatching { decode(JSONObject(text)) }
        .getOrElse { BlendParams() }

    fun decode(json: JSONObject): BlendParams {
        val style = runCatching { BlendStyle.valueOf(json.optString("style", BlendStyle.NATURAL.name)) }
            .getOrDefault(BlendStyle.NATURAL)
        val base = BlendParams.of(style)
        return base.copy(
            heightFraction = json.float("heightFraction", base.heightFraction),
            offsetX = json.float("offsetX", base.offsetX),
            offsetY = json.float("offsetY", base.offsetY),
            rotationDeg = json.float("rotationDeg", base.rotationDeg),
            flipHorizontal = json.optBoolean("flipHorizontal", base.flipHorizontal),
            lightAzimuthDeg = json.float("lightAzimuthDeg", base.lightAzimuthDeg),
            lightElevationDeg = json.float("lightElevationDeg", base.lightElevationDeg),
            sceneLightStrength = json.float("sceneLightStrength", base.sceneLightStrength),
            shadingConsistency = json.float("shadingConsistency", base.shadingConsistency),
            shadowStrength = json.float("shadowStrength", base.shadowStrength),
            shadowSoftness = json.float("shadowSoftness", base.shadowSoftness),
            shadowLength = json.float("shadowLength", base.shadowLength),
            contactShadow = json.float("contactShadow", base.contactShadow),
            ambientOcclusion = json.float("ambientOcclusion", base.ambientOcclusion),
            rimLight = json.float("rimLight", base.rimLight),
            bounceLight = json.float("bounceLight", base.bounceLight),
            colorMatch = json.float("colorMatch", base.colorMatch),
            contrastMatch = json.float("contrastMatch", base.contrastMatch),
            chromaMatch = json.float("chromaMatch", base.chromaMatch),
            whiteBalanceMatch = json.float("whiteBalanceMatch", base.whiteBalanceMatch),
            toneMatch = json.float("toneMatch", base.toneMatch),
            highlightMatch = json.float("highlightMatch", base.highlightMatch),
            exposure = json.float("exposure", base.exposure),
            contrast = json.float("contrast", base.contrast),
            saturation = json.float("saturation", base.saturation),
            temperature = json.float("temperature", base.temperature),
            tint = json.float("tint", base.tint),
            edgeFeather = json.float("edgeFeather", base.edgeFeather),
            edgeErode = json.float("edgeErode", base.edgeErode),
            noiseMatch = json.float("noiseMatch", base.noiseMatch),
            sharpnessMatch = json.float("sharpnessMatch", base.sharpnessMatch),
            edgeDecontamination = json.float("edgeDecontamination", base.edgeDecontamination),
            grain = json.float("grain", base.grain),
            backgroundBlur = json.float("backgroundBlur", base.backgroundBlur),
            vignette = json.float("vignette", base.vignette),
            chromaticAberration = json.float("chromaticAberration", base.chromaticAberration),
        )
    }

    fun encodeMatting(options: MattingOptions): JSONObject = JSONObject().apply {
        put("enabled", options.enabled)
        put("tolerance", options.tolerance)
        put("feather", options.feather)
        put("protectEnclosedRegions", options.protectEnclosedRegions)
    }

    fun decodeMatting(json: JSONObject?): MattingOptions {
        if (json == null) return MattingOptions()
        val base = MattingOptions()
        return base.copy(
            enabled = json.optBoolean("enabled", base.enabled),
            tolerance = json.float("tolerance", base.tolerance),
            feather = json.float("feather", base.feather),
            protectEnclosedRegions = json.optBoolean(
                "protectEnclosedRegions",
                base.protectEnclosedRegions,
            ),
        )
    }

    private fun JSONObject.float(key: String, fallback: Float): Float =
        optDouble(key, fallback.toDouble()).toFloat()
}
