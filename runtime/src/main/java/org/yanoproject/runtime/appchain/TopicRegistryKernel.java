package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.topics.TopicChainSettings;
import org.yanoproject.api.appchain.topics.TopicCommandRules;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicNames;
import org.yanoproject.api.appchain.topics.TopicPayloadMessage;
import org.yanoproject.api.appchain.topics.TopicRefusal;
import org.yanoproject.api.appchain.topics.TopicState;
import org.yanoproject.api.appchain.topics.TopicWire;
import org.yanoproject.api.appchain.topics.TopicWire.ActiveRevision;
import org.yanoproject.api.appchain.topics.TopicWire.Activation;
import org.yanoproject.api.appchain.topics.TopicWire.CommandStatement;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.Create;
import org.yanoproject.api.appchain.topics.TopicWire.EntryContent;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.ExpiryBatch;
import org.yanoproject.api.appchain.topics.TopicWire.ExpiryItem;
import org.yanoproject.api.appchain.topics.TopicWire.GovConfigure;
import org.yanoproject.api.appchain.topics.TopicWire.GovCreate;
import org.yanoproject.api.appchain.topics.TopicWire.GovRecover;
import org.yanoproject.api.appchain.topics.TopicWire.GovRetire;
import org.yanoproject.api.appchain.topics.TopicWire.GovernanceApproval;
import org.yanoproject.api.appchain.topics.TopicWire.GovernanceCommand;
import org.yanoproject.api.appchain.topics.TopicWire.QueuedRevision;
import org.yanoproject.api.appchain.topics.TopicWire.Receipt;
import org.yanoproject.api.appchain.topics.TopicWire.RegistryCommand;
import org.yanoproject.api.appchain.topics.TopicWire.Retire;
import org.yanoproject.api.appchain.topics.TopicWire.Revoke;
import org.yanoproject.api.appchain.topics.TopicWire.Rotate;
import org.yanoproject.api.appchain.topics.TopicWire.Settings;
import org.yanoproject.api.appchain.topics.TopicWire.Update;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.api.appchain.topics.TopicWriteRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The deterministic topic-registry state transition (ADR app-layer/039 D2, D5, D5a, D8, D9). It runs
 * inside {@link SystemInputKernel} in the block's atomic commit: owner and governance commands are
 * processed in block order before the state machine, and after the machine the revisions that govern
 * the next height are materialized and expired items pruned.
 */
final class TopicRegistryKernel {
    static final String CONFIG = TopicKeys.CONFIG_HISTORY_NAME;
    static final String GOVERNANCE_AUTHORITY = "~governance";
    /** Bound of governance approvals in progress (one record, D2). */
    static final int MAX_GOVERNANCE_PENDING = 64;

    private final String chainId;
    private final Supplier<String> generationId;
    private final MemberGroup group;
    private final Settings genesisSeed;
    private final long maxMessageBytes;
    private final int pruneBudget;

    /**
     * @param genesisSeed  the YAML seed when topics are enabled at genesis, else null (D1)
     * @param generationId the chain generation id ({@code StateCommitmentIdentity.digest()}), lowercase hex
     */
    TopicRegistryKernel(String chainId, Supplier<String> generationId, MemberGroup group, Settings genesisSeed,
                        long maxMessageBytes, int maxBlockMessages) {
        this.chainId = Objects.requireNonNull(chainId, "chainId");
        this.generationId = Objects.requireNonNull(generationId, "generationId");
        this.group = Objects.requireNonNull(group, "group");
        if (maxMessageBytes < 1 || maxBlockMessages < 1) {
            throw new IllegalArgumentException("invalid topic kernel bounds");
        }
        this.genesisSeed = genesisSeed;
        this.maxMessageBytes = maxMessageBytes;
        this.pruneBudget = 2 * maxBlockMessages;
    }

    /**
     * The topic registry of a chain, shared by the engine and the conformance harness: present on
     * governed-membership chains, which enable topics at genesis or later by governance (D1, OQ11); null
     * on static chains, which refuse {@code topics.mode=enabled}.
     */
    static TopicRegistryKernel forChain(String chainId, Map<String, String> settings, Supplier<String> generationId,
                                        MemberGroup group, long maxMessageBytes, int maxBlockMessages) {
        Optional<Settings> seed = TopicChainSettings.genesisSeed(settings);
        if (!GovernedMembership.selected(settings)) {
            if (seed.isPresent()) {
                throw new IllegalArgumentException(
                        "topics.mode=enabled requires membership.mode=governed (ADR app-layer/039 D1)");
            }
            return null;
        }
        return new TopicRegistryKernel(chainId, generationId, group, seed.orElse(null), maxMessageBytes,
                maxBlockMessages);
    }

