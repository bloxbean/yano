package com.bloxbean.cardano.yano.api.appchain.consensus;

/** Explicit safety and liveness assumptions for certified app-chain consensus. */
public record ConsensusQuorum(int members, int threshold, int maxByzantineMembers) {

    public ConsensusQuorum {
        if (members < 1) {
            throw new IllegalArgumentException("consensus members must be positive");
        }
        if (threshold < 1 || threshold > members) {
            throw new IllegalArgumentException("consensus threshold must be within membership");
        }
        if (maxByzantineMembers < 0 || maxByzantineMembers >= members) {
            throw new IllegalArgumentException("max Byzantine members is outside membership");
        }
        if (2L * threshold - members <= maxByzantineMembers) {
            throw new IllegalArgumentException(
                    "consensus quorums do not intersect in an honest member");
        }
        if (threshold > members - maxByzantineMembers) {
            throw new IllegalArgumentException(
                    "consensus threshold cannot remain live under the configured fault bound");
        }
    }

    public int unavailableMembersTolerated() {
        return members - threshold;
    }
}
