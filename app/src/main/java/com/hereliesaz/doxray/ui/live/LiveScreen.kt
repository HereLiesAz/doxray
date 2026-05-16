package com.hereliesaz.doxray.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
        // Anchor PiP + log gradient + flip button: Task 9.
    }
}
