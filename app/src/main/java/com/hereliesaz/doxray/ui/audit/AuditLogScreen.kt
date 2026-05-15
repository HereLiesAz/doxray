package com.hereliesaz.doxray.ui.audit

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hereliesaz.doxray.db.AuditEvent
import org.json.JSONObject

@Composable
fun AuditLogScreen(viewModel: AuditLogViewModel) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    if (events.isEmpty()) {
        Text(text = "No audit events yet.", modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(events, key = { it.id }) { event ->
            AuditEventCard(event)
        }
    }
}

@Composable
private fun AuditEventCard(event: AuditEvent) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            AssistChip(
                onClick = {},
                label = { Text(event.type, fontSize = 10.sp) },
                colors = AssistChipDefaults.assistChipColors(),
            )
            Text(text = event.summary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                text = DateUtils.getRelativeTimeSpanString(event.timestamp).toString(),
                fontSize = 11.sp,
            )
            AnimatedVisibility(visible = expanded) {
                val pretty = runCatching { JSONObject(event.detailsJson).toString(2) }
                    .getOrElse { event.detailsJson }
                Text(
                    text = pretty,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
