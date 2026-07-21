package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcomeCommitment;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Offline trust-root calculator for one exact role-evidence genesis profile. */
public final class RoleEvidenceProfileCli {
    private RoleEvidenceProfileCli() {
    }

    public static void main(String[] args) {
        try {
            System.out.println(HexFormat.of().formatHex(profileDigest(options(args))));
        } catch (RuntimeException invalid) {
            System.err.println("error: invalid role-evidence profile arguments");
            System.exit(2);
        }
    }

    static byte[] profileDigest(Map<String, String> options) {
        if (!options.keySet().equals(java.util.Set.of(
                "--chain", "--members", "--threshold", "--storage-gate",
                "--continuation", "--evidence-capacity"))) {
            throw new IllegalArgumentException("unexpected option");
        }
        String chain = required(options, "--chain");
        List<String> members = List.of(required(options, "--members").split(",", -1));
        int threshold = positive(options, "--threshold");
        int capacity = positive(options, "--evidence-capacity");
        String gate = required(options, "--storage-gate");
        if (!(gate.equals("app-final") || gate.equals("l1-anchored"))) {
            throw new IllegalArgumentException("invalid storage gate");
        }
        String continuation = required(options, "--continuation");
        if (!(continuation.equals("explicit") || continuation.equals("direct"))) {
            throw new IllegalArgumentException("invalid continuation");
        }
        AppChainMembershipEpoch epoch = new AppChainMembershipEpoch(0, members, threshold);
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("effects.enabled", "true");
        settings.put("effects.max-per-block", "128");
        settings.put("effects.max-payload-bytes", "16384");
        settings.put("effects.max-expiry-blocks", "100000");
        settings.put("effects.result-window-blocks", "100000");
        settings.put("effects.default-gate", "app-final");
        settings.put("effects.outcome-commitment", "per-effect");
        settings.put("machines.evidence-registry.storage-gate", gate);
        settings.put("machines.evidence-registry.storage-expiry-blocks", "0");
        settings.put("machines.evidence-registry.notification-expiry-blocks", "0");
        settings.put("machines.composite.evidence-capacity-per-block",
                Integer.toString(capacity));
        if (continuation.equals("direct")) {
            settings.put("machines.evidence-registry.activations.direct-result-emission", "1");
        }
        AppStateMachineContext context = new AppStateMachineContext() {
            @Override public String chainId() { return chain; }
            @Override public Map<String, String> settings() { return Map.copyOf(settings); }
            @Override public Optional<AppChainConsensusProfile> consensusProfile() {
                return Optional.of(new AppChainConsensusProfile(
                        AppChainConsensusProfile.SCHEMA_VERSION,
                        AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES, 64,
                        AppChainConfig.DEFAULT_BLOCK_MAX_BYTES, 0, false,
                        true, 128, 16_384, 100_000, 100_000,
                        FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT,
                        true, members.subList(0, threshold)));
            }
            @Override public Optional<AppChainMembershipView> membershipView() {
                return Optional.of(height -> epoch);
            }
        };
        return RoleEvidencePreset.create(context).profile().digest();
    }

    private static Map<String, String> options(String[] args) {
        if (args == null || args.length == 0 || (args.length & 1) != 0) {
            throw new IllegalArgumentException("options required");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (!args[index].startsWith("--")
                    || values.putIfAbsent(args[index], args[index + 1]) != null) {
                throw new IllegalArgumentException("invalid options");
            }
        }
        return Map.copyOf(values);
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name);
        return value;
    }

    private static int positive(Map<String, String> options, String name) {
        String value = required(options, name);
        if (!value.matches("[1-9][0-9]{0,8}")) throw new IllegalArgumentException(name);
        return Integer.parseInt(value);
    }
}
