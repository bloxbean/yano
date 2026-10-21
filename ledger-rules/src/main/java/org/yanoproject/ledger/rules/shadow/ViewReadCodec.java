package org.yanoproject.ledger.rules.shadow;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import co.nstant.in.cbor.model.Array;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.RecordingLedgerView;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * JSON encoding of recorded {@link org.yanoproject.ledger.rules.view.LedgerView} reads, for shadow-dump
 * bundles ({@link ShadowDumpBundle}).
 *
 * <p>CCL objects travel as CBOR hex (outputs, pool registrations, governance actions) so the replay sees the
 * same decoded values; protocol parameters travel as CCL's own JSON bean.</p>
 */
public final class ViewReadCodec {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private ViewReadCodec() {
    }

    // ------------------------------------------------------------------ encode

    /** Encodes one read: {@code {method, key, outcome, value | reason}}. */
    public static ObjectNode encode(RecordingLedgerView.Read read) {
        ObjectNode node = JSON.objectNode();
        node.put("method", read.method());
        node.put("key", read.key());
        switch (read.result()) {
            case Lookup.Present<?> p -> {
                node.put("outcome", "present");
                node.set("value", encodeValue(read.method(), p.value()));
            }
            case Lookup.Absent<?> a -> node.put("outcome", "absent");
            case Lookup.Unavailable<?> u -> {
                node.put("outcome", "unavailable");
                node.put("reason", u.reason());
            }
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    static JsonNode encodeValue(String method, Object value) {
        return switch (method) {
            case RecordingLedgerView.UTXO -> utxo((UtxoEntry) value);
            case RecordingLedgerView.ACCOUNT -> account((AccountState) value);
            case RecordingLedgerView.POOL -> pool((PoolState) value);
            case RecordingLedgerView.POOL_BY_VRF -> JSON.textNode(((PoolId) value).hashHex());
            case RecordingLedgerView.DREP -> drep((DRepState) value);
            case RecordingLedgerView.COMMITTEE_BY_COLD -> member((CommitteeMemberState) value);
            case RecordingLedgerView.COMMITTEE_BY_HOT, RecordingLedgerView.COMMITTEE_MEMBERS -> {
                ArrayNode array = JSON.arrayNode();
                ((List<CommitteeMemberState>) value).forEach(m -> array.add(member(m)));
                yield array;
            }
            case RecordingLedgerView.COMMITTEE_CANDIDATES -> {
                ArrayNode array = JSON.arrayNode();
                new TreeSet<>(((Set<CredentialKey>) value).stream().map(CredentialKey::toString).toList())
                        .forEach(array::add);
                yield array;
            }
            case RecordingLedgerView.PROPOSAL -> proposal((ProposalState) value);
            case RecordingLedgerView.ACTIVE_PROPOSALS -> {
                ArrayNode array = JSON.arrayNode();
                ((List<ProposalState>) value).forEach(p -> array.add(proposal(p)));
                yield array;
            }
            case RecordingLedgerView.ENACTED_ROOTS -> roots((EnactedRoots) value);
            case RecordingLedgerView.GUARDRAIL -> JSON.textNode((String) value);
            case RecordingLedgerView.DORMANT_EPOCHS -> JSON.numberNode((Long) value);
            case RecordingLedgerView.TREASURY -> JSON.textNode(value.toString());
            case RecordingLedgerView.PROTOCOL_PARAMS -> MAPPER.valueToTree((ProtocolParams) value);
            default -> throw new IllegalArgumentException("unknown ledger view method " + method);
        };
    }

    private static ObjectNode utxo(UtxoEntry entry) {
        ObjectNode node = JSON.objectNode();
        node.put("outpoint", entry.outpoint().txHash() + "#" + entry.outpoint().index());
        try {
            node.put("output", HexUtil.encodeHexString(CborSerializationUtil.serialize(entry.output().serialize())));
        } catch (Exception e) {
            throw new IllegalStateException("cannot encode output " + entry.outpoint() + ": " + e.getMessage(), e);
        }
        byte[] inline = entry.inlineDatumCbor();
        if (inline != null) {
            node.put("inlineDatum", HexUtil.encodeHexString(inline));
        }
        return node;
    }

    private static ObjectNode account(AccountState account) {
        ObjectNode node = JSON.objectNode();
        node.put("credential", account.credential().toString());
        node.put("deposit", account.deposit().toString());
        node.put("rewardBalance", account.rewardBalance().toString());
        if (account.delegatedPool() != null) {
            node.put("delegatedPool", account.delegatedPool().hashHex());
        }
        if (account.drepDelegation() != null) {
            DRepTarget drep = account.drepDelegation();
            node.put("drep", drep.kind() == DRepTarget.Kind.CREDENTIAL ? drep.credential().toString()
                    : drep.kind().name());
        }
        return node;
    }

    private static ObjectNode pool(PoolState pool) {
        ObjectNode node = JSON.objectNode();
        node.put("id", pool.id().hashHex());
        node.put("deposit", pool.deposit().toString());
        if (pool.vrfKeyHashHex() != null) {
            node.put("vrf", pool.vrfKeyHashHex());
        }
        if (pool.retiringEpoch() != null) {
            node.put("retiringEpoch", pool.retiringEpoch());
        }
        if (pool.params() != null) {
            node.put("params", registration(pool.params()));
        }
        if (pool.futureParams() != null) {
            node.put("futureParams", registration(pool.futureParams()));
        }
        return node;
    }

    private static String registration(PoolRegistration registration) {
        try {
            return HexUtil.encodeHexString(CborSerializationUtil.serialize(registration.serialize(Era.Conway)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot encode a pool registration: " + e.getMessage(), e);
        }
    }

    private static ObjectNode drep(DRepState drep) {
        ObjectNode node = JSON.objectNode();
        node.put("credential", drep.credential().toString());
        node.put("deposit", drep.deposit().toString());
        node.put("expiryEpoch", drep.expiryEpoch());
        return node;
    }

    private static ObjectNode member(CommitteeMemberState member) {
        ObjectNode node = JSON.objectNode();
        node.put("cold", member.cold().toString());
        if (member.hot() != null) {
            node.put("hot", member.hot().toString());
        }
        node.put("resigned", member.resigned());
        if (member.expiryEpoch() != null) {
            node.put("expiryEpoch", member.expiryEpoch());
        }
        return node;
    }

    private static ObjectNode proposal(ProposalState proposal) {
        ObjectNode node = JSON.objectNode();
        node.put("id", proposal.id().toString());
        node.put("type", proposal.type().name());
        if (proposal.action() != null) {
            try {
                node.put("action", HexUtil.encodeHexString(CborSerializationUtil.serialize(
                        proposal.action().serialize())));
            } catch (Exception e) {
                throw new IllegalStateException("cannot encode the action of " + proposal.id() + ": "
                        + e.getMessage(), e);
            }
        }
        if (proposal.prevActionId() != null) {
            node.put("prev", proposal.prevActionId().toString());
        }
        node.put("proposedEpoch", proposal.proposedEpoch());
        node.put("expiresAfterEpoch", proposal.expiresAfterEpoch());
        node.put("deposit", proposal.deposit().toString());
        if (proposal.returnAddress() != null) {
            node.put("returnAddress", proposal.returnAddress());
        }
        if (proposal.paramUpdateKeys() != null) {
            ArrayNode keys = node.putArray("paramUpdateKeys");
            new TreeSet<>(proposal.paramUpdateKeys()).forEach(keys::add);
        }
        return node;
    }

    private static ObjectNode roots(EnactedRoots roots) {
        ObjectNode node = JSON.objectNode();
        putId(node, "pparamUpdate", roots.pparamUpdate());
        putId(node, "hardFork", roots.hardFork());
        putId(node, "committee", roots.committee());
        putId(node, "constitution", roots.constitution());
        return node;
    }

    private static void putId(ObjectNode node, String field, GovActionId id) {
        if (id != null) {
            node.put(field, id.toString());
        }
    }

    // ------------------------------------------------------------------ decode

    /** Decodes a read encoded by {@link #encode}. */
    public static RecordingLedgerView.Read decode(JsonNode node) {
        String method = node.path("method").asText();
        String key = node.path("key").asText("");
        Lookup<?> result = switch (node.path("outcome").asText()) {
            case "present" -> Lookup.present(decodeValue(method, node.get("value")));
            case "absent" -> Lookup.absent();
            case "unavailable" -> Lookup.unavailable(node.path("reason").asText("unavailable"));
            default -> throw new IllegalArgumentException("unknown outcome in " + node);
        };
        return new RecordingLedgerView.Read(method, key, result);
    }

    static Object decodeValue(String method, JsonNode value) {
        return switch (method) {
            case RecordingLedgerView.UTXO -> decodeUtxo(value);
            case RecordingLedgerView.ACCOUNT -> decodeAccount(value);
            case RecordingLedgerView.POOL -> decodePool(value);
            case RecordingLedgerView.POOL_BY_VRF -> new PoolId(value.asText());
            case RecordingLedgerView.DREP -> new DRepState(credential(value.get("credential").asText()),
                    new BigInteger(value.get("deposit").asText()), value.get("expiryEpoch").asLong());
            case RecordingLedgerView.COMMITTEE_BY_COLD -> decodeMember(value);
            case RecordingLedgerView.COMMITTEE_BY_HOT, RecordingLedgerView.COMMITTEE_MEMBERS -> {
                List<CommitteeMemberState> members = new ArrayList<>();
                value.forEach(m -> members.add(decodeMember(m)));
                yield List.copyOf(members);
            }
            case RecordingLedgerView.COMMITTEE_CANDIDATES -> {
                Set<CredentialKey> candidates = new LinkedHashSet<>();
                value.forEach(c -> candidates.add(credential(c.asText())));
                yield Set.copyOf(candidates);
            }
            case RecordingLedgerView.PROPOSAL -> decodeProposal(value);
            case RecordingLedgerView.ACTIVE_PROPOSALS -> {
                List<ProposalState> proposals = new ArrayList<>();
                value.forEach(p -> proposals.add(decodeProposal(p)));
                yield List.copyOf(proposals);
            }
            case RecordingLedgerView.ENACTED_ROOTS -> new EnactedRoots(actionId(value, "pparamUpdate"),
                    actionId(value, "hardFork"), actionId(value, "committee"), actionId(value, "constitution"));
            case RecordingLedgerView.GUARDRAIL -> value.asText();
            case RecordingLedgerView.DORMANT_EPOCHS -> value.asLong();
            case RecordingLedgerView.TREASURY -> new BigInteger(value.asText());
            case RecordingLedgerView.PROTOCOL_PARAMS -> MAPPER.convertValue(value, ProtocolParams.class);
            default -> throw new IllegalArgumentException("unknown ledger view method " + method);
        };
    }

    private static UtxoEntry decodeUtxo(JsonNode value) {
        String[] outpoint = value.get("outpoint").asText().split("#");
        TransactionOutput output;
        try {
            output = TransactionOutput.deserialize(CborSerializationUtil.deserialize(
                    HexUtil.decodeHexString(value.get("output").asText())));
        } catch (Exception e) {
            throw new IllegalArgumentException("undecodable output " + value.get("outpoint") + ": " + e.getMessage(), e);
        }
        byte[] inline = value.hasNonNull("inlineDatum") ? HexUtil.decodeHexString(value.get("inlineDatum").asText())
                : null;
        return new UtxoEntry(new Outpoint(outpoint[0], Integer.parseInt(outpoint[1])), output, inline);
    }

    private static AccountState decodeAccount(JsonNode value) {
        PoolId pool = value.hasNonNull("delegatedPool") ? new PoolId(value.get("delegatedPool").asText()) : null;
        DRepTarget drep = null;
        if (value.hasNonNull("drep")) {
            String text = value.get("drep").asText();
            drep = switch (text) {
                case "ALWAYS_ABSTAIN" -> DRepTarget.ALWAYS_ABSTAIN;
                case "ALWAYS_NO_CONFIDENCE" -> DRepTarget.ALWAYS_NO_CONFIDENCE;
                default -> DRepTarget.credential(credential(text));
            };
        }
        return new AccountState(credential(value.get("credential").asText()),
                new BigInteger(value.get("deposit").asText()), new BigInteger(value.get("rewardBalance").asText()),
                pool, drep);
    }

    private static PoolState decodePool(JsonNode value) {
        return new PoolState(new PoolId(value.get("id").asText()), new BigInteger(value.get("deposit").asText()),
                value.hasNonNull("vrf") ? value.get("vrf").asText() : null,
                value.hasNonNull("retiringEpoch") ? value.get("retiringEpoch").asLong() : null,
                value.hasNonNull("params") ? decodeRegistration(value.get("params").asText()) : null,
                value.hasNonNull("futureParams") ? decodeRegistration(value.get("futureParams").asText()) : null);
    }

    private static PoolRegistration decodeRegistration(String hex) {
        try {
            return PoolRegistration.deserialize(CborSerializationUtil.deserialize(HexUtil.decodeHexString(hex)));
        } catch (Exception e) {
            throw new IllegalArgumentException("undecodable pool registration: " + e.getMessage(), e);
        }
    }

    private static CommitteeMemberState decodeMember(JsonNode value) {
        return new CommitteeMemberState(credential(value.get("cold").asText()),
                value.hasNonNull("hot") ? credential(value.get("hot").asText()) : null,
                value.path("resigned").asBoolean(false),
                value.hasNonNull("expiryEpoch") ? value.get("expiryEpoch").asLong() : null);
    }

    private static ProposalState decodeProposal(JsonNode value) {
        GovAction action = null;
        if (value.hasNonNull("action")) {
            action = GovAction.deserialize((Array) CborSerializationUtil.deserialize(
                    HexUtil.decodeHexString(value.get("action").asText())));
        }
        Set<Integer> keys = null;
        if (value.has("paramUpdateKeys")) {
            keys = new TreeSet<>(Comparator.naturalOrder());
            for (JsonNode k : value.get("paramUpdateKeys")) {
                keys.add(k.asInt());
            }
        }
        return new ProposalState(actionId(value.get("id").asText()),
                GovActionType.valueOf(value.get("type").asText()), action, actionId(value, "prev"),
                value.get("proposedEpoch").asLong(), value.get("expiresAfterEpoch").asLong(),
                new BigInteger(value.get("deposit").asText()),
                value.hasNonNull("returnAddress") ? value.get("returnAddress").asText() : null, keys);
    }

    private static GovActionId actionId(JsonNode node, String field) {
        return node.hasNonNull(field) ? actionId(node.get(field).asText()) : null;
    }

    /** Parses {@link GovActionId#toString()} ({@code txhash#index}). */
    static GovActionId actionId(String text) {
        int hash = text.lastIndexOf('#');
        return new GovActionId(text.substring(0, hash), Integer.parseInt(text.substring(hash + 1)));
    }

    /** Parses {@link CredentialKey#toString()} ({@code key:hash} or {@code script:hash}). */
    static CredentialKey credential(String text) {
        int colon = text.indexOf(':');
        CredentialType type = text.substring(0, colon).equals("script") ? CredentialType.SCRIPT : CredentialType.KEY;
        return new CredentialKey(type, text.substring(colon + 1));
    }
}
