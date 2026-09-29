package org.yanoproject.runtime.validation.shadowsync;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.conway.utxo.MinFee;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator.BlockResult;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator.Expected;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator.Kind;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator.TxResult;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.ADDRESS;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.agreeing;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.block;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.engine;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.env;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.id;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.indexOf;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.invalid;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.isValidFlag;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.params;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.valid;

/** ADR-056 Phase 7a: one block's transactions against the pre-block state, with the chain's expected outcomes. */
class SyncBlockValidatorTest {

    private final SyncBlockValidator validator = new SyncBlockValidator();

    @TempDir
    Path dumps;

    @Test
    void eachTransactionSeesTheEffectsOfTheEarlierOnesInTheBlock() {
        List<String> seen = new CopyOnWriteArrayList<>();
        LedgerValidationEngine chained = engine("java-julc", request -> {
            int index = indexOf(request.txCbor());
            if (index > 0) {
                Lookup<?> parent = request.view().utxo(new Outpoint(id(index - 1), 0));
                seen.add(index + ":" + parent.isPresent());
            }
            assertThat(request.rule()).isEqualTo(TxValidationRequest.Rule.LEDGER);
            assertThat(request.origin()).isEqualTo(TxValidationRequest.Origin.SYNC);
            assertThat(request.previous()).isNull();
            return valid(request.txCbor(), true);
        });

        BlockResult result = validator.validate(block(3, Set.of()), base(10), env(10), List.of(chained), null);

        assertThat(seen).containsExactly("1:true", "2:true");
        assertThat(result.engines().getFirst().txs()).extracting(TxResult::kind)
                .containsExactly(Kind.AGREED, Kind.AGREED, Kind.AGREED);
        assertThat(result.protocolMajor()).isEqualTo(10);
    }

    @Test
    void aPhase2InvalidTransactionMustComeOutValidWithAPhase2InvalidVerdict() {
        SyncBlock block = block(3, Set.of(1));
        assertThat(isValidFlag(block.txs().get(1))).isFalse();

        BlockResult agreed = validator.validate(block, base(10), env(10), List.of(agreeing("java-julc")), null);
        assertThat(agreed.engines().getFirst().txs().get(1)).satisfies(tx -> {
            assertThat(tx.expected()).isEqualTo(Expected.PHASE2_INVALID);
            assertThat(tx.kind()).isEqualTo(Kind.AGREED);
            assertThat(tx.actual()).isEqualTo("PHASE2_INVALID");
        });

        // The engine's scripts passed where the chain's failed: the engine would have rejected the block.
        LedgerValidationEngine tagMismatch = engine("java-julc", request -> isValidFlag(request.txCbor())
                ? valid(request.txCbor(), true) : invalid(LedgerRuleName.UTXOS, "ValidationTagMismatch"));
        BlockResult mismatch = validator.validate(block, base(10), env(10), List.of(tagMismatch), null);
        assertThat(mismatch.engines().getFirst().txs().get(1)).satisfies(tx -> {
            assertThat(tx.kind()).isEqualTo(Kind.DISAGREED);
            assertThat(tx.actual()).isEqualTo("UTXOS.ValidationTagMismatch");
        });

        // A phase-2-valid verdict for a transaction the chain marked invalid is a disagreement too.
        LedgerValidationEngine ignoresFlag = engine("java-julc", request -> valid(request.txCbor(), true));
        assertThat(validator.validate(block, base(10), env(10), List.of(ignoresFlag), null).engines().getFirst()
                .txs().get(1).kind()).isEqualTo(Kind.DISAGREED);
    }

