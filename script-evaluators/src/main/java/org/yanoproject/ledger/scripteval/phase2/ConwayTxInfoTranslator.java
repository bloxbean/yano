package org.yanoproject.ledger.scripteval.phase2;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;

import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.conway.tx.AddressBytes;
import org.yanoproject.ledger.rules.conway.tx.CborReader;
import org.yanoproject.ledger.rules.conway.tx.CborSlice;
import org.yanoproject.ledger.rules.conway.tx.Hashes;
import org.yanoproject.ledger.rules.conway.tx.LedgerValue;
import org.yanoproject.ledger.rules.conway.tx.RawCertificate;
import org.yanoproject.ledger.rules.conway.tx.RawCredential;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.RawProposal;
import org.yanoproject.ledger.rules.conway.tx.RawRedeemer;
import org.yanoproject.ledger.rules.conway.tx.RawScript;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.RawVoter;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.phase2.ScriptCollection;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import static org.yanoproject.ledger.scripteval.phase2.DataTerms.bool;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.bytes;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.constr;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.credential;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.integer;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.just;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.list;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.map;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.maybe;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.nothing;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.pair;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.stakingHash;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.tuple;

/**
 * Builds the Plutus V1, V2 and V3 script contexts of a Conway transaction as cardano-ledger does
 * ({@code f649f975}: {@code Conway/TxInfo.hs} {@code EraPlutusTxInfo 'PlutusV1/V2/V3 ConwayEra}, with the Alonzo and
 * Babbage helpers it calls), encoded with plutus-ledger-api 1.65's {@code ToData} instances.
 *
 * <p>Everything hashed or read byte-exactly comes from the transaction's original bytes ({@link RawTransaction}): the
 * transaction id, witness datums and their hashes, redeemer data, inline datums of the transaction's outputs, and
 * Word64 coins and quantities as {@link BigInteger}. Maps are in Haskell's key order (inputs, signatories, datums,
 * withdrawals, votes, the redeemer map, values).</p>
 *
 * <p>It only builds: the translation failures Haskell reports ({@code ContextError}) are checked first, engine-neutrally,
 * by {@link ScriptCollection#collectErrors}, so reaching one here is an {@link IllegalStateException} (the engine fails
 * closed).</p>
 *
 * <p>Not thread-safe: one instance per transaction evaluation.</p>
 */
final class ConwayTxInfoTranslator {

    /**
     * A transaction output as the context reads it.
     *
     * @param address     the address bytes
     * @param value       the value (Word64 coin and quantities)
     * @param datumHash   the datum hash, or null
     * @param inlineDatum the inline datum, or null
     * @param scriptRef   the reference script, or null
     * @param source      Haskell's {@code TxOutSource}, for error messages
     */
    record Output(byte[] address, LedgerValue value, byte[] datumHash, PlutusData inlineDatum, RawScript scriptRef,
                  String source) {
    }

    /**
     * A redeemer with its data.
     *
     * @param tag   0 spend, 1 mint, 2 cert, 3 reward, 4 voting, 5 proposing
     * @param index the index within its purpose
     * @param data  the redeemer data
     * @param mem   declared memory units
     * @param steps declared CPU steps
     */
    record Redeemer(int tag, long index, PlutusData data, BigInteger mem, BigInteger steps) {
    }

    private static final int SET_TAG = 258;
    private static final int BYRON_TYPE = 8;

    private final RawTransaction raw;
    private final byte[] txCbor;
    private final Map<Outpoint, UtxoEntry> resolved;
    private final int protocolMajor;
    private final SlotConfig slotConfig;

    private final Map<TxInRef, Optional<Output>> resolvedOutputs = new HashMap<>();
    private final Map<Integer, PlutusData> txInfos = new HashMap<>();
    private List<Output> outputs;
    private TreeMap<String, PlutusData> witnessDatums;
    private TreeMap<Long, Redeemer> redeemers;
    private List<PlutusData> proposals;

    /**
     * @param raw           the transaction read from its original bytes
     * @param resolved      the spending, reference and collateral inputs, resolved
     * @param protocolMajor the ledger's protocol major version
     * @param slotConfig    slot-to-POSIX-time conversion
     */
    ConwayTxInfoTranslator(RawTransaction raw, Map<Outpoint, UtxoEntry> resolved, int protocolMajor,
                           SlotConfig slotConfig) {
        this.raw = Objects.requireNonNull(raw, "raw");
        this.txCbor = raw.txCbor();
        this.resolved = Objects.requireNonNull(resolved, "resolved");
        this.protocolMajor = protocolMajor;
        this.slotConfig = Objects.requireNonNull(slotConfig, "slotConfig");
    }

    RawTransaction raw() {
        return raw;
    }

    // ------------------------------------------------------------------ public surface

