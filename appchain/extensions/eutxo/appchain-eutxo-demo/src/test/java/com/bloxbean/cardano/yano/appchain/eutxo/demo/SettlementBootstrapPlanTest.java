package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.yano.appchain.eutxo.client.NullifierShardMirror;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-UTXO-009 SP-M6: the bootstrap plan is a deterministic pure function
 * from seeds + config to the full deploy identity, built from the CHECKED-IN
 * artifact templates in linear parameterization order.
 */
class SettlementBootstrapPlanTest {
    private static final EutxoOutpoint ROOT_SEED =
            new EutxoOutpoint("5e".repeat(32), 0);
    private static final EutxoOutpoint SHARD_SEED =
            new EutxoOutpoint("5e".repeat(32), 1);
    private static final String MEMBER_ONE = "aa".repeat(32);
    private static final String MEMBER_TWO = "bb".repeat(32);

    @Test
    void planIsDeterministicAndDerivesDistinctLinearIdentities() {
        SettlementBootstrapPlan first = SettlementBootstrapPlan.plan(
                ROOT_SEED, SHARD_SEED, config());
        SettlementBootstrapPlan second = SettlementBootstrapPlan.plan(
                ROOT_SEED, SHARD_SEED, config());

        // Deterministic: same seeds + config -> identical identity.
        assertThat(second.rootThreadPolicyIdHex())
                .isEqualTo(first.rootThreadPolicyIdHex());
        assertThat(second.shardThreadPolicyIdHex())
                .isEqualTo(first.shardThreadPolicyIdHex());
        assertThat(second.vaultAddress()).isEqualTo(first.vaultAddress());
        assertThat(second.shardAddress()).isEqualTo(first.shardAddress());
        assertThat(second.rootAddress()).isEqualTo(first.rootAddress());

        // Distinct one-shot policies and distinct script identities.
        assertThat(first.rootThreadPolicyIdHex())
                .isNotEqualTo(first.shardThreadPolicyIdHex());
        assertThat(first.vaultScriptHash())
                .isNotEqualTo(first.shardScriptHash())
                .isNotEqualTo(first.rootScriptHash());
        assertThat(first.vaultAddress()).startsWith("addr_test");
        assertThat(first.vaultScriptHash()).hasSize(28);

        // A different seed changes the policy id (one-shot identity).
        SettlementBootstrapPlan other = SettlementBootstrapPlan.plan(
                new EutxoOutpoint("6f".repeat(32), 0), SHARD_SEED, config());
        assertThat(other.rootThreadPolicyIdHex())
                .isNotEqualTo(first.rootThreadPolicyIdHex());
        // Root policy feeds the vault params -> the vault moves too.
        assertThat(other.vaultAddress()).isNotEqualTo(first.vaultAddress());
    }

    @Test
    void shardDatumsCoverAllSixteenShardsWithTheEmptyTrieRoot() {
        SettlementBootstrapPlan plan = SettlementBootstrapPlan.plan(
                ROOT_SEED, SHARD_SEED, config());
        byte[] emptyRoot = NullifierShardMirror.emptyRoot();
        assertThat(plan.shardDatums()).hasSize(16);
        for (int index = 0; index < 16; index++) {
            var datum = plan.shardDatums().get(index);
            assertThat(datum.shardIndex()).isEqualTo(index);
            assertThat(datum.nullifierRoot()).isEqualTo(emptyRoot);
            assertThat(datum.chainId()).isEqualTo("payments-eutxo");
            assertThat(datum.bridgeEpoch()).isEqualTo(7);
            assertThat(datum.threadTokenName()).containsExactly((byte) index);
        }
    }

    @Test
    void genesisRootDatumMatchesTheOnChainShapeWithSortedMembers()
            throws Exception {
        SettlementBootstrapPlan plan = SettlementBootstrapPlan.plan(
                ROOT_SEED, SHARD_SEED, config());
        PlutusData decoded = PlutusData.deserialize(plan.initialRootDatum());
        ConstrPlutusData constr = (ConstrPlutusData) decoded;
        assertThat(constr.getAlternative()).isZero();
        List<PlutusData> fields = constr.getData().getPlutusDataList();
        assertThat(fields).hasSize(10);
        assertThat(((BigIntPlutusData) fields.get(0)).getValue()).isEqualTo(1);
        assertThat(new String(((BytesPlutusData) fields.get(1)).getValue()))
                .isEqualTo("payments-eutxo");
        assertThat(((BigIntPlutusData) fields.get(3)).getValue()).isZero();
        assertThat(((BytesPlutusData) fields.get(4)).getValue())
                .containsOnly(0);
        // Members strictly increasing (config supplied them out of order).
        List<PlutusData> members = ((ListPlutusData) fields.get(5))
                .getPlutusDataList();
        assertThat(members).hasSize(2);
        assertThat(HexFormat.of().formatHex(
                ((BytesPlutusData) members.get(0)).getValue()))
                .isEqualTo(MEMBER_ONE);
        assertThat(HexFormat.of().formatHex(
                ((BytesPlutusData) members.get(1)).getValue()))
                .isEqualTo(MEMBER_TWO);
        assertThat(((BigIntPlutusData) fields.get(6)).getValue()).isEqualTo(2);
        assertThat(((BigIntPlutusData) fields.get(8)).getValue())
                .isEqualTo(1_000);
        assertThat(((BigIntPlutusData) fields.get(9)).getValue())
                .isEqualTo(86_400);
    }

    @Test
    void invalidConfigurationsAreRefused() {
        assertThatThrownBy(() -> SettlementBootstrapPlan.plan(
                ROOT_SEED, ROOT_SEED, config()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct one-shot seeds");
        assertThatThrownBy(() -> new SettlementBootstrapPlan.Config(
                "payments-eutxo", 7, Networks.testnet(), new byte[0],
                List.of(MEMBER_ONE, MEMBER_ONE), 1, 1_000, 86_400))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct");
        assertThatThrownBy(() -> new SettlementBootstrapPlan.Config(
                "payments-eutxo", 7, Networks.testnet(), new byte[0],
                List.of(MEMBER_ONE, MEMBER_TWO), 3, 1_000, 86_400))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
        assertThatThrownBy(() -> new SettlementBootstrapPlan.Config(
                "payments-eutxo", 7, Networks.testnet(), new byte[0],
                List.of(MEMBER_ONE, MEMBER_TWO), 2, 1_000, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tier-1");
    }

    private static SettlementBootstrapPlan.Config config() {
        return new SettlementBootstrapPlan.Config(
                "payments-eutxo",
                7,
                Networks.testnet(),
                new byte[0],
                List.of(MEMBER_TWO, MEMBER_ONE), // out of order on purpose
                2,
                1_000,
                86_400);
    }
}