    /** The D11 verdict on one message: a refusal, or the message the machine's admission receives (D7a). */
    record Verdict(TopicRefusal refusal, String detail, AppMessage machineMessage, TopicWriteRules.Result write,
                   String command) {
        boolean allowed() {
            return refusal == null;
        }
    }

    /**
     * D11 for one message against committed (parent) state, as REST and gossip ingress and the proposer judge
     * it: registry commands get command admission, other {@code ~} topics pass, ordinary topics the write check.
     *
     * @param earlierStatements statement hashes of commands and protected writes earlier in the candidate block
     *                          (D9); a kept command adds {@link Verdict#command()}, a kept framed write its
     *                          statement hash
     */
    static Verdict judge(AppMessage message, String chainId, String generationId, long blockTime, TopicState state,
                         Set<String> earlierStatements) {
        String topic = message.getTopic() != null ? message.getTopic() : "";
        if (TopicCommandRules.TOPIC.equals(topic)) {
            TopicCommandRules.Admission admission = TopicCommandRules.admit(message.getBody(), chainId, generationId,
                    blockTime, state, Set.of(), earlierStatements);
            return admission.admitted() ? new Verdict(null, null, message, null,
                    TopicCommandRules.statementHash(admission.command().statement()))
                    : new Verdict(admission.refusal(), admission.detail(), null, null, null);
        }
        if (topic.startsWith("~")) {
            return new Verdict(null, null, message, null, null);
        }
        TopicWriteRules.Result write = TopicWriteRules.check(topic, message.getBody(), chainId, generationId,
                blockTime, state, earlierStatements);
        return write.allowed() ? new Verdict(null, null, TopicPayloadMessage.of(message, write), write, null)
                : new Verdict(write.refusal(), write.detail(), null, null, null);
    }

    /**
     * Commands and writes of the block, in block order, before the state machine runs. A write that fails
     * the D11 check invalidates the block; a protected write's statement is recorded as used (D9).
     */
    AppBlockExecutionContext beforeMachine(AppBlockExecutionContext context, AppStateWriter state) {
        AppBlock block = context.block();
        Set<String> schemasStoredInBlock = new HashSet<>();
        Set<String> statementsInBlock = new HashSet<>();
        Map<String, Long> revokedInBlock = new LinkedHashMap<>();
        Map<Integer, AppMessage> payloadViews = new HashMap<>();
        boolean enabled = new TopicState(state, maxMessageBytes).config().isPresent();
        for (int index = 0; index < block.messages().size(); index++) {
            AppMessage message = block.messages().get(index);
            String topic = message.getTopic() != null ? message.getTopic() : "";
            if (TopicCommandRules.TOPIC.equals(topic)) {
                include(block, message, state, schemasStoredInBlock, statementsInBlock, revokedInBlock);
            } else if (TopicCommandRules.GOVERNANCE_TOPIC.equals(topic)) {
                govern(block, message, state, schemasStoredInBlock);
            } else if (enabled && !topic.startsWith("~")) {
                AppMessage view = write(block, message, state, statementsInBlock);
                if (view != message) {
                    payloadViews.put(index, view);
                }
            }
        }
        // D8: a revocation governs from the next block, so the block's own messages were judged without it.
        revokedInBlock.forEach((hash, notAfter) -> {
            state.put(TopicKeys.revoked(hash), new byte[] {1});
            addExpiry(block.height(), TopicKeys.CREDENTIAL_QUEUE, new ExpiryItem(ExpiryItem.REVOKED, hash), notAfter,
                    state);
        });
        return payloadViews.isEmpty() ? context : context.withPayloadViews(payloadViews);
    }

    /** D11 for one write; returns what the machine receives (the payload view on a registered topic, D7a). */
    private AppMessage write(AppBlock block, AppMessage message, AppStateWriter state, Set<String> statementsInBlock) {
        TopicWriteRules.Result result = TopicWriteRules.check(message.getTopic(), message.getBody(), chainId,
                generationId.get(), block.timestamp(), new TopicState(state, maxMessageBytes), statementsInBlock);
        if (!result.allowed()) {
            throw new TopicRefusedException("a refused topic write", result.refusal(), result.detail());
        }
        if (result.framed()) {
            statementsInBlock.add(result.statementHash());
            state.put(TopicKeys.used(result.statementHash()), new byte[] {1});
            addExpiry(block.height(), TopicKeys.CREDENTIAL_QUEUE,
                    new ExpiryItem(ExpiryItem.USED, result.statementHash()), result.statementNotAfter(), state);
        }
        return TopicPayloadMessage.of(message, result);
    }

