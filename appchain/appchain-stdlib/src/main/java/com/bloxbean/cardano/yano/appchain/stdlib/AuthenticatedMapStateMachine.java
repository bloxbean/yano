package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValidatorResolver;
import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidator;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorInitContext;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorVerdict;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract.CollectionDescriptor;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract.Command;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract.Entry;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract.Genesis;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract.Mutation;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract.MutationResult;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract.PointQuery;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract.PointResult;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract.Receipt;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract.ReceiptQuery;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract.ReceiptResult;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract.*;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ADR-025 version-1 authenticated map.
 *
 * <p>The logical transition is commitment-profile neutral. Phase 1 runs it on
 * the existing MPF-backed runtime; later phases reuse these exact command,
 * entry, authorization, receipt, and query semantics with other backends.</p>
 */
public final class AuthenticatedMapStateMachine implements AppStateMachine {
    public static final String ID = AuthenticatedMapContract.STATE_MACHINE_ID;

    private final Genesis genesis;
    private final byte[] genesisId;
    private final Map<String, CollectionDescriptor> collections;
    private final Map<String, AuthenticatedMapSchema.Schema> schemas;
    private final Map<String, AuthenticatedMapValueValidator> pluginValidators;
    private final AppChainMembershipView membershipView;

    public AuthenticatedMapStateMachine(Genesis genesis) {
        this(genesis, null, null);
    }

    public AuthenticatedMapStateMachine(Genesis genesis, AppChainMembershipView membershipView) {
        this(genesis, membershipView, null);
    }

    public AuthenticatedMapStateMachine(
            Genesis genesis,
            AppChainMembershipView membershipView,
            AuthenticatedMapValidatorResolver validatorResolver
    ) {
        this.genesis = Objects.requireNonNull(genesis, "genesis");
        this.genesisId = AuthenticatedMapContract.genesisId(genesis);
        Map<String, CollectionDescriptor> declared = new LinkedHashMap<>();
        genesis.collections().forEach(descriptor -> declared.put(descriptor.id(), descriptor));
        this.collections = Map.copyOf(declared);
        Map<String, AuthenticatedMapSchema.Schema> declaredSchemas = new LinkedHashMap<>();
        Map<String, AuthenticatedMapValueValidator> declaredPlugins = new LinkedHashMap<>();
        genesis.validators().forEach(descriptor -> {
            if (descriptor.kind() == AuthenticatedMapContract.VALIDATOR_KIND_SCHEMA) {
                declaredSchemas.put(descriptor.id(),
                        AuthenticatedMapSchema.decode(descriptor.definition()));
            } else {
                if (validatorResolver == null) {
                    throw new IllegalArgumentException(
                            "authenticated-map plugin validator requires a catalog resolver");
                }
                List<String> collectionIds = genesis.collections().stream()
                        .filter(collection -> descriptor.id().equals(collection.validatorId()))
                        .map(CollectionDescriptor::id)
                        .sorted()
                        .toList();
                ValidatorInitContext context = new ValidatorInitContext(
                        descriptor.id(), descriptor.providerId(), descriptor.contractVersion(),
                        descriptor.parameters(), collectionIds);
                declaredPlugins.put(descriptor.id(), Objects.requireNonNull(
                        validatorResolver.resolve(descriptor.definition(), context),
                        "authenticated-map validator resolver returned null"));
            }
        });
        this.schemas = Map.copyOf(declaredSchemas);
        this.pluginValidators = Map.copyOf(declaredPlugins);
        this.membershipView = membershipView;
        if (membershipView == null && genesis.collections().stream()
                .anyMatch(descriptor -> descriptor.authorization()
                        == AuthenticatedMapContract.AUTH_MEMBER)) {
            throw new IllegalArgumentException(
                    "authenticated-map member authorization requires a membership view");
        }
        validateInitialPluginValues();
    }

    @Override
    public String id() {
        return ID;
    }

    public Genesis genesis() {
        return genesis;
    }

    public byte[] genesisId() {
        return genesisId.clone();
    }

