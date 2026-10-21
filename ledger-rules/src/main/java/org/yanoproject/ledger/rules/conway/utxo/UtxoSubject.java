package org.yanoproject.ledger.rules.conway.utxo;

import com.bloxbean.cardano.client.spec.NetworkId;

import org.yanoproject.ledger.rules.conway.ConwayParams;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.tx.LedgerValue;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the {@code UTXO} units read ({@code ConwayScopes.UTXO}): the transition, the typed parameters, the validation
 * slot, every output (with the collateral return), the network, and — computed once, for the collateral checks of
 * {@code feesOK} — the collateral.
 */
public final class UtxoSubject {

    /**
     * The collateral as {@code validateTotalCollateral} (Babbage/Rules/Utxo.hs:216-245) reads it.
     *
     * @param inputs     {@code collateral ◁ utxo}, the collateral inputs in the UTxO
     * @param returned   the collateral return, or null
     * @param balance    the value of {@link #inputs}
     * @param total      {@code balance} minus the collateral return's value
     * @param adaBalance {@code collAdaBalance} (Babbage/Collateral.hs:30-41): Σ collateral coin − return coin
     */
    public record Collateral(Map<TxInRef, UtxoEntry> inputs, RawOutput returned, LedgerValue balance,
                             LedgerValue total, BigInteger adaBalance) {
    }

    private final TransitionContext ctx;
    private final ConwayParams pp;
    private final long slot;
    private final List<RawOutput> allOutputs;
    private final int network;
    private Collateral collateral;

    public UtxoSubject(TransitionContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.pp = new ConwayParams(ctx.params());
        this.slot = ctx.env().currentSlot();
        this.allOutputs = ctx.raw().allOutputs();
        this.network = ctx.env().networkId() == NetworkId.MAINNET ? 1 : 0;
    }

    public TransitionContext ctx() {
        return ctx;
    }

    public RawTransaction raw() {
        return ctx.raw();
    }

    public ConwayParams pp() {
        return pp;
    }

    /** @return the slot the transaction is validated at */
    public long slot() {
        return slot;
    }

    /** @return the outputs and the collateral return */
    public List<RawOutput> allOutputs() {
        return allOutputs;
    }

    /** @return the ledger's network id, 0 (testnet) or 1 (mainnet) */
    public int network() {
        return network;
    }

    /** @return the collateral, computed on first use */
    public Collateral collateral() {
        if (collateral == null) {
            RawTransaction raw = ctx.raw();
            Map<TxInRef, UtxoEntry> inputs = new LinkedHashMap<>();
            for (TxInRef in : raw.collateralSet()) {
                ctx.utxo(in).ifPresent(entry -> inputs.put(in, entry));
            }
            RawOutput returned = raw.collateralReturn();
            LedgerValue balance = LedgerValue.ZERO;
            for (UtxoEntry entry : inputs.values()) {
                balance = balance.add(LedgerValue.of(entry.output().getValue()));
            }
            LedgerValue total = returned == null ? balance : balance.subtract(returned.value());
            BigInteger ada = returned == null ? balance.coin() : balance.coin().subtract(returned.value().coin());
            collateral = new Collateral(Collections.unmodifiableMap(inputs), returned, balance, total, ada);
        }
        return collateral;
    }
}
