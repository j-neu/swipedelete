package com.swipedelete.core.storage

sealed interface DeleteResult {
    data class Deleted(val count: Int) : DeleteResult
    data class Partial(val deleted: Int, val failures: List<Failure>) : DeleteResult
}