    @Test
    void ruleRejectionsAreDisagreementsAndEngineOnlyFailuresAreEngineFailures() {
        assertThat(SyncBlockValidator.classify(Expected.VALID, invalid(LedgerRuleName.UTXO, "FeeTooSmallUTxO")))
                .isEqualTo(Kind.DISAGREED);
        assertThat(SyncBlockValidator.classify(Expected.VALID,
                invalid(LedgerRuleName.ENGINE, "LedgerStateUnavailable"))).isEqualTo(Kind.ENGINE_FAILURE);
        assertThat(SyncBlockValidator.classify(Expected.VALID, invalid(LedgerRuleName.ENGINE, "DecodingFailure")))
                .isEqualTo(Kind.ENGINE_FAILURE);

        LedgerValidationEngine throwing = engine("amaru", request -> {
            throw new IllegalStateException("boom");
        });
        TxResult threw = validator.validate(block(1, Set.of()), base(10), env(10), List.of(throwing), null)
                .engines().getFirst().txs().getFirst();
        assertThat(threw.kind()).isEqualTo(Kind.ENGINE_FAILURE);
        assertThat(threw.actual()).isEqualTo("ENGINE." + SyncBlockValidator.ENGINE_THREW);
    }

    @Test
    void aFindingWhoseEffectsCannotBeRecoveredTaintsTheRestOfTheBlock() {
        // Placeholder transactions do not decode, so the chain's effects of a rejected one cannot be derived.
        LedgerValidationEngine rejectsSecond = engine("java-julc", request -> indexOf(request.txCbor()) == 1
                ? invalid(LedgerRuleName.UTXO, "FeeTooSmallUTxO") : request.view().utxo(
                        new Outpoint(id(1), 0)).isPresent() || indexOf(request.txCbor()) == 0
                        ? valid(request.txCbor(), true) : invalid(LedgerRuleName.UTXO, "BadInputsUTxO"));

        List<TxResult> txs = validator.validate(block(3, Set.of()), base(10), env(10), List.of(rejectsSecond), null)
                .engines().getFirst().txs();

        assertThat(txs).extracting(TxResult::kind).containsExactly(Kind.AGREED, Kind.DISAGREED, Kind.ENGINE_FAILURE);
        assertThat(txs.get(2).overlayTainted()).isTrue();
        assertThat(txs.get(2).failures()).extracting(f -> f.constructor())
                .contains(SyncBlockValidator.OVERLAY_TAINTED);
    }

    @Test
    void aRejectedTransactionStillAdvancesTheOverlayByTheChainsEffects() throws Exception {
        String funding = "aa".repeat(32);
        Transaction first = payment(List.of(new TransactionInput(funding, 0)), List.of(), null);
        String firstId = TxIdentity.txIdHex(first.serialize());
        Transaction second = payment(List.of(new TransactionInput(firstId, 0)), List.of(), null);
        SyncBlock block = realBlock(List.of(first, second), Set.of());
        LedgerView base = InMemoryLedgerView.builder().protocolParams(params(10))
                .utxo(funding, 0, ShadowSyncTestSupport.output(5_000_000)).build();
        List<String> secondSaw = new ArrayList<>();
        LedgerValidationEngine engine = engine("java-julc", request -> {
            if (TxIdentity.txIdHex(request.txCbor()).equals(firstId)) {
                return invalid(LedgerRuleName.UTXO, "FeeTooSmallUTxO");
            }
            Lookup<?> parent = request.view().utxo(new Outpoint(firstId, 0));
            secondSaw.add(String.valueOf(parent.isPresent()));
            return valid2(request.txCbor());
        });

        List<TxResult> txs = validator.validate(block, base, env(10), List.of(engine), null).engines().getFirst().txs();

        assertThat(txs).extracting(TxResult::kind).containsExactly(Kind.DISAGREED, Kind.AGREED);
        assertThat(txs.get(1).overlayTainted()).isFalse();
        assertThat(secondSaw).containsExactly("true");
    }