    @Override
    public void init(AppStateReader state, AppChainInfo info) {
        Objects.requireNonNull(state, "state");
        if (!genesis.chainId().equals(info.chainId())) {
            throw new IllegalStateException("authenticated-map genesis chain id differs from runtime chain");
        }
        Optional<byte[]> marker = state.get(AuthenticatedMapContract.genesisMarkerKey());
        if (state.committedHeight() == 0) {
            if (marker.isPresent()) {
                throw new IllegalStateException(
                        "authenticated-map genesis marker exists before height 1");
            }
        } else if (marker.isEmpty() || !Arrays.equals(marker.orElseThrow(), genesisId)) {
            throw new IllegalStateException(
                    "retained authenticated-map genesis identity differs from local configuration");
        }
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        try {
            if (!AuthenticatedMapContract.DEFAULT_TOPIC.equals(message.getTopic())) {
                return AdmissionResult.reject("authenticated-map requires topic "
                        + AuthenticatedMapContract.DEFAULT_TOPIC);
            }
            if (message.getBody() == null || message.getBody().length > genesis.maxBatchBytes()) {
                return AdmissionResult.reject("authenticated-map command exceeds genesis byte limit");
            }
            Command command = AuthenticatedMapContract.decodeCommand(message.getBody());
            validateCommandBounds(command);
            if (containsGovernedMutation(command)) {
                return AdmissionResult.reject(
                        "governed collections require the final v1 authorization envelope");
            }
            validateCommandValues(command);
            return AdmissionResult.accept();
        } catch (IllegalArgumentException malformed) {
            return AdmissionResult.reject("Malformed authenticated-map v1 command");
        }
    }

    /** Structural admission for the final v1 action/evidence envelope. */
    AdmissionResult validateFinal(AppMessage message) {
        try {
            if (!AuthenticatedMapContract.DEFAULT_TOPIC.equals(message.getTopic())) {
                return AdmissionResult.reject("authenticated-map requires topic "
                        + AuthenticatedMapContract.DEFAULT_TOPIC);
            }
            if (message.getBody() == null || message.getBody().length > genesis.maxBatchBytes()) {
                return AdmissionResult.reject("authenticated-map command exceeds genesis byte limit");
            }
            AuthenticatedMapCommandV1 command =
                    AuthenticatedMapAuthorizationContract.decodeCommand(message.getBody());
            Command mutations = legacyCommand(command);
            validateCommandBounds(mutations);
            validateCommandValues(mutations);
            validateAuthorizationAssignments(command);
            return AdmissionResult.accept();
        } catch (IllegalArgumentException malformed) {
            return AdmissionResult.reject("Malformed authenticated-map v1 command");
        }
    }

    @Override
    public AdmissionResult validateForBlock(
            AppMessage message,
            long candidateHeight,
            AppStateReader committedState
    ) {
        AdmissionResult structural = validate(message);
        if (!structural.isAccepted()) {
            return structural;
        }
        Command command = AuthenticatedMapContract.decodeCommand(message.getBody());
        for (Mutation mutation : command.mutations()) {
            CollectionDescriptor descriptor = collections.get(mutation.collectionId());
            if (descriptor.authorization() == AuthenticatedMapContract.AUTH_MEMBER
                    && !isMember(message.getSender(), candidateHeight)) {
                return AdmissionResult.reject(
                        "authenticated-map sender is not a member at candidate height");
            }
        }
        return AdmissionResult.accept();
    }

