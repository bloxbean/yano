package com.bloxbean.cardano.yano.runtime.appchain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR app-layer/008.2 §2.9 (I2.3): TTL-bounded wire dedup — an id stays
 * deduplicated for the message's whole lifetime, expired entries are swept,
 * and the hard cap only ever evicts as a memory backstop.
 */
class SeenMessageIdsTest {

    @Test
    void duplicateWithinTtl_isDeduplicated() {
        SeenMessageIds seen = new SeenMessageIds(10_000);
        assertThat(seen.markSeen("aa", 1_000)).isTrue();
        assertThat(seen.markSeen("aa", 1_000)).isFalse(); // re-diffused → duplicate
        assertThat(seen.markSeen("bb", 1_000)).isTrue();
        assertThat(seen.size()).isEqualTo(2);
    }

    @Test
    void expiredEntries_areSweptAndIdBecomesFreshAgain() {
        SeenMessageIds seen = new SeenMessageIds(10_000);
        seen.markSeen("aa", 1_000);
        seen.markSeen("bb", 2_000);

        assertThat(seen.sweep(1_500)).isEqualTo(0); // aa expired, bb kept — no cap eviction
        assertThat(seen.size()).isEqualTo(1);

        // aa's message is expired everywhere (agents reject it), so the id
        // becoming markable again is safe — and closes the old count-window
        // loophole for STILL-VALID messages, which now survive to their expiry
        assertThat(seen.markSeen("aa", 3_000)).isTrue();
        assertThat(seen.markSeen("bb", 2_000)).isFalse(); // still within TTL → still dedup
    }

    @Test
    void hardCap_evictsOnlyExcess_andReportsIt() {
        SeenMessageIds seen = new SeenMessageIds(1); // floor clamps to 1_000
        for (int i = 0; i < 1_500; i++) {
            seen.markSeen("id-" + i, 9_999);
        }
        int evicted = seen.sweep(0); // nothing expired — pure cap enforcement
        assertThat(evicted).isEqualTo(500);
        assertThat(seen.size()).isEqualTo(1_000);
    }
}
