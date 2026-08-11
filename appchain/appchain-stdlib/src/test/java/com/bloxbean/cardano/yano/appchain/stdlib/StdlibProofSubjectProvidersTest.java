package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectRegistry;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.BalancesContract;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StdlibProofSubjectProvidersTest {
    @Test
    void stockMachinesBindReleasedDescriptorsToTheirManifests() {
        var machines = List.of(new BalancesStateMachine(), new KvRegistryStateMachine(),
                new DocTrailStateMachine(), new ApprovalsStateMachine());

        for (var machine : machines) {
            var registry = ProofSubjectRegistry.bind(machine.capabilityManifest(),
                    machine.proofSubjectProviders());
            assertThat(registry.descriptors()).hasSize(1);
            assertThat(registry.descriptors().getFirst().descriptorDigest()).hasSize(64);
            assertThat(registry.descriptors().getFirst().claims())
                    .noneMatch(claim -> claim.claimId().contains("absent"));
        }
    }

    @Test
    void balanceClaimsAreRecomputedFromAuthenticatedBytes() {
        ProofSubjectProvider provider = StdlibProofSubjectProviders.balances();
        var resolved = provider.resolve(StdlibProofSubjectProviders.BALANCE,
                Map.of("account", "alice"), ProofSubjectProvider.ProofView.latest());
        var fact = provider.decode(StdlibProofSubjectProviders.BALANCE,
                new BigInteger("42").toByteArray());

        assertThat(resolved.physicalKey()).isEqualTo(BalancesContract.accountKey("alice"));
        assertThat(provider.evaluate(StdlibProofSubjectProviders.BALANCE, fact,
                new ProofSubjectProvider.ClaimRequest("minimum", Map.of("expected", "40")))
                .satisfied()).isTrue();
        assertThat(provider.evaluate(StdlibProofSubjectProviders.BALANCE, fact,
                new ProofSubjectProvider.ClaimRequest("maximum", Map.of("expected", "40")))
                .satisfied()).isFalse();
    }

    @Test
    void approvalStatusAndQuorumUseOnlyCanonicalValue() {
        ProofSubjectProvider provider = StdlibProofSubjectProviders.approvals();
        byte[] encoded = new ApprovalsStateMachine.Item(
                ApprovalsStateMachine.STATUS_APPROVED, new byte[32], new byte[32],
                1, 100, List.of(new byte[32]), new byte[0]).encode();
        var fact = provider.decode(StdlibProofSubjectProviders.APPROVAL, encoded);

        assertThat(provider.evaluate(StdlibProofSubjectProviders.APPROVAL, fact,
                new ProofSubjectProvider.ClaimRequest("status", Map.of("expected", "APPROVED")))
                .satisfied()).isTrue();
        assertThat(provider.evaluate(StdlibProofSubjectProviders.APPROVAL, fact,
                new ProofSubjectProvider.ClaimRequest("quorum-reached", Map.of()))
                .satisfied()).isTrue();
    }
}
