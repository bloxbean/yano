package org.yanoproject.runtime.appchain;

import org.yanoproject.api.appchain.topics.TopicRefusal;

import java.util.Optional;

/**
 * A block carries a topic command or write that the registry refuses (ADR app-layer/039 D11, I19): the block
 * is invalid. Thrown by the topic kernel inside block application; a follower rejects the proposal on it.
 */
final class TopicRefusedException extends IllegalArgumentException {
    private final TopicRefusal refusal;

    TopicRefusedException(String what, TopicRefusal refusal, String detail) {
        super("block carries " + what + ": " + refusal + " (" + detail + ")");
        this.refusal = refusal;
    }

    TopicRefusal refusal() {
        return refusal;
    }

    /** The refusal behind a block-application failure, if that is what failed it. */
    static Optional<TopicRefusedException> causeOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TopicRefusedException refused) {
                return Optional.of(refused);
            }
        }
        return Optional.empty();
    }
}
