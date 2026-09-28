package org.yanoproject.ledger.conformance.mutation;

import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
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

    List<TransactionInput> inputs = new ArrayList<>();
    List<TransactionInput> collateral = new ArrayList<>();
    /** Outputs before the change output. */
    List<TransactionOutput> outputs = new ArrayList<>();
    String changeAddress;
    Long ttl;
    NetworkId bodyNetworkId;
    /** Added to the minimum fee: {@code -1} makes the fee one lovelace too small. */
    BigInteger feeAdjust = BigInteger.ZERO;
    /** Added to the change output after balancing: {@code +1} breaks value conservation. */
    BigInteger changeAdjust = BigInteger.ZERO;
    Metadata metadata;
    AuxDataHash auxDataHash = AuxDataHash.MATCHING;
    /** Keep the body's auxiliary-data hash but leave the auxiliary data out. */
    boolean dropAuxData;
    List<PlutusV3Script> plutusScripts = new ArrayList<>();
    List<Redeemer> redeemers = new ArrayList<>();
    List<NativeScript> nativeScripts = new ArrayList<>();
    List<TestKey> signers = new ArrayList<>();
    /** Flip a bit of the first vkey witness's signature after signing. */
    boolean corruptFirstSignature;

    public TxSpec copy() {
        TxSpec copy = new TxSpec();
        copy.inputs = new ArrayList<>(inputs);
        copy.collateral = new ArrayList<>(collateral);
        copy.outputs = new ArrayList<>(outputs);
        copy.changeAddress = changeAddress;
        copy.ttl = ttl;
        copy.bodyNetworkId = bodyNetworkId;
        copy.feeAdjust = feeAdjust;
        copy.changeAdjust = changeAdjust;
        copy.metadata = metadata;
        copy.auxDataHash = auxDataHash;
        copy.dropAuxData = dropAuxData;
        copy.plutusScripts = new ArrayList<>(plutusScripts);
        copy.redeemers = new ArrayList<>(redeemers);
        copy.nativeScripts = new ArrayList<>(nativeScripts);
        copy.signers = new ArrayList<>(signers);
        copy.corruptFirstSignature = corruptFirstSignature;
        return copy;
    }
}