    /**
     * The script arguments ({@code toPlutusArgs}): V1/V2 {@code [datum,] redeemer, ScriptContext}; V3
     * {@code [ScriptContext]}.
     */
    List<PlutusData> arguments(int language, Redeemer redeemer) {
        PlutusData txInfo = txInfo(language);
        PlutusData datum = redeemer.tag() == 0 ? spendingDatum(redeemer.index()) : null;
        if (language == 3) {
            PlutusData info = scriptInfoV3(redeemer.tag(), redeemer.index(), datum);
            return List.of(constr(0, txInfo, redeemer.data(), info));
        }
        PlutusData context = constr(0, txInfo, purposeV1V2(redeemer.tag(), redeemer.index()));
        return datum != null ? List.of(datum, redeemer.data(), context) : List.of(redeemer.data(), context);
    }

    /** @return the redeemer for {@code (tag, index)}, as Haskell's {@code Redeemers} map holds it */
    Optional<Redeemer> redeemer(int tag, long index) {
        return Optional.ofNullable(redeemers().get(RawRedeemer.key(tag, index)));
    }

    /** @return the resolved output of an input, or empty when it is not in the UTxO */
    Optional<Output> resolvedOutput(TxInRef in) {
        return resolvedOutputs.computeIfAbsent(in, ref -> {
            UtxoEntry entry = resolved.get(Outpoints.normalize(ref.outpoint()));
            return entry == null ? Optional.empty() : Optional.of(fromUtxo(entry, "TxOutFromInput (" + ref + ")"));
        });
    }

    // ------------------------------------------------------------------ TxInfo per language

    /** @return {@code language}'s {@code TxInfo}, built once */
    PlutusData txInfo(int language) {
        return txInfos.computeIfAbsent(language, l -> switch (l) {
            case 1 -> txInfoV1();
            case 2 -> txInfoV2();
            case 3 -> txInfoV3();
            default -> throw new IllegalArgumentException("not a Plutus language: " + l);
        });
    }

    private PlutusData txInfoV1() {
        guardConwayFeaturesForV1V2();
        PlutusData range = validRange();
        List<PlutusData> inputs = new ArrayList<>();
        for (TxInRef in : raw.inputSet()) {
            inputs.add(constr(0, txOutRef(in, false), txOutV1(lookup(in))));
        }
        for (TxInRef in : raw.referenceSet()) {
            txOutV1(lookup(in)); // checked, not included (Conway/TxInfo.hs:411)
        }
        List<PlutusData> outs = new ArrayList<>();
        for (Output out : outputs()) {
            outs.add(txOutV1(out));
        }
        List<PlutusData> certs = certificatesV1V2();
        List<PlutusData> withdrawals = new ArrayList<>();
        for (RawTransaction.Withdrawal w : withdrawalsInPlutusV1Order()) {
            withdrawals.add(tuple(stakingHash(accountCredential(w.rewardAccount())), integer(w.amount())));
        }
        List<PlutusData> datums = new ArrayList<>();
        witnessDatums().forEach((hash, datum) -> datums.add(tuple(bytes(HexUtil.decodeHexString(hash)), datum)));
        return constr(0,
                list(inputs),
                list(outs),
                coinValue(raw.fee()),
                mintValueV1V2(),
                list(certs),
                list(withdrawals),
                range,
                list(signatories()),
                list(datums),
                constr(0, bytes(raw.txId())));
    }

    private PlutusData txInfoV2() {
        guardConwayFeaturesForV1V2();
        PlutusData range = validRange();
        List<PlutusData> inputs = new ArrayList<>();
        for (TxInRef in : raw.inputSet()) {
            inputs.add(constr(0, txOutRef(in, false), txOutV2(lookup(in))));
        }
        List<PlutusData> refInputs = new ArrayList<>();
        for (TxInRef in : raw.referenceSet()) {
            refInputs.add(constr(0, txOutRef(in, false), txOutV2(lookup(in))));
        }
        List<PlutusData> outs = new ArrayList<>();
        for (Output out : outputs()) {
            outs.add(txOutV2(out));
        }
        List<PlutusData> certs = certificatesV1V2();
        List<PlutusData.Pair> redeemerMap = new ArrayList<>();
        for (Redeemer r : redeemers().values()) {
            redeemerMap.add(pair(purposeV1V2(r.tag(), r.index()), r.data()));
        }
        List<PlutusData.Pair> withdrawals = new ArrayList<>();
        for (RawTransaction.Withdrawal w : withdrawalsInPlutusV1Order()) {
            withdrawals.add(pair(stakingHash(accountCredential(w.rewardAccount())), integer(w.amount())));
        }
        return constr(0,
                list(inputs),
                list(refInputs),
                list(outs),
                coinValue(raw.fee()),
                mintValueV1V2(),
                list(certs),
                map(withdrawals),
                range,
                list(signatories()),
                map(redeemerMap),
                map(datumMap()),
                constr(0, bytes(raw.txId())));
    }

