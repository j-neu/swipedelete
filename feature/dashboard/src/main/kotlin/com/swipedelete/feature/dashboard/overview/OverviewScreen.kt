package com.swipedelete.feature.dashboard.overview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DashboardRoute(
    onOpenQuarantine: () -> Unit,
    onOpenHistory: () -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OverviewScreen(state, viewModel::open, viewModel::close, onOpenQuarantine, onOpenHistory, onAddFolder, modifier)
}

@Composable
fun OverviewScreen(
    state: OverviewUiState,
    onOpen: (Metric) -> Unit,
    onClose: () -> Unit,
    onOpenQuarantine: () -> Unit,
    onOpenHistory: () -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val drill = state.drilldown
    if (drill != null) {
        BackHandler(onBack = onClose)
        LazyColumn(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { OutlinedButton(onClick = onClose) { Text("Back") } }
            item { Text("${drill.title} \u2022 ${drill.rows.size} files", style = MaterialTheme.typography.titleMedium) }
            items(drill.rows, key = { it.id }) { file ->
                Column {
                    Text(file.name)
                    Text("${file.folder} \u2022 ${com.swipedelete.core.common.formatBytes(file.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        return
    }

    LazyColumn(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Storage", style = MaterialTheme.typography.titleLarge) }
        item { Text(state.capacityText) }
        state.reclaimableText?.let { item { Text(it) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenQuarantine) { Text("Quarantine") }
                OutlinedButton(onClick = onOpenHistory) { Text("History") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.exclusionBanner, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onAddFolder) { Text("Add a folder") }
            }
        }
        state.emptyText?.let { item { Text(it) } }
        val overview = state.overview ?: return@LazyColumn
        if (overview.categories.isNotEmpty()) {
            item { Text("By type", style = MaterialTheme.typography.titleMedium) }
            items(overview.categories, key = { it.label }) { MetricRow(it, onOpen) }
            item { MetricRow(overview.topFiles, onOpen) }
            item { Text("Largest folders", style = MaterialTheme.typography.titleMedium) }
            items(overview.topFolders, key = { it.metric.label }) { MetricRow(it.metric, onOpen) }
        }
    }
}

@Composable
private fun MetricRow(metric: Metric, onOpen: (Metric) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(metric) }.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(metric.label)
        Text("${metric.count} \u2022 ${metric.bytesText}")
    }
}
