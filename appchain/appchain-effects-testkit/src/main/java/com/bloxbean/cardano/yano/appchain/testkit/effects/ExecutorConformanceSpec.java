package com.bloxbean.cardano.yano.appchain.testkit.effects;

import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Connector-specific adapters consumed by {@link EffectExecutorConformance}. */
public interface ExecutorConformanceSpec {
    /**
     * Returns the exact effect type claimed by the executor.
     *
     * @return the stable connector action type
     */
    String actionType();

    /**
     * Returns a canonical command payload that should succeed.
     *
     * @return the valid command bytes
     */
    byte[] validPayload();

    /**
     * Returns named invalid and boundary payloads with expected classifications.
     *
     * @return a non-empty immutable or caller-owned case list
     */
    List<PayloadCase> invalidPayloads();

    /**
     * Returns secret canaries that must not appear in snapshots or captured logs.
     *
     * @return bounded forbidden sentinel strings
     */
    Set<String> forbiddenSentinels();

    /**
     * Declares the connector's honest unknown-acknowledgement guarantee.
     *
     * @return the external idempotency model
     */
    IdempotencyModel idempotencyModel();

    /**
     * Reports whether the fixture implements an optional scenario.
     *
     * @param scenario the scenario queried by the suite
     * @return {@code true} when {@link #open(ExecutorScenario, byte[])} supports it
     */
    default boolean supports(ExecutorScenario scenario) {
        return scenario == ExecutorScenario.SUCCESS;
    }

    /**
     * Opens one fresh executor, provider client, and external probe fixture.
     *
     * @param scenario the provider-independent behavior to simulate
     * @param payload the payload installed in the pending effect
     * @return the fresh fixture
     * @throws Exception when fixture construction unexpectedly fails
     */
    ExecutorFixture open(ExecutorScenario scenario, byte[] payload) throws Exception;

    /**
     * Recreate the executor/client after a simulated process restart while
     * preserving the same external provider state and effect identity.
     *
     * @param previous the closed pre-restart fixture
     * @param scenario the reconciliation scenario to resume
     * @param payload the original effect payload
     * @return a fresh executor sharing the previous external probe state
     * @throws Exception when fixture reconstruction unexpectedly fails
     */
    ExecutorFixture restart(ExecutorFixture previous,
                            ExecutorScenario scenario,
                            byte[] payload) throws Exception;

    /**
     * Deliberately fails partial construction and exposes owned-resource accounting.
     *
     * @return the failure, resource counters, diagnostics, and captured logs
     * @throws Exception when the fixture cannot perform the deliberate failure scenario
     */
    FailedConstructionObservation failedConstruction() throws Exception;

    /**
     * Performs strict connector receipt decode and canonical re-encode assertions.
     *
     * @param canonicalExternalRef the confirmed external reference to verify
     */
    void verifyReceipt(byte[] canonicalExternalRef);

    /**
     * Performs strict decode and canonical re-encode for a non-empty submitted handle.
     *
     * @param canonicalSubmittedRef the submitted external reference to verify
     */
    void verifySubmittedRef(byte[] canonicalSubmittedRef);

    /**
     * Verify connector-specific semantic agreement between the command,
     * authenticated receipt, and decoded durable detail. This hook must not
     * perform provider I/O.
     *
     * @param validPayload canonical command payload used by the fixture
     * @param canonicalExternalRef canonical confirmed receipt
     * @param detailDocument decoded and effect-bound durable detail document
     */
    void verifyReceiptDetailConsistency(byte[] validPayload,
                                        byte[] canonicalExternalRef,
                                        ConnectorDetailDocumentV1 detailDocument);

    /**
     * Returns the maximum time allowed for executor and fixture cleanup.
     *
     * @return a positive bounded timeout
     */
    default Duration closeTimeout() {
        return Duration.ofSeconds(2);
    }
}