    /** After the machine: materialize what governs the next height, then prune expired items. */
    void afterMachine(long height, long blockTime, AppStateWriter state) {
        if (height == 1 && genesisSeed != null) {
            state.put(TopicKeys.config(), TopicWireCodec.encodeConfig(new Config(1, 1, genesisSeed)));
        }
        activate(height + 1, state);
        prune(blockTime, state);
    }

    // ------------------------------------------------------------------ owner commands (D5, D5a)

    private void include(AppBlock block, AppMessage message, AppStateWriter state, Set<String> schemasStoredInBlock,
                         Set<String> statementsInBlock, Map<String, Long> revokedInBlock) {
        TopicState view = new TopicState(state, maxMessageBytes);
        TopicCommandRules.Admission admission = TopicCommandRules.admit(message.getBody(), chainId,
                generationId.get(), block.timestamp(), view, schemasStoredInBlock, statementsInBlock);
        if (!admission.admitted()) {
            // The proposer never selects it (I19); a follower rejects the proposal on this refusal.
            throw new TopicRefusedException("an inadmissible topic command", admission.refusal(),
                    admission.detail());
        }
        Config config = view.config().orElseThrow();
        RegistryCommand command = admission.command();
        CommandStatement statement = command.statement();
        String messageId = messageId(message);
        long epoch = admission.authorityEpoch();
        Receipt receipt = switch (statement.operation()) {
            case Create create -> storeSchemaIfAccepted(create(block, statement.target(), statement.authority(),
                    epoch, new EntryContent(create.owners(), create.policy(), false), config, messageId, state),
                    create.policy().schema(), create.schemaIr(), state, schemasStoredInBlock);
            case Revoke revoke -> revoke(block, statement, revoke.delegation(), state, revokedInBlock);
            case Update update -> storeSchemaIfAccepted(change(block, statement, epoch, config, messageId, state,
                    content -> new EntryContent(content.owners(), update.policy(), content.retired())),
                    update.policy().schema(), update.schemaIr(), state, schemasStoredInBlock);
            case Rotate rotate -> change(block, statement, epoch, config, messageId, state,
                    content -> new EntryContent(rotate.owners(), content.policy(), content.retired()));
            case Retire ignored -> change(block, statement, epoch, config, messageId, state,
                    content -> new EntryContent(content.owners(), content.policy(), true));
        };
        String statementHash = TopicCommandRules.statementHash(statement);
        statementsInBlock.add(statementHash);
        // Claim signers choose their own keys, so a refused claim leaves no receipt or used statement (state
        // anyone could mint); every other included command is single-use until its notAfter (D9)
        if (receipt.code().equals(Receipt.ACCEPTED) || !TopicWire.AUTHORITY_CLAIM.equals(statement.authority())) {
            writeReceipt(block, messageId, receipt, config.settings().maxCommandMs(), state);
            state.put(TopicKeys.used(statementHash), new byte[] {1});
            // in the receipt queue: its retention (max-command-ms) never holds back the shorter write proofs
            addExpiry(block.height(), TopicKeys.RECEIPT_QUEUE, new ExpiryItem(ExpiryItem.USED, statementHash),
                    statement.notAfter(), state);
        }
    }

    /** IR is stored only for a command that took effect, so stale or over-limit commands leave nothing (D10). */
    private static Receipt storeSchemaIfAccepted(Receipt receipt, String schema, byte[] ir, AppStateWriter state,
                                                 Set<String> schemasStoredInBlock) {
        if (receipt.code().equals(Receipt.ACCEPTED)) {
            storeSchema(schema, ir, state, schemasStoredInBlock);
        }
        return receipt;
    }

    private Receipt create(AppBlock block, String name, String authority, long epoch, EntryContent content,
                           Config config, String messageId, AppStateWriter state) {
        TopicState view = new TopicState(state, maxMessageBytes);
        if (view.entry(name).isPresent()) {
            return new Receipt(TopicRefusal.TOPIC_COMMAND_STALE.name(), 0);
        }
        String top = TopicNames.topLevel(name);
        boolean topLevel = top.equals(name);
        EntryRecord topRecord = topLevel ? null : view.entry(top).orElse(null);
        long count = view.count();
        long subtree = topLevel ? 0 : topRecord == null ? 0 : topRecord.subtree();
        if (count >= config.settings().maxEntries()
                || !topLevel && (topRecord == null || subtree >= config.settings().maxEntriesPerNamespace())) {
            return new Receipt(TopicRefusal.TOPIC_COMMAND_LIMIT.name(), 0);
        }
        long activation = block.height() + config.settings().activationLag();
        putRecord(state, new EntryRecord(name, null, 1, 1, topLevel ? 1 : 0));
        if (!topLevel) {
            putRecord(state, new EntryRecord(top, topRecord.active(), topRecord.head(), topRecord.pending(),
                    topRecord.subtree() + 1));
        }
        state.put(TopicKeys.count(), TopicKeys.u64(count + 1));
        queue(state, name, new QueuedRevision(1, activation, authority, epoch, content, messageId));
        return new Receipt(Receipt.ACCEPTED, activation);
    }

