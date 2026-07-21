package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Arrays;
import java.util.Objects;

/** Public, canonical mapping from component-local keys to MPF physical keys. */
public final class CompositeStateKeys {
    public static final int MAX_PHYSICAL_KEY_BYTES = 256;
    private static final byte[] PROFILE_MARKER = "~composite/profile/v1"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COMPONENT_DOMAIN = "yano-composite-state-v1\0"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EFFECT_OWNER_DOMAIN = "~composite/effect-owner/v1/"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] QUOTA_DOMAIN = "~composite/quota/v1/"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WORKFLOW_QUOTA_DOMAIN = "~composite/workflow-quota/v1/"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WORKFLOW_CLAIM_DOMAIN = "~composite/workflow-claim/v1/"
            .getBytes(StandardCharsets.US_ASCII);

    private CompositeStateKeys() {
    }

    /** Reserved authenticated key containing the canonical effective profile. */
    public static byte[] profileMarkerKey() {
        return CompositeCommitmentV1.profileMarkerKey();
    }

    public static byte[] componentKey(String componentId, byte[] localKey) {
        return CompositeCommitmentV1.componentKey(componentId, localKey);
    }

    static boolean isComponentKey(byte[] key) {
        return key != null && key.length > COMPONENT_DOMAIN.length
                && Arrays.equals(COMPONENT_DOMAIN,
                Arrays.copyOfRange(key, 0, COMPONENT_DOMAIN.length));
    }

    static byte[] effectOwnerKey(com.bloxbean.cardano.yano.api.appchain.effects.EffectId effectId) {
        byte[] hash = Objects.requireNonNull(effectId, "effectId").hash();
        return ByteBuffer.allocate(EFFECT_OWNER_DOMAIN.length + hash.length)
                .put(EFFECT_OWNER_DOMAIN).put(hash).array();
    }

    static byte[] quotaKey(long blockHeight, ComponentGeneration generation) {
        if (blockHeight < 1) {
            throw new IllegalArgumentException("blockHeight must be >= 1");
        }
        byte[] suffix = (blockHeight + "/" + generation.componentId() + "/" + generation.fromHeight())
                .getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(QUOTA_DOMAIN.length + suffix.length)
                .put(QUOTA_DOMAIN).put(suffix).array();
    }

    static byte[] workflowQuotaKey(long blockHeight, WorkflowDescriptor workflow) {
        if (blockHeight < 1) {
            throw new IllegalArgumentException("blockHeight must be >= 1");
        }
        byte[] suffix = (blockHeight + "/" + workflow.workflowId() + "/" + workflow.fromHeight())
                .getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(WORKFLOW_QUOTA_DOMAIN.length + suffix.length)
                .put(WORKFLOW_QUOTA_DOMAIN).put(suffix).array();
    }

    static byte[] workflowClaimKey(WorkflowDescriptor workflow, String operationId) {
        String id = CompositeValidation.printable(operationId, "workflow operationId");
        byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(id.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        byte[] workflowId = (workflow.workflowId() + "/" + workflow.fromHeight() + "/")
                .getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(WORKFLOW_CLAIM_DOMAIN.length + workflowId.length + digest.length)
                .put(WORKFLOW_CLAIM_DOMAIN).put(workflowId).put(digest).array();
    }

    static byte[] encodeGeneration(ComponentGeneration generation) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeString(out, generation.componentId());
            writeString(out, generation.semanticVersion());
            out.writeLong(generation.fromHeight());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory generation encoding failed", impossible);
        }
    }

    static ComponentGeneration decodeGeneration(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > 512) {
            throw new IllegalArgumentException("invalid effect-owner generation encoding");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            ComponentGeneration generation = new ComponentGeneration(
                    readString(in), readString(in), in.readLong());
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing effect-owner generation bytes");
            }
            return generation;
        } catch (IOException | RuntimeException malformed) {
            if (malformed instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalArgumentException("invalid effect-owner generation encoding", malformed);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 1 || size > CompositeValidation.MAX_TEXT_BYTES || size > in.available()) {
            throw new IllegalArgumentException("invalid generation string length");
        }
        byte[] bytes = in.readNBytes(size);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("generation string is not canonical UTF-8");
        }
        return value;
    }
}
