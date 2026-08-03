package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Advisory, offline authenticated-map value validation against one canonical genesis.
 *
 * <p>This preflight is deliberately not an authorization, admission, finality, or consensus
 * boundary. Authoritative validation still occurs while the state machine applies the command.</p>
 */
public final class AuthenticatedMapPreflight {
    private final AuthenticatedMapContract.Genesis genesis;
    private final Map<String, AuthenticatedMapContract.CollectionDescriptor> collections;
    private final Map<String, AuthenticatedMapContract.ValidatorDescriptor> validators;
    private final Map<String, AuthenticatedMapSchema.Schema> schemas;
    private final PluginResolver pluginResolver;

    private AuthenticatedMapPreflight(
            AuthenticatedMapContract.Genesis genesis,
            PluginResolver pluginResolver
    ) {
        this.genesis = Objects.requireNonNull(genesis, "genesis");
        this.pluginResolver = Objects.requireNonNull(pluginResolver, "pluginResolver");
        Map<String, AuthenticatedMapContract.CollectionDescriptor> collectionIndex =
                new LinkedHashMap<>();
        genesis.collections().forEach(item -> collectionIndex.put(item.id(), item));
        collections = Map.copyOf(collectionIndex);
        Map<String, AuthenticatedMapContract.ValidatorDescriptor> validatorIndex =
                new LinkedHashMap<>();
        Map<String, AuthenticatedMapSchema.Schema> schemaIndex = new LinkedHashMap<>();
        for (AuthenticatedMapContract.ValidatorDescriptor validator : genesis.validators()) {
            validatorIndex.put(validator.id(), validator);
            if (validator.kind() == AuthenticatedMapContract.VALIDATOR_KIND_SCHEMA) {
                schemaIndex.put(validator.id(), AuthenticatedMapSchema.decode(
                        validator.definition()));
            }
        }
        validators = Map.copyOf(validatorIndex);
        schemas = Map.copyOf(schemaIndex);
    }

    public static AuthenticatedMapPreflight fromGenesis(
            AuthenticatedMapContract.Genesis genesis
    ) {
        return fromGenesis(genesis, descriptor -> Optional.empty());
    }

    public static AuthenticatedMapPreflight fromGenesis(
            AuthenticatedMapContract.Genesis genesis,
            PluginResolver pluginResolver
    ) {
        return new AuthenticatedMapPreflight(genesis, pluginResolver);
    }

    public static AuthenticatedMapPreflight fromEncodedGenesis(byte[] canonicalGenesis) {
        return fromGenesis(AuthenticatedMapContract.decodeGenesis(canonicalGenesis));
    }

    public static AuthenticatedMapPreflight fromEncodedGenesis(
            byte[] canonicalGenesis,
            PluginResolver pluginResolver
    ) {
        return fromGenesis(
                AuthenticatedMapContract.decodeGenesis(canonicalGenesis), pluginResolver);
    }

    public AuthenticatedMapContract.Genesis genesis() {
        return genesis;
    }

    /** Validate one candidate value without submitting it. */
    public Result validate(String collectionId, byte[] applicationKey, byte[] value) {
        AuthenticatedMapContract.CollectionDescriptor collection = collections.get(collectionId);
        if (collection == null) {
            return rejected(AuthenticatedMapContract.ERROR_UNKNOWN_COLLECTION);
        }
        byte[] key = applicationKey == null ? null : applicationKey.clone();
        byte[] candidate = value == null ? null : value.clone();
        if (key == null || key.length == 0 || key.length > collection.maxKeyBytes()
                || candidate == null || candidate.length > collection.maxValueBytes()) {
            return rejected(AuthenticatedMapContract.ERROR_COLLECTION_BOUNDS);
        }
        if (!AuthenticatedMapContract.valueEncodingAccepts(
                collection.valueEncoding(), candidate, collection.maxValueBytes())) {
            return rejected(AuthenticatedMapContract.ERROR_VALUE_ENCODING);
        }
        if (collection.validatorId().isEmpty()) {
            return accepted();
        }
        AuthenticatedMapContract.ValidatorDescriptor validator = validators.get(
                collection.validatorId());
        if (validator.kind() == AuthenticatedMapContract.VALIDATOR_KIND_SCHEMA) {
            return schemas.get(validator.id()).accepts(candidate)
                    ? accepted() : rejected(AuthenticatedMapContract.ERROR_VALUE_SCHEMA);
        }
        Optional<PluginValidator> plugin = Objects.requireNonNull(
                pluginResolver.resolve(validator), "plugin resolver result");
        if (plugin.isEmpty()) {
            return unavailable(AuthenticatedMapContract.ERROR_VALUE_VALIDATOR);
        }
        boolean valid = plugin.orElseThrow().accepts(
                collection.id(), key.clone(), candidate.clone());
        return valid ? accepted()
                : rejected(AuthenticatedMapContract.ERROR_VALUE_VALIDATOR);
    }

