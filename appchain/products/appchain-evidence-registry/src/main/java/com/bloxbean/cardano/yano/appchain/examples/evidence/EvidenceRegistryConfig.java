package com.bloxbean.cardano.yano.appchain.examples.evidence;

import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.effects.ActivationSchedule;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcomeCommitment;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;

import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Immutable, consensus-affecting configuration for the evidence registry. */
public final class EvidenceRegistryConfig {
    private static final String PREFIX = "machines.evidence-registry.";
    private static final Pattern PUBLIC_KEY = Pattern.compile("[0-9a-fA-F]{64}");
    private final String chainId;
    private final Set<String> issuers;
    private final Set<String> notifySenders;
    private final FinalityGate storageGate;
    private final long storageExpiryBlocks;
    private final long notificationExpiryBlocks;
    private final ActivationSchedule activations;

    EvidenceRegistryConfig(String chainId,
                           Set<String> issuers,
                           Set<String> notifySenders,
                           FinalityGate storageGate,
                           long storageExpiryBlocks,
                           long notificationExpiryBlocks,
                           ActivationSchedule activations) {
        this.chainId = requireChainId(chainId);
        this.issuers = Set.copyOf(Objects.requireNonNull(issuers, "issuers"));
        this.notifySenders = Set.copyOf(Objects.requireNonNull(notifySenders, "notifySenders"));
        this.storageGate = Objects.requireNonNull(storageGate, "storageGate");
        if (storageGate != FinalityGate.APP_FINAL && storageGate != FinalityGate.L1_ANCHORED) {
            throw invalid("storage-gate must be app-final or l1-anchored");
        }
        if (storageExpiryBlocks < 0 || notificationExpiryBlocks < 0) {
            throw invalid("expiry values must be non-negative");
        }
        this.storageExpiryBlocks = storageExpiryBlocks;
        this.notificationExpiryBlocks = notificationExpiryBlocks;
        this.activations = Objects.requireNonNull(activations, "activations");
    }

    /** Parses and validates the chain context before the machine is activated. */
    public static EvidenceRegistryConfig from(AppStateMachineContext context) {
        Objects.requireNonNull(context, "context");
        Map<String, String> settings = Map.copyOf(
                Objects.requireNonNull(context.settings(), "context.settings()"));
        AppChainConsensusProfile profile = context.consensusProfile().orElseThrow(() ->
                invalid("evidence-registry requires AppStateMachineContext.consensusProfile() (ADR-016)"));
        requireEffects(profile);

        FinalityGate gate = switch (setting(settings, PREFIX + "storage-gate", "app-final")
                .toLowerCase(Locale.ROOT)) {
            case "app-final", "app_final" -> FinalityGate.APP_FINAL;
            case "l1-anchored", "l1_anchored" -> FinalityGate.L1_ANCHORED;
            default -> throw invalid("storage-gate must be app-final or l1-anchored");
        };
        long storageExpiry = nonNegativeLong(settings,
                PREFIX + "storage-expiry-blocks", 0);
        long notificationExpiry = nonNegativeLong(settings,
                PREFIX + "notification-expiry-blocks", 0);
        validateExpiryWindow(profile, storageExpiry, notificationExpiry);

        return new EvidenceRegistryConfig(
                context.chainId(),
                publicKeys(settings, PREFIX + "issuers"),
                publicKeys(settings, PREFIX + "notify-senders"),
                gate,
                storageExpiry,
                notificationExpiry,
                ActivationSchedule.from(settings, EvidenceRegistryStateMachine.ID));
    }

    public String chainId() {
        return chainId;
    }

    public FinalityGate storageGate() {
        return storageGate;
    }

    public long storageExpiryBlocks() {
        return storageExpiryBlocks;
    }

    public long notificationExpiryBlocks() {
        return notificationExpiryBlocks;
    }

    public boolean directResultEmissionActive(long height) {
        return activations.isActive("direct-result-emission", height);
    }

    /** An empty issuer allow-list means any already-authenticated chain member. */
    public boolean isIssuer(byte[] sender) {
        String encoded = publicKey(sender);
        return encoded != null && (issuers.isEmpty() || issuers.contains(encoded));
    }

