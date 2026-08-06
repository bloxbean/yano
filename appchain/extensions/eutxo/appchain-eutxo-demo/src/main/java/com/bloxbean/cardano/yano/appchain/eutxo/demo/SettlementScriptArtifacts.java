package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADR-UTXO-009 SP-M6: resolves the settlement deploy identity from the
 * CHECKED-IN unparameterized templates ({@code META-INF/plutus/*.plutus.json}
 * in appchain-eutxo-bridge-onchain, plus the audited AnchorThreadPolicy from
 * appchain-anchor-onchain for the root-thread NFT). Everything downstream —
 * policy ids, script hashes, addresses — is computed from the bundled
 * artifact bytes, never from validator sources (the anchor pattern).
 *
 * <p>Parameterization is LINEAR (the SP-M6 deploy-ordering fix):
 * <ol>
 *   <li>root policy = AnchorThreadPolicy(rootSeed), shard policy =
 *       ShardThreadPolicy(shardSeed) — both one-shot,</li>
 *   <li>vault = SettlementVaultValidator(rootPolicyId, rootAssetName,
 *       SHARD-THREAD-POLICY-id, keyPrefix, claimDomain, caps),</li>
 *   <li>shard = NullifierShardValidator(shardPolicyId, VAULT-script-hash),
 *       root validator = SettlementRootValidator(rootPolicyId,
 *       rootAssetName).</li>
 * </ol>
 */
public final class SettlementScriptArtifacts {
    static final String VAULT_RESOURCE =
            "META-INF/plutus/SettlementVaultValidator.plutus.json";
    static final String SHARD_RESOURCE =
            "META-INF/plutus/NullifierShardValidator.plutus.json";
    static final String ROOT_RESOURCE =
            "META-INF/plutus/SettlementRootValidator.plutus.json";
    static final String SHARD_POLICY_RESOURCE =
            "META-INF/plutus/ShardThreadPolicy.plutus.json";
    static final String ROOT_POLICY_RESOURCE =
            "META-INF/plutus/AnchorThreadPolicy.plutus.json";

    private static final Pattern CBOR_HEX =
            Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private final String vaultTemplateHex;
    private final String shardTemplateHex;
    private final String rootTemplateHex;
    private final String shardPolicyTemplateHex;
    private final String rootPolicyTemplateHex;

    public SettlementScriptArtifacts() {
        this.vaultTemplateHex = bundled(VAULT_RESOURCE);
        this.shardTemplateHex = bundled(SHARD_RESOURCE);
        this.rootTemplateHex = bundled(ROOT_RESOURCE);
        this.shardPolicyTemplateHex = bundled(SHARD_POLICY_RESOURCE);
        this.rootPolicyTemplateHex = bundled(ROOT_POLICY_RESOURCE);
    }

    /** One-shot root-thread NFT policy (audited AnchorThreadPolicy). */
    public PlutusV3Script rootThreadPolicy(byte[] seedTxId, long seedIndex) {
        return apply(rootPolicyTemplateHex,
                BytesPlutusData.of(seedTxId),
                BigIntPlutusData.of(BigInteger.valueOf(seedIndex)));
    }

    /** One-shot 16-token shard-thread policy. */
    public PlutusV3Script shardThreadPolicy(byte[] seedTxId, long seedIndex) {
        return apply(shardPolicyTemplateHex,
                BytesPlutusData.of(seedTxId),
                BigIntPlutusData.of(BigInteger.valueOf(seedIndex)));
    }

    /** The settlement vault, pairing shards by THREAD TOKEN policy. */
    public PlutusV3Script vault(
            byte[] rootThreadPolicyId,
            byte[] rootThreadAssetName,
            byte[] shardThreadPolicyId,
            byte[] withdrawalKeyPrefix,
            byte[] claimDomain,
            int maxSettleBatch,
            int maxExitBatch
    ) {
        if (maxSettleBatch < 1 || maxSettleBatch > 255
                || maxExitBatch < 1 || maxExitBatch > 255) {
            throw new IllegalArgumentException("batch caps must be 1..255");
        }
        return apply(vaultTemplateHex,
                BytesPlutusData.of(rootThreadPolicyId),
                BytesPlutusData.of(rootThreadAssetName),
                BytesPlutusData.of(shardThreadPolicyId),
                BytesPlutusData.of(withdrawalKeyPrefix),
                BytesPlutusData.of(claimDomain),
                BytesPlutusData.of(new byte[] {(byte) maxSettleBatch}),
                BytesPlutusData.of(new byte[] {(byte) maxExitBatch}));
    }

    /** The nullifier shard validator, pairing the vault by script hash. */
    public PlutusV3Script shard(byte[] shardThreadPolicyId, byte[] vaultScriptHash) {
        return apply(shardTemplateHex,
                BytesPlutusData.of(shardThreadPolicyId),
                BytesPlutusData.of(vaultScriptHash));
    }

    /** The accepted-root thread validator. */
    public PlutusV3Script rootValidator(
            byte[] rootThreadPolicyId, byte[] rootThreadAssetName) {
        return apply(rootTemplateHex,
                BytesPlutusData.of(rootThreadPolicyId),
                BytesPlutusData.of(rootThreadAssetName));
    }

    public static byte[] scriptHash(PlutusV3Script script) {
        try {
            return script.getScriptHash();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "cannot hash settlement script", failure);
        }
    }

    public static Address scriptAddress(PlutusV3Script script, Network network) {
        return AddressProvider.getEntAddress(script, network);
    }

    // ------------------------------------------------------------------

    private static PlutusV3Script apply(String templateHex, PlutusData... params) {
        var coreParams =
                new com.bloxbean.cardano.julc.core.PlutusData[params.length];
        for (int index = 0; index < params.length; index++) {
            coreParams[index] = PlutusDataAdapter.fromClientLib(params[index]);
        }
        var program = JulcScriptAdapter.toProgram(templateHex)
                .applyParams(coreParams);
        return JulcScriptAdapter.fromProgram(program);
    }

    private static String bundled(String resource) {
        try (InputStream in = SettlementScriptArtifacts.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "bundled settlement artifact not on classpath: " + resource);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = CBOR_HEX.matcher(json);
            if (!matcher.find()) {
                throw new IllegalStateException("no cborHex in " + resource);
            }
            return matcher.group(1);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "cannot load bundled settlement artifact " + resource, failure);
        }
    }
}
