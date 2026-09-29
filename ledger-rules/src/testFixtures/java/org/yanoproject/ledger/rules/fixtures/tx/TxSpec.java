package org.yanoproject.ledger.rules.fixtures.tx;

import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;
import com.bloxbean.cardano.client.transaction.spec.script.NativeScript;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * A description of a Conway transaction that {@link ConwayTxBuilder} turns into signed bytes. Mutations edit a
 * copy of a base spec; the builder then recomputes what depends on the edit (the change output, the minimum fee,
 * the auxiliary-data and script-data hashes) and signs the result again, so a mutant carries exactly the fault
 * the mutation introduced.
 */
public final class TxSpec {

    /** How the body's auxiliary-data hash relates to the auxiliary data. */
    public enum AuxDataHash {
        /** The hash of the attached auxiliary data (none when there is none). */
        MATCHING,
        /** Left out although auxiliary data is attached. */
        OMITTED,
        /** The hash of different auxiliary data. */
        WRONG
    }

    public List<TransactionInput> inputs = new ArrayList<>();
    public List<TransactionInput> collateral = new ArrayList<>();
    public List<TransactionInput> referenceInputs = new ArrayList<>();
    /** Outputs before the change output. */
    public List<TransactionOutput> outputs = new ArrayList<>();
    public String changeAddress;
    public Long ttl;
    public Long validityStart;
    public NetworkId bodyNetworkId;
    /** The body's total collateral field, or null to leave it out. */
    public BigInteger totalCollateral;
    /** The {@code is_valid} flag. */
    public boolean isValid = true;
    /** Added to the minimum fee: {@code -1} makes the fee one lovelace too small. */
    public BigInteger feeAdjust = BigInteger.ZERO;
    /** Added to the change output after balancing: {@code +1} breaks value conservation. */
    public BigInteger changeAdjust = BigInteger.ZERO;
    public Metadata metadata;
    public AuxDataHash auxDataHash = AuxDataHash.MATCHING;
    /** Keep the body's auxiliary-data hash but leave the auxiliary data out. */
    public boolean dropAuxData;
    public List<PlutusV3Script> plutusScripts = new ArrayList<>();
    public List<PlutusV2Script> plutusV2Scripts = new ArrayList<>();
    /** Datums for the witness set (key 4). */
    public List<PlutusData> datums = new ArrayList<>();
    public List<Redeemer> redeemers = new ArrayList<>();
    /** PlutusV3 scripts carried in the auxiliary data (attached with or without metadata). */
    public List<PlutusV3Script> auxPlutusScripts = new ArrayList<>();
    /** Flip a bit of the body's script integrity hash. */
    public boolean corruptScriptDataHash;
    public List<NativeScript> nativeScripts = new ArrayList<>();
    public List<TestKey> signers = new ArrayList<>();
    /** Keys that sign as bootstrap witnesses of their {@link MutationWorld#bootstrapAddress(TestKey)}. */
    public List<TestKey> bootstrapSigners = new ArrayList<>();
    /** The index in {@link #bootstrapSigners} whose signature gets one bit flipped, or -1. */
    public int corruptBootstrapSignature = -1;
    /** Required signers (body key 14), key hashes hex. */
    public List<String> requiredSigners = new ArrayList<>();
    /** Flip a bit of the first vkey witness's signature after signing. */
    public boolean corruptFirstSignature;
    public List<Certificate> certs = new ArrayList<>();
    public List<Withdrawal> withdrawals = new ArrayList<>();
    public List<ProposalProcedure> proposals = new ArrayList<>();
    /** The voting procedures, or null to leave them out. */
    public VotingProcedures votingProcedures;
    /** The treasury donation, or null to leave it out. */
    public BigInteger donation;

    public TxSpec copy() {
        TxSpec copy = new TxSpec();
        copy.inputs = new ArrayList<>(inputs);
        copy.collateral = new ArrayList<>(collateral);
        copy.referenceInputs = new ArrayList<>(referenceInputs);
        copy.outputs = new ArrayList<>(outputs);
        copy.changeAddress = changeAddress;
        copy.ttl = ttl;
        copy.validityStart = validityStart;
        copy.totalCollateral = totalCollateral;
        copy.isValid = isValid;
        copy.bodyNetworkId = bodyNetworkId;
        copy.feeAdjust = feeAdjust;
        copy.changeAdjust = changeAdjust;
        copy.metadata = metadata;
        copy.auxDataHash = auxDataHash;
        copy.dropAuxData = dropAuxData;
        copy.plutusScripts = new ArrayList<>(plutusScripts);
        copy.plutusV2Scripts = new ArrayList<>(plutusV2Scripts);
        copy.datums = new ArrayList<>(datums);
        copy.redeemers = new ArrayList<>(redeemers);
        copy.auxPlutusScripts = new ArrayList<>(auxPlutusScripts);
        copy.corruptScriptDataHash = corruptScriptDataHash;
        copy.nativeScripts = new ArrayList<>(nativeScripts);
        copy.signers = new ArrayList<>(signers);
        copy.bootstrapSigners = new ArrayList<>(bootstrapSigners);
        copy.corruptBootstrapSignature = corruptBootstrapSignature;
        copy.requiredSigners = new ArrayList<>(requiredSigners);
        copy.corruptFirstSignature = corruptFirstSignature;
        copy.certs = new ArrayList<>(certs);
        copy.withdrawals = new ArrayList<>(withdrawals);
        copy.proposals = new ArrayList<>(proposals);
        copy.votingProcedures = votingProcedures;
        copy.donation = donation;
        return copy;
    }
}
