package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceScenarioFileBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsAnOversizedSampleWithoutReadingIt() throws Exception {
        Path sample = temporary.resolve("oversized-sample");
        try (var channel = Files.newByteChannel(sample,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(EvidenceScenario.MAX_SAMPLE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[]{1}));
        }

        assertThatThrownBy(() -> EvidenceScenario.readSample(sample))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.SAMPLE_INVALID);
    }

    @Test
    void rejectsASymlinkedSample() throws Exception {
        Path target = temporary.resolve("sample");
        Files.writeString(target, "evidence");
        Path link = temporary.resolve("sample-link");
        Files.createSymbolicLink(link, target.getFileName());

        assertThatThrownBy(() -> EvidenceScenario.readSample(link))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.SAMPLE_INVALID);
    }
}
