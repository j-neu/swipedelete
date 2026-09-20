package com.swipedelete.core.storage

import com.swipedelete.core.model.JournalEntry
import com.swipedelete.core.model.MediaItem
import com.swipedelete.core.storage.fake.FakeStorageGateway

class FakeStorageGatewayContractTest : StorageGatewayContractTest() {

    private var fake: FakeStorageGateway? = null

    override fun newGateway(items: List<MediaItem>): StorageGateway =
        FakeStorageGateway(items).also { fake = it }

    private val gateway: FakeStorageGateway
        get() = requireNotNull(fake) { "newGateway() has not been called" }

    override fun destinationWith(existingName: String): Destination {
        val source = destinationSource()
        gateway.addSource(source)
        gateway.createFileAt(pathOf(source, existingName))
        return Destination(source)
    }

    override fun newFileAt(path: String) = gateway.createFileAt(path)

    override fun fileExists(path: String): Boolean = gateway.fileExists(path)

    override fun quarantinePathFor(entry: JournalEntry): String =
        requireNotNull(entry.toUri) { "quarantine entry has no toUri" }
}
