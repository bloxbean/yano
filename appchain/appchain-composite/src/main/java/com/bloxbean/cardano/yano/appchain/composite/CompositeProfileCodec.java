package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
}