    private Receipt change(AppBlock block, CommandStatement statement, long epoch, Config config, String messageId,
                           AppStateWriter state, UnaryOperator<EntryContent> edit) {
        TopicState view = new TopicState(state, maxMessageBytes);
        Optional<EntryRecord> maybe = view.entry(statement.target());
        if (maybe.isEmpty() || maybe.get().head() != statement.expected()) {
            return new Receipt(TopicRefusal.TOPIC_COMMAND_STALE.name(), 0);
        }
        EntryRecord record = maybe.get();
        if (record.pending() >= config.settings().maxPendingPerEntry()) {
            return new Receipt(TopicRefusal.TOPIC_COMMAND_LIMIT.name(), 0);
        }
        EntryContent head = view.headContent(record).orElseThrow(() ->
                new IllegalStateException("topic entry head revision is missing: " + record.name()));
        long revision = record.head() + 1;
        long activation = activationAfterHead(block, config, record, view);
        putRecord(state, new EntryRecord(record.name(), record.active(), revision, record.pending() + 1,
                record.subtree()));
        queue(state, record.name(), new QueuedRevision(revision, activation, statement.authority(), epoch,
                edit.apply(head), messageId));
        return new Receipt(Receipt.ACCEPTED, activation);
    }

    /**
     * {@code height + lag}, but never before the entry's queued head: a revision is built on the head's
     * content, so inclusion order stays activation order even after a governed lag decrease (D5a).
     */
    private long activationAfterHead(AppBlock block, Config config, EntryRecord record, TopicState view) {
        long activation = block.height() + config.settings().activationLag();
        if (record.pending() > 0) {
            activation = Math.max(activation, view.queued(record.name(), record.head())
                    .map(QueuedRevision::activation).orElse(0L));
        }
        return activation;
    }

    /**
     * D8: recorded after the block's messages, so later blocks refuse the delegation while this block's own
     * messages are judged as the parent state judges them; retention ends at the delegation's notAfter.
     */
    private Receipt revoke(AppBlock block, CommandStatement statement, TopicWire.Delegation delegation,
                           AppStateWriter state, Map<String, Long> revokedInBlock) {
        String hash = TopicWireCodec.delegationHash(statement.chainId(), statement.generationId(), delegation);
        if (state.get(TopicKeys.revoked(hash)).isEmpty()) {
            revokedInBlock.putIfAbsent(hash, delegation.notAfter());
        }
        return new Receipt(Receipt.ACCEPTED, block.height() + 1);
    }

    private static void storeSchema(String schema, byte[] ir, AppStateWriter state, Set<String> schemasStoredInBlock) {
        if (ir != null && state.get(TopicKeys.schema(schema)).isEmpty()) {
            state.put(TopicKeys.schema(schema), ir);
            schemasStoredInBlock.add(schema);
        }
    }

    // ------------------------------------------------------------------ governance commands (D5, D13)

    /**
     * The configuration governing this block's governance: the committed one, or while applying block 1 of a
     * genesis-enabled chain the seed, which that block writes after its messages (D1). Node-local YAML is
     * never read at any other height, so a restart with a different YAML cannot change consensus state.
     */
    private Optional<Config> governingConfig(TopicState view, long height) {
        Optional<Config> committed = view.config();
        return committed.isPresent() || height != 1 || genesisSeed == null
                ? committed : Optional.of(new Config(1, 1, genesisSeed));
    }