    private PlutusData txInfoV3() {
        PlutusData range = validRange();
        List<PlutusData> inputs = new ArrayList<>();
        for (TxInRef in : raw.inputSet()) {
            inputs.add(constr(0, txOutRef(in, true), txOutV2(lookup(in))));
        }
        List<PlutusData> refInputs = new ArrayList<>();
        for (TxInRef in : raw.referenceSet()) {
            refInputs.add(constr(0, txOutRef(in, true), txOutV2(lookup(in))));
        }
        if (protocolMajor >= 11) {
            List<String> common = raw.referenceSet().stream().filter(raw.inputSet()::contains)
                    .map(TxInRef::toString).toList();
            if (!common.isEmpty()) {
                throw untranslatable("ReferenceInputsNotDisjointFromInputs " + common);
            }
        }
        List<PlutusData> outs = new ArrayList<>();
        for (Output out : outputs()) {
            outs.add(txOutV2(out));
        }
        List<PlutusData> certs = new ArrayList<>();
        for (RawCertificate cert : raw.certificates()) {
            certs.add(txCertV3(cert));
        }
        List<PlutusData.Pair> redeemerMap = new ArrayList<>();
        for (Redeemer r : redeemers().values()) {
            redeemerMap.add(pair(purposeV3(r.tag(), r.index()), r.data()));
        }
        List<PlutusData.Pair> withdrawals = new ArrayList<>();
        for (RawTransaction.Withdrawal w : withdrawalsInLedgerOrder()) {
            withdrawals.add(pair(accountCredential(w.rewardAccount()), integer(w.amount())));
        }
        List<PlutusData.Pair> voteMap = new ArrayList<>();
        raw.votes().forEach((voter, actions) -> {
            List<PlutusData.Pair> inner = new ArrayList<>();
            actions.forEach((id, vote) -> inner.add(pair(actionId(id), constr(vote))));
            voteMap.add(pair(voterV3(voter), map(inner)));
        });
        BigInteger donation = raw.donation();
        return constr(0,
                list(inputs),
                list(refInputs),
                list(outs),
                integer(raw.fee()),
                multiAssetMap(raw.mint()),
                list(certs),
                map(withdrawals),
                range,
                list(signatories()),
                map(redeemerMap),
                map(datumMap()),
                bytes(raw.txId()),
                map(voteMap),
                list(proposals()),
                maybe(raw.currentTreasuryValue() != null ? integer(raw.currentTreasuryValue()) : null),
                maybe(donation != null && donation.signum() != 0 ? integer(donation) : null));
    }

    /** {@code guardConwayFeaturesForPlutusV1V2} (Conway/TxInfo.hs:352-381). */
    private void guardConwayFeaturesForV1V2() {
        if (!raw.voters().isEmpty()) {
            throw untranslatable("VotingProceduresFieldNotSupported");
        }
        if (raw.proposalCount() > 0) {
            throw untranslatable("ProposalProceduresFieldNotSupported");
        }
        if (raw.donation() != null && raw.donation().signum() != 0) {
            throw untranslatable("TreasuryDonationFieldNotSupported " + raw.donation());
        }
        if (raw.currentTreasuryValue() != null) {
            throw untranslatable("CurrentTreasuryFieldNotSupported " + raw.currentTreasuryValue());
        }
    }

    // ------------------------------------------------------------------ validity interval

    /** Conway's {@code transValidityInterval} (Conway/TxInfo.hs), every language. */
    private PlutusData validRange() {
        BigInteger lower = raw.validityStart();
        BigInteger upper = raw.ttl();
        PlutusData lowerBound = lower == null
                ? constr(0, constr(0), bool(true))
                : constr(0, constr(1, integer(posixTime(lower))), bool(true));
        PlutusData upperBound = upper == null
                ? constr(0, constr(2), bool(true))
                : constr(0, constr(1, integer(posixTime(upper))), bool(false));
        return constr(0, lowerBound, upperBound);
    }

    /** {@code slotToPOSIXTime}, milliseconds; the forecast horizon is checked by the collection checks. */
    private BigInteger posixTime(BigInteger slot) {
        return BigInteger.valueOf(slotConfig.getZeroTime())
                .add(slot.subtract(BigInteger.valueOf(slotConfig.getZeroSlot()))
                        .multiply(BigInteger.valueOf(slotConfig.getSlotLength())));
    }

    // ------------------------------------------------------------------ outputs

    private Output lookup(TxInRef in) {
        return resolvedOutput(in).orElseThrow(() -> untranslatable("TranslationLogicMissingInput " + in));
    }

    /** Conway's {@code transTxOutV1} (Conway/TxInfo.hs:306-320): an inline datum, then a Byron address, fail. */
    private PlutusData txOutV1(Output out) {
        if (out.inlineDatum() != null) {
            throw untranslatable("InlineDatumsNotSupported (" + out.source() + ")");
        }
        PlutusData address = address(out);
        return constr(0, address, value(out.value()), maybe(out.datumHash() != null ? bytes(out.datumHash()) : null));
    }

