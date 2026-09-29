package org.yanoproject.ledger.rules.fixtures.blueprint;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.HardForkInitiationAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.NewConstitution;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.NoConfidence;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.UpdateCommittee;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;
import org.yanoproject.ledger.rules.util.ProposalParamUpdateKeys;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Decodes a Conway {@code NewEpochState} (as dumped into the cardano-blueprint ledger conformance vectors) into an
 * {@link InMemoryLedgerView} with everything the Java rules read (ADR-056 §8, Phase 7b).
 *
 * <p><b>Ground truth.</b> The {@code EncCBOR} instances of cardano-ledger. The vectors were produced by a ledger from
 * before cardano-ledger {@code 38c76760b} (2025-07-18, the {@code Accounts} refactor), so the certificate state uses
 * the {@code UMap} and the four-map {@code PState} of that time; the decoder reads that layout and, for the
 * account map, also the later one (a map of {@code ConwayAccountState}, ADR-056's pin {@code f649f975}). Every other
 * part has the same encoding at both revisions:</p>
 * <pre>
 * NewEpochState    = [epoch, blocksMadePrev, blocksMadeCur, EpochState, pulsingRewUpdate, poolDistr, stashedAVVM]
 * EpochState       = [[treasury, reserves], LedgerState, snapshots, nonMyopic]
 * LedgerState      = [ConwayCertState, UTxOState]                       (Shelley/LedgerState/Types.hs)
 * ConwayCertState  = [VState, PState, DState]                           (Conway/State/CertState.hs)
 * VState           = [{DRep cred => DRepState}, {cold => CommitteeAuthorization}, numDormantEpochs]
 * DRepState        = [expiry, StrictMaybe anchor, deposit, delegators]  (DRep.hs)
 * CommitteeAuthorization = [0, hot credential] / [1, StrictMaybe anchor]
 * PState           = [{pool => PoolParams}, {pool => future PoolParams}, {pool => retiring epoch}, {pool => deposit}]
 * DState           = [UMap, futureGenDelegs, genDelegs, InstantaneousRewards]
 * UMap             = [{stake cred => UMElem}, {ptr => stake cred}]      (UMap.hs)
 * UMElem           = [StrictMaybe [reward, deposit], ptrs, StrictMaybe pool, StrictMaybe DRep]
 * UTxOState        = [{TxIn => TxOut}, deposited, fees, ConwayGovState, instantStake, donation]
 * ConwayGovState   = [Proposals, StrictMaybe Committee, Constitution, curPParams, prevPParams, futurePParams,
 *                     drepPulsingState]                                 (Conway/Governance.hs)
 * Proposals        = [[4 x StrictMaybe GovActionId roots], [GovActionState]]
 * GovActionState   = [id, ccVotes, drepVotes, spoVotes, ProposalProcedure, proposedIn, expiresAfter]
 * Committee        = [{cold => expiry epoch}, threshold]
 * Constitution     = [anchor, guardrail script hash / null]
 * </pre>
 * <p>StrictMaybe is {@code []} or {@code [x]}. The blueprint vectors store each protocol-parameter record by its
 * hash (a 32-byte string resolved through {@code pparams/}); an inline record is decoded as well.</p>
 *
 * <p><b>Fail closed.</b> A part the decoder cannot read (a MemPack-encoded UTxO entry, a later {@code PState}
 * layout, a missing parameter record, a malformed item) is reported in {@link Decoded#unsupported()} and the view is
 * not built, so the caller skips the vector with the reason instead of validating against a guessed state. Parts the
 * rules never read (blocks made, snapshots, the reward pulser, the stake distribution, the DRep pulser, the MIR pots)
 * are skipped by their item boundaries without inspection.</p>
 */
public final class NewEpochStateDecoder {

    /**
     * The decoded state.
     *
     * @param view        the ledger view; {@code null} when {@link #unsupported()} is not empty
     * @param params      the current protocol parameters, or {@code null} when they could not be decoded
     * @param epoch       the state's epoch ({@code nesEL})
     * @param populated   per decoded part, its number of entries (only non-empty parts), e.g. {@code utxo=1}
     * @param unsupported why a part could not be decoded (empty when the view was built)
     * @param keys        the keys of the decoded state (for comparing a whole state with another view)
     * @param notes       approximations the decoder made in parts the transaction rules do not read (a rounded
     *                    voting threshold, see {@link ConwayPParamsDecoder})
     */
    public record Decoded(InMemoryLedgerView view, ProtocolParams params, long epoch, Map<String, Integer> populated,
                          List<String> unsupported, Keys keys, List<String> notes) {
        public Decoded {
            populated = Collections.unmodifiableMap(new LinkedHashMap<>(populated));
            unsupported = List.copyOf(unsupported);
            notes = List.copyOf(notes);
        }

        public boolean ok() {
            return unsupported.isEmpty();
        }
    }

    /**
     * The keys of a decoded state, in state order: what an {@link InMemoryLedgerView} can look up but not enumerate.
     *
     * @param utxo      the unspent outputs
     * @param accounts  the registered stake credentials
     * @param pools     the registered pools
     * @param dreps     the registered DReps
     * @param proposals the proposals in {@code Proposals}
     */
    public record Keys(Set<Outpoint> utxo, Set<CredentialKey> accounts, Set<PoolId> pools, Set<CredentialKey> dreps,
                       List<GovActionId> proposals) {
        public Keys {
            utxo = Collections.unmodifiableSet(new LinkedHashSet<>(utxo));
            accounts = Collections.unmodifiableSet(new LinkedHashSet<>(accounts));
            pools = Collections.unmodifiableSet(new LinkedHashSet<>(pools));
            dreps = Collections.unmodifiableSet(new LinkedHashSet<>(dreps));
            proposals = List.copyOf(proposals);
        }
    }

    private final Function<String, Optional<byte[]>> pparamsByHash;

    /** @param pparamsByHash resolves a protocol-parameter record from its hash (lowercase hex) */
    public NewEpochStateDecoder(Function<String, Optional<byte[]>> pparamsByHash) {
        this.pparamsByHash = Objects.requireNonNull(pparamsByHash, "pparamsByHash");
    }

    /** Decodes a {@code NewEpochState}; never throws for malformed input (see {@link Decoded#unsupported()}). */
    public Decoded decode(byte[] cbor) {
        return new Run(cbor).decode();
    }

    /** One decode: collects the builder, the counts and the failures. */
    private final class Run {
        private final byte[] cbor;
        private final InMemoryLedgerView.Builder view = InMemoryLedgerView.builder();
        private final Map<String, Integer> populated = new LinkedHashMap<>();
        private final List<String> unsupported = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private ProtocolParams params;
        private long epoch = -1;
        // Parts read in one place and joined in another.
        private final Map<CredentialKey, Long> committeeTerms = new LinkedHashMap<>();
        /** Cold credential → authorised hot credential, or {@code null} when the member resigned. */
        private final Map<CredentialKey, CredentialKey> committeeAuth = new LinkedHashMap<>();
        private final List<ProposalCandidate> proposals = new ArrayList<>();
        private final Set<Outpoint> utxoKeys = new LinkedHashSet<>();
        private final Set<CredentialKey> accountKeys = new LinkedHashSet<>();
        private final Set<PoolId> poolKeys = new LinkedHashSet<>();
        private final Set<CredentialKey> drepKeys = new LinkedHashSet<>();

        Run(byte[] cbor) {
            this.cbor = cbor;
        }

        Decoded decode() {
            try {
                CborReader r = new CborReader(cbor);
                r.readArray(7, "NewEpochState");
                epoch = r.readUint();
                r.skip(); // blocks made, previous epoch
                r.skip(); // blocks made, current epoch
                byte[] epochState = r.readRaw();
                r.skip(); // pulsing reward update: rewards reach accounts only at the boundary
                r.skip(); // pool distribution (stake), not read by the rules
                r.skip(); // stashed AVVM addresses (Shelley only)
                if (!r.atEnd()) {
                    unsupported.add("NewEpochState: trailing bytes");
                }
                epochState(epochState);
            } catch (RuntimeException e) {
                unsupported.add("NewEpochState: " + e.getMessage());
            }
            if (params == null && unsupported.isEmpty()) {
                unsupported.add("protocol parameters: missing");
            }
            if (params != null) {
                view.protocolParams(params);
            }
            if (unsupported.isEmpty()) {
                joinCommittee();
                joinProposals();
            }
            return new Decoded(unsupported.isEmpty() ? view.build() : null, params, epoch, populated, unsupported,
                    new Keys(utxoKeys, accountKeys, poolKeys, drepKeys,
                            proposals.stream().map(ProposalCandidate::id).toList()), notes);
        }

        private void epochState(byte[] bytes) {
            CborReader r = new CborReader(bytes);
            r.readArray(4, "EpochState");
            part("chain account state", r.readRaw(), this::accountState);
            r.readArray(2, "LedgerState");
            byte[] certState = r.readRaw();
            byte[] utxoState = r.readRaw();
            part("cert state", certState, this::certState);
            part("UTxO state", utxoState, this::utxoState);
            // snapshots and non-myopic member rewards follow; the rules read neither
        }

        /** Runs one part's decoder; a failure is recorded against the part, and the rest of the state is still read. */
        private void part(String name, byte[] bytes, Consumer<CborReader> decoder) {
            try {
                CborReader r = new CborReader(bytes);
                decoder.accept(r);
                if (!r.atEnd()) {
                    unsupported.add(name + ": trailing bytes at offset " + r.position());
                }
            } catch (RuntimeException e) {
                unsupported.add(name + ": " + e.getMessage());
            }
        }

        private void count(String part, int n) {
            if (n > 0) {
                populated.merge(part, n, Integer::sum);
            }
        }

        // ----------------------------------------------------------------------------------------- pots

        private void accountState(CborReader r) {
            r.readArray(2, "ChainAccountState");
            BigInteger treasury = r.readBigInteger();
            r.readBigInteger(); // reserves
            view.treasury(treasury);
            count("treasury", treasury.signum() != 0 ? 1 : 0);
        }

        // ----------------------------------------------------------------------------------- cert state

        private void certState(CborReader r) {
            r.readArray(3, "ConwayCertState");
            part("VState", r.readRaw(), this::vState);
            part("PState", r.readRaw(), this::pState);
            part("DState", r.readRaw(), this::dState);
        }

        private void vState(CborReader r) {
            r.readArray(3, "VState");
            long dreps = r.readMap();
            int n = 0;
            for (long i = 0; r.hasNext(dreps, i); i++, n++) {
                CredentialKey credential = credential(r);
                r.readArray(4, "DRepState");
                long expiry = r.readUint();
                r.skip(); // anchor
                BigInteger deposit = r.readBigInteger();
                r.skip(); // delegators (the reverse index; the accounts carry the delegations)
                view.drep(new DRepState(credential, deposit, expiry));
                drepKeys.add(credential);
            }
            count("dreps", n);
            long committee = r.readMap();
            n = 0;
            for (long i = 0; r.hasNext(committee, i); i++, n++) {
                CredentialKey cold = credential(r);
                r.readArray(2, "CommitteeAuthorization");
                int kind = r.readInt();
                switch (kind) {
                    case 0 -> committeeAuth.put(cold, credential(r));
                    case 1 -> {
                        r.skip(); // StrictMaybe anchor
                        committeeAuth.put(cold, null);
                    }
                    default -> throw new IllegalArgumentException("CommitteeAuthorization: unknown tag " + kind);
                }
            }
            count("committee hot keys and resignations", n);
            long dormant = r.readUint();
            view.dormantEpochs(dormant);
            count("dormant epochs", dormant > 0 ? 1 : 0);
        }

        private void pState(CborReader r) {
            r.readArray(4, "PState");
            // The four maps of the vectors' ledger: registered params, future params, retiring, deposits. The later
            // layout starts with the VRF-key index ({vrf hash32 => count}); it is recognised only to fail closed.
            byte[] registered = r.readRaw();
            byte[] future = r.readRaw();
            byte[] retiring = r.readRaw();
            byte[] deposits = r.readRaw();
            Map<PoolId, PoolRegistration> params = poolParamsMap(registered, "registered pool params");
            Map<PoolId, PoolRegistration> futureParams = poolParamsMap(future, "future pool params");
            Map<PoolId, Long> retiringAt = new LinkedHashMap<>();
            CborReader rr = new CborReader(retiring);
            long size = rr.readMap();
            for (long i = 0; rr.hasNext(size, i); i++) {
                retiringAt.put(PoolId.of(rr.readBytes()), rr.readUint());
            }
            Map<PoolId, BigInteger> depositOf = new LinkedHashMap<>();
            CborReader dr = new CborReader(deposits);
            size = dr.readMap();
            for (long i = 0; dr.hasNext(size, i); i++) {
                depositOf.put(PoolId.of(dr.readBytes()), dr.readBigInteger());
            }
            for (Map.Entry<PoolId, PoolRegistration> pool : params.entrySet()) {
                BigInteger deposit = depositOf.get(pool.getKey());
                if (deposit == null) {
                    throw new IllegalArgumentException("pool " + pool.getKey() + " has no deposit entry");
                }
                view.pool(new PoolState(pool.getKey(), deposit, HexUtil.encodeHexString(pool.getValue().getVrfKeyHash()),
                        retiringAt.get(pool.getKey()), pool.getValue(), futureParams.get(pool.getKey())));
                poolKeys.add(pool.getKey());
            }
            for (PoolId id : futureParams.keySet()) {
                if (!params.containsKey(id)) {
                    throw new IllegalArgumentException("future params for unregistered pool " + id);
                }
            }
            for (PoolId id : retiringAt.keySet()) {
                if (!params.containsKey(id)) {
                    throw new IllegalArgumentException("retirement of unregistered pool " + id);
                }
            }
            count("pools", params.size());
            count("future pool params", futureParams.size());
            count("retiring pools", retiringAt.size());
        }

        private Map<PoolId, PoolRegistration> poolParamsMap(byte[] bytes, String what) {
            CborReader r = new CborReader(bytes);
            long size = r.readMap();
            Map<PoolId, PoolRegistration> result = new LinkedHashMap<>();
            for (long i = 0; r.hasNext(size, i); i++) {
                byte[] key = r.readBytes();
                if (key.length != 28) {
                    throw new IllegalArgumentException(what + ": a " + key.length + "-byte key (the later PState "
                            + "layout, which starts with the VRF-key index, is not supported)");
                }
                result.put(PoolId.of(key), poolParams(r.readRaw()));
            }
            return result;
        }

        /**
         * {@code PoolParams} is encoded as the nine fields of a {@code pool_registration} certificate
         * ({@code CBORGroup}); CCL decodes it as the certificate {@code [3, ...fields]}.
         */
        private PoolRegistration poolParams(byte[] raw) {
            CborReader r = new CborReader(raw);
            r.readArray(9, "PoolParams");
            int fieldsStart = r.position();
            byte[] cert = new byte[2 + raw.length - fieldsStart];
            cert[0] = (byte) 0x8a; // array(10)
            cert[1] = 0x03;        // pool_registration
            System.arraycopy(raw, fieldsStart, cert, 2, raw.length - fieldsStart);
            try {
                return PoolRegistration.deserialize(CborSerializationUtil.deserialize(cert));
            } catch (Exception e) {
                throw new IllegalArgumentException("CCL cannot decode PoolParams " + HexUtil.encodeHexString(raw), e);
            }
        }

        private void dState(CborReader r) {
            r.readArray(4, "DState");
            int major = r.peekMajor();
            int n = 0;
            if (major == 4) {
                // UMap: [{stake credential => UMElem}, {ptr => stake credential}]
                r.readArray(2, "UMap");
                long size = r.readMap();
                for (long i = 0; r.hasNext(size, i); i++) {
                    CredentialKey credential = credential(r);
                    r.readArray(4, "UMElem");
                    BigInteger reward = null;
                    BigInteger deposit = null;
                    if (r.readStrictMaybeHeader()) {
                        r.readArray(2, "RDPair");
                        reward = r.readBigInteger();
                        deposit = r.readBigInteger();
                    }
                    r.skip(); // pointers
                    PoolId pool = r.readStrictMaybeHeader() ? PoolId.of(r.readBytes()) : null;
                    DRepTarget drep = r.readStrictMaybeHeader() ? drep(r) : null;
                    if (reward != null) {
                        // An element without a reward/deposit pair is not a registration (pointer-only leftovers).
                        view.account(new AccountState(credential, deposit, reward, pool, drep));
                        accountKeys.add(credential);
                        n++;
                    }
                }
                r.skip(); // pointers
            } else if (major == 5) {
                // ConwayAccounts (cardano-ledger f649f975): {stake credential => [balance, deposit, pool / null, drep / null]}
                long size = r.readMap();
                for (long i = 0; r.hasNext(size, i); i++, n++) {
                    CredentialKey credential = credential(r);
                    r.readArray(4, "ConwayAccountState");
                    BigInteger balance = r.readBigInteger();
                    BigInteger deposit = r.readBigInteger();
                    PoolId pool = null;
                    if (r.peekNull()) {
                        r.readNull();
                    } else {
                        pool = PoolId.of(r.readBytes());
                    }
                    DRepTarget drep = null;
                    if (r.peekNull()) {
                        r.readNull();
                    } else {
                        drep = drep(r);
                    }
                    view.account(new AccountState(credential, deposit, balance, pool, drep));
                    accountKeys.add(credential);
                }
            } else {
                throw new IllegalArgumentException("DState accounts: unexpected major type " + major);
            }
            count("accounts", n);
            // future genesis delegations, genesis delegations, instantaneous rewards: not read by Conway rules
            r.skip();
            r.skip();
            r.skip();
        }

        // ------------------------------------------------------------------------------------ UTxO state

        private void utxoState(CborReader r) {
            r.readArray(6, "UTxOState");
            part("UTxO", r.readRaw(), this::utxo);
            r.skip(); // deposited (the total; the per-item deposits are read from the cert state)
            r.skip(); // fees
            part("governance state", r.readRaw(), this::govState);
            r.skip(); // instant stake
            r.skip(); // donation
        }

        private void utxo(CborReader r) {
            long size = r.readMap();
            int n = 0;
            for (long i = 0; r.hasNext(size, i); i++, n++) {
                if (r.peekMajor() == 2) {
                    throw new IllegalArgumentException("MemPack-encoded TxIn keys are not supported (the pinned "
                            + "vectors use the CBOR TxIn encoding)");
                }
                r.readArray(2, "TxIn");
                String txId = r.readBytesHex();
                int index = r.readInt();
                if (r.peekMajor() == 2) {
                    throw new IllegalArgumentException("MemPack-encoded TxOut values are not supported (the pinned "
                            + "vectors use the CBOR TxOut encoding)");
                }
                byte[] output = r.readRaw();
                TransactionOutput decoded;
                try {
                    decoded = TransactionOutput.deserialize(CborSerializationUtil.deserialize(output));
                } catch (Exception e) {
                    throw new IllegalArgumentException("CCL cannot decode TxOut " + HexUtil.encodeHexString(output), e);
                }
                Outpoint outpoint = Outpoints.of(txId, index);
                view.utxo(new UtxoEntry(outpoint, decoded, AmaruScenarioLoader.inlineDatumBytes(output)));
                utxoKeys.add(outpoint);
            }
            count("utxo", n);
        }

        // ------------------------------------------------------------------------------ governance state

        private void govState(CborReader r) {
            r.readArray(7, "ConwayGovState");
            part("proposals", r.readRaw(), this::proposals);
            part("committee", r.readRaw(), this::committee);
            part("constitution", r.readRaw(), this::constitution);
            part("current protocol parameters", r.readRaw(), pp -> params = protocolParams(pp));
            r.skip(); // previous protocol parameters
            byte[] future = r.readRaw();
            CborReader fr = new CborReader(future);
            fr.readArray();
            count("future protocol parameters", fr.readInt() != 0 ? 1 : 0);
            r.skip(); // DRep pulsing state: ratification input, not read by the transaction rules
        }

        private ProtocolParams protocolParams(CborReader r) {
            if (r.peekMajor() == 2) {
                String hash = r.readBytesHex();
                byte[] record = pparamsByHash.apply(hash).orElseThrow(() ->
                        new IllegalArgumentException("no protocol-parameter record for hash " + hash));
                return ConwayPParamsDecoder.decode(record, notes);
            }
            return ConwayPParamsDecoder.decode(r, notes);
        }

        private void proposals(CborReader r) {
            r.readArray(2, "Proposals");
            r.readArray(4, "proposal roots");
            GovActionId pparams = optionalGovActionId(r);
            GovActionId hardFork = optionalGovActionId(r);
            GovActionId committee = optionalGovActionId(r);
            GovActionId constitution = optionalGovActionId(r);
            view.enactedRoots(new EnactedRoots(pparams, hardFork, committee, constitution));
            count("enacted roots", (int) Stream.of(pparams, hardFork, committee, constitution)
                    .filter(Objects::nonNull).count());
            long size = r.readArray();
            for (long i = 0; r.hasNext(size, i); i++) {
                r.readArray(7, "GovActionState");
                GovActionId id = govActionId(r);
                r.skip(); // committee votes
                r.skip(); // DRep votes
                r.skip(); // stake pool votes
                byte[] procedure = r.readRaw();
                long proposedIn = r.readUint();
                long expiresAfter = r.readUint();
                proposals.add(new ProposalCandidate(id, procedure, proposedIn, expiresAfter));
            }
            count("proposals", proposals.size());
        }

        private void committee(CborReader r) {
            if (!r.readStrictMaybeHeader()) {
                return;
            }
            r.readArray(2, "Committee");
            long size = r.readMap();
            for (long i = 0; r.hasNext(size, i); i++) {
                committeeTerms.put(credential(r), r.readUint());
            }
            r.readRational(); // threshold: ratification only
            count("committee members", committeeTerms.size());
        }

        private void constitution(CborReader r) {
            r.readArray(2, "Constitution");
            r.skip(); // anchor
            if (r.peekNull()) {
                r.readNull();
            } else {
                view.guardrailScriptHash(r.readBytesHex());
                count("guardrail script", 1);
            }
        }

        /** Elected members (with their term) joined with the committee state (hot keys and resignations). */
        private void joinCommittee() {
            Map<CredentialKey, CommitteeMemberState> members = new LinkedHashMap<>();
            committeeTerms.forEach((cold, term) -> members.put(cold, new CommitteeMemberState(cold, null, false, term)));
            committeeAuth.forEach((cold, hot) -> {
                Long term = committeeTerms.get(cold);
                members.put(cold, new CommitteeMemberState(cold, hot, hot == null, term));
            });
            members.values().forEach(view::committeeMember);
        }

        private void joinProposals() {
            for (ProposalCandidate p : proposals) {
                try {
                    view.proposal(p.toState());
                } catch (RuntimeException e) {
                    unsupported.add("proposal " + p.id() + ": " + e.getMessage());
                }
            }
        }
    }

    /** A proposal read from the state; its procedure is decoded with CCL once the whole state has been read. */
    private record ProposalCandidate(GovActionId id, byte[] procedure, long proposedIn, long expiresAfter) {

        ProposalState toState() {
            CborReader r = new CborReader(procedure);
            r.readArray(4, "ProposalProcedure");
            r.skip(); // deposit
            r.skip(); // return address
            byte[] actionBytes = r.readRaw();
            ProposalProcedure decoded;
            try {
                decoded = ProposalProcedure.deserialize(CborSerializationUtil.deserialize(procedure));
            } catch (Exception e) {
                throw new IllegalArgumentException("CCL cannot decode the proposal procedure", e);
            }
            GovAction action = Objects.requireNonNull(decoded.getGovAction(), "gov action");
            Set<Integer> keys = ProposalParamUpdateKeys.fromGovAction(actionBytes);
            return new ProposalState(id, action.getType(), action, prevActionId(action), proposedIn, expiresAfter,
                    decoded.getDeposit(), decoded.getRewardAccount(), keys);
        }
    }

    /** The parent in the purpose's lineage, as {@code TxEffectsDeriver} records it for a new proposal. */
    static GovActionId prevActionId(GovAction action) {
        return GovActionId.of(switch (action) {
            case ParameterChangeAction a -> a.getPrevGovActionId();
            case HardForkInitiationAction a -> a.getPrevGovActionId();
            case NoConfidence a -> a.getPrevGovActionId();
            case UpdateCommittee a -> a.getPrevGovActionId();
            case NewConstitution a -> a.getPrevGovActionId();
            default -> null;
        });
    }

    // ------------------------------------------------------------------------------------ small items

    /** {@code credential = [0, hash28] / [1, hash28]}. */
    static CredentialKey credential(CborReader r) {
        r.readArray(2, "credential");
        CredentialType type = CredentialType.fromTag(r.readInt());
        byte[] hash = r.readBytes();
        if (hash.length != 28) {
            throw new IllegalArgumentException("credential hash of " + hash.length + " bytes");
        }
        return new CredentialKey(type, HexUtil.encodeHexString(hash));
    }

    /** {@code DRep = [0, keyhash] / [1, scripthash] / [2] / [3]}. */
    static DRepTarget drep(CborReader r) {
        long length = r.readArray();
        int kind = r.readInt();
        return switch (kind) {
            case 0, 1 -> {
                if (length != 2) {
                    throw new IllegalArgumentException("DRep credential: expected 2 fields");
                }
                byte[] hash = r.readBytes();
                yield DRepTarget.credential(new CredentialKey(CredentialType.fromTag(kind),
                        HexUtil.encodeHexString(hash)));
            }
            case 2 -> DRepTarget.ALWAYS_ABSTAIN;
            case 3 -> DRepTarget.ALWAYS_NO_CONFIDENCE;
            default -> throw new IllegalArgumentException("unknown DRep kind " + kind);
        };
    }

    /** {@code gov_action_id = [transaction_id, index]}. */
    static GovActionId govActionId(CborReader r) {
        r.readArray(2, "GovActionId");
        return new GovActionId(r.readBytesHex(), r.readInt());
    }

    /** A StrictMaybe {@code GovPurposeId}: {@code []} or {@code [gov_action_id]}. */
    static GovActionId optionalGovActionId(CborReader r) {
        return r.readStrictMaybeHeader() ? govActionId(r) : null;
    }
}
