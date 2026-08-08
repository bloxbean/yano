package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Preferred builder for a normal {@link AppStateMachine} composed from other machines. */
public final class ComposableAppStateMachine {
    private ComposableAppStateMachine() {
    }

    public static Builder builder(
            String machineId,
            AppStateMachineContext context,
            String profileId,
            String profileVersion
    ) {
        return new Builder(machineId, context, profileId, profileVersion);
    }

    public static final class Builder {
        private final String machineId;
        private final AppStateMachineContext context;
        private final String profileId;
        private final String profileVersion;
        private final List<ComponentDescriptor> descriptors = new ArrayList<>();
        private final List<AppStateMachine> machines = new ArrayList<>();
        private final List<CompositeWorkflow> workflows = new ArrayList<>();
        private final List<LegacyQueryAlias> aliases = new ArrayList<>();
        private AggregateQueryLimitsV1 aggregateLimits = AggregateQueryLimitsV1.DEFAULT;

        private Builder(
                String machineId,
                AppStateMachineContext context,
                String profileId,
                String profileVersion
        ) {
            this.machineId = CompositeValidation.id(machineId, "machineId");
            this.context = Objects.requireNonNull(context, "context");
            this.profileId = CompositeValidation.id(profileId, "profileId");
            this.profileVersion = CompositeValidation.printable(profileVersion, "profileVersion");
        }

        public Builder machine(ComponentDescriptor descriptor, AppStateMachine machine) {
            descriptors.add(Objects.requireNonNull(descriptor, "descriptor"));
            machines.add(Objects.requireNonNull(machine, "machine"));
            return this;
        }

        public Builder workflow(CompositeWorkflow workflow) {
            workflows.add(Objects.requireNonNull(workflow, "workflow"));
            return this;
        }

        public Builder queryAlias(LegacyQueryAlias alias) {
            aliases.add(Objects.requireNonNull(alias, "alias"));
            return this;
        }

        public Builder aggregateQueryLimits(AggregateQueryLimitsV1 limits) {
            this.aggregateLimits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        public CompositeStateMachine build() {
            List<WorkflowDescriptor> workflowDescriptors = workflows.stream()
                    .map(CompositeWorkflow::descriptor).toList();
            CompositeProfile profile = new CompositeProfile(
                    CompositeProfile.SCHEMA_VERSION, profileId, profileVersion,
                    descriptors, workflowDescriptors, aliases, aggregateLimits);
            return CompositeStateMachine.create(machineId, context, profile, machines, workflows);
        }
    }
}
