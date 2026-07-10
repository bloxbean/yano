package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig.AnchorScriptConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves the script-anchor artifacts (ADR app-layer/008.4 §2.4) and derives
 * the on-chain identity from them. Everything downstream — thread policy id,
 * validator script hash, anchor address — is computed from the CONFIGURED
 * compiled UPLC artifact, never from validator sources, so the julc default
 * and the Aiken opt-in are interchangeable behind the same refs:
 * <ul>
 *   <li>{@code builtin:julc} — bundled artifact from
 *       {@code META-INF/plutus/*.plutus.json} (appchain-anchor-onchain jar)</li>
 *   <li>{@code file:/path} — a blueprint-style JSON ({@code cborHex} field)
 *       or a raw double-CBOR UPLC hex file</li>
 *   <li>{@code hex:...} — inline double-CBOR UPLC hex</li>
 * </ul>
 */
final class AnchorScriptArtifacts {

    static final String BUILTIN_VALIDATOR_RESOURCE = "META-INF/plutus/AnchorValidator.plutus.json";
    static final String BUILTIN_POLICY_RESOURCE = "META-INF/plutus/AnchorThreadPolicy.plutus.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String validatorTemplateHex;
    private final String policyTemplateHex;

    AnchorScriptArtifacts(AnchorScriptConfig config) {
        Objects.requireNonNull(config, "anchor script config");
        this.validatorTemplateHex = resolve(config.validatorRef(), BUILTIN_VALIDATOR_RESOURCE);
        this.policyTemplateHex = resolve(config.threadPolicyRef(), BUILTIN_POLICY_RESOURCE);
    }

    /**
     * The one-shot thread policy, parameterized by the bootstrap seed UTxO
     * (params: seedTxId bytes, seedIndex int — ADR 008.4 §2.3). The script's
     * hash IS the thread policy id.
     */
    PlutusV3Script threadPolicy(byte[] seedTxId, long seedIndex) {
        return apply(policyTemplateHex,
                BytesPlutusData.of(seedTxId),
                BigIntPlutusData.of(BigInteger.valueOf(seedIndex)));
    }

    /** The anchor spending validator, parameterized by the thread policy id. */
    PlutusV3Script validator(byte[] threadPolicyId) {
        return apply(validatorTemplateHex, BytesPlutusData.of(threadPolicyId));
    }

    static byte[] scriptHash(PlutusV3Script script) {
        try {
            return script.getScriptHash();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash anchor script", e);
        }
    }

    static Address scriptAddress(PlutusV3Script script, Network network) {
        return AddressProvider.getEntAddress(script, network);
    }

    // ------------------------------------------------------------------

    private static PlutusV3Script apply(String templateHex, PlutusData... params) {
        var coreParams = new com.bloxbean.cardano.julc.core.PlutusData[params.length];
        for (int i = 0; i < params.length; i++) {
            coreParams[i] = PlutusDataAdapter.fromClientLib(params[i]);
        }
        var program = JulcScriptAdapter.toProgram(templateHex).applyParams(coreParams);
        return JulcScriptAdapter.fromProgram(program);
    }

    /** Resolve a ref to the unparameterized double-CBOR UPLC hex. */
    static String resolve(String ref, String builtinResource) {
        String trimmed = ref == null ? "" : ref.trim();
        if (trimmed.isEmpty() || AnchorScriptConfig.BUILTIN_JULC.equals(trimmed)) {
            return readBuiltin(builtinResource);
        }
        if (trimmed.startsWith("hex:")) {
            return normalizeHex(trimmed.substring("hex:".length()), ref);
        }
        if (trimmed.startsWith("file:")) {
            Path path = Path.of(trimmed.substring("file:".length()));
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8).trim();
                return content.startsWith("{")
                        ? cborHexFromJson(content, trimmed)
                        : normalizeHex(content, trimmed);
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot read anchor script artifact " + trimmed, e);
            }
        }
        throw new IllegalArgumentException("Unsupported anchor script ref '" + ref
                + "' — expected builtin:julc, file:/path or hex:...");
    }

    private static String readBuiltin(String resource) {
        ClassLoader loader = AnchorScriptArtifacts.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Bundled anchor artifact " + resource
                        + " not found on the classpath — is appchain-anchor-onchain on the runtime classpath?");
            }
            return cborHexFromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), resource);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read bundled anchor artifact " + resource, e);
        }
    }

    private static String cborHexFromJson(String json, String source) {
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode cborHex = node.get("cborHex");
            if (cborHex == null || cborHex.asText().isBlank()) {
                // CIP-57 blueprint: validators[].compiledCode
                JsonNode validators = node.get("validators");
                if (validators != null && validators.isArray() && validators.size() == 1) {
                    JsonNode compiled = validators.get(0).get("compiledCode");
                    if (compiled != null && !compiled.asText().isBlank()) {
                        return normalizeHex(compiled.asText(), source);
                    }
                }
                throw new IllegalArgumentException("No cborHex (or single-validator compiledCode) in " + source);
            }
            return normalizeHex(cborHex.asText(), source);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid anchor script artifact JSON in " + source, e);
        }
    }

    private static String normalizeHex(String hex, String source) {
        String cleaned = hex.trim();
        try {
            HexUtil.decodeHexString(cleaned);
        } catch (Exception e) {
            throw new IllegalArgumentException("Anchor script artifact " + source
                    + " is not valid hex", e);
        }
        return cleaned;
    }
}
