package com.chameleon.blend.core.img

/**
 * Plain, Android-free image buffer used by the blending engine.
 *
 * Channels are stored as separate float planes in sRGB space (0..1). Keeping the engine free of
 * [android.graphics.Bitmap] means the whole pipeline can be unit tested on the JVM and reused by a
 * future GPU/renderscript backend without touching the maths.
 */
class RasterImage(
    val width: Int,
    val height: Int,
    val r: FloatArray = FloatArray(width * height),
    val g: FloatArray = FloatArray(width * height),
    val b: FloatArray = FloatArray(width * height),
    val a: FloatArray = FloatArray(width * height) { 1f },
) {
    val size: Int get() = width * height

    init {
        require(width > 0 && height > 0) { "RasterImage needs a positive size" }
        require(r.size == size && g.size == size && b.size == size && a.size == size) {
            "Plane size mismatch: expected $size"
        }
    }

    fun index(x: Int, y: Int): Int = y * width + x

    fun copy(): RasterImage = RasterImage(
        width = width,
        height = height,
        r = r.copyOf(),
        g = g.copyOf(),
        b = b.copyOf(),
        a = a.copyOf(),
    )

    /** Copies colour channels but replaces alpha, used when the matting result is cached separately. */
    fun withAlpha(alpha: FloatArray): RasterImage = RasterImage(width, height, r, g, b, alpha)

    fun sampleNearest(x: Int, y: Int, out: FloatArray) {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        val i = cy * width + cx
        out[0] = r[i]
        out[1] = g[i]
        out[2] = b[i]
        out[3] = a[i]
    }

    companion object {
        /** Builds a buffer from packed 0xAARRGGBB pixels. */
        fun fromArgb(pixels: IntArray, width: Int, height: Int): RasterImage {
            require(pixels.size >= width * height) { "Pixel array too small" }
            val img = RasterImage(width, height)
            for (i in 0 until width * height) {
                val c = pixels[i]
                img.r[i] = ((c ushr 16) and 0xFF) / 255f
                img.g[i] = ((c ushr 8) and 0xFF) / 255f
                img.b[i] = (c and 0xFF) / 255f
                img.a[i] = ((c ushr 24) and 0xFF) / 255f
            }
            return img
        }
    }

    fun toArgb(out: IntArray = IntArray(size)): IntArray {
        for (i in 0 until size) {
            val ia = (a[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val ir = (r[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val ig = (g[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val ib = (b[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            out[i] = (ia shl 24) or (ir shl 16) or (ig shl 8) or ib
        }
        return out
    }
}
