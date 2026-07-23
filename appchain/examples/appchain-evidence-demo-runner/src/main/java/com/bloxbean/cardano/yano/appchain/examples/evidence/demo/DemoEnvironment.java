package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.util.List;

/** Owned clients shared by init, probe, and run commands. */
final class DemoEnvironment implements AutoCloseable {
    final DemoConfig config;
    final List<YanoAuditClient> yano;
    final S3DemoStore s3;
    final KuboClient kubo;
    final KafkaDemoClient kafka;

    DemoEnvironment(DemoConfig config) {
        this.config = config;
        this.yano = config.yanoUrls().stream()
                .map(uri -> new YanoAuditClient(uri, config.chainId(),
                        config.yanoMemberKeys(), config.yanoThreshold(),
                        config.yanoApiKey(), config.stateMachine(),
                        config.expectedCompositeProfileDigest()))
                .toList();
        S3DemoStore newS3 = null;
        KafkaDemoClient newKafka = null;
        try {
            newS3 = new S3DemoStore(config.s3());
            newKafka = new KafkaDemoClient(config.kafka());
            this.s3 = newS3;
            this.kafka = newKafka;
            this.kubo = new KuboClient(config.ipfs().apiUrl());
        } catch (RuntimeException failure) {
            if (newKafka != null) {
                newKafka.close();
            }
            if (newS3 != null) {
                newS3.close();
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        try {
            kafka.close();
        } finally {
            s3.close();
        }
    }
}