    @Test
    void aFindingWritesAReplayBundleThatReproducesIt() {
        LedgerValidationEngine readsAndRejects = engine("java-julc", request -> {
            request.view().protocolParams();
            request.view().utxo(new Outpoint("bb".repeat(32), 0));
            return invalid(LedgerRuleName.UTXO, "BadInputsUTxO");
        });
        List<Path> written = new ArrayList<>();
        SyncBlockValidator.Dumper dumper = bundle -> {
            Path file = bundle.write(dumps);
            written.add(file);
            return file;
        };

        TxResult result = validator.validate(block(1, Set.of()), base(10), env(10), List.of(readsAndRejects), dumper)
                .engines().getFirst().txs().getFirst();

        assertThat(result.dump()).isNotNull();
        assertThat(written).containsExactly(result.dump());
        ShadowDumpBundle bundle = ShadowDumpBundle.read(result.dump());
        assertThat(bundle.admission().engine()).isEqualTo(SyncBlockValidator.CHAIN);
        assertThat(bundle.admission().valid()).isTrue();
        assertThat(bundle.shadow().valid()).isFalse();
        assertThat(bundle.rule()).isEqualTo(TxValidationRequest.Rule.LEDGER);
        assertThat(bundle.origin()).isEqualTo(TxValidationRequest.Origin.SYNC);
        assertThat(bundle.env().forecastBasisSlot()).isEqualTo(env(10).forecastBasisSlot());
        // The replay answers the recorded reads.
        TxValidationOutcome replayed = readsAndRejects.validate(bundle.replayRequest());
        assertThat(replayed).isInstanceOf(TxValidationOutcome.Invalid.class);
        assertThat(bundle.replayView().utxo(new Outpoint("bb".repeat(32), 0))).isInstanceOf(Lookup.Absent.class);
    }

    @Test
    void aFullDumperSkipsTheRecordingRerun() {
        List<Integer> calls = new ArrayList<>();
        LedgerValidationEngine rejecting = engine("java-julc", request -> {
            calls.add(1);
            return invalid(LedgerRuleName.UTXO, "BadInputsUTxO");
        });
        SyncBlockValidator.Dumper full = new SyncBlockValidator.Dumper() {
            @Override
            public Path write(ShadowDumpBundle bundle) {
                throw new AssertionError("a full dumper is never asked to write");
            }

            @Override
            public boolean accepts() {
                return false;
            }
        };

        TxResult result = validator.validate(block(1, Set.of()), base(10), env(10), List.of(rejecting), full)
                .engines().getFirst().txs().getFirst();

        assertThat(result.dump()).isNull();
        assertThat(calls).as("validated once, not re-run over a recording").hasSize(1);
    }

    @Test
    void theBlockExUnitsSumIncludesPhase2InvalidTransactionsAndIsBoundedByTheBlockMaximum() throws Exception {
        String funding = "aa".repeat(32);
        Transaction a = withRedeemer(payment(List.of(new TransactionInput(funding, 0)), List.of(), null), 600, 7_000);
        Transaction b = withRedeemer(payment(List.of(new TransactionInput(funding, 1)), List.of(), null), 500, 2_000);
        SyncBlock block = realBlock(List.of(a, b), Set.of(1));
        ProtocolParams params = params(10);
        params.setMaxBlockExMem("1000");
        params.setMaxBlockExSteps("10000");
        LedgerView base = InMemoryLedgerView.builder().protocolParams(params).build();

        SyncBlockValidator.ExUnitsCheck units = validator.validate(block, base, env(10), List.of(), null).exUnits();

        assertThat(units.checked()).isTrue();
        assertThat(units.mem()).isEqualTo(BigInteger.valueOf(1_100));   // the invalid transaction counts too
        assertThat(units.steps()).isEqualTo(BigInteger.valueOf(9_000));
        assertThat(units.violated()).as("memory 1100 > 1000").isTrue();

        params.setMaxBlockExMem("1100");
        assertThat(validator.validate(block, InMemoryLedgerView.builder().protocolParams(params).build(), env(10),
                List.of(), null).exUnits().violated()).as("point-wise <=").isFalse();

        assertThat(validator.validate(block(1, Set.of()), base(10), env(10), List.of(), null).exUnits().checked())
                .as("placeholders do not decode").isFalse();
    }

