package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerContext;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * ADR-005 S1: one configured member proposes every block (the v1 default).
 * Safety from the threshold cert; liveness = ops runbook if the proposer dies.
 */
final class FixedSequencerMode implements SequencerMode {

    static final String ID = "fixed";

    private byte[] proposerKey;
    private String proposerHex;
    private boolean self;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void init(SequencerContext context) {
        String configured = context.settings().getOrDefault("sequencer.proposer", "").trim();
        if (configured.isEmpty()) {
            throw new IllegalArgumentException(
                    "sequencer.mode=fixed requires sequencer.proposer (member public key hex)");
        }
        this.proposerHex = configured.toLowerCase(Locale.ROOT);
        this.proposerKey = HexUtil.decodeHexString(proposerHex);
        this.self = proposerHex.equals(context.selfKeyHex());
    }

    @Override
    public boolean shouldProposeNow(long height) {
        return self;
    }

    @Override
    public ProposalEligibility checkProposal(byte[] proposerKeyOfBlock, long height) {
        return Arrays.equals(proposerKeyOfBlock, proposerKey)
                ? ProposalEligibility.ACCEPT
                : ProposalEligibility.REJECT;
    }

    @Override
    public Map<String, Object> status() {
        return Map.of("mode", ID, "proposer", proposerHex);
    }
}