    /** Babbage's {@code transTxOutV2} (Babbage/TxInfo.hs), V2 and V3. */
    private PlutusData txOutV2(Output out) {
        PlutusData datum;
        if (out.inlineDatum() != null) {
            datum = constr(2, out.inlineDatum());
        } else if (out.datumHash() != null) {
            datum = constr(1, bytes(out.datumHash()));
        } else {
            datum = constr(0);
        }
        PlutusData address = address(out);
        return constr(0, address, value(out.value()), datum,
                maybe(out.scriptRef() != null ? bytes(out.scriptRef().hash()) : null));
    }

    /** {@code transAddr}: a Shelley address; a Byron address is {@code ByronTxOutInContext}. */
    private static PlutusData address(Output out) {
        byte[] address = out.address();
        int type = (address[0] & 0xff) >>> 4;
        if (type == BYRON_TYPE || type > 7 || address.length < 29) {
            throw untranslatable("ByronTxOutInContext (" + out.source() + ")");
        }
        PlutusData payment = credential((type & 1) != 0, Arrays.copyOfRange(address, 1, 29));
        PlutusData staking;
        if (type <= 3) {
            staking = just(stakingHash(credential((type & 2) != 0, Arrays.copyOfRange(address, 29, 57))));
        } else if (type <= 5) {
            staking = just(pointer(address));
        } else {
            staking = nothing();
        }
        return constr(0, payment, staking);
    }

    /**
     * {@code StakingPtr slot txIx certIx} of a pointer address, normalised as the ledger stores pointers
     * ({@code mkPtrNormalized}, Credential.hs:240-246: a slot above Word32 or an index above Word16 gives
     * {@code Ptr 0 0 0}).
     */
    private static PlutusData pointer(byte[] address) {
        int[] cursor = {29};
        BigInteger slot = varNat(address, cursor);
        BigInteger txIx = varNat(address, cursor);
        BigInteger certIx = varNat(address, cursor);
        if (slot.bitLength() > 32 || txIx.bitLength() > 16 || certIx.bitLength() > 16) {
            slot = BigInteger.ZERO;
            txIx = BigInteger.ZERO;
            certIx = BigInteger.ZERO;
        }
        return constr(1, integer(slot), integer(txIx), integer(certIx));
    }

    private static BigInteger varNat(byte[] bytes, int[] cursor) {
        BigInteger value = BigInteger.ZERO;
        while (true) {
            if (cursor[0] >= bytes.length) {
                throw new IllegalStateException("truncated pointer address");
            }
            int b = bytes[cursor[0]++] & 0xff;
            value = value.shiftLeft(7).or(BigInteger.valueOf(b & 0x7f));
            if ((b & 0x80) == 0) {
                return value;
            }
        }
    }

    /** {@code transValue}: the lovelace entry first (always, even 0), then the policies and names in byte order. */
    private static PlutusData value(LedgerValue value) {
        List<PlutusData.Pair> entries = new ArrayList<>();
        entries.add(pair(bytes(new byte[0]), map(List.of(pair(bytes(new byte[0]), integer(value.coin()))))));
        entries.addAll(multiAssetEntries(value.assets()));
        return map(entries);
    }

    private static PlutusData coinValue(BigInteger coin) {
        return map(List.of(pair(bytes(new byte[0]), map(List.of(pair(bytes(new byte[0]), integer(coin)))))));
    }

    /** V1/V2 {@code transMintValue}: a zero lovelace entry, then the minted assets ("hysterical raisins"). */
    private PlutusData mintValueV1V2() {
        List<PlutusData.Pair> entries = new ArrayList<>();
        entries.add(pair(bytes(new byte[0]), map(List.of(pair(bytes(new byte[0]), integer(0))))));
        entries.addAll(multiAssetEntries(raw.mint()));
        return map(entries);
    }

    /** V3 {@code transMintValue} and {@code transMultiAsset}: the assets only. */
    private static PlutusData multiAssetMap(Map<String, Map<String, BigInteger>> assets) {
        return map(multiAssetEntries(assets));
    }

    private static List<PlutusData.Pair> multiAssetEntries(Map<String, Map<String, BigInteger>> assets) {
        TreeMap<String, Map<String, BigInteger>> sorted = new TreeMap<>();
        assets.forEach((policy, names) -> sorted.put(policy.toLowerCase(), names));
        List<PlutusData.Pair> entries = new ArrayList<>();
        sorted.forEach((policy, names) -> {
            TreeMap<String, BigInteger> sortedNames = new TreeMap<>();
            names.forEach((name, quantity) -> sortedNames.put(name.toLowerCase(), quantity));
            List<PlutusData.Pair> inner = new ArrayList<>();
            sortedNames.forEach((name, quantity) -> inner.add(pair(bytes(HexUtil.decodeHexString(name)),
                    integer(quantity))));
            entries.add(pair(bytes(HexUtil.decodeHexString(policy)), map(inner)));
        });
        return entries;
    }

