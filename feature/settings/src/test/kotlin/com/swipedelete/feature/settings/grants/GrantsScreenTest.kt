package com.swipedelete.feature.settings.grants

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.swipedelete.core.designsystem.SwipeDeleteTheme
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef
import com.swipedelete.feature.settings.R
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC 4.5: grants are shown with their display name and a **one-tap revoke**.
 * The revoke affordance must be a real clickable action exposed to semantics,
 * not a decorative icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GrantsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val source = SourceRef(
        id = SourceId(TREE_DOWNLOAD),
        treeUri = TREE_DOWNLOAD,
        displayName = "Camera",
        access = SourceRef.Access.RW,
        kind = SourceKind.SAF,
        itemCount = null,
        totalBytes = null,
        grantedAt = 0L,
    )

    @Test
    fun `a grant row shows its display name and offers a one-tap revoke`() {
        var revoked: SourceId? = null
        composeRule.setContent {
            SwipeDeleteTheme {
                GrantRow(source = source, onRevoke = { revoked = it })
            }
        }

        composeRule.onNodeWithText("Camera").assertIsDisplayed()

        val revoke = composeRule.onNodeWithContentDescription(
            context.getString(R.string.grants_revoke_named, "Camera"),
        )
        revoke.assertHasClickAction()
        revoke.performClick()

        assertEquals(source.id, revoked, "a single tap on the revoke affordance must revoke that grant")
    }

    @Test
    fun `a read-only grant is labelled as read only`() {
        composeRule.setContent {
            SwipeDeleteTheme {
                GrantRow(source = source.copy(access = SourceRef.Access.RO), onRevoke = {})
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.grants_access_read)).assertIsDisplayed()
    }

    @Test
    fun `an empty grant set explains that the app is still usable`() {
        composeRule.setContent {
            SwipeDeleteTheme {
                GrantsScreen(state = GrantsState(), onRevoke = {})
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.grants_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.grants_empty_body)).assertIsDisplayed()
    }
}
