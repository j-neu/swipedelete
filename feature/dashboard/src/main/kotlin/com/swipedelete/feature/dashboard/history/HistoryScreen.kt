package com.swipedelete.feature.dashboard.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryRoute(modifier: Modifier = Modifier, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.export(it.toString(), ExportFormat.Json) }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.export(it.toString(), ExportFormat.Csv) }
    }
    HistoryScreen(
        state = state,
        onRestore = viewModel::restore,
        onKeepBoth = viewModel::keepBoth,
        onSkip = viewModel::skip,
        onExportJson = { json.launch("swipedelete-journal.json") },
        onExportCsv = { csv.launch("swipedelete-journal.csv") },
        modifier = modifier,
    )
}

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onRestore: (String) -> Unit,
    onKeepBoth: () -> Unit,
    onSkip: () -> Unit,
    onExportJson: () -> Unit = {},
    onExportCsv: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("History", style = MaterialTheme.typography.titleLarge) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExportJson) { Text("Export JSON") }
                OutlinedButton(onClick = onExportCsv) { Text("Export CSV") }
            }
        }
        state.message?.let { item { Text(it) } }
        if (state.batches.isEmpty()) item { Text("Nothing has been deleted yet.") }
        state.batches.forEach { batch ->
            item(key = batch.batchId) {
                val date = DateFormat.getDateTimeInstance().format(Date(batch.createdAt))
                Text(
                    "$date \u2022 ${batch.itemCount} item${if (batch.itemCount == 1) "" else "s"} \u2022 ${batch.bytesText}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(batch.rows, key = { it.id }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(row.name)
                        Text("${row.kind} \u2022 ${row.status.text}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (row.status.canRestore) {
                        OutlinedButton(onClick = { onRestore(row.id) }) { Text("Restore") }
                    }
                }
            }
        }
    }

    state.conflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = onSkip,
            title = { Text("A file with this name already exists") },
            text = { Text("${conflict.name} is already in its original place. Nothing will be overwritten.") },
            confirmButton = { TextButton(onClick = onKeepBoth) { Text("Keep both") } },
            dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
        )
    }
}
