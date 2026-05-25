package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.VrfCert;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link NonceEvolutionListener} threads TPraos extraEntropy through the
 * TICKN transition and that targeted WARNs fire only when appropriate.
 */
class NonceEvolutionListenerExtraEntropyTest {

    private static final long EPOCH_LENGTH = 10;
    private static final long MAINNET_MAGIC = 764824073L;
    private static final long CUSTOM_MAGIC = 42L;
    private static final String ENTROPY_HEX =
            "d982e06fd33e7440b43cefad529b7ecafbaa255e38178ad4189a37e4ce9bf1fa";
    private static final byte[] ENTROPY_BYTES = HexUtil.decodeHexString(ENTROPY_HEX);

    private ListAppender listAppender;

    @BeforeEach
    void attachAppender() {
        listAppender = new ListAppender();
        LogManager.getLogger(NonceEvolutionListener.class).addAppender(listAppender);
    }

    @AfterEach
    void detachAppender() {
        LogManager.getLogger(NonceEvolutionListener.class).removeAppender(listAppender);
    }

    @Test
    void tpraosBoundary_queriesProviderAndAppliesEntropy() {
        // Property: TICKN result with entropy = combineNonces(TICKN-baseline, entropy)
        // Verify by running the boundary twice with/without entropy and comparing.
        EpochNonceState withEntropyState = freshState();
        CountingProvider withEntropyProvider = new CountingProvider(Map.of(1, ENTROPY_HEX));
        NonceEvolutionListener withEntropyListener = new NonceEvolutionListener(
                withEntropyState, null, null, withEntropyProvider, true, CUSTOM_MAGIC);

        EpochNonceState baselineState = freshState();
        CountingProvider emptyProvider = new CountingProvider(Map.of());
        NonceEvolutionListener baselineListener = new NonceEvolutionListener(
                baselineState, null, null, emptyProvider, true, CUSTOM_MAGIC);

        // Drive identical block sequences
        withEntropyListener.onBlockApplied(blockEvent(0, null, Era.Shelley, 2));
        baselineListener.onBlockApplied(blockEvent(0, null, Era.Shelley, 2));
        withEntropyListener.onBlockApplied(blockEvent(EPOCH_LENGTH, "aa".repeat(32), Era.Shelley, 3));
        baselineListener.onBlockApplied(blockEvent(EPOCH_LENGTH, "aa".repeat(32), Era.Shelley, 3));

        assertThat(withEntropyState.getCurrentEpoch()).isEqualTo(1);
        assertThat(withEntropyProvider.queriedEpochs).containsExactly(1);

        // After the boundary, evolving/lab continue to update from the slot=EPOCH_LENGTH block.
        // But epochNonce is set ONLY by performTickn and not re-derived afterwards.
        // So we can directly compare epochNonce values between the two states.
        byte[] baseline = baselineState.getEpochNonce();
        byte[] withEntropy = withEntropyState.getEpochNonce();
        assertThat(withEntropy).containsExactly(
                EpochNonceState.combineNonces(baseline, ENTROPY_BYTES));
    }

    @Test
    void babbagePlusBoundary_doesNotQueryProvider() {
        EpochNonceState state = freshState();
        CountingProvider provider = new CountingProvider(Map.of(1, ENTROPY_HEX));
        NonceEvolutionListener listener = new NonceEvolutionListener(
                state, null, null, provider, true, CUSTOM_MAGIC);

        listener.onBlockApplied(blockEvent(0, null, Era.Babbage, 2));
        listener.onBlockApplied(blockEvent(EPOCH_LENGTH, "aa".repeat(32), Era.Babbage, 3));

        // Provider must not be queried at all on Babbage+ boundaries.
        assertThat(provider.queriedEpochs).isEmpty();
        assertNoWarn();
    }

    @Test
    void nullEra_atBoundary_emitsWarnAndDoesNotQuery() {
        EpochNonceState state = freshState();
        CountingProvider provider = new CountingProvider(Map.of(1, ENTROPY_HEX));
        NonceEvolutionListener listener = new NonceEvolutionListener(
                state, null, null, provider, true, CUSTOM_MAGIC);

        listener.onBlockApplied(blockEvent(0, null, Era.Shelley, 2));
        // Cross boundary with era=null (data-resolution failure)
        listener.onBlockApplied(blockEvent(EPOCH_LENGTH, "aa".repeat(32), /*era*/ null, 3));

        assertThat(provider.queriedEpochs).isEmpty(); // not queried in null-era branch
        assertWarnContaining("unknown era");
    }

