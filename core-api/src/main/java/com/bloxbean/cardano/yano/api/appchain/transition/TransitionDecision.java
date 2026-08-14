package com.bloxbean.cardano.yano.api.appchain.transition;

import java.util.Objects;

/** Pure result of capability evaluation: either one complete plan or a rejection. */
public sealed interface TransitionDecision permits TransitionDecision.Approved,
        TransitionDecision.Rejected {

    static Approved approve(TransitionPlan plan) {
        return new Approved(plan);
    }

    static Rejected reject(String code, String detail) {
        return new Rejected(new TransitionRejection(code, detail));
    }

    record Approved(TransitionPlan plan) implements TransitionDecision {
        public Approved {
            plan = Objects.requireNonNull(plan, "plan");
        }
    }

    record Rejected(TransitionRejection rejection) implements TransitionDecision {
        public Rejected {
            rejection = Objects.requireNonNull(rejection, "rejection");
        }
    }
}
