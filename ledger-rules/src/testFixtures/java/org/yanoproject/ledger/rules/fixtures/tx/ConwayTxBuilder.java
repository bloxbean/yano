package org.yanoproject.ledger.rules.fixtures.tx;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.plutus.spec.CostMdls;
import com.bloxbean.cardano.client.plutus.spec.CostModel;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Language;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.util.ScriptDataHashGenerator;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.spec.AuxiliaryData;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds signed Conway transactions from a {@link TxSpec} against a {@link LedgerView}.
 *
 * <ul>
 *   <li><b>Balance</b>: the change output (last) receives the value of the spending inputs the view knows, minus
 *       the other outputs and the fee, plus {@link TxSpec#changeAdjust}. Only lovelace.</li>
 *   <li><b>Fee</b>: the exact minimum for the final bytes, {@code minFeeA · size + minFeeB} (the size without the
 *       {@code is_valid} byte, as Haskell's {@code toCBORForSizeComputation}) plus the script fee
 *       {@code ⌈priceMem · mem + priceSteps · steps⌉} (Haskell {@code getMinFeeTxUtxo}; no reference scripts), plus
 *       {@link TxSpec#feeAdjust}. The size depends on the fee's encoding, so the fee is iterated to a fixed
 *       point.</li>
 *   <li><b>Hashes</b>: the auxiliary-data hash of the attached metadata and the script-data hash of the redeemers
 *       with the PlutusV3 language view of the view's cost model.</li>
 *   <li><b>Witnesses</b>: every signer signs the body exactly as it is encoded in the final bytes (after the edits
 *       CCL's serialiser would undo, such as a removed auxiliary-data hash), so a mutant is signed again after its
 *       edit and its signatures stay valid.</li>
 * </ul>
 */
public final class ConwayTxBuilder {

    private static final int MAX_ITERATIONS = 10;
    private static final UnsignedInteger BODY_AUX_DATA_HASH = new UnsignedInteger(7);
    private static final UnsignedInteger WITNESS_VKEYS = new UnsignedInteger(0);

    private ConwayTxBuilder() {
    }

    public static BuiltTx build(TxSpec spec, LedgerView view) {
        ProtocolParams params = view.protocolParams().require("protocol parameters");
        BigInteger available = BigInteger.ZERO;
        List<MultiAsset> availableAssets = new ArrayList<>();
        for (TransactionInput input : spec.inputs) {
            Outpoint outpoint = Outpoints.of(input.getTransactionId(), input.getIndex());
            if (view.utxo(outpoint) instanceof Lookup.Present<UtxoEntry> present) {
                Value value = present.value().output().getValue();
                available = available.add(value.getCoin());
                if (value.getMultiAssets() != null) {
                    availableAssets = MultiAsset.mergeMultiAssetLists(availableAssets, value.getMultiAssets());
                }
            }
        }
        BigInteger spent = spec.outputs.stream().map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger scriptFee = scriptFee(spec.redeemers, params);

        BigInteger fee = BigInteger.ZERO;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            BigInteger change = available.subtract(spent).subtract(fee).add(spec.changeAdjust);
            BuiltTx built = assemble(spec, params, fee, change, availableAssets, scriptFee);
            BigInteger next = built.minFee().add(spec.feeAdjust);
            if (next.equals(fee)) {
                return built;
            }
            fee = next;
        }
        throw new IllegalStateException("the fee did not converge in " + MAX_ITERATIONS + " iterations");
    }

    private static BuiltTx assemble(TxSpec spec, ProtocolParams params, BigInteger fee, BigInteger change,
                                    List<MultiAsset> changeAssets, BigInteger scriptFee) {
        try {
            List<TransactionOutput> outputs = new ArrayList<>(spec.outputs);
            TransactionOutput changeOutput = MutationWorld.output(spec.changeAddress, change);
            if (!changeAssets.isEmpty()) {
                changeOutput.getValue().setMultiAssets(new ArrayList<>(changeAssets));
            }
            outputs.add(changeOutput);

            AuxiliaryData auxData = null;
            byte[] auxDataHash = null;
            if (spec.metadata != null) {
                auxData = AuxiliaryData.builder().metadata(spec.metadata).build();
                auxDataHash = spec.auxDataHash == TxSpec.AuxDataHash.WRONG
                        ? AuxiliaryData.builder().metadata(new CBORMetadata().put(BigInteger.ONE, "other")).build()
                                .getAuxiliaryDataHash()
                        : auxData.getAuxiliaryDataHash();
            }

            TransactionBody.TransactionBodyBuilder body = TransactionBody.builder()
                    .inputs(new ArrayList<>(spec.inputs))
                    .outputs(outputs)
                    .fee(fee)
                    .auxiliaryDataHash(auxDataHash);
            if (spec.ttl != null) {
                body.ttl(spec.ttl);
            }
            if (spec.validityStart != null) {
                body.validityStartInterval(spec.validityStart);
            }
            if (spec.totalCollateral != null) {
                body.totalCollateral(spec.totalCollateral);
            }
            if (!spec.referenceInputs.isEmpty()) {
                body.referenceInputs(new ArrayList<>(spec.referenceInputs));
            }
            if (!spec.certs.isEmpty()) {
                body.certs(new ArrayList<>(spec.certs));
            }
            if (!spec.withdrawals.isEmpty()) {
                body.withdrawals(new ArrayList<>(spec.withdrawals));
            }
            if (!spec.proposals.isEmpty()) {
                body.proposalProcedures(new ArrayList<>(spec.proposals));
            }
            if (spec.donation != null) {
                body.donation(spec.donation);
            }
            if (spec.bodyNetworkId != null) {
                body.networkId(spec.bodyNetworkId);
            }
            if (!spec.collateral.isEmpty()) {
                body.collateral(new ArrayList<>(spec.collateral));
            }
            if (!spec.redeemers.isEmpty()) {
                body.scriptDataHash(ScriptDataHashGenerator.generate(Era.Conway, spec.redeemers, List.of(),
                        plutusV3CostModel(params)));
            }

            TransactionWitnessSet witnesses = new TransactionWitnessSet();
            if (!spec.plutusScripts.isEmpty()) {
                witnesses.setPlutusV3Scripts(new ArrayList<>(spec.plutusScripts));
            }
            if (!spec.redeemers.isEmpty()) {
                witnesses.setRedeemers(new ArrayList<>(spec.redeemers));
            }
            if (!spec.nativeScripts.isEmpty()) {
                witnesses.setNativeScripts(new ArrayList<>(spec.nativeScripts));
            }

            Transaction unsigned = Transaction.builder()
                    .era(Era.Conway)
                    .body(body.build())
                    .witnessSet(witnesses)
                    .auxiliaryData(auxData)
                    .isValid(spec.isValid)
                    .build();
            byte[] cbor = sign(unsigned.serialize(), spec);
            // Haskell sizes a transaction without its is_valid flag (Alonzo toCBORForSizeComputation): one byte less.
            long size = cbor.length - 1L;
            BigInteger minFee = BigInteger.valueOf(params.getMinFeeA() * size + params.getMinFeeB()).add(scriptFee);
            return new BuiltTx(cbor, Transaction.deserialize(cbor), minFee);
        } catch (Exception e) {
            throw new IllegalStateException("cannot build transaction: " + e.getMessage(), e);
        }
    }

    /**
     * Applies the edits that must bypass CCL's serialiser (it fills in a missing auxiliary-data hash), then adds a
     * vkey witness per signer over the body exactly as it is encoded in the returned bytes.
     */
    private static byte[] sign(byte[] unsignedCbor, TxSpec spec) throws Exception {
        Array tx = (Array) CborSerializationUtil.deserialize(unsignedCbor);
        Map body = (Map) tx.getDataItems().get(0);
        if (spec.metadata != null && spec.auxDataHash == TxSpec.AuxDataHash.OMITTED) {
            body.remove(BODY_AUX_DATA_HASH);
        }
        if (spec.dropAuxData) {
            tx.getDataItems().set(3, SimpleValue.NULL);
        }
        byte[] bodyHash = Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(body));
        SigningProvider signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider();
        Array vkeyWitnesses = new Array();
        boolean corrupt = spec.corruptFirstSignature;
        for (TestKey key : spec.signers) {
            byte[] signature = signingProvider.sign(bodyHash, key.secretKey().getBytes());
            if (corrupt) {
                signature[signature.length - 1] ^= 0x01;
                corrupt = false;
            }
            Array witness = new Array();
            witness.add(new ByteString(key.verificationKey()));
            witness.add(new ByteString(signature));
            vkeyWitnesses.add(witness);
        }
        if (!spec.signers.isEmpty()) {
            ((Map) tx.getDataItems().get(1)).put(WITNESS_VKEYS, vkeyWitnesses);
        }
        return CborSerializationUtil.serialize(tx);
    }

    private static CostMdls plutusV3CostModel(ProtocolParams params) {
        List<Long> values = params.getCostModelsRaw().get("PlutusV3");
        CostMdls costMdls = new CostMdls();
        costMdls.add(new CostModel(Language.PLUTUS_V3, values.stream().mapToLong(Long::longValue).toArray()));
        return costMdls;
    }

    /** Haskell {@code txscriptfee}: {@code ⌈priceMem · mem + priceSteps · steps⌉}. */
    public static BigInteger scriptFee(List<Redeemer> redeemers, ProtocolParams params) {
        BigInteger mem = BigInteger.ZERO;
        BigInteger steps = BigInteger.ZERO;
        for (Redeemer redeemer : redeemers) {
            ExUnits exUnits = redeemer.getExUnits();
            mem = mem.add(exUnits.getMem());
            steps = steps.add(exUnits.getSteps());
        }
        if (mem.signum() == 0 && steps.signum() == 0) {
            return BigInteger.ZERO;
        }
        BigDecimal cost = params.getPriceMem().multiply(new BigDecimal(mem))
                .add(params.getPriceStep().multiply(new BigDecimal(steps)));
        return cost.setScale(0, RoundingMode.CEILING).toBigIntegerExact();
    }
}
