package org.yanoproject.ledger.scripteval.phase2;

import com.bloxbean.cardano.client.spec.UnitInterval;
import com.bloxbean.cardano.client.transaction.spec.ProtocolParamUpdate;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.julclang.core.PlutusData;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.bytes;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.constr;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.integer;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.list;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.map;
import static org.yanoproject.ledger.scripteval.phase2.DataTerms.pair;

/**
 * The script contexts {@link ConwayTxInfoTranslator} builds, against cardano-ledger {@code f649f975}'s Conway
 * translation and plutus-ledger-api 1.65's encodings, where julc's own {@code CclTxConverter} /
 * {@code V1V2ScriptContextBuilder} differ (ADR-056 Phase 7c, "Phase 2 evaluator: Julc").
 */
class ConwayTxInfoTranslatorTest {

    /**
     * {@code transTxCert} (Conway/TxInfo.hs:572-581): a {@code reg_cert} carries its deposit in the PlutusV3 context
     * from protocol version 10 and none during the bootstrap phase (9); V1/V2 never carry it.
     */
    @Test
    void aRegCertDepositIsLeftOutDuringTheBootstrapPhase() throws Exception {
        TxSpec spec = MutationWorld.simpleSpec();
        StakeCredential credential = StakeCredential.fromKeyHash(HexUtil.decodeHexString(TestKey.DEV_77.keyHash()));
        spec.certs.add(RegCert.builder().stakeCredential(credential).coin(MutationWorld.KEY_DEPOSIT).build());
        spec.signers.add(TestKey.DEV_77);
        spec.changeAdjust = MutationWorld.KEY_DEPOSIT.negate();
        PlutusData cred = constr(0, bytes(HexUtil.decodeHexString(TestKey.DEV_77.keyHash())));

        assertThat(field(translator(spec, 9).txInfo(3), 5)).isEqualTo(list(List.of(constr(0, cred, constr(1)))));
        assertThat(field(translator(spec, 10).txInfo(3), 5)).isEqualTo(list(List.of(
                constr(0, cred, constr(0, integer(MutationWorld.KEY_DEPOSIT))))));
        // V1/V2: DCertDelegRegKey (StakingHash cred), at every protocol version.
        assertThat(field(translator(spec, 10).txInfo(2), 5)).isEqualTo(list(List.of(constr(0, constr(0, cred)))));
    }

    /**
     * {@code txInfoData}: {@code [(DatumHash, Datum)]} in V1 (a list of tuples), a {@code Map} from V2, keyed by the
     * hash of the datum's original bytes. julc's {@code V1V2ScriptContextBuilder} encodes V1's as a map.
     */
    @Test
    void witnessDatumsAreAListOfPairsInV1AndAMapFromV2() throws Exception {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.inputs.set(1, MutationWorld.DATUM_SCRIPT_INPUT);
        spec.datums.add(MutationWorld.DATUM);
        ConwayTxInfoTranslator translator = translator(spec, 10);
        byte[] hash = HexUtil.decodeHexString(translator.witnessDatums().firstKey());
        assertThat(field(translator.txInfo(1), 8)).isEqualTo(list(List.of(constr(0, bytes(hash), integer(42)))));
        assertThat(field(translator.txInfo(2), 10)).isEqualTo(map(List.of(pair(bytes(hash), integer(42)))));
        assertThat(field(translator.txInfo(3), 10)).isEqualTo(map(List.of(pair(bytes(hash), integer(42)))));
    }

    /**
     * {@code transValue} puts the lovelace entry first; V1/V2 {@code transMintValue} adds a zero lovelace entry
     * ("hysterical raisins") and V1/V2 fees are a {@code Value}; V3 mint has no lovelace and its fee is an integer.
     */
    @Test
    void valuesFeesAndMintFollowEachLanguage() throws Exception {
        ConwayTxInfoTranslator translator = translator(MutationWorld.simpleSpec(), 10);
        PlutusData zeroAdaMint = map(List.of(pair(bytes(new byte[0]), map(List.of(pair(bytes(new byte[0]),
                integer(0)))))));
        assertThat(field(translator.txInfo(1), 3)).isEqualTo(zeroAdaMint);
        assertThat(field(translator.txInfo(2), 4)).isEqualTo(zeroAdaMint);
        assertThat(field(translator.txInfo(3), 4)).isEqualTo(map(List.of()));

        BigInteger fee = translator.raw().fee();
        assertThat(field(translator.txInfo(1), 2)).isEqualTo(map(List.of(pair(bytes(new byte[0]),
                map(List.of(pair(bytes(new byte[0]), integer(fee))))))));
        assertThat(field(translator.txInfo(3), 3)).isEqualTo(integer(fee));

        PlutusData firstOutput = ((PlutusData.ListData) field(translator.txInfo(3), 2)).items().getFirst();
        PlutusData value = ((PlutusData.ConstrData) firstOutput).fields().get(1);
        assertThat(((PlutusData.MapData) value).entries().getFirst().key()).isEqualTo(bytes(new byte[0]));
    }

