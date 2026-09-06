package com.bloxbean.cardano.yano.runtime.wallet;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDeregistration;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDelegation;
import com.bloxbean.cardano.yaci.core.model.certs.RegCert;
import com.bloxbean.cardano.yaci.core.model.certs.UnregCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegDelegCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeVoteDelegCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeVoteRegDelegCert;
import com.bloxbean.cardano.yaci.core.model.certs.VoteDelegCert;
import com.bloxbean.cardano.yaci.core.model.certs.VoteRegDelegCert;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.RegDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.UnregDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.UpdateDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.MoveInstataneous;
import com.bloxbean.cardano.yano.api.wallet.WalletCredential;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Expectations are stated independently of the production event walker. */
class WalletCredentialsTest {
    private static final String PAYMENT = "01".repeat(28);
    private static final String STAKE = "02".repeat(28);

    @Test void addressHeadersKeepRoleAndKeyScriptIdentityDistinct() {
        for (int type = 0; type <= 7; type++) {
            String raw = "%02x".formatted(type << 4) + PAYMENT
                    + (type <= 3 ? STAKE : type <= 5 ? "000000" : "");
            Set<WalletCredential> actual = new HashSet<>();
            WalletCredentials.address(new Address(HexFormat.of().parseHex(raw)).toBech32(), actual);
            WalletCredential payment = new WalletCredential("payment", (type & 1) == 0 ? "key" : "script", PAYMENT);
            if (type <= 3) assertThat(actual).containsExactlyInAnyOrder(payment,
                    new WalletCredential("stake", type < 2 ? "key" : "script", STAKE));
            else assertThat(actual).containsExactly(payment);
        }
        for (boolean script : List.of(false, true)) {
            Set<WalletCredential> actual = new HashSet<>();
            WalletCredentials.address(reward(STAKE, script), actual);
            assertThat(actual).containsExactly(new WalletCredential("stake", script ? "script" : "key", STAKE));
        }
    }

    @Test void everySupportedStakeCertificateIncludesItsSubject() {
        for (StakeCredType type : StakeCredType.values()) {
            StakeCredential stake = StakeCredential.builder().type(type).hash(STAKE).build();
            List<Certificate> fixtures = List.of(
                StakeRegistration.builder().stakeCredential(stake).build(),
                StakeDeregistration.builder().stakeCredential(stake).build(),
                StakeDelegation.builder().stakeCredential(stake).build(),
                RegCert.builder().stakeCredential(stake).build(),
                UnregCert.builder().stakeCredential(stake).build(),
                StakeRegDelegCert.builder().stakeCredential(stake).build(),
                StakeVoteDelegCert.builder().stakeCredential(stake).build(),
                StakeVoteRegDelegCert.builder().stakeCredential(stake).build(),
                VoteDelegCert.builder().stakeCredential(stake).build(),
                VoteRegDelegCert.builder().stakeCredential(stake).build());
            for (Certificate certificate : fixtures) {
                Set<WalletCredential> actual = events(TransactionBody.builder().certificates(List.of(certificate)).build());
                assertThat(actual).as(certificate.getClass().getSimpleName()).containsExactly(
                        new WalletCredential("stake", type == StakeCredType.ADDR_KEYHASH ? "key" : "script", STAKE));
                assertFilterContainsAll(actual);
            }
        }
    }

    @Test void drepCertificatesUseTheirOwnRoleAndType() {
        for (StakeCredType type : StakeCredType.values()) {
            Credential drep = Credential.builder().type(type).hash(STAKE).build();
            for (Certificate certificate : List.of(RegDrepCert.builder().drepCredential(drep).build(),
                    UnregDrepCert.builder().drepCredential(drep).build(), UpdateDrepCert.builder().drepCredential(drep).build())) {
                Set<WalletCredential> actual = events(TransactionBody.builder().certificates(List.of(certificate)).build());
                assertThat(actual).containsExactly(new WalletCredential("drep", type == StakeCredType.ADDR_KEYHASH ? "key" : "script", STAKE));
                assertFilterContainsAll(actual);
            }
        }
    }

    @Test void withdrawalPoolOwnersMirRecipientsAndProposalRefundsAreIncluded() {
        StakeCredential mir = StakeCredential.builder().type(StakeCredType.SCRIPTHASH).hash("03".repeat(28)).build();
        TransactionBody tx = TransactionBody.builder()
                .withdrawals(Map.of(reward(STAKE, true), BigInteger.ONE))
                .certificates(List.of(PoolRegistration.builder().poolParams(PoolParams.builder()
                                .rewardAccount(reward("04".repeat(28), false)).poolOwners(Set.of("05".repeat(28))).build()).build(),
                        MoveInstataneous.builder().stakeCredentialCoinMap(Map.of(mir, BigInteger.TEN)).build()))
                .proposalProcedures(List.of(ProposalProcedure.builder().rewardAccount(reward("06".repeat(28), true)).build()))
                .build();
        Set<WalletCredential> actual = events(tx);
        assertThat(actual).containsExactlyInAnyOrder(
                new WalletCredential("stake", "script", STAKE),
                new WalletCredential("stake", "script", "03".repeat(28)),
                new WalletCredential("stake", "key", "04".repeat(28)),
                new WalletCredential("stake", "key", "05".repeat(28)),
                new WalletCredential("stake", "script", "06".repeat(28)));
        assertFilterContainsAll(actual);
    }

    @Test void decoderHexRewardAccountsMatchTheirDisplayForms() {
        TransactionBody hex = TransactionBody.builder()
                .withdrawals(Map.of("f0" + STAKE, BigInteger.ONE))
                .certificates(List.of(PoolRegistration.builder().poolParams(PoolParams.builder()
                        .rewardAccount("e0" + PAYMENT).poolOwners(Set.of()).build()).build()))
                .proposalProcedures(List.of(ProposalProcedure.builder().rewardAccount("f0" + PAYMENT).build()))
                .build();
        TransactionBody display = TransactionBody.builder()
                .withdrawals(Map.of(reward(STAKE, true), BigInteger.ONE))
                .certificates(List.of(PoolRegistration.builder().poolParams(PoolParams.builder()
                        .rewardAccount(reward(PAYMENT, false)).poolOwners(Set.of()).build()).build()))
                .proposalProcedures(List.of(ProposalProcedure.builder().rewardAccount(reward(PAYMENT, true)).build()))
                .build();
        assertThat(events(hex)).containsExactlyInAnyOrderElementsOf(events(display));
        assertThat(events(hex)).hasSize(3);
        assertFilterContainsAll(events(hex));
    }

    private static Set<WalletCredential> events(TransactionBody tx) {
        Set<WalletCredential> result = new HashSet<>();
        WalletCredentials.events(tx, result);
        return result;
    }

    private static void assertFilterContainsAll(Set<WalletCredential> credentials) {
        byte[] filter = CredentialFilter.encode(new byte[16], credentials.stream().map(WalletCredential::filterElement).toList());
        for (WalletCredential credential : credentials) {
            assertThat(CredentialFilter.matches(filter, List.of(credential.filterElement()))).isTrue();
        }
    }

    private static String reward(String hash, boolean script) {
        return new Address(HexFormat.of().parseHex((script ? "f0" : "e0") + hash)).toBech32();
    }
}