    private void govern(AppBlock block, AppMessage message, AppStateWriter state, Set<String> schemasStoredInBlock) {
        String sender = HexUtil.encodeHexString(message.getSender()).toLowerCase(Locale.ROOT);
        GovernanceCommand command;
        try {
            command = TopicWireCodec.decodeGovernanceCommand(message.getBody());
        } catch (IllegalArgumentException malformed) {
            return; // members submit through the privileged path; a malformed command is void (ADR-008.3)
        }
        if (!group.containsAt(sender, block.height()) || !chainId.equals(command.chainId())
                || !generationId.get().equals(command.generationId())) {
            return;
        }
        String hash = TopicWireCodec.blake2b256Hex(message.getBody());
        long height = block.height();
        TopicState view = new TopicState(state, maxMessageBytes);
        Optional<Config> config = governingConfig(view, height);
        long window = config.map(c -> c.settings().governanceWindowBlocks())
                .orElse(Settings.DEFAULT_GOVERNANCE_WINDOW_BLOCKS);
        // Receipts only under a governing configuration: an unapproved command never sets its own retention.
        long maxCommandMs = config.map(c -> c.settings().maxCommandMs()).orElse(0L);
        List<GovernanceApproval> pending = new ArrayList<>();
        GovernanceApproval current = null;
        for (GovernanceApproval stored : view.governancePending()) {
            Set<String> members = group.currentApprovers(stored.approvers(), height);
            if (height - stored.firstSeen() > window || members.isEmpty()) {
                continue; // expired, or only removed members approved it
            }
            GovernanceApproval approval = new GovernanceApproval(stored.commandHash(), stored.firstSeen(),
                    List.copyOf(members));
            if (approval.commandHash().equals(hash)) {
                current = approval;
            } else {
                pending.add(approval);
            }
        }
        TreeSet<String> approvers = new TreeSet<>(current == null ? List.of() : current.approvers());
        boolean newVote = approvers.add(sender);
        boolean approved = approvers.size() >= group.thresholdAt(height);
        Receipt receipt = null;
        if (!approved) {
            // Each member's votes hold at most an equal share of the bounded record, so the faulty minority
            // cannot fill it, even by approving each other's commands, and deny governance to an honest quorum.
            long held = pending.stream().filter(approval -> approval.approvers().contains(sender)).count();
            if (!newVote || (current != null || pending.size() < MAX_GOVERNANCE_PENDING)
                    && held < proposalShare(height)) {
                pending.add(new GovernanceApproval(hash, current == null ? height : current.firstSeen(),
                        List.copyOf(approvers)));
            } else {
                if (current != null) {
                    pending.add(current); // the vote is refused; the command keeps its approvals
                }
                receipt = new Receipt(TopicRefusal.TOPIC_COMMAND_LIMIT.name(), 0);
            }
        }
        state.put(TopicKeys.governance(), TopicWireCodec.encodeGovernancePending(pending));
        if (approved) {
            receipt = applyGovernance(block, command, messageId(message), state, schemasStoredInBlock);
        }
        if (receipt != null && maxCommandMs > 0) {
            writeReceipt(block, messageId(message), receipt, maxCommandMs, state);
        }
    }

    /** Pending commands one member may have approved: an equal share of the record (D5). */
    private int proposalShare(long height) {
        return Math.max(1, MAX_GOVERNANCE_PENDING / group.membersAt(height).size());
    }

    private Receipt applyGovernance(AppBlock block, GovernanceCommand command, String messageId,
                                    AppStateWriter state, Set<String> schemasStoredInBlock) {
        TopicState view = new TopicState(state, maxMessageBytes);
        Optional<Config> maybeConfig = governingConfig(view, block.height());
        if (command.operation() instanceof GovConfigure configure) {
            if (state.get(TopicKeys.configPending()).isPresent()) {
                return new Receipt(TopicRefusal.TOPIC_COMMAND_LIMIT.name(), 0); // one configuration change at a time
            }
            long lag = maybeConfig.map(config -> config.settings().activationLag())
                    .orElse(configure.settings().activationLag());
            long revision = maybeConfig.map(Config::revision).orElse(0L) + 1;
            long activation = block.height() + lag;
            state.put(TopicKeys.queued(CONFIG, revision), TopicWireCodec.encodeConfig(
                    new Config(revision, revision, configure.settings())));
            state.put(TopicKeys.configPending(), TopicKeys.u64(revision));
            addActivation(state, activation, new Activation(CONFIG, revision, messageId));
            return new Receipt(Receipt.ACCEPTED, activation);
        }
        if (maybeConfig.isEmpty()) {
            return new Receipt(TopicRefusal.TOPIC_COMMAND_INVALID.name(), 0);
        }
        Config config = maybeConfig.get();
        return switch (command.operation()) {
            case GovCreate create -> {
                if (!config.settings().governance() || !TopicNames.isTopLevel(create.name())
                        || !TopicNames.isValidEntryName(create.name())) {
                    yield new Receipt(TopicRefusal.TOPIC_COMMAND_DENIED.name(), 0);
                }
                TopicCommandRules.Admission schema = TopicCommandRules.checkSchema(create.policy(),
                        create.schemaIr(), view, schemasStoredInBlock);
                if (schema != null) {
                    yield new Receipt(schema.refusal().name(), 0);
                }
                yield storeSchemaIfAccepted(create(block, create.name(), GOVERNANCE_AUTHORITY, 0,
                        new EntryContent(create.owners(), create.policy(), false), config, messageId, state),
                        create.policy().schema(), create.schemaIr(), state, schemasStoredInBlock);
            }
            case GovRecover recover -> override(block, recover.name(), config, messageId, state,
                    content -> new EntryContent(recover.owners(), content.policy(), content.retired()));
            case GovRetire retire -> override(block, retire.name(), config, messageId, state,
                    content -> new EntryContent(content.owners(), content.policy(), true));
            case GovConfigure ignored -> throw new IllegalStateException("handled above");
        };
    }