    /**
     * Conway's {@code transValidityInterval}: no bounds is {@code always}; only a TTL is {@code (-inf, t)} with the
     * upper bound exclusive; both are {@code [t1, t2)}. POSIX times come from the slot config.
     */
    @Test
    void theValidityIntervalFollowsConway() throws Exception {
        TxSpec ttlOnly = MutationWorld.simpleSpec();
        ConwayTxInfoTranslator translator = translator(ttlOnly, 10);
        long upper = posix(translator.raw().ttl().longValue());
        PlutusData negInfInclusive = constr(0, constr(0), constr(1));
        assertThat(field(translator.txInfo(3), 7)).isEqualTo(constr(0, negInfInclusive,
                constr(0, constr(1, integer(upper)), constr(0))));

        TxSpec both = MutationWorld.simpleSpec();
        both.validityStart = MutationWorld.SLOT - 10;
        translator = translator(both, 10);
        assertThat(field(translator.txInfo(1), 6)).isEqualTo(constr(0,
                constr(0, constr(1, integer(posix(MutationWorld.SLOT - 10))), constr(1)),
                constr(0, constr(1, integer(upper)), constr(0))));

        TxSpec none = MutationWorld.simpleSpec();
        none.ttl = null;
        assertThat(field(translator(none, 10).txInfo(3), 7)).isEqualTo(constr(0, negInfInclusive,
                constr(0, constr(2), constr(1))));
    }

    /**
     * {@code ChangedParameters} is the ledger's {@code ToPlutusData PParamsUpdate} (Conway/PParams.hs:198-205): a map of
     * parameter tag to value, a rational as {@code List [I n, I d]} (reduced). julc's {@code CclTxConverter} replaces
     * every governance action with {@code InfoAction}.
     */
    @Test
    void aParameterChangeCarriesTheChangedParameters() throws Exception {
        TxSpec spec = MutationWorld.simpleSpec();
        ProtocolParamUpdate update = ProtocolParamUpdate.builder()
                .expansionRate(new UnitInterval(BigInteger.valueOf(6), BigInteger.valueOf(2000)))
                .collateralPercent(140)
                .build();
        spec.inputs.add(MutationWorld.GOV_INPUT);
        spec.proposals.add(ProposalProcedure.builder()
                .deposit(MutationWorld.GOV_ACTION_DEPOSIT)
                .rewardAccount(MutationWorld.rewardAccount(TestKey.DEV_77, MutationWorld.NETWORK))
                .govAction(new ParameterChangeAction(null, update, null))
                .anchor(new Anchor("https://example.com/proposal.json", new byte[32]))
                .build());
        spec.changeAdjust = MutationWorld.GOV_ACTION_DEPOSIT.negate();
        ConwayTxInfoTranslator translator = translator(spec, 10);

        PlutusData proposal = ((PlutusData.ListData) field(translator.txInfo(3), 13)).items().getFirst();
        PlutusData action = ((PlutusData.ConstrData) proposal).fields().get(2);
        assertThat(action).isEqualTo(constr(0, constr(1),
                map(List.of(pair(integer(10), list(List.of(integer(3), integer(1000)))),
                        pair(integer(23), integer(140)))),
                constr(1)));
    }

    private static long posix(long slot) {
        var config = MutationWorld.env().slotConfig();
        return config.getZeroTime() + (slot - config.getZeroSlot()) * config.getSlotLength();
    }

    private static PlutusData field(PlutusData txInfo, int index) {
        return ((PlutusData.ConstrData) txInfo).fields().get(index);
    }

    private static ConwayTxInfoTranslator translator(TxSpec spec, int protocolMajor) throws Exception {
        byte[] cbor = ConwayTxBuilder.build(spec, MutationWorld.view()).cbor();
        RawTransaction raw = RawTransaction.parse(cbor, Transaction.deserialize(cbor));
        Map<Outpoint, UtxoEntry> resolved = new HashMap<>();
        for (TxInRef in : raw.allInputs()) {
            if (MutationWorld.view().utxo(in.outpoint()) instanceof Lookup.Present<UtxoEntry> present) {
                resolved.put(present.value().outpoint(), present.value());
            }
        }
        return new ConwayTxInfoTranslator(raw, resolved, protocolMajor, MutationWorld.env().slotConfig());
    }
}
