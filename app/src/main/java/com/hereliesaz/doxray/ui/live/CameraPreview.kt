package com.hereliesaz.doxray.ui.live

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts a CameraX [PreviewView] inside Compose. The caller supplies the
 * [Preview] use case (built by [com.hereliesaz.doxray.camera.PhoneFrameSource]).
 * On null [previewUseCase], renders nothing (caller should branch).
 */
@Composable
fun CameraPreview(
    previewUseCase: Preview?,
    modifier: Modifier = Modifier,
) {
    if (previewUseCase == null) return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).also { view ->
                previewUseCase.setSurfaceProvider(view.surfaceProvider)
            }
        },
        update = { view ->
            previewUseCase.setSurfaceProvider(view.surfaceProvider)
        },
    )
}
