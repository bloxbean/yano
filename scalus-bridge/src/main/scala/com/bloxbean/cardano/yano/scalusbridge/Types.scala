package com.bloxbean.cardano.yano.scalusbridge

/**
 * Result of ledger validation (transit).
 * Java-facing — no Scala types exposed.
 */
class TransitResult(
    val isSuccess: Boolean,
    val errorMessage: String,
    val errorClassName: String
)