    /** Validate the value-bearing portion of one mutation. */
    public Result validate(AuthenticatedMapContract.Mutation mutation) {
        Objects.requireNonNull(mutation, "mutation");
        if (isValueBearing(mutation.operation())) {
            return validate(mutation.collectionId(), mutation.applicationKey(), mutation.value());
        }
        AuthenticatedMapContract.CollectionDescriptor collection = collections.get(
                mutation.collectionId());
        if (collection == null) {
            return rejected(AuthenticatedMapContract.ERROR_UNKNOWN_COLLECTION);
        }
        return mutation.applicationKey().length <= collection.maxKeyBytes()
                ? accepted() : rejected(AuthenticatedMapContract.ERROR_COLLECTION_BOUNDS);
    }

    /** Return one advisory result per mutation, preserving command order. */
    public List<Result> validate(AuthenticatedMapContract.Command command) {
        Objects.requireNonNull(command, "command");
        List<Result> results = new ArrayList<>(command.mutations().size());
        command.mutations().forEach(mutation -> results.add(validate(mutation)));
        return List.copyOf(results);
    }

    /** Fail locally unless every mutation can be preflighted and accepted. */
    public void requireAccepted(AuthenticatedMapContract.Command command) {
        List<Result> results = validate(command);
        for (int index = 0; index < results.size(); index++) {
            Result result = results.get(index);
            if (!result.accepted()) {
                throw new PreflightException(index, result);
            }
        }
    }

    public static Explanation explain(int code) {
        return switch (code) {
            case AuthenticatedMapContract.ERROR_NONE -> explanation(code, "NONE",
                    "The mutation was applied without an authenticated-map error.");
            case AuthenticatedMapContract.ERROR_UNKNOWN_COLLECTION -> explanation(code,
                    "UNKNOWN_COLLECTION", "The collection is not declared by chain genesis.");
            case AuthenticatedMapContract.ERROR_COLLECTION_BOUNDS -> explanation(code,
                    "COLLECTION_BOUNDS", "The key or value exceeds the collection bounds.");
            case AuthenticatedMapContract.ERROR_UNAUTHORIZED -> explanation(code,
                    "UNAUTHORIZED", "The sender does not satisfy the collection policy.");
            case AuthenticatedMapContract.ERROR_ALREADY_EXISTS -> explanation(code,
                    "ALREADY_EXISTS", "The operation required an absent entry.");
            case AuthenticatedMapContract.ERROR_ABSENT -> explanation(code,
                    "ABSENT", "The operation required an existing entry.");
            case AuthenticatedMapContract.ERROR_REVOKED -> explanation(code,
                    "REVOKED", "The operation required an active entry.");
            case AuthenticatedMapContract.ERROR_ACTIVE -> explanation(code,
                    "ACTIVE", "The operation required a revoked entry.");
            case AuthenticatedMapContract.ERROR_PRECONDITION -> explanation(code,
                    "PRECONDITION", "The revision or value-hash precondition failed.");
            case AuthenticatedMapContract.ERROR_RESTORE_FORBIDDEN -> explanation(code,
                    "RESTORE_FORBIDDEN", "Genesis forbids restoration for this collection.");
            case AuthenticatedMapContract.ERROR_VALUE_ENCODING -> explanation(code,
                    "VALUE_ENCODING", "The value violates the collection encoding constraint.");
            case AuthenticatedMapContract.ERROR_VALUE_SCHEMA -> explanation(code,
                    "VALUE_SCHEMA", "The canonical CBOR value does not satisfy its schema.");
            case AuthenticatedMapContract.ERROR_VALUE_VALIDATOR -> explanation(code,
                    "VALUE_VALIDATOR", "The genesis-pinned custom validator rejected the value.");
            default -> throw new IllegalArgumentException(
                    "authenticated-map error code must be in [0, 12]");
        };
    }