    private static PlutusData txOutRef(TxInRef in, boolean v3) {
        PlutusData txId = bytes(HexUtil.decodeHexString(in.txIdHex()));
        return constr(0, v3 ? txId : constr(0, txId), integer(in.index()));
    }

    /** The transaction's outputs, from the body. */
    private List<Output> outputs() {
        if (outputs == null) {
            List<Output> list = new ArrayList<>();
            for (RawOutput out : raw.outputs()) {
                byte[] inline = out.inlineDatum();
                list.add(new Output(out.address(), out.value(), out.datumHash(),
                        inline != null ? PlutusDataCborDecoder.decode(inline) : null, out.scriptRef(),
                        "TxOutFromOutput (TxIx " + out.index() + ")"));
            }
            outputs = list;
        }
        return outputs;
    }

    private static Output fromUtxo(UtxoEntry entry, String source) {
        TransactionOutput out = entry.output();
        byte[] address = AddressBytes.fromCcl(out.getAddress());
        LedgerValue value = LedgerValue.of(out.getValue());
        PlutusData inline = null;
        byte[] stored = entry.inlineDatumCbor();
        if (stored != null) {
            inline = PlutusDataCborDecoder.decode(stored);
        } else if (out.getInlineDatum() != null) {
            try {
                inline = PlutusDataCborDecoder.decode(CborSerializationUtil.serialize(out.getInlineDatum().serialize()));
            } catch (Exception e) {
                throw new IllegalStateException("cannot re-encode the inline datum of " + source, e);
            }
        }
        RawScript scriptRef = out.getScriptRef() != null ? RawScript.fromScriptRef(out.getScriptRef()) : null;
        return new Output(address, value, out.getDatumHash(), inline, scriptRef, source);
    }

    // ------------------------------------------------------------------ witnesses

    /** {@code transTxBodyReqSignerHashes}: the required signers as a set, in hash order. */
    private List<PlutusData> signatories() {
        TreeMap<String, byte[]> sorted = new TreeMap<>();
        for (byte[] signer : raw.requiredSigners()) {
            sorted.put(HexUtil.encodeHexString(signer), signer);
        }
        List<PlutusData> list = new ArrayList<>();
        sorted.values().forEach(s -> list.add(bytes(s)));
        return list;
    }

    private List<PlutusData.Pair> datumMap() {
        List<PlutusData.Pair> entries = new ArrayList<>();
        witnessDatums().forEach((hash, datum) -> entries.add(pair(bytes(HexUtil.decodeHexString(hash)), datum)));
        return entries;
    }

    /** {@code TxDats}: the witness datums keyed by the hash of their original bytes, in hash order. */
    TreeMap<String, PlutusData> witnessDatums() {
        if (witnessDatums == null) {
            TreeMap<String, PlutusData> datums = new TreeMap<>();
            CborSlice field = raw.witnessFields().get(RawTransaction.WITNESS_DATUMS);
            if (field != null) {
                CborReader reader = new CborReader(txCbor, field);
                reader.skipTag(SET_TAG);
                long count = reader.readArrayHeader();
                for (long i = 0; reader.hasNext(count, i); i++) {
                    byte[] datum = reader.copy(reader.readItem());
                    datums.putIfAbsent(HexUtil.encodeHexString(Hashes.blake2b256(datum)),
                            PlutusDataCborDecoder.decode(datum));
                }
            }
            witnessDatums = datums;
        }
        return witnessDatums;
    }

    /**
     * {@code getSpendingDatum} (Babbage): the spent output's inline datum, or the witness datum of its hash, or none.
     */
    private PlutusData spendingDatum(long index) {
        List<TxInRef> inputs = new ArrayList<>(raw.inputSet());
        if (index >= inputs.size()) {
            return null;
        }
        Optional<Output> out = resolvedOutput(inputs.get((int) index));
        if (out.isEmpty()) {
            return null;
        }
        if (out.get().inlineDatum() != null) {
            return out.get().inlineDatum();
        }
        byte[] hash = out.get().datumHash();
        return hash != null ? witnessDatums().get(HexUtil.encodeHexString(hash)) : null;
    }

    /** The redeemers as Haskell's {@code Redeemers} map holds them ({@link RawTransaction#redeemers()}), with their data. */
    TreeMap<Long, Redeemer> redeemers() {
        if (redeemers == null) {
            TreeMap<Long, Redeemer> byKey = new TreeMap<>();
            for (RawRedeemer r : raw.redeemers()) {
                byKey.put(r.key(), new Redeemer(r.tag(), r.index(),
                        PlutusDataCborDecoder.decode(r.data().copy(txCbor)), r.mem(), r.steps()));
            }
            redeemers = byKey;
        }
        return redeemers;
    }

    /** Consumes the break of an indefinite container after {@code read} elements. */
    private static void endOf(CborReader reader, long length, long read) {
        if (length == CborReader.INDEFINITE) {
            reader.hasNext(length, read);
        }
    }

    // ------------------------------------------------------------------ purposes

