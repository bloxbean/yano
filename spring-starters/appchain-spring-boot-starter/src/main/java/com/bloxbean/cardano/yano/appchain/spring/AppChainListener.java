package com.bloxbean.cardano.yano.appchain.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean method as a consumer of finalized app-chain messages
 * (ADR app-layer/006 E1.4). The starter subscribes over SSE (auto-reconnecting)
 * and invokes the method once per finalized message, in order.
 *
 * <pre>
 * &#64;Component
 * class OrderHandler {
 *     &#64;AppChainListener(topic = "orders")
 *     void onOrder(AppChainClient.StreamedMessage message) { ... }
 * }
 * </pre>
 *
 * Supported parameter types: {@code AppChainClient.StreamedMessage} (full
 * envelope), {@code byte[]} (the body), or {@code String} (the body as UTF-8).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AppChainListener {

    /** Topic to consume; empty = all topics. */
    String topic() default "";

    /**
     * Height to replay from on first subscribe; -1 = live-only (new messages).
     * Reconnects always resume from the last seen height.
     */
    long fromHeight() default -1;
}