    /**
     * A governance override cancels the entry's pending revisions and becomes its new head (D5a). Retiring
     * a pending-only creation cancels it outright, freeing the name before it ever governs.
     */
    private Receipt override(AppBlock block, String name, Config config, String messageId, AppStateWriter state,
                             UnaryOperator<EntryContent> edit) {
        TopicState view = new TopicState(state, maxMessageBytes);
        Optional<EntryRecord> maybe = view.entry(name);
        if (maybe.isEmpty()) {
            return new Receipt(TopicRefusal.TOPIC_COMMAND_STALE.name(), 0);
        }
        EntryRecord record = maybe.get();
        if (record.active() == null) {
            if (edit.apply(view.headContent(record).orElseThrow()).retired()) {
                cancelFrom(record, 1, state);
                return new Receipt(Receipt.ACCEPTED, block.height() + 1);
            }
            return new Receipt(TopicRefusal.TOPIC_COMMAND_STALE.name(), 0);
        }
        long firstPending = record.active().revision() + 1;
        for (long revision = firstPending; revision <= record.head(); revision++) {
            supersede(record.name(), revision, state); // cancelled: activation finds nothing to install
        }
        long revision = record.head() + 1;
        long activation = block.height() + config.settings().activationLag();
        putRecord(state, new EntryRecord(name, record.active(), revision, 1, record.subtree()));
        queue(state, name, new QueuedRevision(revision, activation, GOVERNANCE_AUTHORITY, 0,
                edit.apply(record.active().content()), messageId));
        return new Receipt(Receipt.ACCEPTED, activation);
    }

    // ------------------------------------------------------------------ activation (D5a: materialized at a - 1)

    private void activate(long activationHeight, AppStateWriter state) {
        byte[] countKey = TopicKeys.activationCount(activationHeight);
        Optional<byte[]> listed = state.get(countKey);
        if (listed.isEmpty()) {
            return;
        }
        state.delete(countKey);
        long count = TopicKeys.u64(listed.get());
        for (long index = 0; index < count; index++) {
            byte[] itemKey = TopicKeys.activation(activationHeight, index);
            Activation activation = TopicWireCodec.decodeActivation(state.get(itemKey).orElseThrow());
            state.delete(itemKey);
            if (CONFIG.equals(activation.name())) {
                activateConfig(activation.revision(), activationHeight, state);
            } else {
                activateEntry(activation, activationHeight, state);
            }
        }
    }

    private void activateConfig(long revision, long activationHeight, AppStateWriter state) {
        byte[] queuedKey = TopicKeys.queued(CONFIG, revision);
        Optional<byte[]> queued = state.get(queuedKey);
        if (queued.isEmpty()) {
            return;
        }
        state.delete(queuedKey);
        state.delete(TopicKeys.configPending());
        Config next = TopicWireCodec.decodeConfig(queued.get());
        Optional<Config> previous = state.get(TopicKeys.config()).map(TopicWireCodec::decodeConfig);
        previous.ifPresent(old -> state.put(TopicKeys.history(CONFIG, old.revision()),
                TopicWireCodec.encodeConfig(old)));
        // The ~registrars epoch advances only when the registrar set changes (D5a).
        long registrarsSince = previous
                .filter(old -> Objects.equals(old.settings().registrars(), next.settings().registrars()))
                .map(Config::registrarsSince).orElse(next.revision());
        state.put(TopicKeys.config(), TopicWireCodec.encodeConfig(
                new Config(next.revision(), registrarsSince, next.settings())));
    }

