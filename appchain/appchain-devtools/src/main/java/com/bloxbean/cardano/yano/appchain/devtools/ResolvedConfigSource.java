package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.ConfigSourceKind;

/** Redaction-safe summary of a source participating in resolved configuration. */
record ResolvedConfigSource(String name, ConfigSourceKind kind, int ordinal) {
}
