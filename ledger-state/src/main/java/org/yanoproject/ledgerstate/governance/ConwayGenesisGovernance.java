package org.yanoproject.ledgerstate.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yanoproject.ledgerstate.governance.GovernanceCborCodec.CommitteeThreshold;
import org.yanoproject.ledgerstate.governance.GovernanceCborCodec.ConstitutionRecord;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore.CredentialKey;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The governance state the Conway genesis defines (Haskell {@code Conway/Translation.hs:169-178}: the
 * committee and the constitution), parsed exactly as {@link ConwayGenesisBootstrap} parses it, without
 * storing anything.
 *
 * <p>ADR-056 step 1d uses it as a <em>view-level</em> fallback while Yano has not yet persisted the bootstrap
 * (a fresh devnet persists it at its first epoch boundary): validation then sees the genesis committee and
 * constitution instead of "not bootstrapped". Canonical ledger-state mutation is unchanged (ADR-056
 * invariant 8).</p>
 *
 * @param members          genesis committee members: cold credential to term expiry epoch
 * @param threshold        the genesis committee threshold, if the genesis has one
 * @param constitution     the genesis constitution, or {@code null} when the genesis has none
 * @param initialTreasury  the treasury Yano stores in its first AdaPot (Haskell {@code createInitialState}
 *                         starts the treasury at zero; Yano's known-network constant otherwise)
 * @param hasInitialDReps  the genesis registers DReps ({@code initialDReps}, {@code Conway/Transition.hs:82-92}),
 *                         which Yano's bootstrap does not model
 * @param hasDelegations   the genesis carries {@code delegs}, likewise not modelled
 */
public record ConwayGenesisGovernance(Map<CredentialKey, Integer> members, Optional<CommitteeThreshold> threshold,
                                      ConstitutionRecord constitution, BigInteger initialTreasury,
                                      boolean hasInitialDReps, boolean hasDelegations) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public ConwayGenesisGovernance {
        members = Map.copyOf(members);
        threshold = threshold != null ? threshold : Optional.empty();
        initialTreasury = initialTreasury != null ? initialTreasury : BigInteger.ZERO;
    }

    /**
     * Loads the genesis the bootstrap would read: {@code path}, else {@code conway-genesis.json} on the
     * classpath.
     *
     * @return the parsed state, or empty when no Conway genesis is found or it does not parse
     */
    public static Optional<ConwayGenesisGovernance> load(String path, BigInteger initialTreasury) {
        try {
            if (path != null && !path.isBlank()) {
                File file = new File(path);
                if (file.exists()) {
                    return Optional.of(parse(JSON.readTree(file), initialTreasury));
                }
            }
            try (InputStream is = ConwayGenesisGovernance.class.getClassLoader()
                    .getResourceAsStream("conway-genesis.json")) {
                return is == null ? Optional.empty() : Optional.of(parse(JSON.readTree(is), initialTreasury));
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Parses a Conway genesis document. */
    public static ConwayGenesisGovernance parse(JsonNode genesis, BigInteger initialTreasury) {
        Map<CredentialKey, Integer> members = new LinkedHashMap<>();
        Optional<CommitteeThreshold> threshold = Optional.empty();
        JsonNode committee = genesis.get("committee");
        if (committee != null) {
            JsonNode membersNode = committee.get("members");
            if (membersNode != null && membersNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = membersNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String key = field.getKey();
                    int credType = key.startsWith("scriptHash-") ? 1 : 0;
                    String hash = key.startsWith("scriptHash-") ? key.substring("scriptHash-".length())
                            : key.startsWith("keyHash-") ? key.substring("keyHash-".length()) : key;
                    members.put(new CredentialKey(credType, hash), field.getValue().asInt());
                }
            }
            JsonNode thresholdNode = committee.get("threshold");
            if (thresholdNode != null) {
                threshold = Optional.of(threshold(thresholdNode));
            }
        }
        ConstitutionRecord constitution = null;
        JsonNode constitutionNode = genesis.get("constitution");
        if (constitutionNode != null) {
            JsonNode anchor = constitutionNode.get("anchor");
            constitution = new ConstitutionRecord(
                    anchor != null && anchor.has("url") ? anchor.get("url").asText() : null,
                    anchor != null && anchor.has("dataHash") ? anchor.get("dataHash").asText() : null,
                    constitutionNode.has("script") ? constitutionNode.get("script").asText() : null);
        }
        return new ConwayGenesisGovernance(members, threshold, constitution, initialTreasury,
                nonEmpty(genesis.get("initialDReps")), nonEmpty(genesis.get("delegs")));
    }

    private static CommitteeThreshold threshold(JsonNode node) {
        if (node.isObject()) {
            return new CommitteeThreshold(node.get("numerator").bigIntegerValue(),
                    node.get("denominator").bigIntegerValue());
        }
        BigDecimal stripped = node.decimalValue().stripTrailingZeros();
        int scale = stripped.scale();
        if (scale <= 0) {
            return new CommitteeThreshold(stripped.toBigIntegerExact(), BigInteger.ONE);
        }
        return new CommitteeThreshold(stripped.movePointRight(scale).toBigInteger(), BigInteger.TEN.pow(scale));
    }

    private static boolean nonEmpty(JsonNode node) {
        return node != null && !node.isNull() && node.size() > 0;
    }
}
