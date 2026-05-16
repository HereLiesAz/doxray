package com.hereliesaz.doxray.ui.dossier

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hereliesaz.doxray.db.Encounter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DossierDetailScreen(viewModel: DossierDetailViewModel, onDeleted: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmingDelete by remember { mutableStateOf(false) }
    val identity = state.identity

    if (identity == null) {
        Text(text = "Dossier not found.", modifier = Modifier.padding(16.dp))
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row {
                    val anchor = state.anchorBytes
                    if (anchor != null) {
                        val bmp = remember(anchor) { BitmapFactory.decodeByteArray(anchor, 0, anchor.size) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = identity.primaryIdentity,
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)),
                            )
                        } else {
                            Box(Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)).background(Color.LightGray))
                        }
                    } else {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)).background(Color.LightGray))
                    }
                    Spacer(modifier = Modifier.padding(end = 12.dp))
                    Column {
                        Text(text = identity.primaryIdentity, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        val isReencountered = identity.lastSeenTimestamp - identity.firstSeenTimestamp > 24 * 60 * 60 * 1000L
                        val encounterLine = if (isReencountered)
                            "${identity.encounterCount} encounter(s) — re-encountered"
                        else
                            "${identity.encounterCount} encounter(s)"
                        Text(text = encounterLine, fontSize = 14.sp)
                        identity.visibleText?.takeIf { it.isNotBlank() }?.let { vt ->
                            Text(text = "Visible text: \"$vt\"", fontSize = 13.sp)
                        }
                        Text(
                            text = "First seen: " + formatAbsolute(identity.firstSeenTimestamp),
                            fontSize = 12.sp,
                        )
                        Text(
                            text = "Last seen: " + DateUtils.getRelativeTimeSpanString(identity.lastSeenTimestamp).toString(),
                            fontSize = 12.sp,
                        )
                    }
                }
                IconButton(onClick = { confirmingDelete = true }) {
                    Text("✕")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(text = "Encounters", fontWeight = FontWeight.Bold)
        }
        items(state.encounters, key = { it.id }) { e ->
            EncounterRow(e)
        }
        if (state.socialLinks.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(text = "Links", fontWeight = FontWeight.Bold)
                state.socialLinks.forEach { Text(text = it, fontSize = 12.sp) }
            }
        }
        if (state.backgroundData.length() > 0) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(text = "Background", fontWeight = FontWeight.Bold)
                Text(text = state.backgroundData.toString(2), fontSize = 12.sp)
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete dossier?") },
            text = { Text("This permanently removes ${identity.primaryIdentity} and all encounters.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.delete(onDeleted)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EncounterRow(e: Encounter) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text = formatAbsolute(e.timestamp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (e.latitude != null && e.longitude != null) {
            val acc = e.locationAccuracyMeters?.let { " ±${it.toInt()}m" } ?: ""
            Text(text = "📍 ${"%.5f".format(e.latitude)}, ${"%.5f".format(e.longitude)}$acc", fontSize = 12.sp)
        } else {
            Text(text = "📍 location unavailable", fontSize = 12.sp)
        }
    }
}

private fun formatAbsolute(ms: Long): String =
    SimpleDateFormat("MMM dd yyyy, HH:mm:ss", Locale.getDefault()).format(Date(ms))
