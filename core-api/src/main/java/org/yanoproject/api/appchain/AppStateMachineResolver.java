package org.yanoproject.api.appchain;

/**
 * Construction-only access to catalog-selected machines, including the host-owned ordered log.
 * Composite providers use this interface instead of linking optional implementation classes or creating
 * a second service-loader path. The host remains responsible for provider selection and plugin callback
 * isolation. A resolver is not a transition capability and must never be invoked during block execution.
 */
@FunctionalInterface
public interface AppStateMachineResolver {
    /**
     * Creates a selected machine using the component-specific construction context.
     *
     * @param machineId catalog contribution id, not a Java class name
     * @param context component-scoped settings and permitted construction capabilities
     * @return the host-wrapped state machine
     * @throws IllegalArgumentException if the requested machine cannot be resolved or configured
     */
    AppStateMachine create(String machineId, AppStateMachineContext context);
}
