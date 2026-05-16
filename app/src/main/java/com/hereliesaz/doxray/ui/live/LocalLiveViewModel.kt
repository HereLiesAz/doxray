package com.hereliesaz.doxray.ui.live

import androidx.compose.runtime.compositionLocalOf

val LocalLiveViewModel = compositionLocalOf<LiveViewModel> {
    error("LocalLiveViewModel not provided")
}
