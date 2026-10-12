package com.swipedelete.feature.dashboard.quarantine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuarantineCardTest {

    private val now = 1_000_000L

    @Test
    fun `an empty quarantine says nothing is held rather than showing zeros`() {
        val card = quarantineCard(emptyList(), now)

        assertEquals(QuarantineCard.Empty, card)
        assertEquals("Nothing in quarantine", QuarantineCard.Empty.headline)
    }

    @Test
    fun `a held quarantine shows count, reclaimable bytes and that they are not yet reclaimed`() {
        val card = quarantineCard(
            listOf(HeldItem(sizeBytes = 1024, expiresAt = now + 90_000), HeldItem(sizeBytes = 1024, expiresAt = now + 5_000_000)),
            now,
        )

        val held = assertIs<QuarantineCard.Held>(card)
        assertEquals(2, held.itemCount)
        assertEquals("2.0 KB reclaimable", held.bytesText)
        assertTrue("not yet reclaimed" in held.notice, held.notice)
        assertTrue("freed" !in held.bytesText)
    }

    @Test
    fun `the countdown counts to the soonest expiry`() {
        val held = quarantineCard(
            listOf(HeldItem(1, now + 5_000_000), HeldItem(1, now + 90_000)),
            now,
        ) as QuarantineCard.Held

        assertEquals("Soonest expiry in 1m 30s", held.expiryText)
    }

    @Test
    fun `an expired item is never purged silently and the card says so`() {
        val held = quarantineCard(listOf(HeldItem(1, now - 1)), now) as QuarantineCard.Held

        assertTrue("kept until you empty" in held.expiryText, held.expiryText)
    }
}