    @Test
    void malformedEntropy_gracefullySkipped() {
        EpochNonceState withMalformed = freshState();
        CountingProvider malformedProvider = new CountingProvider(Map.of(1, "not-hex-at-all"));
        NonceEvolutionListener malformedListener = new NonceEvolutionListener(
                withMalformed, null, null, malformedProvider, true, CUSTOM_MAGIC);

        EpochNonceState baseline = freshState();
        NonceEvolutionListener baselineListener = new NonceEvolutionListener(
                baseline, null, null, new CountingProvider(Map.of()), true, CUSTOM_MAGIC);

        malformedListener.onBlockApplied(blockEvent(0, null, Era.Mary, 2));
        baselineListener.onBlockApplied(blockEvent(0, null, Era.Mary, 2));
        malformedListener.onBlockApplied(blockEvent(EPOCH_LENGTH, "aa".repeat(32), Era.Mary, 3));
        baselineListener.onBlockApplied(blockEvent(EPOCH_LENGTH, "aa".repeat(32), Era.Mary, 3));

        // Malformed entropy is decoded to null → no entropy applied → epochNonce matches baseline
        assertThat(withMalformed.getEpochNonce()).containsExactly(baseline.getEpochNonce());
        assertWarnContaining("Malformed extraEntropy");
    }

    @Test
    void mainnetEpoch259_missingEntropy_emitsHardGuardWarn() {
        // Set epoch length so epoch boundary lands at 259*EPOCH_LENGTH; we can't actually drive
        // 259 epochs of blocks. Instead seed state via seedFromExternal to epoch 258, then cross
        // into 259 with a Shelley block.
        EpochNonceState state = new EpochNonceState(EPOCH_LENGTH, 1, 1.0);
        state.seedFromExternal(258, HexUtil.decodeHexString("11".repeat(32)));
        CountingProvider provider = new CountingProvider(Map.of()); // missing entry for 259
        NonceEvolutionListener listener = new NonceEvolutionListener(
                state, null, null, provider, /*tracked*/ true, MAINNET_MAGIC);

        long slot259 = 259L * EPOCH_LENGTH;
        listener.onBlockApplied(blockEvent(slot259, "bb".repeat(32), Era.Alonzo, 5));

        assertWarnContaining("Mainnet epoch 259 extraEntropy is missing");
        // Hard guard fires even when tracked=true
    }

    @Test
    void mainnetEpoch259_withEntropy_noHardGuardWarn() {
        EpochNonceState state = new EpochNonceState(EPOCH_LENGTH, 1, 1.0);
        state.seedFromExternal(258, HexUtil.decodeHexString("11".repeat(32)));
        CountingProvider provider = new CountingProvider(Map.of(259, ENTROPY_HEX));
        NonceEvolutionListener listener = new NonceEvolutionListener(
                state, null, null, provider, true, MAINNET_MAGIC);

        long slot259 = 259L * EPOCH_LENGTH;
        listener.onBlockApplied(blockEvent(slot259, "bb".repeat(32), Era.Alonzo, 5));

        assertNoWarnContaining("Mainnet epoch 259 extraEntropy is missing");
    }

    @Test
    void mainnetPre259_TPraosBoundary_doesNotEmitHardGuard() {
        EpochNonceState state = new EpochNonceState(EPOCH_LENGTH, 1, 1.0);
        state.seedFromExternal(209, HexUtil.decodeHexString("11".repeat(32)));
        CountingProvider provider = new CountingProvider(Map.of()); // no entropy anywhere
        NonceEvolutionListener listener = new NonceEvolutionListener(
                state, null, null, provider, true, MAINNET_MAGIC);

        long slot210 = 210L * EPOCH_LENGTH;
        listener.onBlockApplied(blockEvent(slot210, "bb".repeat(32), Era.Shelley, 5));

        assertNoWarnContaining("Mainnet epoch 259");
        assertNoWarnContaining("First TPraos boundary"); // mainnet excluded from custom-network heuristic
    }