    /** {@code transPlutusPurposeV1V2} over {@code redeemerPointerInverse}. */
    PlutusData purposeV1V2(int tag, long index) {
        return switch (tag) {
            case 0 -> constr(1, txOutRef(pointee(sortedInputs(), tag, index), false));
            case 1 -> constr(0, bytes(HexUtil.decodeHexString(pointee(sortedPolicies(), tag, index))));
            case 2 -> constr(3, dCert(pointee(raw.certificates(), tag, index)));
            case 3 -> constr(2, stakingHash(accountCredential(
                    pointee(withdrawalsInLedgerOrder(), tag, index).rewardAccount())));
            case 4, 5 -> throw untranslatable("PlutusPurposeNotSupported " + ScriptCollection.purposeName(tag) + "[" + index + "]");
            default -> throw new IllegalArgumentException("redeemer tag " + tag);
        };
    }

    /** {@code transPlutusPurposeV3} over {@code redeemerPointerInverse}. */
    PlutusData purposeV3(int tag, long index) {
        return switch (tag) {
            case 0 -> constr(1, txOutRef(pointee(sortedInputs(), tag, index), true));
            case 1 -> constr(0, bytes(HexUtil.decodeHexString(pointee(sortedPolicies(), tag, index))));
            case 2 -> constr(3, integer(index), txCertV3(pointee(raw.certificates(), tag, index)));
            case 3 -> constr(2, accountCredential(pointee(withdrawalsInLedgerOrder(), tag, index).rewardAccount()));
            case 4 -> constr(4, voterV3(pointee(raw.voters(), tag, index)));
            case 5 -> constr(5, integer(index), pointee(proposals(), tag, index));
            default -> throw new IllegalArgumentException("redeemer tag " + tag);
        };
    }

    /** {@code scriptPurposeToScriptInfo}: the V3 {@code ScriptInfo}, the spending one with the datum. */
    private PlutusData scriptInfoV3(int tag, long index, PlutusData datum) {
        PlutusData purpose = purposeV3(tag, index);
        List<PlutusData> fields = ((PlutusData.ConstrData) purpose).fields();
        if (tag == 0) {
            return constr(1, fields.get(0), maybe(datum));
        }
        return new PlutusData.ConstrData(((PlutusData.ConstrData) purpose).tag(), fields);
    }

    private static <T> T pointee(List<T> items, int tag, long index) {
        if (index < 0 || index >= items.size()) {
            throw untranslatable("RedeemerPointerPointsToNothing " + ScriptCollection.purposeName(tag) + "[" + index + "]");
        }
        return items.get((int) index);
    }

    private List<TxInRef> sortedInputs() {
        return new ArrayList<>(raw.inputSet());
    }

    private List<String> sortedPolicies() {
        List<String> policies = new ArrayList<>();
        raw.mint().keySet().forEach(p -> policies.add(p.toLowerCase()));
        policies.sort(Comparator.naturalOrder());
        return policies;
    }

    /** The withdrawals in Haskell's {@code Map AccountAddress} order ({@link RawProposal#ACCOUNT_ORDER}). */
    private List<RawTransaction.Withdrawal> withdrawalsInLedgerOrder() {
        List<RawTransaction.Withdrawal> sorted = new ArrayList<>(raw.withdrawals());
        sorted.sort(Comparator.comparing(RawTransaction.Withdrawal::rewardAccount, RawProposal.ACCOUNT_ORDER));
        return sorted;
    }

    /**
     * V1/V2 withdrawals ({@code transWithdrawals}): a Haskell {@code Map PV1.StakingCredential Integer}, so in
     * plutus-ledger-api's {@code Ord Credential}: {@code PubKeyCredential} before {@code ScriptCredential}, then hash.
     */
    private List<RawTransaction.Withdrawal> withdrawalsInPlutusV1Order() {
        List<RawTransaction.Withdrawal> sorted = new ArrayList<>(raw.withdrawals());
        sorted.sort(Comparator.comparingInt((RawTransaction.Withdrawal w) -> (w.rewardAccount()[0] & 0x10) != 0 ? 1 : 0)
                .thenComparing(w -> Arrays.copyOfRange(w.rewardAccount(), 1, 29), Arrays::compareUnsigned));
        return sorted;
    }

    private static PlutusData accountCredential(byte[] account) {
        return credential((account[0] & 0x10) != 0, Arrays.copyOfRange(account, 1, 29));
    }

    private static PlutusData credentialOf(RawCredential credential) {
        return credential(credential.script(), credential.hash());
    }

    // ------------------------------------------------------------------ certificates

    private List<PlutusData> certificatesV1V2() {
        List<PlutusData> certs = new ArrayList<>();
        for (RawCertificate cert : raw.certificates()) {
            certs.add(dCert(cert));
        }
        return certs;
    }

