package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerContext;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerModeProvider;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Harmless sequencer fixture used only by the native plugin conformance build. */
public final class ConformanceSequencerModeProvider implements SequencerModeProvider {
    public static final String ID = "conformance-mode";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SequencerMode create(SequencerContext context) {
        SequencerMode mode = new SequencerMode() {
            private final AtomicBoolean firstCallback = new AtomicBoolean(true);

            @Override
            public String id() {
                ConformanceTcclProbe.requireCatalogFacade("sequencer identity");
                ConformanceTcclProbe.productCallback(firstCallback,
                        "sequencer identity");
                return ID;
            }

            @Override
            public void init(SequencerContext ignored) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "sequencer initialization");
            }

            @Override
            public boolean shouldProposeNow(long height) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "sequencer proposal decision");
                return false;
            }

            @Override
            public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "sequencer proposal check");
                return ProposalEligibility.REJECT;
            }

            @Override
            public Map<String, Object> status() {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "sequencer status");
                return Map.of("mode", ID);
            }
        };
        ConformanceTcclProbe.poisonProviderCallback();
        return mode;
    }
}
