package org.yanoproject.api.appchain.topics;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.appchain.schema.CborSchema;
import org.yanoproject.api.appchain.topics.TopicWire.ActiveRevision;
import org.yanoproject.api.appchain.topics.TopicWire.CommandStatement;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.Create;
import org.yanoproject.api.appchain.topics.TopicWire.Delegation;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.KeySet;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.RegistryCommand;
import org.yanoproject.api.appchain.topics.TopicWire.Revoke;
import org.yanoproject.api.appchain.topics.TopicWire.Signature;
import org.yanoproject.api.appchain.topics.TopicWire.Update;
import org.yanoproject.api.util.StrictEd25519;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Admission of owner commands on {@code ~topics/registry} (ADR app-layer/039 D5, D5a, D8, I19).
 * Every check here depends only on the parent state and the block time, so ingress, the proposer and
 * followers reach the same verdict; a refusal means the command never enters a block. Outcomes that
 * depend on other commands (stale head, limits) are decided at inclusion and become receipts.
 */
public final class TopicCommandRules {
    public static final String TOPIC = "~topics/registry";
    public static final String GOVERNANCE_TOPIC = "~governance/topics";

    private TopicCommandRules() {
    }

    /** A command that may enter a block, with the authority epoch to record (D5a); or a refusal. */
    public record Admission(RegistryCommand command, long authorityEpoch, TopicRefusal refusal, String detail) {
        static Admission refused(TopicRefusal refusal, String detail) {
            return new Admission(null, 0, refusal, detail);
        }

        public boolean admitted() {
            return refusal == null;
        }
    }

