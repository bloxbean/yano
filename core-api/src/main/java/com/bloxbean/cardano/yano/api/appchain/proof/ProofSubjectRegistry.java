package com.bloxbean.cardano.yano.api.appchain.proof;

import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Effective, manifest-bound subject registry for one chain generation. */
public final class ProofSubjectRegistry {
    private final List<ProofSubjectDescriptorV1> descriptors;
    private final Map<String, ProofSubjectProvider> providers;

    private ProofSubjectRegistry(List<ProofSubjectDescriptorV1> descriptors,
                                 Map<String, ProofSubjectProvider> providers) {
        this.descriptors = List.copyOf(descriptors);
        this.providers = Map.copyOf(providers);
    }

    public static ProofSubjectRegistry empty() {
        return new ProofSubjectRegistry(List.of(), Map.of());
    }

    public static ProofSubjectRegistry bind(AppCapabilityManifest manifest,
                                            List<ProofSubjectProvider> candidates) {
        Objects.requireNonNull(manifest, "manifest");
        Map<String, AppCapabilityManifest.ProofSubject> declared = new LinkedHashMap<>();
        manifest.proofSubjects().forEach(subject -> declared.put(subject.subjectId(), subject));
        List<ProofSubjectDescriptorV1> effective = new ArrayList<>();
        Map<String, ProofSubjectProvider> selected = new LinkedHashMap<>();
        for (ProofSubjectProvider provider : Objects.requireNonNull(candidates, "candidates")) {
            if (provider == null) throw new IllegalArgumentException("null proof subject provider");
            List<ProofSubjectDescriptorV1> offered = List.copyOf(provider.descriptors(manifest));
            if (effective.size() + offered.size() > ProofSubjectDescriptorV1.MAX_DESCRIPTORS) {
                throw new IllegalArgumentException("too many proof subject descriptors");
            }
            for (ProofSubjectDescriptorV1 descriptor : offered) {
                var subject = declared.get(descriptor.subjectId());
                if (subject == null
                        || subject.subjectVersion() != descriptor.subjectVersion()
                        || !subject.componentId().equals(descriptor.componentId())
                        || !subject.descriptorDigest().equals(descriptor.descriptorDigest())) {
                    throw new IllegalArgumentException("proof subject descriptor is not manifest-bound: "
                            + descriptor.subjectId());
                }
                if (selected.putIfAbsent(descriptor.subjectId(), provider) != null) {
                    throw new IllegalArgumentException("colliding proof subject provider: "
                            + descriptor.subjectId());
                }
                effective.add(descriptor);
            }
        }
        effective.sort(java.util.Comparator.comparing(ProofSubjectDescriptorV1::subjectId));
        return new ProofSubjectRegistry(effective, selected);
    }

    public List<ProofSubjectDescriptorV1> descriptors() { return descriptors; }

    public ProofSubjectProvider provider(String subjectId) {
        ProofSubjectProvider provider = providers.get(subjectId);
        if (provider == null) throw new IllegalArgumentException("unknown typed proof subject");
        return provider;
    }

    public ProofSubjectDescriptorV1 descriptor(String subjectId) {
        return descriptors.stream().filter(value -> value.subjectId().equals(subjectId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown typed proof subject"));
    }
}
