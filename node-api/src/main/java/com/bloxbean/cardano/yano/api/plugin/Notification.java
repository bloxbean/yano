package com.bloxbean.cardano.yano.api.plugin;

import com.bloxbean.cardano.yaci.events.api.EventMetadata;

import java.util.Map;

public interface Notification {
    String type();
    Map<String, Object> attributes();
    Object payload();
    EventMetadata metadata();
}