    /**
     * @param state                 the parent state, or the block's candidate state during apply
     * @param schemasStoredInBlock  schema hashes stored by earlier commands of the same block; they do not
     *                              count as known, so every caller reaches the parent-state verdict
     * @param earlierStatements     statement hashes of commands and protected writes earlier in the same block
     */
    public static Admission admit(byte[] body, String chainId, String generationId, long blockTime,
                                  TopicState state, Set<String> schemasStoredInBlock, Set<String> earlierStatements) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(schemasStoredInBlock, "schemasStoredInBlock");
        Objects.requireNonNull(earlierStatements, "earlierStatements");
        Optional<Config> maybeConfig = state.config();
        if (maybeConfig.isEmpty()) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, "topics are not enabled on this chain");
        }
        Config config = maybeConfig.get();
        RegistryCommand command;
        try {
            command = TopicWireCodec.decodeRegistryCommand(body);
        } catch (IllegalArgumentException malformed) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, malformed.getMessage());
        }
        CommandStatement statement = command.statement();
        if (!chainId.equals(statement.chainId()) || !generationId.equals(statement.generationId())) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, "command is for another chain generation");
        }
        if (statement.notAfter() < blockTime
                || statement.notAfter() - blockTime > config.settings().maxCommandMs()) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_EXPIRED, "notAfter outside the inclusion window");
        }
        String target = statement.target();
        if (!TopicNames.isValidEntryName(target)) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, "invalid target name");
        }
        // A command statement is single-use until its notAfter, so an included command cannot be relayed
        // again: not after a cancellation frees its name, nor onto a later creation of that name (D9)
        String statementHash = statementHash(statement);
        if (earlierStatements.contains(statementHash) || state.used(statementHash)) {
            return Admission.refused(TopicRefusal.TOPIC_PROOF_REPLAYED, "command statement already used");
        }
        return switch (statement.operation()) {
            case Create create -> admitCreate(command, create, config, state, schemasStoredInBlock);
            case Revoke revoke -> admitRevoke(command, revoke.delegation(), config, blockTime, state);
            default -> admitChange(command, state, schemasStoredInBlock);
        };
    }

    private static Admission admitCreate(RegistryCommand command, Create create, Config config, TopicState state,
                                         Set<String> schemasStoredInBlock) {
        CommandStatement statement = command.statement();
        String target = statement.target();
        String authority = statement.authority();
        if (statement.expected() != null) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, "a creation names no head revision");
        }
        KeySet signers;
        long epoch = 0;
        switch (authority) {
            case TopicWire.AUTHORITY_REGISTRARS -> {
                if (!TopicNames.isTopLevel(target)) {
                    return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, "registrars create top-level names");
                }
                if (config.settings().registrars() == null) {
                    return Admission.refused(TopicRefusal.TOPIC_COMMAND_DENIED, "no registrars are configured");
                }
                signers = config.settings().registrars();
                epoch = config.registrarsSince();
            }
            case TopicWire.AUTHORITY_CLAIM -> {
                if (!TopicNames.isTopLevel(target)) {
                    return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, "open claims are single-segment");
                }
                if (!config.settings().openClaims()) {
                    return Admission.refused(TopicRefusal.TOPIC_COMMAND_DENIED, "open claims are disabled");
                }
                signers = create.owners();
            }
            default -> {
                if (TopicNames.isTopLevel(target) || authority.equals(target)
                        || !TopicNames.isNamespace(authority) || !TopicNames.covers(authority, target)) {
                    return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID,
                            "a deeper entry is created by an ancestor namespace");
                }
                Optional<ActiveRevision> active = activeRevision(state, authority);
                if (active.isEmpty() || active.get().content().retired()) {
                    return Admission.refused(TopicRefusal.TOPIC_COMMAND_DENIED,
                            "authority has no active, unretired revision");
                }
                signers = active.get().content().owners();
                epoch = active.get().ownersSince();
            }
        }
        // Signatures before the schema: a forged command never reaches schema canonicalization
        Admission signed = signedBy(command, signers, epoch);
        Admission schema = signed.admitted()
                ? checkSchema(create.policy(), create.schemaIr(), state, schemasStoredInBlock) : null;
        return schema != null ? schema : signed;
    }

    /** Update, rotate and retire: the target itself or an ancestor namespace authorizes (D3). */
    private static Admission admitChange(RegistryCommand command, TopicState state, Set<String> schemasStoredInBlock) {
        CommandStatement statement = command.statement();
        String authority = statement.authority();
        if (statement.expected() == null) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, "a change names the target's head revision");
        }
        if (!authority.equals(statement.target())
                && !(TopicNames.isNamespace(authority) && TopicNames.covers(authority, statement.target()))) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, "authority must be the target or an ancestor");
        }
        Optional<ActiveRevision> active = activeRevision(state, authority);
        if (active.isEmpty() || active.get().content().retired()) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_DENIED, "authority has no active, unretired revision");
        }
        Admission signed = signedBy(command, active.get().content().owners(), active.get().ownersSince());
        Admission schema = signed.admitted() && statement.operation() instanceof Update update
                ? checkSchema(update.policy(), update.schemaIr(), state, schemasStoredInBlock) : null;
        return schema != null ? schema : signed;
    }

    /** D8 revocation: the whole signed delegation, a bounded retention horizon, an authorized revoker. */
    private static Admission admitRevoke(RegistryCommand command, Delegation delegation, Config config,
                                         long blockTime, TopicState state) {
        CommandStatement statement = command.statement();
        if (statement.expected() != null || !delegation.scope().equals(statement.target())) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, "a revocation targets its delegation's scope");
        }
        byte[] signed = TopicWireCodec.delegationSignedBytes(statement.chainId(), statement.generationId(),
                delegation);
        if (!StrictEd25519.verify(HexUtil.decodeHexString(delegation.issuer()), delegation.sig(), signed)) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, "delegation signature does not verify");
        }
        if (delegation.notAfter() < blockTime) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_EXPIRED, "the delegation has already expired");
        }
        if (delegation.notAfter() - blockTime > config.settings().maxDelegationMs()) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, "delegation is beyond the retention horizon");
        }
        String authority = statement.authority();
        if (TopicWire.AUTHORITY_ISSUER.equals(authority)) {
            if (!TopicWriteRules.mayDelegate(state, delegation.scope(), delegation.issuer())) {
                return Admission.refused(TopicRefusal.TOPIC_COMMAND_DENIED,
                        "issuer is not an allowed delegator for the scope");
            }
            return signedBy(command, new KeySet(1, List.of(delegation.issuer())), 0);
        }
        if (!TopicNames.covers(authority, delegation.scope())) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, "authority must govern the scope");
        }
        Optional<ActiveRevision> active = activeRevision(state, authority);
        if (active.isEmpty()) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_DENIED, "authority has no active revision");
        }
        return signedBy(command, active.get().content().owners(), 0);
    }

    /** Every signature verifies, is by a key of the set, and distinct signers meet the threshold (I20). */
    private static Admission signedBy(RegistryCommand command, KeySet keys, long epoch) {
        byte[] statement = TopicWireCodec.encodeCommandStatement(command.statement());
        for (Signature signature : command.signatures()) {
            if (!keys.keys().contains(signature.key())
                    || !StrictEd25519.verify(HexUtil.decodeHexString(signature.key()), signature.sig(), statement)) {
                return Admission.refused(TopicRefusal.TOPIC_COMMAND_DENIED,
                        "signature is invalid or not by the authority");
            }
        }
        if (command.signatures().size() < keys.threshold()) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_DENIED, "signatures below the authority threshold");
        }
        return new Admission(command, epoch, null, null);
    }

    /**
     * A schema hash must be stored already, before this block, or come with matching IR bytes (D10); null when
     * the policy's schema reference is valid. Shared by owner commands and governance creation.
     */
    public static Admission checkSchema(Policy policy, byte[] schemaIr, TopicState state,
                                         Set<String> schemasStoredInBlock) {
        String schema = policy.schema();
        if (schemaIr != null) {
            return validSchemaIr(schema, schemaIr) ? null
                    : Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID,
                    "schema IR is not canonical yano-cbor-schema-ir-v1 matching the policy hash");
        }
        if (schema != null && !schema.isEmpty()
                && (!state.hasSchema(schema) || schemasStoredInBlock.contains(schema))) {
            return Admission.refused(TopicRefusal.TOPIC_COMMAND_INVALID, "unknown schema hash without IR");
        }
        return null;
    }

    /** IR carried in a command must be canonical {@code yano-cbor-schema-ir-v1} with the policy's hash (D10). */
    public static boolean validSchemaIr(String schemaHash, byte[] ir) {
        return schemaHash != null && !schemaHash.isEmpty() && ir.length > 0
                && schemaHash.equals(TopicWireCodec.blake2b256Hex(ir)) && CborSchema.isCanonicalDefinition(ir);
    }

    /** The replay-guard key of a command: the hash of its signed statement (D9). */
    public static String statementHash(CommandStatement statement) {
        return TopicWireCodec.blake2b256Hex(TopicWireCodec.encodeCommandStatement(statement));
    }

    private static Optional<ActiveRevision> activeRevision(TopicState state, String name) {
        return state.entry(name).map(EntryRecord::active);
    }
}
