package com.swipedelete.feature.dashboard.quarantine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun QuarantineRoute(modifier: Modifier = Modifier, viewModel: QuarantineViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    QuarantineScreen(
        state = state,
        onRestoreAll = viewModel::restoreAll,
        onEmpty = viewModel::beginEmpty,
        onProceed = viewModel::proceedEmpty,
        onType = viewModel::typeCount,
        onCancel = viewModel::cancelEmpty,
        onConfirm = viewModel::confirmEmpty,
        modifier = modifier,
    )
}

@Composable
fun QuarantineScreen(
    state: QuarantineUiState,
    onRestoreAll: () -> Unit,
    onEmpty: () -> Unit,
    onProceed: () -> Unit,
    onType: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Quarantine", style = MaterialTheme.typography.titleLarge)
        when (val card = state.card) {
            QuarantineCard.Empty -> Text(QuarantineCard.Empty.headline)
            is QuarantineCard.Held -> {
                Text("${card.itemCount} item${if (card.itemCount == 1) "" else "s"} held")
                Text(card.bytesText, style = MaterialTheme.typography.titleMedium)
                Text(card.notice)
                Text(card.expiryText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRestoreAll, enabled = !state.busy) { Text("Restore all") }
                    OutlinedButton(onClick = onEmpty, enabled = !state.busy) { Text("Empty\u2026") }
                }
            }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    when (state.step) {
        EmptyQuarantineConfirmation.Step.Idle -> Unit
        EmptyQuarantineConfirmation.Step.Warning -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Empty quarantine?") },
            text = { Text(state.warning) },
            confirmButton = { TextButton(onClick = onProceed) { Text("Continue") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        EmptyQuarantineConfirmation.Step.TypeCount -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Type the number of items to confirm") },
            text = {
                OutlinedTextField(
                    value = state.typed,
                    onValueChange = onType,
                    label = { Text("Item count") },
                    singleLine = true,
                )
            },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Delete permanently") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
    }
}
