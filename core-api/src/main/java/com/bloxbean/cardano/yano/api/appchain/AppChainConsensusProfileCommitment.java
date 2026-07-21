package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcomeCommitment;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Canonical ADR-016 key, binary codec and domain-separated digest. */
public final class AppChainConsensusProfileCommitment {
    public static final int FIXED_BYTES = 53;
    public static final int MAX_BYTES = FIXED_BYTES + AppChainConfig.MAX_MEMBERS * 32;

    private static final byte FLAG_ENFORCE_SENDER_SEQ = 1;
    private static final byte FLAG_EFFECTS_ENABLED = 1 << 1;
    private static final byte FLAG_EFFECTS_STRICT_PREFIX = 1 << 2;
    private static final int KNOWN_FLAGS = FLAG_ENFORCE_SENDER_SEQ
            | FLAG_EFFECTS_ENABLED | FLAG_EFFECTS_STRICT_PREFIX;
    private static final byte[] MARKER_KEY = "~yano/consensus-profile/v1"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RESERVED_PREFIX = "~yano/"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DIGEST_DOMAIN = "yano-app-chain-consensus-profile-v1\0"
            .getBytes(StandardCharsets.US_ASCII);

    private AppChainConsensusProfileCommitment() {
    }

    public static byte[] markerKey() {
        return MARKER_KEY.clone();
    }

    public static boolean isReserved(byte[] key) {
        if (key == null || key.length < RESERVED_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < RESERVED_PREFIX.length; i++) {
            if (key[i] != RESERVED_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    public static byte[] encode(AppChainConsensusProfile profile) {
        Objects.requireNonNull(profile, "profile");
        List<String> signers = profile.effectResultSigners();
        ByteBuffer out = ByteBuffer.allocate(FIXED_BYTES + signers.size() * 32)
                .order(ByteOrder.BIG_ENDIAN);
        out.putInt(profile.schemaVersion());
        out.putInt(profile.maxMessageBytes());
        out.putInt(profile.maxBlockMessages());
        out.putLong(profile.maxBlockBytes());
        out.putInt(profile.l1StabilityDepth());
        int flags = 0;
        if (profile.enforceSenderSeq()) {
            flags |= FLAG_ENFORCE_SENDER_SEQ;
        }
        if (profile.effectsEnabled()) {
            flags |= FLAG_EFFECTS_ENABLED;
        }
        if (profile.effectsStrictReservedPrefix()) {
            flags |= FLAG_EFFECTS_STRICT_PREFIX;
        }
        out.put((byte) flags);
        out.putInt(profile.effectsMaxPerBlock());
        out.putInt(profile.effectsMaxPayloadBytes());
        out.putLong(profile.effectsMaxExpiryBlocks());
        out.putLong(profile.effectsResultWindowBlocks());
        out.put(gateCode(profile.effectsDefaultGate()));
        out.put(outcomeCode(profile.effectsOutcomeCommitment()));
        out.putShort((short) signers.size());
        for (String signer : signers) {
            byte[] key = HexUtil.decodeHexString(signer);
            if (key.length != 32) {
                throw new IllegalArgumentException("effect result signer must decode to 32 bytes");
            }
            out.put(key);
        }
        return out.array();
    }

    public static AppChainConsensusProfile decode(byte[] canonicalBytes) {
        byte[] bytes = Objects.requireNonNull(canonicalBytes, "canonicalBytes").clone();
        if (bytes.length < FIXED_BYTES || bytes.length > MAX_BYTES
                || (bytes.length - FIXED_BYTES) % 32 != 0) {
            throw new IllegalArgumentException("invalid consensus profile byte length");
        }
        try {
            ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            int schemaVersion = in.getInt();
            int maxMessageBytes = in.getInt();
            int maxBlockMessages = in.getInt();
            long maxBlockBytes = in.getLong();
            int l1StabilityDepth = in.getInt();
            int flags = Byte.toUnsignedInt(in.get());
            if ((flags & ~KNOWN_FLAGS) != 0) {
                throw new IllegalArgumentException("consensus profile contains unknown flag bits");
            }
            int effectsMaxPerBlock = in.getInt();
            int effectsMaxPayloadBytes = in.getInt();
            long effectsMaxExpiryBlocks = in.getLong();
            long effectsResultWindowBlocks = in.getLong();
            FinalityGate defaultGate = decodeGate(in.get());
            EffectOutcomeCommitment outcome = decodeOutcome(in.get());
            int signerCount = Short.toUnsignedInt(in.getShort());
            if (signerCount > AppChainConfig.MAX_MEMBERS
                    || in.remaining() != signerCount * 32) {
                throw new IllegalArgumentException("invalid consensus profile signer tail");
            }
            List<String> signers = new ArrayList<>(signerCount);
            for (int i = 0; i < signerCount; i++) {
                byte[] key = new byte[32];
                in.get(key);
                signers.add(HexUtil.encodeHexString(key));
            }
            AppChainConsensusProfile profile = new AppChainConsensusProfile(
                    schemaVersion,
                    maxMessageBytes,
                    maxBlockMessages,
                    maxBlockBytes,
                    l1StabilityDepth,
                    (flags & FLAG_ENFORCE_SENDER_SEQ) != 0,
                    (flags & FLAG_EFFECTS_ENABLED) != 0,
                    effectsMaxPerBlock,
                    effectsMaxPayloadBytes,
                    effectsMaxExpiryBlocks,
                    effectsResultWindowBlocks,
                    defaultGate,
                    outcome,
                    (flags & FLAG_EFFECTS_STRICT_PREFIX) != 0,
                    signers);
            if (!Arrays.equals(bytes, encode(profile))) {
                throw new IllegalArgumentException("consensus profile is not canonically encoded");
            }
            return profile;
        } catch (java.nio.BufferUnderflowException malformed) {
            throw new IllegalArgumentException("truncated consensus profile", malformed);
        }
    }

    public static byte[] digest(AppChainConsensusProfile profile) {
        return digest(encode(profile));
    }

    public static byte[] digest(byte[] canonicalBytes) {
        byte[] bytes = Objects.requireNonNull(canonicalBytes, "canonicalBytes").clone();
        decode(bytes);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DIGEST_DOMAIN);
            return digest.digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte gateCode(FinalityGate gate) {
        return (byte) gate.code();
    }

    private static FinalityGate decodeGate(byte code) {
        return FinalityGate.fromCode(Byte.toUnsignedInt(code));
    }

    private static byte outcomeCode(EffectOutcomeCommitment outcome) {
        return switch (outcome) {
            case PER_EFFECT -> 0;
            case PER_BLOCK -> 1;
        };
    }

    private static EffectOutcomeCommitment decodeOutcome(byte code) {
        return switch (Byte.toUnsignedInt(code)) {
            case 0 -> EffectOutcomeCommitment.PER_EFFECT;
            case 1 -> EffectOutcomeCommitment.PER_BLOCK;
            default -> throw new IllegalArgumentException("unknown effect outcome-commitment code");
        };
    }
}