    private void activateEntry(Activation activation, long activationHeight, AppStateWriter state) {
        String name = activation.name();
        long revision = activation.revision();
        TopicState view = new TopicState(state, maxMessageBytes);
        Optional<QueuedRevision> maybeQueued = view.queued(name, revision);
        Optional<EntryRecord> maybeRecord = view.entry(name);
        if (maybeQueued.isEmpty() || maybeRecord.isEmpty()
                || maybeQueued.get().activation() != activationHeight
                || !maybeQueued.get().messageId().equals(activation.messageId())) {
            return; // cancelled, or a stale ticket of a cancelled creation whose name was reused
        }
        QueuedRevision queued = maybeQueued.get();
        EntryRecord record = maybeRecord.get();
        if (!epochStillActive(queued, view)) {
            cancelFrom(record, revision, state);
            return;
        }
        state.delete(TopicKeys.queued(name, revision));
        if (record.active() != null) {
            state.put(TopicKeys.history(name, record.active().revision()),
                    TopicWireCodec.encodeActiveRevision(record.active()));
        }
        // The entry's authority epoch advances when its owners change or a governance override activates (D5a).
        long ownersSince = record.active() != null && !GOVERNANCE_AUTHORITY.equals(queued.authority())
                && record.active().content().owners().equals(queued.content().owners())
                ? record.active().ownersSince() : revision;
        putRecord(state, new EntryRecord(name, new ActiveRevision(revision, activationHeight, ownersSince,
                queued.content()), record.head(), record.pending() - 1, record.subtree()));
    }

    /** The authority's active epoch must still be the recorded one immediately before activation (D5a). */
    private boolean epochStillActive(QueuedRevision queued, TopicState view) {
        String authority = queued.authority();
        return switch (authority) {
            case GOVERNANCE_AUTHORITY, TopicWire.AUTHORITY_CLAIM, TopicWire.AUTHORITY_ISSUER -> true;
            case TopicWire.AUTHORITY_REGISTRARS -> view.config()
                    .map(config -> config.registrarsSince() == queued.authorityEpoch()).orElse(false);
            default -> view.entry(authority).map(EntryRecord::active)
                    .map(active -> active.ownersSince() == queued.authorityEpoch()).orElse(false);
        };
    }

    /**
     * Cancel revision {@code revision} and every later queued revision of the entry: they were built on
     * its content (D5a, cancellation cascades). Each gets a SUPERSEDED receipt; revision numbers are never
     * reused, so the head stays. A pending creation that is cancelled frees its name.
     */
    private void cancelFrom(EntryRecord record, long revision, AppStateWriter state) {
        for (long cancelled = revision; cancelled <= record.head(); cancelled++) {
            supersede(record.name(), cancelled, state);
        }
        if (record.active() == null) {
            state.delete(TopicKeys.entry(record.name()));
            TopicState view = new TopicState(state, maxMessageBytes);
            state.put(TopicKeys.count(), TopicKeys.u64(view.count() - 1));
            String top = TopicNames.topLevel(record.name());
            if (!top.equals(record.name())) {
                view.entry(top).ifPresent(parent -> putRecord(state, new EntryRecord(parent.name(),
                        parent.active(), parent.head(), parent.pending(), parent.subtree() - 1)));
            }
            return;
        }
        putRecord(state, new EntryRecord(record.name(), record.active(), record.head(), 0, record.subtree()));
    }

    /** Delete a queued revision and turn its command's receipt, if still kept, into SUPERSEDED. */
    private void supersede(String name, long revision, AppStateWriter state) {
        byte[] key = TopicKeys.queued(name, revision);
        Optional<byte[]> queued = state.get(key);
        if (queued.isEmpty()) {
            return;
        }
        state.delete(key);
        byte[] receiptKey = TopicKeys.receipt(TopicWireCodec.decodeQueuedRevision(queued.get()).messageId());
        if (state.get(receiptKey).isPresent()) {
            state.put(receiptKey, TopicWireCodec.encodeReceipt(
                    new Receipt(TopicRefusal.TOPIC_COMMAND_SUPERSEDED.name(), 0)));
        }
    }

    // ------------------------------------------------------------------ expiry (D9, F14)

    private void writeReceipt(AppBlock block, String messageId, Receipt receipt, long maxCommandMs,
                              AppStateWriter state) {
        state.put(TopicKeys.receipt(messageId), TopicWireCodec.encodeReceipt(receipt));
        addExpiry(block.height(), TopicKeys.RECEIPT_QUEUE, new ExpiryItem(ExpiryItem.RECEIPT, messageId),
                block.timestamp() + maxCommandMs, state);
    }

    private static String messageId(AppMessage message) {
        return message.getMessageIdHex().toLowerCase(Locale.ROOT);
    }

