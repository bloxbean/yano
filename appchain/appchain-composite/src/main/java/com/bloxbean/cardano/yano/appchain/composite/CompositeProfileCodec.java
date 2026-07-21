package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Canonical binary profile encoding and domain-separated digest. */
public final class CompositeProfileCodec {
    private CompositeProfileCodec() {
    }

    public static byte[] encode(CompositeProfile profile) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(profile.schemaVersion());
            writeString(out, profile.profileId());
            writeString(out, profile.profileVersion());
            out.writeInt(profile.components().size());
            for (ComponentDescriptor component : profile.components()) {
                writeString(out, component.componentId());
                writeString(out, component.semanticVersion());
                writeString(out, component.configurationId());
                writeString(out, component.stateAndResultCompatibilityId());
                out.writeLong(component.fromHeight());
                out.writeLong(component.untilHeight());
                writeStrings(out, component.topics());
                writeStrings(out, component.queryPaths());
                out.writeInt(component.maxEffectsPerBlock());
            }
            out.writeInt(profile.workflows().size());
            for (WorkflowDescriptor workflow : profile.workflows()) {
                writeString(out, workflow.workflowId());
                writeString(out, workflow.semanticVersion());
                writeString(out, workflow.topic());
                out.writeLong(workflow.fromHeight());
                out.writeLong(workflow.untilHeight());
                out.writeInt(workflow.participants().size());
                for (ComponentGeneration participant : workflow.participants()) {
                    writeString(out, participant.componentId());
                    writeString(out, participant.semanticVersion());
                    out.writeLong(participant.fromHeight());
                }
                out.writeInt(workflow.maxEffectsPerBlock());
            }
            out.writeInt(profile.queryAliases().size());
            for (LegacyQueryAlias alias : profile.queryAliases()) {
                writeString(out, alias.aliasPath());
                writeString(out, alias.componentId());
                writeString(out, alias.localPath());
            }
            AggregateQueryLimitsV1 limits = profile.aggregateQueryLimits();
            out.writeInt(limits.maxSubqueries());
            out.writeInt(limits.maxParameterBytes());
            out.writeInt(limits.maxResponseBytes());
            out.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > CompositeCommitmentV1.MAX_PROFILE_BYTES) {
                throw new IllegalArgumentException("canonical composite profile exceeds 65536 bytes");
            }
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory profile encoding failed", impossible);
        }
    }

    public static byte[] digest(CompositeProfile profile) {
        return CompositeCommitmentV1.profileDigest(encode(profile));
    }

    /** Strict inverse of the frozen v1 encoding with canonical round-trip enforcement. */
    public static CompositeProfile decode(byte[] encoded) {
        byte[] bytes = encoded != null ? encoded.clone() : null;
        if (bytes == null || bytes.length == 0
                || bytes.length > CompositeCommitmentV1.MAX_PROFILE_BYTES) {
            throw new IllegalArgumentException("invalid canonical composite profile length");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int schema = in.readInt();
            String profileId = readString(in);
            String profileVersion = readString(in);
            int componentCount = readCount(in, CompositeProfile.MAX_COMPONENTS, "components");
            List<ComponentDescriptor> components = new ArrayList<>(componentCount);
            for (int index = 0; index < componentCount; index++) {
                components.add(new ComponentDescriptor(
                        readString(in), readString(in), readString(in), readString(in),
                        readUnsignedLong(in, "component fromHeight"),
                        readUnsignedLong(in, "component untilHeight"),
                        readStrings(in, ComponentDescriptor.MAX_ROUTES, "component topics"),
                        readStrings(in, ComponentDescriptor.MAX_ROUTES, "component queries"),
                        readNonNegativeInt(in, "component maxEffectsPerBlock")));
            }
            int workflowCount = readCount(in, CompositeProfile.MAX_WORKFLOWS, "workflows");
            List<WorkflowDescriptor> workflows = new ArrayList<>(workflowCount);
            for (int index = 0; index < workflowCount; index++) {
                String workflowId = readString(in);
                String semanticVersion = readString(in);
                String topic = readString(in);
                long fromHeight = readUnsignedLong(in, "workflow fromHeight");
                long untilHeight = readUnsignedLong(in, "workflow untilHeight");
                int participantCount = readCount(in, 16, "workflow participants");
                if (participantCount == 0) {
                    throw new IllegalArgumentException("workflow participants must not be empty");
                }
                List<ComponentGeneration> participants = new ArrayList<>(participantCount);
                for (int participant = 0; participant < participantCount; participant++) {
                    participants.add(new ComponentGeneration(readString(in), readString(in),
                            readUnsignedLong(in, "participant fromHeight")));
                }
                workflows.add(new WorkflowDescriptor(workflowId, semanticVersion, topic,
                        fromHeight, untilHeight, participants,
                        readNonNegativeInt(in, "workflow maxEffectsPerBlock")));
            }
            int aliasCount = readCount(in, CompositeProfile.MAX_ALIASES, "query aliases");
            List<LegacyQueryAlias> aliases = new ArrayList<>(aliasCount);
            for (int index = 0; index < aliasCount; index++) {
                aliases.add(new LegacyQueryAlias(readString(in), readString(in), readString(in)));
            }
            AggregateQueryLimitsV1 limits = new AggregateQueryLimitsV1(
                    readNonNegativeInt(in, "aggregate maxSubqueries"),
                    readNonNegativeInt(in, "aggregate maxParameterBytes"),
                    readNonNegativeInt(in, "aggregate maxResponseBytes"));
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing canonical composite profile bytes");
            }
            CompositeProfile profile = new CompositeProfile(schema, profileId, profileVersion,
                    components, workflows, aliases, limits);
            if (!Arrays.equals(bytes, encode(profile))) {
                throw new IllegalArgumentException("non-canonical composite profile encoding");
            }
            return profile;
        } catch (IOException | RuntimeException malformed) {
            if (malformed instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalArgumentException("invalid canonical composite profile", malformed);
        }
    }

    private static void writeStrings(DataOutputStream out, java.util.List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) {
            writeString(out, value);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static List<String> readStrings(
            DataInputStream in, int maximum, String field
    ) throws IOException {
        int count = readCount(in, maximum, field);
        List<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(readString(in));
        }
        return values;
    }

    private static String readString(DataInputStream in) throws IOException {
        int size = readCount(in, CompositeValidation.MAX_TEXT_BYTES, "text");
        if (size == 0 || size > in.available()) {
            throw new IllegalArgumentException("invalid composite profile text length");
        }
        byte[] bytes = in.readNBytes(size);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("composite profile text is not canonical UTF-8");
        }
        return value;
    }

    private static int readCount(DataInputStream in, int maximum, String field) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException("invalid composite profile " + field + " count");
        }
        return value;
    }

    private static int readNonNegativeInt(DataInputStream in, String field) throws IOException {
        int value = in.readInt();
        if (value < 0) {
            throw new IllegalArgumentException("negative composite profile " + field);
        }
        return value;
    }

    private static long readUnsignedLong(DataInputStream in, String field) throws IOException {
        long value = in.readLong();
        if (value < 0) {
            throw new IllegalArgumentException("negative composite profile " + field);
        }
        return value;
    }
}
