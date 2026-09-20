package com.swipedelete.feature.settings.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.designsystem.components.PrimaryButton
import com.swipedelete.core.model.SlotId
import com.swipedelete.feature.settings.R
import com.swipedelete.feature.settings.grants.SourceGrantRepository

/**
 * SPEC 4.5 / AC-19. Onboarding's folder picker **is** the app's first permission
 * request: there is no separate permission screen, the UI *is* the grant flow,
 * and it works at Tier 0. Skipping completes the flow with zero slots and no
 * error, because granting nothing must not be a dead end.
 *
 * [OnboardingRoute] owns the `@HiltViewModel` and the system picker; the
 * stateless [OnboardingScreen] takes the state so it can be rendered and
 * asserted without Hilt.
 */
@Composable
fun OnboardingRoute(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // ACTION_OPEN_DOCUMENT_TREE with read+write: the same persisted grant the
    // footer's destination slots use. No permission prompt precedes it.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) viewModel.onFolderPicked(uri)
    }

    OnboardingScreen(
        state = state,
        onPickFolder = { picker.launch(null) },
        onRemoveSlot = viewModel::removeSlot,
        onSkip = {
            viewModel.skip()
            onFinished()
        },
        onDone = {
            viewModel.complete()
            onFinished()
        },
        modifier = modifier,
    )
}

@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onPickFolder: () -> Unit,
    onRemoveSlot: (SlotId) -> Unit,
    onSkip: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SwipeDeleteDimens.SpacingMedium),
        verticalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingMedium),
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.onboarding_body),
            style = MaterialTheme.typography.bodyMedium,
        )

        GrantSlotPicker(
            slots = state.slots,
            onPickFolder = onPickFolder,
            onRemoveSlot = onRemoveSlot,
        )

        errorMessage(state.error)?.let { message ->
            Text(
                text = message,
                color = SwipeDeleteTheme.colors.destructive,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        PrimaryButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(R.string.onboarding_done),
        )
        TextButton(
            onClick = onSkip,
            modifier = Modifier.defaultMinSize(
                minWidth = SwipeDeleteDimens.MinTouchTarget,
                minHeight = SwipeDeleteDimens.MinTouchTarget,
            ),
        ) {
            Text(text = stringResource(R.string.onboarding_skip))
        }
    }
}

@Composable
private fun errorMessage(error: OnboardingError?): String? = when (error) {
    null -> null
    is OnboardingError.SlotLimitReached -> stringResource(R.string.onboarding_slot_limit)
    is OnboardingError.GrantRefused ->
        if (error.capReached) {
            stringResource(R.string.onboarding_error_grant_cap, SourceGrantRepository.MAX_GRANTS)
        } else {
            stringResource(R.string.onboarding_error_grant_denied)
        }
}
