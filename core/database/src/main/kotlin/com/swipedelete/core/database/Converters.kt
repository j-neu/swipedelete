package com.swipedelete.core.database

import androidx.room.TypeConverter
import com.swipedelete.core.model.DeckStatus
import com.swipedelete.core.model.JournalState
import com.swipedelete.core.model.OpKind
import com.swipedelete.core.model.ProtectedRootKind
import com.swipedelete.core.model.SlotMode
import com.swipedelete.core.model.SourceKind
import com.swipedelete.core.model.SourceRef

/**
 * Every enum column is stored as its [Enum.name] (e.g. `'PENDING'`) so that
 * hand-written SQL literals such as `WHERE state = 'PENDING'` match exactly.
 * Room passes `null` through for nullable columns.
 */
class Converters {
    @TypeConverter fun journalStateToString(value: JournalState): String = value.name
    @TypeConverter fun stringToJournalState(value: String): JournalState = JournalState.valueOf(value)

    @TypeConverter fun opKindToString(value: OpKind): String = value.name
    @TypeConverter fun stringToOpKind(value: String): OpKind = OpKind.valueOf(value)

    @TypeConverter fun sourceKindToString(value: SourceKind): String = value.name
    @TypeConverter fun stringToSourceKind(value: String): SourceKind = SourceKind.valueOf(value)

    @TypeConverter fun accessToString(value: SourceRef.Access): String = value.name
    @TypeConverter fun stringToAccess(value: String): SourceRef.Access = SourceRef.Access.valueOf(value)

    @TypeConverter fun slotModeToString(value: SlotMode): String = value.name
    @TypeConverter fun stringToSlotMode(value: String): SlotMode = SlotMode.valueOf(value)

    @TypeConverter fun deckStatusToString(value: DeckStatus): String = value.name
    @TypeConverter fun stringToDeckStatus(value: String): DeckStatus = DeckStatus.valueOf(value)

    @TypeConverter fun protectedRootKindToString(value: ProtectedRootKind): String = value.name
    @TypeConverter fun stringToProtectedRootKind(value: String): ProtectedRootKind = ProtectedRootKind.valueOf(value)
}
