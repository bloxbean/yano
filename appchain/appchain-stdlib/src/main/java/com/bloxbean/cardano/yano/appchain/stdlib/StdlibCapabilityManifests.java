package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;

import java.util.List;

final class StdlibCapabilityManifests {
    private StdlibCapabilityManifests() {
    }

    static AppCapabilityManifest.Builder component(String id, String topic) {
        return component(id, topic, List.of());
    }

    static AppCapabilityManifest.Builder component(
            String id, String topic, List<String> querySubjects
    ) {
        return component(id, List.of(topic), querySubjects);
    }

    static AppCapabilityManifest.Builder component(
            String id, List<String> topics, List<String> querySubjects
    ) {
        return AppCapabilityManifest.builder(id, "1.0.0")
                .component(new AppCapabilityManifest.Component(
                        id, "1.0.0", "intrinsic-v1", "application/v1",
                        topics, querySubjects, AppCapabilityManifest.Origin.INTRINSIC));
    }
}
