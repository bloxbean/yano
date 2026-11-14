package org.yanoproject.api.appchain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;

/** Immutable chain-wide L1 availability policy. Legacy chains retain their exact context digest. */
public enum AppLedgerOperatingProfile {
    CARDANO("cardano"),
    STANDALONE("standalone-v1");

    public static final String SETTING = "consensus.operating-profile";
    private static final byte[] DOMAIN = "yano-app-ledger-standalone-v1\0".getBytes(StandardCharsets.US_ASCII);
    private final String id;

    AppLedgerOperatingProfile(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static AppLedgerOperatingProfile from(Map<String, String> settings) {
        String value = settings.getOrDefault(SETTING, CARDANO.id).trim().toLowerCase(Locale.ROOT);
        for (AppLedgerOperatingProfile profile : values()) {
            if (profile.id.equals(value)) return profile;
        }
        throw new IllegalArgumentException("Unknown yano.app-chain." + SETTING);
    }

    public byte[] bindContext(byte[] legacyDigest) {
        if (legacyDigest == null || legacyDigest.length != 32) {
            throw new IllegalArgumentException("Consensus context digest must be 32 bytes");
        }
        if (this == CARDANO) return legacyDigest.clone();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            return digest.digest(legacyDigest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public void validate(AppChainConfig config) {
        if (this != STANDALONE) return;
        if (config.l1StabilityDepth() != 0) reject("l1.stability-depth");
        if (config.epochStabilityDepth() != 0) reject("l1.epoch-stability-depth");
        if (config.anchoringEnabled()) reject("anchor.enabled");
        if (config.retentionEnabled()) reject("retention.enabled");
        if ("rotating".equals(config.pluginSettings().getOrDefault("sequencer.mode", "").trim())) {
            reject("sequencer.mode=rotating (L1 slot clock)");
        }
        for (String key : config.pluginSettings().keySet()) {
            if (key.startsWith("observers.")) reject(key);
        }
        if (Boolean.parseBoolean(config.pluginSettings().getOrDefault("state.l1-proof-consumption-required", "false"))) {
            reject("state.l1-proof-consumption-required");
        }
    }

    public boolean accepts(AppBlock block) {
        return this != STANDALONE || block.l1Slot() == 0 && block.l1BlockHash().length == 0
                && block.messages().stream().noneMatch(message -> message.getTopic().startsWith("~l1/"));
    }

    private static void reject(String suffix) {
        throw new IllegalArgumentException("yano.app-chain." + suffix + " requires Cardano L1; "
                + "it is unavailable in standalone-v1");
    }
}
