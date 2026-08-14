package com.bloxbean.cardano.yano.api.appchain.authmap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatorInitContextTest {

    @Test
    void snapshotsEveryMutableInput() {
        byte[] parameters = {(byte) 0xa0};
        List<String> collections = new ArrayList<>(List.of("alpha", "beta"));
        ValidatorInitContext context = new ValidatorInitContext(
                "descriptor", "provider", "contract-v1", parameters, collections);

        parameters[0] = 0;
        collections.clear();
        byte[] returned = context.parameters();
        returned[0] = 0;

        assertThat(context.parameters()).containsExactly((byte) 0xa0);
        assertThat(context.collectionIds()).containsExactly("alpha", "beta");
        assertThatThrownBy(() -> context.collectionIds().add("gamma"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnorderedDuplicateAndOversizedInputs() {
        assertThatThrownBy(() -> new ValidatorInitContext(
                "descriptor", "provider", "contract-v1", new byte[]{(byte) 0xa0},
                List.of("beta", "alpha")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonically ordered");
        assertThatThrownBy(() -> new ValidatorInitContext(
                "descriptor", "provider", "contract-v1", new byte[]{(byte) 0xa0},
                List.of("alpha", "alpha")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> new ValidatorInitContext(
                "descriptor", "provider", "contract-v1",
                new byte[ValidatorInitContext.MAX_PARAMETERS_BYTES + 1], List.of("alpha")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parameters");
    }
}
