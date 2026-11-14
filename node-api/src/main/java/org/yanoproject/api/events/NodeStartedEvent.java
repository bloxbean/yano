package org.yanoproject.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;

public record NodeStartedEvent(long timestamp) implements Event {}