    @Test
    void customNetwork_firstTPraosBoundary_withoutTracking_warnsOnce() {
        EpochNonceState state = freshState();
        CountingProvider provider = new CountingProvider(Map.of()); // no entropy entries
        NonceEvolutionListener listener = new NonceEvolutionListener(
                state, null, null, provider, /*tracked*/ false, CUSTOM_MAGIC);

        listener.onBlockApplied(blockEvent(0, null, Era.Shelley, 2));
        // Cross 1st TPraos boundary
        listener.onBlockApplied(blockEvent(EPOCH_LENGTH, "aa".repeat(32), Era.Shelley, 3));
        // Cross 2nd TPraos boundary — should NOT warn again
        listener.onBlockApplied(blockEvent(2 * EPOCH_LENGTH, "bb".repeat(32), Era.Shelley, 4));

        long matches = listAppender.events.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getRenderedMessage().contains("First TPraos boundary"))
                .count();
        assertThat(matches).isEqualTo(1L);
    }

    @Test
    void customNetwork_withTracking_noFirstBoundaryWarn() {
        EpochNonceState state = freshState();
        CountingProvider provider = new CountingProvider(Map.of());
        NonceEvolutionListener listener = new NonceEvolutionListener(
                state, null, null, provider, /*tracked*/ true, CUSTOM_MAGIC);

        listener.onBlockApplied(blockEvent(0, null, Era.Shelley, 2));
        listener.onBlockApplied(blockEvent(EPOCH_LENGTH, "aa".repeat(32), Era.Shelley, 3));

        assertNoWarnContaining("First TPraos boundary");
    }

    // --- Helpers ---

    private static EpochNonceState freshState() {
        EpochNonceState state = new EpochNonceState(EPOCH_LENGTH, 1, 1.0);
        state.initFromGenesisHash(bytes(32, 1));
        return state;
    }

    private static BlockAppliedEvent blockEvent(long slot, String prevHash, Era era, int vrfSeed) {
        var vrf = VrfCert.builder()
                ._1(HexUtil.encodeHexString(bytes(64, vrfSeed)))
                .build();
        var headerBody = HeaderBody.builder()
                .slot(slot)
                .blockNumber(slot + 1)
                .prevHash(prevHash)
                .vrfResult(vrf)
                .build();
        var header = BlockHeader.builder()
                .headerBody(headerBody)
                .build();
        var block = Block.builder()
                .era(era != null ? era : Era.Babbage) // Block.era is non-null in model; listener uses event.era()
                .header(header)
                .build();
        return new BlockAppliedEvent(era, slot, slot + 1, "cc".repeat(32), block);
    }

    private static byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }

    private void assertNoWarn() {
        boolean anyWarn = listAppender.events.stream().anyMatch(e -> e.getLevel() == Level.WARN);
        assertThat(anyWarn).as("no WARN logs expected, got: %s", warnMessages()).isFalse();
    }

    private void assertWarnContaining(String fragment) {
        boolean found = listAppender.events.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .anyMatch(e -> e.getRenderedMessage().contains(fragment));
        assertThat(found).as("expected WARN containing '%s', got: %s", fragment, warnMessages()).isTrue();
    }

    private void assertNoWarnContaining(String fragment) {
        boolean found = listAppender.events.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .anyMatch(e -> e.getRenderedMessage().contains(fragment));
        assertThat(found).as("did not expect WARN containing '%s', got: %s", fragment, warnMessages()).isFalse();
    }

    private List<String> warnMessages() {
        List<String> out = new ArrayList<>();
        for (LoggingEvent e : listAppender.events) {
            if (e.getLevel() == Level.WARN) out.add(e.getRenderedMessage());
        }
        return out;
    }

    /** Minimal {@link EpochParamProvider} stub that records queried epochs. */
    private static final class CountingProvider implements EpochParamProvider {
        private final Map<Integer, String> entropyByEpoch;
        final List<Integer> queriedEpochs = new ArrayList<>();

        CountingProvider(Map<Integer, String> entropyByEpoch) {
            this.entropyByEpoch = entropyByEpoch;
        }

        @Override
        public String getExtraEntropy(long epoch) {
            queriedEpochs.add((int) epoch);
            return entropyByEpoch.get((int) epoch);
        }

        // The only non-default methods on EpochParamProvider.
        @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.ZERO; }
        @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.ZERO; }
    }

    /** Minimal log4j appender capturing events into a list. */
    private static final class ListAppender extends AppenderSkeleton {
        final List<LoggingEvent> events = new ArrayList<>();

        @Override protected void append(LoggingEvent event) { events.add(event); }
        @Override public void close() { /* no-op */ }
        @Override public boolean requiresLayout() { return false; }
    }
}
