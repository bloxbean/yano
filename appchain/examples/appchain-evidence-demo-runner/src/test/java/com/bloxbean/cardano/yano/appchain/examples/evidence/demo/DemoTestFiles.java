package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

final class DemoTestFiles {
    private DemoTestFiles() {
    }

    static Path config(Path directory) throws IOException {
        Files.createDirectories(directory.resolve("secrets"));
        secret(directory.resolve("secrets/access"), "minio-access");
        secret(directory.resolve("secrets/secret"), "minio-secret");
        Files.createDirectories(directory.resolve("samples"));
        Files.writeString(directory.resolve("samples/certificate.txt"), "sample evidence\n");
        Path config = directory.resolve("demo.properties");
        Files.writeString(config, properties());
        return config;
    }

    static String properties() {
        return """
                demo.chain-id=evidence-chain
                demo.yano.urls=http://127.0.0.1:7070/api/v1,http://127.0.0.1:7071/api/v1,http://127.0.0.1:7072/api/v1
                demo.yano.member-keys=8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c,8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394,ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1
                demo.yano.threshold=2
                demo.sample-file=samples/certificate.txt
                demo.report-directory=reports
                demo.evidence-id=sample-inspection-001
                s3.endpoint=http://minio.example:9000
                s3.region=us-east-1
                s3.access-key-file=secrets/access
                s3.secret-key-file=secrets/secret
                s3.source-bucket=evidence-staging
                s3.source-prefix=staged
                s3.destination-bucket=evidence-archive
                s3.destination-prefix=verified
                s3.target=archive
                s3.target-id=archive-v1
                s3.encryption-policy-id=plain-demo-v1
                s3.retention-policy-id=none-v1
                s3.path-style=true
                ipfs.api-url=http://ipfs.example:5001
                ipfs.target=local
                ipfs.target-id=local-kubo-v1
                ipfs.replication-policy=demo-single
                kafka.bootstrap-servers=kafka:9092
                kafka.target=primary
                kafka.target-id=primary-v1
                kafka.topic-alias=evidence-ready
                kafka.physical-topic=evidence.available.v1
                scenario.timeout-seconds=30
                scenario.poll-interval-millis=100
                scenario.require-anchor=true
                """;
    }

    static void secret(Path path, String value) throws IOException {
        Files.writeString(path, value + "\n");
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        }
    }
}
