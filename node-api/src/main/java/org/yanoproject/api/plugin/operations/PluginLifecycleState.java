package org.yanoproject.api.plugin.operations;

/** Node-local lifecycle state of one selected bundle, contribution, or product. */
public enum PluginLifecycleState {
    NOT_SELECTED,
    VALIDATED,
    ACTIVATING,
    ACTIVE,
    STOPPED,
    FAILED,
    CLOSED
}
