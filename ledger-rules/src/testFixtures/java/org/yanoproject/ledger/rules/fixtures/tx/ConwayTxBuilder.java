package org.yanoproject.ledger.rules.fixtures.tx;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
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
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.spec.AuxiliaryData;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.io.ByteArrayOutputStream;
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
 *   <li><b>Hashes</b>: the auxiliary-data hash of the attached auxiliary data, and the script integrity hash as
 *       Haskell's {@code mkScriptIntegrity} computes it from the final witness set bytes: the redeemers (or
 *       {@code a0}), the datums, and the language views (CCL's encoding) of the languages of the Plutus witness
 *       scripts (in this world every provided script is needed); none without redeemers, datums and Plutus
 *       scripts.</li>
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
            if (spec.metadata != null || !spec.auxPlutusScripts.isEmpty()) {
                AuxiliaryData.AuxiliaryDataBuilder aux = AuxiliaryData.builder().metadata(spec.metadata);
                if (!spec.auxPlutusScripts.isEmpty()) {
                    aux.plutusV3Scripts(new ArrayList<>(spec.auxPlutusScripts));
                }
                auxData = aux.build();
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
            if (spec.votingProcedures != null) {
                body.votingProcedures(spec.votingProcedures);
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
            if (!spec.requiredSigners.isEmpty()) {
                body.requiredSigners(spec.requiredSigners.stream().map(HexUtil::decodeHexString).toList());
            }
            TransactionWitnessSet witnesses = new TransactionWitnessSet();
            if (!spec.plutusScripts.isEmpty()) {
                witnesses.setPlutusV3Scripts(new ArrayList<>(spec.plutusScripts));
            }
            if (!spec.plutusV2Scripts.isEmpty()) {
                witnesses.setPlutusV2Scripts(new ArrayList<>(spec.plutusV2Scripts));
            }
            if (!spec.datums.isEmpty()) {
                witnesses.setPlutusDataList(new ArrayList<>(spec.datums));
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
            byte[] integrity = scriptIntegrityHash(unsigned.serialize(), spec, params);
            if (integrity != null) {
                if (spec.corruptScriptDataHash) {
                    integrity[0] ^= 0x01;
                }
                unsigned.getBody().setScriptDataHash(integrity);
            }
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
        if (!spec.bootstrapSigners.isEmpty()) {
            Array bootstrap = new Array();
            for (int i = 0; i < spec.bootstrapSigners.size(); i++) {
                TestKey key = spec.bootstrapSigners.get(i);
                byte[] signature = signingProvider.sign(bodyHash, key.secretKey().getBytes());
                if (i == spec.corruptBootstrapSignature) {
                    signature[signature.length - 1] ^= 0x01;
                }
                Array witness = new Array();
                witness.add(new ByteString(key.verificationKey()));
                witness.add(new ByteString(signature));
                witness.add(new ByteString(MutationWorld.BOOTSTRAP_CHAIN_CODE));
                witness.add(new ByteString(MutationWorld.BOOTSTRAP_ATTRIBUTES));
                bootstrap.add(witness);
            }
            ((Map) tx.getDataItems().get(1)).put(new UnsignedInteger(2), bootstrap);
        }
        return CborSerializationUtil.serialize(tx);
    }

    /**
     * Haskell {@code mkScriptIntegrity} over the witness set as serialised: the redeemers' bytes (or {@code a0}),
     * the datums' bytes, and the language views of the Plutus witness scripts' languages.
     *
     * @return the hash, or null when there are no redeemers, datums or Plutus scripts
     */
    private static byte[] scriptIntegrityHash(byte[] unsignedCbor, TxSpec spec, ProtocolParams params)
            throws Exception {
        CostMdls costMdls = new CostMdls();
        if (!spec.plutusV2Scripts.isEmpty()) {
            costMdls.add(costModel(params, "PlutusV2", Language.PLUTUS_V2));
        }
        if (!spec.plutusScripts.isEmpty()) {
            costMdls.add(costModel(params, "PlutusV3", Language.PLUTUS_V3));
        }
        if (spec.redeemers.isEmpty() && spec.datums.isEmpty() && costMdls.isEmpty()) {
            return null;
        }
        Array tx = (Array) CborSerializationUtil.deserialize(unsignedCbor);
        Map witnessSet = (Map) tx.getDataItems().get(1);
        DataItem redeemers = witnessSet.get(new UnsignedInteger(5));
        DataItem datums = witnessSet.get(new UnsignedInteger(4));
        ByteArrayOutputStream preimage = new ByteArrayOutputStream();
        preimage.writeBytes(redeemers != null ? CborSerializationUtil.serialize(redeemers) : new byte[]{(byte) 0xa0});
        if (datums != null) {
            preimage.writeBytes(CborSerializationUtil.serialize(datums));
        }
        preimage.writeBytes(costMdls.getLanguageViewEncoding());
        return Blake2bUtil.blake2bHash256(preimage.toByteArray());
    }

    private static CostModel costModel(ProtocolParams params, String name, Language language) {
        List<Long> values = params.getCostModelsRaw().get(name);
        return new CostModel(language, values.stream().mapToLong(Long::longValue).toArray());
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
