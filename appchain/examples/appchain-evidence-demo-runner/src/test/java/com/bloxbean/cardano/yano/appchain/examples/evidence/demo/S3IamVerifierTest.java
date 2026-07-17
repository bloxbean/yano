package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3IamVerifierTest {
    private static final byte[] PROBE = "yano-rustfs-iam-probe-v1\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void readsOnlyTheExactBoundedProbe() throws Exception {
        try (ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) PROBE.length).build(),
                new ByteArrayInputStream(PROBE))) {
            assertThat(S3IamVerifier.readProbeBody(response)).isEqualTo(PROBE);
        }
    }

    @Test
    void abortsAnUnboundedHostileBodyAfterOneByteBeyondTheProbe() {
        CountingHostileInputStream hostile = new CountingHostileInputStream();
        ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                GetObjectResponse.builder().build(), hostile);

        assertThatThrownBy(() -> S3IamVerifier.readProbeBody(response))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.EXTERNAL_STATE_MISMATCH);
        assertThat(hostile.read).isEqualTo(PROBE.length + 1);
        assertThat(hostile.closed).isTrue();
    }

    private static final class CountingHostileInputStream extends InputStream {
        private int read;
        private boolean closed;

        @Override
        public int read() {
            read++;
            return 'x';
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            Arrays.fill(target, offset, offset + length, (byte) 'x');
            read += length;
            return length;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
