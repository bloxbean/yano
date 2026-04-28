package com.bloxbean.cardano.yano.app.api;

import com.bloxbean.cardano.yano.api.config.NodeConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EpochUtil} — verifies era-aware epoch/slot math via NodeConfig.
 */
class EpochUtilTest {

    private static NodeConfig config(long epochLength, long byronSlotsPerEpoch, long firstNonByronSlot) {
        return new NodeConfig() {
            @Override public void validate() {}
            @Override public boolean isClientEnabled() { return false; }
            @Override public boolean isServerEnabled() { return false; }
            @Override public long getProtocolMagic() { return 0; }
            @Override public long getEpochLength() { return epochLength; }
            @Override public long getByronSlotsPerEpoch() { return byronSlotsPerEpoch; }
            @Override public long getFirstNonByronSlot() { return firstNonByronSlot; }
        };
    }

    @Test
    void mainnet_slot4492800_isEpoch208() {
        var cfg = config(432000, 21600, 4492800);
        assertEquals(208, EpochUtil.slotToEpoch(4492800, cfg));
    }

    @Test
    void mainnet_lastByronSlot_isEpoch207() {
        var cfg = config(432000, 21600, 4492800);
        assertEquals(207, EpochUtil.slotToEpoch(4492799, cfg));
    }

    @Test
    void preview_slot864004_isEpoch10() {
        var cfg = config(86400, 4320, 0);
        assertEquals(10, EpochUtil.slotToEpoch(864004, cfg));
    }

    @Test
    void preview_slot0_isEpoch0() {
        var cfg = config(86400, 4320, 0);
        assertEquals(0, EpochUtil.slotToEpoch(0, cfg));
    }

    @Test
    void preprod_slot86400_isEpoch4() {
        var cfg = config(432000, 21600, 86400);
        assertEquals(4, EpochUtil.slotToEpoch(86400, cfg));
    }

    @Test
    void epochSlot_mainnet_shelleyStart_is0() {
        var cfg = config(432000, 21600, 4492800);
        assertEquals(0, EpochUtil.slotToEpochSlot(4492800, cfg));
    }

    @Test
    void epochSlot_mainnet_lastByronSlot_is21599() {
        var cfg = config(432000, 21600, 4492800);
        assertEquals(21599, EpochUtil.slotToEpochSlot(4492799, cfg));
    }
}
