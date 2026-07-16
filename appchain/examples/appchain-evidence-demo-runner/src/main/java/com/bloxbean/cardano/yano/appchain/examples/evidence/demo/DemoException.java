package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

/** Runner exception whose message never reflects external or secret data. */
public final class DemoException extends RuntimeException {
    private final DemoError error;

    public DemoException(DemoError error) {
        super(error.name(), null, false, false);
        this.error = error;
    }

    public DemoError error() {
        return error;
    }
}
