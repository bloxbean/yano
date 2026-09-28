package org.yanoproject.scalusbridge;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDelegation;
import com.bloxbean.cardano.client.transaction.spec.cert.StakePoolId;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.TxValidationRequest.Origin;
import org.yanoproject.ledger.rules.TxValidationRequest.Rule;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.ValidationError;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.OverlayLedgerView;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 step 1d: the Scalus engine adapter reads account, pool and UTxO state from the request's view, so
 * chained certificate effects in an overlay are visible; unavailable reads fail closed; failures carry Haskell
 * names and the right phase.
 */
class ScalusLedgerValidationEngineTest {

    private static final long INPUT_LOVELACE = 100_000_000L;
    private static final long FEE = 1_000_000L;
    private static final BigInteger KEY_DEPOSIT = BigInteger.valueOf(2_000_000);
    private static final String GENESIS_TX = "aa".repeat(32);
    private static final String POOL = "bb".repeat(28);

    private final Account account = new Account(Networks.testnet());
    private final byte[] stakeHash = account.getBaseAddress().getDelegationCredentialHash().orElseThrow();
    private final CredentialKey stakeKey = CredentialKey.key(HexUtil.encodeHexString(stakeHash));
    private final ScalusLedgerValidationEngine engine = new ScalusLedgerValidationEngine(null);

    @Test
    void registerThenDelegateAcrossAnOverlay() throws Exception {
        InMemoryLedgerView base = base().build();
        Transaction register = signed(0, List.of(new RegCert(StakeCredential.fromKeyHash(stakeHash), KEY_DEPOSIT)),
                KEY_DEPOSIT.longValueExact());
        TxValidationOutcome registered = engine.validate(request(register, base, Rule.MEMPOOL));
        assertThat(registered).isInstanceOf(TxValidationOutcome.Valid.class);
        OverlayLedgerView overlay = OverlayLedgerView.over(base)
                .apply(((TxValidationOutcome.Valid) registered).effects());
        assertThat(overlay.account(stakeKey).orElseThrowUnavailable()).map(AccountState::deposit)
                .contains(KEY_DEPOSIT);

        Transaction delegate = signed(1, List.of(new StakeDelegation(StakeCredential.fromKeyHash(stakeHash),
                StakePoolId.fromHexPoolId(POOL))), 0);

        // Against the overlay (the registration is visible): valid, and the delegation is in the effects.
        TxValidationOutcome delegated = engine.validate(request(delegate, overlay, Rule.MEMPOOL));
        assertThat(delegated).isInstanceOf(TxValidationOutcome.Valid.class);
        OverlayLedgerView after = overlay.apply(((TxValidationOutcome.Valid) delegated).effects());
        assertThat(after.account(stakeKey).orElseThrowUnavailable()).map(AccountState::delegatedPool)
                .contains(new PoolId(POOL));

        // Against the canonical base alone the credential is not registered: a DELEG phase-1 failure.
        TxValidationOutcome rejected = engine.validate(request(delegate, base, Rule.LEDGER));
        LedgerFailure failure = first(rejected);
        assertThat(failure.qualifiedName()).isEqualTo("DELEG.StakeKeyNotRegisteredDELEG");
        assertThat(failure.phase()).isEqualTo(LedgerFailure.Phase.PHASE_1);
    }

    @Test
    void unavailableAccountStateFailsClosed() throws Exception {
        InMemoryLedgerView view = base().unavailable(InMemoryLedgerView.Area.ACCOUNTS).build();
        Transaction delegate = signed(1, List.of(new StakeDelegation(StakeCredential.fromKeyHash(stakeHash),
                StakePoolId.fromHexPoolId(POOL))), 0);

        assertThat(first(engine.validate(request(delegate, view, Rule.MEMPOOL))).qualifiedName())
                .isEqualTo("ENGINE." + LedgerFailure.LEDGER_STATE_UNAVAILABLE);
    }

