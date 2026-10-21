package org.yanoproject.tx.gate;

import org.yanoproject.runtime.events.PropagatingEventBus.SubscriberInfo;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncValidator;

import java.util.ArrayList;
import java.util.List;

/**
 * ADR-056 Phase 7a ordering guard. Shadow sync captures the pre-block state from the first {@code BlockAppliedEvent}
 * listener; that is only correct while no listener that changes ledger state runs before the UTxO apply (priority
 * 100). This guard checks a live node's subscriptions: the capture listener is first, and every other subscription
 * below 100 is one of the known listeners that change no validation-visible state. A new low-priority subscriber
 * fails the guard until it is reviewed and added here.
 */
public final class ShadowSyncOrderingGuard {

    /** The first ledger-changing listener (UTxO apply); account state and governance follow at 110. */
    public static final int FIRST_LEDGER_PRIORITY = 100;

    /**
     * Known listeners below 100, by listener class prefix (a lambda's class name starts with its defining class; an
     * annotation-registered listener's with the generated {@code <Listener>_EventBindings} class). Each changes no
     * state a {@code LedgerView} reads, and runs after the capture anyway (the capture is first):
     * <ul>
     *   <li>0, {@code ChronologySubsystem}: {@code LedgerStateSubsystem.handleEraTransition} records the era start slot
     *       (the follower already persisted it before the boundary events) and the one-off Shelley-start UTxO total
     *       used by AdaPot bookkeeping;</li>
     *   <li>1, {@code LoggingPlugin}: logs the event;</li>
     *   <li>50, {@code NonceEvolutionListener}: the epoch nonce, a consensus input, not a ledger-rule input;</li>
     *   <li>0, the gate fixtures' own read-only observers ({@code BlockRevalidator}, {@code DevnetGateNode}).</li>
     * </ul>
     */
    public record Allowed(int priority, String classPrefix) {
    }

    public static final List<Allowed> ALLOWED = List.of(
            new Allowed(0, "org.yanoproject.runtime.chronology.ChronologySubsystem"),
            new Allowed(1, "org.yanoproject.runtime.plugins.LoggingPlugin"),
            new Allowed(50, "org.yanoproject.runtime.blockproducer.NonceEvolutionListener"),
            new Allowed(0, "org.yanoproject.tx.gate.BlockRevalidator"),
            new Allowed(0, "org.yanoproject.tx.gate.DevnetGateNode"));

    private ShadowSyncOrderingGuard() {
    }

    /** @throws AssertionError when the capture listener is not first or an unknown listener runs before 100 */
    public static void assertCaptureRunsFirst(List<SubscriberInfo> subscribers) {
        List<String> problems = new ArrayList<>();
        if (subscribers.isEmpty() || subscribers.getFirst().priority() != ShadowSyncValidator.SUBSCRIPTION_PRIORITY
                || !subscribers.getFirst().listenerClass().startsWith(ShadowSyncValidator.class.getName())) {
            problems.add("the shadow-sync capture listener is not the first BlockAppliedEvent listener");
        }
        for (SubscriberInfo sub : subscribers.subList(Math.min(1, subscribers.size()), subscribers.size())) {
            if (sub.priority() >= FIRST_LEDGER_PRIORITY) {
                continue;
            }
            boolean known = ALLOWED.stream().anyMatch(a -> a.priority() == sub.priority()
                    && sub.listenerClass().startsWith(a.classPrefix()));
            if (!known) {
                problems.add("unreviewed BlockAppliedEvent listener before the UTxO apply: " + sub);
            }
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(String.join("; ", problems) + ". Subscribers: " + subscribers);
        }
    }
}
