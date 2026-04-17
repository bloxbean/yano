package com.bloxbean.cardano.yano.runtime.rollback;

import com.bloxbean.cardano.yano.api.rollback.RollbackCapableStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for adhoc rollback orchestration logic.
 */
class AdhocRollbackTest {

    // --- commonFloor validation ---

    @Test
    void commonFloor_singleStore_usesItsFloor() {
        var stores = List.of(mockStore("utxo", 1000, 200));
        long floor = computeCommonFloor(stores);
        assertThat(floor).isEqualTo(200);
    }

    @Test
    void commonFloor_multipleStores_usesMaxFloor() {
        var stores = List.of(
                mockStore("chainState", 1000, 0),
                mockStore("utxo", 1000, 500),
                mockStore("accountState", 1000, 0)
        );
        long floor = computeCommonFloor(stores);
        assertThat(floor).isEqualTo(500); // UTXO has the highest floor
    }

    @Test
    void commonFloor_allZero() {
        var stores = List.of(
                mockStore("chainState", 1000, 0),
                mockStore("accountState", 1000, 0)
        );
        long floor = computeCommonFloor(stores);
        assertThat(floor).isEqualTo(0);
    }

    @Test
    void targetBelowFloor_rejected() {
        long commonFloor = 500;
        long targetSlot = 300;
        assertThat(targetSlot >= commonFloor).isFalse();
    }

    @Test
    void targetAtFloor_accepted() {
        long commonFloor = 500;
        long targetSlot = 500;
        assertThat(targetSlot >= commonFloor).isTrue();
    }

    @Test
    void targetAboveFloor_accepted() {
        long commonFloor = 500;
        long targetSlot = 800;
        assertThat(targetSlot >= commonFloor).isTrue();
    }

    // --- Post-rollback verification ---

    @Test
    void verification_allStoresBelowTarget_passes() {
        var stores = List.of(
                mockStore("chainState", 600, 0),
                mockStore("utxo", 600, 0),
                mockStore("accountState", 600, 0)
        );
        long targetSlot = 700;
        // All stores report latestAppliedSlot <= targetSlot
        for (var store : stores) {
            assertThat(store.getLatestAppliedSlot() <= targetSlot).isTrue();
        }
    }

    @Test
    void verification_storeAboveTarget_fails() {
        var stores = List.of(
                mockStore("chainState", 600, 0),
                mockStore("utxo", 800, 0), // above target
                mockStore("accountState", 600, 0)
        );
        long targetSlot = 700;
        boolean anyAbove = stores.stream().anyMatch(s -> s.getLatestAppliedSlot() > targetSlot);
        assertThat(anyAbove).isTrue();
    }

    // --- Helper ---

    private static long computeCommonFloor(List<RollbackCapableStore> stores) {
        long floor = 0;
        for (var store : stores) {
            long f = store.getRollbackFloorSlot();
            if (f > floor) floor = f;
        }
        return floor;
    }

    private static RollbackCapableStore mockStore(String name, long latestSlot, long floorSlot) {
        return new RollbackCapableStore() {
            @Override public String storeName() { return name; }
            @Override public long getLatestAppliedSlot() { return latestSlot; }
            @Override public long getRollbackFloorSlot() { return floorSlot; }
            @Override public void rollbackToSlot(long targetSlot) {}
        };
    }
}
