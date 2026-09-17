package com.chameleon.blend.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.chameleon.blend.core.img.RasterImage
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

enum class ExportFormat(val mime: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
}

/** Where an exported image ended up. */
sealed interface SaveResult {
    data class Gallery(val uri: Uri) : SaveResult
    data class AppStorage(val file: File) : SaveResult
    data class Failed(val reason: String) : SaveResult
}

/**
 * Bitmap <-> engine bridge and the platform side of import/export.
 *
 * Decoding always targets sRGB so the colour maths in the engine sees predictable values, and always
 * uses a software allocation so the pixels can be read back for processing.
 */
object BitmapIo {

    fun decode(context: Context, uri: Uri, maxDimension: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        decodeSource(source, maxDimension)
    } catch (t: Throwable) {
        null
    }

    fun decodeFile(file: File, maxDimension: Int): Bitmap? = try {
        decodeSource(ImageDecoder.createSource(file), maxDimension)
    } catch (t: Throwable) {
        null
    }

    private fun decodeSource(source: ImageDecoder.Source, maxDimension: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val size = info.size
            val longest = max(size.width, size.height)
            if (longest > maxDimension) {
                val scale = maxDimension.toFloat() / longest
                decoder.setTargetSize(
                    max(1, (size.width * scale).toInt()),
                    max(1, (size.height * scale).toInt()),
                )
            }
        }

    fun toRaster(bitmap: Bitmap): RasterImage {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return RasterImage.fromArgb(pixels, width, height)
    }

    fun toBitmap(raster: RasterImage): Bitmap =
        Bitmap.createBitmap(raster.toArgb(), raster.width, raster.height, Bitmap.Config.ARGB_8888)

    /** Saves into the shared Pictures collection. Returns the public uri. */
    fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        format: ExportFormat = ExportFormat.JPEG,
        quality: Int = 95,
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.${format.extension}")
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Chameleon")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { stream ->
                val compressFormat = if (format == ExportFormat.PNG) {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                bitmap.compress(compressFormat, quality, stream)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Downscales while keeping the aspect ratio; returns the input when it already fits. */
    fun scaledDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Saves through MediaStore and falls back to the app-specific Pictures folder when the media
     * provider refuses the insert (restricted profiles, revoked MediaProvider, full volume).
     */
    fun saveImage(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        format: ExportFormat = ExportFormat.JPEG,
        quality: Int = 95,
    ): SaveResult {
        val galleryUri = runCatching {
            saveToGallery(context, bitmap, displayName, format, quality)
        }.getOrNull()
        if (galleryUri != null) return SaveResult.Gallery(galleryUri)

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "Pictures")
        val file = File(dir, "$displayName.${format.extension}")
        return if (writeToFile(file, bitmap, format, quality)) {
            SaveResult.AppStorage(file)
        } else {
            SaveResult.Failed("无法写入存储空间")
        }
    }

    /** Writes a temporary copy in the cache so other apps can receive it through a content uri. */
    fun shareCacheFile(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        format: ExportFormat = ExportFormat.JPEG,
        quality: Int = 95,
    ): Pair<Uri, File>? {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { stale ->
            if (System.currentTimeMillis() - stale.lastModified() > 3_600_000L) stale.delete()
        }
        val file = File(dir, "$displayName.${format.extension}")
        return try {
            FileOutputStream(file).use { stream ->
                val compressFormat = if (format == ExportFormat.PNG) {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                bitmap.compress(compressFormat, quality, stream)
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            uri to file
        } catch (t: Throwable) {
            null
        }
    }

    fun writeToFile(file: File, bitmap: Bitmap, format: ExportFormat = ExportFormat.JPEG, quality: Int = 92): Boolean =
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { stream ->
                val compressFormat = if (format == ExportFormat.PNG) {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                bitmap.compress(compressFormat, quality, stream)
            }
        } catch (t: Throwable) {
            false
        }
}
