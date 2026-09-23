package org.yanoproject.api.appchain.transition;

import java.util.List;

/** Versioned field schema of a transition event. */
public record EventDescriptor(String eventId, List<CommandDescriptor.Field> fields) {
    public EventDescriptor {
        CommandDescriptor.requireName(eventId);
        fields = List.copyOf(fields);
        if (fields.size() > TransitionScalars.MAX_FIELDS
                || fields.stream().map(CommandDescriptor.Field::name).distinct().count() != fields.size()) {
            throw new IllegalArgumentException("invalid event fields");
        }
    }
}
