package com.swipedelete.app.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.swipedelete.core.designsystem.SwipeDeleteDimens
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.Tier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Decision D2: the Activity-bound capability request seam.
 *
 * [AndroidCapabilityController]'s request seams are `null` in the Hilt
 * singleton because an Application-scoped object must not hold an Activity.
 * This composable is the other half: it builds a controller whose two seams are
 * backed by `rememberLauncherForActivityResult` and suspend on the launcher
 * callback result. Feature UI calls it *at the affordance* (SPEC 4.4 — requests
 * happen in context, never at onboarding); the singleton is never a launcher
 * holder.
 */
@Composable
fun rememberCapabilityRequester(): CapabilityController {
    val context = LocalContext.current

    val mediaResult = remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // Every requested permission must be granted: an images-only grant must
        // never make a video deck report FULL (partial-grant semantics).
        mediaResult.value?.complete(grants.values.all { it })
        mediaResult.value = null
    }

    val settingsResult = remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // The special-access screen reports nothing; re-read the platform toggle
        // after it returns.
        settingsResult.value?.complete(allFilesAccessHeld())
        settingsResult.value = null
    }

    return remember(context, mediaLauncher, settingsLauncher) {
        AndroidCapabilityController(
            context = context,
            requestMediaPermissions = { permissions ->
                val result = CompletableDeferred<Boolean>()
                mediaResult.value = result
                mediaLauncher.launch(permissions)
                result.await()
            },
            openAllFilesAccessSettings = {
                val result = CompletableDeferred<Boolean>()
                settingsResult.value = result
                settingsLauncher.launch(allFilesAccessSettingsIntent(context))
                result.await()
            },
        )
    }
}

/**
 * The in-context affordance for the tier-1 whole-library media decks
 * (SPEC 4.4 / AC-19).
 *
 * It asks the gate about [Feature.WholeLibraryDecks] — the feature that is
 * actually narrowed at tier 0 — **not** [Feature.DeckTriage], which is full at
 * tier 0 because triaging a granted folder needs no broad permission. At tier 0
 * the panel names what it excludes and offers the photo/video grant; once the
 * capability is held it disappears (nothing left to narrow).
 */
@Composable
fun WholeLibraryAffordance(
    controller: CapabilityController,
    gate: TieredFeatureGate,
    modifier: Modifier = Modifier,
) {
    var refresh by remember { mutableIntStateOf(0) }
    val tier by produceState(initialValue = Tier.TIER_0, controller, refresh) {
        value = controller.tier().first()
    }

    val availability = gate.isAvailable(Feature.WholeLibraryDecks, tier)
    if (availability !is Availability.NARROWED) return

    val scope = rememberCoroutineScope()
    val colors = SwipeDeleteTheme.colors

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = SwipeDeleteDimens.SpacingMedium,
                    vertical = SwipeDeleteDimens.SpacingSmall,
                ),
            horizontalArrangement = Arrangement.spacedBy(SwipeDeleteDimens.SpacingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(availability.reasonRes),
                color = colors.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    scope.launch {
                        controller.requestPhotoVideo()
                        refresh++
                    }
                },
            ) {
                Text(text = stringResource(availability.grantAffordanceRes))
            }
        }
    }
}

/** The `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` intent for this package. */
internal fun allFilesAccessSettingsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

/** Re-reads the All Files Access platform toggle; false below API 30. */
internal fun allFilesAccessHeld(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