    /** {@code transTxCertV1V2} (Conway/TxInfo.hs:383-397): Shelley certificates and deposit-less reg/unreg. */
    private static PlutusData dCert(RawCertificate cert) {
        return switch (cert.tag()) {
            case RawCertificate.STAKE_REGISTRATION, RawCertificate.REG ->
                    constr(0, stakingHash(credentialOf(cert.credential())));
            case RawCertificate.STAKE_DEREGISTRATION, RawCertificate.UNREG ->
                    constr(1, stakingHash(credentialOf(cert.credential())));
            case RawCertificate.STAKE_DELEGATION -> constr(2, stakingHash(credentialOf(cert.credential())),
                    bytes(cert.delegatee().pool()));
            case RawCertificate.POOL_REGISTRATION -> constr(3, bytes(cert.poolId()), bytes(cert.pool().vrfKeyHash()));
            case RawCertificate.POOL_RETIREMENT -> constr(4, bytes(cert.poolId()), integer(cert.epoch()));
            default -> throw untranslatable("CertificateNotSupported " + cert);
        };
    }

    /** {@code transTxCert} (Conway/TxInfo.hs:560-605), with the bootstrap-phase deposits of reg/unreg. */
    private PlutusData txCertV3(RawCertificate cert) {
        boolean bootstrap = ScriptCollection.bootstrapPhase(protocolMajor);
        return switch (cert.tag()) {
            case RawCertificate.STAKE_REGISTRATION -> constr(0, credentialOf(cert.credential()), nothing());
            case RawCertificate.STAKE_DEREGISTRATION -> constr(1, credentialOf(cert.credential()), nothing());
            case RawCertificate.REG -> constr(0, credentialOf(cert.credential()),
                    bootstrap ? nothing() : just(integer(cert.coin())));
            case RawCertificate.UNREG -> constr(1, credentialOf(cert.credential()),
                    bootstrap ? nothing() : just(integer(cert.coin())));
            case RawCertificate.STAKE_DELEGATION, RawCertificate.VOTE_DELEG, RawCertificate.STAKE_VOTE_DELEG ->
                    constr(2, credentialOf(cert.credential()), delegatee(cert.delegatee()));
            case RawCertificate.STAKE_REG_DELEG, RawCertificate.VOTE_REG_DELEG, RawCertificate.STAKE_VOTE_REG_DELEG ->
                    constr(3, credentialOf(cert.credential()), delegatee(cert.delegatee()), integer(cert.coin()));
            case RawCertificate.REG_DREP -> constr(4, credentialOf(cert.credential()), integer(cert.coin()));
            case RawCertificate.UPDATE_DREP -> constr(5, credentialOf(cert.credential()));
            case RawCertificate.UNREG_DREP -> constr(6, credentialOf(cert.credential()), integer(cert.coin()));
            case RawCertificate.POOL_REGISTRATION -> constr(7, bytes(cert.poolId()), bytes(cert.pool().vrfKeyHash()));
            case RawCertificate.POOL_RETIREMENT -> constr(8, bytes(cert.poolId()), integer(cert.epoch()));
            case RawCertificate.AUTH_COMMITTEE_HOT -> constr(9, credentialOf(cert.credential()), credentialOf(cert.hot()));
            case RawCertificate.RESIGN_COMMITTEE_COLD -> constr(10, credentialOf(cert.credential()));
            default -> throw new IllegalStateException("not a Conway certificate: " + cert);
        };
    }

    private static PlutusData delegatee(RawCertificate.Delegatee delegatee) {
        if (delegatee.pool() != null && delegatee.drep() != null) {
            return constr(2, bytes(delegatee.pool()), drep(delegatee.drep()));
        }
        return delegatee.pool() != null ? constr(0, bytes(delegatee.pool())) : constr(1, drep(delegatee.drep()));
    }

    private static PlutusData drep(RawCertificate.DRep drep) {
        return switch (drep.kind()) {
            case 0, 1 -> constr(0, credentialOf(drep.credential()));
            case 2 -> constr(1);
            default -> constr(2);
        };
    }

    // ------------------------------------------------------------------ governance

    private static PlutusData voterV3(RawVoter voter) {
        return switch (voter.tag()) {
            case 0, 1 -> constr(0, DataTerms.credential(voter.isScript(), voter.hash()));
            case 2, 3 -> constr(1, DataTerms.credential(voter.isScript(), voter.hash()));
            default -> constr(2, bytes(voter.hash()));
        };
    }

    private static PlutusData actionId(GovActionId id) {
        return constr(0, bytes(HexUtil.decodeHexString(id.txHashHex())), integer(id.index()));
    }

    private static PlutusData maybeActionId(GovActionId id) {
        return maybe(id != null ? actionId(id) : null);
    }

    private static PlutusData maybeHash(byte[] hash) {
        return maybe(hash != null ? bytes(hash) : null);
    }

    /** {@code transProposal} of every proposal procedure ({@link RawTransaction#proposals()}), in order. */
    private List<PlutusData> proposals() {
        if (proposals == null) {
            List<PlutusData> list = new ArrayList<>();
            for (RawProposal proposal : raw.proposals()) {
                list.add(constr(0, integer(proposal.deposit()), accountCredential(proposal.returnAccount()),
                        govAction(proposal)));
            }
            proposals = list;
        }
        return proposals;
    }

