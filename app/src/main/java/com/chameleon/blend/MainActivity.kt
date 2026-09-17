package com.chameleon.blend

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chameleon.blend.ui.editor.AppScreen
import com.chameleon.blend.ui.editor.EditorScreen
import com.chameleon.blend.ui.editor.EditorViewModel
import com.chameleon.blend.ui.home.HomeScreen
import com.chameleon.blend.ui.settings.SettingsScreen
import com.chameleon.blend.data.ThemeMode
import com.chameleon.blend.ui.theme.ChameleonTheme

class MainActivity : ComponentActivity() {

    private var incomingImage: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        incomingImage = extractSharedImage(intent)
        setContent {
            ChameleonApp(incomingImage = incomingImage)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingImage = extractSharedImage(intent)
    }

    private fun extractSharedImage(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}

@Composable
fun ChameleonApp(
    incomingImage: Uri? = null,
    viewModel: EditorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    ChameleonTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
        ChameleonContent(
            state = state,
            settings = settings,
            viewModel = viewModel,
            incomingImage = incomingImage,
        )
    }
}

@Composable
private fun ChameleonContent(
    state: com.chameleon.blend.ui.editor.EditorState,
    settings: com.chameleon.blend.data.AppSettings,
    viewModel: EditorViewModel,
    incomingImage: Uri?,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val foregroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let(viewModel::setForeground)
    }
    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let(viewModel::setBackground)
    }

    LaunchedEffect(incomingImage) {
        if (incomingImage != null) {
            viewModel.setBackground(incomingImage)
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(state.pendingShareUri) {
        val uri = state.pendingShareUri
        if (uri != null) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享 Chameleon 作品"))
            viewModel.consumeShare()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        when (state.screen) {
            AppScreen.HOME -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                HomeScreen(
                    foregroundThumb = state.foregroundThumb,
                    backgroundThumb = state.backgroundThumb,
                    recents = state.recents,
                    hasBothImages = state.hasBothImages,
                    loading = state.loading,
                    onPickForeground = {
                        foregroundPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onPickBackground = {
                        backgroundPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onClearForeground = viewModel::clearForeground,
                    onClearBackground = viewModel::clearBackground,
                    onStart = viewModel::openEditor,
                    onOpenRecent = viewModel::openRecent,
                    onDeleteRecent = viewModel::deleteRecent,
                    onOpenSettings = viewModel::openSettings,
                )

            }

            AppScreen.SETTINGS -> SettingsScreen(
                settings = settings,
                cacheSizeLabel = formatBytes(state.cacheSizeBytes),
                onUpdate = viewModel::updateSettings,
                onClearCache = viewModel::clearSourceCache,
                onClearProjects = viewModel::clearProjects,
                onBack = { viewModel.setScreen(AppScreen.HOME) },
            )

            AppScreen.EDITOR -> EditorScreen(
                    state = state,
                    onBack = { viewModel.setScreen(AppScreen.HOME) },
                    onParamsChange = viewModel::updateParams,
                    onStyle = viewModel::applyStyle,
                    onAuto = { viewModel.autoTune(keepPlacement = false) },
                    onToggleCompare = viewModel::toggleCompare,
                    onTab = viewModel::selectTab,
                    onMattingChange = viewModel::updateMatting,
                    onResetLayout = viewModel::resetPlacement,
                    onShowExport = viewModel::showExportSheet,
                    onExport = viewModel::export,
                    settings = settings,
                )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0)
}
