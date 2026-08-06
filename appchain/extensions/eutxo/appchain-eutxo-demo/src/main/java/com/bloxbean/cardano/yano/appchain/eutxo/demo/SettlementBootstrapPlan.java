package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.yano.appchain.eutxo.client.NullifierShardMirror;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoShardDatum;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * ADR-UTXO-009 SP-M6: the fully resolved settlement deploy identity — the
 * pure function from (two one-shot seed UTxOs, bridge config) to every
 * script, address, thread token, and initial inline datum the bootstrap
 * transactions create:
 * <ol>
 *   <li>root thread NFT at the root validator with the genesis RootDatum
 *       (empty state root, member set + threshold, generation 0),</li>
 *   <li>16 shard threads at the shard validator, each with the EMPTY-trie
 *       nullifier root ({@link NullifierShardMirror#emptyRoot()}),</li>
 *   <li>the vault address deposits/settlements custody.</li>
 * </ol>
 * The devnet E2E submits these outputs via two mint transactions (one per
 * one-shot policy) funded by the seeds; this plan is the deterministic,
 * unit-testable core.
 */
public record SettlementBootstrapPlan(
        String rootThreadPolicyIdHex,
        String shardThreadPolicyIdHex,
        String vaultAddress,
        String shardAddress,
        String rootAddress,
        byte[] vaultScriptHash,
        byte[] shardScriptHash,
        byte[] rootScriptHash,
        byte[] initialRootDatum,
        List<EutxoShardDatum> shardDatums,
        PlutusV3Script rootThreadPolicy,
        PlutusV3Script shardThreadPolicy,
        PlutusV3Script vaultScript,
        PlutusV3Script shardScript,
        PlutusV3Script rootScript
) {
    /** The default withdrawal-commitment key prefix (matches SP-M2 vectors). */
    public static final byte[] DEFAULT_KEY_PREFIX = new byte[] {0x01, 0x03};
    /** The v2 claim digest domain (matches EutxoWithdrawalCommitment). */
    public static final byte[] DEFAULT_CLAIM_DOMAIN =
            "yano-eutxo-withdrawal-v2".getBytes(StandardCharsets.US_ASCII);

    public record Config(
            String chainId,
            long bridgeEpoch,
            Network network,
            byte[] rootThreadAssetName,
            List<String> memberKeysHex,
            int threshold,
            long updatedAtSlot,
            long fallbackDelaySlots,
            byte[] initialStateRoot
    ) {
        /** Genesis with an empty (all-zero) accepted state root. */
        public Config(
                String chainId, long bridgeEpoch, Network network,
                byte[] rootThreadAssetName, List<String> memberKeysHex,
                int threshold, long updatedAtSlot, long fallbackDelaySlots) {
            this(chainId, bridgeEpoch, network, rootThreadAssetName,
                    memberKeysHex, threshold, updatedAtSlot,
                    fallbackDelaySlots, new byte[32]);
        }

        public Config {
            chainId = Objects.requireNonNull(chainId, "chainId").trim();
            if (chainId.isEmpty() || chainId.length() > 128) {
                throw new IllegalArgumentException(
                        "chain id must contain 1-128 characters");
            }
            if (bridgeEpoch < 0) {
                throw new IllegalArgumentException(
                        "bridge epoch cannot be negative");
            }
            Objects.requireNonNull(network, "network");
            rootThreadAssetName = Objects.requireNonNull(
                    rootThreadAssetName, "rootThreadAssetName").clone();
            Objects.requireNonNull(memberKeysHex, "memberKeysHex");
            // Sorted, distinct 32-byte Ed25519 member keys — the on-chain
            // RootDatum requires strictly increasing order.
            TreeSet<String> sorted = new TreeSet<>();
            for (String member : memberKeysHex) {
                String normalized = Objects.requireNonNull(member, "member")
                        .trim().toLowerCase(java.util.Locale.ROOT);
                if (HexFormat.of().parseHex(normalized).length != 32) {
                    throw new IllegalArgumentException(
                            "member keys must be 32-byte hex");
                }
                if (!sorted.add(normalized)) {
                    throw new IllegalArgumentException(
                            "member keys must be distinct");
                }
            }
            if (sorted.isEmpty() || sorted.size() > 32) {
                throw new IllegalArgumentException(
                        "member set must contain 1-32 keys");
            }
            memberKeysHex = List.copyOf(sorted);
            if (threshold < 1 || threshold > memberKeysHex.size()) {
                throw new IllegalArgumentException(
                        "threshold must be 1..members");
            }
            if (updatedAtSlot < 0) {
                throw new IllegalArgumentException(
                        "updatedAtSlot cannot be negative");
            }
            if (fallbackDelaySlots < EutxoProfile.V3_FALLBACK_DELAY_MIN_SLOTS
                    || fallbackDelaySlots > EutxoProfile.V3_FALLBACK_DELAY_MAX_SLOTS) {
                throw new IllegalArgumentException(
                        "fallback delay must respect the tier-1 profile bounds");
            }
            initialStateRoot = Objects.requireNonNull(
                    initialStateRoot, "initialStateRoot").clone();
            if (initialStateRoot.length != 32) {
                throw new IllegalArgumentException(
                        "initial state root must be 32 bytes");
            }
        }
    }

    public static SettlementBootstrapPlan plan(
            EutxoOutpoint rootSeed,
            EutxoOutpoint shardSeed,
            Config config
    ) {
        Objects.requireNonNull(rootSeed, "rootSeed");
        Objects.requireNonNull(shardSeed, "shardSeed");
        Objects.requireNonNull(config, "config");
        if (rootSeed.equals(shardSeed)) {
            throw new IllegalArgumentException(
                    "root and shard policies need distinct one-shot seeds");
        }
        SettlementScriptArtifacts artifacts = new SettlementScriptArtifacts();

        PlutusV3Script rootPolicy = artifacts.rootThreadPolicy(
                HexFormat.of().parseHex(rootSeed.transactionId()),
                rootSeed.index());
        PlutusV3Script shardPolicy = artifacts.shardThreadPolicy(
                HexFormat.of().parseHex(shardSeed.transactionId()),
                shardSeed.index());
        byte[] rootPolicyId = SettlementScriptArtifacts.scriptHash(rootPolicy);
        byte[] shardPolicyId = SettlementScriptArtifacts.scriptHash(shardPolicy);

        PlutusV3Script vault = artifacts.vault(
                rootPolicyId,
                config.rootThreadAssetName(),
                shardPolicyId,
                DEFAULT_KEY_PREFIX,
                DEFAULT_CLAIM_DOMAIN,
                EutxoProfile.V3_MAX_SETTLE_BATCH,
                EutxoProfile.V3_MAX_EXIT_BATCH);
        byte[] vaultHash = SettlementScriptArtifacts.scriptHash(vault);
        PlutusV3Script shard = artifacts.shard(shardPolicyId, vaultHash);
        PlutusV3Script root = artifacts.rootValidator(
                rootPolicyId, config.rootThreadAssetName());

        List<EutxoShardDatum> shardDatums = new ArrayList<>(
                NullifierShardMirror.SHARD_COUNT);
        byte[] emptyRoot = NullifierShardMirror.emptyRoot();
        for (int index = 0; index < NullifierShardMirror.SHARD_COUNT; index++) {
            shardDatums.add(new EutxoShardDatum(
                    EutxoShardDatum.VERSION, config.chainId(),
                    config.bridgeEpoch(), index, emptyRoot));
        }

        return new SettlementBootstrapPlan(
                HexFormat.of().formatHex(rootPolicyId),
                HexFormat.of().formatHex(shardPolicyId),
                SettlementScriptArtifacts.scriptAddress(
                        vault, config.network()).getAddress(),
                SettlementScriptArtifacts.scriptAddress(
                        shard, config.network()).getAddress(),
                SettlementScriptArtifacts.scriptAddress(
                        root, config.network()).getAddress(),
                vaultHash,
                SettlementScriptArtifacts.scriptHash(shard),
                SettlementScriptArtifacts.scriptHash(root),
                genesisRootDatum(config),
                List.copyOf(shardDatums),
                rootPolicy, shardPolicy, vault, shard, root);
    }

    /**
     * The genesis RootDatum the root thread is bootstrapped with —
     * {@code Constr0[1, chainId, bridgeEpoch, height=0, stateRoot=32 zero
     * bytes, memberKeys(sorted), threshold, generation=0, updatedAtSlot,
     * fallbackDelaySlots]}, the exact on-chain
     * {@code SettlementRootValidator.RootDatum} shape.
     */
    static byte[] genesisRootDatum(Config config) {
        try {
            List<com.bloxbean.cardano.client.plutus.spec.PlutusData> members =
                    new ArrayList<>();
            for (String member : config.memberKeysHex()) {
                members.add(BytesPlutusData.of(HexFormat.of().parseHex(member)));
            }
            return ConstrPlutusData.of(0,
                            BigIntPlutusData.of(1),
                            BytesPlutusData.of(config.chainId()
                                    .getBytes(StandardCharsets.UTF_8)),
                            BigIntPlutusData.of(config.bridgeEpoch()),
                            BigIntPlutusData.of(0),
                            BytesPlutusData.of(config.initialStateRoot()),
                            ListPlutusData.of(members.toArray(
                                    new com.bloxbean.cardano.client.plutus
                                            .spec.PlutusData[0])),
                            BigIntPlutusData.of(config.threshold()),
                            BigIntPlutusData.of(0),
                            BigIntPlutusData.of(config.updatedAtSlot()),
                            BigIntPlutusData.of(config.fallbackDelaySlots()))
                    .serializeToBytes();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "cannot encode the genesis root datum", failure);
        }
    }
}
