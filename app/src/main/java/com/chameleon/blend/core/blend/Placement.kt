package com.chameleon.blend.core.blend

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Where the character lands on the background, in background pixel coordinates. */
data class Placement(
    val centerX: Float,
    val centerY: Float,
    val scale: Float,
    val rotationDeg: Float,
    val flip: Boolean,
) {
    val cosR: Float get() = cos(Math.toRadians(rotationDeg.toDouble())).toFloat()
    val sinR: Float get() = sin(Math.toRadians(rotationDeg.toDouble())).toFloat()
}

object PlacementSolver {

    /**
     * Fits the foreground so that its visible height equals [BlendParams.heightFraction] of the
     * background height, then offsets the centre by the user's drag values.
     */
    fun solve(
        bgWidth: Int,
        bgHeight: Int,
        fgWidth: Int,
        fgHeight: Int,
        params: BlendParams,
    ): Placement {
        val targetHeight = max(8f, bgHeight * params.heightFraction)
        val scale = targetHeight / max(1, fgHeight)
        val cx = bgWidth * (0.5f + params.offsetX)
        val cy = bgHeight * (0.5f + params.offsetY)
        return Placement(
            centerX = cx,
            centerY = cy,
            scale = scale,
            rotationDeg = params.rotationDeg,
            flip = params.flipHorizontal,
        )
    }

    /**
     * Chooses a starting height fraction from the aspect ratio of the cut-out. Full body art is
     * placed taller than a bust shot, which keeps the automatic composition believable.
     */
    fun suggestedHeightFraction(fgWidth: Int, fgHeight: Int, coverage: Float): Float {
        val aspect = fgHeight.toFloat() / max(1, fgWidth)
        val base = when {
            aspect >= 2.0f -> 0.74f
            aspect >= 1.5f -> 0.66f
            aspect >= 1.05f -> 0.52f
            aspect >= 0.8f -> 0.42f
            else -> 0.34f
        }
        // Wide subjects (landscapes, groups) should not dominate the frame.
        val coveragePenalty = if (coverage > 0.75f) 0.88f else 1f
        return (base * coveragePenalty).coerceIn(0.18f, 0.86f)
    }

    /**
     * Offsets that put the bottom of the subject on the estimated ground line, centred on the
     * rule-of-thirds column with more breathing room.
     */
    fun suggestedOffsets(
        groundY: Float,
        heightFraction: Float,
        busyLeft: Float,
        busyRight: Float,
    ): Pair<Float, Float> {
        val centerY = groundY - heightFraction / 2f
        val preferLeft = busyLeft <= busyRight
        val centerX = if (preferLeft) 0.38f else 0.62f
        val balance = abs(busyLeft - busyRight)
        val blendToCentre = (1f - balance.coerceIn(0f, 1f)) * 0.5f
        val offsetX = ((centerX - 0.5f) * (1f - blendToCentre)).coerceIn(-0.35f, 0.35f)
        val offsetY = (centerY - 0.5f).coerceIn(-0.35f, 0.35f)
        return offsetX to offsetY
    }
}
