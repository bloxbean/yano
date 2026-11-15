package org.yanoproject.app.spi;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.yanoproject.app.YanoProducer;
import org.yanoproject.runtime.assembly.Yano;

/** Publishes the application's single {@link Yano} instance for injection. */
@ApplicationScoped
public class YanoInstanceProducer {

    private final YanoProducer producer;

    @Inject
    public YanoInstanceProducer(YanoProducer producer) {
        this.producer = producer;
    }

    @Produces
    @ApplicationScoped
    public Yano yano() {
        return producer.ensureYano();
    }
}
