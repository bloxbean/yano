package com.bloxbean.cardano.yano.api.appchain.observation;

import java.util.Set;

/** Exact v1 observation wire allowlist; prefix matches alone never grant admission. */
public final class ObservationTopics {
    public static final String REPORT = "~obs-diffusion/report/v1";
    public static final String CERTIFICATE = "~obs-diffusion/certificate/v1";
    public static final String RESULT = "~obs/result/v1";
    public static final String TICK = "~obs/tick/v1";
    public static final String DIFFUSION_PREFIX = "~obs-diffusion/";
    public static final String SEQUENCED_PREFIX = "~obs/";
    private static final Set<String> ALLOWED = Set.of(REPORT, CERTIFICATE, RESULT, TICK);

    private ObservationTopics() {
    }

    public static boolean isAllowed(String topic) {
        return ALLOWED.contains(topic);
    }

    public static boolean isDiffusionOnly(String topic) {
        return REPORT.equals(topic) || CERTIFICATE.equals(topic);
    }

    public static boolean isReserved(String topic) {
        return topic != null && (topic.startsWith(DIFFUSION_PREFIX)
                || topic.startsWith(SEQUENCED_PREFIX));
    }
}
