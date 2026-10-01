package org.yanoproject.ledger.rules.conway;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.TxValidationRequest.Origin;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Decides between full validation and re-application (ADR-056 §6, "Re-application and invalidation").
 *
 * <p>A transaction is re-applied (static checks and Plutus skipped, as Haskell's {@code reapplyTx}) only when
 * {@code previous} is this transaction's verdict and none of the following changed since it was produced:</p>
 * <ul>
 *   <li>the protocol major version ({@code reapplyValidatedTx}'s guard, Shelley/API/Mempool.hs:420-439);</li>
 *   <li>the phase-2 environment digest (cost models, prices, ExUnits limits, allowed languages);</li>
 *   <li>what the spending, collateral and reference inputs resolve to ({@link #resolvedInputsDigest});</li>
 *   <li>the origin rule: a {@code SYNC} verdict is re-used only for {@code SYNC}, never for admission or block
 *       building; {@code LOCAL}, {@code PEER} and {@code BLOCK_BUILD} verdicts are re-used by each other.</li>
 * </ul>
 * <p>It also requires the same {@code is_valid} flag, and a {@code previous} without a resolved-inputs digest
 * (produced by an engine that does not record one) is never re-applied.</p>
 */
public final class ReapplyPolicy {

    /**
     * @param reapply true to re-apply
     * @param reason  why the transaction is validated in full (empty when re-applied)
     */
    public record Decision(boolean reapply, String reason) {
        static final Decision REAPPLY = new Decision(true, "");

        static Decision full(String reason) {
            return new Decision(false, reason);
        }
    }

    private ReapplyPolicy() {
    }

    /**
     * @param previous       the transaction's earlier verdict, or null
     * @param txId           this transaction's id
     * @param isValid        this transaction's {@code is_valid} flag
     * @param protocolMajor  the protocol major version of the state being validated against
     * @param env            the validation environment
     * @param origin         the request's origin
     * @param resolvedDigest {@link #resolvedInputsDigest} of the request's state, or null when it could not be
     *                       computed
     */
    public static Decision decide(ValidatedTx previous, byte[] txId, boolean isValid, int protocolMajor,
                                  ValidationEnv env, Origin origin, byte[] resolvedDigest) {
        if (previous == null) {
            return Decision.full("never validated");
        }
        if (!Arrays.equals(previous.txId(), txId)) {
            return Decision.full("the previous verdict is for another transaction");
        }
        if (previous.phase2Valid() != isValid) {
            return Decision.full("the previous verdict is for the other is_valid flag");
        }
        if (previous.validatedProtocolMajor() != protocolMajor) {
            return Decision.full("protocol major version changed from " + previous.validatedProtocolMajor()
                    + " to " + protocolMajor);
        }
        if (!Arrays.equals(previous.validatedPhase2EnvDigest(), env.phase2EnvDigest())) {
            return Decision.full("the phase-2 environment changed");
        }
        byte[] previousDigest = previous.resolvedInputsDigest();
        if (previousDigest == null || resolvedDigest == null) {
            return Decision.full("resolved inputs not recorded");
        }
        if (!Arrays.equals(previousDigest, resolvedDigest)) {
            return Decision.full("an input resolves to a different output");
        }
        if (previous.origin() == Origin.SYNC && origin != Origin.SYNC) {
            return Decision.full("a SYNC verdict is not re-used for " + origin);
        }
        return Decision.REAPPLY;
    }

    /**
     * A blake2b-256 digest of what every input resolves to: for each spending, collateral and reference input in
     * {@code TxIn} order, the input, whether it is in the UTxO, and the output's encoding (with its stored
     * inline-datum bytes when the view has them).
     *
     * @return the digest, or null when an output cannot be encoded
     */
    public static byte[] resolvedInputsDigest(SortedSet<TxInRef> inputs, Map<TxInRef, UtxoEntry> resolved) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (TxInRef in : inputs) {
                out.writeBytes((in + (resolved.containsKey(in) ? "+" : "-")).getBytes(StandardCharsets.UTF_8));
                UtxoEntry entry = resolved.get(in);
                if (entry != null) {
                    byte[] output = CborSerializationUtil.serialize(entry.output().serialize());
                    out.writeBytes(Integer.toString(output.length).getBytes(StandardCharsets.UTF_8));
                    out.writeBytes(output);
                    byte[] datum = entry.inlineDatumCbor();
                    if (datum != null) {
                        out.writeBytes(("d" + datum.length).getBytes(StandardCharsets.UTF_8));
                        out.writeBytes(datum);
                    }
                }
                out.write(';');
            }
        } catch (Exception e) {
            return null;
        }
        return Blake2bUtil.blake2bHash256(out.toByteArray());
    }

    /**
     * {@link #resolvedInputsDigest(SortedSet, Map)} for engines that resolve by outpoint (the Scalus and Amaru
     * adapters): {@code inputs} are every spending, reference and collateral input the body names, and
     * {@code resolved} what the view answered for those present.
     *
     * @return the digest, or null when an output cannot be encoded
     */
    public static byte[] resolvedInputsDigest(Collection<Outpoint> inputs, Map<Outpoint, UtxoEntry> resolved) {
        SortedSet<TxInRef> refs = new TreeSet<>();
        Map<TxInRef, UtxoEntry> byRef = new HashMap<>();
        for (Outpoint input : inputs) {
            Outpoint key = Outpoints.normalize(input);
            TxInRef ref = new TxInRef(key.txHash(), key.index());
            refs.add(ref);
            UtxoEntry entry = resolved.get(key);
            if (entry != null) {
                byRef.put(ref, entry);
            }
        }
        return resolvedInputsDigest(refs, byRef);
    }

    /** @return the spending, reference and collateral inputs of {@code body}, normalized */
    public static List<Outpoint> allInputs(TransactionBody body) {
        List<Outpoint> inputs = new ArrayList<>();
        for (List<TransactionInput> list : Arrays.asList(body.getInputs(), body.getReferenceInputs(),
                body.getCollateral())) {
            if (list != null) {
                for (TransactionInput in : list) {
                    inputs.add(Outpoints.of(in.getTransactionId(), in.getIndex()));
                }
            }
        }
        return inputs;
    }
}