    /** Owners are always allowed; configured runners are an additional allow-list. */
    public boolean canNotify(byte[] owner, byte[] sender) {
        String senderHex = publicKey(sender);
        String ownerHex = publicKey(owner);
        return senderHex != null && ownerHex != null
                && (ownerHex.equals(senderHex) || notifySenders.contains(senderHex));
    }

    /**
     * Bounded non-secret identity of the fully parsed consensus configuration,
     * used by the ADR-013.2 composite profile commitment.
     */
    public String configurationId() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeStrings(out, issuers.stream().sorted().toList());
            writeStrings(out, notifySenders.stream().sorted().toList());
            writeString(out, storageGate.name());
            out.writeLong(storageExpiryBlocks);
            out.writeLong(notificationExpiryBlocks);
            var entries = activations.entries().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList();
            out.writeInt(entries.size());
            for (Map.Entry<String, Long> entry : entries) {
                writeString(out, entry.getKey());
                out.writeLong(entry.getValue());
            }
            out.flush();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("Unable to derive evidence configuration identity", impossible);
        }
    }

    private static void writeStrings(DataOutputStream out, java.util.List<String> values)
            throws java.io.IOException {
        out.writeInt(values.size());
        for (String value : values) {
            writeString(out, value);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws java.io.IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static void requireEffects(AppChainConsensusProfile profile) {
        if (!profile.effectsEnabled()) {
            throw invalid("effects.enabled must be true");
        }
        if (profile.effectsMaxPerBlock() < 2) {
            throw invalid("effects.max-per-block must be at least 2");
        }
        // Storage commands are at most 2 KiB. The registry's narrower Kafka
        // command (1 KiB event, two 63-byte aliases, 40-byte key, no headers)
        // is also strictly below this bound.
        int requiredPayload = ObjectPutCommandV1.MAX_ENCODED_BYTES;
        if (profile.effectsMaxPayloadBytes() < requiredPayload) {
            throw invalid("effects.max-payload-bytes is too small for evidence effects");
        }
        long worstCaseEffects = Math.multiplyExact((long) profile.maxBlockMessages(), 2L);
        if (worstCaseEffects > profile.effectsMaxPerBlock()) {
            throw invalid("block.max-messages * 2 must be <= effects.max-per-block");
        }
        if (profile.effectsOutcomeCommitment() != EffectOutcomeCommitment.PER_EFFECT) {
            throw invalid("evidence-registry requires effects.outcome-commitment=per-effect");
        }
    }

    private static void validateExpiryWindow(AppChainConsensusProfile profile,
                                             long storageExpiry,
                                             long notificationExpiry) {
        long limit = Math.min(profile.effectsMaxExpiryBlocks(),
                profile.effectsResultWindowBlocks());
        if ((storageExpiry > 0 && storageExpiry > limit)
                || (notificationExpiry > 0 && notificationExpiry > limit)) {
            throw invalid("evidence expiry exceeds the configured effect result window");
        }
    }

    private static Set<String> publicKeys(Map<String, String> settings, String key) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addPublicKeys(values, settings.get(key), key);
        for (int index = 0; index < 256; index++) {
            addPublicKeys(values, settings.get(key + "[" + index + "]"), key);
        }
        return Set.copyOf(values);
    }

    private static void addPublicKeys(Set<String> target, String configured, String key) {
        if (configured == null || configured.isBlank()) {
            return;
        }
        for (String candidate : configured.split(",", -1)) {
            String normalized = candidate.trim();
            if (!PUBLIC_KEY.matcher(normalized).matches()) {
                throw invalid(key.substring(PREFIX.length())
                        + " must contain 32-byte hexadecimal public keys");
            }
            target.add(normalized.toLowerCase(Locale.ROOT));
        }
    }

    private static String publicKey(byte[] key) {
        return key != null && key.length == EvidenceContract.HASH_BYTES
                ? HexFormat.of().formatHex(key) : null;
    }

    private static String setting(Map<String, String> settings, String key, String fallback) {
        String value = settings.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static long nonNegativeLong(Map<String, String> settings, String key, long fallback) {
        long value = parseLong(setting(settings, key, Long.toString(fallback)), key);
        if (value < 0) {
            throw invalid(key + " must be non-negative");
        }
        return value;
    }

    private static long parseLong(String value, String key) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid(key + " must be a canonical integer");
        }
    }

    private static String requireChainId(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            throw invalid("chain id must not be blank");
        }
        return chainId;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid evidence-registry configuration: " + reason);
    }
}
