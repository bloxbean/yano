package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yano.consensus.selection.HeaderFanIn;
import com.bloxbean.cardano.yano.consensus.selection.InMemoryCandidateHeaderStore;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationResult;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationSnapshot;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MultiPeerScaffoldingTest {
    @Test
    void candidateHeaderListenerStoresObservedHeadersOnlyAsCandidates() {
        var store = new InMemoryCandidateHeaderStore();
        var listener = new CandidateHeaderListener("peer-a", true, new HeaderFanIn(store), header -> { });

        listener.rollforward(null, BlockHeader.builder()
                .headerBody(HeaderBody.builder()
                        .slot(100)
                        .blockNumber(10)
                        .prevHash("prev")
                        .blockHash("hash")
                        .build())
                .build(), new byte[] {1});

        assertThat(listener.headersObserved()).isEqualTo(1);
        assertThat(store.get("hash")).isPresent();
    }

    @Test
    void candidateHeaderListenerDoesNotStoreRejectedHeaders() {
        var store = new InMemoryCandidateHeaderStore();
        var listener = new CandidateHeaderListener(
                "peer-a",
                true,
                new HeaderFanIn(store),
                header -> { },
                rejectingHeaderValidator());

        listener.rollforward(null, BlockHeader.builder()
                .headerBody(HeaderBody.builder()
                        .slot(100)
                        .blockNumber(10)
                        .prevHash("prev")
                        .blockHash("hash")
                        .build())
                .build(), new byte[] {1});

        assertThat(listener.headersObserved()).isZero();
        assertThat(store.get("hash")).isEmpty();
    }

    private static HeaderValidator rejectingHeaderValidator() {
        return new HeaderValidator() {
            @Override
            public HeaderValidationResult validateShelley(BlockHeader blockHeader, byte[] originalHeaderBytes) {
                return HeaderValidationResult.rejected("test", "header", "rejected by test");
            }

            @Override
            public HeaderValidationSnapshot snapshot() {
                return HeaderValidationSnapshot.none();
            }
        };
    }
}
