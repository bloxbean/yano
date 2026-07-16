package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.util.ArrayList;
import java.util.List;

/** Idempotent service validation and connector-resource initialization. */
final class DemoInitializer {
    private final DemoEnvironment environment;

    DemoInitializer(DemoEnvironment environment) {
        this.environment = environment;
    }

    void probe() {
        await(false);
    }

    void initialize() {
        await(true);
    }

    static void initializeConnectors(DemoConfig config) {
        try (S3DemoStore s3 = new S3DemoStore(config.s3());
             KafkaDemoClient kafka = new KafkaDemoClient(config.kafka())) {
            KuboClient kubo = new KuboClient(config.ipfs().apiUrl());
            long deadline = System.nanoTime() + config.timeout().toNanos();
            DemoError last = DemoError.SERVICE_TIMEOUT;
            while (System.nanoTime() < deadline) {
                try {
                    s3.validate();
                    kubo.probe();
                    kafka.probeBroker();
                    kafka.ensureTopic();
                    return;
                } catch (DemoException unavailable) {
                    last = unavailable.error();
                    sleep(config.pollInterval());
                }
            }
            throw new DemoException(last == DemoError.INITIALIZATION_FAILED
                    ? DemoError.INITIALIZATION_FAILED : DemoError.SERVICE_TIMEOUT);
        }
    }

    private void await(boolean initialize) {
        long deadline = System.nanoTime() + environment.config.timeout().toNanos();
        DemoError last = DemoError.SERVICE_TIMEOUT;
        while (System.nanoTime() < deadline) {
            try {
                List<YanoAuditClient.Status> statuses = new ArrayList<>();
                for (YanoAuditClient node : environment.yano) {
                    if (node.l1BlockNumber() < 1) {
                        throw new DemoException(DemoError.SERVICE_TIMEOUT);
                    }
                    statuses.add(node.status());
                }
                DemoClusterTopology.verify(statuses,
                        environment.config.yanoMemberKeys(),
                        environment.config.yanoThreshold());
                environment.s3.validate();
                environment.kubo.probe();
                environment.kafka.probeBroker();
                if (initialize) {
                    environment.kafka.ensureTopic();
                }
                return;
            } catch (DemoException unavailable) {
                if (unavailable.error() == DemoError.CLUSTER_DIVERGED) {
                    throw unavailable;
                }
                last = unavailable.error();
                sleep(environment.config.pollInterval());
            }
        }
        throw new DemoException(last == DemoError.INITIALIZATION_FAILED
                ? DemoError.INITIALIZATION_FAILED : DemoError.SERVICE_TIMEOUT);
    }

    static void sleep(java.time.Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
    }
}
