package com.swipedelete.core.storage.saf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Host tests for [SafStorageGateway.freeName], the rule that makes `move` never
 * overwrite on SAF (V3/AC-17). Pure and static, so it needs no `Context` and no
 * device.
 */
class SafStorageGatewayNamesTest {

    @Test
    fun `keeps a free name unchanged`() {
        assertEquals("a.gif", SafStorageGateway.freeName(emptySet(), "a.gif"))
    }

    @Test
    fun `appends the first free counter for a taken name`() {
        assertEquals("a (1).gif", SafStorageGateway.freeName(setOf("a.gif"), "a.gif"))
        assertEquals("a (2).gif", SafStorageGateway.freeName(setOf("a.gif", "a (1).gif"), "a.gif"))
    }

    @Test
    fun `treats a dotfile as a whole name and a plain name as extensionless`() {
        assertEquals(".hidden (1)", SafStorageGateway.freeName(setOf(".hidden"), ".hidden"))
        assertEquals("notes (1)", SafStorageGateway.freeName(setOf("notes"), "notes"))
    }
}