    @Test
    void unavailableUtxoFailsClosedAndAbsentUtxoIsBadInputs() throws Exception {
        Transaction plain = signed(0, List.of(), 0);

        assertThat(first(engine.validate(request(plain, base().unavailable(InMemoryLedgerView.Area.UTXO).build(),
                Rule.LEDGER))).qualifiedName()).isEqualTo("ENGINE." + LedgerFailure.LEDGER_STATE_UNAVAILABLE);

        InMemoryLedgerView empty = InMemoryLedgerView.builder().protocolParams(protocolParams()).build();
        LedgerFailure bad = first(engine.validate(request(plain, empty, Rule.LEDGER)));
        assertThat(bad.qualifiedName()).isEqualTo("UTXO.BadInputsUTxO");
        assertThat(bad.phase()).isEqualTo(LedgerFailure.Phase.PHASE_1);
    }

    @Test
    void allInputsSpentIsTheMempoolFailureOnly() throws Exception {
        InMemoryLedgerView empty = InMemoryLedgerView.builder().protocolParams(protocolParams()).build();
        TxValidationOutcome outcome = engine.validate(request(signed(0, List.of(), 0), empty, Rule.MEMPOOL));

        assertThat(((TxValidationOutcome.Invalid) outcome).failures()).extracting(LedgerFailure::qualifiedName)
                .containsExactly("LEDGER.ConwayMempoolFailure");
    }

    /**
     * The legacy validator labelled every failure whose Scalus class name mentions "Script" as phase 2. An
     * extraneous native script witness is a phase-1 UTXOW failure.
     */
    @Test
    void scriptNamedPhaseOneFailuresAreNotPhaseTwo() throws Exception {
        Transaction tx = unsigned(0, List.of(), 0);
        TransactionWitnessSet witnesses = new TransactionWitnessSet();
        witnesses.setNativeScripts(List.of(new ScriptPubkey("dd".repeat(28))));
        tx.setWitnessSet(witnesses);
        tx = account.sign(tx);

        LedgerFailure failure = first(engine.validate(request(tx, base().build(), Rule.LEDGER)));
        assertThat(failure.qualifiedName()).isEqualTo("UTXOW.ExtraneousScriptWitnessesUTXOW");
        assertThat(failure.phase()).isEqualTo(LedgerFailure.Phase.PHASE_1);
        assertThat(failure.toValidationError().phase())
                .isEqualTo(ValidationError.Phase.PHASE_1);
    }

    @Test
    void missingSignatureIsAUtxowFailure() throws Exception {
        Transaction tx = unsigned(0, List.of(), 0);

        assertThat(first(engine.validate(request(tx, base().build(), Rule.LEDGER))).qualifiedName())
                .isEqualTo("UTXOW.MissingVKeyWitnessesUTXOW");
    }

    @Test
    void undecodableTransactionIsAnEngineFailure() {
        TxValidationOutcome outcome = engine.validate(new TxValidationRequest(new byte[]{(byte) 0x84, 0x01},
                base().build(), env(), Rule.MEMPOOL, Origin.LOCAL, null));

        assertThat(first(outcome).qualifiedName()).isEqualTo("ENGINE." + ScalusLedgerValidationEngine.DECODING_FAILURE);
    }

    // ------------------------------------------------------------------ fixtures

    private InMemoryLedgerView.Builder base() {
        return InMemoryLedgerView.builder()
                .utxo(entry(0))
                .utxo(entry(1))
                .pool(new PoolState(new PoolId(POOL), BigInteger.valueOf(500_000_000), "cc".repeat(32), null, null,
                        null))
                .protocolParams(protocolParams());
    }

    private UtxoEntry entry(int index) {
        return new UtxoEntry(new Outpoint(GENESIS_TX, index), new TransactionOutput(account.baseAddress(),
                Value.builder().coin(BigInteger.valueOf(INPUT_LOVELACE)).build()));
    }