    AdmissionResult validateFinalForBlock(AppMessage message, long candidateHeight) {
        AdmissionResult structural = validateFinal(message);
        if (!structural.isAccepted()) {
            return structural;
        }
        AuthenticatedMapCommandV1 command =
                AuthenticatedMapAuthorizationContract.decodeCommand(message.getBody());
        for (Mutation mutation : command.action().mutations()) {
            CollectionDescriptor descriptor = collections.get(mutation.collectionId());
            if (descriptor.authorization() == AuthenticatedMapContract.AUTH_MEMBER
                    && !isMember(message.getSender(), candidateHeight)) {
                return AdmissionResult.reject(
                        "authenticated-map sender is not a member at candidate height");
            }
        }
        return AdmissionResult.accept();
    }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                      AppEffectEmitter effects) {
        AppBlock block = context.block();
        initializeOrVerify(block.height(), writer);
        for (AppMessage message : context.messages()) {
            if (!AuthenticatedMapContract.DEFAULT_TOPIC.equals(message.getTopic())) {
                continue;
            }
            Command command;
            try {
                command = AuthenticatedMapContract.decodeCommand(message.getBody());
                validateCommandBounds(command);
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            byte[] receiptKey = AuthenticatedMapContract.receiptKey(message.getMessageId());
            if (writer.get(receiptKey).isPresent()) {
                continue;
            }
            if (containsGovernedMutation(command)) {
                Receipt receipt = Receipt.rejected(message.getMessageId(), block.height(),
                        AuthenticatedMapContract.batchCommitment(command),
                        AuthenticatedMapContract.ERROR_GOVERNED_ROUTE_UNSUPPORTED);
                writer.put(receiptKey, AuthenticatedMapContract.encodeReceipt(receipt));
                continue;
            }
            applyCommand(block.height(), message, command, receiptKey, writer);
        }
    }

    /** Applies the final v1 envelope inside the authenticated-map composite workflow. */
    void applyFinal(AppBlockExecutionContext context, AppStateWriter writer) {
        applyFinal(context, writer, null);
    }

    /** Applies final-v1 commands with an optional governed authorization evaluator. */
    void applyFinal(
            AppBlockExecutionContext context,
            AppStateWriter writer,
            FinalAuthorizationEvaluator authorizer
    ) {
        AppBlock block = context.block();
        initializeOrVerify(block.height(), writer);
        for (AppMessage message : context.messages()) {
            if (!AuthenticatedMapContract.DEFAULT_TOPIC.equals(message.getTopic())) {
                continue;
            }
            AuthenticatedMapCommandV1 command;
            Command mutations;
            try {
                command = AuthenticatedMapAuthorizationContract.decodeCommand(message.getBody());
                mutations = legacyCommand(command);
                validateCommandBounds(mutations);
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            byte[] receiptKey = AuthenticatedMapContract.receiptKey(message.getMessageId());
            if (writer.get(receiptKey).isPresent()) {
                continue;
            }
            byte[] actionCommitment =
                    AuthenticatedMapAuthorizationContract.actionCommitment(command.action());
            try {
                validateAuthorizationAssignments(command);
            } catch (IllegalArgumentException mismatched) {
                Receipt receipt = Receipt.rejected(message.getMessageId(), block.height(),
                        actionCommitment,
                        AuthenticatedMapContract.ERROR_AUTHORIZATION_ASSIGNMENT);
                writer.put(receiptKey, AuthenticatedMapContract.encodeReceipt(receipt));
                continue;
            }
            boolean governed = command.action().authorizations().stream().anyMatch(assignment ->
                    assignment.authorizationKind()
                            == AuthenticatedMapContract.AUTH_GOVERNED_ROLE
                            || assignment.authorizationKind()
                            == AuthenticatedMapContract.AUTH_APPROVAL);
            FinalAuthorization authorization = FinalAuthorization.basic();
            if (governed) {
                authorization = authorizer == null
                        ? FinalAuthorization.rejected(
                        AuthenticatedMapContract.ERROR_GOVERNED_ROUTE_UNSUPPORTED)
                        : Objects.requireNonNull(
                        authorizer.authorize(message, command, writer),
                        "authenticated-map final authorizer returned null");
                if (!authorization.accepted()) {
                    Receipt receipt = Receipt.rejected(message.getMessageId(), block.height(),
                            actionCommitment, authorization.errorCode());
                    writer.put(receiptKey, AuthenticatedMapContract.encodeReceipt(receipt));
                    continue;
                }
            }
            applyCommand(block.height(), message, mutations, receiptKey, writer,
                    actionCommitment, authorization.governedMutationIndexes(),
                    authorization.directConsumptions(),
                    authorization.approvalConsumptions());
        }
    }

    @Override
    public byte[] query(String path, byte[] params, AppQueryContext state) {
        try {
            return switch (path) {
                case AuthenticatedMapContract.POINT_QUERY_PATH -> queryPoint(params, state);
                case AuthenticatedMapContract.RECEIPT_QUERY_PATH -> queryReceipt(params, state);
                default -> throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                        "unsupported authenticated-map query path");
            };
        } catch (AppQueryException expected) {
            throw expected;
        } catch (IllegalArgumentException malformed) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "invalid authenticated-map query parameters");
        }
    }

    private byte[] queryPoint(byte[] params, AppQueryContext state) {
        PointQuery query = AuthenticatedMapContract.decodePointQuery(params);
        CollectionDescriptor descriptor = collections.get(query.collectionId());
        if (descriptor == null || query.applicationKey().length > descriptor.maxKeyBytes()) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "unknown collection or key exceeds collection bounds");
        }
        if (query.historical() && query.height() != state.committedHeight()) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "historical query must execute against its requested finalized height");
        }
        Entry entry = state.get(AuthenticatedMapContract.canonicalKey(
                        query.collectionId(), query.applicationKey()))
                .map(AuthenticatedMapContract::decodeEntry)
                .orElse(null);
        int presence = entry == null
                ? AuthenticatedMapContract.PRESENCE_ABSENT
                : entry.status() == AuthenticatedMapContract.STATUS_ACTIVE
                ? AuthenticatedMapContract.PRESENCE_ACTIVE
                : AuthenticatedMapContract.PRESENCE_REVOKED;
        return AuthenticatedMapContract.encodePointResult(new PointResult(
                state.committedHeight(), state.stateRoot(), query.collectionId(),
                query.applicationKey(), presence, entry));
    }

    private byte[] queryReceipt(byte[] params, AppQueryContext state) {
        ReceiptQuery query = AuthenticatedMapContract.decodeReceiptQuery(params);
        Receipt receipt = state.get(AuthenticatedMapContract.receiptKey(query.messageId()))
                .map(AuthenticatedMapContract::decodeReceipt)
                .orElse(null);
        int presence = receipt == null
                ? AuthenticatedMapContract.RECEIPT_ABSENT
                : AuthenticatedMapContract.RECEIPT_PRESENT;
        return AuthenticatedMapContract.encodeReceiptResult(new ReceiptResult(
                state.committedHeight(), state.stateRoot(), query.messageId(), presence, receipt));
    }

    private void initializeOrVerify(long height, AppStateWriter writer) {
        byte[] markerKey = AuthenticatedMapContract.genesisMarkerKey();
        Optional<byte[]> marker = writer.get(markerKey);
        if (height == 1) {
            if (marker.isPresent()) {
                throw new IllegalStateException(
                        "authenticated-map genesis marker exists before initialization");
            }
            writer.put(markerKey, genesisId);
            for (AuthenticatedMapContract.GenesisEntry initial : genesis.initialEntries()) {
                byte[] key = AuthenticatedMapContract.canonicalKey(
                        initial.collectionId(), initial.applicationKey());
                if (writer.get(key).isPresent()) {
                    throw new IllegalStateException(
                            "authenticated-map initial entry already exists before height 1");
                }
                writer.put(key, AuthenticatedMapContract.encodeEntry(Entry.active(
                        1, initial.controller(), initial.value(), 0, 0)));
            }
        } else if (height < 1 || marker.isEmpty()
                || !Arrays.equals(marker.orElseThrow(), genesisId)) {
            throw new IllegalStateException(
                    "authenticated-map genesis marker is absent or mismatched at height " + height);
        }
    }

    private void validateCommandBounds(Command command) {
        if (command.mutations().size() > genesis.maxBatchItems()) {
            throw new IllegalArgumentException("command exceeds genesis item limit");
        }
        for (Mutation mutation : command.mutations()) {
            CollectionDescriptor descriptor = collections.get(mutation.collectionId());
            if (descriptor == null) {
                throw new IllegalArgumentException("unknown collection");
            }
            if (mutation.applicationKey().length > descriptor.maxKeyBytes()
                    || mutation.value().length > descriptor.maxValueBytes()) {
                throw new IllegalArgumentException("mutation exceeds collection bounds");
            }
        }
    }

    private void validateCommandValues(Command command) {
        for (Mutation mutation : command.mutations()) {
            if (!valueBearing(mutation.operation())) {
                continue;
            }
            CollectionDescriptor descriptor = collections.get(mutation.collectionId());
            if (!AuthenticatedMapContract.valueEncodingAccepts(
                    descriptor.valueEncoding(), mutation.value(), descriptor.maxValueBytes())) {
                throw new IllegalArgumentException("mutation violates collection value encoding");
            }
            AuthenticatedMapSchema.Schema schema = schemas.get(descriptor.validatorId());
            if (schema != null && !schema.accepts(mutation.value())) {
                throw new IllegalArgumentException("mutation violates collection value schema");
            }
            AuthenticatedMapValueValidator plugin = pluginValidators.get(
                    descriptor.validatorId());
            if (plugin != null && invokePlugin(plugin, mutation.collectionId(),
                    mutation.applicationKey(), mutation.value()) == ValidatorVerdict.REJECT) {
                throw new IllegalArgumentException(
                        "mutation violates collection value validator");
            }
        }
    }

    private boolean containsGovernedMutation(Command command) {
        return command.mutations().stream()
                .map(mutation -> collections.get(mutation.collectionId()))
                .anyMatch(descriptor -> descriptor.authorization()
                        == AuthenticatedMapContract.AUTH_GOVERNED_ROLE
                        || descriptor.authorization() == AuthenticatedMapContract.AUTH_APPROVAL);
    }

    private void validateAuthorizationAssignments(AuthenticatedMapCommandV1 command) {
        for (int index = 0; index < command.action().mutations().size(); index++) {
            Mutation mutation = command.action().mutations().get(index);
            CollectionDescriptor descriptor = collections.get(mutation.collectionId());
            AuthorizationAssignmentV1 assignment =
                    command.action().authorizations().get(index);
            if (assignment.authorizationKind() != descriptor.authorization()
                    || !assignment.policyId().equals(
                    descriptor.authorizationPolicyId())) {
                throw new IllegalArgumentException(
                        "authorization assignment differs from collection genesis");
            }
        }
    }

    private static Command legacyCommand(AuthenticatedMapCommandV1 command) {
        return new Command(command.action().batch(), command.action().mutations());
    }

    private void applyCommand(long height, AppMessage message, Command command,
                              byte[] receiptKey, AppStateWriter writer) {
        applyCommand(height, message, command, receiptKey, writer,
                AuthenticatedMapContract.batchCommitment(command));
    }

    private void applyCommand(long height, AppMessage message, Command command,
                              byte[] receiptKey, AppStateWriter writer,
                              byte[] batchCommitment) {
        applyCommand(height, message, command, receiptKey, writer, batchCommitment,
                Set.of(), List.of(), List.of());
    }

    private void applyCommand(
            long height,
            AppMessage message,
            Command command,
            byte[] receiptKey,
            AppStateWriter writer,
            byte[] batchCommitment,
            Set<Integer> governedMutationIndexes,
            List<DirectConsumptionV1> directConsumptions,
            List<ApprovalConsumptionV1> approvalConsumptions
    ) {
        Set<ByteKey> consumptionKeys = new HashSet<>();
        for (DirectConsumptionV1 consumption : directConsumptions) {
            byte[] key = AuthenticatedMapContract.directConsumptionKey(
                    consumption.actorId(), consumption.authorizationId());
            if (!consumptionKeys.add(new ByteKey(key)) || writer.get(key).isPresent()) {
                Receipt receipt = Receipt.rejected(message.getMessageId(), height,
                        batchCommitment,
                        AuthenticatedMapContract.ERROR_DIRECT_AUTHORIZATION_REPLAY);
                writer.put(receiptKey, AuthenticatedMapContract.encodeReceipt(receipt));
                return;
            }
        }
        Set<ByteKey> approvalConsumptionKeys = new HashSet<>();
        for (ApprovalConsumptionV1 consumption : approvalConsumptions) {
            byte[] key = AuthenticatedMapContract.approvalConsumptionKey(
                    consumption.proposalId());
            if (!approvalConsumptionKeys.add(new ByteKey(key))
                    || writer.get(key).isPresent()) {
                Receipt receipt = Receipt.rejected(message.getMessageId(), height,
                        batchCommitment, AuthenticatedMapContract.ERROR_APPROVAL_REPLAY);
                writer.put(receiptKey, AuthenticatedMapContract.encodeReceipt(receipt));
                return;
            }
        }
        List<PendingMutation> pending = new ArrayList<>(command.mutations().size());
        try {
            for (int index = 0; index < command.mutations().size(); index++) {
                Mutation mutation = command.mutations().get(index);
                byte[] key = AuthenticatedMapContract.canonicalKey(
                        mutation.collectionId(), mutation.applicationKey());
                Entry current = writer.get(key)
                        .map(AuthenticatedMapContract::decodeEntry)
                        .orElse(null);
                Entry next = transition(height, message.getSender(), mutation,
                        collections.get(mutation.collectionId()), current,
                        governedMutationIndexes.contains(index));
                pending.add(new PendingMutation(key, mutation, next));
            }
        } catch (TransitionFailure rejected) {
            Receipt receipt = Receipt.rejected(message.getMessageId(), height,
                    batchCommitment, rejected.errorCode());
            writer.put(receiptKey, AuthenticatedMapContract.encodeReceipt(receipt));
            return;
        }

        for (DirectConsumptionV1 consumption : directConsumptions) {
            writer.put(AuthenticatedMapContract.directConsumptionKey(
                            consumption.actorId(), consumption.authorizationId()),
                    consumption.encode());
        }
        for (ApprovalConsumptionV1 consumption : approvalConsumptions) {
            writer.put(AuthenticatedMapContract.approvalConsumptionKey(
                    consumption.proposalId()), consumption.encode());
        }
        List<MutationResult> results = new ArrayList<>(pending.size());
        for (PendingMutation mutation : pending) {
            writer.put(mutation.canonicalKey(),
                    AuthenticatedMapContract.encodeEntry(mutation.entry()));
            results.add(new MutationResult(
                    mutation.mutation().collectionId(),
                    mutation.mutation().applicationKey(),
                    mutation.entry().status(),
                    mutation.entry().revision(),
                    mutation.entry().logicalValueHash()));
        }
        Receipt receipt = Receipt.applied(message.getMessageId(), height,
                batchCommitment, results);
        writer.put(receiptKey, AuthenticatedMapContract.encodeReceipt(receipt));
    }

    private Entry transition(
            long height,
            byte[] sender,
            Mutation mutation,
            CollectionDescriptor descriptor,
            Entry current,
            boolean governedAuthorized
    ) {
        if (sender == null || sender.length != 32) {
            throw failure(AuthenticatedMapContract.ERROR_UNAUTHORIZED);
        }
        if (current == null) {
            return create(height, sender, mutation, descriptor, governedAuthorized);
        }
        authorize(height, sender, descriptor, current, governedAuthorized);
        if (current.status() == AuthenticatedMapContract.STATUS_REVOKED) {
            if (mutation.operation() != AuthenticatedMapContract.OP_RESTORE) {
                throw failure(AuthenticatedMapContract.ERROR_REVOKED);
            }
            if (!descriptor.restoreAllowed()) {
                throw failure(AuthenticatedMapContract.ERROR_RESTORE_FORBIDDEN);
            }
            requireValueValidation(descriptor, mutation.applicationKey(), mutation.value());
            return Entry.active(Math.addExact(current.revision(), 1),
                    current.controller(), mutation.value(), current.createdHeight(), height);
        }

        return switch (mutation.operation()) {
            case AuthenticatedMapContract.OP_PUT -> {
                requireValueValidation(descriptor, mutation.applicationKey(), mutation.value());
                yield updated(height, current, mutation.value());
            }
            case AuthenticatedMapContract.OP_PUT_IF_ABSENT ->
                    throw failure(AuthenticatedMapContract.ERROR_ALREADY_EXISTS);
            case AuthenticatedMapContract.OP_COMPARE_AND_SET -> {
                requirePreconditions(current, mutation);
                requireValueValidation(descriptor, mutation.applicationKey(), mutation.value());
                yield updated(height, current, mutation.value());
            }
            case AuthenticatedMapContract.OP_TRANSFER_CONTROLLER -> {
                if (descriptor.authorization() != AuthenticatedMapContract.AUTH_OWNER) {
                    throw failure(AuthenticatedMapContract.ERROR_UNAUTHORIZED);
                }
                requirePreconditions(current, mutation);
                yield Entry.active(Math.addExact(current.revision(), 1),
                        mutation.newController(), current.value(), current.createdHeight(), height);
            }
            case AuthenticatedMapContract.OP_REVOKE -> {
                requirePreconditions(current, mutation);
                yield current.revoked(height);
            }
            case AuthenticatedMapContract.OP_RESTORE ->
                    throw failure(AuthenticatedMapContract.ERROR_ACTIVE);
            default -> throw new IllegalStateException("unsupported authenticated-map operation");
        };
    }

    private Entry create(
            long height,
            byte[] sender,
            Mutation mutation,
            CollectionDescriptor descriptor,
            boolean governedAuthorized
    ) {
        if (mutation.operation() != AuthenticatedMapContract.OP_PUT
                && mutation.operation() != AuthenticatedMapContract.OP_PUT_IF_ABSENT) {
            throw failure(AuthenticatedMapContract.ERROR_ABSENT);
        }
        if (descriptor.authorization() == AuthenticatedMapContract.AUTH_MEMBER
                && !isMember(sender, height)) {
            throw failure(AuthenticatedMapContract.ERROR_UNAUTHORIZED);
        }
        if ((descriptor.authorization() == AuthenticatedMapContract.AUTH_GOVERNED_ROLE
                || descriptor.authorization() == AuthenticatedMapContract.AUTH_APPROVAL)
                && !governedAuthorized) {
            throw failure(AuthenticatedMapContract.ERROR_UNAUTHORIZED);
        }
        requireValueValidation(descriptor, mutation.applicationKey(), mutation.value());
        byte[] controller = descriptor.authorization() == AuthenticatedMapContract.AUTH_OWNER
                ? sender : new byte[0];
        return Entry.active(1, controller, mutation.value(), height, height);
    }

    private void requireValueValidation(
            CollectionDescriptor descriptor,
            byte[] applicationKey,
            byte[] value
    ) {
        if (!AuthenticatedMapContract.valueEncodingAccepts(
                descriptor.valueEncoding(), value, descriptor.maxValueBytes())) {
            throw failure(AuthenticatedMapContract.ERROR_VALUE_ENCODING);
        }
        AuthenticatedMapSchema.Schema schema = schemas.get(descriptor.validatorId());
        if (schema != null && !schema.accepts(value)) {
            throw failure(AuthenticatedMapContract.ERROR_VALUE_SCHEMA);
        }
        AuthenticatedMapValueValidator plugin = pluginValidators.get(descriptor.validatorId());
        if (plugin != null && invokePlugin(plugin, descriptor.id(), applicationKey, value)
                == ValidatorVerdict.REJECT) {
            throw failure(AuthenticatedMapContract.ERROR_VALUE_VALIDATOR);
        }
    }

    private void validateInitialPluginValues() {
        for (AuthenticatedMapContract.GenesisEntry initial : genesis.initialEntries()) {
            CollectionDescriptor descriptor = collections.get(initial.collectionId());
            AuthenticatedMapValueValidator plugin = pluginValidators.get(
                    descriptor.validatorId());
            if (plugin != null && invokePlugin(plugin, initial.collectionId(),
                    initial.applicationKey(), initial.value()) == ValidatorVerdict.REJECT) {
                throw new IllegalArgumentException(
                        "initial entry violates collection value validator");
            }
        }
    }

    private static ValidatorVerdict invokePlugin(
            AuthenticatedMapValueValidator validator,
            String collectionId,
            byte[] applicationKey,
            byte[] value
    ) {
        try {
            ValidatorVerdict verdict = validator.validate(
                    collectionId, applicationKey.clone(), value.clone());
            if (verdict == null) {
                throw new IllegalStateException("validator returned a null verdict");
            }
            return verdict;
        } catch (RuntimeException failure) {
            throw new ValidatorExecutionFailure(failure);
        }
    }

    private static boolean valueBearing(int operation) {
        return operation == AuthenticatedMapContract.OP_PUT
                || operation == AuthenticatedMapContract.OP_PUT_IF_ABSENT
                || operation == AuthenticatedMapContract.OP_COMPARE_AND_SET
                || operation == AuthenticatedMapContract.OP_RESTORE;
    }

    private void authorize(
            long height,
            byte[] sender,
            CollectionDescriptor descriptor,
            Entry current,
            boolean governedAuthorized
    ) {
        switch (descriptor.authorization()) {
            case AuthenticatedMapContract.AUTH_OPEN -> {
                return;
            }
            case AuthenticatedMapContract.AUTH_OWNER -> {
                if (!Arrays.equals(sender, current.controller())) {
                    throw failure(AuthenticatedMapContract.ERROR_UNAUTHORIZED);
                }
            }
            case AuthenticatedMapContract.AUTH_MEMBER -> {
                if (!isMember(sender, height)) {
                    throw failure(AuthenticatedMapContract.ERROR_UNAUTHORIZED);
                }
            }
            case AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                    AuthenticatedMapContract.AUTH_APPROVAL -> {
                if (!governedAuthorized) {
                    throw failure(AuthenticatedMapContract.ERROR_UNAUTHORIZED);
                }
            }
            default -> throw failure(AuthenticatedMapContract.ERROR_UNAUTHORIZED);
        }
    }

    @FunctionalInterface
    interface FinalAuthorizationEvaluator {
        FinalAuthorization authorize(
                AppMessage message,
                AuthenticatedMapCommandV1 command,
                AppStateWriter mapState
        );
    }

    record FinalAuthorization(
            int errorCode,
            Set<Integer> governedMutationIndexes,
            List<DirectConsumptionV1> directConsumptions,
            List<ApprovalConsumptionV1> approvalConsumptions
    ) {
        FinalAuthorization {
            governedMutationIndexes = Set.copyOf(governedMutationIndexes);
            directConsumptions = List.copyOf(directConsumptions);
            approvalConsumptions = List.copyOf(approvalConsumptions);
        }

        static FinalAuthorization basic() {
            return new FinalAuthorization(AuthenticatedMapContract.ERROR_NONE,
                    Set.of(), List.of(), List.of());
        }

        static FinalAuthorization rejected(int errorCode) {
            return new FinalAuthorization(errorCode, Set.of(), List.of(), List.of());
        }

        boolean accepted() {
            return errorCode == AuthenticatedMapContract.ERROR_NONE;
        }
    }

    private boolean isMember(byte[] sender, long height) {
        if (membershipView == null || sender == null || sender.length != 32) {
            return false;
        }
        AppChainMembershipEpoch epoch = membershipView.epochAt(height);
        String senderHex = HexFormat.of().formatHex(sender);
        return epoch.members().contains(senderHex);
    }

    private static void requirePreconditions(Entry current, Mutation mutation) {
        if (mutation.expectedRevision() != 0
                && mutation.expectedRevision() != current.revision()
                || mutation.expectedValueHash().length != 0
                && !Arrays.equals(mutation.expectedValueHash(), current.logicalValueHash())) {
            throw failure(AuthenticatedMapContract.ERROR_PRECONDITION);
        }
    }

    private static Entry updated(long height, Entry current, byte[] value) {
        return Entry.active(Math.addExact(current.revision(), 1), current.controller(),
                value, current.createdHeight(), height);
    }

    private static TransitionFailure failure(int errorCode) {
        return new TransitionFailure(errorCode);
    }

    private record PendingMutation(byte[] canonicalKey, Mutation mutation, Entry entry) {
        private PendingMutation {
            canonicalKey = canonicalKey.clone();
        }

        @Override public byte[] canonicalKey() { return canonicalKey.clone(); }
    }

    private record ByteKey(byte[] value) {
        private ByteKey {
            value = value.clone();
        }

        @Override public byte[] value() { return value.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof ByteKey key && Arrays.equals(value, key.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }

    private static final class TransitionFailure extends RuntimeException {
        private final int errorCode;

        private TransitionFailure(int errorCode) {
            super(null, null, false, false);
            this.errorCode = errorCode;
        }

        private int errorCode() {
            return errorCode;
        }
    }

    private static final class ValidatorExecutionFailure extends IllegalStateException {
        private ValidatorExecutionFailure(RuntimeException cause) {
            super("authenticated-map validator execution failed", cause);
        }
    }
}
