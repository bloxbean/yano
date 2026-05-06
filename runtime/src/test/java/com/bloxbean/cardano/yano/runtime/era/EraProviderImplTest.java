package com.bloxbean.cardano.yano.runtime.era;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.*;

class EraProviderImplTest {

    private File tempDir;
    private DirectRocksDBChainState chainState;

    // Preview-like epoch calc: epochLength=86400, byronSlotsPerEpoch=4320, firstNonByronSlot=0
    private static final EpochSlotCalc PREVIEW_CALC = new EpochSlotCalc(86400, 4320, 0);

    // Mainnet-like epoch calc
    private static final EpochSlotCalc MAINNET_CALC = new EpochSlotCalc(432000, 21600, 4492800);

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("era-service-test").toFile();
        chainState = new DirectRocksDBChainState(tempDir.getAbsolutePath());
    }

    @AfterEach
    void tearDown() {
        if (chainState != null) chainState.close();
        if (tempDir != null) deleteRecursively(tempDir);
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursively(c);
        }
        f.delete();
    }

    @Test
    void noEraMetadata_allEmpty() {
        var svc = new EraProviderImpl(chainState, PREVIEW_CALC);

        assertThat(svc.getEarliestKnownEra()).isEmpty();
        assertThat(svc.getStartEpoch(Era.Conway)).isEmpty();
        assertThat(svc.startsInOrAfter(Era.Conway)).isFalse();
        assertThat(svc.resolveFirstConwayEpochOrNull()).isNull();
        assertThat(svc.resolveKnownFirstEpochOrNull(Era.Conway.getValue())).isNull();
    }

    @Test
    void startsFromShelley_conwayNotReached() {
        chainState.setEraStartSlot(Era.Shelley.getValue(), 0);

        var svc = new EraProviderImpl(chainState, PREVIEW_CALC);

        assertThat(svc.getEarliestKnownEra()).hasValue(Era.Shelley);
        assertThat(svc.startsInOrAfter(Era.Conway)).isFalse();
        assertThat(svc.resolveFirstConwayEpochOrNull()).isNull();
    }

    @Test
    void conwayReachedLater_returnsConwayEpoch() {
        // Simulate: Shelley from slot 0, Conway at slot 55814400 (epoch 646 on preview)
        chainState.setEraStartSlot(Era.Shelley.getValue(), 0);
        long conwaySlot = 646L * 86400; // epoch 646
        chainState.setEraStartSlot(Era.Conway.getValue(), conwaySlot);

        var svc = new EraProviderImpl(chainState, PREVIEW_CALC);

        assertThat(svc.getStartEpoch(Era.Conway)).hasValue(646);
        assertThat(svc.resolveFirstConwayEpochOrNull()).isEqualTo(646);
        assertThat(svc.resolveKnownFirstEpochOrNull(Era.Conway.getValue())).isEqualTo(646);
    }

    @Test
    void startsInConway_returnsZero() {
        // Devnet starting directly in Conway
        chainState.setEraStartSlot(Era.Conway.getValue(), 0);

        var svc = new EraProviderImpl(chainState, PREVIEW_CALC);

        assertThat(svc.getEarliestKnownEra()).hasValue(Era.Conway);
        assertThat(svc.startsInOrAfter(Era.Conway)).isTrue();
        assertThat(svc.resolveFirstConwayEpochOrNull()).isEqualTo(0);
        assertThat(svc.resolveKnownFirstEpochOrNull(Era.Conway.getValue())).isZero();
    }

    @Test
    void startsAfterConway_dijkstra_returnsZero() {
        // Future devnet starting in a post-Conway era (no Conway slot, but era > Conway)
        // Simulate with era value 8 (hypothetical Dijkstra)
        chainState.setEraStartSlot(8, 0); // era value > Conway(7)

        var svc = new EraProviderImpl(chainState, PREVIEW_CALC);

        // Earliest known era has value 8, which is > Conway(7)
        assertThat(svc.startsInOrAfter(Era.Conway)).isTrue();
        assertThat(svc.resolveFirstConwayEpochOrNull()).isEqualTo(0);
        assertThat(svc.resolveKnownFirstEpochOrNull(Era.Conway.getValue())).isNull();
        // Era value 8 is not in the Era enum, so getEarliestKnownEra returns empty
        assertThat(svc.getEarliestKnownEra()).isEmpty();
        // But the numeric value is available
        assertThat(svc.getEarliestKnownEraValue()).hasValue(8);
    }

    @Test
    void inferredEarlierEraDoesNotHaveKnownStart() {
        chainState.setEraStartSlot(Era.Conway.getValue(), 0);

        var svc = new EraProviderImpl(chainState, PREVIEW_CALC);

        assertThat(svc.resolveFirstEpochOrNull(Era.Babbage.getValue())).isZero();
        assertThat(svc.resolveKnownFirstEpochOrNull(Era.Babbage.getValue())).isNull();
    }

    @Test
    void sanchonetBabbageStartSlotResolvesToEpochTwo() {
        chainState.setEraStartSlot(Era.Babbage.getValue(), 172827);

        var svc = new EraProviderImpl(chainState, PREVIEW_CALC);

        assertThat(svc.resolveKnownFirstEpochOrNull(Era.Babbage.getValue())).isEqualTo(2);
        assertThat(svc.resolveFirstEpochOrNull(Era.Babbage.getValue())).isEqualTo(2);
    }

    @Test
    void mainnet_conwayAtKnownSlot() {
        // Mainnet: Conway at some future slot
        chainState.setEraStartSlot(Era.Shelley.getValue(), 4492800);
        long conwaySlot = 4492800 + (208L * 432000); // hypothetical
        chainState.setEraStartSlot(Era.Conway.getValue(), conwaySlot);

        var svc = new EraProviderImpl(chainState, MAINNET_CALC);

        assertThat(svc.getStartEpoch(Era.Conway)).isPresent();
        assertThat(svc.resolveFirstConwayEpochOrNull()).isNotNull();
    }

    @Test
    void getStartSlot_delegatesToChainState() {
        chainState.setEraStartSlot(Era.Allegra.getValue(), 12345);

        var svc = new EraProviderImpl(chainState, PREVIEW_CALC);

        assertThat(svc.getStartSlot(Era.Allegra)).hasValue(12345);
        assertThat(svc.getStartSlot(Era.Conway)).isEmpty();
    }
}
