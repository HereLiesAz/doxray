package com.hereliesaz.doxray.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LiveScreen(viewModel: LiveViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val inputMode by viewModel.inputMode.collectAsStateWithLifecycle()
    val match by viewModel.lastMatchFlow.collectAsStateWithLifecycle()

    when (inputMode) {
        InputMode.META -> MetaLiveSurface(
            isConnected = state.isConnected,
            hasMetaSdk = state.hasMetaSdk,
            logLines = state.logLines,
            onConnect = { viewModel.connect() },
            onDisconnect = { viewModel.disconnect() },
        )
        InputMode.PHONE -> PhoneLiveSurface(
            previewUseCase = viewModel.previewUseCase(),
            logLines = state.logLines,
            match = match,
            onFlip = { viewModel.flipPhoneCamera() },
        )
    }
}

@Composable
private fun MetaLiveSurface(
    isConnected: Boolean,
    hasMetaSdk: Boolean,
    logLines: List<String>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Text(
            text = if (isConnected) "Status: Connected to Glasses" else "Status: Disconnected",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        if (!hasMetaSdk) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Meta Wearables DAT SDK not bundled in this build (closed beta). " +
                    "Even if your Ray-Bans are paired in the Meta View app, this third-party " +
                    "build cannot access their camera. Switch to Camera mode to use the phone.",
                fontSize = 13.sp,
                color = Color(0xFFD08A2C),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onConnect, enabled = !isConnected) { Text("Connect") }
            Button(onClick = onDisconnect, enabled = isConnected) { Text("Disconnect") }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Recent Activity Log:", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logLines) { line ->
                Text(text = line, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun PhoneLiveSurface(
    previewUseCase: androidx.camera.core.Preview?,
    logLines: List<String>,
    match: MatchEvent?,
    onFlip: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            previewUseCase = previewUseCase,
            modifier = Modifier.fillMaxSize(),
        )
        AnchorPipOverlay(
            match = match,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                    ),
                )
                .padding(12.dp),
        ) {
            Column {
                logLines.takeLast(4).forEach { line ->
                    Text(
                        text = line,
                        fontSize = 12.sp,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        IconButton(
            onClick = onFlip,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f)),
        ) {
            Text(text = "⟳", color = Color.White, fontSize = 20.sp)
        }
    }
}
