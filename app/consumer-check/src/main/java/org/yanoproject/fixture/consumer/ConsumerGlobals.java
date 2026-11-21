package org.yanoproject.fixture.consumer;

import jakarta.enterprise.context.ApplicationScoped;
import org.yanoproject.app.spi.RuntimeGlobalsContributor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Downstream runtime-globals contribution. */
@ApplicationScoped
public class ConsumerGlobals implements RuntimeGlobalsContributor {

    public static final String KEY = "yano.fixture.consumer-check";

    private final AtomicInteger calls = new AtomicInteger();

    /** How often the host read this contribution. */
    public int calls() {
        return calls.get();
    }

    @Override
    public Map<String, Object> globals() {
        calls.incrementAndGet();
        return Map.of(KEY, "present");
    }
}