    /** Append to this block's batch of the item's queue; batches are linked in height order for FIFO pruning. */
    private void addExpiry(long height, int queue, ExpiryItem item, long notAfter, AppStateWriter state) {
        byte[] key = TopicKeys.expiryBatch(queue, height);
        Optional<ExpiryBatch> existing = state.get(key).map(TopicWireCodec::decodeExpiryBatch);
        if (existing.isEmpty()) {
            long tail = state.get(TopicKeys.expiryTail(queue)).map(TopicKeys::u64).orElse(0L);
            if (tail == 0) {
                state.put(TopicKeys.expiryHead(queue), TopicKeys.u64(height));
            } else {
                ExpiryBatch previous = TopicWireCodec.decodeExpiryBatch(
                        state.get(TopicKeys.expiryBatch(queue, tail)).orElseThrow());
                state.put(TopicKeys.expiryBatch(queue, tail), TopicWireCodec.encodeExpiryBatch(new ExpiryBatch(
                        previous.maxNotAfter(), height, previous.count(), previous.pruned())));
            }
            state.put(TopicKeys.expiryTail(queue), TopicKeys.u64(height));
        }
        long index = existing.map(ExpiryBatch::count).orElse(0L);
        state.put(TopicKeys.expiryItem(queue, height, index), TopicWireCodec.encodeExpiryItem(item));
        long maxNotAfter = Math.max(notAfter, existing.map(ExpiryBatch::maxNotAfter).orElse(0L));
        state.put(key, TopicWireCodec.encodeExpiryBatch(new ExpiryBatch(maxNotAfter, 0, index + 1, 0)));
    }

    private void prune(long blockTime, AppStateWriter state) {
        prune(TopicKeys.CREDENTIAL_QUEUE, blockTime, state);
        prune(TopicKeys.RECEIPT_QUEUE, blockTime, state);
    }

    /** Remove expired batches from the front of a queue, at most {@code 2 x max-block-messages} items per block. */
    private void prune(int queue, long blockTime, AppStateWriter state) {
        long first = state.get(TopicKeys.expiryHead(queue)).map(TopicKeys::u64).orElse(0L);
        if (first == 0) {
            return; // nothing indexed: write nothing, so a chain without topics keeps its state (I1)
        }
        long budget = pruneBudget;
        long head = first;
        while (head != 0 && budget > 0) {
            byte[] batchKey = TopicKeys.expiryBatch(queue, head);
            ExpiryBatch batch = TopicWireCodec.decodeExpiryBatch(state.get(batchKey).orElseThrow());
            if (blockTime <= batch.maxNotAfter()) {
                break;
            }
            long end = Math.min(batch.count(), batch.pruned() + budget);
            for (long index = batch.pruned(); index < end; index++) {
                byte[] itemKey = TopicKeys.expiryItem(queue, head, index);
                ExpiryItem item = TopicWireCodec.decodeExpiryItem(state.get(itemKey).orElseThrow());
                state.delete(switch (item.kind()) {
                    case ExpiryItem.USED -> TopicKeys.used(item.hash());
                    case ExpiryItem.REVOKED -> TopicKeys.revoked(item.hash());
                    default -> TopicKeys.receipt(item.hash());
                });
                state.delete(itemKey);
            }
            budget -= end - batch.pruned();
            if (end < batch.count()) {
                state.put(batchKey, TopicWireCodec.encodeExpiryBatch(
                        new ExpiryBatch(batch.maxNotAfter(), batch.next(), batch.count(), end)));
                break;
            }
            state.delete(batchKey);
            head = batch.next();
        }
        if (head == 0) {
            state.delete(TopicKeys.expiryHead(queue));
            state.delete(TopicKeys.expiryTail(queue));
        } else if (head != first) {
            state.put(TopicKeys.expiryHead(queue), TopicKeys.u64(head));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void queue(AppStateWriter state, String name, QueuedRevision queued) {
        state.put(TopicKeys.queued(name, queued.revision()), TopicWireCodec.encodeQueuedRevision(queued));
        addActivation(state, queued.activation(), new Activation(name, queued.revision(), queued.messageId()));
    }

    private static void addActivation(AppStateWriter state, long height, Activation activation) {
        byte[] countKey = TopicKeys.activationCount(height);
        long index = state.get(countKey).map(TopicKeys::u64).orElse(0L);
        state.put(TopicKeys.activation(height, index), TopicWireCodec.encodeActivation(activation));
        state.put(countKey, TopicKeys.u64(index + 1));
    }

    private static void putRecord(AppStateWriter state, EntryRecord record) {
        state.put(TopicKeys.entry(record.name()), TopicWireCodec.encodeEntryRecord(record));
    }

}
