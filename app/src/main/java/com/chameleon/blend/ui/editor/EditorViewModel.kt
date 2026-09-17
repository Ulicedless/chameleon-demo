package com.chameleon.blend.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chameleon.blend.core.blend.BlendEngine
import com.chameleon.blend.core.blend.BlendParams
import com.chameleon.blend.core.blend.BlendPipeline
import com.chameleon.blend.core.blend.BlendStyle
import com.chameleon.blend.core.blend.Matting
import com.chameleon.blend.core.blend.MattingOptions
import com.chameleon.blend.core.blend.SceneAnalysis
import com.chameleon.blend.core.img.ImageOps
import com.chameleon.blend.core.img.RasterImage
import com.chameleon.blend.data.BitmapIo
import com.chameleon.blend.data.ExportFormat
import com.chameleon.blend.data.ProjectStore
import com.chameleon.blend.data.RecentProject
import com.chameleon.blend.data.SaveResult
import com.chameleon.blend.data.SettingsStore
import com.chameleon.blend.data.SourceCache
import com.chameleon.blend.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Owns the editing session: decodes sources, extracts the matte, tunes parameters against the
 * photograph, renders previews in two quality tiers and writes exports.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ProjectStore(application)
    private val sourceCache = SourceCache(application)
    private val captureJobs = HashMap<SourceCache.Role, Job>()
    private val settingsStore = SettingsStore(application)

    /** User preferences, also consumed by the theme. */
    val settings: StateFlow<AppSettings> = settingsStore.state

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    // Full quality working copies (preview resolution) and cheap copies for dragging.
    private var workBackground: RasterImage? = null
    private var workForeground: RasterImage? = null
    private var workAlpha: FloatArray? = null
    private var workAnalysis: SceneAnalysis? = null

    private var quickBackground: RasterImage? = null
    private var quickForeground: RasterImage? = null
    private var quickAlpha: FloatArray? = null

    private var renderJob: Job? = null
    private var renderDebounceJob: Job? = null
    private var generation = 0
    private var sourceAlphaAvailable = false

    init {
        refreshRecents()
    }

    // ------------------------------------------------------------------ picking images

    fun setForeground(uri: Uri) {
        _state.update {
            it.copy(
                foreground = ImageSource.Picked(uri),
                matting = it.matting.mattingFromSettings(settingsStore.state.value),
            )
        }
        cacheSource(SourceCache.Role.FOREGROUND, uri)
        loadSources()
    }

    fun setBackground(uri: Uri) {
        _state.update { it.copy(background = ImageSource.Picked(uri)) }
        cacheSource(SourceCache.Role.BACKGROUND, uri)
        loadSources()
    }

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        val previous = settingsStore.state.value
        settingsStore.update(block)
        val updated = settingsStore.state.value
        if (updated.previewQuality != previous.previewQuality && _state.value.hasBothImages) {
            loadSources()
        }
        if (updated.blendIntensity != previous.blendIntensity && _state.value.hasBothImages) {
            autoTune(keepPlacement = true)
        }
    }

    fun clearSourceCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sourceCache.clear() }
            refreshCacheSize()
            _state.update { it.copy(message = "已清理图片缓存") }
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) { sourceCache.sizeBytes() }
            _state.update { it.copy(cacheSizeBytes = size) }
        }
    }

    fun openSettings() {
        refreshCacheSize()
        _state.update { it.copy(screen = AppScreen.SETTINGS) }
    }

    fun clearProjects() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.list().forEach(store::delete) }
            refreshRecents()
            _state.update { it.copy(message = "已清空最近作品") }
        }
    }

    fun cacheSizeBytes(): Long = sourceCache.sizeBytes()

    /**
     * Copies the picked image into app storage. Picker grants can disappear with the app session,
     * so every later decode (export included) reads our own file instead.
     */
    private fun cacheSource(role: SourceCache.Role, uri: Uri) {
        captureJobs[role]?.cancel()
        captureJobs[role] = viewModelScope.launch(Dispatchers.IO) {
            val file = sourceCache.capture(getApplication(), role, uri) ?: return@launch
            _state.update { current ->
                val matches = when (role) {
                    SourceCache.Role.FOREGROUND -> (current.foreground as? ImageSource.Picked)?.uri == uri
                    SourceCache.Role.BACKGROUND -> (current.background as? ImageSource.Picked)?.uri == uri
                }
                if (!matches) return@update current
                val source = ImageSource.Local(file)
                when (role) {
                    SourceCache.Role.FOREGROUND -> current.copy(foreground = source)
                    SourceCache.Role.BACKGROUND -> current.copy(background = source)
                }
            }
        }
    }

    fun clearForeground() {
        _state.update {
            it.copy(foreground = null, foregroundThumb = null, preview = null)
        }
        workForeground = null
        quickForeground = null
        workAlpha = null
        quickAlpha = null
    }

    fun clearBackground() {
        _state.update {
            it.copy(background = null, backgroundThumb = null, preview = null)
        }
        workBackground = null
        quickBackground = null
        workAnalysis = null
    }

    private fun loadSources() {
        val current = _state.value
        val fgSource = current.foreground
        val bgSource = current.background
        if (fgSource == null || bgSource == null) {
            // Still show the thumbnail of whichever side is ready.
            viewModelScope.launch { updateThumbnails() }
            return
        }
        val generationToken = ++generation
        renderJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, stage = "读取图片", preview = null) }
            val prepared = withContext(Dispatchers.Default) {
                prepare(fgSource, bgSource, current.matting)
            }
            if (generationToken != generation) return@launch
            if (prepared == null) {
                _state.update { it.copy(loading = false, stage = "", message = "图片读取失败，请换一张试试") }
                return@launch
            }
            workBackground = prepared.background
            workForeground = prepared.foreground
            workAlpha = prepared.alpha
            workAnalysis = prepared.analysis
            quickBackground = prepared.quickBackground
            quickForeground = prepared.quickForeground
            quickAlpha = prepared.quickAlpha
            sourceAlphaAvailable = prepared.sourceHasAlpha

            val tuned = BlendEngine.autoTune(
                prepared.background,
                prepared.analysis,
                prepared.foreground,
                prepared.alpha,
                intensity = settingsStore.state.value.blendIntensity,
                autoStyleByScene = settingsStore.state.value.autoStyleByScene,
            )
            _state.update {
                it.copy(
                    loading = false,
                    previewAspect = prepared.background.width.toFloat() / prepared.background.height,
                    params = tuned,
                    summary = BlendEngine.summarize(prepared.analysis),
                    foregroundHasAlpha = prepared.sourceHasAlpha,
                    foregroundThumb = prepared.foregroundThumb.asImageBitmap(),
                    backgroundThumb = prepared.backgroundThumb.asImageBitmap(),
                )
            }
            render(interactiveFirst = true)
        }
    }

    private suspend fun updateThumbnails() {
        val current = _state.value
        val fgThumb = current.foreground?.let { source ->
            withContext(Dispatchers.IO) { decode(source, 512) }
        }
        val bgThumb = current.background?.let { source ->
            withContext(Dispatchers.IO) { decode(source, 512) }
        }
        _state.update {
            it.copy(
                foregroundThumb = fgThumb?.asImageBitmap() ?: it.foregroundThumb,
                backgroundThumb = bgThumb?.asImageBitmap() ?: it.backgroundThumb,
                previewAspect = bgThumb?.let { bmp -> bmp.width.toFloat() / bmp.height } ?: it.previewAspect,
            )
        }
    }

    private class Prepared(
        val background: RasterImage,
        val foreground: RasterImage,
        val alpha: FloatArray,
        val analysis: SceneAnalysis,
        val quickBackground: RasterImage,
        val quickForeground: RasterImage,
        val quickAlpha: FloatArray,
        val backgroundThumb: Bitmap,
        val foregroundThumb: Bitmap,
        val sourceHasAlpha: Boolean,
    )

    private fun prepare(
        fgSource: ImageSource,
        bgSource: ImageSource,
        matting: MattingOptions,
        maxDimension: Int = previewMaxDimension,
    ): Prepared? {
        val fgBitmap = decode(fgSource, maxDimension) ?: return null
        val bgBitmap = decode(bgSource, maxDimension) ?: return null
        val fgRaster = BitmapIo.toRaster(fgBitmap)
        val bgRaster = BitmapIo.toRaster(bgBitmap)
        val hasAlpha = Matting.hasUsableAlpha(fgRaster)
        val alpha = Matting.extractAlpha(fgRaster, matting)
        val analysis = SceneAnalysis.analyze(bgRaster)

        val quickLong = quickMaxDimension
        val quickBg = ImageOps.fitInside(bgRaster, quickLong, quickLong)
        val quickFg = ImageOps.fitInside(fgRaster, quickLong, quickLong)
        val quickAlpha = if (quickFg.width == fgRaster.width && quickFg.height == fgRaster.height) {
            alpha
        } else {
            ImageOps.resizeBilinear(alpha, fgRaster.width, fgRaster.height, quickFg.width, quickFg.height)
        }
        val bgThumb = BitmapIo.toBitmap(ImageOps.fitInside(bgRaster, 512, 512))
        val fgThumb = BitmapIo.toBitmap(ImageOps.fitInside(fgRaster, 512, 512))
        return Prepared(
            background = bgRaster,
            foreground = fgRaster,
            alpha = alpha,
            analysis = analysis,
            quickBackground = quickBg,
            quickForeground = quickFg,
            quickAlpha = quickAlpha,
            backgroundThumb = bgThumb,
            foregroundThumb = fgThumb,
            sourceHasAlpha = hasAlpha,
        )
    }

    private fun decode(source: ImageSource, maxDimension: Int): Bitmap? = when (source) {
        is ImageSource.Picked -> BitmapIo.decode(getApplication(), source.uri, maxDimension)
        is ImageSource.Local -> BitmapIo.decodeFile(source.file, maxDimension)
    }

    /** Prefers the private copy, falling back to the original uri and finally to the cache again. */
    private fun decodeForExport(
        role: SourceCache.Role,
        source: ImageSource,
        maxDimension: Int,
    ): Bitmap? {
        sourceCache.existing(role)?.let { cached ->
            BitmapIo.decodeFile(cached, maxDimension)?.let { return it }
        }
        decode(source, maxDimension)?.let { return it }
        val cached = sourceCache.existing(role) ?: return null
        return BitmapIo.decodeFile(cached, maxDimension)
    }

    // ------------------------------------------------------------------ parameters

    fun updateParams(block: (BlendParams) -> BlendParams) {
        val updated = block(_state.value.params)
        if (updated == _state.value.params) return
        _state.update { it.copy(params = updated) }
        scheduleRender()
    }

    /**
     * Coalesces the burst of updates that a drag produces: rendering is expensive and the last
     * value is the only one that matters.
     */
    private fun scheduleRender() {
        renderDebounceJob?.cancel()
        renderDebounceJob = viewModelScope.launch {
            delay(GESTURE_DEBOUNCE)
            render(interactiveFirst = true)
        }
    }

    fun applyStyle(style: BlendStyle) {
        val bg = workBackground ?: return
        val fg = workForeground ?: return
        val alpha = workAlpha ?: return
        val analysis = workAnalysis ?: return
        val settings = settingsStore.state.value
        val tuned = BlendEngine.autoTune(
            bg,
            analysis,
            fg,
            alpha,
            style = style,
            intensity = settings.blendIntensity,
            autoStyleByScene = false,
        )
        _state.update { current ->
            current.copy(
                params = tuned.copy(
                    offsetX = current.params.offsetX,
                    offsetY = current.params.offsetY,
                    heightFraction = current.params.heightFraction,
                    rotationDeg = current.params.rotationDeg,
                    flipHorizontal = current.params.flipHorizontal,
                ),
            )
        }
        render(interactiveFirst = true)
    }

    fun autoTune(keepPlacement: Boolean = false) {
        val bg = workBackground ?: return
        val fg = workForeground ?: return
        val alpha = workAlpha ?: return
        val analysis = workAnalysis ?: return
        val settings = settingsStore.state.value
        val tuned = BlendEngine.autoTune(
            bg,
            analysis,
            fg,
            alpha,
            style = if (settings.autoStyleByScene) null else settings.defaultStyle,
            intensity = settings.blendIntensity,
            autoStyleByScene = settings.autoStyleByScene,
        )
        _state.update { current ->
            val merged = if (keepPlacement) {
                tuned.copy(
                    offsetX = current.params.offsetX,
                    offsetY = current.params.offsetY,
                    heightFraction = current.params.heightFraction,
                    rotationDeg = current.params.rotationDeg,
                    flipHorizontal = current.params.flipHorizontal,
                )
            } else {
                tuned
            }
            current.copy(params = merged, summary = BlendEngine.summarize(analysis))
        }
        render(interactiveFirst = true)
    }

    fun resetPlacement() {
        autoTune(keepPlacement = false)
    }

    fun updateMatting(block: (MattingOptions) -> MattingOptions) {
        val options = block(_state.value.matting)
        _state.update { it.copy(matting = options) }
        val fg = workForeground
        if (fg != null && !sourceAlphaAvailable) {
            viewModelScope.launch {
                _state.update { it.copy(rendering = true, stage = "重新抠图") }
                val alpha = withContext(Dispatchers.Default) { Matting.extractAlpha(fg, options) }
                workAlpha = alpha
                quickAlpha = quickForeground?.let { quick ->
                    if (quick.width == fg.width && quick.height == fg.height) {
                        alpha
                    } else {
                        ImageOps.resizeBilinear(alpha, fg.width, fg.height, quick.width, quick.height)
                    }
                }
                render(interactiveFirst = true)
            }
        }
    }

    fun selectTab(tab: ControlTab) {
        _state.update { it.copy(tab = tab) }
    }

    fun toggleCompare() {
        val show = !_state.value.showRaw
        _state.update { it.copy(showRaw = show) }
        if (show && _state.value.rawPreview == null) {
            renderRawPreview()
        }
    }

    private fun renderRawPreview() {
        val bg = quickBackground ?: return
        val fg = quickForeground ?: return
        val alpha = quickAlpha ?: return
        val analysis = workAnalysis ?: return
        val params = _state.value.params
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                val out = BlendPipeline.render(
                    bg,
                    fg,
                    alpha,
                    BlendEngine.rawPasteParams(params),
                    analysis,
                    fast = true,
                )
                BitmapIo.toBitmap(out)
            }
            _state.update { it.copy(rawPreview = bitmap.asImageBitmap()) }
        }
    }

    fun setScreen(screen: AppScreen) {
        _state.update { it.copy(screen = screen) }
    }

    fun showExportSheet(visible: Boolean) {
        _state.update { it.copy(exportSheetVisible = visible) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun consumeShare() {
        _state.update { it.copy(pendingShareUri = null) }
    }

    // ------------------------------------------------------------------ rendering

    private fun render(interactiveFirst: Boolean) {
        val bg = workBackground ?: return
        val fg = workForeground ?: return
        val alpha = workAlpha ?: return
        val analysis = workAnalysis ?: return
        val token = ++generation
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            _state.update { it.copy(rendering = true, stage = "准备融合") }
            if (interactiveFirst) {
                val quick = withContext(Dispatchers.Default) {
                    val qb = quickBackground ?: bg
                    val qf = quickForeground ?: fg
                    val qa = quickAlpha ?: alpha
                    BitmapIo.toBitmap(
                        BlendPipeline.render(
                            qb,
                            qf,
                            qa,
                            _state.value.params,
                            analysis,
                            fast = true,
                            onStage = { stage -> _state.update { it.copy(stage = stage) } },
                        ),
                    )
                }
                if (token != generation) return@launch
                _state.update { it.copy(preview = quick.asImageBitmap()) }
                delay(SETTLE_DELAY)
                if (token != generation) return@launch
            }
            val full = withContext(Dispatchers.Default) {
                BitmapIo.toBitmap(
                    BlendPipeline.render(
                        bg,
                        fg,
                        alpha,
                        _state.value.params,
                        analysis,
                        fast = false,
                        onStage = { stage -> _state.update { it.copy(stage = stage) } },
                    ),
                )
            }
            if (token != generation) return@launch
            _state.update { it.copy(preview = full.asImageBitmap(), rendering = false, stage = "") }
            if (_state.value.showRaw) {
                _state.update { it.copy(rawPreview = null) }
                renderRawPreview()
            }
        }
    }

    // ------------------------------------------------------------------ export

    fun export(request: ExportRequest) {
        val bgSource = _state.value.background ?: return
        val fgSource = _state.value.foreground ?: return
        val params = _state.value.params
        val matting = _state.value.matting
        var render: Exported? = null
        viewModelScope.launch {
            _state.update { it.copy(exporting = true, exportSheetVisible = false, stage = "准备导出") }
            try {
                // Wait for the private copies first: exporting must never depend on a picker grant.
                captureJobs[SourceCache.Role.FOREGROUND]?.join()
                captureJobs[SourceCache.Role.BACKGROUND]?.join()
                // Free the comparison bitmap before allocating the export.
                _state.update { it.copy(rawPreview = null, showRaw = false) }

                render = withContext(Dispatchers.Default) {
                    renderForExport(bgSource, fgSource, params, matting, request)
                }
                val exported = render
                if (exported == null) {
                    _state.update {
                        it.copy(message = "导出失败：原始图片已无法读取，请重新选择一次图片")
                    }
                    return@launch
                }

                val name = "chameleon_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
                if (request.target == ExportTarget.SHARE) {
                    val uri = withContext(Dispatchers.IO) {
                        BitmapIo.shareCacheFile(
                            getApplication(),
                            exported.bitmap,
                            name,
                            request.format,
                            request.quality,
                        )?.first
                    }
                    if (uri == null) {
                        _state.update { it.copy(message = "分享失败：无法写出临时文件") }
                    } else {
                        _state.update { it.copy(pendingShareUri = uri) }
                    }
                } else {
                    val saved = withContext(Dispatchers.IO) {
                        BitmapIo.saveImage(
                            getApplication(),
                            exported.bitmap,
                            name,
                            request.format,
                            request.quality,
                        )
                    }
                    when (saved) {
                        is SaveResult.Gallery -> _state.update {
                            it.copy(
                                message = if (exported.downgradedTo != null) {
                                    "已保存到相册 · Chameleon（内存不足，已按 ${exported.downgradedTo} px 导出）"
                                } else {
                                    "已保存到相册 · Chameleon"
                                },
                            )
                        }

                        is SaveResult.AppStorage -> _state.update {
                            it.copy(message = "系统相册不可写，已保存到应用目录：${saved.file.name}")
                        }

                        is SaveResult.Failed -> _state.update {
                            it.copy(message = "保存失败：${saved.reason}")
                        }
                    }
                }

                if (produceHistory) {
                    withContext(Dispatchers.IO) {
                        store.save(
                            exported.background,
                            exported.foreground,
                            exported.bitmap,
                            params,
                            matting,
                        )
                    }
                    refreshRecents()
                }
            } catch (oom: OutOfMemoryError) {
                _state.update { it.copy(message = "内存不足，已尝试降低分辨率，请再试一次或选择 1280 px") }
            } catch (t: Throwable) {
                _state.update { it.copy(message = "导出失败：${t.javaClass.simpleName}") }
            } finally {
                // Never leave the UI stuck in "exporting".
                render?.release()
                _state.update { it.copy(exporting = false, stage = "") }
            }
        }
    }

    private val produceHistory: Boolean
        get() = settings.value.keepProjectHistory

    private class Exported(
        val bitmap: Bitmap,
        val background: Bitmap,
        val foreground: Bitmap,
        val downgradedTo: Int? = null,
    ) {
        /** Frees the working copies, keeping only the exported bitmap. */
        fun release() {
            if (background !== bitmap) background.recycle()
            if (foreground !== bitmap) foreground.recycle()
        }
    }

    /**
     * Renders at the requested resolution, stepping down when the device runs out of heap instead of
     * failing outright, and finally falling back to the resolution that is already in memory.
     */
    private fun renderForExport(
        bgSource: ImageSource,
        fgSource: ImageSource,
        params: BlendParams,
        matting: MattingOptions,
        request: ExportRequest,
    ): Exported? {
        var target = request.maxDimension
        var downgraded: Int? = null
        while (target >= 1024) {
            try {
                val bgBitmap = decodeForExport(SourceCache.Role.BACKGROUND, bgSource, target)
                val fgBitmap = decodeForExport(SourceCache.Role.FOREGROUND, fgSource, target)
                if (bgBitmap == null || fgBitmap == null) return null
                val bgRaster = BitmapIo.toRaster(bgBitmap)
                val fgRaster = BitmapIo.toRaster(fgBitmap)
                val alpha = Matting.extractAlpha(fgRaster, matting)
                // Analyse the export resolution so statistics match the pixels being blended.
                val analysis = SceneAnalysis.analyze(bgRaster)
                val output = BlendPipeline.render(
                    bgRaster,
                    fgRaster,
                    alpha,
                    params,
                    analysis,
                    fast = false,
                    onStage = { stage -> _state.update { it.copy(stage = stage) } },
                )
                val result = BitmapIo.toBitmap(output)
                return Exported(
                    bitmap = result,
                    background = BitmapIo.scaledDown(bgBitmap, PROJECT_COPY_MAX),
                    foreground = BitmapIo.scaledDown(fgBitmap, PROJECT_COPY_MAX),
                    downgradedTo = downgraded,
                ).also {
                    if (bgBitmap !== it.background) bgBitmap.recycle()
                    if (fgBitmap !== it.foreground) fgBitmap.recycle()
                }
            } catch (oom: OutOfMemoryError) {
                downgraded = (target * 0.7f).toInt().coerceAtLeast(1024)
                if (downgraded >= target) return renderFromPreview(params)
                target = downgraded
            }
        }
        return renderFromPreview(params)
    }

    /** Last resort: reuse the rasters that are already in memory so the user still gets a file. */
    private fun renderFromPreview(params: BlendParams): Exported? {
        val bg = workBackground ?: return null
        val fg = workForeground ?: return null
        val alpha = workAlpha ?: return null
        val analysis = workAnalysis ?: return null
        return try {
            val output = BlendPipeline.render(bg, fg, alpha, params, analysis, fast = false)
            val bitmap = BitmapIo.toBitmap(output)
            Exported(
                bitmap = bitmap,
                background = BitmapIo.toBitmap(ImageOps.fitInside(bg, PROJECT_COPY_MAX, PROJECT_COPY_MAX)),
                foreground = BitmapIo.toBitmap(ImageOps.fitInside(fg, PROJECT_COPY_MAX, PROJECT_COPY_MAX)),
                downgradedTo = max(bg.width, bg.height),
            )
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ projects

    fun refreshRecents() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.list() }
            _state.update { it.copy(recents = list) }
        }
    }

    fun openRecent(project: RecentProject) {
        _state.update {
            it.copy(
                screen = AppScreen.EDITOR,
                foreground = ImageSource.Local(project.foreground),
                background = ImageSource.Local(project.background),
                params = project.params,
                matting = project.matting,
            )
        }
        loadSources()
    }

    fun deleteRecent(project: RecentProject) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(project) }
            refreshRecents()
        }
    }

    fun openEditor() {
        if (_state.value.hasBothImages) {
            _state.update { it.copy(screen = AppScreen.EDITOR) }
        }
    }

    companion object {
        /** Copies kept inside a saved project. */
        const val PROJECT_COPY_MAX = 1600

        private const val SETTLE_DELAY = 260L

        private const val GESTURE_DEBOUNCE = 90L
    }

    private val previewMaxDimension: Int
        get() = settingsStore.state.value.previewQuality.maxDimension

    private val quickMaxDimension: Int
        get() = max(512, previewMaxDimension * 5 / 8)

    private fun MattingOptions.mattingFromSettings(settings: AppSettings): MattingOptions =
        MattingOptions(
            enabled = settings.autoMatting,
            tolerance = settings.mattingTolerance,
            feather = settings.mattingFeather,
            protectEnclosedRegions = true,
        )
}
