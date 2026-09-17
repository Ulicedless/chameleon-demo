package com.chameleon.blend.data

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.chameleon.blend.core.blend.BlendParams
import com.chameleon.blend.core.blend.BlendStyle
import com.chameleon.blend.core.blend.MattingOptions
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecentProject(
    val id: String,
    val createdAt: Long,
    val title: String,
    val style: BlendStyle,
    val background: File,
    val foreground: File,
    val result: File,
    val params: BlendParams,
    val matting: MattingOptions,
)

/**
 * Keeps the last few works on disk so they can be reopened and re-edited instead of being lost when
 * the activity dies. Only downscaled copies are stored, which keeps a project well under a megabyte.
 */
class ProjectStore(private val context: Context) {

    private val root: File get() = File(context.filesDir, "projects").apply { mkdirs() }

    fun list(): List<RecentProject> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { loadMeta(it) }.sortedByDescending { it.createdAt }
    }

    fun save(
        background: Bitmap,
        foreground: Bitmap,
        result: Bitmap,
        params: BlendParams,
        matting: MattingOptions,
    ): RecentProject? {
        val id = "p${System.currentTimeMillis()}"
        val dir = File(root, id).apply { mkdirs() }
        val bgFile = File(dir, "background.jpg")
        val fgFile = File(dir, "foreground.png")
        val resultFile = File(dir, "result.jpg")
        val scaledBg = scaleDown(background, 1600)
        val scaledFg = scaleDown(foreground, 1600)
        val scaledResult = scaleDown(result, 1600)
        val ok = BitmapIo.writeToFile(bgFile, scaledBg) &&
            BitmapIo.writeToFile(fgFile, scaledFg, ExportFormat.PNG) &&
            BitmapIo.writeToFile(resultFile, scaledResult)
        if (!ok) return null

        val title = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
        val meta = JSONObject().apply {
            put("id", id)
            put("createdAt", System.currentTimeMillis())
            put("title", title)
            put("params", JSONObject(BlendParamsJson.encode(params)))
            put("matting", BlendParamsJson.encodeMatting(matting))
        }
        File(dir, "meta.json").writeText(meta.toString())
        trim()
        return loadMeta(dir)
    }

    fun delete(project: RecentProject) {
        File(root, project.id).deleteRecursively()
    }

    private fun trim(keep: Int = 12) {
        val all = list()
        all.drop(keep).forEach { File(root, it.id).deleteRecursively() }
    }

    private fun loadMeta(dir: File): RecentProject? {
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return null
        return try {
            val json = JSONObject(metaFile.readText())
            val bg = File(dir, "background.jpg")
            val fg = File(dir, "foreground.png")
            val result = File(dir, "result.jpg")
            if (!bg.exists() || !fg.exists() || !result.exists()) return null
            val params = json.optJSONObject("params")?.let { BlendParamsJson.decode(it) }
                ?: BlendParams()
            RecentProject(
                id = json.optString("id", dir.name),
                createdAt = json.optLong("createdAt", dir.lastModified()),
                title = json.optString("title", dir.name),
                style = params.style,
                background = bg,
                foreground = fg,
                result = result,
                params = params,
                matting = BlendParamsJson.decodeMatting(json.optJSONObject("matting")),
            )
        } catch (t: Throwable) {
            null
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        return bitmap.scale(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            filter = true,
        )
    }
}
