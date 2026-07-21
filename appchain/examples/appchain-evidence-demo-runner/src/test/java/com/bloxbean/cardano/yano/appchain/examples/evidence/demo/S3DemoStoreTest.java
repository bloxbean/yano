package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3DemoStoreTest {
    private static final int LIMIT = 16;

    @Test
    void readsABoundedObject() throws Exception {
        byte[] content = "bounded-object".getBytes(StandardCharsets.US_ASCII);
        try (ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) content.length).build(),
                new ByteArrayInputStream(content))) {
            assertThat(S3DemoStore.readObjectBody(response, LIMIT)).isEqualTo(content);
        }
    }

    @Test
    void abortsWithoutReadingWhenTheDeclaredBodyIsOversized() {
        CountingInputStream hostile = new CountingInputStream();
        ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) LIMIT + 1).build(), hostile);

        assertExternalStateMismatch(() -> S3DemoStore.readObjectBody(response, LIMIT));
        assertThat(hostile.read).isZero();
        assertThat(hostile.closed).isTrue();
    }

    @Test
    void abortsAnUnboundedBodyAfterOnlyOneByteBeyondTheLimit() {
        CountingInputStream hostile = new CountingInputStream();
        ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                GetObjectResponse.builder().build(), hostile);

        assertExternalStateMismatch(() -> S3DemoStore.readObjectBody(response, LIMIT));
        assertThat(hostile.read).isEqualTo(LIMIT + 1);
        assertThat(hostile.closed).isTrue();
    }

    private static void assertExternalStateMismatch(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.EXTERNAL_STATE_MISMATCH);
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static final class CountingInputStream extends InputStream {
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
        public void close() {
            closed = true;
        }
    }
}
