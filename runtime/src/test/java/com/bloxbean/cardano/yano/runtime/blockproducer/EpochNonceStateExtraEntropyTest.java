package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for TPraos extraEntropy support in {@link EpochNonceState#performTickn}.
 * <p>
 * Tests use the snapshot/restore approach to verify the formula *property*
 * (entropy is incorporated via {@code combineNonces} for TPraos eras, ignored
 * elsewhere) without needing to predict internal candidate/ticknPrev values.
 */
class EpochNonceStateExtraEntropyTest {

    private static final long EPOCH_LENGTH = 600;
    private static final long SECURITY_PARAM = 100;
    private static final double ACTIVE_SLOTS_COEFF = 1.0;

    /** Mainnet epoch 259 entropy — the only public-network non-null extraEntropy on record. */
    private static final byte[] MAINNET_259_ENTROPY = HexUtil.decodeHexString(
            "d982e06fd33e7440b43cefad529b7ecafbaa255e38178ad4189a37e4ce9bf1fa");

    private static final byte[] FIXTURE_PREV_HASH =
            Blake2bUtil.blake2bHash256("test-prev-block".getBytes());

    private EpochNonceState state;

    @BeforeEach
    void setUp() {
        state = new EpochNonceState(EPOCH_LENGTH, SECURITY_PARAM, ACTIVE_SLOTS_COEFF);
        state.initFromGenesis("entropy-test-genesis".getBytes());
    }

    @Test
    void nullEntropy_TPraosEra_matchesLegacyFormula() {
        driveOneBlockInEpoch0(Era.Shelley);
        byte[] preTickn = state.serialize();

        // New API with null entropy
        state.advanceEpochIfNeeded(EPOCH_LENGTH, null, Era.Shelley);
        byte[] withNullEntropy = state.getEpochNonce();

        // Legacy no-arg overload
        state.restore(preTickn);
        state.advanceEpochIfNeeded(EPOCH_LENGTH);
        byte[] legacy = state.getEpochNonce();

        assertThat(withNullEntropy).containsExactly(legacy);
        assertThat(state.getCurrentEpoch()).isEqualTo(1);
    }

    @Test
    void nonNullEntropy_TPraosEra_appliesEntropy() {
        driveOneBlockInEpoch0(Era.Shelley);
        byte[] preTickn = state.serialize();

        // Baseline: TICKN without entropy
        state.advanceEpochIfNeeded(EPOCH_LENGTH, null, Era.Shelley);
        byte[] baseline = state.getEpochNonce();

        // With entropy
        state.restore(preTickn);
        state.advanceEpochIfNeeded(EPOCH_LENGTH, MAINNET_259_ENTROPY, Era.Shelley);
        byte[] withEntropy = state.getEpochNonce();

        // Property: with-entropy result = combineNonces(baseline, entropy)
        assertThat(withEntropy).containsExactly(
                EpochNonceState.combineNonces(baseline, MAINNET_259_ENTROPY));
        assertThat(Arrays.equals(baseline, withEntropy)).isFalse();
    }

    @Test
    void nonNullEntropy_AlonzoEra_appliesEntropy() {
        driveOneBlockInEpoch0(Era.Alonzo);
        byte[] preTickn = state.serialize();

        state.advanceEpochIfNeeded(EPOCH_LENGTH, null, Era.Alonzo);
        byte[] baseline = state.getEpochNonce();

        state.restore(preTickn);
        state.advanceEpochIfNeeded(EPOCH_LENGTH, MAINNET_259_ENTROPY, Era.Alonzo);

        assertThat(state.getEpochNonce()).containsExactly(
                EpochNonceState.combineNonces(baseline, MAINNET_259_ENTROPY));
    }

    @Test
    void nonNullEntropy_BabbageEra_ignoresEntropy() {
        driveOneBlockInEpoch0(Era.Babbage);
        byte[] preTickn = state.serialize();

        state.advanceEpochIfNeeded(EPOCH_LENGTH, null, Era.Babbage);
        byte[] baseline = state.getEpochNonce();

        state.restore(preTickn);
        state.advanceEpochIfNeeded(EPOCH_LENGTH, MAINNET_259_ENTROPY, Era.Babbage);

        assertThat(state.getEpochNonce()).containsExactly(baseline);
    }

    @Test
    void nonNullEntropy_ConwayEra_ignoresEntropy() {
        driveOneBlockInEpoch0(Era.Conway);
        byte[] preTickn = state.serialize();

        state.advanceEpochIfNeeded(EPOCH_LENGTH, null, Era.Conway);
        byte[] baseline = state.getEpochNonce();

        state.restore(preTickn);
        state.advanceEpochIfNeeded(EPOCH_LENGTH, MAINNET_259_ENTROPY, Era.Conway);

        assertThat(state.getEpochNonce()).containsExactly(baseline);
    }

    @Test
    void nonNullEntropy_nullEra_ignoresEntropy_noSilentApply() {
        // Pre-Babbage era block to set up candidate, then cross with unknown era
        driveOneBlockInEpoch0(Era.Mary);
        byte[] preTickn = state.serialize();

        state.advanceEpochIfNeeded(EPOCH_LENGTH, null, Era.Mary);
        byte[] baseline = state.getEpochNonce();

        state.restore(preTickn);
        state.advanceEpochIfNeeded(EPOCH_LENGTH, MAINNET_259_ENTROPY, /*era*/ null);

        // No fallback to TPraos for null era — entropy must be ignored
        assertThat(state.getEpochNonce()).containsExactly(baseline);
    }

    @Test
    void backwardCompatOverload_advanceWithoutEntropyArgs_stillWorks() {
        driveOneBlockInEpoch0(Era.Conway);
        int epochBefore = state.getCurrentEpoch();

        state.advanceEpochIfNeeded(EPOCH_LENGTH);

        assertThat(state.getCurrentEpoch()).isEqualTo(epochBefore + 1);
        assertThat(state.getEpochNonce()).isNotEmpty();
    }

    @Test
    void usesTPraosExtraEntropy_eraSemantics() {
        assertThat(EpochNonceState.usesTPraosExtraEntropy(Era.Shelley)).isTrue();
        assertThat(EpochNonceState.usesTPraosExtraEntropy(Era.Allegra)).isTrue();
        assertThat(EpochNonceState.usesTPraosExtraEntropy(Era.Mary)).isTrue();
        assertThat(EpochNonceState.usesTPraosExtraEntropy(Era.Alonzo)).isTrue();
        assertThat(EpochNonceState.usesTPraosExtraEntropy(Era.Babbage)).isFalse();
        assertThat(EpochNonceState.usesTPraosExtraEntropy(Era.Conway)).isFalse();
        assertThat(EpochNonceState.usesTPraosExtraEntropy(null)).isFalse(); // explicit: no fallback
    }

    /**
     * Drive exactly one block in epoch 0 with deterministic inputs so candidate
     * has evolved before the boundary cross.
     */
    private void driveOneBlockInEpoch0(Era era) {
        byte[] vrfOutput = new byte[64];
        vrfOutput[0] = 0x42;
        state.onBlockObserved(/*slot*/ 10, FIXTURE_PREV_HASH, vrfOutput, era);
    }
}
