package com.bloxbean.cardano.yano.runtime.appchain;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Built-in {@code address-deposit} observer (ADR 008.4 §3.1, ADR-005 D5
 * bridge-deposit use case): watches L1 payments to a configured address and
 * claims {@code [address, total-lovelace]} per paying transaction.
 *
 * <p>Config: {@code observers.<id>.type: address-deposit},
 * {@code observers.<id>.address: <bech32>}.
 */
final class AddressDepositObserver implements L1Observer {

    static final String TYPE = "address-deposit";
    private static final String LOVELACE = "lovelace";

    private final String observerId;
    private final String address;

    AddressDepositObserver(String observerId, Map<String, String> settings) {
        this.observerId = observerId;
        String addressValue = settings.get("address");
        if (addressValue == null || addressValue.isBlank())
            throw new IllegalArgumentException("observers." + observerId
                    + ".address is required for the address-deposit observer");
        this.address = addressValue.trim();
    }

    @Override
    public String observerId() {
        return observerId;
    }

    @Override
    public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
        if (block == null || block.getTransactionBodies() == null)
            return List.of();
        List<L1Observation> observations = new ArrayList<>();
        for (TransactionBody tx : block.getTransactionBodies()) {
            BigInteger deposited = depositedLovelace(tx);
            if (deposited.signum() <= 0)
                continue;
            observations.add(new L1Observation(observerId,
                    HexUtil.decodeHexString(tx.getTxHash()),
                    slot, blockHash, claim(deposited)));
        }
        return observations;
    }

    private BigInteger depositedLovelace(TransactionBody tx) {
        BigInteger sum = BigInteger.ZERO;
        if (tx.getOutputs() == null)
            return sum;
        for (TransactionOutput output : tx.getOutputs()) {
            if (!address.equals(output.getAddress()) || output.getAmounts() == null)
                continue;
            for (Amount amount : output.getAmounts()) {
                if (LOVELACE.equals(amount.getUnit()) && amount.getQuantity() != null) {
                    sum = sum.add(amount.getQuantity());
                }
            }
        }
        return sum;
    }

    /** claim = [address, lovelace] (l1-observation-v1.cddl). */
    private byte[] claim(BigInteger lovelace) {
        try {
            Array array = new Array();
            array.add(new UnicodeString(address));
            array.add(new UnsignedInteger(lovelace));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new CborEncoder(out).encode(array);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Deposit claim encoding failed", e);
        }
    }

    @Override
    public Map<String, Object> status() {
        return Map.of("type", TYPE, "address", address);
    }
}
