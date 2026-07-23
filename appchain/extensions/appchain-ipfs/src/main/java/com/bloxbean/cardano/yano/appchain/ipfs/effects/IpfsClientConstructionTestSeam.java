package com.bloxbean.cardano.yano.appchain.ipfs.effects;

/** Explicitly test-only gate for constructing a real provider client at startup. */
final class IpfsClientConstructionTestSeam {
    static final String TEST_MODE_PROPERTY = "yano.test.enabled";
    static final String MODE_PROPERTY = "yano.test.connector-client-construction-smoke";
    static final String MODE_V1 = "v1";

    private IpfsClientConstructionTestSeam() {
    }

    static boolean armed() {
        String mode = System.getProperty(MODE_PROPERTY);
        if (mode == null) {
            return false;
        }
        if (!Boolean.parseBoolean(System.getProperty(TEST_MODE_PROPERTY, "false"))) {
            throw new IllegalArgumentException(
                    "connector client construction smoke requires -D"
                            + TEST_MODE_PROPERTY + "=true");
        }
        if (!MODE_V1.equals(mode)) {
            throw new IllegalArgumentException(
                    "connector client construction smoke mode must be exactly " + MODE_V1);
        }
        return true;
    }
}