    /** {@code transGovAction} (Conway/TxInfo.hs:695-730). */
    private PlutusData govAction(RawProposal proposal) {
        PlutusData prev = maybeActionId(proposal.prevActionId());
        return switch (proposal.actionTag()) {
            case RawProposal.PARAMETER_CHANGE -> constr(0, prev,
                    changedParameters(new CborReader(txCbor, proposal.paramUpdateSlice())),
                    maybeHash(proposal.policyHash()));
            case RawProposal.HARD_FORK_INITIATION -> constr(1, prev, constr(0,
                    integer(proposal.protocolVersion().major()), integer(proposal.protocolVersion().minor())));
            case RawProposal.TREASURY_WITHDRAWALS -> {
                // RawProposal keeps them in Map AccountAddress order.
                List<PlutusData.Pair> entries = new ArrayList<>();
                proposal.withdrawals().forEach(w -> entries.add(pair(accountCredential(w.account()),
                        integer(w.amount()))));
                yield constr(2, map(entries), maybeHash(proposal.policyHash()));
            }
            case RawProposal.NO_CONFIDENCE -> constr(3, prev);
            case RawProposal.UPDATE_COMMITTEE -> {
                List<PlutusData> removed = new ArrayList<>();
                proposal.committeeRemovals().forEach(c -> removed.add(credentialOf(c)));
                List<PlutusData.Pair> added = new ArrayList<>();
                proposal.committeeAdditions().forEach((c, epoch) -> added.add(pair(credentialOf(c), integer(epoch))));
                BigInteger[] quorum = proposal.quorum();
                yield constr(4, prev, list(removed), map(added), DataTerms.rational(quorum[0], quorum[1]));
            }
            case RawProposal.NEW_CONSTITUTION -> constr(5, prev, constr(0, maybeHash(proposal.constitutionScript())));
            default -> constr(6);
        };
    }

    /**
     * {@code ChangedParameters}: the ledger's {@code ToPlutusData (PParamsUpdate era)} (Conway/PParams.hs:198-205) — a
     * {@code Map} from each present parameter's tag to its value, in {@code eraPParams} order (ascending tags); values
     * are {@code I} for coins and counts, {@code List [I n, I d]} for rationals (reduced, the ledger's
     * {@code ToPlutusData Rational}), {@code List} for {@code ExUnits}, prices and voting thresholds, and a
     * {@code Map} in key order for cost models.
     */
    private static PlutusData changedParameters(CborReader reader) {
        long count = reader.readMapHeader();
        TreeMap<BigInteger, PlutusData> byKey = new TreeMap<>();
        for (long i = 0; reader.hasNext(count, i); i++) {
            BigInteger key = reader.readUnsigned();
            byKey.put(key, parameterValue(reader));
        }
        List<PlutusData.Pair> entries = new ArrayList<>();
        byKey.forEach((key, value) -> entries.add(pair(integer(key), value)));
        return map(entries);
    }

    private static PlutusData parameterValue(CborReader reader) {
        int major = reader.peekMajor();
        switch (major) {
            case 0, 1 -> {
                return integer(reader.readInteger());
            }
            case 4 -> {
                long length = reader.readArrayHeader();
                List<PlutusData> items = new ArrayList<>();
                for (long i = 0; reader.hasNext(length, i); i++) {
                    items.add(parameterValue(reader));
                }
                return list(items);
            }
            case 5 -> {
                long length = reader.readMapHeader();
                List<PlutusData.Pair> entries = new ArrayList<>();
                for (long i = 0; reader.hasNext(length, i); i++) {
                    PlutusData key = parameterValue(reader);
                    entries.add(pair(key, parameterValue(reader)));
                }
                entries.sort((a, b) -> a.key() instanceof PlutusData.IntData x && b.key() instanceof PlutusData.IntData y
                        ? x.value().compareTo(y.value()) : 0);
                return map(entries);
            }
            case 6 -> {
                long tag = reader.readTag();
                if (tag != 30) {
                    throw new IllegalStateException("unexpected tag " + tag + " in a parameter update");
                }
                long length = reader.readArrayHeader();
                BigInteger numerator = reader.readInteger();
                BigInteger denominator = reader.readInteger();
                endOf(reader, length, 2);
                BigInteger[] reduced = DataTerms.reduce(numerator, denominator);
                return list(List.of(integer(reduced[0]), integer(reduced[1])));
            }
            default -> throw new IllegalStateException("unexpected CBOR major type " + major + " in a parameter update");
        }
    }

    /** A translation failure {@link ScriptCollection#collectErrors} reports before any context is built. */
    private static IllegalStateException untranslatable(String error) {
        return new IllegalStateException("the script context cannot be translated (" + error + "), which the "
                + "collection checks should have reported as UTXOS.CollectErrors");
    }
}