    private Transaction unsigned(int inputIndex, List<Certificate> certs, long deposit) {
        TransactionOutput change = new TransactionOutput(account.baseAddress(),
                Value.builder().coin(BigInteger.valueOf(INPUT_LOVELACE - FEE - deposit)).build());
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(GENESIS_TX, inputIndex)))
                .outputs(List.of(change))
                .fee(BigInteger.valueOf(FEE))
                .certs(certs.isEmpty() ? null : certs)
                .build();
        return Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).isValid(true).build();
    }

    private Transaction signed(int inputIndex, List<Certificate> certs, long deposit) {
        Transaction tx = account.sign(unsigned(inputIndex, certs, deposit));
        return certs.isEmpty() ? tx : TransactionSigner.INSTANCE.sign(tx, account.stakeHdKeyPair());
    }

    private static TxValidationRequest request(Transaction tx, LedgerView view, Rule rule) throws Exception {
        return new TxValidationRequest(tx.serialize(), view, env(), rule, Origin.LOCAL, null);
    }

    private static ValidationEnv env() {
        return new ValidationEnv(1_000, 0, 10, 0, NetworkId.TESTNET, new SlotConfig(1000, 0, 1_600_000_000_000L),
                new byte[32]);
    }

    private static LedgerFailure first(TxValidationOutcome outcome) {
        assertThat(outcome).isInstanceOf(TxValidationOutcome.Invalid.class);
        return ((TxValidationOutcome.Invalid) outcome).failures().getFirst();
    }

    static ProtocolParams protocolParams() {
        ProtocolParams pp = new ProtocolParams();
        pp.setProtocolMajorVer(10);
        pp.setProtocolMinorVer(0);
        pp.setMinFeeA(44);
        pp.setMinFeeB(155381);
        pp.setMaxBlockSize(90112);
        pp.setMaxTxSize(16384);
        pp.setMaxBlockHeaderSize(1100);
        pp.setKeyDeposit(KEY_DEPOSIT.toString());
        pp.setPoolDeposit("500000000");
        pp.setEMax(18);
        pp.setNOpt(500);
        pp.setA0(new BigDecimal("0.3"));
        pp.setRho(new BigDecimal("0.003"));
        pp.setTau(new BigDecimal("0.2"));
        pp.setMinPoolCost("340000000");
        LinkedHashMap<String, LinkedHashMap<String, Long>> costModels = new LinkedHashMap<>();
        LinkedHashMap<String, Long> v1 = new LinkedHashMap<>();
        v1.put("000", 1L);
        costModels.put("PlutusV1", v1);
        pp.setCostModels(costModels);
        pp.setPriceMem(new BigDecimal("0.0577"));
        pp.setPriceStep(new BigDecimal("0.0000721"));
        pp.setMaxTxExMem("14000000");
        pp.setMaxTxExSteps("10000000000");
        pp.setMaxBlockExMem("62000000");
        pp.setMaxBlockExSteps("20000000000");
        pp.setMaxValSize("5000");
        pp.setCollateralPercent(new BigDecimal("150"));
        pp.setMaxCollateralInputs(3);
        pp.setCoinsPerUtxoSize("4310");
        pp.setGovActionDeposit(BigInteger.valueOf(100_000_000_000L));
        pp.setGovActionLifetime(6);
        pp.setDrepDeposit(BigInteger.valueOf(500_000_000L));
        pp.setDrepActivity(20);
        pp.setCommitteeMinSize(3);
        pp.setCommitteeMaxTermLength(146);
        pp.setMinFeeRefScriptCostPerByte(new BigDecimal("15"));
        pp.setPvtMotionNoConfidence(new BigDecimal("0.51"));
        pp.setPvtCommitteeNormal(new BigDecimal("0.51"));
        pp.setPvtCommitteeNoConfidence(new BigDecimal("0.51"));
        pp.setPvtHardForkInitiation(new BigDecimal("0.51"));
        pp.setPvtPPSecurityGroup(new BigDecimal("0.51"));
        pp.setDvtMotionNoConfidence(new BigDecimal("0.67"));
        pp.setDvtCommitteeNormal(new BigDecimal("0.67"));
        pp.setDvtCommitteeNoConfidence(new BigDecimal("0.6"));
        pp.setDvtUpdateToConstitution(new BigDecimal("0.75"));
        pp.setDvtHardForkInitiation(new BigDecimal("0.6"));
        pp.setDvtPPNetworkGroup(new BigDecimal("0.67"));
        pp.setDvtPPEconomicGroup(new BigDecimal("0.67"));
        pp.setDvtPPTechnicalGroup(new BigDecimal("0.67"));
        pp.setDvtPPGovGroup(new BigDecimal("0.75"));
        pp.setDvtTreasuryWithdrawal(new BigDecimal("0.67"));
        return pp;
    }
}
