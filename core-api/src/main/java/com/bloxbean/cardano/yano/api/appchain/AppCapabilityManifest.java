package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable v1 discovery description for one application profile.
 * Operational counters deliberately do not belong here.
 */
public record AppCapabilityManifest(
        int schemaVersion,
        String applicationId,
        String applicationVersion,
        String manifestDigest,
        List<Component> components,
        List<Workflow> workflows,
        List<CrossCutting> crossCutting,
        List<ProofSubject> proofSubjects
) {
    public static final int SCHEMA_VERSION = 1;

    public AppCapabilityManifest {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported capability manifest version");
        }
        applicationId = id(applicationId, "applicationId");
        applicationVersion = text(applicationVersion, "applicationVersion");
        components = sortedUnique(components, Component::id, "component");
        workflows = sortedUnique(workflows, Workflow::id, "workflow");
        crossCutting = sortedUnique(crossCutting, CrossCutting::capabilityId, "capability");
        proofSubjects = sortedUnique(proofSubjects, ProofSubject::subjectId, "proof subject");
        String expected = digest(schemaVersion, applicationId, applicationVersion,
                components, workflows, crossCutting, proofSubjects);
        if (manifestDigest == null || manifestDigest.isEmpty()) {
            manifestDigest = expected;
        } else if (!manifestDigest.equals(expected)) {
            throw new IllegalArgumentException("capability manifest digest mismatch");
        }
    }

    public static Builder builder(String applicationId, String applicationVersion) {
        return new Builder(applicationId, applicationVersion);
    }

    public static AppCapabilityManifest application(String applicationId) {
        return builder(applicationId, "1.0.0").build();
    }

    public AppCapabilityManifest withCrossCutting(CrossCutting capability) {
        List<CrossCutting> values = new ArrayList<>(crossCutting);
        values.add(Objects.requireNonNull(capability, "capability"));
        return new AppCapabilityManifest(SCHEMA_VERSION, applicationId, applicationVersion,
                "", components, workflows, values, proofSubjects);
    }

    public AppCapabilityManifest withProofSubject(ProofSubject proofSubject) {
        List<ProofSubject> values = new ArrayList<>(proofSubjects);
        values.add(Objects.requireNonNull(proofSubject, "proofSubject"));
        return new AppCapabilityManifest(SCHEMA_VERSION, applicationId, applicationVersion,
                "", components, workflows, crossCutting, values);
    }

    public enum Origin {
        INTRINSIC,
        COMPOSED,
        LAUNCHER_ENABLED,
        RUNTIME_CONFIGURED
    }

    public record Component(
            String id,
            String version,
            String configurationId,
            String stateNamespace,
            List<String> topics,
            List<String> querySubjects,
            Origin origin
    ) {
        public Component {
            id = AppCapabilityManifest.id(id, "component id");
            version = text(version, "component version");
            configurationId = text(configurationId, "configurationId");
            stateNamespace = text(stateNamespace, "stateNamespace");
            topics = sortedStrings(topics, "topics");
            querySubjects = sortedStrings(querySubjects, "querySubjects");
            origin = Objects.requireNonNull(origin, "origin");
        }
    }

    public record Workflow(
            String id,
            String version,
            List<String> participantComponentIds,
            String topic,
            List<String> effectTypes,
            Origin origin
    ) {
        public Workflow {
            id = AppCapabilityManifest.id(id, "workflow id");
            version = text(version, "workflow version");
            participantComponentIds = sortedStrings(
                    participantComponentIds, "participantComponentIds");
            topic = text(topic, "workflow topic");
            effectTypes = sortedStrings(effectTypes, "effectTypes");
            origin = Objects.requireNonNull(origin, "origin");
        }
    }

    public record CrossCutting(
            String capabilityId,
            String version,
            boolean enabled,
            String configurationDigest,
            Map<String, String> attributes,
            Origin origin
    ) {
        public CrossCutting {
            capabilityId = AppCapabilityManifest.id(capabilityId, "capability id");
            version = text(version, "capability version");
            configurationDigest = text(configurationDigest, "configurationDigest");
            attributes = Collections.unmodifiableMap(
                    new TreeMap<>(Objects.requireNonNull(attributes, "attributes")));
            attributes.forEach((key, value) -> {
                text(key, "attribute key");
                text(value, "attribute value");
            });
            origin = Objects.requireNonNull(origin, "origin");
        }
    }

    public record ProofSubject(
            String subjectId,
            String componentId,
            String keyNamespace,
            String verificationTarget
    ) {
        public ProofSubject {
            subjectId = AppCapabilityManifest.id(subjectId, "proof subject id");
            componentId = componentId == null || componentId.isEmpty()
                    ? "" : AppCapabilityManifest.id(componentId, "proof component id");
            keyNamespace = text(keyNamespace, "keyNamespace");
            verificationTarget = text(verificationTarget, "verificationTarget");
        }
    }

    public static final class Builder {
        private final String applicationId;
        private final String applicationVersion;
        private final List<Component> components = new ArrayList<>();
        private final List<Workflow> workflows = new ArrayList<>();
        private final List<CrossCutting> crossCutting = new ArrayList<>();
        private final List<ProofSubject> proofSubjects = new ArrayList<>();

        private Builder(String applicationId, String applicationVersion) {
            this.applicationId = applicationId;
            this.applicationVersion = applicationVersion;
        }

        public Builder component(Component value) { components.add(value); return this; }
        public Builder workflow(Workflow value) { workflows.add(value); return this; }
        public Builder crossCutting(CrossCutting value) { crossCutting.add(value); return this; }
        public Builder proofSubject(ProofSubject value) { proofSubjects.add(value); return this; }

        public AppCapabilityManifest build() {
            return new AppCapabilityManifest(SCHEMA_VERSION, applicationId,
                    applicationVersion, "", components, workflows, crossCutting, proofSubjects);
        }
    }

    private static String digest(
            int schemaVersion, String applicationId, String applicationVersion,
            List<Component> components, List<Workflow> workflows,
            List<CrossCutting> capabilities, List<ProofSubject> proofSubjects
    ) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(schemaVersion);
            write(out, applicationId);
            write(out, applicationVersion);
            out.writeInt(components.size());
            for (Component value : components) {
                write(out, value.id()); write(out, value.version());
                write(out, value.configurationId()); write(out, value.stateNamespace());
                writes(out, value.topics()); writes(out, value.querySubjects());
                write(out, value.origin().name());
            }
            out.writeInt(workflows.size());
            for (Workflow value : workflows) {
                write(out, value.id()); write(out, value.version());
                writes(out, value.participantComponentIds()); write(out, value.topic());
                writes(out, value.effectTypes()); write(out, value.origin().name());
            }
            out.writeInt(capabilities.size());
            for (CrossCutting value : capabilities) {
                write(out, value.capabilityId()); write(out, value.version());
                out.writeBoolean(value.enabled()); write(out, value.configurationDigest());
                out.writeInt(value.attributes().size());
                for (Map.Entry<String, String> entry : value.attributes().entrySet()) {
                    write(out, entry.getKey()); write(out, entry.getValue());
                }
                write(out, value.origin().name());
            }
            out.writeInt(proofSubjects.size());
            for (ProofSubject value : proofSubjects) {
                write(out, value.subjectId()); write(out, value.componentId());
                write(out, value.keyNamespace()); write(out, value.verificationTarget());
            }
            out.flush();
            return HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writes(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) write(out, value);
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static <T> List<T> sortedUnique(
            List<T> values,
            java.util.function.Function<T, String> key,
            String kind
    ) {
        List<T> sorted = new ArrayList<>(Objects.requireNonNull(values, kind));
        sorted.sort(Comparator.comparing(key));
        Set<String> ids = new HashSet<>();
        for (T value : sorted) {
            if (value == null || !ids.add(key.apply(value))) {
                throw new IllegalArgumentException("duplicate " + kind + " id");
            }
        }
        return List.copyOf(sorted);
    }

    private static List<String> sortedStrings(List<String> values, String field) {
        List<String> sorted = Objects.requireNonNull(values, field).stream()
                .map(value -> text(value, field + " entry")).sorted().toList();
        if (sorted.stream().distinct().count() != sorted.size()) {
            throw new IllegalArgumentException(field + " contains duplicates");
        }
        return sorted;
    }

    private static String id(String value, String field) {
        value = text(value, field);
        if (!value.matches("[a-z0-9][a-z0-9:._-]{0,127}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    private static String text(String value, String field) {
        value = Objects.requireNonNull(value, field);
        if (value.isBlank() || value.indexOf('\0') >= 0
                || value.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
