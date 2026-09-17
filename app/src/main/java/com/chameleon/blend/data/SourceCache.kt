package com.chameleon.blend.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File

/**
 * Keeps a private copy of every image the user picks.
 *
 * Document and photo-picker grants are tied to the app session: after the process is restarted the
 * uri can no longer be decoded, which is exactly the failure mode where the first export works and
 * every later one reports "导出失败". Owning the bytes removes that whole class of problem, and it
 * also lets the export path read a predictable, size-bounded file instead of a 108 MP original.
 */
class SourceCache(context: Context) {

    private val appContext: Context = context.applicationContext

    private val root: File get() = File(appContext.filesDir, "sources").apply { mkdirs() }

    enum class Role(val fileName: String) {
        FOREGROUND("foreground.png"),
        BACKGROUND("background.png"),
    }

    fun fileFor(role: Role): File = File(root, role.fileName)

    fun existing(role: Role): File? = fileFor(role).takeIf { it.exists() && it.length() > 0 }

    /** Decodes [uri] (downscaled to [maxDimension]) and stores a private PNG copy. */
    fun capture(context: Context, role: Role, uri: Uri, maxDimension: Int = MAX_DIMENSION): File? {
        val bitmap = BitmapIo.decode(context, uri, maxDimension) ?: return null
        return capture(role, bitmap)
    }

    fun capture(role: Role, bitmap: Bitmap): File? {
        val target = fileFor(role)
        val temp = File(root, role.fileName + ".tmp")
        val ok = BitmapIo.writeToFile(temp, bitmap, ExportFormat.PNG)
        if (!ok) {
            temp.delete()
            return null
        }
        if (target.exists()) target.delete()
        return if (temp.renameTo(target)) target else null
    }

    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }

    fun sizeBytes(): Long = root.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        /** Enough resolution for any export option while staying memory friendly. */
        const val MAX_DIMENSION = 2560
    }
}
