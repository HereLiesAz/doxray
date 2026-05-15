package com.hereliesaz.doxray.ui.dossier

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DossierListScreen(viewModel: DossierListViewModel, onOpen: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.rows.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "No dossiers yet. Connect to glasses and the app will start cataloguing faces.",
                fontSize = 14.sp,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.rows, key = { it.faceId }) { row ->
            DossierRowCard(row = row, onClick = { onOpen(row.faceId) })
        }
    }
}

@Composable
private fun DossierRowCard(row: DossierRow, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(text = row.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = DateUtils.getRelativeTimeSpanString(row.lastSeenMillis).toString(),
                    fontSize = 12.sp,
                )
            }
            Text(text = "${row.encounterCount}×", fontWeight = FontWeight.Bold)
        }
    }
}