    private static Explanation explanation(int code, String name, String meaning) {
        return new Explanation(code, name, mechanism(code), meaning);
    }

    private static String mechanism(int code) {
        return switch (code) {
            case 0 -> "none";
            case 1, 2 -> "collection";
            case 3 -> "authorization";
            case 4, 5, 6, 7, 8, 9 -> "state-transition";
            case 10 -> "value-encoding";
            case 11 -> "schema";
            case 12 -> "plugin-validator";
            default -> throw new IllegalArgumentException("unsupported authenticated-map code");
        };
    }

    private static Result accepted() {
        return result(Status.ACCEPTED, AuthenticatedMapContract.ERROR_NONE);
    }

    private static Result rejected(int code) {
        return result(Status.REJECTED, code);
    }

    private static Result unavailable(int code) {
        return result(Status.UNAVAILABLE, code);
    }

    private static Result result(Status status, int code) {
        Explanation explanation = explain(code);
        String detail = status == Status.UNAVAILABLE
                ? "The required plugin validator is not available to this preflight client."
                : explanation.meaning();
        return new Result(status, code, explanation.name(), explanation.mechanism(), detail);
    }

    private static boolean isValueBearing(int operation) {
        return operation == AuthenticatedMapContract.OP_PUT
                || operation == AuthenticatedMapContract.OP_PUT_IF_ABSENT
                || operation == AuthenticatedMapContract.OP_COMPARE_AND_SET
                || operation == AuthenticatedMapContract.OP_RESTORE;
    }

    public enum Status {
        ACCEPTED,
        REJECTED,
        UNAVAILABLE
    }

    public record Result(
            Status status,
            int code,
            String codeName,
            String mechanism,
            String detail
    ) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(codeName, "codeName");
            Objects.requireNonNull(mechanism, "mechanism");
            Objects.requireNonNull(detail, "detail");
        }

        public boolean accepted() {
            return status == Status.ACCEPTED;
        }

        public boolean authoritative() {
            return false;
        }
    }

    public record Explanation(int code, String name, String mechanism, String meaning) {
        public Explanation {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(mechanism, "mechanism");
            Objects.requireNonNull(meaning, "meaning");
        }
    }

    @FunctionalInterface
    public interface PluginResolver {
        Optional<PluginValidator> resolve(
                AuthenticatedMapContract.ValidatorDescriptor descriptor);
    }

    @FunctionalInterface
    public interface PluginValidator {
        boolean accepts(String collectionId, byte[] applicationKey, byte[] value);
    }

    /** Local failure raised before any HTTP submission. */
    public static final class PreflightException extends RuntimeException {
        private final int mutationIndex;
        private final Result result;

        public PreflightException(int mutationIndex, Result result) {
            super("authenticated-map advisory preflight " + result.status().name().toLowerCase()
                    + " mutation " + mutationIndex + " with " + result.codeName()
                    + " (authoritative validation still occurs on apply)");
            if (mutationIndex < 0) {
                throw new IllegalArgumentException("mutationIndex must be nonnegative");
            }
            this.mutationIndex = mutationIndex;
            this.result = Objects.requireNonNull(result, "result");
        }

        public int mutationIndex() {
            return mutationIndex;
        }

        public Result result() {
            return result;
        }
    }
}
