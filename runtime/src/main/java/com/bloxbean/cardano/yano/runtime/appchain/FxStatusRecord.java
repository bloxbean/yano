package com.bloxbean.cardano.yano.runtime.appchain;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.List;

/**
 * Node-local execution progress of one effect (ADR app-layer/010 F3, runtime
 * tier). Never replicated, never in any root; two nodes legitimately disagree
 * about these values. Wall-clock timestamps are fine — this is the execution
 * plane.
 *
 * @param status          runtime status (see constants)
 * @param attempts        attempts made on this node
 * @param nextAttemptAt   epoch millis before which no retry fires (backoff)
 * @param lastError       last failure, for operators ("" = none)
 * @param submittedRef    external ref of an in-flight Submitted action
 * @param externalRef     external ref of the final outcome (DONE)
 * @param updatedAt       epoch millis of the last transition
 */
record FxStatusRecord(int status,
                      int attempts,
                      long nextAttemptAt,
                      String lastError,
                      byte[] submittedRef,
                      byte[] externalRef,
                      long updatedAt,
                      int outcomeCode,
                      long injectedAt) {

    /** No local outcome yet. */
    static final int OUTCOME_NONE = 0;
    /** External action confirmed — inject CONFIRMED for CHAIN effects (FX-M3). */
    static final int OUTCOME_CONFIRMED = 1;
    /** Definitive external failure — inject FAILED for CHAIN effects (FX-M3). */
    static final int OUTCOME_FAILED = 2;

    /** Discovered, waiting for gate/dispatch. */
    static final int PENDING = 0;
    /** Failed attempt(s); waiting out backoff. */
    static final int RETRY = 2;
    /** Long-running action in flight (executor re-polled each tick). */
    static final int SUBMITTED = 3;
    /** Locally complete (NONE-policy terminal; CHAIN outcome recorded, result loop in FX-M3). */
    static final int DONE = 4;
    /** Poison lane: attempt cap or non-retryable failure; operator requeue/cancel (F9). */
    static final int PARKED = 5;
    /** Open effect predating executor enablement; operator must requeue explicitly (F10). */
    static final int QUARANTINED = 6;
    /** Operator chose not to execute (rebuild/backfill policy). */
    static final int SKIPPED = 7;
    /** Claimed by an EXTERNAL executor (FX-M4); lease deadline in nextAttemptAt. */
    static final int EXTERNAL = 8;

    FxStatusRecord {
        lastError = lastError != null ? lastError : "";
        submittedRef = submittedRef != null ? submittedRef : new byte[0];
        externalRef = externalRef != null ? externalRef : new byte[0];
    }

    static FxStatusRecord pending() {
        return new FxStatusRecord(PENDING, 0, 0, "", null, null, System.currentTimeMillis(),
                OUTCOME_NONE, 0);
    }

    boolean locallyTerminal() {
        return status == DONE || status == SKIPPED;
    }

    boolean executable() {
        // An EXTERNAL claim is re-eligible once its lease (nextAttemptAt)
        // expires — dispatch/claim honor nextAttemptAt before acting
        return status == PENDING || status == RETRY || status == SUBMITTED || status == EXTERNAL;
    }

    /** External claim: leased to {@code executorId} until {@code leaseUntilMillis}. */
    FxStatusRecord external(String executorId, long leaseUntilMillis) {
        return new FxStatusRecord(EXTERNAL, attempts, leaseUntilMillis, executorId, submittedRef,
                null, System.currentTimeMillis(), OUTCOME_NONE, injectedAt);
    }

    /** A REAL failed attempt — the only transition that consumes attempt budget. */
    FxStatusRecord retry(String error, long nextAt) {
        return new FxStatusRecord(RETRY, attempts + 1, nextAt, error, submittedRef, null,
                System.currentTimeMillis(), OUTCOME_NONE, injectedAt);
    }

    /** Precondition not met (EffectExecution.Retry) — no attempt consumed. */
    FxStatusRecord deferred(String reason, long nextAt) {
        return new FxStatusRecord(RETRY, attempts, nextAt, reason, submittedRef, null,
                System.currentTimeMillis(), OUTCOME_NONE, injectedAt);
    }

    /** Long-running action started — polling it is not an attempt. */
    FxStatusRecord submitted(byte[] ref) {
        return new FxStatusRecord(SUBMITTED, attempts, 0, "", ref, null,
                System.currentTimeMillis(), OUTCOME_NONE, injectedAt);
    }

    FxStatusRecord done(byte[] ref) {
        return new FxStatusRecord(DONE, attempts + 1, 0, "", submittedRef, ref,
                System.currentTimeMillis(), OUTCOME_CONFIRMED, injectedAt);
    }

    /** Definitive external failure of a CHAIN effect — local terminal, FAILED injectable. */
    FxStatusRecord doneFailed(String reason) {
        return new FxStatusRecord(DONE, attempts + 1, 0, reason, submittedRef, null,
                System.currentTimeMillis(), OUTCOME_FAILED, injectedAt);
    }

    FxStatusRecord parked(String error) {
        return new FxStatusRecord(PARKED, attempts + 1, 0, error, submittedRef, null,
                System.currentTimeMillis(), OUTCOME_NONE, injectedAt);
    }

    FxStatusRecord requeued() {
        return new FxStatusRecord(PENDING, attempts, 0, lastError, submittedRef, null,
                System.currentTimeMillis(), OUTCOME_NONE, injectedAt);
    }

    FxStatusRecord injected(long atMillis) {
        return new FxStatusRecord(status, attempts, nextAttemptAt, lastError, submittedRef,
                externalRef, updatedAt, outcomeCode, atMillis);
    }

    static FxStatusRecord quarantined() {
        return new FxStatusRecord(QUARANTINED, 0, 0, "", null, null, System.currentTimeMillis(),
                OUTCOME_NONE, 0);
    }

    String statusName() {
        return switch (status) {
            case PENDING -> "PENDING";
            case RETRY -> "RETRY";
            case SUBMITTED -> "SUBMITTED";
            case DONE -> "DONE";
            case PARKED -> "PARKED";
            case QUARANTINED -> "QUARANTINED";
            case SKIPPED -> "SKIPPED";
            case EXTERNAL -> "EXTERNAL";
            default -> "UNKNOWN(" + status + ")";
        };
    }

    byte[] encode() {
        Array arr = new Array();
        arr.add(new UnsignedInteger(status));
        arr.add(new UnsignedInteger(attempts));
        arr.add(new UnsignedInteger(Math.max(0, nextAttemptAt)));
        arr.add(new UnicodeString(lastError));
        arr.add(new ByteString(submittedRef));
        arr.add(new ByteString(externalRef));
        arr.add(new UnsignedInteger(Math.max(0, updatedAt)));
        arr.add(new UnsignedInteger(outcomeCode));
        arr.add(new UnsignedInteger(Math.max(0, injectedAt)));
        return CborSerializationUtil.serialize(arr);
    }

    static FxStatusRecord decode(byte[] bytes) {
        Array arr = (Array) CborSerializationUtil.deserializeOne(bytes);
        List<DataItem> items = arr.getDataItems();
        return new FxStatusRecord(
                ((UnsignedInteger) items.get(0)).getValue().intValue(),
                ((UnsignedInteger) items.get(1)).getValue().intValue(),
                ((UnsignedInteger) items.get(2)).getValue().longValue(),
                ((UnicodeString) items.get(3)).getString(),
                ((ByteString) items.get(4)).getBytes(),
                ((ByteString) items.get(5)).getBytes(),
                ((UnsignedInteger) items.get(6)).getValue().longValue(),
                // Lenient on pre-M3 rows (runtime CF is node-local/disposable)
                items.size() > 7 ? ((UnsignedInteger) items.get(7)).getValue().intValue() : OUTCOME_NONE,
                items.size() > 8 ? ((UnsignedInteger) items.get(8)).getValue().longValue() : 0);
    }
}
