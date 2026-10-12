package com.swipedelete.feature.dashboard.quarantine

/**
 * The two-step ladder in front of the only permanent delete in the app (§6.6).
 * [confirm] is the sole source of `allowPermanent = true`: it is true only at the
 * typing step, with the exact item count typed. Everything else returns false.
 */
class EmptyQuarantineConfirmation(private val itemCount: Int) {

    enum class Step { Idle, Warning, TypeCount }

    var step: Step = Step.Idle
        private set

    private var typed: String = ""

    val warning: String =
        "This permanently deletes $itemCount quarantined item${if (itemCount == 1) "" else "s"}. " +
            "It cannot be undone."

    fun begin() {
        if (itemCount > 0 && step == Step.Idle) step = Step.Warning
    }

    fun proceed() {
        if (step == Step.Warning) step = Step.TypeCount
    }

    fun type(text: String) {
        if (step == Step.TypeCount) typed = text
    }

    fun cancel() {
        step = Step.Idle
        typed = ""
    }

    /** True only when permanent deletion has been confirmed; resets the flow. */
    fun confirm(): Boolean {
        val allowed = step == Step.TypeCount && typed.trim() == itemCount.toString()
        if (allowed) cancel()
        return allowed
    }
}