    @Test
    void theBlockReferenceScriptSizeIsMeasuredAgainstThePreBlockUtxoUntilPv10AndCumulativelyFrom11()
            throws Exception {
        PlutusV2Script script = PlutusV2Script.builder().cborHex("49480100002221200101").build();
        String funding = "aa".repeat(32);
        String scriptHolder = "cc".repeat(32);
        TransactionOutput withScript = TransactionOutput.builder().address(ADDRESS)
                .value(Value.builder().coin(BigInteger.valueOf(5_000_000)).build()).scriptRef(script).build();
        long size = MinFee.scriptOriginalSize(withScript.getScriptRef());
        // A references the pre-block script holder and creates an output with the same script; B references both
        // that new output and the pre-block holder, and spends A's output.
        Transaction a = payment(List.of(new TransactionInput(funding, 0)),
                List.of(new TransactionInput(scriptHolder, 0)), withScript);
        String aId = TxIdentity.txIdHex(a.serialize());
        Transaction b = payment(List.of(new TransactionInput(aId, 0)),
                List.of(new TransactionInput(scriptHolder, 0), new TransactionInput(aId, 0)), null);
        SyncBlock block = realBlock(List.of(a, b), Set.of());
        LedgerView base = InMemoryLedgerView.builder().protocolParams(params(10))
                .utxo(funding, 0, ShadowSyncTestSupport.output(10_000_000))
                .utxo(scriptHolder, 0, withScript).build();

        SyncBlockValidator.RefScriptCheck pv10 = validator.validate(block, base, env(10), List.of(), null).refScripts();
        SyncBlockValidator.RefScriptCheck pv11 = validator.validate(block, base, env(11), List.of(), null).refScripts();

        assertThat(pv10.checked()).isTrue();
        assertThat(pv10.totalSize()).isEqualTo(2 * size);        // A: holder; B: holder (A's output not pre-block)
        assertThat(pv11.totalSize()).isEqualTo(3 * size);        // B also counts A's output (inputs ∪ refs, once)
        assertThat(new SyncBlockValidator(2 * size).validate(block, base, env(11), List.of(), null).refScripts()
                .violated()).isTrue();
        assertThat(new SyncBlockValidator(2 * size).validate(block, base, env(10), List.of(), null).refScripts()
                .violated()).isFalse();
    }

    private static TxValidationOutcome valid2(byte[] tx) {
        String id = TxIdentity.txIdHex(tx);
        return new TxValidationOutcome.Valid(new TxEffects(id, true, List.of(),
                List.of(), List.of()), new ValidatedTx(tx,
                HexUtil.decodeHexString(id), 10, 0, new byte[32], true,
                TxValidationRequest.Origin.SYNC), false);
    }

    private static Transaction withRedeemer(Transaction tx, long mem, long steps) {
        tx.getWitnessSet().setRedeemers(new ArrayList<>(List.of(Redeemer.builder().tag(RedeemerTag.Spend)
                .index(BigInteger.ZERO).data(ConstrPlutusData.of(0))
                .exUnits(ExUnits.builder().mem(BigInteger.valueOf(mem)).steps(BigInteger.valueOf(steps)).build())
                .build())));
        return tx;
    }

    private static LedgerView base(int pv) {
        return InMemoryLedgerView.builder().protocolParams(params(pv)).build();
    }

    private static Transaction payment(List<TransactionInput> inputs, List<TransactionInput> references,
                                       TransactionOutput extra) {
        List<TransactionOutput> outputs = new ArrayList<>();
        outputs.add(extra != null ? extra : ShadowSyncTestSupport.output(1_000_000));
        TransactionBody body = TransactionBody.builder().inputs(new ArrayList<>(inputs))
                .referenceInputs(references.isEmpty() ? null : new ArrayList<>(references))
                .outputs(outputs).fee(BigInteger.valueOf(200_000)).build();
        return Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).build();
    }

    private static SyncBlock realBlock(List<Transaction> txs, Set<Integer> invalid) throws Exception {
        List<byte[]> bytes = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Transaction tx : txs) {
            byte[] cbor = tx.serialize();
            bytes.add(cbor);
            ids.add(TxIdentity.txIdHex(cbor));
        }
        return new SyncBlock(bytes, ids, invalid);
    }
}
