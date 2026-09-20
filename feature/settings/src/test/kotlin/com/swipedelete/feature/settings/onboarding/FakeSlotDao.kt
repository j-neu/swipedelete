package com.swipedelete.feature.settings.onboarding

import com.swipedelete.core.database.dao.SlotDao
import com.swipedelete.core.database.entity.SlotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [SlotDao]. The real one is a Room DAO; the view model's slot
 * persistence rules (five maximum, stable order, survive recreation) are
 * independent of Room, so they are exercised here on the host JVM.
 */
class FakeSlotDao : SlotDao {

    private val rows = LinkedHashMap<Int, SlotEntity>()

    override suspend fun upsert(slot: SlotEntity) {
        rows[slot.id] = slot
    }

    override suspend fun upsertAll(slots: List<SlotEntity>) {
        slots.forEach { upsert(it) }
    }

    override suspend fun all(): List<SlotEntity> = rows.values.sortedBy { it.sortOrder }

    override fun observeAll(): Flow<List<SlotEntity>> = flowOf(rows.values.sortedBy { it.sortOrder })

    override suspend fun byId(id: Int): SlotEntity? = rows[id]

    override suspend fun delete(id: Int) {
        rows.remove(id)
    }
}
